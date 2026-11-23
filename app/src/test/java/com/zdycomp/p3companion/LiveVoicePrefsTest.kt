package com.zdycomp.p3companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.1 settings: live view test result, speech service, speak-through and voice commands. */
class LiveVoicePrefsTest {

    @Test fun defaults() {
        val p = Prefs(FakeSharedPreferences())
        assertNull(p.livePath)
        assertNull(p.liveProbeSummary)
        assertEquals(0L, p.liveProbeAt)
        assertFalse(p.keepSnapshots)
        assertNull(p.recognizerService)
        assertEquals(Prefs.SpeakThrough.FAST, p.speakThrough)
        assertTrue(p.voiceCommands)
    }

    @Test fun deviceFactsSurviveResetButNeverLeaveInABackup() {
        val p = Prefs(FakeSharedPreferences())
        p.livePath = XkGlasses.LIVE_NONE
        p.liveProbeSummary = "no video"
        p.liveProbeAt = 1234L
        p.recognizerService = "com.google.android.tts/x.Service"
        p.speakThrough = Prefs.SpeakThrough.CALL
        p.voiceCommands = false
        val json = SettingsBackup.export(p.snapshot(), "3.1.0")
        assertFalse(json.contains("live_path"))
        assertFalse(json.contains("live_probe"))
        assertFalse(json.contains("recognizer_service"))
        assertTrue(json.contains("speak_through"))
        assertTrue(json.contains("voice_commands"))
        p.resetToDefaults()
        assertEquals(XkGlasses.LIVE_NONE, p.livePath)
        assertEquals(1234L, p.liveProbeAt)
        assertEquals("com.google.android.tts/x.Service", p.recognizerService)
        assertEquals(Prefs.SpeakThrough.FAST, p.speakThrough)
        assertTrue(p.voiceCommands)
    }

    @Test fun probePathsCoverEveryOpenCommandInOrder() {
        // 3.3: 102A/102C left the list (102A started the glasses' own mic on the owner's E13C-5; 102C rejected the data).
        assertEquals(listOf("5720/3", "5720/2", "5710/3", "5710/2"), XkGlasses.PROBE_PATHS)
        assertEquals("5710", XkGlasses.probeNode("5710/2"))
        assertEquals(Xk.ACTION_WRITE, XkGlasses.probeAction("5710/2"))
        assertEquals(Xk.ACTION_EXECUTE, XkGlasses.probeAction("102A"))
        // The open and close frames the probe sends are ordinary control frames.
        val open = Xk.control(0x20, "5710", Xk.ACTION_EXECUTE, 0x50, byteArrayOf(1))
        assertEquals("5710", open.node)
        assertEquals(Xk.ACTION_EXECUTE, open.action)
    }
}
