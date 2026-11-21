package com.zdycomp.p3companion

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/** Home screen: plug the glasses in, tap once, everything new lands in the gallery. */
class ImportScreen(private val a: MainActivity) : Screen {
    override val label = "Import"

    private val handler = Handler(Looper.getMainLooper())
    private var statusHolder: LinearLayout? = null
    private var progressBar: ProgressBar? = null
    private var progressText: TextView? = null
    private var importButton: Button? = null
    private var cancelButton: Button? = null
    private var resultText: TextView? = null
    private var lastAvailable: Boolean? = null
    private var ticks = 0

    private val tick = object : Runnable {
        override fun run() {
            updateProgress()
            // Check the drive every 3 seconds, and import by itself when it appears.
            if (ticks++ % 10 == 0 && !a.importer.running) {
                val available = a.importer.driveAvailable()
                if (available != lastAvailable) {
                    val wasOff = lastAvailable == false
                    lastAvailable = available
                    paintStatus(available)
                    if (available && wasOff && a.prefs.autoImport) startImport()
                }
            }
            handler.postDelayed(this, 300)
        }
    }

    override fun build(): View {
        val ui = a.ui
        val col = ui.column(16)
        col.addView(ui.title("Import"))

        // Drive card
        val driveCard = ui.card()
        driveCard.addView(ui.heading("Glasses drive"))
        val holder = ui.column()
        statusHolder = holder
        driveCard.addView(holder)
        lastAvailable = a.importer.driveAvailable()
        paintStatus(lastAvailable == true)
        val chosen = a.prefs.driveUri != null
        driveCard.addView(
            if (chosen) ui.secondaryButton("Choose a different drive") { a.pickDrive() }
            else ui.primaryButton("Choose glasses drive") { a.pickDrive() }
        )
        col.addView(driveCard)

        // Import card
        val importCard = ui.card()
        importCard.addView(ui.heading("Photos, videos and recordings"))
        val kinds = buildList {
            if (a.prefs.importPhotos) add("photos")
            if (a.prefs.importVideos) add("videos")
            if (a.prefs.importAudio) add("recordings")
        }
        importCard.addView(
            ui.body(
                if (kinds.isEmpty()) "Every file type is switched off in Settings."
                else "Copies new ${kinds.joinToString(", ")} into your phone's gallery, in the \"${a.prefs.albumName}\" album. " +
                    "Files already imported are skipped.",
                muted = true,
            )
        )
        importButton = ui.primaryButton("Import new files") { startImport() }.also { importCard.addView(it) }
        progressBar = ui.progress().also { importCard.addView(it) }
        progressText = ui.body("", muted = true, sizeSp = 13f).also {
            it.setPadding(0, ui.dp(6), 0, 0)
            importCard.addView(it)
        }
        cancelButton = ui.secondaryButton("Stop import") { a.importer.cancel() }.also { importCard.addView(it) }
        resultText = ui.body("", sizeSp = 14f).also {
            it.setPadding(0, ui.dp(10), 0, 0)
            importCard.addView(it)
        }
        importCard.addView(ui.secondaryButton("Open gallery") { a.show(1) })
        col.addView(importCard)

        // How-to card
        val how = ui.card()
        how.addView(ui.heading("How to connect"))
        how.addView(
            ui.body(
                "1. Clip the magnetic charging cable onto the glasses.\n" +
                    "2. Plug the USB end into your phone. A USB-A plug needs a small USB-C OTG adapter.\n" +
                    "3. If the phone asks what the USB connection is for, pick file transfer or storage.\n" +
                    "4. The first time only, tap Choose glasses drive and select the glasses.\n" +
                    "5. After that, plugging in starts the import by itself" +
                    (if (a.prefs.autoImport) "." else " if you turn on Auto-import in Settings."),
                muted = true,
            )
        )
        col.addView(how)

        updateProgress()
        handler.removeCallbacks(tick)
        ticks = 1
        handler.post(tick)

        return ScrollView(a).apply {
            isFillViewport = true
            addView(col)
        }
    }

    override fun onHidden() {
        handler.removeCallbacks(tick)
    }

    private fun paintStatus(available: Boolean) {
        val holder = statusHolder ?: return
        val ui = a.ui
        holder.removeAllViews()
        val chosen = a.prefs.driveUri != null
        when {
            !chosen -> {
                holder.addView(ui.statusLine("No drive chosen yet", ui.p.warn))
                holder.addView(ui.body("Plug the glasses in, then choose them here once.", muted = true, sizeSp = 13f))
            }
            available -> {
                holder.addView(ui.statusLine("Glasses plugged in", ui.p.good))
                holder.addView(ui.body(a.prefs.driveLabel ?: "USB drive", muted = true, sizeSp = 13f))
            }
            else -> {
                holder.addView(ui.statusLine("Glasses not plugged in", ui.p.muted))
                holder.addView(
                    ui.body("Saved drive: ${a.prefs.driveLabel ?: "USB drive"}", muted = true, sizeSp = 13f)
                )
            }
        }
    }

    private fun startImport() {
        if (a.importer.running) return
        if (a.prefs.driveUri == null) { a.pickDrive(); return }
        if (!a.importer.driveAvailable()) {
            a.toast("Plug the glasses in first.")
            return
        }
        a.keepAwake(true)
        a.importer.start { a.keepAwake(false); updateProgress() }
        updateProgress()
    }

    private fun updateProgress() {
        val imp = a.importer
        val running = imp.running
        importButton?.isEnabled = !running
        importButton?.alpha = if (running) 0.5f else 1f
        cancelButton?.visibility = if (running) View.VISIBLE else View.GONE
        progressBar?.visibility = if (running) View.VISIBLE else View.GONE
        val pr = imp.progress
        if (running && pr != null) {
            when (pr.stage) {
                ImportProgress.Stage.SCANNING -> {
                    progressBar?.isIndeterminate = true
                    progressText?.text = "Looking for new files on the glasses…"
                }
                ImportProgress.Stage.COPYING -> {
                    progressBar?.isIndeterminate = false
                    progressBar?.progress = pr.percent
                    progressText?.text = "Copying ${pr.done + 1} of ${pr.total}: ${pr.currentName}\n" +
                        "${mb(pr.bytesDone)} of ${mb(pr.bytesTotal)} · ${pr.percent}%"
                }
            }
        } else {
            progressText?.text = ""
        }
        progressText?.visibility = if (running) View.VISIBLE else View.GONE

        val err = imp.lastError
        val res = imp.lastResult
        resultText?.apply {
            when {
                running -> { text = ""; visibility = View.GONE }
                err != null -> { text = err; setTextColor(a.ui.p.bad); visibility = View.VISIBLE }
                res != null -> {
                    text = res.summary() + if (res.failed.isNotEmpty()) "\nFailed: " + res.failed.take(5).joinToString(", ") else ""
                    setTextColor(if (res.failed.isEmpty()) a.ui.p.good else a.ui.p.warn)
                    visibility = View.VISIBLE
                }
                a.prefs.lastImportSummary != null -> {
                    text = "Last import: " + a.prefs.lastImportSummary
                    setTextColor(a.ui.p.muted)
                    visibility = View.VISIBLE
                }
                else -> { text = ""; visibility = View.GONE }
            }
        }
    }

    private fun mb(bytes: Long): String = String.format("%.1f MB", bytes / 1_048_576.0)
}
