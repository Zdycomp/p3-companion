"""#8 accord.check: ClaudRick accord (vendored): parse.program() -> verify_program() -> explain_program().

Never `fill` or `build` (fill calls the API). The program is parsed from text in memory; no path
from the client is ever opened. Exact structural scan: confidence 256.
"""

from __future__ import annotations

import os
import sys

from . import VENDOR, Result, na

ID = "accord.check"
BASE_VERSION = "1"
TITLE = "Accord checker"
ACCEPTS = ".accord programs"
KIND = "offline"
DETERMINISTIC = True
NEEDS_KEY = False
FILES = ("accord/accord.py", "accord/core.py", "accord/parse.py")

ACCORD = os.path.join(VENDOR, "accord")


def _modules():
    if ACCORD not in sys.path:
        sys.path.append(ACCORD)
    import accord
    import core
    import parse
    for m in (accord, core, parse):
        if os.path.dirname(os.path.abspath(m.__file__)) != ACCORD:
            raise ImportError(f"a module named {m.__name__!r} outside the vendored copy shadows it")
    return accord, core, parse


def check(src: str) -> dict:
    accord, core, parse = _modules()
    try:
        functions = parse.program(src)
    except core.AccordError as exc:
        return {"accepted": False, "explanation": f"refused: {exc}", "functions": []}
    report = core.verify_program(functions)
    fns = []
    for name, r in report.reports.items():
        fns.append({"name": name, "accepted": bool(r.accepted), "trust": r.trust,
                    "stage": r.stage, "tac_sha256": core.tac_hash(r.tac) if r.tac else None})
    return {"accepted": bool(report.accepted), "explanation": accord.explain_program(report), "functions": fns}


def accepts(item, cfg) -> bool:
    return item.is_accord and len(item.data) <= cfg.max_code


def run(item, cfg) -> Result:
    if item.text is None:
        return na("not text")
    out = check(item.text)
    first = out["explanation"].splitlines()[0] if out["explanation"] else ""
    if out["accepted"]:
        return Result("ok", 0, 256, out, first)
    return Result("ok", 40, 256, out, first)
