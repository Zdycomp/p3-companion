package com.zdycomp.p3companion

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
 * 3.8 Wi-Fi: the phone's Wi-Fi state, a search for the glasses as a network or a Wi-Fi Direct device,
 * Connect (for this app only, confirmed by Android) and a look at what answers on the glasses' network.
 * Reached from the Glasses tab and Settings. Leaving the screen leaves the glasses' network, so the
 * phone is never left on it by accident. Work is in [WifiControl].
 */
class WifiScreen(private val a: MainActivity) : Screen {
    override val label = "Wi-Fi"

    private val main = Handler(Looper.getMainLooper())
    private val wifi get() = WifiControl.get(a)
    private var visible = false
    private var statusBox: LinearLayout? = null
    private var probeView: TextView? = null
    private var showOthers = false
    private var refreshPosted = false

    private val listener = WifiControl.Listener { scheduleRefresh() }

    private val tick = object : Runnable {
        override fun run() {
            if (!visible) return
            paintStatus()
            main.postDelayed(this, 3_000)
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
        val col = ui.page()

        // ---- the phone's Wi-Fi ----
        val phone = ui.card()
        phone.addView(ui.heading("This phone's Wi-Fi", R.drawable.ic_wifi))
        val box = ui.column()
        statusBox = box
        phone.addView(box)
        paintStatus()
        phone.addView(ui.buttonRow(
            ui.button("Wi-Fi panel", Btn.TONAL, R.drawable.ic_settings, full = false) { openPanel() },
            ui.button("Refresh", Btn.TEXT, R.drawable.ic_refresh, full = false) { paintStatus() },
        ).apply { setPadding(0, ui.dp(8), 0, 0) })
        phone.addView(ui.body("The app never turns Wi-Fi on or off and never changes your saved networks. The Wi-Fi panel is Android's own.",
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

        // ---- joined to the glasses ----
        val link = w.link
        val joining = w.connecting
        if (link != null || joining != null) {
            val c = ui.card()
            c.addView(ui.heading(if (link != null) "Connected to ${link.name}" else "Joining $joining…", R.drawable.ic_link))
            if (link == null) {
                c.addView(ui.row().apply {
                    addView(ui.spinner(20), LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)).apply { marginEnd = ui.dp(12) })
                    addView(ui.text("Approve it in Android's dialog if one shows.", Type.BODY_MEDIUM, s.onSurfaceVariant))
                })
                c.addView(ui.button("Cancel", Btn.TEXT, R.drawable.ic_close, full = false) { w.disconnect() })
            } else {
                c.addView(ui.body(
                    (if (link.p2p) "Wi-Fi Direct group" else "For this app only, no internet") +
                        (link.note?.let { "; $it" } ?: "") + (link.host?.let { ". The glasses' side: $it." } ?: ". The glasses' address isn't known.") +
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

        // ---- find the glasses ----
        val find = ui.card()
        find.addView(ui.heading("Find the glasses on Wi-Fi", R.drawable.ic_glasses_tab))
        find.addView(ui.body(
            "Looks for the glasses as a Wi-Fi network or a Wi-Fi Direct device (about 10 s). Names that look like your glasses " +
                "(their name, P3, E13C, D6BE, XK, Shenju) are shown first. This model's listing says it has no Wi-Fi, so finding nothing is likely.",
            muted = true, sizeSp = 13f))
        val findRow = ui.row().apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        findRow.addView(ui.button(if (w.scanning) "Looking…" else "Find the glasses on Wi-Fi", Btn.FILLED, R.drawable.ic_wifi, full = false) { scan() }
            .apply { isEnabled = !w.scanning })
        if (w.scanning) findRow.addView(ui.spinner(24), LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)).apply { marginStart = ui.dp(16) })
        find.addView(findRow, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(8) })
        w.lastScan?.let { scan ->
            val glasses = scan.found.filter { it.looksLikeGlasses }
            val others = scan.found.filterNot { it.looksLikeGlasses }
            find.addView(ui.text(
                if (glasses.isEmpty()) "Nothing that looks like the glasses (${others.size} other${if (others.size == 1) "" else "s"} nearby)."
                else "Looks like your glasses:", Type.TITLE_SMALL, if (glasses.isEmpty()) s.onSurfaceVariant else s.primary,
            ).apply { setPadding(0, ui.dp(12), 0, ui.dp(4)) })
            glasses.forEach { f -> find.addView(foundRow(f, highlight = true)) }
            scan.notes.forEach { n -> find.addView(ui.text(n, Type.BODY_SMALL, s.onSurfaceVariant).apply { setPadding(0, ui.dp(4), 0, 0) }) }
            if (others.isNotEmpty()) {
                find.addView(ui.listRow("Other networks and devices (${others.size})", "Connect works on these too, if the glasses use another name",
                    if (showOthers) R.drawable.ic_expand_less else R.drawable.ic_expand_more) { showOthers = !showOthers; a.refresh() })
                if (showOthers) others.forEach { f -> find.addView(foundRow(f, highlight = false)) }
            }
        }
        col.addView(find)

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

        handlerRestart()
        return ui.screen("Wi-Fi", col, back = { a.goBack() }, actions = listOf(
            BarAction(R.drawable.ic_settings, "Wi-Fi panel") { openPanel() },
        ))
    }

    private fun handlerRestart() {
        main.removeCallbacks(tick)
        main.postDelayed(tick, 3_000)
    }

    private fun foundRow(f: WifiControl.Found, highlight: Boolean): View {
        val ui = a.ui
        val s = ui.s
        val sub = buildList {
            add(if (f.p2p) "Wi-Fi Direct" else "Network")
            if (!f.p2p) { add(WifiText.signal(f.rssi)); add(WifiText.band(f.freqMhz)); add(f.security.label) }
            if (f.match != null) add("matches \"${f.match}\"")
        }.joinToString(" · ")
        val busy = wifi.connecting != null || wifi.link?.name == f.name
        val connect = ui.button(if (wifi.link?.name == f.name) "Joined" else "Connect", if (highlight) Btn.FILLED else Btn.TONAL, full = false) { connect(f) }
            .apply { isEnabled = !busy }
        return ui.listRow(f.name, sub, if (f.p2p) R.drawable.ic_link else R.drawable.ic_wifi, connect).apply {
            if (highlight) background = ui.rounded(s.secondaryContainer, 12)
            contentDescription = f.name + ", " + sub + if (highlight) ", looks like your glasses" else ""
            layoutParams = LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(4) }
        }
    }

    /** Repaints the phone's Wi-Fi rows in place (every 3 s while the screen shows). */
    private fun paintStatus() {
        val box = statusBox ?: return
        val ui = a.ui
        val s = ui.s
        val st = wifi.status()
        box.removeAllViews()
        fun row(label: String, value: String) {
            box.addView(ui.row().apply {
                setPadding(0, ui.dp(3), 0, ui.dp(3))
                isScreenReaderFocusable = true
                contentDescription = "$label: $value"
                addView(ui.text(label, Type.BODY_MEDIUM, s.onSurfaceVariant), LinearLayout.LayoutParams(ui.dp(110), Ui.WRAP))
                addView(ui.text(value, Type.BODY_MEDIUM).apply { fontFeatureSettings = "tnum" }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            })
        }
        if (!st.hasWifi) { row("Wi-Fi", "this phone has no Wi-Fi"); return }
        row("Wi-Fi", if (st.enabled) "On" else "Off")
        if (!st.enabled) return
        row("Network", when {
            !st.connected -> "not connected"
            st.ssid != null -> st.ssid
            else -> "connected (Android hides the name without Location)"
        })
        if (!st.connected) return
        row("Signal", WifiText.signal(st.rssi))
        row("Link speed", WifiText.linkSpeed(st.linkMbps))
        row("Frequency", WifiText.frequency(st.freqMhz))
        st.address?.let { row("Address", it) }
        st.gateway?.let { row("Gateway", it) }
        row("Internet", if (st.internet) "yes" else "not checked or none")
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

    private fun connect(f: WifiControl.Found) {
        val done: (String?) -> Unit = { err -> if (err != null) a.toast(err) }
        when {
            f.p2p || !f.security.needsPassword -> { wifi.connect(f, null, done); a.refresh() }
            !f.security.specifierOk -> a.ui.dialog(
                "Use the Wi-Fi panel",
                "\"${f.name}\" uses ${f.security.label} security, which apps can't join. Join it in Android's Wi-Fi panel instead.",
                R.drawable.ic_wifi, confirm = "Open panel", dismiss = "Cancel",
            ) { openPanel(); true }
            else -> askPassword(f, done)
        }
    }

    /**
     * The person types the network's password here; it goes straight into Android's join request and is
     * never saved, logged or sent anywhere else. The Wi-Fi panel is offered as the other way.
     */
    private fun askPassword(f: WifiControl.Found, done: (String?) -> Unit) {
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
            addView(ui.body("Used once to ask Android to join, never saved or written to a log. Android confirms in its own dialog.",
                muted = true, sizeSp = 12f).apply { setPadding(0, ui.dp(8), 0, 0) })
            addView(ui.button("Use the phone's Wi-Fi panel instead", Btn.TEXT, R.drawable.ic_settings, full = false) { openPanel() })
        }
        ui.dialog("Join \"${f.name}\"", "This network (${f.security.label}) needs its password.", R.drawable.ic_lock, view = box,
            confirm = "Connect", dismiss = "Cancel", onDismiss = { field.text.clear() }) {
            val pw = field.text
            val problem = WifiText.passphraseProblem(pw, f.security)
            if (problem != null) { field.error = problem; false } else {
                wifi.connect(f, pw.toString(), done)
                field.text.clear()
                a.refresh()
                true
            }
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
        statusBox = null
        probeView = null
        // Leaving the screen (not a refresh of it) leaves the glasses' network.
        main.post {
            if (a.currentScreen != MainActivity.SCREEN_WIFI && (wifi.link != null || wifi.connecting != null) && !wifi.probing) {
                wifi.disconnect()
                a.toast("Left the glasses' Wi-Fi.")
            }
        }
    }
}
