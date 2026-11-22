package com.zdycomp.p3companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class AiWireTest {

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
    private val key = "AIzaSyTESTKEY1234567890abcdefXYZ"

    @Suppress("UNCHECKED_CAST")
    private fun obj(s: String) = Json.parse(s) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.m(k: String) = this[k] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.l(k: String) = this[k] as List<Map<String, Any?>>

    // ---------------- Gemini ----------------

    @Test fun geminiRequestHasEndpointHeaderAndImage() {
        val req = AiWire.build(AiProvider.GEMINI, " $key ", "models/gemini-2.5-flash", "SYS", listOf(AiTurn(true, "What is this?")), jpeg)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent", req.url)
        assertTrue(req.headers.contains("x-goog-api-key" to key))
        assertFalse("key must not be in the URL", req.url.contains(key))
        assertFalse("key must not be in the body", req.body.contains(key))

        val body = obj(req.body)
        assertEquals("SYS", body.m("system_instruction").l("parts")[0]["text"])
        val contents = body.l("contents")
        assertEquals(1, contents.size)
        assertEquals("user", contents[0]["role"])
        val parts = contents[0].l("parts")
        val inline = parts[0].m("inline_data")
        assertEquals("image/jpeg", inline["mime_type"])
        assertEquals(Base64.getEncoder().encodeToString(jpeg), inline["data"])
        assertEquals("What is this?", parts[1]["text"])
    }

    @Test fun geminiHistoryUsesModelRoleAndImageOnlyOnLastTurn() {
        val turns = listOf(AiTurn(true, "Hi"), AiTurn(false, "Hello!"), AiTurn(true, "And this?"))
        val contents = obj(AiWire.geminiBody("S", turns, jpeg)).l("contents")
        assertEquals(listOf("user", "model", "user"), contents.map { it["role"] })
        assertEquals(1, contents[0].l("parts").size)
        assertEquals(2, contents[2].l("parts").size)
    }

    @Test fun geminiAnswerIsJoinedAndThoughtsSkipped() {
        val body = """{"candidates":[{"content":{"role":"model","parts":[{"text":"thinking","thought":true},
            {"text":"It's a "},{"text":"robin."}]},"finishReason":"STOP"}]}"""
        assertEquals("It's a robin.", AiWire.parseGemini(body))
    }

    @Test fun geminiBlockedPromptExplains() {
        val body = """{"promptFeedback":{"blockReason":"SAFETY"}}"""
        assertErrorContains("safety") { AiWire.parseGemini(body) }
        val empty = """{"candidates":[{"finishReason":"SAFETY"}]}"""
        assertErrorContains("safety") { AiWire.parseGemini(empty) }
    }

    @Test fun geminiBadKeyIsExplainedWithoutTheKey() {
        val body = """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}"""
        val msg = AiWire.errorMessage(AiProvider.GEMINI, 400, body, key)
        assertTrue(msg, msg.contains("didn't accept your key"))
        assertFalse(msg.contains(key))
    }

    // ---------------- Claude ----------------

    @Test fun claudeRequestHasHeadersAndImageBlock() {
        val req = AiWire.build(AiProvider.CLAUDE, key, "claude-sonnet-5-5", "SYS", listOf(AiTurn(true, "Describe")), jpeg)
        assertEquals("https://api.anthropic.com/v1/messages", req.url)
        assertTrue(req.headers.contains("x-api-key" to key))
        assertTrue(req.headers.contains("anthropic-version" to "2023-06-01"))
        assertFalse(req.body.contains(key))

        val body = obj(req.body)
        assertEquals("claude-sonnet-5-5", body["model"])
        assertEquals(4096L, body["max_tokens"])
        assertEquals("SYS", body["system"])
        val msg = body.l("messages")[0]
        assertEquals("user", msg["role"])
        val content = msg.l("content")
        assertEquals("image", content[0]["type"])
        val source = content[0].m("source")
        assertEquals("base64", source["type"])
        assertEquals("image/jpeg", source["media_type"])
        assertEquals(Base64.getEncoder().encodeToString(jpeg), source["data"])
        assertEquals(mapOf("type" to "text", "text" to "Describe"), content[1])
    }

    @Test fun claudeTextOnlyTurnsArePlainStringsAndAlternate() {
        val turns = listOf(AiTurn(false, "stray"), AiTurn(true, "a"), AiTurn(true, "b"), AiTurn(false, "c"), AiTurn(true, "d"))
        val msgs = obj(AiWire.claudeBody("claude-haiku-5-5", "S", turns, null)).l("messages")
        assertEquals(listOf("user", "assistant", "user"), msgs.map { it["role"] })
        assertEquals("a\nb", msgs[0]["content"])
        assertNull(obj(AiWire.claudeBody("claude-haiku-4-5", "S", turns, null))["fallbacks"])
    }

    @Test fun claudeFallbackOnlyForModelsThatSupportIt() {
        val req = AiWire.build(AiProvider.CLAUDE, key, "claude-sonnet-5-5", "S", listOf(AiTurn(true, "q")))
        assertEquals("default", obj(req.body)["fallbacks"])
        assertTrue(req.headers.any { it.first == "anthropic-beta" })
        val old = AiWire.build(AiProvider.CLAUDE, key, "claude-haiku-4-5", "S", listOf(AiTurn(true, "q")))
        assertFalse(old.headers.any { it.first == "anthropic-beta" })
    }

    @Test fun claudeAnswerKeepsOnlyTextBlocks() {
        val body = """{"id":"msg_1","type":"message","role":"assistant","content":[
            {"type":"thinking","thinking":"","signature":"x"},{"type":"text","text":"Hello \"there\" é"}],
            "stop_reason":"end_turn"}"""
        assertEquals("Hello \"there\" é", AiWire.parseClaude(body))
    }

    @Test fun claudeRefusalAndErrors() {
        assertErrorContains("declined") { AiWire.parseClaude("""{"content":[],"stop_reason":"refusal"}""") }
        val auth = """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""
        assertTrue(AiWire.errorMessage(AiProvider.CLAUDE, 401, auth).contains("didn't accept your key"))
        val rate = """{"type":"error","error":{"type":"rate_limit_error","message":"Number of requests has exceeded"}}"""
        assertTrue(AiWire.errorMessage(AiProvider.CLAUDE, 429, rate).contains("limit"))
        assertTrue(AiWire.errorMessage(AiProvider.CLAUDE, 529, null).contains("busy"))
        assertTrue(AiWire.errorMessage(AiProvider.GEMINI, 404, "not json").contains("model name"))
        assertTrue(AiWire.errorMessage(AiProvider.GEMINI, 429, """{"error":{"status":"RESOURCE_EXHAUSTED","message":"Quota exceeded"}}""").contains("limit"))
    }

    // ---------------- shared ----------------

    @Test fun missingKeyAndEmptyQuestionAreRefusedClearly() {
        assertErrorContains("key") { AiWire.build(AiProvider.GEMINI, "  ", "m", "s", listOf(AiTurn(true, "q"))) }
        assertErrorContains("question") { AiWire.build(AiProvider.GEMINI, key, "m", "s", listOf(AiTurn(true, "  "))) }
    }

    @Test fun modelNamesAreCleaned() {
        assertEquals("gemini-2.5-flash", AiWire.cleanModel(" models/gemini-2.5-flash ", AiProvider.GEMINI))
        assertEquals("claude-sonnet-5-5", AiWire.cleanModel("", AiProvider.CLAUDE))
        val odd = AiWire.cleanModel("gemini-2.5-pro/../evil?x=1#y", AiProvider.GEMINI)
        assertFalse(odd.any { it in "/?#=" })
        assertEquals("gemini-2.5-pro..evilx1y", odd)
    }

    @Test fun keysAreMasked() {
        val masked = AiWire.maskKey(key)
        assertEquals("AIza…wXYZ".replace("wXYZ", key.takeLast(4)), masked)
        assertFalse(masked.contains(key.substring(4, key.length - 4)))
        assertEquals("••••", AiWire.maskKey("short"))
        assertEquals("oops ${AiWire.maskKey(key)} here", AiWire.redact("oops $key here", key))
    }

    @Test fun jsonRoundTripsAwkwardText() {
        val text = "quote \" backslash \\ newline \n tab \t control \u0001 emoji 😀 中文"
        assertEquals(text, (Json.parse(Json.write(mapOf("t" to text))) as Map<*, *>)["t"])
        assertEquals(listOf(1L, 2.5, true, null), Json.parse("[1, 2.5, true, null]"))
        assertErrorContains("unexpected") { Json.parse("{\"a\":}") }
        assertErrorContains("unexpected") { Json.parse("[1,2") }
    }

    // ---------------- language enforcement ----------------

    @Test fun systemPromptAlwaysEnforcesTheReplyLanguage() {
        for (spoken in listOf(true, false)) {
            val p = AiWire.systemPrompt("English", spoken)
            assertTrue(p.contains("Always write your whole answer in English only"))
            assertTrue(p.contains("Never answer in Chinese"))
        }
        val es = AiWire.systemPrompt("spanish", spoken = true)
        assertTrue(es.contains("in Spanish only"))
        assertTrue(es.contains("read aloud"))
        assertTrue(es.contains("Never answer in Chinese"))
    }

    @Test fun unknownLanguageFallsBackToEnglish() {
        assertEquals("English", Languages.byName("Klingon").name)
        assertTrue(AiWire.systemPrompt("", spoken = false).contains("in English only"))
        assertEquals("ja-JP", Languages.byName("japanese").tag)
    }

    @Test fun translationPromptNamesBothLanguagesAndOutputOnlyTarget() {
        val p = AiWire.translationPrompt("English", "Spanish")
        assertTrue(p.contains("recognised in English"))
        assertTrue(p.contains("Output only the Spanish translation"))
        assertTrue(p.contains("never use any language other than Spanish"))
    }

    @Test fun readAndTranslatePresetUsesTheReplyLanguage() {
        assertTrue(AiWire.photoPreset(3, "German").contains("translate it into German"))
        assertEquals(4, AiWire.photoPresetLabels.size)
    }

    // ---------------- speech text ----------------

    @Test fun speakableRemovesMarkdown() {
        val md = "## Result\n- **Robin** (_Erithacus rubecula_)\n- See [docs](https://x.y)\n`code`"
        assertEquals("Result\nRobin (Erithacus rubecula)\nSee docs\ncode", AiWire.speakable(md))
    }

    @Test fun speechChunksRespectTheLimitAndSentenceEnds() {
        val text = (1..40).joinToString(" ") { "Sentence number $it is here." }
        val chunks = AiWire.speechChunks(text, 100)
        assertTrue(chunks.size > 5)
        assertTrue(chunks.all { it.length <= 100 })
        assertTrue(chunks.dropLast(1).all { it.endsWith(".") })
        assertEquals(text.replace(" ", ""), chunks.joinToString("").replace(" ", ""))
    }

    private fun assertErrorContains(part: String, block: () -> Unit) {
        try {
            block()
            fail("expected an error mentioning \"$part\"")
        } catch (e: AiException) {
            assertTrue("\"${e.message}\" should mention \"$part\"", e.message!!.contains(part, ignoreCase = true))
        }
    }
}
