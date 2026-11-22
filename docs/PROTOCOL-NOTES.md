# P3 Companion: wire protocol notes

Spec for replacing LensMoo on Shenju-family glasses (NovaSight P3 / XK One Pro / W20 / E13C-1).
This file is a spec only. It contains no vendor code.

**Source keys** (line numbers refer to these files)

| Key | Path |
|---|---|
| `B/` | `/home/claude/beneduzi/xk-one-pro-sdk/` (live-validated photo, battery and bind path; one unit, fw 1.0.2) |
| `L/` | `/home/claude/joaosouz4dev/lensmoo-crash-fix/` (live preview, audio and recording tests on a different unit, E13C-1 fw 1.0.2, `noStorageDevice=1`; plus static analysis of LensMoo 1.8.0) |
| `A/` | `/home/user/p3/app/src/main/java/com/zdycomp/p3companion/` (our current code) |

**Confidence tags**

- **VALIDATED-LIVE**: bytes exchanged with real glasses, and the result checked.
- **OBSERVED-IN-CAPTURE**: seen in a captured vendor session or in a callback during a live test, but the exact wire bytes are only partly known.
- **INFERRED-FROM-VENDOR-SDK**: from builders, parsers or interfaces in the decompiled vendor app. Never sent by either project.
- **UNKNOWN**: not established.

---

## 0. Framing corrections that affect everything

### 0.1 One layout for request and reply data

**INFERRED, byte-checked against every capture.**

After the 4-character node, the real layout in both directions is:

```
[fmt:1][len:2 LE][data:len]
```

`fmt` is the `DataFormat` enum: `00` BIN, `01` TXT, `02` JSON, `03` NODATA, `04` ERRCODE.

- Our `Xk.control()` writes `[len BE2][00][data]` (`A/XkProtocol.kt:110-112`).
- B's builder writes the same (`B/python/xkglasses/protocol.py:120-129`).
- For data shorter than 256 bytes, this is byte-identical to `[00][len LE2]`.
  - Example: 2420 in the capture is `00 06 00 "en\0\0\0\0"` (`B/.../protocol.py:66`).
- Replies are only consistent with the LE reading.
  - Example: 1001 reply `02 9B 01` gives JSON, 411 bytes (`B/docs/VALIDATION.md:357-359`).
- Action: switch `control()` to `[fmt][len LE]` before sending any data of 256 bytes or more. Nothing we send today reaches that size.

### 0.2 Envelope and acks

**VALIDATED-LIVE.**

- `cmd` (envelope bytes 2-3) only tells direction, through bit `0x8000`.
- Request, response or push is told by the payload `action` field at `payload[6..7]`:
  - `0x8002` = response to our request
  - `0x8001` = unsolicited push
  - `0x8004` = ack
- Source: `B/docs/PROTOCOL.md:112-119`. Note that the comment in `A/XkProtocol.kt:15` mixes up these two fields.
- Dispatch by node, not by action. The test fixture shows a reply with action `0x8064` (`B/python/tests/test_codec.py:281`).

Acks:

| Ack | Bytes | When |
|---|---|---|
| Control ack | `head 30, cmd 0004, payload [01, rx_order]` | For every device control frame except device acks |
| Media ack | `head 4A, cmd 0009, payload [01]` | On a `0x4A` frame whose divide low bits are 3 |

- Keep separate TX and RX counters (`B/docs/PROTOCOL.md:177-180`, `B/.../protocol.py:141-148,196-198`).
- Our current behaviour in `A/XkGlasses.kt:391-399` is correct.

### 0.3 Device quirks to design for

**VALIDATED-LIVE.**

- Many nodes are answered only once per session.
- Some setup nodes (7100, 7110, 2410, 2420, C10A, 57A0, 5770) answer only inside the setup order (`B/docs/NODES.md:102-105`).
- Every request needs a timeout, and must never block the UI.
- A missing reply means "unknown", not "failed".
- No keepalive is needed; the link was idle for 240 s and still worked (`B/docs/PROTOCOL.md:289-310`).

### 0.4 Session

**VALIDATED-LIVE.**

The minimum session is:

1. `0001` (action 3)
2. `0002` (action 3)
3. `102E`

- The `0001`/`0002` contents are random and are not checked (`B/docs/VALIDATION.md:37-49`).
- The `102E` data is `[bindType][16×00][idLen][32-hex account id]`, with `bindType=2` (CONNECT_BACK).
- The account id is validated offline. It cannot be generated (`B/docs/BONDED_ENROLLMENT.md:55-75,120-158`).
- Only one RFCOMM client can hold channel 8 at a time, so LensMoo must be closed.

---

## 1. Live video preview

### 1.1 Node map

Source: `L/docs/VIDEO-QUALITY-RESEARCH.md:34-43` and `L/docs/AUDIO-RESEARCH.md:198`.

| Purpose | Node | Action | Request data | Reply / push |
|---|---|---|---|---|
| Open preview | `5720` | 3 (EXECUTE)¹ | `[01]` | reply on `0x30` node 5720 (`fmt 04` status); then push `5710` |
| Close preview | `5720` | 3¹ | `[00]` | as above; push `5710 = 00` |
| Query preview state | `5711` | 3¹ | `[00]`¹ | reply `data[0]`: 1 = open, else closed |
| Preview state push | `5710` | device→app, action 0x8001 | — | `data[0]`: 0 = closed, 1 = open, 2 = busy/refused (seen live) |
| H.264 frames | `0x4A` media channel | — | — | see 1.2 |

Confidence:

- Node numbers: **INFERRED-FROM-VENDOR-SDK** (builder bytes, not log strings).
- The open/close effect: **OBSERVED-IN-CAPTURE**. L opened and closed preview through the vendor SDK, got frames, and saw 5710 values 0, 1 and 2 (`L/docs/AUDIO-RESEARCH.md:175,198,219`; `L/docs/VIDEO-QUALITY-RESEARCH.md:17`).
- Our direct wire send of 5720 has never been done: **UNKNOWN**.
- ¹ Action 3 and the `[00]` query byte are **INFERRED**. Its siblings in the same builder class, `57A0` and `5770`, are captured as action 3 with data `[00]` (`B/.../protocol.py:69-72`). `57B0` is action 3 with no data. If 5720 comes back `fmt 04, code 03` (INVALID_PARAM), retry with action 2.

**Conflict, resolved.** B reports "preview not implemented" (`B/docs/VALIDATION.md:188-193`). B sent `5710`, which is the push node; it was named after a vendor log string. B never sent `5720 [01]`. B's 5710 reply `fmt 04 code 04` (INVALID_URN) is exactly what a push-only node should return. L got real frames. **We trust L.**

### 1.2 How frames arrive

What is known:

- **OBSERVED-IN-CAPTURE.** The vendor callbacks `MainFragment.decodeFrame(byte[])` and `onPreviewResult(byte[])` (hook points at `L/scripts/build.py:124`) receive:
  ```
  [media_type=0x02][len:4 LE][Annex-B H.264]
  ```
- In those callbacks, `len == packet.length − 5`, so the 5-byte header is **excluded** from the length (`L/android/src/dev/lensfix/video/PreviewPayload.java:50-55`; `L/docs/RECORDING-API-RESEARCH.md:7`).
- That is the same shape as the photo element header `[media_type=0x00][len LE4]`. The photo length **includes** the 5-byte header (`B/docs/PROTOCOL.md:196-207`).

What is not known:

- The SPP-level carriage is **UNKNOWN**. Nobody has logged raw RFCOMM bytes during a preview.
- The vendor SDK parses video frames as `MsgBean` with `divideType == 5` into `WmVideoFrameInfo{frameId, frameType, frameLen, frameData}` (`B/docs/VALIDATION.md:183-185`). **INFERRED-FROM-VENDOR-SDK.**

Implementation rule, robust to both readings:

1. Feed every `0x4A` frame that is not an ack to a media demuxer. Do not feed only to the photo assembler.
2. Divide low 3 bits `0`: payload = one whole element.
3. Divide `1`/`2`/`3`: strip the 4-byte LE `cmd_idx` (it counts 0, 1, 2…), then concatenate. `3` ends the element. Send `4A0009` on `3`.
4. Divide `5` (and any `4`–`7`): take the payload as a whole video element candidate.
   - Send no ack by default.
   - **If the stream stalls after the first frame, try acking** (UNKNOWN).
5. An element is H.264 if `elem[0]==0x02` and `LE32(elem[1..4]) ∈ {elem.size−5, elem.size}`, and Annex-B data follows.
   - If not, scan the element for `00 00 00 01` or `00 00 01` and take the data from there. This covers a possible `WmVideoFrameInfo` prefix.
   - `elem[0]==0x00` means a photo element.
6. Fix needed today: `A/XkProtocol.kt:212` throws on fragment types 4–7, and `A/XkGlasses.kt:393` drops media frames when no photo is pending.

### 1.3 Annex-B extraction

`PreviewPayload.java` logic, summarized:

1. Reject a packet if any of these hold:
   - shorter than 10 bytes or larger than 2 MiB
   - `b[0] != 2`
   - declared LE32 length `!= size − 5`
2. Split the remainder on 3- or 4-byte start codes. A missing first start code, or an empty NAL, means reject (lines 85-99).
3. Reject if any NAL has its forbidden bit set, or a type outside 1–12. Require at least one type 1, 5 or 7 (lines 59-66).
4. Per NAL (lines 22-46):
   - type 7 → SPS
   - type 8 → PPS
   - type 9 (AUD) → dropped
   - everything else → re-prefixed with `00 00 00 01` into the sample
   - `keyFrame` = the frame contains type 5
5. Width and height come from the SPS. It removes emulation-prevention bytes, handles high-profile chroma and scaling lists, and applies frame-mbs and cropping (lines 102-172).

How to use it:

- Start the decoder or muxer only after both SPS and PPS have been seen. Pass them as `csd-0` / `csd-1`, each with a 4-byte start code.
- Leave SPS/PPS out of the sample data.
- Use the monotonic arrival clock for PTS. There is no source timestamp (`L/docs/RECORDING-API-RESEARCH.md:13-23`).
- For our parser, accept both length conventions (rule 5 in 1.2).

### 1.4 Observed stream

**VALIDATED-LIVE**, on L's unit:

| Property | Value | Source |
|---|---|---|
| Resolution | 320×240 H.264, from the SPS | `L/docs/VALIDATION.md:23,40` |
| Frame rate | 295 frames / 10 s = 29.5 fps; 427 frames / 14.12 s = 30.2 fps | same |
| Bitrate | 684 472 B / 14.12 s ≈ 48 KB/s ≈ 390 kbit/s, about 1.6 KB per frame | same |
| Audio | none | same |

- Ignore `1001.preview_width/height` (160×120); it is wrong for H.264.
- No resolution or bitrate command exists (`L/docs/VIDEO-QUALITY-RESEARCH.md:43`; `B/docs/NODES.md:200-201`).
- The capability mask supports this. Reading 7100 bytes LSB-first (`B/docs/NODES.md:265-302`):
  - bit 22 "camera preview" = 1
  - bit 23 "video transfer" = 0
  - The bit order itself is unverified.

### 1.5 Interaction with photo, recording and audio

| Combination | Result | Confidence | Source |
|---|---|---|---|
| Native record `5780 [01]` while preview is open | Status 2 (busy) | OBSERVED-IN-CAPTURE | `L/docs/VIDEO-QUALITY-RESEARCH.md:15` |
| Open preview while protocol audio (5750) is on | 5710 = 2, zero frames | OBSERVED-IN-CAPTURE | `L/docs/AUDIO-RESEARCH.md:219` |
| Turn on SCO while preview is running | Preview closes (5710 = 0); reopening gives no frames | OBSERVED-IN-CAPTURE | `L/docs/HARDWARE-VARIANT.md:58-60`; `L/docs/AUDIO-RESEARCH.md:161-162` |
| Photo (`57B0`) during preview | Not tested | **UNKNOWN** | — |

On photo during preview:

- The vendor's `AbVideoPreview.toggleVideoPreviewPicture()` builds `57B0`. This suggests the in-preview shutter is the intended flow (`L/docs/VIDEO-QUALITY-RESEARCH.md:40`).
- But photo elements and video frames share the `0x4A` channel.

Our rule:

1. Send `5720 [00]`.
2. Wait for `5710 = 0`, at most 2 s.
3. Run the photo flow.
4. Reopen the preview.
5. Optionally, try an in-preview photo behind a flag, using the media-type demux in 1.2.

---

## 2. Recording on the glasses

### 2.1 Nodes

Source: `L/docs/VIDEO-QUALITY-RESEARCH.md:38-39`.

| Purpose | Node | Action | Data | Notes |
|---|---|---|---|---|
| Start/stop native recording | `5780` | 3 | `[01]` / `[00]` | `fmt 04` status reply; 2 = busy |
| Recording state query | `57A0` | 3 | `[00]` | reply `data[0]`: 1 = recording |
| Recording state push | `5790` | push | — | `data[0]`: 1 = on, 0 = off |

Confidence:

- Node mapping: **INFERRED-FROM-VENDOR-SDK**.
- 57A0 bytes: **OBSERVED-IN-CAPTURE** (`B/.../protocol.py:69-70`; reply `00 01 00 00`, `B/docs/VALIDATION.md:311`).

**Conflict.** B labels 57A0 as "video-preview state" (`B/docs/PROTOCOL.md:261-276`), from a log string. L maps the interface `getVideoPreviewShootState()` to `wlc.w()`, which builds 57A0. L also documents that the vendor log strings are wrong (`L/docs/VIDEO-QUALITY-RESEARCH.md:39,43`). **We trust L.** 57A0 is recording state, and preview state is 5711/5710.

- B's `HARDWARE.md:47` ("57A0 = battery") is stale. Battery comes from 1003.

### 2.2 Live behaviour

| Unit | What happened | Confidence | Source |
|---|---|---|---|
| L, `noStorageDevice=1`, through the vendor SDK | With preview closed, start and stop both returned status 0. `video_num` / `record_num` stayed 0. Nothing recorded. | OBSERVED-IN-CAPTURE | `L/docs/VIDEO-QUALITY-RESEARCH.md:15-17` |
| B, with its own frames | `5780` with action 1 or 2 always gave INVALID_PARAM. Action 3 was never tried. | VALIDATED-LIVE | `B/docs/VALIDATION.md:423-437` |

For units with storage (`noStorageDevice=0`):

- 5780 is expected to record to flash, and 5713 `video_num` should rise.
- There is **no known way to download those files** (section 3). **UNKNOWN.**
- The LensMoo UI hides record and import controls when `noStorageDevice=1` (`L/docs/VIDEO-QUALITY-RESEARCH.md:11,53`).

Fix needed: `A/XkGlasses.kt:271` tells users to "Import it over the cable". USB is reported to be charge-only (`B/docs/VALIDATION.md:201-203`), so remove that text.

### 2.3 Capability flags

**7110 `WmGlassesFunctionSupport`** (`B/docs/NODES.md:309-318`; `B/docs/PAYLOADS.md:85`)

- Flags: `noStorageDevice`, `supportAiChat`, `supportFunctionVersion`, `supportScoLink`, `supportVolcEngine`, `supportWakeWord`, `supportZlsyEngine`.
- Request: `7110`, action 1, no data. **VALIDATED-LIVE.**
- It answers only inside the setup order. Send it right after 102E, before anything else.
- Raw reply seen: `10 37 00…` (B's unit) (`B/docs/NODES.md:265-268`).
- The byte/bit position of each flag is **UNKNOWN**; the list above is alphabetical, not wire order.

Rule:

1. Log the raw 7110 bytes.
2. Decide "has storage" from behaviour: storage = `5712.total_memory > 0` **and** any 5713 count above 0 has ever been seen.
3. Until then, act as a no-storage unit (stream to phone).
4. B's unit reports 665 MB total but never stored anything (`B/docs/VALIDATION.md:307-308`), so memory size alone is not enough.

**7100 `WmFunctionSupport`** (`B/docs/NODES.md:256-322`)

- Request: action 1, no data.
- Reply: `[ver][mask 16][maxContacts 2][sideButtons 1][…]`.
- Mostly smartwatch noise.
- `sideButtonCount = 2` is real.

---

## 3. Stored media album over Bluetooth

### 3.1 Counts and memory

**VALIDATED-LIVE.**

| Node | Request | Reply |
|---|---|---|
| `5713` | action 1, no data | JSON `{"photo_num":"N","video_num":"N","record_num":"N","music_num":"N"}`, values are strings (`B/docs/VALIDATION.md:308`) |
| `5712` | action 1, no data | JSON `{"total_memory":N,"remain_memory":N}` |

### 3.2 Album nodes

`AbPhotoLibrary`, implemented by `xhc` (`L/docs/VIDEO-QUALITY-RESEARCH.md:69`; `B/docs/NODES.md:227`). All are **INFERRED-FROM-VENDOR-SDK**; data layouts are **UNKNOWN**.

| Node | Role | Status |
|---|---|---|
| `7200` | Enumerate stored images (list/count) | Silent when B probed it with no arguments (`B/docs/VALIDATION.md:269-271`) |
| `7310` | Enumerate or select an item (probably "prepare item *k* for transfer", which leads to a 7320 push) | Silent with no arguments |
| `7300` | Fetch element *i* of the **current** transfer. Action 2, `[i]` (1 byte, 1-based) | **VALIDATED-LIVE** |
| `7320` | Push: element count for the current transfer | **VALIDATED-LIVE** |
| `7500` | End or abort transfer. Action 3, no data | **VALIDATED-LIVE** |
| `7400` | **Delete photo.** Destructive | Never probe without the user confirming |
| `7600` | Unknown (possibly video transfer) | Silent |
| `5760` (after 7500) | A push seen after a photo transfer | B's reading (`B/docs/NODES.md:85`). Conflicts with L, where 5760 = audio-state push. Treat it by its data, not its timing. |

Feasibility:

- **Downloading the newest photo**: works (`57B0` → `57B1` → `7320` → `7300`×N → `7500`). **VALIDATED-LIVE.**
- **Downloading a chosen stored photo**: needs 7200/7310 payloads, which are UNKNOWN. Not feasible without a capture of LensMoo browsing a storage unit's album.
  - Capture plan: on a `noStorageDevice=0` unit, log `0x30` frames while LensMoo opens its album and downloads one older photo.
- **Deleting**: 7400 data is UNKNOWN. Do not ship.
- **On `noStorageDevice=1` units (L's unit, and likely the P3)**: the glasses keep nothing. B saw `photo_num` stay at 0 after many photos. LensMoo's album is **phone-local**. Our album is MediaStore (Pictures/ and Movies/ in our folder). That is a full replacement there.
- **Video or audio files over SPP**: no known path.
  - 9000 = INVALID_URN.
  - 9001 = INVALID_DATA; it needs a multi-item package (`B/docs/VALIDATION.md:423-437`).
  - **Not feasible.**

### 3.3 Fix needed for 7320

Our count parse reads 2 bytes **big-endian** (`A/XkGlasses.kt:301-310`).

B's live-proven parser reads `payload[16..17]`, which is effectively `data[0]`, because `payload[16]` is the high byte of the length (`B/python/xkglasses/client.py:128-135`).

Use:

- `data[0]` if `len == 1`
- otherwise LE16

Range is 1–255, because 7300 takes a 1-byte index. If the data were `[06 00]`, our current code would fail.

---

## 4. Audio

### 4.1 Paths

| Path | How | Confidence |
|---|---|---|
| **Mic via HFP/SCO** | Standard Android: `setCommunicationDevice(glasses SCO sink)` on API 31+, then `AudioRecord` with VOICE_COMMUNICATION, 16 kHz mono PCM16. A 10 s capture was clear. | VALIDATED-LIVE (`L/docs/AUDIO-RESEARCH.md:160`; route guidance at `:36-44`) |
| **Speakers via A2DP** | Normal media playback. Volume is the phone's media volume and AVRCP. No SPP volume node exists; capability bit 49 "app control volume" reads 0. | A2DP profile present (`B/docs/HARDWARE.md:33-39`). Volume control is **UNKNOWN**/untested. |
| **Protocol mic (Opus over SPP)** | `5750` action 3 `[01]` to start, `[00]` to stop. Reply `fmt 04`, code 0 = ok. State push `5760` `data[0]`: 1 = on. Query `5770` action 3 `[00]` (bytes captured, `B/.../protocol.py:71-72`). | Data stream VALIDATED-LIVE through the vendor SDK: 342 packets, 6.84 s (`L/docs/AUDIO-RESEARCH.md:110-112,175-179,218`). Node bytes INFERRED. |

Protocol mic details:

- Timing: ack about 0.2 s after the start command, and `5760=1` about 3 s later.
- Stream format, consistent across three tests (7040/22, 109440/342, 109120/341):
  ```
  [0x28][40-byte Opus packet]
  ```
  - 320 samples per packet = 20 ms at 16 kHz mono, about 16 kbit/s.
- Parsing: on a bad length byte, skip 1 byte and look again. Keep partial bytes between chunks (`L/docs/AUDIO-RESEARCH.md:14,128-130`).
- Decode with Android `MediaCodec` `audio/opus` (needs an OpusHead CSD), or with bundled libopus.

**Conflict.** B lists "audio routing over SPP" as absent (`B/docs/NODES.md:202`) and names 5770 "storage". L's later live test supersedes both. **We trust L.**

Where the Opus bytes travel on the wire is **UNKNOWN**. Probably `0x4A` elements (a media_type other than 0 or 2?) or a `0x2B` sub-channel.

- Plan: while 5750 is on, log every non-`0x30` frame, and look for runs of `28` followed by 40 bytes.

### 4.2 Conflicts with video

- SCO, or protocol audio, rules out the camera preview on L's unit (section 1.5).
- Silent preview with the **phone's** microphone, muxed in as AAC, is feasible. This is the recommended "video with sound" mode, labelled honestly.

### 4.3 The double-press "voice memo" on no-storage units

**INFERRED-FROM-VENDOR-SDK; the stream itself is VALIDATED.**

1. The glasses push `5760 = 1`.
2. LensMoo, if `noStorageDevice == 1`, opens its recording screen. Its PCM consumer then writes a WAV on the **phone** from the Opus stream (`L/docs/AUDIO-RESEARCH.md:149,183-190`).
3. So the phone, not the glasses, makes the recording.

What we must do:

- Ack the push.
- On `5760=1`: start decoding and write an M4A/WAV.
- On `5760=0`: finish the file.
- Also offer an in-app "Record audio" button that sends `5750 [01]` / `[00]`.

---

## 5. Button and touch events

All arrive as `0x30` pushes. Ack each with `0004`.

| Event | Meaning | Confidence |
|---|---|---|
| `57B1` | Shutter fired, about 2.6 s after a trigger (from the app's 57B0 or a **single press** of the right-front button) | VALIDATED-LIVE (`B/docs/PROTOCOL.md:149-158`) |
| `7320` `[count]` | Photo ready, N elements. **The host must download and send 7500, or abort with 7500.** Otherwise the glasses stay blocked. | VALIDATED-LIVE (`B/docs/NODES.md:94-97`) |
| `5760` `[1/0]` | **Double press** of the right-front button: voice memo start/stop (single press stops) | Gesture: OBSERVED (`L/docs/VIDEO-RESEARCH.md:25,31`). That it maps to 5760: INFERRED (4.3). |
| `C101` | Touch strip / voice-assistant trigger. LensMoo starts an AI chat. | OBSERVED (`B/docs/PROTOCOL.md:280-285`; `B/python/xkglasses/client.py:116-119`). Data layout UNKNOWN. Log the raw bytes. |
| `C107` | Second touch/button event. Ack it, meaning unknown. | OBSERVED, semantics UNKNOWN |
| Rear button | "A separate control" (`L/docs/VIDEO-RESEARCH.md:31`) | UNKNOWN. May surface as an AVRCP key. |
| Long press about 3 s | Power on/off (sibling W600 manual) | UNKNOWN on P3 (`B/docs/VALIDATION.md:209-210`) |
| AVRCP keys | When there is no SPP session, buttons can show up as `KEYCODE_CAMERA` / `HEADSETHOOK` to a `MediaSession` | INFERRED (`B/docs/HARDWARE.md:58`; `B/docs/REVERSE_ENGINEERING.md:67`) |

Notes:

- Without an SPP session, the button plays "please connect to the app".
- Sending `C101`/`C104`/`C107`/`C109`/`C10A` **as requests** returns INVALID_URN. Do not send them.

---

## 6. Settings and miscellaneous

| Function | Request | Reply | Confidence |
|---|---|---|---|
| Battery and charging | `1003` action 1, no data | BIN `[is_charging][level 0-100]` + 8×00 | VALIDATED-LIVE (`B/docs/PROTOCOL.md:220-244`) |
| Device info | `1001` action 1, no data | JSON: `prod_mode`, `soft_ver`, `mac_addr`, `dev_id`, `dev_name`, `battery_main` (string), `screen`, `preview_width/height` (ignore these), `offline_asr_auth`, … | VALIDATED-LIVE (`B/docs/VALIDATION.md:287-300`) |
| Language list | `2410` action 1, no data | List of NUL-padded tags (`en`, `zh-cn`, …) | OBSERVED-IN-CAPTURE (`B/docs/PAYLOADS.md:88`) |
| Set prompt language | `2420` action **2**, data = tag NUL-padded to 6 bytes (e.g. `65 6E 00 00 00 00`) | `fmt 04 code 00` | OBSERVED-IN-CAPTURE (`B/.../protocol.py:65-66`; `B/docs/PAYLOADS.md:61`). The 6-byte width is from one capture; pad or truncate to 6. Capability bit 47 = 1. |
| Sound settings | `1017` read action 1 gives a 4-byte mask. Write action 2 `[sub][val]`. | Write is acked but **has no effect**; it does not mute prompts. | VALIDATED-LIVE (`B/docs/VALIDATION.md:223-244`). Do not ship it as a feature. |
| Volume | No SPP node | Use the phone's media volume / AVRCP | — |
| Disconnect cleanly | `102E` action 3, `bindType=3` | ? | INFERRED |
| **Unbind** | `102E` action 3, `bindType=4` + account id | **Removes the BT pairing on the glasses.** The account id still works after re-pairing. | VALIDATED-LIVE (`B/docs/BONDED_ENROLLMENT.md:171-174`). Destructive: confirm twice. |
| **Power off** | `102E` action 3, `bindType=5` + same data layout | ? | INFERRED (`B/docs/NODES.md:32-41`). Confirm first. |
| Time sync | `1007` | INVALID_URN | VALIDATED-LIVE: not implemented |
| Notifications | `1004`, `4700` | INVALID_URN or silent | Not implemented |
| Reboot | Capability bit 58 = 1, but no node is known | — | UNKNOWN |

---

## 7. Rest of the vendor app's link use, and what is not feasible

### 7.1 Also needed for a full replacement (feasible)

- **Setup order:** 0001 → 0002 → 102E → 7110 → 7100 → 1001 → 1003 → 2410 → 5713, then 57A0, 5711 and 5770 as needed. The `0x2B` frames are optional (`B/docs/VALIDATION.md:53-65`).
- **Auto-fetch button photos** from the `7320` push. Already implemented.
- **Preview screen**, with phone-side MP4 recording (optionally with phone mic audio).
- **Voice memos** (section 4.3).
- **Own voice assistant:** `C101` triggers the phone's speech recognition over SCO, and replies play over A2DP.
- **Battery and charging** display.
- **Language** setting.
- **Unbind / power off**, with confirmation.
- **Phone-side album**: Pictures and Movies in our MediaStore folder.

### 7.2 Not feasible, and why

| Item | Why |
|---|---|
| **OTA firmware** | `102A`/`102C`/`5610`/`5620` carry `OtaCmdInfo{crc, offSet, payload}`; `1030`-`1032` carry `DivideInfo`. There is no rollback or recovery image, so a bad update can brick the glasses (`B/docs/PAYLOADS.md:132,190-193`; `L/docs/FIRMWARE-RESEARCH.md:53,88`). **Never send these nodes.** |
| **Generating the 102E account id** | The firmware validates it offline. The user must supply their own LensMoo account id. |
| **Vendor cloud AI** (`0x2B` FGS/FND, StarBurst/Aliyun) | Needs vendor keys; it fails even in LensMoo (`L/docs/VALIDATION.md:7`). Replace with our own assistant. |
| **HD video / resolution choice** | No such command exists. 320×240 is the most received. |
| **Video plus glasses-mic audio at the same time** | Refused as busy on the tested unit. |
| **Downloading stored video/audio files** | No known transfer path (section 3). On no-storage units there is nothing stored. |
| **Wi-Fi transfer** | No access point and no SSID contract was found (`B/docs/VALIDATION.md:197-198`; `L/docs/VIDEO-QUALITY-RESEARCH.md:71`). |
| **Raw sensors (A000/A001), muting voice prompts, time sync** | Not implemented in the firmware. |
| **Watch-only nodes** (2100-5410, B001-B004) | Do not apply to glasses. |
