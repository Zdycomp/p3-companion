package com.zdycomp.p3companion

/** What's new in this version, shown once after an update and on the About screen. */
object WhatsNew {
    const val VERSION = "3.0.0"

    val headlines = listOf(
        "A fresh look: colors from your wallpaper, accent colors, pure black and high contrast",
        "New Glasses tab: battery ring, big shutter, record button and a self-timer",
        "Quick Settings tile and home-screen shortcuts for a photo, live view or voice chat",
        "Gallery: select several to share or delete, sort, and 2 to 5 columns",
        "Assistant: voice speed and pitch, memory length and your own quick questions",
        "Lock the app, back up your settings, low-battery and photo notifications",
    )

    val details = listOf(
        "Appearance" to listOf(
            "Material You colors from your wallpaper on Android 12 and newer, or pick Violet, Ocean, Emerald, Sunset, Rose, Graphite or any hue.",
            "Pure black for OLED screens, high contrast, four text sizes, reduce motion and vibration on taps.",
            "New icons, bottom bar with an animated indicator, tonal cards, smoother buttons and switches.",
        ),
        "Glasses" to listOf(
            "Connect when the app opens and reconnect after a drop are now separate switches.",
            "Choose whether the glasses stay connected while the app is in the background.",
            "Low-battery alert, photo-saved notification with a picture, shutter sound and vibration on the phone.",
            "Open the photo right after taking it, and a 3, 5 or 10 second self-timer.",
        ),
        "Shortcuts" to listOf(
            "Quick Settings tile: one tap takes a photo while the glasses are connected.",
            "Long-press the app icon for Take photo, Live view and Voice chat.",
        ),
        "Gallery" to listOf("Select several items to share or delete, sort four ways, grid of 2 to 5 columns."),
        "Assistant" to listOf(
            "Voice speed and pitch with a sample, how much of the conversation is remembered, editable quick photo questions, and an option to only show voice answers.",
        ),
        "Privacy and storage" to listOf(
            "Lock the app with your fingerprint, face or screen lock; hide the app in Recents.",
            "Save settings to a file and restore them (keys are never written to the file).",
            "See how much space the app uses and clear temporary files.",
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
