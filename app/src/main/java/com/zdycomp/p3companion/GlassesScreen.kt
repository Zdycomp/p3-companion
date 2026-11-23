package com.zdycomp.p3companion

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference

/** Live control of the glasses: connect, battery, take photos, start and stop video, record audio, pair. */
@SuppressLint("MissingPermission")
class GlassesScreen(private val a: MainActivity) : Screen {
    override val label = "Glasses"

    private val listener = XkGlasses.Listener { if (visible && countdown == 0) a.refresh() }
    private val recorderListener: () -> Unit = { if (visible) a.refresh() }
    private var visible = false
    private val main = Handler(Looper.getMainLooper())

    // Voice recorder clock
    private var clockView: TextView? = null
    private val tick = object : Runnable {
        override fun run() {
            val rec = a.recorder
            clockView?.text = VoiceRecorder.clock(rec.elapsedMs())
            if (visible && rec.recording) main.postDelayed(this, 500)
        }
    }

    // Capture controls
    private var shutterRef: WeakReference<ShutterButton>? = null
    private var timerLine: TextView? = null
    private var countdown = 0
    private var beeper: ToneGenerator? = null
    private var thumbUri: Uri? = null
    private var thumbBmp: Bitmap? = null
    private var tipsOpen = false

    // Pairing new glasses
    private data class Found(val address: String, val name: String?, val bonded: Boolean)
    private val found = LinkedHashMap<String, Found>()
    private var scanning = false
    private var bondingAddress: String? = null
    private var pairStatus: String? = null
    private var justPaired: XkGlasses.Paired? = null
    private var pairReceiver: BroadcastReceiver? = null
    private var pairBox: LinearLayout? = null

    override fun build(): View {
        val ui = a.ui
        val g = a.glasses
        visible = true
        g.removeListener(listener)
        g.addListener(listener)
        a.recorder.removeListener(recorderListener)
        a.recorder.addListener(recorderListener)

        val col = ui.page()
        val hasKey = a.prefs.userKey != null
        val connected = g.state == XkGlasses.State.READY || g.state == XkGlasses.State.BUSY

        col.addView(hero())

        g.lastError?.let { err ->
            col.addView(ui.banner(err, Tone.BAD, "Details") { a.showDiagnostics() })
            if (err.contains("LensMoo") && g.state == XkGlasses.State.IDLE) {
                col.addView(ui.button("Open LensMoo's app info", Btn.OUTLINED, R.drawable.ic_open_in_new) {
                    runCatching {
                        a.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:com.lensmoo.app")))
                    }.onFailure { a.toast("LensMoo isn't installed.") }
                }.apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(12) })
            }
        }
        g.lastMessage?.takeIf { g.lastError == null }?.let { col.addView(ui.banner(it, Tone.GOOD)) }

        if (connected && !g.signedIn && g.keylessReport.isNotEmpty()) {
            val k = ui.outlinedCard()
            k.addView(ui.heading("Without your key the glasses answered", R.drawable.ic_info))
            k.addView(ui.body(g.keylessReport.joinToString("\n") { "• $it" }, muted = true, sizeSp = 13f))
            col.addView(k)
        }

        // Key setup sits right under the status: before connecting, or once connected without a key.
        if (!hasKey) col.addView(setupCard())
        else if (connected && !g.signedIn) {
            val sign = ui.card().apply { background = ui.rounded(ui.s.primaryContainer, 16) }
            sign.addView(ui.text("Key saved", Type.TITLE_MEDIUM, ui.s.onPrimaryContainer))
            sign.addView(ui.text("Sign in on this connection to unlock photos and live view.", Type.BODY_MEDIUM, ui.s.onPrimaryContainer))
            sign.addView(ui.button("Sign in now", Btn.FILLED, R.drawable.ic_lock) { g.signIn() })
            col.addView(sign)
        }

        if (connected) col.addView(captureCard())
        else if (g.state == XkGlasses.State.IDLE) col.addView(connectCard(hasKey))

        col.addView(recorderCard())

        // Pick the glasses from paired devices, or pair new ones
        if (g.state == XkGlasses.State.IDLE) {
            col.addView(deviceCard())
            col.addView(pairCard())
        }

        if (hasKey) col.addView(keyCard())
        col.addView(tipsCard())
        if (a.prefs.showLog) col.addView(logCard())
        col.addView(ui.button("Scan with SupRevolt", Btn.TONAL, R.drawable.ic_shield_scan) { a.showSupRevolt() })
        col.addView(ui.button("Diagnostics and connection log", Btn.TONAL, R.drawable.ic_build) { a.showDiagnostics() })

        val actions = buildList {
            if (connected) add(BarAction(R.drawable.ic_refresh, "Refresh battery and counts") { g.refresh(); a.toast("Refreshing…") })
            add(BarAction(R.drawable.ic_build, "Diagnostics") { a.showDiagnostics() })
        }
        return ui.screen("Glasses", col, actions = actions)
    }

    // ---------------- hero ----------------

    private fun hero(): LinearLayout {
        val ui = a.ui
        val s = ui.s
        val g = a.glasses
        val fg = s.onPrimaryContainer
        val card = ui.column().apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(s.primaryContainer, Tonal.mix(s.primaryContainer, s.tertiaryContainer, 0.6)),
            ).apply { cornerRadius = ui.dpf(24f) }
            setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(20))
            layoutParams = LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(4); bottomMargin = ui.dp(12) }
        }
        val narrow = a.resources.configuration.screenWidthDp < 360 || a.resources.configuration.fontScale >= 1.5f
        val top = if (narrow) ui.column() else ui.row()

        val ring = RingView(a, ui, Ui.withAlpha(fg, 41), fg)
        ring.set(if (g.state == XkGlasses.State.IDLE) null else g.battery, g.charging == true)
        top.addView(ring, LinearLayout.LayoutParams(ui.dp(96), ui.dp(96)).apply {
            if (narrow) bottomMargin = ui.dp(12) else marginEnd = ui.dp(20)
        })

        val info = ui.column()
        val name = g.deviceName ?: a.prefs.glassesName ?: "Your glasses"
        info.addView(ui.text(name, Type.HEADLINE_SMALL, fg).apply { maxLines = 2; isAccessibilityHeading = true })
        g.firmware?.let { fw ->
            info.addView(ui.text("Firmware $fw" + (g.model?.let { " · $it" } ?: ""), Type.BODY_SMALL, fg).apply { alpha = 0.7f })
        }
        val (label, tone) = when (g.state) {
            XkGlasses.State.READY -> (if (g.signedIn) "Connected" else "Connected, not signed in") to (if (g.signedIn) Tone.GOOD else Tone.WARN)
            XkGlasses.State.BUSY -> (g.busyText ?: "Working…") to Tone.BUSY
            XkGlasses.State.CONNECTING -> "Connecting…" to Tone.WARN
            XkGlasses.State.IDLE -> (if (g.pausedForBackground) "Paused" else "Not connected") to Tone.NEUTRAL
        }
        info.addView(ui.statusChip(label, tone, pulse = g.state == XkGlasses.State.CONNECTING).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(10)
        })
        if (g.state == XkGlasses.State.READY || g.state == XkGlasses.State.BUSY) {
            val link = ui.row().apply { setPadding(0, ui.dp(8), 0, 0) }
            link.addView(ui.icon(R.drawable.ic_bluetooth, fg, 16), LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)).apply { marginEnd = ui.dp(6) })
            link.addView(ui.text(
                "Bluetooth link" + (if (g.charging == true) " · charging" else "") + (if (g.recording) " · recording" else ""),
                Type.BODY_SMALL, fg,
            ))
            info.addView(link)
        }
        top.addView(info, LinearLayout.LayoutParams(if (narrow) Ui.MATCH else 0, Ui.WRAP, if (narrow) 0f else 1f))
        card.addView(top)

        if (g.photoCount != null || g.videoCount != null || g.audioCount != null) {
            val tiles = ui.row().apply { setPadding(0, ui.dp(16), 0, 0) }
            listOf(
                Triple(R.drawable.ic_photo, g.photoCount, "photos"),
                Triple(R.drawable.ic_videocam, g.videoCount, "videos"),
                Triple(R.drawable.ic_mic, g.audioCount, "recordings"),
            ).forEachIndexed { i, (icon, n, what) ->
                val tile = ui.column().apply {
                    background = ui.rounded(Ui.withAlpha(fg, 20), 12)
                    setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
                    isScreenReaderFocusable = true
                    contentDescription = "${n ?: "unknown"} $what on the glasses"
                }
                tile.addView(ui.icon(icon, fg, 20))
                tile.addView(ui.text(n?.toString() ?: "—", Type.TITLE_MEDIUM, fg).apply { fontFeatureSettings = "tnum" })
                tile.addView(ui.text(what, Type.BODY_SMALL, fg).apply { maxLines = 1 })
                tiles.addView(tile, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { if (i > 0) marginStart = ui.dp(8) })
            }
            card.addView(tiles)
        }

        when (g.state) {
            XkGlasses.State.CONNECTING -> card.addView(ui.button("Cancel", Btn.TONAL, R.drawable.ic_close) { g.disconnect() })
            XkGlasses.State.READY, XkGlasses.State.BUSY -> {}
            XkGlasses.State.IDLE -> {}
        }
        return card
    }

    // ---------------- capture ----------------

    private fun captureCard(): LinearLayout {
        val ui = a.ui
        val s = ui.s
        val g = a.glasses
        val ready = g.state == XkGlasses.State.READY
        val card = ui.card()
        card.addView(ui.heading("Capture", R.drawable.ic_photo_camera))

        val controls = ui.row().apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(8), 0, ui.dp(8)) }
        // Last photo thumbnail
        val thumb = ImageView(a).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ui.rounded(s.surfaceContainerHighest, 12)
            clipToOutline = true
            contentDescription = "Open last photo"
            val last = g.lastPhoto
            if (last == null) {
                setImageDrawable(ui.drawable(R.drawable.ic_photo, s.onSurfaceVariant))
                scaleType = ImageView.ScaleType.CENTER
                isEnabled = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            } else {
                loadThumb(last, this)
                setOnClickListener { a.openMedia(last, "image/jpeg") }
                ui.pressable(this)
            }
        }
        controls.addView(thumb, LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)))

        val shutter = ShutterButton(a, ui, s.onSurface, s.primary).apply {
            isEnabled = ready || this@GlassesScreen.countdown > 0
            contentDescription = if (this@GlassesScreen.countdown > 0) "Cancel self-timer" else "Take photo"
            countdown = this@GlassesScreen.countdown.takeIf { it > 0 }
            setOnClickListener { shutterPressed() }
        }
        shutterRef = WeakReference(shutter)
        controls.addView(shutter, LinearLayout.LayoutParams(ui.dp(80), ui.dp(80)).apply {
            marginStart = ui.dp(28); marginEnd = ui.dp(28)
        })

        val record = RecordButton(a, ui, s.outline).apply {
            recording = g.recording
            isEnabled = ready
            contentDescription = if (g.recording) "Stop recording on the glasses" else "Record video on the glasses"
            setOnClickListener {
                ui.haptic(this, if (g.recording) Haptic.END else Haptic.START)
                g.setRecording(!g.recording)
            }
        }
        controls.addView(record, LinearLayout.LayoutParams(ui.dp(64), ui.dp(64)).apply { marginStart = ui.dp(0) })
        card.addView(controls)

        if (g.recording) {
            card.addView(ui.statusChip("Recording on the glasses", Tone.BAD, pulse = true).apply {
                (layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL
            })
        }
        timerLine = ui.text(timerText(), Type.BODY_MEDIUM, s.onSurfaceVariant).apply {
            gravity = Gravity.CENTER
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(0, ui.dp(4), 0, ui.dp(4))
        }
        card.addView(timerLine)

        val steps = Prefs.TIMER_STEPS
        card.addView(ui.choiceRow("Self-timer", steps.map { if (it == 0) "Off" else "$it s" }, steps.indexOf(a.prefs.selfTimer).coerceAtLeast(0)) {
            a.prefs.selfTimer = steps[it]
            timerLine?.text = timerText()
        })

        card.addView(ui.button("Open live view", Btn.FILLED, R.drawable.ic_videocam) { a.showLiveView() }.apply { isEnabled = ready })
        card.addView(ui.buttonRow(
            ui.button("Refresh", Btn.TONAL, R.drawable.ic_refresh, full = false) { g.refresh() },
            ui.button("Disconnect", Btn.OUTLINED, R.drawable.ic_close, full = false) { g.disconnect() },
        ))
        return card
    }

    private fun timerText(): String = when {
        countdown > 0 -> "Taking the photo in $countdown…"
        a.glasses.state == XkGlasses.State.BUSY -> a.glasses.busyText ?: "Working…"
        a.prefs.selfTimer > 0 -> "Self-timer: ${a.prefs.selfTimer} seconds"
        else -> "Tap the shutter to take a photo with the glasses"
    }

    private fun shutterPressed() {
        val g = a.glasses
        if (countdown > 0) { cancelTimer("Self-timer cancelled."); return }
        if (g.state != XkGlasses.State.READY) return
        val secs = a.prefs.selfTimer
        if (secs <= 0) { g.takePhoto(); return }
        countdown = secs
        beeper = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull()
        showCount()
        main.postDelayed(countTick, 1_000)
    }

    private fun showCount() {
        beeper?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        shutterRef?.get()?.apply {
            countdown = this@GlassesScreen.countdown.takeIf { it > 0 }
            contentDescription = if (this@GlassesScreen.countdown > 0) "Cancel self-timer" else "Take photo"
        }
        timerLine?.text = timerText()
    }

    private val countTick = object : Runnable {
        override fun run() {
            if (countdown <= 0) return
            val g = a.glasses
            if (g.state != XkGlasses.State.READY) { cancelTimer("The glasses aren't ready, so the self-timer stopped."); return }
            countdown--
            if (countdown == 0) {
                releaseBeeper()
                g.takePhoto()
                if (visible) a.refresh()
                return
            }
            showCount()
            main.postDelayed(this, 1_000)
        }
    }

    private fun cancelTimer(msg: String) {
        main.removeCallbacks(countTick)
        countdown = 0
        releaseBeeper()
        a.toast(msg)
        if (visible) a.refresh()
    }

    private fun releaseBeeper() { beeper?.release(); beeper = null }

    /** A photo just arrived (from [MainActivity.onPhotoSaved]). */
    fun photoArrived() { if (visible) shutterRef?.get()?.flashSuccess() }

    private fun loadThumb(uri: Uri, into: ImageView) {
        if (uri == thumbUri && thumbBmp != null) { into.setImageBitmap(thumbBmp); return }
        Thread {
            val bmp = ImagePrep.thumbnail(a.contentResolver, uri, 160)
            main.post {
                thumbUri = uri
                thumbBmp = bmp
                if (bmp != null) into.setImageBitmap(bmp)
            }
        }.start()
    }

    // ---------------- not connected ----------------

    private fun connectCard(hasKey: Boolean): LinearLayout {
        val ui = a.ui
        val g = a.glasses
        val card = ui.card()
        val saved = a.prefs.glassesAddress
        if (saved != null) {
            card.addView(ui.emptyState(
                R.drawable.ic_bluetooth,
                if (g.pausedForBackground) "Paused" else "Not connected",
                if (hasKey) "Your glasses connect over Bluetooth. Close LensMoo first if it's installed: the glasses talk to one app at a time."
                else "You can connect before adding your key: the app checks what the glasses answer, then helps you finish setup.",
                "Connect",
            ) { a.withBluetooth { g.connect(saved, a.prefs.glassesName) } }.apply { setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)) })
        } else {
            card.addView(ui.emptyState(
                R.drawable.ic_glasses_tab,
                "Pair your glasses",
                "Turn the glasses on and keep them close, or pick them below if they're already paired.",
                "Pair new glasses",
            ) { a.withScan { startScan() } }.apply { setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)) })
        }
        return card
    }

    private fun deviceCard(): LinearLayout {
        val ui = a.ui
        val card = ui.card()
        card.addView(ui.heading("Your glasses", R.drawable.ic_bluetooth))
        if (!a.hasBluetoothPermission()) {
            card.addView(ui.body("Allow Bluetooth so the app can see your paired glasses.", muted = true, sizeSp = 14f))
            card.addView(ui.button("Allow Bluetooth", Btn.FILLED) { a.withBluetooth { a.refresh() } })
            return card
        }
        val paired = a.glasses.pairedDevices()
        if (paired.isEmpty()) {
            card.addView(ui.body(
                "No paired devices yet. Use Pair new glasses below, or pair them in your phone's Bluetooth settings.",
                muted = true, sizeSp = 14f,
            ))
        } else {
            card.addView(ui.body("Tap your glasses. Likely matches are at the top.", muted = true, sizeSp = 13f))
            val list = ui.column().apply { setPadding(0, ui.dp(4), 0, 0) }
            paired.take(12).forEach { d ->
                val chosen = d.address == a.prefs.glassesAddress
                val likely = XkGlasses.looksLikeGlasses(d.name)
                val trailing = when {
                    chosen -> ui.icon(R.drawable.ic_check, ui.s.primary)
                    likely -> ui.statusChip("Likely", Tone.INFO)
                    else -> null
                }
                list.addView(ui.listRow(d.name, if (chosen) "Saved as your glasses" else null,
                    if (likely) R.drawable.ic_glasses_tab else R.drawable.ic_bluetooth, trailing) { pick(d) }.apply {
                    setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
                })
            }
            card.addView(list)
        }
        card.addView(ui.button("Open Bluetooth settings", Btn.TEXT, R.drawable.ic_open_in_new, full = false) {
            runCatching { a.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }
        })
        return card
    }

    private fun pick(d: XkGlasses.Paired) {
        a.prefs.glassesAddress = d.address
        a.prefs.glassesName = d.name
        a.withBluetooth { a.glasses.connect(d.address, d.name) }
    }

    /** Finish setup: get the owner's own key from their own phone's Bluetooth log. Short and prominent. */
    private fun setupCard(): LinearLayout {
        val ui = a.ui
        val s = ui.s
        val card = ui.card().apply { background = ui.rounded(s.surfaceContainerLow, 16, s.primary, 2) }
        card.addView(ui.heading("Finish setup: add your glasses key", R.drawable.ic_lock))
        card.addView(ui.body(
            "The glasses take photos and video only from an app signed in with your LensMoo account ID. " +
                "Get it once from your own phone (nothing is uploaded):",
            muted = true, sizeSp = 13f,
        ))
        val steps = listOf(
            "Developer options › Bluetooth HCI snoop log › Enabled (not Filtered).",
            "Turn Bluetooth off and on. Open LensMoo and connect the glasses.",
            "Developer options › Take bug report › Interactive. When it's ready, tap the notification › Share › P3 Companion.",
            "Turn the snoop log off again.",
        )
        steps.forEachIndexed { i, step ->
            card.addView(ui.row().apply {
                gravity = Gravity.TOP
                setPadding(0, ui.dp(10), 0, 0)
                addView(ui.text("${i + 1}", Type.LABEL_LARGE, s.onPrimary).apply {
                    gravity = Gravity.CENTER
                    background = ui.oval(s.primary)
                }, LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)).apply { marginEnd = ui.dp(12) })
                addView(ui.body(step, sizeSp = 14f), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            })
        }
        card.addView(ui.button("Open Developer options", Btn.FILLED, R.drawable.ic_settings) {
            runCatching { a.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                .onFailure {
                    a.toast("First unlock them: Settings › About phone › Software information › tap Build number 7 times.")
                }
        })
        card.addView(ui.buttonRow(
            ui.button("Pick a saved bug report", Btn.TONAL, R.drawable.ic_upload, full = false) { a.pickKeyFile() },
            ui.button("I have the key", Btn.OUTLINED, R.drawable.ic_edit, full = false) { enterKeyDialog() },
        ))
        card.addView(ui.body(
            "No Developer options? Settings › About phone › Software information › tap Build number 7 times.",
            muted = true, sizeSp = 12f,
        ).apply { setPadding(0, ui.dp(8), 0, 0) })
        return card
    }

    private fun keyCard(): LinearLayout {
        val ui = a.ui
        val card = ui.card()
        card.addView(ui.heading("Glasses key", R.drawable.ic_lock))
        card.addView(ui.body("Saved: " + a.prefs.maskForDisplay(a.prefs.userKey ?: ""), muted = true, sizeSp = 14f))
        card.addView(ui.buttonRow(
            ui.button("Replace", Btn.TONAL, R.drawable.ic_edit, full = false) { enterKeyDialog() },
            ui.button("From a bug report", Btn.TONAL, R.drawable.ic_upload, full = false) { a.pickKeyFile() },
            ui.button("Remove", Btn.DANGER, R.drawable.ic_delete, full = false) {
                a.confirm("Remove key?", "You'll need to set it up again to control the glasses.", "Remove") {
                    a.glasses.disconnect()
                    a.prefs.userKey = null
                    a.refresh()
                }
            },
        ))
        return card
    }

    private fun enterKeyDialog() {
        val ui = a.ui
        val input = ui.input("", "32 letters and numbers") {}.apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        ui.dialog(
            "Glasses key",
            "Paste your LensMoo account ID (32 characters, 0-9 and a-f).",
            icon = R.drawable.ic_lock,
            view = input,
            confirm = "Save",
            dismiss = "Cancel",
        ) {
            val k = input.text.toString().trim().lowercase()
            if (Xk.isValidUserId(k)) {
                a.prefs.userKey = k
                a.toast("Key saved.")
                a.onKeySaved()
                true
            } else {
                input.error = "That isn't 32 characters of 0-9 and a-f."
                ui.haptic(input, Haptic.REJECT)
                false
            }
        }
    }

    private fun tipsCard(): LinearLayout {
        val ui = a.ui
        val card = ui.outlinedCard()
        val head = ui.row().apply {
            isClickable = true
            minimumHeight = ui.dp(40)
            addView(ui.icon(R.drawable.ic_info, ui.s.primary, 20), LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)).apply { marginEnd = ui.dp(10) })
            addView(ui.text("Good to know", Type.TITLE_MEDIUM).apply { isAccessibilityHeading = true }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            addView(ui.icon(if (tipsOpen) R.drawable.ic_expand_less else R.drawable.ic_expand_more, ui.s.onSurfaceVariant))
            stateDescription = if (tipsOpen) "Expanded" else "Collapsed"
            setOnClickListener { tipsOpen = !tipsOpen; a.refresh() }
        }
        card.addView(head)
        if (tipsOpen) {
            card.addView(ui.body(
                "• Photos taken here are copied to your phone right away, in the \"${a.prefs.albumName}\" album.\n" +
                    "• Photos you take with the button on the glasses are saved too while connected" +
                    (if (a.prefs.saveButtonPhotos) "." else " (turned off in Settings).") + "\n" +
                    (if (a.prefs.keepConnectedInBackground)
                        "• While connected, a notification keeps the link up even with the app closed, so button photos still arrive.\n"
                    else "• The link closes when you leave the app (Settings › Glasses), so button photos arrive only while it's open.\n") +
                    "• Live view streams the camera to the phone and can record it to Movies › ${a.prefs.albumName} (video only: " +
                    "the glasses can't send sound at the same time).\n" +
                    "• Bluetooth photos are the size the glasses make them (often 640×480).\n" +
                    "• Add the Glasses photo tile to Quick Settings, or long-press the app icon, for one-tap photos.",
                muted = true, sizeSp = 13f,
            ).apply { setPadding(0, ui.dp(8), 0, 0) })
        }
        return card
    }

    private fun logCard(): LinearLayout {
        val ui = a.ui
        val g = a.glasses
        val card = ui.card()
        card.addView(ui.heading("Connection log", R.drawable.ic_history))
        val lines = g.logLines(60)
        card.addView(ui.mono(if (lines.isEmpty()) "Nothing yet." else lines.joinToString("\n")).apply {
            background = ui.rounded(ui.s.surfaceContainerLowest, 12)
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
        })
        card.addView(ui.buttonRow(
            ui.button("Share", Btn.TEXT, R.drawable.ic_share, full = false) { a.shareText("P3 glasses log", DiagnosticsScreen.redact(g.logText())) },
            ui.button("Copy", Btn.TEXT, R.drawable.ic_copy, full = false) {
                a.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("P3 log", DiagnosticsScreen.redact(g.logText())))
                a.toast("Copied.")
            },
        ))
        return card
    }

    // ---------------- voice recorder ----------------

    private fun recorderCard(): LinearLayout {
        val ui = a.ui
        val s = ui.s
        val rec = a.recorder
        val card = ui.card()
        card.addView(ui.heading("Voice recorder", R.drawable.ic_mic))
        when {
            rec.recording -> {
                val line = ui.row()
                val clock = ui.text(VoiceRecorder.clock(rec.elapsedMs()), Type.HEADLINE_SMALL, s.error).apply {
                    fontFeatureSettings = "tnum"
                    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
                }
                clockView = clock
                line.addView(ui.statusChip("Recording", Tone.BAD, pulse = true), LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { marginEnd = ui.dp(12) })
                line.addView(clock)
                card.addView(line)
                card.addView(ui.body(
                    if (rec.throughGlasses) "Using the glasses' microphone." else "Using the phone's microphone.",
                    muted = true, sizeSp = 13f,
                ).apply { setPadding(0, ui.dp(8), 0, 0) })
                card.addView(ui.button("Stop and save", Btn.DANGER, R.drawable.ic_stop) { ui.haptic(card, Haptic.END); rec.stop() })
                main.removeCallbacks(tick)
                main.postDelayed(tick, 500)
            }
            rec.starting -> card.addView(ui.statusChip("Getting the microphone ready…", Tone.BUSY))
            else -> {
                clockView = null
                val viaGlasses = a.assistant.route.willUseGlasses()
                val row = ui.row()
                val mic = ui.iconButton(R.drawable.ic_mic, "Record audio", IconBtn.TONAL) {
                    a.withMic(forRecording = true) { rec.start() }
                }.apply { layoutParams = LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)).apply { marginEnd = ui.dp(12) }; setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14)) }
                row.addView(mic)
                row.addView(ui.body(
                    (if (viaGlasses) "Records with the glasses' microphone (they're connected as your headset)."
                    else "Records with the phone's microphone. Connect the glasses as a Bluetooth headset to use theirs.") +
                        " Saved to Music › ${a.prefs.albumName}.",
                    muted = true, sizeSp = 13f,
                ), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                card.addView(row)
            }
        }
        rec.error?.let { card.addView(ui.banner(it, Tone.BAD).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(8) }) }
        val saved = rec.lastSaved
        if (saved != null && !rec.recording) {
            card.addView(ui.button("Play ${rec.lastSavedName ?: "last recording"}", Btn.TONAL, R.drawable.ic_play) {
                runCatching {
                    a.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(saved, "audio/mp4")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }.onFailure { a.toast("No app on this phone can play it.") }
            })
        }
        return card
    }

    // ---------------- pairing ----------------

    private fun adapter(): BluetoothAdapter? = a.getSystemService(BluetoothManager::class.java)?.adapter

    private fun pairCard(): LinearLayout {
        val ui = a.ui
        val card = ui.card()
        card.addView(ui.heading("Pair new glasses", R.drawable.ic_add))
        card.addView(ui.body(
            "Turn the glasses on and keep them close. If they're connected to another phone, disconnect them there first.",
            muted = true, sizeSp = 13f,
        ))
        val box = ui.column()
        pairBox = box
        card.addView(box)
        renderPair()
        return card
    }

    private fun renderPair() {
        val box = pairBox ?: return
        val ui = a.ui
        box.removeAllViews()
        if (scanning) {
            box.addView(ui.statusChip("Searching for nearby devices…", Tone.BUSY).apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(12)
            })
            box.addView(ui.button("Stop searching", Btn.TONAL, R.drawable.ic_stop) { stopScan() })
        } else {
            box.addView(ui.button(if (found.isEmpty()) "Search for glasses" else "Search again", Btn.FILLED, R.drawable.ic_bluetooth) {
                a.withScan { startScan() }
            })
        }
        pairStatus?.let { box.addView(ui.body(it, sizeSp = 14f).apply { setPadding(0, ui.dp(8), 0, 0) }) }

        justPaired?.let { d ->
            if (a.glasses.state == XkGlasses.State.IDLE) {
                if (a.prefs.userKey != null) {
                    box.addView(ui.button("Connect to ${d.name}", Btn.FILLED, R.drawable.ic_link) { a.withBluetooth { a.glasses.connect(d.address, d.name) } })
                } else {
                    box.addView(ui.body("Next, add your glasses key above, then connect.", muted = true, sizeSp = 13f))
                }
            }
        }

        val list = found.values.sortedWith(
            compareByDescending<Found> { XkGlasses.strongMatch(it.name, it.address) }
                .thenByDescending { XkGlasses.looksLikeGlasses(it.name ?: "") }
                .thenByDescending { it.name != null }
        ).take(20)
        if (list.isNotEmpty()) {
            box.addView(ui.body("Tap your glasses. Likely matches are at the top.", muted = true, sizeSp = 13f).apply {
                setPadding(0, ui.dp(10), 0, 0)
            })
        }
        list.forEach { f ->
            val name = f.name ?: "Unnamed device"
            val sub = when {
                f.address == bondingAddress -> "Pairing…"
                f.bonded -> "Paired"
                f.name == null -> XkGlasses.maskAddress(f.address)
                else -> null
            }
            val likely = XkGlasses.looksLikeGlasses(f.name ?: "")
            val trailing = when {
                f.address == bondingAddress -> ui.spinner(20)
                likely -> ui.statusChip("Likely", Tone.INFO)
                else -> null
            }
            box.addView(ui.listRow(name, sub, if (likely) R.drawable.ic_glasses_tab else R.drawable.ic_bluetooth, trailing) { pairWith(f) }.apply {
                setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
            })
        }
    }

    private fun startScan() {
        val ad = adapter() ?: run { a.toast("This phone has no Bluetooth."); return }
        registerPairReceiver()
        runCatching { ad.cancelDiscovery() }
        found.clear()
        justPaired = null
        val ok = runCatching { ad.startDiscovery() }.getOrDefault(false)
        scanning = ok
        pairStatus = if (ok) null else "Couldn't start searching. Turn Bluetooth off and on, then try again."
        if (!ok) unregisterPairReceiverIfIdle()
        renderPair()
    }

    private fun stopScan() {
        runCatching { adapter()?.cancelDiscovery() }
        scanning = false
        unregisterPairReceiverIfIdle()
        renderPair()
    }

    private fun pairWith(f: Found) {
        val ad = adapter() ?: return
        runCatching { ad.cancelDiscovery() }
        scanning = false
        val device = runCatching { ad.getRemoteDevice(f.address) }.getOrNull() ?: return
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            paired(f.address, f.name ?: device.name ?: f.address)
            return
        }
        registerPairReceiver()
        val ok = runCatching { device.createBond() }.getOrDefault(false)
        if (ok) {
            bondingAddress = f.address
            pairStatus = "Pairing with ${f.name ?: f.address}… Accept the request on your phone (and on the glasses) if one appears."
        } else {
            pairStatus = "Couldn't start pairing with ${f.name ?: f.address}. Try again, or pair it in Bluetooth settings."
            unregisterPairReceiverIfIdle()
        }
        renderPair()
    }

    private fun paired(address: String, name: String) {
        bondingAddress = null
        a.prefs.glassesAddress = address
        a.prefs.glassesName = name
        justPaired = XkGlasses.Paired(address, name)
        found[address] = Found(address, name, true)
        pairStatus = "Paired with $name and saved as your glasses."
        unregisterPairReceiverIfIdle()
        if (visible) a.refresh() else renderPair()
    }

    private fun registerPairReceiver() {
        if (pairReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                when (intent.action) {
                    BluetoothAdapter.ACTION_DISCOVERY_STARTED -> scanning = true
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        scanning = false
                        if (bondingAddress == null && justPaired == null) {
                            pairStatus = if (found.isEmpty()) "Nothing found. Make sure the glasses are on and close, then search again."
                            else "Search finished."
                        }
                        unregisterPairReceiverIfIdle()
                    }
                    BluetoothDevice.ACTION_FOUND, BluetoothDevice.ACTION_NAME_CHANGED -> {
                        val d = device ?: return
                        if (d.type == BluetoothDevice.DEVICE_TYPE_LE) return // the glasses' control link is Classic
                        val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: runCatching { d.name }.getOrNull()
                        val old = found[d.address]
                        found[d.address] = Found(d.address, name ?: old?.name, d.bondState == BluetoothDevice.BOND_BONDED)
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val d = device ?: return
                        if (d.address != bondingAddress) return
                        when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                            BluetoothDevice.BOND_BONDED -> {
                                paired(d.address, found[d.address]?.name ?: runCatching { d.name }.getOrNull() ?: d.address)
                                return
                            }
                            BluetoothDevice.BOND_NONE -> {
                                bondingAddress = null
                                pairStatus = "Pairing didn't finish. Check the glasses are in pairing mode and try again."
                                unregisterPairReceiverIfIdle()
                            }
                        }
                    }
                }
                renderPair()
            }
        }
        pairReceiver = r
        AudioRoute.registerSystemReceiver(a.applicationContext, r, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        })
    }

    private fun unregisterPairReceiverIfIdle() {
        if (scanning || bondingAddress != null) return
        pairReceiver?.let { runCatching { a.applicationContext.unregisterReceiver(it) } }
        pairReceiver = null
    }

    override fun onHidden() {
        visible = false
        a.glasses.removeListener(listener)
        a.recorder.removeListener(recorderListener)
        main.removeCallbacks(tick)
        // A refresh rebuilds the screen right away; only leaving the tab stops the self-timer.
        main.post {
            if (!visible && countdown > 0) {
                main.removeCallbacks(countTick)
                countdown = 0
                releaseBeeper()
            }
        }
        clockView = null
        pairBox = null
        timerLine = null
        shutterRef = null
    }
}
