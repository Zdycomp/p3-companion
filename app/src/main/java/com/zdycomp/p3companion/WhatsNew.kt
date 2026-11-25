package com.zdycomp.p3companion

/** What's new in this version, shown once after an update and on the About screen. */
object WhatsNew {
    const val VERSION = "3.5.0"

    val headlines = listOf(
        "Voice questions: the speech service now records the mic itself, so the phone's mic is heard again",
        "If one speech service hears nothing, Google's network recognizer listens once more, and the one that works is remembered",
        "The glasses' mic through call audio is only used when the link really opens and isn't silent",
        "New: the glasses' own mic over the data link, decoded on the phone, no call audio needed",
        "Test voice tries every mic, says \"hello glasses\" with each, and voice questions use the one that works",
        "The Assistant screen shows which mic is in use",
        "Live view no longer closes and reopens when the screen refreshes or the live view test finds video",
        "No automatic photos while live view is opening or on",
        "Broken live pictures are logged less often and the picture waits for the next key frame",
    )

    val details = listOf(
        "Voice" to listOf(
            "In 3.4 the app recorded the mic itself and handed the sound to the speech service. Google's on-phone recognizer heard nothing in it, even from the phone's mic. Now the speech service opens the mic itself.",
            "If a speech service listens but understands nothing, the next one (usually Google's network recognizer) listens once more. A service only counts as working when it gives words; one that said \"no match\" while another understood is tried last from then on.",
            "The glasses' call audio counts as ready only when the phone says the link is up (3.4 also went on after 1.5 seconds with no signal, which recorded silence). Two ways of opening it are tried, 2 seconds each, and every step is in the connection log.",
            "Before a question goes through the glasses' call audio, 0.4 seconds are measured; a silent mic is skipped and the next mic is used.",
            "The glasses can also send their mic over the data link (5750, Opus). The app turns it on, decodes it, and feeds the speech service 16 kHz sound, ending 1 second after you stop talking. A speech service that won't take it is remembered and another mic is used.",
            "The order is: the mic Test voice found working, then the others, and the phone's mic last.",
        ),
        "Test voice" to listOf(
            "Diagnostics › Test voice records 3 seconds from the phone's mic, the glasses' call audio and the glasses' data-link mic, and shows each level in dBFS.",
            "With each mic it asks you to say \"hello glasses\" and shows what was heard, or the error, and which speech service listened.",
            "It ends with one line saying what works, and voice questions start with that mic from then on.",
        ),
        "Live view" to listOf(
            "A close waits 1.5 seconds: if the screen opens again in that time (a refresh, coming back to the app), the video simply keeps running.",
            "When the live view test finds video on the Live view screen, it stays open instead of closing and opening again.",
            "While live view is opening or on, the app only takes a photo you asked for. When the glasses' own button takes one, the log says so, the photo is saved, and live view opens again.",
            "A broken picture is still skipped; the log notes it three times, then once every 10 seconds with a count, and the picture waits for the next key frame (at most 2 seconds).",
            "When the mic starts, the live view on the glasses is now really closed.",
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
