"""Child process for ever.run. Started only as: python -I ever_runner.py <server-made temp file>.

Runs the vendored Ever runtime (ezr run_source(), the engine behind `ever_cli.py run`) on one file
and prints one JSON line. Compared with `ever_cli.py run` it never writes a proficiency profile to
~/.ever, never opens the repair-archive database, and refuses `extern` (native library calls).
"""

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
INTERP = os.path.join(os.path.dirname(HERE), "vendor", "ezr", "interp")
sys.path.append(INTERP)

import repair_archive  # noqa: E402

repair_archive.open_repair_archive = lambda *a, **k: None   # no database writes

import ffi  # noqa: E402


def _no_extern(*a, **k):
    raise ffi.FFIError("extern is disabled on SupRevolt: programs cannot load native libraries")


ffi.load_extern = _no_extern

import runtime  # noqa: E402


def main() -> int:
    if len(sys.argv) != 2:
        print(json.dumps({"ok": False, "errors": ["usage: ever_runner.py <file>"]}))
        return 2
    with open(sys.argv[1], encoding="utf-8") as f:
        source = f.read()
    result = runtime.run_source(source, profiler=None)
    shown = []
    for sv in result.shown:
        line = runtime._format_value(sv.name, sv.value, sv.confidence)
        text = line.rsplit(" [", 1)[0]
        if text.startswith(sv.name + " = "):
            text = text[len(sv.name) + 3:]
        shown.append({"name": sv.name, "value": text, "confidence": int(sv.confidence), "line": line})
    print(json.dumps({
        "ok": bool(result.ok),
        "errors": [str(e) for e in result.errors],
        "corrections": [c.line1() for c in result.corrections],
        "recoveries": [str(r) for r in result.recoveries],
        "shown": shown,
    }, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
