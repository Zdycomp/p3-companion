"""The scan catalog, in its fixed order.

Every scan module defines:
  ID, BASE_VERSION, DETERMINISTIC, NEEDS_KEY, KIND ("offline" | "ai" | "subprocess"),
  TITLE, ACCEPTS (words), FILES (vendored files it depends on),
  accepts(item, cfg) -> bool, run(item, cfg) -> Result, and optionally cfg_key(item, cfg) -> dict.

A scan's VERSION is BASE_VERSION plus a short hash of its own source and the vendored files it uses,
so editing any of them is a new cache key, never a silent overwrite.
"""

from __future__ import annotations

import hashlib
import os
import re
from dataclasses import dataclass, field

from ..util import DOCX, canon, decode_text, docx_text, extension, sha256_hex

HERE = os.path.dirname(os.path.abspath(__file__))
VENDOR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "vendor")
PROMPTS = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "prompts")

STATES = ("ok", "n/a", "unknown", "error")


@dataclass
class Result:
    state: str
    value: int | None = None          # risk 0 to 100 (0 = nothing found)
    confidence: int | None = None     # 0 to 256
    output: dict = field(default_factory=dict)
    note: str = ""
    transient: bool = False           # never cached (missing key, crash, timeout)
    child_text: str | None = None     # image.read: text that becomes a new blob

    def body(self) -> dict:
        return {
            "state": self.state,
            "value": self.value,
            "confidence": self.confidence,
            "output": self.output,
            "note": self.note,
        }

    def canonical(self) -> str:
        return canon(self.body())


def na(note: str = "not this kind of input") -> Result:
    return Result("n/a", None, None, {}, note)


def unknown(note: str, output: dict | None = None, transient: bool = True) -> Result:
    return Result("unknown", None, 0, output or {}, note, transient=transient)


def error(note: str) -> Result:
    return Result("error", None, 0, {}, note, transient=True)


# ---------------------------------------------------------------- input item

CODE_EXT = {
    ".py": "python", ".js": "javascript", ".mjs": "javascript",
    ".go": "go", ".rs": "rust", ".java": "java", ".kt": "kotlin",
}
CODE_LANGS = ("python", "javascript", "go", "rust", "java", "kotlin")

_DNA = re.compile(r"[ACGTacgt\s]+")


@dataclass
class Item:
    data: bytes
    sha: str
    type: str
    filename: str = ""
    lang: str = ""                  # code language hint from the client
    parent: str = ""                # parent blob sha for derived text
    prior: dict = field(default_factory=dict)   # scan id -> Result, same blob, this job
    _text: object = None

    @property
    def ext(self) -> str:
        return extension(self.filename)

    @property
    def text(self) -> str | None:
        """UTF-8 text, or the paragraphs of a .docx. None for images and other binary."""
        if self._text is None:
            if self.type == DOCX:
                t = docx_text(self.data)
            elif self.type.startswith("text/"):
                t = decode_text(self.data)
            else:
                t = None
            self._text = t if t is not None else False
        return self._text or None

    # kinds -------------------------------------------------------------
    @property
    def is_image(self) -> bool:
        return self.type.startswith("image/")

    @property
    def code_lang(self) -> str:
        if self.type != "text/plain":
            return ""
        if self.lang in CODE_LANGS:
            return self.lang
        return CODE_EXT.get(self.ext, "")

    @property
    def is_ever(self) -> bool:
        return self.type == "text/plain" and self.ext == ".ever"

    @property
    def is_accord(self) -> bool:
        return self.type == "text/plain" and self.ext == ".accord"

    @property
    def is_dna(self) -> bool:
        t = self.text if self.type == "text/plain" else None
        if not t or not _DNA.fullmatch(t):
            return False
        return sum(1 for c in t if not c.isspace()) >= 12

    @property
    def is_build_log(self) -> bool:
        t = self.text if self.type == "text/plain" else None
        return bool(t) and any(k in t for k in ("FAILURE:", "BUILD FAILED", "BUILD SUCCESSFUL", "> Task :"))

    @property
    def is_prose(self) -> bool:
        """Human text: a document, a message, extracted photo text. Not code, DNA or a build log."""
        if self.type == DOCX:
            return bool(self.text and self.text.strip())
        if self.type != "text/plain" or not (self.text and self.text.strip()):
            return False
        return not (self.code_lang or self.is_ever or self.is_accord or self.is_dna or self.is_build_log)


# ---------------------------------------------------------------- registry

from . import (  # noqa: E402  (catalog order is this import order, and the table below)
    accord_check,
    blob_fingerprint,
    code_edapt,
    dna_binary,
    dna_sequence,
    ever_heal,
    ever_run,
    fineprint_ai,
    fineprint_lexicon,
    image_read,
    jargonary_ai,
    refix_gradle,
)

CATALOG = [
    blob_fingerprint,   # 1
    refix_gradle,       # 2
    dna_sequence,       # 3
    dna_binary,         # 4
    code_edapt,         # 5
    ever_heal,          # 6
    ever_run,           # 7
    accord_check,       # 8
    fineprint_lexicon,  # 9
    fineprint_ai,       # 10
    jargonary_ai,       # 11
    image_read,         # 12
]
BY_ID = {m.ID: m for m in CATALOG}
TEXT_SCANS = ("fineprint.lexicon", "fineprint.ai", "jargonary.ai")   # run again on image.read text


def _hash_files(paths: list[str]) -> str:
    h = hashlib.sha256()
    for p in paths:
        with open(p, "rb") as f:
            h.update(sha256_hex(f.read()).encode())
    return h.hexdigest()


def version_of(mod) -> str:
    files = [mod.__file__] + [os.path.join(VENDOR, f) for f in getattr(mod, "FILES", ())] + \
        [os.path.join(PROMPTS, f) for f in getattr(mod, "PROMPT_FILES", ())] + \
        [os.path.join(HERE, f) for f in getattr(mod, "EXTRA_FILES", ())]
    return f"{mod.BASE_VERSION}+{_hash_files(files)[:8]}"


VERSIONS = {m.ID: version_of(m) for m in CATALOG}
CATALOG_VERSION = sha256_hex(",".join(f"{m.ID}@{VERSIONS[m.ID]}" for m in CATALOG).encode())[:12]


def cfg_hash(mod, item: Item, cfg) -> str:
    key = mod.cfg_key(item, cfg) if hasattr(mod, "cfg_key") else {}
    return sha256_hex(canon(key).encode())[:16]


def order(ids) -> list[str]:
    """Requested ids in catalog order (never request order). 'all' is the whole catalog."""
    if ids == "all" or ids == ["all"]:
        return [m.ID for m in CATALOG]
    wanted = set(ids)
    return [m.ID for m in CATALOG if m.ID in wanted]
