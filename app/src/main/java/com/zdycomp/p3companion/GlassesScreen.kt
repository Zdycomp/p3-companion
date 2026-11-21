package com.zdycomp.p3companion

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView

/** Live control of the glasses: connect, battery, take photos, start and stop video. */
class GlassesScreen(private val a: MainActivity) : Screen {
    override val label = "Glasses"

    private val listener = XkGlasses.Listener { if (visible) a.refresh() }
    private var visible = false

    override fun build(): View {
        val ui = a.ui
        val g = a.glasses
        visible = true
        g.removeListener(listener)
        g.addListener(listener)

        val col = ui.column(16)
        col.addView(ui.title("Glasses"))

        val hasKey = a.prefs.userKey != null
        if (!hasKey) {
            col.addView(setupCard())
        }

        // Status and controls
        val card = ui.card()
        val (label, color) = when (g.state) {
            XkGlasses.State.READY -> "Connected" to ui.p.good
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

        when (g.state) {
            XkGlasses.State.READY, XkGlasses.State.BUSY -> {
                val ready = g.state == XkGlasses.State.READY
                card.addView(ui.primaryButton("Take photo") { g.takePhoto() }.apply { isEnabled = ready; alpha = if (ready) 1f else .5f })
                card.addView(
                    (if (g.recording) ui.dangerButton("Stop video") { g.setRecording(false) }
                    else ui.secondaryButton("Start video") { g.setRecording(true) })
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
                if (saved != null && hasKey) {
                    card.addView(ui.primaryButton("Connect") { a.withBluetooth { g.connect(saved, a.prefs.glassesName) } })
                }
                card.addView(ui.body(
                    "Close LensMoo first: the glasses talk to one app at a time.",
                    muted = true, sizeSp = 13f,
                ).apply { setPadding(0, ui.dp(8), 0, 0) })
            }
        }
        col.addView(card)

        // Pick the glasses from paired devices
        if (g.state == XkGlasses.State.IDLE) col.addView(deviceCard())

        if (hasKey) col.addView(keyCard())

        val tips = ui.card()
        tips.addView(ui.heading("Good to know"))
        tips.addView(ui.body(
            "• Photos taken here are copied to your phone right away, in the \"${a.prefs.albumName}\" album.\n" +
                "• Photos you take with the button on the glasses are saved too while connected" +
                (if (a.prefs.saveButtonPhotos) "." else " (turned off in Settings).") + "\n" +
                "• Videos stay on the glasses. Bring them over with the cable on the Import tab.\n" +
                "• Bluetooth photos are smaller than the camera's full size. The cable import gets the originals.",
            muted = true, sizeSp = 13f,
        ))
        col.addView(tips)

        if (a.prefs.showLog) col.addView(logCard())

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
                "No paired devices yet. Pair the glasses in your phone's Bluetooth settings first " +
                    "(or connect them once in LensMoo), then come back.",
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
        if (a.prefs.userKey == null) {
            a.toast("Saved. Add your glasses key above, then connect.")
            a.refresh()
        } else {
            a.withBluetooth { a.glasses.connect(d.address, d.name) }
        }
    }

    /** First-time setup: get the owner's own key from their own phone's Bluetooth log. */
    private fun setupCard(): LinearLayout {
        val ui = a.ui
        val card = ui.card()
        card.addView(ui.heading("Set up (one time)"))
        card.addView(ui.body(
            "Your glasses only take commands from an app that signs in with your LensMoo account's ID. " +
                "This app reads that ID from your own phone, once, and keeps it here. Nothing is uploaded.",
            muted = true,
        ))
        card.addView(ui.divider())
        card.addView(ui.body(
            "1. Settings › About phone › Software information: tap Build number 7 times to unlock Developer options.\n" +
                "2. Settings › Developer options: turn on Bluetooth HCI snoop log (pick Enabled, not Filtered, if asked).\n" +
                "3. Turn Bluetooth off and back on.\n" +
                "4. Open LensMoo and connect to your glasses once. Take one photo in it.\n" +
                "5. Developer options › Take bug report › Interactive. Wait for the notification, tap it, and share it to P3 Companion. " +
                "Or save it and tap the button below.\n" +
                "6. Turn the snoop log back off.",
            sizeSp = 14f,
        ))
        card.addView(ui.primaryButton("Open bug report or log file") { a.pickKeyFile() })
        card.addView(ui.secondaryButton("Open Developer options") {
            runCatching { a.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                .onFailure { a.toast("Unlock Developer options first (step 1).") }
        })
        card.addView(ui.secondaryButton("I already have the key") { enterKeyDialog() })
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
                    a.refresh()
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
        val lines = synchronized(g) { g.log.toList().takeLast(60) }
        card.addView(ui.mono(if (lines.isEmpty()) "Nothing yet." else lines.joinToString("\n")))
        card.addView(ui.secondaryButton("Share log") { a.shareText("P3 glasses log", g.logText()) })
        card.addView(ui.secondaryButton("Copy log") {
            a.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("P3 log", g.logText()))
            a.toast("Copied.")
        })
        return card
    }

    override fun onHidden() {
        visible = false
        a.glasses.removeListener(listener)
    }
}
