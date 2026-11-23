package com.zdycomp.p3companion

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/** Every option in one place, grouped into sections. Changes are saved the moment you make them. */
class SettingsScreen(private val a: MainActivity) : Screen {
    override val label = "Settings"

    private val main = Handler(Looper.getMainLooper())
    private var storageText: TextView? = null

    override fun build(): View {
        val ui = a.ui
        val col = ui.page()
        appearance(col)
        glasses(col)
        capture(col)
        import(col)
        assistant(col)
        supRevolt(col)
        privacy(col)
        storage(col)
        backup(col)
        about(col)
        return ui.screen("Settings", col)
    }

    private fun pad(v: LinearLayout): LinearLayout = v.apply { setPadding(a.ui.dp(16), paddingTop, a.ui.dp(16), paddingBottom) }

    private fun note(text: String): TextView = a.ui.text(text, Type.BODY_SMALL, a.ui.s.onSurfaceVariant).apply {
        setPadding(a.ui.dp(16), 0, a.ui.dp(16), a.ui.dp(8))
    }

    // ---------------- Appearance ----------------

    private fun appearance(col: LinearLayout) {
        val ui = a.ui
        val p = a.prefs
        col.addView(ui.sectionLabel("Appearance"))
        val g = ui.group()
        g.addView(pad(ui.choiceRow("Theme", listOf("Dark", "Light", "Match phone"),
            when (p.theme) { Prefs.Theme.DARK -> 0; Prefs.Theme.LIGHT -> 1; Prefs.Theme.SYSTEM -> 2 }) {
            p.theme = listOf(Prefs.Theme.DARK, Prefs.Theme.LIGHT, Prefs.Theme.SYSTEM)[it]
            a.recreate()
        }))

        val dynamicOn = Themes.effectiveAccent(p) == Accent.DYNAMIC
        if (Themes.dynamicAvailable) {
            g.addView(ui.switchRow("Colors from your wallpaper", "Material You: the app takes its colors from your wallpaper",
                dynamicOn, R.drawable.ic_palette) { on ->
                p.accent = if (on) Accent.DYNAMIC else p.lastPreset
                a.recreate()
            })
        }
        g.addView(ui.text(if (dynamicOn) "Accent color · using your wallpaper colors" else "Accent color", Type.BODY_LARGE).apply {
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(4))
        })
        g.addView(swatches(dynamicOn))
        if (!dynamicOn && p.accent == Accent.CUSTOM) g.addView(hueSlider())

        val dark = ui.p.isDark
        if (dark) {
            g.addView(ui.switchRow("Pure black", "Black backgrounds for OLED screens", p.amoled, R.drawable.ic_dark_mode) {
                p.amoled = it; a.recreate()
            })
        }
        g.addView(ui.switchRow("High contrast", "Stronger text and outlines", p.highContrast, R.drawable.ic_contrast) {
            p.highContrast = it; a.recreate()
        })

        // Text size with a live preview.
        val sizes = Prefs.TEXT_SCALES
        val names = listOf("Small", "Default", "Large", "Largest")
        val idx = sizes.indexOf(p.textScale).coerceAtLeast(1)
        val preview = ui.text("Aa", Type.TITLE_LARGE).apply { gravity = Gravity.CENTER }
        val sizeLabel = ui.text("Text size: ${names[idx]}", Type.BODY_LARGE)
        fun previewAt(i: Int) {
            sizeLabel.text = "Text size: ${names[i]}"
            preview.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22f * sizes[i])
        }
        previewAt(idx)
        g.addView(ui.row().apply {
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), 0)
            addView(ui.icon(R.drawable.ic_text_fields, ui.s.onSurfaceVariant), LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)).apply { marginEnd = ui.dp(16) })
            addView(sizeLabel, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            addView(preview, LinearLayout.LayoutParams(ui.dp(56), ui.dp(40)))
        })
        g.addView(ui.slider(sizes.size, idx, onStep = { previewAt(it) }) { i ->
            if (sizes[i] != p.textScale) { p.textScale = sizes[i]; a.recreate() }
        }.apply { setPadding(ui.dp(56), ui.dp(12), ui.dp(24), ui.dp(12)) })

        val motions = listOf(Prefs.Motion.SYSTEM, Prefs.Motion.ON, Prefs.Motion.OFF)
        g.addView(pad(ui.choiceRow("Reduce motion", listOf("Match phone", "On", "Off"), motions.indexOf(p.reduceMotion)) {
            p.reduceMotion = motions[it]; a.recreate()
        }))
        g.addView(ui.switchRow("Vibrate on taps", "Light feedback on switches, tabs and photos", p.haptics, R.drawable.ic_vibration) {
            p.haptics = it; a.recreate()
        })
        g.addView(pad(ui.choiceRow("Open the app on", listOf("Import", "Gallery", "Glasses", "Assistant"), p.startTab.coerceIn(0, 3)) {
            p.startTab = it
        }))
        col.addView(g)
    }

    private fun swatches(disabled: Boolean): View {
        val ui = a.ui
        val p = a.prefs
        val dark = ui.p.isDark
        val flow = Flow(a, ui.dp(8), ui.dp(8)).apply { setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(12)) }
        val choices = Accent.values().filter { it != Accent.DYNAMIC }
        choices.forEach { acc ->
            val scheme = Scheme.build(Accent.source(acc, p.accentHue), dark)
            val selected = !disabled && Themes.effectiveAccent(p) == acc
            val v = Swatch(a, ui, scheme, selected).apply {
                contentDescription = acc.label
                stateDescription = if (selected) "Selected" else null
                isEnabled = !disabled
                alpha = if (disabled) 0.4f else 1f
                setOnClickListener {
                    ui.haptic(this, Haptic.TICK)
                    p.accent = acc
                    p.lastPreset = acc
                    a.recreate()
                }
            }
            flow.addView(v, android.view.ViewGroup.MarginLayoutParams(ui.dp(48), ui.dp(48)))
        }
        return flow
    }

    private fun hueSlider(): View {
        val ui = a.ui
        val p = a.prefs
        val box = ui.column().apply { setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(8)) }
        val label = ui.text("Hue ${p.accentHue}°", Type.BODY_MEDIUM, ui.s.onSurfaceVariant)
        box.addView(label)
        val bar = ui.slider(360, p.accentHue, onStep = { label.text = "Hue $it°" }) { p.accentHue = it; a.recreate() }
        val stops = IntArray(7) { Tonal.colorAt(60.0, it * 60.0, 48.0) }
        bar.progressDrawable = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, stops).apply { cornerRadius = ui.dpf(3f) }
        bar.progressTintList = null
        bar.progressBackgroundTintList = null
        box.addView(bar)
        return box
    }

    // ---------------- Glasses ----------------

    private fun glasses(col: LinearLayout) {
        val ui = a.ui
        val p = a.prefs
        col.addView(ui.sectionLabel("Glasses"))
        val g = ui.group()
        g.addView(ui.switchRow("Connect when the app opens", null, p.connectOnLaunch, R.drawable.ic_link) { p.connectOnLaunch = it })
        g.addView(ui.switchRow("Reconnect after a drop", "When the link drops or the glasses come back in range", p.autoReconnect, R.drawable.ic_refresh) {
            p.autoReconnect = it
        })
        g.addView(ui.switchRow(
            "Keep connected in the background",
            if (p.keepConnectedInBackground) "Button photos are saved even with the app closed"
            else "Button photos are saved only while the app is open",
            p.keepConnectedInBackground, R.drawable.ic_bluetooth,
        ) { p.keepConnectedInBackground = it; a.refresh() })
        g.addView(ui.switchRow(
            "Save photos taken with the glasses' button",
            "While connected, each photo you take on the glasses is copied over Bluetooth",
            p.saveButtonPhotos, R.drawable.ic_photo_camera,
        ) { p.saveButtonPhotos = it })
        g.addView(pad(ui.choiceRow("Connection notification", listOf("Detailed", "Minimal"), if (p.minimalNotification) 1 else 0) {
            p.minimalNotification = it == 1
        }))
        g.addView(ui.listRow("Hide the connection notification", "Opens Android's settings for it", R.drawable.ic_notifications,
            ui.icon(R.drawable.ic_open_in_new, ui.s.onSurfaceVariant)) {
            a.launch(Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, a.packageName)
                .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, "glasses_link"))
        })
        val notes = listOf(Prefs.PhotoNote.OFF, Prefs.PhotoNote.TEXT, Prefs.PhotoNote.PICTURE)
        g.addView(pad(ui.choiceRow("Photo saved notification", listOf("Off", "Text", "With picture"), notes.indexOf(p.photoNote)) {
            p.photoNote = notes[it]
            if (it > 0) a.withNotifications {}
        }))
        g.addView(note("Shown when a photo arrives while the app isn't open."))
        val levels = Prefs.BATTERY_STEPS
        g.addView(pad(ui.choiceRow("Low battery alert", levels.map { if (it == 0) "Off" else "$it%" }, levels.indexOf(p.lowBatteryAt).coerceAtLeast(0)) {
            p.lowBatteryAt = levels[it]
            if (levels[it] > 0) a.withNotifications {}
        }))
        g.addView(ui.switchRow("Show connection log", "On the Glasses tab, for troubleshooting", p.showLog, R.drawable.ic_history) { p.showLog = it })
        g.addView(ui.listRow("Run diagnostics", "Checks every step and makes a report", R.drawable.ic_build,
            ui.icon(R.drawable.ic_chevron_right, ui.s.onSurfaceVariant)) { a.showDiagnostics() })
        if (p.glassesAddress != null) {
            g.addView(ui.listRow("Forget these glasses", p.glassesName, R.drawable.ic_delete, danger = true) {
                a.confirm("Forget glasses?", "You'll pick them again from your paired devices.", "Forget") {
                    a.glasses.disconnect()
                    p.glassesAddress = null
                    p.glassesName = null
                    a.refresh()
                }
            })
        }
        col.addView(g)
    }

    // ---------------- Capture ----------------

    private fun capture(col: LinearLayout) {
        val ui = a.ui
        val p = a.prefs
        col.addView(ui.sectionLabel("Capture"))
        val g = ui.group()
        g.addView(ui.switchRow("Shutter sound on the phone", "The glasses' own beep can't be turned off", p.shutterSound, R.drawable.ic_volume_up) {
            p.shutterSound = it
        })
        g.addView(ui.switchRow("Vibrate when a photo is saved", "Two short pulses, also with the app closed", p.vibrateOnPhoto, R.drawable.ic_vibration) {
            p.vibrateOnPhoto = it
        })
        g.addView(ui.switchRow("Open photo after taking it", "After Take photo in the app", p.openAfterCapture, R.drawable.ic_open_in_new) {
            p.openAfterCapture = it
        })
        val steps = Prefs.TIMER_STEPS
        g.addView(pad(ui.choiceRow("Self-timer", steps.map { if (it == 0) "Off" else "$it s" }, steps.indexOf(p.selfTimer).coerceAtLeast(0)) {
            p.selfTimer = steps[it]
        }))
        g.addView(ui.switchRow("Keep screen on in live view", null, p.liveKeepScreenOn, R.drawable.ic_videocam) { p.liveKeepScreenOn = it })
        g.addView(note("Live view and its recordings come in the size the glasses send (often 320×240). There's no command to change it, so the live view header shows what arrives."))
        col.addView(g)
    }

    // ---------------- Import ----------------

    private fun import(col: LinearLayout) {
        val ui = a.ui
        val p = a.prefs
        col.addView(ui.sectionLabel("Import over the cable"))
        val g = ui.group()
        g.addView(ui.switchRow("Import when plugged in", "Starts by itself when the glasses' drive appears", p.autoImport, R.drawable.ic_usb) { p.autoImport = it })
        g.addView(ui.switchRow("Photos", null, p.importPhotos, R.drawable.ic_photo) { p.importPhotos = it })
        g.addView(ui.switchRow("Videos", null, p.importVideos, R.drawable.ic_videocam) { p.importVideos = it })
        g.addView(ui.switchRow("Voice recordings", null, p.importAudio, R.drawable.ic_mic) { p.importAudio = it })
        g.addView(ui.switchRow(
            "Free up the glasses",
            "Delete each file from the glasses after it's safely copied",
            p.deleteAfterImport, R.drawable.ic_delete_sweep,
        ) { on ->
            if (on) {
                a.confirm(
                    "Delete from the glasses after import?",
                    "Each file is removed from the glasses only after its copy on the phone is complete and the same size.",
                    "Turn on",
                ) { p.deleteAfterImport = true; a.refresh() }
                a.refresh()
            } else p.deleteAfterImport = false
        })
        g.addView(ui.switchRow("Keep screen on while importing", null, p.keepScreenOn, R.drawable.ic_power) { p.keepScreenOn = it })
        g.addView(pad(ui.choiceRow("File names", listOf("Date and time", "Keep original"),
            if (p.naming == Prefs.Naming.DATE) 0 else 1) {
            p.naming = if (it == 0) Prefs.Naming.DATE else Prefs.Naming.ORIGINAL
        }))
        g.addView(ui.column().apply {
            setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(12))
            addView(ui.text("Album name", Type.BODY_LARGE).apply { setPadding(0, 0, 0, ui.dp(6)) })
            addView(ui.input(p.albumName, Prefs.DEFAULT_ALBUM) { p.albumName = it })
            addView(ui.text("Used for photos, videos and recordings from the glasses.", Type.BODY_SMALL, ui.s.onSurfaceVariant).apply {
                setPadding(0, ui.dp(6), 0, 0)
            })
        })
        g.addView(ui.listRow("Choose a different glasses drive", p.driveLabel, R.drawable.ic_usb,
            ui.icon(R.drawable.ic_chevron_right, ui.s.onSurfaceVariant)) { a.pickDrive() })
        g.addView(ui.listRow("Forget imported history", "The next import copies everything again", R.drawable.ic_history, danger = true) {
            a.confirm(
                "Forget imported history?",
                "The next import copies everything on the glasses again, including files you already have (${a.importer.ledgerCount()} remembered).",
                "Forget",
            ) { a.importer.resetLedger(); a.toast("Done.") }
        })
        col.addView(g)
    }

    // ---------------- Assistant ----------------

    private fun assistant(col: LinearLayout) {
        val ui = a.ui
        col.addView(ui.sectionLabel("Assistant"))
        col.addView(AiSetup.card(a).apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(8) })
        col.addView(AiSetup.optionsGroup(a))
    }

    // ---------------- SupRevolt ----------------

    private fun supRevolt(col: LinearLayout) {
        val ui = a.ui
        val p = a.prefs
        col.addView(ui.sectionLabel("SupRevolt"))
        val g = ui.group()
        val sub = when {
            p.srReady -> p.srUrl
            else -> "Scan photos, documents and text on your own server"
        }
        g.addView(ui.listRow("SupRevolt server and scans", sub, R.drawable.ic_shield_scan,
            ui.icon(R.drawable.ic_chevron_right, ui.s.onSurfaceVariant)) { a.showSupRevolt() })
        col.addView(g)
    }

    // ---------------- Privacy ----------------

    private fun privacy(col: LinearLayout) {
        val ui = a.ui
        val p = a.prefs
        col.addView(ui.sectionLabel("Privacy"))
        val g = ui.group()
        val secure = AppLock.deviceSecure(a)
        g.addView(ui.switchRow(
            "Lock the app",
            if (secure) "Ask for your fingerprint, face or screen lock" else "Set a screen lock on your phone first",
            p.appLock && secure, R.drawable.ic_lock, enabled = secure,
        ) { on -> p.appLock = on; if (on) AppLock.markUnlocked(); a.refresh() })
        if (p.appLock && secure) {
            val steps = Prefs.LOCK_STEPS
            g.addView(pad(ui.choiceRow("Lock after", steps.map { if (it == 0) "Right away" else "$it min" }, steps.indexOf(p.lockAfterMin).coerceAtLeast(0)) {
                p.lockAfterMin = steps[it]
            }))
        }
        g.addView(ui.switchRow("Hide content in Recents", "Also blocks screenshots, including of Live view", p.secureWindow, R.drawable.ic_photo_library) {
            p.secureWindow = it; a.recreate()
        })
        g.addView(ui.switchRow("Hide keys completely", "Show only \"Saved\" instead of the first and last characters", p.hideKeys, R.drawable.ic_lock) {
            p.hideKeys = it
        })
        g.addView(ui.listRow("Clear Assistant history", "Conversation and translations", R.drawable.ic_chat) {
            a.confirm("Clear Assistant history?", "The conversation and the translations on this phone are removed.", "Clear") {
                a.assistant.clearChat()
                a.assistant.clearTranslations()
                a.toast("Cleared.")
            }
        })
        g.addView(ui.listRow("Clear connection log", null, R.drawable.ic_delete_sweep) {
            a.glasses.clearLog()
            a.toast("Connection log cleared.")
        })
        col.addView(g)
    }

    // ---------------- Storage ----------------

    private fun storage(col: LinearLayout) {
        val ui = a.ui
        col.addView(ui.sectionLabel("Storage"))
        val g = ui.group()
        val text = ui.text("Counting…", Type.BODY_MEDIUM, ui.s.onSurfaceVariant)
        storageText = text
        g.addView(ui.row().apply {
            gravity = Gravity.TOP
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(8))
            addView(ui.icon(R.drawable.ic_storage, ui.s.onSurfaceVariant), LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)).apply { marginEnd = ui.dp(16) })
            addView(ui.column().apply {
                addView(ui.text("Storage used", Type.BODY_LARGE))
                addView(text)
            }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        })
        countStorage()
        val busy = when {
            a.readingKey -> "Wait for the key search to finish"
            a.importer.running -> "Wait for the import to finish"
            else -> null
        }
        g.addView(ui.listRow("Clear temporary files", busy ?: "Leftovers from key searches and live view", R.drawable.ic_delete_sweep) {
            when {
                a.readingKey -> a.toast("Wait for the key search to finish.")
                a.importer.running -> a.toast("Wait for the import to finish.")
                else -> clearCache()
            }
        })
        col.addView(g)
    }

    private fun countStorage() {
        val album = a.prefs.albumName
        val ctx = a.applicationContext
        Thread {
            var bytes = 0L
            var files = 0
            val vol = MediaStore.VOLUME_EXTERNAL_PRIMARY
            listOf(
                MediaStore.Images.Media.getContentUri(vol) to Environment.DIRECTORY_PICTURES,
                MediaStore.Video.Media.getContentUri(vol) to Environment.DIRECTORY_MOVIES,
                MediaStore.Audio.Media.getContentUri(vol) to Environment.DIRECTORY_MUSIC,
            ).forEach { (uri, dir) ->
                runCatching {
                    ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE),
                        "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf("$dir/$album/"), null)?.use { c ->
                        while (c.moveToNext()) { bytes += c.getLong(0); files++ }
                    }
                }
            }
            val data = size(ctx.filesDir) + size(File(ctx.applicationInfo.dataDir, "shared_prefs"))
            val cache = size(ctx.cacheDir)
            val text = "Photos and videos from the glasses: ${human(bytes)} ($files files)\n" +
                "App data: ${human(data)}\nTemporary files: ${human(cache)}"
            main.post { storageText?.text = text }
        }.start()
    }

    private fun clearCache() {
        val ctx = a.applicationContext
        val album = a.prefs.albumName
        Thread {
            val before = size(ctx.cacheDir)
            ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            PreviewRecorder.cleanStale(ctx, album)
            main.post {
                a.toast("Cleared ${human(before)} of temporary files.")
                countStorage()
            }
        }.start()
    }

    private fun size(f: File?): Long = if (f == null || !f.exists()) 0L else if (f.isFile) f.length() else
        f.listFiles()?.sumOf { size(it) } ?: 0L

    private fun human(b: Long): String = when {
        b >= 1L shl 30 -> String.format("%.1f GB", b / (1024.0 * 1024 * 1024))
        b >= 1L shl 20 -> String.format("%.1f MB", b / (1024.0 * 1024))
        b >= 1L shl 10 -> String.format("%d KB", b / 1024)
        else -> "$b B"
    }

    // ---------------- Backup ----------------

    private fun backup(col: LinearLayout) {
        val ui = a.ui
        col.addView(ui.sectionLabel("Backup"))
        val g = ui.group()
        g.addView(ui.listRow("Save settings to a file", "Keys are never written to the file", R.drawable.ic_save) { a.saveSettingsFile() })
        g.addView(ui.listRow("Restore settings from a file", null, R.drawable.ic_upload) { a.restoreSettingsFile() })
        g.addView(ui.listRow("Reset settings to default", "Keeps your keys, glasses and import history", R.drawable.ic_restart, danger = true) {
            a.confirm("Reset settings?", "Every option goes back to its default. Your glasses key, AI keys, saved glasses and import history stay.", "Reset") {
                a.prefs.resetToDefaults()
                a.recreate()
            }
        })
        col.addView(g)
    }

    // ---------------- About ----------------

    private fun about(col: LinearLayout) {
        val ui = a.ui
        col.addView(ui.sectionLabel("About"))
        val g = ui.group()
        g.addView(ui.listRow("About P3 Companion", "Version ${a.appVersion()} · what's new, credits", R.drawable.ic_info,
            ui.icon(R.drawable.ic_chevron_right, ui.s.onSurfaceVariant)) { a.showAbout() })
        col.addView(g)
        col.addView(ui.text("Not made by or connected to NovaSight, Shenju or LensMoo.", Type.BODY_SMALL, ui.s.onSurfaceVariant).apply {
            gravity = Gravity.CENTER
            setPadding(0, ui.dp(16), 0, 0)
        })
    }

    override fun onHidden() { storageText = null }
}

/**
 * Accent swatch (DESIGN-SPEC 1.5): top half primary, bottom-left secondaryContainer, bottom-right
 * tertiaryContainer; the selected one gets a ring and a check.
 */
private class Swatch(context: Context, private val ui: Ui, private val scheme: Scheme, private val selected: Boolean) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ui.dpf(3f) }
    private val box = RectF()
    private val check = ui.drawable(R.drawable.ic_check, scheme.onPrimary)

    init {
        isClickable = true
        isFocusable = true
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(Ui.withAlpha(ui.s.onSurface, 31)), null, ui.oval(android.graphics.Color.WHITE)
        )
        ui.pressable(this)
    }

    override fun onDraw(c: Canvas) {
        val inset = if (selected) ui.dpf(6f) else ui.dpf(4f)
        box.set(inset, inset, width - inset, height - inset)
        paint.color = scheme.primary
        c.drawArc(box, 180f, 180f, true, paint)
        paint.color = scheme.secondaryContainer
        c.drawArc(box, 90f, 90f, true, paint)
        paint.color = scheme.tertiaryContainer
        c.drawArc(box, 0f, 90f, true, paint)
        if (selected) {
            ring.color = ui.s.primary
            c.drawCircle(width / 2f, height / 2f, width / 2f - ring.strokeWidth / 2, ring)
            val s = ui.dp(18)
            val cx = width / 2; val cy = height / 2 - ui.dp(6)
            paint.color = scheme.primary
            check.setBounds(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
            check.draw(c)
        }
    }
}
