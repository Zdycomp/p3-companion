"""#7 ever.run: runs an Ever program and reports each shown value with its own [n/256] confidence.

The only code-execution surface on the server, so it is OFF unless SUPREVOLT_ENABLE_EVER_RUN=1.
When on, it runs in a child process:
  argv  = [python, -I, ever_runner.py, <sha256>.ever]   fixed; user content only in the temp file
  shell = False, env = {PATH}, cwd = a fresh temp dir, new session (killed as a group on timeout)
  rlimits: CPU 5 s, address space 512 MB, 32 open files, 1 MB file size; 5 s wall clock
  stdout capped at 256 KB; `extern` (native libraries) refused inside the runner.
Result confidence = the min of the program's own confidences; any `z` value makes it UNKNOWN.
"""

from __future__ import annotations

import json
import os
import shutil
import signal
import subprocess
import sys
import tempfile

from . import Result, error, na, unknown
from ..util import sha256_hex

ID = "ever.run"
BASE_VERSION = "1"
TITLE = "Ever runner"
ACCEPTS = ".ever programs (off unless the server enables it)"
KIND = "subprocess"
DETERMINISTIC = True
NEEDS_KEY = False
FILES = ("ezr/interp/runtime.py", "ezr/interp/syntax.py", "ezr/interp/forgive.py", "ezr/interp/ever.py",
         "ezr/interp/ir.py", "ezr/interp/scope.py", "ezr/interp/evalue.py", "ezr/interp/ffi.py",
         "ezr/interp/builtins_ml.py", "ezr/interp/profiler.py", "ezr/interp/repair_archive.py")
EXTRA_FILES = ("ever_runner.py",)
RUNNER = os.path.join(os.path.dirname(os.path.abspath(__file__)), "ever_runner.py")
OUTPUT_CAP = 256 * 1024


def cfg_key(item, cfg) -> dict:
    return {"timeout": cfg.ever_run_timeout, "cpu": 5, "as_mb": 512}


def accepts(item, cfg) -> bool:
    return item.is_ever and len(item.data) <= cfg.max_code


def _limits():  # runs in the child between fork and exec
    import resource
    resource.setrlimit(resource.RLIMIT_CPU, (5, 5))
    resource.setrlimit(resource.RLIMIT_AS, (512 * 1024 * 1024, 512 * 1024 * 1024))
    resource.setrlimit(resource.RLIMIT_NOFILE, (32, 32))
    resource.setrlimit(resource.RLIMIT_FSIZE, (1024 * 1024, 1024 * 1024))
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))


def execute(source: str, timeout: float = 5.0) -> dict:
    """Run one program in the sandboxed child. Returns the runner's JSON, or {'error': ...}."""
    tmp = tempfile.mkdtemp(prefix="suprevolt-ever-")
    try:
        path = os.path.join(tmp, sha256_hex(source.encode()) + ".ever")
        with open(path, "w", encoding="utf-8") as f:
            f.write(source)
        argv = [sys.executable, "-I", RUNNER, path]
        env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "PYTHONHASHSEED": "0",
               "PYTHONDONTWRITEBYTECODE": "1", "HOME": tmp}
        proc = subprocess.Popen(argv, shell=False, env=env, cwd=tmp, stdin=subprocess.DEVNULL,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                preexec_fn=_limits, start_new_session=True)
        try:
            out, err = proc.communicate(timeout=timeout)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(proc.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            proc.communicate()
            return {"error": f"stopped after {timeout:g} s (time limit)"}
        out = out[:OUTPUT_CAP]
        if proc.returncode != 0:
            if proc.returncode in (-signal.SIGXCPU, -signal.SIGKILL):
                return {"error": "stopped at the 5 s CPU limit"}
            tail = err[-300:].decode("utf-8", "replace").strip().splitlines()
            return {"error": f"runner exited {proc.returncode}: {tail[-1] if tail else ''}"}
        try:
            return json.loads(out.decode("utf-8").strip().splitlines()[-1])
        except (ValueError, IndexError):
            return {"error": "runner output unreadable"}
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def run(item, cfg) -> Result:
    if not cfg.enable_ever_run:
        return na("disabled on this server (set SUPREVOLT_ENABLE_EVER_RUN=1 to allow running programs)")
    if item.text is None:
        return na("not text")
    r = execute(item.text, cfg.ever_run_timeout)
    if "error" in r:
        return error(r["error"])
    out = {"ok": r["ok"], "errors": r["errors"], "corrections": r["corrections"],
           "recoveries": r["recoveries"], "shown": r["shown"]}
    if not r["ok"]:
        return Result("ok", 40, 256, out, "program refused: " + "; ".join(r["errors"])[:200])
    confs = [s["confidence"] for s in r["shown"]]
    zs = [s["name"] for s in r["shown"] if s["confidence"] == 0]
    if zs:
        return unknown(f"unknown (z): {', '.join(zs)}", out, transient=False)
    return Result("ok", 0, min(confs) if confs else 256, out, f"{len(confs)} value(s) shown")
