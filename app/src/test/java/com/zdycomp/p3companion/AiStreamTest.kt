package com.zdycomp.p3companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Streaming answers (3.1): request shape, server-sent events, and cutting text into sentences. */
class AiStreamTest {

    private val turns = listOf(AiTurn(true, "Hi"))

    @Test fun geminiStreamUrlAndHeaders() {
        val r = AiWire.buildStream(AiProvider.GEMINI, "AIzaKEY1234567", "gemini-2.5-flash", "sys", turns)
        assertEquals(AiWire.GEMINI_BASE + "gemini-2.5-flash:streamGenerateContent?alt=sse", r.url)
        assertTrue(r.headers.contains("Accept" to "text/event-stream"))
        assertTrue(r.body.contains("\"contents\""))
    }

    @Test fun claudeStreamBodyAsksToStream() {
        val r = AiWire.buildStream(AiProvider.CLAUDE, "sk-ant-KEY123456", "claude-sonnet-5-5", "sys", turns)
        assertEquals(AiWire.CLAUDE_URL, r.url)
        val body = Json.parse(r.body) as Map<*, *>
        assertEquals(true, body["stream"])
        val plain = Json.parse(AiWire.build(AiProvider.CLAUDE, "sk-ant-KEY123456", "claude-sonnet-5-5", "sys", turns).body) as Map<*, *>
        assertNull(plain["stream"])
    }

    private fun events(provider: AiProvider, raw: String): List<AiWire.StreamEvent> {
        val sse = SseParser()
        val out = mutableListOf<AiWire.StreamEvent>()
        raw.split("\n").forEach { line -> sse.line(line)?.let { out += AiWire.parseStreamData(provider, it) } }
        sse.flush()?.let { out += AiWire.parseStreamData(provider, it) }
        return out
    }

    @Test fun geminiServerSentEvents() {
        val raw = """
            data: {"candidates":[{"content":{"parts":[{"text":"Hello there."}],"role":"model"}}]}

            data: {"candidates":[{"content":{"parts":[{"thought":true,"text":"thinking"},{"text":" How are you?"}],"role":"model"},"finishReason":"STOP"}]}

        """.trimIndent()
        val ev = events(AiProvider.GEMINI, raw)
        assertEquals(listOf(AiWire.StreamEvent.Text("Hello there."), AiWire.StreamEvent.Text(" How are you?"), AiWire.StreamEvent.Stop("STOP")), ev)
    }

    @Test fun geminiBlockedPrompt() {
        val ev = events(AiProvider.GEMINI, "data: {\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}\n")
        assertTrue(ev.single() is AiWire.StreamEvent.Failed)
        assertTrue(AiWire.emptyStreamMessage(AiProvider.GEMINI, "SAFETY").contains("safety"))
    }

    @Test fun claudeServerSentEvents() {
        val raw = """
            event: message_start
            data: {"type":"message_start","message":{"id":"m1","content":[]}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: ping
            data: {"type": "ping"}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Sure. "}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"It is sunny."}}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}

            event: message_stop
            data: {"type":"message_stop"}
        """.trimIndent()
        val ev = events(AiProvider.CLAUDE, raw)
        assertEquals(listOf(AiWire.StreamEvent.Text("Sure. "), AiWire.StreamEvent.Text("It is sunny."), AiWire.StreamEvent.Stop("end_turn")), ev)
    }

    @Test fun claudeRefusalAndErrors() {
        val refusal = events(AiProvider.CLAUDE, "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"refusal\"}}\n")
        assertTrue((refusal.single() as AiWire.StreamEvent.Failed).message.contains("declined"))
        val busy = events(AiProvider.CLAUDE, "data: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}\n")
        assertTrue((busy.single() as AiWire.StreamEvent.Failed).message.contains("busy"))
    }

    @Test fun splitterSpeaksEachSentenceAsSoonAsItEnds() {
        val sp = SentenceSplitter()
        assertEquals(emptyList<String>(), sp.feed("Hello"))
        assertEquals(emptyList<String>(), sp.feed(" there."))
        assertEquals(listOf("Hello there."), sp.feed(" Dr. Smith"))
        assertEquals(listOf("Dr. Smith is in!"), sp.feed(" is in! It costs 3.50 dollars"))
        assertEquals(emptyList<String>(), sp.feed(", e.g. today"))
        assertEquals(listOf("It costs 3.50 dollars, e.g. today."), sp.feed(".\nNext"))
        assertEquals("Next", sp.flush())
        assertNull(sp.flush())
    }

    @Test fun splitterCutsVeryLongRunsAtACommaAndHandlesCjk() {
        val sp = SentenceSplitter(softLimit = 40)
        val out = sp.feed("one two three four five six seven, eight nine ten eleven twelve")
        assertEquals(listOf("one two three four five six seven,"), out)
        assertEquals(listOf("你好。"), SentenceSplitter().feed("你好。再见"))
        assertEquals(listOf("\"Quoted.\""), SentenceSplitter().feed("\"Quoted.\" Next"))
    }

    @Test fun sseIgnoresCommentsAndJoinsMultiLineData() {
        val sse = SseParser()
        assertNull(sse.line(": keep-alive"))
        assertNull(sse.line("data: {\"a\":"))
        assertNull(sse.line("data: 1}"))
        assertEquals("{\"a\":\n1}", sse.line(""))
        assertNull(sse.flush())
    }
}
