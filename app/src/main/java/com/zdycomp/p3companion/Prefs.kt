package com.zdycomp.p3companion

import android.content.Context
import android.content.SharedPreferences

/**
 * Every setting the app has, in one place, saved the moment it changes. Tests build it over an
 * in-memory [SharedPreferences] through the internal constructor.
 */
class Prefs internal constructor(private val sp: SharedPreferences) {

    constructor(context: Context) :
        this(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    enum class Theme { SYSTEM, DARK, LIGHT }
    enum class Naming { ORIGINAL, DATE }
    enum class Motion { SYSTEM, ON, OFF }
    enum class PhotoNote { OFF, TEXT, PICTURE }
    enum class GallerySort { NEWEST, OLDEST, LARGEST, NAME }

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

    /** The RFCOMM method that last worked, tried first next time. */
    var linkMethod: String?
        get() = sp.getString("link_method", null)
        set(v) = sp.edit().putString("link_method", v).apply()

    /** The person disconnected on purpose: don't reconnect by ourselves. */
    var userStopped: Boolean
        get() = sp.getBoolean("user_stopped", false)
        set(v) = sp.edit().putBoolean("user_stopped", v).apply()

    /** Action code that the glasses accepted for the live view command (3, or 2 as fallback). */
    var previewAction: Int
        get() = sp.getInt("preview_action", Xk.ACTION_EXECUTE)
        set(v) = sp.edit().putInt("preview_action", v).apply()

    /** Live view command the probe found working ("5720/3", "5710/2"…), "none", or null if never tested. */
    var livePath: String?
        get() = sp.getString("live_path", null)
        set(v) = sp.edit().putString("live_path", v).apply()

    /** The live view probe's plain-English result, and when it ran (ms since 1970, 0 = never). */
    var liveProbeSummary: String?
        get() = sp.getString("live_probe_summary", null)
        set(v) = sp.edit().putString("live_probe_summary", v).apply()

    var liveProbeAt: Long
        get() = sp.getLong("live_probe_at", 0L)
        set(v) = sp.edit().putLong("live_probe_at", v).apply()

    /** 3.3: a request after the open that made the glasses send frames ("5711/3 01"…), or null. */
    var livePull: String?
        get() = sp.getString("live_probe_pull", null)
        set(v) = sp.edit().putString("live_probe_pull", v).apply()

    /** Which version of the live view test produced [livePath] (0 = 3.1, whose "none" is tested again). */
    var liveProbeVersion: Int
        get() = sp.getInt("live_probe_version", 0)
        set(v) = sp.edit().putInt("live_probe_version", v).apply()

    /** Snapshot view: keep every snapshot in the gallery (off deletes each one after it's shown). */
    var keepSnapshots: Boolean
        get() = sp.getBoolean("keep_snapshots", false)
        set(v) = sp.edit().putBoolean("keep_snapshots", v).apply()

    /** The speech recognition service that last worked ("package/class"), tried first next time. */
    var recognizerService: String?
        get() = sp.getString("recognizer_service", null)
        set(v) = sp.edit().putString("recognizer_service", v).apply()

    enum class SpeakThrough { FAST, CALL }

    /**
     * Where spoken answers play. FAST: 3.3, a voice answer plays at once on the call link the mic opened
     * (no switch to media audio), which is let go right after; anything else on the media stream.
     * CALL: the hands-free call link stays up for the whole session, like before 3.1.
     */
    var speakThrough: SpeakThrough
        get() = enumOr(sp.getString("speak_through", null), SpeakThrough.FAST)
        set(v) = sp.edit().putString("speak_through", v.name).apply()

    /** Offline voice commands ("take a photo", "battery") are handled before asking the AI. */
    var voiceCommands: Boolean
        get() = sp.getBoolean("voice_commands", true)
        set(v) = sp.edit().putBoolean("voice_commands", v).apply()

    /** Whether Android has asked for this permission before (to spot "don't ask again"). */
    fun wasAsked(permission: String): Boolean = sp.getBoolean("asked_$permission", false)
    fun markAsked(permission: String) = sp.edit().putBoolean("asked_$permission", true).apply()

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


    // ---------- Appearance (2.1) ----------

    /** Accent color; [Accent.DYNAMIC] (wallpaper) is the default on Android 12+, Violet on 11. */
    var accent: Accent
        get() = enumOr(sp.getString("accent", null), Accent.DYNAMIC)
        set(v) = sp.edit().putString("accent", v.name).apply()

    /** The last accent picked by hand, so turning wallpaper colors off goes back to it. */
    var lastPreset: Accent
        get() = enumOr(sp.getString("last_preset", null), Accent.VIOLET).let { if (it == Accent.DYNAMIC) Accent.VIOLET else it }
        set(v) = sp.edit().putString("last_preset", v.name).apply()

    /** Hue (0-359) for the Custom accent. */
    var accentHue: Int
        get() = sp.getInt("accent_hue", 265).coerceIn(0, 359)
        set(v) = sp.edit().putInt("accent_hue", v.coerceIn(0, 359)).apply()

    /** Pure black backgrounds when the look is dark (AMOLED). */
    var amoled: Boolean
        get() = sp.getBoolean("amoled", false)
        set(v) = sp.edit().putBoolean("amoled", v).apply()

    var highContrast: Boolean
        get() = sp.getString("contrast", null) == "high"
        set(v) = sp.edit().putString("contrast", if (v) "high" else "standard").apply()

    /** Multiplies every text size: 0.9, 1.0, 1.15 or 1.3. */
    var textScale: Float
        get() = TEXT_SCALES.minByOrNull { kotlin.math.abs(it - sp.getFloat("text_scale", 1f)) } ?: 1f
        set(v) = sp.edit().putFloat("text_scale", TEXT_SCALES.minByOrNull { kotlin.math.abs(it - v) } ?: 1f).apply()

    var reduceMotion: Motion
        get() = enumOr(sp.getString("reduce_motion", null), Motion.SYSTEM)
        set(v) = sp.edit().putString("reduce_motion", v.name).apply()

    var haptics: Boolean
        get() = sp.getBoolean("haptics", true)
        set(v) = sp.edit().putBoolean("haptics", v).apply()

    // ---------- Glasses (2.1) ----------

    /** Connect when the app opens. Until changed it follows the 2.0 "Reconnect by itself" switch. */
    var connectOnLaunch: Boolean
        get() = if (sp.contains("connect_on_launch")) sp.getBoolean("connect_on_launch", true) else autoReconnect
        set(v) = sp.edit().putBoolean("connect_on_launch", v).apply()

    /** Keep the link (and button photos) going while the app is in the background. */
    var keepConnectedInBackground: Boolean
        get() = sp.getBoolean("keep_bg", true)
        set(v) = sp.edit().putBoolean("keep_bg", v).apply()

    /** Connection notification shows only "Glasses connected" (no battery). */
    var minimalNotification: Boolean
        get() = sp.getBoolean("note_minimal", false)
        set(v) = sp.edit().putBoolean("note_minimal", v).apply()

    /** Notification for photos saved while the app isn't on screen. */
    var photoNote: PhotoNote
        get() = enumOr(sp.getString("photo_note", null), PhotoNote.PICTURE)
        set(v) = sp.edit().putString("photo_note", v.name).apply()

    /** Low battery alert at this percentage; 0 is off. */
    var lowBatteryAt: Int
        get() = sp.getInt("low_battery", 20).let { if (it in BATTERY_STEPS) it else 20 }
        set(v) = sp.edit().putInt("low_battery", if (v in BATTERY_STEPS) v else 20).apply()

    // ---------- Capture (2.1) ----------

    /** Phone plays a shutter sound when the glasses take a photo. */
    var shutterSound: Boolean
        get() = sp.getBoolean("shutter_sound", false)
        set(v) = sp.edit().putBoolean("shutter_sound", v).apply()

    /** Phone vibrates when a photo from the glasses is saved. */
    var vibrateOnPhoto: Boolean
        get() = sp.getBoolean("vibrate_photo", true)
        set(v) = sp.edit().putBoolean("vibrate_photo", v).apply()

    /** Open the photo full screen after Take photo, when the app is on screen. */
    var openAfterCapture: Boolean
        get() = sp.getBoolean("open_after", false)
        set(v) = sp.edit().putBoolean("open_after", v).apply()

    /** Self-timer seconds before Take photo: 0 (off), 3, 5 or 10. */
    var selfTimer: Int
        get() = sp.getInt("self_timer", 0).let { if (it in TIMER_STEPS) it else 0 }
        set(v) = sp.edit().putInt("self_timer", if (v in TIMER_STEPS) v else 0).apply()

    var liveKeepScreenOn: Boolean
        get() = sp.getBoolean("live_screen_on", true)
        set(v) = sp.edit().putBoolean("live_screen_on", v).apply()

    // ---------- Gallery (2.1) ----------

    var gallerySort: GallerySort
        get() = enumOr(sp.getString("gallery_sort", null), GallerySort.NEWEST)
        set(v) = sp.edit().putString("gallery_sort", v.name).apply()

    var galleryColumns: Int
        get() = sp.getInt("gallery_cols", 3).coerceIn(2, 5)
        set(v) = sp.edit().putInt("gallery_cols", v.coerceIn(2, 5)).apply()

    // ---------- Assistant (2.1) ----------

    var ttsRate: Float
        get() = sp.getFloat("tts_rate", 1f).coerceIn(0.5f, 2f)
        set(v) = sp.edit().putFloat("tts_rate", v.coerceIn(0.5f, 2f)).apply()

    var ttsPitch: Float
        get() = sp.getFloat("tts_pitch", 1f).coerceIn(0.5f, 2f)
        set(v) = sp.edit().putFloat("tts_pitch", v.coerceIn(0.5f, 2f)).apply()

    /** How many earlier messages travel with a voice question: 0, 6, 12 or 24. */
    var historyLimit: Int
        get() = sp.getInt("history_limit", AiWire.HISTORY_LIMIT).let { if (it in HISTORY_STEPS) it else AiWire.HISTORY_LIMIT }
        set(v) = sp.edit().putInt("history_limit", if (v in HISTORY_STEPS) v else AiWire.HISTORY_LIMIT).apply()

    /** Speak voice-chat answers out loud (otherwise they're only shown). */
    var speakChatAnswers: Boolean
        get() = sp.getBoolean("speak_chat", true)
        set(v) = sp.edit().putBoolean("speak_chat", v).apply()

    /** Quick photo questions: label and question; "{language}" becomes the reply language. */
    var photoPresets: List<Pair<String, String>>
        get() {
            val raw = sp.getString("photo_presets", null) ?: return AiWire.photoPresetTemplates()
            val list = runCatching {
                (Json.parse(raw) as List<*>).mapNotNull { item ->
                    val m = item as? Map<*, *> ?: return@mapNotNull null
                    val l = (m["label"] as? String)?.trim().orEmpty()
                    val q = (m["question"] as? String)?.trim().orEmpty()
                    if (l.isEmpty() || q.isEmpty()) null else l.take(60) to q.take(1000)
                }
            }.getOrNull()
            return if (list.isNullOrEmpty()) AiWire.photoPresetTemplates() else list.take(MAX_PRESETS)
        }
        set(v) {
            val clean = v.map { it.first.trim().take(60) to it.second.trim().take(1000) }
                .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }.take(MAX_PRESETS)
            sp.edit().putString("photo_presets", Json.write(clean.map { mapOf("label" to it.first, "question" to it.second) })).apply()
        }

    fun resetPhotoPresets() = sp.edit().remove("photo_presets").apply()

    // ---------- Privacy (2.1) ----------

    var appLock: Boolean
        get() = sp.getBoolean("app_lock", false)
        set(v) = sp.edit().putBoolean("app_lock", v).apply()

    /** Minutes in the background before the lock comes back: 0, 1, 5 or 15. */
    var lockAfterMin: Int
        get() = sp.getInt("lock_after", 0).let { if (it in LOCK_STEPS) it else 0 }
        set(v) = sp.edit().putInt("lock_after", if (v in LOCK_STEPS) v else 0).apply()

    /** Hide the app in Recents and block screenshots (FLAG_SECURE). */
    var secureWindow: Boolean
        get() = sp.getBoolean("secure_window", false)
        set(v) = sp.edit().putBoolean("secure_window", v).apply()

    /** Show "Saved" instead of the first and last characters of keys. */
    var hideKeys: Boolean
        get() = sp.getBoolean("hide_keys", false)
        set(v) = sp.edit().putBoolean("hide_keys", v).apply()

    /** The versionCode the What's new dialog was last shown for (0 = never opened before). */
    var lastSeenVersion: Int
        get() = sp.getInt("last_seen_version", 0)
        set(v) = sp.edit().putInt("last_seen_version", v).apply()

    /** Masks a key for display, honouring [hideKeys]. */
    fun maskForDisplay(key: String): String = if (hideKeys) "Saved" else KeyFinder.mask(key)

    // ---------- backup and reset ----------

    /** Every saved value, for [SettingsBackup.export] (which keeps only the whitelisted keys). */
    fun snapshot(): Map<String, Any?> = sp.all.toMap()

    /**
     * Applies restored values. Each goes through its setter so names are cleaned and enums checked
     * exactly as when they're changed in the app. Returns how many were applied.
     */
    fun restore(values: Map<String, Any?>): Int {
        var n = 0
        for ((k, v) in values) {
            val ok = runCatching {
                when {
                    k == "album" -> albumName = v as String
                    k == "reply_lang" -> replyLanguage = v as String
                    k == "tr_from" -> translateFrom = v as String
                    k == "tr_to" -> translateTo = v as String
                    k.startsWith("ai_model_") -> {
                        val provider = AiProvider.valueOf(k.removePrefix("ai_model_"))
                        setAiModel(provider, v as String)
                    }
                    k == "photo_presets" -> {
                        sp.edit().putString(k, v as String).apply()
                        photoPresets = photoPresets // re-validate
                    }
                    else -> when (v) {
                        is Boolean -> sp.edit().putBoolean(k, v).apply()
                        is Int -> sp.edit().putInt(k, v).apply()
                        is Float -> sp.edit().putFloat(k, v).apply()
                        is String -> sp.edit().putString(k, v).apply()
                        else -> error("type")
                    }
                }
            }.isSuccess
            if (ok) n++
        }
        return n
    }

    /** Back to defaults, keeping the glasses key, AI keys, saved glasses, drive and import history. */
    fun resetToDefaults() {
        val keep = sp.all.filterKeys { k ->
            k == "user_key" || k.startsWith("ai_key_") || k == "drive_uri" || k == "drive_label" ||
                k == "ble_addr" || k == "ble_name" || k == "link_method" || k == "preview_action" ||
                k == "live_path" || k.startsWith("live_probe_") || k == "recognizer_service" ||
                k.startsWith("asked_") || k == "last_import" || k == "last_seen_version" || k == "user_stopped"
        }
        val e = sp.edit().clear()
        for ((k, v) in keep) when (v) {
            is Boolean -> e.putBoolean(k, v)
            is Int -> e.putInt(k, v)
            is Float -> e.putFloat(k, v)
            is Long -> e.putLong(k, v)
            is String -> e.putString(k, v)
        }
        e.apply()
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    companion object {
        const val FILE = "p3_settings"
        const val DEFAULT_ALBUM = "P3 Glasses"
        const val MAX_PRESETS = 8
        val TEXT_SCALES = listOf(0.9f, 1f, 1.15f, 1.3f)
        val BATTERY_STEPS = listOf(0, 10, 15, 20, 30)
        val TIMER_STEPS = listOf(0, 3, 5, 10)
        val HISTORY_STEPS = listOf(0, 6, 12, 24)
        val LOCK_STEPS = listOf(0, 1, 5, 15)

        /** Album names become folder names, so strip anything a folder can't hold. */
        fun cleanAlbum(raw: String): String {
            val cleaned = raw.replace(Regex("[\\\\/:*?\"<>|]"), "").trim().take(40)
            return cleaned.ifBlank { DEFAULT_ALBUM }
        }
    }
}
