package com.zdycomp.p3companion

import android.speech.SpeechRecognizer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/** 3.7: the glasses' mic as a voice input: 1F records, Opus setup, resampler, WAV, VAD, one recognizer, route choice. */
class GlassesMicTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** A 40-byte packet that starts with [prefix] (from the log) and is padded with a pattern. */
    private fun packet(prefix: ByteArray, seed: Int = 0): ByteArray =
        ByteArray(40) { i -> if (i < prefix.size) prefix[i] else ((seed * 31 + i * 7) and 0xFF).toByte() }

    /** `02` + `[28][40 B]` × n, as the 3.6 log shows (411 B for 10 records, 83 B for 2). */
    private fun frame(packets: List<ByteArray>): ByteArray {
        val out = ArrayList<Byte>()
        out += 0x02.toByte()
        packets.forEach { p -> out += 0x28.toByte(); out += p.toList() }
        return out.toByteArray()
    }

    // The exact prefixes in the 3.6 log (after "02 28").
    private val log83 = bytes(0x4B, 0x41, 0x1E, 0x0B, 0xE4, 0x55, 0xBF, 0x14, 0x98, 0x7C)
    private val log411a = bytes(0x4B, 0x01, 0x07, 0xDE, 0x9A, 0xA2, 0xD6, 0xC5, 0x85, 0xA3, 0xA0, 0x1C, 0x7B, 0x07, 0x97, 0x35, 0xE9, 0x11, 0xA4, 0x25, 0xAC, 0xD9)
    private val log411b = bytes(0x48, 0x87, 0xF7, 0xDB, 0xA4, 0x76, 0xEF, 0xD3, 0xBC, 0xC3, 0x22, 0x99, 0x07, 0x0F, 0x05, 0x1F, 0x77, 0xB4, 0xAD, 0xC4, 0x18, 0x6B)

    /** The first, short frame: a code-3 packet with 30 bytes of padding (zeros), then a second packet. */
    private fun frame83(): ByteArray {
        val first = ByteArray(40).also { System.arraycopy(log83, 0, it, 0, log83.size) } // 10 bytes + 30 zeros of padding
        return frame(listOf(first, packet(bytes(0x48), 1)))
    }

    private fun frame411(prefix: ByteArray, seed: Int): ByteArray =
        frame(listOf(packet(prefix, seed)) + (1 until 10).map { packet(bytes(if (it % 2 == 0) 0x48 else 0x4B, 0x01), seed + it) })

    // ---------------- 1F records ----------------

    @Test fun logFramesSizesMatchTheRecordLayout() {
        assertEquals(83, frame83().size)
        assertEquals(411, frame411(log411a, 1).size)
        // The log's first bytes.
        assertArrayEquals(bytes(0x02, 0x28) + log83 + ByteArray(12), frame83().copyOf(24))
        assertArrayEquals(bytes(0x02, 0x28) + log411a, frame411(log411a, 1).copyOf(24))
        assertArrayEquals(bytes(0x02, 0x28) + log411b, frame411(log411b, 2).copyOf(24))
    }

    @Test fun splitterReadsTheLogFrames() {
        val a = MicRecords.split(frame83())
        assertEquals(2, a.packets.size)
        assertEquals(0, a.skipped)
        assertArrayEquals(log83, a.packets[0].copyOf(10))
        assertTrue(a.packets.all { it.size == 40 })
        for (f in listOf(frame411(log411a, 1), frame411(log411b, 2))) {
            val s = MicRecords.split(f)
            assertEquals(10, s.packets.size)
            assertEquals(0, s.skipped)
            assertArrayEquals(f.copyOfRange(2, 42), s.packets[0])
            assertArrayEquals(f.copyOfRange(411 - 40, 411), s.packets[9])
        }
        assertTrue(MicRecords.looksLike(frame83()))
        assertTrue(MicRecords.looksLike(frame411(log411b, 3)))
    }

    @Test fun splitterTakesATypeByteBeforeEveryRecordToo() {
        val p = (0 until 3).map { packet(bytes(0x48), it) }
        val f = p.flatMap { listOf(0x02.toByte(), 0x28.toByte()) + it.toList() }.toByteArray()
        val s = MicRecords.split(f)
        assertEquals(3, s.packets.size)
        assertEquals(0, s.skipped)
        p.forEachIndexed { i, b -> assertArrayEquals(b, s.packets[i]) }
    }

    @Test fun splitterResyncsAfterABadRecord() {
        val f = frame411(log411a, 4)
        f[1 + 41 * 3] = 0x11 // the fourth record's length byte is damaged
        val s = MicRecords.split(f)
        assertTrue(s.skipped > 0)
        assertTrue("got ${s.packets.size}", s.packets.size >= 6)
        assertArrayEquals(f.copyOfRange(411 - 40, 411), s.packets.last())
        // A code-3 packet must have 1..48 frames.
        assertFalse(MicRecords.tocOk(bytes(0x4B, 0x00, 0x00), 0, 3))
        assertTrue(MicRecords.tocOk(bytes(0x4B, 0x41, 0x1E), 0, 3))
        assertFalse(MicRecords.looksLike(ByteArray(83)))
    }

    @Test fun framerNoLongerLosesPacketsAtFrameStarts() {
        // 3.6's log: one 83 B frame, then 411 B frames. Old: the 02 at each frame start was "skipped" (112 B).
        val frames = listOf(frame83()) + (1..69).map { frame411(if (it % 2 == 0) log411a else log411b, it) }
        val now = OpusFramer()
        val got = frames.sumOf { now.feedRecords(it).size }
        assertEquals(2 + 69 * 10, got)
        assertEquals(0L, now.skipped)
        assertTrue(now.locked)
        val old = OpusFramer()
        frames.forEach { old.feed(it) }
        assertTrue("old skipped ${old.skipped}", old.skipped >= 70)
    }

    // ---------------- Opus decoder setup ----------------

    @Test fun opusCsdFor16kSilkDecodedAt48k() {
        val (head, delay, preroll) = OpusCsd.csd(channels = 1, inputRate = 16_000)
        assertEquals("OpusHead", String(head, 0, 8, Charsets.US_ASCII))
        assertEquals(1, head[8].toInt())
        assertEquals(1, head[9].toInt())
        assertEquals(312, (head[10].toInt() and 0xFF) or ((head[11].toInt() and 0xFF) shl 8))
        assertEquals(16_000L, Xk.le32(head, 12))
        assertEquals(0, head[16].toInt()); assertEquals(0, head[17].toInt()); assertEquals(0, head[18].toInt())
        // 312 samples at 48 kHz = 6.5 ms; the seek pre-roll is 80 ms (RFC 7845).
        assertEquals(6_500_000L, OpusCsd.codecDelayNs())
        assertEquals(6_500_000L, ByteBuffer.wrap(delay).order(ByteOrder.LITTLE_ENDIAN).long)
        assertEquals(80_000_000L, ByteBuffer.wrap(preroll).order(ByteOrder.LITTLE_ENDIAN).long)
    }

    // ---------------- resampler ----------------

    private fun tone48(hz: Double, n: Int, amp: Int = 8_000) = ShortArray(n) { (amp * sin(2 * PI * hz * it / 48_000.0)).toInt().toShort() }

    private fun run(r: Resampler, src: ShortArray, chunk: Int = 960): ShortArray {
        val out = ArrayList<Short>()
        var p = 0
        while (p < src.size) { val n = minOf(chunk, src.size - p); out += r.process(src.copyOfRange(p, p + n)).toList(); p += n }
        return out.toShortArray()
    }

    /** Frequency from zero crossings, skipping the filter's start. */
    private fun freq(s: ShortArray, rate: Int): Double {
        val from = 200
        var crossings = 0
        for (i in from + 1 until s.size) if ((s[i - 1] < 0) != (s[i] < 0)) crossings++
        return crossings / 2.0 / ((s.size - from - 1).toDouble() / rate)
    }

    @Test fun resamplerKeepsTheToneAndItsFrequency() {
        for (hz in listOf(440.0, 1_000.0, 3_000.0)) {
            val out = run(Resampler(48_000, 1), tone48(hz, 48_000))
            assertEquals("tone $hz", hz, freq(out, 16_000), hz * 0.01)
            val inDb = Level.of(tone48(hz, 48_000))
            assertEquals("level $hz", inDb, Level.of(out.copyOfRange(100, out.size)), 0.5)
        }
    }

    @Test fun resamplerFiltersWhatWouldFoldBack() {
        // 11 kHz can't exist at 16 kHz; decimation alone would fold it to 5 kHz. 3.6's 3-sample average let it through at -7.5 dB.
        val out = run(Resampler(48_000, 1), tone48(11_000.0, 48_000))
        val inDb = Level.of(tone48(11_000.0, 48_000))
        val outDb = Level.of(out.copyOfRange(100, out.size))
        assertTrue("11 kHz came out at ${outDb - inDb} dB", outDb - inDb < -50)
    }

    @Test fun resamplerIsTheSameInPiecesOrWhole() {
        val src = ShortArray(9_600) { Random(it).nextInt(-5_000, 5_000).toShort() }
        val whole = Resampler(48_000, 1).process(src)
        val pieces = run(Resampler(48_000, 1), src, chunk = 137)
        assertArrayEquals(whole, pieces)
        // 44.1 kHz too (not a whole ratio): about the right number of samples.
        val r44 = run(Resampler(44_100, 1), ShortArray(44_100))
        assertTrue("got ${r44.size}", abs(r44.size - 16_000) <= 30)
    }

    // ---------------- WAV ----------------

    @Test fun wavWriter() {
        val w = Wav.pcm16(shortArrayOf(1, -2, 0x1234), 16_000)
        assertEquals(44 + 6, w.size)
        val b = ByteBuffer.wrap(w).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(w, 0, 4, Charsets.US_ASCII))
        assertEquals(36 + 6, b.getInt(4))
        assertEquals("WAVE", String(w, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(w, 12, 4, Charsets.US_ASCII))
        assertEquals(16, b.getInt(16))
        assertEquals(1, b.getShort(20).toInt()) // PCM
        assertEquals(1, b.getShort(22).toInt()) // mono
        assertEquals(16_000, b.getInt(24))
        assertEquals(32_000, b.getInt(28)) // byte rate
        assertEquals(2, b.getShort(32).toInt()) // block align
        assertEquals(16, b.getShort(34).toInt())
        assertEquals("data", String(w, 36, 4, Charsets.US_ASCII))
        assertEquals(6, b.getInt(40))
        assertEquals(1, b.getShort(44).toInt())
        assertEquals(-2, b.getShort(46).toInt())
        assertEquals(0x1234, b.getShort(48).toInt())
    }

    @Test fun clipRingKeepsTheLastSeconds() {
        val r = ClipRing(5)
        r.add(shortArrayOf(1, 2, 3))
        assertArrayEquals(shortArrayOf(1, 2, 3), r.snapshot())
        r.add(shortArrayOf(4, 5, 6, 7))
        assertArrayEquals(shortArrayOf(3, 4, 5, 6, 7), r.snapshot())
        r.clear()
        assertEquals(0, r.samples)
    }

    // ---------------- VAD for the Gemini clip ----------------

    private fun tone(ms: Int, peak: Int) = ShortArray(16 * ms) { (peak * sin(2 * PI * 300 * it / 16_000.0)).toInt().toShort() }
    private fun noise(ms: Int, peak: Int, rnd: Random) = ShortArray(16 * ms) { rnd.nextInt(-peak, peak + 1).toShort() }
    private fun feed(v: Vad, s: ShortArray) { s.toList().chunked(320).forEach { v.feed(it.toShortArray()) } }

    @Test fun clipVadEndsAfter800msOfQuiet() {
        val v = Vad.clip()
        val rnd = Random(7)
        feed(v, noise(300, 30, rnd))
        feed(v, tone(1_200, 6_000))
        assertEquals(Vad.State.SPEECH, v.state)
        feed(v, noise(700, 30, rnd))
        assertEquals(Vad.State.SPEECH, v.state)
        feed(v, noise(120, 30, rnd))
        assertEquals(Vad.State.ENDED_AFTER_SPEECH, v.state)
    }

    @Test fun clipVadStopsAt15Seconds() {
        // Talking on and on: syllables with short pauses, never 800 ms of quiet.
        val v = Vad.clip()
        val rnd = Random(8)
        repeat(29) { feed(v, tone(300, 6_000)); feed(v, noise(200, 30, rnd)) } // 14.5 s
        assertEquals(Vad.State.SPEECH, v.state)
        feed(v, tone(300, 6_000)); feed(v, noise(300, 30, rnd))
        assertEquals(Vad.State.ENDED_MAX, v.state)
    }

    // ---------------- one recognizer at a time ----------------

    @Test fun recognizerGateAllowsOneAtATimeWithACooldown() {
        val g = RecognizerGate(300)
        assertEquals(0L, g.waitBeforeCreate(0))
        assertTrue(g.created(0))
        assertEquals(-1L, g.waitBeforeCreate(10))
        assertFalse(g.created(10)) // a second one while the first exists
        assertTrue(g.startListening())
        assertFalse(g.startListening()) // never two listens on it
        g.stoppedListening()
        assertTrue(g.startListening())
        g.released(1_000)
        assertFalse(g.exists)
        assertFalse(g.startListening())
        assertEquals(200L, g.waitBeforeCreate(1_100))
        assertFalse(g.created(1_100)) // too soon after the last one was destroyed
        assertEquals(0L, g.waitBeforeCreate(1_300))
        assertTrue(g.created(1_300))
        g.released(1_400)
        g.released(1_500) // releasing twice keeps the first time
        assertEquals(200L, g.waitBeforeCreate(1_500))
    }

    @Test fun tooManyRequestsMovesToTheNextService() {
        assertEquals(VoiceChoice.OnError.NEXT_SERVICE,
            VoiceChoice.onError(SpeechRecognizer.ERROR_TOO_MANY_REQUESTS, true, false, false, false, false, true))
    }

    // ---------------- the pipe ----------------

    /** Simulates the pump: bursts of [burst] samples every [everyMs], 20 ms ticks. */
    @Test fun pipeIsPacedToRealTime() {
        val p = PipePacer(16_000)
        var written = 0L
        var silence = 0L
        var avail = 0
        var maxAhead = 0L
        var silenceWhileTalking = 0L
        var t = 0L
        while (t <= 3_000) {
            if (t % 200 == 0L && t < 2_000) avail += 3_200 // the glasses' 200 ms bursts
            val plan = p.plan(t, written, avail, inputEnded = false)
            avail -= plan.real
            written += plan.real + plan.silence
            silence += plan.silence
            if (t < 2_000) silenceWhileTalking += plan.silence
            if (p.started) maxAhead = maxOf(maxAhead, written - (t - 0) * 16)
            t += 20
        }
        assertEquals(0L, silenceWhileTalking) // 3.6 slipped 20 ms of silence in between the bursts
        assertTrue(silence > 0) // after the bursts stop, the stall is filled
        assertTrue("ran ${maxAhead / 16} ms ahead", maxAhead <= 16 * 100 + 1)
        // All the speech went in, none of it replaced by silence while the bursts kept coming.
        assertEquals(0, avail)
        assertTrue(written >= 32_000)
    }

    @Test fun pipeWaitsForThePrebufferAndFillsOnlyAStall() {
        val p = PipePacer(16_000)
        assertEquals(PipePacer.Plan(0, 0), p.plan(0, 0, 1_000, false)) // under 200 ms: wait
        assertFalse(p.started)
        val first = p.plan(20, 0, 3_200, false)
        assertTrue(p.started)
        assertEquals(1_600, first.real) // real time plus 100 ms ahead
        assertEquals(0, first.silence)
        // Nothing more arrives: no silence while under 300 ms behind...
        assertEquals(0, p.plan(20 + 300, 1_600, 0, false).silence)
        // ...then the stall is filled.
        assertTrue(p.plan(20 + 600, 1_600, 0, false).silence > 0)
        // A recorded clip that ended: it goes in at once (it's short), then silence keeps the pace until the end of speech.
        val q = PipePacer(16_000)
        assertEquals(PipePacer.Plan(800, 800), q.plan(0, 0, 800, inputEnded = true))
        assertTrue(q.started)
        assertEquals(PipePacer.Plan(0, 1_600), q.plan(100, 1_600, 0, inputEnded = true))
    }

    // ---------------- route choice ----------------

    private val spp = MicRoute.GLASSES_SPP
    private val sco = MicRoute.GLASSES_SCO
    private val phone = MicRoute.PHONE

    @Test fun sppModeChoosesPipeGeminiOrNothing() {

        assertEquals(VoiceChoice.SppMode.PIPE, VoiceChoice.sppMode(VoiceChoice.PipeState.UNKNOWN, 34, geminiKey = false))
        assertEquals(VoiceChoice.SppMode.PIPE, VoiceChoice.sppMode(VoiceChoice.PipeState.OK, 33, geminiKey = true))
        assertEquals(VoiceChoice.SppMode.GEMINI, VoiceChoice.sppMode(VoiceChoice.PipeState.UNUSABLE, 34, geminiKey = true))
        assertEquals(VoiceChoice.SppMode.GEMINI, VoiceChoice.sppMode(VoiceChoice.PipeState.UNKNOWN, 31, geminiKey = true))
        // Only a Claude key: Claude doesn't take audio.
        assertEquals(VoiceChoice.SppMode.NONE, VoiceChoice.sppMode(VoiceChoice.PipeState.UNUSABLE, 34, geminiKey = false))
        assertEquals(VoiceChoice.SppMode.NONE, VoiceChoice.sppMode(VoiceChoice.PipeState.UNKNOWN, 30, geminiKey = false))
    }

    @Test fun pipeSelfTestVerdict() {

        val nm = SpeechRecognizer.ERROR_NO_MATCH
        assertEquals(VoiceChoice.PipeState.OK, VoiceChoice.pipeVerdict(true, 0, ownMicWorked = true, spoke = true))
        assertEquals(VoiceChoice.PipeState.UNUSABLE, VoiceChoice.pipeVerdict(false, nm, ownMicWorked = true, spoke = true))
        assertEquals(VoiceChoice.PipeState.UNUSABLE, VoiceChoice.pipeVerdict(false, SpeechRecognizer.ERROR_CLIENT, true, true))
        // The recognizer's own mic didn't work, or nobody spoke: it proves nothing about the pipe.
        assertEquals(VoiceChoice.PipeState.UNKNOWN, VoiceChoice.pipeVerdict(false, nm, ownMicWorked = false, spoke = true))
        assertEquals(VoiceChoice.PipeState.UNKNOWN, VoiceChoice.pipeVerdict(false, nm, ownMicWorked = true, spoke = false))
        assertEquals(VoiceChoice.PipeState.UNKNOWN, VoiceChoice.pipeVerdict(false, SpeechRecognizer.ERROR_NETWORK, true, true))
    }

    @Test fun defaultRouteIsTheGlassesWhenAnyWorks() {
        val ok = true to "heard"
        val bad = false to "no"
        assertEquals(sco, VoiceChoice.defaultRoute(mapOf(phone to ok, sco to ok, spp to ok)))
        assertEquals(spp, VoiceChoice.defaultRoute(mapOf(phone to ok, sco to bad, spp to ok)))
        assertEquals(phone, VoiceChoice.defaultRoute(mapOf(phone to ok, sco to bad, spp to bad)))
        assertNull(VoiceChoice.defaultRoute(mapOf(phone to bad)))
        val s = VoiceChoice.summary(linkedMapOf(phone to ok, sco to bad, spp to (true to "heard \"hello glasses\", Gemini")))
        assertTrue(s, s.endsWith("Voice questions will use the glasses mic (data link)."))
        // And the next question starts there.
        assertEquals(listOf(spp, phone), VoiceChoice.routeOrder(mapOf(phone to 1, sco to VoiceChoice.SKIP_AT, spp to 1), true, true, true))
    }

    private val tts = VoiceChoice.Rec("com.google.android.tts/x.GoogleTTSRecognitionService", "com.google.android.tts")
    private val gsa = VoiceChoice.Rec("com.google.android.googlequicksearchbox/x.GoogleRecognitionService", VoiceChoice.GOOGLE_APP)

    @Test fun callAudioAndPipedAudioAskGooglesNetworkRecognizerFirst() {
        // The phone mic last worked with "Speech Recognition and Synthesis": it stays first for the phone mic...
        assertEquals(tts.key, VoiceChoice.recognizerOrder(listOf(tts, gsa), true, true, tts.key, emptySet(), emptySet(), false).first())
        // ...but call audio goes to Google's network recognizer first, then the rest, the on-phone one included.
        val sco = VoiceChoice.recognizerOrder(listOf(tts, gsa), true, true, tts.key, emptySet(), emptySet(), piped = false, networkFirst = true)
        assertEquals(listOf(gsa.key, tts.key, VoiceChoice.ON_DEVICE, VoiceChoice.DEFAULT), sco)
        // Piped audio: Google's network recognizer first, never the on-phone one.
        val piped = VoiceChoice.recognizerOrder(listOf(tts, gsa), true, true, tts.key, setOf(gsa.key), emptySet(), piped = true)
        assertEquals(gsa.key, piped.first())
        assertFalse(VoiceChoice.ON_DEVICE in piped)
    }

    // ---------------- Gemini transcription ----------------

    @Test fun geminiTranscribeRequest() {
        val wav = Wav.pcm16(ShortArray(160), 16_000)
        val req = AiWire.geminiTranscribe("AIzaTESTKEY", wav, "English")
        assertEquals(AiWire.GEMINI_BASE + "gemini-2.5-flash:generateContent", req.url)
        assertTrue(req.headers.contains("x-goog-api-key" to "AIzaTESTKEY"))
        assertFalse(req.body.contains("AIzaTESTKEY"))
        val root = Json.parse(req.body) as Map<*, *>
        val parts = (((root["contents"] as List<*>)[0] as Map<*, *>)["parts"]) as List<*>
        val inline = (parts[0] as Map<*, *>)["inline_data"] as Map<*, *>
        assertEquals("audio/wav", inline["mime_type"])
        assertArrayEquals(wav, Base64.getDecoder().decode(inline["data"] as String))
        val text = (parts[1] as Map<*, *>)["text"] as String
        assertTrue(text, text.contains("Transcribe exactly") && text.contains("text only") && text.contains("English"))
    }

    @Test fun geminiTranscriptParsing() {
        fun body(t: String) = """{"candidates":[{"content":{"parts":[{"text":${Json.write(t)}}]},"finishReason":"STOP"}]}"""
        assertEquals("hello glasses", AiWire.parseTranscript(body(" hello glasses\n")))
        assertEquals("hello glasses", AiWire.parseTranscript(body("\"hello glasses\"")))
        assertNull(AiWire.parseTranscript(body("(no speech)")))
        assertNull(AiWire.parseTranscript(body("No speech.")))
        assertNull(AiWire.parseTranscript("""{"candidates":[{"content":{"parts":[]},"finishReason":"STOP"}]}"""))
        var threw = false
        try { AiWire.parseTranscript("""{"promptFeedback":{"blockReason":"SAFETY"}}""") } catch (_: AiException) { threw = true }
        assertTrue(threw)
    }

    @Test fun clipProviderServesOnlyPlainWavNames() {
        assertTrue(ClipProvider.nameOk(VoiceClips.SPP))
        assertTrue(ClipProvider.nameOk(VoiceClips.SCO))
        assertFalse(ClipProvider.nameOk("../shared_prefs.wav"))
        assertFalse(ClipProvider.nameOk("a/b.wav"))
        assertFalse(ClipProvider.nameOk("prefs.xml"))
        assertFalse(ClipProvider.nameOk(null))
    }
}
