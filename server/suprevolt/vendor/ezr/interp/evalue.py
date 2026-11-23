"""
evalue.py — Ever / Tapestry, Python-side unified variant type

The same type system as evalue.h, expressed in Python.
A Python EValue maps 1-to-1 with the C EValue struct, so the same
value is representable identically in Python, C++, and C without
conversion loss at any boundary.

    SCALAR (inline):  Void, Bool, Int, Real, Text
    COMPOSITE (pool): Blob, List, Record
    UNKNOWN (inline): a value that is not known, with the reason and the
                      source span it came from (see "No null" below)

Every composite lifter produces the same record shape as its C
counterpart, so a SQL row lifted in Python is the same structure as one
lifted in C, and either can be serialised through the ABI layer and
decoded by the other side.

No null crosses this boundary
-----------------------------
A raw null -- Python None, a C NULL pointer, a foreign `null` literal,
an SQL NULL -- says only "nothing here" and loses why. Ever does not let
one through:

  * Python None handed to lift_python, serialise or an EValue's items is
    refused with NullAtBoundary. The caller must say what it means:
    EValue.unknown(reason, span), or EValue.void() for a deliberate unit.
  * Data that *contains* a null (a `null` literal, an SQL NULL) is lifted
    to EValue.unknown with a reason and the Span it was found at.
  * An UNKNOWN carries confidence 0, is never equal to anything (not even
    another UNKNOWN), and asking for its .native raises UnknownValue
    rather than handing back None.

VOID stays what it always was: an explicit unit, "no value, on purpose".
It is never produced from a null any more.

Codric Enterprise · Ricky (Dreid) · 2026
"""

from __future__ import annotations

import math
import struct
from dataclasses import dataclass, field
from enum import IntEnum
from typing import Any, Dict, Iterator, List, Optional, Union

# ─────────────────────────────────────────────
# Type tags — must match ev_type in evalue.h
# ─────────────────────────────────────────────


class EvType(IntEnum):
    VOID = 0
    BOOL = 1
    INT = 2
    REAL = 3
    TEXT = 4
    BLOB = 5
    LIST = 6
    RECORD = 7
    UNKNOWN = 8


EV_INLINE_MAX = 192
EV_FIELD_NAME = 64
EV_CERTAIN = 256
EV_SPAN_SOURCE_MAX = 63  # bytes of a Span's source name on the wire


class NullAtBoundary(ValueError):
    """A raw null tried to cross into or out of EValue."""


class UnknownValue(LookupError):
    """The value was asked for, and it is not known."""

    def __init__(self, v: "EValue"):
        super().__init__(f"value is unknown: {v.ev_reason} at {v.ev_span}")
        self.evalue = v


@dataclass(frozen=True)
class Span:
    """Where an unknown came from: a source name, a 1-based line and
    column (0 where the source has no lines, e.g. a bare literal)."""

    source: str
    line: int = 0
    col: int = 0

    def __post_init__(self) -> None:
        if not isinstance(self.source, str) or not self.source:
            raise ValueError("a Span needs a non-empty source name")
        if not (0 <= self.line < 2**31 and 0 <= self.col < 2**31):
            raise ValueError(f"Span line/col out of range: {self.line}:{self.col}")

    def __str__(self) -> str:
        return f"{self.source}:{self.line}:{self.col}"


# ─────────────────────────────────────────────
# The variant
# ─────────────────────────────────────────────


@dataclass(frozen=True)
class EValue:
    """Unified variant. Immutable — Ever is pure.

    Scalars carry their value directly.
    Composites carry a Python object (bytes / list / dict).
    The ev_tag drives serialisation and cross-language routing.
    """

    ev_tag: EvType
    ev_bool: bool = False
    ev_int: int = 0
    ev_real: float = 0.0
    ev_text: str = ""
    ev_blob: bytes = b""
    ev_list: List["EValue"] = field(default_factory=list)
    ev_record: Dict[str, "EValue"] = field(default_factory=dict)
    confidence: int = EV_CERTAIN
    ev_reason: str = ""
    ev_span: Optional[Span] = None

    def __post_init__(self) -> None:
        if self.ev_tag == EvType.UNKNOWN:
            if not isinstance(self.ev_reason, str) or not self.ev_reason.strip():
                raise NullAtBoundary("an UNKNOWN must say why it is unknown")
            if not isinstance(self.ev_span, Span):
                raise NullAtBoundary("an UNKNOWN must say where it came from (a Span)")
            # Unknown is worth nothing. It does not get to claim otherwise.
            object.__setattr__(self, "confidence", 0)
        if self.ev_tag == EvType.LIST:
            for i, item in enumerate(self.ev_list):
                if not isinstance(item, EValue):
                    raise NullAtBoundary(f"list item {i} is {item!r}, not an EValue")
        if self.ev_tag == EvType.RECORD:
            for k, item in self.ev_record.items():
                if not isinstance(item, EValue):
                    raise NullAtBoundary(f"record field {k!r} is {item!r}, not an EValue")

    # ── constructors ──

    @classmethod
    def unknown(cls, reason: str, span: Span) -> "EValue":
        """A value that is not known, and the reason, and where."""
        return cls(EvType.UNKNOWN, ev_reason=reason, ev_span=span)

    @classmethod
    def void(cls, conf: int = EV_CERTAIN) -> "EValue":
        return cls(EvType.VOID, confidence=conf)

    @classmethod
    def from_bool(cls, v: bool, conf: int = EV_CERTAIN) -> "EValue":
        return cls(EvType.BOOL, ev_bool=bool(v), confidence=conf)

    @classmethod
    def from_int(cls, v: int, conf: int = EV_CERTAIN) -> "EValue":
        return cls(EvType.INT, ev_int=int(v), confidence=conf)

    @classmethod
    def from_real(cls, v: float, conf: int = EV_CERTAIN) -> "EValue":
        return cls(EvType.REAL, ev_real=float(v), confidence=conf)

    @classmethod
    def from_text(cls, v: str, conf: int = EV_CERTAIN) -> "EValue":
        if len(v) < EV_INLINE_MAX:
            return cls(EvType.TEXT, ev_text=v, confidence=conf)
        return cls(EvType.BLOB, ev_blob=v.encode("utf-8"), confidence=conf)

    @classmethod
    def from_blob(cls, v: bytes, conf: int = EV_CERTAIN) -> "EValue":
        return cls(EvType.BLOB, ev_blob=bytes(v), confidence=conf)

    @classmethod
    def from_list(cls, items: List["EValue"], conf: int = EV_CERTAIN) -> "EValue":
        return cls(EvType.LIST, ev_list=list(items), confidence=conf)

    @classmethod
    def from_record(cls, fields: Dict[str, "EValue"], conf: int = EV_CERTAIN) -> "EValue":
        return cls(EvType.RECORD, ev_record=dict(fields), confidence=conf)

    # ── predicates ──

    @property
    def is_void(self) -> bool:
        return self.ev_tag == EvType.VOID

    @property
    def is_unknown(self) -> bool:
        return self.ev_tag == EvType.UNKNOWN

    @property
    def is_scalar(self) -> bool:
        return self.ev_tag <= EvType.TEXT

    @property
    def is_composite(self) -> bool:
        return EvType.BLOB <= self.ev_tag <= EvType.RECORD

    @property
    def type_name(self) -> str:
        return self.ev_tag.name.lower()

    # ── equality — mirrors ev_equal in C ──

    def __eq__(self, other: object) -> bool:
        if not isinstance(other, EValue):
            return NotImplemented
        if self.ev_tag == EvType.UNKNOWN or other.ev_tag == EvType.UNKNOWN:
            # Two unknowns are not known to be equal. Same rule in C.
            return False
        if self.ev_tag != other.ev_tag:
            # INT and REAL interoperate
            if self.ev_tag == EvType.INT and other.ev_tag == EvType.REAL:
                return float(self.ev_int) == other.ev_real
            if self.ev_tag == EvType.REAL and other.ev_tag == EvType.INT:
                return self.ev_real == float(other.ev_int)
            return False
        tag = self.ev_tag
        if tag == EvType.VOID:
            return True
        if tag == EvType.BOOL:
            return self.ev_bool == other.ev_bool
        if tag == EvType.INT:
            return self.ev_int == other.ev_int
        if tag == EvType.REAL:
            return math.isclose(self.ev_real, other.ev_real, rel_tol=1e-12)
        if tag == EvType.TEXT:
            return self.ev_text == other.ev_text
        if tag == EvType.BLOB:
            return self.ev_blob == other.ev_blob
        if tag == EvType.LIST:
            if len(self.ev_list) != len(other.ev_list):
                return False
            return all(a == b for a, b in zip(self.ev_list, other.ev_list))
        if tag == EvType.RECORD:
            if set(self.ev_record) != set(other.ev_record):
                return False
            return all(self.ev_record[k] == other.ev_record[k] for k in self.ev_record)
        return False

    def __hash__(self) -> int:
        return hash(
            (
                self.ev_tag,
                self.ev_int,
                self.ev_real,
                self.ev_text,
                self.ev_blob,
                self.ev_reason,
                self.ev_span,
                tuple(self.ev_list or []),
                tuple(sorted((self.ev_record or {}).items())),
            )
        )

    # ── access ──

    def get(self, key: Union[str, int], default: Optional["EValue"] = None) -> Optional["EValue"]:
        """Uniform field/index access regardless of tag."""
        if self.ev_tag == EvType.RECORD:
            return self.ev_record.get(str(key), default)
        if self.ev_tag == EvType.LIST:
            try:
                return self.ev_list[int(key)]
            except (IndexError, TypeError, ValueError):
                return default
        return default

    def __len__(self) -> int:
        if self.ev_tag == EvType.LIST:
            return len(self.ev_list)
        if self.ev_tag == EvType.RECORD:
            return len(self.ev_record)
        if self.ev_tag == EvType.BLOB:
            return len(self.ev_blob)
        if self.ev_tag == EvType.TEXT:
            return len(self.ev_text or "")
        return 0

    def __iter__(self) -> Iterator["EValue"]:
        if self.ev_tag == EvType.LIST:
            return iter(self.ev_list)
        if self.ev_tag == EvType.RECORD:
            return iter(self.ev_record.values())
        return iter([])

    # ── natural Python value ──

    @property
    def native(self) -> Any:
        """The Python value, without the EValue wrapper.

        An UNKNOWN has no Python value; asking raises UnknownValue rather
        than returning None, which would be the null this type refuses."""
        if self.ev_tag == EvType.UNKNOWN:
            raise UnknownValue(self)
        if self.ev_tag == EvType.VOID:
            return None
        if self.ev_tag == EvType.BOOL:
            return self.ev_bool
        if self.ev_tag == EvType.INT:
            return self.ev_int
        if self.ev_tag == EvType.REAL:
            return self.ev_real
        if self.ev_tag == EvType.TEXT:
            return self.ev_text
        if self.ev_tag == EvType.BLOB:
            return self.ev_blob
        if self.ev_tag == EvType.LIST:
            return [item.native for item in self.ev_list]
        if self.ev_tag == EvType.RECORD:
            return {k: v.native for k, v in self.ev_record.items()}
        return None

    # ── description ──

    def describe(self) -> str:
        if self.ev_tag == EvType.UNKNOWN:
            return f"unknown ({self.ev_reason} at {self.ev_span})"
        if self.ev_tag == EvType.VOID:
            return "nothing"
        if self.ev_tag == EvType.BOOL:
            return "true" if self.ev_bool else "false"
        if self.ev_tag == EvType.INT:
            return str(self.ev_int)
        if self.ev_tag == EvType.REAL:
            return f"{self.ev_real:.6g}"
        if self.ev_tag == EvType.TEXT:
            return f'"{self.ev_text}"'
        if self.ev_tag == EvType.BLOB:
            return f"<blob {len(self.ev_blob)} bytes>"
        if self.ev_tag == EvType.LIST:
            return f"<list {len(self.ev_list)} items>"
        if self.ev_tag == EvType.RECORD:
            keys = ", ".join(list(self.ev_record)[:4])
            return "{" + keys + ("…" if len(self.ev_record) > 4 else "") + "}"
        return "?"

    def __repr__(self) -> str:
        return f"EValue({self.describe()}@{self.confidence}/256)"


# ─────────────────────────────────────────────
# Wire format — must match ev_serialise / ev_deserialise in evalue.c
# ─────────────────────────────────────────────


def serialise(v: EValue) -> bytes:
    """Python EValue → self-describing bytes.
    Same format as the C implementation so either side can decode.

    UNKNOWN: tag, u16 reason length, reason, u8 source length, source,
    i32 line, i32 col (all little-endian). Reason and source share C's
    192-byte inline text, so the reason is cut to fit after the source.
    """
    if v is None:
        raise NullAtBoundary(
            "serialise(None): say EValue.unknown(reason, span) " "or EValue.void()"
        )
    if not isinstance(v, EValue):
        raise TypeError(f"serialise expects an EValue, got {type(v).__name__}")
    tag = v.ev_tag
    if tag == EvType.UNKNOWN:
        span = _span_of(v)
        src, reason = _unknown_wire_parts(v)
        return (
            bytes([EvType.UNKNOWN])
            + struct.pack("<H", len(reason))
            + reason
            + bytes([len(src)])
            + src
            + struct.pack("<ii", span.line, span.col)
        )
    if tag == EvType.VOID:
        return bytes([EvType.VOID])
    if tag == EvType.BOOL:
        return bytes([EvType.BOOL, 1 if v.ev_bool else 0])
    if tag == EvType.INT:
        # struct pack "<q" is signed int64; pass the value directly
        return bytes([EvType.INT]) + struct.pack("<q", v.ev_int)
    if tag == EvType.REAL:
        return bytes([EvType.REAL]) + struct.pack("<d", v.ev_real)
    if tag == EvType.TEXT:
        s = v.ev_text.encode("utf-8")
        return bytes([EvType.TEXT]) + struct.pack("<H", len(s)) + s
    if tag == EvType.BLOB:
        return bytes([EvType.BLOB]) + struct.pack("<i", len(v.ev_blob)) + v.ev_blob
    if tag == EvType.LIST:
        out = bytes([EvType.LIST]) + struct.pack("<i", len(v.ev_list))
        for item in v.ev_list:
            out += serialise(item)
        return out
    if tag == EvType.RECORD:
        out = bytes([EvType.RECORD]) + struct.pack("<i", len(v.ev_record))
        for name, fval in v.ev_record.items():
            nb = name.encode("utf-8")[: EV_FIELD_NAME - 1]
            out += bytes([len(nb)]) + nb + serialise(fval)
        return out
    raise ValueError(f"unknown tag {tag}")


def _cut_utf8(b: bytes, limit: int) -> bytes:
    """At most `limit` bytes, never splitting a UTF-8 character."""
    if len(b) <= limit:
        return b
    return b[:limit].decode("utf-8", errors="ignore").encode("utf-8")


def _span_of(v: EValue) -> Span:
    """An UNKNOWN's span. __post_init__ guarantees one; this says so to
    the type checker instead of trusting it."""
    if v.ev_span is None:
        raise NullAtBoundary("an UNKNOWN without a span reached the wire")
    return v.ev_span


def _unknown_wire_parts(v: EValue) -> "tuple[bytes, bytes]":
    """(source, reason) as bytes, cut so `source NUL reason NUL` fits the
    192-byte inline text the C side stores them in."""
    src = _cut_utf8(_span_of(v).source.encode("utf-8"), EV_SPAN_SOURCE_MAX)
    reason = _cut_utf8(v.ev_reason.encode("utf-8"), EV_INLINE_MAX - len(src) - 2)
    return src, reason


def deserialise(buf: bytes, pos: int = 0) -> tuple:
    """bytes → (EValue, bytes_consumed).
    Same wire format as the C implementation.

    A missing buffer is a raw null and is refused; a tag this side does
    not know is refused too, rather than read as nothing.
    """
    if buf is None:
        raise NullAtBoundary("deserialise(None)")
    if pos >= len(buf):
        raise NullAtBoundary(f"deserialise: nothing to read at offset {pos}")
    try:
        tag = EvType(buf[pos])
    except ValueError:
        raise ValueError(f"unknown tag {buf[pos]} at offset {pos}") from None
    pos += 1
    if tag == EvType.UNKNOWN:
        # Bounded like C's reader. C cannot raise, so it returns an
        # UNKNOWN("truncated ..."); Python refuses the buffer instead.
        start = pos - 1
        try:
            rlen = struct.unpack_from("<H", buf, pos)[0]
            pos += 2
            if pos + rlen + 1 > len(buf):
                raise struct.error("short")
            reason = buf[pos : pos + rlen].decode("utf-8", errors="replace")
            pos += rlen
            slen = buf[pos]
            pos += 1
            if pos + slen + 8 > len(buf):
                raise struct.error("short")
            source = buf[pos : pos + slen].decode("utf-8", errors="replace")
            pos += slen
            line, col = struct.unpack_from("<ii", buf, pos)
            pos += 8
        except struct.error:
            raise ValueError(f"truncated UNKNOWN at offset {start}") from None
        return EValue.unknown(reason, Span(source, line, col)), pos
    if tag == EvType.VOID:
        return EValue.void(), pos
    if tag == EvType.BOOL:
        v = bool(buf[pos])
        pos += 1
        return EValue.from_bool(v), pos
    if tag == EvType.INT:
        n = struct.unpack_from("<q", buf, pos)[0]
        pos += 8
        return EValue.from_int(n), pos
    if tag == EvType.REAL:
        f = struct.unpack_from("<d", buf, pos)[0]
        pos += 8
        return EValue.from_real(f), pos
    if tag == EvType.TEXT:
        slen = struct.unpack_from("<H", buf, pos)[0]
        pos += 2
        s = buf[pos : pos + slen].decode("utf-8", errors="replace")
        pos += slen
        return EValue.from_text(s), pos
    if tag == EvType.BLOB:
        blen = struct.unpack_from("<i", buf, pos)[0]
        pos += 4
        return EValue.from_blob(buf[pos : pos + blen]), pos + blen
    if tag == EvType.LIST:
        count = struct.unpack_from("<i", buf, pos)[0]
        pos += 4
        items = []
        for _ in range(count):
            item, pos = deserialise(buf, pos)
            items.append(item)
        return EValue.from_list(items), pos
    if tag == EvType.RECORD:
        nf = struct.unpack_from("<i", buf, pos)[0]
        pos += 4
        fields = {}
        for _ in range(nf):
            nlen = buf[pos]
            pos += 1
            name = buf[pos : pos + nlen].decode("utf-8", errors="replace")
            pos += nlen
            fval, pos = deserialise(buf, pos)
            fields[name] = fval
        return EValue.from_record(fields), pos
    raise ValueError(f"unknown tag {tag}")


# ─────────────────────────────────────────────
# Lifters — source language → EValue
# ─────────────────────────────────────────────

NULLS = {"null", "nil", "None", "NULL", "undefined", "nullptr", ""}

#: The span a literal gets when its caller does not know where it was.
LITERAL_SPAN = Span("<literal>", 0, 0)


def lift_literal(literal: str, conf: int = 120, span: Optional[Span] = None) -> EValue:
    """Any source language literal → EValue at intake confidence.

    A null spelling in any language becomes UNKNOWN, carrying which
    spelling it was and `span` (pass one when you know where the literal
    was read; the C lifter uses the same default)."""
    if literal is None:
        raise NullAtBoundary("lift_literal(None): a literal must be text")
    s = literal.strip()
    if s in NULLS:
        why = f"null literal {s!r}" if s else "empty literal"
        return EValue.unknown(why, span or LITERAL_SPAN)
    if s in ("true", "True"):
        return EValue.from_bool(True, conf)
    if s in ("false", "False"):
        return EValue.from_bool(False, conf)
    # quoted string
    if len(s) >= 2 and s[0] in ('"', "'") and s[-1] == s[0]:
        return EValue.from_text(s[1:-1], conf)
    # integer
    try:
        return EValue.from_int(int(s), conf)
    except ValueError:
        pass
    # real
    try:
        return EValue.from_real(float(s), conf)
    except ValueError:
        pass
    return EValue.from_text(s, conf)


def lift_sql_row(
    cols: List[str],
    vals: List[Optional[str]],
    conf: int = EV_CERTAIN,
    source: str = "sql",
    row: int = 0,
) -> EValue:
    """SQL column names + values → EValue(RECORD).
    Identical structure to ev_from_sql_row in evalue.c.

    An SQL NULL becomes UNKNOWN("SQL NULL in column 'c'") at
    Span(source, row, column number), column numbers counted from 1.
    """
    fields: Dict[str, EValue] = {}
    for i, (col, val) in enumerate(zip(cols, vals)):
        span = Span(source, row, i + 1)
        if val is None:
            fields[col] = EValue.unknown(f"SQL NULL in column {col!r}", span)
        else:
            fields[col] = lift_literal(val, span=span)
    return EValue.from_record(fields, conf=conf)


def lift_html_element(
    tag: str, attrs: Dict[str, str], children: Optional[List[EValue]] = None, conf: int = EV_CERTAIN
) -> EValue:
    """HTML element → EValue(RECORD) with tag/attrs/children fields.
    Identical structure to ev_from_html_element in evalue.c.
    """
    return EValue.from_record(
        {
            "tag": EValue.from_text(tag),
            "attrs": EValue.from_record({k: EValue.from_text(v) for k, v in attrs.items()}),
            "children": EValue.from_list(children or []),
        },
        conf=conf,
    )


def lift_python(v: Any, conf: int = EV_CERTAIN, path: str = "$") -> EValue:
    """Python native value → EValue.
    This is the bridge from the Ever interpreter's Python values to the
    unified type.

    None is refused with NullAtBoundary, naming where in the structure it
    was (`path`). Python cannot say why a None is there; the caller can,
    with EValue.unknown(reason, span) or EValue.void(). An EValue passes
    through unchanged.
    """
    if v is None:
        raise NullAtBoundary(
            f"None at {path}: say EValue.unknown(reason, span) " f"or EValue.void()"
        )
    if isinstance(v, EValue):
        return v
    if isinstance(v, bool):
        return EValue.from_bool(v, conf)
    if isinstance(v, int):
        return EValue.from_int(v, conf)
    if isinstance(v, float):
        return EValue.from_real(v, conf)
    if isinstance(v, str):
        return EValue.from_text(v, conf)
    if isinstance(v, bytes):
        return EValue.from_blob(v, conf)
    if isinstance(v, list):
        return EValue.from_list(
            [lift_python(x, path=f"{path}[{i}]") for i, x in enumerate(v)], conf
        )
    if isinstance(v, dict):
        return EValue.from_record(
            {str(k): lift_python(vv, path=f"{path}.{k}") for k, vv in v.items()}, conf
        )
    return EValue.from_text(str(v), conf)


# ─────────────────────────────────────────────
# Demo
# ─────────────────────────────────────────────

if __name__ == "__main__":
    print("\n=== EValue — Python unified variant type ===\n")

    cases = [
        ("int", EValue.from_int(42)),
        ("real", EValue.from_real(3.14159)),
        ("bool", EValue.from_bool(True)),
        ("text", EValue.from_text("Codric")),
        ("void", EValue.void()),
    ]
    for name, v in cases:
        buf = serialise(v)
        back, _ = deserialise(buf)
        mark = "✓" if v == back else "✗"
        print(f"  {mark}  {name:6} {v.describe()!r:20} wire={len(buf)}B")

    row = lift_sql_row(["id", "name", "score"], ["1", "Alice", "98.6"])
    print(f"\n  SQL row  : {row.describe()}")
    row_name = row.get("name")
    assert row_name is not None
    print(f"    name   : {row_name.describe()}")
    row_score = row.get("score")
    assert row_score is not None
    print(f"    score  : {row_score.describe()}")

    elem = lift_html_element("h1", {"class": "header", "id": "title"})
    print(f"\n  HTML <h1>: {elem.describe()}")
    elem_attrs = elem.get("attrs")
    assert elem_attrs is not None
    elem_class = elem_attrs.get("class")
    assert elem_class is not None
    print(f"    class  : {elem_class.describe()}")

    lst = lift_python([10, 20, "Codric", True])
    print(f"\n  Python list: {lst.describe()}")
    for i, item in enumerate(lst):
        print(f"    [{i}] {item.describe()}")
    print()
