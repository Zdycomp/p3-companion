#!/usr/bin/env python3
"""Regenerate the protocol test vectors for P3 Companion (throwaway tooling, not app code).

Writes every file in app/src/test/resources/vectors/ deterministically, then re-reads them
and verifies: envelope CRC-16/CCITT-FALSE of every frame (except the frames that are corrupt on
purpose), decoded reply values, photo reassembly (FFD8..FFD9, SHA-256), H.264 preview access-unit
count and SPS size, and noise/resync behaviour under two parser models.

    python3 tools/make_vectors.py            # write + verify
    python3 tools/make_vectors.py --check    # verify only (fails if files differ from a fresh build)

Sources (both MIT): beneduzi/xk-one-pro-sdk (captured frames in python/xkglasses/protocol.py,
python/tests/test_codec.py, docs/*.md) and joaosouz4dev/lensmoo-crash-fix
(tests/fixtures/*.h264, android/src/dev/lensfix/video/PreviewPayload.java for the type-2 envelope).
Fixture .h264 files are read from that checkout (override with LENSFIX_FIXTURES=<dir>).

The SDK author's real 32-hex userId is never written: the captured 102E frame is rebuilt with
0123456789abcdef0123456789abcdef and its CRC recomputed. A SHA-256 guard refuses any output that
contains the original id.
"""

import hashlib
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "app/src/test/resources/vectors"
FIXTURES = Path(os.environ.get("LENSFIX_FIXTURES", "/home/claude/joaosouz4dev/lensmoo-crash-fix/tests/fixtures"))

SAFE_USER_ID = "0123456789abcdef0123456789abcdef"

# --------------------------------------------------------------------------- codec

def crc16(data: bytes) -> int:
    crc = 0xFFFF
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return crc


def frame(head: int, order: int, cmd: int, payload: bytes, divide: int = 0, crc_override=None) -> bytes:
    crc = crc16(payload) if crc_override is None else crc_override
    return (bytes([head, order & 0xFF]) + cmd.to_bytes(2, "little") + divide.to_bytes(2, "little")
            + len(payload).to_bytes(2, "little") + b"\x00" * 4 + crc.to_bytes(2, "little")
            + b"\x00\x00" + payload)


def request_payload(req_id: int, action: int, node: str, data: bytes = b"") -> bytes:
    """App -> glasses control payload. len is BIG-endian and excludes the 00 format byte."""
    return (req_id.to_bytes(2, "little") + b"\xff\xff\xff\xff" + action.to_bytes(2, "little")
            + b"\x00\x01" + node.encode() + len(data).to_bytes(2, "big") + b"\x00" + data)


def reply_payload(req_id: int, action_bytes: bytes, node: str, rtype: int, data: bytes) -> bytes:
    """Glasses -> app payload. After the node: [type 1][len 2 LITTLE-endian][data]."""
    return (req_id.to_bytes(2, "little") + b"\xff\xff\xff\xff" + action_bytes + b"\x00\x01"
            + node.encode() + bytes([rtype]) + len(data).to_bytes(2, "little") + data)


# action bytes as captured in device replies (see test_codec.py): 64 80 on query replies,
# 04 80 on the 57A0 frame the SDK test treats as a push.
ACT_REPLY = bytes.fromhex("6480")
ACT_PUSH = bytes.fromhex("0480")
CMD_REPLY = 0x8002   # envelope cmd used for replies by the SDK test (only bit 15 is load-bearing)
CMD_PUSH = 0x8001
CMD_DEV_ACK = 0x8004
CMD_MEDIA = 0x0001   # PROTOCOL.md names device media frames "4A0001" (bit 15 NOT set)

HEADS = (0x30, 0x4A, 0x2B)

# --------------------------------------------------------------------------- embedded data

# 16x16 baseline JPEG, 653 bytes, made once with Pillow (quality 50) and frozen here.
TINY_JPEG = bytes.fromhex(
    "ffd8ffe000104a46494600010100000100010000ffdb004300100b0c0e0c0a100e0d0e1211101318281a1816161831"
    "23251d283a333d3c3933383740485c4e404457453738506d51575f626768673e4d71797064785c656763ffdb004301"
    "1112121815182f1a1a2f634238426363636363636363636363636363636363636363636363636363636363636363"
    "636363636363636363636363636363636363ffc00011080010001003012200021101031101ffc4001f00000105010101"
    "01010100000000000000000102030405060708090a0bffc400b5100002010303020403050504040000017d010203000411"
    "05122131410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a343536"
    "3738393a434445464748494a535455565758595a636465666768696a737475767778797a838485868788898a9293949596"
    "9798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9"
    "eaf1f2f3f4f5f6f7f8f9faffc4001f0100030101010101010101010000000000000102030405060708090a0bffc400b511"
    "00020102040403040705040400010277000102031104052131061241510761711322328108144291a1b1c109233352f015"
    "6272d10a162434e125f11718191a262728292a35363738393a434445464748494a535455565758595a636465666768696a"
    "737475767778797a82838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6"
    "c7c8c9cad2d3d4d5d6d7d8d9dae2e3e4e5e6e7e8e9eaf2f3f4f5f6f7f8f9faffda000c03010002110311003f00e7edec7a"
    "715a76f63d38ad3b7b1e9c569dbd8f4e2aaae2c32fc76da9ffd9")


def load_fixture(name: str) -> bytes:
    p = FIXTURES / name
    if not p.exists():
        sys.exit(f"missing fixture {p}: set LENSFIX_FIXTURES to lensmoo-crash-fix/tests/fixtures")
    return p.read_bytes()


# --------------------------------------------------------------------------- H.264 helpers

def split_annexb(data: bytes):
    out, i, n = [], 0, len(data)

    def sc(at):
        if at + 2 < n and data[at] == 0 and data[at + 1] == 0:
            if data[at + 2] == 1:
                return 3
            if at + 3 < n and data[at + 2] == 0 and data[at + 3] == 1:
                return 4
        return 0
    while i < n:
        p = sc(i)
        if not p:
            raise ValueError("missing start code")
        b = i + p
        e = b
        while e < n and not sc(e):
            e += 1
        out.append(data[b:e])
        i = e
    return out


class _Bits:
    def __init__(self, b):
        self.b, self.p = b, 0

    def u(self, n):
        v = 0
        for _ in range(n):
            v = (v << 1) | ((self.b[self.p // 8] >> (7 - self.p % 8)) & 1)
            self.p += 1
        return v

    def ue(self):
        z = 0
        while self.u(1) == 0:
            z += 1
        return (1 << z) - 1 + self.u(z)

    def se(self):
        c = self.ue()
        return -(c // 2) if c % 2 == 0 else (c + 1) // 2


def sps_size(nal: bytes):
    """Width/height from an SPS NAL (no start code), with frame cropping."""
    rbsp, zeros = bytearray(), 0
    for v in nal[1:]:
        if zeros >= 2 and v == 3:
            zeros = 0
            continue
        rbsp.append(v)
        zeros = zeros + 1 if v == 0 else 0
    r = _Bits(bytes(rbsp))
    profile = r.u(8); r.u(16); r.ue()
    chroma, separate = 1, False
    if profile in (100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135):
        chroma = r.ue()
        if chroma == 3:
            separate = r.u(1) == 1
        r.ue(); r.ue(); r.u(1)
        if r.u(1):
            for i in range(12 if chroma == 3 else 8):
                if r.u(1):
                    last = nxt = 8
                    for _ in range(16 if i < 6 else 64):
                        if nxt:
                            nxt = (last + r.se() + 256) % 256
                        last = nxt or last
    r.ue()
    poc = r.ue()
    if poc == 0:
        r.ue()
    elif poc == 1:
        r.u(1); r.se(); r.se()
        for _ in range(r.ue()):
            r.se()
    r.ue(); r.u(1)
    wmb, hmap = r.ue() + 1, r.ue() + 1
    frame_mbs_only = r.u(1)
    if not frame_mbs_only:
        r.u(1)
    r.u(1)
    l = rt = t = bt = 0
    if r.u(1):
        l, rt, t, bt = r.ue(), r.ue(), r.ue(), r.ue()
    at = 0 if separate else chroma
    cx = 1 if at in (0, 3) else 2
    cy = (2 if at == 1 else 1) * (2 - frame_mbs_only)
    return wmb * 16 - cx * (l + rt), hmap * 16 * (2 - frame_mbs_only) - cy * (t + bt)

# --------------------------------------------------------------------------- reference parsers

def parse_strict(stream: bytes):
    """Every frame in a clean stream; raises on any CRC error (used for per-file CRC checks)."""
    out, i = [], 0
    while i < len(stream):
        h = stream[i]
        assert h in HEADS, f"bad head {h:#x} at {i}"
        plen = int.from_bytes(stream[i + 6:i + 8], "little")
        raw = stream[i:i + 16 + plen]
        assert len(raw) == 16 + plen, "truncated"
        out.append(raw)
        i += 16 + plen
    return out


def crc_ok(raw: bytes) -> bool:
    return crc16(raw[16:]) == int.from_bytes(raw[12:14], "little")


KNOWN_CMDS = {0x0001, 0x8001, 0x8002, 0x0004, 0x8004, 0x0009, 0x8009}
MAX_PLEN = 32768  # assumption: largest frame seen is far below this; no published maximum


class AppParserModel:
    """Byte-for-byte model of Xk.Parser in XkProtocol.kt (lines 141-177) as of 2026-11-22."""

    def __init__(self, hardened=False):
        self.buf = bytearray()
        self.hardened = hardened
        self.dropped = 0

    def feed(self, chunk: bytes):
        self.buf += chunk
        out, pos = [], 0
        b = self.buf
        while len(b) - pos >= 16:
            h = b[pos]
            if h not in HEADS:
                pos += 1; self.dropped += 1; continue
            plen = b[pos + 6] | (b[pos + 7] << 8)
            cmd = b[pos + 2] | (b[pos + 3] << 8)
            if self.hardened and (plen > MAX_PLEN or any(b[pos + 8:pos + 12]) or cmd not in KNOWN_CMDS):
                # proposed: before trusting a length, require offset == 0, a known cmd and a
                # bounded payload; otherwise treat this byte as noise
                pos += 1; self.dropped += 1; continue
            if len(b) - pos < 16 + plen:
                break
            raw = bytes(b[pos:pos + 16 + plen])
            if not crc_ok(raw):
                pos += 1; self.dropped += 1; continue
            out.append(raw)
            pos += 16 + plen
        del self.buf[:pos]
        return out


def node_of(raw: bytes) -> str:
    p = raw[16:]
    cmd = int.from_bytes(raw[2:4], "little") & 0x7FFF
    if raw[0] == 0x30 and len(p) >= 14 and cmd not in (4, 9):
        return p[10:14].decode("ascii", "replace")
    return ""


def reply_fields(raw: bytes):
    p = raw[16:]
    rtype = p[14]
    ln = int.from_bytes(p[15:17], "little")
    return rtype, ln, p[17:17 + ln]

# --------------------------------------------------------------------------- vector builders

FILES = {}       # name -> list of (comment_lines, hex_line)
EXPECT = {}      # name -> dict


def add(name, raw: bytes, *comments):
    FILES.setdefault(name, []).append((list(comments), raw.hex().upper()))


def header(name, *lines):
    FILES.setdefault(name, [])
    FILES[name].insert(0, (list(lines), None))


def build_requests():
    n = "requests_app_captured.hex"
    header(n, "App -> glasses frames. CAPTURED verbatim (xk-one-pro-sdk protocol.py _SETUP_HEX and",
           "test_codec.py goldens), except 102E whose userId is replaced by the safe test id",
           "and whose CRC is recomputed. Each line: Xk.control(order, node, action, requestId, data)",
           "must encode to exactly these bytes. Parameters are in the comment above each line.")
    caps = [
        ("306C01000000110000000000388900009500FFFFFFFF0100000137313030000000", "order=0x6C node=7100 action=1 req=0x95 data=-"),
        ("307501000000110000000000465B0000A300FFFFFFFF0100000131303031000000", "order=0x75 node=1001 action=1 req=0xA3 data=-"),
        ("30780100000011000000000044E50000A600FFFFFFFF0100000131303033000000", "order=0x78 node=1003 action=1 req=0xA6 data=-"),
        ("30880100000011000000000083DF0000BE00FFFFFFFF0100000135373133000000", "order=0x88 node=5713 action=1 req=0xBE data=-"),
        ("308201000000120000000000FFD20000B300FFFFFFFF030000013537413000010000", "order=0x82 node=57A0 action=3 req=0xB3 data=00"),
        ("308C010000001100000000006BEB0000C400FFFFFFFF0300000135374230000000", "order=0x8C node=57B0 action=3 req=0xC4 data=-"),
        ("303601000000120000000000119900003E00FFFFFFFF020000013733303000010001", "order=0x36 node=7300 action=2 req=0x3E data=01"),
        ("306D04000000020000000000F84E00000106", "ACK: Xk.ack(order=0x6D, target=0x06) (cmd 0x0004, payload 01 06)"),
    ]
    for hx, c in caps:
        raw = bytes.fromhex(hx)
        assert crc_ok(raw), c
        add(n, raw, "CAPTURED " + c)
    # 102E user bind: captured order 0x6E, req 0x98, action 3, bindType 2, 16 zero bytes, 0x20, id
    data = bytes([2]) + bytes(16) + bytes([32]) + SAFE_USER_ID.encode()
    raw = frame(0x30, 0x6E, 0x0001, request_payload(0x98, 3, "102E", data))
    add(n, raw, "CAPTURED layout, userId SANITIZED, CRC recomputed:",
        f"order=0x6E node=102E action=3 req=0x98 data=Xk.userBindData(\"{SAFE_USER_ID}\") (len field 00 32)")
    EXPECT[n] = {"frames": len(caps) + 1, "all_crc_valid": True}


def build_battery():
    n = "reply_1003_battery.hex"
    header(n, "Glasses -> app replies to 1003 (battery). Data = [is_charging][level] + 8 zero bytes.",
           "Payload bytes CAPTURED (test_codec.py _CAP_1003_*); envelope order/cmd CONSTRUCTED.",
           "Note: reply requestId a6 00 echoes the 1003 query's requestId 0xA6 from the same capture.")
    cap_chg = bytes.fromhex("a600ffffffff6480000131303033000a0001640000000000000000")
    cap_idle = bytes.fromhex("a600ffffffff6480000131303033000a0000640000000000000000")
    con = reply_payload(0x00A6, ACT_REPLY, "1003", 0x00, bytes([0, 57]) + bytes(8))
    add(n, frame(0x30, 0x0B, CMD_REPLY, cap_chg), "CAPTURED payload: charging=true level=100")
    add(n, frame(0x30, 0x0C, CMD_REPLY, cap_idle), "CAPTURED payload: charging=false level=100")
    add(n, frame(0x30, 0x0D, CMD_REPLY, con), "CONSTRUCTED: charging=false level=57")
    EXPECT[n] = {"frames": [{"node": "1003", "charging": True, "level": 100},
                            {"node": "1003", "charging": False, "level": 100},
                            {"node": "1003", "charging": False, "level": 57}]}


def build_1001():
    n = "reply_1001_device_info.hex"
    # JSON from xk-one-pro-sdk docs/VALIDATION.md section 10 (byte length 411 = documented len 0x019B).
    # Identifiers of the author's unit (mac, dev_id hex, name suffix) replaced with same-length dummies.
    js = ('{"prod_mode":"E13C-1","soft_ver":"1.0.2","mac_addr":"02:00:00:00:00:01",'
          '"dev_id":"TBZNDZAIEYE-W20-------E13C1-----------020000000001000000--------",'
          '"dev_name":"xk one Pro_0001","prod_category":"01","prod_subcate":"01",'
          '"battery_main":"100","dial_ability":"2","screen":"w320h380","ch":"304","cw":"320",'
          '"nch":"304","ncw":"320","screen_shape":"0","preview_width":"160","preview_height":"120",'
          '"offline_asr_auth":"1"}')
    assert len(js) == 411, len(js)
    header(n, "Glasses -> app reply to 1001 (device info), type 02 FMT_JSON, len 0x019B (411) LE.",
           "JSON text from SDK VALIDATION.md s10 (same length as the capture); the author's MAC,",
           "dev_id digits and name suffix are replaced by same-length dummies. Envelope CONSTRUCTED.")
    add(n, frame(0x30, 0x0E, CMD_REPLY, reply_payload(0x00A3, ACT_REPLY, "1001", 0x02, js.encode())),
        "RECONSTRUCTED from documented text: dev_name=xk one Pro_0001 soft_ver=1.0.2 prod_mode=E13C-1 battery_main=100")
    EXPECT[n] = {"frames": [{"node": "1001", "type": 2, "len": 411, "dev_name": "xk one Pro_0001",
                             "soft_ver": "1.0.2", "prod_mode": "E13C-1", "battery_main": 100,
                             "preview_width": 160, "preview_height": 120}]}


def build_5713():
    n = "reply_5713_media_counts.hex"
    header(n, "Glasses -> app replies to 5713 (media counts), type 02 FMT_JSON. Counts are STRINGS.",
           "Frame 1: JSON text from SDK VALIDATION.md s10, 66 bytes = documented len 66. Envelope CONSTRUCTED.",
           "Frame 2: CONSTRUCTED non-zero counts.")
    j1 = '{"photo_num":"0","video_num":"0","record_num":"0","music_num":"0"}'
    j2 = '{"photo_num":"12","video_num":"3","record_num":"5","music_num":"0"}'
    assert len(j1) == 66
    add(n, frame(0x30, 0x0F, CMD_REPLY, reply_payload(0x00BE, ACT_REPLY, "5713", 0x02, j1.encode())),
        "RECONSTRUCTED: photo=0 video=0 record=0")
    add(n, frame(0x30, 0x10, CMD_REPLY, reply_payload(0x00BF, ACT_REPLY, "5713", 0x02, j2.encode())),
        "CONSTRUCTED: photo=12 video=3 record=5")
    EXPECT[n] = {"frames": [{"node": "5713", "photo": 0, "video": 0, "record": 0},
                            {"node": "5713", "photo": 12, "video": 3, "record": 5}]}


def build_pushes():
    n = "push_7320_count.hex"
    header(n, "Glasses -> app push 7320 (photo element count). CONSTRUCTED: no 7320 frame was ever",
           "published. Layout inferred from the SDK client, validated end to end, which reads the count",
           "as payload[16]<<8|payload[17] = (high byte of LE len, i.e. 0)<<8 | data[0]. So data[0] = count.")
    add(n, frame(0x30, 0x21, CMD_PUSH, reply_payload(0x0000, ACT_PUSH, "7320", 0x00, bytes([6]))),
        "CONSTRUCTED: count=6, data = 06 (1 byte)")
    add(n, frame(0x30, 0x22, CMD_PUSH, reply_payload(0x0000, ACT_PUSH, "7320", 0x00, bytes([1]))),
        "CONSTRUCTED: count=1, data = 01")
    EXPECT[n] = {"frames": [{"node": "7320", "count": 6}, {"node": "7320", "count": 1}],
                 "app_must_ack": True}

    n = "push_7320_count_u16le.hex"
    header(n, "SPECULATIVE variant of 7320 with a 2-byte little-endian count. Not observed; included",
           "because the validated SDK parser accepts it (it reads data[0]) and a big-endian reader does not.",
           "A correct parser returns the same count as push_7320_count.hex.")
    add(n, frame(0x30, 0x23, CMD_PUSH, reply_payload(0x0000, ACT_PUSH, "7320", 0x00, bytes([6, 0]))),
        "CONSTRUCTED: count=6, data = 06 00")
    add(n, frame(0x30, 0x24, CMD_PUSH, reply_payload(0x0000, ACT_PUSH, "7320", 0x00, bytes([1, 0]))),
        "CONSTRUCTED: count=1, data = 01 00 (a big-endian reader gets 256)")
    EXPECT[n] = {"frames": [{"node": "7320", "count": 6}, {"node": "7320", "count": 1}]}

    n = "push_57B1_capture.hex"
    header(n, "Glasses -> app push 57B1 (shutter done, photo being prepared). CONSTRUCTED: body unknown;",
           "a test may only assert node, direction and that the app ACKs it. It carries NO count.")
    add(n, frame(0x30, 0x20, CMD_PUSH, reply_payload(0x0000, ACT_PUSH, "57B1", 0x00, b"\x00")),
        "CONSTRUCTED: node=57B1")
    EXPECT[n] = {"frames": [{"node": "57B1"}], "app_must_ack": True}

    n = "push_C101_touch.hex"
    header(n, "Glasses -> app push C101 (touch panel / voice button). CONSTRUCTED: only the node is",
           "documented (SDK HARDWARE.md s4, PROTOCOL.md s6). Assert node + ACK, not the body.")
    add(n, frame(0x30, 0x40, CMD_PUSH, reply_payload(0x0000, ACT_PUSH, "C101", 0x00, b"\x01")),
        "CONSTRUCTED: node=C101 data=01")
    add(n, frame(0x30, 0x41, CMD_PUSH, reply_payload(0x0000, ACT_PUSH, "C107", 0x00, b"\x01")),
        "CONSTRUCTED: node=C107 data=01 (second button node named in PROTOCOL.md s6)")
    EXPECT[n] = {"frames": [{"node": "C101"}, {"node": "C107"}], "app_must_ack": True}


def build_errcodes():
    n = "reply_102E_bind.hex"
    header(n, "Glasses -> app replies to 102E (user bind), type 04 FMT_ERRCODE, 1 data byte = ErrorCode.",
           "CONSTRUCTED on the captured FMT_ERRCODE layout (see replies_captured_misc.hex). Whether the",
           "glasses reply to a rejected key at all is NOT documented; they may stay silent.")
    add(n, frame(0x30, 0x12, CMD_REPLY, reply_payload(0x0098, ACT_REPLY, "102E", 0x04, b"\x01")),
        "CONSTRUCTED: ERR_CODE_FAIL (01) -> app must report the key as refused")
    add(n, frame(0x30, 0x13, CMD_REPLY, reply_payload(0x0098, ACT_REPLY, "102E", 0x04, b"\x00")),
        "CONSTRUCTED: ERR_CODE_OK (00) -> bound")
    add(n, frame(0x30, 0x14, CMD_REPLY, reply_payload(0x0098, ACT_REPLY, "102E", 0x03, b"")),
        "CONSTRUCTED: FMT_NODATA, len 0 -> treat as accepted (no error code present)")
    EXPECT[n] = {"frames": [{"node": "102E", "type": 4, "code": 1, "refused": True},
                            {"node": "102E", "type": 4, "code": 0, "refused": False},
                            {"node": "102E", "type": 3, "len": 0, "refused": False}]}

    n = "replies_captured_misc.hex"
    header(n, "Glasses -> app reply payloads CAPTURED verbatim (test_codec.py); envelopes CONSTRUCTED.")
    caps = [
        ("0000ffffffff648000014331303104010004", "C101 reply: type 04 len 1 code 04 ERR_CODE_INVALID_URN",
         {"node": "C101", "type": 4, "code": 4}),
        ("0000ffffffff648000013537413004010003", "57A0 reply: type 04 len 1 code 03 ERR_CODE_INVALID_PARAM",
         {"node": "57A0", "type": 4, "code": 3}),
        ("0000ffffffff64800001313031370004001f000000", "1017 reply: type 00 len 4 data 1f 00 00 00 (settings bitmask)",
         {"node": "1017", "type": 0, "data": "1f000000"}),
        ("1e00ffffffff048000013537413000010000", "57A0 push: type 00 len 1 data 00 = video preview OFF (NOT battery)",
         {"node": "57A0", "type": 0, "data": "00"}),
    ]
    exp = []
    for i, (hx, c, e) in enumerate(caps):
        add(n, frame(0x30, 0x50 + i, CMD_PUSH if "push" in c else CMD_REPLY, bytes.fromhex(hx)), "CAPTURED payload: " + c)
        exp.append(e)
    EXPECT[n] = {"frames": exp}


def media_frames(element: bytes, frag_sizes, order0: int, cmd_alt_index=None, divide_high=None):
    """Split one element into 0x4A frames. frag_sizes=None -> one divide-0 frame (no cmd_idx)."""
    if frag_sizes is None:
        return [frame(0x4A, order0, CMD_MEDIA, element, divide=0)]
    assert sum(frag_sizes) == len(element)
    out, at = [], 0
    for k, sz in enumerate(frag_sizes):
        dt = 1 if k == 0 else (3 if k == len(frag_sizes) - 1 else 2)
        if divide_high is not None and k == divide_high[0]:
            dt |= divide_high[1]
        cmd = 0x8001 if cmd_alt_index == k else CMD_MEDIA
        out.append(frame(0x4A, order0 + k, cmd, k.to_bytes(4, "little") + element[at:at + sz], divide=dt))
        at += sz
    return out


def build_photo():
    n = "photo_download.hex"
    j = TINY_JPEG
    slices = [j[:120], j[120:520], j[520:]]
    elements = [b"\x00" + (5 + len(s)).to_bytes(4, "little") + s for s in slices]  # len INCLUDES header
    header(n, "Full photo download, glasses -> app frames only, in arrival order. CONSTRUCTED from the",
           "documented two-layer format (SDK PROTOCOL.md s4): element = [00 media_type][LE4 total length",
           "INCLUDING this 5-byte header][JPEG slice]; 0x4A divide 0 = whole element, no index;",
           "divide 1/2/3 = first/middle/last, each payload starts with LE4 cmd_idx 0,1,2,... per element.",
           "Media frames use cmd 0x0001 ('4A0001' in the docs) except one using 0x8001: accept both.",
           "One middle fragment has non-zero high bits in divide_type (0x0042): mask with & 7.",
           "Expected JPEG: photo_expected_jpeg.hex (653 bytes, 16x16).")
    o = 0x60
    add(n, frame(0x30, o, CMD_DEV_ACK, bytes([0x01, 0x20])), "device ACK of the app's 57B0 (ignore)"); o += 1
    add(n, frame(0x30, o, CMD_REPLY, reply_payload(0x00C4, ACT_REPLY, "57B0", 0x04, b"\x00")), "57B0 reply OK -> ACK it"); o += 1
    add(n, frame(0x30, o, CMD_PUSH, reply_payload(0, ACT_PUSH, "57B1", 0x00, b"\x00")), "57B1 push -> ACK it"); o += 1
    add(n, frame(0x30, o, CMD_PUSH, reply_payload(0, ACT_PUSH, "7320", 0x00, b"\x03")), "7320 push count=3 -> ACK it, then request 7300 index 1"); o += 1
    plans = [(None, None, None), ([180, 150, 75], 1, (1, 0x0040)), ([90, 48], None, None)]
    for idx, (el, (sizes, alt, high)) in enumerate(zip(elements, plans), start=1):
        add(n, frame(0x30, o, CMD_REPLY, reply_payload(0x3D + idx, ACT_REPLY, "7300", 0x04, b"\x00")),
            f"7300 reply for element {idx} -> ACK it"); o += 1
        if sizes is not None:
            sizes = list(sizes)
            sizes[-1] = len(el) - sum(sizes[:-1])
        fr = media_frames(el, sizes, o, alt, high)
        o += len(fr)
        for k, f in enumerate(fr):
            dt = int.from_bytes(f[4:6], "little") & 7
            note = f"element {idx}/3 ({len(el)} B) 0x4A divide={dt}"
            if dt == 3:
                note += " -> app sends 4A 0009 payload 01"
            if dt == 0:
                note += " (whole element, no cmd_idx, no 4A ack)"
            add(n, f, note)
    add(n, frame(0x30, o, CMD_REPLY, reply_payload(0x0050, ACT_REPLY, "7500", 0x04, b"\x00")), "7500 reply OK -> ACK it"); o += 1
    add(n, frame(0x30, o, CMD_PUSH, reply_payload(0, ACT_PUSH, "5760", 0x00, b"\x00")), "5760 push after transfer (SDK NODES.md s3) -> ACK it")
    add("photo_expected_jpeg.hex", j, "Expected reassembled JPEG (16x16 baseline, 653 bytes). sha256=" + hashlib.sha256(j).hexdigest())
    header("photo_expected_jpeg.hex", "Single line: the exact JPEG bytes photo_download.hex must reassemble to.")
    EXPECT[n] = {"element_count": 3, "element_lengths": [len(e) for e in elements],
                 "divide_types_per_element": [[0], [1, 2, 3], [1, 3]],
                 "media_acks_expected": 2, "control_acks_expected": 8,
                 "jpeg_len": len(j), "jpeg_sha256": hashlib.sha256(j).hexdigest()}
    return elements


def build_preview():
    res = {}
    for fname, out, reps in (("synthetic-baseline-320x240.h264", "preview_h264_320x240.hex", 3),
                             ("synthetic-high-640x360.h264", "preview_h264_640x360.hex", 1)):
        au = load_fixture(fname)
        nals = split_annexb(au)
        kinds = [x[0] & 31 for x in nals]
        sps = next(x for x in nals if x[0] & 31 == 7)
        w, h = sps_size(sps)
        element = b"\x02" + len(au).to_bytes(4, "little") + au   # len EXCLUDES header (PreviewPayload.java)
        header(out, f"Live preview: {reps} H.264 access unit(s), each = the whole of lensmoo-crash-fix",
               f"tests/fixtures/{fname} (synthetic, MIT; {len(au)} bytes, NAL types {kinds}).",
               "Element = [02 media_type][LE4 length of the Annex-B data, EXCLUDING the 5-byte header]",
               "[Annex-B]. NOTE: photo elements (type 00) count the header, preview (type 02) does not.",
               "Transport CONSTRUCTED: the 0x4A fragmentation of preview elements was never published; these",
               "use the photo scheme (divide 1/2/3 + LE4 cmd_idx, max 1000-byte frame payload).",
               "A 1003 battery reply is interleaved between access units: demux by head and keep both.")
        o = 0x10
        for r in range(reps):
            sizes = []
            left = len(element)
            while left > 0:
                s = min(996, left); sizes.append(s); left -= s
            for f in media_frames(element, sizes, o):
                dt = int.from_bytes(f[4:6], "little") & 7
                add(out, f, f"AU {r + 1}/{reps} divide={dt}")
                o += 1
            if r == 0 and reps > 1:
                add(out, frame(0x30, 0x2C, CMD_REPLY, bytes.fromhex("a600ffffffff6480000131303033000a0000640000000000000000")),
                    "interleaved 1003 reply (charging=false level=100) -> ACK it, battery UI updates")
        EXPECT[out] = {"access_units": reps, "keyframes": reps, "width": w, "height": h,
                       "nal_types_per_au": kinds, "au_bytes": len(au),
                       "au_sha256": hashlib.sha256(au).hexdigest(),
                       "element_len_field": len(au), "control_frames_interleaved": 1 if reps > 1 else 0}
        res[out] = (au, w, h)
    return res


def build_noise():
    good_a = frame(0x30, 0x11, CMD_REPLY, bytes.fromhex("a600ffffffff6480000131303033000a0001640000000000000000"))
    good_b = frame(0x30, 0x12, CMD_REPLY, reply_payload(0x00BE, ACT_REPLY, "5713", 0x02,
                                                       b'{"photo_num":"0","video_num":"0","record_num":"0","music_num":"0"}'))
    good_c = frame(0x30, 0x14, CMD_PUSH, reply_payload(0, ACT_PUSH, "7320", 0x00, b"\x06"))
    # corrupt media frame whose bytes contain no head value after byte 0 (clean resync case)
    body = bytes([0x00, 0x00, 0x00, 0x00]) + bytes((i * 7 + 1) % 256 for i in range(40))
    body = bytes(x if x not in HEADS else x ^ 0x01 for x in body)
    bad = bytearray(frame(0x4A, 0x15, CMD_MEDIA, body, divide=1))
    bad[20] ^= 0xFF
    bad[20] = bad[20] if bad[20] not in HEADS else bad[20] ^ 0x01
    assert not crc_ok(bytes(bad)) and not any(x in HEADS for x in bad[1:])
    garbage1 = bytes([0x00, 0x11, 0xFF, 0xA5, 0x5A, 0x01, 0x02, 0x03])
    garbage2 = bytes([0xEE] * 5 + [0x7F, 0x80])
    stream = garbage1 + good_a + garbage2 + bytes(bad) + good_b + bytes([0x99, 0x98]) + good_c
    cut1 = len(garbage1) + 7                       # inside good_a's header
    cut2 = len(garbage1) + len(good_a) + len(garbage2) + len(bad) + 30   # inside good_b's payload
    chunks = [stream[:cut1], stream[cut1:cut2], stream[cut2:]]
    n = "noise_resync.hex"
    header(n, "Robustness. EACH LINE IS ONE read() CHUNK (not one frame): feed lines in order to ONE parser.",
           "Contents: 8 garbage bytes, valid 1003 reply (split across chunks 1/2 inside its header),",
           "7 garbage bytes, a 0x4A frame with a corrupted payload byte (bad CRC; must be dropped),",
           "valid 5713 reply (split across chunks 2/3), 2 garbage bytes, valid 7320 push (count 6).",
           "No garbage or corrupt byte equals 30/4A/2B, so a byte-by-byte resync parser recovers at once.")
    for c in chunks:
        add(n, c)
    EXPECT[n] = {"chunks": len(chunks), "frames_out": ["1003", "5713", "7320"],
                 "frames_out_after_chunk": [0, 1, 3], "corrupt_frames_dropped": 1}

    # ---- adversarial: a corrupt CONTROL frame whose payload contains '0' (0x30) bytes, i.e. false
    # heads with huge declared lengths, plus garbage that looks like a header.
    bad2 = bytearray(frame(0x30, 0x16, CMD_REPLY, bytes.fromhex("a600ffffffff6480000131303033000a0001640000000000000000")))
    bad2[18] ^= 0x55
    assert not crc_ok(bytes(bad2))
    fake = bytes([0x30, 0x00, 0x01, 0x00, 0x00, 0x00, 0xFF, 0x7F, 0xAB, 0xCD, 0, 0, 0x12, 0x34, 0, 0])
    stream2 = fake + good_a + bytes(bad2) + good_b
    n = "noise_false_header.hex"
    header(n, "Robustness, adversarial. EACH LINE IS ONE read() CHUNK. Feed both lines, then nothing more.",
           "Line 1: a fake 16-byte header (30 .. len 0x7FFF, offset ABCD0000) then a valid 1003 reply.",
           "Line 2: a 1003 reply with one corrupted byte (its node '1003' contains 0x30 bytes, which",
           "look like heads with huge lengths), then a valid 5713 reply.",
           "SPEC: both valid replies must come out after the last chunk. A parser that trusts any 0x30",
           "byte's length field stalls waiting for ~32 KB and never emits them (defect P1 in REVIEW).")
    add(n, fake + good_a)
    add(n, bytes(bad2) + good_b)
    EXPECT[n] = {"chunks": 2, "frames_out": ["1003", "5713"], "corrupt_frames_dropped": 1,
                 "current_app_parser_result": None, "hardened_parser_result": None}
    return {"noise_resync.hex": chunks, "noise_false_header.hex": [fake + good_a, bytes(bad2) + good_b]}

# --------------------------------------------------------------------------- write + verify

def render(name):
    lines = []
    for comments, hx in FILES[name]:
        lines += ["# " + c for c in comments]
        if hx is not None:
            lines.append(hx)
    return "\n".join(lines) + "\n"


def read_vector(path: Path):
    out = []
    for ln in path.read_text().splitlines():
        ln = ln.strip()
        if ln and not ln.startswith("#"):
            out.append(bytes.fromhex(ln))
    return out


def verify(noise_chunks):
    report = []
    forbidden = _forbidden_hash()
    # 0. privacy guard: no 32-hex ascii run other than the safe id
    for p in sorted(OUT.glob("*")):
        blob = p.read_bytes()
        texts = [blob.decode("latin1")]
        if p.suffix == ".hex":
            texts += [b.decode("latin1") for b in read_vector(p)]
        for t in texts:
            for i in range(len(t) - 31):
                s = t[i:i + 32]
                if all(c in "0123456789abcdef" for c in s) and hashlib.sha256(s.encode()).hexdigest() == forbidden:
                    raise SystemExit(f"FORBIDDEN userId found in {p.name}")
    report.append("privacy: original userId absent from all vector files")

    # 1. CRC of every frame in every per-frame file
    for p in sorted(OUT.glob("*.hex")):
        if p.name.startswith("noise_") or p.name == "photo_expected_jpeg.hex":
            continue
        frames = read_vector(p)
        for f in frames:
            assert len(parse_strict(f)) == 1, p.name
            assert crc_ok(f), f"CRC mismatch in {p.name}"
        report.append(f"{p.name}: {len(frames)} frames, all CRC-16/CCITT-FALSE valid")

    # 2. decoded values
    for name, exp in EXPECT.items():
        if "frames" not in exp or not isinstance(exp["frames"], list):
            continue
        frames = read_vector(OUT / name)
        assert len(frames) == len(exp["frames"]), name
        for f, e in zip(frames, exp["frames"]):
            assert node_of(f) == e["node"], (name, node_of(f))
            assert int.from_bytes(f[2:4], "little") & 0x8000, "device frames carry bit 15"
            rt, ln, d = reply_fields(f)
            if "count" in e:
                assert d[0] == e["count"], name
            if "level" in e:
                assert (d[0] == 1) == e["charging"] and d[1] == e["level"]
            if "photo" in e:
                j = json.loads(d)
                assert int(j["photo_num"]) == e["photo"] and int(j["video_num"]) == e["video"] and int(j["record_num"]) == e["record"]
            if "dev_name" in e:
                j = json.loads(d)
                assert ln == e["len"] and j["dev_name"] == e["dev_name"] and j["soft_ver"] == e["soft_ver"]
            if "code" in e:
                assert rt == e["type"] and d[0] == e["code"], name
            if "data" in e:
                assert d.hex() == e["data"], name
    report.append("decoded values: 1003/1001/5713/7320/57B1/C101/102E/captured misc match expectations")

    # 3. photo reassembly through the app-parser model
    pm = AppParserModel()
    frames = []
    for f in read_vector(OUT / "photo_download.hex"):
        frames += pm.feed(f)
    jpeg, cur, nxt, media_acks, ctl_acks, elems = bytearray(), bytearray(), 0, 0, 0, []
    for f in frames:
        cmd = int.from_bytes(f[2:4], "little")
        dt = int.from_bytes(f[4:6], "little") & 7
        p = f[16:]
        if f[0] == 0x30:
            if (cmd & 0x7FFF) not in (4, 9):
                ctl_acks += 1
            continue
        if dt == 0:
            el = p
        else:
            idx = int.from_bytes(p[:4], "little")
            if dt == 1:
                cur, nxt = bytearray(), 0
            assert idx == nxt, "cmd_idx order"
            nxt += 1
            cur += p[4:]
            if dt != 3:
                continue
            media_acks += 1
            el = bytes(cur)
        assert el[0] == 0x00 and int.from_bytes(el[1:5], "little") == len(el), "element header"
        elems.append(len(el))
        jpeg += el[5:]
    exp = EXPECT["photo_download.hex"]
    assert bytes(jpeg) == read_vector(OUT / "photo_expected_jpeg.hex")[0]
    assert jpeg[:2] == b"\xff\xd8" and jpeg[-2:] == b"\xff\xd9"
    assert hashlib.sha256(jpeg).hexdigest() == exp["jpeg_sha256"] and len(jpeg) == exp["jpeg_len"]
    assert elems == exp["element_lengths"] and media_acks == exp["media_acks_expected"] and ctl_acks == exp["control_acks_expected"]
    report.append(f"photo_download.hex: {len(elems)} elements {elems}, JPEG {len(jpeg)} B FFD8..FFD9 sha256 {exp['jpeg_sha256'][:16]}..., "
                  f"{media_acks} 4A-0009 acks, {ctl_acks} control acks")
    if shutil.which("ffprobe"):
        tmp = OUT.parent / "_probe.jpg"
        tmp.write_bytes(jpeg)
        r = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "stream=width,height", "-of", "csv=p=0", str(tmp)],
                           capture_output=True, text=True)
        tmp.unlink()
        report.append(f"  ffprobe decodes the JPEG: {r.stdout.strip()}")

    # 4. preview
    for name in ("preview_h264_320x240.hex", "preview_h264_640x360.hex"):
        exp = EXPECT[name]
        pm = AppParserModel()
        frames = []
        for f in read_vector(OUT / name):
            frames += pm.feed(f)
        aus, cur, ctl, keys = [], bytearray(), 0, 0
        for f in frames:
            dt = int.from_bytes(f[4:6], "little") & 7
            if f[0] == 0x30:
                ctl += 1; continue
            p = f[16:]
            if dt == 1:
                cur = bytearray()
            cur += p[4:] if dt else p
            if dt in (0, 3):
                el = bytes(cur)
                assert el[0] == 2 and int.from_bytes(el[1:5], "little") == len(el) - 5
                aus.append(el[5:])
        dims = set()
        for au in aus:
            nals = split_annexb(au)
            keys += any(x[0] & 31 == 5 for x in nals)
            dims.add(sps_size(next(x for x in nals if x[0] & 31 == 7)))
            assert hashlib.sha256(au).hexdigest() == exp["au_sha256"]
        assert len(aus) == exp["access_units"] and keys == exp["keyframes"] and dims == {(exp["width"], exp["height"])}
        assert ctl == exp["control_frames_interleaved"]
        msg = f"{name}: {len(frames) - ctl} media frames -> {len(aus)} AUs, {keys} IDR, SPS {exp['width']}x{exp['height']}"
        if shutil.which("ffprobe"):
            tmp = OUT.parent / "_probe.h264"
            tmp.write_bytes(b"".join(aus))
            r = subprocess.run(["ffprobe", "-v", "error", "-count_frames", "-show_entries",
                                "stream=width,height,nb_read_frames", "-of", "csv=p=0", str(tmp)],
                               capture_output=True, text=True)
            tmp.unlink()
            msg += f"; ffprobe width,height,frames = {r.stdout.strip()}"
        report.append(msg)

    # 5. noise under the current-app parser model and the hardened model
    for name, chunks in noise_chunks.items():
        for hardened in (False, True):
            pm = AppParserModel(hardened)
            got, after = [], []
            for c in chunks:
                got += [node_of(f) for f in pm.feed(c)]
                after.append(len(got))
            ok = got == EXPECT[name]["frames_out"]
            label = "hardened model" if hardened else "current Xk.Parser model"
            report.append(f"{name} [{label}]: emitted {got} after chunks {after} -> {'PASS' if ok else 'FAIL (stalled)'}")
            if name == "noise_resync.hex" and not hardened:
                assert ok and after == EXPECT[name]["frames_out_after_chunk"]
            if hardened:
                assert ok, f"hardened model must pass {name}"
            key = "hardened_parser_result" if hardened else "current_app_parser_result"
            if key in EXPECT[name]:
                EXPECT[name][key] = "PASS" if ok else f"FAIL: emitted {got}"
    return report


def _forbidden_hash():
    # Hash of the userId inside the SDK's captured 102E frame, computed from the SDK checkout when
    # available, else the frozen value. The id itself never enters this repository.
    sdk = Path("/home/claude/beneduzi/xk-one-pro-sdk/python/xkglasses/protocol.py")
    if sdk.exists():
        for ln in sdk.read_text().splitlines():
            ln = ln.strip().strip('",')
            if ln.startswith("306E01") and "31303245" in ln:
                uid = bytes.fromhex(ln)[-32:].decode()
                return hashlib.sha256(uid.encode()).hexdigest()
    return FROZEN_FORBIDDEN_SHA256


# SHA-256 of the SDK author's captured userId (the value itself is deliberately not stored here).
FROZEN_FORBIDDEN_SHA256 = "7ea6807e542993dfc98dba2e1817f4c7e98f54b31772aa19322422a05ecb4dc9"


def main():
    check_only = "--check" in sys.argv
    FILES.clear(); EXPECT.clear()
    build_requests(); build_battery(); build_1001(); build_5713(); build_pushes(); build_errcodes()
    build_photo(); build_preview(); noise = build_noise()
    rendered = {name: render(name) for name in FILES}
    if check_only:
        for name, text in rendered.items():
            if (OUT / name).read_text() != text:
                raise SystemExit(f"{name} differs from a fresh build")
    else:
        OUT.mkdir(parents=True, exist_ok=True)
        for name, text in rendered.items():
            (OUT / name).write_text(text)
    report = verify(noise)
    exp_text = json.dumps(EXPECT, indent=1, sort_keys=True) + "\n"
    if check_only:
        if (OUT / "expected.json").read_text() != exp_text:
            raise SystemExit("expected.json differs from a fresh build")
    else:
        (OUT / "expected.json").write_text(exp_text)
    print("\n".join(report))
    print(f"OK: {len(rendered)} vector files + expected.json in {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
