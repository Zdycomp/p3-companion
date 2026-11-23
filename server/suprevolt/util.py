"""Small deterministic helpers: canonical JSON, hashing, type sniffing, .docx text."""

from __future__ import annotations

import hashlib
import io
import json
import re
import struct
import zipfile
from xml.etree import ElementTree as ET


def canon(obj) -> str:
    """Canonical JSON: sorted keys, no spaces, UTF-8 kept readable. Same object, same bytes."""
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False)


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def normalize_text_bytes(data: bytes) -> bytes:
    """Text is hashed as UTF-8 with CRLF turned into LF, nothing else changed."""
    return data.replace(b"\r\n", b"\n")


def decode_text(data: bytes) -> str | None:
    """The bytes as text, or None when they are not clean UTF-8 text (binary, NUL bytes)."""
    if b"\x00" in data:
        return None
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return None


def is_texty(data: bytes) -> bool:
    return decode_text(data) is not None


DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"


def sniff(data: bytes) -> str:
    """Media type from magic bytes only (never from what the client claims)."""
    if data[:3] == b"\xff\xd8\xff":
        return "image/jpeg"
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return "image/png"
    if data[:6] in (b"GIF87a", b"GIF89a"):
        return "image/gif"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"
    if data[:5] == b"%PDF-":
        return "application/pdf"
    if data[:4] == b"PK\x03\x04":
        try:
            with zipfile.ZipFile(io.BytesIO(data)) as z:
                if "word/document.xml" in z.namelist():
                    return DOCX
        except zipfile.BadZipFile:
            pass
        return "application/zip"
    if not data:
        return "application/octet-stream"
    if decode_text(data) is not None:
        return "text/plain"
    return "application/octet-stream"


def image_size(data: bytes) -> tuple[int, int] | None:
    """(width, height) for PNG, GIF, JPEG and WebP, read from the header only."""
    try:
        if data[:8] == b"\x89PNG\r\n\x1a\n":
            return struct.unpack(">II", data[16:24])
        if data[:6] in (b"GIF87a", b"GIF89a"):
            return struct.unpack("<HH", data[6:10])
        if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
            kind = data[12:16]
            if kind == b"VP8 ":
                w, h = struct.unpack("<HH", data[26:30])
                return w & 0x3FFF, h & 0x3FFF
            if kind == b"VP8L":
                b = data[21:25]
                w = 1 + (((b[1] & 0x3F) << 8) | b[0])
                h = 1 + (((b[3] & 0x0F) << 10) | (b[2] << 2) | ((b[1] & 0xC0) >> 6))
                return w, h
            if kind == b"VP8X":
                w = 1 + int.from_bytes(data[24:27], "little")
                h = 1 + int.from_bytes(data[27:30], "little")
                return w, h
            return None
        if data[:3] == b"\xff\xd8\xff":
            i = 2
            n = len(data)
            while i + 9 < n:
                if data[i] != 0xFF:
                    i += 1
                    continue
                marker = data[i + 1]
                if marker in (0xD8, 0x01) or 0xD0 <= marker <= 0xD7:
                    i += 2
                    continue
                seg = struct.unpack(">H", data[i + 2:i + 4])[0]
                if marker in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
                    h, w = struct.unpack(">HH", data[i + 5:i + 9])
                    return w, h
                i += 2 + seg
    except (struct.error, IndexError):
        return None
    return None


_W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"


def docx_text(data: bytes, limit: int = 4 * 1024 * 1024) -> str | None:
    """Paragraph text of a .docx (word/document.xml), one paragraph per line. Deterministic."""
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            info = z.getinfo("word/document.xml")
            if info.file_size > limit:
                return None
            xml = z.read("word/document.xml")
        root = ET.fromstring(xml)
    except (zipfile.BadZipFile, KeyError, ET.ParseError):
        return None
    paras = []
    for p in root.iter(_W + "p"):
        paras.append("".join(t.text or "" for t in p.iter(_W + "t")))
    return "\n".join(paras)


_EXT_RE = re.compile(r"\.[A-Za-z0-9]{1,10}$")


def extension(filename: str | None) -> str:
    """Lower-case extension like '.kt', or '' (only the final name part is used, never a path)."""
    if not filename:
        return ""
    name = filename.replace("\\", "/").rsplit("/", 1)[-1]
    m = _EXT_RE.search(name)
    return m.group(0).lower() if m else ""
