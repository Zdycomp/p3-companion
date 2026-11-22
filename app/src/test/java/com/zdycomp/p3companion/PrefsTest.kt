package com.zdycomp.p3companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** REGRESSION-CHECKLIST 6.5: today's defaults stay, and the 2.1 settings migrate from the 2.0 ones. */
class PrefsTest {

    @Test fun twoPointOhDefaultsAreUnchanged() {
        val p = Prefs(FakeSharedPreferences())
        assertEquals(Prefs.Theme.DARK, p.theme)
        assertTrue(p.autoImport)
        assertFalse(p.deleteAfterImport)
        assertEquals(Prefs.Naming.DATE, p.naming)
        assertEquals("P3 Glasses", p.albumName)
        assertTrue(p.autoReconnect)
        assertTrue(p.saveButtonPhotos)
        assertFalse(p.showLog)
        assertTrue(p.tapToTalk)
        assertTrue(p.useGlassesAudio)
        assertTrue(p.speakPhotoAnswers)
        assertEquals(Xk.ACTION_EXECUTE, p.previewAction)
        assertEquals("English", p.replyLanguage)
        assertEquals(0, p.startTab)
    }

    @Test fun newOptionsDefaultToTodaysBehaviour() {
        val p = Prefs(FakeSharedPreferences())
        assertTrue("connect on launch follows auto-reconnect", p.connectOnLaunch)
        assertTrue("stays connected in the background like 2.0", p.keepConnectedInBackground)
        assertFalse(p.minimalNotification)
        assertEquals(0, p.selfTimer)
        assertFalse(p.openAfterCapture)
        assertFalse(p.shutterSound)
        assertTrue(p.liveKeepScreenOn)
        assertEquals(AiWire.HISTORY_LIMIT, p.historyLimit)
        assertTrue(p.speakChatAnswers)
        assertEquals(1f, p.ttsRate, 0f)
        assertEquals(1f, p.ttsPitch, 0f)
        assertFalse(p.appLock)
        assertFalse(p.secureWindow)
        assertFalse(p.hideKeys)
        assertEquals(1f, p.textScale, 0f)
        assertFalse(p.amoled)
        assertFalse(p.highContrast)
        assertEquals(Prefs.GallerySort.NEWEST, p.gallerySort)
        assertEquals(3, p.galleryColumns)
        assertEquals(AiWire.photoPresetTemplates(), p.photoPresets)
    }

    @Test fun connectOnLaunchMigratesFromAutoReconnect() {
        val off = Prefs(FakeSharedPreferences(mapOf("auto_reconnect" to false)))
        assertFalse(off.connectOnLaunch)
        off.connectOnLaunch = true
        assertTrue(off.connectOnLaunch)
        assertFalse("the drop setting keeps its own value", off.autoReconnect)
    }

    @Test fun outOfRangeValuesFallBack() {
        val p = Prefs(FakeSharedPreferences(mapOf(
            "self_timer" to 7, "low_battery" to 42, "history_limit" to 99, "gallery_cols" to 12,
            "text_scale" to 5f, "accent" to "NOPE", "accent_hue" to 999, "tts_rate" to 9f,
        )))
        assertEquals(0, p.selfTimer)
        assertEquals(20, p.lowBatteryAt)
        assertEquals(AiWire.HISTORY_LIMIT, p.historyLimit)
        assertEquals(5, p.galleryColumns)
        assertEquals(1.3f, p.textScale, 0f)
        assertEquals(Accent.DYNAMIC, p.accent)
        assertEquals(359, p.accentHue)
        assertEquals(2f, p.ttsRate, 0f)
    }

    @Test fun photoPresetsRoundTripAndAreCapped() {
        val p = Prefs(FakeSharedPreferences())
        val list = (1..12).map { "Q$it" to "Question $it about {language}?" }
        p.photoPresets = list
        assertEquals(Prefs.MAX_PRESETS, p.photoPresets.size)
        assertEquals("Q1" to "Question 1 about {language}?", p.photoPresets[0])
        p.photoPresets = listOf("  " to "empty label is dropped", "Ok" to "Fine")
        assertEquals(listOf("Ok" to "Fine"), p.photoPresets)
        p.resetPhotoPresets()
        assertEquals(AiWire.photoPresetTemplates(), p.photoPresets)
    }

    @Test fun presetTemplatesKeepTheOldQuestions() {
        assertEquals(4, AiWire.photoPresetTemplates().size)
        for (i in 0..3) {
            assertEquals(AiWire.photoPreset(i, "German"), AiWire.fillLanguage(AiWire.photoPresetTemplates()[i].second, "German"))
        }
        assertTrue(AiWire.photoPreset(3, "French").contains("into French. If it is already in French"))
        assertFalse(AiWire.photoPreset(3, "French").contains(AiWire.LANGUAGE_TOKEN))
    }

    @Test fun keysAreMaskedOrHidden() {
        val p = Prefs(FakeSharedPreferences())
        val key = "0123456789abcdef0123456789abcdef"
        assertEquals("0123••••••••cdef", p.maskForDisplay(key))
        p.hideKeys = true
        assertEquals("Saved", p.maskForDisplay(key))
        assertEquals("••••", KeyFinder.mask("1234567"))
        assertEquals("1234••••••••5678", KeyFinder.mask("12345678"))
    }

    @Test fun resetKeepsKeysGlassesAndHistory() {
        val sp = FakeSharedPreferences(mapOf(
            "user_key" to "0123456789abcdef0123456789abcdef", "ai_key_GEMINI" to "AIza-secret", "ble_addr" to "AA:BB:CC:DD:EE:FF",
            "drive_uri" to "content://x", "last_import" to "Imported 3 photos", "theme" to "LIGHT", "haptics" to false,
        ))
        val p = Prefs(sp)
        p.resetToDefaults()
        assertEquals("0123456789abcdef0123456789abcdef", p.userKey)
        assertEquals("AIza-secret", p.aiKey(AiProvider.GEMINI))
        assertEquals("AA:BB:CC:DD:EE:FF", p.glassesAddress)
        assertEquals("content://x", p.driveUri)
        assertEquals(Prefs.Theme.DARK, p.theme)
        assertTrue(p.haptics)
    }
}
