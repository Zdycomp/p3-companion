package com.zdycomp.p3companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** REGRESSION-CHECKLIST 6.6: the settings file never holds a key, and it round-trips. */
class SettingsBackupTest {

    private val secrets = mapOf(
        "user_key" to "0123456789abcdef0123456789abcdef",
        "ai_key_GEMINI" to "AIzaSyTESTKEY1234567890",
        "ai_key_CLAUDE" to "sk-ant-test-key",
        "drive_uri" to "content://com.android.externalstorage.documents/tree/1234",
        "drive_label" to "GLASSES",
        "ble_addr" to "AA:BB:CC:DD:EE:FF",
        "ble_name" to "xk one Pro_EEFF",
        "link_method" to "ch8-secure",
        "user_stopped" to true,
        "preview_action" to 2,
        "asked_android.permission.RECORD_AUDIO" to true,
        "last_import" to "Imported 3 photos",
        "last_seen_version" to 2,
    )

    private fun fullPrefs(): Prefs {
        val p = Prefs(FakeSharedPreferences(secrets))
        p.theme = Prefs.Theme.LIGHT
        p.accent = Accent.OCEAN
        p.accentHue = 123
        p.amoled = true
        p.highContrast = true
        p.textScale = 1.15f
        p.reduceMotion = Prefs.Motion.ON
        p.haptics = false
        p.albumName = "Trip"
        p.autoReconnect = false
        p.connectOnLaunch = true
        p.keepConnectedInBackground = false
        p.photoNote = Prefs.PhotoNote.TEXT
        p.lowBatteryAt = 15
        p.selfTimer = 5
        p.gallerySort = Prefs.GallerySort.LARGEST
        p.galleryColumns = 4
        p.ttsRate = 1.3f
        p.ttsPitch = 0.8f
        p.historyLimit = 24
        p.photoPresets = listOf("Mine" to "What is this {language}?")
        p.replyLanguage = "German"
        p.setAiModel(AiProvider.GEMINI, "gemini-2.5-pro")
        p.appLock = true
        p.lockAfterMin = 5
        return p
    }

    @Test fun exportNeverContainsKeysOrConnectionState() {
        val text = SettingsBackup.export(fullPrefs().snapshot(), "3.0.0")
        for ((k, v) in secrets) {
            assertFalse("exported $k", text.contains("\"$k\""))
            if (v is String) assertFalse("exported the value of $k", text.contains(v))
        }
        assertFalse(text.contains("ai_key"))
        assertFalse(text.contains("asked_"))
        assertTrue(text.contains("\"format\":\"p3c-settings\""))
        assertTrue(text.contains("\"ai_model_GEMINI\":\"gemini-2.5-pro\""))
    }

    @Test fun roundTripRestoresEveryWhitelistedValue() {
        val source = fullPrefs()
        val text = SettingsBackup.export(source.snapshot(), "3.0.0")
        val target = Prefs(FakeSharedPreferences())
        val r = SettingsBackup.read(text)
        assertEquals(0, r.ignored)
        target.restore(r.values)
        val a = source.snapshot().filterKeys { !SettingsBackup.isSecret(it) }
        val b = target.snapshot()
        assertEquals(a.keys, b.keys)
        for ((k, v) in a) assertEquals("key $k", v, b[k])
        // and nothing secret came along
        assertTrue(b.keys.none { SettingsBackup.isSecret(it) })
        assertEquals(1.3f, target.ttsRate, 0f)
        assertEquals(listOf("Mine" to "What is this {language}?"), target.photoPresets)
    }

    @Test fun rejectsOtherFilesAndNewerVersions() {
        expectBad("{\"format\":\"something\",\"version\":1,\"prefs\":{}}")
        expectBad("{\"format\":\"p3c-settings\",\"version\":2,\"prefs\":{}}")
        expectBad("not json at all")
        expectBad("[1,2,3]")
        expectBad("{\"format\":\"p3c-settings\",\"version\":1}")
    }

    private fun expectBad(text: String) {
        try {
            SettingsBackup.read(text)
            fail("accepted $text")
        } catch (e: SettingsBackup.BadFile) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test fun ignoresUnknownKeysWrongTypesAndSecrets() {
        val text = """{"format":"p3c-settings","version":1,"app":"9","prefs":{
            "haptics":"yes","self_timer":3,"unknown":1,"user_key":"0123456789abcdef0123456789abcdef",
            "ai_key_GEMINI":"x","text_scale":1.15,"theme":"LIGHT","gallery_cols":2.5}}"""
        val r = SettingsBackup.read(text)
        assertEquals(setOf("self_timer", "text_scale", "theme"), r.values.keys)
        assertEquals(5, r.ignored)
        assertEquals(3, r.values["self_timer"])
        assertEquals(1.15f, r.values["text_scale"])
    }

    @Test fun albumNamesAreCleanedOnRestore() {
        val text = """{"format":"p3c-settings","version":1,"prefs":{"album":"My/Trip:*2026","reply_lang":"klingon"}}"""
        val p = Prefs(FakeSharedPreferences())
        p.restore(SettingsBackup.read(text).values)
        assertEquals("MyTrip2026", p.albumName)
        assertEquals("English", p.replyLanguage)
    }

    @Test fun fileNameHasTheDate() {
        val name = SettingsBackup.fileName(java.util.GregorianCalendar(2026, 10, 22).time)
        assertEquals("P3-Companion-settings-2026-11-22.json", name)
    }
}
