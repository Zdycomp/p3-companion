"""#1 blob.fingerprint: sha256, size, sniffed type, UTF-8 validity, line and word count. New code."""

from __future__ import annotations

from . import Result
from ..util import decode_text, image_size, sha256_hex

ID = "blob.fingerprint"
BASE_VERSION = "1"
TITLE = "Fingerprint"
ACCEPTS = "anything"
KIND = "offline"
DETERMINISTIC = True
NEEDS_KEY = False
FILES: tuple = ()


def accepts(item, cfg) -> bool:
    return True


def run(item, cfg) -> Result:
    data = item.data
    text = decode_text(data)
    out = {
        "sha256": sha256_hex(data),
        "size": len(data),
        "type": item.type,
        "utf8": text is not None,
    }
    if text is not None:
        out["lines"] = text.count("\n") + (0 if text.endswith("\n") or not text else 1)
        out["words"] = len(text.split())
        out["chars"] = len(text)
    dims = image_size(data) if item.type.startswith("image/") else None
    if dims:
        out["width"], out["height"] = dims
    note = f"{item.type}, {len(data)} bytes"
    return Result("ok", 0, 256, out, note)
