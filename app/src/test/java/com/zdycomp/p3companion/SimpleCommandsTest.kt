package com.zdycomp.p3companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 4.0: the test table of docs/SIMPLE-COMMANDS.md §3. L rows run the offline matcher; F rows feed a
 * canned Gemini answer with functionCall parts through [AiWire.parseGeminiCalls] and [FnCalls].
 */
class SimpleCommandsTest {

    private val none = CommandState()
    private val video = CommandState(videoRecording = true)

    private data class L(val n: Int, val text: String, val state: CommandState, val id: String?, val slots: Map<String, String> = emptyMap())

    private val local = listOf(
        L(1, "take a picture", none, "glasses.photo"),
        L(2, "snap a pic please", none, "glasses.photo"),
        L(3, "snap one", none, "glasses.photo"),
        L(4, "start video", none, "glasses.video_start"),
        L(5, "stop recording", video, "glasses.video_stop"),
        L(6, "what am I looking at", none, "ai.look"),
        L(7, "what's this", none, "ai.look"),
        L(8, "read the sign", none, "ai.read_text"),
        L(9, "translate this to Spanish", none, "ai.translate_view", mapOf("lang" to "Spanish")),
        L(10, "translate", none, "tr.start"),
        L(11, "flashlight on", none, "util.torch", mapOf("onoff" to "true")),
        L(12, "lights off", none, "util.torch", mapOf("onoff" to "false")),
        L(13, "flashlight", none, "util.torch", mapOf("onoff" to "toggle")),
        L(14, "louder", none, "util.volume", mapOf("dir" to "up")),
        L(15, "turn it down", none, "util.volume", mapOf("dir" to "down")),
        L(16, "battery", none, "util.phone_battery"),
        L(17, "how's the battery", none, "util.phone_battery"),
        L(18, "live view", none, "glasses.live_open"),
        L(19, "never mind", none, "ai.stop"),
        L(20, "what can I say", none, "sys.help"),
        L(21, "scan this", none, "suprevolt.scan"),
        L(22, "set a timer for 5 minutes", none, "util.timer", mapOf("dur" to "300")),
        L(23, "delete the last photo", none, "gallery.delete_last"),
        L(24, "can you snap that sign and tell me what it says in Spanish", none, null),
    )

    @Test fun localRows() {
        val bad = local.mapNotNull { row ->
            val m = VoiceCommands.match(row.text, row.state)
            if (m?.id == row.id && row.slots.all { (k, v) -> m?.slots?.get(k) == v }) null
            else "row ${row.n} \"${row.text}\": got ${m?.id} ${m?.slots}, wanted ${row.id} ${row.slots}"
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
        assertTrue(VoiceCommands.rule("gallery.delete_last")!!.needsConfirm)
    }

    /** A canned Gemini answer whose parts are these function calls. */
    private fun answer(vararg calls: Pair<String, Map<String, Any?>>, text: String? = null): String = Json.write(mapOf(
        "candidates" to listOf(mapOf(
            "content" to mapOf("role" to "model", "parts" to buildList {
                text?.let { add(mapOf("text" to it)) }
                calls.forEach { (n, a) -> add(mapOf("functionCall" to mapOf("name" to n, "args" to a))) }
            }),
            "finishReason" to "STOP",
        ))
    ))

    private fun run(body: String): FnCalls.Plan = FnCalls.plan(AiWire.parseGeminiCalls(body))

    private fun assertPlan(n: Int, body: String, vararg want: Match) {
        val p = run(body)
        assertEquals("row $n", want.toList(), p.matches)
        assertNull("row $n", p.askBack)
    }

    @Test fun functionRows() {
        assertPlan(25, answer("take_photo" to mapOf("then" to "translate", "language" to "Spanish")), Match("ai.translate_view", mapOf("lang" to "Spanish")))
        assertPlan(26, answer("take_photo" to mapOf("then" to "none", "delay_seconds" to 3)), Match("glasses.photo_timer", mapOf("n" to "3")))
        assertPlan(27, answer("take_photo" to mapOf("then" to "ask", "question" to "Is this mushroom safe to eat?")),
            Match(FnCalls.ASK_VIEW, mapOf("question" to "Is this mushroom safe to eat?")))
        assertPlan(28, answer("record" to mapOf("kind" to "video", "on" to true)), Match("glasses.video_start"))
        assertPlan(29, answer("record" to mapOf("kind" to "audio", "on" to true)), Match("rec.memo_start"))
        assertPlan(30, answer("get_status" to mapOf("what" to "connection")), Match("glasses.status"))
        assertPlan(31, answer("get_status" to mapOf("what" to "glasses_storage")), Match("glasses.counts"))
        assertPlan(32, answer("glasses" to mapOf("action" to "import")), Match("import.start"))
        assertPlan(33, answer("last_photo" to mapOf("action" to "describe")), Match("gallery.describe_last"))
        assertPlan(34, answer("last_photo" to mapOf("action" to "delete")), Match("gallery.delete_last"))
        assertPlan(35, answer("translate" to mapOf("action" to "phrase", "text" to "where is the bathroom", "to" to "Italian")),
            Match("tr.say_in", mapOf("phrase" to "where is the bathroom", "lang" to "Italian")))
        assertPlan(36, answer("translate" to mapOf("action" to "conversation", "to" to "Portuguese")),
            Match("tr.set_pair", mapOf("to" to "Portuguese")), Match("tr.conversation"))
        assertPlan(37, answer("set_alarm" to mapOf("hour" to 6, "minute" to 45)), Match("util.alarm", mapOf("h" to "6", "m" to "45", "ampm" to "1")))
        assertPlan(38, answer("set_flashlight" to mapOf("on" to true)), Match("util.torch", mapOf("onoff" to "true")))
        assertPlan(39, answer(
            "set_setting" to mapOf("name" to "speech_rate", "value" to "slower"),
            "set_setting" to mapOf("name" to "text_size", "value" to "larger"),
        ), Match("set.speech_rate", mapOf("dir" to "down")), Match("acc.text_size", mapOf("dir" to "up")))
        // Row 40: only words ("How long should the timer be?"): no call. And a timer with no length asks back.
        assertTrue(AiWire.parseGeminiCalls(answer(text = "How long should the timer be?")).isEmpty())
        val p = run(answer("set_timer" to emptyMap()))
        assertTrue(p.matches.isEmpty())
        assertEquals("How long should the timer be?", p.askBack)
    }

    @Test fun deletingStillNeedsASpokenYesAndStopsTheChain() {
        val p = run(answer(
            "last_photo" to mapOf("action" to "delete"),
            "set_flashlight" to mapOf("on" to true),
        ))
        assertEquals(listOf(Match("gallery.delete_last")), p.matches)
        assertEquals(1, p.dropped)
        assertTrue(VoiceCommands.rule(p.matches.last().id)!!.needsConfirm)
        assertTrue(VoiceCommands.rule("ai.clear")!!.needsConfirm)
        assertEquals(Match("ai.clear"), FnCalls.toMatch(FnCall("assistant", mapOf("action" to "clear"))))
    }

    @Test fun extraChecks() {
        assertNull(FnCalls.toMatches(FnCall("launch_rocket")))
        assertNull(FnCalls.toMatches(FnCall("take_photo", mapOf("then" to "ask"))))
        assertNull(FnCalls.toMatches(FnCall("translate", mapOf("action" to "phrase", "text" to "hi"))))
        assertNull(FnCalls.toMatches(FnCall("set_setting", mapOf("name" to "volume", "value" to "sideways"))))
        assertNull(FnCalls.toMatches(FnCall("set_language", mapOf("target" to "replies", "language" to "Klingon"))))
        assertNull(FnCalls.toMatches(FnCall("set_timer", mapOf("seconds" to 0))))
        assertNull(FnCalls.toMatches(FnCall("set_alarm", mapOf("hour" to 25, "minute" to 0))))
        assertEquals("Translate into which language?", FnCalls.missingQuestion(FnCall("translate", mapOf("action" to "phrase", "text" to "hi"))))
        // Three calls keep their order; a fourth is dropped.
        val four = run(answer(
            "set_setting" to mapOf("name" to "volume", "value" to "up"),
            "set_flashlight" to mapOf("on" to false),
            "open_screen" to mapOf("name" to "gallery"),
            "assistant" to mapOf("action" to "repeat"),
        ))
        assertEquals(listOf("util.volume", "util.torch", "nav.tab"), four.matches.map { it.id })
        assertEquals("GALLERY", four.matches[2].slots["tab"])
        assertEquals(1, four.dropped)
        // Numbers may come as Long, Double or text.
        assertEquals("90", FnCalls.toMatch(FnCall("set_timer", mapOf("seconds" to 90.0)))?.slots?.get("dur"))
        assertEquals("120", FnCalls.toMatch(FnCall("set_timer", mapOf("seconds" to "120")))?.slots?.get("dur"))
        // Languages go through the matcher's own names.
        assertEquals("Chinese (Mandarin)", FnCalls.toMatch(FnCall("set_language", mapOf("target" to "replies", "language" to "mandarin")))?.slots?.get("lang"))
        assertEquals(Match("tr.start"), FnCalls.toMatch(FnCall("translate", mapOf("action" to "listen"))))
        assertEquals(listOf(Match("tr.set_pair", mapOf("to" to "French", "from" to "German")), Match("tr.start")),
            FnCalls.toMatches(FnCall("translate", mapOf("action" to "listen", "to" to "French", "from" to "German"))))
        assertEquals(Match("nav.diagnostics"), FnCalls.toMatch(FnCall("open_screen", mapOf("name" to "diagnostics"))))
        assertEquals(Match("gallery.describe_last", mapOf("question" to "who is that")),
            FnCalls.toMatch(FnCall("last_photo", mapOf("action" to "ask", "question" to "who is that"))))
        assertEquals(Match("set.self_timer", mapOf("n" to "5")), FnCalls.toMatch(FnCall("set_setting", mapOf("name" to "self_timer", "value" to "5"))))
        assertTrue(FnCalls.describe(FnCall("take_photo", mapOf("then" to "translate", "language" to "Spanish"))).startsWith("AI chose: take_photo then=translate"))
    }

    @Test fun everyDeclaredFunctionIsHandledAndTheDeclarationsAreValidJson() {
        assertEquals(15, FnCalls.FUNCTIONS.size)
        val parsed = Json.parse(Json.write(FnCalls.FUNCTIONS)) as List<*>
        assertEquals(15, parsed.size)
        parsed.forEach { f ->
            val m = f as Map<*, *>
            assertTrue((m["description"] as String).isNotBlank())
            assertEquals("object", (m["parameters"] as Map<*, *>)["type"])
        }
        // Each function with a minimal valid call gives a command.
        val minimal = mapOf(
            "take_photo" to mapOf("then" to "none"), "record" to mapOf("kind" to "video", "on" to false),
            "live_view" to mapOf("action" to "snapshot"), "get_status" to mapOf("what" to "time"), "glasses" to mapOf("action" to "connect"),
            "last_photo" to mapOf("action" to "show"), "scan" to emptyMap(), "translate" to mapOf("action" to "stop"),
            "set_language" to mapOf("target" to "swap"), "set_timer" to mapOf("seconds" to 60), "set_alarm" to mapOf("hour" to 7, "minute" to 0),
            "set_flashlight" to emptyMap(), "set_setting" to mapOf("name" to "high_contrast", "value" to "on"),
            "open_screen" to mapOf("name" to "settings"), "assistant" to mapOf("action" to "help"),
        )
        assertEquals(FnCalls.names.toSet(), minimal.keys)
        minimal.forEach { (n, a) ->
            val ms = FnCalls.toMatches(FnCall(n, a))
            assertTrue(n, !ms.isNullOrEmpty())
            ms!!.forEach { m -> assertTrue("$n → ${m.id}", m.id == FnCalls.ASK_VIEW || VoiceCommands.rule(m.id) != null) }
        }
    }

    @Test fun theVoiceChatRequestCarriesTheToolsOnlyWhenAsked() {
        val turns = listOf(AiTurn(true, "I need some light"))
        val withTools = Json.parse(AiWire.geminiBody(FnCalls.toolsPrompt("English"), turns, null, tools = true)) as Map<*, *>
        val tools = (withTools["tools"] as List<*>)[0] as Map<*, *>
        assertEquals(15, (tools["functionDeclarations"] as List<*>).size)
        assertEquals("AUTO", ((withTools["toolConfig"] as Map<*, *>)["functionCallingConfig"] as Map<*, *>)["mode"])
        val plain = Json.parse(AiWire.geminiBody("S", turns, null)) as Map<*, *>
        assertFalse(plain.containsKey("tools"))
        val stream = AiWire.buildStream(AiProvider.GEMINI, "AIzaKEY12345", "gemini-3.5-flash", "S", turns, tools = true)
        assertTrue(stream.body.contains("functionDeclarations"))
        val claude = AiWire.buildStream(AiProvider.CLAUDE, "sk-ant-KEY12345", "claude-sonnet-5-5", "S", turns, tools = true)
        assertFalse(claude.body.contains("functionDeclarations"))
        val prompt = FnCalls.toolsPrompt("Spanish")
        assertTrue(prompt.contains("call take_photo"))
        assertTrue(prompt.contains("in Spanish only"))
        assertTrue(prompt.contains("never ask for that yes yourself"))
    }

    @Test fun aStreamedFunctionCallArrivesWhole() {
        val data = answer("set_flashlight" to mapOf("on" to true))
        val ev = AiWire.parseStreamData(AiProvider.GEMINI, data)
        val call = ev.filterIsInstance<AiWire.StreamEvent.Call>().single().call
        assertEquals("set_flashlight", call.name)
        assertEquals(true, call.args["on"])
        assertTrue(ev.last() is AiWire.StreamEvent.Stop)
    }

    @Test fun helpNowTeachesTheTwelve() {
        assertEquals(12, VoiceCommands.core.size)
        assertTrue(VoiceCommands.HELP_SPEECH.contains("Or just ask me in your own words"))
        val help = VoiceCommands.coreHelpText()
        assertTrue(help.startsWith("Say any of these:"))
        assertTrue(help.contains("Also works:"))
        assertTrue(VoiceCommands.rules.count { it.core } >= 12)
        // Every core help label is one of the core rules.
        VoiceCommands.rules.filter { it.core }.forEach { assertEquals(it.id, VoiceCommands.match(it.helpLabel)?.id) }
    }
}
