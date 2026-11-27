package com.zdycomp.p3companion

/** What's new in this version, shown once after an update and on the About screen. */
object WhatsNew {
    const val VERSION = "3.9.1"

    val headlines = listOf(
        "When the glasses drop Bluetooth altogether, the app waits for them to come back and settle before reconnecting",
        "Wi-Fi: Join on this phone saves an ordinary network the proper way, through Android's own dialog, with internet",
        "Wi-Fi: every network and Wi-Fi Direct device is listed, likely glasses first, with details for each",
        "Wi-Fi: when joining the glasses fails, the app now says why",
        "Wi-Fi: Wake the glasses' Wi-Fi, and the phone's connection details with a 60 s signal trace",
        "The connection log notes Bluetooth profile changes to help explain drops",
    )

    val details = listOf(
        "Joining networks" to listOf(
            "Tap a network, then Join on this phone. Android shows its own dialog and saves it like any network you add in Settings, with internet. The app then says plainly whether it was saved, already saved, or refused.",
            "The 3.8 Connect used a request meant only for a device's own network (local, no internet), which is why an ordinary network like your home Wi-Fi couldn't be joined. That request is now used only for the glasses, or a network you mark as a device's.",
            "When joining the glasses fails, the app says why: Android's dialog timed out, it was declined, Android never found the network, or the phone can't use its band or security.",
            "A password has 8 to 63 plain characters. You type it, it goes straight to Android, and the app never saves or logs it.",
        ),
        "Lists" to listOf(
            "Every network nearby, hidden ones too, with signal bars and dBm, band, security, and whether it's connected or saved from this app.",
            "Every Wi-Fi Direct device with its name, type and status, likely glasses first. Tap one for \"Is this the glasses?\" Nothing connects until you tap Connect.",
        ),
        "Glasses Wi-Fi" to listOf(
            "Wake the glasses' Wi-Fi scans while asking the glasses over Bluetooth, read-only, whether anything turns their Wi-Fi on, then scans again 5 s and 15 s later.",
            "The glasses' documentation names no Wi-Fi command, so this is a long shot. The app says plainly when no glasses network has been seen in any scan.",
        ),
        "This phone's Wi-Fi" to listOf(
            "Shows the network, signal over the last minute, link speed, Wi-Fi standard, IP address, gateway and DNS.",
            "Android 10 and later don't let apps switch Wi-Fi on or off, so the Wi-Fi switch opens Android's own Wi-Fi panel.",
        ),
        "Bluetooth drops" to listOf(
            "3.9.1: when the glasses drop their whole Bluetooth connection (not just the app's link), the app says so and waits for them to come back, for hands-free to settle and 1.5 s more before reconnecting, instead of trying again while they're still restarting. The Glasses tab shows \"The glasses dropped Bluetooth; reconnecting when they're ready\" meanwhile.",
            "3.9.1: if a reconnect dies within 5 s, the next try waits the same way. Once it's back, the log says how long it took and how many tries. Other drops keep the usual quick retries.",
            "The connection log now notes when the glasses' Bluetooth audio connections change, with the time to the millisecond, and how long since the last data when the link drops. Nothing about connecting changed.",
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
