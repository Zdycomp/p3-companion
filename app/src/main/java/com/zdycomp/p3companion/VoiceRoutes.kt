package com.zdycomp.p3companion

import android.speech.SpeechRecognizer
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 3.5: the pure parts of voice input, JVM-tested: which mic a voice question uses and in what order
 * the others are tried, which speech service listens, what to do on each recognizer error, the
 * end-of-speech detector for our own audio, and the plumbing for the glasses' own mic stream
 * (Opus over the Bluetooth data link, node 5750): packet framing, the decoder setup and the resampler.
 */
enum class MicRoute(val label: String) {
    /** The glasses stream their mic as Opus over the data link (node 5750); we decode it and feed the recognizer. */
    GLASSES_SPP("glasses mic (data link)"),
    /** The glasses as the phone's hands-free headset (Bluetooth SCO); the recognizer records it itself. */
    GLASSES_SCO("glasses mic (call audio)"),
    /** The phone's own mic, opened by the recognizer itself. */
    PHONE("phone mic"),
}

object VoiceChoice {

    /** A route that failed this often in a row (or that Test voice found silent) is skipped. */
    const val SKIP_AT = -2

    /** "GLASSES_SPP:1,GLASSES_SCO:-2,PHONE:1" → scores; unknown names are ignored. */
    fun parseScores(s: String?): Map<MicRoute, Int> {
        if (s.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<MicRoute, Int>()
        s.split(',').forEach { part ->
            val name = part.substringBefore(':').trim()
            val v = part.substringAfter(':', "").trim().toIntOrNull() ?: return@forEach
            MicRoute.values().firstOrNull { it.name == name }?.let { out[it] = v.coerceIn(SKIP_AT, 1) }
        }
        return out
    }

    fun formatScores(m: Map<MicRoute, Int>): String = MicRoute.values().filter { it in m }.joinToString(",") { "${it.name}:${m[it]}" }

    /** 1 = worked last time; 0 = not known; -1, -2 = failed (the second failure in a row skips it). */
    fun scoreAfter(old: Int, worked: Boolean): Int = if (worked) 1 else max(SKIP_AT, min(old, 0) - 1)

    /**
     * The mics to try for one voice question, best first. Glasses routes that worked come first (the
     * data-link stream before call audio), then call audio when not known, then the phone's mic, which
     * is always the last resort. The data-link stream is only tried once Test voice or a question
     * proved it works, because waiting for it costs about 3 s when it doesn't.
     */
    fun routeOrder(scores: Map<MicRoute, Int>, headset: Boolean, linked: Boolean, useGlasses: Boolean): List<MicRoute> {
        val out = ArrayList<MicRoute>(3)
        val spp = scores[MicRoute.GLASSES_SPP] ?: 0
        val sco = scores[MicRoute.GLASSES_SCO] ?: 0
        if (useGlasses) {
            if (linked && spp > 0) out += MicRoute.GLASSES_SPP
            if (headset && sco > 0) out += MicRoute.GLASSES_SCO
            if (headset && sco in (SKIP_AT + 1)..0) out += MicRoute.GLASSES_SCO
        }
        out += MicRoute.PHONE
        return out
    }

    // ---------------- speech services ----------------

    const val GOOGLE_APP = "com.google.android.googlequicksearchbox"
    const val ON_DEVICE = "on-device"
    const val DEFAULT = "default"

    /** Recognition services known to work well, tried before any other: Google's network recognizer first. */
    val PREFERRED = listOf(GOOGLE_APP, "com.google.android.tts", "com.google.android.as")

    /** A recognition service found on the phone: [key] is "package/class". */
    data class Rec(val key: String, val pkg: String)

    /**
     * The order speech services are tried in. The one that last gave words comes first, unless it
     * since gave "no match" while another one understood; then Google's network recognizer, the other
     * known ones, the on-phone recognizer, the rest, and the phone's default. Services in [noMatch]
     * go to the end. With our own audio ([piped]) the services in [noPipe] are left out.
     */
    fun recognizerOrder(
        found: List<Rec>, onDevice: Boolean, defaultAvailable: Boolean, remembered: String?,
        noMatch: Set<String>, noPipe: Set<String>, piped: Boolean,
    ): List<String> {
        val all = ArrayList<String>()
        fun add(k: String) { if (k !in all) all += k }
        val keys = found.map { it.key }.toSet()
        if (remembered != null && (remembered in keys || (remembered == ON_DEVICE && onDevice))) add(remembered)
        PREFERRED.forEach { pkg -> found.filter { it.pkg == pkg }.forEach { add(it.key) } }
        if (onDevice) add(ON_DEVICE)
        found.forEach { add(it.key) }
        if (all.isEmpty() || defaultAvailable) add(DEFAULT)
        val usable = if (piped) all.filter { it !in noPipe } else all
        return usable.filter { it !in noMatch } + usable.filter { it in noMatch }
    }

    enum class OnError {
        /** The service is broken or refused: try the next service. */
        NEXT_SERVICE,
        /** It listened but understood nothing (and heard no words): listen once more with the next service. */
        NEXT_SERVICE_ONCE,
        /** It doesn't take our own audio: mark it "no pipe" and use another mic. */
        NO_PIPE,
        /** Report the error. */
        GIVE_UP,
    }

    /**
     * What to do with a recognizer error. [ready] it said it was listening; [partials] it showed words;
     * [piped] it was fed our own audio; [ourVadHeard] our own detector heard speech in that audio;
     * [triedAnother] a "no match" already moved on once in this question.
     */
    fun onError(
        code: Int, ready: Boolean, partials: Boolean, piped: Boolean, ourVadHeard: Boolean,
        triedAnother: Boolean, micAllowed: Boolean,
    ): OnError {
        val noMatch = code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
        if (piped && !partials && (code == SpeechRecognizer.ERROR_CLIENT || (noMatch && ourVadHeard) ||
                code == SpeechRecognizer.ERROR_AUDIO)) return OnError.NO_PIPE
        return when {
            code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> if (micAllowed) OnError.NEXT_SERVICE else OnError.GIVE_UP
            noMatch && !ready -> OnError.NEXT_SERVICE
            noMatch && !partials && !piped && !triedAnother -> OnError.NEXT_SERVICE_ONCE
            noMatch -> OnError.GIVE_UP
            code == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS || code == SpeechRecognizer.ERROR_AUDIO -> OnError.GIVE_UP
            partials -> OnError.GIVE_UP
            else -> OnError.NEXT_SERVICE
        }
    }

    /** "a,b" ↔ set, for the remembered service lists. */
    fun parseSet(s: String?): Set<String> = s?.split('|')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
    fun formatSet(s: Set<String>): String = s.sorted().joinToString("|")

    /** One line for Test voice: what works and what doesn't. */
    fun summary(results: Map<MicRoute, Pair<Boolean, String>>): String {
        val ok = MicRoute.values().filter { results[it]?.first == true }
        val bad = MicRoute.values().filter { results[it]?.first == false }
        val parts = ArrayList<String>()
        if (ok.isNotEmpty()) parts += "Works: " + ok.joinToString(", ") { "${it.label} (${results[it]!!.second})" }
        if (bad.isNotEmpty()) parts += "Not working: " + bad.joinToString(", ") { "${it.label} (${results[it]!!.second})" }
        if (parts.isEmpty()) return "Nothing was tested."
        val use = ok.firstOrNull()
        return parts.joinToString(". ") + ". " + (use?.let { "Voice questions will use the ${it.label}." } ?: "No mic worked.")
    }
}

/** RMS level in dBFS of 16-bit samples; -120 for silence. */
object Level {
    fun dbfs(sumSquares: Double, count: Int): Double {
        if (count <= 0) return -120.0
        val rms = sqrt(sumSquares / count)
        return if (rms <= 0.0) -120.0 else max(-120.0, 20 * log10(rms / 32768.0))
    }

    fun of(s: ShortArray, count: Int = s.size): Double {
        var sum = 0.0
        for (i in 0 until count) sum += s[i].toDouble() * s[i]
        return dbfs(sum, count)
    }

    /** Below this a mic counts as silent (a dead SCO input reads as digital zero or faint hiss). */
    const val SILENT_DB = -60.0
}

/**
 * End-of-speech detector for audio we feed the recognizer ourselves (16 kHz mono PCM16). Speech is a
 * level above both [minSpeechDb] and the noise floor + [overFloorDb]; it ends after [endSilenceMs] of
 * quiet following at least [minSpeechMs] of speech, or gives up after [noSpeechMs] without speech,
 * or stops at [maxMs].
 */
class Vad(
    private val rate: Int = 16_000,
    private val endSilenceMs: Int = 1_000,
    private val noSpeechMs: Int = 6_000,
    private val maxMs: Int = 12_000,
    private val minSpeechMs: Int = 120,
    private val minSpeechDb: Double = -50.0,
    private val overFloorDb: Double = 10.0,
) {
    enum class State { WAITING, SPEECH, ENDED_AFTER_SPEECH, ENDED_NO_SPEECH, ENDED_MAX }

    var state = State.WAITING; private set
    /** Whether speech was ever detected. */
    var heardSpeech = false; private set
    var floorDb = 0.0; private set
    var peakDb = -120.0; private set
    var totalMs = 0.0; private set
    private var hasFloor = false
    private var loudRun = 0.0
    private var quietRun = 0.0

    private companion object { const val START_FLOOR_CAP_DB = -35.0 }

    val ended: Boolean get() = state == State.ENDED_AFTER_SPEECH || state == State.ENDED_NO_SPEECH || state == State.ENDED_MAX

    /** Feeds one chunk (any length; about 20 ms is typical). Returns the state after it. */
    fun feed(s: ShortArray, count: Int = s.size): State {
        if (ended || count <= 0) return state
        val ms = count * 1000.0 / rate
        val db = Level.of(s, count)
        totalMs += ms
        if (db > peakDb) peakDb = db
        // The floor starts at the first piece's level, but never above [START_FLOOR_CAP_DB], so speech from the first moment still counts.
        if (!hasFloor) { floorDb = min(db, START_FLOOR_CAP_DB); hasFloor = true }
        val loud = db > minSpeechDb && db > floorDb + overFloorDb
        // The floor follows quiet sound down at once and up slowly, so speech doesn't raise it.
        floorDb = if (db < floorDb) db else floorDb + min(db - floorDb, 0.02 * ms)
        if (loud) { loudRun += ms; quietRun = 0.0 } else { quietRun += ms; loudRun = 0.0 }
        when (state) {
            State.WAITING -> if (loudRun >= minSpeechMs) { state = State.SPEECH; heardSpeech = true }
                else if (totalMs >= noSpeechMs) state = State.ENDED_NO_SPEECH
            State.SPEECH -> if (quietRun >= endSilenceMs) state = State.ENDED_AFTER_SPEECH
            else -> {}
        }
        if (!ended && totalMs >= maxMs) state = State.ENDED_MAX
        return state
    }
}

/**
 * Splits the glasses' mic stream into Opus packets: each is `[0x28][40 bytes]` (20 ms at 16 kHz mono,
 * PROTOCOL-NOTES 4.1). A bad length byte skips one byte; bytes are kept between chunks. Until three
 * packets in a row line up, a packet only counts when the byte after it starts the next one, so
 * random data can't pass for audio.
 */
class OpusFramer(private val lengthByte: Int = 0x28) {
    private var buf = ByteArray(0)
    private var run = 0
    var locked = false; private set
    var packets = 0L; private set
    var skipped = 0L; private set

    fun feed(src: ByteArray, off: Int = 0, len: Int = src.size - off): List<ByteArray> {
        if (len <= 0) return emptyList()
        val b = ByteArray(buf.size + len)
        System.arraycopy(buf, 0, b, 0, buf.size)
        System.arraycopy(src, off, b, buf.size, len)
        val out = ArrayList<ByteArray>()
        var p = 0
        val step = lengthByte + 1
        while (p < b.size) {
            if ((b[p].toInt() and 0xFF) != lengthByte) { skip(); p++; continue }
            if (b.size - p < step) break
            if (!locked) {
                if (b.size - p < step + 1) break // need the next packet's first byte to confirm
                if ((b[p + step].toInt() and 0xFF) != lengthByte) { skip(); p++; continue }
            }
            out += b.copyOfRange(p + 1, p + step)
            packets++
            if (++run >= 3) locked = true
            p += step
        }
        buf = b.copyOfRange(p, b.size)
        if (buf.size > 4096) { skipped += buf.size - 4096; buf = buf.copyOfRange(buf.size - 4096, buf.size) }
        return out
    }

    private fun skip() { skipped++; run = 0; locked = false }

    fun reset() { buf = ByteArray(0); run = 0; locked = false }
}

/** Codec setup for Android's Opus decoder (MediaCodec "audio/opus" wants all three). */
object OpusCsd {
    /** The 19-byte OpusHead: version 1, [channels], pre-skip, input rate, gain 0, mapping family 0. */
    fun head(channels: Int = 1, inputRate: Int = 16_000, preSkip: Int = 0): ByteArray {
        val b = ByteArray(19)
        "OpusHead".forEachIndexed { i, c -> b[i] = c.code.toByte() }
        b[8] = 1
        b[9] = channels.toByte()
        b[10] = (preSkip and 0xFF).toByte(); b[11] = ((preSkip shr 8) and 0xFF).toByte()
        for (i in 0 until 4) b[12 + i] = ((inputRate shr (8 * i)) and 0xFF).toByte()
        return b // gain (16..17) and mapping family (18) stay 0
    }

    /** csd-1 (codec delay) and csd-2 (seek pre-roll): nanoseconds, 64-bit little-endian. */
    fun nanos(ns: Long): ByteArray = ByteArray(8) { i -> ((ns shr (8 * i)) and 0xFF).toByte() }

    const val SEEK_PREROLL_NS = 80_000_000L
    /** 20 ms per packet. */
    const val PACKET_US = 20_000L
}

/**
 * Streams any PCM16 rate and channel count down to 16 kHz mono: channels are averaged, then each
 * output sample is the mean of the input samples it covers (a box filter, enough against aliasing
 * for speech recognition). State is kept between calls so a stream can be fed in pieces.
 */
class Resampler(private val inRate: Int, private val channels: Int, private val outRate: Int = 16_000) {
    private var buf = ShortArray(0)
    private var pos = 0.0
    private val stepIn = inRate.toDouble() / outRate

    fun process(src: ShortArray, count: Int = src.size): ShortArray {
        val frames = count / channels
        val mono = ShortArray(frames) { f ->
            var s = 0
            for (c in 0 until channels) s += src[f * channels + c]
            (s / channels).toShort()
        }
        if (inRate == outRate) return mono
        val b = ShortArray(buf.size + mono.size)
        System.arraycopy(buf, 0, b, 0, buf.size)
        System.arraycopy(mono, 0, b, buf.size, mono.size)
        val out = ArrayList<Short>((mono.size / stepIn).toInt() + 2)
        val half = max(stepIn, 1.0) / 2
        while (pos + half < b.size) {
            val from = max(0, floor(pos - half + 0.5).toInt())
            val to = min(b.size - 1, floor(pos + half - 0.5).toInt().coerceAtLeast(from))
            var s = 0L
            for (i in from..to) s += b[i]
            out += (s / (to - from + 1)).toInt().toShort()
            pos += stepIn
        }
        val keepFrom = max(0, min(b.size, floor(pos - half).toInt()))
        buf = b.copyOfRange(keepFrom, b.size)
        pos -= keepFrom
        return out.toShortArray()
    }
}

/** Lets a repeated log line through for the first [firstN] times, then once per [everyMs], counting the rest. */
class RateLimit(private val firstN: Int = 3, private val everyMs: Long = 10_000) {
    private var count = 0
    private var lastAt = Long.MIN_VALUE / 2
    private var held = 0

    /** Null: stay quiet. Otherwise how many were held back since the last line let through. */
    @Synchronized fun allow(now: Long): Int? {
        count++
        if (count <= firstN || now - lastAt >= everyMs) {
            lastAt = now
            val h = held
            held = 0
            return h
        }
        held++
        return null
    }

    @Synchronized fun reset() { count = 0; held = 0; lastAt = Long.MIN_VALUE / 2 }
}

/**
 * 3.5: live view opens and closes are debounced by [windowMs]: a close waits that long (an open in
 * the meantime keeps the stream running and sends nothing), and an open right after a real close
 * waits until [windowMs] after it, so a screen rebuild or the live view test never churns the glasses.
 */
class LiveDebounce(private val windowMs: Long = 1_500) {
    data class Open(val keepRunning: Boolean, val delayMs: Long)

    var closePending = false; private set
    private var closedAt = Long.MIN_VALUE / 2

    /** A close was asked for: how long to wait before really sending it. */
    @Synchronized fun close(@Suppress("UNUSED_PARAMETER") now: Long): Long { closePending = true; return windowMs }

    /** An open was asked for. */
    @Synchronized fun open(now: Long): Open {
        if (closePending) { closePending = false; return Open(true, 0) }
        return Open(false, max(0L, closedAt + windowMs - now))
    }

    /** The pending close is due: true when it should still go out. */
    @Synchronized fun closeDue(now: Long): Boolean {
        if (!closePending) return false
        closePending = false
        closedAt = now
        return true
    }

    /** A close sent at once (the mic, a photo or the snapshot view needs the camera). */
    @Synchronized fun closedNow(now: Long) { closePending = false; closedAt = now }
}

/** 3.5: after a broken live picture the decoder waits for a key picture, but never longer than [maxWaitMs]. */
object LiveResync {
    const val MAX_WAIT_MS = 2_000L

    /** May this picture go to the decoder? [gapAgeMs] is how long ago a picture was dropped (null: no gap). */
    fun mayDecode(waitingForKey: Boolean, entry: Boolean, gapAgeMs: Long?, maxWaitMs: Long = MAX_WAIT_MS): Boolean =
        !waitingForKey || entry || (gapAgeMs != null && gapAgeMs >= maxWaitMs)
}
