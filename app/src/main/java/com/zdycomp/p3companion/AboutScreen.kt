package com.zdycomp.p3companion

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import java.text.DateFormat
import java.util.Date

/** About P3 Companion (OPTIONS-PLAN B4): version, what's new, credits. Back returns to Settings. */
class AboutScreen(private val a: MainActivity) : Screen {
    override val label = "About"

    override fun build(): View {
        val ui = a.ui
        val s = ui.s
        val col = ui.page()

        val pi = runCatching { a.packageManager.getPackageInfo(a.packageName, 0) }.getOrNull()
        val version = pi?.versionName ?: "?"
        val code = pi?.longVersionCode ?: 0L
        val updated = pi?.lastUpdateTime?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }

        val head = ui.column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, ui.dp(16), 0, ui.dp(16))
        }
        head.addView(ImageView(a).apply {
            setImageDrawable(a.getDrawable(R.mipmap.ic_launcher))
            contentDescription = null
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ui.dp(72), ui.dp(72)))
        head.addView(ui.text("P3 Companion", Type.HEADLINE_SMALL).apply { setPadding(0, ui.dp(12), 0, 0); isAccessibilityHeading = true })
        head.addView(ui.text("Version $version ($code)", Type.BODY_MEDIUM, s.onSurfaceVariant))
        if (updated != null) head.addView(ui.text("Updated $updated", Type.BODY_SMALL, s.onSurfaceVariant))
        col.addView(head)

        val g = a.glasses
        if (g.firmware != null || g.model != null || g.deviceName != null) {
            val gl = ui.card()
            gl.addView(ui.heading("Your glasses", R.drawable.ic_glasses_tab))
            gl.addView(ui.body(listOfNotNull(
                g.deviceName?.let { "Name: $it" },
                g.model?.let { "Model: $it" },
                g.firmware?.let { "Firmware: $it" },
            ).joinToString("\n"), muted = true, sizeSp = 14f))
            col.addView(gl)
        }

        val news = ui.card()
        news.addView(ui.heading("What's new in ${WhatsNew.VERSION}", R.drawable.ic_auto_awesome))
        WhatsNew.details.forEach { (section, lines) ->
            news.addView(ui.text(section, Type.TITLE_SMALL, s.primary).apply { setPadding(0, ui.dp(10), 0, ui.dp(4)) })
            lines.forEach { line ->
                news.addView(ui.row().apply {
                    gravity = Gravity.TOP
                    setPadding(0, ui.dp(3), 0, ui.dp(3))
                    addView(ui.icon(R.drawable.ic_check, s.primary, 16), LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)).apply {
                        marginEnd = ui.dp(10); topMargin = ui.dp(2)
                    })
                    addView(ui.body(line, sizeSp = 14f), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                })
            }
        }
        col.addView(news)

        val credits = ui.outlinedCard()
        credits.addView(ui.heading("Credits", R.drawable.ic_info))
        credits.addView(ui.body(
            "No account, no ads. Everything works offline except the Assistant, which sends only what you ask to the AI service you choose.\n\n" +
                "Bluetooth protocol written from the MIT-licensed XK One Pro SDK by Gustavo Beneduzi. " +
                "Research notes from LensFix by João Victor Souza (MIT); no code from it is included. " +
                "Icons: Material Icons (Apache 2.0).\n\n" +
                "Not made by or connected to NovaSight, Shenju or LensMoo.",
            muted = true, sizeSp = 13f,
        ))
        col.addView(credits)

        col.addView(ui.button("Copy app and glasses info", Btn.TONAL, R.drawable.ic_copy) {
            val info = buildString {
                appendLine("P3 Companion $version ($code)")
                appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
                g.model?.let { appendLine("Glasses model: $it") }
                g.firmware?.let { appendLine("Glasses firmware: $it") }
            }
            a.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("P3 Companion info", info))
            a.toast("Copied.")
        })
        col.addView(ui.button("Share what's new", Btn.TEXT, R.drawable.ic_share, full = false) {
            a.shareText("P3 Companion ${WhatsNew.VERSION}", WhatsNew.text())
        })

        return ui.screen("About", col, back = { a.goBack() })
    }
}
