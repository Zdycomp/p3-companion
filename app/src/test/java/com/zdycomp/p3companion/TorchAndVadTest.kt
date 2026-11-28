package com.zdycomp.p3companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** 4.0: the flashlight's state machine, and the end-of-speech detector for Gemini speech-to-text. */
class TorchAndVadTest {

    // ---------------- flashlight ----------------

    @Test fun torchStaysOnUntilTurnedOff() {
        val t = TorchLogic()
        assertEquals(TorchLogic.State.OFF, t.state)
        assertEquals(true, t.target("true"))
        t.changed(true, 1_000)
        assertTrue(t.on)
        assertEquals(1_000, t.onSince)
        // Off by default: no auto-off, however long it's been.
        assertNull(t.deadline())
        assertFalse(t.due(10L * 3600 * 1000))
        assertEquals("Flashlight on.", t.spoken(true))
        assertEquals(false, t.target("toggle"))
        t.changed(false, 5_000)
        assertFalse(t.on)
        assertEquals(0, t.onSince)
        assertEquals("Flashlight off.", t.spoken(false))
    }

    @Test fun torchToggleAndAutoOffTimer() {
        val t = TorchLogic(autoOffMs = 5 * 60_000L)
        assertEquals(true, t.target(null))
        assertEquals(true, t.target("toggle"))
        t.changed(true, 10_000)
        assertEquals(10_000 + 5 * 60_000L, t.deadline())
        assertFalse(t.due(10_000 + 5 * 60_000L - 1))
        assertTrue(t.due(10_000 + 5 * 60_000L))
        assertEquals("Flashlight on. It turns off in 5 minutes.", t.spoken(true))
        // Turning on again while on keeps the first time (the timer isn't pushed back by a repeat).
        t.changed(true, 20_000)
        assertEquals(10_000L, t.onSince)
        assertEquals("1 minute", TorchLogic.minutes(60_000))
    }

    @Test fun torchUnavailableAndCameraInUse() {
        val t = TorchLogic()
        t.failed(TorchLogic.Fail.CAMERA_IN_USE)
        assertEquals(TorchLogic.State.BUSY, t.state)
        assertTrue(t.spoken(true).contains("another app is using the camera"))
        // It works again once the camera is free.
        t.changed(true, 1)
        assertEquals(TorchLogic.State.ON, t.state)
        assertNull(t.problem)
        t.unavailable()
        assertEquals(TorchLogic.State.BUSY, t.state)
        assertFalse(t.on)
        val none = TorchLogic()
        none.failed(TorchLogic.Fail.NO_FLASH)
        assertNull(none.target("true"))
        assertEquals("This phone has no flashlight.", none.spoken(true))
        none.unavailable()
        assertEquals(TorchLogic.State.NO_FLASH, none.state)
        // An error while on leaves it on.
        val on = TorchLogic().apply { changed(true, 1) }
        on.failed(TorchLogic.Fail.ERROR)
        assertTrue(on.on)
    }

    @Test fun noFlashlightPhraseEverTouchesTheGlassesCamera() {
        val phrases = listOf(
            "flashlight on", "flashlight off", "flashlight", "torch on", "turn on the flashlight", "turn off the torch", "lights off",
            "light on", "turn on the light", "flash on", "turn off the flash", "toggle the flashlight", "flashlight toggle", "enable the torch",
        )
        phrases.forEach { p ->
            val m = VoiceCommands.match(p)
            assertEquals(p, "util.torch", m?.id)
            assertTrue(p, VoiceCommands.rulesMatching(p).none { it.startsWith("glasses.") || it.startsWith("ai.") })
        }
        assertEquals("toggle", VoiceCommands.match("toggle the flashlight")?.slots?.get("onoff"))
        assertEquals("false", VoiceCommands.match("lights off")?.slots?.get("onoff"))
        assertEquals("true", VoiceCommands.match("turn on the flash")?.slots?.get("onoff"))
        // And the AI's function for it means the same command, never a photo.
        assertEquals(Match("util.torch", mapOf("onoff" to "true")), FnCalls.toMatch(FnCall("set_flashlight", mapOf("on" to true))))
        assertEquals("toggle", FnCalls.toMatch(FnCall("set_flashlight"))?.slots?.get("onoff"))
    }

    // ---------------- end of speech for Gemini ----------------

    private fun tone(ms: Int, peak: Int) = ShortArray(16 * ms) { (peak * sin(2 * PI * 300 * it / 16_000.0)).toInt().toShort() }
    private fun noise(ms: Int, peak: Int, rnd: Random) = ShortArray(16 * ms) { rnd.nextInt(-peak, peak + 1).toShort() }
    private fun feed(v: Vad, s: ShortArray) { s.toList().chunked(320).forEach { v.feed(it.toShortArray()) } }

    @Test fun geminiVadStartsOnSpeechAndEndsAfter900msOfQuiet() {
        val v = Vad.gemini()
        val rnd = Random(11)
        feed(v, noise(500, 30, rnd))
        assertEquals(Vad.State.WAITING, v.state)
        assertEquals(-1.0, v.speechStartMs, 0.0)
        feed(v, tone(1_000, 6_000))
        assertEquals(Vad.State.SPEECH, v.state)
        assertEquals(500.0, v.speechStartMs, 30.0)
        feed(v, noise(860, 30, rnd))
        assertEquals(Vad.State.SPEECH, v.state)
        feed(v, noise(60, 30, rnd))
        assertEquals(Vad.State.ENDED_AFTER_SPEECH, v.state)
        assertEquals(1_500.0, v.speechEndMs, 30.0)
    }

    @Test fun geminiVadStopsAt20Seconds() {
        val v = Vad.gemini()
        val rnd = Random(12)
        repeat(39) { feed(v, tone(300, 6_000)); feed(v, noise(200, 30, rnd)) } // 19.5 s, never 900 ms of quiet
        assertEquals(Vad.State.SPEECH, v.state)
        feed(v, tone(300, 6_000)); feed(v, noise(300, 30, rnd))
        assertEquals(Vad.State.ENDED_MAX, v.state)
    }

    @Test fun holdToTalkIgnoresPausesAndSilenceAloneGivesUp() {
        val h = Vad.hold()
        val rnd = Random(13)
        feed(h, tone(600, 6_000))
        feed(h, noise(3_000, 30, rnd)) // a long pause while still holding
        assertEquals(Vad.State.SPEECH, h.state)
        feed(h, tone(400, 6_000))
        assertEquals(Vad.State.SPEECH, h.state)
        val quiet = Vad.gemini()
        feed(quiet, noise(8_100, 30, Random(14)))
        assertEquals(Vad.State.ENDED_NO_SPEECH, quiet.state)
    }

    @Test fun clipsAreTrimmedAroundTheSpeech() {
        val clip = ShortArray(16_000 * 5) { it.toShort() }
        val t = Vad.trim(clip, 16_000, speechStartMs = 2_000.0, speechEndMs = 3_000.0)
        assertEquals((2_000 - 300) * 16, t[0].toInt() and 0xFFFF)
        assertEquals((3_000 + 400 - 1_700) * 16, t.size)
        // No speech start known: the whole clip. Near the edges it clamps.
        assertTrue(Vad.trim(clip, 16_000, -1.0, -1.0) === clip)
        assertEquals(clip.size, Vad.trim(clip, 16_000, 100.0, 4_900.0).size)
        assertEquals(0, Vad.trim(ShortArray(0), 16_000, 10.0, 20.0).size)
    }

    @Test fun dataLinkMicPrefersGeminiWhenThereIsAKey() {
        assertEquals(VoiceChoice.SppMode.GEMINI, VoiceChoice.sppMode(VoiceChoice.PipeState.OK, 35, geminiKey = true, preferGemini = true))
        assertEquals(VoiceChoice.SppMode.PIPE, VoiceChoice.sppMode(VoiceChoice.PipeState.OK, 35, geminiKey = true, preferGemini = false))
        assertEquals(VoiceChoice.SppMode.PIPE, VoiceChoice.sppMode(VoiceChoice.PipeState.UNKNOWN, 35, geminiKey = false, preferGemini = true))
        assertEquals(VoiceChoice.SppMode.NONE, VoiceChoice.sppMode(VoiceChoice.PipeState.UNUSABLE, 35, geminiKey = false, preferGemini = true))
    }
}
