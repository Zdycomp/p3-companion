# Protocol review: XkGlasses.kt and XkProtocol.kt

Reviewer: Experimental Physicist. Read-only review of the files as of 2026-11-22 17:21
(XkGlasses.kt 468 lines, md5 b06c4bbd...; XkProtocol.kt 230 lines, md5 56f57d8c...). Line numbers
refer to those versions. Evidence comes from xk-one-pro-sdk ("SDK", MIT), lensmoo-crash-fix
("LensFix", MIT) and from running the real `Xk.Parser`, `Xk.PhotoAssembler` and a copy of the
`elementCount` logic against the vectors in `app/src/test/resources/vectors/`.

Severity: **High** = breaks a feature or can hang the glasses; **Med** = fails under plausible
conditions; **Low** = hygiene. Confidence is how sure we are that the defect is real on P3 hardware.

## What is right (keep it)

* Envelope, CRC-16/CCITT-FALSE over the payload, little-endian CRC at 12..13, reserved 14..15 = 0
  (XkProtocol.kt:76-88). Request length big-endian, format byte not counted (110-112). All 9
  captured request frames in `requests_app_captured.hex` re-encode byte for byte.
* `102E` data layout (122-131) matches the capture: `00 32 | 00 | 02 | 16x00 | 20 | id`.
* ACKs reference the **received** order (XkGlasses.kt:399), media ACK `4A/0009/01` only on divide 3
  (394), divide masked with `& 7` (XkProtocol.kt:56). Actions: 1003/1001/5713 READ, 7300 WRITE,
  57B0/7500/102E EXECUTE, bind request ids 1 and 4: all as captured.
* Parser resyncs byte by byte after garbage and bad CRC (`noise_resync.hex` passes on the real class).
* Photo reassembly (184-229) rebuilds `photo_download.hex` exactly (653-byte JPEG, all divide types).

## Defects and risky assumptions

| ID | Sev | Where | Problem | Evidence | Fix | Conf. |
|---|---|---|---|---|---|---|
| P1 | High | XkProtocol.kt:153-159 | Parser trusts the length of any byte equal to 30/4A/2B. A corrupt frame whose payload holds `0x30` (every node name with a `0`, e.g. "1003") makes it wait for up to 64 KB; all later replies stall until that much data arrives, so requests time out on an idle link. | `noise_false_header.hex`: real `Xk.Parser` emits nothing. | Before `break`, reject the candidate (pos++) unless bytes 8..11 are zero, cmd is in {0001, 8001, 8002, 0004, 8004, 0009, 8009} and plen <= 32768. The modelled hardened parser passes both noise files. | High |
| P2 | High | XkGlasses.kt:301-310 | 7320 count read as **big-endian** `data[0]<<8 \| data[1]`. The SDK client, validated end to end, effectively reads `data[0]` (it computes `payload[16]<<8 \| payload[17]` = high byte of the LE length, 0, then data[0]). A 2-byte LE count gives "bad part count" (06 00) or **256 parts** (01 00). Range 1..400 and `payload.last()` fallback are guesses; SDK says 1..20. | `push_7320_count_u16le.hex` through the copied logic: error / 256. SDK client.py `_handle_frame` 7320 branch; NODES.md s2. | `count = data[0]` (or LE16 when len >= 2); accept 1..20; otherwise abort with 7500. | Med (no 7320 frame was ever published) |
| P3 | High | XkGlasses.kt:281-299, 403-423 | Transfers are not always closed. If a download fails part-way (timeout, CRC-dropped fragment, assembler error) no `7500` is sent; if `saveButtonPhotos` is off, a button photo's 7320 is ACKed and then ignored. SDK: "if the host does not complete the transfer ... the glasses stay blocked waiting"; a host must finish or abort with 7500. | SDK NODES.md s3 note. | Send `7500` in a `finally` of `download` on any failure, and immediately after ACKing an unwanted 7320. On a bad fragment, reset the element and re-request that 7300 index once before giving up. | High |
| P4 | Med | XkGlasses.kt:171-175 | Connect **requires** a `102E` reply within 6 s. No source shows the glasses replying to 102E; both validated clients send 102E and never wait. A wrong key shows up as capture failing (SDK VALIDATION s4), not as a documented error reply. If the glasses stay silent, every connect fails with "didn't answer the sign-in step". | SDK client.py `setup()`, flutter `XkGlassesController.connect()`. | Wait up to 2 s; on type 4 code != 0 report "key refused"; on silence continue, then probe with 1003 and only fail if nothing at all answers. Log which case happened. | Med |
| P5 | Med | XkGlasses.kt:167-171 | 600 ms between bind 2 and 102E. Both validated clients wait 1.5 s after the bind pair (client.py `bind`: 0.2+0.1 s per frame then 1.5 s; flutter: 200 ms per frame then 1500 ms). | as cited | Use 200 ms after 0001, then 1500 ms after 0002. | Med |
| P6 | High | XkGlasses.kt:361-385, 188-194, 92-121, 129-157 | Reconnect races. (a) The reader decides "link lost" by `socket != null`, so an old reader that exits after a reconnect assigned a new socket closes the **new** link. (b) The parser (line 68) is shared across links, so stale partial bytes prefix the new stream. (c) `connect` only guards CONNECTING (93); calling it while READY opens a second socket and reader without closing the first. (d) Failed socket attempts are never closed (146-156). (e) Auto-reconnect tries once (381-383); a failure is never retried. | Code reading. | Give each reader its own socket `s` and check `socket === s`; new `Xk.Parser()` per connection; close any existing link at the start of `connect`; close each failed socket; retry with backoff (4, 8, 16, 30, 60 s) until user disconnect. | High |
| P7 | Med | XkGlasses.kt:349-356, 423 | Replies matched by node only. A late reply to a timed-out request satisfies the next request for that node. Captured replies echo the request id (1003 query id 0xA6, reply starts `A6 00`). | `reply_1003_battery.hex` header note; SDK test_codec `_CAP_1003_*` vs `_SETUP_HEX`. | Key pending requests by request id (`payload[0..1]` LE); fall back to node for pushes. | Med |
| P8 | Med | XkProtocol.kt:66-74 | `data` ignores the reply length; `json()` takes the first `{` anywhere in the payload. Replies echo the request id in payload[0..1], so when the id byte is 0x7B (`{`), e.g. the 60th request after 0x40 or any id 0x7Bxx, JSON parsing starts at byte 0 and fails silently (battery/name/counts never update). | Request id echo above; `requestId` starts at 0x40 and counts up (XkGlasses.kt:74, 338). | `data = payload[17 until 17+len]` with `len = LE16(payload[15..16])`; parse JSON from that slice only. | High |
| P9 | Med | XkGlasses.kt:200-227 | `refreshNow` re-asks 1003, 1001, 5713 after every photo/stop with 3 s timeouts on the single `io` thread. The SDK reports "the device answers most nodes once per session"; if P3 firmware does too, each refresh costs 9 s and blocks Take photo. | SDK NODES.md s3 probing note, VALIDATION s12. | Ask 1001 once per connection; 1.5 s timeouts; keep last value and mark it stale. Device test plan step 4 measures this. | Med |
| P10 | Med | XkProtocol.kt:216-228; XkGlasses.kt:391-396 | Elements are not checked: media type byte and declared length are discarded, and `jpeg()` checks FFD8 but not FFD9. A live-preview element (type 02) arriving during a download would be spliced into the photo; a truncated element passes. Note photo length **includes** the 5-byte header, preview length **excludes** it. | SDK PROTOCOL.md s4 step 2-3; LensFix PreviewPayload.java `parse`; vectors `photo_download.hex`, `preview_h264_*.hex`. | In `store`: require byte 0 == 0 and LE32(1..4) == raw.size; route type 02 to the preview decoder; require FFD9 at the end. | High |
| P11 | Med | XkGlasses.kt:253-279 | Video start/stop uses 5780 (EXECUTE, 1 byte). SDK got INVALID_PARAM for every 5780 form it tried; LensFix found 5780 is the vendor's `toggleVideoPreviewShoot`, but its start/stop left `video_num` at 0 and returned status 2 (busy) with preview on. The app sets `recording = true` on any non-error reply. | SDK VALIDATION s12; LensFix VIDEO-QUALITY-RESEARCH. | Treat success as "5790 says recording" or "video_num went up after stop" (5713); show code 3 as unsupported; close the preview (5720 off) before 5780. | Med |
| P12 | Med | XkGlasses.kt:423 | Every push is offered to a per-node queue even when nobody waits (C101, 57B1, 5760, 5790): those queues grow for the whole session, and a stale 7320 left in the queue is cleared only by takePhoto. C101 is never delivered to voice chat. | Code reading. | Offer only to queues with a pending waiter; add a listener hook for C101/C107 (voice chat) and 5790/5760. | High |
| P13 | Low | XkGlasses.kt:425 | 0x2B frames from the glasses (F060, F04F, FGS replies, SDK PROTOCOL s9) are never ACKed. The captured session sends 0004 frames right after each 2B exchange, so the vendor app may ACK them. Effect unknown (photo path works without the channel). | SDK protocol.py `_SETUP_HEX` order. | Log 2B frames; if the glasses repeat them, ACK with `Xk.ack(order, rxOrder)`. | Low |
| P14 | Low | XkGlasses.kt:63, 440-450 | `log` is a public `ArrayDeque`; writes are synchronized but any direct read from the UI is not. | Code reading. | Make it private; expose `logText()` only. | High |
| P15 | Low | XkGlasses.kt:135-142 | Up to three blocking connects (each can take 5-12 s on Samsung) before failing; the SDK channel is 8. | SDK HARDWARE.md s2. | Close failed sockets; try channel 8 second; cap total connect time at 20 s. | Med |

## Missing for a LensMoo replacement (protocol side)

* Live preview: open/close node **5720** (1 byte), state query 5711, H.264 elements type 02 (LensFix
  VIDEO-QUALITY-RESEARCH). Not implemented. The SDK's 5710 is not the right node (LensFix: log text
  says 5710, builder sends 5720).
* Glasses mic over the protocol: 5750 toggle / 5770 query / 5760 state, 40-byte Opus packets with a
  0x28 length byte, 16 kHz mono (LensFix AUDIO-RESEARCH). SCO via HFP is the verified alternative.
  Camera open while audio active returned busy (status 2): one owner for mic and camera.
* Touch panel C101/C107 -> voice chat: ACKed but never surfaced (P12).

## Measurements done for this review

| Vector | Real Kotlin result |
|---|---|
| requests_app_captured.hex | 9/9 frames parse; encoders match captures (existing tests cover 7100/1001/57A0/ack) |
| noise_resync.hex | PASS: 1003, 5713, 7320 emitted; 77 noise bytes dropped |
| noise_false_header.hex | FAIL: nothing emitted (P1) |
| photo_download.hex | PASS: 653-byte JPEG identical to photo_expected_jpeg.hex |
| push_7320_count.hex | 6 and 1 (correct) |
| push_7320_count_u16le.hex | error and 256 (P2) |
| reply_1003_battery.hex | data `01 64...`, `00 64...`, `00 39...` -> charging/level correct |
