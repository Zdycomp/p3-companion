"""Determinism gates, as `python -m suprevolt --selftest` and from the unit tests.

1. Golden: every file in golden/<scan>/ runs through its scan, and the canonical JSON of the result
   ({state, value, confidence, output, note}, sorted keys, no spaces) must equal <case>.expected.json
   byte for byte.
2. Twice-run: each golden input runs twice in this process and once in a fresh process with
   PYTHONHASHSEED=0 and once with =1. All output hashes must be equal.

`python -m suprevolt.selftest --case <scan>/<name>` prints one case's hash (the fresh-process half).
`python -m suprevolt.selftest --write` regenerates the expected files (only after a reviewed change).
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys

from .config import AiConfig, Config
from .loop import prepare
from .scans import BY_ID, Item
from .util import sha256_hex

GOLDEN = os.path.join(os.path.dirname(os.path.abspath(__file__)), "golden")
SERVER_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def cases() -> list[tuple[str, str]]:
    out = []
    for scan in sorted(os.listdir(GOLDEN)):
        d = os.path.join(GOLDEN, scan)
        if not os.path.isdir(d):
            continue
        for f in sorted(os.listdir(d)):
            if f.endswith(".in"):
                out.append((scan, f[:-3]))
    return out


def case_paths(scan: str, name: str) -> dict:
    base = os.path.join(GOLDEN, scan, name)
    return {"in": base + ".in", "meta": base + ".meta.json", "expected": base + ".expected.json"}


def offline_cfg(enable_ever_run: bool = False) -> Config:
    """A config with no AI keys at all, whatever the environment holds."""
    return Config(data_dir=os.devnull, enable_ever_run=enable_ever_run, ai=AiConfig())


def run_case(scan: str, name: str):
    p = case_paths(scan, name)
    meta = {}
    if os.path.exists(p["meta"]):
        with open(p["meta"], encoding="utf-8") as f:
            meta = json.load(f)
    with open(p["in"], "rb") as f:
        data, kind = prepare(f.read())
    item = Item(data, sha256_hex(data), kind, meta.get("filename", ""), meta.get("lang", ""))
    cfg = offline_cfg(bool(meta.get("enable_ever_run")))
    return BY_ID[scan].run(item, cfg)


def canonical_case(scan: str, name: str) -> str:
    return run_case(scan, name).canonical()


def fresh_hash(scan: str, name: str, seed: str) -> str:
    env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "PYTHONHASHSEED": seed,
           "PYTHONDONTWRITEBYTECODE": "1"}
    r = subprocess.run([sys.executable, "-s", "-m", "suprevolt.selftest", "--case", f"{scan}/{name}"],
                       cwd=SERVER_ROOT, env=env, capture_output=True, timeout=60)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.decode("utf-8", "replace")[-400:])
    return r.stdout.decode().strip()


def run_selftest(cfg=None, verbose: bool = True) -> int:
    failures = 0
    for scan, name in cases():
        p = case_paths(scan, name)
        got = canonical_case(scan, name)
        with open(p["expected"], encoding="utf-8") as f:
            want = f.read()
        hashes = {sha256_hex(got.encode()), sha256_hex(canonical_case(scan, name).encode()),
                  fresh_hash(scan, name, "0"), fresh_hash(scan, name, "1")}
        ok = got == want and len(hashes) == 1
        failures += not ok
        if verbose:
            print(f"{'ok  ' if ok else 'FAIL'} {scan}/{name}" + ("" if got == want else "  (golden differs)")
                  + ("" if len(hashes) == 1 else "  (not deterministic)"))
    if verbose:
        print("selftest: " + ("all gates pass" if not failures else f"{failures} failure(s)"))
    return 1 if failures else 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="suprevolt.selftest")
    ap.add_argument("--case")
    ap.add_argument("--write", action="store_true")
    a = ap.parse_args(argv)
    if a.case:
        scan, name = a.case.split("/", 1)
        print(sha256_hex(canonical_case(scan, name).encode()))
        return 0
    if a.write:
        for scan, name in cases():
            with open(case_paths(scan, name)["expected"], "w", encoding="utf-8") as f:
                f.write(canonical_case(scan, name))
            print("wrote", scan, name)
        return 0
    return run_selftest()


if __name__ == "__main__":
    sys.exit(main())
