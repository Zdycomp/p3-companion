package com.zdycomp.p3companion

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.widget.LinearLayout

/** The AI provider, key, model and reply-language controls, shared by the Assistant tab and Settings. */
object AiSetup {

    private const val GEMINI_KEY_PAGE = "https://aistudio.google.com/apikey"
    private const val CLAUDE_KEY_PAGE = "https://console.anthropic.com/settings/keys"

    fun card(a: MainActivity): LinearLayout {
        val ui = a.ui
        val p = a.prefs
        val provider = p.aiProvider
        val card = ui.card()
        card.addView(ui.heading("AI setup"))
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
        card.addView(ui.body("${provider.label} key", sizeSp = 15f).apply { setPadding(0, ui.dp(8), 0, ui.dp(6)) })
        if (key != null) {
            card.addView(ui.body("Saved: " + AiWire.maskKey(key), muted = true, sizeSp = 13f))
            card.addView(ui.secondaryButton("Replace key") { keyDialog(a, provider) })
            card.addView(ui.dangerButton("Remove key") {
                a.confirm("Remove key?", "The assistant won't work with ${provider.label} until you add a key again.", "Remove") {
                    p.setAiKey(provider, null)
                    a.refresh()
                }
            })
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
            card.addView(ui.primaryButton("Save key") {
                val typed = field.text.toString()
                if (typed.isBlank()) a.toast("Paste your key first.")
                else { p.setAiKey(provider, typed); a.toast("Key saved."); a.refresh() }
            })
            card.addView(ui.secondaryButton(if (provider == AiProvider.GEMINI) "Get a free Gemini key" else "Get a Claude key") {
                runCatching {
                    a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(if (provider == AiProvider.GEMINI) GEMINI_KEY_PAGE else CLAUDE_KEY_PAGE)))
                }.onFailure { a.toast("No web browser found.") }
            })
        }

        // Model
        card.addView(ui.body("Model", sizeSp = 15f).apply { setPadding(0, ui.dp(12), 0, ui.dp(6)) })
        card.addView(ui.input(p.aiModel(provider), provider.defaultModel) { typed ->
            p.setAiModel(provider, typed.ifBlank { provider.defaultModel })
        })
        if (p.aiModel(provider) != provider.defaultModel) {
            card.addView(ui.secondaryButton("Use the default model (${provider.defaultModel})") {
                p.setAiModel(provider, provider.defaultModel)
                a.refresh()
            })
        }

        // Reply language
        card.addView(ui.body("Reply language: ${p.replyLanguage}", sizeSp = 15f).apply { setPadding(0, ui.dp(12), 0, 0) })
        card.addView(ui.body("Every answer is written and spoken in this language.", muted = true, sizeSp = 13f))
        card.addView(ui.secondaryButton("Change reply language") {
            pickLanguage(a, "Reply language", p.replyLanguage) { p.replyLanguage = it; a.refresh() }
        })
        return card
    }

    /** The on/off options for the assistant, shown in Settings. */
    fun optionsCard(a: MainActivity): LinearLayout {
        val ui = a.ui
        val p = a.prefs
        val card = ui.card()
        card.addView(ui.heading("Assistant"))
        card.addView(ui.switchRow(
            "Tap the glasses to talk",
            "A tap on the glasses' touch pad asks the assistant a question while the app is open",
            p.tapToTalk,
        ) { p.tapToTalk = it })
        card.addView(ui.switchRow(
            "Use the glasses' mic and speaker",
            "When the glasses are connected as your phone's Bluetooth headset",
            p.useGlassesAudio,
        ) { p.useGlassesAudio = it })
        card.addView(ui.switchRow("Read photo answers aloud", null, p.speakPhotoAnswers) { p.speakPhotoAnswers = it })
        return card
    }

    private fun keyDialog(a: MainActivity, provider: AiProvider) {
        val input = android.widget.EditText(a).apply {
            hint = "Paste your key"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(a)
            .setTitle("${provider.label} key")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val k = input.text.toString().trim()
                if (k.isEmpty()) a.toast("Nothing to save.")
                else { a.prefs.setAiKey(provider, k); a.toast("Key saved."); a.refresh() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun pickLanguage(a: MainActivity, title: String, current: String, onPick: (String) -> Unit) {
        val names = Languages.names.toTypedArray()
        AlertDialog.Builder(a)
            .setTitle(title)
            .setSingleChoiceItems(names, names.indexOf(current).coerceAtLeast(0)) { d, which ->
                onPick(names[which])
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
