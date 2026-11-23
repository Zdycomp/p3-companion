package com.zdycomp.p3companion

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout

/**
 * SupRevolt (3.2): send a glasses photo, a document or typed text to your own SupRevolt server, which
 * runs every scan in a fixed order and answers with a verdict and a 0 to 256 confidence per scan.
 * Opened from Settings › SupRevolt, the Glasses and Assistant tabs, and the Gallery. Back returns.
 */
class SupRevoltScreen(private val a: MainActivity) : Screen {
    override val label = "SupRevolt"

    private val sr get() = SupRevolt.get(a.prefs)
    private var visible = false
    private var editing = false
    private var showSkipped = false
    private val expanded = mutableSetOf<String>()
    private var urlDraft: String? = null
    private var tokenDraft = ""
    private var draftText = ""
    private val listener = SupRevolt.Listener { if (visible) a.refresh() }

    override fun build(): View {
        val ui = a.ui
        visible = true
        sr.removeListener(listener)
        sr.addListener(listener)
        sr.loadCatalog()

        val col = ui.page()
        col.addView(setupCard())
        if (a.prefs.srReady && !editing) {
            col.addView(inputCard())
            col.addView(scansCard())
            statusBox(col)
            sr.job?.let { col.addView(resultsCard(it)) }
        }
        val actions = buildList {
            if (sr.job != null && !sr.busy) add(BarAction(R.drawable.ic_share, "Share report") { share() })
        }
        return ui.screen("SupRevolt", col, back = { a.goBack() }, actions = actions)
    }

    override fun onHidden() {
        visible = false
        sr.removeListener(listener)
    }

    // ---------------- setup ----------------

    private fun setupCard(): LinearLayout {
        val ui = a.ui
        val p = a.prefs
        val card = ui.card()
        card.addView(ui.heading("Your SupRevolt server", R.drawable.ic_shield_scan))
        if (p.srReady && !editing) {
            card.addView(ui.listRow("Server", p.srUrl, R.drawable.ic_link))
            val tok = p.srToken ?: ""
            card.addView(ui.listRow("Token", if (p.hideKeys) "Saved" else SupRevoltWire.maskToken(tok), R.drawable.ic_lock))
            if (SupRevoltWire.checkUrl(p.srUrl ?: "").kind == SupRevoltWire.UrlKind.LOCAL_HTTP) {
                card.addView(ui.banner("Plain http:// on your own network. The token is not encrypted on the way.", Tone.WARN))
            }
            card.addView(ui.buttonRow(
                ui.button(if (sr.testing) "Testing…" else "Test", Btn.FILLED, R.drawable.ic_check_circle, full = false) { sr.test() }
                    .apply { isEnabled = !sr.testing },
                ui.button("Change", Btn.OUTLINED, R.drawable.ic_edit, full = false) {
                    editing = true; urlDraft = p.srUrl; tokenDraft = ""; a.refresh()
                },
            ))
            sr.testMessage?.let { msg ->
                card.addView(ui.banner(msg, if (sr.testing) Tone.BUSY else if (sr.testOk) Tone.GOOD else Tone.BAD).apply {
                    (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(12)
                })
            }
            return card
        }

        card.addView(ui.body(
            "Run the server on your computer, an Oracle Cloud free machine or Render (steps in server/README.md). " +
                "It prints an access token once when it first starts. The address must start with https://; plain " +
                "http:// is only allowed for this phone or your home network.",
            muted = true, sizeSp = 13f,
        ))
        val url = field(urlDraft ?: p.srUrl ?: "", "https://your-server.example", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI) {
            urlDraft = it
        }
        card.addView(label("Server address"))
        card.addView(url)
        card.addView(label("Access token"))
        card.addView(field(tokenDraft, if (p.srToken != null) "Leave empty to keep the saved token" else "Paste the token",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) { tokenDraft = it })
        val row = ui.buttonRow(ui.button("Save and test", Btn.FILLED, R.drawable.ic_save, full = false) { save() })
        if (editing) row.addView(ui.button("Cancel", Btn.TEXT, full = false) { editing = false; tokenDraft = ""; a.refresh() })
        card.addView(row, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(12) })
        if (p.srUrl != null || p.srToken != null) {
            card.addView(ui.listRow("Forget this server", "Removes the address and token from this phone", R.drawable.ic_delete, danger = true) {
                a.confirm("Forget the server?", "The address and token are removed from this phone.", "Forget") {
                    p.srUrl = null; p.srToken = null; p.srHttpOk = false
                    editing = false; urlDraft = null; tokenDraft = ""
                    sr.forgetServer(); sr.clear()
                    a.refresh()
                }
            })
        }
        return card
    }

    private fun label(t: String) = a.ui.text(t, Type.TITLE_SMALL).apply { setPadding(0, a.ui.dp(12), 0, a.ui.dp(6)) }

    private fun field(value: String, hint: String, type: Int, onText: (String) -> Unit): EditText = EditText(a).apply {
        val ui = a.ui
        setText(value)
        this.hint = hint
        ui.style(this, Type.BODY_LARGE, ui.s.onSurface)
        setHintTextColor(ui.s.onSurfaceVariant)
        background = ui.fieldBackground()
        setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
        isSingleLine = true
        inputType = type
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        addTextChangedListener(watcher(onText))
    }

    private fun watcher(onText: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) { onText(s?.toString() ?: "") }
    }

    private fun save() {
        val p = a.prefs
        val check = SupRevoltWire.checkUrl(urlDraft ?: p.srUrl ?: "")
        if (check.kind == SupRevoltWire.UrlKind.BAD) { a.toast(check.reason ?: "Check the address."); return }
        val token = tokenDraft.trim().ifEmpty { p.srToken ?: "" }
        if (token.length < 16) { a.toast("Paste the access token the server printed when it first started."); return }
        fun commit(httpOk: Boolean) {
            val changedServer = check.url != p.srUrl
            p.srUrl = check.url
            p.srToken = token
            p.srHttpOk = httpOk
            editing = false; urlDraft = null; tokenDraft = ""
            if (changedServer) { sr.forgetServer(); sr.clear() }
            sr.test()
            a.refresh()
        }
        if (check.kind == SupRevoltWire.UrlKind.LOCAL_HTTP) {
            a.ui.dialog(
                "Use plain http://?",
                "This address is on this phone or your home network, so the app allows it, but the token and what you scan " +
                    "travel unencrypted. Only use it on your own Wi-Fi. For anything on the internet use https://.",
                icon = R.drawable.ic_warning, confirm = "Use it", dismiss = "Cancel",
            ) { commit(true); true }
        } else commit(false)
    }

    // ---------------- input ----------------

    private fun inputCard(): LinearLayout {
        val ui = a.ui
        val g = a.glasses
        val card = ui.card()
        card.addView(ui.heading("What to scan", R.drawable.ic_photo_camera))
        val ready = g.state == XkGlasses.State.READY
        val buttons = ui.buttonRow(
            ui.button("Take a photo with the glasses", Btn.FILLED, R.drawable.ic_photo_camera, full = false) {
                a.whenConnected("take the photo") { sr.scanNewGlassesPhoto(a) { _, err -> err?.let { a.snack(it) } } }
            }.apply { isEnabled = !sr.busy },
        )
        g.lastPhoto?.let { last ->
            buttons.addView(ui.button("Glasses' last photo", Btn.TONAL, R.drawable.ic_photo, full = false) {
                sr.scanUri(a.contentResolver, last, "Glasses' last photo")
            }.apply { isEnabled = !sr.busy })
        }
        buttons.addView(ui.button("Pick a document", Btn.TONAL, R.drawable.ic_upload, full = false) { a.pickDocumentForSupRevolt() }
            .apply { isEnabled = !sr.busy })
        card.addView(buttons)
        if (!ready) card.addView(ui.body("The glasses connect first when you take a photo with them.", muted = true, sizeSp = 12f).apply {
            setPadding(0, ui.dp(4), 0, 0)
        })

        card.addView(ui.text("Or type or paste text", Type.TITLE_SMALL).apply { setPadding(0, ui.dp(16), 0, ui.dp(6)) })
        card.addView(EditText(a).apply {
            setText(draftText)
            hint = "A contract, a message, an offer, a build log, DNA, code…"
            ui.style(this, Type.BODY_LARGE, ui.s.onSurface)
            setHintTextColor(ui.s.onSurfaceVariant)
            background = ui.fieldBackground()
            setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            maxLines = 8
            addTextChangedListener(watcher { draftText = it })
        })
        card.addView(ui.button("Scan text", Btn.TONAL, R.drawable.ic_shield_scan) {
            sr.scanText(draftText) { _, err -> err?.let { a.snack(it) } }
        }.apply { isEnabled = !sr.busy })
        return card
    }

    // ---------------- scans ----------------

    private fun scansCard(): LinearLayout {
        val ui = a.ui
        val p = a.prefs
        val card = ui.card()
        card.addView(ui.heading("Scans", R.drawable.ic_select_all))
        val chosen = p.srScans
        val flow = Flow(a, ui.dp(8), 0)
        flow.addView(ui.chip("All", chosen.isEmpty()) { p.srScans = emptyList(); a.refresh() })
        val cat = sr.catalog
        if (cat == null) {
            card.addView(flow)
            card.addView(ui.body("Test the server to list its scans.", muted = true, sizeSp = 12f))
            return card
        }
        cat.forEach { scan ->
            val suffix = when {
                !scan.enabled -> " (off)"
                !scan.usable -> " (no key)"
                else -> ""
            }
            flow.addView(ui.chip(scan.title + suffix, scan.id in chosen) {
                val now = chosen.toMutableList()
                if (scan.id in now) now.remove(scan.id) else now.add(scan.id)
                p.srScans = now
                a.refresh()
            }.apply {
                if (!scan.usable) alpha = 0.55f
                contentDescription = scan.title + suffix + ". " + scan.accepts
            })
        }
        card.addView(flow)
        val unusable = cat.filter { !it.usable && it.enabled }
        if (unusable.isNotEmpty()) {
            card.addView(ui.body(
                "Greyed scans need an AI key on the server. With All they answer \"unknown\", which makes the verdict unknown " +
                    "rather than guessing. Pick the scans you want to leave them out.",
                muted = true, sizeSp = 12f,
            ).apply { setPadding(0, ui.dp(4), 0, 0) })
        }
        return card
    }

    private fun statusBox(col: LinearLayout) {
        val ui = a.ui
        if (sr.busy) {
            col.addView(ui.statusChip(sr.status ?: "Scanning…", Tone.BUSY).apply {
                (layoutParams as LinearLayout.LayoutParams).apply { topMargin = ui.dp(4); bottomMargin = ui.dp(12) }
            })
        }
        sr.error?.let { col.addView(ui.banner(it, Tone.BAD)) }
    }

    // ---------------- results ----------------

    private fun riskColor(risk: Int?, unknown: Boolean): Int {
        val s = a.ui.s
        return when {
            unknown || risk == null -> s.outline
            risk >= 60 -> s.error
            risk >= 25 -> s.warning
            else -> s.success
        }
    }

    private fun resultsCard(job: SrJob): LinearLayout {
        val ui = a.ui
        val s = ui.s
        val card = ui.card()
        val v = job.verdict
        sr.subject?.let { card.addView(ui.text("Scanned: $it", Type.LABEL_MEDIUM, s.onSurfaceVariant)) }
        if (v == null) {
            card.addView(ui.body("The server is still working on this scan.", muted = true))
            return card
        }
        val (bg, fg) = when (SupRevoltWire.tone(v.level)) {
            SupRevoltWire.VerdictTone.GOOD -> s.successContainer to s.onSuccessContainer
            SupRevoltWire.VerdictTone.WARN -> s.warningContainer to s.onWarningContainer
            SupRevoltWire.VerdictTone.BAD -> s.errorContainer to s.onErrorContainer
            SupRevoltWire.VerdictTone.UNKNOWN -> s.surfaceContainerHighest to s.onSurface
        }
        val head = ui.column().apply {
            background = ui.rounded(bg, 16)
            setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        head.addView(ui.text(SupRevoltWire.levelLabel(v.level), Type.HEADLINE_SMALL, fg).apply { isAccessibilityHeading = true })
        val nums = buildString {
            v.risk?.let { append("Risk $it/100 · ") }
            append("Confidence ").append(SupRevoltWire.confText(v.confidence, v.isUnknown))
            if (!v.isUnknown) append(" (${v.percent}%)")
        }
        head.addView(ui.text(nums, Type.BODY_MEDIUM, fg))
        head.addView(ConfidenceBar(a, ui, v.confidence, v.isUnknown, fg).apply {
            layoutParams = LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(6) }
        })
        v.reasons.take(6).forEach { r -> head.addView(ui.text("• $r", Type.BODY_SMALL, fg).apply { setPadding(0, ui.dp(4), 0, 0) }) }
        card.addView(head, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(8); bottomMargin = ui.dp(8) })

        val shown = job.results.filter { !it.skipped }
        shown.forEachIndexed { i, r -> card.addView(resultRow(r, "$i:${r.scan}")) }

        val skipped = job.results.filter { it.skipped }
        if (skipped.isNotEmpty()) {
            card.addView(ui.button(if (showSkipped) "Hide skipped" else "Show skipped (${skipped.size})", Btn.TEXT,
                if (showSkipped) R.drawable.ic_expand_less else R.drawable.ic_expand_more, full = false) {
                showSkipped = !showSkipped; a.refresh()
            })
            if (showSkipped) {
                card.addView(ui.body(skipped.joinToString("\n") { "${it.title}: not this kind of input" + if (it.note.isNotBlank() && it.note != "not this kind of input") " (${it.note})" else "" },
                    muted = true, sizeSp = 12f))
            }
        }
        card.addView(ui.buttonRow(
            ui.button("Share report", Btn.TONAL, R.drawable.ic_share, full = false) { share() },
            ui.button("Clear", Btn.TEXT, R.drawable.ic_delete_sweep, full = false) { sr.clear() },
        ), LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(8) })
        return card
    }

    private fun resultRow(r: SrResult, key: String): View {
        val ui = a.ui
        val s = ui.s
        val open = key in expanded
        val row = ui.column().apply {
            setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(10))
            isClickable = true
            isFocusable = true
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Ui.withAlpha(s.onSurface, 31)), null,
                android.graphics.drawable.ColorDrawable(android.graphics.Color.WHITE),
            )
            setOnClickListener { if (open) expanded.remove(key) else expanded.add(key); a.refresh() }
        }
        val top = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(ui.text(r.title + if (r.fromPhotoText) " · photo text" else "", Type.TITLE_SMALL), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        val tag = when {
            r.unknown -> "unknown"
            r.cached -> "cached"
            else -> "live"
        }
        top.addView(ui.text(tag, Type.LABEL_SMALL, s.onSurfaceVariant).apply {
            background = ui.rounded(android.graphics.Color.TRANSPARENT, 100, s.outlineVariant)
            setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(2))
        })
        top.addView(ui.icon(if (open) R.drawable.ic_expand_less else R.drawable.ic_expand_more, s.onSurfaceVariant, 20),
            LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)).apply { marginStart = ui.dp(8) })
        row.addView(top)
        row.addView(ConfidenceBar(a, ui, r.confidence, r.unknown, riskColor(r.risk, r.unknown)).apply {
            layoutParams = LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(4) }
        })
        val sub = buildString {
            if (!r.unknown) append("Risk ${r.risk ?: 0}/100")
            if (r.note.isNotBlank()) { if (isNotEmpty()) append(" · "); append(r.note) }
        }
        if (sub.isNotEmpty()) row.addView(ui.text(sub, Type.BODY_SMALL, s.onSurfaceVariant))
        if (open) {
            val lines = r.details.ifEmpty { listOf("Nothing more to show.") }
            row.addView(ui.text(lines.joinToString("\n"), Type.BODY_SMALL, s.onSurface).apply {
                setTextIsSelectable(true)
                setPadding(0, ui.dp(6), 0, 0)
            })
            row.addView(ui.text(
                (if (r.deterministic) "Same input, same answer." else "AI answer: cached, not guaranteed identical next time.") +
                    if (r.ms > 0) " ${r.ms} ms." else "",
                Type.LABEL_SMALL, s.onSurfaceVariant,
            ).apply { setPadding(0, ui.dp(4), 0, 0) })
        }
        row.contentDescription = "${r.title}. " + (if (r.unknown) "Unknown. " else "Risk ${r.risk ?: 0} of 100, confidence ${r.confidence} of 256. ") +
            r.note + if (open) "" else ". Double tap for details."
        return row
    }

    private fun share() {
        val job = sr.job ?: return
        a.shareText("SupRevolt scan", SupRevoltWire.report(job))
    }
}
