# P3 Companion: simple commands + AI function calling

Status: implemented in 4.0.0 (`FnCalls.kt`, `AiWire` tools, `Assistant.handleCalls`, `SimpleCommandsTest`). Owner of this doc: Experimental Physicist. Implementer: polyglot code specialist.
Builds on `docs/VOICE-COMMANDS.md` (the 51-rule offline matcher in `VoiceCommands.kt`). Nothing in that file is removed.

Owner's ask: "change up the commands to more simple AI appropriate usage".

What changes, in one breath: people learn **12 plain commands** that run offline at once. Everything else is said in
normal words and goes to **Gemini with function calling**, which picks an app action and its settings. Every one of
the old 51 rules still matches offline as an alias, so nothing already learned breaks.

Note on the count: the brief says 48 commands. The code now has **51 rules** (the original 47 + `sys.help`, plus
3.6's `tr.conversation`, `tr.conversation_stop` and `suprevolt.scan`). All 51 are covered below.

---

## 0. The new flow

```
finished voice sentence (Assistant.heard)
  1. pending yes/no?        -> sys.yes / sys.no (unchanged, VOICE-COMMANDS 3.6)
  2. VoiceCommands.match()  -> core 12 + all 51 old aliases, offline, ~1 ms   (unchanged code path)
  3. no match, key present  -> Gemini call WITH tools (section 2)
        functionCall part   -> FnCalls.toMatch(call) -> same CommandRunner.run(match) as step 2
        text part           -> spoken answer, as today (askAi streaming)
  4. no match, no key / offline -> today's "Add your key" note, and the 12 core commands still work
```

The point of step 3: the model never runs anything itself. It only names a function and arguments; the app turns that
into the same `Match(id, slots)` the offline matcher makes and runs it through the same `CommandRunner`, with the
same confirmation step. One action path, two ways in.

Help changes: `sys.help` speaks only the core 12 (short), and the chat NOTE line says "Or just ask in your own words."
The full alias list stays in Settings > Voice commands for anyone who wants it.

---

## 1. Core set: 12 plain commands (offline, instant)

Matched by `VoiceCommands.match()` before any network call. "Maps to" is the existing rule id, so `CommandRunner`
needs no new case except where marked NEW. Phrasings marked (new) need a pattern added; the rest match today.

| # | Command | Natural phrasings | Maps to |
|---|---|---|---|
| 1 | take a picture | "take a picture", "take a photo", "snap a pic", "snap one" (new), "cheese" | `glasses.photo` |
| 2 | start / stop video | "start video", "record a video", "stop video", "stop recording" (video running) | `glasses.video_start` / `glasses.video_stop` |
| 3 | what am I looking at | "what am I looking at", "what do you see", "what's this", "describe this" | `ai.look` (needs net) |
| 4 | read this | "read this", "read the sign", "what does that say", "read it to me" | `ai.read_text` (needs net) |
| 5 | translate | "translate this", "translate the menu", "translate this to Spanish" → view; bare "translate" → listen mode (old meaning kept) | `ai.translate_view` / `tr.start` |
| 6 | flashlight on / off | "flashlight on", "torch off", "turn on the flashlight", "light on" (new), "lights off" (new); bare "flashlight" toggles | `util.torch` |
| 7 | volume up / down | "volume up", "louder", "turn it up", "quieter", "turn it down" | `util.volume` |
| 8 | battery | "battery", "how's the battery" (new), "glasses battery", "phone battery" | bare: `util.phone_battery` with the reply changed to both (see below); "glasses battery": `glasses.battery` |
| 9 | live view | "live view", "open live view", "show me what the glasses see", "close live view" | `glasses.live_open` / `glasses.live_close` |
| 10 | stop | "stop", "cancel", "never mind", "be quiet", "quiet" | `ai.stop` (P0, early-fire on partials, unchanged) |
| 11 | help | "help", "what can I say", "what can you do" | `sys.help` (now speaks the 12) |
| 12 | scan this | "scan this", "run a scan", "SupRevolt scan" | `suprevolt.scan` (needs net) |

Two small behaviour changes, both safe:

- **Bare "battery"** keeps its id `util.phone_battery` (test row 12 in `VoiceCommandsTest` still passes) but the reply
  becomes "Glasses at 64 percent, phone at 81." when the glasses are connected, and the old phone-only reply when not.
  On camera glasses most people mean the glasses; this answers both without guessing.
- **New `sys.help` speech:** "Try: take a picture, what am I looking at, read this, translate, flashlight on, or say
  stop. Or just ask me in your own words."

New patterns to add (all in existing rules, P2 tier, anchored like the rest):
`glasses.photo`: `snap (?:one|it|that)`; `util.torch`: `lights? (?<o4>on|off)`; `util.phone_battery`:
`how is the battery`, `how(?:'s| is) my battery`.

---

## 2. Everything else: Gemini function calling

### 2.1 Request

Same endpoint and body as today's `AiWire.geminiBody()` (chat mode, spoken), plus two fields:

```json
{
  "system_instruction": { "parts": [ { "text": "<section 2.3 prompt>" } ] },
  "contents": [ "...history + the user's sentence, unchanged..." ],
  "tools": [ { "functionDeclarations": [ "<section 2.2, all 15>" ] } ],
  "toolConfig": { "functionCallingConfig": { "mode": "AUTO" } }
}
```

- `AUTO`: the model either calls a function or answers in text. Never `ANY` (that would force a call on plain questions).
- Works with `streamGenerateContent?alt=sse` too: a `functionCall` arrives as a whole part inside one chunk.
  `AiWire.parseStreamData` gets a new `StreamEvent.Call(name, args)`. If a Call arrives, stop the sentence speaker,
  drop any text already buffered for this turn, and dispatch the call.
- Photo questions (`captureAndAsk`, `askPhoto`) are **not** sent with tools. Only the voice-chat turn is.
- Claude provider: the same 15 declarations go in `tools: [{name, description, input_schema}]` and come back as
  `tool_use` blocks. Same `FnCalls.toMatch`. Gemini is the primary target.

### 2.2 The 15 functions

Plain JSON Schema subset that Gemini accepts (`type`, `description`, `properties`, `required`, `enum`, `minimum`,
`maximum`). Kept as one Kotlin constant `AiWire.FUNCTIONS` built with `Json.write`, so a JVM test can check it parses.

```json
[
  {
    "name": "take_photo",
    "description": "Take a photo with the glasses camera, then optionally do something with it. Use for anything that needs to see what the user is looking at.",
    "parameters": {
      "type": "object",
      "properties": {
        "then": { "type": "string", "enum": ["none", "describe", "read_text", "identify", "translate", "ask"],
                  "description": "What to do with the photo. none = just save it. ask = answer 'question' about it." },
        "question": { "type": "string", "description": "The user's question about the photo, only when then=ask." },
        "language": { "type": "string", "description": "Target language name for then=translate, e.g. Spanish." },
        "delay_seconds": { "type": "integer", "minimum": 0, "maximum": 30, "description": "Self-timer delay. 0 = now." }
      },
      "required": ["then"]
    }
  },
  {
    "name": "record",
    "description": "Start or stop recording video on the glasses, or an audio voice memo on the phone.",
    "parameters": {
      "type": "object",
      "properties": {
        "kind": { "type": "string", "enum": ["video", "audio"] },
        "on": { "type": "boolean", "description": "true = start, false = stop." }
      },
      "required": ["kind", "on"]
    }
  },
  {
    "name": "live_view",
    "description": "Open or close the live camera preview, or save a snapshot from it.",
    "parameters": {
      "type": "object",
      "properties": { "action": { "type": "string", "enum": ["open", "close", "snapshot"] } },
      "required": ["action"]
    }
  },
  {
    "name": "get_status",
    "description": "Tell the user a fact about the glasses, phone or app.",
    "parameters": {
      "type": "object",
      "properties": {
        "what": { "type": "string", "enum": ["glasses_battery", "phone_battery", "connection", "glasses_storage",
                                             "recording", "saved_photos", "screen", "time", "date"] }
      },
      "required": ["what"]
    }
  },
  {
    "name": "glasses",
    "description": "Connect to the paired glasses, or import the photos and videos stored on them.",
    "parameters": {
      "type": "object",
      "properties": { "action": { "type": "string", "enum": ["connect", "import"] } },
      "required": ["action"]
    }
  },
  {
    "name": "last_photo",
    "description": "Do something with the most recent saved photo: show it, describe it, answer a question about it, or delete it.",
    "parameters": {
      "type": "object",
      "properties": {
        "action": { "type": "string", "enum": ["show", "describe", "ask", "delete"] },
        "question": { "type": "string", "description": "Only when action=ask." }
      },
      "required": ["action"]
    }
  },
  {
    "name": "scan",
    "description": "Take a new photo and send it to the user's SupRevolt scan server for a safety and confidence scan.",
    "parameters": { "type": "object", "properties": {} }
  },
  {
    "name": "translate",
    "description": "Translate speech or a phrase. phrase = translate 'text' once. listen = translate the next thing the user says. conversation = keep translating both sides until stopped. stop = end conversation translation. For text the user is looking at, use take_photo with then=translate instead.",
    "parameters": {
      "type": "object",
      "properties": {
        "action": { "type": "string", "enum": ["phrase", "listen", "conversation", "stop"] },
        "text": { "type": "string", "description": "The phrase to translate, only for action=phrase." },
        "to": { "type": "string", "description": "Target language name." },
        "from": { "type": "string", "description": "Source language name, if the user said one." }
      },
      "required": ["action"]
    }
  },
  {
    "name": "set_language",
    "description": "Change the language the assistant answers in, or the translation languages, or swap the translation direction.",
    "parameters": {
      "type": "object",
      "properties": {
        "target": { "type": "string", "enum": ["replies", "translation", "swap"] },
        "language": { "type": "string", "description": "New language name (the 'to' language for translation)." },
        "from": { "type": "string", "description": "Translation source language, only for target=translation." }
      },
      "required": ["target"]
    }
  },
  {
    "name": "set_timer",
    "description": "Start a countdown timer in the phone's clock app.",
    "parameters": {
      "type": "object",
      "properties": { "seconds": { "type": "integer", "minimum": 1, "maximum": 86400 } },
      "required": ["seconds"]
    }
  },
  {
    "name": "set_alarm",
    "description": "Set an alarm in the phone's clock app. Use 24-hour time.",
    "parameters": {
      "type": "object",
      "properties": {
        "hour": { "type": "integer", "minimum": 0, "maximum": 23 },
        "minute": { "type": "integer", "minimum": 0, "maximum": 59 }
      },
      "required": ["hour", "minute"]
    }
  },
  {
    "name": "set_flashlight",
    "description": "Turn the phone's flashlight on or off. Leave 'on' out to toggle.",
    "parameters": { "type": "object", "properties": { "on": { "type": "boolean" } } }
  },
  {
    "name": "set_setting",
    "description": "Change an app or phone setting. volume: up/down. speech_rate: faster/slower/normal. text_size: larger/smaller/normal. speak_answers, tap_to_talk, high_contrast: on/off. self_timer: 0, 3, 5 or 10 (seconds).",
    "parameters": {
      "type": "object",
      "properties": {
        "name": { "type": "string", "enum": ["volume", "speech_rate", "text_size", "speak_answers", "tap_to_talk",
                                             "high_contrast", "self_timer"] },
        "value": { "type": "string", "description": "See the function description for allowed values." }
      },
      "required": ["name", "value"]
    }
  },
  {
    "name": "open_screen",
    "description": "Show a screen of the app.",
    "parameters": {
      "type": "object",
      "properties": {
        "name": { "type": "string", "enum": ["gallery", "glasses", "assistant", "settings", "import", "diagnostics"] }
      },
      "required": ["name"]
    }
  },
  {
    "name": "assistant",
    "description": "Control the conversation: stop talking, repeat the last answer, clear the chat history, or list what the user can say.",
    "parameters": {
      "type": "object",
      "properties": { "action": { "type": "string", "enum": ["stop", "repeat", "clear", "help"] } },
      "required": ["action"]
    }
  }
]
```

### 2.3 System prompt (tools turn)

Replaces `AiWire.systemPrompt(lang, spoken = true)` for the voice-chat turn only. `{lang}` is `prefs.replyLanguage`
(default English). The existing `AiWire.languageRule(lang)` is appended unchanged so the Chinese-drift fix stays.

```
You are the voice assistant in the user's P3 camera glasses. You can work the glasses and the phone with the
functions you have.
- If the user wants something a function does, call it. Do not describe the call or say it is done; the phone
  speaks the result.
- You cannot see unless you call take_photo. For anything about what they are looking at, call take_photo.
- Otherwise answer in one or two short spoken sentences of plain text: no lists, markdown, emoji or links.
- Answer in {lang} unless the user asks for another language in this request.
- If the request is unclear, or a needed value is missing (which language, how long), ask one short question
  and call nothing.
- For deleting or clearing, just call the function. The phone asks the user for a spoken yes itself; never ask
  for that yes yourself and never say it was done.
- Never call more than three functions for one request.
<AiWire.languageRule(lang)>
```

### 2.4 Rules the app enforces (not the model)

1. **Spoken yes for anything that deletes or sends.** The app decides, from the resulting rule id, never from the
   model's words: `gallery.delete_last` and `ai.clear` keep `needsConfirm = true` and go through the same
   `pendingConfirm` path ("Delete the last photo? Say yes or no."). Any future send/share/post function gets
   `needsConfirm = true` and reads back recipient and full text before the yes (VOICE-COMMANDS section 6).
   `scan` uploads only to the user's own SupRevolt server, as the old `suprevolt.scan` did, and stays confirm-free.
2. **Ask back when unclear.** If `FnCalls.toMatch` returns null (unknown name, missing required arg, value out of
   range, unknown language), the app does not guess. It speaks one short question built from what is missing,
   e.g. "Translate into which language?" / "How long should the timer be?", and listens once more like a confirm.
   The answer goes back to Gemini with the history, so it completes the call.
3. **Several calls in one answer:** run in order, at most 3. A call that needs a yes stops the chain there; the rest
   are dropped (the user can ask again).
4. **No result round-trip.** After a call the app speaks `CommandRunner.Reply.say` itself; no `functionResponse` is
   sent back. One network trip per action. The `take_photo then=…` and `last_photo describe/ask` cases do their
   existing photo request (with the image, without tools) as they do today.
5. The call and its args are logged as a NOTE line ("AI chose: take_photo then=translate language=Spanish") so the
   owner can see what the model picked.

### 2.5 Function → old rule id (covers all 51)

`FnCalls.toMatch(name, args): Match?` is pure Kotlin (no android.*), next to `VoiceCommands`, so it runs on the JVM.

| Function call | Match id and slots |
|---|---|
| `take_photo{then:none}` / `{then:none, delay_seconds:n>0}` | `glasses.photo` / `glasses.photo_timer{n}` |
| `take_photo{then:describe}` / `read_text` / `identify` | `ai.look` / `ai.read_text` / `ai.identify` |
| `take_photo{then:translate, language?}` | `ai.translate_view{lang?}` |
| `take_photo{then:ask, question}` | **NEW** `ai.ask_view{question}` → `captureAndAsk(question)` (null without question) |
| `record{video,true}` / `{video,false}` | `glasses.video_start` / `glasses.video_stop` |
| `record{audio,true}` / `{audio,false}` | `rec.memo_start` / `rec.memo_stop` |
| `live_view{open}` / `{close}` / `{snapshot}` | `glasses.live_open` / `glasses.live_close` / `glasses.live_snapshot` |
| `get_status{glasses_battery}` / `phone_battery` / `connection` / `glasses_storage` | `glasses.battery` / `util.phone_battery` / `glasses.status` / `glasses.counts` |
| `get_status{recording}` / `saved_photos` / `screen` / `time` / `date` | `rec.status` / `gallery.count` / `acc.where_am_i` / `util.time` / `util.date` |
| `glasses{connect}` / `{import}` | `glasses.connect` / `import.start` |
| `last_photo{show}` / `{describe}` / `{delete}` | `gallery.last_photo` / `gallery.describe_last` / `gallery.delete_last` (confirm) |
| `last_photo{ask, question}` | `gallery.describe_last{question}` (new optional slot; runner uses it instead of preset 0) |
| `scan{}` | `suprevolt.scan` |
| `translate{phrase, text, to}` | `tr.say_in{phrase, lang}` (null if text or to missing) |
| `translate{listen, to?, from?}` | `tr.set_pair` first if a language is given, then `tr.start` |
| `translate{conversation}` / `{stop}` | `tr.conversation` / `tr.conversation_stop` |
| `set_language{replies, language}` | `tr.reply_language{lang}` |
| `set_language{translation, language, from?}` / `{swap}` | `tr.set_pair{to, from?}` / `tr.swap` |
| `set_timer{seconds}` | `util.timer{dur}` |
| `set_alarm{hour, minute}` | `util.alarm{h, m, ampm:"1"}` (24-hour is exact, no next-occurrence guess) |
| `set_flashlight{on:true/false}` / `{}` | `util.torch{onoff:true/false}` / `util.torch{onoff:toggle}` |
| `set_setting{volume, up/down}` | `util.volume{dir}` |
| `set_setting{speech_rate, faster/slower/normal}` | `set.speech_rate{dir: up/down/normal}` |
| `set_setting{text_size, larger/smaller/normal}` | `acc.text_size{dir: up/down/normal}` |
| `set_setting{speak_answers / tap_to_talk / high_contrast, on/off}` | `set.speak_answers` / `set.tap_to_talk` / `acc.high_contrast` `{onoff}` |
| `set_setting{self_timer, 0/3/5/10}` | `set.self_timer{n}` (nearest step, as today) |
| `open_screen{gallery/glasses/assistant/settings/import}` / `{diagnostics}` | `nav.tab{tab: GALLERY/…}` / `nav.diagnostics` |
| `assistant{stop}` / `repeat` / `clear` / `help` | `ai.stop` / `ai.repeat` / `ai.clear` (confirm) / `sys.help` |

Language names in args go through the same `language()` lookup the matcher uses; an unknown name returns null (ask back).
`sys.yes` / `sys.no` are never function calls: they only exist while a confirmation is pending, offline.

---

## 3. Test table (40 rows)

**L rows** run on the JVM through `VoiceCommands.match(text, state)` (same harness as `VoiceCommandsTest`).
**F rows** run on the JVM by feeding a canned Gemini response through `AiWire.parseGeminiCalls(body)` then
`FnCalls.toMatch(...)`; they test parsing and mapping, not the model. Canned body shape:

```json
{"candidates":[{"content":{"role":"model","parts":[
  {"functionCall":{"name":"take_photo","args":{"then":"translate","language":"Spanish"}}}
]},"finishReason":"STOP"}]}
```

For model quality, run the F utterances against the live API by hand (or a nightly job) and compare the call name.

| # | Kind | Utterance | State | Expected local id {slots} / function call → Match |
|---|---|---|---|---|
| 1 | L | "take a picture" | none | `glasses.photo` |
| 2 | L | "snap a pic please" | none | `glasses.photo` |
| 3 | L | "snap one" (new pattern) | none | `glasses.photo` |
| 4 | L | "start video" | none | `glasses.video_start` |
| 5 | L | "stop recording" | video | `glasses.video_stop` |
| 6 | L | "what am I looking at" | none | `ai.look` |
| 7 | L | "what's this" | none | `ai.look` |
| 8 | L | "read the sign" | none | `ai.read_text` |
| 9 | L | "translate this to Spanish" | none | `ai.translate_view {lang=Spanish}` |
| 10 | L | "translate" | none | `tr.start` |
| 11 | L | "flashlight on" | none | `util.torch {onoff=true}` |
| 12 | L | "lights off" (new pattern) | none | `util.torch {onoff=false}` |
| 13 | L | "flashlight" | none | `util.torch {onoff=toggle}` |
| 14 | L | "louder" | none | `util.volume {dir=up}` |
| 15 | L | "turn it down" | none | `util.volume {dir=down}` |
| 16 | L | "battery" | none | `util.phone_battery` (reply names both) |
| 17 | L | "how's the battery" (new pattern) | none | `util.phone_battery` |
| 18 | L | "live view" | none | `glasses.live_open` |
| 19 | L | "never mind" | none | `ai.stop` |
| 20 | L | "what can I say" | none | `sys.help` |
| 21 | L | "scan this" | none | `suprevolt.scan` |
| 22 | L | "set a timer for 5 minutes" (old alias) | none | `util.timer {dur=300}` |
| 23 | L | "delete the last photo" (old alias) | none | `gallery.delete_last` (asks yes) |
| 24 | L | "can you snap that sign and tell me what it says in Spanish" | none | `null` (too free for the matcher → AI) |
| 25 | F | "can you snap that sign and tell me what it says in Spanish" | none | `take_photo{then:"translate",language:"Spanish"}` → `ai.translate_view {lang=Spanish}` |
| 26 | F | "grab a shot of this in three seconds" | none | `take_photo{then:"none",delay_seconds:3}` → `glasses.photo_timer {n=3}` |
| 27 | F | "is this mushroom safe to eat" | none | `take_photo{then:"ask",question:"Is this mushroom safe to eat?"}` → `ai.ask_view {question=…}` |
| 28 | F | "film this for me, I'll tell you when to stop" | none | `record{kind:"video",on:true}` → `glasses.video_start` |
| 29 | F | "jot down a quick audio note" | none | `record{kind:"audio",on:true}` → `rec.memo_start` |
| 30 | F | "are my glasses still hooked up" | none | `get_status{what:"connection"}` → `glasses.status` |
| 31 | F | "how much room is left on the glasses" | none | `get_status{what:"glasses_storage"}` → `glasses.counts` |
| 32 | F | "pull everything off the glasses onto my phone" | none | `glasses{action:"import"}` → `import.start` |
| 33 | F | "what was in that picture I just took" | none | `last_photo{action:"describe"}` → `gallery.describe_last` |
| 34 | F | "get rid of that last shot" | none | `last_photo{action:"delete"}` → `gallery.delete_last` (app asks "Say yes or no.") |
| 35 | F | "how would I ask where the bathroom is in Italian" | none | `translate{action:"phrase",text:"where is the bathroom",to:"Italian"}` → `tr.say_in {phrase=…, lang=Italian}` |
| 36 | F | "help me talk with this guy, he only speaks Portuguese" | none | `translate{action:"conversation",to:"Portuguese"}` → `tr.set_pair {to=Portuguese}` then `tr.conversation` |
| 37 | F | "wake me at quarter to seven tomorrow" | none | `set_alarm{hour:6,minute:45}` → `util.alarm {h=6, m=45, ampm=1}` |
| 38 | F | "I need some light, it's dark in here" | none | `set_flashlight{on:true}` → `util.torch {onoff=true}` |
| 39 | F | "your voice is too fast, and make the words bigger" | none | two calls: `set_setting{name:"speech_rate",value:"slower"}`, `set_setting{name:"text_size",value:"larger"}` → `set.speech_rate {dir=down}`, `acc.text_size {dir=up}` |
| 40 | F | "set a timer" (no length) | none | canned text part "How long should the timer be?" and no call → spoken question, no Match. Also: canned `set_timer{}` without seconds → `toMatch` returns null → app asks "How long should the timer be?" |

Extra F checks worth one line each in the same test class: unknown function name → null; `take_photo{then:"ask"}`
without `question` → null; `translate{action:"phrase",text:"hi"}` without `to` → null; `set_setting{name:"volume",
value:"sideways"}` → null; a response with three `functionCall` parts keeps order; a fourth is dropped.

---

## 4. Old phrases keep working (aliases)

- `VoiceCommands.kt` keeps all 51 rules and every pattern. The core 12 are a **teaching subset** of the same rules,
  plus the three new patterns in section 1. `VoiceCommandsTest` must still pass unchanged (only row 12's spoken
  reply text changes; the test checks the id, so it does not).
- Order stays: offline matcher first, AI second. So an old phrase is never sent to the AI, still works with no
  internet or no key, and still takes ~1 ms.
- `Rule` gets one field, `core: Boolean = false`. The 12 core rules set it. `sys.help` and the Settings list show
  core first; the rest are listed under "Also works".
- The Settings > Voice commands switch (`prefs.voiceCommands`) still turns the offline matcher off. With it off,
  every sentence goes to Gemini with tools, so free speech still works the app.

---

## 5. Work list for the code specialist

1. `AiWire`: `FUNCTIONS` constant, `toolsPrompt(lang)`, `geminiBody(..., tools = true)` adds `tools` + `toolConfig`;
   `parseGeminiCalls(body): List<FnCall>`; `StreamEvent.Call` in `parseStreamData`.
2. New `FnCalls.kt` (pure Kotlin): `data class FnCall(name, args: Map<String, Any?>)`, `toMatch(call): Match?`,
   `missingQuestion(call): String` for ask-back.
3. `Assistant.askAi`: in CHAT mode with Gemini, send tools; on a Call, run it via `runCommand(my, match, false)`
   (so confirmation is the same code); log the NOTE line; ask back on null.
4. `CommandRunner`: new `ai.ask_view{question}`; optional `question` slot on `gallery.describe_last`; bare-battery
   reply names both devices.
5. `VoiceCommands`: three new patterns, `core` flag, new help speech.
6. Tests: the 40 rows above plus the extra F checks, all JVM.

Out of scope: no wake word, no sending/sharing functions, no change to translate or conversation modes themselves.
