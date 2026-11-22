package com.zdycomp.p3companion

import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout

/** The AI provider, key, model and reply-language controls, shared by the Assistant tab and Settings. */
object AiSetup {

    private const val GEMINI_KEY_PAGE = "https://aistudio.google.com/apikey"
    private const val CLAUDE_KEY_PAGE = "https://console.anthropic.com/settings/keys"

    private fun maskAi(a: MainActivity, key: String) = if (a.prefs.hideKeys) "Saved" else AiWire.maskKey(key)

    fun card(a: MainActivity): LinearLayout {
        val ui = a.ui
        val p = a.prefs
        val provider = p.aiProvider
        val card = ui.card()
        card.addView(ui.heading("AI setup", R.drawable.ic_auto_awesome))
        card.addView(ui.body(
            "The assistant uses an AI service with your own key. Google Gemini has a free key from Google AI Studio. " +
                "Your key stays on this phone; photos and questions go only to the service you pick.",
            muted = true, sizeSp = 13f,
        ))

        card.addView(ui.choiceRow("AI service", AiProvider.values().map { it.label }, provider.ordinal) {
            p.aiProvider = AiProvider.values()[it]
            a.refresh()
        })

        // Key
        val key = p.aiKey(provider)
        card.addView(ui.text("${provider.label} key", Type.TITLE_SMALL).apply { setPadding(0, ui.dp(12), 0, ui.dp(6)) })
        if (key != null) {
            card.addView(ui.row().apply {
                addView(ui.icon(R.drawable.ic_lock, ui.s.onSurfaceVariant, 18), LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)).apply { marginEnd = ui.dp(8) })
                addView(ui.body("Saved: " + maskAi(a, key), muted = true, sizeSp = 14f))
            })
            card.addView(ui.buttonRow(
                ui.button("Replace key", Btn.TONAL, R.drawable.ic_edit, full = false) { keyDialog(a, provider) },
                ui.button("Remove key", Btn.DANGER, R.drawable.ic_delete, full = false) {
                    a.confirm("Remove key?", "The assistant won't work with ${provider.label} until you add a key again.", "Remove") {
                        p.setAiKey(provider, null)
                        a.refresh()
                    }
                },
            ))
        } else {
            val field = ui.input("", "Paste your key here") { typed ->
                if (typed.isNotBlank() && p.aiKey(provider) == null) {
                    p.setAiKey(provider, typed)
                    a.toast("Key saved.")
                    a.refresh()
                }
            }
            field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            card.addView(field)
            card.addView(ui.button("Save key", Btn.FILLED, R.drawable.ic_save) {
                val typed = field.text.toString()
                if (typed.isBlank()) a.toast("Paste your key first.")
                else { p.setAiKey(provider, typed); a.toast("Key saved."); a.refresh() }
            })
            card.addView(ui.button(if (provider == AiProvider.GEMINI) "Get a free Gemini key" else "Get a Claude key", Btn.TEXT, R.drawable.ic_open_in_new, full = false) {
                runCatching {
                    a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(if (provider == AiProvider.GEMINI) GEMINI_KEY_PAGE else CLAUDE_KEY_PAGE)))
                }.onFailure { a.toast("No web browser found.") }
            })
        }

        // Model
        card.addView(ui.text("Model", Type.TITLE_SMALL).apply { setPadding(0, ui.dp(16), 0, ui.dp(6)) })
        card.addView(ui.input(p.aiModel(provider), provider.defaultModel) { typed ->
            p.setAiModel(provider, typed.ifBlank { provider.defaultModel })
        })
        if (p.aiModel(provider) != provider.defaultModel) {
            card.addView(ui.button("Use the default model (${provider.defaultModel})", Btn.TEXT, R.drawable.ic_restart, full = false) {
                p.setAiModel(provider, provider.defaultModel)
                a.refresh()
            })
        }

        // Reply language
        card.addView(ui.listRow("Reply language", "${p.replyLanguage} · every answer is written and spoken in it", R.drawable.ic_translate,
            ui.icon(R.drawable.ic_chevron_right, ui.s.onSurfaceVariant)) {
            pickLanguage(a, "Reply language", p.replyLanguage) { p.replyLanguage = it; a.refresh() }
        }.apply { setPadding(0, ui.dp(8), 0, ui.dp(8)) })
        return card
    }

    /** The assistant options shown in Settings. */
    fun optionsGroup(a: MainActivity): LinearLayout {
        val ui = a.ui
        val p = a.prefs
        val g = ui.group()
        g.addView(ui.switchRow(
            "Tap the glasses to talk",
            "A tap on the glasses' touch pad asks a question while the app is open",
            p.tapToTalk, R.drawable.ic_glasses_tab,
        ) { p.tapToTalk = it })
        g.addView(ui.switchRow(
            "Use the glasses' mic and speaker",
            "When the glasses are connected as your phone's Bluetooth headset",
            p.useGlassesAudio, R.drawable.ic_mic,
        ) { p.useGlassesAudio = it })
        g.addView(ui.switchRow("Speak voice chat answers", "Off shows the answer without reading it aloud", p.speakChatAnswers, R.drawable.ic_volume_up) {
            p.speakChatAnswers = it
        })
        g.addView(ui.switchRow("Read photo answers aloud", null, p.speakPhotoAnswers, R.drawable.ic_photo) { p.speakPhotoAnswers = it })

        // Voice speed and pitch: 0.5 to 2.0 in 0.1 steps.
        fun voiceSlider(title: String, value: Float, set: (Float) -> Unit) {
            val label = ui.text("", Type.BODY_MEDIUM, ui.s.onSurfaceVariant)
            fun show(step: Int) { label.text = "$title: " + String.format(java.util.Locale.US, "%.1f×", 0.5f + step / 10f) }
            val step = ((value - 0.5f) * 10).toInt().coerceIn(0, 15)
            show(step)
            g.addView(ui.column().apply {
                setPadding(ui.dp(56), ui.dp(8), ui.dp(16), 0)
                addView(label)
                addView(ui.slider(16, step, onStep = { show(it) }) { set(0.5f + it / 10f) })
            })
        }
        g.addView(ui.listRow("Voice", "Speed and pitch of spoken answers", R.drawable.ic_volume_up,
            ui.button("Play sample", Btn.TEXT, R.drawable.ic_play, full = false) {
                a.assistant.readAloud("Hello! This is how the assistant's answers will sound.")
            }))
        voiceSlider("Speed", p.ttsRate) { p.ttsRate = it }
        voiceSlider("Pitch", p.ttsPitch) { p.ttsPitch = it }

        val hist = Prefs.HISTORY_STEPS
        g.addView(padded(a, ui.choiceRow("Remember the last", hist.map { if (it == 0) "None" else "$it messages" }, hist.indexOf(p.historyLimit).coerceAtLeast(0)) {
            p.historyLimit = hist[it]
        }))
        g.addView(ui.text("More messages make follow-up questions work better but send more text each time.", Type.BODY_SMALL, ui.s.onSurfaceVariant).apply {
            setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(8))
        })
        g.addView(ui.listRow("Quick photo questions", "${p.photoPresets.size} questions · edit, reorder or add your own", R.drawable.ic_edit,
            ui.icon(R.drawable.ic_chevron_right, ui.s.onSurfaceVariant)) { editPresets(a) })
        return g
    }

    private fun padded(a: MainActivity, v: LinearLayout): LinearLayout = v.apply { setPadding(a.ui.dp(16), paddingTop, a.ui.dp(16), paddingBottom) }

    // ---------------- quick photo questions ----------------

    fun editPresets(a: MainActivity) {
        val ui = a.ui
        val list = ui.column()
        fun render() {
            list.removeAllViews()
            val items = a.prefs.photoPresets
            items.forEachIndexed { i, (label, question) ->
                val row = ui.row().apply { setPadding(0, ui.dp(4), 0, ui.dp(4)) }
                val texts = ui.column()
                texts.addView(ui.text(label, Type.BODY_LARGE).apply { maxLines = 1 })
                texts.addView(ui.text(question, Type.BODY_SMALL, ui.s.onSurfaceVariant).apply { maxLines = 2 })
                row.addView(texts, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                fun move(to: Int) {
                    val m = items.toMutableList(); val x = m.removeAt(i); m.add(to, x); a.prefs.photoPresets = m; render()
                }
                if (i > 0) row.addView(ui.iconButton(R.drawable.ic_arrow_up, "Move $label up") { move(i - 1) })
                if (i < items.lastIndex) row.addView(ui.iconButton(R.drawable.ic_arrow_down, "Move $label down") { move(i + 1) })
                row.addView(ui.iconButton(R.drawable.ic_edit, "Edit $label") {
                    presetDialog(a, label, question) { l, q ->
                        val m = items.toMutableList(); m[i] = l to q; a.prefs.photoPresets = m; render()
                    }
                })
                row.addView(ui.iconButton(R.drawable.ic_delete, "Delete $label", tint = ui.s.error) {
                    if (items.size <= 1) { a.toast("Keep at least one question."); return@iconButton }
                    val m = items.toMutableList(); m.removeAt(i); a.prefs.photoPresets = m; render()
                })
                list.addView(row)
            }
            val actions = ui.buttonRow(
                ui.button("Add", Btn.TONAL, R.drawable.ic_add, full = false) {
                    if (items.size >= Prefs.MAX_PRESETS) { a.toast("Up to ${Prefs.MAX_PRESETS} questions."); return@button }
                    presetDialog(a, "", "") { l, q -> a.prefs.photoPresets = items + (l to q); render() }
                },
                ui.button("Reset to defaults", Btn.TEXT, R.drawable.ic_restart, full = false) {
                    a.prefs.resetPhotoPresets(); render()
                },
            )
            list.addView(actions)
            list.addView(ui.text("Write ${AiWire.LANGUAGE_TOKEN} where the reply language should go.", Type.BODY_SMALL, ui.s.onSurfaceVariant).apply {
                setPadding(0, ui.dp(8), 0, 0)
            })
        }
        render()
        ui.dialog("Quick photo questions", icon = R.drawable.ic_photo, view = list, confirm = "Done", onDismiss = { a.refresh() }) { a.refresh(); true }
    }

    private fun presetDialog(a: MainActivity, label: String, question: String, onSave: (String, String) -> Unit) {
        val ui = a.ui
        val box = ui.column()
        val l = ui.input(label, "Button label") {}
        val q = ui.input(question, "Question for the AI") {}.apply {
            isSingleLine = false
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3; maxLines = 6
            gravity = Gravity.TOP or Gravity.START
        }
        box.addView(l)
        box.addView(q, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(12) })
        ui.dialog(if (label.isEmpty()) "New question" else "Edit question", view = box, confirm = "Save", dismiss = "Cancel") {
            val lt = l.text.toString().trim()
            val qt = q.text.toString().trim()
            when {
                lt.isEmpty() -> { l.error = "Give it a short label."; false }
                qt.isEmpty() -> { q.error = "Write the question."; false }
                else -> { onSave(lt.take(60), qt.take(1000)); true }
            }
        }
    }

    // ---------------- keys and languages ----------------

    private fun keyDialog(a: MainActivity, provider: AiProvider) {
        val ui = a.ui
        val input: EditText = ui.input("", "Paste your key") {}.apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        ui.dialog("${provider.label} key", icon = R.drawable.ic_lock, view = input, confirm = "Save", dismiss = "Cancel") {
            val k = input.text.toString().trim()
            if (k.isEmpty()) { input.error = "Nothing to save."; false }
            else { a.prefs.setAiKey(provider, k); a.toast("Key saved."); a.refresh(); true }
        }
    }

    fun pickLanguage(a: MainActivity, title: String, current: String, onPick: (String) -> Unit) {
        a.ui.sheet(title, Languages.names.map { name ->
            SheetItem(name, selected = name == current) { onPick(name) }
        })
    }
}
