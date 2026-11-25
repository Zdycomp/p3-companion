package com.zdycomp.p3companion

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.UUID

/**
 * Checks everything between the phone and the glasses and writes a plain report to share with
 * whoever is helping. The glasses key and the full Bluetooth address never appear in it.
 * The connection log is always available here too.
 */
@SuppressLint("MissingPermission")
class DiagnosticsScreen(private val a: MainActivity) : Screen {
    override val label = "Diagnostics"

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var report: String? = null
    private var resultsBox: LinearLayout? = null
    @Volatile private var liveReport: String? = null
    private var logView: TextView? = null
    private var visible = false

    override fun build(): View {
        val ui = a.ui
        visible = true
        val col = ui.page()

        val card = ui.card()
        card.addView(ui.body(
            "Runs every check between this phone and your glasses: permissions, pairing, the Bluetooth link, sign-in, " +
                "response times, audio and live view. If the glasses aren't connected it also tries to connect. " +
                "The report hides your glasses key and the full Bluetooth address.",
            muted = true, sizeSp = 13f,
        ))
        val runRow = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
        runRow.addView(ui.button(if (running) "Running…" else "Run the checks", Btn.FILLED, R.drawable.ic_build, full = false) { run() }
            .apply { isEnabled = !running })
        if (running) runRow.addView(ui.spinner(24), LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)).apply { marginStart = ui.dp(16) })
        card.addView(runRow, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(8) })
        card.addView(ui.button("Test without key", Btn.OUTLINED, R.drawable.ic_lock, full = false) { a.withBluetooth { testWithoutKey() } }
            .apply { isEnabled = !running })
        card.addView(ui.body(
            "Test without key reconnects without signing in and records whether battery, device info and the camera " +
                "answer anyway. It may take one photo, which isn't saved. Then it reconnects normally.",
            muted = true, sizeSp = 12f,
        ).apply { setPadding(0, ui.dp(4), 0, 0) })
        col.addView(card)

        // 3.1: the live view test and the voice test, each with its own shareable log.
        val tests = ui.card()
        tests.addView(ui.heading("Live view and voice", R.drawable.ic_videocam))
        tests.addView(ui.body(
            "Test live view tries every live view command your glasses might use and listens for video after each " +
                "(about 40 seconds), then saves the one that works. Test voice records 3 s from each mic (the phone's, the glasses' " +
                "call audio and the glasses' own data-link stream), asks you to say \"hello glasses\" with each, and uses the one that works.",
            muted = true, sizeSp = 13f,
        ))
        a.prefs.liveProbeSummary?.let { sum ->
            tests.addView(ui.text("Last live view test: $sum", Type.BODY_SMALL, ui.s.onSurfaceVariant).apply { setPadding(0, ui.dp(6), 0, 0) })
        }
        a.prefs.voiceTestSummary?.let { sum ->
            tests.addView(ui.text("Last voice test: $sum", Type.BODY_SMALL, ui.s.onSurfaceVariant).apply { setPadding(0, ui.dp(6), 0, 0) })
        }
        tests.addView(ui.buttonRow(
            ui.button("Test live view", Btn.TONAL, R.drawable.ic_videocam, full = false) { liveTest() }.apply { isEnabled = !running },
            ui.button("Test voice", Btn.TONAL, R.drawable.ic_mic, full = false) { a.withMic { voiceTest() } }.apply { isEnabled = !running },
            ui.button("Try higher quality", Btn.OUTLINED, R.drawable.ic_auto_awesome, full = false) { qualityTest() }.apply { isEnabled = !running },
        ).apply { setPadding(0, ui.dp(8), 0, 0) })
        a.prefs.liveQualityReport?.let { sum ->
            tests.addView(ui.text("Last quality try: $sum", Type.BODY_SMALL, ui.s.onSurfaceVariant).apply { setPadding(0, ui.dp(6), 0, 0) })
        }
        if (testLog != null) {
            tests.addView(ui.button("Share this test's log", Btn.TEXT, R.drawable.ic_share, full = false) {
                a.shareText("P3 Companion test log", redact(testLog ?: ""))
            })
        }
        // 3.7: hear what the glasses' mic really sent (your voice, or noise?), on the phone's speaker.
        val clips = VoiceClips.saved(a)
        if (clips.isNotEmpty() && !running) {
            tests.addView(ui.text("What the glasses heard: " + clips.joinToString(", ") { "%s, %.1f s".format(java.util.Locale.US, VoiceClips.label(it), VoiceClips.seconds(it)) },
                Type.BODY_SMALL, ui.s.onSurfaceVariant).apply { setPadding(0, ui.dp(6), 0, 0) })
            tests.addView(ui.buttonRow(
                ui.button("Play back what the glasses heard", Btn.TONAL, R.drawable.ic_play, full = false) {
                    VoiceClips.play(a, VoiceClips.saved(a), onEach = { f -> a.toast("Playing the ${VoiceClips.label(f)} on the phone's speaker") }) { err ->
                        if (err != null) a.toast("Couldn't play it: $err")
                    }
                },
                ui.button("Share", Btn.TEXT, R.drawable.ic_share, full = false) {
                    if (!VoiceClips.share(a)) a.toast("Nothing recorded yet: run Test voice first.")
                },
            ).apply { setPadding(0, ui.dp(4), 0, 0) })
        }
        col.addView(tests)

        // 3.8: what size the Bluetooth photos really are, and whether the glasses hold a bigger copy.
        val full = ui.card()
        full.addView(ui.heading("Full-size photos", R.drawable.ic_photo))
        full.addView(ui.body(
            "Find full-size photos takes one photo, measures it, and asks the glasses (about 90 s) whether they keep a larger " +
                "copy anywhere: their photo album commands, a part past the last one, and Wi-Fi. It never deletes anything " +
                "and never sends the glasses' update or delete commands.",
            muted = true, sizeSp = 13f,
        ))
        PhotoStats.describe(a.prefs.bluetoothPhotoSizes)?.let { d ->
            full.addView(ui.text("Bluetooth photos so far: $d", Type.BODY_SMALL, ui.s.onSurfaceVariant).apply { setPadding(0, ui.dp(6), 0, 0) })
        }
        a.prefs.fullResReport?.let { sum ->
            full.addView(ui.text("Last search: $sum", Type.BODY_SMALL, ui.s.onSurfaceVariant).apply { setPadding(0, ui.dp(6), 0, 0) })
        }
        full.addView(ui.button("Find full-size photos", Btn.TONAL, R.drawable.ic_photo_camera, full = false) {
            a.confirm("Find full-size photos?", "Takes one photo and asks the glasses about stored photos. Deletes nothing.", "Start") {
                // The Wi-Fi look runs either way; without the permission it notes what it couldn't see.
                a.withNearbyWifi { _ -> fullResTest(wifi = true) }
            }
        }.apply { isEnabled = !running },
            LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { topMargin = ui.dp(8) })
        col.addView(full)

        val results = ui.card()
        results.addView(ui.heading("Results", R.drawable.ic_check_circle))
        val box = ui.column()
        resultsBox = box
        renderResults(if (running) liveReport else report ?: liveReport)
        results.addView(box)
        col.addView(results)

        val fixes = ui.card()
        fixes.addView(ui.heading("Quick fixes", R.drawable.ic_settings))
        val chips = Flow(a, ui.dp(8), 0)
        chips.addView(ui.assistChip("Run in the background", R.drawable.ic_power) { askBatteryExemption() })
        chips.addView(ui.assistChip("Bluetooth settings", R.drawable.ic_bluetooth) {
            runCatching { a.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }
        })
        chips.addView(ui.assistChip("App permissions", R.drawable.ic_lock) {
            runCatching {
                a.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", a.packageName, null)))
            }
        })
        fixes.addView(chips)
        fixes.addView(ui.body(
            "On Samsung phones also add P3 Companion to Settings › Battery › Background usage limits › Never sleeping apps.",
            muted = true, sizeSp = 13f,
        ).apply { setPadding(0, ui.dp(4), 0, 0) })
        col.addView(fixes)

        val logCard = ui.card()
        logCard.addView(ui.heading("Connection log", R.drawable.ic_history))
        val lv = ui.mono(logText()).apply { setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12)) }
        logView = lv
        logCard.addView(android.widget.ScrollView(a).apply {
            background = ui.rounded(ui.s.surfaceContainerLowest, 12)
            isNestedScrollingEnabled = true
            addView(lv)
        }, LinearLayout.LayoutParams(Ui.MATCH, ui.dp(320)))
        logCard.addView(ui.buttonRow(
            ui.button("Refresh", Btn.TEXT, R.drawable.ic_refresh, full = false) { logView?.text = logText() },
            ui.button("Share log", Btn.TEXT, R.drawable.ic_share, full = false) { a.shareText("P3 glasses log", redact(a.glasses.logText())) },
        ))
        col.addView(logCard)

        return ui.screen("Diagnostics", col, back = { a.goBack() }, actions = listOf(
            BarAction(R.drawable.ic_share, "Share report") {
                val r = report ?: run { a.toast("Run the checks first."); return@BarAction }
                a.shareText("P3 Companion diagnostics", r + "\n\n" + redact(a.glasses.logText()))
            },
            BarAction(R.drawable.ic_copy, "Copy report") {
                val r = report ?: run { a.toast("Run the checks first."); return@BarAction }
                a.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("P3 diagnostics", r))
                a.toast("Copied.")
            },
        ))
    }

    /** Report lines as rows: a status icon (PASS / WARN / FAIL) and the text. */
    private fun renderResults(text: String?) {
        val box = resultsBox ?: return
        val ui = a.ui
        val s = ui.s
        box.removeAllViews()
        if (text.isNullOrBlank()) {
            box.addView(ui.body("No report yet. Tap Run the checks.", muted = true, sizeSp = 14f))
            return
        }
        text.lines().filter { it.isNotBlank() }.forEach { line ->
            val tag = line.take(4)
            val (icon, color) = when (tag) {
                "PASS" -> R.drawable.ic_check_circle to s.success
                "WARN" -> R.drawable.ic_warning to s.warning
                "FAIL" -> R.drawable.ic_error to s.error
                "INFO", "SKIP" -> R.drawable.ic_info to s.onSurfaceVariant
                else -> null to s.onSurface
            }
            val body = if (icon != null) line.drop(4).trim() else line
            box.addView(ui.row().apply {
                gravity = Gravity.TOP
                setPadding(0, ui.dp(4), 0, ui.dp(4))
                isScreenReaderFocusable = true
                if (icon != null) {
                    addView(ui.icon(icon, color, 20), LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)).apply { marginEnd = ui.dp(12); topMargin = ui.dp(1) })
                    contentDescription = tag.lowercase() + ": " + body
                }
                addView(ui.text(body, if (icon == null) Type.TITLE_SMALL else Type.BODY_MEDIUM).apply { setTextIsSelectable(false) },
                    LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            })
        }
    }

    private fun logText(): String {
        val lines = a.glasses.logLines(80)
        return if (lines.isEmpty()) "Nothing yet." else redact(lines.joinToString("\n"))
    }

    private fun askBatteryExemption() {
        val pm = a.getSystemService(PowerManager::class.java)
        if (pm?.isIgnoringBatteryOptimizations(a.packageName) == true) { a.toast("Already allowed."); return }
        runCatching {
            @SuppressLint("BatteryLife")
            val i = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${a.packageName}"))
            a.startActivity(i)
        }.onFailure {
            runCatching { a.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun run() {
        if (running) return
        running = true
        liveReport = null
        a.refresh()
        val sb = StringBuilder()
        fun line(s: String) {
            sb.appendLine(s)
            val text = sb.toString()
            liveReport = text
            main.post { if (visible) renderResults(text) }
        }
        Thread {
            try { checks(::line) } catch (e: Exception) { line("FAIL  checks stopped: ${e.message}") }
            report = redact(sb.toString())
            running = false
            main.post { if (visible) a.refresh() }
        }.start()
    }

    @Volatile private var testLog: String? = null

    /** Live view test: every open command in turn; the result is saved and the full log can be shared. */
    private fun liveTest() {
        if (running) return
        val g = a.glasses
        if (g.state != XkGlasses.State.READY) { a.toast("Connect the glasses first (they must not be busy)."); return }
        running = true
        liveReport = null
        testLog = null
        val sb = StringBuilder()
        fun line(s: String) {
            sb.appendLine(s)
            val text = sb.toString()
            liveReport = text
            if (visible) renderResults(text)
        }
        a.refresh()
        line("Live view test")
        line("INFO  about a minute; keep the glasses on and connected (taps on the glasses are ignored meanwhile)")
        a.withNearbyWifi { wifi -> g.runLiveProbe(onLine = { l -> line("INFO  $l") }, wifi = wifi) { res ->
            line(when {
                res.path != null -> "PASS  ${res.summary}"
                res.complete -> "WARN  ${res.summary}"
                else -> "FAIL  ${res.summary}"
            })
            line("Done.")
            val log = buildString {
                appendLine("P3 Companion live view test")
                appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, app ${a.appVersion()}")
                appendLine("Glasses: ${g.model ?: "?"}, firmware ${g.firmware ?: "?"}")
                res.log.forEach { appendLine(it) }
            }
            testLog = log
            report = redact(sb.toString())
            running = false
            if (visible) a.refresh()
        } }
    }

    /**
     * 3.6 "Try higher quality": opens live view with each candidate open data for 3 s (standard first),
     * logs the size, pictures a second and kilobits a second of each, and always closes with [00].
     */
    private fun qualityTest() {
        if (running) return
        val g = a.glasses
        if (g.state != XkGlasses.State.READY) { a.toast("Connect the glasses first (they must not be busy)."); return }
        running = true
        liveReport = null
        testLog = null
        val sb = StringBuilder()
        fun line(s: String) {
            sb.appendLine(s)
            val text = sb.toString()
            liveReport = text
            if (visible) renderResults(text)
        }
        a.refresh()
        line("Try higher quality")
        line("INFO  under a minute; live view opens and closes once per setting, 3 s each")
        g.runQualityProbe(onLine = { l -> line("INFO  $l") }) { res ->
            line(when {
                res.best != null -> "PASS  ${res.summary}"
                res.complete -> "INFO  ${res.summary}"
                else -> "FAIL  ${res.summary}"
            })
            line("Done.")
            testLog = buildString {
                appendLine("P3 Companion quality test")
                appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, app ${a.appVersion()}")
                appendLine("Glasses: ${g.model ?: "?"}, firmware ${g.firmware ?: "?"}")
                (listOfNotNull(res.standard) + res.results).forEach { appendLine(VideoQuality.line(it, "5720", a.prefs.previewAction)) }
                appendLine(res.summary)
            }
            report = redact(sb.toString())
            running = false
            if (visible) a.refresh()
        }
    }

    /** 3.8 "Find full-size photos" (docs/FULL-RES-PLAN.md): one photo, the album and transfer reads, Wi-Fi; a shareable log. */
    private fun fullResTest(wifi: Boolean) {
        if (running) return
        val g = a.glasses
        if (g.state != XkGlasses.State.READY) { a.toast("Connect the glasses first (they must not be busy)."); return }
        running = true
        liveReport = null
        testLog = null
        val sb = StringBuilder()
        fun line(s: String) {
            sb.appendLine(s)
            val text = sb.toString()
            liveReport = text
            if (visible) renderResults(text)
        }
        a.refresh()
        line("Find full-size photos")
        line("INFO  about 90 seconds; keep the glasses on and connected")
        g.runFullResProbe(onLine = { l -> line("INFO  $l") }, wifi = wifi) { res ->
            line(if (res.complete) "PASS  ${res.summary}" else "FAIL  ${res.summary}")
            line("Done.")
            testLog = buildString {
                appendLine("P3 Companion full-size photo search")
                appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, app ${a.appVersion()}")
                appendLine("Glasses: ${g.model ?: "?"}, firmware ${g.firmware ?: "?"}")
                res.log.forEach { appendLine(it) }
            }
            report = redact(sb.toString())
            running = false
            if (visible) a.refresh()
        }
    }

    /**
     * 3.5 voice test: for the phone's mic, the glasses through call audio and the glasses over the data
     * link it records 3 s (level in dBFS), then listens for "hello glasses" and logs what was heard and
     * by which speech service. Voice questions then start with the mic that worked ([VoiceTest]).
     */
    private fun voiceTest() {
        if (running) return
        running = true
        liveReport = null
        testLog = null
        val sb = StringBuilder()
        fun line(s: String) {
            sb.appendLine(s)
            val text = sb.toString()
            liveReport = text
            if (visible) renderResults(text)
        }
        a.refresh()
        VoiceTest(a, ::line) { _ ->
            testLog = buildString {
                appendLine("P3 Companion voice test")
                appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, app ${a.appVersion()}")
                append(sb)
            }
            report = sb.toString()
            running = false
            if (visible) a.refresh()
        }.run()
    }

    /** Learns whether these glasses really need the key: 1003, 1001 and 57B0 without 102E. */
    private fun testWithoutKey() {
        if (running) return
        val g = a.glasses
        val addr = a.prefs.glassesAddress ?: run { a.toast("Pick or pair your glasses first."); return }
        val name = a.prefs.glassesName
        running = true
        liveReport = null
        a.refresh()
        val sb = StringBuilder()
        fun line(s: String) {
            sb.appendLine(s)
            val text = sb.toString()
            liveReport = text
            main.post { if (visible) renderResults(text) }
        }
        Thread {
            try {
                line("Test without key")
                if (g.state != XkGlasses.State.IDLE) {
                    line("INFO  disconnecting first")
                    main.post { g.disconnect() }
                    Thread.sleep(2_000)
                }
                main.post { g.connect(addr, name, withoutKey = true, testPhoto = true) }
                Thread.sleep(1_000)
                val end = SystemClock.elapsedRealtime() + 70_000
                while (g.state == XkGlasses.State.CONNECTING && SystemClock.elapsedRealtime() < end) Thread.sleep(500)
                if (g.state == XkGlasses.State.IDLE) line("FAIL  couldn't open the link: ${g.lastError ?: "unknown"}")
                else {
                    line("PASS  link opened without the key")
                    g.keylessReport.forEach { line("INFO  $it") }
                    line("INFO  " + g.keylessSummary())
                }
                main.post { g.disconnect() }
                if (a.prefs.userKey != null) {
                    Thread.sleep(2_500)
                    line("INFO  reconnecting with your key")
                    main.post { g.connect(addr, name) }
                }
                line("Done.")
            } catch (e: Exception) {
                line("FAIL  test stopped: ${e.message}")
            }
            report = redact(sb.toString())
            running = false
            main.post { if (visible) a.refresh() }
        }.start()
    }

    private fun granted(p: String) = a.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun checks(line: (String) -> Unit) {
        val p = a.prefs
        val g = a.glasses
        line("P3 Companion diagnostics")
        val version = runCatching { a.packageManager.getPackageInfo(a.packageName, 0).versionName }.getOrNull() ?: "?"
        line("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), app $version")

        // Permissions
        val perms = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_CONNECT); add(Manifest.permission.BLUETOOTH_SCAN) }
            else add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        perms.forEach { line("${if (granted(it)) "PASS" else "FAIL"}  permission ${it.substringAfterLast('.')}") }
        val notes = a.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
        line("${if (notes) "PASS" else "WARN"}  notifications ${if (notes) "on" else "off (the background connection notice is hidden)"}")

        // Background
        val pm = a.getSystemService(PowerManager::class.java)
        val exempt = pm?.isIgnoringBatteryOptimizations(a.packageName) == true
        line("${if (exempt) "PASS" else "WARN"}  battery optimisation ${if (exempt) "off for this app" else "on (Android may stop the link in the background)"}")
        val restricted = a.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true
        line("${if (restricted) "FAIL" else "PASS"}  background use ${if (restricted) "restricted" else "allowed"}")
        line("INFO  background connection service ${if (GlassesService.running) "running" else "not running"}")
        line("INFO  settings: connect on open ${if (p.connectOnLaunch) "on" else "off"}, reconnect after a drop ${if (p.autoReconnect) "on" else "off"}, " +
            "keep connected in the background ${if (p.keepConnectedInBackground) "on" else "off"}" + if (g.pausedForBackground) " (paused now)" else "")

        // Bluetooth
        val adapter = a.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) { line("FAIL  this phone has no Bluetooth"); return }
        line("${if (adapter.isEnabled) "PASS" else "FAIL"}  Bluetooth ${if (adapter.isEnabled) "on" else "off"}")
        if (!adapter.isEnabled || (Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_CONNECT))) {
            line("SKIP  the rest needs Bluetooth on and the Nearby devices permission"); return
        }
        if (Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_SCAN)) {
            if (runCatching { adapter.isDiscovering }.getOrDefault(false)) line("WARN  the phone is searching for devices (slows connecting)")
        }
        val hfp = runCatching { adapter.getProfileConnectionState(BluetoothProfile.HEADSET) }.getOrDefault(-1)
        val a2dp = runCatching { adapter.getProfileConnectionState(BluetoothProfile.A2DP) }.getOrDefault(-1)
        line("INFO  headset profile ${profileState(hfp)}, media audio profile ${profileState(a2dp)}")

        // Saved glasses
        val addr = p.glassesAddress
        if (addr == null) {
            line("FAIL  no glasses chosen yet (Glasses tab › Your glasses or Pair new glasses)")
        } else {
            val d = runCatching { adapter.getRemoteDevice(addr) }.getOrNull()
            if (d == null) line("FAIL  saved address isn't valid")
            else {
                val bond = when (d.bondState) {
                    BluetoothDevice.BOND_BONDED -> "paired"
                    BluetoothDevice.BOND_BONDING -> "pairing"
                    else -> "NOT paired"
                }
                val type = when (d.type) {
                    BluetoothDevice.DEVICE_TYPE_CLASSIC -> "classic"
                    BluetoothDevice.DEVICE_TYPE_DUAL -> "dual"
                    BluetoothDevice.DEVICE_TYPE_LE -> "low-energy only (can't carry the link)"
                    else -> "unknown"
                }
                val spp = d.uuids?.any { it == ParcelUuid(SPP) }
                line("${if (d.bondState == BluetoothDevice.BOND_BONDED) "PASS" else "FAIL"}  glasses \"${d.name ?: p.glassesName ?: "?"}\" " +
                    "${XkGlasses.maskAddress(addr)}: $bond, type $type, serial port ${spp?.let { if (it) "listed" else "not listed" } ?: "unknown"}")
                if (!XkGlasses.strongMatch(d.name, addr)) line("INFO  name doesn't end in the address digits (fine for NovaSight-branded glasses)")
            }
        }
        val key = p.userKey
        line("${if (key != null) "PASS" else "FAIL"}  glasses key ${key?.let { KeyFinder.mask(it) } ?: "missing (Glasses tab › Set up)"}")

        // Link
        if (g.state == XkGlasses.State.IDLE && addr != null && key != null) {
            line("INFO  not connected; trying to connect now…")
            main.post { g.connect(addr, p.glassesName) }
            val end = SystemClock.elapsedRealtime() + 70_000
            Thread.sleep(1_000)
            while (g.state == XkGlasses.State.CONNECTING && SystemClock.elapsedRealtime() < end) Thread.sleep(500)
        }
        g.lastConnectReport.forEach { line("INFO  link attempt $it") }
        p.linkMethod?.let { line("INFO  method that worked last: $it") }
        g.lastBind?.let { line("INFO  $it") }
        when (g.state) {
            XkGlasses.State.READY, XkGlasses.State.BUSY -> {
                line("PASS  connected and signed in" + (g.firmware?.let { ", firmware $it" } ?: "") + (g.model?.let { ", model $it" } ?: ""))
                val times = (1..3).mapNotNull { runCatching { g.onLinkThread(5_000) { g.probeBattery() } }.getOrNull() }
                if (times.isEmpty()) line("FAIL  battery query got no answer")
                else line("PASS  battery query answered in ${times.joinToString("/")} ms" + (g.battery?.let { ", battery $it%" } ?: ""))
            }
            XkGlasses.State.CONNECTING -> line("WARN  still connecting after 70 s")
            XkGlasses.State.IDLE -> line("FAIL  not connected" + (g.lastError?.let { ": $it" } ?: ""))
        }
        line("INFO  link health: ${g.parserDropped} stray bytes, ${g.parserBadCrc} bad checksums, largest media frame ${g.maxMediaFrame} B")
        synchronized(g.linkLosses) { g.linkLosses.toList() }.takeLast(5).forEach { line("INFO  link lost $it") }
        line("INFO  live view ${g.preview.name.lowercase()}, ${g.videoFrames} video frames this session, ${g.mediaFramesSeen} media frames in all")
        if (g.previewInfo.isNotEmpty()) line("INFO  preview info from the glasses: " + g.previewInfo.entries.joinToString(", ") { "${it.key}=${it.value}" })
        g.previewState57A0?.let { line("INFO  video preview state (57A0): $it") }
        val lp = p.livePath
        line(when {
            lp == null -> "INFO  live view test: not run yet (Test live view above)"
            lp == XkGlasses.LIVE_NONE -> "WARN  live view test: no video over Bluetooth; Live view uses snapshot view"
            else -> "PASS  live view test: video works with command $lp"
        })

        // Audio
        val am = a.getSystemService(AudioManager::class.java)
        val headset = a.assistant.route.headsetConnected()
        line("${if (headset) "PASS" else "WARN"}  glasses as phone headset (mic): ${if (headset) "yes" else "no, the phone's mic is used"}")
        val a2dpOut = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP } == true
        line("INFO  Bluetooth media audio output: ${if (a2dpOut) "yes" else "no"}")
        val sr = SpeechRecognizer.isRecognitionAvailable(a)
        line("${if (sr) "PASS" else "WARN"}  speech recognition ${if (sr) "available" else "not available"} (phone default)")
        a.assistant.speech.serviceReport().forEach { line("INFO  $it") }
        a.assistant.speech.lastErrorDetail?.let { line("WARN  last listening problem: $it") }
        line("INFO  answers speak through: " + if (p.speakThrough == Prefs.SpeakThrough.CALL) "call audio" else "glasses audio (fast)")
        line("INFO  voice commands: " + if (p.voiceCommands) "on (${VoiceCommands.rules.size})" else "off")
        a.assistant.route.lastReport?.let { line("INFO  last mic link: $it") }
        line("INFO  mic order for voice questions: " + VoiceChoice.routeOrder(p.micRouteScores, a.assistant.route.headsetConnected(),
            a.glasses.state == XkGlasses.State.READY && MicCapture.supported(), p.useGlassesAudio).joinToString(" → ") { it.label })
        p.voiceTestSummary?.let { line("INFO  last voice test: $it") } ?: line("WARN  voice test not run yet: tap Test voice to find the mic that works")
        a.assistant.lastTiming?.let { line("INFO  last voice timing: $it") }
        line("Done.")
    }

    private fun profileState(s: Int) = when (s) {
        BluetoothProfile.STATE_CONNECTED -> "connected"
        BluetoothProfile.STATE_CONNECTING -> "connecting"
        BluetoothProfile.STATE_DISCONNECTED -> "not connected"
        else -> "unknown"
    }

    override fun onHidden() {
        visible = false
        resultsBox = null
        logView = null
    }

    companion object {
        private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private val MAC = Regex("(?i)\\b([0-9a-f]{2}:){5}[0-9a-f]{2}\\b")
        private val KEY = Regex("\\b[0-9a-f]{32}\\b")

        /** Hides full Bluetooth addresses and anything shaped like a glasses key. */
        fun redact(text: String): String =
            text.replace(MAC) { XkGlasses.maskAddress(it.value) }.replace(KEY) { KeyFinder.mask(it.value) }
    }
}
