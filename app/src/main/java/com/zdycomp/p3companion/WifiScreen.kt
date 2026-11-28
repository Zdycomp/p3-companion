package com.zdycomp.p3companion

import android.app.Dialog
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 3.8 Wi-Fi, 3.9 network controller: the phone's Wi-Fi (state, connection details, a 60 s signal trace,
 * the Wi-Fi switch that opens Android's panel), every network and Wi-Fi Direct device nearby, joining
 * an ordinary network on the phone (Android's Add networks dialog), joining the glasses for this app
 * only, waking the glasses' Wi-Fi, and a look at what answers on the glasses' network. Reached from
 * the Glasses tab and Settings. Leaving the screen leaves an app-only network, so the phone is never
 * left on it by accident. Work is in [WifiControl].
 */
class WifiScreen(private val a: MainActivity) : Screen {
    override val label = "Wi-Fi"

    private val main = Handler(Looper.getMainLooper())
    private val wifi get() = WifiControl.get(a)
    private var visible = false
    private var statusBox: LinearLayout? = null
    private var probeView: TextView? = null
    private var showAllNetworks = false
    private var refreshPosted = false

    private val listener = WifiControl.Listener { scheduleRefresh() }

    /** 4.1.1: the Wake button follows whether the glasses are connected over Bluetooth. */
    private var lastBt: Boolean? = null
    private val glassesListener = XkGlasses.Listener { if (btConnected() != lastBt) scheduleRefresh() }

    private fun btConnected(): Boolean = a.glasses.state.let { it == XkGlasses.State.READY || it == XkGlasses.State.BUSY }

    private val tick = object : Runnable {
        override fun run() {
            if (!visible) return
            paintStatus()
            main.postDelayed(this, 2_000)
        }
    }

    private fun scheduleRefresh() {
        if (!visible || refreshPosted) return
        refreshPosted = true
        main.postDelayed({ refreshPosted = false; if (visible && a.currentScreen == MainActivity.SCREEN_WIFI) a.refresh() }, 250)
    }

    override fun build(): View {
        val ui = a.ui
        val s = ui.s
        val w = wifi
        visible = true
        w.addListener(listener)
        a.glasses.removeListener(glassesListener)
        a.glasses.addListener(glassesListener)
        val bt = btConnected()
        lastBt = bt
        val col = ui.page()
        val st = w.status()

        // ---- the phone's Wi-Fi ----
        val phone = ui.card()
        phone.addView(ui.heading("This phone's Wi-Fi", R.drawable.ic_wifi))
        phone.addView(ui.switchRow("Wi-Fi", if (st.enabled) "On. Tap to open Android's Wi-Fi panel" else "Off. Tap to open Android's Wi-Fi panel",
            st.enabled, R.drawable.ic_power) { _ ->
            a.toast("Android 10 and later don't let apps switch Wi-Fi on or off, so Android's own Wi-Fi panel opens.")
            openPanel()
        })
        val box = ui.column()
        statusBox = box
        phone.addView(box)
        paintStatus()
        phone.addView(ui.buttonRow(
            ui.button("Open Android Wi-Fi panel", Btn.TONAL, R.drawable.ic_settings, full = false) { openPanel() },
            ui.button("Refresh", Btn.TEXT, R.drawable.ic_refresh, full = false) { paintStatus() },
        ).apply { setPadding(0, ui.dp(8), 0, 0) })
        phone.addView(ui.body("Apps can't turn Wi-Fi on or off since Android 10, so the switch opens Android's panel. " +
            "Join on this phone saves a network only after you confirm in Android's own dialog.",
            muted = true, sizeSp = 12f).apply { setPadding(0, ui.dp(4), 0, 0) })
        if (!w.hasLocation() || !w.hasNearby()) {
            phone.addView(ui.banner(
                "Android shows Wi-Fi names only to apps with the Location permission (the app doesn't use your location)" +
                    (if (!w.hasNearby()) ", and Wi-Fi Direct needs Nearby devices." else "."),
                Tone.WARN, "Allow") { a.askWifiAgain { a.refresh() } }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(8) })
        } else if (!w.locationOn()) {
            phone.addView(ui.banner("Location is off, so Android hides Wi-Fi names and the list of networks.", Tone.WARN, "Turn on") {
                runCatching { a.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(8) })
        }
        col.addView(phone)

        w.lastJoinResult?.let { r ->
            col.addView(ui.banner(r, if (r.startsWith("Couldn't") || r.startsWith("Android couldn't") || r.startsWith("Nothing")) Tone.WARN else Tone.INFO),
                LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(8); bottomMargin = ui.dp(8) })
        }

        // ---- joined for this app only ----
        val link = w.link
        val joining = w.connecting
        if (link != null || joining != null) {
            val c = ui.card()
            c.addView(ui.heading(if (link != null) "Connected to ${link.name}" else "Joining $joining…", R.drawable.ic_link))
            if (link == null) {
                c.addView(ui.row().apply {
                    addView(ui.spinner(20), LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)).apply { marginEnd = ui.dp(12) })
                    addView(ui.text("Approve it in Android's dialog if one shows (up to ${WifiControl.JOIN_TIMEOUT_MS / 1000} s).", Type.BODY_MEDIUM, s.onSurfaceVariant))
                })
                c.addView(ui.button("Cancel", Btn.TEXT, R.drawable.ic_close, full = false) { w.disconnect() })
            } else {
                c.addView(ui.body(
                    (if (link.p2p) "Wi-Fi Direct group" else "For this app only, no internet") +
                        (link.note?.let { "; $it" } ?: "") + (link.host?.let { ". The other side: $it." } ?: ". The other side's address isn't known.") +
                        (if (!link.p2p) " While joined, the phone may leave its usual Wi-Fi; it goes back when you disconnect or leave this screen." else ""),
                    muted = true, sizeSp = 13f))
                c.addView(ui.buttonRow(
                    ui.button(if (w.probing) "Looking…" else "Look for services", Btn.FILLED, R.drawable.ic_shield_scan, full = false) { probe() }
                        .apply { isEnabled = !w.probing && link.host != null },
                    ui.button("Disconnect", Btn.DANGER, R.drawable.ic_close, full = false) { w.disconnect(); a.toast("Disconnected.") },
                ).apply { setPadding(0, ui.dp(8), 0, 0) })
                c.addView(ui.body("Tries ports ${PortProbe.PORTS.joinToString(", ")} (1 s each); asks web ports for their front page and " +
                    "video ports (RTSP) what they offer. Sends no passwords.", muted = true, sizeSp = 12f).apply { setPadding(0, ui.dp(4), 0, 0) })
            }
            if (w.lastProbe.isNotEmpty()) {
                val pv = ui.mono(w.lastProbe.joinToString("\n")).apply { setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12)); setTextIsSelectable(true) }
                probeView = pv
                c.addView(pv.apply { background = ui.rounded(s.surfaceContainerLowest, 12) },
                    LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(8) })
            }
            col.addView(c)
        }

        // ---- find and wake the glasses ----
        val find = ui.card()
        find.addView(ui.heading("Find the glasses on Wi-Fi", R.drawable.ic_glasses_tab))
        find.addView(ui.body(
            "Scan lists every network and Wi-Fi Direct device nearby (about 10 s), with names that look like your glasses " +
                "(their name, P3, E13C, D6BE, XK, Shenju) first. Wake also asks the glasses over Bluetooth, with read-only " +
                "requests, whether anything switches their Wi-Fi on, and scans again 5 s and 15 s later.",
            muted = true, sizeSp = 13f))
        find.addView(ui.body(WifiText.NO_GLASSES_WIFI, sizeSp = 13f).apply { setPadding(0, ui.dp(6), 0, 0) })
        val busy = w.scanning || w.waking
        val findRow = ui.buttonRow(
            ui.button(if (w.scanning && !w.waking) "Scanning…" else "Scan", Btn.FILLED, R.drawable.ic_wifi, full = false) { scan() }
                .apply { isEnabled = !busy },
            ui.button(WifiText.wakeLabel(bt, w.waking), Btn.TONAL, if (bt) R.drawable.ic_bolt else R.drawable.ic_bluetooth, full = false) { wake() }
                .apply { isEnabled = !busy },
        )
        find.addView(findRow, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(8) })
        if (busy) find.addView(ui.row().apply {
            setPadding(0, ui.dp(8), 0, 0)
            addView(ui.spinner(20), LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)).apply { marginEnd = ui.dp(12) })
            addView(ui.text(if (w.waking) "Asking the glasses and scanning (about 30 s)…" else "Scanning…", Type.BODY_MEDIUM, s.onSurfaceVariant))
        })
        val scanRes = w.lastScan
        if (scanRes != null) {
            val glasses = scanRes.found.filter { it.looksLikeGlasses }
            find.addView(ui.text(
                when {
                    glasses.isNotEmpty() -> "Looks like your glasses:"
                    w.scansWithGlasses == 0 -> "No glasses network or Wi-Fi Direct name seen in any of ${w.scans} scan(s)."
                    else -> "Nothing that looks like the glasses this time (seen in ${w.scansWithGlasses} of ${w.scans} scans)."
                }, Type.TITLE_SMALL, if (glasses.isEmpty()) s.onSurfaceVariant else s.primary,
            ).apply { setPadding(0, ui.dp(12), 0, ui.dp(4)) })
            glasses.forEach { f -> find.addView(foundRow(f, st, highlight = true)) }
            scanRes.notes.forEach { n -> find.addView(ui.text(n, Type.BODY_SMALL, s.onSurfaceVariant).apply { setPadding(0, ui.dp(4), 0, 0) }) }
        }
        col.addView(find)

        if (scanRes != null) {
            // ---- every network ----
            val nets = scanRes.networks.filterNot { it.looksLikeGlasses }
                .sortedWith(compareByDescending<WifiControl.Found> { w.isConnected(it, st) }.thenBy { it.hidden }.thenByDescending { it.rssi })
            val netCard = ui.card()
            netCard.addView(ui.heading("Networks (${nets.size})", R.drawable.ic_wifi))
            netCard.addView(ui.body("Tap one for details, Join on this phone, or the Wi-Fi panel. \"Saved\" means saved from this app; " +
                "Android doesn't let apps read the phone's saved list.", muted = true, sizeSp = 12f))
            if (nets.isEmpty()) netCard.addView(ui.text("No networks in the last scan.", Type.BODY_MEDIUM, s.onSurfaceVariant))
            val shown = if (showAllNetworks) nets else nets.take(8)
            shown.forEach { f -> netCard.addView(foundRow(f, st, highlight = false)) }
            if (nets.size > 8) netCard.addView(ui.listRow(if (showAllNetworks) "Show fewer" else "Show all ${nets.size}", null,
                if (showAllNetworks) R.drawable.ic_expand_less else R.drawable.ic_expand_more) { showAllNetworks = !showAllNetworks; a.refresh() })
            col.addView(netCard)

            // ---- Wi-Fi Direct ----
            val peers = scanRes.peers.sortedWith(compareByDescending<WifiControl.Found> { it.score }.thenBy { it.name.lowercase() })
            val p2pCard = ui.card()
            p2pCard.addView(ui.heading("Wi-Fi Direct devices (${peers.size})", R.drawable.ic_link))
            p2pCard.addView(ui.body("Likely glasses first. Tap one for its details and \"Is this the glasses?\"; nothing connects until you tap Connect.",
                muted = true, sizeSp = 12f))
            if (peers.isEmpty()) p2pCard.addView(ui.text("No Wi-Fi Direct devices in the last scan.", Type.BODY_MEDIUM, s.onSurfaceVariant))
            peers.forEach { f -> p2pCard.addView(foundRow(f, st, highlight = f.looksLikeGlasses)) }
            col.addView(p2pCard)
        }

        // ---- log ----
        val logCard = ui.card()
        logCard.addView(ui.heading("Wi-Fi log", R.drawable.ic_history))
        val lines = w.logLines().takeLast(40)
        logCard.addView(ui.mono(if (lines.isEmpty()) "Nothing yet." else lines.joinToString("\n")).apply {
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
            background = ui.rounded(s.surfaceContainerLowest, 12)
            setTextIsSelectable(true)
        })
        logCard.addView(ui.buttonRow(
            ui.button("Share", Btn.TEXT, R.drawable.ic_share, full = false) {
                a.shareText("P3 Companion Wi-Fi log", DiagnosticsScreen.redact(w.logLines().joinToString("\n")))
            },
            ui.button("Clear", Btn.TEXT, R.drawable.ic_delete_sweep, full = false) { w.clearLog() },
        ))
        col.addView(logCard)

        main.removeCallbacks(tick)
        main.postDelayed(tick, 2_000)
        return ui.screen("Wi-Fi", col, back = { a.goBack() }, actions = listOf(
            BarAction(R.drawable.ic_settings, "Android Wi-Fi panel") { openPanel() },
        ))
    }

    private fun foundRow(f: WifiControl.Found, st: WifiControl.Status, highlight: Boolean): View {
        val ui = a.ui
        val s = ui.s
        val w = wifi
        val sub = buildList {
            if (f.p2p) {
                add("Wi-Fi Direct"); add(WifiMatch.typeName(f.deviceType)); add(WifiText.p2pStatus(f.p2pStatus))
            } else {
                add(WifiText.signalShort(f.rssi)); add(WifiText.band(f.freqMhz)); add(f.security.label)
                if (f.count > 1) add("${f.count} access points")
                if (w.isConnected(f, st)) add("connected")
                if (w.isSaved(f)) add("saved")
                if (w.isMarkedDevice(f)) add(WifiText.MARKED_TAG)
            }
            if (f.match != null) add("matches \"${f.match}\"")
        }.joinToString(" · ")
        return ui.listRow(f.name, sub, if (f.p2p) R.drawable.ic_link else R.drawable.ic_wifi, ui.icon(R.drawable.ic_chevron_right, s.onSurfaceVariant)) {
            if (f.p2p) peerDetails(f) else networkDetails(f)
        }.apply {
            if (highlight) background = ui.rounded(s.secondaryContainer, 12)
            contentDescription = f.name + ", " + sub + if (highlight) ", looks like your glasses" else ""
            layoutParams = LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(4) }
        }
    }

    /** A network's details and what can be done with it. */
    private fun networkDetails(f: WifiControl.Found) {
        val ui = a.ui
        val w = wifi
        val st = w.status()
        var dialog: Dialog? = null
        val box = ui.column()
        fun line(label: String, value: String) = box.addView(ui.text("$label: $value", Type.BODY_MEDIUM, ui.s.onSurfaceVariant)
            .apply { setPadding(0, ui.dp(2), 0, ui.dp(2)) })
        line("Name", f.ssid ?: "hidden (the network doesn't broadcast its name)")
        line("Strongest access point", f.address.ifBlank { "?" } + if (f.count > 1) " (${f.count} seen)" else "")
        line("Signal", WifiText.signalShort(f.rssi) + " (" + WifiText.quality(f.rssi) + ")")
        line("Frequency", WifiText.frequency(f.freqMhz))
        line("Security", f.security.label + if (f.capabilities.isNotBlank()) "  ${f.capabilities}" else "")
        line("On this phone", listOfNotNull(if (w.isConnected(f, st)) "connected" else null, if (w.isSaved(f)) "saved" else null)
            .joinToString(", ").ifEmpty { "not connected" })
        if (!w.bandSupported(f.freqMhz)) line("Note", "this phone's Wi-Fi can't use ${WifiText.band(f.freqMhz)}")
        if (!w.securitySupported(f.security)) line("Note", "this phone can't join ${f.security.label} networks from an app")
        val join = WifiText.join(f.security)
        val actions = mutableListOf<View>()
        if (!f.hidden && join != WifiText.Join.PANEL_ONLY && !f.looksLikeGlasses) {
            actions += ui.button("Join on this phone", Btn.FILLED, R.drawable.ic_wifi, full = false) { dialog?.dismiss(); joinOnPhone(f) }
        }
        if (!f.hidden && join != WifiText.Join.PANEL_ONLY && w.appOnlyAllowed(f)) {
            actions += ui.button("For this app only", if (f.looksLikeGlasses) Btn.FILLED else Btn.TONAL, R.drawable.ic_link, full = false) {
                dialog?.dismiss(); connectAppOnly(f)
            }.apply { isEnabled = w.connecting == null && w.link?.name != f.name }
        }
        actions += ui.button("Open Android Wi-Fi panel", Btn.TEXT, R.drawable.ic_settings, full = false) { dialog?.dismiss(); openPanel() }
        box.addView(ui.buttonRow(*actions.toTypedArray()), LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(8) })
        if (!f.looksLikeGlasses && !f.hidden) {
            // 4.1.1: the glasses have no Wi-Fi, so the mark sits under an advanced note (and says so when set).
            val marked = w.isMarkedDevice(f)
            if (marked) box.addView(ui.banner(WifiText.markedWarning(f.name), Tone.WARN, "Unmark") {
                w.markDevice(f, false); dialog?.dismiss(); networkDetails(f)
            }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(12) })
            box.addView(ui.text(WifiText.MARK_TITLE, Type.TITLE_SMALL, ui.s.onSurfaceVariant).apply { setPadding(0, ui.dp(16), 0, ui.dp(2)) })
            box.addView(ui.body(WifiText.NO_GLASSES_WIFI + " " + WifiText.MARK_NOTE, muted = true, sizeSp = 12f))
            box.addView(ui.switchRow("Mark as another device's network", "Not the glasses: they have no Wi-Fi", marked) { on ->
                w.markDevice(f, on); dialog?.dismiss(); networkDetails(f)
            })
        }
        box.addView(ui.body(
            if (f.looksLikeGlasses) "This looks like your glasses, so it's joined for this app only: local, no internet, and the phone goes back to its usual Wi-Fi afterwards."
            else "Join on this phone saves it like any network you add in Settings, with internet. Android asks you first.",
            muted = true, sizeSp = 12f).apply { setPadding(0, ui.dp(8), 0, 0) })
        dialog = ui.dialog(f.name, null, R.drawable.ic_wifi, view = box, confirm = "Close")
    }

    /** A Wi-Fi Direct device's details, "Is this the glasses?", and Connect (only when tapped). */
    private fun peerDetails(f: WifiControl.Found) {
        val ui = a.ui
        val box = ui.column()
        fun line(label: String, value: String) = box.addView(ui.text("$label: $value", Type.BODY_MEDIUM, ui.s.onSurfaceVariant)
            .apply { setPadding(0, ui.dp(2), 0, ui.dp(2)) })
        line("Device name", f.name)
        line("Address", f.address.ifBlank { "?" })
        line("Type", WifiMatch.typeName(f.deviceType) + (f.deviceType?.let { " ($it)" } ?: ""))
        line("Status", WifiText.p2pStatus(f.p2pStatus))
        box.addView(ui.text("Is this the glasses?", Type.TITLE_SMALL, ui.s.primary).apply { setPadding(0, ui.dp(12), 0, ui.dp(4)) })
        box.addView(ui.body(WifiMatch.verdict(f.name, f.deviceType, tokens()), sizeSp = 14f))
        box.addView(ui.body("Connect asks Android to form a Wi-Fi Direct group with it; Android (and maybe the device) shows its own prompt.",
            muted = true, sizeSp = 12f).apply { setPadding(0, ui.dp(8), 0, 0) })
        ui.dialog(f.name, null, R.drawable.ic_link, view = box, confirm = "Connect", dismiss = "Close") {
            wifi.connect(f, null) { err -> if (err != null) a.toast(err) }
            a.refresh()
            true
        }
    }

    /** Repaints the phone's Wi-Fi rows in place (every 2 s while the screen shows). */
    private fun paintStatus() {
        val box = statusBox ?: return
        val ui = a.ui
        val s = ui.s
        val st = wifi.status()
        box.removeAllViews()
        fun row(label: String, value: String, mono: Boolean = false) {
            box.addView(ui.row().apply {
                setPadding(0, ui.dp(3), 0, ui.dp(3))
                isScreenReaderFocusable = true
                contentDescription = "$label: $value"
                addView(ui.text(label, Type.BODY_MEDIUM, s.onSurfaceVariant), LinearLayout.LayoutParams(ui.dp(110), Ui.WRAP))
                addView((if (mono) ui.mono(value) else ui.text(value, Type.BODY_MEDIUM)).apply { fontFeatureSettings = "tnum" },
                    LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            })
        }
        if (!st.hasWifi) { row("Wi-Fi", "this phone has no Wi-Fi"); return }
        if (!st.enabled) { row("State", "Wi-Fi is off"); return }
        row("Network", when {
            !st.connected -> "not connected"
            st.ssid != null -> st.ssid
            else -> "connected (Android hides the name without Location)"
        })
        if (!st.connected) return
        row("Signal", WifiText.signalShort(st.rssi) + " (" + WifiText.quality(st.rssi) + ")")
        val trace = wifi.signalHistory()
        if (trace.size >= 2) row("Last 60 s", WifiText.sparkline(trace), mono = true)
        row("Link speed", if (st.txMbps > 0 || st.rxMbps > 0) "${WifiText.linkSpeed(st.txMbps)} up, ${WifiText.linkSpeed(st.rxMbps)} down"
            else WifiText.linkSpeed(st.linkMbps))
        st.standard?.let { row("Standard", it) }
        row("Frequency", WifiText.frequency(st.freqMhz))
        st.bssid?.let { row("Access point", it) }
        st.address?.let { row("IP address", it) }
        if (st.ipv6.isNotEmpty()) row("IPv6", st.ipv6.joinToString("\n"))
        st.gateway?.let { row("Gateway", it) }
        if (st.dns.isNotEmpty()) row("DNS", st.dns.joinToString("\n"))
        st.privateDns?.let { row("Private DNS", it) }
        row("Internet", if (st.internet) "yes" else "not checked or none")
        if (st.metered) row("Metered", "yes")
    }

    private fun openPanel() {
        runCatching { a.startActivity(Intent(Settings.Panel.ACTION_WIFI)) }
            .onFailure { runCatching { a.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } }
    }

    private fun tokens(): List<String> {
        val g = a.glasses
        return WifiMatch.tokens(g.deviceName ?: a.prefs.glassesName, g.model, a.prefs.glassesAddress)
    }

    private fun scan() {
        wifi.scan(tokens()) { res ->
            val n = res.found.count { it.looksLikeGlasses }
            a.toast(if (n == 0) "Nothing that looks like the glasses." else "Found $n that look${if (n == 1) "s" else ""} like the glasses.")
        }
        a.refresh()
    }

    private fun wake() {
        // 4.1.1: Wake asks the glasses over Bluetooth; without that link it says so and doesn't scan.
        if (!btConnected()) { a.toast("Connect the glasses first: Wake asks them over Bluetooth. Scan still looks for networks."); return }
        wifi.wake(tokens()) { out -> a.toast(out.substringBefore(". ").take(120)) }
        a.refresh()
    }

    /** "Join on this phone": Android's Add networks dialog, with the result codes in plain words. */
    private fun joinOnPhone(f: WifiControl.Found) {
        val ssid = f.ssid ?: return
        val go: (CharSequence?) -> String? = { pw ->
            val (intent, err) = wifi.addIntent(f, pw)
            if (intent == null) err ?: "Couldn't build the request." else {
                a.addWifiNetwork(intent) { ok, data -> a.toast(wifi.onAddResult(ssid, ok, data)); a.refresh() }
                null
            }
        }
        if (f.security.needsPassword) askPassword(f, "Save", "Android saves it like any network you add in Settings and asks you first.", go)
        else go(null)?.let { a.toast(it) }
    }

    /** "For this app only": the glasses, or a network marked as a device's. */
    private fun connectAppOnly(f: WifiControl.Found) {
        val done: (String?) -> Unit = { err -> if (err != null) a.toast(err); a.refresh() }
        if (!f.security.needsPassword) { wifi.connect(f, null, done); a.refresh(); return }
        askPassword(f, "Connect", "Android confirms in its own dialog; the app waits up to ${WifiControl.JOIN_TIMEOUT_MS / 1000} s.") { pw ->
            WifiText.passphraseProblem(pw ?: "", f.security) ?: run { wifi.connect(f, pw, done); a.refresh(); null }
        }
    }

    /**
     * The person types the network's password here; it goes straight into Android's builder and is never
     * saved, logged or sent anywhere else. [use] returns null when it went ahead, else what is wrong.
     */
    private fun askPassword(f: WifiControl.Found, confirm: String, note: String, use: (CharSequence?) -> String?) {
        val ui = a.ui
        val field = EditText(a).apply {
            hint = "Password"
            ui.style(this, Type.BODY_LARGE, ui.s.onSurface)
            setHintTextColor(ui.s.onSurfaceVariant)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            background = ui.fieldBackground()
            setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
        }
        val box = ui.column().apply {
            addView(field)
            addView(ui.body("8 to 63 characters. Used once, never saved by the app or written to a log. $note",
                muted = true, sizeSp = 12f).apply { setPadding(0, ui.dp(8), 0, 0) })
            addView(ui.button("Use Android's Wi-Fi panel instead", Btn.TEXT, R.drawable.ic_settings, full = false) { openPanel() })
        }
        ui.dialog("\"${f.name}\"", "This network (${f.security.label}) needs its password.", R.drawable.ic_lock, view = box,
            confirm = confirm, dismiss = "Cancel", onDismiss = { field.text.clear() }) {
            val pw = field.text
            val problem = WifiText.passphraseProblem(pw, f.security) ?: use(pw)
            if (problem != null) { field.error = problem; false } else { field.text.clear(); true }
        }
    }

    private fun probe() {
        wifi.probe(onLine = { _ -> probeView?.text = wifi.lastProbe.joinToString("\n") }) { sum ->
            // Left the screen while it ran: leave the glasses' network now.
            if (!visible) wifi.disconnect() else a.toast(sum)
        }
        a.refresh()
    }

    override fun onHidden() {
        visible = false
        main.removeCallbacks(tick)
        wifi.removeListener(listener)
        a.glasses.removeListener(glassesListener)
        statusBox = null
        probeView = null
        // Leaving the screen (not a refresh of it, nor Android's own dialog over it) leaves the app-only network.
        main.post {
            if (a.currentScreen != MainActivity.SCREEN_WIFI && (wifi.link != null || wifi.connecting != null) && !wifi.probing) {
                wifi.disconnect()
                a.toast("Left the app-only Wi-Fi.")
            }
        }
    }
}
