"""#5 code.edapt: everlang edapt fixers (vendored): fix() and the language table.

Language from the file name's extension or the client's `lang` hint. A rule matcher: 256 when a
rule fires, 224 when nothing is found.

At the vendored commit edapt is mid-refactor: `diagnose_brackets` (which edapt's cli.py and
everlang's heal.py import) does not exist, `check_brackets` calls a missing helper, and the
JavaScript, Rust and Java fixers raise NameError (`_fix_semicolons`). So:
  - languages are probed once at import; only those whose fixer runs are accepted (today Python,
    Go, Kotlin); the rest come back automatically when a fixed edapt is vendored;
  - bracket balance is checked here with edapt's own `_mask` (strings and comments blanked) and
    `_PAIRS`, the same rule as its `check_brackets`: reported, never guessed.
"""

from __future__ import annotations

from . import Result, na

ID = "code.edapt"
BASE_VERSION = "1"
TITLE = "Code fixer (edapt)"
ACCEPTS = "source code: .py .js .mjs .go .rs .java .kt, or a lang hint"
KIND = "offline"
DETERMINISTIC = True
NEEDS_KEY = False
FILES = ("ezr/edapt/fixers.py",)


def _probe() -> tuple:
    from ..vendor.ezr.edapt import fixers
    ok = []
    for lang in sorted(fixers.LANGUAGES):
        try:
            fixers.fix("x = 1\n", lang)
            ok.append(lang)
        except Exception:
            pass
    return tuple(ok)


WORKING = _probe()
ACCEPTS = "source code in " + ", ".join(WORKING) + " (by extension or lang hint)"


def brackets(src: str, lang: str) -> list[str]:
    from ..vendor.ezr.edapt import fixers
    masked, unterminated, _ = fixers._mask(src.splitlines(), fixers._SYNTAX[lang])
    stack, problems = [], []
    openers = set(fixers._PAIRS.values())
    for lineno, line in enumerate(masked, start=1):
        for ch in line:
            if ch in openers:
                stack.append((ch, lineno))
            elif ch in fixers._PAIRS:
                if not stack or stack[-1][0] != fixers._PAIRS[ch]:
                    problems.append(f"line {lineno}: '{ch}' has no matching '{fixers._PAIRS[ch]}'")
                else:
                    stack.pop()
    for ch, lineno in stack:
        problems.append(f"line {lineno}: '{ch}' is never closed")
    for lineno in sorted(set(unterminated)):
        if lineno:
            problems.append(f"line {lineno}: a string is never closed")
    return problems


def cfg_key(item, cfg) -> dict:
    return {"lang": item.code_lang}


def accepts(item, cfg) -> bool:
    return item.code_lang in WORKING and len(item.data) <= cfg.max_code


def check(src: str, lang: str) -> dict:
    from ..vendor.ezr.edapt import fixers
    result = fixers.fix(src, lang)
    problems = brackets(src, lang)
    return {
        "language": lang,
        "fixes": [{"line": f.line, "what": f.what, "became": f.became, "reason": f.reason,
                   "name": f.name, "form": f.form, "kind": f.kind} for f in result.fixes],
        "bracket_problems": list(problems),
        "code": result.code,
        "changed": result.code != src,
    }


def run(item, cfg) -> Result:
    lang = item.code_lang
    if lang not in WORKING or item.text is None:
        return na("not source code in a language edapt knows")
    out = check(item.text, lang)
    nf, nb = len(out["fixes"]), len(out["bracket_problems"])
    if nb:
        return Result("ok", 40, 256, out, f"{nb} bracket problem(s) edapt will not guess at, {nf} fix(es)")
    if nf:
        return Result("ok", 20, 256, out, f"{nf} fix(es)")
    return Result("ok", 0, 224, out, "looks clean")
