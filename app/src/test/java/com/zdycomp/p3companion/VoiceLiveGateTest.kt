package com.zdycomp.p3companion

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** 3.3: which mic a voice question uses, when the glasses' call audio may start, and the live view test's pure parts. */
class VoiceLiveGateTest {

    @Test fun micChoice() {
        assertEquals(MicCapture.Mic.PHONE, MicCapture.decide(scoUp = false, glassesRmsDb = -20.0))
        assertEquals(MicCapture.Mic.PHONE, MicCapture.decide(scoUp = true, glassesRmsDb = null))
        assertEquals(MicCapture.Mic.PHONE, MicCapture.decide(scoUp = true, glassesRmsDb = -120.0))
        assertEquals(MicCapture.Mic.PHONE, MicCapture.decide(scoUp = true, glassesRmsDb = -61.0))
        assertEquals(MicCapture.Mic.GLASSES, MicCapture.decide(scoUp = true, glassesRmsDb = -59.0))
        assertEquals(MicCapture.Mic.GLASSES, MicCapture.decide(scoUp = true, glassesRmsDb = -25.0))
    }

    @Test fun rmsLevels() {
        assertEquals(-120.0, MicCapture.rmsDb(ShortArray(6400)), 0.001)
        assertEquals(0.0, MicCapture.rmsDb(ShortArray(100) { if (it % 2 == 0) Short.MIN_VALUE else Short.MIN_VALUE }), 0.01)
        // A 1000-peak sine: RMS 707 → about -33.3 dBFS, well above silence.
        val sine = ShortArray(MicCapture.RATE * MicCapture.PROBE_MS / 1000) { (1000 * sin(2 * PI * 440 * it / MicCapture.RATE)).toInt().toShort() }
        val db = MicCapture.rmsDb(sine)
        assertEquals(-33.3, db, 0.3)
        assertEquals(MicCapture.Mic.GLASSES, MicCapture.decide(true, db))
        // Faint hiss (peak 8) is treated as a dead headset mic.
        val hiss = ShortArray(6400) { if (it % 2 == 0) 8 else -8 }
        assertEquals(MicCapture.Mic.PHONE, MicCapture.decide(true, MicCapture.rmsDb(hiss)))
    }

    @Test fun noMatchRetriesOnceWithThePhoneMic() {
        assertTrue(Assistant.shouldRetryWithPhone(SpeechRecognizer.ERROR_NO_MATCH, glassesOrFed = true, alreadyRetried = false))
        assertTrue(Assistant.shouldRetryWithPhone(SpeechRecognizer.ERROR_SPEECH_TIMEOUT, true, false))
        assertTrue(Assistant.shouldRetryWithPhone(SpeechRecognizer.ERROR_AUDIO, true, false))
        assertFalse(Assistant.shouldRetryWithPhone(SpeechRecognizer.ERROR_NO_MATCH, true, alreadyRetried = true))
        assertFalse(Assistant.shouldRetryWithPhone(SpeechRecognizer.ERROR_NO_MATCH, glassesOrFed = false, alreadyRetried = false))
        assertFalse(Assistant.shouldRetryWithPhone(SpeechRecognizer.ERROR_NETWORK, true, false))
    }

    @Test fun callAudioWaitsForASettledLink() {
        val s = XkGlasses.State.READY
        assertNotNull(XkGlasses.audioBlockerFor(XkGlasses.State.CONNECTING, false, false, 60_000))
        assertNotNull(XkGlasses.audioBlockerFor(s, probing = true, transferring = false, readyForMs = 60_000))
        assertNotNull(XkGlasses.audioBlockerFor(s, false, transferring = true, readyForMs = 60_000))
        assertNotNull(XkGlasses.audioBlockerFor(s, false, false, readyForMs = 2_999))
        assertNull(XkGlasses.audioBlockerFor(s, false, false, readyForMs = 3_000))
        assertNull(XkGlasses.audioBlockerFor(XkGlasses.State.IDLE, false, false, 0)) // no link: SCO can't hurt it
    }

    @Test fun tapToTalkDelay() {
        assertNull(XkGlasses.tapDelayFor(XkGlasses.State.READY, probing = true, readyForMs = 60_000))
        assertEquals(2_000L, XkGlasses.tapDelayFor(XkGlasses.State.READY, false, 1_000))
        assertEquals(0L, XkGlasses.tapDelayFor(XkGlasses.State.READY, false, 3_000))
        assertEquals(500L, XkGlasses.tapDelayFor(XkGlasses.State.CONNECTING, false, 0))
        assertEquals(0L, XkGlasses.tapDelayFor(XkGlasses.State.IDLE, false, 0))
    }

    @Test fun probeNoLongerStartsTheGlassesMic() {
        assertFalse(XkGlasses.PROBE_PATHS.any { it.startsWith("102A") || it.startsWith("102C") })
        assertEquals("5720/3", XkGlasses.PROBE_PATHS.first())
        assertEquals("5711/3 01", XkGlasses.pullName(XkGlasses.PULL_TRIES[1]))
        assertEquals("5750/1", XkGlasses.pullName(XkGlasses.PULL_TRIES[3]))
    }

    @Test fun wifiNamesThatLookLikeTheGlasses() {
        val t = WifiFinder.tokens("P3_D6BE", "E13C-5", "AA:BB:CC:DD:D6:BE")
        assertTrue("d6be" in t)
        assertTrue("e13c" in t)
        assertTrue(WifiFinder.matches("P3_D6BE", t))
        assertTrue(WifiFinder.matches("DIRECT-4F-E13C-5", t))
        assertTrue(WifiFinder.matches("Shenju_cam", t))
        assertFalse(WifiFinder.matches("HomeNetwork", t))
        assertFalse(WifiFinder.matches("Starbucks WiFi", t))
    }
}
