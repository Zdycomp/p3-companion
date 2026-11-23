"""sqlite3 (WAL) for jobs, results and meta, plus a sha256-addressed blob directory."""

from __future__ import annotations

import json
import os
import sqlite3
import threading
import time

from .util import sha256_hex

SCHEMA = """
CREATE TABLE IF NOT EXISTS jobs(
  id TEXT PRIMARY KEY, blob TEXT NOT NULL, scans TEXT NOT NULL, state TEXT NOT NULL,
  seq INTEGER NOT NULL, created INTEGER NOT NULL, done INTEGER,
  filename TEXT NOT NULL DEFAULT '', lang TEXT NOT NULL DEFAULT '',
  results TEXT, verdict TEXT);
CREATE INDEX IF NOT EXISTS jobs_queue ON jobs(state, seq);
CREATE TABLE IF NOT EXISTS results(
  blob TEXT NOT NULL, scan_id TEXT NOT NULL, scan_version TEXT NOT NULL, cfg_hash TEXT NOT NULL,
  json TEXT NOT NULL, value INTEGER, confidence INTEGER, state TEXT NOT NULL, ms INTEGER NOT NULL,
  filename TEXT NOT NULL DEFAULT '', lang TEXT NOT NULL DEFAULT '',
  PRIMARY KEY(blob, scan_id, scan_version, cfg_hash));
CREATE TABLE IF NOT EXISTS blobs(sha TEXT PRIMARY KEY, size INTEGER NOT NULL, type TEXT NOT NULL,
  created INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL);
"""


class Store:
    def __init__(self, data_dir: str):
        self.dir = data_dir
        self.blob_dir = os.path.join(data_dir, "blobs")
        os.makedirs(self.blob_dir, exist_ok=True)
        try:
            os.chmod(data_dir, 0o700)
        except OSError:
            pass
        self._lock = threading.RLock()
        self.db = sqlite3.connect(os.path.join(data_dir, "suprevolt.db"), check_same_thread=False,
                                  isolation_level=None, timeout=30)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA synchronous=NORMAL")
        self.db.executescript(SCHEMA)

    # ---------------------------------------------------------------- meta
    def meta_get(self, key: str, default: str | None = None) -> str | None:
        with self._lock:
            row = self.db.execute("SELECT value FROM meta WHERE key=?", (key,)).fetchone()
        return row[0] if row else default

    def meta_set(self, key: str, value: str) -> None:
        with self._lock:
            self.db.execute("INSERT INTO meta(key,value) VALUES(?,?) "
                            "ON CONFLICT(key) DO UPDATE SET value=excluded.value", (key, value))

    # ---------------------------------------------------------------- blobs
    def blob_path(self, sha: str) -> str:
        return os.path.join(self.blob_dir, sha[:2], sha[2:])

    def put_blob(self, data: bytes, type_: str) -> str:
        sha = sha256_hex(data)
        path = self.blob_path(sha)
        if not os.path.exists(path):
            os.makedirs(os.path.dirname(path), exist_ok=True)
            tmp = f"{path}.{os.getpid()}.{threading.get_ident()}.tmp"
            with open(tmp, "wb") as f:
                f.write(data)
            os.replace(tmp, path)
        with self._lock:
            self.db.execute("INSERT OR IGNORE INTO blobs(sha,size,type,created) VALUES(?,?,?,?)",
                            (sha, len(data), type_, int(time.time())))
        return sha

    def get_blob(self, sha: str) -> tuple[bytes, str] | None:
        with self._lock:
            row = self.db.execute("SELECT type FROM blobs WHERE sha=?", (sha,)).fetchone()
        if not row:
            return None
        try:
            with open(self.blob_path(sha), "rb") as f:
                data = f.read()
        except FileNotFoundError:
            return None
        if sha256_hex(data) != sha:
            return None   # the disk lied; never serve a blob that is not what its name says
        return data, row[0]

    # ---------------------------------------------------------------- jobs
    def job(self, job_id: str) -> dict | None:
        with self._lock:
            cur = self.db.execute("SELECT * FROM jobs WHERE id=?", (job_id,))
            row = cur.fetchone()
            cols = [c[0] for c in cur.description]
        return dict(zip(cols, row)) if row else None

    def add_job(self, job_id: str, blob: str, scans: list[str], filename: str, lang: str) -> tuple[dict, bool]:
        """Insert a queued job, or return the existing one. (job, created)."""
        with self._lock:
            existing = self.job(job_id)
            if existing:
                return existing, False
            seq = (self.db.execute("SELECT COALESCE(MAX(seq),0) FROM jobs").fetchone()[0] or 0) + 1
            self.db.execute("INSERT INTO jobs(id,blob,scans,state,seq,created,filename,lang) VALUES(?,?,?,?,?,?,?,?)",
                            (job_id, blob, json.dumps(scans), "queued", seq, int(time.time()), filename, lang))
            return self.job(job_id), True

    def queue_depth(self) -> int:
        with self._lock:
            return self.db.execute("SELECT COUNT(*) FROM jobs WHERE state IN ('queued','running')").fetchone()[0]

    def next_job(self) -> dict | None:
        with self._lock:
            cur = self.db.execute("SELECT * FROM jobs WHERE state='queued' ORDER BY seq LIMIT 1")
            row = cur.fetchone()
            if not row:
                return None
            cols = [c[0] for c in cur.description]
            job = dict(zip(cols, row))
            self.db.execute("UPDATE jobs SET state='running' WHERE id=?", (job["id"],))
            job["state"] = "running"
            return job

    def requeue_running(self) -> None:
        """After a crash or restart, a job left 'running' goes back to the queue in its old place."""
        with self._lock:
            self.db.execute("UPDATE jobs SET state='queued' WHERE state='running'")

    def finish_job(self, job_id: str, results: list, verdict: dict) -> None:
        with self._lock:
            self.db.execute("UPDATE jobs SET state='done', done=?, results=?, verdict=? WHERE id=?",
                            (int(time.time()), json.dumps(results), json.dumps(verdict), job_id))

    # ---------------------------------------------------------------- results cache
    def cached(self, blob: str, scan_id: str, version: str, cfg: str) -> dict | None:
        with self._lock:
            row = self.db.execute("SELECT json, ms FROM results WHERE blob=? AND scan_id=? AND scan_version=? "
                                  "AND cfg_hash=?", (blob, scan_id, version, cfg)).fetchone()
        return {"json": row[0], "ms": row[1]} if row else None

    def put_result(self, blob, scan_id, version, cfg, canonical_json, value, confidence, state, ms,
                   filename="", lang="") -> None:
        with self._lock:
            self.db.execute("INSERT OR REPLACE INTO results VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                            (blob, scan_id, version, cfg, canonical_json, value, confidence, state, ms,
                             filename, lang))

    def stale_results(self, current: dict) -> list[tuple]:
        with self._lock:
            rows = self.db.execute("SELECT blob, scan_id, scan_version, filename, lang FROM results").fetchall()
        return [r for r in rows if r[1] in current and r[2] != current[r[1]]]

    def delete_result(self, blob, scan_id, version) -> None:
        with self._lock:
            self.db.execute("DELETE FROM results WHERE blob=? AND scan_id=? AND scan_version=?",
                            (blob, scan_id, version))

    def audit_sample(self, scan_ids: list[str], versions: dict, n: int = 20) -> list[tuple]:
        """The n cached deterministic results with the lowest blob sha256 (fixed, never random)."""
        if not scan_ids:
            return []
        marks = ",".join("?" * len(scan_ids))
        with self._lock:
            rows = self.db.execute(
                f"SELECT blob, scan_id, scan_version, cfg_hash, json, filename, lang FROM results "
                f"WHERE scan_id IN ({marks}) ORDER BY blob, scan_id, cfg_hash", scan_ids).fetchall()
        return [r for r in rows if versions.get(r[1]) == r[2]][:n]
