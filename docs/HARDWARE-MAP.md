# Hardware map: everything the glasses expose, and how to use it dynamically

Experimental Physicist, BLE/RF group, 2026-11-28. Docs only. Glasses: NovaSight P3_D6BE, Shenju XK model
**E13C-5, firmware 1.0.0**, phone moto g 2025 (Android 15). App at 4.0.0 (`0dab35d`).

Owner's ask: *"use every aspect of the hardware that's in these glasses dynamically, especially the voice chat
and the microphone that's in the glasses, and sharpen up the image better than you can."*

This file is an inventory from evidence only. Nothing here was sent to the glasses. No vendor or decompiled code
was used; protocol facts come from `docs/PROTOCOL-NOTES.md` (which cites the two public MIT projects) and the
owner's own logs.

## 0. Sources and confidence

| Tag | Meaning |
|---|---|
| **SEEN** | In the owner's E13C-5 logs: `docs/LOG-2026-11-23.txt` (3.1.0), `LOG-2026-11-25-liveview.txt` (3.3.0), `LOG-2026-11-25-v340.txt` (3.4.0), `LOG-2026-11-25-v360-voice.txt` (3.6.0), or numbers quoted from later device runs in `CHANGELOG.md`. |
| **LAB** | Checked on a PC in this review with ffmpeg/libopus (section 4.6). Not on the glasses. |
| **DOC** | In `PROTOCOL-NOTES.md` / `docs/rf/*`: seen on *other* units (E13C-1 fw 1.0.2, xk one Pro fw 1.0.2) or in the vendor SDK's builders. |
| **INFERRED** | Follows from the above, not observed. |
| **UNKNOWN** | No evidence. A test is listed in section 8. |

Code references are to `app/src/main/java/com/zdycomp/p3companion/` (abbreviated `A/`).

---

## 1. Inventory at a glance

| # | Capability | How the app reaches it | Used today (4.0.0) | Confidence |
|---|---|---|---|---|
| 1 | Control link | SPP/RFCOMM ch 8, head `0x30`, session `0001`→`0002`→`102E` | Yes | SEEN |
| 2 | Camera, still photo | `57B0` act 3 → push `57B1` → push `7320 [n]` → `7300` act 2 `[i]` ×n on head `0x4A` → `7500` | Yes (app and button) | SEEN: 640×480 JPEG, 54-75 KB, 5-6 parts, ~1.15 s transfer |
| 3 | Camera, live video | `5720` act 3 `[01]`/`[00]`; state push `5710`; query `5711`; H.264 on head `0x1E` | Yes | SEEN: 320×240 Baseline L4.0, 29.7-30.2 fps, 340-440 kbps |
| 4 | On-glasses video recording | `5780` act 3 `[01]`/`[00]`; state `57A0`/`5790` | Wired (`A/XkGlasses.kt:1661`), never confirmed on E13C-5 | DOC; UNKNOWN here |
| 5 | **Glasses mic, data link (Opus)** | `5750` act 3 `[01]`/`[00]`; state push `5760`; packets on head **`0x1F`** cmd `8002` | Yes, decoded for voice input | SEEN (stream, layout, timing) + LAB (codec) |
| 6 | Glasses mic, hands-free (SCO) | Android HFP: `setCommunicationDevice` + `AudioRecord` `VOICE_COMMUNICATION` | Yes (default when proven) | SEEN: SCO connected in 511-751 ms |
| 7 | Speakers, media (A2DP) | Normal playback; `AudioTrack` for Gemini TTS | Yes | SEEN (device timing line "through media audio") |
| 8 | Speakers, call audio (SCO) | Playback on `STREAM_VOICE_CALL` while SCO is up | Yes (option "speak on the call link") | SEEN (SCO); quality UNKNOWN |
| 9 | Built-in voice prompts | Played by the glasses; language via `2420` act 2 | Language never written | DOC |
| 10 | Volume | No SPP node; phone stream volume (+ AVRCP absolute volume if the glasses support it) | Phone volume only (`A/CommandRunner.kt:423`) | DOC: no node; AVRCP volume UNKNOWN |
| 11 | Touch pad | Push `C107` (seen), `C101` (documented) | Yes: one action for both (`A/XkGlasses.kt:1173`) | SEEN `C107`; gestures UNKNOWN |
| 12 | Photo button (right front) | Single press → `57B1` + `7320` | Yes (auto download) | SEEN |
| 13 | Double press → voice memo | Push `5760 = 1/0` | Logged only | DOC; UNKNOWN on E13C-5 |
| 14 | Rear button, long press | Not known | No | UNKNOWN |
| 15 | Media keys (AVRCP) | `MediaSession` key events | No (`MediaSession` not used anywhere) | INFERRED |
| 16 | HFP voice-dial key (AT+BVRA) | `Intent.ACTION_VOICE_COMMAND` | No | INFERRED |
| 17 | Battery and charging | `1003` read; push every ~6 min; `1001.battery_main` | Yes | SEEN |
| 18 | Capability flags | `7110` (answers only in setup), `7100` | `7110` logged raw; `7100` read once by T13 (section 9) | SEEN both (4.1.1 T13); meanings mostly DOC/GUESS |
| 19 | Device info | `1001` JSON | Name, firmware, model, battery, preview size, MAC check | SEEN/DOC |
| 20 | Language list / prompt language | `2410` read / `2420` write | Read only inside the live view probe | SEEN |
| 21 | Sound settings | `1017` read mask, write act 2 | No (write has no effect on B's unit) | DOC |
| 22 | Storage counters | `5713` counts JSON, `5712` memory JSON | `5713` read; `5712` only in the full-size probe | SEEN/DOC |
| 23 | Album / stored transfer | `7200`, `7310`, `7600` (read-only probes) | Only in Diagnostics probes | DOC; silent |
| 24 | USB / cable | Pogo charging connector | Import tab reads a drive if one appears | DOC: charge-only |
| 25 | Vendor channel | Head `0x2B` | Logged as size only | SEEN (7 B frame at sign-in) |
| 26 | Disconnect / power off / unbind | `102E` act 3 bindType 3 / 5 / 4 | No | DOC/INFERRED; unbind destructive |
| 27 | LED / indicator light | No node known | No | UNKNOWN |
| 28 | Sensors (IMU, wear, light) | `A000`/`A001` "raw sensors" not implemented in firmware (other units) | No | DOC (absent); UNKNOWN here |
| 29 | Wi-Fi | None found | Wi-Fi screen scans and reports | DOC + SEEN (none found) |
| 30 | BLE | Not needed; possible LE advertising when no Classic link | No | DOC |
| — | **Never send** | `102A` `102C` `5610` `5620` `1030`-`1032` (OTA), `7400` (delete), `9000`/`9001` | Denylisted in `FullRes` | DOC |

**Unused or under-used today:** 4 (recording, unconfirmed), 9 (prompt language), 10 (AVRCP volume), the gesture
detail of 11, 13 (double-press memo), 14, 15, 16, 18 (`7100`), 20 (`2420`), 25 (`0x2B` contents), and any `0x1F`
mic stream the glasses start by themselves. Sections 5 and 7 turn these into dynamic uses.

---

## 2. Each capability in detail

### 2.1 Link and frame heads

- SPP on RFCOMM ch 8 (`sdp-secure` first, then `ch8-secure`), one client at a time (LensMoo must be closed). SEEN.
- Envelope 16 bytes; `divide` low 3 bits = fragment (0 whole, 1 first, 2 middle, 3 last), high bits = element
  length hint: `((divide & 0xF8) << 5 | divide >> 8) & 0x1FFF` (`A/XkProtocol.kt:90`). SEEN.
- Heads seen from the E13C-5: `0x30` control, `0x4A` photo media, `0x1E` live video, **`0x1F` mic audio**,
  `0x2B` vendor. The `0x1F` frames carry the same length hint: divide `9B08` → 411, divide `5300` → 83, which are
  exactly the frame sizes logged. SEEN (computed from the 3.6 log).
- No keepalive needed (240 s idle on another unit). DOC.
- Link drops seen: right after a `C107` touch (`03:22:06`) and right after sign-in (`03:22:13`). The 3.9.1 drop
  recovery handles them. SEEN.

### 2.2 Camera: still photo

- Path: `57B0` act 3 (reply `00`, then the request still times out: the reply is device-numbered) → `57B1 01`
  about 2-3 s later → `7320 [05|06]` → `7300` act 2 `[i]` per part → `0x4A` bursts, `4A 0009 [01]` ack on each
  last fragment → `7500`. SEEN (`LOG-2026-11-23` 05:31-05:32).
- Measured: 54 KB in 5 parts in 1153 ms (48 KB/s); 73 KB in 6 parts in 1142 ms (65 KB/s); 640×480 JPEG
  (CHANGELOG 4.0.0). SEEN.
- The glasses cannot be asked for a bigger picture: no resolution node, no stored original (FULL-RES-PLAN §0).
  A seller listing for a sibling says "1 MP 1280×720 camera", so the sensor is likely larger than what is sent.
  DOC/INFERRED.
- One photo cycle is about **3.5-4 s** end to end (shutter ~2.5 s + transfer ~1.2 s). SEEN.
- Today: app photo, button photo with auto-download, `-enhanced` 2× copy (`ImageEnhance`). Used.

Dynamic proposals: see section 6 (burst stacking and JPEG-aware deblocking are what can beat the current single-
frame sharpen).

### 2.3 Camera: live video

- `5720` act 3 `[01]` opens; `5710` push `01` on, `00` off, `02` busy. `[01 01]`…`[01 03]` give the same
  320×240; `[02]`, `[03]` accepted but no video. SEEN.
- Per-fragment `1E 0009 [01]` ack gives ~30 fps; without it the stream crawls (~5 pictures in 10 s). SEEN.
- SPS `67 42 C0 28…`: Baseline, level 4.0, 320×240, SPS+PPS in every picture seen. SEEN.
- An open can trigger the glasses' own shutter (`57B1` 2 s after an open that met `5710 = 02`). SEEN (3.4).
- Conflicts on another unit: SCO on → preview closes; `5750` on → preview refused (`02`). DOC. **Not yet tested on
  the E13C-5** (test T6).

Dynamic: live view is the only 30 fps sensor stream. Use it as (a) the viewfinder, (b) a frame source for
multi-frame stacking when a 640×480 photo is not needed, and (c) a "look" input for Gemini (`ai.ask_view`)
without a 4 s photo cycle. Close it automatically when a glasses mic route that conflicts with it is needed
(section 5).

### 2.4 On-glasses video recording

- `5780` act 3 `[01]`/`[00]`, state `57A0` (read act 1; act 3 gives `03` INVALID_PARAM, SEEN) and push `5790`.
  On a no-storage unit, start/stop returned OK and nothing was recorded. DOC.
- `7110` on the E13C-5 is byte-identical to the unit that reports `noStorageDevice = 1`
  (`10 37 00…00 64 00 02 08 0C`). INFERRED: **this unit stores nothing**.
- Dynamic use: none unless T10 shows `5713.video_num` rising. Record video on the phone from the live stream instead.

### 2.5 Glasses microphone over the data link (Opus) — the important one

Full recipe in section 4. Facts:

| Fact | Value | Confidence |
|---|---|---|
| Start / stop | `5750` act 3 `[01]` / `[00]`, reply `00` | SEEN |
| State push | `5760 01` about 3 s after start; `5760 00` after stop | SEEN |
| First decoded sound | 3873 ms after the start command | SEEN |
| Carrier | head `0x1F`, cmd `0x8002`, divide fragment 0 (whole) | SEEN |
| Frame body | `[02]` then n × `[28][40 bytes]`; 411 B = 1 + 10×41 (n = 10), 83 B = 1 + 2×41 | SEEN |
| Rate | ~5 frames/s × 10 packets = 50 packets/s = 20 ms each; 700 packets in ~14 s | SEEN |
| Codec | Opus, TOC config 9 = **SILK-only wideband, 20 ms**, mono; every packet 40 bytes = 16 kbit/s hard CBR | SEEN (bytes) + LAB (libopus makes the identical pattern) |
| Decoder output | Android `MediaCodec` `audio/opus` → 48000 Hz, 1 channel | SEEN (`OpusPcm`, 3.6 log line) |
| Used today | `GlassesMicStream` → `OpusPcm` → `Resampler` 48→16 kHz → `PcmPipe`/`Vad`/WAV for Gemini | — |
| Weak spot | Google's recognizer gave `ERROR_NO_MATCH` when fed this audio by pipe (3.6); the Gemini path takes a WAV instead (4.0) | SEEN |
| Conflicts | Live view refused while it runs (other unit) | DOC; UNKNOWN on E13C-5 |

Dynamic: this mic needs **no call mode**, so A2DP keeps playing high-quality speech while it runs, and the phone's
own audio is not hijacked. Its cost is a ~4 s start. So start it once per voice-chat session and keep it warm,
rather than once per question (section 5).

### 2.6 Glasses microphone over hands-free (SCO)

- `setCommunicationDevice(NovaSight P3_D6BE, type 7)`; headset audio connected in 510 ms, call device confirmed
  in 751 ms. SEEN (3.6).
- `AudioRecord` `VOICE_COMMUNICATION` 16 kHz mono with `NoiseSuppressor` / `AutomaticGainControl` /
  `AcousticEchoCanceler` where present (4.0). Used.
- Whether the link runs mSBC (true 16 kHz, wideband speech) or CVSD (8 kHz, narrowband, upsampled) is
  **UNKNOWN**. It decides whether SCO or Opus is the better-sounding glasses mic (test T5).
- While SCO is up, all phone output goes to the call path: the reply voice becomes call quality. INFERRED
  (standard Android behaviour).
- The 3.6 SCO failure was `ERROR_TOO_MANY_REQUESTS` from the recognizer, not missing audio. SEEN.

Dynamic: fastest glasses mic to open (~0.5-0.75 s) and Android's own echo canceller works on it. Best for a single
quick question, or when the Opus stream is refused.

### 2.7 Speakers

- **A2DP**: media output. Speech timing line: "first audio 1545 ms after the words were recognised, through media
  audio (the glasses when they're the phone's audio device)". SEEN. Codec (SBC/AAC) UNKNOWN; irrelevant for speech.
- **SCO**: call output while SCO is held (4.0 option). SEEN (route), quality UNKNOWN.
- **Built-in prompts** ("please connect to the app"): cannot be muted (`1017` write had no effect, other unit);
  language via `2420` act 2 data = 6-byte NUL-padded tag. `2410` on the E13C-5 returns
  `"en" "en" "zh-cn"`, `2420` reads `"en"`. SEEN (reads). The app never writes `2420`.

Dynamic: keep replies on A2DP whenever the mic in use is not SCO (section 5). Offer "Glasses prompt language"
(write `2420` once, only from a tag in the `2410` list) so the glasses' own prompts match the app.

### 2.8 Volume

- No SPP volume node; capability bit 49 "app control volume" reads 0 (other unit). DOC.
- The app steps the phone's `STREAM_MUSIC` or `STREAM_VOICE_CALL` (`A/CommandRunner.kt:423`). Used.
- Whether the glasses honour AVRCP absolute volume (phone volume = glasses loudness, not just a digital scale) is
  UNKNOWN (test T8).

Dynamic: the mic routes already measure the noise floor (VAD "floor level" in the 4.0 log). Raise the reply
volume a step when the floor is high (street), lower it in a quiet room, within a user cap, and restore it after
the turn.

### 2.9 Touch pad and buttons

| Event | Node / route | Data | Seen on E13C-5 | What it means |
|---|---|---|---|---|
| Touch | `C107` push | none logged (the log prints data only when 1-24 B, so it was empty or longer) | **SEEN** 4 times | A touch. Which gesture is UNKNOWN. |
| Touch / assistant | `C101` push | UNKNOWN | Not seen | Documented as the voice-assistant trigger on other units (DOC) |
| Glasses' audio engaging | `C107` right after the app sent `102A 01` (3.1 probe, now dropped; OTA family, never send) | — | SEEN once | INFERRED: `C107` is also raised when the glasses start their own audio |
| Photo (single press, right front) | `57B1 01`, `7320 [n]` | count | SEEN | Shutter |
| Double press | `5760 01`/`00` | state | Not seen unsolicited | Voice memo (DOC) |
| Rear button | ? | ? | Not seen | UNKNOWN; maybe AVRCP |
| Long press ~3 s | power | — | — | UNKNOWN on P3 |
| Without SPP | AVRCP keys / HFP AT+BVRA | — | — | INFERRED |

Today both `C101` and `C107` call one hook: start a voice chat (`MainActivity.onGlassesTapped`), or stop a memo,
or pause Conversation translate. No gesture is told apart. Section 5.3 gives the turn control; test T1 maps the
gestures.

### 2.10 Battery and charging

- `1003` reply/push `[charging][level][8×00]`: `00 4D` = not charging, 77%. Pushes about every 6 min. SEEN.
- Charging = 1 not yet seen in a log (test T9).

Dynamic: battery-aware policy. Below 20%: prefer the phone mic, cap live view at 2 min, no burst photos; say so
once. On a charging push: allow long sessions (Conversation translate) without the warning.

### 2.11 LED / indicator

No node, flag or log line names an LED. UNKNOWN. A camera-active light, if the glasses have one, is driven by the
firmware. Test T12 only *observes* it. The app should not claim to control any light; the 4.0 flashlight is the
phone's torch.

### 2.12 Storage and USB

- `7110` matches the no-storage unit; `5713` counts stayed 0 on such units; cable reported charge-only (pogo).
  DOC/INFERRED. Album nodes `7200`/`7310`/`7600` silent. DOC.
- Dynamic use: none. The phone is the storage (MediaStore). Keep the read-only checks in Diagnostics.

### 2.13 Sensors (IMU, wear detection, light)

- `A000`/`A001` "raw sensors": not implemented in firmware (other unit). DOC.
- No log has shown an unknown push when the glasses were put on, taken off or folded. UNKNOWN (test T11).
- `7110` lists `supportWakeWord` among its flags, and `1001` has `offline_asr_auth = "1"` (other unit). Bit
  positions UNKNOWN. A wake word on the glasses, if active, would most likely surface as a `C101`/`C107` push.
  INFERRED.

### 2.14 Info and capability nodes

| Node | E13C-5 bytes | Use |
|---|---|---|
| `7110` | `10 37 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 64 00 02 08 0C` (SEEN) | 5 of 7 glasses flags set; order UNKNOWN. Logged only. |
| `7100` | read by T13 on 4.1.1: see section 9 (SEEN) | DOC: mask with bit 22 preview = 1, 23 video transfer = 0, 47 language = 1, 49 app volume = 0, 58 reboot = 1, `sideButtons = 2`. Read it once in setup (READ only) and log it (T13). |
| `1001` | name, `soft_ver 1.0.0`, `prod_mode E13C-5`, `preview_width/height 160/120` (SEEN) | Other fields (`screen`, `dial_ability`, `offline_asr_auth`, `ch`/`cw`…) are smartwatch leftovers or unknown. Log the whole JSON once (T13). |

### 2.15 Vendor channel `0x2B`

- One 7-byte `0x2B` frame right after sign-in. SEEN. Content not logged (only "← 2B frame (7 B)").
- DOC: carries the vendor cloud AI's credential exchange; needs vendor keys; not usable and not needed.
- Dynamic use: none, but log its hex (T13) so a change in it can be correlated with touch or wake-word events.

### 2.16 Power and bind

- `102E` bindType 2 (connect back) used. bindType 3 (disconnect) / 5 (power off) INFERRED; 4 (unbind) DOC,
  destructive. Not proposed for automatic use. A "Power off the glasses" command would need a confirmation and a
  test first.

---

## 3. Nodes and frames seen in the logs that the app ignores or only logs

| Seen | Where | What the app does | Proposal |
|---|---|---|---|
| `C107` data | every touch | Treated as `C101`; data not logged | Log full payload hex; map gestures (T1); separate actions |
| `5760 01`/`00` | 3.6, around `5750` | Logs "voice memo state" | Use as the authoritative "mic stream on/off"; an **unsolicited** `5760 01` (double press) starts a phone-side memo or a voice turn from the `0x1F` stream |
| Head `0x1F` frames while the app did not start the mic | not yet seen | Fall to the generic branch: counted, sniff-logged, dropped | Always run `MicRecords.split` on `0x1F` and keep a short ring; if packets arrive unasked, raise "the glasses are streaming their mic" |
| `0x2B` 7-byte frame | after every sign-in | Size logged | Log hex (T13) |
| `7110` flags | setup | Logged, not decoded | Keep raw; compare across firmware |
| `1001` extra fields | setup | Ignored | Log once |
| `2410` list | live view probe only | Logged | Feed a prompt-language setting (`2420`) |
| `5711 00` | live view open | Logged | Fine as is |
| `57A0` act 3 → `03` | 3.1 probe | Logged | Use act 1 only (already) |
| `5710 02` busy | 3.4 | Used (waits) | Also treat as "camera held by mic/shutter" in the route policy |
| `57B1` without an app request | 3.4 (shutter on open) | Saved as a button photo | Fine as is |
| Late `7300`/`7500` replies | 3.1 | "Ignored a late reply" | Fine as is |
| Timeouts after a `00` reply (`57B0`, `7500`, `5720`) | all logs | Logged as "no answer" | Already matched by node since 3.3 |

---

## 4. Turning the 5750 Opus stream into audio the phone (and Gemini) can use

### 4.1 Frame to packets

```
head 1F | cmd 8002 | divide = length hint, fragment 0 | payload:
  02                      one type byte per frame (MicRecords.TYPE_OPUS)
  28 <40 bytes>           record 1 = one Opus packet
  28 <40 bytes>           record 2
  ... (10 records in a 411-byte frame = 200 ms)
```

`MicRecords.split` (`A/VoiceRoutes.kt:375`) already does this per frame. Nothing is carried between frames. The
3.6 run reported "112 B skipped" because it read frames as one byte stream; 3.7 fixed that.

### 4.2 The Opus packet (RFC 6716 §3)

TOC byte: bits 7-3 config, bit 2 stereo, bits 1-0 frame-count code.

| First bytes | Config | Stereo | Code | Meaning | Frame bytes |
|---|---|---|---|---|---|
| `48 …` | 9 | 0 | 0 | one frame | 39 |
| `4B 01 …` | 9 | 0 | 3 | count byte `01`: CBR, no padding, M = 1 | 38 |
| `4B 41 1E …` | 9 | 0 | 3 | count byte `41`: CBR, **padding**, M = 1; pad length `1E` = 30 | 40 − 3 − 30 = **7**, then 30 × `00` |

Config 9 = SILK-only, wideband (8 kHz audio bandwidth, 16 kHz sampling), 20 ms. Each packet decodes to 20 ms:
320 samples at 16 kHz, 960 at 48 kHz. The first 83-byte frame in the 3.6 log is exactly `4B 41 1E` + 7 bytes +
zeros. SEEN.

LAB: ffmpeg's libopus with `-ar 16000 -b:a 16k -vbr off -application voip -frame_duration 20` produces this same
signature: 151 of 151 packets config 9, all 40 bytes, codes 0 and 3, count bytes only `0x01` and `0x41`. So the
glasses run a standard Opus encoder in hard-CBR VoIP mode at 16 kbit/s. Digital silence encodes as the 7-byte
padded frame; a tone gives 26-39-byte frames.

**Free voice-activity hint (INFERRED, lab-checked on synthetic audio only):** the SILK frame size
(39 / 38 / 40−3−pad) tracks loudness. ≤ 10 bytes means near-silence. It can gate the VAD or a "you're talking"
light on the screen with no decoding at all. Room noise will raise the quiet-floor size: calibrate on T2.

### 4.3 Android MediaCodec decoding (works today)

- `MediaCodec.createDecoderByType("audio/opus")`, format `createAudioFormat("audio/opus", 48000, 1)`. Android's
  decoder always outputs 48 kHz; 3.6 logged "48000 Hz, 1 channel(s) decoded". SEEN.
- CSD, as `OpusCsd.csd()` builds them (`A/VoiceRoutes.kt:429`):

| Key | Bytes | Meaning |
|---|---|---|
| `csd-0` | `4F 70 75 73 48 65 61 64 01 01 38 01 80 3E 00 00 00 00 00` | OpusHead: version 1, 1 channel, pre-skip 312 (6.5 ms at 48 kHz), input rate 16000, gain 0, mapping family 0 |
| `csd-1` | `A0 2E 63 00 00 00 00 00` | codec delay 6 500 000 ns |
| `csd-2` | `00 B4 C4 04 00 00 00 00` | seek pre-roll 80 000 000 ns |

- Feed one 40-byte packet per input buffer, PTS += 20 000 µs. Code-3 padded packets are standard Opus; the decoder
  strips the padding. LAB (ffmpeg decodes them; Android uses the same libopus).
- The glasses' real encoder look-ahead is UNKNOWN; 312 only trims the first 6.5 ms. Harmless for speech either way.
- minSdk 30: the platform Opus decoder is always present.

### 4.4 Ogg Opus file (RFC 7845), to keep the glasses' own bytes

No re-encode, ~15× smaller than WAV, and the exact bits the glasses' mic produced.

1. **Page 0** (header type `02` = BOS, granule 0, sequence 0): one packet, the 19-byte OpusHead above.
2. **Page 1** (type `00`, granule 0, sequence 1): OpusTags =
   `"OpusTags"` + vendor length LE4 + vendor string (e.g. `P3 Companion`) + user-comment count LE4 `00 00 00 00`.
3. **Audio pages** (sequence 2…): up to 255 packets each; **50 packets (1 s) per page** is a good size. Each
   40-byte packet is one lacing value `28`. Granule position = **total packets so far × 960** (48 kHz samples,
   counting pre-skip). Last page: type `04` (EOS).
4. Page header: `"OggS"` `00` type, granule LE8, serial LE4 (one random value per file), sequence LE4,
   CRC LE4, segment count, lacing values. CRC-32: polynomial `0x04C11DB7`, initial 0, not reflected, no final XOR,
   over the whole page with the CRC field zeroed.

Size: 27 + 50 + 2000 = 2077 B per second. A 10 s question is ~21 KB, against ~320 KB as 16 kHz WAV.
LAB: a 3.02 s file built exactly this way from 40-byte packets was read by ffprobe as `opus, 48000 Hz, 1 ch,
3.020 s` and decoded without warnings.

Alternative on the phone: `MediaMuxer` with `MUXER_OUTPUT_OGG` (API 29+) and the same `csd-0`. The hand-written
writer is ~80 lines, JVM-testable against these numbers, and keeps padding untouched; prefer it.

### 4.5 Sending it to Gemini

- Inline part `{"inline_data": {"mime_type": "audio/ogg", "data": <base64>}}` with the existing strict transcribe
  prompt (`AiWire.geminiTranscribe` already takes a `mime` argument).
- Gemini's audio docs (as we last read them) list "OGG Vorbis" under `audio/ogg`; **Opus inside Ogg is not named.** Treat as UNKNOWN until
  T4 passes; keep WAV as the fallback on any 400. Gemini reduces audio to ~16 kbit/s internally, so the glasses'
  16 kbit/s SILK loses nothing extra.
- Keep the decode running in parallel: the VAD (end of speech), the level meter and "Play back what the glasses
  heard" need PCM; the upload needs only the packets. Cut the packet list at the same 300 ms before / 400 ms after
  the speech that 4.0 uses for WAV (15 packets / 20 packets).
- For a streaming voice chat (Gemini Live API, if adopted), the decoded 16 kHz PCM16 from `OpusPcm` is the input
  format such APIs take; check the current API docs before building it.

---

## 5. Dynamic route policy

### 5.1 Inputs the policy reads

| Signal | Source |
|---|---|
| Link ready / settling | `XkGlasses.state`, `readyForMs` (`AUDIO_SETTLE_MS` 3 s) |
| Live view on, photo transfer, probe | `preview`, `assembler`, `probing` |
| Proven routes | `VoiceChoice` scores (`GLASSES_SCO`, `GLASSES_SPP`, `PHONE`) |
| Session warm | `GlassesMicStream` running and `5760 = 1` |
| Battery / charging | `1003` |
| Noise floor | VAD floor dBFS of the last turn |
| Reply playing | `Speech`/`GeminiTtsSession` state |

### 5.2 Which mic and which speaker, when

| Situation | Mic | Speaker | Why |
|---|---|---|---|
| **Voice-chat session** (several turns, app on screen), no live view, `GLASSES_SPP` proven | **Glasses Opus stream, kept warm** for the session; idle-off after 60 s with no turn | **A2DP** (Gemini TTS at 24 kHz) | No call mode, best reply quality, the ~4 s start is paid once |
| Single quick question (tap from idle), `GLASSES_SCO` proven | SCO | SCO (or A2DP after SCO is released if the user chose media) | Opens in ~0.6 s |
| Opus stream refused / no packets in 4.5 s | SCO if proven, else phone | as above | Fallback, logged |
| Live view open | Phone mic (default). Option: "pause live view to listen": close `5720`, use the glasses mic, reopen after the reply | A2DP | Both glasses mics conflict with live view on another unit (T6 confirms for E13C-5) |
| Photo transfer running | Wait for `7500` (≤ 2 s) then proceed; or phone mic if the user is mid-sentence | A2DP | `audioBlocker` already refuses mic during a transfer |
| Link < 3 s old | Phone mic, or wait (existing `tapDelayFor`) | A2DP/phone | SCO right after connect dropped the link before |
| Battery < 20% | Phone mic | A2DP | Saves the glasses' radio |
| Glasses not linked | Phone mic | Phone speaker / whatever is connected | — |
| Conversation translate (long) | Opus stream warm (no call mode for minutes) | A2DP | Call mode for minutes blocks other audio on the phone |

Fallback order inside any row: chosen glasses route → the other proven glasses route → phone mic. Each failure
updates `VoiceChoice` scores as today. One line per turn in the log: route, why, time to first sound.

**Echo and half-duplex (Opus route):** the glasses' speaker and mic sit centimetres apart and the Opus route has no
platform echo canceller. While a reply plays: keep the stream running (no 4 s restart) but **do not feed the VAD or
the upload**; resume 150 ms after playback ends (the SDK's documented TTS tail). Barge-in is by touch, not by voice
(5.3). Test T3 measures whether the reply leaks in.

### 5.3 Touch controls the turn

Until T1 maps the gestures, every touch push (`C101`, `C107`) is one "tap" with a 600 ms debounce (the app already drops an exact
repeat within 1.5 s; a link drop followed one tap at 03:22:06):

| State when tapped | Action |
|---|---|
| Idle, no session | Start a session: choose the mic by 5.2; play a short "listening" earcon on the chosen output; start capture |
| Session warm, idle | Start capture at once (packets from the warm stream; `GlassesMicStream.ring`, 8 s, supplies the 300 ms before the tap) |
| Listening | End the turn now: cut at the last packet, send (Ogg or WAV) |
| Thinking (waiting on Gemini) | Cancel the turn |
| Speaking | Barge-in: stop TTS, start listening (Opus route: re-enable the VAD feed) |
| Memo recording | Stop the memo (as today) |
| Translate | Pause / resume (as today) |

Once T1 gives distinct gestures, the intended split is: single tap = the table above; double tap = end the
session (stop the Opus stream, release SCO); long press = hold-to-talk (start on press, send on release, using the
existing hold mode). An unsolicited `5760 = 1` (double press on the photo button, if T7 confirms it) = start a
phone-side voice memo from the `0x1F` stream, `5760 = 0` = finish it.

Background: today `onTouch` is cleared in `onStop`. With `keepConnectedInBackground`, a tap could post a
notification action "Ask" (mic access from the background needs the foreground service type `microphone`, which
must be started while the app is visible). Registering for `Intent.ACTION_VOICE_COMMAND` would also let an HFP
voice-dial key from the glasses (if they send one, T1) open the app's voice chat.

---

## 6. Sharper pictures: what the hardware allows beyond 4.0

4.0 sharpens one 640×480 JPEG (bilateral denoise, Lanczos 2×/3×, adaptive unsharp mask). The glasses cannot send
more pixels, so the remaining gains come from more frames and from undoing JPEG damage:

1. **JPEG-aware deblocking first.** Read the JPEG's DQT (quantisation tables) and apply a deblocking filter on the
   8×8 grid whose strength follows the actual quantiser step, before denoise and upscale. Upscaling and sharpening
   today also enlarge block edges. Cheap, deterministic, testable.
2. **Burst stack ("Sharp photo").** Take 3 photos back to back (~11 s at ~3.7 s each). Align them to the middle
   one (phase correlation on luma, then a per-tile shift), reject tiles that moved (a person walking), and average
   (noise falls by about √3), then the existing upscale/sharpen. Natural head micro-motion gives sub-pixel shifts,
   so a 2× fusion onto a fine grid recovers some real detail, not just smoothness. Offer it as a voice command and
   as a long press on the shutter button in the app. Skip it below 20% battery.
3. **Live-view stack** for when speed matters more than size: 8-16 consecutive 320×240 frames, aligned and merged to
   640×480. It will not beat a photo's detail, but it is instant and works while live view is open.
4. **Pick the sharpest.** Score each burst frame by variance of the Laplacian and drop the blurred ones before
   stacking; for a single photo, if the score is low, say "That one's blurry, hold still" and offer another.

Mark the results honestly in Gallery › Details, as 4.0 already does ("Enhanced from 640×480 … stacked from 3").

---

## 7. Top dynamic uses (ranked)

1. **Warm glasses-mic session**: 5750 Opus stream held for a voice chat, replies on A2DP, touch for each turn.
   Glasses mic and good speech output together, with no call mode.
2. **Send the glasses' own Opus to Gemini as Ogg** (~21 KB per 10 s, no re-encode), WAV as fallback.
3. **Touch as turn control** (start / send / barge-in / cancel), then gestures split once mapped.
4. **Route policy that reacts to live view, transfers, link age, battery and noise**, with SCO and the phone mic as
   ordered fallbacks and the free SILK-size voice-activity hint.
5. **Burst-stacked, deblocked photos** for "sharpen it better than you can".

---

## 8. Proposed tests on the device

Each is short, sends nothing destructive, and ends with **Diagnostics › Share log**. "Existing" means it can be run
on 4.0.0; "needs" names the small app change that makes the result visible.

| # | Unknown | Steps for Rick | Needs |
|---|---|---|---|
| T1 | Touch gestures and codes | Connect, open Diagnostics, keep the log on screen. On the touch pad do, 5 s apart and 3 times each: single tap, double tap, long press (2 s), swipe forward, swipe back. Then press the rear button once, and the photo button twice quickly. Share log. | Log every `C1xx` push with full payload hex and the time between pushes. Also log `ACTION_VOICE_COMMAND` / `MediaSession` keys if received |
| T2 | Opus quiet-floor size | Test voice (existing) on "glasses mic (data link)": stay silent 3 s, then speak. | Log a per-second histogram of SILK frame sizes |
| T3 | Opus + A2DP together, echo | Settings › voice preview playing on the glasses while Test voice listens on the data-link mic. Did packets keep coming? Did the preview show up in "Play back what the glasses heard"? | Existing (play back) |
| T4 | Gemini takes Ogg Opus | With a Gemini key: Test voice on the data-link mic, say "this is the voice test". | Send the same clip as `audio/ogg` and as WAV; log both transcripts, sizes and latency |
| T5 | SCO codec (mSBC or CVSD) | Test voice on "glasses mic (call audio)", say "six sixty six, sister Sue" (sibilants). Repeat on the data-link mic. Play both back. | Log the share of energy above 4 kHz for each clip (near zero = narrowband CVSD) |
| T6 | Mic vs live view on the E13C-5 | Open live view, then start Test voice on each glasses mic. Note whether video stops and what `5710` says. Then the reverse order. | Existing log lines (`5710`, `5750`, SCO state) |
| T7 | Double press = voice memo | App connected, no mic running. Double-press the photo button. Look for `5760 01` and any `0x1F` frames. Press once to stop. | Count unsolicited `0x1F` frames in the log |
| T8 | Volume path | Play the voice preview; step the phone's media volume from max to 1. Does the glasses' loudness change smoothly (absolute volume) or not at all? | Existing |
| T9 | Charging push | Put the glasses on the charger for 10 min with the app connected. Look for `1003 01 xx`. | Existing |
| T10 | Recording on the glasses | Glasses › Record 10 s, stop. Compare `5713` before and after. | Existing (counts are logged) |
| T11 | Wear / fold sensor | With the log open: take the glasses off, fold them, wait 30 s, unfold, put them on. Any new node or `0x2B` frame is a lead. | Existing; better with T13's hex logging |
| T12 | Indicator light | Look at the frame while taking a photo, during live view and during the data-link mic. Note any light and its colour. | Observation only |
| T13 | Raw setup bytes | Reconnect once. | Log `7100` (READ, act 1, in setup after `7110`), the full `1001` JSON, and `0x2B` frame hex (redact `mac_addr`) |
| T14 | Photo during live view | With live view open, tap Take photo. Does the stream pause, and does the photo arrive whole? | Existing log lines |
| T15 | Wake word | With the app connected and the screen on, say "Hey" / "Hi" / "Xiao" style wake words near the glasses. Any `C101`/`C107`? | Same as T1 |

Report back the shared logs; each result moves one UNKNOWN row above to SEEN.

---

## 9. T13 results (2026-11-28)

Rick's 4.1.1 Hardware test T13 on the E13C-5, firmware 1.0.0, battery 27%. The app sent only reads (`7110`,
`7100`, `1001`, `1003`, action 1, no data). `dev_id` and `mac_addr` were withheld from the report and are not
recorded here. Labels in this section: **SEEN** = the bytes in Rick's log; **INFERRED** = follows from SEEN bytes
plus the DOC sources in section 0; **GUESS** = a plausible reading with no direct evidence, to be tested (9.7).

### 9.1 Raw bytes (SEEN)

| Node | Reply data |
|---|---|
| `7110` | `10 37 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 64 00 02 08 0C` (22 B, byte-identical to the 3.1 log) |
| `7100` | `10 FF FF 4B 0A 5F A3 0D 04 00 00 00 00 00 00 00 00 64 00 02 08 0C` (22 B) |
| `0x2B` after bind | payload `01 10 03 F0 60 00 00` (7 B) |
| `1001` | `prod_mode E13C-5`, `soft_ver 1.0.0`, `prod_category 01`, `prod_subcate 01`, `battery_main 27`, `dial_ability 2`, `screen w320h380`, `ch 304`, `cw 320`, `nch 304`, `ncw 320`, `screen_shape 0`, `preview_width 160`, `preview_height 120`, `offline_asr_auth 1` |
| `1003` | `00 1B 00 00 00 00 00 00 00 00` |
| Earlier logs | `2410` = three 6-byte NUL-padded slots `en`, `en`, `zh-cn`; `2420` = `en`; `57A0` refused (status 03); live video SPS 320×240 |

### 9.2 Shared layout of `7100` and `7110`

Read with the documented `7100` layout `[ver 1][mask 16][maxContacts LE2][sideButtons 1][2 more bytes]`
(PROTOCOL-NOTES 2.3):

| Bytes | `7100` | `7110` | Reading |
|---|---|---|---|
| 0 | `10` | `10` | Struct version 0x10. SEEN (equal in both). |
| 1-16 | `FF FF 4B 0A 5F A3 0D 04` + 8×`00` | `37` + 15×`00` | The two masks. SEEN. |
| 17-18 | `64 00` | `64 00` | `maxContacts` = 100 (LE). INFERRED from the DOC layout. A phone-book limit for a watch; the glasses have no contacts screen, so a leftover. GUESS that it does anything. |
| 19 | `02` | `02` | `sideButtons` = 2. INFERRED (DOC calls it real); fits the photo button plus one more (rear/touch). Which two is UNKNOWN. |
| 20-21 | `08 0C` | `08 0C` | Two more small values (8, 12) or one LE2 (3080). UNKNOWN. GUESS: two more watch limits (counts of something like alarms or dial slots). |

The shared tail `64 00 02 08 0C` is identical in both replies. INFERRED: the firmware fills both answers from the
same capability struct and only the 16-byte mask differs, so the tail tells nothing glasses-specific and must not
be read as part of the `7110` flags. The tail is also byte-for-byte the one on the E13C-1 unit (FULL-RES-PLAN),
so it is a firmware constant, not a per-unit setting. INFERRED.

### 9.3 `7100` mask bits

Reading the 16 mask bytes LSB-first (bit 0 = byte 1 bit 0), 36 bits are set. SEEN bytes; the bit order itself is
still unverified (DOC).

- Set: 0-15 (all), 16, 17, 19, 22, 25, 27, 32, 33, 34, 35, 36, 38, 40, 41, 45, 47, 48, 50, 51, 58. Bytes 9-16 are zero.
- The five positions the DOC names all agree with this unit: 22 camera preview = 1, 23 video transfer = 0,
  47 language = 1, 49 app volume = 0, 58 reboot = 1. SEEN for this unit. Because those DOC positions came from the
  same LSB-first reading of another unit, the agreement shows the two units match there; it does **not** prove
  the bit order. INFERRED.
- Most of the 36 claimed features can't exist on camera glasses with no display (a watch's 16 base features in
  bytes 1-2 are all `FF`; notification nodes `1004`/`4700` are INVALID_URN or silent on other units). INFERRED:
  `7100` is the watch platform's template, not a statement of what works. **Do not gate any app feature on it.**
  Names for the other 31 bits: UNKNOWN (no source in the repo names them).

**Bits set in `7100` but not in `7110`** (same positions compared): 3, 6, 7, 8-15, 16, 17, 19, 22, 25, 27,
32-36, 38, 40, 41, 45, 47, 48, 50, 51, 58 (31 bits). Every `7110` bit (0, 1, 2, 4, 5) is also set in `7100`,
but only because `7100` byte 1 is `FF`. INFERRED: the two masks are different namespaces (watch functions vs
glasses flags), so the "subset" is a coincidence and carries no meaning.

### 9.4 `7110` glasses flags

`0x37` = `0011 0111`: bits 0, 1, 2, 4, 5 set; 3 and 6 clear; bit 7 and bytes 2-16 zero. SEEN. Seven flags are
documented (alphabetically, not in wire order): `noStorageDevice`, `supportAiChat`, `supportFunctionVersion`,
`supportScoLink`, `supportVolcEngine`, `supportWakeWord`, `supportZlsyEngine`, so seven bits fit bits 0-6 exactly.
INFERRED. What behaviour says about each:

| Flag | Expected value | Why |
|---|---|---|
| `noStorageDevice` | 1 | E13C-1 with identical bytes reports 1 (DOC); `5713` counts stay 0. INFERRED |
| `supportScoLink` | 1 | SCO mic works on this unit (2.6, SEEN). INFERRED |
| `supportAiChat` | 1 | The vendor app offers AI chat on these glasses (DOC). GUESS |
| `supportVolcEngine` / `supportZlsyEngine` | one of the two | Two alternative cloud speech/AI engines; a unit normally ships with one. GUESS |
| `supportWakeWord` / `supportFunctionVersion` | one of the two | Whatever is left for the fifth set bit. GUESS |

So which of bits 3 and 6 is the clear flag can't be told from one unit's bytes; test T18 (9.7) is the way.

### 9.5 `1001` fields

- `battery_main 27` equals `1003` `00 1B` (not charging, 0x1B = 27%). SEEN. The `1003` layout `[charging][level]`
  + 8×`00` holds. SEEN.
- Every key and every non-identity value except `prod_mode`, `soft_ver` and the battery is the same as on the
  E13C-1 (`tools/make_vectors.py` vector from the SDK's documented reply). SEEN (comparison). INFERRED: these are
  firmware-template constants, not measured per device.
- `screen w320h380`, `screen_shape 0`, `cw 320`/`ch 304`, `ncw 320`/`nch 304`, `dial_ability 2`: a watch's screen
  (320×380, rectangular), dial (watch-face) canvas and dial capability. The glasses have no display. INFERRED
  leftovers. GUESS for the details: `cw`/`ch` = watch-face canvas (380 − 304 = 76 px status area),
  `ncw`/`nch` = a second canvas (e.g. notification or "new" dial), `dial_ability 2` = level of custom watch-face
  support.
- `preview_width/height 160×120` vs real video 320×240 (SEEN): exactly half in each direction. GUESS: the size of
  a watch's camera-remote preview window, another leftover. Keep ignoring it for live view (as today).
- `prod_category 01` / `prod_subcate 01`: a product family code, same on both units. INFERRED; what 01/01 means
  is UNKNOWN.
- `offline_asr_auth 1`: "offline speech-recognition authorisation" = the firmware holds an **activated licence
  for an on-glasses offline speech engine**. INFERRED from the name. Together with the `supportWakeWord` flag
  this most likely means on-glasses keyword spotting: a wake phrase and perhaps a few fixed offline commands
  (photo, record, volume) that work without the phone or the cloud. GUESS for the command set. GUESS: the `2410`
  list (`en`, `zh-cn`) is the languages its models cover, and the first slot repeating `en` is the current one. Whether the wake word is on by default, and its phrase, are UNKNOWN; no repo doc names a phrase.

Known nodes and paths that could carry a wake/offline-command event (none has yet shown one unasked on this unit):

| Path | Evidence | Relation |
|---|---|---|
| `C101` push | DOC: voice-assistant trigger on other units; never SEEN here | Most likely wake-word event. GUESS |
| `C107` push | SEEN on every touch; SEEN once when the glasses started their own audio (`102A` probe) | Possible "assistant started" event. INFERRED/GUESS |
| Head `0x1F` mic stream started by the glasses | Handled since 4.1 (`unsolicitedMic`, `MIC_HEAD = 0x1F`); never SEEN | After a wake word the glasses would stream the question. GUESS |
| `5760 01`/`00` | SEEN only around the app's own `5750` | Unasked `5760 01` = the glasses opened their mic. INFERRED |
| `57B1` + `7320` | SEEN on the photo button | An offline "take a photo" command would look exactly like a button photo. GUESS |
| `0x2B` frames | SEEN once at sign-in only | The vendor cloud assistant runs here (DOC); a wake word in the vendor flow may produce one. GUESS |
| `2410`/`2420` | SEEN reads | Language of prompts, maybe of the offline models. GUESS |

### 9.6 The `0x2B` frame

`01 10 03 F0 60 00 00`: no ASCII, no `FGS`/`FND` text, and 7 bytes is too short for a key or token. SEEN. So it is
not the documented credential exchange itself. INFERRED. The bytes `F0 60` also appear in the app-side `0x2B`
example `F0 60 01 00` (rf/04 F12), and app-side frames start `01 00 <seq>` (rf/05 F13). INFERRED: a common
sub-header. GUESS at the layout: `[01 version][10 direction or "from glasses"][03 sequence][F0 60 message id LE2]
[00 00 value]`, i.e. the glasses answer message 0x60F0 with 0 where the vendor app sends 1, perhaps "cloud AI
session not open" (this app never opens it). T16 checks whether byte 2 counts and whether the rest is fixed.

### 9.7 New device tests (each read-only or listen-only)

| # | Confirms | Steps for Rick | Needs |
|---|---|---|---|
| T16 | `0x2B` layout (9.6) | Arm T13, reconnect; repeat three times, once at a different battery level. Share log. | Existing (T13 logs the 2B hex) |
| T17 | Wake phrase and offline commands (9.5) | Open Diagnostics › Hardware tests › T15. If LensMoo's settings or the P3 manual name a wake phrase or voice-wake switch, note it (look only; close LensMoo after). Near the glasses say that phrase 3 times, 5 s apart; then "Hey", "Hi", and "take a photo" / "take a picture" 3 times each. Repeat once with the prompt language `zh-cn` only if the manual's phrase is Chinese. | Watch for `C101`, `C107`, `5760 01`, `0x1F` audio, `57B1` with no button press, any new `0x2B`. Add `57B1` and `2B` lines to T15's results list |
| T18 | Which `7110` bit is `supportWakeWord` (9.4) | Only if LensMoo has a voice-wake switch: turn it off in LensMoo, close LensMoo, arm T13 here, reconnect. Turn it back on and repeat. | Existing. A bit that flips in `7110` (or `offline_asr_auth` in `1001`) names the flag; no change means the flag is static |
| T19 | `1017` sound mask on this unit | Arm T13 and reconnect after the app also reads `1017` (action 1) once. | Small change: add a `1017` read to the T13 setup reads. A wake-word on/off bit may live here. GUESS |

### 9.8 App changes worth making

- Log the `0x2B` hex on every sign-in, not only under T13 (7 bytes, read-only). Needed to see it change.
- Never gate a feature on `7100`; keep behaviour-based checks (storage from `5712`/`5713`, live size from the SPS).
- T15: also list `57B1` without a button press and any `0x2B` frame, so an offline "take a photo" or a vendor
  wake event is visible.
