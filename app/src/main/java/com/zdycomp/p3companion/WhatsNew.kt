package com.zdycomp.p3companion

/** What's new in this version, shown once after an update and on the About screen. */
object WhatsNew {
    const val VERSION = "4.0.0"

    val headlines = listOf(
        "Gemini now writes down what you say: far better accuracy than the phone's recognizer, with a live mic level meter",
        "Every spoken reply in Gemini's natural voice (Kore by default, 30 to choose from), no more phone or Chinese voice",
        "Simple commands: 12 easy ones offline, and anything else in your own words: the AI works the app for you",
        "A real flashlight: stays on until you turn it off, with a big button, a Quick Settings tile and Turn off in the notification",
        "Sharper pictures: an enhanced 1280×960 copy of every 640×480 Bluetooth photo (the original is kept), and live sharpening",
        "The app now finds the newest Gemini models for your key by itself",
    )

    val details = listOf(
        "Microphone" to listOf(
            "With a Gemini key, the app records the mic itself (noise suppression, auto gain and echo cancelling when the phone has them), ends after about a second of quiet (at most 20 s), and Gemini writes down exactly what you said. The phone's own recognizer is still there offline and without a Gemini key.",
            "A level meter shows while it listens, so you can see the mic hears you. Hold to talk (Settings › Assistant) records only while you hold the button.",
            "Voice questions use the glasses' call audio when it passed Test voice, otherwise the phone's mic. The connection log notes the mic, its level, how it ended and how long Gemini took.",
        ),
        "Gemini's voice" to listOf(
            "Answers, commands, translations and read-aloud are spoken by Gemini's voice, streamed so the first words play as soon as they arrive, through the glasses when they're connected. Pick a voice and preview it in Settings › Assistant.",
            "Offline or when Gemini's voice fails, the phone's Google voice in your reply language takes over (never a Chinese voice for an English answer), and the log says which voice spoke.",
        ),
        "Simple commands" to listOf(
            "Twelve commands to remember: take a picture, start or stop video, what am I looking at, read this, translate, flashlight on or off, volume up or down, battery, live view, stop, help, scan this. They work offline at once.",
            "Anything else, say it your way (\"I need some light\", \"film this\", \"how much room is left on the glasses\"): Gemini picks the app action. Deleting or clearing still asks you to say yes. Every old phrase still works.",
            "A bare \"battery\" now gives both the glasses' and the phone's battery when the glasses are connected.",
        ),
        "Flashlight" to listOf(
            "The phone's torch stays on until you turn it off: the big button on the Glasses tab, the Flashlight tile in Quick Settings, Turn off in the notification, or \"flashlight off\". An optional auto-off timer is in Settings › Flashlight (off by default).",
            "It's only ever the phone's torch: no flashlight command takes a photo or uses the glasses' camera. If another app has the camera, it says so.",
        ),
        "Pictures" to listOf(
            "Bluetooth photos are 640×480 and live view 320×240: that's all the glasses send (they have no Wi-Fi), so nothing can make the camera capture more.",
            "Within that, Enhance photos (on by default) saves a separate \"-enhanced\" copy: denoised, upscaled 2× (or 3×) with Lanczos, sharpened without halos, with a touch of contrast. Gallery › Details says \"Enhanced 1280×960 from 640×480\". Live-view snapshots get the same treatment.",
            "Live view: \"Sharpen the live picture\" adds a light sharpening on the phone, and turns itself off if the picture drops below 25 per second.",
        ),
        "Models" to listOf(
            "With a Gemini key the app reads Google's model list (about once a week) and uses the newest stable Flash model for chat and speech-to-text, and the newest speech model for its voice. You can still type a model in AI setup.",
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
