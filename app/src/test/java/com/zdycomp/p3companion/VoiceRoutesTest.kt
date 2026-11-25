package com.zdycomp.p3companion

import android.speech.SpeechRecognizer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/** 3.5: mic route choice, speech service choice, recognizer errors, our end-of-speech detector, and the Opus plumbing. */
class VoiceRoutesTest {

    private val spp = MicRoute.GLASSES_SPP
    private val sco = MicRoute.GLASSES_SCO
    private val phone = MicRoute.PHONE

    // ---------------- mic routes ----------------

    @Test fun routeOrderWithoutATest() {
        // Nothing known yet: the glasses' call audio first, then the phone. The data-link stream waits for proof.
        assertEquals(listOf(sco, phone), VoiceChoice.routeOrder(emptyMap(), headset = true, linked = true, useGlasses = true))
        assertEquals(listOf(phone), VoiceChoice.routeOrder(emptyMap(), headset = false, linked = true, useGlasses = true))
        assertEquals(listOf(phone), VoiceChoice.routeOrder(emptyMap(), headset = true, linked = true, useGlasses = false))
    }

    @Test fun routeOrderFollowsTheTest() {
        val tested = mapOf(spp to 1, sco to VoiceChoice.SKIP_AT, phone to 1)
        assertEquals(listOf(spp, phone), VoiceChoice.routeOrder(tested, headset = true, linked = true, useGlasses = true))
        // The data-link stream needs the glasses' data link.
        assertEquals(listOf(phone), VoiceChoice.routeOrder(tested, headset = true, linked = false, useGlasses = true))
        // 3.7: both proven: call audio first (it opens in under a second, the data link takes about 4 s).
        assertEquals(listOf(sco, spp, phone), VoiceChoice.routeOrder(mapOf(spp to 1, sco to 1), true, true, true))
        // A proven call-audio mic before an unproven stream; one failure still tries it.
        assertEquals(listOf(sco, phone), VoiceChoice.routeOrder(mapOf(sco to 1, spp to 0), true, true, true))
        assertEquals(listOf(sco, phone), VoiceChoice.routeOrder(mapOf(sco to -1), true, true, true))
        // The phone's mic is always the last resort, even when the test found it failing.
        assertEquals(listOf(phone), VoiceChoice.routeOrder(mapOf(phone to VoiceChoice.SKIP_AT, sco to VoiceChoice.SKIP_AT), true, true, true))
    }

    @Test fun scoresMoveWithEachQuestion() {
        assertEquals(1, VoiceChoice.scoreAfter(0, worked = true))
        assertEquals(-1, VoiceChoice.scoreAfter(0, worked = false))
        assertEquals(-2, VoiceChoice.scoreAfter(-1, worked = false))
        assertEquals(-2, VoiceChoice.scoreAfter(-2, worked = false))
        assertEquals(-1, VoiceChoice.scoreAfter(1, worked = false)) // a proven mic that fails once is still tried
        assertEquals(1, VoiceChoice.scoreAfter(-2, worked = true))
    }

    @Test fun scoresRoundTrip() {
        val m = mapOf(spp to 1, sco to -2, phone to 0)
        assertEquals(m, VoiceChoice.parseScores(VoiceChoice.formatScores(m)))
        assertEquals(mapOf(sco to -2), VoiceChoice.parseScores("GLASSES_SCO:-9,NOPE:1,PHONE:x"))
        assertTrue(VoiceChoice.parseScores(null).isEmpty())
        val p = Prefs(FakeSharedPreferences())
        p.micRouteScores = m
        assertEquals(m, p.micRouteScores)
        p.recognizerNoPipe = setOf("a/b", "on-device")
        assertEquals(setOf("a/b", "on-device"), p.recognizerNoPipe)
    }

    @Test fun testSummaryNamesWhatWorks() {
        val s = VoiceChoice.summary(linkedMapOf(phone to (true to "heard \"hello glasses\""), sco to (false to "silent (RMS -83 dBFS)")))
        assertTrue(s, s.startsWith("Works: phone mic"))
        assertTrue(s, s.contains("Not working: glasses mic (call audio) (silent (RMS -83 dBFS))"))
        assertTrue(s, s.endsWith("Voice questions will use the phone mic."))
        assertEquals("Nothing was tested.", VoiceChoice.summary(emptyMap()))
    }

    // ---------------- speech services ----------------

    private val tts = VoiceChoice.Rec("com.google.android.tts/com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService", "com.google.android.tts")
    private val gsa = VoiceChoice.Rec("com.google.android.googlequicksearchbox/com.google.android.voicesearch.serviceapi.GoogleRecognitionService", VoiceChoice.GOOGLE_APP)
    private val other = VoiceChoice.Rec("com.samsung.android.bixby.agent/.Recognizer", "com.samsung.android.bixby.agent")

    @Test fun googlesNetworkRecognizerComesBeforeTheOnPhoneOne() {
        val order = VoiceChoice.recognizerOrder(listOf(tts, other, gsa), onDevice = true, defaultAvailable = true,
            remembered = null, noMatch = emptySet(), noPipe = emptySet(), piped = false)
        assertEquals(listOf(gsa.key, tts.key, VoiceChoice.ON_DEVICE, other.key, VoiceChoice.DEFAULT), order)
    }

    @Test fun aServiceThatSaidNoMatchGoesLastEvenIfRemembered() {
        // 3.4 on the moto g: "Speech Recognition and Synthesis" said no match twice and was still tried first.
        val order = VoiceChoice.recognizerOrder(listOf(tts, gsa), onDevice = true, defaultAvailable = false,
            remembered = tts.key, noMatch = setOf(tts.key, VoiceChoice.ON_DEVICE), noPipe = emptySet(), piped = false)
        assertEquals(listOf(gsa.key, tts.key, VoiceChoice.ON_DEVICE), order)
        val remembered = VoiceChoice.recognizerOrder(listOf(tts, gsa), false, false, tts.key, emptySet(), emptySet(), false)
        assertEquals(tts.key, remembered.first())
        // A remembered service that is gone isn't listed.
        assertEquals(listOf(gsa.key), VoiceChoice.recognizerOrder(listOf(gsa), false, false, "gone/x", emptySet(), emptySet(), false))
    }

    @Test fun ourOwnAudioSkipsServicesThatRefusedIt() {
        val piped = VoiceChoice.recognizerOrder(listOf(tts, gsa), onDevice = true, defaultAvailable = true,
            remembered = null, noMatch = emptySet(), noPipe = setOf(gsa.key, VoiceChoice.DEFAULT), piped = true)
        // 3.7: our own audio never goes to the on-phone recognizer.
        assertEquals(listOf(tts.key), piped)
        val unpiped = VoiceChoice.recognizerOrder(listOf(tts, gsa), true, true, null, emptySet(), setOf(gsa.key), piped = false)
        assertTrue(gsa.key in unpiped)
        assertEquals(listOf(VoiceChoice.DEFAULT), VoiceChoice.recognizerOrder(emptyList(), false, false, null, emptySet(), emptySet(), false))
    }

    @Test fun recognizerErrors() {
        val nm = SpeechRecognizer.ERROR_NO_MATCH
        fun e(code: Int, ready: Boolean = true, partials: Boolean = false, piped: Boolean = false, vad: Boolean = false, tried: Boolean = false, mic: Boolean = true) =
            VoiceChoice.onError(code, ready, partials, piped, vad, tried, mic)
        // Listened, understood nothing: the next service listens once more; a second time it's reported.
        assertEquals(VoiceChoice.OnError.NEXT_SERVICE_ONCE, e(nm))
        assertEquals(VoiceChoice.OnError.GIVE_UP, e(nm, tried = true))
        assertEquals(VoiceChoice.OnError.GIVE_UP, e(nm, partials = true))
        assertEquals(VoiceChoice.OnError.NEXT_SERVICE, e(nm, ready = false))
        assertEquals(VoiceChoice.OnError.NEXT_SERVICE_ONCE, e(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        // Our own audio: a client error, or no match while our detector heard speech, means it doesn't take the pipe.
        assertEquals(VoiceChoice.OnError.NO_PIPE, e(SpeechRecognizer.ERROR_CLIENT, piped = true))
        assertEquals(VoiceChoice.OnError.NO_PIPE, e(nm, piped = true, vad = true))
        assertEquals(VoiceChoice.OnError.GIVE_UP, e(nm, piped = true, vad = false)) // nobody spoke
        assertEquals(VoiceChoice.OnError.NEXT_SERVICE, e(SpeechRecognizer.ERROR_CLIENT, piped = false, ready = false))
        assertEquals(VoiceChoice.OnError.NEXT_SERVICE, e(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        assertEquals(VoiceChoice.OnError.GIVE_UP, e(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, mic = false))
        assertEquals(VoiceChoice.OnError.GIVE_UP, e(SpeechRecognizer.ERROR_AUDIO))
        assertEquals(VoiceChoice.OnError.NEXT_SERVICE, e(SpeechRecognizer.ERROR_SERVER_DISCONNECTED))
    }

    @Test fun scoMethodOrder() {
        assertEquals(listOf(AudioRoute.COMM, AudioRoute.LEGACY), AudioRoute.methodOrder(null, null, 35))
        assertEquals(listOf(AudioRoute.LEGACY, AudioRoute.COMM), AudioRoute.methodOrder(null, AudioRoute.LEGACY, 35))
        assertEquals(listOf(AudioRoute.LEGACY), AudioRoute.methodOrder(AudioRoute.LEGACY, null, 35))
        assertEquals(listOf(AudioRoute.LEGACY), AudioRoute.methodOrder(AudioRoute.COMM, null, 30))
    }

    // ---------------- end-of-speech detector ----------------

    private fun tone(ms: Int, peak: Int) = ShortArray(16 * ms) { (peak * sin(2 * PI * 300 * it / 16_000.0)).toInt().toShort() }
    private fun noise(ms: Int, peak: Int, rnd: Random) = ShortArray(16 * ms) { rnd.nextInt(-peak, peak + 1).toShort() }

    private fun feed(v: Vad, s: ShortArray) { s.toList().chunked(320).forEach { v.feed(it.toShortArray()) } }

    @Test fun vadEndsOneSecondAfterSpeech() {
        val v = Vad()
        val rnd = Random(1)
        feed(v, noise(400, 30, rnd))
        assertEquals(Vad.State.WAITING, v.state)
        feed(v, tone(800, 6_000))
        assertEquals(Vad.State.SPEECH, v.state)
        assertTrue(v.heardSpeech)
        feed(v, noise(900, 30, rnd))
        assertEquals(Vad.State.SPEECH, v.state)
        feed(v, noise(200, 30, rnd))
        assertEquals(Vad.State.ENDED_AFTER_SPEECH, v.state)
        assertTrue(v.ended)
    }

    @Test fun vadGivesUpWithoutSpeechAndIgnoresClicksAndSteadyNoise() {
        val v = Vad()
        val rnd = Random(2)
        feed(v, noise(1_000, 600, rnd)) // steady hiss at about -39 dBFS is the floor, not speech
        feed(v, tone(60, 8_000)) // a click is too short to be speech
        feed(v, noise(1_000, 600, rnd))
        assertEquals(Vad.State.WAITING, v.state)
        feed(v, noise(4_000, 600, rnd))
        assertEquals(Vad.State.ENDED_NO_SPEECH, v.state)
        assertFalse(v.heardSpeech)
        // Digital silence (a dead SCO mic) is never speech either.
        val dead = Vad()
        feed(dead, ShortArray(16 * 6_100))
        assertEquals(Vad.State.ENDED_NO_SPEECH, dead.state)
    }

    @Test fun vadHearsSpeechFromTheFirstMoment() {
        val v = Vad()
        feed(v, tone(500, 6_000)) // about -13 dBFS: talking already when the stream starts
        assertTrue(v.heardSpeech)
    }

    @Test fun vadStopsAtTheLimit() {
        val v = Vad(maxMs = 3_000)
        feed(v, noise(200, 30, Random(5)))
        feed(v, tone(3_000, 6_000))
        assertEquals(Vad.State.ENDED_MAX, v.state)
        assertTrue(v.heardSpeech)
    }

    // ---------------- Opus plumbing ----------------

    private fun packet(i: Int) = ByteArray(40) { (i * 7 + it).toByte() }
    private fun stream(n: Int, from: Int = 0): ByteArray = (from until from + n).flatMap { listOf(0x28.toByte()) + packet(it).toList() }.toByteArray()

    @Test fun framerFindsPacketsAcrossChunks() {
        val f = OpusFramer()
        val data = byteArrayOf(1, 2, 3) + stream(10)
        val out = ArrayList<ByteArray>()
        var p = 0
        val rnd = Random(3)
        while (p < data.size) { val n = minOf(data.size - p, rnd.nextInt(1, 60)); out += f.feed(data, p, n); p += n }
        // The last packet waits for the next one's first byte until the framer is locked; it is locked by then.
        assertEquals(10, out.size)
        out.forEachIndexed { i, b -> assertArrayEquals(packet(i), b) }
        assertTrue(f.locked)
        assertEquals(3L, f.skipped)
    }

    @Test fun framerResyncsAndIgnoresRandomData() {
        val f = OpusFramer()
        val rnd = Random(4)
        val junk = ByteArray(4_000) { (rnd.nextInt(256)).toByte().let { if (it == 0x28.toByte()) 0 else it } }
        assertTrue(f.feed(junk).isEmpty())
        assertFalse(f.locked)
        // A random 0x28 with no packet after it doesn't count.
        val lone = ByteArray(200).also { it[10] = 0x28 }
        assertTrue(f.feed(lone).isEmpty())
        val good = stream(5)
        val broken = good.copyOf().also { it[41 * 2] = 0x11 } // the third packet's length byte is damaged
        val out = f.feed(broken + stream(5, 5))
        assertTrue(out.size >= 7)
        assertArrayEquals(packet(9), out.last())
    }

    @Test fun opusDecoderSetup() {
        val h = OpusCsd.head(channels = 1, inputRate = 16_000, preSkip = 312)
        assertEquals(19, h.size)
        assertEquals("OpusHead", String(h, 0, 8, Charsets.US_ASCII))
        assertEquals(1, h[8].toInt())
        assertEquals(1, h[9].toInt())
        assertEquals(312, (h[10].toInt() and 0xFF) or ((h[11].toInt() and 0xFF) shl 8))
        assertEquals(16_000L, Xk.le32(h, 12))
        assertEquals(0, h[18].toInt())
        assertArrayEquals(byteArrayOf(0, 0xB4.toByte(), 0xC4.toByte(), 4, 0, 0, 0, 0), OpusCsd.nanos(OpusCsd.SEEK_PREROLL_NS))
    }

    @Test fun resamplerTo16kMono() {
        // One second of 48 kHz stereo (as Android's Opus decoder gives it) becomes one second of 16 kHz mono.
        val stereo48 = ShortArray(48_000 * 2) { i -> (8_000 * sin(2 * PI * 440 * (i / 2) / 48_000.0)).toInt().toShort() }
        val r = Resampler(48_000, 2)
        val out = ArrayList<Short>()
        var p = 0
        while (p < stereo48.size) { val n = minOf(1_920, stereo48.size - p); out += r.process(stereo48.copyOfRange(p, p + n)).toList(); p += n }
        // 3.7: the low-pass filter holds back half its length (48 input samples = 16 output samples) until more comes.
        assertTrue("got ${out.size}", abs(out.size - 16_000) <= 20)
        val db = Level.of(out.toShortArray())
        assertEquals(Level.of(ShortArray(16_000) { (8_000 * sin(2 * PI * 440 * it / 16_000.0)).toInt().toShort() }), db, 0.5)
        // Already 16 kHz mono: unchanged.
        val same = ShortArray(320) { it.toShort() }
        assertArrayEquals(same, Resampler(16_000, 1).process(same))
        // 16 kHz stereo: channels averaged.
        assertArrayEquals(shortArrayOf(15, 25), Resampler(16_000, 2).process(shortArrayOf(10, 20, 20, 30)))
    }

    @Test fun packetsMakeTwentyMillisecondsEach() {
        // 50 packets a second, as the vendor SDK's 342 packets in 6.84 s.
        val f = OpusFramer()
        val got = f.feed(stream(51))
        assertEquals(51, got.size)
        assertEquals(1_000L, (got.size - 1) * OpusCsd.PACKET_US / 1_000)
    }

    // ---------------- live view ----------------

    @Test fun dropNotesAreRateLimited() {
        val r = RateLimit(3, 10_000)
        assertEquals(0, r.allow(0))
        assertEquals(0, r.allow(10))
        assertEquals(0, r.allow(20))
        assertNull(r.allow(30))
        assertNull(r.allow(5_000))
        assertEquals(2, r.allow(10_020))
        assertNull(r.allow(10_030))
        r.reset()
        assertEquals(0, r.allow(10_040))
    }

    @Test fun liveOpensAndClosesAreDebounced() {
        val d = LiveDebounce(1_500)
        // The live view test hands back / the screen is rebuilt: close then open within 1.5 s keeps the stream.
        assertEquals(1_500L, d.close(0))
        assertEquals(LiveDebounce.Open(keepRunning = true, delayMs = 0), d.open(400))
        assertFalse(d.closeDue(1_500)) // the cancelled close never goes out
        // A real close, then an open 300 ms later, waits until 1.5 s after the close.
        d.close(2_000)
        assertTrue(d.closeDue(3_500))
        assertEquals(LiveDebounce.Open(false, 1_200), d.open(3_800))
        assertEquals(LiveDebounce.Open(false, 0), d.open(5_000))
        d.closedNow(6_000)
        assertEquals(LiveDebounce.Open(false, 1_000), d.open(6_500))
    }

    @Test fun afterADroppedPictureTheDecoderWaitsForAKeyPictureAtMostTwoSeconds() {
        assertTrue(LiveResync.mayDecode(waitingForKey = false, entry = false, gapAgeMs = null))
        assertFalse(LiveResync.mayDecode(true, entry = false, gapAgeMs = 300))
        assertTrue(LiveResync.mayDecode(true, entry = true, gapAgeMs = 300))
        assertTrue(LiveResync.mayDecode(true, entry = false, gapAgeMs = 2_000))
        assertFalse(LiveResync.mayDecode(true, entry = false, gapAgeMs = null))
    }

    @Test fun noAutomaticPhotoWhileLiveViewIsWanted() {
        val off = XkGlasses.Preview.OFF
        assertTrue(XkGlasses.autoPhotoAllowed(off, keepPreview = false, closePending = false, micStream = false))
        assertFalse(XkGlasses.autoPhotoAllowed(XkGlasses.Preview.OPENING, false, false, false))
        assertFalse(XkGlasses.autoPhotoAllowed(XkGlasses.Preview.ON, false, false, false))
        assertFalse(XkGlasses.autoPhotoAllowed(XkGlasses.Preview.BUSY, false, false, false))
        assertFalse(XkGlasses.autoPhotoAllowed(off, keepPreview = true, closePending = false, micStream = false))
        assertFalse(XkGlasses.autoPhotoAllowed(off, keepPreview = false, closePending = true, micStream = false))
        assertFalse(XkGlasses.autoPhotoAllowed(off, keepPreview = false, closePending = false, micStream = true))
    }
}
