"""#4 dna.binary: everlang BinarySequencer.sequence() (vendored): any bytes as a DNA strand.

Capped (64 KB, 16 KB on Render) because the strand is four times the bytes.
"""

from __future__ import annotations

from . import Result
from ..util import sha256_hex

ID = "dna.binary"
BASE_VERSION = "1"
TITLE = "Binary to DNA"
ACCEPTS = "any bytes up to the cap"
KIND = "offline"
DETERMINISTIC = True
NEEDS_KEY = False
FILES = ("everlang/biocomputing/binary_sequencer.py", "everlang/biocomputing/quaternary.py",
         "everlang/biocomputing/sequencer.py", "everlang/biocomputing/dna_engine.py",
         "everlang/core/particle.py")
DNA_SHOWN = 4096


def cfg_key(item, cfg) -> dict:
    return {"cap": cfg.dna_binary_cap}


def accepts(item, cfg) -> bool:
    return 0 < len(item.data) <= cfg.dna_binary_cap


def run(item, cfg) -> Result:
    from ..vendor.everlang.biocomputing.binary_sequencer import BinarySequencer
    r = BinarySequencer().sequence(item.data)
    dna = r["dna"]
    out = {
        "byte_count": r["byte_count"],
        "dna": dna[:DNA_SHOWN],
        "dna_length": len(dna),
        "dna_sha256": sha256_hex(dna.encode()),
        "dna_truncated": len(dna) > DNA_SHOWN,
        "codon_count": len(r["codons"]),
        "trailing_partial": "".join(p.base for p in r["trailing_partial"]),
        "gc_content": r["gc_content"],
        "round_trip": r["round_trip"],
    }
    if not r["round_trip"]:
        return Result("ok", 50, 256, out, "strand does not decode back to the same bytes")
    return Result("ok", 0, 256, out, f"{len(dna)} bases, round trip exact")
