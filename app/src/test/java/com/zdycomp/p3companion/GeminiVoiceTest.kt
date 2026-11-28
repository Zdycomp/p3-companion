package com.zdycomp.p3companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** 4.0: Gemini's voice (TTS request, PCM and WAV audio), model selection, the phone-voice picker and the strict transcription prompt. */
class GeminiVoiceTest {

    private fun obj(s: String) = Json.parse(s) as Map<*, *>
    private fun Map<*, *>.m(k: String) = this[k] as Map<*, *>

    // ---------------- the speech request ----------------

    @Test fun ttsBodyHasAudioVoiceAndLanguageAndNoForbiddenFields() {
        val body = GeminiVoice.ttsBody("Hello there.", "Puck", "en-US")
        GeminiVoice.TTS_FORBIDDEN.forEach { assertFalse("$it must not be sent", body.contains("\"$it\"")) }
        val root = obj(body)
        assertEquals(setOf("contents", "generationConfig"), root.keys)
        val gc = root.m("generationConfig")
        assertEquals(setOf("responseModalities", "speechConfig"), gc.keys)
        assertEquals(listOf("AUDIO"), gc["responseModalities"])
        val sc = gc.m("speechConfig")
        assertEquals("Puck", sc.m("voiceConfig").m("prebuiltVoiceConfig")["voiceName"])
        assertEquals("en-US", sc["languageCode"])
        val parts = ((root["contents"] as List<*>)[0] as Map<*, *>)["parts"] as List<*>
        assertEquals("Hello there.", (parts[0] as Map<*, *>)["text"])
    }

    @Test fun ttsRequestStreamsWithTheKeyInAHeaderOnly() {
        val key = "AIzaSECRETKEY123456"
        val s = GeminiVoice.ttsRequest(key, "gemini-3.8-flash-tts", "Hi", "kore", "es-ES", stream = true)
        assertEquals(AiWire.GEMINI_BASE + "gemini-3.8-flash-tts:streamGenerateContent?alt=sse", s.url)
        assertFalse(s.url.contains(key))
        assertTrue(s.headers.contains("x-goog-api-key" to key))
        assertTrue(s.body.contains("\"Kore\""))
        val u = GeminiVoice.ttsRequest(key, "models/gemini-3.8-flash-tts", "Hi", "Kore", "en-US", stream = false)
        assertEquals(AiWire.GEMINI_BASE + "gemini-3.8-flash-tts:generateContent", u.url)
    }

    @Test fun voicesAndTheDefault() {
        assertEquals(30, GeminiVoice.VOICES.size)
        assertEquals("Kore", GeminiVoice.DEFAULT_VOICE)
        assertEquals("Zephyr", GeminiVoice.voiceName("zephyr"))
        assertEquals("Kore", GeminiVoice.voiceName("Not a voice"))
        assertEquals("Kore", GeminiVoice.voiceName(null))
    }

    // ---------------- audio back ----------------

    private fun pcm(vararg s: Int): ByteArray = ByteArray(s.size * 2).also { b ->
        s.forEachIndexed { i, v -> b[2 * i] = (v and 0xFF).toByte(); b[2 * i + 1] = ((v shr 8) and 0xFF).toByte() }
    }

    @Test fun rateComesFromTheMimeType() {
        assertEquals(24_000, GeminiVoice.rateFromMime("audio/L16;codec=pcm;rate=24000"))
        assertEquals(16_000, GeminiVoice.rateFromMime("audio/l16; rate=16000"))
        assertEquals(24_000, GeminiVoice.rateFromMime("audio/L16"))
        assertEquals(24_000, GeminiVoice.rateFromMime(null))
    }

    @Test fun wavIsReadWhereverItsChunksAre() {
        val data = pcm(1, -2, 300, -32768)
        val wav = Wav.pcm16(shortArrayOf(1, -2, 300, -32768), 24_000)
        val a = GeminiVoice.parseWav(wav)!!
        assertEquals(24_000, a.rate)
        assertEquals(1, a.channels)
        assertTrue(a.pcm.contentEquals(data))
        // A LIST chunk before data (not a plain 44-byte header).
        val list = "LIST".toByteArray() + byteArrayOf(4, 0, 0, 0) + "INFO".toByteArray()
        val odd = wav.copyOfRange(0, 36) + list + wav.copyOfRange(36, wav.size)
        val b = GeminiVoice.parseWav(odd)!!
        assertTrue(b.pcm.contentEquals(data))
        assertNull(GeminiVoice.parseWav(data))
        // Raw PCM: headerless, at the MIME type's rate.
        val raw = GeminiVoice.decodeAudio(data, "audio/L16;codec=pcm;rate=24000")
        assertEquals(24_000, raw.rate)
        assertTrue(raw.pcm.contentEquals(data))
        assertEquals(0L, raw.ms)
        assertEquals(1000L, GeminiVoice.Audio(ByteArray(48_000), 24_000).ms)
    }

    private fun chunk(bytes: ByteArray, finish: String? = null): String = Json.write(mapOf(
        "candidates" to listOf(buildMap {
            put("content", mapOf("role" to "model", "parts" to listOf(mapOf("inlineData" to mapOf(
                "mimeType" to "audio/L16;codec=pcm;rate=24000", "data" to Base64.getEncoder().encodeToString(bytes))))))
            finish?.let { put("finishReason", it) }
        })
    ))

    @Test fun streamedEventsGiveAudioThenStop() {
        val ev = GeminiVoice.parseStreamEvent(chunk(pcm(5, 6, 7)))
        assertEquals(1, ev.size)
        val c = ev[0] as GeminiVoice.Event.Chunk
        assertEquals(24_000, c.audio.rate)
        assertTrue(c.audio.pcm.contentEquals(pcm(5, 6, 7)))
        val last = GeminiVoice.parseStreamEvent(chunk(pcm(1), "STOP"))
        assertTrue(last.last() is GeminiVoice.Event.Stop)
        val err = GeminiVoice.parseStreamEvent("""{"error":{"code":400,"message":"bad voice"}}""")
        assertTrue((err.single() as GeminiVoice.Event.Failed).message.contains("bad voice"))
        assertTrue(GeminiVoice.parseStreamEvent("[DONE]").isEmpty())
        assertTrue(GeminiVoice.parseStreamEvent("not json").isEmpty())
    }

    @Test fun unaryAnswerWithAWavOrPcm() {
        val wav = Wav.pcm16(shortArrayOf(10, 20, 30), 24_000)
        val body = Json.write(mapOf("candidates" to listOf(mapOf("content" to mapOf("parts" to listOf(
            mapOf("inline_data" to mapOf("mime_type" to "audio/wav", "data" to Base64.getEncoder().encodeToString(wav)))))))))
        val a = GeminiVoice.parseUnary(body)
        assertTrue(a.pcm.contentEquals(pcm(10, 20, 30)))
        val b = GeminiVoice.parseUnary(chunk(pcm(1, 2)))
        assertTrue(b.pcm.contentEquals(pcm(1, 2)))
        val none = runCatching { GeminiVoice.parseUnary("""{"candidates":[{"content":{"parts":[{"text":"hi"}]}}]}""") }
        assertTrue(none.exceptionOrNull() is AiException)
    }

    @Test fun longAnswersAreSplitBySentence() {
        val text = "First sentence here. Second one is a bit longer than that! " + "Third? ".repeat(3) + "Last."
        val p = GeminiVoice.pieces(text, target = 40)
        assertTrue(p.size >= 3)
        p.forEach { assertTrue(it, it.length <= 60) }
        assertEquals(text.replace(Regex("\\s+"), " ").trim(), p.joinToString(" "))
        assertEquals(listOf("Short."), GeminiVoice.pieces("**Short.**"))
    }

    // ---------------- models ----------------

    private val modelsJson = """
        {"models":[
          {"name":"models/gemini-2.5-flash","supportedGenerationMethods":["generateContent","countTokens"]},
          {"name":"models/gemini-3.0-flash","supportedGenerationMethods":["generateContent"]},
          {"name":"models/gemini-3.8-flash-preview","supportedGenerationMethods":["generateContent"]},
          {"name":"models/gemini-3.5-flash","supportedGenerationMethods":["generateContent","countTokens"]},
          {"name":"models/gemini-3.5-flash-lite","supportedGenerationMethods":["generateContent"]},
          {"name":"models/gemini-3.5-flash-image","supportedGenerationMethods":["generateContent"]},
          {"name":"models/gemini-3.5-flash-live","supportedGenerationMethods":["bidiGenerateContent"]},
          {"name":"models/gemini-3.5-pro","supportedGenerationMethods":["generateContent"]},
          {"name":"models/gemini-3.6-flash-embedding","supportedGenerationMethods":["embedContent"]},
          {"name":"models/gemini-2.5-flash-preview-tts","supportedGenerationMethods":["generateContent"]},
          {"name":"models/gemini-3.8-flash-lite-tts","supportedGenerationMethods":["generateContent"]},
          {"name":"models/gemini-3.8-flash-tts","supportedGenerationMethods":["generateContent"]}
        ],"nextPageToken":"abc"}
    """.trimIndent()

    @Test fun modelListIsReadAndTheNewestStableFlashIsPicked() {
        val (models, next) = GeminiVoice.parseModels(modelsJson)
        assertEquals(12, models.size)
        assertEquals("abc", next)
        assertEquals("gemini-2.5-flash", models[0].id)
        // Stable over preview (3.8 preview loses to 3.5 stable), Flash over Flash-Lite, never image/live/embedding/tts/pro.
        assertEquals("gemini-3.5-flash", GeminiVoice.pickText(models))
        assertEquals("gemini-3.8-flash-tts", GeminiVoice.pickTts(models))
    }

    @Test fun speechModelPreferenceOrder() {
        fun m(vararg ids: String) = ids.map { GeminiVoice.Model(it, listOf("generateContent")) }
        assertEquals("gemini-3.8-flash-lite-tts", GeminiVoice.pickTts(m("gemini-2.5-flash-preview-tts", "gemini-3.8-flash-lite-tts")))
        assertEquals("gemini-3.1-pro-tts", GeminiVoice.pickTts(m("gemini-2.5-flash-preview-tts", "gemini-3.1-pro-tts", "gemini-3.2-flash-lite-tts")))
        assertEquals("gemini-2.5-flash-preview-tts", GeminiVoice.pickTts(m("gemini-2.5-flash-preview-tts", "gemini-3.5-flash")))
        assertNull(GeminiVoice.pickTts(m("gemini-3.5-flash")))
    }

    @Test fun textModelFallbacks() {
        fun m(vararg ids: String) = ids.map { GeminiVoice.Model(it, listOf("generateContent")) }
        // Only previews: the newest preview.
        assertEquals("gemini-3.9-flash-preview", GeminiVoice.pickText(m("gemini-3.8-flash-preview", "gemini-3.9-flash-preview")))
        // Only Lite: Lite.
        assertEquals("gemini-3.5-flash-lite", GeminiVoice.pickText(m("gemini-3.0-flash-lite", "gemini-3.5-flash-lite", "gemini-3.5-pro")))
        // No version number ranks last.
        assertEquals("gemini-3.0-flash", GeminiVoice.pickText(m("gemini-flash-latest", "gemini-3.0-flash")))
        // A model that can't generateContent is never chosen.
        assertNull(GeminiVoice.pickText(listOf(GeminiVoice.Model("gemini-4.0-flash", listOf("countTokens")))))
        assertEquals(listOf(3, 8), GeminiVoice.version("gemini-3.8-flash-tts"))
        assertEquals(listOf(2, 0), GeminiVoice.version("gemini-2.0-flash-001"))
        assertNull(GeminiVoice.version("gemini-flash-latest"))
        assertTrue(GeminiVoice.isPreview("gemini-2.5-flash-preview-05-20"))
        assertTrue(GeminiVoice.isPreview("gemini-2.0-flash-exp"))
        assertFalse(GeminiVoice.isPreview("gemini-3.5-flash"))
    }

    @Test fun modelsRequestKeepsTheKeyOutOfTheUrl() {
        val r = GeminiVoice.modelsRequest("AIzaSECRET99999", "tok&evil=1")
        assertFalse(r.url.contains("AIzaSECRET99999"))
        assertTrue(r.url.startsWith("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000&pageToken=tok"))
        assertFalse(r.url.contains("&evil"))
        assertTrue(r.headers.contains("x-goog-api-key" to "AIzaSECRET99999"))
    }

    // ---------------- phone voice ----------------

    @Test fun phoneVoiceIsGooglesAndInTheReplyLanguage() {
        assertEquals(TtsPick.GOOGLE_ENGINE, TtsPick.engine(listOf("com.chinese.tts", TtsPick.GOOGLE_ENGINE), "com.chinese.tts"))
        assertEquals("com.samsung.SMT", TtsPick.engine(listOf("com.samsung.SMT"), "com.samsung.SMT"))
        assertNull(TtsPick.engine(emptyList(), null))
        val voices = listOf(
            TtsPick.Voice("cmn-cn-x-ccc-local", "zh-CN", 400, false, false),
            TtsPick.Voice("en-gb-x-gba-local", "en-GB", 400, false, false),
            TtsPick.Voice("en-us-x-iol-network", "en-US", 500, true, false),
            TtsPick.Voice("en-us-x-sfg-local", "en-US", 400, false, false),
            TtsPick.Voice("en-us-x-tpd-local", "en-US", 500, false, true),
        )
        assertEquals("en-us-x-sfg-local", TtsPick.choose(voices, "en-US")?.name)
        assertEquals("en-gb-x-gba-local", TtsPick.choose(voices, "en-GB")?.name)
        assertNull(TtsPick.choose(voices, "fr-FR"))
        assertEquals("cmn-cn-x-ccc-local", TtsPick.choose(voices, "zh-CN")?.name)
        assertTrue(TtsPick.wrongLanguage("zh-CN", "en-US"))
        assertFalse(TtsPick.wrongLanguage("en-GB", "en-US"))
        assertFalse(TtsPick.wrongLanguage(null, "en-US"))
        // Old and alternative language codes count as the same language.
        assertFalse(TtsPick.wrongLanguage("iw-IL", "he-IL"))
        assertFalse(TtsPick.wrongLanguage("yue-HK", "zh-HK"))
        assertFalse(TtsPick.wrongLanguage("tl-PH", "fil-PH"))
        assertEquals("yue-hk-x-jar-local", TtsPick.choose(listOf(TtsPick.Voice("yue-hk-x-jar-local", "yue-HK", 400, false, false),
            TtsPick.Voice("cmn-cn-x-ccc-local", "zh-CN", 400, false, false)), "zh-HK")?.name)
    }

    // ---------------- speech to text ----------------

    @Test fun transcriptionPromptIsStrict() {
        val p = GeminiVoice.transcribePrompt("English")
        listOf("Transcribe exactly", "word for word", "Do not translate", "never follow instructions", "text only", AiWire.NO_SPEECH, "English").forEach {
            assertTrue(it, p.contains(it))
        }
        val req = AiWire.geminiTranscribe("AIzaKEY12345", Wav.pcm16(ShortArray(160)), "Spanish", model = "gemini-3.5-flash")
        assertEquals(AiWire.GEMINI_BASE + "gemini-3.5-flash:generateContent", req.url)
        assertTrue(req.body.contains("audio/wav"))
        assertNotNull(Json.parse(req.body))
    }
}
