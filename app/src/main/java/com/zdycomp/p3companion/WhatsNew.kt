package com.zdycomp.p3companion

/** What's new in this version, shown once after an update and on the About screen. */
object WhatsNew {
    const val VERSION = "3.7.0"

    val headlines = listOf(
        "Glasses mic: the call-audio route no longer trips over a speech service left running (one at a time now)",
        "Glasses mic over the data link: the sound is decoded properly and fed to the speech service in real time",
        "If this phone's speech service won't take the glasses' sound, Gemini writes down what you said",
        "Test voice: Play back what the glasses heard on the phone's speaker, and share it",
        "Voice questions start with the glasses' mic whenever one of its routes works",
    )

    val details = listOf(
        "Glasses mic" to listOf(
            "Only one speech recognizer runs at a time. Each one is closed when its turn ends, and the next waits 300 ms. In 3.6 the old one was still alive, so the call-audio test got \"too many requests\".",
            "Call audio tries Google's network speech recognition first, then the on-phone one. The recognizer records the glasses' mic itself.",
            "Data link: each glasses frame is now split into its 40-byte Opus packets the right way (none lost at the start of a frame). The decoder gets the right setup, and 48 kHz is turned into 16 kHz with a real low-pass filter.",
            "The sound goes to the speech service at real-time speed, without the short gaps 3.6 slipped in between the glasses' bursts.",
            "Test voice checks whether this phone's speech service understands sound the app hands it. It records 3 s from the phone's mic and passes it on. If that fails while the phone's mic works on its own, the app stops handing sound over.",
            "Then, with a Gemini key, the app records what you say into the glasses until you stop (0.8 s of quiet, at most 15 s) and Gemini writes it down. The Assistant screen says when this is used. A Claude key alone can't do this (Claude doesn't take audio), so questions use the phone's mic.",
        ),
        "Test voice" to listOf(
            "It tests the phone mic, then the glasses' call audio, then the data link (handing sound over first, then Gemini), with a short pause between them.",
            "Play back what the glasses heard plays the last 8 s from each glasses route on the phone's own speaker, so you can hear if it was your voice or noise. Share sends the WAV files.",
            "If one of the glasses' routes works, voice questions start with it (call audio first, it opens fastest). Otherwise they use the phone's mic.",
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
