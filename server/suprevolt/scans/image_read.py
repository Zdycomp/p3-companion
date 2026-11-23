"""#12 image.read: reads the text in a photo (P3 Companion's "Read and translate the text" preset).

Uses the owner's AI (Gemini or Claude vision, same request shapes as the app's AiWire). If no key
is set and `tesseract` is installed, it runs offline OCR with a fixed argv instead. The text it finds
becomes a new blob, and the text scans (fineprint.lexicon, fineprint.ai, jargonary.ai) run on it as
child results of the same job. Confidence 120. Not deterministic.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import tempfile

from . import Result, na, unknown
from . import _aiscan as base
from .. import ai

ID = "image.read"
BASE_VERSION = "1"
TITLE = "Read text in a photo (AI)"
ACCEPTS = "JPEG, PNG, GIF or WebP images"
KIND = "ai"
DETERMINISTIC = False
NEEDS_KEY = True
FILES: tuple = ()
EXTRA_FILES = ("_aiscan.py", "../ai.py")
PROMPT_FILES = ("image_read.v1.txt",)
TYPES = ("image/jpeg", "image/png", "image/gif", "image/webp")


def cfg_key(item, cfg) -> dict:
    key = base.cfg_key(PROMPT_FILES[0], cfg)
    if not key["provider"] and shutil.which("tesseract"):
        key = {"provider": "tesseract"}
    return key


def accepts(item, cfg) -> bool:
    return item.type in TYPES and len(item.data) <= cfg.max_image


def _tesseract(data: bytes) -> str:
    exe = shutil.which("tesseract")
    tmp = tempfile.mkdtemp(prefix="suprevolt-ocr-")
    try:
        path = os.path.join(tmp, "page.img")
        with open(path, "wb") as f:
            f.write(data)
        r = subprocess.run([exe, path, "stdout"], shell=False, cwd=tmp, capture_output=True, timeout=30,
                           env={"PATH": os.environ.get("PATH", "/usr/bin:/bin")})
        if r.returncode != 0:
            raise ai.AiError("tesseract could not read the image")
        return r.stdout[:256 * 1024].decode("utf-8", "replace")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def run(item, cfg) -> Result:
    if item.type not in TYPES:
        return na("not an image")
    if not cfg.ai.chosen():
        if shutil.which("tesseract"):
            try:
                text = _tesseract(item.data).strip()
            except (ai.AiError, subprocess.TimeoutExpired, OSError) as exc:
                return unknown(f"offline OCR failed: {exc}")
            out = {"provider": "tesseract", "text": text, "language": "", "translation": ""}
            return Result("ok", 0, base.AI_ENTRY, out, f"{len(text)} characters read offline",
                          child_text=text or None)
        return unknown(cfg.ai.missing_note())
    try:
        data = ai.parse_json(ai.ask(cfg.ai, base.prompt(PROMPT_FILES[0]), image=item.data,
                                    image_type=item.type, timeout=cfg.ai_timeout, max_tokens=4000))
    except ai.AiError as exc:
        return base.failed(exc)
    text = str(data.get("text", ""))[:200000].strip()
    out = {"provider": cfg.ai.chosen(), "model": cfg.ai.model(), "text": text,
           "language": str(data.get("language", ""))[:60], "translation": str(data.get("translation", ""))[:200000]}
    note = f"{len(text)} characters read" if text else "no text in the photo"
    return Result("ok", 0, base.AI_ENTRY, out, note, child_text=text or None)
