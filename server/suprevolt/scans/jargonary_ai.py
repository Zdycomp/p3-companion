"""#11 jargonary.ai: ReVision's Jargonary prompt (plain-English level), plus the Revisor idea's
tone, sarcasm, intent and a 0 to 100 manipulation risk. Lifted into prompts/jargonary.v1.txt.

AI result: confidence 120 (outside data, under the execute floor). Not deterministic.
"""

from __future__ import annotations

from . import Result, na
from . import _aiscan as base
from .. import ai

ID = "jargonary.ai"
BASE_VERSION = "1"
TITLE = "Jargonary (AI)"
ACCEPTS = "text and .docx documents"
KIND = "ai"
DETERMINISTIC = False
NEEDS_KEY = True
FILES: tuple = ()
EXTRA_FILES = ("_aiscan.py", "../ai.py")
PROMPT_FILES = ("jargonary.v1.txt",)


def cfg_key(item, cfg) -> dict:
    return base.cfg_key(PROMPT_FILES[0], cfg)


def accepts(item, cfg) -> bool:
    return item.is_prose and len(item.text or "") <= cfg.max_text


def run(item, cfg) -> Result:
    text = item.text
    if text is None:
        return na("not text")
    missing = base.no_key(cfg)
    if missing:
        return missing
    redacted, findings = ai.redact(text)
    try:
        data = ai.parse_json(ai.ask(cfg.ai, base.prompt(PROMPT_FILES[0]).replace("${text}", redacted),
                                    timeout=cfg.ai_timeout, max_tokens=6000))
    except ai.AiError as exc:
        return base.failed(exc)
    glossary = []
    for g in data.get("jargon_glossary") or []:
        if isinstance(g, dict) and g.get("term"):
            glossary.append({"term": str(g["term"])[:200], "plain_meaning": str(g.get("plain_meaning", ""))[:600],
                             "why_it_matters": str(g.get("why_it_matters", ""))[:600]})
    risk = base.clamp_risk(data.get("manipulation_risk"))
    out = {"provider": cfg.ai.chosen(), "model": cfg.ai.model(),
           "simplified_text": str(data.get("simplified_text", ""))[:20000],
           "jargon_glossary": glossary, "key_takeaway": str(data.get("key_takeaway", ""))[:1000],
           "tone": str(data.get("tone", ""))[:60], "sarcasm": data.get("sarcasm") is True,
           "intent": str(data.get("intent", ""))[:400], "manipulation_risk": risk, "redactions": findings}
    if not out["simplified_text"] and not glossary:
        return base.failed(ai.AiError("the reply has no simplified text"))
    tone = f", tone {out['tone']}" if out["tone"] else ""
    return Result("ok", risk if risk is not None else 0, base.AI_ENTRY, out,
                  f"{len(glossary)} term(s) explained{tone}")
