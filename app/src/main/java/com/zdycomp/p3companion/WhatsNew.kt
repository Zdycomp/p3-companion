package com.zdycomp.p3companion

/** What's new in this version, shown once after an update and on the About screen. */
object WhatsNew {
    const val VERSION = "4.1.1"

    val headlines = listOf(
        "Sharper live view: it opens with the glasses' highest-detail setting and steps down by itself only if that one sends no picture",
        "The Wi-Fi screen says plainly that the glasses have no Wi-Fi; Wake and the hardware tests ask you to connect the glasses first",
        "Hands-free voice chat through the glasses: their own mic stays open the whole conversation, answers in Gemini's voice, and you can talk over a reply to interrupt it",
        "Tap the glasses to send, interrupt or cancel; double tap starts or ends voice chat; every button, touch and media key can be set in Settings",
        "The mic adapts by itself: glasses stream, call audio or phone mic depending on the link, live view, battery and noise, with the one in use shown",
        "Burst photo and Sharp snapshot: several pictures aligned to a hundredth of a pixel and combined, for real extra detail (originals kept)",
    )

    val details = listOf(
        "4.1.1" to listOf(
            "Live view now asks the glasses for their most detailed picture ([01 03]: more data a picture at the same 320×240, so less blur). If they refuse it, or no picture comes in 3 seconds, it tries the next setting down, and remembers the one that worked. Try higher quality also updates it.",
            "The glasses' own mic is loud (peaks at full scale). The voice chain already turns it down to a steady level with no clipping; a test now checks that.",
            "Wi-Fi: the glasses have no Wi-Fi of their own (8 scans and probes found none). Marking a network as a device's is now under an Advanced note; a marked network warns you in case it's your router. Wake says \"Connect the glasses first\" when they aren't on Bluetooth.",
            "Hardware tests: when the glasses aren't connected over Bluetooth, the screen says so at the top and the tests that need them don't run.",
        ),
        "Voice chat" to listOf(
            "Start it with the Hands-free voice chat button on the Assistant tab, by saying \"start voice chat\", or with a double tap on the glasses. Say \"stop voice chat\" (or double tap) to end it; it also ends after a minute with no question.",
            "On the glasses' data-link mic the stream is started once and kept open, so there's no call mode: answers play in Gemini's voice on media audio. Each turn goes to Gemini as the glasses' own Opus sound (about 2 KB a second); if Gemini won't take that, the app sends a WAV instead and remembers which works.",
            "While an answer plays, the mic doesn't pick it up (the echo gate). Speaking clearly louder interrupts it; a tap on the glasses always does. The previous turns are kept, so you can follow up.",
            "Mic audio the glasses start by themselves (their own button or wake word) now goes to voice chat instead of being dropped.",
        ),
        "The mic adapts" to listOf(
            "Voice chat prefers the glasses' own mic stream, then their call audio, then the phone's mic. Live view, a photo coming over, a link under 3 s old or a glasses battery under 20% move it off the glasses by itself; a noisy place makes a single question use call audio, which has the phone's noise suppression. The Assistant tab shows which mic is in use and why.",
            "Every mic now goes through the voice chain: an 80 Hz high-pass, a noise suppressor that tracks the noise floor, automatic level to a steady loudness, and a limiter. Its speech detector looks at loudness, zero crossings and the spectrum, so fans, hum, hiss and clicks don't start a turn.",
            "In voice chat the reply's volume goes up a step in a noisy place and down in a silent room (within a cap you set), and back afterwards. In voice chat on call audio, the call volume follows the media volume.",
        ),
        "Buttons and keys" to listOf(
            "Settings › Glasses buttons: what a tap, double tap, long press, swipes, the photo button's double press, the rear button, the media keys and the voice-dial key do: talk, voice chat, photo, burst photo, sharp snapshot, live view, repeat, volume, flashlight, memo or nothing.",
            "Taps and double taps are told apart by timing. Long press, swipes and the rear button work once Hardware test T1 has learned their codes on your glasses. Every touch push is logged with its bytes and timing.",
            "Battery and charging changes the glasses push are logged and shown (\"Glasses charging (77%)\"). The glasses' own spoken prompts can be set to English in Settings, only if they offer English and aren't English already.",
        ),
        "Sharper pictures" to listOf(
            "Burst photo takes 4 photos (3-6 in Settings › Pictures) while you hold still, about 6 s each, then lines them up to a hundredth of a pixel, leaves out anything that moved, and combines them on a 2× (or 3×) grid with back-projection and a light deconvolution. The small shifts between shots sample the scene between the pixels, which a single photo can't. It's saved as a separate \"-burst\" copy; Gallery › Details says \"Burst ×4, 1280×960 from 640×480\".",
            "Sharp snapshot in live view does the same at once with the last 16 live pictures (no shutter lag).",
            "Honest limits: the glasses send 640×480 photos and a 320×240 live picture. Combining frames recovers detail the pictures aliased and noise hid, about 1.2-1.5× in real resolution when you and the scene hold still; it can't add what the camera never captured. The 4.0 enhanced copy is unchanged.",
        ),
        "Hardware tests" to listOf(
            "Diagnostics › Hardware tests runs the device tests from the hardware map: learn touch codes (T1), the glasses' mic sizes (T2), Ogg Opus against WAV with Gemini (T4), wideband or narrowband call audio (T5), the photo button's double press (T7), raw setup bytes (T13), and the steps for the rest. Results go to the connection log for sharing.",
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
