package com.zdycomp.p3companion

/** What's new in this version, shown once after an update and on the About screen. */
object WhatsNew {
    const val VERSION = "3.3.0"

    val headlines = listOf(
        "Commands no longer wait for an answer that already came: photos, snapshots and sign-in are quicker",
        "Live view listens longer, logs everything the glasses send, and also looks for the glasses on Wi-Fi",
        "Voice questions: the app records the mic itself and switches to the phone's mic if the glasses' mic is silent",
        "Answers to voice questions start speaking right away on the glasses",
        "Tapping the glasses no longer upsets the Bluetooth link",
        "SupRevolt: scan a glasses photo, a document or text on your own server, with a verdict you can trust",
        "Every scan shows its confidence out of 256, and says \"unknown\" instead of guessing",
        "Say \"scan this\": the glasses take a photo and the verdict is read back to you",
        "Send to SupRevolt from the Gallery; open it from Settings, Glasses or the Assistant",
    )

    val details = listOf(
        "Glasses link" to listOf(
            "The glasses answer most commands in a form the app didn't recognise, so it waited 2 to 4 seconds for nothing each time. Every answer is now recognised at once.",
            "Taking a photo, saving it and Snapshot view no longer pause between steps.",
        ),
        "Live view" to listOf(
            "The live view test runs again on glasses that said \"no video\" before, now that the glasses' answers are understood.",
            "It waits for the glasses to say the preview is on, listens 10 seconds, asks for frames in several ways, and logs every byte the glasses send.",
            "Small pictures sent as the preview are shown too, not only video.",
            "With your OK (Nearby devices), it looks for the glasses on Wi-Fi and tells you if they show up there.",
            "Taps on the glasses are ignored while the test runs, so the mic can't close the live view.",
        ),
        "Listening" to listOf(
            "On Android 13 and later the app records the mic itself: the glasses' mic first, and the phone's mic at once if the glasses' mic is silent.",
            "If the speech service didn't catch anything, it listens once more with the phone's mic by itself.",
            "The glasses' call audio never starts while they connect, during a photo transfer or the live view test, or in the first 3 seconds of a new link.",
        ),
        "Speaking" to listOf(
            "When the glasses' call audio is already up after a question, the answer plays on it straight away instead of switching to media audio first, then the call audio is let go.",
            "The connection log shows which mic was used and how long the answer took to start.",
        ),
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
