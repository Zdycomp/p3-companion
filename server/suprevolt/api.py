"""REST API on the standard library's ThreadingHTTPServer (the pattern of ClaudRick's ReVision server).

  GET  /v1/health           no auth: {ok, version, catalog_version, queue_depth, determinism}
  GET  /v1/catalog          every scan: id, version, accepts, deterministic, needs_key, key_present
  POST /v1/scans            JSON {"text", "scans", "wait", "filename", "lang"} or a raw upload
                            (body = the bytes, X-Filename, ?scans=all&wait=10)
  GET  /v1/scans/{id}       {job, state, blob, verdict, results[]}

Every route but health needs `Authorization: Bearer <token>`. The request log never prints headers
or query strings, so neither the token nor anything a client typed ends up in it.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import re
import secrets
import sys
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from . import scans as S
from .loop import Engine, prepare
from .util import DOCX, image_size

JOB_RE = re.compile(r"^/v1/scans/([0-9a-f]{24})$")


# ---------------------------------------------------------------- token

def token_hash(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def ensure_token(store, reset: bool = False, fixed_hash: str = "") -> str | None:
    """Make a token on first run (or on --reset-token). Returns it once; only its sha256 is stored.

    `fixed_hash` (SUPREVOLT_TOKEN_HASH) is for hosts whose disk is wiped on every restart (Render):
    the owner pastes the hash from the first banner, and the same token keeps working.
    """
    if re.fullmatch(r"[0-9a-f]{64}", fixed_hash or ""):
        store.meta_set("token_hash", fixed_hash)
        return None
    if store.meta_get("token_hash") and not reset:
        return None
    token = secrets.token_urlsafe(32)
    store.meta_set("token_hash", token_hash(token))
    return token


def banner(token: str) -> str:
    line = "=" * 72
    return (f"\n{line}\n SupRevolt access token (shown once, only a hash is stored):\n\n    {token}\n\n"
            f" Put it in P3 Companion > Settings > SupRevolt. Lost it? Restart with --reset-token.\n"
            f" Host that wipes its disk on restart (Render)? Also set this environment variable so the\n"
            f" same token keeps working (it is only a hash, not the token):\n"
            f"    SUPREVOLT_TOKEN_HASH={token_hash(token)}\n{line}\n")


# ---------------------------------------------------------------- server

class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, addr, engine: Engine, logf=None):
        super().__init__(addr, Handler)
        self.engine = engine
        self.cfg = engine.cfg
        self.logf = logf
        from .vendor.revision.ratelimit import RateLimiter
        self.limiter = RateLimiter(engine.cfg.rate_per_min, 60.0)
        self.fail_limiter = RateLimiter(20, 60.0)   # wrong-token attempts per client address

    def log(self, msg: str) -> None:
        line = f"{time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())} {msg}\n"
        if self.logf:
            self.logf.write(line)
            self.logf.flush()
        else:
            sys.stderr.write(line)


class HttpError(Exception):
    def __init__(self, status: int, message: str, extra: dict | None = None, headers: dict | None = None):
        super().__init__(message)
        self.status, self.message, self.extra, self.headers = status, message, extra or {}, headers or {}


class Handler(BaseHTTPRequestHandler):
    server_version = "SupRevolt"
    sys_version = ""
    protocol_version = "HTTP/1.1"

    # The default logger prints the request line (with the query); ours prints method, path, status.
    def log_message(self, fmt, *args):
        pass

    def log_request(self, code="-", size="-"):
        path = urllib.parse.urlsplit(self.path).path
        self.server.log(f"{self.command} {path} {code}")

    # ---------------------------------------------------------------- plumbing
    def _send(self, status: int, payload: dict, headers: dict | None = None) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _fail(self, e: HttpError) -> None:
        payload = {"error": e.message, **e.extra}
        if self.command == "POST":
            self.close_connection = True   # the unread body must not be parsed as the next request
            e.headers = {**e.headers, "Connection": "close"}
        self._send(e.status, payload, e.headers)

    def _auth(self) -> str:
        cfg = self.server.cfg
        h = self.headers.get("Authorization", "")
        stored = self.server.engine.store.meta_get("token_hash", "")
        token = h[7:].strip() if h[:7].lower() == "bearer " else ""
        ok = bool(token) and bool(stored) and hmac.compare_digest(token_hash(token), stored)
        if not ok:
            allowed, retry = self.server.fail_limiter.check(self.client_address[0])
            if not allowed:
                raise HttpError(429, "too many attempts", headers={"Retry-After": str(retry)})
            raise HttpError(401, "missing or wrong token", headers={"WWW-Authenticate": "Bearer"})
        allowed, retry = self.server.limiter.check(stored)
        if not allowed:
            raise HttpError(429, f"more than {cfg.rate_per_min} requests a minute", headers={"Retry-After": str(retry)})
        return stored

    def _body(self) -> bytes:
        cfg = self.server.cfg
        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
            raise HttpError(411, "send a Content-Length (chunked uploads are not accepted)")
        try:
            n = int(self.headers.get("Content-Length", ""))
        except ValueError:
            raise HttpError(411, "Content-Length required") from None
        if n < 0:
            raise HttpError(400, "bad Content-Length")
        if n > cfg.max_body:
            left = min(n, 32 * 1024 * 1024)   # drain (bounded) so the client can read the answer
            while left > 0:
                chunk = self.rfile.read(min(65536, left))
                if not chunk:
                    break
                left -= len(chunk)
            raise HttpError(413, f"body over {cfg.max_body // (1024 * 1024)} MB", {"limit": cfg.max_body})
        data = b""
        while len(data) < n:
            chunk = self.rfile.read(min(65536, n - len(data)))
            if not chunk:
                raise HttpError(400, "body ended early")
            data += chunk
        return data

    # ---------------------------------------------------------------- routes
    def do_GET(self):
        path = urllib.parse.urlsplit(self.path).path
        try:
            if path == "/v1/health":
                return self._send(200, self.server.engine.health())
            self._auth()
            if path == "/v1/catalog":
                eng = self.server.engine
                return self._send(200, {"catalog_version": S.CATALOG_VERSION, "scans": eng.catalog()})
            m = JOB_RE.match(path)
            if m:
                job = self.server.engine.store.job(m.group(1))
                if not job:
                    raise HttpError(404, "no such job")
                return self._send(200, self.server.engine.job_view(job))
            raise HttpError(404, "not found")
        except HttpError as e:
            self._fail(e)

    def do_POST(self):
        parts = urllib.parse.urlsplit(self.path)
        try:
            if parts.path != "/v1/scans":
                raise HttpError(404, "not found")
            self._auth()
            body = self._body()
            ctype = self.headers.get("Content-Type", "").split(";")[0].strip().lower()
            query = urllib.parse.parse_qs(parts.query)
            if ctype == "application/json":
                req = self._json(body)
                scans = req.get("scans", "all")
                wait = req.get("wait", 0)
                filename = req.get("filename", "") or ""
                lang = req.get("lang", "") or ""
                text = req.get("text")
                if not isinstance(text, str) or not text:
                    raise HttpError(400, "send {\"text\": \"...\"} or upload the file as the body")
                data = text.encode("utf-8")
            else:
                data = body
                scans = (query.get("scans", ["all"])[0]).split(",")
                wait = query.get("wait", ["0"])[0]
                filename = self.headers.get("X-Filename", "") or ""
                lang = query.get("lang", [""])[0]
                if not data:
                    raise HttpError(400, "empty body")
            ids = self._scan_ids(scans)
            if not isinstance(filename, str) or not isinstance(lang, str):
                raise HttpError(400, "filename and lang must be strings")
            filename = filename.replace("\\", "/").rsplit("/", 1)[-1][:120]
            lang = lang.lower()[:20]
            try:
                wait = max(0, min(self.server.cfg.max_wait, int(float(wait))))
            except (TypeError, ValueError):
                raise HttpError(400, "wait must be a number of seconds, 0 to 20") from None
            data, kind = prepare(data)
            self._limits(data, kind, filename)
            eng = self.server.engine
            if eng.store.queue_depth() >= self.server.cfg.max_queue:
                raise HttpError(429, "the queue is full, try again shortly", headers={"Retry-After": "10"})
            job = eng.submit(data, kind, ids, filename, lang)
            if wait and job["state"] != "done":
                job = eng.wait(job["id"], wait) or job
            if job["state"] == "done":
                return self._send(200, eng.job_view(job))
            return self._send(202, {"job": job["id"], "state": job["state"], "poll": f"/v1/scans/{job['id']}"})
        except HttpError as e:
            self._fail(e)

    def do_HEAD(self):
        self.do_GET()

    # ---------------------------------------------------------------- checks
    @staticmethod
    def _json(body: bytes) -> dict:
        try:
            req = json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            raise HttpError(400, "body is not valid JSON") from None
        if not isinstance(req, dict):
            raise HttpError(400, "body must be a JSON object")
        return req

    @staticmethod
    def _scan_ids(scans) -> list[str]:
        if scans == "all" or scans == ["all"]:
            return ["all"]
        if isinstance(scans, str):
            scans = scans.split(",")
        if not isinstance(scans, list) or not scans or not all(isinstance(s, str) for s in scans):
            raise HttpError(400, "scans must be \"all\" or a list of scan ids", {"valid": list(S.BY_ID)})
        scans = [s.strip() for s in scans]
        bad = sorted(set(s for s in scans if s not in S.BY_ID))
        if bad:
            raise HttpError(400, f"unknown scan id: {', '.join(bad)[:200]}", {"valid": list(S.BY_ID)})
        return sorted(set(scans))

    def _limits(self, data: bytes, kind: str, filename: str) -> None:
        cfg = self.server.cfg
        from .util import extension
        ext = extension(filename)
        if kind.startswith("image/"):
            if len(data) > cfg.max_image:
                raise HttpError(413, "image over 8 MB", {"limit": cfg.max_image})
            dims = image_size(data)
            if dims and dims[0] * dims[1] > cfg.max_pixels:
                raise HttpError(413, "image over 20 megapixels", {"limit_pixels": cfg.max_pixels})
        elif kind == "text/plain":
            code = ext in S.CODE_EXT or ext in (".ever", ".accord")
            limit = cfg.max_code if code else cfg.max_text
            if len(data) > limit:
                raise HttpError(413, f"{'code' if code else 'text'} over {limit // 1024} KB", {"limit": limit})
        elif kind == DOCX:
            pass   # bounded by the body limit; its extracted text is bounded by the scans
