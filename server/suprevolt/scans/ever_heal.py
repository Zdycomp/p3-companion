"""#6 ever.heal: Ever's forgiving front door (vendored ezr forgive.py parse_forgiving()).

Heals what it safely can (stray `;`, trailing or missing commas, keyword case, `:` for `=`...),
names every correction, and refuses only when the intent is ambiguous. Rule matcher confidence:
256 when a correction or refusal is found, 224 when the source was already clean.
"""

from __future__ import annotations

import os
import sys

from . import VENDOR, Result, na

ID = "ever.heal"
BASE_VERSION = "1"
TITLE = "Ever healer"
ACCEPTS = ".ever source"
KIND = "offline"
DETERMINISTIC = True
NEEDS_KEY = False
FILES = ("ezr/interp/forgive.py", "ezr/interp/syntax.py", "ezr/interp/ever.py")

INTERP = os.path.join(VENDOR, "ezr", "interp")


def _forgive():
    if INTERP not in sys.path:
        sys.path.append(INTERP)   # appended, never prepended: nothing already importable is shadowed
    import forgive
    if os.path.dirname(os.path.abspath(forgive.__file__)) != INTERP:
        raise ImportError("a module named 'forgive' outside the vendored copy shadows it")
    return forgive


def heal(src: str) -> dict:
    forgive = _forgive()
    node, err, corrections = forgive.parse_forgiving(src)
    healed_src, _ = forgive._strip_semicolons(src)
    return {
        "corrections": [{"line": c.line, "what": c.what, "became": c.became, "reason": c.reason,
                         "name": c.name, "form": c.form, "kind": c.kind} for c in corrections],
        "parsed": err is None and node is not None,
        "refusal": None if err is None else str(err),
        "statements": len(getattr(node, "statements", None) or []) if node is not None else 0,
        "source_without_semicolons": healed_src if healed_src != src else None,
    }


def accepts(item, cfg) -> bool:
    return item.is_ever and len(item.data) <= cfg.max_code


def run(item, cfg) -> Result:
    if item.text is None:
        return na("not text")
    out = heal(item.text)
    n = len(out["corrections"])
    if out["refusal"]:
        return Result("ok", 40, 256, out, f"refused: {out['refusal']}")
    if n:
        return Result("ok", 20, 256, out, f"{n} correction(s), parses after healing")
    return Result("ok", 0, 224, out, "parses as written")
