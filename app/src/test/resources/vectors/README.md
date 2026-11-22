# Protocol test vectors

Byte-level fixtures for unit tests of `Xk` (XkProtocol.kt) and the frame handling in XkGlasses.kt.
Every file is regenerated and re-verified by `python3 tools/make_vectors.py` (`--check` verifies
without writing and fails if anything differs from a fresh build). Do not hand-edit.

## Format

* `*.hex`: plain text. Lines starting with `#` are comments (the comment above a line describes it).
  Every other line is uppercase hex with no spaces.
* In every file except `noise_*.hex` and `photo_expected_jpeg.hex`, **one line = one complete frame**
  (16-byte envelope + payload) with a valid CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF, payload
  only, little-endian at bytes 12..13).
* In `noise_*.hex`, **one line = one `read()` chunk**. Feed the lines, in order, to ONE parser.
* `expected.json` holds the same expectations as below in machine-readable form.
* Load in a JVM test with `javaClass.classLoader!!.getResource("vectors/<name>")!!.readText()`.

Provenance labels used in the comments:

* **CAPTURED**: bytes from a real session published in xk-one-pro-sdk (MIT).
* **RECONSTRUCTED**: rebuilt from text the SDK docs quote verbatim, with a matching documented length.
* **CONSTRUCTED**: built from the documented layout; no capture of this frame was ever published.
  Assert only what the comment says is known.

Privacy: the SDK author's real 32-hex userId appears nowhere. The captured `102E` frame uses
`0123456789abcdef0123456789abcdef` with its CRC recomputed; the script refuses to write any file
containing the original id (checked by SHA-256). The 1001 JSON has the author's MAC, `dev_id`
digits and name suffix replaced by same-length dummies.

## Layout reminders (where tests usually go wrong)

| Thing | Rule |
|---|---|
| Request length field (`payload[14..15]`) | **big-endian**, counts data only (not the `00` format byte) |
| Reply/push after the node | `[type 1][len 2 **little-endian**][data]`; data starts at `payload[17]` |
| Reply `type` | 00 binary, 02 JSON, 03 no data, 04 error code (1 byte: 00 OK, 01 FAIL, 03 INVALID_PARAM, 04 INVALID_URN) |
| Direction | test `cmd & 0x8000` only; device replies/pushes use 0x8002/0x8001 here, device ACKs 0x8004 |
| Media frames (0x4A) | `cmd` 0x0001 per the docs' name "4A0001" (bit 15 NOT set) - never drop media by direction |
| `divide_type` | fragment kind is `& 0x07`; higher bits may be non-zero |
| Photo element | `[00][LE4 total length INCLUDING the 5-byte header][JPEG slice]` |
| Preview element | `[02][LE4 length EXCLUDING the 5-byte header][Annex-B H.264]` (LensFix PreviewPayload.java) |
| Reply requestId | captured replies echo the request's requestId (1003 query 0xA6 -> reply `A6 00`) |

## Files and what a test must assert

### Encoding (app -> glasses)

**requests_app_captured.hex** (9 frames). Each comment gives `order, node, action, requestId, data`.
Assert `Xk.control(order, node, action, requestId, data).encode()` equals the line byte for byte
(7100, 1001, 1003, 5713, 57A0, 57B0, 7300 are CAPTURED), `Xk.ack(0x6D, 0x06)` equals the ACK line,
and `Xk.control(0x6E, "102E", 3, 0x98, Xk.userBindData("0123456789abcdef0123456789abcdef"))` equals
the last line.

### Device replies and pushes

| File | Frames | Assert |
|---|---|---|
| reply_1003_battery.hex | 3 | node `1003`, type 0; (charging, level) = (true,100) CAPTURED, (false,100) CAPTURED, (false,57) |
| reply_1001_device_info.hex | 1 | node `1001`, type 2, len 411; JSON `dev_name`="xk one Pro_0001", `soft_ver`="1.0.2", `prod_mode`="E13C-1", `battery_main`="100" (a string) |
| reply_5713_media_counts.hex | 2 | node `5713`, type 2; photo/video/record = 0/0/0, then 12/3/5 (values are JSON strings) |
| push_7320_count.hex | 2 | node `7320`; element count 6, then 1; app sends one ACK `[01, rxOrder]` per frame |
| push_7320_count_u16le.hex | 2 | SPECULATIVE 2-byte LE count `06 00` / `01 00`; a correct parser still returns 6 and 1 |
| push_57B1_capture.hex | 1 | node `57B1`, from device, app ACKs it. Carries no count: do not read one |
| push_C101_touch.hex | 2 | nodes `C101`, `C107`; app ACKs both; C101 should reach the voice-chat trigger |
| reply_102E_bind.hex | 3 | type 4 code 1 -> key refused; type 4 code 0 -> bound; type 3 len 0 -> bound |
| replies_captured_misc.hex | 4 | CAPTURED: C101 type 4 code 4 (INVALID_URN); 57A0 type 4 code 3; 1017 type 0 data `1F 00 00 00`; 57A0 type 0 data `00` = preview off. The last one must NOT change the battery |

Every device frame: `fromDevice == true`, and the app answers each non-ACK `0x30` frame with exactly
one `Xk.ack(anyOrder, rxOrder)`, i.e. payload `01 <rxOrder>`, where rxOrder is the received frame's
byte 1 (not the app's own counter).

### photo_download.hex (photo, 15 frames, arrival order)

Glasses -> app frames for one full download of N = 3 elements: device ACK (ignore), `57B0` reply,
`57B1`, `7320` count 3, then per element a `7300` reply followed by its 0x4A frames, then `7500`
reply and a `5760` push.

| Element | 0x4A frames (divide) | Element bytes incl. 5-byte header |
|---|---|---|
| 1 | `0` (whole, no cmd_idx) | 125 |
| 2 | `1, 2, 3` (cmd_idx 0,1,2; the `2` frame has divide 0x0042 and cmd 0x8001) | 405 |
| 3 | `1, 3` (cmd_idx 0,1) | 138 |

Assert:
1. Reassembly gives exactly the bytes in **photo_expected_jpeg.hex**: 653 bytes, starts `FF D8`,
   ends `FF D9`, SHA-256 `e6a5d4a899019014239ff34556a7685b860222f8a9be69de4a80a592caaffba5`
   (a 16x16 baseline JPEG; ffprobe decodes it).
2. Each element's header: byte 0 = `00`, LE4 at bytes 1..4 = the element's own total size.
3. Exactly **2** media ACKs (`4A`, cmd 0x0009, payload `01`): one after each divide-3 frame, none
   after the divide-0 frame.
4. Exactly **8** control ACKs (57B0, 57B1, 7320, three 7300 replies, 7500, 5760); the device ACK
   frame gets none.
5. App TX in between (not in the file): `7300` action 2 data `[i]` for i = 1, 2, 3 in order, then
   `7500` action 3. Elements are requested one at a time.

### Live preview

| File | Media frames | Expect |
|---|---|---|
| preview_h264_320x240.hex | 21 (+1 interleaved 1003 reply) | **3** access units, all IDR, NAL types per AU `[7, 8, 6, 5]` (SPS, PPS, SEI, IDR), SPS **320x240**, each AU 6005 bytes, SHA-256 `e17cc9f3...d8b430`; the 1003 reply between AU 1 and AU 2 still parses (charging false, level 100) |
| preview_h264_640x360.hex | 11 | **1** access unit, IDR, SPS **640x360** (high profile; coded height 368 cropped to 360), 10049 bytes, SHA-256 `1786f718...cd17941` |

Built from lensmoo-crash-fix `tests/fixtures/*.h264` (synthetic test pattern, MIT). Each AU is one
element `[02][LE4 len][Annex-B]`, carried in 0x4A frames with divide 1/2/3 and LE4 cmd_idx, frame
payloads of at most 1000 bytes. **The element envelope is documented; the 0x4A fragmentation of
preview elements is NOT** (LensFix published only the unwrapped envelope, and the SDK names a
`divideType == 5` path for video). Treat the transport part as a hypothesis to confirm with the
connection log on real glasses. A preview element must never be fed to the photo assembler:
route by element byte 0 (`00` photo, `02` H.264).

### Noise and resync (chunk files)

**noise_resync.hex** (3 chunks): 8 garbage bytes, valid 1003 reply split inside its header,
7 garbage bytes, a 0x4A frame with one corrupted payload byte (bad CRC), valid 5713 reply split
inside its payload, 2 garbage bytes, valid 7320 push. Assert the parser emits exactly
`1003, 5713, 7320`, in that order, with 0 frames after chunk 1, 1 after chunk 2 and 3 after chunk 3,
and drops the corrupt frame without throwing. **Current `Xk.Parser` passes** (checked by running the
real Kotlin class).

**noise_false_header.hex** (2 chunks, adversarial): chunk 1 = a fake header (`30`, cmd 0x0001,
len 0x7FFF, non-zero offset) followed by a valid 1003 reply; chunk 2 = a 1003 reply with one
corrupted byte (its ASCII node `1003` contains `0x30` bytes that look like heads with lengths of
~25 KB) followed by a valid 5713 reply. Spec: after both chunks the parser has emitted `1003, 5713`.
**Current `Xk.Parser` emits nothing** (it waits for 32 KB that never comes; checked on the real
Kotlin class). A parser that rejects a candidate header unless offset (bytes 8..11) is zero, cmd is
one of 0x0001/0x8001/0x8002/0x0004/0x8004/0x0009/0x8009 and length <= 32768 passes (modelled in the
script). See REVIEW-protocol.md P1. Mark this test `@Ignore` until the parser is hardened, or
make it the regression test for that fix.

## Measured results (make_vectors.py run, 2026-11-22)

* All 77 frames in the per-frame files pass CRC; the original userId is absent.
* Photo: 3 elements [125, 405, 138] -> 653-byte JPEG, matches; ffprobe 16x16.
* Preview: 3 AUs 320x240 (ffprobe: 320,240,3 frames); 1 AU 640x360 (ffprobe: 640,360,1 frame).
* Real `Xk.Parser` / `Xk.PhotoAssembler`: noise_resync PASS, photo PASS, noise_false_header FAIL.
* Real `XkGlasses.elementCount` logic on push_7320_count_u16le.hex: `06 00` -> "bad part count"
  error, `01 00` -> 256 parts (see REVIEW-protocol.md P2).
