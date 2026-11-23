"""#9 fineprint.lexicon: the offline half of the Revisor / ClauseShield "contract trap" scan.

New code. A versioned table of phrase patterns in the categories of ReVision's Fine Print Analyzer
(ClaudRick/tests/corpora/analyzer/ground-truth.json schema, plus the analyzer prompt's contract_traps).
Patterns describe the shape of a trap, not one document's wording. A pattern marked `guard` is skipped
when a negation ("no", "not", "never", "without") sits just before it, so "There are no restocking
fees" is not a hidden fee.

Risk 0 to 100 combines hits as independent evidence: risk = 100 * (1 - prod(1 - w)), with
w = 10 / 20 / 35 / 50 % for low / medium / high / critical, computed in exact fractions.
Confidence: 200 when anything is found, 160 for "clean" (a lexicon misses paraphrases).
"""

from __future__ import annotations

import re
from fractions import Fraction

from . import Result, na

ID = "fineprint.lexicon"
BASE_VERSION = "1"
TITLE = "Fine print (offline)"
ACCEPTS = "text and .docx documents"
KIND = "offline"
DETERMINISTIC = True
NEEDS_KEY = False
FILES: tuple = ()
TABLE_VERSION = "fineprint-lexicon/1"

CONF_HIT, CONF_CLEAN = 200, 160
WEIGHT = {"low": 10, "medium": 20, "high": 35, "critical": 50}
SEVERITY_RANK = {"low": 0, "medium": 1, "high": 2, "critical": 3}

A = r"['’]"   # straight or curly apostrophe

# (category, subtype, severity, guard, pattern)
TABLE = [
    # hidden terms and costs
    ("hidden_terms_and_costs", "auto_renewal", "high", True, r"\b(?:renews?|renewed|renewing)\s+automatically\b"),
    ("hidden_terms_and_costs", "auto_renewal", "high", True, r"\bauto(?:matic(?:ally)?)?[- ]?renew(?:s|al|ed|ing)?\b"),
    ("hidden_terms_and_costs", "forced_continuity", "critical", True,
     r"\bautomatically\s+(?:enrolled|upgraded|charged|billed|converted|moved)\b"),
    ("hidden_terms_and_costs", "forced_continuity", "critical", False, r"\bcharged\s+automatically\b"),
    ("hidden_terms_and_costs", "forced_continuity", "high", True,
     r"\b(?:subscription|membership|plan|service|coaching)\s+continues\b[^.\n]{0,40}\b(?:every|each|per)\s+(?:week|month|year)\b"),
    ("hidden_terms_and_costs", "forced_continuity", "high", True, r"\brenews?\s+on\s+(?:its|their)\s+own\b"),
    ("hidden_terms_and_costs", "forced_continuity", "medium", False, r"\bforget\s+to\s+cancel\b"),
    ("hidden_terms_and_costs", "forced_continuity", "medium", False, r"\bunless\s+you\s+cancel\b"),
    ("hidden_terms_and_costs", "cancellation_obstruction", "critical", True,
     r"\bmust\s+be\s+(?:delivered|made|submitted|sent|presented)\s+in\s+person\b"),
    ("hidden_terms_and_costs", "cancellation_obstruction", "high", False,
     r"\b(?:by|via)\s+(?:phone|telephone|e-?mail|online)\b[^.\n]{0,40}\bcannot\s+be\s+accepted\b"),
    ("hidden_terms_and_costs", "cancellation_obstruction", "high", False,
     r"\bany\s+other\s+(?:method|means|way)\b[^.\n]{0,60}\b(?:void|invalid|not\s+be\s+accepted|rejected)\b"),
    ("hidden_terms_and_costs", "cancellation_obstruction", "medium", False,
     r"\b(?:details|terms|instructions)\s+(?:are|is)\s+(?:provided|available|found|set\s+out)\s+in\s+(?:the\s+)?(?:appendix|schedule|exhibit|addendum)\b"),
    ("hidden_terms_and_costs", "cancellation_obstruction", "high", True, r"\bcertified\s+mail\s+only\b"),
    ("hidden_terms_and_costs", "hidden_fee", "medium", True,
     r"\b(?:fee|charge|charges|fees)\b[^.\n]{0,40}\b(?:billed|charged|invoiced)\s+separately\b"),
    ("hidden_terms_and_costs", "hidden_fee", "medium", True,
     r"\b(?:maintenance|handling|administrative|admin|processing|service|activation|convenience|restocking|enrollment|setup|set-up)\s+(?:fee|charge)s?\b"),
    ("hidden_terms_and_costs", "hidden_fee", "high", True, r"\bnon-?refundable\b"),
    ("hidden_terms_and_costs", "hidden_fee", "medium", True, r"\b(?:is|are)\s+not\s+included\b"),
    ("hidden_terms_and_costs", "buried_limitation", "critical", False,
     r"\bannual\s+percentage\s+rate\b[^.\n]{0,60}\b(?:exceed|over|above|up\s+to)\b"),
    ("hidden_terms_and_costs", "buried_limitation", "high", False,
     r"\bas\s+(?:disclosed|described|set\s+out|explained)\s+in\s+(?:section|clause|paragraph|appendix|schedule)\b"),
    ("hidden_terms_and_costs", "buried_limitation", "medium", True, r"\b(?:lease|contract)[- ]break(?:ing)?\s+fee\b"),
    ("hidden_terms_and_costs", "buried_limitation", "medium", False,
     r"\bequal\s+to\s+(?:one|two|three|four|six|\d+)\s+months?" + A + r"?\s+rent\b"),
    # artificial urgency
    ("artificial_urgency", "now_or_never", "high", False, r"\bbefore\s+you\s+leave\b"),
    ("artificial_urgency", "now_or_never", "high", False, r"\b(?:today|tonight)" + A + r"?s?\s+(?:presentation|event|call|visit)\s+only\b"),
    ("artificial_urgency", "now_or_never", "high", False, r"\b(?:today|tonight)\s+only\b"),
    ("artificial_urgency", "now_or_never", "high", False, r"\bonly\s+available\s+(?:if|when|today|tonight|now)\b"),
    ("artificial_urgency", "now_or_never", "high", False,
     r"\bwill\s+(?:not|never)\s+be\s+(?:offered|available|repeated)\s+again\b"),
    ("artificial_urgency", "now_or_never", "high", False, r"\b(?:will\s+)?never\s+(?:reopen|return|come\s+back)\b"),
    ("artificial_urgency", "now_or_never", "high", False, r"\bslams?\s+shut\b"),
    ("artificial_urgency", "now_or_never", "medium", False, r"\b(?:last\s+chance|now\s+or\s+never|act\s+now)\b"),
    ("artificial_urgency", "time_pressure", "high", False,
     r"\b(?:closes|ends|expires|ending)\s+(?:at\s+midnight|tonight|today|in\s+\d+\s+(?:hours?|minutes?))\b"),
    ("artificial_urgency", "time_pressure", "high", False, r"\bmidnight\s+tonight\b"),
    ("artificial_urgency", "time_pressure", "medium", False, r"\b(?:shut|shuts|close|closes|ends|expires|gone|over)\s+in\s+(?:the\s+next\s+)?\d+\s+(?:hours?|minutes?)\b"),
    ("artificial_urgency", "scarcity_claim", "medium", False,
     r"\bonly\s+\d+\s+(?:[a-z]+\s+){0,2}(?:left|remain|remaining)\b"),
    ("artificial_urgency", "scarcity_claim", "medium", False,
     r"\b\d+\s+(?:seats|spots|places|boxes|units|slots|tickets)\s+(?:left|remain|remaining)\b"),
    ("artificial_urgency", "scarcity_claim", "medium", False, r"\b(?:selling\s+out\s+fast|while\s+(?:supplies|stocks?)\s+last)\b"),
    ("artificial_urgency", "limited_quantity", "low", False,
     r"\bonce\s+(?:they|it)(?:" + A + r"re|\s+are|\s+is|" + A + r"s)\s+gone\b"),
    ("artificial_urgency", "limited_quantity", "low", False, r"\blimited\s+(?:quantity|quantities|stock|supply|edition)\b"),
    # tone manipulation
    ("tone_manipulation", "fear_appeal", "medium", False,
     r"\b(?:do\s+not|don" + A + r"t)\s+be\s+the\s+(?:last|only)\s+(?:one|person)\b"),
    ("tone_manipulation", "fear_appeal", "medium", False, r"\bon\s+the\s+sidelines\b"),
    ("tone_manipulation", "fear_appeal", "medium", False, r"\b(?:get|be|left)\s+left\s+behind\b|\bfear\s+of\s+missing\s+out\b"),
    ("tone_manipulation", "emotional_appeal", "medium", False,
     r"\b(?:do\s+not|don" + A + r"t)\s+be\s+the\s+(?:person|one|kind\s+of\s+person)\s+who\b"),
    ("tone_manipulation", "emotional_appeal", "medium", False, r"\bgives?\s+up\s+on\s+(?:yourself|themselves|your\s+dreams)\b"),
    ("tone_manipulation", "emotional_appeal", "low", False, r"\byou\s+deserve\s+(?:this|it|better|the\s+best)\b"),
    ("tone_manipulation", "greed_appeal", "low", False, r"\bahead\s+of\s+everyone(?:\s+else)?\b"),
    ("tone_manipulation", "greed_appeal", "medium", False, r"\bstay\s+(?:broke|poor)\b"),
    ("tone_manipulation", "greed_appeal", "medium", False, r"\b(?:get|getting|become)\s+rich\s+(?:quick|fast)\b"),
    ("tone_manipulation", "false_authority", "medium", False, r"\bhand[- ]?(?:selected|picked)\b"),
    ("tone_manipulation", "false_authority", "medium", True, r"\bpre-?approved\b"),
    ("tone_manipulation", "false_authority", "high", False,
     r"\bendorsed\s+by\s+(?:leading\s+|top\s+)?(?:experts|doctors|insiders|professionals|celebrities)\b"),
    ("tone_manipulation", "false_authority", "medium", False, r"\bexperts\s+agree\b"),
    ("tone_manipulation", "false_consensus", "medium", False,
     r"\b(?:thousands|millions|hundreds)\s+of\b[^.\n]{0,40}\b(?:have\s+)?already\s+(?:joined|signed\s+up|switched|bought|invested)\b"),
    ("tone_manipulation", "false_consensus", "medium", False, r"\beveryone\s+in\s+the\s+know\b"),
    ("tone_manipulation", "false_consensus", "low", False, r"\bthe\s+winning\s+side\b"),
    # scare tactics
    ("scare_tactics", "negative_consequence", "high", False,
     r"\b(?:risk|risks|risking)\s+losing\s+your\s+(?:home|house|job|car|savings|family|kids|children)\b"),
    ("scare_tactics", "negative_consequence", "high", False, r"\bfall\s+behind\s+on\s+(?:rent|bills|payments|your\s+mortgage)\b"),
    ("scare_tactics", "social_harm", "medium", False,
     r"\b(?:do\s+not|don" + A + r"t)\s+let\s+(?:that|this|it)\s+happen\s+to\s+your\s+(?:family|kids|children|loved\s+ones)\b"),
    ("scare_tactics", "threat", "high", False, r"\bimmediate\s+eviction\b"),
    ("scare_tactics", "threat", "high", False, r"\breport(?:ed)?\s+to\s+(?:collections|a\s+collection\s+agency|the\s+credit\s+bureaus?)\b"),
    ("scare_tactics", "threat", "medium", True, r"\b(?:legal\s+action|will\s+be\s+(?:prosecuted|sued))\b"),
    ("scare_tactics", "worst_case_framing", "medium", False,
     r"\b(?:spend|spent)\s+years\b[^.\n]{0,60}\bwithout\s+you\b"),
    ("scare_tactics", "worst_case_framing", "medium", False, r"\bwatching\s+others\s+get\s+rich\b"),
    ("scare_tactics", "worst_case_framing", "medium", False, r"\bregret\s+(?:it\s+)?(?:forever|for\s+the\s+rest\s+of\s+your\s+life)\b"),
    # deceptive language
    ("deceptive_language", "contradiction", "high", False, r"\bno\s+commitment\b"),
    ("deceptive_language", "framing", "high", False, r"\b(?:just|only)\s+a\s+(?:small|tiny|modest|low)\s+fee\b"),
    ("deceptive_language", "framing", "high", False, r"\$\d+\s+(?:per|for\s+every)\s+\$100\s+borrowed\b"),
    ("deceptive_language", "false_guarantee", "critical", True,
     r"\bguaranteed\s+(?:to\s+(?:double|triple|\d+x|make|earn|grow)|returns?|profits?|income|results)\b"),
    ("deceptive_language", "false_guarantee", "critical", False, r"\b(?:\d+|ten)x\s+your\s+(?:income|money|investment|returns?)\b"),
    ("deceptive_language", "false_guarantee", "high", False, r"\brisk[- ]free\b"),
    # contract traps (from the analyzer prompt)
    ("contract_traps", "arbitration_clause", "high", True, r"\bbinding\s+arbitration\b"),
    ("contract_traps", "class_action_waiver", "high", True, r"\bclass[- ]action\s+(?:waiver|lawsuits?)\b"),
    ("contract_traps", "liability_waiver", "high", False, r"\bwaives?\s+(?:any|all|your|the)\s+rights?\s+to\b"),
    ("contract_traps", "unilateral_modification", "high", False,
     r"\bmay\s+(?:change|modify|amend|update)\s+(?:these|this|the|its)\s+(?:terms|agreement|prices?|fees)\s+at\s+any\s+time\b"),
    ("contract_traps", "confession_of_judgment", "critical", True, r"\bconfession\s+of\s+judg(?:e)?ment\b"),
    ("contract_traps", "indemnification", "medium", True, r"\b(?:you\s+(?:agree\s+to|shall|will)\s+)indemnify\b"),
]

COMPILED = [(c, s, sev, g, re.compile(p, re.I)) for (c, s, sev, g, p) in TABLE]
_NEG = re.compile(r"\b(?:no|not|never|without|zero|free\s+of)\b[^.\n]{0,12}$", re.I)


def find(text: str) -> list[dict]:
    raw = []
    for idx, (cat, sub, sev, guard, rx) in enumerate(COMPILED):
        for m in rx.finditer(text):
            if guard and _NEG.search(text[max(0, m.start() - 24):m.start()]):
                continue
            raw.append((m.start(), m.end(), idx, cat, sub, sev, m.group(0)))
    raw.sort(key=lambda r: (r[0], r[2]))
    hits, taken = [], []
    for start, end, idx, cat, sub, sev, phrase in raw:
        if any(c == cat and start < e and s < end for (s, e, c) in taken):
            continue   # same category, overlapping span: one finding, not two
        taken.append((start, end, cat))
        hits.append({"category": cat, "subtype": sub, "severity": sev, "phrase": phrase,
                     "offset": start, "rule": idx})
    return hits


def risk_of(hits: list[dict]) -> int:
    rest = Fraction(1)
    for h in hits:
        rest *= Fraction(100 - WEIGHT[h["severity"]], 100)
    r = (1 - rest) * 100
    return min(100, int(r + Fraction(1, 2)))   # round half up, exactly


def scan(text: str) -> dict:
    hits = find(text)
    counts = {}
    for h in hits:
        counts[h["category"]] = counts.get(h["category"], 0) + 1
    worst = max((h["severity"] for h in hits), key=lambda s: SEVERITY_RANK[s], default=None)
    return {"table": TABLE_VERSION, "hits": hits, "counts": counts, "worst": worst, "risk": risk_of(hits)}


def accepts(item, cfg) -> bool:
    return item.is_prose and len(item.text or "") <= cfg.max_text * 4


def run(item, cfg) -> Result:
    text = item.text
    if text is None:
        return na("not text")
    out = scan(text)
    n = len(out["hits"])
    if not n:
        return Result("ok", 0, CONF_CLEAN, out, "no trap phrases found")
    subs = sorted({h["subtype"] for h in out["hits"]})
    return Result("ok", out["risk"], CONF_HIT, out, f"{n} flag(s): {', '.join(subs)}")
