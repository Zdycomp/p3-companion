package com.zdycomp.p3companion

import android.content.Context
import android.content.SharedPreferences

/** Every setting the app has, in one place, saved the moment it changes. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("p3_settings", Context.MODE_PRIVATE)

    enum class Theme { SYSTEM, DARK, LIGHT }
    enum class Naming { ORIGINAL, DATE }

    var theme: Theme
        get() = enumOr(sp.getString("theme", null), Theme.DARK)
        set(v) = sp.edit().putString("theme", v.name).apply()

    /** Saved Storage Access Framework tree for the glasses' drive. */
    var driveUri: String?
        get() = sp.getString("drive_uri", null)
        set(v) = sp.edit().putString("drive_uri", v).apply()

    var driveLabel: String?
        get() = sp.getString("drive_label", null)
        set(v) = sp.edit().putString("drive_label", v).apply()

    var autoImport: Boolean
        get() = sp.getBoolean("auto_import", true)
        set(v) = sp.edit().putBoolean("auto_import", v).apply()

    var deleteAfterImport: Boolean
        get() = sp.getBoolean("delete_after", false)
        set(v) = sp.edit().putBoolean("delete_after", v).apply()

    var importPhotos: Boolean
        get() = sp.getBoolean("imp_photos", true)
        set(v) = sp.edit().putBoolean("imp_photos", v).apply()

    var importVideos: Boolean
        get() = sp.getBoolean("imp_videos", true)
        set(v) = sp.edit().putBoolean("imp_videos", v).apply()

    var importAudio: Boolean
        get() = sp.getBoolean("imp_audio", true)
        set(v) = sp.edit().putBoolean("imp_audio", v).apply()

    var naming: Naming
        get() = enumOr(sp.getString("naming", null), Naming.DATE)
        set(v) = sp.edit().putString("naming", v.name).apply()

    var albumName: String
        get() = sp.getString("album", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_ALBUM
        set(v) = sp.edit().putString("album", cleanAlbum(v)).apply()

    var keepScreenOn: Boolean
        get() = sp.getBoolean("keep_screen_on", true)
        set(v) = sp.edit().putBoolean("keep_screen_on", v).apply()

    var glassesAddress: String?
        get() = sp.getString("ble_addr", null)
        set(v) = sp.edit().putString("ble_addr", v).apply()

    var glassesName: String?
        get() = sp.getString("ble_name", null)
        set(v) = sp.edit().putString("ble_name", v).apply()

    var autoReconnect: Boolean
        get() = sp.getBoolean("auto_reconnect", true)
        set(v) = sp.edit().putBoolean("auto_reconnect", v).apply()

    /**
     * The owner's own LensMoo account id, which the glasses require at connect time.
     * Kept only in this app's private storage and never shown in full.
     */
    var userKey: String?
        get() = sp.getString("user_key", null)
        set(v) = sp.edit().putString("user_key", v).apply()

    /** Save photos taken with the glasses' own button while connected. */
    var saveButtonPhotos: Boolean
        get() = sp.getBoolean("save_button_photos", true)
        set(v) = sp.edit().putBoolean("save_button_photos", v).apply()

    var showLog: Boolean
        get() = sp.getBoolean("show_log", false)
        set(v) = sp.edit().putBoolean("show_log", v).apply()

    var startTab: Int
        get() = sp.getInt("start_tab", 0)
        set(v) = sp.edit().putInt("start_tab", v).apply()

    var lastImportSummary: String?
        get() = sp.getString("last_import", null)
        set(v) = sp.edit().putString("last_import", v).apply()

    // ---------- Assistant ----------

    var aiProvider: AiProvider
        get() = enumOr(sp.getString("ai_provider", null), AiProvider.GEMINI)
        set(v) = sp.edit().putString("ai_provider", v.name).apply()

    /** API keys stay in this private storage only and are never shown in full. */
    fun aiKey(provider: AiProvider = aiProvider): String? =
        sp.getString("ai_key_" + provider.name, null)?.takeIf { it.isNotBlank() }

    fun setAiKey(provider: AiProvider, key: String?) =
        sp.edit().putString("ai_key_" + provider.name, key?.trim()?.takeIf { it.isNotEmpty() }).apply()

    fun aiModel(provider: AiProvider = aiProvider): String =
        sp.getString("ai_model_" + provider.name, null)?.takeIf { it.isNotBlank() } ?: provider.defaultModel

    fun setAiModel(provider: AiProvider, model: String) =
        sp.edit().putString("ai_model_" + provider.name, AiWire.cleanModel(model, provider)).apply()

    /** The language every AI answer is written and spoken in. */
    var replyLanguage: String
        get() = Languages.byName(sp.getString("reply_lang", null)).name
        set(v) = sp.edit().putString("reply_lang", Languages.byName(v).name).apply()

    /** Touching the glasses' touch pad starts a voice question. */
    var tapToTalk: Boolean
        get() = sp.getBoolean("tap_to_talk", true)
        set(v) = sp.edit().putBoolean("tap_to_talk", v).apply()

    /** Use the glasses as microphone and speaker when they're the phone's Bluetooth headset. */
    var useGlassesAudio: Boolean
        get() = sp.getBoolean("glasses_audio", true)
        set(v) = sp.edit().putBoolean("glasses_audio", v).apply()

    var speakPhotoAnswers: Boolean
        get() = sp.getBoolean("speak_photo", true)
        set(v) = sp.edit().putBoolean("speak_photo", v).apply()

    var translateFrom: String
        get() = Languages.byName(sp.getString("tr_from", null)).name
        set(v) = sp.edit().putString("tr_from", Languages.byName(v).name).apply()

    var translateTo: String
        get() = sp.getString("tr_to", null)?.let { Languages.byName(it).name } ?: "Spanish"
        set(v) = sp.edit().putString("tr_to", Languages.byName(v).name).apply()

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    companion object {
        const val DEFAULT_ALBUM = "P3 Glasses"

        /** Album names become folder names, so strip anything a folder can't hold. */
        fun cleanAlbum(raw: String): String {
            val cleaned = raw.replace(Regex("[\\\\/:*?\"<>|]"), "").trim().take(40)
            return cleaned.ifBlank { DEFAULT_ALBUM }
        }
    }
}
