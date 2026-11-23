package com.zdycomp.p3companion

import java.util.Locale

/*
 * Offline voice commands (docs/VOICE-COMMANDS.md). Every finished voice-chat sentence goes through
 * [VoiceCommands.match] before the AI; a hit is handled on the phone in a few milliseconds, a miss
 * (null) goes to the AI exactly as before. Pure Kotlin with no android.* imports, so the JVM tests
 * run exactly the code the app runs.
 */

/** What the matcher needs to know about the app right now. Same text + same state = same answer. */
data class CommandState(
    val memoRecording: Boolean = false,
    val videoRecording: Boolean = false,
    val liveViewOpen: Boolean = false,
    /** Command id waiting for a spoken yes or no, or null. */
    val pendingConfirm: String? = null,
)

/** A recognised command and its slots (numbers, languages, on/off…), all as strings. */
data class Match(val id: String, val slots: Map<String, String> = emptyMap())

/**
 * One command: its patterns (anchored to the whole normalized sentence), the phrase shown in the
 * help list, and whether it needs a spoken yes or the internet. [slots] turns a regex match into
 * slots, or returns null to reject it (an alarm for 25 o'clock).
 */
class Rule(
    val id: String,
    val tier: Int,
    val patterns: List<Regex>,
    val helpLabel: String,
    val group: String,
    val needsConfirm: Boolean = false,
    val needsNet: Boolean = false,
    val slots: (MatchResult, String) -> Map<String, String>? = { _, _ -> emptyMap() },
)

object VoiceCommands {

    const val P0 = 0
    const val P2 = 2
    const val P3 = 3
    const val P4 = 4

    /** Longer sentences are questions for the AI; only P0 and "how do you say…" are tried. */
    const val MAX_WORDS = 14

    // ---------------- normalize ----------------

    private val CONTRACTIONS = listOf(
        "what's" to "what is", "i'm" to "i am", "don't" to "do not", "it's" to "it is", "that's" to "that is",
        "i'd" to "i would", "where's" to "where is", "how's" to "how is", "can't" to "cannot", "doesn't" to "does not",
        "let's" to "let us", "today's" to "today", "phone's" to "phone", "glasses'" to "glasses",
    )

    private val SMALL = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    private val TENS = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )
    private val UNITS = setOf("second", "seconds", "sec", "secs", "minute", "minutes", "min", "mins", "hour", "hours")

    private val LEADING = listOf(
        "hey glasses", "hey p3", "i would like to", "i want to", "go ahead and", "can you", "could you", "would you",
        "will you", "hey", "ok", "okay", "please", "just", "p3",
    )
    private val TRAILING = listOf("thank you", "for me", "right now", "please", "thanks", "now")

    private val CONTRACTION_RX = CONTRACTIONS.map { (a, b) -> Regex("\\b" + Regex.escape(a) + "(?![a-z])") to b }
    private val NOT_WORD = Regex("[^a-z0-9: ]")
    private val SPACES = Regex("\\s+")
    private val STOP_RECORDING = Regex("^(?:stop|end|finish) (?:the )?recording$")
    private val START_RECORDING = Regex("^(?:start|begin) recording$")
    private val SAVE_WORDS = Regex("^(?:save|snapshot|save it|save this|save that|take a snapshot)$")

    /** Lower case, contractions expanded, punctuation gone (":" kept for 6:30), number words as digits, politeness stripped. */
    fun normalize(raw: String): String {
        var s = raw.lowercase(Locale.ROOT).trim().replace('’', '\'').replace('‘', '\'')
        if (s.indexOf('\'') >= 0) for ((rx, b) in CONTRACTION_RX) s = rx.replace(s, b)
        s = SPACES.replace(NOT_WORD.replace(s, " "), " ").trim()
        s = numbers(s)
        var changed = true
        while (changed) {
            changed = false
            for (w in LEADING) if (s.startsWith("$w ") && s.length > w.length + 1) { s = s.substring(w.length + 1).trim(); changed = true }
            for (w in TRAILING) if (s.endsWith(" $w") && s.length > w.length + 1) { s = s.substring(0, s.length - w.length - 1).trim(); changed = true }
        }
        return SPACES.replace(s, " ").trim()
    }

    private fun numbers(text: String): String {
        var s = " $text "
        s = s.replace(" half an hour ", " 30 minutes ").replace(" half a minute ", " 30 seconds ")
            .replace(" a couple of ", " 2 ").replace(" o clock ", " ")
        val words = s.trim().split(' ').filter { it.isNotEmpty() }
        val out = ArrayList<String>(words.size)
        var i = 0
        while (i < words.size) {
            val w = words[i]
            val next = words.getOrNull(i + 1)
            when {
                w in TENS -> {
                    val unit = next?.let { SMALL[it] }
                    if (unit != null && unit in 1..9) { out += (TENS.getValue(w) + unit).toString(); i += 2; continue }
                    out += TENS.getValue(w).toString()
                }
                w in SMALL -> out += SMALL.getValue(w).toString()
                (w == "a" || w == "an") && next != null && next in UNITS -> out += "1"
                else -> out += w
            }
            i++
        }
        return out.joinToString(" ")
    }

    // ---------------- slots ----------------

    private val LANG_ALIASES: List<Pair<String, String>> = run {
        val list = mutableListOf<Pair<String, String>>()
        Languages.all.forEach { l ->
            val n = l.name.lowercase(Locale.ROOT).replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim()
            list += n to l.name
        }
        list += "mandarin chinese" to "Chinese (Mandarin)"
        list += "mandarin" to "Chinese (Mandarin)"
        list += "chinese" to "Chinese (Mandarin)"
        list += "tagalog" to "Filipino"
        list += "brazilian portuguese" to "Portuguese"
        list += "brazilian" to "Portuguese"
        list += "american english" to "English"
        list += "british english" to "English"
        list.distinctBy { it.first }.sortedByDescending { it.first.length }
    }
    private val LANG_NAME = LANG_ALIASES.toMap()
    private val LANG = LANG_ALIASES.joinToString("|") { Regex.escape(it.first) }

    /** The app's language name for a spoken one ("mandarin" → "Chinese (Mandarin)"), or null. */
    fun language(spoken: String?): String? = spoken?.let { LANG_NAME[it.trim().lowercase(Locale.ROOT)] }

    private const val SECS = "(?: ?(?:s|sec|secs|second|seconds))?"
    private const val UNIT = "(?:h|hr|hrs|hour|hours|m|min|mins|minute|minutes|s|sec|secs|second|seconds)"
    private const val DUR = "(?<dur>\\d+ ?$UNIT(?:(?: and)? \\d+ ?$UNIT)*)"
    private const val TIME = "(?<h>\\d{1,2})(?::(?<m>\\d{2})| (?<m2>\\d{2}))?(?: ?(?<ap>am|pm|a m|p m|in the morning|in the evening|in the afternoon|at night|tonight))?"
    private const val PHOTO = "(?:photo|picture|pic|pick|shot)"
    private const val TABS = "import|imports|downloads|transfer|gallery|photos|pictures|album|glasses|device|camera|assistant|ai|chat|settings|options|preferences"

    /** "1 hour 30 minutes" → 5400. */
    fun seconds(dur: String): Int {
        var total = 0L
        Regex("(\\d+) ?([a-z]+)").findAll(dur).forEach { m ->
            val n = m.groupValues[1].toLong()
            val u = m.groupValues[2]
            total += when {
                u.startsWith("h") -> n * 3600
                u.startsWith("m") -> n * 60
                else -> n
            }
        }
        return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun g(m: MatchResult, name: String): String? = runCatching { m.groups[name]?.value }.getOrNull()

    private fun tabOf(word: String): String = when (word) {
        "import", "imports", "downloads", "transfer" -> "IMPORT"
        "gallery", "photos", "pictures", "album" -> "GALLERY"
        "glasses", "device", "camera" -> "GLASSES"
        "assistant", "ai", "chat" -> "ASSISTANT"
        else -> "SETTINGS"
    }

    private fun onOff(word: String?): String = when (word) {
        "on", "enable", "start" -> "true"
        "off", "disable", "stop" -> "false"
        else -> "toggle"
    }

    /** Takes the phrase in the recognizer's own spelling when it can be found in the raw text. */
    private fun rawSpelling(raw: String, phrase: String): String {
        val words = phrase.split(' ').filter { it.isNotEmpty() }.map { Regex.escape(it) }
        val m = Regex(words.joinToString("[^A-Za-z0-9]+"), RegexOption.IGNORE_CASE).find(raw)
        return m?.value ?: phrase
    }

    private fun r(vararg p: String) = p.map { Regex("^$it$") }

    private val VOLUME_UP = Regex("^(?:volume up|louder|turn it up|turn up the volume|turn the volume up|increase (?:the )?volume|raise (?:the )?volume|speak up|more volume)$")

    // ---------------- the rules (order matters inside a tier) ----------------

    val rules: List<Rule> = listOf(
        // P0 control
        Rule("ai.stop", P0, r("(?:stop|cancel|never mind|nevermind|be quiet|shut up|quiet|stop talking|enough|silence|hush|stop it)"),
            "stop", "Assistant"),
        Rule("sys.help", P0, r("help", "what can i say", "what can you do", "(?:list|show|tell me)(?: me)?(?: the| all| your)?(?: voice)? commands",
            "voice commands", "what are (?:the|your)(?: voice)? commands", "what commands (?:are there|can i use|do you know)"),
            "what can I say", "Help"),
        Rule("ai.repeat", P0, r("(?:say|repeat) (?:that|it) again", "repeat(?: that| it)?", "what did you(?: just)? say", "come again", "pardon(?: me)?"),
            "say that again", "Assistant"),

        // P2 camera and glasses (timer before plain photo)
        Rule("glasses.photo_timer", P2, r(
            "(?:take|snap|capture|shoot) (?:a |an |the |me a )?$PHOTO in (?<n>\\d{1,2})$SECS",
            "$PHOTO in (?<n>\\d{1,2})$SECS",
            "self ?timer $PHOTO(?: for| of| in)? (?<n>\\d{1,2})$SECS",
            "(?:take a )?$PHOTO (?:with|on) (?:a )?(?<n>\\d{1,2})$SECS (?:timer|delay)",
        ), "take a picture in 5 seconds", "Camera") { m, _ ->
            val n = g(m, "n")?.toIntOrNull() ?: return@Rule null
            mapOf("n" to n.coerceIn(1, 30).toString())
        },
        Rule("glasses.photo", P2, r(
            "(?:take|snap|capture|shoot|grab) (?:a |an |the |me a |another )?$PHOTO(?: with (?:the |my )?glasses)?",
            "capture", "(?:say )?cheese", "photo", "picture",
        ), "take a picture", "Camera"),
        Rule("glasses.video_start", P2, r(
            "(?:start|begin) recording (?:a |the )?video(?: on (?:the |my )?glasses)?",
            "(?:start|begin) (?:a |the )?video(?: recording)?(?: on (?:the |my )?glasses)?",
            "record (?:a )?video(?: on (?:the |my )?glasses)?", "film (?:this|a video)",
        ), "start recording video", "Camera"),
        Rule("glasses.video_stop", P2, r(
            "(?:stop|end|finish) (?:the )?(?:video|video recording|recording (?:the )?video|filming)",
        ), "stop video", "Camera"),
        Rule("glasses.live_open", P2, r(
            "(?:open|start|show|turn on|go to) (?:the )?live ?view", "live ?view", "show me what (?:the |my )?glasses (?:see|can see)",
        ), "open live view", "Camera"),
        Rule("glasses.live_close", P2, r("(?:close|exit|stop|end|turn off|leave|quit) (?:the )?live ?view"), "close live view", "Camera"),
        Rule("glasses.live_snapshot", P2, r(
            "(?:save|take|grab) (?:a |the )?(?:snapshot|frame|screenshot|screen shot)", "screenshot (?:the )?live ?view", "grab (?:a )?frame",
        ), "save a snapshot", "Camera"),
        Rule("glasses.connect", P2, r(
            "(?:connect|reconnect)(?: to)?(?: the| my)?(?: glasses)?", "connect (?:the |my )?glasses again",
        ), "connect the glasses", "Glasses"),

        // P2 recording
        Rule("rec.memo_start", P2, r(
            "(?:start|begin|record|take|make) (?:a )?(?:voice )?(?:memo|note|voice note|voice recording|audio recording)",
            "record (?:some )?audio", "start (?:an )?audio recording",
        ), "start a voice memo", "Recording"),
        Rule("rec.memo_stop", P2, r(
            "(?:stop|end|finish|save) (?:the |my )?(?:voice )?(?:memo|note|voice note|audio recording|audio)",
        ), "stop the memo", "Recording"),

        // P2 gallery and import
        Rule("gallery.last_photo", P2, r(
            "(?:show|open|display|view) (?:me )?(?:my |the )?(?:last|latest|newest|most recent) $PHOTO", "show me that $PHOTO",
        ), "show the last photo", "Gallery"),
        Rule("gallery.delete_last", P2, r(
            "(?:delete|remove|trash|erase) (?:my |the |that )?(?:last |latest |newest )?(?:photo|picture|pic)",
        ), "delete the last photo", "Gallery", needsConfirm = true),
        Rule("import.start", P2, r(
            "import(?: my| the| all)?(?: photos| pictures| files| media| videos| everything)?(?: from (?:the |my )?glasses)?",
            "(?:copy|sync|transfer|download)(?: my| the| all)? (?:photos|pictures|files|media|videos|everything)(?: from (?:the |my )?glasses)?",
            "(?:copy|sync|transfer|download) (?:from )?(?:the |my )?glasses", "start (?:the |an )?import",
        ), "import from the glasses", "Gallery"),

        // P2 assistant
        Rule("ai.clear", P2, r(
            "(?:clear|reset|erase|delete|wipe) (?:the |this |our |my )?(?:chat|conversation|history|chat history)",
            "start over", "new (?:chat|conversation)", "forget (?:this|the|our) conversation",
        ), "clear the chat", "Assistant", needsConfirm = true),

        // P2 translation
        Rule("tr.set_pair", P2, r(
            "(?:translate|translation) from (?<from>$LANG) (?:to|into) (?<to>$LANG)",
            "translate (?<from>$LANG) (?:to|into) (?<to>$LANG)",
            "set (?:the )?translation(?: language)? (?:to|into) (?<to>$LANG)",
            "set (?:the )?translation from (?<from>$LANG) (?:to|into) (?<to>$LANG)",
            "translate (?:to|into) (?<to>$LANG)",
        ), "translate from Spanish to English", "Translation") { m, _ ->
            buildMap {
                language(g(m, "from"))?.let { put("from", it) }
                put("to", language(g(m, "to")) ?: return@Rule null)
            }
        },
        Rule("tr.swap", P2, r("(?:swap|switch|reverse|flip) (?:the )?(?:languages|translation|translation languages|language)"),
            "swap languages", "Translation"),
        Rule("tr.reply_language", P2, r(
            "(?:answer|reply|respond|talk to me|speak to me|speak) in (?<lang>$LANG)",
            "(?:set|change) (?:the |your )?(?:reply |answer )?language to (?<lang>$LANG)",
        ), "answer in Spanish", "Translation") { m, _ -> mapOf("lang" to (language(g(m, "lang")) ?: return@Rule null)) },
        Rule("tr.start", P2, r(
            "translate", "start translating", "translation(?: mode)?", "(?:start|open|begin) (?:the )?translat(?:or|ion)(?: mode)?", "interpreter(?: mode)?",
        ), "translate", "Translation"),

        // P2 phone utilities
        Rule("util.timer", P2, r(
            "(?:set |start )?(?:a |the )?timer (?:for )?$DUR", "(?:set |start )?(?:a )?$DUR timer", "(?:start )?(?:a )?countdown (?:for )?$DUR",
        ), "set a timer for 5 minutes", "Phone") { m, _ ->
            val s = seconds(g(m, "dur") ?: return@Rule null)
            if (s <= 0) null else mapOf("dur" to s.coerceIn(1, 24 * 3600).toString())
        },
        Rule("util.alarm", P2, r(
            "(?:set |make )?(?:an |a |the )?alarm (?:for |at )?$TIME", "wake me(?: up)? (?:at )?$TIME",
        ), "set an alarm for 7 am", "Phone") { m, _ ->
            var h = g(m, "h")?.toIntOrNull() ?: return@Rule null
            val min = (g(m, "m") ?: g(m, "m2"))?.toIntOrNull() ?: 0
            val ap = g(m, "ap")
            if (h > 23 || min > 59) return@Rule null
            val pm = ap != null && (ap.startsWith("p") || ap.contains("evening") || ap.contains("afternoon") || ap.contains("night"))
            val am = ap != null && !pm
            if ((am || pm) && (h == 0 || h > 12)) return@Rule null
            if (pm && h < 12) h += 12
            if (am && h == 12) h = 0
            mapOf("h" to h.toString(), "m" to min.toString(), "ampm" to if (ap != null) "1" else "0")
        },
        Rule("util.torch", P2, r(
            "(?:turn |switch )?(?:the )?(?:flashlight|torch|flash light)(?: (?<o>on|off))?",
            "(?:turn|switch) (?<o2>on|off) (?:the )?(?:flashlight|torch|flash light)",
            "(?<o3>enable|disable) (?:the )?(?:flashlight|torch|flash light)",
        ), "flashlight on", "Phone") { m, _ -> mapOf("onoff" to onOff(g(m, "o") ?: g(m, "o2") ?: g(m, "o3"))) },
        Rule("util.volume", P2, r(
            "(?:volume up|louder|turn it up|turn up the volume|turn the volume up|increase (?:the )?volume|raise (?:the )?volume|speak up|more volume)",
            "(?:volume down|quieter|softer|turn it down|turn down the volume|turn the volume down|lower (?:the )?volume|decrease (?:the )?volume|less volume)",
        ), "volume up", "Phone") { m, _ -> mapOf("dir" to if (VOLUME_UP.matches(m.value)) "up" else "down") },

        // P2 navigation
        Rule("nav.diagnostics", P2, r(
            "(?:open|show|go to|show me)(?: the)? (?:diagnostics|log|logs|connection log|debug info)", "diagnostics", "debug info",
        ), "open diagnostics", "Navigation"),
        Rule("nav.tab", P2, r(
            "(?:go to|open|show|show me|switch to|take me to)(?: the| my)? (?<tab>$TABS)(?: tab| screen| page)?",
        ), "go to settings", "Navigation") { m, _ -> mapOf("tab" to tabOf(g(m, "tab") ?: return@Rule null)) },

        // P2 settings
        Rule("set.speak_answers", P2, r(
            "(?:stop|do not|quit) (?:reading|read|speaking|speak|saying) (?:the |your )?answers(?: aloud| out loud)?",
            "(?:speak|read) (?:the )?answers (?:aloud |out loud )?(?<o>on|off)",
            "(?<t>text only)(?: answers)?",
            "(?:read|speak|say) (?:the |your )?answers(?: aloud| out loud)?",
            "(?:start )?(?:reading|speaking) (?:the |your )?answers(?: aloud| out loud)?",
        ), "stop reading answers aloud", "Settings") { m, _ ->
            val v = m.value
            val off = g(m, "o") == "off" || g(m, "t") != null || v.startsWith("stop") || v.startsWith("do not") || v.startsWith("quit")
            mapOf("onoff" to if (off) "false" else "true")
        },
        Rule("set.tap_to_talk", P2, r(
            "(?:turn |switch )?(?<o>on|off) tap to talk", "(?:turn |switch )?tap to talk (?<o2>on|off)", "(?<o3>enable|disable) tap to talk",
        ), "tap to talk off", "Settings") { m, _ -> mapOf("onoff" to onOff(g(m, "o") ?: g(m, "o2") ?: g(m, "o3"))) },
        Rule("set.speech_rate", P2, r(
            "(?:talk|speak|read)(?: a bit| a little)? (?<up>faster)", "(?<up2>speed up)", "(?<up3>faster)",
            "(?:talk|speak|read)(?: a bit| a little)? slower", "slow down", "slower",
            "(?<n>normal|regular|default) (?:speed|speaking speed|voice speed|speech rate)", "(?:talk|speak) (?<n2>normally)",
        ), "talk faster", "Settings") { m, _ ->
            mapOf("dir" to when {
                g(m, "up") != null || g(m, "up2") != null || g(m, "up3") != null -> "up"
                g(m, "n") != null || g(m, "n2") != null -> "normal"
                else -> "down"
            })
        },
        Rule("set.self_timer", P2, r(
            "(?:set )?(?:the )?self ?timer (?:to |for |at )?(?<n>\\d{1,2})$SECS",
            "(?:turn |switch )?(?:the )?self ?timer (?<off>off)", "(?:turn|switch) (?<off2>off) (?:the )?self ?timer",
            "(?<off3>disable|no) (?:the )?self ?timer",
        ), "set self timer to 5 seconds", "Settings") { m, _ ->
            val n = g(m, "n")?.toIntOrNull()
            mapOf("n" to (n ?: 0).toString())
        },
        Rule("acc.text_size", P2, r(
            "(?<u>bigger|larger) text", "make (?:the )?text (?<u2>bigger|larger)", "(?<u3>increase|enlarge) (?:the )?text(?: size)?",
            "text (?<u4>bigger|larger)",
            "smaller text", "make (?:the )?text smaller", "(?:decrease|shrink) (?:the )?text(?: size)?", "text smaller",
            "(?<n>normal|default|regular) text(?: size)?", "(?<n2>reset) (?:the )?text size",
        ), "bigger text", "Accessibility") { m, _ ->
            mapOf("dir" to when {
                listOf("u", "u2", "u3", "u4").any { g(m, it) != null } -> "up"
                g(m, "n") != null || g(m, "n2") != null -> "normal"
                else -> "down"
            })
        },
        Rule("acc.high_contrast", P2, r(
            "(?:turn |switch )?high contrast (?<o>on|off)", "(?:turn|switch) (?<o2>on|off) (?:the )?high contrast", "(?<o3>enable|disable) high contrast",
        ), "high contrast on", "Accessibility") { m, _ -> mapOf("onoff" to onOff(g(m, "o") ?: g(m, "o2") ?: g(m, "o3"))) },

        // P3 question-shaped local answers
        Rule("util.time", P3, r("what time is it", "what is the time", "time", "tell me the time", "(?:do you know )?what time it is", "the time"),
            "what time is it", "Phone"),
        Rule("util.date", P3, r(
            "what is (?:the )?date(?: today)?", "what is the date today", "what day is (?:it|today)", "what is today(?: date)?", "date", "today date",
        ), "what is the date", "Phone"),
        Rule("glasses.battery", P3, r(
            "(?:the |my )?glasses battery(?: level)?", "battery (?:level )?(?:on|of|for|in) (?:the |my )?glasses",
            "how much battery (?:do|does) (?:the |my )?glasses have(?: left)?", "how (?:is|are) (?:the |my )?glasses(?: battery| doing on battery)",
            "what is (?:the |my )?glasses battery(?: level| at)?", "how charged are (?:the |my )?glasses",
        ), "glasses battery", "Glasses"),
        Rule("util.phone_battery", P3, r(
            "battery", "(?:the |my )?phone battery(?: level)?", "how much battery do i have(?: left)?", "how much battery (?:does|is left on) (?:the |my )?phone(?: have)?(?: left)?",
            "battery (?:level )?(?:on|of) (?:the |my )?phone", "what is my battery(?: at| level)?", "battery level",
        ), "battery", "Phone"),
        Rule("glasses.status", P3, r(
            "are (?:the |my )?glasses connected", "(?:glasses|connection) status", "is (?:the )?(?:glasses|connection) (?:connected|working|on)",
            "status", "are (?:the |my )?glasses (?:on|working)",
        ), "are the glasses connected", "Glasses"),
        Rule("glasses.counts", P3, r(
            "how many (?:photos|pictures|videos|files|things) (?:are )?(?:on|in) (?:the |my )?glasses", "what is on (?:the |my )?glasses",
            "(?:the )?glasses storage", "what is (?:stored )?on (?:the |my )?glasses",
        ), "how many photos are on the glasses", "Glasses"),
        Rule("rec.status", P3, r(
            "how long have i been recording", "am i recording", "(?:recording|memo) status", "is (?:it|anything|something) recording",
        ), "am I recording", "Recording"),
        Rule("gallery.count", P3, r(
            "how many (?:photos|pictures|videos)(?: and videos)? (?:do i have|are saved|have i taken|are in (?:the |my )?gallery)",
        ), "how many photos do I have", "Gallery"),
        Rule("acc.where_am_i", P3, r("where am i", "what screen is this", "what is on (?:the )?screen", "which (?:screen|tab|page) is this"),
            "where am I", "Accessibility"),

        // P4 AI-backed
        Rule("ai.look", P4, r(
            "what am i looking at", "what do you see", "look at this", "describe (?:this|that|what i see|what i am looking at|the scene|my surroundings)",
            "what is (?:this|that|in front of me)",
        ), "what am I looking at", "Ask about what you see", needsNet = true),
        Rule("ai.read_text", P4, r(
            "read (?:this|that|it|the sign|the text|the label|the menu|this sign|this page|this out loud|it to me|this to me)",
            "what does (?:this|that|the sign|it) say", "read the text",
        ), "read this", "Ask about what you see", needsNet = true),
        Rule("ai.identify", P4, r(
            "what (?:plant|animal|bird|flower|tree|insect|bug|dog|cat|breed|mushroom|car) is (?:this|that|it)",
            "identify (?:this|that|it)(?: plant| animal| bird| flower)?",
            "what kind of (?:plant|animal|bird|flower|tree|dog|cat|car) is (?:this|that)",
        ), "what plant is this", "Ask about what you see", needsNet = true),
        Rule("ai.translate_view", P4, r(
            "translate (?:this|that|the sign|this sign|the menu|this menu|what i am looking at|what i see|the text|this text|this page)(?: (?:to|into) (?<lang>$LANG))?",
        ), "translate this sign", "Ask about what you see", needsNet = true) { m, _ ->
            buildMap { language(g(m, "lang"))?.let { put("lang", it) } }
        },
        Rule("gallery.describe_last", P4, r(
            "(?:describe|explain) (?:my |the )?(?:last|latest) $PHOTO", "what is in (?:my |the )?(?:last|latest) $PHOTO",
            "tell me about (?:that|my last|the last) $PHOTO",
        ), "describe my last photo", "Gallery", needsNet = true),
        // SupRevolt (3.2): a glasses photo sent to your own scan server. In translation mode these words
        // are translated like any other sentence (only "stop" is heard there).
        Rule("suprevolt.scan", P4, r(
            "scan (?:this|that|it)", "run a scan", "(?:sup|super) ?revolt scan(?: this| that)?",
            "scan (?:this|that|it) with (?:sup|super) ?revolt", "(?:sup|super) ?revolt this",
        ), "scan this", "SupRevolt", needsNet = true),
        Rule("tr.say_in", P4, r(
            "how (?:do|would|can) (?:you|i) say (?<phrase>.+?) in (?<lang>$LANG)", "say (?<phrase>.+?) in (?<lang>$LANG)",
            "translate (?<phrase>.+?) (?:to|into) (?<lang>$LANG)", "what is (?<phrase>.+?) in (?<lang>$LANG)",
        ), "how do you say good morning in Japanese", "Translation", needsNet = true) { m, raw ->
            val phrase = g(m, "phrase")?.trim().orEmpty()
            if (phrase.isEmpty() || language(phrase) != null || phrase.startsWith("from ")) return@Rule null
            mapOf("phrase" to rawSpelling(raw, phrase), "lang" to (language(g(m, "lang")) ?: return@Rule null))
        },
    )

    private val byId = rules.associateBy { it.id }

    fun rule(id: String): Rule? = byId[id]

    /** Every command id the app knows (without the internal yes and no). */
    val ids: List<String> get() = rules.map { it.id }

    private val YES = Regex("^(?:yes|yeah|yep|yup|sure|confirm|do it|go ahead|ok|okay|affirmative|yes do it|correct|right)$")
    private val NO = Regex("^(?:no|nope|cancel|stop|do not|never mind|nevermind|no thanks|do not do it|abort)$")
    private val EARLY_STOP = setOf("stop", "cancel", "be quiet", "shut up", "stop talking")

    /** A partial result that is exactly a stop word may end the turn at once (P0 only). */
    fun isEarlyStop(partial: String): Boolean = normalize(partial) in EARLY_STOP

    /** True for a stop word: the only command heard during translation. */
    fun isStop(text: String): Boolean = rules[0].patterns.any { it.matches(normalize(text)) }

    /**
     * The command in [raw], or null for the AI. P0 control first, then state-resolved words, then
     * device actions, local questions and AI-backed commands, first match wins.
     */
    fun match(raw: String, state: CommandState = CommandState()): Match? {
        val t = normalize(raw)
        if (t.isEmpty()) return null
        if (state.pendingConfirm != null) {
            if (YES.matches(t)) return Match("sys.yes")
            if (NO.matches(t)) return Match("sys.no")
        }
        val long = t.split(' ').size > MAX_WORDS
        for (rule in rules) if (rule.tier == P0) tryRule(rule, t, raw)?.let { return it }

        // P1: the same words mean different things depending on what's running.
        if (!long) {
            if (STOP_RECORDING.matches(t)) {
                return Match(if (state.memoRecording) "rec.memo_stop" else if (state.videoRecording) "glasses.video_stop" else "rec.memo_stop")
            }
            if (START_RECORDING.matches(t)) return Match("rec.memo_start")
            if (state.liveViewOpen && SAVE_WORDS.matches(t)) return Match("glasses.live_snapshot")
        }
        for (rule in rules) {
            if (rule.tier == P0) continue
            if (long && rule.id != "tr.say_in") continue
            tryRule(rule, t, raw)?.let { return it }
        }
        return null
    }

    private fun tryRule(rule: Rule, t: String, raw: String): Match? {
        for (p in rule.patterns) {
            val m = p.matchEntire(t) ?: continue
            val slots = rule.slots(m, raw) ?: continue
            return Match(rule.id, slots)
        }
        return null
    }

    /** Rules whose own patterns match [raw], ignoring priority (for the ambiguity guard in tests). */
    fun rulesMatching(raw: String): List<String> {
        val t = normalize(raw)
        return rules.filter { rule -> rule.patterns.any { p -> p.matchEntire(t)?.let { rule.slots(it, raw) != null } == true } }.map { it.id }
    }

    /** Six things to try, spoken by "what can I say". */
    val sampler = listOf("take a picture", "what am I looking at", "read this", "set a timer for 5 minutes", "flashlight on", "stop")

    /** The full list for the screen, grouped. */
    fun helpText(): String = buildString {
        rules.groupBy { it.group }.forEach { (group, list) ->
            append(group).append(": ")
            append(list.joinToString(", ") { "\"${it.helpLabel}\"" })
            append('\n')
        }
    }.trim()
}
