"""The deterministic job loop: one worker thread, oldest job first, scans in fixed catalog order.

Each tick: take the oldest queued job (ORDER BY seq), expand "all" to the catalog in catalog order,
for each scan either `n/a` (does not accept the input), a cached result (same blob, scan version and
config), or a fresh run under a wall-clock limit. image.read's text becomes a new blob and the text
scans run on it as child results. Then the verdict, `done`, and sleep 0.25 s or wake on a new POST.

Once a day at a fixed UTC time the sweep re-runs stale cached results (old scan versions) and audits
determinism: the 20 cached deterministic results with the lowest blob sha256 are re-run and compared
byte for byte. A mismatch marks that scan broken in /v1/health and its cache is not served until the
scan's version changes.
"""

from __future__ import annotations

import datetime as _dt
import json
import threading
import time

from . import scans as S
from .scans import BY_ID, CATALOG_VERSION, TEXT_SCANS, VERSIONS, Item, Result, error
from .store import Store
from .util import canon, normalize_text_bytes, sha256_hex, sniff
from .verdict import verdict


def job_id_for(blob: str, scan_ids: list[str], filename_ext: str, lang: str) -> str:
    key = blob + "\0" + ",".join(sorted(scan_ids)) + "\0" + CATALOG_VERSION + "\0" + filename_ext + "\0" + lang
    return sha256_hex(key.encode())[:24]


def prepare(data: bytes) -> tuple[bytes, str]:
    """Sniff the type from the bytes, then normalize text (CRLF to LF) before it is hashed."""
    kind = sniff(data)
    if kind == "text/plain":
        data = normalize_text_bytes(data)
    return data, kind


class Engine:
    def __init__(self, cfg, store: Store | None = None, log=None):
        self.cfg = cfg
        self.store = store or Store(cfg.data_dir)
        self.log = log or (lambda msg: None)
        self.cond = threading.Condition()
        self.wake = threading.Event()
        self.stop = threading.Event()
        self.thread: threading.Thread | None = None
        self.store.requeue_running()

    # ---------------------------------------------------------------- submit
    def submit(self, data: bytes, kind: str, scan_ids: list[str], filename: str = "", lang: str = "") -> dict:
        from .util import extension
        sha = self.store.put_blob(data, kind)
        jid = job_id_for(sha, scan_ids, extension(filename), lang)
        job, created = self.store.add_job(jid, sha, scan_ids, filename, lang)
        if created:
            self.wake.set()
        return job

    def wait(self, job_id: str, seconds: float) -> dict | None:
        deadline = time.monotonic() + max(0.0, seconds)
        with self.cond:
            while True:
                job = self.store.job(job_id)
                left = deadline - time.monotonic()
                if job is None or job["state"] == "done" or left <= 0:
                    return job
                self.cond.wait(min(left, 0.5))

    # ---------------------------------------------------------------- running
    def start(self) -> None:
        self.thread = threading.Thread(target=self._worker, name="suprevolt-loop", daemon=True)
        self.thread.start()

    def shutdown(self) -> None:
        self.stop.set()
        self.wake.set()
        if self.thread:
            self.thread.join(timeout=10)

    def _worker(self) -> None:
        while not self.stop.is_set():
            try:
                if self._sweep_due():
                    self.sweep()
                job = self.store.next_job()
                if job:
                    self.run_job(job)
                    continue
            except Exception as exc:  # the loop never dies; the job is retried after restart
                self.log(f"loop error: {type(exc).__name__}: {exc}")
                time.sleep(1)
            self.wake.wait(0.25)
            self.wake.clear()

    def run_job(self, job: dict) -> list[dict]:
        ids = S.order(json.loads(job["scans"]))
        got = self.store.get_blob(job["blob"])
        if got is None:
            results = [self._entry(BY_ID[i], error("the input blob is missing on the server"), job["blob"], "", 0, False)
                       for i in ids]
        else:
            data, kind = got
            item = Item(data, job["blob"], kind, job["filename"], job["lang"])
            results = []
            for sid in ids:
                entry, res = self.run_one(BY_ID[sid], item)
                results.append(entry)
                item.prior[sid] = res
                if sid == "image.read" and res.child_text:
                    child, ckind = prepare(res.child_text.encode("utf-8"))
                    csha = self.store.put_blob(child, ckind)
                    citem = Item(child, csha, ckind, "", "", parent=item.sha)
                    for tid in S.order([t for t in TEXT_SCANS if t in ids]):
                        centry, cres = self.run_one(BY_ID[tid], citem)
                        results.append(centry)
                        citem.prior[tid] = cres
        v = verdict(results)
        self.store.finish_job(job["id"], results, v)
        with self.cond:
            self.cond.notify_all()
        return results

    def _broken(self) -> dict:
        raw = json.loads(self.store.meta_get("determinism_broken", "{}"))
        return {k: v for k, v in raw.items() if VERSIONS.get(k) == v}   # a new version is a fix

    def run_one(self, mod, item: Item) -> tuple[dict, Result]:
        try:
            ok = mod.accepts(item, self.cfg)
        except Exception as exc:
            res = error(f"accepts() crashed: {type(exc).__name__}")
            return self._entry(mod, res, item.sha, item.parent, 0, False), res
        if not ok:
            res = Result("n/a", note="not this kind of input")
            return self._entry(mod, res, item.sha, item.parent, 0, False), res
        version = VERSIONS[mod.ID]
        ch = S.cfg_hash(mod, item, self.cfg)
        if mod.ID not in self._broken():
            hit = self.store.cached(item.sha, mod.ID, version, ch)
            if hit:
                body = json.loads(hit["json"])
                res = Result(body["state"], body["value"], body["confidence"], body["output"], body["note"])
                if mod.ID == "image.read" and res.state == "ok":
                    res.child_text = (res.output or {}).get("text") or None
                return self._entry(mod, res, item.sha, item.parent, hit["ms"], True), res
        res, ms = self.execute(mod, item)
        if not res.transient and res.state in ("ok", "unknown"):
            self.store.put_result(item.sha, mod.ID, version, ch, res.canonical(), res.value, res.confidence,
                                  res.state, ms, item.filename, item.lang)
        return self._entry(mod, res, item.sha, item.parent, ms, False), res

    def execute(self, mod, item: Item) -> tuple[Result, int]:
        """Run one scan under its wall-clock limit (offline 5 s, AI 60 s)."""
        if mod.KIND == "ai":
            limit = self.cfg.ai_timeout + 5
        elif mod.KIND == "subprocess":
            limit = self.cfg.ever_run_timeout + 5
        else:
            limit = self.cfg.offline_timeout
        box: dict = {}

        def target():
            try:
                box["r"] = mod.run(item, self.cfg)
            except Exception as exc:
                box["r"] = error(f"crashed: {type(exc).__name__}: {str(exc)[:200]}")

        t0 = time.monotonic()
        th = threading.Thread(target=target, name=f"scan-{mod.ID}", daemon=True)
        th.start()
        th.join(limit)
        ms = int((time.monotonic() - t0) * 1000)
        if th.is_alive():
            return error(f"stopped after {limit:g} s (time limit)"), ms
        return box["r"], ms

    @staticmethod
    def _entry(mod, res: Result, blob: str, parent: str, ms: int, cached: bool) -> dict:
        return {"scan": mod.ID, "title": mod.TITLE, "version": VERSIONS[mod.ID], "state": res.state,
                "value": res.value, "confidence": res.confidence, "deterministic": mod.DETERMINISTIC,
                "cached": cached, "ms": ms, "note": res.note, "output": res.output,
                "blob": blob, "parent": parent or None}

    # ---------------------------------------------------------------- views
    def job_view(self, job: dict) -> dict:
        out = {"job": job["id"], "state": job["state"], "blob": job["blob"],
               "scans": S.order(json.loads(job["scans"])), "catalog_version": CATALOG_VERSION,
               "verdict": None, "results": []}
        if job["state"] == "done":
            out["verdict"] = json.loads(job["verdict"])
            out["results"] = json.loads(job["results"])
        else:
            out["poll"] = f"/v1/scans/{job['id']}"
        return out

    def health(self) -> dict:
        from . import VERSION
        broken = sorted(self._broken())
        return {"ok": True, "version": VERSION, "catalog_version": CATALOG_VERSION,
                "queue_depth": self.store.queue_depth(),
                "determinism": "broken" if broken else "ok", "broken_scans": broken,
                "last_sweep": self.store.meta_get("last_sweep", None)}

    def catalog(self) -> list[dict]:
        out = []
        for m in S.CATALOG:
            entry = {"id": m.ID, "title": m.TITLE, "version": VERSIONS[m.ID], "accepts": m.ACCEPTS,
                     "kind": m.KIND, "deterministic": m.DETERMINISTIC, "needs_key": m.NEEDS_KEY,
                     "key_present": (not m.NEEDS_KEY) or bool(self.cfg.ai.chosen()), "enabled": True}
            if m.ID == "ever.run":
                entry["enabled"] = bool(self.cfg.enable_ever_run)
            if m.ID == "image.read" and not self.cfg.ai.chosen():
                import shutil
                entry["key_present"] = bool(shutil.which("tesseract"))
            out.append(entry)
        return out

    # ---------------------------------------------------------------- sweep
    def _sweep_due(self, now: _dt.datetime | None = None) -> bool:
        now = now or _dt.datetime.now(_dt.timezone.utc)
        h, m = self.cfg.sweep_utc
        if (now.hour, now.minute) < (h, m):
            return False
        return self.store.meta_get("last_sweep_day", "") != now.date().isoformat()

    def sweep(self) -> dict:
        now = _dt.datetime.now(_dt.timezone.utc)
        rerun = 0
        for blob, sid, old_version, filename, lang in self.store.stale_results(VERSIONS):
            got = self.store.get_blob(blob)
            mod = BY_ID[sid]
            if got and mod.DETERMINISTIC:
                item = Item(got[0], blob, got[1], filename, lang)
                if mod.accepts(item, self.cfg):
                    self.run_one(mod, item)
                    rerun += 1
            self.store.delete_result(blob, sid, old_version)
        det = [m.ID for m in S.CATALOG if m.DETERMINISTIC]
        broken = json.loads(self.store.meta_get("determinism_broken", "{}"))
        audited = mismatched = 0
        for blob, sid, version, ch, stored, filename, lang in self.store.audit_sample(det, VERSIONS):
            got = self.store.get_blob(blob)
            if not got:
                continue
            item = Item(got[0], blob, got[1], filename, lang)
            res, _ = self.execute(BY_ID[sid], item)
            audited += 1
            if res.transient:
                continue
            if res.canonical() != stored:
                mismatched += 1
                broken[sid] = version
                self.log(f"determinism audit: {sid} gave a different answer for blob {blob[:12]}")
        self.store.meta_set("determinism_broken", canon(broken))
        self.store.meta_set("last_sweep", now.replace(microsecond=0).isoformat())
        self.store.meta_set("last_sweep_day", now.date().isoformat())
        return {"rerun": rerun, "audited": audited, "mismatched": mismatched}
