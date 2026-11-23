"""#2 refix.gradle: a Python port of ReFix's scan() (ClaudRick/refix2/src/match.js, MIT).

Reads the same vendored signature corpus (refix2/signatures/gradle.json). Only `scan` is ported:
ReFix's apply/revert write files and are never part of SupRevolt.

JavaScript regex semantics are kept: the `m` flag, `\\d` meaning [0-9] only, the first match per
signature, an unmatched group interpolating as "undefined" and being left out of `captures`
(JSON.stringify drops undefined). A parity test runs the real match.js under Node when available.

Known signature bug, kept on purpose so this port stays faithful: gradle.unresolved_dependency's
version group `([a-zA-Z0-9._+-]+)` swallows a sentence's trailing period ("9.9.9."). Such a capture
is flagged in `suspect` and the hit drops from 256 to 192 confidence. See vendor/VENDORED.md.
"""

from __future__ import annotations

import json
import os
import re

from . import VENDOR, Result

ID = "refix.gradle"
BASE_VERSION = "1"
TITLE = "Gradle build fixer"
ACCEPTS = "a Gradle build log (FAILURE:, BUILD FAILED, BUILD SUCCESSFUL or > Task :)"
KIND = "offline"
DETERMINISTIC = True
NEEDS_KEY = False
FILES = ("refix/gradle.json",)

CONF_HIT, CONF_SUSPECT, CONF_NONE = 256, 192, 224


def js_to_py(pattern: str) -> str:
    """Translate the JS escapes whose meaning differs in Python: \\d is ASCII-only in JS."""
    out, i, in_class = [], 0, False
    while i < len(pattern):
        ch = pattern[i]
        if ch == "\\" and i + 1 < len(pattern):
            nxt = pattern[i + 1]
            if nxt == "d":
                out.append("0-9" if in_class else "[0-9]")
            elif nxt == "D" and not in_class:
                out.append("[^0-9]")
            else:
                out.append(ch + nxt)
            i += 2
            continue
        if ch == "[" and not in_class:
            in_class = True
        elif ch == "]" and in_class:
            in_class = False
        out.append(ch)
        i += 1
    return "".join(out)


def load_signatures(path: str | None = None) -> list[dict]:
    with open(path or os.path.join(VENDOR, "refix", "gradle.json"), encoding="utf-8") as f:
        data = json.load(f)
    sigs = []
    for s in data["signatures"]:
        s = dict(s)
        s["_re"] = re.compile(js_to_py(s["match"]), re.M)
        sigs.append(s)
    return sigs


_TPL = re.compile(r"\{([a-zA-Z_][a-zA-Z0-9_]*)\}")


def _interp(tpl: str, caps: dict) -> str:
    def sub(m):
        k = m.group(1)
        if k not in caps:
            return "{" + k + "}"
        v = caps[k]
        return "undefined" if v is None else str(v)
    return _TPL.sub(sub, tpl)


def scan(stderr: str, signatures: list[dict]) -> list[dict]:
    """The port of match.js scan(): one entry per signature that matches, in corpus order."""
    out = []
    for sig in signatures:
        m = sig["_re"].search(stderr)
        if not m:
            continue
        caps = {}
        for i, name in enumerate(sig.get("capture") or []):
            caps[name] = m.group(i + 1) if i + 1 <= (sig["_re"].groups or 0) else None
        out.append({
            "signature_id": sig["id"],
            "kind": sig["kind"],
            "family": sig["family"],
            "failure_text": m.group(0),
            "fixture_form": _interp(sig["fixture_form"], caps),
            "fixture_text": _interp(sig["fixture_text"], caps),
            "captures": {k: v for k, v in caps.items() if v is not None},
            "_caps_all": caps,
        })
    return out


def suspect_captures(caps: dict) -> list[str]:
    """Captures that look wrong: unmatched, or ending in sentence punctuation."""
    return sorted(k for k, v in caps.items() if k != "_" and (v is None or v.endswith((".", ",", ";", ":"))))


_SIGS = None


def accepts(item, cfg) -> bool:
    return item.is_build_log and len(item.data) <= cfg.max_text


def run(item, cfg) -> Result:
    global _SIGS
    if _SIGS is None:
        _SIGS = load_signatures()
    text = item.text or ""
    hits = scan(text, _SIGS)
    if not hits:
        return Result("ok", 0, CONF_NONE, {"hits": []}, "no known Gradle failure matched")
    conf = CONF_HIT
    out_hits = []
    for h in hits:
        bad = suspect_captures(h.pop("_caps_all"))
        h["suspect"] = bad
        h["confidence"] = CONF_SUSPECT if bad else CONF_HIT
        conf = min(conf, h["confidence"])
        out_hits.append(h)
    names = ", ".join(h["signature_id"] for h in out_hits)
    return Result("ok", 40, conf, {"hits": out_hits}, f"{len(out_hits)} fix(es): {names}")
