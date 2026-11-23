# Changelog

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
