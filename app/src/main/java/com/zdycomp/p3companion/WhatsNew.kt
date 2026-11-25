package com.zdycomp.p3companion

/** What's new in this version, shown once after an update and on the About screen. */
object WhatsNew {
    const val VERSION = "3.8.0"

    val headlines = listOf(
        "Every Bluetooth photo now logs its real pixel size and file size (for example \"Photo 640x480, 71 KB\")",
        "Import shows each file's pixel size and file size, and one line comparing cable photos with Bluetooth photos",
        "Gallery › Details shows a photo's real pixel size",
        "Diagnostics › Find full-size photos asks the glasses, safely, whether they keep a larger copy",
        "New Wi-Fi screen: the phone's Wi-Fi, finding the glasses on Wi-Fi, Connect and Disconnect",
    )

    val details = listOf(
        "Photo size" to listOf(
            "The app now reads each photo's real size from the picture itself. The connection log says \"Photo 640x480, 71 KB\" for every Bluetooth photo, with its parts and speed.",
            "Import lists every copied file with its pixel size and file size, and checks each photo reads back byte for byte (no recompression; EXIF and everything else kept).",
            "Under the result, one line compares the two: \"Cable photos: 1280x960 avg 400 KB; Bluetooth photos: 640x480 avg 70 KB\".",
            "In the Gallery, press and hold a photo, then More (⋮) › Details for its pixel size, file size and date.",
            "A 20 MP picture would be about 3 to 8 MB. Bluetooth photos of 54 to 75 KB are most likely 640x480. A bigger number in another app usually means the app enlarged the picture, which adds no real detail.",
        ),
        "Find full-size photos" to listOf(
            "In Diagnostics. Takes one photo, measures it, then asks the glasses (about 90 s) whether they keep photos: the photo counts before and after, their album and transfer commands, one part past the last, and Wi-Fi.",
            "It never deletes and never sends the update or delete commands. It ends with one plain line, like \"Bluetooth photos are 640x480; no larger copy found\", and a log you can share.",
        ),
        "Wi-Fi" to listOf(
            "Open it from the Glasses tab or Settings. It shows the phone's Wi-Fi: on or off, the network, signal, link speed and frequency.",
            "Find the glasses on Wi-Fi lists the networks and Wi-Fi Direct devices nearby, with ones that look like your glasses first.",
            "Connect joins a glasses network for this app only (Android asks you first), or a Wi-Fi Direct device. A password is typed by you, used once and never saved. Disconnect, or leaving the screen, ends it.",
            "Once joined, Look for services checks the glasses' web and video ports and shows what answers.",
            "Android shows Wi-Fi names only to apps with the Location permission; it's asked when you open the screen, and the app never uses your location. The app never turns Wi-Fi on or off: the Wi-Fi panel button opens Android's own.",
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
