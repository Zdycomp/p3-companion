package com.zdycomp.p3companion

import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/** Every option in one place. Changes are saved the moment you make them. */
class SettingsScreen(private val a: MainActivity) : Screen {
    override val label = "Settings"

    override fun build(): View {
        val ui = a.ui
        val p = a.prefs
        val col = ui.column(16)
        col.addView(ui.title("Settings"))

        // Look
        val look = ui.card()
        look.addView(ui.heading("Look"))
        look.addView(ui.choiceRow("Theme", listOf("Dark", "Light", "Match phone"),
            when (p.theme) { Prefs.Theme.DARK -> 0; Prefs.Theme.LIGHT -> 1; Prefs.Theme.SYSTEM -> 2 }) {
            p.theme = listOf(Prefs.Theme.DARK, Prefs.Theme.LIGHT, Prefs.Theme.SYSTEM)[it]
            a.recreate()
        })
        look.addView(ui.choiceRow("Open the app on", listOf("Import", "Gallery", "Glasses", "Assistant"), p.startTab.coerceIn(0, 3)) {
            p.startTab = it
        })
        col.addView(look)

        // Import
        val imp = ui.card()
        imp.addView(ui.heading("Import over the cable"))
        imp.addView(ui.switchRow("Import when plugged in", "Starts by itself when the glasses' drive appears", p.autoImport) { p.autoImport = it })
        imp.addView(ui.switchRow("Photos", null, p.importPhotos) { p.importPhotos = it })
        imp.addView(ui.switchRow("Videos", null, p.importVideos) { p.importVideos = it })
        imp.addView(ui.switchRow("Voice recordings", null, p.importAudio) { p.importAudio = it })
        imp.addView(ui.switchRow(
            "Free up the glasses",
            "Delete each file from the glasses after it's safely copied",
            p.deleteAfterImport,
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
        imp.addView(ui.switchRow("Keep screen on while importing", null, p.keepScreenOn) { p.keepScreenOn = it })
        imp.addView(ui.choiceRow("File names", listOf("Date and time", "Keep original"),
            if (p.naming == Prefs.Naming.DATE) 0 else 1) {
            p.naming = if (it == 0) Prefs.Naming.DATE else Prefs.Naming.ORIGINAL
        })
        imp.addView(ui.body("Album name", sizeSp = 15f).apply { setPadding(0, ui.dp(8), 0, ui.dp(6)) })
        imp.addView(ui.input(p.albumName, Prefs.DEFAULT_ALBUM) { p.albumName = it })
        imp.addView(ui.secondaryButton("Choose a different glasses drive") { a.pickDrive() })
        imp.addView(ui.secondaryButton("Forget imported history") {
            a.confirm(
                "Forget imported history?",
                "The next import copies everything on the glasses again, including files you already have (${a.importer.ledgerCount()} remembered).",
                "Forget",
            ) { a.importer.resetLedger(); a.toast("Done.") }
        })
        col.addView(imp)

        // Glasses
        val gl = ui.card()
        gl.addView(ui.heading("Glasses"))
        gl.addView(ui.switchRow("Reconnect by itself", "When the app opens or the link drops", p.autoReconnect) { p.autoReconnect = it })
        gl.addView(ui.switchRow(
            "Save photos taken with the glasses' button",
            "While connected, each photo you take on the glasses is copied over Bluetooth",
            p.saveButtonPhotos,
        ) { p.saveButtonPhotos = it })
        gl.addView(ui.switchRow("Show connection log", "For troubleshooting", p.showLog) { p.showLog = it })
        gl.addView(ui.secondaryButton("Run diagnostics") { a.showDiagnostics() })
        if (p.glassesAddress != null) {
            gl.addView(ui.dangerButton("Forget these glasses") {
                a.confirm("Forget glasses?", "You'll pick them again from your paired devices.", "Forget") {
                    a.glasses.disconnect()
                    p.glassesAddress = null
                    p.glassesName = null
                    a.refresh()
                }
            })
        }
        col.addView(gl)

        // Assistant
        col.addView(AiSetup.card(a))
        col.addView(AiSetup.optionsCard(a))

        // About
        val about = ui.card()
        about.addView(ui.heading("About"))
        val version = runCatching { a.packageManager.getPackageInfo(a.packageName, 0).versionName }.getOrNull() ?: "?"
        about.addView(ui.body(
            "P3 Companion $version\n" +
                "No account, no ads. Everything works offline except the Assistant, which sends only what you ask to the AI service you choose.\n" +
                "Not made by or connected to NovaSight, Shenju or LensMoo.",
            muted = true, sizeSp = 13f,
        ))
        col.addView(about)

        return ScrollView(a).apply { addView(col, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP)) }
    }
}
