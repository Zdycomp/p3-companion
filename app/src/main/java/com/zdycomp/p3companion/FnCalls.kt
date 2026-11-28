package com.zdycomp.p3companion

/*
 * 4.0 simple commands (docs/SIMPLE-COMMANDS.md): what isn't one of the offline commands goes to Gemini
 * with function calling. The model only names a function and its arguments; this file turns that into
 * the same Match(id, slots) the offline matcher makes, so CommandRunner runs it the same way, with the
 * same spoken yes before deleting or clearing. Pure Kotlin (no android.*), JVM-tested.
 */

/** One function the model chose, with its arguments as parsed JSON (numbers are Long or Double). */
data class FnCall(val name: String, val args: Map<String, Any?> = emptyMap())

object FnCalls {

    /** At most this many calls run for one request (§2.4.3). */
    const val MAX_CALLS = 3

    // ---------------- declarations (§2.2) ----------------

    private fun obj(props: Map<String, Any?>, required: List<String> = emptyList()): Map<String, Any?> =
        linkedMapOf<String, Any?>("type" to "object", "properties" to props).apply { if (required.isNotEmpty()) put("required", required) }

    private fun str(desc: String? = null, enum: List<String>? = null): Map<String, Any?> =
        linkedMapOf<String, Any?>("type" to "string").apply { desc?.let { put("description", it) }; enum?.let { put("enum", it) } }

    private fun int(min: Int, max: Int, desc: String? = null): Map<String, Any?> =
        linkedMapOf<String, Any?>("type" to "integer", "minimum" to min, "maximum" to max).apply { desc?.let { put("description", it) } }

    private fun bool(desc: String? = null): Map<String, Any?> =
        linkedMapOf<String, Any?>("type" to "boolean").apply { desc?.let { put("description", it) } }

    private fun fn(name: String, desc: String, params: Map<String, Any?>) =
        linkedMapOf<String, Any?>("name" to name, "description" to desc, "parameters" to params)

    /** The 15 functions, as Gemini functionDeclarations. */
    val FUNCTIONS: List<Map<String, Any?>> = listOf(
        fn("take_photo", "Take a photo with the glasses camera, then optionally do something with it. Use for anything that needs to see what the user is looking at.",
            obj(linkedMapOf(
                "then" to str("What to do with the photo. none = just save it. ask = answer 'question' about it.", listOf("none", "describe", "read_text", "identify", "translate", "ask")),
                "question" to str("The user's question about the photo, only when then=ask."),
                "language" to str("Target language name for then=translate, e.g. Spanish."),
                "delay_seconds" to int(0, 30, "Self-timer delay. 0 = now."),
            ), listOf("then"))),
        fn("record", "Start or stop recording video on the glasses, or an audio voice memo on the phone.",
            obj(linkedMapOf("kind" to str(enum = listOf("video", "audio")), "on" to bool("true = start, false = stop.")), listOf("kind", "on"))),
        fn("live_view", "Open or close the live camera preview, or save a snapshot from it.",
            obj(linkedMapOf("action" to str(enum = listOf("open", "close", "snapshot"))), listOf("action"))),
        fn("get_status", "Tell the user a fact about the glasses, phone or app.",
            obj(linkedMapOf("what" to str(enum = listOf("glasses_battery", "phone_battery", "connection", "glasses_storage", "recording", "saved_photos", "screen", "time", "date"))), listOf("what"))),
        fn("glasses", "Connect to the paired glasses, or import the photos and videos stored on them.",
            obj(linkedMapOf("action" to str(enum = listOf("connect", "import"))), listOf("action"))),
        fn("last_photo", "Do something with the most recent saved photo: show it, describe it, answer a question about it, or delete it.",
            obj(linkedMapOf("action" to str(enum = listOf("show", "describe", "ask", "delete")), "question" to str("Only when action=ask.")), listOf("action"))),
        fn("scan", "Take a new photo and send it to the user's SupRevolt scan server for a safety and confidence scan.", obj(linkedMapOf())),
        fn("translate", "Translate speech or a phrase. phrase = translate 'text' once. listen = translate the next thing the user says. " +
            "conversation = keep translating both sides until stopped. stop = end conversation translation. " +
            "For text the user is looking at, use take_photo with then=translate instead.",
            obj(linkedMapOf(
                "action" to str(enum = listOf("phrase", "listen", "conversation", "stop")),
                "text" to str("The phrase to translate, only for action=phrase."),
                "to" to str("Target language name."),
                "from" to str("Source language name, if the user said one."),
            ), listOf("action"))),
        fn("set_language", "Change the language the assistant answers in, or the translation languages, or swap the translation direction.",
            obj(linkedMapOf(
                "target" to str(enum = listOf("replies", "translation", "swap")),
                "language" to str("New language name (the 'to' language for translation)."),
                "from" to str("Translation source language, only for target=translation."),
            ), listOf("target"))),
        fn("set_timer", "Start a countdown timer in the phone's clock app.", obj(linkedMapOf("seconds" to int(1, 86_400)), listOf("seconds"))),
        fn("set_alarm", "Set an alarm in the phone's clock app. Use 24-hour time.",
            obj(linkedMapOf("hour" to int(0, 23), "minute" to int(0, 59)), listOf("hour", "minute"))),
        fn("set_flashlight", "Turn the phone's flashlight on or off. Leave 'on' out to toggle.", obj(linkedMapOf("on" to bool()))),
        fn("set_setting", "Change an app or phone setting. volume: up/down. speech_rate: faster/slower/normal. text_size: larger/smaller/normal. " +
            "speak_answers, tap_to_talk, high_contrast: on/off. self_timer: 0, 3, 5 or 10 (seconds).",
            obj(linkedMapOf(
                "name" to str(enum = listOf("volume", "speech_rate", "text_size", "speak_answers", "tap_to_talk", "high_contrast", "self_timer")),
                "value" to str("See the function description for allowed values."),
            ), listOf("name", "value"))),
        fn("open_screen", "Show a screen of the app.",
            obj(linkedMapOf("name" to str(enum = listOf("gallery", "glasses", "assistant", "settings", "import", "diagnostics"))), listOf("name"))),
        fn("assistant", "Control the conversation: stop talking, repeat the last answer, clear the chat history, or list what the user can say.",
            obj(linkedMapOf("action" to str(enum = listOf("stop", "repeat", "clear", "help"))), listOf("action"))),
    )

    val names: List<String> get() = FUNCTIONS.map { it["name"] as String }

    /** The system prompt for the voice-chat turn with tools (§2.3), with the reply-language rule. */
    fun toolsPrompt(replyLanguage: String): String {
        val lang = Languages.byName(replyLanguage).name
        return "You are the voice assistant in the user's P3 camera glasses. You can work the glasses and the phone with the functions you have.\n" +
            "- If the user wants something a function does, call it. Do not describe the call or say it is done; the phone speaks the result.\n" +
            "- You cannot see unless you call take_photo. For anything about what they are looking at, call take_photo.\n" +
            "- Otherwise answer in one or two short spoken sentences of plain text: no lists, markdown, emoji or links.\n" +
            "- Answer in $lang unless the user asks for another language in this request.\n" +
            "- If the request is unclear, or a needed value is missing (which language, how long), ask one short question and call nothing.\n" +
            "- For deleting or clearing, just call the function. The phone asks the user for a spoken yes itself; never ask for that yes yourself and never say it was done.\n" +
            "- Never call more than three functions for one request.\n" +
            AiWire.languageRule(lang)
    }

    // ---------------- reading calls ----------------

    /** The function calls in one Gemini answer object (non-streamed, or one stream chunk), in order. */
    fun callsIn(root: Map<*, *>): List<FnCall> {
        val first = (root["candidates"] as? List<*>)?.firstOrNull() as? Map<*, *> ?: return emptyList()
        val parts = (first["content"] as? Map<*, *>)?.get("parts") as? List<*> ?: return emptyList()
        return parts.mapNotNull { p ->
            val fc = ((p as? Map<*, *>)?.get("functionCall") ?: (p as? Map<*, *>)?.get("function_call")) as? Map<*, *> ?: return@mapNotNull null
            val name = (fc["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            val args = (fc["args"] as? Map<String, Any?>) ?: emptyMap()
            FnCall(name, args)
        }
    }

    /** The calls in a whole Gemini response body; an unreadable body has none. */
    fun parse(body: String): List<FnCall> = runCatching { Json.parse(body) as? Map<*, *> }.getOrNull()?.let { callsIn(it) } ?: emptyList()

    // ---------------- calls → commands (§2.5) ----------------

    private fun s(c: FnCall, k: String): String? = (c.args[k] as? String)?.trim()?.takeIf { it.isNotEmpty() }
    private fun b(c: FnCall, k: String): Boolean? = when (val v = c.args[k]) {
        is Boolean -> v
        is String -> when (v.trim().lowercase()) { "true", "on", "yes" -> true; "false", "off", "no" -> false; else -> null }
        else -> null
    }
    private fun n(c: FnCall, k: String): Long? = when (val v = c.args[k]) {
        is Long -> v
        is Int -> v.toLong()
        is Double -> if (v == Math.floor(v) && !v.isInfinite()) v.toLong() else null
        is String -> v.trim().toLongOrNull()
        else -> null
    }

    /** A language the app knows, by any of its spoken names; null when unknown. */
    private fun lang(raw: String?): String? = raw?.let { VoiceCommands.language(it) ?: Languages.all.firstOrNull { l -> l.name.equals(it.trim(), ignoreCase = true) }?.name }

    /**
     * The command(s) one call means, in order, or null when it can't be done as asked (unknown function,
     * a required value missing or out of range, an unknown language): the app asks back instead of guessing.
     */
    fun toMatches(c: FnCall): List<Match>? = when (c.name) {
        "take_photo" -> when (s(c, "then") ?: "none") {
            "none" -> {
                val d = n(c, "delay_seconds") ?: 0
                if (d !in 0..30) null else if (d > 0) listOf(Match("glasses.photo_timer", mapOf("n" to d.toString()))) else listOf(Match("glasses.photo"))
            }
            "describe" -> listOf(Match("ai.look"))
            "read_text" -> listOf(Match("ai.read_text"))
            "identify" -> listOf(Match("ai.identify"))
            "translate" -> {
                val raw = s(c, "language")
                val l = lang(raw)
                if (raw != null && l == null) null else listOf(Match("ai.translate_view", if (l != null) mapOf("lang" to l) else emptyMap()))
            }
            "ask" -> s(c, "question")?.let { listOf(Match(ASK_VIEW, mapOf("question" to it))) }
            else -> null
        }
        "record" -> {
            val on = b(c, "on")
            when {
                on == null -> null
                s(c, "kind") == "video" -> listOf(Match(if (on) "glasses.video_start" else "glasses.video_stop"))
                s(c, "kind") == "audio" -> listOf(Match(if (on) "rec.memo_start" else "rec.memo_stop"))
                else -> null
            }
        }
        "live_view" -> when (s(c, "action")) {
            "open" -> listOf(Match("glasses.live_open"))
            "close" -> listOf(Match("glasses.live_close"))
            "snapshot" -> listOf(Match("glasses.live_snapshot"))
            else -> null
        }
        "get_status" -> STATUS[s(c, "what")]?.let { listOf(Match(it)) }
        "glasses" -> when (s(c, "action")) {
            "connect" -> listOf(Match("glasses.connect"))
            "import" -> listOf(Match("import.start"))
            else -> null
        }
        "last_photo" -> when (s(c, "action")) {
            "show" -> listOf(Match("gallery.last_photo"))
            "describe" -> listOf(Match("gallery.describe_last"))
            "ask" -> s(c, "question")?.let { listOf(Match("gallery.describe_last", mapOf("question" to it))) }
            "delete" -> listOf(Match("gallery.delete_last"))
            else -> null
        }
        "scan" -> listOf(Match("suprevolt.scan"))
        "translate" -> translate(c)
        "set_language" -> when (s(c, "target")) {
            "replies" -> lang(s(c, "language"))?.let { listOf(Match("tr.reply_language", mapOf("lang" to it))) }
            "translation" -> {
                val to = lang(s(c, "language"))
                val fromRaw = s(c, "from")
                val from = lang(fromRaw)
                if (to == null || (fromRaw != null && from == null)) null
                else listOf(Match("tr.set_pair", buildMap { put("to", to); from?.let { put("from", it) } }))
            }
            "swap" -> listOf(Match("tr.swap"))
            else -> null
        }
        "set_timer" -> n(c, "seconds")?.takeIf { it in 1..86_400 }?.let { listOf(Match("util.timer", mapOf("dur" to it.toString()))) }
        "set_alarm" -> {
            val h = n(c, "hour")
            val m = n(c, "minute")
            if (h == null || m == null || h !in 0..23 || m !in 0..59) null
            else listOf(Match("util.alarm", mapOf("h" to h.toString(), "m" to m.toString(), "ampm" to "1")))
        }
        "set_flashlight" -> {
            if (c.args.containsKey("on") && c.args["on"] != null && b(c, "on") == null) null
            else listOf(Match("util.torch", mapOf("onoff" to when (b(c, "on")) { true -> "true"; false -> "false"; null -> "toggle" })))
        }
        "set_setting" -> setting(c)
        "open_screen" -> when (val name = s(c, "name")) {
            "diagnostics" -> listOf(Match("nav.diagnostics"))
            "gallery", "glasses", "assistant", "settings", "import" -> listOf(Match("nav.tab", mapOf("tab" to name.uppercase())))
            else -> null
        }
        "assistant" -> when (s(c, "action")) {
            "stop" -> listOf(Match("ai.stop"))
            "repeat" -> listOf(Match("ai.repeat"))
            "clear" -> listOf(Match("ai.clear"))
            "help" -> listOf(Match("sys.help"))
            else -> null
        }
        else -> null
    }

    /** The first command of [c], for callers that need only one. */
    fun toMatch(c: FnCall): Match? = toMatches(c)?.lastOrNull()

    /** 4.0: the new command id for "take a photo and answer my question about it". */
    const val ASK_VIEW = "ai.ask_view"

    private val STATUS = mapOf(
        "glasses_battery" to "glasses.battery", "phone_battery" to "util.phone_battery", "connection" to "glasses.status",
        "glasses_storage" to "glasses.counts", "recording" to "rec.status", "saved_photos" to "gallery.count",
        "screen" to "acc.where_am_i", "time" to "util.time", "date" to "util.date",
    )

    private fun translate(c: FnCall): List<Match>? {
        val toRaw = s(c, "to")
        val fromRaw = s(c, "from")
        val to = lang(toRaw)
        val from = lang(fromRaw)
        if ((toRaw != null && to == null) || (fromRaw != null && from == null)) return null
        val pair = if (to != null) Match("tr.set_pair", buildMap { put("to", to); from?.let { put("from", it) } }) else null
        return when (s(c, "action")) {
            "phrase" -> {
                val text = s(c, "text") ?: return null
                if (to == null) null else listOf(Match("tr.say_in", mapOf("phrase" to text, "lang" to to)))
            }
            "listen" -> listOfNotNull(pair, Match("tr.start"))
            "conversation" -> listOfNotNull(pair, Match("tr.conversation"))
            "stop" -> listOf(Match("tr.conversation_stop"))
            else -> null
        }
    }

    private fun setting(c: FnCall): List<Match>? {
        val v = s(c, "value")?.lowercase()?.trim() ?: return null
        fun onOff(): String? = when (v) { "on", "true", "yes", "enable", "enabled" -> "true"; "off", "false", "no", "disable", "disabled" -> "false"; else -> null }
        return when (s(c, "name")) {
            "volume" -> when (v) { "up", "louder", "higher" -> "up"; "down", "quieter", "lower" -> "down"; else -> null }?.let { listOf(Match("util.volume", mapOf("dir" to it))) }
            "speech_rate" -> when (v) { "faster", "up", "fast" -> "up"; "slower", "down", "slow" -> "down"; "normal", "default" -> "normal"; else -> null }
                ?.let { listOf(Match("set.speech_rate", mapOf("dir" to it))) }
            "text_size" -> when (v) { "larger", "bigger", "up" -> "up"; "smaller", "down" -> "down"; "normal", "default" -> "normal"; else -> null }
                ?.let { listOf(Match("acc.text_size", mapOf("dir" to it))) }
            "speak_answers" -> onOff()?.let { listOf(Match("set.speak_answers", mapOf("onoff" to it))) }
            "tap_to_talk" -> onOff()?.let { listOf(Match("set.tap_to_talk", mapOf("onoff" to it))) }
            "high_contrast" -> onOff()?.let { listOf(Match("acc.high_contrast", mapOf("onoff" to it))) }
            "self_timer" -> {
                val secs = if (v == "off") 0L else v.removeSuffix("seconds").removeSuffix("s").trim().toLongOrNull()
                secs?.takeIf { it in 0..30 }?.let { listOf(Match("set.self_timer", mapOf("n" to it.toString()))) }
            }
            else -> null
        }
    }

    /** What to run for an answer's calls: at most [MAX_CALLS] calls, in order; the chain stops after one that needs a spoken yes. */
    data class Plan(val matches: List<Match>, val askBack: String?, val dropped: Int)

    fun plan(calls: List<FnCall>): Plan {
        val out = ArrayList<Match>()
        var dropped = 0
        calls.forEachIndexed { i, c ->
            if (i >= MAX_CALLS) { dropped++; return@forEachIndexed }
            if (out.lastOrNull()?.let { VoiceCommands.rule(it.id)?.needsConfirm } == true) { dropped++; return@forEachIndexed }
            val ms = toMatches(c) ?: return Plan(out, missingQuestion(c), calls.size - i - 1 + dropped)
            for (m in ms) {
                out += m
                if (VoiceCommands.rule(m.id)?.needsConfirm == true) break
            }
        }
        return Plan(out, null, dropped)
    }

    /** One short question about what a call is missing (§2.4.2). */
    fun missingQuestion(c: FnCall): String = when (c.name) {
        "set_timer" -> "How long should the timer be?"
        "set_alarm" -> "What time should the alarm be?"
        "translate" -> if (s(c, "action") == "phrase" && s(c, "text") == null) "What should I translate?" else "Translate into which language?"
        "set_language" -> "Which language?"
        "take_photo" -> if (s(c, "then") == "ask") "What would you like to know about it?" else "What should I do with the photo?"
        "last_photo" -> "What would you like to know about that photo?"
        "set_setting" -> "Which setting, and to what?"
        "record" -> "Video or a voice memo, start or stop?"
        else -> "Sorry, could you say that another way?"
    }

    /** "AI chose: take_photo then=translate language=Spanish" for the chat's note line (§2.4.5). */
    fun describe(c: FnCall): String = "AI chose: ${c.name}" + c.args.entries.joinToString("") { (k, v) -> " $k=${v.toString().take(60)}" }
}
