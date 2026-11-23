"""#3 dna.sequence: everlang DnaSequencer.run() (vendored), serialized to plain JSON.

Exact structural scan: confidence 256 whether the strand parses or is reported invalid.
"""

from __future__ import annotations

from . import Result, na

ID = "dna.sequence"
BASE_VERSION = "1"
TITLE = "DNA sequencer"
ACCEPTS = "DNA text: only A, C, G, T and spaces, at least 12 bases"
KIND = "offline"
DETERMINISTIC = True
NEEDS_KEY = False
FILES = ("everlang/biocomputing/sequencer.py",)
MAX_CODONS_SHOWN = 2000


def accepts(item, cfg) -> bool:
    return item.is_dna and len(item.data) <= cfg.max_code


def serialize(result: dict) -> dict:
    codons = result["codons"]
    shown = codons[:MAX_CODONS_SHOWN]
    return {
        "length": len(result["pairs"]),
        "codon_count": len(codons),
        "codons": [{"bases": "".join(p.base for p in c.pairs),
                    "complement": "".join(p.complement or "?" for p in c.pairs),
                    "start": c.start_index} for c in shown],
        "codons_truncated": len(codons) > len(shown),
        "trailing_partial": "".join(p.base for p in result["trailing_partial"]),
        "lexer_errors": [{"base": t.base, "index": t.index} for t in result["lexer_errors"][:100]],
        "parser_errors": [{"base": t.base, "index": t.index} for t in result["parser_errors"][:100]],
        "error_count": len(result["lexer_errors"]),
        "gc_content": result["gc_content"],
        "valid": result["valid"],
    }


def run(item, cfg) -> Result:
    from ..vendor.everlang.biocomputing.sequencer import DnaSequencer, DnaSyntaxError
    text = item.text
    if text is None:
        return na("not text")
    try:
        out = serialize(DnaSequencer().run(text))
    except DnaSyntaxError as exc:
        return Result("ok", 30, 256, {"valid": False, "error": str(exc)}, "empty sequence")
    if out["valid"]:
        return Result("ok", 0, 256, out, f"{out['codon_count']} codons, GC {out['gc_content']}")
    return Result("ok", 30, 256, out, f"invalid strand: {out['error_count']} non-ACGT bases")
