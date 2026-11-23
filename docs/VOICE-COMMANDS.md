# P3 Companion: voice commands spec

Status: design only, no code yet. Owner of this doc: Experimental Physicist. Implementer: polyglot code specialist.
Scope: 47 new local voice commands plus `sys.help`, an offline matcher, a spoken-yes confirmation step, a test table, and a latency budget.

---

## 0. Where it plugs in

Today, every finished voice turn in Chat mode goes straight to the AI: `Assistant.heard()` → `AiClient.ask()` → `Speech.speak()`. The new layer sits in front of that call.

```
Speech.listen(onResult = text)
   └─ Assistant.heard(my, Mode.CHAT, text)
        ├─ pendingConfirm?  → ConfirmGate (section 3.6)
        ├─ VoiceCommands.match(text, CommandState) → Match?   (pure Kotlin, JVM-testable)
        │     └─ hit  → CommandRunner.run(match) → reply text → speech.speak(reply)   (no network unless the row says so)
        └─ null → existing AI path, unchanged
```

New files:

| File | Android deps | Purpose |
|---|---|---|
| `VoiceCommands.kt` | none (pure Kotlin, no `android.*` imports) | `normalize()`, slot parsers, the ordered rule table, `match()`. Unit-tested on the JVM. |
| `CommandRunner.kt` | yes | Runs a `Match` against `XkGlasses`, `Assistant`, `VoiceRecorder`, `Prefs`, `MainActivity` and framework APIs. Returns the sentence to speak. |

`Mode.TRANSLATE` turns never go through the matcher. Every utterance there is something to translate. The single exception is `ai.stop`, matched on the final result so "stop" still works.

Small hooks the coder must add (all are currently private):

| Hook | Why |
|---|---|
| `MainActivity.whenConnected(what, then)` → `internal` | glasses commands reuse the shortcut path |
| `MainActivity.current` → read-only `internal val currentScreen` | `acc.where_am_i`, live-view-aware commands |
| `LiveViewScreen.snapshot()` → `internal` | `glasses.live_snapshot` |
| `Assistant.lastAiLine(): String?` | `ai.repeat` |
| `Speech.warmUp()` (calls `withTts {}`) | latency (section 5) |

---

## 1. What exists today (do not duplicate)

There is **no voice-command layer** in the app today. Every Chat utterance goes to the AI. These are the existing triggers that act like commands. The new commands reuse their code paths and do not replace them.

| Existing trigger | Where | What it does |
|---|---|---|
| Tap on the glasses (`glasses.onTouch`) | `MainActivity.onGlassesTapped()` | Opens Assistant › Voice and starts `assistant.voiceChat()`. If a turn is running it calls `tapWhileBusy()`, which finishes listening or stops. |
| Big voice button | `AssistantScreen` | `assistant.voiceChat()` / `tapWhileBusy()` |
| Translate button | `AssistantScreen` | `assistant.translate()` |
| Photo quick questions (4 presets, editable) | `AiWire.photoPresetLabels`, `Assistant.askPhoto/captureAndAsk` | Describe, identify plant or animal, food and calories, read and translate the text |
| App shortcut `action.PHOTO` | `MainActivity.handleAction` | Glasses tab + `glasses.takePhoto()` |
| App shortcut `action.LIVE` | same | Glasses tab + `showLiveView()` |
| App shortcut `action.VOICE` | same | Assistant › Voice |
| App shortcut `action.GLASSES` | same | Glasses tab |
| Quick Settings tile | `PhotoTileService` | Takes a photo |
| Glasses' own shutter button | `glasses.onShutter` / `onPhotoSaved` | Photo saved from the frame button |

---

## 2. The commands (47 + help)

Legend: **Net** = needs the internet (AI call). **Confirm** = needs a spoken "yes" first (section 3.6). Spoken replies are deliberately short. `{x}` is a slot.

None of these commands sends a message, places a call, shares, emails or posts. Those are out of scope on purpose. If the owner asks for them later, each one must go through ConfirmGate and read back the recipient and the text in full.

### 2.1 Camera and glasses (11)

| id | Example phrasings | Action (exact call) | Speaks back | Net | Confirm |
|---|---|---|---|---|---|
| `glasses.photo` | "take a picture", "snap a photo", "capture", "take a photo" | `a.whenConnected("take the photo") { glasses.takePhoto() }`. Honours `prefs.selfTimer` the same way GlassesScreen does: if it's above 0, behave like `glasses.photo_timer` with that value. | "Taking a photo." The existing `onPhotoSaved` snack confirms. | no | no |
| `glasses.photo_timer` | "take a picture in {5} seconds", "photo in ten seconds", "self timer photo {3}" | `main.postDelayed({ glasses.takePhoto() }, s*1000)`, with `s` clamped to 1..30. A later `ai.stop` cancels the pending runnable. | "Photo in {5} seconds." | no | no |
| `glasses.video_start` | "start recording video", "record a video", "start video" | `glasses.setRecording(true)` inside `whenConnected` | "Recording video on the glasses." | no | no |
| `glasses.video_stop` | "stop video", "stop recording video", "end the video" | `glasses.setRecording(false)` | "Video stopped." | no | no |
| `glasses.live_open` | "open live view", "show me what the glasses see", "live view" | `a.whenConnected("open live view") { a.showLiveView() }` (same as `ACTION_LIVE`) | "Opening live view." | no | no |
| `glasses.live_close` | "close live view", "exit live view", "stop live view" | `if (a.currentScreen == SCREEN_LIVE) a.goBack()` (this closes the preview through `LiveView.onHidden`) | "Live view closed." / "Live view isn't open." | no | no |
| `glasses.live_snapshot` | "save a snapshot", "grab a frame", "screenshot the live view" | `(screens[SCREEN_LIVE] as LiveViewScreen).snapshot()`. Only valid while live view is open, otherwise reply with the fallback text. | "Snapshot saved." / "Open live view first." | no | no |
| `glasses.battery` | "glasses battery", "how much battery do the glasses have", "battery on the glasses" | Read `glasses.battery` and `glasses.charging`. If that's null and the state is READY: `glasses.refresh()`, then answer on the first listener change, waiting at most 1.5 s. Say "Checking." first if it isn't known yet. | "Glasses at {64} percent{, charging}." / "The glasses aren't connected." | no | no |
| `glasses.status` | "are the glasses connected", "glasses status", "connection status" | Read `glasses.state`, `deviceName`, `signedIn` | "Connected to {P3-XXXX}." / "Connecting." / "Not connected." | no | no |
| `glasses.connect` | "connect the glasses", "reconnect", "connect to my glasses" | `a.withBluetooth { glasses.reconnectSaved() }`. If `prefs.glassesAddress == null`, open the Glasses tab instead. | "Connecting to your glasses." / "Pair the glasses on the Glasses tab first." | no | no |
| `glasses.counts` | "how many photos are on the glasses", "what's on the glasses", "glasses storage" | `glasses.refresh()`, then read `photoCount`, `videoCount` and `audioCount` | "{12} photos, {3} videos and {1} recording on the glasses." | no | no |

### 2.2 Recording (3)

| id | Example phrasings | Action | Speaks back | Net | Confirm |
|---|---|---|---|---|---|
| `rec.memo_start` | "start a voice memo", "record audio", "take a note", "start recording" (when nothing else is recording) | Speak first, then in the speak-done callback: `a.withMic(forRecording = true) { VoiceRecorder.get(app, prefs).start() }`. The recognizer has already been destroyed in `onResults`, so the mic is free. | "Recording. Tap the glasses to stop." | no | no |
| `rec.memo_stop` | "stop the memo", "stop audio recording", "stop recording" (when a memo is running) | `VoiceRecorder.stop()`. **Caveat:** while MediaRecorder holds the mic, most phones can't run SpeechRecognizer. So this is mainly reached by a glasses tap → voice turn after the coder makes `onGlassesTapped` stop an active memo first. Spec: if `VoiceRecorder.recording`, a glasses tap stops the memo and does not start a voice turn. | "Memo saved." | no | no |
| `rec.status` | "how long have I been recording", "am I recording", "recording status" | `VoiceRecorder.elapsedMs()` → `VoiceRecorder.clock()`. Also `glasses.recording`. | "Memo running for {2 minutes 10 seconds}." / "Video recording on the glasses." / "Nothing is recording." | no | no |

### 2.3 Gallery and import (5)

| id | Example phrasings | Action | Speaks back | Net | Confirm |
|---|---|---|---|---|---|
| `gallery.last_photo` | "show the last photo", "open my last picture", "show me that photo" | `glasses.lastPhoto` ?: the newest image in `Pictures/<album>` (MediaStore query, `DATE_ADDED DESC LIMIT 1`) → `a.openMedia(uri, "image/jpeg")` | "Opening your last photo." / "No photos yet." | no | no |
| `gallery.describe_last` | "describe my last photo", "what's in the last picture", "tell me about that photo" | Same URI → `assistant.askPhoto(uri, AiWire.photoPreset(0, prefs.replyLanguage))`, then force `readAloud(answer)` even if `speakPhotoAnswers` is off (it was a voice request) | "Looking at your last photo." then the AI answer | **yes** | no |
| `gallery.count` | "how many photos do I have", "how many pictures are saved" | MediaStore `COUNT` in the album folder (photos and videos) | "{48} photos and {6} videos in {P3 Glasses}." | no | no |
| `gallery.delete_last` | "delete the last photo", "remove that picture", "trash the last photo" | After a spoken yes: `a.requestDelete(listOf(uri)) {}`. On Android 11+ this also shows the system delete dialog on the phone. | Ask: "Delete the last photo? Say yes or no." Then "Confirm on your phone." / "Cancelled." | no | **yes** |
| `import.start` | "import from the glasses", "copy my photos", "sync the glasses", "start import" | `a.show(TAB_IMPORT)`, then `App.importer(a).start()`. If `driveAvailable()` is false, just open the tab. | "Importing." / "Pick the glasses' drive on the Import tab first." | no | no |

### 2.4 Assistant and AI (7)

| id | Example phrasings | Action | Speaks back | Net | Confirm |
|---|---|---|---|---|---|
| `ai.look` | "what am I looking at", "what do you see", "look at this", "describe this" | `assistant.captureAndAsk(AiWire.photoPreset(0, reply))`. Answer is read aloud. | "Looking." then the AI answer | **yes** | no |
| `ai.read_text` | "read this", "read the sign", "what does this say", "read the text" | `captureAndAsk(AiWire.photoPreset(3, reply))` | "Reading." then the answer | **yes** | no |
| `ai.identify` | "what plant is this", "what animal is this", "identify this" | `captureAndAsk(AiWire.photoPreset(1, reply))` | "Checking." then the answer | **yes** | no |
| `ai.translate_view` | "translate what I'm looking at", "translate this sign", "translate the menu" | `captureAndAsk("Read all the text in this photo and translate it into {reply}.")`. If a language slot is present, it replaces `reply`. | "Translating." then the answer | **yes** | no |
| `ai.repeat` | "say that again", "repeat that", "what did you say", "repeat" | `assistant.readAloud(assistant.lastAiLine())` | The last answer / "Nothing to repeat yet." | no | no |
| `ai.stop` | "stop", "cancel", "never mind", "be quiet", "shut up", "quiet" | `assistant.stop()`, and cancel any pending `photo_timer` / confirmation | Nothing (silence is the confirmation). Optional short earcon. | no | no |
| `ai.clear` | "clear the chat", "start over", "new conversation", "forget this conversation" | After a spoken yes: `assistant.clearChat()` | Ask: "Clear this conversation? Say yes or no." Then "Cleared." | no | **yes** (it deletes history) |

### 2.5 Translation (5)

| id | Example phrasings | Action | Speaks back | Net | Confirm |
|---|---|---|---|---|---|
| `tr.start` | "translate", "start translating", "translation mode" | `screen.section = SECTION_TRANSLATE; a.show(TAB_ASSISTANT)`. After this turn ends: `main.post { assistant.translate() }`. | "Go ahead, I'm listening in {Spanish}." | recognizer only | no |
| `tr.set_pair` | "translate from {Spanish} to {English}", "set translation to {French}", "translate {German} into {English}" | `prefs.translateFrom` / `translateTo` = `Languages.byName(slot).name`. A single language means "to"; "from" stays as it was. | "Translating {Spanish} to {English}." | no | no |
| `tr.swap` | "swap languages", "switch languages", "reverse the translation" | Swap `translateFrom` and `translateTo` | "Now {English} to {Spanish}." | no | no |
| `tr.say_in` | "how do you say {good morning} in {Japanese}", "say {thank you} in {French}", "translate {where is the train} to {Spanish}" | One-shot `AiClient.ask(provider, key, model, AiWire.translationPrompt(reply, lang), [AiTurn(true, phrase)])`, then speak with the **target** language tag. Not added to chat history. | The translation, in the target voice | **yes** | no |
| `tr.reply_language` | "answer in {Spanish}", "reply in {French}", "talk to me in {German}" | `prefs.replyLanguage = Languages.byName(slot).name` | "{Spanish} it is." (spoken in the new language's voice) | no | no |

### 2.6 Phone utilities (7)

| id | Example phrasings | Action | Speaks back | Net | Confirm |
|---|---|---|---|---|---|
| `util.time` | "what time is it", "what's the time", "time" | `android.text.format.DateFormat.getTimeFormat(app).format(Date())` (follows the phone's 12/24-hour setting) | "It's {3:45 PM}." | no | no |
| `util.date` | "what's the date", "what day is it", "what's today" | `SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())` | "Today is {Monday, November 23}." | no | no |
| `util.phone_battery` | "phone battery", "how much battery do I have", "battery" (bare "battery" means the phone) | `BatteryManager.getIntProperty(BATTERY_PROPERTY_CAPACITY)` and `isCharging` (API 23+, no permission) | "Phone at {81} percent{, charging}." | no | no |
| `util.timer` | "set a timer for {5 minutes}", "timer {30 seconds}", "{10} minute timer" | `Intent(AlarmClock.ACTION_SET_TIMER).putExtra(EXTRA_LENGTH, secs).putExtra(EXTRA_SKIP_UI, true)`, then `a.launch(it)`. Needs `<uses-permission android:name="com.android.alarm.permission.SET_ALARM"/>` (a normal permission with no prompt). Catch `ActivityNotFoundException`. Clamp to 1 s..24 h. | "Timer set for {5 minutes}." / "No clock app can take timers." | no | no |
| `util.alarm` | "set an alarm for {7 am}", "wake me up at {6:30}", "alarm {7:15 pm}" | `Intent(AlarmClock.ACTION_SET_ALARM).putExtra(EXTRA_HOUR, h).putExtra(EXTRA_MINUTES, m).putExtra(EXTRA_SKIP_UI, true)` with the same permission. With no am/pm: if the 24-hour format is on, use it as said; otherwise pick the next occurrence. | "Alarm set for {7:00 AM}." (always read back the exact time) | no | no |
| `util.torch` | "flashlight on", "turn off the flashlight", "torch on", "flashlight" (alone = toggle) | `CameraManager.setTorchMode(id, on)`, where `id` is the first camera with `FLASH_INFO_AVAILABLE == true`, back-facing first. No permission needed. Track the state with `registerTorchCallback`. Catch `CameraAccessException` (camera in use). | "Flashlight on." / "Flashlight off." / "The flashlight is busy." | no | no |
| `util.volume` | "volume up", "louder", "turn it down", "quieter", "volume down" | `AudioManager.adjustStreamVolume(stream, ADJUST_RAISE/LOWER, 0)`. `stream = STREAM_VOICE_CALL` when `assistant.throughGlasses`, otherwise `STREAM_MUSIC` (`USAGE_ASSISTANT` plays on the music stream). Do two steps per command. | "Louder." / "Quieter." (spoken at the new volume) | no | no |

### 2.7 Navigation (2)

| id | Example phrasings | Action | Speaks back | Net | Confirm |
|---|---|---|---|---|---|
| `nav.tab` | "go to {gallery}", "open {settings}", "show the {glasses} tab", "open {import}", "go to {assistant}" | `a.show(TAB_IMPORT / TAB_GALLERY / TAB_GLASSES / TAB_ASSISTANT / TAB_SETTINGS)`. Slot synonyms: gallery = photos / pictures / album; glasses = device / camera; assistant = ai / chat; settings = options / preferences; import = downloads / transfer. | "{Gallery}." | no | no |
| `nav.diagnostics` | "open diagnostics", "show the log", "debug info" | `a.showDiagnostics()` | "Diagnostics." | no | no |

### 2.8 Settings toggles (4)

| id | Example phrasings | Action | Speaks back | Net | Confirm |
|---|---|---|---|---|---|
| `set.speak_answers` | "stop reading answers aloud", "read answers aloud", "speak answers on/off" | `prefs.speakChatAnswers = on` | "I'll read answers aloud." / "Answers will be text only." (always spoken, even when turning it off) | no | no |
| `set.tap_to_talk` | "turn off tap to talk", "enable tap to talk", "tap to talk on" | `prefs.tapToTalk = on` | "Tap to talk {on}." | no | no |
| `set.speech_rate` | "talk faster", "speak slower", "normal speed", "slow down" | `prefs.ttsRate = (rate ± 0.15).coerceIn(0.5f, 2.0f)`; "normal" sets 1.0 | "Like this?" (spoken at the new rate) | no | no |
| `set.self_timer` | "set self timer to {5} seconds", "self timer off", "turn off the self timer" | `prefs.selfTimer` = nearest value in `Prefs.TIMER_STEPS` (0, 3, 5, 10) | "Self-timer {5 seconds}." / "Self-timer off." | no | no |

### 2.9 Accessibility (3)

| id | Example phrasings | Action | Speaks back | Net | Confirm |
|---|---|---|---|---|---|
| `acc.text_size` | "bigger text", "make the text larger", "smaller text", "normal text size" | `prefs.textScale` = next or previous value in `Prefs.TEXT_SCALES`, then `a.refresh()`. Recreate if the scale is applied at activity start. | "Text {larger}." / "That's the largest size." | no | no |
| `acc.high_contrast` | "high contrast on", "turn off high contrast" | `prefs.highContrast = on`, then `a.refresh()` | "High contrast {on}." | no | no |
| `acc.where_am_i` | "where am I", "what screen is this", "what's on screen" | Name of `a.currentScreen`, plus the glasses state in one clause | "Assistant tab. Glasses connected." | no | no |

### 2.10 System (1, plus the internal yes/no)

| id | Example phrasings | Action | Speaks back | Net | Confirm |
|---|---|---|---|---|---|
| `sys.help` | "help", "what can I say", "what can you do", "list commands" | Speak a 6-item sampler and put the full list as a NOTE line in the chat log. The list is generated from the rule table's `helpLabel`s, so it never goes stale. | "Try: take a picture, what am I looking at, read this, set a timer for five minutes, flashlight on, or say stop. The full list is on screen." | no | no |
| `sys.yes` / `sys.no` | "yes", "yeah", "yep", "do it", "confirm", "go ahead" / "no", "nope", "cancel", "don't" | **Only matched while a confirmation is pending** (section 3.6). Otherwise "yes" falls through to the AI. | — | no | — |

**Count: 47 new commands, plus `sys.help`, which makes 48.** That clears the owner's minimum of 34 by 14.

---

## 3. Matcher design (offline, pure Kotlin)

### 3.1 API

```kotlin
data class CommandState(
    val memoRecording: Boolean = false,
    val videoRecording: Boolean = false,
    val liveViewOpen: Boolean = false,
    val pendingConfirm: String? = null,   // command id awaiting yes/no
)
data class Match(val id: String, val slots: Map<String, String> = emptyMap())

object VoiceCommands {
    fun normalize(raw: String): String
    fun match(raw: String, state: CommandState = CommandState()): Match?   // null → AI
    val rules: List<Rule>   // ordered; Rule(id, priority, patterns: List<Regex>, helpLabel, needsConfirm, needsNet)
}
```

No `android.*` imports, so it compiles and tests on the JVM. The state is passed in, so `match()` stays deterministic: the same text and state always give the same result.

### 3.2 normalize()

Apply in this order:
1. `lowercase(Locale.ROOT)`, then trim.
2. Replace curly apostrophes with `'`. Expand contractions: `what's→what is`, `i'm→i am`, `don't→do not`, `it's→it is`, `that's→that is`.
3. Replace every character other than `[a-z0-9: ]` with a space. This keeps `6:30`. Collapse whitespace.
4. Number words to digits: `zero..twenty`, tens (`thirty..ninety`), compounds (`twenty five→25`), `a/an` before a unit (`a minute→1 minute`), `half an hour→30 minutes`, `half a minute→30 seconds`, `a couple of→2`. Recognizers usually return digits already, so this is a fallback.
5. Strip leading politeness and wake words, repeatedly: `hey|ok|okay|please|can you|could you|would you|will you|i want to|i would like to|i'd like to|go ahead and|just|hey glasses|hey p3|p3`.
6. Strip trailing `please|for me|now|right now|thanks|thank you`.
7. Collapse whitespace again.

Examples: `"Hey, could you take a picture please?"` → `take a picture`. `"Set a timer for five minutes."` → `set a timer for 5 minutes`.

### 3.3 Slot grammar (named groups)

| Slot | Regex fragment | Parsed to |
|---|---|---|
| `n` (seconds for photo_timer) | `(?<n>\d{1,2})\s*(?:s|sec|secs|second|seconds)?` | Int, clamped 1..30 |
| `dur` | `(?<dur>(?:\d+\s*(?:h|hr|hrs|hour|hours|m|min|mins|minute|minutes|s|sec|secs|second|seconds)\s*(?:and\s*)?)+)` | total seconds (sum the parts: "1 hour 30 minutes" → 5400) |
| `time` | `(?<h>\d{1,2})(?::(?<m>\d{2})|\s(?<m2>\d{2}))?\s*(?<ap>am|pm|a m|p m|in the morning|in the evening|at night)?` | hour 0-23, minute 0-59. Reject h>23 or m>59. |
| `lang` | alternation built from `Languages.all` names, lowercased, plus aliases: `mandarin|chinese→Chinese (Mandarin)`, `cantonese`, `farsi`→no (not supported, fall through), `tagalog→Filipino`, `brazilian→Portuguese` | `Language.name` |
| `tab` | `import|imports|downloads|transfer|gallery|photos|pictures|album|glasses|device|camera|assistant|ai|chat|settings|options|preferences` | tab constant |
| `onoff` | `on|off|enable|disable|turn on|turn off|start|stop` | Boolean |
| `phrase` | `(?<phrase>.+?)` (lazy, only in `tr.say_in`) | String, kept as the recognizer spelled it (from the **raw** text, not the normalized one) |

The `lang` alternation must be built longest-first so "chinese mandarin" wins over "chinese".

### 3.4 Rule shape and anchoring

- Every pattern is **anchored** (`^…$`) to the normalized utterance. This is the main protection against false positives: "what time is it in Tokyo" does not match `util.time` and goes to the AI, which can answer it.
- Each rule has 3-10 patterns. Example:

```kotlin
Rule("util.timer", P2, listOf(
  Regex("^(?:set )?(?:a )?timer (?:for )?$DUR$"),
  Regex("^(?:set )?(?:a )?$DUR timer$"),
  Regex("^(?:start )?(?:a )?countdown (?:for )?$DUR$"),
), help = "set a timer for 5 minutes")
```

- Length gate: if the normalized text has more than 14 words, skip P2 and P3 and try only P0 and `tr.say_in`, then fall to the AI. Long sentences are questions.

### 3.5 Priority order (first match wins; within a tier, table order)

| Tier | Rules | Why first |
|---|---|---|
| **P0 control** | `sys.yes` / `sys.no` (only when `pendingConfirm != null`), `ai.stop`, `sys.help`, `ai.repeat` | Must never be swallowed by anything else. Must be fast. |
| **P1 state-resolved** | "stop recording" / "stop": resolved by state. If `memoRecording` → `rec.memo_stop`. Else if `videoRecording` → `glasses.video_stop`. Bare "stop" with neither → `ai.stop`. Also "start recording" → `rec.memo_start`, and "save" / "snapshot" while `liveViewOpen` → `glasses.live_snapshot`. | The same words mean different things depending on state |
| **P2 device actions with exact phrasing** | `glasses.*`, `rec.*`, `gallery.*`, `import.start`, `util.timer`, `util.alarm`, `util.torch`, `util.volume`, `nav.*`, `set.*`, `acc.*`, `tr.set_pair`, `tr.swap`, `tr.reply_language`, `tr.start` | Commands with clear verbs and slots |
| **P3 question-shaped local answers** | `util.time`, `util.date`, `util.phone_battery`, `glasses.battery`, `glasses.status`, `glasses.counts`, `rec.status`, `gallery.count` | Anchored questions. Anything longer falls through. |
| **P4 AI-backed commands** | `ai.look`, `ai.read_text`, `ai.identify`, `ai.translate_view`, `gallery.describe_last`, `tr.say_in` | Still local routing, but they hit the network |
| **Fallthrough** | `null` → the existing AI chat path | |

Specific tie-breaks the coder must encode (they are all in the test table):
- "battery" alone → `util.phone_battery`. Any mention of "glasses" → `glasses.battery`.
- "translate" alone → `tr.start`. "translate this / the sign / what I'm looking at" → `ai.translate_view`. "translate X to Y" where X is a language → `tr.set_pair`. "translate {phrase} to {lang}" where the phrase is not a language → `tr.say_in`.
- "take a picture in 5 seconds" → `glasses.photo_timer`, not `glasses.photo`. So list photo_timer's patterns before photo's.
- "cancel" → `sys.no` if a confirmation is pending, otherwise `ai.stop`.

### 3.6 ConfirmGate (spoken yes)

Applies to every rule with `needsConfirm = true`. Today that's `gallery.delete_last` and `ai.clear`. Any future send, call, message or delete command must also set it.

1. The matcher returns a confirm-required id. CommandRunner does **not** act. It speaks the question ("Delete the last photo? Say yes or no.") and sets `pendingConfirm = Pending(id, slots, expiresAt = now + 10 s)`.
2. When the question finishes speaking, the Assistant starts **one** new listen turn automatically, so no tap is needed.
3. The next **final** result (never a partial) goes through `match(text, state.copy(pendingConfirm = id))`.
   - `sys.yes` → run the action and speak the result.
   - Anything else, including silence, an error or a timeout → "Cancelled." and clear the pending state.
4. The pending state is single-use. It's cleared by `ai.stop`, by a tab change and by `Assistant.stop()`. A "yes" heard with no pending state is just text for the AI.
5. Yes words: `yes|yeah|yep|yup|sure|confirm|do it|go ahead|ok|okay|affirmative`. "No" words: `no|nope|cancel|stop|do not|don't|never mind`. These are anchored. "yes but not that one" is not a yes.

### 3.7 Test table (turn into JVM tests: `VoiceCommandsTest.kt`)

Default state = `CommandState()` unless a state column is filled. `null` = falls through to the AI.

| # | Utterance (raw recognizer output) | State | Expected id | Expected slots |
|---|---|---|---|---|
| 1 | Take a picture | | `glasses.photo` | |
| 2 | hey could you snap a photo please | | `glasses.photo` | |
| 3 | take a picture in 5 seconds | | `glasses.photo_timer` | n=5 |
| 4 | photo in ten seconds | | `glasses.photo_timer` | n=10 |
| 5 | start recording video | | `glasses.video_start` | |
| 6 | stop recording | videoRecording | `glasses.video_stop` | |
| 7 | stop recording | memoRecording | `rec.memo_stop` | |
| 8 | stop | | `ai.stop` | |
| 9 | open live view | | `glasses.live_open` | |
| 10 | save a snapshot | liveViewOpen | `glasses.live_snapshot` | |
| 11 | how much battery do the glasses have | | `glasses.battery` | |
| 12 | battery | | `util.phone_battery` | |
| 13 | are the glasses connected | | `glasses.status` | |
| 14 | reconnect | | `glasses.connect` | |
| 15 | how many photos are on the glasses | | `glasses.counts` | |
| 16 | how many photos do I have | | `gallery.count` | |
| 17 | start a voice memo | | `rec.memo_start` | |
| 18 | how long have I been recording | | `rec.status` | |
| 19 | show the last photo | | `gallery.last_photo` | |
| 20 | describe my last photo | | `gallery.describe_last` | |
| 21 | delete the last photo | | `gallery.delete_last` | |
| 22 | yes | pendingConfirm=gallery.delete_last | `sys.yes` | |
| 23 | yes | | null | |
| 24 | cancel | pendingConfirm=ai.clear | `sys.no` | |
| 25 | cancel | | `ai.stop` | |
| 26 | What am I looking at? | | `ai.look` | |
| 27 | read the sign | | `ai.read_text` | |
| 28 | what plant is this | | `ai.identify` | |
| 29 | translate this menu | | `ai.translate_view` | |
| 30 | say that again | | `ai.repeat` | |
| 31 | start over | | `ai.clear` | |
| 32 | translate | | `tr.start` | |
| 33 | translate from Spanish to English | | `tr.set_pair` | from=Spanish, to=English |
| 34 | set translation to French | | `tr.set_pair` | to=French |
| 35 | swap languages | | `tr.swap` | |
| 36 | how do you say good morning in Japanese | | `tr.say_in` | phrase=good morning, lang=Japanese |
| 37 | answer in mandarin | | `tr.reply_language` | lang=Chinese (Mandarin) |
| 38 | What time is it? | | `util.time` | |
| 39 | what time is it in Tokyo | | null | |
| 40 | what's the date | | `util.date` | |
| 41 | set a timer for five minutes | | `util.timer` | dur=300 |
| 42 | 1 hour 30 minute timer | | `util.timer` | dur=5400 |
| 43 | wake me up at 6:30 am | | `util.alarm` | h=6, m=30 |
| 44 | set an alarm for 7 pm | | `util.alarm` | h=19, m=0 |
| 45 | set an alarm for 25 | | null | (invalid hour → AI says it can't) |
| 46 | turn on the flashlight | | `util.torch` | onoff=true |
| 47 | flashlight | | `util.torch` | onoff=toggle |
| 48 | louder | | `util.volume` | dir=up |
| 49 | go to settings | | `nav.tab` | tab=SETTINGS |
| 50 | open photos | | `nav.tab` | tab=GALLERY |
| 51 | show the log | | `nav.diagnostics` | |
| 52 | stop reading answers aloud | | `set.speak_answers` | onoff=false |
| 53 | talk faster | | `set.speech_rate` | dir=up |
| 54 | set self timer to 5 seconds | | `set.self_timer` | n=5 |
| 55 | bigger text | | `acc.text_size` | dir=up |
| 56 | high contrast on | | `acc.high_contrast` | onoff=true |
| 57 | where am I | | `acc.where_am_i` | |
| 58 | what can I say | | `sys.help` | |
| 59 | import from the glasses | | `import.start` | |
| 60 | tell me a joke about pizza | | null | |
| 61 | can you take a picture of the moon and tell me how far away it is | | null | (longer than 14 words → AI) |
| 62 | what is the capital of France | | null | |

Extra test classes:
- `normalize()` unit tests: rows 2, 26 and 38 show punctuation, politeness and contraction handling.
- Every `Rule.helpLabel` must match its own rule (a self-test loop over `rules`).
- No two rules may match the same `helpLabel` (an ambiguity guard).
- Speed: `match()` over all 62 rows × 1,000 iterations must average under 0.5 ms per call on the JVM.

---

## 4. CommandRunner contract

```kotlin
class CommandRunner(a: MainActivity, assistant: Assistant, glasses: XkGlasses, prefs: Prefs) {
    /** Called on main. Calls back with the line to speak (null = silent) before doing slow work. */
    fun run(m: Match, speakNow: (String?) -> Unit)
}
```

- **Speak first, then act** for anything slower than 50 ms (glasses I/O, MediaStore queries, AI calls). The acknowledgement ("Taking a photo.", "Looking.") is what hits the 300 ms budget. Results arrive later, either as a second utterance or through the existing UI and snacks.
- Every command writes one `Who.NOTE` line to `assistant.chat` (for example "Command: flashlight on"). The user can see what was understood, and the chat history stays honest.
- When a command needs a permission the app doesn't have (mic, Bluetooth), use the existing `withMic`, `withBluetooth` and similar helpers, and speak "Allow {Bluetooth} on the phone."
- Commands never add to the AI `history` list, except `ai.*` photo questions, which go through `askPhoto` as they do today.

---

## 5. Latency budget (local commands)

**Requirement: a locally answered command starts speaking within 300 ms of `onResults` (the final recognition result).**

| Stage | Budget | How |
|---|---|---|
| `normalize` + `match` | ≤ 2 ms | Regexes are precompiled in an `object` initializer. First-use JIT is warmed by calling `match("")` at Assistant init. |
| CommandRunner dispatch to the `speakNow` callback | ≤ 20 ms | No I/O before speaking. Glasses, MediaStore and AI work run after the acknowledgement. |
| TTS engine ready | 0 ms (pre-warmed) | **Today `Speech.withTts` creates the TextToSpeech engine on the first `speak()`, which costs 0.5-2 s on the first answer.** Add `Speech.warmUp()` and call it when the Assistant is created and when the Assistant tab or a glasses connection comes up. |
| `setLanguage` | ≤ 30 ms | Cache the last locale and skip `setLanguage` when it's unchanged. Today it's called on every speak, which costs 50-200 ms on some engines. |
| Audio route | 0 ms | Don't `releaseRoute()` between the recognition result and the command reply. Reuse the SCO link the turn already holds (`throughGlasses`). Tearing SCO down and back up costs 300-1500 ms and is the most likely cause of the "too much delay" the owner hears through the glasses. |
| TTS time to first audio | ≤ 200 ms | Use an on-device voice. Keep replies short (one clause). `QUEUE_FLUSH`. |
| **Total** | **≤ 250 ms typical, 300 ms hard ceiling** | |

Measure, don't guess:
- Stamp `SystemClock.elapsedRealtime()` in `onResults` and in `UtteranceProgressListener.onStart`. Log the difference to the glasses log (`glasses.addLog("cmd {id} {ms} ms")`) so it shows up in Diagnostics.
- In DEVICE-TEST-PLAN, add one case per group: run 10 utterances and record p50 and p90. Pass if p90 ≤ 300 ms with the phone speaker and p90 ≤ 400 ms through the glasses. The glasses pass is reported separately because Bluetooth SCO itself adds about 100 ms of output latency that the app can't remove.

Related speech-delay notes for the coder (outside the 300 ms rule, but they're the owner's complaint):
1. `Speech.start()` creates and destroys a new `SpeechRecognizer` on every turn. Keep one instance per session and call `startListening` again. Creating the on-device recognizer costs 100-400 ms.
2. Add `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS = 700` and `EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS = 500`. Some engines honour these and end the turn sooner after you stop talking. Other engines ignore them, which is harmless.
3. Early fire for P0 only: if a **partial** result, normalized, exactly equals a P0 stop word ("stop", "cancel", "be quiet"), act right away. Never early-fire any other command or a confirmation "yes".

---

## 6. Out of scope (said plainly)

- Sending texts, email, chat messages, calls, sharing and posting. None are included. If they're added later, each one goes through ConfirmGate and reads back the recipient and the full text before the yes.
- Wake word or always-on listening. Commands only run inside a turn started by a glasses tap or the voice button.
- The live view not working and the speech service failing are separate bugs. `glasses.live_open` and `glasses.live_snapshot` depend on the live-view fix. Every command depends on `Speech.recognitionAvailable()` being true.
- The "server gateway / SupRevolt" backend the owner mentioned. No command here talks to a remote server other than the existing AI providers.
