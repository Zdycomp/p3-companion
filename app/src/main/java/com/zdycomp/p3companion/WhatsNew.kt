package com.zdycomp.p3companion

/** What's new in this version, shown once after an update and on the About screen. */
object WhatsNew {
    const val VERSION = "3.2.0"

    val headlines = listOf(
        "SupRevolt: scan a glasses photo, a document or text on your own server, with a verdict you can trust",
        "Every scan shows its confidence out of 256, and says \"unknown\" instead of guessing",
        "Say \"scan this\": the glasses take a photo and the verdict is read back to you",
        "Send to SupRevolt from the Gallery; open it from Settings, Glasses or the Assistant",
    )

    val details = listOf(
        "SupRevolt" to listOf(
            "Settings › SupRevolt: your server's https:// address and the access token it printed once. The token stays in the app's private storage, is shown masked and is never written to a settings file.",
            "Scan a new or the last glasses photo, any document you pick, or text you type. Pick which scans to run, or All.",
            "Results show the verdict (Clear, Caution, Danger, or Unknown with the reason), and one row per scan with a 0 to 256 confidence bar. Tap a row for details. Skipped scans are behind Show skipped.",
            "Share report makes a plain summary without the server address or token.",
            "Plain http:// is only allowed for this phone or your home network, after a warning.",
        ),
        "Voice" to listOf(
            "\"Scan this\", \"run a scan\" or \"SupRevolt scan\" takes a photo with the glasses, scans it and says the verdict.",
        ),
    )

    /** Plain text for sharing or the changelog. */
    fun text(): String = buildString {
        appendLine("P3 Companion $VERSION")
        details.forEach { (section, lines) ->
            appendLine()
            appendLine(section)
            lines.forEach { appendLine("• $it") }
        }
    }
}
