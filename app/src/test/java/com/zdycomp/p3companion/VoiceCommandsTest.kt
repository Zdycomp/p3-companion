package com.zdycomp.p3companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The test table from docs/VOICE-COMMANDS.md §3.7, plus normalize, help-label and speed checks. */
class VoiceCommandsTest {

    private val none = CommandState()
    private val video = CommandState(videoRecording = true)
    private val memo = CommandState(memoRecording = true)
    private val live = CommandState(liveViewOpen = true)

    private data class Row(val n: Int, val text: String, val state: CommandState, val id: String?, val slots: Map<String, String> = emptyMap())

    private val table = listOf(
        Row(1, "Take a picture", none, "glasses.photo"),
        Row(2, "hey could you snap a photo please", none, "glasses.photo"),
        Row(3, "take a picture in 5 seconds", none, "glasses.photo_timer", mapOf("n" to "5")),
        Row(4, "photo in ten seconds", none, "glasses.photo_timer", mapOf("n" to "10")),
        Row(5, "start recording video", none, "glasses.video_start"),
        Row(6, "stop recording", video, "glasses.video_stop"),
        Row(7, "stop recording", memo, "rec.memo_stop"),
        Row(8, "stop", none, "ai.stop"),
        Row(9, "open live view", none, "glasses.live_open"),
        Row(10, "save a snapshot", live, "glasses.live_snapshot"),
        Row(11, "how much battery do the glasses have", none, "glasses.battery"),
        Row(12, "battery", none, "util.phone_battery"),
        Row(13, "are the glasses connected", none, "glasses.status"),
        Row(14, "reconnect", none, "glasses.connect"),
        Row(15, "how many photos are on the glasses", none, "glasses.counts"),
        Row(16, "how many photos do I have", none, "gallery.count"),
        Row(17, "start a voice memo", none, "rec.memo_start"),
        Row(18, "how long have I been recording", none, "rec.status"),
        Row(19, "show the last photo", none, "gallery.last_photo"),
        Row(20, "describe my last photo", none, "gallery.describe_last"),
        Row(21, "delete the last photo", none, "gallery.delete_last"),
        Row(22, "yes", CommandState(pendingConfirm = "gallery.delete_last"), "sys.yes"),
        Row(23, "yes", none, null),
        Row(24, "cancel", CommandState(pendingConfirm = "ai.clear"), "sys.no"),
        Row(25, "cancel", none, "ai.stop"),
        Row(26, "What am I looking at?", none, "ai.look"),
        Row(27, "read the sign", none, "ai.read_text"),
        Row(28, "what plant is this", none, "ai.identify"),
        Row(29, "translate this menu", none, "ai.translate_view"),
        Row(30, "say that again", none, "ai.repeat"),
        Row(31, "start over", none, "ai.clear"),
        Row(32, "translate", none, "tr.start"),
        Row(33, "translate from Spanish to English", none, "tr.set_pair", mapOf("from" to "Spanish", "to" to "English")),
        Row(34, "set translation to French", none, "tr.set_pair", mapOf("to" to "French")),
        Row(35, "swap languages", none, "tr.swap"),
        Row(36, "how do you say good morning in Japanese", none, "tr.say_in", mapOf("phrase" to "good morning", "lang" to "Japanese")),
        Row(37, "answer in mandarin", none, "tr.reply_language", mapOf("lang" to "Chinese (Mandarin)")),
        Row(38, "What time is it?", none, "util.time"),
        Row(39, "what time is it in Tokyo", none, null),
        Row(40, "what's the date", none, "util.date"),
        Row(41, "set a timer for five minutes", none, "util.timer", mapOf("dur" to "300")),
        Row(42, "1 hour 30 minute timer", none, "util.timer", mapOf("dur" to "5400")),
        Row(43, "wake me up at 6:30 am", none, "util.alarm", mapOf("h" to "6", "m" to "30")),
        Row(44, "set an alarm for 7 pm", none, "util.alarm", mapOf("h" to "19", "m" to "0")),
        Row(45, "set an alarm for 25", none, null),
        Row(46, "turn on the flashlight", none, "util.torch", mapOf("onoff" to "true")),
        Row(47, "flashlight", none, "util.torch", mapOf("onoff" to "toggle")),
        Row(48, "louder", none, "util.volume", mapOf("dir" to "up")),
        Row(49, "go to settings", none, "nav.tab", mapOf("tab" to "SETTINGS")),
        Row(50, "open photos", none, "nav.tab", mapOf("tab" to "GALLERY")),
        Row(51, "show the log", none, "nav.diagnostics"),
        Row(52, "stop reading answers aloud", none, "set.speak_answers", mapOf("onoff" to "false")),
        Row(53, "talk faster", none, "set.speech_rate", mapOf("dir" to "up")),
        Row(54, "set self timer to 5 seconds", none, "set.self_timer", mapOf("n" to "5")),
        Row(55, "bigger text", none, "acc.text_size", mapOf("dir" to "up")),
        Row(56, "high contrast on", none, "acc.high_contrast", mapOf("onoff" to "true")),
        Row(57, "where am I", none, "acc.where_am_i"),
        Row(58, "what can I say", none, "sys.help"),
        Row(59, "import from the glasses", none, "import.start"),
        Row(60, "tell me a joke about pizza", none, null),
        Row(61, "can you take a picture of the moon and tell me how far away it is", none, null),
        Row(62, "what is the capital of France", none, null),
        Row(63, "scan this", none, "suprevolt.scan"),
        Row(64, "SupRevolt scan", none, "suprevolt.scan"),
        Row(65, "hey could you run a scan please", none, "suprevolt.scan"),
        Row(66, "super revolt scan that", none, "suprevolt.scan"),
        Row(67, "scan it with SupRevolt", none, "suprevolt.scan"),
        Row(68, "scan the whole room and tell me what is dangerous", none, null),
    )

    @Test fun specTable() {
        val failures = table.mapNotNull { row ->
            val m = VoiceCommands.match(row.text, row.state)
            val idOk = m?.id == row.id
            val slotsOk = row.slots.all { (k, v) -> m?.slots?.get(k) == v }
            if (idOk && slotsOk) null else "row ${row.n} \"${row.text}\": got ${m?.id} ${m?.slots}, wanted ${row.id} ${row.slots}"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun normalizeHandlesPunctuationPolitenessAndContractions() {
        assertEquals("snap a photo", VoiceCommands.normalize("hey could you snap a photo please"))
        assertEquals("what am i looking at", VoiceCommands.normalize("What am I looking at?"))
        assertEquals("what time is it", VoiceCommands.normalize("What time is it?"))
        assertEquals("take a picture", VoiceCommands.normalize("Hey, could you take a picture please?"))
        assertEquals("set a timer for 5 minutes", VoiceCommands.normalize("Set a timer for five minutes."))
        assertEquals("what is the date", VoiceCommands.normalize("What’s the date"))
        assertEquals("set a timer for 25 minutes", VoiceCommands.normalize("set a timer for twenty five minutes"))
        assertEquals("timer for 1 minute", VoiceCommands.normalize("timer for a minute"))
        assertEquals("timer for 30 minutes", VoiceCommands.normalize("timer for half an hour"))
        assertEquals("okay", VoiceCommands.normalize("okay"))
        assertEquals("wake me up at 6:30 a m", VoiceCommands.normalize("Wake me up at 6:30 a.m."))
        assertEquals("18", VoiceCommands.match("Wake me up at 6 p.m.")?.slots?.get("h"))
    }

    @Test fun moreSlots() {
        assertEquals("5400", VoiceCommands.match("set a timer for 1 hour and 30 minutes")?.slots?.get("dur"))
        assertEquals("30", VoiceCommands.match("timer 30 seconds")?.slots?.get("dur"))
        assertEquals("0", VoiceCommands.match("self timer off")?.slots?.get("n"))
        assertEquals("down", VoiceCommands.match("turn it down")?.slots?.get("dir"))
        assertEquals("normal", VoiceCommands.match("normal speed")?.slots?.get("dir"))
        assertEquals("false", VoiceCommands.match("turn off the flashlight")?.slots?.get("onoff"))
        assertEquals("0", VoiceCommands.match("set an alarm for 12 am")?.slots?.get("h"))
        assertEquals("0", VoiceCommands.match("set an alarm for 7")?.slots?.get("ampm"))
        assertEquals("Spanish", VoiceCommands.match("translate this sign into Spanish")?.slots?.get("lang"))
        assertEquals("Hola amigo", VoiceCommands.match("Translate Hola amigo to English")?.slots?.get("phrase"))
        assertEquals("ASSISTANT", VoiceCommands.match("open chat")?.slots?.get("tab"))
        assertEquals("30", VoiceCommands.match("take a photo in 45 seconds")?.slots?.get("n"))
    }

    @Test fun stateDecidesStopRecordingAndSave() {
        assertEquals("rec.memo_start", VoiceCommands.match("start recording")?.id)
        assertEquals("glasses.live_snapshot", VoiceCommands.match("save", live)?.id)
        assertNull(VoiceCommands.match("save"))
        assertEquals("sys.no", VoiceCommands.match("no", CommandState(pendingConfirm = "ai.clear"))?.id)
        assertNull(VoiceCommands.match("yes but not that one", CommandState(pendingConfirm = "ai.clear"))?.takeIf { it.id == "sys.yes" })
        assertEquals("sys.yes", VoiceCommands.match("Yes please.", CommandState(pendingConfirm = "ai.clear"))?.id)
        assertEquals("sys.yes", VoiceCommands.match("Okay", CommandState(pendingConfirm = "ai.clear"))?.id)
    }

    @Test fun earlyStopOnlyForStopWords() {
        assertTrue(VoiceCommands.isEarlyStop("Stop"))
        assertTrue(VoiceCommands.isEarlyStop("be quiet"))
        assertTrue(!VoiceCommands.isEarlyStop("stop recording"))
        assertTrue(!VoiceCommands.isEarlyStop("yes"))
        assertTrue(VoiceCommands.isStop("never mind"))
        assertTrue(!VoiceCommands.isStop("hello"))
    }

    @Test fun scanThisIsANetCommandAndIsNotHeardDuringTranslation() {
        val rule = VoiceCommands.rule("suprevolt.scan")!!
        assertTrue(rule.needsNet)
        assertEquals("scan this", rule.helpLabel)
        // In translation mode only a stop word is a command (Assistant checks isStop); "scan this" is translated.
        assertFalse(VoiceCommands.isStop("scan this"))
        assertFalse(VoiceCommands.isStop("SupRevolt scan"))
        assertTrue(VoiceCommands.helpText().contains("SupRevolt: \"scan this\""))
    }

    @Test fun conversationTranslateCommands() {
        listOf(
            "translate conversation", "Start translating", "start translating the conversation", "translate this conversation",
            "conversation mode", "hey could you translate the conversation please", "start conversation translation",
            "interpret the conversation", "translate everything they say", "keep translating",
        ).forEach { assertEquals(it, "tr.conversation", VoiceCommands.match(it)?.id) }
        listOf("stop translating", "Stop the translation.", "end conversation", "finish translating").forEach {
            assertEquals(it, "tr.conversation_stop", VoiceCommands.match(it)?.id)
        }
        // The one-sentence translator and the other translation commands are unchanged.
        assertEquals("tr.start", VoiceCommands.match("translate")?.id)
        assertEquals("tr.start", VoiceCommands.match("translation mode")?.id)
        assertEquals("ai.translate_view", VoiceCommands.match("translate this sign")?.id)
        assertEquals("tr.say_in", VoiceCommands.match("translate good night to French")?.id)
        assertEquals("ai.stop", VoiceCommands.match("stop")?.id)
    }

    @Test fun whatEndsAConversationWhileItListens() {
        assertTrue(VoiceCommands.isConversationStop("stop"))
        assertTrue(VoiceCommands.isConversationStop("Stop translating."))
        assertTrue(VoiceCommands.isConversationStop("never mind"))
        assertFalse(VoiceCommands.isConversationStop("stop by the store on your way home"))
        assertFalse(VoiceCommands.isConversationStop("translate conversation"))
        assertFalse(VoiceCommands.isConversationStop("¿dónde está la estación?"))
    }

    @Test fun atLeast34CommandsWithUniqueIds() {
        val ids = VoiceCommands.ids
        assertEquals(ids.size, ids.toSet().size)
        assertTrue("only ${ids.size} commands", ids.size >= 35)
        assertTrue(VoiceCommands.rules.filter { it.needsConfirm }.map { it.id }.containsAll(listOf("gallery.delete_last", "ai.clear")))
    }

    @Test fun everyHelpLabelMatchesItsOwnRule() {
        val wrong = VoiceCommands.rules.filter { VoiceCommands.match(it.helpLabel)?.id != it.id }
            .map { "${it.id}: \"${it.helpLabel}\" → ${VoiceCommands.match(it.helpLabel)?.id}" }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test fun noHelpLabelMatchesTwoRules() {
        val ambiguous = VoiceCommands.rules.mapNotNull { r ->
            val hits = VoiceCommands.rulesMatching(r.helpLabel)
            if (hits.size == 1) null else "\"${r.helpLabel}\" → $hits"
        }
        assertTrue(ambiguous.joinToString("\n"), ambiguous.isEmpty())
    }

    @Test fun helpTextListsEveryCommand() {
        val text = VoiceCommands.helpText()
        VoiceCommands.rules.forEach { assertTrue(it.helpLabel, text.contains(it.helpLabel)) }
        VoiceCommands.sampler.forEach { assertTrue(it, VoiceCommands.match(it) != null) }
    }

    @Test fun fastEnough() {
        table.forEach { VoiceCommands.match(it.text, it.state) } // warm up
        val n = 1_000
        val t0 = System.nanoTime()
        repeat(n) { table.forEach { VoiceCommands.match(it.text, it.state) } }
        val perCall = (System.nanoTime() - t0) / 1e6 / (n * table.size)
        assertTrue("match() took $perCall ms per call", perCall < 0.5)
    }
}
