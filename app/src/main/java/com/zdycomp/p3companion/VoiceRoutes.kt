package com.zdycomp.p3companion

import android.speech.SpeechRecognizer
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.sin
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
     * The mics to try for one voice question, best first. 4.0: only glasses routes that passed Test voice
     * (or worked for a real question) are tried, call audio first (3.7: it opens in under a second; the
     * data-link stream takes about 4 s to start); otherwise the phone's mic, which is always the last
     * resort. 3.5-3.9 also tried call audio that was never proven before the phone's mic, which cost a
     * second or more of probing on every question when it didn't carry sound.
     */
    fun routeOrder(scores: Map<MicRoute, Int>, headset: Boolean, linked: Boolean, useGlasses: Boolean): List<MicRoute> {
        val out = ArrayList<MicRoute>(3)
        val spp = scores[MicRoute.GLASSES_SPP] ?: 0
        val sco = scores[MicRoute.GLASSES_SCO] ?: 0
        if (useGlasses) {
            // Test voice sets 1 (worked) or -2 (failed), so -1 means "proven, then failed once": still tried, after the data link.
            if (headset && sco > 0) out += MicRoute.GLASSES_SCO
            if (linked && spp > 0) out += MicRoute.GLASSES_SPP
            if (headset && sco == -1) out += MicRoute.GLASSES_SCO
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
        noMatch: Set<String>, noPipe: Set<String>, piped: Boolean, networkFirst: Boolean = false,
    ): List<String> {
        val all = ArrayList<String>()
        fun add(k: String) { if (k !in all) all += k }
        val keys = found.map { it.key }.toSet()
        // 3.7: the glasses' call audio and our own audio go to Google's network recognizer first.
        if (networkFirst || piped) found.filter { it.pkg == GOOGLE_APP }.forEach { add(it.key) }
        if (remembered != null && (remembered in keys || (remembered == ON_DEVICE && onDevice))) add(remembered)
        PREFERRED.forEach { pkg -> found.filter { it.pkg == pkg }.forEach { add(it.key) } }
        if (onDevice) add(ON_DEVICE)
        found.forEach { add(it.key) }
        if (all.isEmpty() || defaultAvailable) add(DEFAULT)
        // 3.7: our own audio never goes to the on-phone recognizer (it answered "no match" for it twice).
        val usable = if (piped) all.filter { it !in noPipe && it != ON_DEVICE } else all
        val pinned = if (networkFirst || piped) usable.filter { k -> found.any { it.key == k && it.pkg == GOOGLE_APP } } else emptyList()
        val rest = usable - pinned.toSet()
        return pinned + rest.filter { it !in noMatch } + rest.filter { it in noMatch }
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
            // 3.7: "too many requests" is about that service's sessions: the next service (after the cooldown) may listen.
            code == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> OnError.NEXT_SERVICE
            code == SpeechRecognizer.ERROR_AUDIO -> OnError.GIVE_UP
            partials -> OnError.GIVE_UP
            else -> OnError.NEXT_SERVICE
        }
    }

    /** "a,b" ↔ set, for the remembered service lists. */
    fun parseSet(s: String?): Set<String> = s?.split('|')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
    fun formatSet(s: Set<String>): String = s.sorted().joinToString("|")

    /**
     * 3.7: the mic voice questions start with after Test voice: a glasses route if one worked (call audio
     * first, it opens fastest), else the phone's mic, else null.
     */
    fun defaultRoute(results: Map<MicRoute, Pair<Boolean, String>>): MicRoute? =
        listOf(MicRoute.GLASSES_SCO, MicRoute.GLASSES_SPP, MicRoute.PHONE).firstOrNull { results[it]?.first == true }

    /** 3.7: whether piping our own audio into the recognizer works on this phone (the pipe self-test). */
    enum class PipeState { UNKNOWN, OK, UNUSABLE }

    /** 3.7: how the glasses' data-link audio becomes words. */
    enum class SppMode {
        /** Fed live to the speech service through a pipe (EXTRA_AUDIO_SOURCE). */
        PIPE,
        /** Recorded with our own end-of-speech detector and transcribed by Gemini. */
        GEMINI,
        /** Nothing can turn it into words here (no pipe, no Gemini key). */
        NONE,
    }

    /**
     * The pipe when it isn't known to be broken (Android 13+), else Gemini when there's a Gemini key.
     * Claude doesn't take audio, so a Claude key alone gives [SppMode.NONE].
     */
    fun sppMode(pipe: PipeState, sdk: Int, geminiKey: Boolean, preferGemini: Boolean = false): SppMode = when {
        // 4.0: with a Gemini key Gemini writes it down first (more accurate than the phone's recognizer).
        preferGemini && geminiKey -> SppMode.GEMINI
        sdk >= 33 && pipe != PipeState.UNUSABLE -> SppMode.PIPE
        geminiKey -> SppMode.GEMINI
        else -> SppMode.NONE
    }

    /**
     * The pipe self-test's verdict: our phone-mic recording piped into the recognizer gave [pipedWorked]
     * words (or [pipedCode]), while the recognizer's own mic gave [ownMicWorked]. Only a "no match" (or a
     * refusal) when the recognizer's own mic works proves the pipe unusable.
     */
    fun pipeVerdict(pipedWorked: Boolean, pipedCode: Int, ownMicWorked: Boolean, spoke: Boolean): PipeState = when {
        pipedWorked -> PipeState.OK
        !ownMicWorked || !spoke -> PipeState.UNKNOWN
        pipedCode == SpeechRecognizer.ERROR_NO_MATCH || pipedCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
            pipedCode == SpeechRecognizer.ERROR_CLIENT || pipedCode == SpeechRecognizer.ERROR_AUDIO -> PipeState.UNUSABLE
        else -> PipeState.UNKNOWN
    }

    /** One line for Test voice: what works and what doesn't. */
    fun summary(results: Map<MicRoute, Pair<Boolean, String>>): String {
        val ok = MicRoute.values().filter { results[it]?.first == true }
        val bad = MicRoute.values().filter { results[it]?.first == false }
        val parts = ArrayList<String>()
        if (ok.isNotEmpty()) parts += "Works: " + ok.joinToString(", ") { "${it.label} (${results[it]!!.second})" }
        if (bad.isNotEmpty()) parts += "Not working: " + bad.joinToString(", ") { "${it.label} (${results[it]!!.second})" }
        if (parts.isEmpty()) return "Nothing was tested."
        val use = defaultRoute(results)
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
) : SpeechDetector {
    enum class State { WAITING, SPEECH, ENDED_AFTER_SPEECH, ENDED_NO_SPEECH, ENDED_MAX }

    override var state = State.WAITING; private set
    /** Whether speech was ever detected. */
    override var heardSpeech = false; private set
    override var floorDb = 0.0; private set
    override var peakDb = -120.0; private set
    var totalMs = 0.0; private set
    /** 4.0: where the speech began (ms from the start), or -1 before any speech. */
    override var speechStartMs = -1.0; private set
    /** 4.0: where the last loud piece ended (ms from the start), or -1. */
    override var speechEndMs = -1.0; private set
    private var hasFloor = false
    private var loudRun = 0.0
    private var quietRun = 0.0

    companion object {
        private const val START_FLOOR_CAP_DB = -35.0

        /** 3.7: for a clip we record ourselves (Gemini): ends 800 ms after the speech, at most 15 s. */
        fun clip() = Vad(endSilenceMs = 800, noSpeechMs = 8_000, maxMs = 15_000)

        /** 4.0: Gemini speech-to-text: starts on speech, ends after 900 ms of quiet, gives up after 8 s of nothing, at most 20 s. */
        fun gemini() = Vad(endSilenceMs = GEMINI_END_MS, noSpeechMs = 8_000, maxMs = GEMINI_MAX_MS)

        /** 4.0: hold to talk: only the release (or 20 s) ends it, never a pause. */
        fun hold() = Vad(endSilenceMs = Int.MAX_VALUE / 2, noSpeechMs = GEMINI_MAX_MS, maxMs = GEMINI_MAX_MS)

        const val GEMINI_END_MS = 900
        const val GEMINI_MAX_MS = 20_000

        /**
         * 4.0: the part of a clip worth sending: from [preRollMs] before the speech began to [tailMs] after it
         * ended (the whole clip when no speech start is known). Less to upload, same words.
         */
        fun trim(clip: ShortArray, rate: Int, speechStartMs: Double, speechEndMs: Double, preRollMs: Int = 300, tailMs: Int = 400): ShortArray {
            if (clip.isEmpty() || speechStartMs < 0) return clip
            val from = ((speechStartMs - preRollMs) * rate / 1000).toInt().coerceIn(0, clip.size)
            val to = if (speechEndMs < 0) clip.size else ((speechEndMs + tailMs) * rate / 1000).toInt().coerceIn(from, clip.size)
            return if (from == 0 && to == clip.size) clip else clip.copyOfRange(from, to)
        }
    }

    override val ended: Boolean get() = state == State.ENDED_AFTER_SPEECH || state == State.ENDED_NO_SPEECH || state == State.ENDED_MAX

    /** Feeds one chunk (any length; about 20 ms is typical). Returns the state after it. */
    override fun feed(s: ShortArray, count: Int): State {
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
        if (loud) { loudRun += ms; quietRun = 0.0; if (heardSpeech || loudRun >= minSpeechMs) speechEndMs = totalMs } else { quietRun += ms; loudRun = 0.0 }
        when (state) {
            State.WAITING -> if (loudRun >= minSpeechMs) { state = State.SPEECH; heardSpeech = true; speechStartMs = maxOf(0.0, totalMs - loudRun) }
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

    /**
     * 3.7: one whole head-0x1F frame (`02` + `[len][packet]`…, [MicRecords]); nothing is carried over
     * between frames. Two clean packets in one frame (or three in a row) lock it.
     */
    fun feedRecords(frame: ByteArray): List<ByteArray> {
        val sp = MicRecords.split(frame)
        skipped += sp.skipped
        packets += sp.packets.size
        if (sp.skipped > 0) run = 0
        run += sp.packets.size
        if ((sp.packets.size >= 2 && sp.skipped == 0) || run >= 3) locked = true
        return sp.packets
    }

    fun reset() { buf = ByteArray(0); run = 0; locked = false }
}

/**
 * 3.7: the head-0x1F mic frames (cmd 8002) as the E13C-5 sends them: one type byte `02`, then whole
 * records `[len][len bytes]`, each one Opus packet. 3.6's log: a 411-byte frame is `02` + 10 × `[28][40 B]`
 * (1 + 10·41) and an 83-byte one `02` + 2 × `[28][40 B]`. The packets are 20 ms SILK wideband (TOC 0x48,
 * code 0, or 0x4B, code 3 with padding to make them all 40 bytes). 3.6 read the frames as one byte
 * stream, so the `02` at each frame's start was "skipped" and the first frame's second packet lost.
 */
object MicRecords {
    const val TYPE_OPUS = 0x02

    class Split(val packets: List<ByteArray>, val skipped: Int)

    /** True when [b] looks like such a frame: `02` then a record length with an Opus packet after it. */
    fun looksLike(b: ByteArray): Boolean = b.size >= 3 && (b[0].toInt() and 0xFF) == TYPE_OPUS &&
        lengthOk(b[1].toInt() and 0xFF) && 2 + (b[1].toInt() and 0xFF) <= b.size && tocOk(b, 2, b[1].toInt() and 0xFF)

    private fun lengthOk(len: Int) = len in 3..0x7F && len != TYPE_OPUS

    /** A plausible Opus packet at [off] of [len] bytes (RFC 6716 3.1/3.2: a code-3 packet has 1..48 frames). */
    fun tocOk(b: ByteArray, off: Int, len: Int): Boolean {
        if (len < 1 || off + len > b.size) return false
        val code = b[off].toInt() and 0x03
        if (code == 3) {
            if (len < 2) return false
            val m = b[off + 1].toInt() and 0x3F
            return m in 1..48
        }
        return true
    }

    /**
     * Splits one frame's payload. Also takes `02` before every record (`[02][28][40 B]`…), which is how
     * the layout first looked in the log. Bytes that don't make a record are counted in [Split.skipped];
     * the frame's own leading `02` isn't.
     */
    fun split(b: ByteArray): Split {
        val out = ArrayList<ByteArray>()
        var skipped = 0
        var p = 0
        while (p < b.size) {
            var q = p
            if ((b[q].toInt() and 0xFF) == TYPE_OPUS && q + 1 < b.size && lengthOk(b[q + 1].toInt() and 0xFF)) q++
            val len = b[q].toInt() and 0xFF
            if (lengthOk(len) && q + 1 + len <= b.size && tocOk(b, q + 1, len)) {
                out += b.copyOfRange(q + 1, q + 1 + len)
                p = q + 1 + len
            } else {
                skipped++
                p++
            }
        }
        return Split(out, skipped)
    }
}

/**
 * Codec setup for Android's Opus decoder (MediaCodec "audio/opus" wants all three: csd-0 the OpusHead,
 * csd-1 the codec delay and csd-2 the seek pre-roll, both in nanoseconds). The glasses send 16 kHz
 * SILK wideband mono; the decoder always works at 48 kHz, where the pre-skip is counted (RFC 7845 5.1):
 * an encoder's usual 6.5 ms look-ahead is 312 samples at 48 kHz, and the codec delay is that same time.
 */
object OpusCsd {
    /** 6.5 ms at 48 kHz, libopus' look-ahead for 16 kHz input too (RFC 7845 recommends at least this). */
    const val PRE_SKIP = 312
    const val DECODE_RATE = 48_000

    /** The 19-byte OpusHead: version 1, [channels], pre-skip, input rate, gain 0, mapping family 0. */
    fun head(channels: Int = 1, inputRate: Int = 16_000, preSkip: Int = PRE_SKIP): ByteArray {
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

    /** The pre-skip as time: 312 samples at 48 kHz = 6 500 000 ns. */
    fun codecDelayNs(preSkip: Int = PRE_SKIP): Long = preSkip * 1_000_000_000L / DECODE_RATE

    /** csd-0, csd-1, csd-2 for 16 kHz mono SILK decoded at 48 kHz. */
    fun csd(channels: Int = 1, inputRate: Int = 16_000): List<ByteArray> =
        listOf(head(channels, inputRate, PRE_SKIP), nanos(codecDelayNs(PRE_SKIP)), nanos(SEEK_PREROLL_NS))

    const val SEEK_PREROLL_NS = 80_000_000L
    /** 20 ms per packet. */
    const val PACKET_US = 20_000L
}

/**
 * Streams any PCM16 rate and channel count to 16 kHz mono. Channels are averaged; going down, a
 * windowed-sinc low-pass (Blackman, cut-off at 85 % of the new Nyquist) runs before the rate change,
 * so 48 kHz → 16 kHz is a real low-pass and decimation by 3 (3.6 averaged 3 samples, which let
 * 8-16 kHz fold back into the speech band). A ratio that isn't whole interpolates between filtered
 * samples. State is kept between calls so a stream can be fed in pieces; output lags by half the filter.
 */
class Resampler(private val inRate: Int, private val channels: Int, private val outRate: Int = 16_000) {
    private val step = inRate.toDouble() / outRate
    private val taps: DoubleArray = if (inRate > outRate) lowPass(inRate, outRate) else doubleArrayOf(1.0)
    private val half = taps.size / 2
    private var hist = ShortArray(0)
    private var pos = 0.0

    fun process(src: ShortArray, count: Int = src.size): ShortArray {
        val frames = count / channels
        val mono = ShortArray(frames) { f ->
            var s = 0
            for (c in 0 until channels) s += src[f * channels + c]
            (s / channels).toShort()
        }
        if (inRate == outRate) return mono
        val b = ShortArray(hist.size + mono.size)
        System.arraycopy(hist, 0, b, 0, hist.size)
        System.arraycopy(mono, 0, b, hist.size, mono.size)
        val out = ShortArray((mono.size / step).toInt() + 4)
        var n = 0
        while (true) {
            val i0 = floor(pos).toInt()
            val frac = pos - i0
            val needs = if (frac > 1e-9) i0 + 1 + half else i0 + half
            if (needs >= b.size) break
            var y = fir(b, i0)
            if (frac > 1e-9) y += frac * (fir(b, i0 + 1) - y)
            if (n == out.size) break
            out[n++] = y.coerceIn(-32768.0, 32767.0).let { Math.round(it).toInt() }.toShort()
            pos += step
        }
        val keepFrom = max(0, min(b.size, floor(pos).toInt() - half))
        hist = b.copyOfRange(keepFrom, b.size)
        pos -= keepFrom
        return out.copyOf(n)
    }

    private fun fir(b: ShortArray, center: Int): Double {
        var acc = 0.0
        val start = center - half
        for (k in taps.indices) {
            val i = start + k
            if (i >= 0) acc += taps[k] * b[i]
        }
        return acc
    }

    companion object {
        /** Low-pass taps at [inRate] for a new rate [outRate]: 32 taps per unit of the ratio, odd count. */
        fun lowPass(inRate: Int, outRate: Int): DoubleArray {
            val ratio = inRate.toDouble() / outRate
            val n = (32 * ceil(ratio)).toInt() + 1
            val fc = 0.85 * (outRate / 2.0) / inRate // cycles per input sample
            val m = (n - 1) / 2.0
            val h = DoubleArray(n) { i ->
                val x = i - m
                val sinc = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
                val w = 0.42 - 0.5 * cos(2 * PI * i / (n - 1)) + 0.08 * cos(4 * PI * i / (n - 1))
                sinc * w
            }
            val sum = h.sum()
            for (i in h.indices) h[i] /= sum
            return h
        }
    }
}

/** 3.7: 16-bit PCM WAV files (RIFF, little-endian), for "Play back what the glasses heard" and Gemini. */
object Wav {
    const val HEADER = 44

    fun header(dataBytes: Int, rate: Int, channels: Int = 1): ByteArray {
        val b = java.nio.ByteBuffer.allocate(HEADER).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII)); b.putInt(36 + dataBytes)
        b.put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII)); b.putInt(16)
        b.putShort(1) // PCM
        b.putShort(channels.toShort()); b.putInt(rate); b.putInt(rate * channels * 2)
        b.putShort((channels * 2).toShort()); b.putShort(16)
        b.put("data".toByteArray(Charsets.US_ASCII)); b.putInt(dataBytes)
        return b.array()
    }

    fun pcm16(samples: ShortArray, rate: Int = 16_000, channels: Int = 1): ByteArray {
        val out = ByteArray(HEADER + samples.size * 2)
        System.arraycopy(header(samples.size * 2, rate, channels), 0, out, 0, HEADER)
        for (i in samples.indices) {
            val v = samples[i].toInt()
            out[HEADER + 2 * i] = (v and 0xFF).toByte()
            out[HEADER + 2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }
}

/** 3.7: keeps the last [max] samples of a stream (thread-safe), for playing back what a mic heard. */
class ClipRing(private val max: Int) {
    private val buf = ShortArray(max)
    private var end = 0
    private var size = 0

    @Synchronized fun add(s: ShortArray, count: Int = s.size) {
        for (i in 0 until count) {
            buf[end] = s[i]
            end = (end + 1) % max
            if (size < max) size++
        }
    }

    @Synchronized fun snapshot(): ShortArray {
        val out = ShortArray(size)
        val start = (end - size + max) % max
        for (i in 0 until size) out[i] = buf[(start + i) % max]
        return out
    }

    @Synchronized fun clear() { end = 0; size = 0 }

    val samples: Int @Synchronized get() = size
}

/**
 * 3.7: one SpeechRecognizer at a time, app-wide. 3.6's call-audio test got ERROR_TOO_MANY_REQUESTS
 * because the phone-mic test's recognizer was kept and a new one made straight after another was
 * dropped. A recognizer is made only when none exists and [cooldownMs] after the last one was
 * released; it listens only once at a time. Pure bookkeeping; [Speech] does the Android side.
 */
class RecognizerGate(val cooldownMs: Long = 300) {
    var exists = false; private set
    var listening = false; private set
    private var releasedAt = Long.MIN_VALUE / 4

    /** How long to wait before a new recognizer may be made; -1 while one still exists (release it first). */
    @Synchronized fun waitBeforeCreate(now: Long): Long = if (exists) -1 else max(0L, releasedAt + cooldownMs - now)

    /** A recognizer was made. False (and nothing changes) when one already exists or the cooldown isn't over. */
    @Synchronized fun created(now: Long): Boolean {
        if (exists || now < releasedAt + cooldownMs) return false
        exists = true
        listening = false
        return true
    }

    /** It may start listening: false when there is none or it already listens. */
    @Synchronized fun startListening(): Boolean {
        if (!exists || listening) return false
        listening = true
        return true
    }

    @Synchronized fun stoppedListening() { listening = false }

    /** It was cancelled and destroyed. */
    @Synchronized fun released(now: Long) {
        if (!exists) return
        exists = false
        listening = false
        releasedAt = now
    }
}

/**
 * 3.7: paces audio we write into the recognizer's pipe to real time. 3.6 wrote whatever had arrived at
 * once (the glasses send 10 packets, 200 ms, in one burst) and slipped 20 ms of silence in after every
 * 60 ms without data, so speech was chopped up and ran faster than real time. Now: nothing is written
 * until [prebufferMs] has arrived (or the input ended); then at most real time plus [leadMs] ahead; and
 * silence only when the input stalled for more than [stallMs] (or after it ended, until end of speech).
 */
class PipePacer(private val rate: Int = 16_000, private val prebufferMs: Int = 200, private val leadMs: Int = 100, private val stallMs: Int = 300) {
    /** What to write now: [real] samples of audio, then [silence] samples of silence. */
    data class Plan(val real: Int, val silence: Int)

    var started = false; private set
    private var startedAtMs = 0L

    /**
     * [nowMs] a monotonic clock, [written] samples written so far, [available] samples waiting,
     * [inputEnded] no more audio is coming.
     */
    fun plan(nowMs: Long, written: Long, available: Int, inputEnded: Boolean): Plan {
        if (!started) {
            if (available < rate * prebufferMs / 1000 && !inputEnded) return Plan(0, 0)
            started = true
            startedAtMs = nowMs
        }
        val allowed = (nowMs - startedAtMs) * rate / 1000 + rate * leadMs / 1000
        val room = (allowed - written).coerceAtLeast(0).toInt()
        val real = min(room, available)
        val behind = allowed - written - real - rate * leadMs / 1000
        val silence = if (available - real <= 0 && (inputEnded || behind > rate.toLong() * stallMs / 1000)) (room - real).coerceAtLeast(0) else 0
        return Plan(real, silence)
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
