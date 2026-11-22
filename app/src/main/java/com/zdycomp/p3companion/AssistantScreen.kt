package com.zdycomp.p3companion

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

/** The AI assistant: voice chat, live translation and questions about photos. */
class AssistantScreen(private val a: MainActivity) : Screen {
    override val label = "Assistant"

    /** Which part is open: [SECTION_VOICE], [SECTION_TRANSLATE] or [SECTION_PHOTO]. */
    var section = SECTION_VOICE
    private var showSetup = false
    private var question = ""
    private var captureFirst = false
    private var visible = false

    private var thumbUri: Uri? = null
    private var thumb: Bitmap? = null

    // Parts that change while the screen is open; filled by render().
    private var audioBox: LinearLayout? = null
    private var liveBox: LinearLayout? = null

    private val listener = Assistant.Listener { if (visible) render() }

    override fun build(): View {
        val ui = a.ui
        val p = a.prefs
        visible = true
        a.assistant.removeListener(listener)
        a.assistant.addListener(listener)

        val col = ui.column(16)
        col.addView(ui.title("Assistant"))

        if (!a.assistant.hasKey() || showSetup) {
            col.addView(AiSetup.card(a))
            if (a.assistant.hasKey()) col.addView(ui.secondaryButton("Done") { showSetup = false; a.refresh() }.apply {
                (layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(12)
            })
        } else {
            val summary = ui.card()
            val provider = p.aiProvider
            summary.addView(ui.body("${provider.label} · ${p.aiModel(provider)}", sizeSp = 14f))
            summary.addView(ui.body("Answers in ${p.replyLanguage}", muted = true, sizeSp = 13f))
            audioBox = ui.column().also { summary.addView(it) }
            summary.addView(ui.secondaryButton("Change AI setup") { showSetup = true; a.refresh() })
            col.addView(summary)
        }

        col.addView(ui.choiceRow("What would you like to do?", listOf("Voice chat", "Translate", "Photo"), section) {
            section = it
            a.refresh()
        })

        val card = ui.card().apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(4)
        }
        when (section) {
            SECTION_VOICE -> buildVoice(card)
            SECTION_TRANSLATE -> buildTranslate(card)
            else -> buildPhoto(card)
        }
        col.addView(card)

        render()
        return ScrollView(a).apply { addView(col) }
    }

    // ---------------- voice chat ----------------

    private fun buildVoice(card: LinearLayout) {
        val ui = a.ui
        card.addView(ui.heading("Voice chat"))
        card.addView(ui.body(
            "Tap the button, ask your question out loud, and hear the answer" +
                (if (a.prefs.tapToTalk) ". You can also tap the touch pad on the glasses while they're connected." else "."),
            muted = true, sizeSp = 13f,
        ))
        liveBox = ui.column().also { card.addView(it) }
        card.addView(ui.secondaryButton("Clear conversation") { a.assistant.clearChat() })
    }

    // ---------------- translate ----------------

    private fun buildTranslate(card: LinearLayout) {
        val ui = a.ui
        val p = a.prefs
        card.addView(ui.heading("Live translation"))
        card.addView(ui.body(
            "Each tap listens in ${p.translateFrom}, then speaks the ${p.translateTo} translation. Swap to let the other person answer.",
            muted = true, sizeSp = 13f,
        ))
        val langs = ui.row().apply { setPadding(0, ui.dp(4), 0, 0) }
        val from = ui.secondaryButton(p.translateFrom) {
            AiSetup.pickLanguage(a, "Listen in", p.translateFrom) { p.translateFrom = it; a.refresh() }
        }
        val swap = ui.secondaryButton("⇄") {
            val f = p.translateFrom
            p.translateFrom = p.translateTo
            p.translateTo = f
            a.refresh()
        }.apply { contentDescription = "Swap languages" }
        val to = ui.secondaryButton(p.translateTo) {
            AiSetup.pickLanguage(a, "Translate into", p.translateTo) { p.translateTo = it; a.refresh() }
        }
        langs.addView(from, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { topMargin = ui.dp(8) })
        langs.addView(swap, LinearLayout.LayoutParams(ui.dp(56), Ui.WRAP).apply {
            topMargin = ui.dp(8); leftMargin = ui.dp(8); rightMargin = ui.dp(8)
        })
        langs.addView(to, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { topMargin = ui.dp(8) })
        card.addView(langs)
        liveBox = ui.column().also { card.addView(it) }
        card.addView(ui.secondaryButton("Clear translations") { a.assistant.clearTranslations() })
    }

    // ---------------- photo ----------------

    private fun buildPhoto(card: LinearLayout) {
        val ui = a.ui
        val g = a.glasses
        card.addView(ui.heading("Ask about a photo"))
        card.addView(ui.choiceRow(
            "Photo to ask about",
            listOf("A photo I pick", "Take one with the glasses"),
            if (captureFirst) 1 else 0,
        ) { captureFirst = it == 1; a.refresh() })

        if (captureFirst) {
            val ready = g.state == XkGlasses.State.READY
            card.addView(ui.body(
                if (ready) "Each question below first takes a new photo with the glasses, then asks about it."
                else "Connect the glasses on the Glasses tab to take a photo with them.",
                muted = true, sizeSp = 13f,
            ))
            if (!ready) card.addView(ui.secondaryButton("Go to the Glasses tab") { a.show(MainActivity.TAB_GLASSES) })
        } else {
            card.addView(ui.secondaryButton("Choose a photo") { a.pickPhotoForAssistant() })
            val last = g.lastPhoto
            if (last != null && last != a.assistant.photoUri) {
                card.addView(ui.secondaryButton("Use the glasses' last photo") {
                    a.assistant.photoUri = last
                    a.refresh()
                })
            }
        }

        liveBox = ui.column().also { card.addView(it) }

        card.addView(ui.body("Your question", sizeSp = 15f).apply { setPadding(0, ui.dp(12), 0, ui.dp(6)) })
        val field = EditText(a).apply {
            setText(question)
            hint = "Type a question about the photo"
            setHintTextColor(ui.p.muted)
            setTextColor(ui.p.text)
            background = ui.rounded(ui.p.cardAlt, 12)
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            maxLines = 5
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) { question = s?.toString() ?: "" }
            })
        }
        card.addView(field)
        card.addView(ui.primaryButton("Ask") { ask(question) })
        card.addView(ui.body("Or tap one:", muted = true, sizeSp = 13f).apply { setPadding(0, ui.dp(10), 0, 0) })
        AiWire.photoPresetLabels.forEachIndexed { i, label ->
            card.addView(ui.secondaryButton(label) { ask(AiWire.photoPreset(i, a.prefs.replyLanguage)) })
        }
    }

    private fun ask(q: String) {
        val text = q.trim()
        if (text.isEmpty()) { a.toast("Type a question or tap one of the buttons."); return }
        val asst = a.assistant
        if (captureFirst) {
            asst.captureAndAsk(text)
        } else {
            val uri = asst.photoUri
            if (uri == null) { a.toast("Choose a photo first."); return }
            asst.askPhoto(uri, text)
        }
    }

    // ---------------- live parts ----------------

    private fun render() {
        val ui = a.ui
        val asst = a.assistant
        audioBox?.let { box ->
            box.removeAllViews()
            val route = asst.route
            val (text, color) = when {
                !a.prefs.useGlassesAudio -> "Using the phone's mic and speaker (glasses audio is off in Settings)" to ui.p.muted
                route.headsetConnected() -> "Talking through ${route.headsetName() ?: "the glasses"}" to ui.p.good
                else -> "Using the phone's mic and speaker. Connect the glasses as a Bluetooth headset to talk through them." to ui.p.muted
            }
            box.addView(ui.statusLine(text, color).apply { setPadding(0, ui.dp(8), 0, 0) })
        }
        val box = liveBox ?: return
        box.removeAllViews()
        when (section) {
            SECTION_VOICE -> renderVoice(box, Assistant.Mode.CHAT)
            SECTION_TRANSLATE -> renderVoice(box, Assistant.Mode.TRANSLATE)
            else -> renderPhoto(box)
        }
    }

    private fun renderVoice(box: LinearLayout, mode: Assistant.Mode) {
        val ui = a.ui
        val asst = a.assistant
        val mine = asst.mode == mode
        val label = when {
            asst.phase == Assistant.Phase.IDLE ->
                if (mode == Assistant.Mode.CHAT) "🎤  Tap to talk" else "🎤  Speak ${a.prefs.translateFrom}"
            !mine -> "Stop"
            asst.phase == Assistant.Phase.LISTENING -> "Listening… tap when you're done"
            asst.phase == Assistant.Phase.THINKING -> "Thinking… tap to cancel"
            else -> "Speaking… tap to stop"
        }
        val button = if (asst.phase == Assistant.Phase.IDLE || !mine) ui.primaryButton(label) { voiceTap(mode) }
        else ui.dangerButton(label) { voiceTap(mode) }
        button.minHeight = ui.dp(64)
        box.addView(button)

        if (mine && asst.phase == Assistant.Phase.LISTENING) {
            box.addView(ui.body(asst.partial ?: "Go ahead, I'm listening…", muted = asst.partial == null).apply {
                setPadding(0, ui.dp(10), 0, 0)
            })
        }
        if (mine && asst.phase != Assistant.Phase.IDLE && asst.throughGlasses) {
            box.addView(ui.body("Through the glasses", muted = true, sizeSp = 12f).apply { setPadding(0, ui.dp(4), 0, 0) })
        }

        val lines = if (mode == Assistant.Mode.CHAT) asst.chat else asst.translations
        if (lines.isEmpty()) {
            box.addView(ui.body(
                if (mode == Assistant.Mode.CHAT) "Your conversation shows up here." else "Translations show up here.",
                muted = true, sizeSp = 13f,
            ).apply { setPadding(0, ui.dp(12), 0, 0) })
            return
        }
        box.addView(ui.divider().apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(12) })
        // Newest first, so the latest answer sits right under the button.
        lines.asReversed().take(30).forEach { line -> box.addView(lineView(line, mode)) }
    }

    private fun lineView(line: Assistant.Line, mode: Assistant.Mode): View {
        val ui = a.ui
        val p = a.prefs
        val who = when (line.who) {
            Assistant.Who.YOU -> if (mode == Assistant.Mode.TRANSLATE) p.translateFrom else "You"
            Assistant.Who.AI -> if (mode == Assistant.Mode.TRANSLATE) p.translateTo else "Assistant"
            Assistant.Who.NOTE -> "Note"
            Assistant.Who.ERROR -> "Problem"
        }
        val wrap = ui.column().apply { setPadding(0, ui.dp(8), 0, ui.dp(4)) }
        wrap.addView(ui.body(who, sizeSp = 12f).apply {
            setTextColor(when (line.who) {
                Assistant.Who.AI -> ui.p.accent
                Assistant.Who.ERROR -> ui.p.bad
                else -> ui.p.muted
            })
        })
        wrap.addView(ui.body(line.text, muted = line.who == Assistant.Who.NOTE).apply {
            setTextIsSelectable(true)
            if (line.who == Assistant.Who.ERROR) setTextColor(ui.p.bad)
        })
        if (line.who == Assistant.Who.AI) {
            wrap.setOnLongClickListener { copy(line.text); true }
        }
        return wrap
    }

    private fun voiceTap(mode: Assistant.Mode) {
        val asst = a.assistant
        if (asst.phase != Assistant.Phase.IDLE) {
            if (asst.mode == mode) asst.tapWhileBusy() else asst.stop()
            return
        }
        a.withMic {
            if (mode == Assistant.Mode.CHAT) asst.voiceChat() else asst.translate()
        }
    }

    private fun renderPhoto(box: LinearLayout) {
        val ui = a.ui
        val asst = a.assistant
        val uri = asst.photoUri
        if (!captureFirst && uri != null) {
            val image = ImageView(a).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = ui.rounded(ui.p.cardAlt, 12)
                contentDescription = "Chosen photo"
            }
            box.addView(image, LinearLayout.LayoutParams(Ui.MATCH, ui.dp(220)).apply { topMargin = ui.dp(10) })
            loadThumb(uri, image)
        } else if (!captureFirst) {
            box.addView(ui.body("No photo chosen yet.", muted = true, sizeSp = 13f).apply { setPadding(0, ui.dp(8), 0, 0) })
        }

        val status = asst.photoStatus
        if (asst.photoBusy) {
            box.addView(ui.statusLine(status ?: "Working…", ui.p.warn).apply { setPadding(0, ui.dp(12), 0, 0) })
            box.addView(ui.secondaryButton("Cancel") { asst.cancelPhoto() })
        }
        asst.photoError?.let { err ->
            box.addView(ui.body(err, sizeSp = 14f).apply { setTextColor(ui.p.bad); setPadding(0, ui.dp(12), 0, 0) })
        }
        val answer = asst.photoAnswer
        if (answer != null) {
            val ans = ui.column().apply {
                background = ui.rounded(ui.p.cardAlt, 12)
                setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
            }
            asst.photoQuestion?.let { q ->
                ans.addView(ui.body(q, muted = true, sizeSp = 12f).apply { maxLines = 2; setPadding(0, 0, 0, ui.dp(6)) })
            }
            ans.addView(ui.body(answer).apply { setTextIsSelectable(true) })
            box.addView(ans, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(12) })
            val speaking = asst.phase == Assistant.Phase.SPEAKING && asst.mode == Assistant.Mode.READ_ALOUD
            val actions = ui.row()
            val read = if (speaking) ui.dangerButton("Stop reading") { asst.stop() } else ui.secondaryButton("Read aloud") { asst.readAloud(answer) }
            val copy = ui.secondaryButton("Copy") { copy(answer) }
            val share = ui.secondaryButton("Share") { a.shareText("Photo answer", answer) }
            listOf(read, copy, share).forEachIndexed { i, b ->
                actions.addView(b, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply {
                    topMargin = ui.dp(8)
                    if (i > 0) leftMargin = ui.dp(6)
                })
            }
            box.addView(actions)
        }
    }

    private fun loadThumb(uri: Uri, into: ImageView) {
        if (uri == thumbUri && thumb != null) { into.setImageBitmap(thumb); return }
        into.setImageDrawable(null)
        Thread {
            val bmp = ImagePrep.thumbnail(a.contentResolver, uri, 720)
            a.runOnUiThread {
                thumbUri = uri
                thumb = bmp
                if (bmp != null) into.setImageBitmap(bmp) else into.visibility = View.GONE
            }
        }.start()
    }

    private fun copy(text: String) {
        a.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Assistant", text))
        a.toast("Copied.")
    }

    override fun onHidden() {
        visible = false
        a.assistant.removeListener(listener)
        audioBox = null
        liveBox = null
    }

    companion object {
        const val SECTION_VOICE = 0
        const val SECTION_TRANSLATE = 1
        const val SECTION_PHOTO = 2
    }
}
