package com.zdycomp.p3companion

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Live control of the glasses: connect, battery, take photos, start and stop video, record audio, pair. */
@SuppressLint("MissingPermission")
class GlassesScreen(private val a: MainActivity) : Screen {
    override val label = "Glasses"

    private val listener = XkGlasses.Listener { if (visible) a.refresh() }
    private val recorderListener: () -> Unit = { if (visible) a.refresh() }
    private var visible = false
    private val main = Handler(Looper.getMainLooper())

    // Voice recorder clock
    private var clockView: TextView? = null
    private val tick = object : Runnable {
        override fun run() {
            val rec = a.recorder
            clockView?.text = "Recording " + VoiceRecorder.clock(rec.elapsedMs())
            if (visible && rec.recording) main.postDelayed(this, 500)
        }
    }

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

        val col = ui.column(16)
        col.addView(ui.title("Glasses"))

        val hasKey = a.prefs.userKey != null
        val connected = g.state == XkGlasses.State.READY || g.state == XkGlasses.State.BUSY

        // Status and controls
        val card = ui.card()
        val (label, color) = when (g.state) {
            XkGlasses.State.READY -> (if (g.signedIn) "Connected" else "Connected, not signed in") to
                (if (g.signedIn) ui.p.good else ui.p.warn)
            XkGlasses.State.BUSY -> (g.busyText ?: "Working…") to ui.p.warn
            XkGlasses.State.CONNECTING -> "Connecting…" to ui.p.warn
            XkGlasses.State.IDLE -> "Not connected" to ui.p.muted
        }
        card.addView(ui.statusLine(label, color))
        (g.deviceName ?: a.prefs.glassesName)?.let { card.addView(ui.body(it, muted = true, sizeSp = 13f)) }

        val facts = buildList {
            g.battery?.let { add("Battery: $it%" + if (g.charging == true) ", charging" else "") }
            val counts = listOfNotNull(
                g.photoCount?.let { "$it photos" },
                g.videoCount?.let { "$it videos" },
                g.audioCount?.let { "$it recordings" },
            )
            if (counts.isNotEmpty()) add("On the glasses: " + counts.joinToString(", "))
            g.firmware?.let { add("Firmware ${it}" + (g.model?.let { m -> " · $m" } ?: "")) }
        }
        if (facts.isNotEmpty()) {
            card.addView(ui.divider())
            card.addView(ui.body(facts.joinToString("\n")))
        }
        g.lastMessage?.let { card.addView(ui.body(it, sizeSp = 14f).apply { setTextColor(ui.p.good); setPadding(0, ui.dp(8), 0, 0) }) }
        g.lastError?.let { card.addView(ui.body(it, sizeSp = 14f).apply { setTextColor(ui.p.bad); setPadding(0, ui.dp(8), 0, 0) }) }
        if (connected && !g.signedIn && g.keylessReport.isNotEmpty()) {
            card.addView(ui.divider())
            card.addView(ui.body("Without your key the glasses answered:", sizeSp = 14f))
            card.addView(ui.body(g.keylessReport.joinToString("\n") { "• $it" }, muted = true, sizeSp = 13f))
        }

        when (g.state) {
            XkGlasses.State.READY, XkGlasses.State.BUSY -> {
                val ready = g.state == XkGlasses.State.READY
                card.addView(ui.primaryButton("Take photo") { g.takePhoto() }.apply { isEnabled = ready; alpha = if (ready) 1f else .5f })
                card.addView(ui.primaryButton("Live view and record to phone") { a.showLiveView() }
                    .apply { isEnabled = ready; alpha = if (ready) 1f else .5f })
                card.addView(
                    (if (g.recording) ui.dangerButton("Stop recording on the glasses") { g.setRecording(false) }
                    else ui.secondaryButton("Record on the glasses (if supported)") { g.setRecording(true) })
                        .apply { isEnabled = ready; alpha = if (ready) 1f else .5f }
                )
                g.lastPhoto?.let { uri ->
                    card.addView(ui.secondaryButton("Open last photo") {
                        runCatching {
                            a.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }
                    })
                }
                card.addView(ui.secondaryButton("Refresh battery and counts") { g.refresh() })
                card.addView(ui.secondaryButton("Disconnect") { g.disconnect() })
            }
            XkGlasses.State.CONNECTING -> card.addView(ui.secondaryButton("Cancel") { g.disconnect() })
            XkGlasses.State.IDLE -> {
                val saved = a.prefs.glassesAddress
                if (saved != null) {
                    card.addView(ui.primaryButton("Connect") { a.withBluetooth { g.connect(saved, a.prefs.glassesName) } })
                    if (!hasKey) card.addView(ui.body(
                        "You can connect before adding your key: the app checks what the glasses answer, then helps you finish setup.",
                        muted = true, sizeSp = 13f,
                    ).apply { setPadding(0, ui.dp(6), 0, 0) })
                } else {
                    card.addView(ui.primaryButton("Pair new glasses") { a.withScan { startScan() } })
                    card.addView(ui.body("Or pick your glasses below if they're already paired.", muted = true, sizeSp = 13f))
                }
                if (g.lastError?.contains("LensMoo") == true) {
                    card.addView(ui.secondaryButton("Open LensMoo's app info (to force-stop it)") {
                        runCatching {
                            a.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:com.lensmoo.app")))
                        }.onFailure { a.toast("LensMoo isn't installed.") }
                    })
                }
                card.addView(ui.body(
                    "If LensMoo is still installed, close it first: the glasses talk to one app at a time.",
                    muted = true, sizeSp = 13f,
                ).apply { setPadding(0, ui.dp(8), 0, 0) })
            }
        }
        col.addView(card)

        // Key setup sits right under the status: before connecting, or once connected without a key.
        if (!hasKey) col.addView(setupCard())
        else if (connected && !g.signedIn) {
            val sign = ui.card()
            sign.addView(ui.heading("Key saved"))
            sign.addView(ui.body("Sign in on this connection to unlock photos and live view.", muted = true, sizeSp = 13f))
            sign.addView(ui.primaryButton("Sign in now") { g.signIn() })
            col.addView(sign)
        }

        col.addView(recorderCard())

        // Pick the glasses from paired devices, or pair new ones
        if (g.state == XkGlasses.State.IDLE) {
            col.addView(deviceCard())
            col.addView(pairCard())
        }

        if (hasKey) col.addView(keyCard())

        val tips = ui.card()
        tips.addView(ui.heading("Good to know"))
        tips.addView(ui.body(
            "• Photos taken here are copied to your phone right away, in the \"${a.prefs.albumName}\" album.\n" +
                "• Photos you take with the button on the glasses are saved too while connected" +
                (if (a.prefs.saveButtonPhotos) "." else " (turned off in Settings).") + "\n" +
                "• While connected, a notification keeps the link up even with the app closed, so button photos still arrive.\n" +
                "• Live view streams the camera to the phone and can record it to Movies › ${a.prefs.albumName} (video only: " +
                "the glasses can't send sound at the same time).\n" +
                "• Bluetooth photos are the size the glasses make them (often 640×480).",
            muted = true, sizeSp = 13f,
        ))
        col.addView(tips)

        if (a.prefs.showLog) col.addView(logCard())
        col.addView(ui.secondaryButton("Diagnostics and connection log") { a.showDiagnostics() }.apply {
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(12)
        })

        return ScrollView(a).apply { addView(col) }
    }

    private fun deviceCard(): LinearLayout {
        val ui = a.ui
        val card = ui.card()
        card.addView(ui.heading("Your glasses"))
        if (!a.hasBluetoothPermission()) {
            card.addView(ui.body("Allow Bluetooth so the app can see your paired glasses.", muted = true))
            card.addView(ui.primaryButton("Allow Bluetooth") { a.withBluetooth { a.refresh() } })
            return card
        }
        val paired = a.glasses.pairedDevices()
        if (paired.isEmpty()) {
            card.addView(ui.body(
                "No paired devices yet. Use Pair new glasses below, or pair them in your phone's Bluetooth settings.",
                muted = true,
            ))
        } else {
            card.addView(ui.body("Tap your glasses. Likely matches are at the top.", muted = true, sizeSp = 13f))
            paired.take(12).forEach { d ->
                val chosen = d.address == a.prefs.glassesAddress
                val text = (if (chosen) "✓ " else "") + d.name
                val btn = if (XkGlasses.looksLikeGlasses(d.name) && !chosen) ui.primaryButton(text) { pick(d) }
                else ui.secondaryButton(text) { pick(d) }
                card.addView(btn)
            }
        }
        card.addView(ui.secondaryButton("Open Bluetooth settings") {
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
        val card = ui.card().apply { background = ui.rounded(ui.p.cardAlt, 16, ui.p.accent) }
        card.addView(ui.heading("Finish setup: add your glasses key"))
        card.addView(ui.body(
            "The glasses take photos and video only from an app signed in with your LensMoo account ID. " +
                "Get it once from your own phone (nothing is uploaded):",
            muted = true, sizeSp = 13f,
        ))
        card.addView(ui.body(
            "1. Developer options › Bluetooth HCI snoop log › Enabled (not Filtered).\n" +
                "2. Turn Bluetooth off and on. Open LensMoo and connect the glasses.\n" +
                "3. Developer options › Take bug report › Interactive. When it's ready, tap the notification › Share › P3 Companion.\n" +
                "4. Turn the snoop log off again.",
            sizeSp = 14f,
        ).apply { setPadding(0, ui.dp(6), 0, 0) })
        card.addView(ui.primaryButton("Open Developer options") {
            runCatching { a.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                .onFailure {
                    a.toast("First unlock them: Settings › About phone › Software information › tap Build number 7 times.")
                }
        })
        card.addView(ui.secondaryButton("Pick a saved bug report or log") { a.pickKeyFile() })
        card.addView(ui.secondaryButton("I already have the key") { enterKeyDialog() })
        card.addView(ui.body(
            "No Developer options? Settings › About phone › Software information › tap Build number 7 times.",
            muted = true, sizeSp = 12f,
        ).apply { setPadding(0, ui.dp(6), 0, 0) })
        return card
    }

    private fun keyCard(): LinearLayout {
        val ui = a.ui
        val card = ui.card()
        card.addView(ui.heading("Glasses key"))
        card.addView(ui.body("Saved: " + KeyFinder.mask(a.prefs.userKey ?: ""), muted = true, sizeSp = 13f))
        card.addView(ui.secondaryButton("Replace key") { enterKeyDialog() })
        card.addView(ui.secondaryButton("Read key from a new bug report") { a.pickKeyFile() })
        card.addView(ui.dangerButton("Remove key") {
            a.confirm("Remove key?", "You'll need to set it up again to control the glasses.", "Remove") {
                a.glasses.disconnect()
                a.prefs.userKey = null
                a.refresh()
            }
        })
        return card
    }

    private fun enterKeyDialog() {
        val input = EditText(a).apply {
            hint = "32 letters and numbers"
            isSingleLine = true
        }
        AlertDialog.Builder(a)
            .setTitle("Glasses key")
            .setMessage("Paste your LensMoo account ID (32 characters, 0-9 and a-f).")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val k = input.text.toString().trim().lowercase()
                if (Xk.isValidUserId(k)) {
                    a.prefs.userKey = k
                    a.toast("Key saved.")
                    a.onKeySaved()
                } else {
                    a.toast("That isn't 32 characters of 0-9 and a-f.")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun logCard(): LinearLayout {
        val ui = a.ui
        val g = a.glasses
        val card = ui.card()
        card.addView(ui.heading("Connection log"))
        val lines = g.logLines(60)
        card.addView(ui.mono(if (lines.isEmpty()) "Nothing yet." else lines.joinToString("\n")))
        card.addView(ui.secondaryButton("Share log") { a.shareText("P3 glasses log", g.logText()) })
        card.addView(ui.secondaryButton("Copy log") {
            a.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("P3 log", g.logText()))
            a.toast("Copied.")
        })
        return card
    }

    // ---------------- voice recorder ----------------

    private fun recorderCard(): LinearLayout {
        val ui = a.ui
        val rec = a.recorder
        val card = ui.card()
        card.addView(ui.heading("Voice recorder"))
        when {
            rec.recording -> {
                val line = ui.statusLine("Recording " + VoiceRecorder.clock(rec.elapsedMs()), ui.p.bad)
                clockView = line.getChildAt(1) as? TextView
                card.addView(line)
                card.addView(ui.body(
                    if (rec.throughGlasses) "Using the glasses' microphone." else "Using the phone's microphone.",
                    muted = true, sizeSp = 13f,
                ))
                card.addView(ui.dangerButton("Stop and save") { rec.stop() })
                main.removeCallbacks(tick)
                main.postDelayed(tick, 500)
            }
            rec.starting -> card.addView(ui.statusLine("Getting the microphone ready…", ui.p.warn))
            else -> {
                clockView = null
                val viaGlasses = a.assistant.route.willUseGlasses()
                card.addView(ui.body(
                    (if (viaGlasses) "Records with the glasses' microphone (they're connected as your headset)."
                    else "Records with the phone's microphone. Connect the glasses as a Bluetooth headset to use theirs.") +
                        " Saved to Music › ${a.prefs.albumName}, and shown in the Gallery.",
                    muted = true, sizeSp = 13f,
                ))
                card.addView(ui.primaryButton("Record audio") { a.withMic(forRecording = true) { rec.start() } })
            }
        }
        rec.error?.let { card.addView(ui.body(it, sizeSp = 14f).apply { setTextColor(ui.p.bad); setPadding(0, ui.dp(8), 0, 0) }) }
        val saved = rec.lastSaved
        if (saved != null && !rec.recording) {
            card.addView(ui.body("Saved ${rec.lastSavedName ?: "the recording"}.", sizeSp = 14f).apply {
                setTextColor(ui.p.good); setPadding(0, ui.dp(8), 0, 0)
            })
            card.addView(ui.secondaryButton("Play last recording") {
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
        card.addView(ui.heading("Pair new glasses"))
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
            box.addView(ui.statusLine("Searching for nearby devices…", ui.p.warn).apply { setPadding(0, ui.dp(10), 0, 0) })
            box.addView(ui.secondaryButton("Stop searching") { stopScan() })
        } else {
            box.addView(ui.primaryButton(if (found.isEmpty()) "Search for glasses" else "Search again") {
                a.withScan { startScan() }
            })
        }
        pairStatus?.let { box.addView(ui.body(it, sizeSp = 14f).apply { setPadding(0, ui.dp(8), 0, 0) }) }

        justPaired?.let { d ->
            if (a.glasses.state == XkGlasses.State.IDLE) {
                if (a.prefs.userKey != null) {
                    box.addView(ui.primaryButton("Connect to ${d.name}") { a.withBluetooth { a.glasses.connect(d.address, d.name) } })
                } else {
                    box.addView(ui.body("Next, add your glasses key in Set up above, then connect.", muted = true, sizeSp = 13f))
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
            val name = f.name ?: "Unnamed device (${f.address})"
            val text = name + when {
                f.address == bondingAddress -> " · pairing…"
                f.bonded -> " · paired"
                else -> ""
            }
            val btn = if (XkGlasses.looksLikeGlasses(f.name ?: "")) ui.primaryButton(text) { pairWith(f) }
            else ui.secondaryButton(text) { pairWith(f) }
            box.addView(btn)
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
        clockView = null
        pairBox = null
    }
}
