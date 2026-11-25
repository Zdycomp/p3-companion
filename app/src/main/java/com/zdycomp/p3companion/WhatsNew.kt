package com.zdycomp.p3companion

/** What's new in this version, shown once after an update and on the About screen. */
object WhatsNew {
    const val VERSION = "3.6.0"

    val headlines = listOf(
        "New: Conversation translate keeps listening, translates every phrase, shows it with the time and saves the log",
        "Hear any phrase again, read a whole conversation aloud, share it as text, or delete it",
        "Two-way mode, auto-detect, speak each translation through the glasses, and keep going in the background",
        "Live view: the picture has its exact shape, fit or fill, rotate, pinch to zoom and full screen",
        "Live view shows \"Live\" with the delay in milliseconds and drops late pictures instead of lagging",
        "Snapshots are saved at the glasses' own size (or 2× and sharpened), recordings can have the phone's mic",
        "Live view opens again by itself after Bluetooth drops, if you're still on the screen",
        "\"Try higher quality\" tests whether the glasses send a bigger picture (only when you ask)",
    )

    val details = listOf(
        "Conversation translate" to listOf(
            "Open it from the Assistant tab (Translate) or say \"translate conversation\" or \"start translating\". It uses the mic that works from 3.5.",
            "Each phrase shows in grey while you speak, then the time, what was said, the translation and its language. Say \"stop\" or \"stop translating\" to end.",
            "Two-way: English is translated into Spanish and Spanish into English, by the language the AI hears; after each phrase it listens for the other person. Auto-detect lets the AI work out the language.",
            "Each translation can be spoken in its own language, through the glasses when they're connected; listening pauses while it speaks.",
            "Every session is saved on the phone (never with an API key). Open one to read it, tap a phrase to hear it again, Read all, Share or Delete.",
            "Without an AI key the words are still logged, and the screen shows how to add one.",
            "Leaving the screen stops it, unless \"Keep translating in the background\" is on: a notification with Stop shows then.",
        ),
        "Live view" to listOf(
            "The frame takes the video's exact shape. Fit or Fill, Rotate in quarter turns, pinch to zoom, drag to look around, double-tap to zoom or reset.",
            "Full screen turns the phone sideways and hides everything but the picture; the controls come back with a tap and hide after 3 seconds.",
            "Pictures are shown the moment they're decoded and a late one is skipped, so the picture doesn't fall behind. The stats show \"Live\" and the delay from the glasses to the screen.",
            "Snapshots come from the decoded picture at the glasses' own size, turned like the screen, optionally 2× larger and sharpened. Recordings are at the native size and can include the phone's mic.",
            "If Bluetooth drops while you watch, the screen says so and opens live view again when the glasses are back.",
            "Try higher quality (Live view and Diagnostics) opens the live view with 5 other settings for 3 seconds each and closes it again after each. If one gives a bigger picture at 15 fps or more, you can pick it under Quality; otherwise it says the glasses send 320×240 only.",
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
