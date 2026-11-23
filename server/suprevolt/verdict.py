"""The SupRevolt verdict: {level, risk, confidence, percent, reasons} over the applicable results.

everconf semantics: every applicable result is C(risk, confidence); `n/a` results are left out;
an `unknown` or `error` result is UNKNOWN and poisons the aggregate (csum is UNKNOWN), so the verdict
is UNKNOWN and says which scan could not answer. Otherwise risk = max, confidence = cmin_conf, and
CLEAR (< 25) / CAUTION (25 to 59) / DANGER (60+), with _UNCONFIRMED below the 128 execute floor.
"""

from __future__ import annotations

from .conf import FLOOR, UNKNOWN, C, cmin_conf, csum


def percent(conf: int) -> int:
    """218/256 shown as 85%: both from the same integer, rounded half up."""
    return (int(conf) * 100 + 128) // 256


def level_for(risk: int, conf: int) -> str:
    base = "CLEAR" if risk < 25 else "CAUTION" if risk < 60 else "DANGER"
    return base + ("_UNCONFIRMED" if conf < FLOOR else "")


def verdict(results: list[dict]) -> dict:
    applicable = [r for r in results if r["state"] != "n/a"]
    if not applicable:
        return {"level": "UNKNOWN", "risk": None, "confidence": 0, "percent": 0,
                "reasons": ["no requested scan applies to this input"], "applicable": 0}
    items = []
    for r in applicable:
        if r["state"] in ("unknown", "error") or r.get("value") is None or r.get("confidence") is None:
            items.append(UNKNOWN)
        else:
            items.append(C(int(r["value"]), confidence=int(r["confidence"])))
    poisoned = csum(items).is_unknown
    conf = cmin_conf([C(None, 0) if i is UNKNOWN else i for i in items])
    if poisoned:
        reasons = [f"{_name(r)}: {r.get('note') or r['state']}" for r in applicable
                   if r["state"] in ("unknown", "error")]
        known = [i.value for i in items if i is not UNKNOWN]
        return {"level": "UNKNOWN", "risk": max(known) if known else None, "confidence": 0, "percent": 0,
                "reasons": reasons, "applicable": len(applicable)}
    risk = max(i.value for i in items)
    reasons = [f"{_name(r)}: {r.get('note', '')}" for r in applicable if r["value"] and r["value"] > 0]
    if not reasons:
        reasons = ["nothing found by any applicable scan"]
    return {"level": level_for(risk, conf), "risk": risk, "confidence": conf, "percent": percent(conf),
            "reasons": reasons, "applicable": len(applicable)}


def _name(r: dict) -> str:
    return r["scan"] + (" (photo text)" if r.get("parent") else "")
