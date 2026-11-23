"""#10 fineprint.ai: ReVision's Fine Print Analyzer prompt (lifted verbatim into prompts/fineprint.v1.txt).

Text passes the redaction gate first. AI flags enter at 120 confidence; a flag whose phrase is also
an offline lexicon hit is corroborated to min(200, 120 + 80). The result's confidence is the min over
its flags (120 when the AI flags nothing), so an AI-only alarm shows, but never above "unconfirmed".
Not deterministic: cached, run with the provider's most repeatable settings, marked deterministic false.
"""

from __future__ import annotations

import re

from . import Result, na
from . import _aiscan as base
from . import fineprint_lexicon
from .. import ai

ID = "fineprint.ai"
BASE_VERSION = "1"
TITLE = "Fine print (AI)"
ACCEPTS = "text and .docx documents"
KIND = "ai"
DETERMINISTIC = False
NEEDS_KEY = True
FILES: tuple = ()
EXTRA_FILES = ("_aiscan.py", "../ai.py", "fineprint_lexicon.py")
PROMPT_FILES = ("fineprint.v1.txt",)
CATEGORIES = ("deceptive_language", "tone_manipulation", "scare_tactics", "artificial_urgency",
              "hidden_terms_and_costs", "contract_traps")
SEV = ("low", "medium", "high", "critical")


def cfg_key(item, cfg) -> dict:
    return base.cfg_key(PROMPT_FILES[0], cfg)


def accepts(item, cfg) -> bool:
    return item.is_prose and len(item.text or "") <= cfg.max_text


def _norm(s: str) -> str:
    return re.sub(r"\s+", " ", s.replace("’", "'")).strip().lower()


def corroborate(flags: list[dict], text: str, lex_hits: list[dict]) -> None:
    low = _norm(text)
    spans = [(h["offset"], h["offset"] + len(h["phrase"]), _norm(h["phrase"])) for h in lex_hits]
    ntext = text.replace("’", "'").lower()
    for f in flags:
        p = _norm(f["phrase"])
        hit = False
        if p:
            at = ntext.find(f["phrase"].replace("’", "'").lower())
            if at >= 0:
                end = at + len(f["phrase"])
                hit = any(s < end and at < e for s, e, _ in spans)
            if not hit:
                hit = any(lp and (lp in p or (len(p) >= 8 and p in lp)) for _, _, lp in spans)
        f["in_text"] = bool(p) and p in low
        f["corroborated"] = hit
        f["confidence"] = min(base.CORROBORATED, base.AI_ENTRY + 80) if hit else base.AI_ENTRY


def parse(data: dict) -> tuple[int | None, list[dict], str]:
    flags = []
    for cat in CATEGORIES:
        block = data.get(cat)
        if not isinstance(block, dict):
            continue
        for d in block.get("detections") or []:
            if not isinstance(d, dict) or not str(d.get("phrase", "")).strip():
                continue
            sev = str(d.get("severity", "")).lower()
            flags.append({"category": cat, "type": str(d.get("type", ""))[:60],
                          "phrase": str(d["phrase"])[:400], "severity": sev if sev in SEV else "medium",
                          "explanation": str(d.get("explanation", ""))[:600]})
    return base.clamp_risk(data.get("overall_risk_score")), flags, str(data.get("synthesis", ""))[:1000]


def run(item, cfg) -> Result:
    text = item.text
    if text is None:
        return na("not text")
    missing = base.no_key(cfg)
    if missing:
        return missing
    redacted, findings = ai.redact(text)
    try:
        reply = ai.ask(cfg.ai, base.prompt(PROMPT_FILES[0]).replace("${text}", redacted),
                       timeout=cfg.ai_timeout, max_tokens=4000)
        risk, flags, synthesis = parse(ai.parse_json(reply))
    except ai.AiError as exc:
        return base.failed(exc)
    if risk is None:
        return base.failed(ai.AiError("the reply has no overall_risk_score"))
    corroborate(flags, text, fineprint_lexicon.find(text))
    conf = min([f["confidence"] for f in flags], default=base.AI_ENTRY)
    out = {"provider": cfg.ai.chosen(), "model": cfg.ai.model(), "overall_risk_score": risk,
           "flags": flags, "synthesis": synthesis, "redactions": findings,
           "corroborated": sum(1 for f in flags if f["corroborated"])}
    return Result("ok", risk, conf, out, f"{len(flags)} AI flag(s), {out['corroborated']} confirmed offline")
