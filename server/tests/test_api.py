"""REST API against a real server process on a random port (python -m suprevolt --port 0)."""

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.error
import urllib.request

from helpers import DATA, SERVER, clean_env, read


def start(data_dir, **env_extra):
    proc = subprocess.Popen([sys.executable, "-m", "suprevolt", "--port", "0", "--data", data_dir],
                            cwd=SERVER, env=clean_env(**env_extra), stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True)
    token, port, seen = None, None, []
    deadline = time.time() + 20
    while time.time() < deadline and port is None:
        line = proc.stdout.readline()
        if not line:
            break
        seen.append(line)
        if "shown once" in line:
            proc.stdout.readline()
            token = proc.stdout.readline().strip()
        m = re.search(r"listening on http://[\d.]+:(\d+)", line)
        if m:
            port = int(m.group(1))
    if port is None:
        proc.kill()
        raise RuntimeError("server did not start: " + "".join(seen))
    return proc, token, port


class Api:
    def __init__(self, port, token):
        self.base, self.token = f"http://127.0.0.1:{port}", token

    def call(self, method, path, body=None, headers=None, token=True):
        h = dict(headers or {})
        if token is True and self.token:
            h["Authorization"] = f"Bearer {self.token}"
        elif isinstance(token, str):
            h["Authorization"] = f"Bearer {token}"
        if isinstance(body, dict):
            body = json.dumps(body).encode()
            h.setdefault("Content-Type", "application/json")
        req = urllib.request.Request(self.base + path, data=body, method=method, headers=h)
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return r.status, json.loads(r.read() or b"{}"), dict(r.headers)
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read() or b"{}"), dict(e.headers)


class ApiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dir = tempfile.mkdtemp(prefix="suprevolt-test-")
        cls.proc, cls.token, port = start(cls.dir, SUPREVOLT_RATE_PER_MIN="1000")
        cls.api = Api(port, cls.token)

    @classmethod
    def tearDownClass(cls):
        cls.proc.terminate()
        try:
            cls.proc.wait(10)
        except subprocess.TimeoutExpired:
            cls.proc.kill()
        cls.proc.stdout.close()
        shutil.rmtree(cls.dir, ignore_errors=True)

    def test_token_printed_once_and_only_its_hash_stored(self):
        self.assertTrue(self.token and len(self.token) >= 40)
        import sqlite3
        db = sqlite3.connect(os.path.join(self.dir, "suprevolt.db"))
        stored = db.execute("SELECT value FROM meta WHERE key='token_hash'").fetchone()[0]
        db.close()
        import hashlib
        self.assertEqual(stored, hashlib.sha256(self.token.encode()).hexdigest())

    def test_health_needs_no_token_and_shows_no_secrets(self):
        s, body, _ = self.api.call("GET", "/v1/health", token=False)
        self.assertEqual(s, 200)
        self.assertTrue(body["ok"])
        self.assertEqual(body["determinism"], "ok")
        self.assertNotIn(self.token, json.dumps(body))

    def test_auth_rejection(self):
        for tok in (False, "wrong-token", ""):
            s, body, h = self.api.call("GET", "/v1/catalog", token=tok)
            self.assertEqual(s, 401, tok)
        s, _, _ = self.api.call("POST", "/v1/scans", {"text": "hi"}, token="nope")
        self.assertEqual(s, 401)
        s, _, _ = self.api.call("GET", "/v1/catalog", headers={"Authorization": "Basic " + self.token}, token=None)
        self.assertEqual(s, 401)

    def test_catalog(self):
        s, body, _ = self.api.call("GET", "/v1/catalog")
        self.assertEqual(s, 200)
        ids = [c["id"] for c in body["scans"]]
        self.assertEqual(ids[0], "blob.fingerprint")
        self.assertEqual(len(ids), 12)
        by = {c["id"]: c for c in body["scans"]}
        self.assertFalse(by["fineprint.ai"]["key_present"])
        self.assertFalse(by["ever.run"]["enabled"])
        self.assertTrue(by["dna.sequence"]["deterministic"])

    def test_text_scan_wait_and_same_job_twice(self):
        req = {"text": "Your plan renews automatically each year.\r\n", "scans": ["fineprint.lexicon"], "wait": 10}
        s, a, _ = self.api.call("POST", "/v1/scans", req)
        self.assertEqual(s, 200, a)
        self.assertEqual(a["state"], "done")
        self.assertEqual(a["verdict"]["level"], "CAUTION")
        self.assertEqual(a["results"][0]["confidence"], 200)
        req["text"] = "Your plan renews automatically each year.\n"        # CRLF vs LF: same job
        s, b, _ = self.api.call("POST", "/v1/scans", req)
        self.assertEqual((s, b["job"]), (200, a["job"]))
        s, c, _ = self.api.call("GET", f"/v1/scans/{a['job']}")
        self.assertEqual((s, c["verdict"]), (200, a["verdict"]))

    def test_all_without_key_is_unknown(self):
        s, body, _ = self.api.call("POST", "/v1/scans", {"text": "Only 5 seats remain. Enrollment closes at "
                                                         "midnight tonight.", "scans": "all", "wait": 15})
        self.assertEqual(s, 200)
        self.assertEqual(body["verdict"]["level"], "UNKNOWN")
        self.assertTrue(any("no AI key" in r for r in body["verdict"]["reasons"]))
        self.assertEqual([r["scan"] for r in body["results"]][:2], ["blob.fingerprint", "refix.gradle"])

    def test_queued_then_poll(self):
        s, body, _ = self.api.call("POST", "/v1/scans", {"text": "ACGTACGTACGTACGTAAAA", "scans": "all"})
        self.assertIn(s, (200, 202))
        for _ in range(50):
            s, body, _ = self.api.call("GET", f"/v1/scans/{body['job']}")
            if body["state"] == "done":
                break
            time.sleep(0.2)
        self.assertEqual(body["state"], "done")
        self.assertEqual(body["verdict"]["level"], "CLEAR")

    def test_raw_docx_upload(self):
        data = read(os.path.join(DATA, "D01-gym-membership.docx"))
        s, body, _ = self.api.call("POST", "/v1/scans?scans=fineprint.lexicon,blob.fingerprint&wait=10", data,
                                   {"Content-Type": "application/octet-stream", "X-Filename": "../../etc/D01.docx"})
        self.assertEqual(s, 200, body)
        self.assertEqual(body["verdict"]["level"], "DANGER")
        self.assertEqual(body["results"][0]["output"]["type"],
                         "application/vnd.openxmlformats-officedocument.wordprocessingml.document")

    def test_unknown_scan_id_is_400_with_valid_ids(self):
        s, body, _ = self.api.call("POST", "/v1/scans", {"text": "x", "scans": ["nope"]})
        self.assertEqual(s, 400)
        self.assertIn("fineprint.lexicon", body["valid"])

    def test_bad_requests(self):
        self.assertEqual(self.api.call("POST", "/v1/scans", b"{not json", {"Content-Type": "application/json"})[0], 400)
        self.assertEqual(self.api.call("POST", "/v1/scans", {"scans": "all"})[0], 400)
        self.assertEqual(self.api.call("GET", "/v1/scans/zzz")[0], 404)
        self.assertEqual(self.api.call("GET", "/v1/scans/" + "0" * 24)[0], 404)

    def test_size_limits(self):
        big = b"a" * (9 * 1024 * 1024)
        s, body, _ = self.api.call("POST", "/v1/scans?scans=all", big, {"Content-Type": "text/plain"})
        self.assertEqual(s, 413)
        s, _, _ = self.api.call("POST", "/v1/scans", {"text": "a" * (257 * 1024)})
        self.assertEqual(s, 413)
        s, _, _ = self.api.call("POST", "/v1/scans", {"text": "x = 1\n" * 12000, "filename": "big.py"})
        self.assertEqual(s, 413)
        huge = b"\x89PNG\r\n\x1a\n" + b"\0\0\0\rIHDR" + (6000).to_bytes(4, "big") * 2 + b"\x08\x02\0\0\0"
        s, body, _ = self.api.call("POST", "/v1/scans", huge, {"Content-Type": "image/png"})
        self.assertEqual(s, 413)
        self.assertIn("megapixels", body["error"])

    def test_token_never_in_the_log_file(self):
        self.api.call("GET", "/v1/catalog")
        self.api.call("GET", "/v1/catalog", token="another-secret-looking-value")
        with open(os.path.join(self.dir, "suprevolt.log"), encoding="utf-8") as f:
            log = f.read()
        self.assertIn("GET /v1/catalog 200", log)
        self.assertNotIn(self.token, log)
        self.assertNotIn("another-secret-looking-value", log)
        self.assertNotIn("Authorization", log)


class RateLimitAndResetTest(unittest.TestCase):
    def test_rate_limit_and_reset_token(self):
        d = tempfile.mkdtemp(prefix="suprevolt-test-")
        proc, token, port = start(d, SUPREVOLT_RATE_PER_MIN="3")
        try:
            api = Api(port, token)
            codes = [api.call("GET", "/v1/catalog")[0] for _ in range(4)]
            self.assertEqual(codes, [200, 200, 200, 429])
        finally:
            proc.terminate()
            proc.wait(10)
            proc.stdout.close()
        # a restart keeps the token (no new banner); --reset-token prints a new one
        proc, token2, port = start(d)
        proc.terminate(); proc.wait(10); proc.stdout.close()
        self.assertIsNone(token2)
        out = subprocess.run([sys.executable, "-c",
                              "import sys; sys.argv=['x','--port','0','--data',sys.argv[1],'--reset-token'];"
                              "import signal,threading,os;threading.Timer(1.5, lambda: os.kill(os.getpid(), signal.SIGTERM)).start();"
                              "from suprevolt.__main__ import main; main(sys.argv[1:])", d],
                             cwd=SERVER, env=clean_env(), capture_output=True, text=True, timeout=30)
        self.assertIn("shown once", out.stdout)
        self.assertNotIn(token, out.stdout)
        shutil.rmtree(d, ignore_errors=True)

    def test_fixed_token_hash_for_hosts_without_a_disk(self):
        import hashlib
        d = tempfile.mkdtemp(prefix="suprevolt-test-")
        tok = "a-token-the-owner-kept-from-the-first-banner-0123456789"
        proc, printed, port = start(d, SUPREVOLT_TOKEN_HASH=hashlib.sha256(tok.encode()).hexdigest())
        try:
            self.assertIsNone(printed)   # no new token is made or shown
            self.assertEqual(Api(port, tok).call("GET", "/v1/catalog")[0], 200)
        finally:
            proc.terminate()
            proc.wait(10)
            proc.stdout.close()
            shutil.rmtree(d, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()
