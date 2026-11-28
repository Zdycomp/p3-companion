package com.zdycomp.p3companion

import java.util.Locale
import kotlin.math.roundToInt

/**
 * 4.1: the pure decisions behind "use every part of the glasses dynamically" (docs/HARDWARE-MAP.md §5),
 * kept free of Android so the JVM tests cover them: which mic to use when ([RoutePolicy]), what each
 * touch, button and media key does ([Gesture], [HwAction], [GestureMap], [TouchClassifier]), how loud
 * the reply plays in a noisy place ([VolumePolicy]) and which audio format Gemini gets ([AudioUpload]).
 */

/** §5: the mic (and the reason) for the next voice turn, from what the link, the camera and the battery are doing. */
object RoutePolicy {
    class Inputs(
        val linked: Boolean,
        val useGlasses: Boolean,
        /** How long the link has been up, in ms (the glasses' call audio right after connecting has dropped the link). */
        val linkAgeMs: Long,
        val liveViewOn: Boolean,
        val photoTransfer: Boolean,
        val battery: Int?,
        val charging: Boolean,
        /** The last turn's noise floor in dBFS, or null. */
        val noiseDb: Double?,
        /** Test voice / real-question scores per route. */
        val scores: Map<MicRoute, Int>,
        /** The data-link audio can become words here (Gemini key or the pipe). */
        val sppUsable: Boolean,
        /** A voice-chat session (several turns) rather than one quick question. */
        val session: Boolean,
        /** The glasses' Opus stream is already running (kept warm, or started by the glasses themselves). */
        val streamWarm: Boolean,
        /** Settings: "pause live view to listen through the glasses". */
        val pauseLiveViewToListen: Boolean = false,
    )

    class Decision(val order: List<MicRoute>, val why: String, val pauseLiveView: Boolean = false) {
        val first: MicRoute get() = order.first()
        override fun toString() = order.joinToString(" → ") { it.label } + " ($why)"
    }

    const val SETTLE_MS = 3_000L
    const val LOW_BATTERY = 20
    /** A floor this loud is a noisy place (street, car). */
    const val NOISY_DB = -42.0

    private fun usable(s: Map<MicRoute, Int>, r: MicRoute) = (s[r] ?: 0) > VoiceChoice.SKIP_AT
    private fun proven(s: Map<MicRoute, Int>, r: MicRoute) = (s[r] ?: 0) > 0

    fun decide(i: Inputs): Decision {
        val phone = listOf(MicRoute.PHONE)
        if (!i.useGlasses) return Decision(phone, "glasses audio is off in Settings")
        if (!i.linked) return Decision(phone, "the glasses aren't connected")
        if (i.streamWarm && i.sppUsable) return Decision(listOf(MicRoute.GLASSES_SPP, MicRoute.PHONE), "the glasses' mic stream is already running")
        if (i.linkAgeMs < SETTLE_MS) return Decision(phone, "the link is under 3 s old")
        if (i.battery != null && i.battery < LOW_BATTERY && !i.charging) return Decision(phone, "glasses battery ${i.battery}%: saving it")
        if (i.photoTransfer) return Decision(phone, "a photo is coming over the link")
        val spp = i.sppUsable && usable(i.scores, MicRoute.GLASSES_SPP)
        val sco = usable(i.scores, MicRoute.GLASSES_SCO)
        if (i.liveViewOn) {
            return if (i.pauseLiveViewToListen && (spp || sco))
                Decision(listOfNotNull(if (spp) MicRoute.GLASSES_SPP else null, if (sco) MicRoute.GLASSES_SCO else null) + MicRoute.PHONE,
                    "live view pauses while the glasses listen", pauseLiveView = true)
            else Decision(phone, "live view is open (the glasses' mics would stop the video)")
        }
        val noisy = i.noiseDb != null && i.noiseDb > NOISY_DB
        val out = ArrayList<MicRoute>(3)
        val why: String
        if (i.session) {
            // A conversation: the data-link stream, started once and kept warm, needs no call mode, so replies stay on media audio.
            if (spp) out += MicRoute.GLASSES_SPP
            if (sco && (proven(i.scores, MicRoute.GLASSES_SCO) || !spp)) out += MicRoute.GLASSES_SCO
            why = if (spp) "voice chat: the glasses' own mic stream, kept warm" else "voice chat: the glasses' call audio"
        } else if (noisy && proven(i.scores, MicRoute.GLASSES_SCO)) {
            // Noisy: the call audio gets the phone's own noise suppression and echo cancelling.
            out += MicRoute.GLASSES_SCO
            if (spp) out += MicRoute.GLASSES_SPP
            why = "noisy here (floor %.0f dBFS): call audio has the phone's noise suppression".format(Locale.US, i.noiseDb)
        } else {
            // One quick question: call audio opens in under a second; the data-link stream takes about 4 s.
            if (proven(i.scores, MicRoute.GLASSES_SCO)) out += MicRoute.GLASSES_SCO
            if (spp && proven(i.scores, MicRoute.GLASSES_SPP)) out += MicRoute.GLASSES_SPP
            if (out.isEmpty() && spp) out += MicRoute.GLASSES_SPP
            why = if (out.firstOrNull() == MicRoute.GLASSES_SCO) "one question: call audio opens fastest" else if (out.isNotEmpty()) "one question: the glasses' mic stream" else "no glasses mic has worked yet"
        }
        out += MicRoute.PHONE
        return Decision(out.distinct(), why)
    }
}

/** Every input the glasses (and the Bluetooth headset profile) can give the app. */
enum class Gesture(val label: String) {
    TAP("Tap on the touch pad"),
    DOUBLE_TAP("Double tap on the touch pad"),
    LONG_PRESS("Long press on the touch pad"),
    SWIPE_FORWARD("Swipe forward"),
    SWIPE_BACK("Swipe back"),
    PHOTO_DOUBLE_PRESS("Photo button, double press"),
    REAR_BUTTON("Rear button"),
    MEDIA_PLAY_PAUSE("Media key: play/pause"),
    MEDIA_NEXT("Media key: next"),
    MEDIA_PREVIOUS("Media key: previous"),
    VOICE_KEY("Voice-dial key (hands-free)"),
}

/** What a gesture can do. */
enum class HwAction(val label: String) {
    VOICE_TURN("Talk: start, send, interrupt or cancel"),
    VOICE_CHAT_TOGGLE("Start or stop voice chat"),
    TAKE_PHOTO("Take a photo"),
    BURST_PHOTO("Burst photo (sharpest)"),
    SHARP_SNAPSHOT("Sharp snapshot from live view"),
    LIVE_VIEW("Open or close live view"),
    REPEAT_LAST("Say the last answer again"),
    VOLUME_UP("Volume up"),
    VOLUME_DOWN("Volume down"),
    FLASHLIGHT("Flashlight on or off"),
    VOICE_MEMO("Start or stop a voice memo"),
    NOTHING("Nothing"),
}

/** The user's gesture → action table, saved as "TAP:VOICE_TURN,DOUBLE_TAP:VOICE_CHAT_TOGGLE,…". */
object GestureMap {
    val DEFAULTS: Map<Gesture, HwAction> = linkedMapOf(
        Gesture.TAP to HwAction.VOICE_TURN,
        Gesture.DOUBLE_TAP to HwAction.VOICE_CHAT_TOGGLE,
        Gesture.LONG_PRESS to HwAction.TAKE_PHOTO,
        Gesture.SWIPE_FORWARD to HwAction.VOLUME_UP,
        Gesture.SWIPE_BACK to HwAction.VOLUME_DOWN,
        Gesture.PHOTO_DOUBLE_PRESS to HwAction.BURST_PHOTO,
        Gesture.REAR_BUTTON to HwAction.REPEAT_LAST,
        Gesture.MEDIA_PLAY_PAUSE to HwAction.VOICE_TURN,
        Gesture.MEDIA_NEXT to HwAction.TAKE_PHOTO,
        Gesture.MEDIA_PREVIOUS to HwAction.REPEAT_LAST,
        Gesture.VOICE_KEY to HwAction.VOICE_CHAT_TOGGLE,
    )

    fun parse(s: String?): Map<Gesture, HwAction> {
        val out = LinkedHashMap(DEFAULTS)
        s?.split(',')?.forEach { part ->
            val g = Gesture.values().firstOrNull { it.name == part.substringBefore(':').trim() } ?: return@forEach
            val a = HwAction.values().firstOrNull { it.name == part.substringAfter(':', "").trim() } ?: return@forEach
            out[g] = a
        }
        return out
    }

    fun format(m: Map<Gesture, HwAction>): String = Gesture.values().filter { it in m }.joinToString(",") { "${it.name}:${m[it]!!.name}" }

    fun actionFor(m: Map<Gesture, HwAction>, g: Gesture): HwAction = m[g] ?: DEFAULTS[g] ?: HwAction.NOTHING

    /** "C107:01" style learned codes (hardware test T1) → gesture: "C107:01=LONG_PRESS|C101:=TAP". */
    fun parseCodes(s: String?): Map<String, Gesture> = s?.split('|')?.mapNotNull { p ->
        val k = p.substringBefore('=').trim()
        val g = Gesture.values().firstOrNull { it.name == p.substringAfter('=', "").trim() }
        if (k.isEmpty() || g == null) null else k to g
    }?.toMap() ?: emptyMap()

    fun formatCodes(m: Map<String, Gesture>): String = m.entries.sortedBy { it.key }.joinToString("|") { "${it.key}=${it.value.name}" }

    /** The key a touch push is learned and looked up by: node and payload hex. */
    fun codeKey(node: String, data: ByteArray): String = node + ":" + data.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
}

/**
 * Turns the glasses' touch pushes (C101 / C107 with their payload) into gestures. A push whose node
 * and payload were learned in hardware test T1 is that gesture at once. Otherwise touches are told
 * apart by timing: two within [doubleMs] are a double tap, one alone a tap. When double tap does
 * nothing, a tap fires at once instead of waiting. Exact repeats within [debounceMs] are dropped.
 */
class TouchClassifier(
    private val learned: Map<String, Gesture> = emptyMap(),
    private val waitForDouble: Boolean = true,
    val doubleMs: Long = 400,
    private val debounceMs: Long = 120,
) {
    private var pendingAt = -1L
    private var lastAt = -1L
    /** Time between the last two touch pushes, for the T1 log. */
    var lastGapMs = -1L; private set

    /** A touch push; returns the gestures decided now (a later [poll] may decide the pending one). */
    fun touch(node: String, data: ByteArray, now: Long): List<Gesture> {
        lastGapMs = if (lastAt < 0) -1 else now - lastAt
        if (lastAt >= 0 && now - lastAt < debounceMs) return emptyList()
        lastAt = now
        learned[GestureMap.codeKey(node, data)]?.let { g ->
            val out = flushPending()
            return out + g
        }
        if (!waitForDouble) return listOf(Gesture.TAP)
        if (pendingAt >= 0 && now - pendingAt <= doubleMs) { pendingAt = -1; return listOf(Gesture.DOUBLE_TAP) }
        val out = if (pendingAt >= 0) listOf(Gesture.TAP) else emptyList()
        pendingAt = now
        return out
    }

    /** Call [doubleMs] after a touch: a tap still waiting for a second one becomes a tap. */
    fun poll(now: Long): List<Gesture> = if (pendingAt >= 0 && now - pendingAt > doubleMs) flushPending() else emptyList()

    private fun flushPending(): List<Gesture> = if (pendingAt >= 0) { pendingAt = -1; listOf(Gesture.TAP) } else emptyList()

    val waiting: Boolean get() = pendingAt >= 0
}

/** §2.8: the reply's loudness follows the noise around you, within your cap, and goes back after the turn. */
object VolumePolicy {
    /** The media volume step for a reply: one step up in a noisy place, two in a loud one, one down in a quiet room. */
    fun replyStep(current: Int, max: Int, noiseDb: Double?, capPercent: Int): Int {
        if (noiseDb == null || max <= 0) return current
        val cap = (max * capPercent.coerceIn(10, 100) / 100.0).roundToInt().coerceIn(1, max)
        val target = when {
            noiseDb > -35 -> current + 2
            noiseDb > RoutePolicy.NOISY_DB -> current + 1
            noiseDb < -65 && current > 1 -> current - 1
            else -> current
        }
        return if (target > current) target.coerceAtMost(maxOf(cap, current)) else target.coerceAtLeast(1)
    }

    /** The same loudness on another stream: [index] of [fromMax] to the nearest step of [toMax]. */
    fun mapStep(index: Int, fromMax: Int, toMax: Int): Int =
        if (fromMax <= 0 || toMax <= 0) 0 else (index.toDouble() / fromMax * toMax).roundToInt().coerceIn(0, toMax)

    fun percent(index: Int, max: Int): Int = if (max <= 0) 0 else (index * 100.0 / max).roundToInt()
}

/**
 * §4.5: which audio Gemini gets for the glasses' data-link mic. The glasses' own Opus packets as Ogg
 * Opus (`audio/ogg`, about 2 KB a second, no re-encode) until Gemini is known to refuse it, then the
 * decoded 16 kHz WAV; whatever worked is remembered ([Prefs.geminiOggState]).
 */
object AudioUpload {
    const val UNKNOWN = 0
    const val OGG_WORKS = 1
    const val OGG_REFUSED = 2

    fun useOgg(state: Int, havePackets: Boolean): Boolean = havePackets && state != OGG_REFUSED

    /** A Gemini error that means it won't take this audio (rather than a key, quota or network problem). */
    fun isFormatRefusal(code: Int, message: String?): Boolean {
        if (code != 400 && code != 415) return false
        val m = message?.lowercase(Locale.US) ?: return code == 415
        return code == 415 || listOf("mime", "audio", "unsupported", "format", "invalid argument", "inline_data", "decode").any { it in m }
    }

    /**
     * After an Ogg try: [ok] it gave words; [refused] it was turned down as a format; [empty] Gemini
     * heard nothing although our detector heard speech (the WAV retry then decides).
     */
    fun after(state: Int, ok: Boolean, refused: Boolean): Int = when {
        ok -> OGG_WORKS
        refused -> OGG_REFUSED
        else -> state
    }

    fun label(state: Int): String = when (state) {
        OGG_WORKS -> "Ogg Opus (the glasses' own packets)"
        OGG_REFUSED -> "WAV (Gemini didn't take Ogg Opus)"
        else -> "Ogg Opus first, WAV if refused (not tried yet)"
    }
}

/**
 * §2.7: the glasses' own prompt language. `2410` lists the tags they have and `2420` holds the one in
 * use, each a 6-byte NUL-padded ASCII tag ("en", "zh-cn"). The app writes `2420` (action 2) only with a
 * tag from the glasses' own list, only when it differs, and only when asked.
 */
object PromptLanguage {
    const val TAG_BYTES = 6

    fun tags(b: ByteArray): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() { if (cur.isNotEmpty()) { out += cur.toString(); cur.setLength(0) } }
        for (x in b) {
            val c = x.toInt() and 0xFF
            if (c == 0 || c == 0x22 || c == 0x2C || c == 0x20) flush()
            else if (c in 0x21..0x7E) cur.append(c.toChar().lowercaseChar())
            else flush()
        }
        flush()
        return out
    }

    fun mayWrite(list: List<String>, current: String?, tag: String): Boolean = tag in list && current != tag

    fun payload(tag: String): ByteArray {
        val t = tag.toByteArray(Charsets.US_ASCII)
        require(t.size < TAG_BYTES) { "tag too long" }
        return ByteArray(TAG_BYTES).also { System.arraycopy(t, 0, it, 0, t.size) }
    }
}

/**
 * Hardware test T5: how much of a clip's energy sits above [hz]. Wideband speech (mSBC on call audio,
 * SILK wideband on the data link) keeps a few per cent above 4 kHz; narrowband CVSD call audio,
 * upsampled from 8 kHz, keeps almost none.
 */
object Spectrum {
    fun shareAbove(pcm: ShortArray, rate: Int, hz: Double, n: Int = 512): Double {
        if (pcm.size < n) return 0.0
        val fft = Fft(n)
        val win = DoubleArray(n) { 0.5 - 0.5 * kotlin.math.cos(2 * Math.PI * it / (n - 1)) }
        val cut = (hz / rate * n).toInt().coerceIn(1, n / 2)
        var hi = 0.0; var all = 0.0
        var at = 0
        while (at + n <= pcm.size) {
            val re = DoubleArray(n) { pcm[at + it] / 32768.0 * win[it] }; val im = DoubleArray(n)
            fft.transform(re, im)
            for (k in 1..n / 2) { val p = re[k] * re[k] + im[k] * im[k]; all += p; if (k >= cut) hi += p }
            at += n / 2
        }
        return if (all <= 0) 0.0 else hi / all
    }

    /** "6.3 % above 4 kHz: wideband" for the T5 log. */
    fun verdict(share: Double): String = String.format(java.util.Locale.US, "%.1f %% of the energy above 4 kHz: %s", share * 100,
        if (share < 0.003) "narrowband (8 kHz, like CVSD call audio)" else "wideband (16 kHz)")
}
