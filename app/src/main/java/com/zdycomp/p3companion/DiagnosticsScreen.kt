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
    private var reportView: TextView? = null
    private var logView: TextView? = null
    private var visible = false

    override fun build(): View {
        val ui = a.ui
        visible = true
        val col = ui.column(16)
        val top = ui.row()
        top.addView(ui.title("Diagnostics"), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        top.addView(ui.secondaryButton("Close") { a.goBack() }.apply { layoutParams = LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP) })
        col.addView(top)

        val card = ui.card()
        card.addView(ui.body(
            "Runs every check between this phone and your glasses: permissions, pairing, the Bluetooth link, sign-in, " +
                "response times, audio and live view. If the glasses aren't connected it also tries to connect. " +
                "The report hides your glasses key and the full Bluetooth address.",
            muted = true, sizeSp = 13f,
        ))
        card.addView(ui.primaryButton(if (running) "Running…" else "Run the checks") { run() }.apply { isEnabled = !running })
        card.addView(ui.secondaryButton("Test without key") { a.withBluetooth { testWithoutKey() } }.apply { isEnabled = !running })
        card.addView(ui.body(
            "Test without key reconnects without signing in and records whether battery, device info and the camera " +
                "answer anyway. It may take one photo, which isn't saved. Then it reconnects normally.",
            muted = true, sizeSp = 12f,
        ))
        val rv = ui.mono(report ?: "No report yet.")
        reportView = rv
        card.addView(rv.apply { setPadding(0, ui.dp(10), 0, 0) })
        card.addView(ui.secondaryButton("Share report") {
            val r = report ?: run { a.toast("Run the checks first."); return@secondaryButton }
            a.shareText("P3 Companion diagnostics", r + "\n\n" + redact(a.glasses.logText()))
        })
        card.addView(ui.secondaryButton("Copy report") {
            val r = report ?: run { a.toast("Run the checks first."); return@secondaryButton }
            a.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("P3 diagnostics", r))
            a.toast("Copied.")
        })
        col.addView(card)

        val fixes = ui.card()
        fixes.addView(ui.heading("Quick fixes"))
        fixes.addView(ui.secondaryButton("Let the app run in the background") { askBatteryExemption() })
        fixes.addView(ui.secondaryButton("Open Bluetooth settings") {
            runCatching { a.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }
        })
        fixes.addView(ui.secondaryButton("Open this app's permissions") {
            runCatching {
                a.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", a.packageName, null)))
            }
        })
        fixes.addView(ui.body(
            "On Samsung phones also add P3 Companion to Settings › Battery › Background usage limits › Never sleeping apps.",
            muted = true, sizeSp = 13f,
        ))
        col.addView(fixes)

        val logCard = ui.card()
        logCard.addView(ui.heading("Connection log"))
        val lv = ui.mono(logText())
        logView = lv
        logCard.addView(lv)
        logCard.addView(ui.secondaryButton("Refresh log") { logView?.text = logText() })
        logCard.addView(ui.secondaryButton("Share log") { a.shareText("P3 glasses log", redact(a.glasses.logText())) })
        col.addView(logCard)

        return ScrollView(a).apply { addView(col) }
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
        a.refresh()
        val sb = StringBuilder()
        fun line(s: String) {
            sb.appendLine(s)
            val text = sb.toString()
            main.post { if (visible) reportView?.text = text }
        }
        Thread {
            try { checks(::line) } catch (e: Exception) { line("FAIL  checks stopped: ${e.message}") }
            report = redact(sb.toString())
            running = false
            main.post { if (visible) a.refresh() }
        }.start()
    }

    /** Learns whether these glasses really need the key: 1003, 1001 and 57B0 without 102E. */
    private fun testWithoutKey() {
        if (running) return
        val g = a.glasses
        val addr = a.prefs.glassesAddress ?: run { a.toast("Pick or pair your glasses first."); return }
        val name = a.prefs.glassesName
        running = true
        a.refresh()
        val sb = StringBuilder()
        fun line(s: String) {
            sb.appendLine(s)
            val text = sb.toString()
            main.post { if (visible) reportView?.text = text }
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
        line("INFO  live view ${g.preview.name.lowercase()}, ${g.videoFrames} video frames this session")

        // Audio
        val am = a.getSystemService(AudioManager::class.java)
        val headset = a.assistant.route.headsetConnected()
        line("${if (headset) "PASS" else "WARN"}  glasses as phone headset (mic): ${if (headset) "yes" else "no, the phone's mic is used"}")
        val a2dpOut = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP } == true
        line("INFO  Bluetooth media audio output: ${if (a2dpOut) "yes" else "no"}")
        val sr = SpeechRecognizer.isRecognitionAvailable(a)
        line("${if (sr) "PASS" else "WARN"}  speech recognition ${if (sr) "available" else "not available"}")
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
        reportView = null
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
