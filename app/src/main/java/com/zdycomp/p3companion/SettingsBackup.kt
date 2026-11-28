package com.zdycomp.p3companion

/**
 * Saving settings to a file and reading them back (OPTIONS-PLAN B1/B2). Pure Kotlin over plain maps
 * so it is unit-tested. Only whitelisted keys ever leave the app: no glasses key, no AI key, no
 * saved glasses address, drive or connection state.
 */
object SettingsBackup {

    const val FORMAT = "p3c-settings"
    const val VERSION = 1

    enum class Kind { BOOL, INT, FLOAT, STRING }

    /** Every key that may be saved and restored, with its type. */
    val WHITELIST: Map<String, Kind> = linkedMapOf(
        // Look
        "theme" to Kind.STRING, "accent" to Kind.STRING, "last_preset" to Kind.STRING, "accent_hue" to Kind.INT, "amoled" to Kind.BOOL,
        "contrast" to Kind.STRING, "text_scale" to Kind.FLOAT, "reduce_motion" to Kind.STRING, "haptics" to Kind.BOOL,
        "start_tab" to Kind.INT,
        // Import
        "auto_import" to Kind.BOOL, "delete_after" to Kind.BOOL, "imp_photos" to Kind.BOOL, "imp_videos" to Kind.BOOL,
        "imp_audio" to Kind.BOOL, "naming" to Kind.STRING, "album" to Kind.STRING, "keep_screen_on" to Kind.BOOL,
        // Glasses
        "auto_reconnect" to Kind.BOOL, "connect_on_launch" to Kind.BOOL, "keep_bg" to Kind.BOOL,
        "note_minimal" to Kind.BOOL, "photo_note" to Kind.STRING, "low_battery" to Kind.INT,
        "save_button_photos" to Kind.BOOL, "show_log" to Kind.BOOL,
        // Capture
        "shutter_sound" to Kind.BOOL, "vibrate_photo" to Kind.BOOL, "open_after" to Kind.BOOL, "self_timer" to Kind.INT,
        "live_screen_on" to Kind.BOOL, "live_fill" to Kind.BOOL, "live_rotation" to Kind.INT, "live_sharpen" to Kind.BOOL,
        "live_upscale" to Kind.BOOL, "live_record_audio" to Kind.BOOL,
        // Gallery
        "gallery_sort" to Kind.STRING, "gallery_cols" to Kind.INT,
        // Assistant (never the keys)
        "ai_provider" to Kind.STRING, "reply_lang" to Kind.STRING, "tap_to_talk" to Kind.BOOL, "glasses_audio" to Kind.BOOL,
        "speak_photo" to Kind.BOOL, "speak_chat" to Kind.BOOL, "tr_from" to Kind.STRING, "tr_to" to Kind.STRING,
        "tts_rate" to Kind.FLOAT, "tts_pitch" to Kind.FLOAT, "history_limit" to Kind.INT, "photo_presets" to Kind.STRING,
        "speak_through" to Kind.STRING, "voice_commands" to Kind.BOOL, "keep_snapshots" to Kind.BOOL,
        "convo_two_way" to Kind.BOOL, "convo_auto" to Kind.BOOL, "convo_speak" to Kind.BOOL, "convo_original" to Kind.BOOL,
        "convo_background" to Kind.BOOL,
        // 4.0
        "gemini_voice_on" to Kind.BOOL, "gemini_voice" to Kind.STRING, "gemini_stt" to Kind.BOOL, "hold_to_talk" to Kind.BOOL,
        "gemini_tts_model" to Kind.STRING, "enhance_photos" to Kind.BOOL, "enhance_strength" to Kind.FLOAT, "enhance_scale" to Kind.INT,
        "live_sharpen_live" to Kind.BOOL, "torch_auto_off" to Kind.INT,
        // 4.1 (the learned touch codes and Gemini's format answer stay with these glasses and this key)
        "gesture_map" to Kind.STRING, "barge_in" to Kind.BOOL, "reply_volume_auto" to Kind.BOOL, "reply_volume_cap" to Kind.INT,
        "voice_chain" to Kind.BOOL, "pause_live_listen" to Kind.BOOL, "burst_count" to Kind.INT, "live_burst" to Kind.BOOL,
        "media_keys_always" to Kind.BOOL, "adopt_glasses_mic" to Kind.BOOL,
        // 4.1.2 (the remembered open data stays with these glasses)
        "live_detail_first" to Kind.BOOL,
        // SupRevolt (never the token or the server address)
        "sr_scans" to Kind.STRING,
        // Privacy
        "app_lock" to Kind.BOOL, "lock_after" to Kind.INT, "secure_window" to Kind.BOOL, "hide_keys" to Kind.BOOL,
    )

    /** Keys that are never written, even if someone adds them to the whitelist by mistake. */
    private val NEVER = listOf(
        "user_key", "ai_key_", "drive_uri", "drive_label", "ble_addr", "ble_name", "link_method",
        "user_stopped", "preview_action", "asked_", "last_import", "last_seen_version",
        "live_path", "live_probe_", "live_quality_", "live_hq", "recognizer_service", "recognizer_", "sr_token", "sr_url", "sr_http_ok",
    )

    fun isSecret(key: String): Boolean = NEVER.any { key == it || (it.endsWith("_") && key.startsWith(it)) }

    private fun kindOf(key: String): Kind? = when {
        isSecret(key) -> null
        key.startsWith("ai_model_") && AiProvider.values().any { "ai_model_" + it.name == key } -> Kind.STRING
        else -> WHITELIST[key]
    }

    /** The backup file's JSON text for these saved values. */
    fun export(values: Map<String, Any?>, appVersion: String): String {
        val prefs = sortedMapOf<String, Any?>()
        for ((k, v) in values) {
            val kind = kindOf(k) ?: continue
            val ok = when (kind) {
                Kind.BOOL -> v is Boolean
                Kind.INT -> v is Int
                Kind.FLOAT -> v is Float
                Kind.STRING -> v is String
            }
            if (ok) prefs[k] = if (v is Float) v.toDouble() else v
        }
        return Json.write(linkedMapOf("format" to FORMAT, "version" to VERSION, "app" to appVersion, "prefs" to prefs))
    }

    class Result(val values: Map<String, Any?>, val ignored: Int)

    class BadFile(message: String) : Exception(message)

    /** Reads a backup file. Throws [BadFile] with a message for people when it isn't one. */
    fun read(text: String): Result {
        val root = runCatching { Json.parse(text) }.getOrNull() as? Map<*, *>
            ?: throw BadFile("That file isn't a P3 Companion settings file.")
        if (root["format"] != FORMAT) throw BadFile("That file isn't a P3 Companion settings file.")
        val version = (root["version"] as? Number)?.toInt() ?: throw BadFile("That settings file has no version.")
        if (version > VERSION) throw BadFile("That settings file is from a newer version of the app. Update the app first.")
        val prefs = root["prefs"] as? Map<*, *> ?: throw BadFile("That settings file is empty.")
        val out = linkedMapOf<String, Any?>()
        var ignored = 0
        for ((rawKey, v) in prefs) {
            val k = rawKey as? String
            val kind = k?.let { kindOf(it) }
            val value: Any? = when (kind) {
                Kind.BOOL -> v as? Boolean
                Kind.INT -> (v as? Number)?.takeIf { it is Long || it is Int }?.toLong()
                    ?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
                Kind.FLOAT -> (v as? Number)?.toDouble()?.takeIf { !it.isNaN() && !it.isInfinite() }?.toFloat()
                Kind.STRING -> (v as? String)?.take(4000)
                null -> null
            }
            if (k == null || value == null) ignored++ else out[k] = value
        }
        return Result(out, ignored)
    }

    fun fileName(date: java.util.Date = java.util.Date()): String =
        "P3-Companion-settings-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(date) + ".json"
}
