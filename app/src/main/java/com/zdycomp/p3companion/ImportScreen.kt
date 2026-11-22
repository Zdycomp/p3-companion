package com.zdycomp.p3companion

import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** Home screen: plug the glasses in, tap once, everything new lands in the gallery. */
class ImportScreen(private val a: MainActivity) : Screen {
    override val label = "Import"

    private val handler = Handler(Looper.getMainLooper())
    private var statusHolder: LinearLayout? = null
    private var progressBar: ProgressBar? = null
    private var progressText: TextView? = null
    private var progressFile: TextView? = null
    private var importButton: Button? = null
    private var cancelButton: Button? = null
    private var resultBox: LinearLayout? = null
    private var lastAvailable: Boolean? = null
    private var wasRunning = false
    private var ticks = 0
    private var howOpen = false

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
        val p = a.prefs
        val col = ui.page()

        // Drive card
        val driveCard = ui.card()
        val holder = ui.column()
        statusHolder = holder
        driveCard.addView(holder)
        lastAvailable = a.importer.driveAvailable()
        paintStatus(lastAvailable == true)
        col.addView(driveCard)

        // Import card
        val importCard = ui.card()
        importCard.addView(ui.heading("Photos, videos and recordings", R.drawable.ic_photo_library))
        val chips = Flow(a, ui.dp(8), 0)
        listOf(
            Triple("Photos", p.importPhotos) { v: Boolean -> p.importPhotos = v },
            Triple("Videos", p.importVideos) { v: Boolean -> p.importVideos = v },
            Triple("Recordings", p.importAudio) { v: Boolean -> p.importAudio = v },
        ).forEach { (name, on, set) ->
            var state = on
            chips.addView(ui.chip(name, state) { v ->
                state = !state
                set(state)
                ui.paintChip(v, name, state)
                ui.haptic(v, Haptic.TICK)
            }, android.view.ViewGroup.MarginLayoutParams(Ui.WRAP, Ui.WRAP))
        }
        importCard.addView(chips)
        importCard.addView(ui.body(
            "New files are copied into your phone's gallery, in the \"${p.albumName}\" album. Files already imported are skipped." +
                if (p.deleteAfterImport) " Each one is removed from the glasses after a verified copy." else "",
            muted = true, sizeSp = 13f,
        ).apply { setPadding(0, ui.dp(4), 0, 0) })
        importButton = ui.button("Import new files", Btn.FILLED, R.drawable.ic_download) { startImport() }.also { importCard.addView(it) }

        progressText = ui.text("", Type.BODY_MEDIUM).apply {
            fontFeatureSettings = "tnum"
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(0, ui.dp(12), 0, 0)
        }.also { importCard.addView(it) }
        progressBar = ui.progress().also { importCard.addView(it) }
        progressFile = ui.text("", Type.BODY_SMALL, ui.s.onSurfaceVariant).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(0, ui.dp(6), 0, 0)
        }.also { importCard.addView(it) }
        cancelButton = ui.button("Stop", Btn.TONAL, R.drawable.ic_stop) { a.importer.cancel() }.also { importCard.addView(it) }
        resultBox = ui.column().also { importCard.addView(it, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(12) }) }
        col.addView(importCard)

        // How-to card, collapsed to one line
        val how = ui.outlinedCard()
        how.addView(ui.row().apply {
            isClickable = true
            minimumHeight = ui.dp(40)
            addView(ui.icon(R.drawable.ic_info, ui.s.primary, 20), LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)).apply { marginEnd = ui.dp(10) })
            addView(ui.text("How to connect", Type.TITLE_MEDIUM).apply { isAccessibilityHeading = true }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            addView(ui.icon(if (howOpen) R.drawable.ic_expand_less else R.drawable.ic_expand_more, ui.s.onSurfaceVariant))
            stateDescription = if (howOpen) "Expanded" else "Collapsed"
            setOnClickListener { howOpen = !howOpen; a.refresh() }
        })
        if (howOpen || p.driveUri == null) {
            how.addView(ui.body(
                "1. Clip the magnetic charging cable onto the glasses.\n" +
                    "2. Plug the USB end into your phone. A USB-A plug needs a small USB-C OTG adapter.\n" +
                    "3. If the phone asks what the USB connection is for, pick file transfer or storage.\n" +
                    "4. The first time only, tap Choose glasses drive and select the glasses.\n" +
                    "5. After that, plugging in starts the import by itself" +
                    (if (p.autoImport) "." else " if you turn on Import when plugged in, in Settings."),
                muted = true, sizeSp = 14f,
            ).apply { setPadding(0, ui.dp(8), 0, 0) })
        }
        col.addView(how)

        wasRunning = a.importer.running
        updateProgress()
        handler.removeCallbacks(tick)
        ticks = 1
        handler.post(tick)

        return ui.screen("Import", col, actions = listOf(
            BarAction(R.drawable.ic_photo_library, "Open gallery") { a.show(MainActivity.TAB_GALLERY) },
        ))
    }

    override fun onHidden() {
        handler.removeCallbacks(tick)
    }

    private fun paintStatus(available: Boolean) {
        val holder = statusHolder ?: return
        val ui = a.ui
        val s = ui.s
        holder.removeAllViews()
        val chosen = a.prefs.driveUri != null
        val (title, sub, color) = when {
            !chosen -> Triple("No drive chosen yet", "Plug the glasses in, then choose them here once.", s.warning)
            available -> Triple("Glasses plugged in", a.prefs.driveLabel ?: "USB drive", s.success)
            else -> Triple("Glasses not plugged in", "Saved drive: ${a.prefs.driveLabel ?: "USB drive"}", s.onSurfaceVariant)
        }
        val row = ui.row()
        val circle = FrameLayout(a).apply {
            background = ui.oval(s.secondaryContainer)
            addView(ui.icon(R.drawable.ic_usb, color), FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER))
        }
        row.addView(circle, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { marginEnd = ui.dp(16) })
        val texts = ui.column()
        texts.addView(ui.text(title, Type.TITLE_MEDIUM).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE })
        texts.addView(ui.text(sub, Type.BODY_SMALL, s.onSurfaceVariant))
        row.addView(texts, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        row.addView(ui.button(if (chosen) "Change" else "Choose", if (chosen) Btn.TEXT else Btn.FILLED, full = false) { a.pickDrive() })
        holder.addView(row)
    }

    private fun startImport() {
        if (a.importer.running) return
        if (a.prefs.driveUri == null) { a.pickDrive(); return }
        if (!a.importer.driveAvailable()) {
            a.ui.haptic(importButton, Haptic.REJECT)
            a.toast("Plug the glasses in first.")
            return
        }
        a.keepAwake(true)
        a.importer.start { a.keepAwake(false); updateProgress() }
        updateProgress()
    }

    private fun updateProgress() {
        val ui = a.ui
        val imp = a.importer
        val running = imp.running
        importButton?.isEnabled = !running
        cancelButton?.visibility = if (running) View.VISIBLE else View.GONE
        progressBar?.visibility = if (running) View.VISIBLE else View.GONE
        progressText?.visibility = if (running) View.VISIBLE else View.GONE
        progressFile?.visibility = if (running) View.VISIBLE else View.GONE
        val pr = imp.progress
        if (running && pr != null) {
            when (pr.stage) {
                ImportProgress.Stage.SCANNING -> {
                    progressBar?.isIndeterminate = true
                    progressText?.text = "Looking for new files on the glasses…"
                    progressFile?.text = ""
                }
                ImportProgress.Stage.COPYING -> {
                    progressBar?.isIndeterminate = false
                    progressBar?.setProgress(pr.percent, !ui.reduceMotion)
                    progressText?.text = "${pr.done + 1} of ${pr.total} · ${mb(pr.bytesDone)} of ${mb(pr.bytesTotal)}"
                    progressFile?.text = pr.currentName
                }
            }
        }
        if (wasRunning && !running) {
            wasRunning = false
            val ok = imp.lastError == null && imp.lastResult?.failed?.isEmpty() == true
            ui.haptic(importButton, if (ok) Haptic.CONFIRM else Haptic.REJECT)
        } else if (running) wasRunning = true

        val box = resultBox ?: return
        val err = imp.lastError
        val res = imp.lastResult
        val key = "$running|$err|${res?.summary()}|${a.prefs.lastImportSummary}"
        if (box.tag == key) return
        box.tag = key
        box.removeAllViews()
        when {
            running -> {}
            err != null -> box.addView(ui.banner(err, Tone.BAD))
            res != null -> {
                val text = res.summary() + if (res.failed.isNotEmpty()) "\nFailed: " + res.failed.take(5).joinToString(", ") else ""
                box.addView(ui.banner(text, if (res.failed.isEmpty()) Tone.GOOD else Tone.WARN))
                if (res.imported > 0) box.addView(ui.button("Open gallery", Btn.FILLED, R.drawable.ic_photo_library) { a.show(MainActivity.TAB_GALLERY) })
            }
            a.prefs.lastImportSummary != null -> box.addView(ui.text("Last import: " + a.prefs.lastImportSummary, Type.BODY_SMALL, ui.s.onSurfaceVariant))
        }
    }

    private fun mb(bytes: Long): String = String.format("%.1f MB", bytes / 1_048_576.0)
}
