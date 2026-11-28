package com.zdycomp.p3companion

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

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

    // 4.0: the mic level meter (updated without rebuilding the screen) and hold to talk.
    private var levelBar: android.widget.ProgressBar? = null
    private var holdActive = false
    private var holdStatus: android.widget.TextView? = null

    override fun build(): View {
        val ui = a.ui
        val p = a.prefs
        visible = true
        a.assistant.removeListener(listener)
        a.assistant.addListener(listener)

        val col = ui.page()

        if (!a.assistant.hasKey() || showSetup) {
            col.addView(AiSetup.card(a))
            if (a.assistant.hasKey()) col.addView(ui.button("Done", Btn.FILLED, R.drawable.ic_check) { showSetup = false; a.refresh() }.apply {
                (layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(12)
            })
        } else {
            val provider = p.aiProvider
            val summary = ui.row().apply { setPadding(ui.dp(4), 0, 0, ui.dp(4)) }
            summary.addView(ui.statusChip("${provider.label} · ${p.aiModel(provider)}", Tone.INFO))
            summary.addView(ui.text("Answers in ${p.replyLanguage}", Type.BODY_SMALL, ui.s.onSurfaceVariant).apply {
                setPadding(ui.dp(12), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            col.addView(summary)
            audioBox = ui.column().also { col.addView(it) }
        }

        col.addView(ui.segmented(listOf("Voice", "Translate", "Photo"), section) {
            section = it
            a.refresh()
        }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(4); bottomMargin = ui.dp(8) })

        val card = ui.card()
        when (section) {
            SECTION_VOICE -> buildVoice(card)
            SECTION_TRANSLATE -> buildTranslate(card)
            else -> buildPhoto(card)
        }
        col.addView(card)

        render()
        return ui.screen("Assistant", col, actions = listOf(
            BarAction(R.drawable.ic_shield_scan, "SupRevolt scan") { a.showSupRevolt() },
            BarAction(R.drawable.ic_settings, "AI setup") { showSetup = !showSetup; a.refresh() },
        ))
    }

    // ---------------- voice chat ----------------

    private fun buildVoice(card: LinearLayout) {
        val ui = a.ui
        card.addView(ui.heading("Voice chat", R.drawable.ic_mic))
        card.addView(ui.body(
            "Tap the mic, ask your question out loud, and hear the answer" +
                (if (a.prefs.tapToTalk) ". You can also tap the touch pad on the glasses while they're connected." else "."),
            muted = true, sizeSp = 13f,
        ))
        liveBox = ui.column().also { card.addView(it) }
        card.addView(ui.buttonRow(
            ui.button("What can I say?", Btn.TEXT, R.drawable.ic_info, full = false) { AiSetup.showCommands(a) },
            ui.button("Clear conversation", Btn.TEXT, R.drawable.ic_delete_sweep, full = false) { a.assistant.clearChat() },
        ))
    }

    // ---------------- translate ----------------

    private fun buildTranslate(card: LinearLayout) {
        val ui = a.ui
        val p = a.prefs
        card.addView(ui.heading("Live translation", R.drawable.ic_translate))
        card.addView(ui.body(
            "Each tap listens in ${p.translateFrom}, then speaks the ${p.translateTo} translation. Swap to let the other person answer.",
            muted = true, sizeSp = 13f,
        ))
        val langs = ui.row().apply { setPadding(0, ui.dp(8), 0, 0) }
        val from = ui.button(p.translateFrom, Btn.OUTLINED, full = false) {
            AiSetup.pickLanguage(a, "Listen in", p.translateFrom) { p.translateFrom = it; a.refresh() }
        }
        val swap = ui.iconButton(R.drawable.ic_swap_horiz, "Swap languages", IconBtn.TONAL) {
            val f = p.translateFrom
            p.translateFrom = p.translateTo
            p.translateTo = f
            a.refresh()
        }
        val to = ui.button(p.translateTo, Btn.OUTLINED, full = false) {
            AiSetup.pickLanguage(a, "Translate into", p.translateTo) { p.translateTo = it; a.refresh() }
        }
        langs.addView(from, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        langs.addView(swap, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)).apply { marginStart = ui.dp(8); marginEnd = ui.dp(8) })
        langs.addView(to, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        card.addView(langs)
        // 3.6: the continuous, logged version.
        val convo = ui.tonalCard().apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = ui.dp(12) }
        convo.addView(ui.text("Conversation translate", Type.TITLE_SMALL))
        convo.addView(ui.body(
            if (a.assistant.convoOn) "Running now. Open it to see the log." else
                "Keeps listening: every phrase is translated, shown with the time, saved, and can be read again or spoken back. Two-way and auto-detect too.",
            muted = true, sizeSp = 13f,
        ).apply { setPadding(0, ui.dp(4), 0, ui.dp(4)) })
        convo.addView(ui.button(if (a.assistant.convoOn) "Open Conversation translate" else "Start Conversation translate", Btn.TONAL, R.drawable.ic_translate, full = false) {
            a.showConversation()
        })
        card.addView(convo, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(12) })
        liveBox = ui.column().also { card.addView(it) }
        card.addView(ui.button("Clear translations", Btn.TEXT, R.drawable.ic_delete_sweep, full = false) { a.assistant.clearTranslations() })
    }

    // ---------------- photo ----------------

    private fun buildPhoto(card: LinearLayout) {
        val ui = a.ui
        val g = a.glasses
        card.addView(ui.heading("Ask about a photo", R.drawable.ic_photo))
        card.addView(ui.choiceRow(null, listOf("A photo I pick", "Take one with the glasses"), if (captureFirst) 1 else 0) {
            captureFirst = it == 1; a.refresh()
        })

        if (captureFirst) {
            val ready = g.state == XkGlasses.State.READY
            card.addView(ui.body(
                if (ready) "Each question below first takes a new photo with the glasses, then asks about it."
                else "Connect the glasses on the Glasses tab to take a photo with them.",
                muted = true, sizeSp = 13f,
            ))
            if (!ready) card.addView(ui.button("Go to the Glasses tab", Btn.TONAL, R.drawable.ic_glasses_tab) { a.show(MainActivity.TAB_GLASSES) })
        } else {
            val row = ui.buttonRow(ui.button("Choose a photo", Btn.TONAL, R.drawable.ic_photo_library, full = false) { a.pickPhotoForAssistant() })
            val last = g.lastPhoto
            if (last != null && last != a.assistant.photoUri) {
                row.addView(ui.button("Glasses' last photo", Btn.OUTLINED, R.drawable.ic_photo_camera, full = false) {
                    a.assistant.photoUri = last
                    a.refresh()
                })
            }
            card.addView(row)
        }

        liveBox = ui.column().also { card.addView(it) }

        card.addView(ui.text("Your question", Type.TITLE_SMALL).apply { setPadding(0, ui.dp(16), 0, ui.dp(6)) })
        val field = EditText(a).apply {
            setText(question)
            hint = "Type a question about the photo"
            ui.style(this, Type.BODY_LARGE, ui.s.onSurface)
            setHintTextColor(ui.s.onSurfaceVariant)
            background = ui.fieldBackground()
            setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
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
        card.addView(ui.button("Ask", Btn.FILLED, R.drawable.ic_auto_awesome) { ask(question) })
        card.addView(ui.text("Or tap a quick question:", Type.BODY_SMALL, ui.s.onSurfaceVariant).apply { setPadding(0, ui.dp(12), 0, 0) })
        val chips = Flow(a, ui.dp(8), 0)
        a.prefs.photoPresets.forEach { (label, template) ->
            chips.addView(ui.assistChip(label, R.drawable.ic_auto_awesome) { ask(AiWire.fillLanguage(template, a.prefs.replyLanguage)) })
        }
        chips.addView(ui.assistChip("Edit", R.drawable.ic_edit) { AiSetup.editPresets(a) })
        card.addView(chips)
    }

    private fun ask(q: String) {
        val text = q.trim()
        if (text.isEmpty()) { a.toast("Type a question or tap one of the quick questions."); return }
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
            val (text, tone) = when {
                !a.prefs.useGlassesAudio -> "Phone mic and speaker (glasses audio is off in Settings)" to Tone.NEUTRAL
                route.headsetConnected() -> "Talking through ${route.headsetName() ?: "the glasses"}" to Tone.GOOD
                else -> "Phone mic and speaker. Connect the glasses as a Bluetooth headset to talk through them." to Tone.NEUTRAL
            }
            // 3.5: which mic the next question starts with (Test voice decides; others are tried if it fails).
            val next = asst.plannedMic()
            box.addView(ui.banner("$text\nMic for the next question: the ${next.label}" +
                (if (next == MicRoute.PHONE && a.prefs.useGlassesAudio && a.prefs.voiceTestSummary == null) " (run Test voice in Diagnostics to try the glasses' mic)" else "") +
                (asst.glassesMicNote()?.let { "\n$it" } ?: ""),
                tone).apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(8) })
        }
        val box = liveBox ?: return
        // 4.0: while the mic button is held, the button must stay where the finger is: only the words change.
        if (holdActive) {
            holdStatus?.text = when (asst.phase) {
                Assistant.Phase.LISTENING -> "Listening… let go when you're done"
                Assistant.Phase.IDLE -> "Hold to talk"
                else -> "Let go"
            }
            return
        }
        box.removeAllViews()
        levelBar = null
        when (section) {
            SECTION_VOICE -> renderVoice(box, Assistant.Mode.CHAT)
            SECTION_TRANSLATE -> renderVoice(box, Assistant.Mode.TRANSLATE)
            else -> renderPhoto(box)
        }
    }

    private fun renderVoice(box: LinearLayout, mode: Assistant.Mode) {
        val ui = a.ui
        val s = ui.s
        val asst = a.assistant
        val mine = asst.mode == mode
        val idle = asst.phase == Assistant.Phase.IDLE || !mine
        val hold = a.prefs.holdToTalk
        val status = when {
            asst.phase == Assistant.Phase.IDLE ->
                if (mode == Assistant.Mode.CHAT) (if (hold) "Hold to talk" else "Tap to talk")
                else (if (hold) "Hold and speak ${a.prefs.translateFrom}" else "Tap and speak ${a.prefs.translateFrom}")
            hold && mine && asst.phase == Assistant.Phase.LISTENING -> "Listening… let go when you're done"
            !mine -> "Tap to stop"
            asst.phase == Assistant.Phase.LISTENING -> "Listening… tap when you're done"
            asst.phase == Assistant.Phase.THINKING -> "Thinking… tap to cancel"
            else -> "Speaking… tap to stop"
        }

        // Big push-to-talk button: filled mic; error ring while it talks through the glasses.
        val ptt = FrameLayout(a).apply { setPadding(0, ui.dp(16), 0, ui.dp(8)) }
        val listening = mine && asst.phase == Assistant.Phase.LISTENING
        if (listening && !ui.reduceMotion) {
            val halo = View(a).apply { background = ui.oval(Ui.withAlpha(s.primary, 51)) }
            ptt.addView(halo, FrameLayout.LayoutParams(ui.dp(96), ui.dp(96), Gravity.CENTER))
            halo.animate().scaleX(1.15f).scaleY(1.15f).setDuration(700).withEndAction {
                halo.animate().scaleX(1f).scaleY(1f).setDuration(700).start()
            }.start()
        }
        val mic = ui.iconButton(if (idle) R.drawable.ic_mic else R.drawable.ic_stop, status, IconBtn.FILLED) { voiceTap(mode) }.apply {
            setPadding(ui.dp(22), ui.dp(22), ui.dp(22), ui.dp(22))
            if (!idle) {
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Ui.withAlpha(s.onError, 31)),
                    ui.oval(s.error, strokeColor = if (asst.throughGlasses) s.onSurface else null, strokeDp = 3),
                    ui.oval(android.graphics.Color.WHITE),
                )
                setImageDrawable(ui.drawable(R.drawable.ic_stop, s.onError))
            }
            elevation = if (s.amoled) 0f else ui.dpf(3f)
        }
        ptt.addView(mic, FrameLayout.LayoutParams(ui.dp(72), ui.dp(72), Gravity.CENTER))
        box.addView(ptt, LinearLayout.LayoutParams(Ui.MATCH, ui.dp(120)))
        val statusView = ui.text(status, Type.LABEL_LARGE, s.onSurfaceVariant).apply {
            gravity = Gravity.CENTER
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        box.addView(statusView)
        // 4.0: hold to talk: press starts listening, letting go ends it (a tap while busy still stops).
        if (hold && idle) {
            @Suppress("ClickableViewAccessibility")
            mic.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        holdActive = true
                        holdStatus = statusView
                        statusView.text = "Listening… let go when you're done"
                        // The level meter shows at once (the screen isn't rebuilt while the button is held).
                        val bar = ui.progress().apply {
                            contentDescription = "Microphone level"
                            (layoutParams as LinearLayout.LayoutParams).apply { marginStart = ui.dp(48); marginEnd = ui.dp(48) }
                        }
                        box.addView(bar, box.indexOfChild(statusView) + 1)
                        levelBar = bar
                        asst.levelListener = { db -> levelBar?.progress = levelPercent(db) }
                        a.ui.haptic(v, Haptic.START)
                        a.withMic { if (holdActive) { if (mode == Assistant.Mode.CHAT) asst.voiceChat(hold = true) else asst.translate(hold = true) } }
                        true
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        if (holdActive) {
                            holdActive = false
                            holdStatus = null
                            a.ui.haptic(v, Haptic.END)
                            asst.releaseHold()
                            render()
                        }
                        true
                    }
                    else -> true
                }
            }
        }
        // 4.1: hands-free voice chat: one button to start or end it, and the mic it uses right now.
        if (mode == Assistant.Mode.CHAT) {
            val on = asst.voiceChatOn
            box.addView(ui.button(if (on) "End hands-free voice chat" else "Hands-free voice chat", if (on) Btn.TONAL else Btn.OUTLINED,
                if (on) R.drawable.ic_stop else R.drawable.ic_glasses_mono, full = false) {
                if (on) asst.endVoiceChat("you ended it") else a.withMic { asst.startVoiceChat() }
            }.apply { (layoutParams as? LinearLayout.LayoutParams)?.gravity = Gravity.CENTER_HORIZONTAL },
                LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = ui.dp(4) })
            asst.voiceChatStatus?.let { st ->
                box.addView(ui.text(st, Type.BODY_SMALL, s.primary).apply { gravity = Gravity.CENTER; setPadding(ui.dp(8), ui.dp(4), ui.dp(8), 0) })
            } ?: box.addView(ui.text("Hands-free: the mic stays open; just talk, and tap the glasses to send, interrupt or cancel.",
                Type.BODY_SMALL, s.onSurfaceVariant).apply { gravity = Gravity.CENTER; setPadding(ui.dp(8), ui.dp(4), ui.dp(8), 0) })
        }

        if (mine && asst.phase != Assistant.Phase.IDLE) {
            val micLine = asst.micInUse?.let { "Mic: the $it" } ?: if (asst.phase == Assistant.Phase.LISTENING) "Choosing the mic…" else null
            val through = if (asst.throughGlasses) "Through the glasses" else null
            listOfNotNull(micLine, through).takeIf { it.isNotEmpty() }?.let {
                box.addView(ui.text(it.joinToString(" · "), Type.BODY_SMALL, s.onSurfaceVariant).apply { gravity = Gravity.CENTER })
            }
        }

        if (listening) {
            // 4.0: a live level meter, so it's plain whether the mic hears you.
            val bar = ui.progress().apply {
                progress = levelPercent(asst.micLevelDb)
                contentDescription = "Microphone level"
                (layoutParams as LinearLayout.LayoutParams).apply { marginStart = ui.dp(48); marginEnd = ui.dp(48) }
            }
            levelBar = bar
            box.addView(bar)
            asst.levelListener = { db -> levelBar?.progress = levelPercent(db) }
            box.addView(ui.text(asst.partial ?: "Go ahead, I'm listening…", Type.BODY_LARGE, s.onSurfaceVariant).apply {
                setTypeface(typeface, Typeface.ITALIC)
                gravity = Gravity.CENTER
                setPadding(ui.dp(8), ui.dp(12), ui.dp(8), 0)
            })
        }

        // Which speech service listened, the last listening problem in full, and how fast the last answer started.
        val sp = asst.speech
        val details = listOfNotNull(
            asst.micInUse?.let { "Last mic: the $it" },
            sp.lastService?.let { "Speech service: $it" },
            sp.lastVoice?.let { "Voice: $it" },
            asst.lastVoiceError?.let { "Last problem: $it" },
            asst.lastTiming?.let { "Last timing: $it" },
        )
        if (details.isNotEmpty()) {
            box.addView(ui.text(details.joinToString("\n"), Type.BODY_SMALL, s.onSurfaceVariant).apply {
                gravity = Gravity.CENTER
                setPadding(ui.dp(8), ui.dp(8), ui.dp(8), 0)
                setTextIsSelectable(true)
            })
        }

        val lines = if (mode == Assistant.Mode.CHAT) asst.chat else asst.translations
        if (lines.isEmpty()) {
            box.addView(ui.text(
                if (mode == Assistant.Mode.CHAT) "Your conversation shows up here." else "Translations show up here.",
                Type.BODY_MEDIUM, s.onSurfaceVariant,
            ).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(16), 0, ui.dp(8)) })
            return
        }
        box.addView(ui.divider().apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(16) })
        // Newest first, so the latest answer sits right under the button.
        lines.asReversed().take(30).forEach { line -> box.addView(bubble(line, mode)) }
    }

    /** Chat bubble: yours on the right (primaryContainer), the assistant's on the left, notes centred. */
    private fun bubble(line: Assistant.Line, mode: Assistant.Mode): View {
        val ui = a.ui
        val s = ui.s
        val p = a.prefs
        val r = ui.dpf(20f)
        val small = ui.dpf(4f)
        val wrap = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ui.dp(6), 0, ui.dp(6))
        }
        when (line.who) {
            Assistant.Who.NOTE, Assistant.Who.ERROR -> {
                wrap.gravity = Gravity.CENTER_HORIZONTAL
                if (line.who == Assistant.Who.ERROR) wrap.addView(ui.banner(line.text, Tone.BAD))
                else wrap.addView(ui.text(line.text, Type.BODY_SMALL, s.onSurfaceVariant).apply { gravity = Gravity.CENTER })
                return wrap
            }
            else -> {}
        }
        val you = line.who == Assistant.Who.YOU
        wrap.gravity = if (you) Gravity.END else Gravity.START
        val who = when {
            mode == Assistant.Mode.TRANSLATE -> if (you) p.translateFrom else p.translateTo
            you -> "You"
            else -> "Assistant"
        }
        wrap.addView(ui.text(who, Type.LABEL_MEDIUM, if (you) s.onSurfaceVariant else s.primary).apply { setPadding(ui.dp(12), 0, ui.dp(12), ui.dp(2)) })
        val bg = GradientDrawable().apply {
            setColor(if (you) s.primaryContainer else s.surfaceContainerHigh)
            cornerRadii = if (you) floatArrayOf(r, r, r, r, small, small, r, r) else floatArrayOf(r, r, r, r, r, r, small, small)
        }
        val textType = if (mode == Assistant.Mode.TRANSLATE && you) Type.BODY_MEDIUM else Type.BODY_LARGE
        val tv = ui.text(line.text, textType, if (you) s.onPrimaryContainer else s.onSurface).apply {
            background = bg
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
            setTextIsSelectable(true)
            maxWidth = (a.resources.displayMetrics.widthPixels * 0.78f).toInt()
        }
        wrap.addView(tv, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP))
        if (!you) {
            wrap.addView(ui.row().apply {
                addView(ui.iconButton(R.drawable.ic_copy, "Copy answer") { copy(line.text) })
                addView(ui.iconButton(R.drawable.ic_volume_up, "Read aloud") { a.assistant.readAloud(line.text) })
            })
        }
        return wrap
    }

    /** -60 dBFS (quiet room) to 0 dBFS as 0..100. */
    private fun levelPercent(db: Double): Int = ((db + 60.0) / 60.0 * 100).toInt().coerceIn(0, 100)

    private fun voiceTap(mode: Assistant.Mode) {
        val asst = a.assistant
        if (asst.phase != Assistant.Phase.IDLE) {
            liveBox?.let { a.ui.haptic(it, Haptic.END) }
            if (asst.mode == mode) asst.tapWhileBusy() else asst.stop()
            return
        }
        a.withMic {
            liveBox?.let { a.ui.haptic(it, Haptic.START) }
            if (mode == Assistant.Mode.CHAT) asst.voiceChat() else asst.translate()
        }
    }

    private fun renderPhoto(box: LinearLayout) {
        val ui = a.ui
        val s = ui.s
        val asst = a.assistant
        val uri = asst.photoUri
        if (!captureFirst && uri != null) {
            val image = ImageView(a).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = ui.rounded(s.surfaceContainerHighest, 16)
                clipToOutline = true
                contentDescription = "Chosen photo"
                setOnClickListener { a.openMedia(uri, "image/*") }
            }
            val w = a.resources.displayMetrics.widthPixels - ui.dp(64)
            box.addView(image, LinearLayout.LayoutParams(Ui.MATCH, w * 9 / 16).apply { topMargin = ui.dp(12) })
            loadThumb(uri, image)
        } else if (!captureFirst) {
            box.addView(ui.text("No photo chosen yet.", Type.BODY_MEDIUM, s.onSurfaceVariant).apply { setPadding(0, ui.dp(8), 0, 0) })
        }

        val status = asst.photoStatus
        if (asst.photoBusy) {
            box.addView(ui.statusChip(status ?: "Working…", Tone.BUSY).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(12) })
            box.addView(ui.button("Cancel", Btn.TONAL, R.drawable.ic_close, full = false) { asst.cancelPhoto() })
        }
        asst.photoError?.let { err ->
            box.addView(ui.banner(err, Tone.BAD).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(12) })
        }
        val answer = asst.photoAnswer
        if (answer != null) {
            val ans = ui.column().apply {
                background = ui.rounded(s.surfaceContainerHigh, 16)
                setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(8))
            }
            asst.photoQuestion?.let { q ->
                ans.addView(ui.text(q, Type.BODY_SMALL, s.onSurfaceVariant).apply { maxLines = 2; setPadding(0, 0, 0, ui.dp(8)) })
            }
            ans.addView(ui.text(answer, Type.BODY_LARGE).apply { setTextIsSelectable(true) })
            val speaking = asst.phase == Assistant.Phase.SPEAKING && asst.mode == Assistant.Mode.READ_ALOUD
            ans.addView(ui.row().apply {
                gravity = Gravity.END
                addView(ui.iconButton(if (speaking) R.drawable.ic_stop else R.drawable.ic_volume_up, if (speaking) "Stop reading" else "Read aloud") {
                    if (speaking) asst.stop() else asst.readAloud(answer)
                })
                addView(ui.iconButton(R.drawable.ic_copy, "Copy") { copy(answer) })
                addView(ui.iconButton(R.drawable.ic_share, "Share") { a.shareText("Photo answer", answer) })
            })
            box.addView(ans, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(12) })
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
        a.assistant.levelListener = null
        levelBar = null
        if (holdActive) { holdActive = false; holdStatus = null; a.assistant.releaseHold() }
        audioBox = null
        liveBox = null
    }

    companion object {
        const val SECTION_VOICE = 0
        const val SECTION_TRANSLATE = 1
        const val SECTION_PHOTO = 2
    }
}
