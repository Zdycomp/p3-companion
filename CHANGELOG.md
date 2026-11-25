# Changelog

## 3.8.0 (versionCode 11)

Goal: answer "where are my 20 MP photos?" with measurements, and a Wi-Fi controller. The owner's Bluetooth photos
are 54-75 KB (the SDK says 640x480); the glasses show up as a drive in the Import tab over the charging cable.
Plan and safety rules: docs/FULL-RES-PLAN.md (Theoretical Physicist). Live view (30 fps), photos, the voice
routes, translation, SupRevolt and connect are unchanged.

**Photo size everywhere**
- `JpegInfo`: pure-Kotlin JPEG frame-header reader. From FFD8 it walks the `FF xx [len BE2]` segments and stops at
  SOS; SOF0-SOF15 except C4/C8/CC give width, height, components and sampling (4:2:0, 4:2:2, 4:4:4, grey). It
  counts SOFs, notes EXIF (APP1) and an EXIF thumbnail, counts SOIs in the file, and gives null (size unknown,
  never blocks saving) for anything unreadable.
- Every Bluetooth photo logs `Photo WxH, N KB (0.31 MP, 4:2:0, baseline, 1 SOF, no EXIF, 1 picture) in 6 parts,
  … ms, … KB/s, … retries`; the last 30 sizes are kept (`Prefs.bluetoothPhotoSizes`).
- Import: each photo's first 512 KB is kept during the plain stream copy (no decode, no recompression, EXIF kept)
  to read its pixel size (JPEG header, else Android's header-only decode for PNG/WebP), and the gallery copy is
  read back and compared by CRC-32 ("byte-for-byte copy"; a mismatch is reported, not failed, since Android may
  hide location data from apps). The result lists every file (`ImportedFile.line()`: name, WxH, KB) and one line
  `Cable photos: 1280x960 avg 400 KB; Bluetooth photos: 640x480 avg 70 KB` (`PhotoStats`), plus the pixel ratio
  when both sides have a clear size. The last 30 cable sizes are kept (`Prefs.cablePhotoSizes`).
- Gallery: press and hold › More › Details shows the real pixel size (from the photo's own header), MP,
  sampling, EXIF, file size and date (video: MediaStore width and height).

**Find full-size photos** (Diagnostics, opt-in, about 90 s; `XkGlasses.runFullResProbe`, `FullRes`)
- Exactly the plan's probe list: 5713/5712 before and after one normal 57B0 photo (raw 7320 bytes, each part's
  type and size, SOF size logged); one 7300 action 2 `[N+1]` during that transfer, before its 7500; action-1
  reads of 7200, 7310, 7600 with no data and with `[00]`; action-2 index tries only for 7200/7310 (`[00]`,
  `[01]`, `[00 01]`, `[photo_num]`) when no read said INVALID_URN, and `[00]` for 7600 only when it answered
  with something else; the Wi-Fi look (`WifiFinder`) during steps 4-5; a final 7500 and 5713.
- Hard allow-list in code (`FullRes.allowed`), checked before every probe request: never 7400, 102A, 102C, 5610,
  5620, 9000, 9001, 1017, 102E; reads only on the plan's nodes; action 3 only 57B0 and 7500.
- Per request: 3 s for the reply, 3 s more listening; logs type (binary/JSON/no data/status), status name,
  bytes, the first 64 bytes or the JSON, unsolicited pushes, and the 0x4A frames (count, bytes, largest). A 7320
  is downloaded into `P3_probe-<node>_…jpg`; stray 0x4A is collected (20 s / 2 MB at most) and any JPEG in it
  saved and measured; 7500 always follows media; then 1.5 s of quiet. Each request is persisted before it is
  sent; one that dropped the link is skipped in later runs.
- Ends with one line, e.g. "Bluetooth photos are 640x480; no larger copy found. The photo count didn't rise: the
  glasses keep no photos." Live view reopens afterwards if a screen wanted it.

**Wi-Fi screen** (Glasses tab and Settings; `WifiScreen`, `WifiControl`, `WifiMath`)
- The phone's Wi-Fi: on/off, network name, signal (dBm and words), link speed, frequency with band and channel,
  address, gateway, internet. Refreshes every 3 s. Wi-Fi panel shortcut (`Settings.Panel.ACTION_WIFI`). The app
  never turns Wi-Fi on or off or changes saved networks.
- Permissions asked only when the screen opens, none required: Nearby Wi-Fi devices (13+, never for location)
  and location (Android shows Wi-Fi names and scan results only to apps with it). The manifest drops the
  `maxSdkVersion=30` on ACCESS_FINE_LOCATION and adds ACCESS_COARSE_LOCATION, ACCESS_NETWORK_STATE and
  CHANGE_NETWORK_STATE.
- Find the glasses on Wi-Fi: a scan plus `WifiP2pManager.discoverPeers`, about 8 s; networks and Wi-Fi Direct
  devices listed with matches first and highlighted (`WifiMatch`: the glasses' name, model, last 4 address
  digits, P3, E13C, D6BE, XK, Shenju, NovaSight; words of 3 letters or fewer must stand as words, so "AP3000"
  and "XKCD" don't match).
- Connect: an access point through `WifiNetworkSpecifier` (`requestNetwork`, local-only, no internet asked;
  Android confirms), with a password the person types in the app's field for WPA2/WPA3 (used once, never saved
  or logged; the Wi-Fi panel is offered instead, and is the only way for WEP/enterprise); a Wi-Fi Direct peer
  through `WifiP2pManager.connect` (PBC). Disconnect releases the request or removes the group; leaving the
  screen disconnects too.
- Look for services: the glasses' side (gateway, else DHCP server, else .1), TCP ports 80, 8080, 554, 8554, 7070,
  5000 with 1 s timeouts; `GET /` on web ports and `OPTIONS` on RTSP ports, the first 200 characters logged. The
  app's process is bound to that network only while this runs. No credentials are ever sent.

**Tests**: 37 new JVM tests (276 in all): `JpegInfoTest` (real JDK-encoded baseline 16x8, progressive 640x480,
grey, EXIF with a thumbnail and APP2/APP13/COM before the frame header, fill bytes, hand-built SOFs and sampling,
cut-off and non-JPEG input), `PhotoStatsTest` (the summary line and its rounding, mixed sizes, settings round
trip, import file lines), `FullResTest` (the deny/allow list, the plan's index tries, reply and summary text,
JSON counts, dropped-request bookkeeping), `WifiMathTest` (glasses-name matching, signal/band/channel/security,
password length, gateway choice, port-probe requests, lines and summaries).

## 3.7.0 (versionCode 10)

Goal: the glasses' own mic as a usable voice input. Owner's 3.6.0 log (moto g 2025, E13C-5): the phone mic worked;
call audio (SCO) connected in 751 ms but the recognizer answered ERROR_TOO_MANY_REQUESTS; the data link (5750,
head-0x1F Opus) delivered 700 packets but the piped recognizer answered ERROR_NO_MATCH. Live view, photos,
connect, the phone-mic route, SupRevolt and translation are unchanged.

**Call audio (SCO)**
- One SpeechRecognizer app-wide (`RecognizerGate`, `Speech`): a fresh recognizer per attempt, made only when none
  exists and 300 ms after the last was cancelled and destroyed; destroyed as soon as its listen ends (3.6 kept the
  phone-mic test's recognizer alive and reused it); `cancelListening()` destroys too; a new `listen()` ends the one
  before; a recognizer never gets a second `startListening()`.
- `listen(networkFirst = true)` for call audio: Google's network recognizer (`com.google.android.googlequicksearchbox`)
  first, then the remembered one, the on-phone one and the rest. The recognizer opens the mic itself with the glasses
  as the communication device. `ERROR_TOO_MANY_REQUESTS` moves to the next service (after the cooldown).
- Every attempt is logged with the service, its kind (Google network / on-phone / service) and its result.

**Data link (5750, head 0x1F)**
- Record layout checked against the log: a frame is one type byte `02`, then whole records `[len 0x28][40-byte
  Opus packet]` (411 B = 1 + 10 × 41, 83 B = 1 + 2 × 41; TOC 0x48 = SILK WB 20 ms code 0, 0x4B = code 3 with
  padding, e.g. `4B 41 1E` = 1 frame, 30 bytes of padding). 3.6 read the frames as one byte stream, so each frame's
  `02` was "skipped" and unlocked the framer, and the first frame's second packet was lost (the 112 B skipped).
  `MicRecords.split` now splits each frame on its own (`OpusFramer.feedRecords`); it also takes `02` before every
  record.
- Opus decoder setup (`OpusCsd.csd`): OpusHead with pre-skip 312 (6.5 ms at 48 kHz) and input rate 16000, csd-1
  codec delay 6 500 000 ns (3.6: pre-skip 0 and delay 0), csd-2 seek pre-roll 80 ms.
- `Resampler`: Blackman windowed-sinc low-pass (97 taps at 48 kHz, cut-off 6.8 kHz) before the rate change, so
  48 kHz → 16 kHz is a low-pass and decimation by 3 (3.6 averaged 3 samples: 11 kHz came through at -7.5 dB, now
  under -50 dB). Non-whole ratios interpolate between filtered samples.
- Pipe (`PcmPipe`, `PipePacer`): 16 kHz mono PCM16 little-endian written in real time every 20 ms, after 200 ms
  of pre-buffer, at most 100 ms ahead. Silence only when the input stalls for over 300 ms (3.6 slipped 20 ms of
  silence in after every 60 ms without data, between the glasses' 200 ms bursts). The write end stays open until
  our end-of-speech detector ends it. Encoding, channel count and sample rate extras set. Piped audio goes to
  Google's network recognizer first and never to the on-phone one.
- Pipe self-test (Test voice): 3 s of the phone's mic recorded with AudioRecord and piped in. If that gives no match
  while the recognizer's own phone mic worked, `Prefs.pipeState` = UNUSABLE and pipes are no longer used
  (`VoiceChoice.pipeVerdict`). Test voice forgets the 3.5/3.6 per-service "no pipe" marks before it.
- Gemini route (`VoiceChoice.sppMode`): when the pipe is unusable (or Android < 13) and a Gemini key is set, the
  glasses' audio is recorded with our VAD (`Vad.clip()`: 800 ms of quiet after speech, at most 15 s), sent to
  `gemini-2.5-flash` as inline `audio/wav` base64 with "transcribe exactly, reply with the text only"
  (`AiWire.geminiTranscribe`, `AiClient.transcribe`, `AiWire.parseTranscript`, "(no speech)" → nothing heard). The
  log and the Assistant screen say so. With only a Claude key: "the glasses' data-link mic needs a Gemini key
  (Claude doesn't take audio)" and the phone mic is used. A tap while listening ends the recording.

**Test voice**
- Order: phone mic, glasses call audio, glasses data link (pipe self-test, the glasses clip through the pipe, then
  Gemini), with 1.5 s between routes. The default is a glasses route if one worked (call audio first, it opens in
  under a second; the data link takes about 4 s), else the phone mic (`VoiceChoice.defaultRoute`). `routeOrder` now
  puts proven call audio before the proven data link. One summary line, as before.
- "Play back what the glasses heard" (Diagnostics): the last 8 s from each glasses route (the data-link stream's
  ring buffer, the call-audio recording) saved as WAV in `cache/voice/`, played with MediaPlayer on the phone's
  built-in speaker (not the glasses, which are the media output), and Share (ACTION_SEND_MULTIPLE through
  `ClipProvider`, a framework-only read-only provider serving `cache/voice/*.wav`).

**Tests**: 24 new JVM tests (`GlassesMicTest`): the 1F splitter with the log's exact byte prefixes and built frames,
the old framer's loss shown, OpusHead/CSD, the resampler (tone in at 440 Hz/1/3 kHz → same frequency and level out,
11 kHz removed, pieces = whole, 44.1 kHz), the WAV writer, the clip ring, the clip VAD, the recognizer gate, the
pipe pacer, `sppMode`, `pipeVerdict`, `defaultRoute`, the network-first service order, the Gemini request and reply,
the clip provider's names. Three 3.5 tests changed with the behaviour (call audio first, no on-phone recognizer for
piped audio, the filter's 16-sample lag).

## 3.6.0 (versionCode 9)

The owner asked for the best real-world live video and a translation log that can be read or spoken back.
Live video at 30 fps on head 0x1E (5720 action 3 `[01]`, media ack per fragment), connect, photos, the 3.5 voice
routes and SupRevolt are unchanged.

**Conversation translate** (`Conversation.kt`, `ConversationScreen.kt`, `Assistant` `Mode.CONVERSATION`)
- A continuous loop on the 3.5 voice routes (the chosen mic; a conversation keeps the mic that worked and, on the
  glasses' call audio, keeps the link up between phrases without re-measuring it). Partial results show greyed;
  each final result is translated with the configured AI. Silence just listens again; three real problems in a row
  pause it with the reason. Never opens the system speech dialog.
- Reply format `[Language]` + translation (`ConversationWire`), so every entry has the detected language. One-way,
  auto-detect, or two-way (`TwoWay`: A→B, B→A, anything else →B; the next phrase is listened for in the other
  language, and after silence too).
- Speaks each translation in its target language (`speech.speak`, on the call link when it is held, otherwise
  media audio: the 3.5 low-latency route). Listening pauses while it speaks so it never translates itself.
- `TranslationStore`: one app-private JSON-lines file per session (`files/translations/conv-<date>-<n>.jsonl`),
  appended per phrase on its own thread; listed newest first; read back in order; export as text; delete one
  (confirm); delete all (confirm). API keys, the SupRevolt token and the glasses key are replaced with `[hidden]`,
  and key-shaped strings (Gemini, Anthropic, OpenAI, bearer) are too.
- No AI key: the original words are logged with "Not translated: no AI key" and the setup card is shown.
- Pause/Resume, Stop, swap languages, "Listening for" chips in two-way, a tap on the glasses pauses or resumes.
  Leaving the screen or the app stops it unless "Keep translating in the background" is on
  (`ConversationService`, microphone foreground service, notification with Stop).
- Voice commands: `tr.conversation` ("translate conversation", "start translating", …; "start translating" moved
  from `tr.start`) and `tr.conversation_stop` ("stop translating"). `VoiceCommands.isConversationStop`.

**Live view**
- Picture: frame sized to the SPS aspect ratio (`ViewMath.frameHeight`), Fit/Fill, rotate 90/180/270, pinch zoom
  (1-5×) with pan clamped to the picture, double-tap zoom/reset; the TextureView transform does the scaling
  (filtered). Full screen: landscape, system and app bars hidden, controls overlay hidden after 3 s. The activity
  now handles orientation changes itself (`configChanges`), so turning the phone never tears down the video or a
  recording; other screens are rebuilt in place.
- Latency: the decoder queue is 10 pictures (was 30); output is collected every 4 ms while pictures are inside the
  decoder and only the newest decoded picture is rendered (late ones are released unrendered and counted).
  "● Live · ~N ms" in the stats (last fragment → render, smoothed); the 5 s fps/kbps line stays.
- Snapshot: `TextureView.getBitmap(w, h)` at the SPS size (the view transform isn't applied), turned like the
  screen, optional 2× smooth upscale and unsharp mask (`ViewMath.unsharp`) on the copy only, JPEG 95 with size.
- Recording: native size as before, plus optional AAC-LC 44.1 kHz mono from the phone's mic (`MicAac`,
  `AacCsd`), timed on the same clock as the video.
- Auto-reconnect: on a link drop the screen saves any recording, says "Bluetooth dropped. Reconnecting…", asks
  for a reconnect itself if auto-reconnect is off, and opens live view again 1 s after the glasses are back.
- "Try higher quality" (opt-in, Live view and Diagnostics; `XkGlasses.runQualityProbe`, `VideoQuality`): with the
  preview closed, 5720/<saved action> with `[01]` (baseline), `[01 01]`, `[01 02]`, `[01 03]`, `[02]`, `[03]`, 3 s
  each, SPS size / fps / kbps logged, always closed with the same node/action `[00]` and 1.5 s apart. 2410/2420
  (language tags) are never written. A bigger SPS at >= 15 fps becomes Quality "High WxH" (saved; the open then
  sends that data); otherwise "The glasses send 320×240 only". Live view reopens with the chosen (default
  Standard) setting afterwards.

**Tests**: 215 JVM tests (31 new: session store, two-way choice and reply format, the new voice commands, SPS
sizes for probe results and the probe's choice, aspect/zoom/pan math, unsharp mask, AAC config).

## 3.5.0 (versionCode 8)

From the owner's 3.4.0 log (`docs/LOG-2026-11-25-v340.txt`, moto g 2025, Android 15, E13C-5 fw 1.0.0).
Live video at 30 fps is kept as it was (5720 action 3, 0x1E reassembly, media ack per fragment). Connect, bind,
reply matching, photos, SupRevolt and the 48 voice commands' matching are unchanged.

**Voice: why it never worked on the moto g (best reading of the log)**
- Glasses mic at -83 dBFS: digital silence, so the SCO link never carried audio. `AudioRoute` in 3.4 declared the
  link ready after 1.5 s with no SCO or headset signal at all ("assumed ready … no link signal") and then recorded.
- Phone mic: `ERROR_NO_MATCH` twice from "Speech Recognition and Synthesis" (Google's on-phone recognizer). The phone
  mic was the app's own `AudioRecord` piped in through `EXTRA_AUDIO_SOURCE`, started while the phone was still in
  `MODE_IN_COMMUNICATION` with the glasses as the call device. Either that recording was also routed to the dead SCO
  input, or this recognizer doesn't accept the pipe; either way it heard nothing. And because the error came after
  "ready", that service was saved as "the one that works" and tried first again.

**Voice: what 3.5 does**
- `VoiceRoutes.kt` (pure, tested): `MicRoute` (glasses data link, glasses call audio, phone), `VoiceChoice` (route
  order and scores, speech service order, what to do on each recognizer error), `Vad`, `OpusFramer`, `OpusCsd`,
  `Resampler`, `RateLimit`, `LiveDebounce`, `LiveResync`.
- Phone mic: no pipe. The recognizer opens the mic itself. Service order: the one that last gave words, Google's
  network recognizer (`com.google.android.googlequicksearchbox`), the other Google ones, the on-phone one, the rest,
  the default. A `NO_MATCH` with no partial words hands over once to the next service; if that one understands, the
  first goes on `recognizer_no_match` (tried last). Only words make a service the remembered one.
- Glasses call audio (SCO): ready only on `ACTION_SCO_AUDIO_STATE_UPDATED = CONNECTED`, the headset's
  `STATE_AUDIO_CONNECTED`, or the communication device switching to the glasses (+300 ms). `setCommunicationDevice`
  first, then `startBluetoothSco`, 2 s each; the one that gives a live mic is saved (`recognizer_sco_method`). Every
  state change is logged ("Voice: SCO: …"). Before a question, 0.4 s are measured on the glasses' input; below
  -60 dBFS the next mic is used. The recognizer then records the mic itself (routed to the call device).
- Glasses data-link mic (new): `5750` action 3 `[01]`/`[00]`. Where the Opus bytes travel is still unknown
  (PROTOCOL-NOTES 4.1), so while the stream is on every non-photo, non-video carrier (0x4A frames and elements of
  other types, 0x1E non-video elements, 0x2B, unknown heads, long 0x30 pushes, unframed bytes) is searched for
  `[28][40 bytes]` packets; the first that lines up three in a row is used and logged ("Opus packets found in …").
  Decoded with MediaCodec `audio/opus` (OpusHead, codec delay, 80 ms pre-roll), resampled to 16 kHz mono and fed to
  the recognizer through a pipe (`EXTRA_AUDIO_SOURCE` + encoding PCM16, 1 channel, 16000 Hz). The pipe is written
  continuously (20 ms of silence after 60 ms without sound) and closed by our `Vad` 1 s after speech ends (also after
  6 s without speech, or 12 s). `ERROR_CLIENT`, `ERROR_AUDIO`, or `NO_MATCH` while our VAD heard speech marks that
  service `recognizer_no_pipe` and the next mic is used.
- Route order (`recognizer_routes`): routes that worked first (data link, then call audio), call audio when not known,
  the phone's mic always last. A failure counts -1; two in a row skip the route until it works again or Test voice.
  The Assistant screen says which mic the next question uses and which one is listening.
- Diagnostics › Test voice: per route, 3 s recording with RMS dBFS (and the input Android used), then "Say: hello
  glasses" with the text heard or the error and the speech service. One summary line, saved and used as the default.

**Live view**
- The photo at 00:40:02-08 was not sent by the app: every request is logged as `→ node` and there is no `→ 57B0`, and
  the link thread was inside the open's busy wait (5710 = 02) when 57B1 came. Busy at open plus a shutter 2 s later is
  the glasses' own button (57B1 follows a trigger by about 2.6 s); the app downloaded it as a button photo, as it
  must (7320 needs 7300/7500). Now: such a 57B1 is logged as the glasses' own shutter, the photo is saved, and live
  view opens again afterwards. While live view is opening, on, or about to close, the snapshot view's automatic
  photos are refused (`XkGlasses.autoPhotoAllowed`); only a photo the person asked for is taken.
- Open/close churn: `closePreview()` waits 1.5 s; an `openPreview()` meanwhile keeps the stream running, sends
  nothing, and replays the last SPS and key picture to the new screen. An open within 1.5 s of a real close waits
  until 1.5 s after it. From the Live view screen the live view test runs with `keepOpen`: video it finds stays open.
  The mic and the snapshot view close at once (`immediate`); `micWillStart` now really sends the close (3.4 marked
  the preview off first, so the close was skipped).
- "Dropped an unfinished picture": logged 3 times per open, then once per 10 s with a count. No command is known to
  ask the glasses for a key frame, so the decoder waits for the next IDR, at most 2 s (`VideoPipeline.resync`).

## 3.4.0 (versionCode 7)

From the owner's first live video over Bluetooth (3.3.0, `docs/LOG-2026-11-25-liveview.txt`, E13C-5 fw 1.0.0).
Connect, pairing, sign-in, reply matching, photos, voice and SupRevolt are unchanged.

**Live video on head 0x1E (decode verified against the log's bytes)**
- `0x1E` is a known frame head now (it was only taken while the sniffer ran). Its divide is the `0x4A` layout:
  low 3 bits first/middle/last (`9D29 9D2A 9D2B` = 1, 2, 3), higher bits the element length with its header
  (`0x59D` = 1437 = 1432 + 5). The high byte is the length's low byte, not a picture id.
- `LiveStream`: fragment payload `[index LE4][chunk]`; index 0 starts a picture, the chunks join into
  `[02][length LE4][Annex-B]` and the length (body only; the header-counting reading is accepted too) closes it.
  A gap, an index without a start, or a new index 0 before the end drops the unfinished picture cleanly. Other
  element types are counted and logged. Runs on the reader thread, copies bytes only, never blocks.
- Whole pictures go to the existing MediaCodec Surface decoder and the MP4 recorder. Size comes from the SPS
  (the owner's: Baseline, level 4.0, 320×240). Decoding and recording start on an IDR, or on an all-I-slice picture
  that carries its own SPS and PPS (`H264.sliceType`, `VideoAu.entry`).
- Acks for `0x1E` (`LiveAck`, `LiveAckPicker`): a `1E 0009 [01]` per fragment echoing its order and divide, then a
  photo-style `1E 0009 [01]` per picture, then `1E 0004 [01, order]`, then none. Each gets 5 s of video; the first at
  2 fps or more is kept and saved (`live_ack`, with each one's fps in `live_ack_report`); if none reaches it, the best
  is kept. A saved one that falls under 2 fps is searched again once per session. Photo (`4A`) and control (`30`) acks
  are byte-identical to 3.3.
- Live view screen: size, fps and kbps (5 s averages), the ack in use and skipped pictures. The log gets the same once
  per 5 s, the first picture's size and NAL types, and every ack switch.
- If the glasses push `5710 = 00` while Live view is on screen, the app opens once more with the same command
  (5720, saved action, `[01]`); again only after 30 s of video. Leaving the screen still closes it with `[00]`, and a
  photo during live view still closes and reopens it.
- No more "a head-1E frame not used" sniffer lines: 1E frames go to the assembler. The sniffer stays, rate-limited,
  for heads that are really unknown. The live test logs the real time it listened (it stops at 3 pictures, so 3.3's
  "heard for 10 s" was not 10 s and its byte count says nothing about the frame rate).

**Logs**
- The second line of the shared glasses log is the app version and versionCode.

## 3.3.0 (versionCode 6)

From the owner's Diagnostics log of 3.1.0 (`docs/LOG-2026-11-23.txt`, moto g 2025 on Android 15, E13C-5 firmware 1.0.0).
Includes the 3.2.0 SupRevolt server work below. Connect order, pairing, the link attempts, framing defaults, photos, battery,
reconnect, gallery, import and all 48 voice commands are unchanged.

**Reply matching (root cause of every "← X 00" followed by "✕ X: no answer")**
- The glasses answer a successful execute (57B0, 7500, 5720 action 3, 102E…) with a device-numbered frame: payload
  action `0x8004` and the glasses' own counter in the request-id field (captured: 57A0 executed with id `0xB3` answered
  `1E 00 … 04 80 … 00 01 00 00`). Only read and error answers echo our id (action `0x8064`). 3.1 accepted another id
  only with action `0x8002`, so these answers were taken for pushes and the request timed out (2-4 s each); the
  glasses' counter colliding with a timed-out id also produced "Ignored a late 7500 reply".
- `Xk.matchReply`: same node and our id → exact; device-numbered (0x8004) → the answer, whatever number it carries;
  an echoed id of a request on the same node that timed out in the last 15 s → late; anything else for the node →
  the answer. The first three node-matched answers per node are logged with id and action. State nodes
  (5710, 5711, 57A0, 5750, 5790) still go through the push handling when they answer.
- 102E's "02 00" now completes sign-in at once; 7110 follows no sooner than 300 ms after 102E was sent (the
  validated harness used 150 ms). Order unchanged, 102E still never blocks a connect.
- `Xk.ackFor`: the link ack for every device control frame (replies and pushes alike) and the media ack on last
  fragments, byte-identical to 3.1.

**Live view**
- Receive sniffer while live view is open or tested: counts every byte read, bytes inside frames and skipped runs;
  logs (rate-limited, first 48 bytes) each skipped run with head/cmd/divide/length, every 0x4A frame outside a photo
  download, 2B frames and frames with an unknown head. The parser accepts an unknown head or small unknown cmd only
  then, and only with a matching CRC and a length under 16 KB.
- Any media element while preview is wanted goes to the live view: whole JPEG pictures (shown on the Live view
  screen) and H.264 behind an unknown header (`VideoAu.scan`).
- Probe: 102A (it started the glasses' mic) and 102C dropped; a refused command is skipped at once; after an accepted
  open it waits for the 5710 push, on 02 (busy) waits up to 4 s and opens once more, listens 10 s when 5710 = 01,
  then tries 5711 [00]/[01]/read, 5750 read and 7300 [01] (released with 7500), logging each reply and what arrived.
  Touches are ignored during the test. With Nearby Wi-Fi devices allowed (asked when the test starts, never for
  location), it scans for an access point or Wi-Fi Direct peer named like the glasses (name, model, last 4 of the
  address, P3/E13C/XK/Shenju) and logs matches only; it doesn't connect.
- Opening live view also waits for 5710: busy → wait and retry once. A pull the test found useful is repeated.
- A "none" saved by the 3.1 test is tested again (`live_probe_version`). Snapshot view asks for the next picture at
  once (no timeout stalls left), 1 s after a failure.

**Voice**
- Android 13+: the app records the mic itself (`MicCapture`, 16 kHz mono PCM through a pipe in
  `EXTRA_AUDIO_SOURCE`): the glasses' SCO input via `setPreferredDevice`, measured for 400 ms, and the phone's mic in
  the same stream when it's silent (below -60 dBFS). No match, speech timeout or an audio error retries once with
  the phone's mic and the recognizer's own recording. The chosen mic and its level are logged.
- Call audio (SCO) never starts while connecting, during a photo transfer or the live view test, or in the first
  3 s of a link; a glasses tap waits for that.
- Speaking: with the call audio up after a question, the answer plays on it at once (no switch to media audio, no
  lead-in), then the call audio is let go (Call audio mode keeps it 10 s as before). A command that starts a photo
  transfer lets the call audio go first.

**Tests**: 125 JVM tests (104 before): reply matching on captured and constructed bytes, push acks, sniffer skip
reports and lenient parsing, JPEG/H.264 detection, mic choice and levels, the audio gate and tap delay, the phone-mic
retry rule and Wi-Fi name matching.

## 3.2.0 (versionCode 5)

SupRevolt: P3 Companion becomes the front door to one scan server (docs/SUPREVOLT-ARCHITECTURE.md). The glasses
connect path (`XkGlasses.kt`) is untouched.

**Server (`server/`, Python 3.11+, standard library only)**
- `python -m suprevolt`: sqlite (WAL) job loop, one worker, scans in fixed catalog order, sha256-addressed blobs
  (text normalized CRLF → LF), results cached by (blob, scan version, config hash), daily sweep that re-runs stale
  results and audits 20 cached results for determinism (`/v1/health` shows `determinism: broken` on a mismatch).
- 12 scans: `blob.fingerprint`, `refix.gradle` (Python port of ReFix `match.js`), `dna.sequence`, `dna.binary`,
  `code.edapt`, `ever.heal`, `ever.run` (off by default; sandboxed child process, fixed argv, rlimits, 5 s),
  `accord.check`, `fineprint.lexicon` (offline, 34 of 34 planted traps in the analyzer corpus, controls clear),
  `fineprint.ai`, `jargonary.ai`, `image.read` (owner's Gemini or Claude key from the environment; UNKNOWN without one).
- Confidence 0 to 256 with everconf semantics: one unknown makes the verdict UNKNOWN with the reason; AI results enter
  at 120 and reach 200 only when the offline lexicon saw the same phrase. Verdict CLEAR / CAUTION / DANGER,
  `_UNCONFIRMED` under 128.
- REST: `/v1/health`, `/v1/catalog`, `POST /v1/scans` (JSON text or raw upload), `GET /v1/scans/{id}`. Bearer token
  generated on first run, printed once, stored as sha256; size limits (8 MB body, 256 KB text, 64 KB code,
  8 MB / 20 MP images); 30 requests a minute; the request log never holds headers or query strings.
- Vendored code frozen with hashes (`server/suprevolt/vendor/VENDORED.md`); golden outputs, run-twice and fresh-process
  determinism tests, API tests on a random port (`python -m unittest discover -s tests`); CI workflow `server.yml`.
- Deployment files: Dockerfile (arm64 friendly, non-root), docker-compose.yml, systemd unit, Caddyfile; phone-only
  Oracle Cloud Always Free steps and the Render fallback with its limits in `server/README.md`.

**App**
- SupRevolt screen (Settings › SupRevolt, Glasses tab, Assistant top bar, Gallery › Send to SupRevolt): server address
  and token (app-private, masked, excluded from settings backups and kept on reset), Test (health then catalog), scan a
  new or the last glasses photo (2048 px JPEG), a picked document (any type) or typed text; scan chips from the
  catalog (greyed without a key on the server); verdict header, one row per scan with a 0 to 256 confidence bar
  (hatched when unknown), cached/live tag, tap for details, skipped scans hidden; Share report without address or token.
- HTTPS required; plain http only to this phone or a private IPv4 address, after a warning, over a small socket client
  (no cleartext exception in the manifest).
- Voice command `suprevolt.scan` ("scan this", "run a scan", "SupRevolt scan"): takes a glasses photo, scans it and
  says e.g. "Caution. Three flags. Confidence 182 of 256." Translated, not run, during live translation.
- New files: `SupRevoltWire.kt` (pure, JVM-tested), `SupRevoltClient.kt`, `SupRevolt.kt`, `SupRevoltScreen.kt`,
  `ConfidenceBar.kt`, `ic_shield_scan.xml`. Tests: `SupRevoltWireTest`, `SupRevoltClientTest`, 6 new voice rows.

## 3.1.0 (versionCode 4)

Connect, pairing, sign-in and photo transfer are unchanged. All changes to `XkGlasses.kt` are additive (see
`docs/REGRESSION-CHECKLIST.md` §8).

**Live view**
- Live view test (`XkGlasses.runLiveProbe`): reads the 1001 `preview_*` fields, `57A0` (video-preview state), `2410` and
  `2420`, then tries `5720` action 3 and 2, `5710` action 3 and 2, `102A` and `102C` with `[01]`, listens 5 s after each
  for `0x4A` media (video elements, divideType 5), logs every reply's type and status byte in hex, and closes each with
  `[00]`. The working command is saved (`live_path`) and used by the live view from then on; "none" when no video came.
- Runs by itself when the live view shows no video within 6 s, and from Diagnostics › Test live view (Share sends the
  full log as text, key and address masked).
- Snapshot view when the glasses send no video over Bluetooth: takes a photo, shows it, takes the next (never while the
  glasses record, the voice recorder runs or an import runs). Snapshots are deleted after they're shown unless kept.
- The screen says which mode is active (live video, testing, snapshot view) and why.
- `57A0` pushes are recorded (the SDK's preview-state node); `takePhoto(onDone, announce)` per R-C5.

**Speaking (delay)**
- One TextToSpeech engine started with the app; language, rate, pitch and audio usage are only set when they change.
- AI answers are streamed (Gemini `streamGenerateContent?alt=sse`, Claude `stream: true`, read with `HttpURLConnection`
  and an SSE parser) and spoken sentence by sentence; plain requests remain the fallback.
- Settings › Speak through: Glasses audio (fast, default) plays answers on the media stream and lets go of the call link
  right after listening; Call audio keeps the link up for the whole session (10 s after the last turn).
- Timing lines in the connection log: recognised words → first text → first audio.

**Listening (voice service)**
- Recognition services are enumerated (`RecognitionService` intent) and tried in order: the one that worked last,
  Google (`googlequicksearchbox`, `tts`, `as`), the on-phone recognizer, others such as Samsung's, then the default.
  Service-side errors (client, server, busy, permissions, language, network, no answer in 4 s) move to the next one;
  last resort is the system speech dialog (`ACTION_RECOGNIZE_SPEECH`).
- The glasses' mic link is waited for (SCO broadcasts or communication-device change, up to 1.5 s), then the phone's
  mic is used; an audio error on the glasses' mic retries once with the phone's mic.
- The service used and the exact error (name, code, service) show in the Assistant and Diagnostics; Diagnostics › Test voice.

**Voice commands**: 47 offline commands plus help (`VoiceCommands.kt`, pure Kotlin; `CommandRunner.kt`), checked before
the AI, with a spoken yes for deleting a photo and clearing the chat. Settings › Voice commands and What can I say.

**Tests**: 104 JVM tests (82 before): the full voice-command table from `docs/VOICE-COMMANDS.md`, streaming and SSE
parsing, sentence splitting, and the new settings.

## 3.0.0 (versionCode 3)

The connection path (pairing, sign-in, link, photo transfer, live view) is unchanged from 2.0.0.

**Appearance**
- Material You colors from your wallpaper on Android 12+, built from `android.R.color.system_*`; on Android 11, or when
  turned off, a tonal palette generated from an accent preset (Violet, Ocean, Emerald, Sunset, Rose, Graphite) or any hue.
- Pure black (AMOLED), high contrast, four text sizes, reduce motion (match phone / on / off), vibration on taps.
- Every color pair is checked for WCAG contrast (4.5:1 text, 3:1 outlines) in unit tests, with a runtime guard for unusual wallpapers.
- New components: top app bar that tints on scroll, bottom navigation with vector icons and an animated indicator,
  tonal and outlined cards, filled / tonal / outlined / text buttons, chips and segmented buttons, M3 switches and sliders,
  snackbars, dialogs and bottom sheets, empty states, press scale and ripples. Refined launcher icon with a themed-icon layer.

**Glasses**
- Hero card with battery ring, connection chip and counts; capture card with a large shutter, record-on-glasses button,
  last-photo thumbnail and a 3 / 5 / 10 second self-timer.
- Options: connect when the app opens, reconnect after a drop, keep connected in the background, connection notification
  detail, photo-saved notification (off / text / with picture), low-battery alert (10-30 %), phone shutter sound,
  vibrate when a photo is saved, open the photo after taking it.
- Quick Settings tile "Glasses photo" and home-screen shortcuts: Take photo, Live view, Voice chat.

**Gallery**: multi-select share and delete, sort newest / oldest / largest / name, 2-5 columns, ask the Assistant about a photo.

**Assistant**: voice speed and pitch with a sample, how many messages are remembered (0 / 6 / 12 / 24), editable quick photo
questions, chat bubbles, speak or only show voice answers.

**Privacy, storage, backup**: app lock (fingerprint, face or screen lock), hide content in Recents, hide keys completely,
clear Assistant history and connection log, storage used and clear temporary files, save / restore settings as JSON
(glasses key, AI keys, saved glasses and drive are never written), reset to defaults, About screen with What's new.

## 2.0.0 (versionCode 2)

Reliable connect, pairing, background link, live view, diagnostics.
