"""Claude and Gemini over urllib, with the owner's key from the environment.

Lifted from ClaudRick/src/revision/anthropic_client.py (AnthropicClient: stdlib urllib, key injected
server-side, readable errors) and given a Gemini twin shaped like P3 Companion's AiWire.geminiBody().
The redaction gate is a port of ReVision's runRedactionGate(): card numbers (Luhn), SSNs, passwords,
API keys, emails and phone numbers are replaced before any text leaves the server.

`transport(url, headers, body, timeout) -> (status, bytes)` is injectable so tests replay stored
replies without a network or a key. Keys never appear in a log line, an error or a result.
"""

from __future__ import annotations

import base64
import json
import re
import ssl
import urllib.error
import urllib.request

from .config import AiConfig

ANTHROPIC_URL = "https://api.anthropic.com/v1/messages"
ANTHROPIC_VERSION = "2026-10-21"
# On a safety decline, let the API re-run the request on a fallback model it picks by category.
ANTHROPIC_BETA = "server-side-fallback-2026-07-01"
GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta/models/"


class AiError(Exception):
    """The AI call failed (network, HTTP error, refusal, unreadable reply)."""


class NoKey(AiError):
    """No key for the chosen provider is set on the server."""


def default_transport(url: str, headers: dict, body: bytes, timeout: float) -> tuple[int, bytes]:
    req = urllib.request.Request(url, data=body, method="POST", headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=ssl.create_default_context()) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read()
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        reason = getattr(exc, "reason", exc)
        raise AiError(f"could not reach the AI service: {reason}") from None


def _error_text(raw: bytes) -> str:
    text = raw.decode("utf-8", "replace")
    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        return text[:200]
    err = data.get("error") if isinstance(data, dict) else None
    if isinstance(err, dict):
        return str(err.get("message", ""))[:200]
    return str(err or text)[:200]


def ask(ai: AiConfig, prompt: str, image: bytes | None = None, image_type: str = "image/jpeg",
        timeout: float = 60.0, max_tokens: int = 4000) -> str:
    """Send one user turn (text, optionally one image) and return the reply text."""
    provider = ai.chosen()
    if not provider:
        raise NoKey(ai.missing_note())
    send = ai.transport or default_transport
    if provider == "claude":
        content = []
        if image is not None:
            content.append({"type": "image", "source": {
                "type": "base64", "media_type": image_type, "data": base64.b64encode(image).decode()}})
        content.append({"type": "text", "text": prompt})
        # No temperature: current Claude models and API version 2026-10-21 accept only default sampling.
        body = {"model": ai.claude_model, "max_tokens": max_tokens, "fallbacks": "default",
                "messages": [{"role": "user", "content": content}]}
        headers = {"content-type": "application/json", "x-api-key": ai.anthropic_key,
                   "anthropic-version": ANTHROPIC_VERSION, "anthropic-beta": ANTHROPIC_BETA}
        status, raw = send(ANTHROPIC_URL, headers, json.dumps(body).encode(), timeout)
        if status // 100 != 2:
            raise AiError(f"Claude error ({status}): {_error_text(raw)}")
        try:
            data = json.loads(raw)
        except json.JSONDecodeError:
            raise AiError("Claude sent a reply that is not JSON") from None
        if data.get("stop_reason") == "refusal":
            raise AiError("Claude declined this request")
        text = "".join(b.get("text", "") for b in data.get("content", []) if b.get("type") == "text")
        if not text.strip():
            raise AiError("Claude sent an empty reply")
        return text
    parts = []
    if image is not None:
        parts.append({"inline_data": {"mime_type": image_type, "data": base64.b64encode(image).decode()}})
    parts.append({"text": prompt})
    body = {"contents": [{"role": "user", "parts": parts}],
            "generationConfig": {"temperature": 0, "responseMimeType": "application/json"}}
    headers = {"Content-Type": "application/json", "x-goog-api-key": ai.gemini_key}
    status, raw = send(GEMINI_BASE + ai.gemini_model + ":generateContent", headers,
                       json.dumps(body).encode(), timeout)
    if status // 100 != 2:
        raise AiError(f"Gemini error ({status}): {_error_text(raw)}")
    try:
        data = json.loads(raw)
        cand = data["candidates"][0]
        text = "".join(p.get("text", "") for p in cand.get("content", {}).get("parts", []))
    except (json.JSONDecodeError, KeyError, IndexError, TypeError):
        raise AiError("Gemini sent a reply SupRevolt could not read") from None
    if not text.strip():
        raise AiError("Gemini sent an empty reply (blocked or no answer)")
    return text


def parse_json(text: str) -> dict:
    """The JSON object in a reply, with ```json fences or chatter around it removed."""
    t = re.sub(r"```(?:json)?", "", text).strip()
    start, end = t.find("{"), t.rfind("}")
    if start < 0 or end <= start:
        raise AiError("the AI reply holds no JSON object")
    try:
        data = json.loads(t[start:end + 1])
    except json.JSONDecodeError:
        raise AiError("the AI reply is not valid JSON") from None
    if not isinstance(data, dict):
        raise AiError("the AI reply is not a JSON object")
    return data


def cfg_key(ai: AiConfig, prompt_bytes: bytes) -> dict:
    """What decides an AI answer. Changing the model or the prompt is a new cache key."""
    import hashlib
    p = ai.chosen()
    return {"provider": p, "model": ai.model(),
            "prompt": hashlib.sha256(prompt_bytes).hexdigest(),
            "sampling": "temperature=0" if p == "gemini" else "default"}


# ------------------------------------------------------------- redaction gate

def _luhn(digits: str) -> bool:
    clean = re.sub(r"\D", "", digits, flags=re.A)
    if not 13 <= len(clean) <= 19:
        return False
    total, alt = 0, False
    for ch in reversed(clean):
        d = int(ch)
        if alt:
            d *= 2
            if d > 9:
                d -= 9
        total += d
        alt = not alt
    return total % 10 == 0


_RULES = [
    ("US Social Security Number", "[REDACTED-SSN]", re.compile(r"\b\d{3}[-\s]\d{2}[-\s]\d{4}\b", re.A)),
    ("Immigration A-Number", "[REDACTED-A-NUMBER]", re.compile(r"\bA-?\d{7,9}\b", re.A | re.I)),
    ("US bank routing number", "[REDACTED-ROUTING]",
     re.compile(r"routing\s*(?:number|no\.?|#)?\s*[:\-]?\s*(\d{9})\b", re.A | re.I)),
    ("Password / credential value", "[REDACTED-PASSWORD]",
     re.compile(r"\b(password|pwd|passcode|pin code|secret)\s*[:=]\s*\S+", re.A | re.I)),
    ("API key / access token", "[REDACTED-TOKEN]",
     re.compile(r"\b(sk-[A-Za-z0-9]{16,}|AKIA[0-9A-Z]{16}|ghp_[A-Za-z0-9]{30,}|xox[baprs]-[A-Za-z0-9-]{10,})\b", re.A)),
    ("API key / access token", "[REDACTED-TOKEN]",
     re.compile(r"\b(api[_\s-]?key|access[_\s-]?token|secret[_\s-]?key)\s*[:=]\s*['\"]?[A-Za-z0-9_\-]{12,}['\"]?",
                re.A | re.I)),
    ("Email address", "[REDACTED-EMAIL]", re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b", re.A)),
    ("Phone number", "[REDACTED-PHONE]",
     re.compile(r"\b(?:\+?1[-.\s]?)?\(?\d{3}\)?[-.\s]?\d{3}[-.\s]?\d{4}\b", re.A)),
]
_CARD = re.compile(r"\b(?:\d[ -]*?){13,19}\b", re.A)


def redact(text: str) -> tuple[str, list[dict]]:
    """Remove sensitive values before text is sent to an AI. Returns (text, [{category, count}])."""
    findings: list[dict] = []
    cards = [m for m in _CARD.finditer(text) if _luhn(m.group(0))]
    if cards:
        findings.append({"category": "Credit/debit card number", "count": len(cards)})
        for m in sorted(cards, key=lambda m: m.start(), reverse=True):
            text = text[:m.start()] + "[REDACTED-CARD]" + text[m.end():]
    for category, placeholder, rx in _RULES:
        n = len(rx.findall(text))
        if n:
            findings.append({"category": category, "count": n})
            text = rx.sub(placeholder, text)
    return text, findings
