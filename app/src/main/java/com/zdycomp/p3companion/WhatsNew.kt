package com.zdycomp.p3companion

/** What's new in this version, shown once after an update and on the About screen. */
object WhatsNew {
    const val VERSION = "3.1.0"

    val headlines = listOf(
        "Live view finds out what your glasses can do, and shows snapshots when they can't stream video",
        "Answers start speaking sooner: the first sentence plays while the rest is still coming",
        "Voice input works on more phones: if one speech service fails, the next one is tried",
        "48 voice commands that work offline: \"take a picture\", \"battery\", \"set a timer for 5 minutes\" and more",
        "New Speak through option: glasses audio (fast) or call audio",
    )

    val details = listOf(
        "Live view" to listOf(
            "If no video arrives within 6 seconds, the app tests every live view command your glasses might use and remembers the one that works.",
            "When your glasses don't send video over Bluetooth, Live view switches to Snapshot view: a new picture every few seconds, with Stop and Save.",
            "Snapshots are deleted after they're shown unless you tap Save this one or turn on Keep every snapshot.",
            "Diagnostics › Test live view runs the same test and lets you share its full log.",
        ),
        "Speaking" to listOf(
            "The voice is ready as soon as the app opens, and answers are streamed and read sentence by sentence.",
            "Glasses audio (fast) plays answers on the glasses' media sound without waiting for the call link; Call audio keeps the old behaviour for the whole conversation.",
            "The connection log shows how long each answer took to start.",
        ),
        "Listening" to listOf(
            "The app picks a speech service that works on your phone (Google's first), tries the next one if it fails, and remembers the good one.",
            "If the glasses' mic isn't ready within 1.5 seconds, the phone's mic is used instead.",
            "The Assistant shows which speech service listened and the exact error; Diagnostics › Test voice checks it in one tap.",
        ),
        "Voice commands" to listOf(
            "Camera, recording, gallery, translation, timers and alarms, flashlight, volume, navigation, settings and accessibility, all handled on the phone.",
            "Say \"what can I say\" for the list. Deleting a photo or clearing the chat asks you to say yes first.",
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
