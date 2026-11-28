package com.zdycomp.p3companion

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 4.1: the glasses-mic voice chain (docs/SHARPEN-AND-AUDIO-MATH.md Part 2), pure Kotlin on 20 ms
 * frames of 16 kHz mono: 80 Hz high-pass → STFT noise suppressor (minimum-statistics noise floor,
 * decision-directed Wiener gain) → AGC to −20 dBFS on speech → look-ahead limiter at −1 dBFS, with a
 * speech detector ([VadPlus]) on energy, zero crossings and noise-whitened spectral flatness. Applied
 * to every route the app records itself; where the phone already suppresses noise (call audio,
 * VOICE_RECOGNITION with the platform effect) the suppressor stage is skipped, never stacked.
 */

/** What any end-of-speech detector the app feeds offers ([Vad] and [VadPlus]). */
interface SpeechDetector {
    val state: Vad.State
    val heardSpeech: Boolean
    val peakDb: Double
    val floorDb: Double
    val speechStartMs: Double
    val speechEndMs: Double
    val ended: Boolean
    fun feed(s: ShortArray, count: Int = s.size): Vad.State
}

/** Re-blocks chunks of any length into exact [size]-sample frames, losing nothing. */
class FrameBuffer(val size: Int = VoiceChain.FRAME) {
    private val buf = FloatArray(size)
    private var n = 0

    fun push(s: ShortArray, count: Int = s.size, out: (FloatArray) -> Unit) {
        for (i in 0 until count) {
            buf[n++] = s[i] / 32768f
            if (n == size) { out(buf.copyOf()); n = 0 }
        }
    }

    val pending: Int get() = n
}

/** 2nd-order Butterworth high-pass (RBJ cookbook), direct form I in double precision. */
class Biquad(f0: Double = 80.0, fs: Double = 16_000.0, q: Double = 1 / sqrt(2.0)) {
    val b0: Double; val b1: Double; val b2: Double; val a1: Double; val a2: Double
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

    init {
        val w0 = 2 * PI * f0 / fs
        val alpha = sin(w0) / (2 * q)
        val c = cos(w0)
        val a0 = 1 + alpha
        b0 = (1 + c) / 2 / a0; b1 = -(1 + c) / a0; b2 = (1 + c) / 2 / a0
        a1 = -2 * c / a0; a2 = (1 - alpha) / a0
    }

    fun process(x: FloatArray): FloatArray = FloatArray(x.size) { i ->
        val v = x[i].toDouble()
        val y = b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = v; y2 = y1; y1 = y
        y.toFloat()
    }
}

/**
 * STFT analysis and synthesis: √Hann window of 640, hop 320, FFT 1024 (513 bins of 15.625 Hz). With
 * unity gains the output is the input delayed by one frame (320 samples), exactly.
 */
class Stft {
    val m = 640
    val hop = VoiceChain.FRAME
    val nfft = 1024
    val bins = nfft / 2 + 1
    private val fft = Fft(nfft)
    private val win = DoubleArray(m) { sin(PI * (it + 0.5) / m) }
    private val hist = DoubleArray(m)
    private val ola = DoubleArray(m)
    val re = DoubleArray(nfft)
    val im = DoubleArray(nfft)
    val power = DoubleArray(bins)

    /** Analyses one new frame; [re]/[im]/[power] hold its spectrum afterwards. */
    fun analyse(frame: FloatArray) {
        System.arraycopy(hist, hop, hist, 0, m - hop)
        for (i in 0 until hop) hist[m - hop + i] = frame[i].toDouble()
        for (i in 0 until nfft) { re[i] = if (i < m) hist[i] * win[i] else 0.0; im[i] = 0.0 }
        fft.transform(re, im)
        for (k in 0 until bins) power[k] = re[k] * re[k] + im[k] * im[k]
    }

    /** Applies real gains per bin (mirrored), inverse transforms and overlap-adds: one frame of output. */
    fun synthesise(gain: DoubleArray?): FloatArray {
        if (gain != null) for (k in 0 until bins) {
            re[k] *= gain[k]; im[k] *= gain[k]
            if (k in 1 until nfft / 2) { re[nfft - k] *= gain[k]; im[nfft - k] *= gain[k] }
        }
        fft.transform(re, im, inverse = true)
        for (i in 0 until m) ola[i] += re[i] * win[i]
        val out = FloatArray(hop) { ola[it].toFloat() }
        System.arraycopy(ola, hop, ola, 0, m - hop)
        for (i in m - hop until m) ola[i] = 0.0
        return out
    }

    /** E|X(k)|² for white noise of variance σ²: σ² Σ w² = 320 σ². */
    val windowEnergy: Double = win.sumOf { it * it }
}

/** Noise PSD by minimum statistics (Martin 2001, simplified). Frozen while the echo gate is closed. */
class NoiseTracker(private val bins: Int, private val windowEnergy: Double) {
    var alphaS = 0.80
    val sub = 15
    val subs = 5
    var bMin = BIAS
    val lambda = DoubleArray(bins)
    private val smooth = DoubleArray(bins)
    private val subMin = DoubleArray(bins) { Double.MAX_VALUE }
    private val ring = Array(subs) { DoubleArray(bins) { Double.MAX_VALUE } }
    private var ringAt = 0
    private var inSub = 0
    var frames = 0; private set
    private val initSum = DoubleArray(bins)
    var holdRise = false

    fun update(p: DoubleArray) {
        frames++
        val cap = START_CAP_DB.let { windowEnergy * 10.0.pow(it / 10) }
        if (frames == 1) for (k in 0 until bins) smooth[k] = p[k]
        else for (k in 0 until bins) smooth[k] = alphaS * smooth[k] + (1 - alphaS) * p[k]
        for (k in 0 until bins) if (smooth[k] < subMin[k]) subMin[k] = smooth[k]
        inSub++
        if (inSub == sub) {
            System.arraycopy(subMin, 0, ring[ringAt], 0, bins)
            ringAt = (ringAt + 1) % subs
            java.util.Arrays.fill(subMin, Double.MAX_VALUE)
            inSub = 0
        }
        if (frames <= INIT_FRAMES) {
            // The first 200 ms: the mean, but never above a quiet room, so speech from the first moment still counts.
            for (k in 0 until bins) { initSum[k] += p[k]; lambda[k] = min(initSum[k] / frames, cap) }
            return
        }
        for (k in 0 until bins) {
            var mn = subMin[k]
            for (r in ring) if (r[k] < mn) mn = r[k]
            if (mn == Double.MAX_VALUE) mn = smooth[k]
            val est = max(bMin * mn, 1e-14)
            // While speech is going on the floor may fall but never rise, so a long utterance isn't learned as noise.
            lambda[k] = if (holdRise && est > lambda[k]) lambda[k] else est
        }
    }

    /** Noise floor as a level: dBFS of the white noise that would give this PSD over 100-7000 Hz. */
    fun floorDb(): Double {
        var s = 0.0
        for (k in 7..448) s += lambda[k]
        return 10 * log10(s / (442 * windowEnergy) + 1e-12)
    }

    companion object {
        /**
         * Bias of the minimum for α_s 0.8 over 75 frames, measured on white noise in the tests (the spec's
         * 1.66 left the floor about 1.2 dB low with this light smoothing; 2.2 makes it unbiased).
         */
        const val BIAS = 2.2
        const val INIT_FRAMES = 10
        const val START_CAP_DB = -50.0
    }
}

/** Per-frame speech features (MATH §2.6). */
class VadFeatures(val eAbs: Double, val deltaE: Double, val zcr: Double, val sfm: Double, val rHi: Double) {
    val loud: Boolean get() = eAbs > -55 && deltaE > 4
    val voiced: Boolean get() = loud && zcr < VOICED_ZCR && sfm < -4
    val unvoiced: Boolean get() = loud && zcr >= VOICED_ZCR && deltaE > 6 && rHi > 0.5
    val speech: Boolean get() = voiced || unvoiced || (loud && deltaE > 15)

    companion object {
        /**
         * Zero crossings per sample below which a loud frame can be voiced. The spec's 0.12 assumes speech
         * whose energy sits under 1 kHz; on the glasses' wideband SILK (and the synthetic test voice, with
         * formants to 2.5 kHz) voiced frames cross 0.15-0.25, while fricatives and white noise sit near 0.5.
         */
        const val VOICED_ZCR = 0.30
    }
}

/** The analysis shared by the detector and the suppressor: HPF frame + its STFT + the noise PSD. */
class Analyzer {
    val hpf = Biquad()
    val stft = Stft()
    val noise = NoiseTracker(stft.bins, stft.windowEnergy)
    var last: VadFeatures? = null; private set
    var lastHpf: FloatArray = FloatArray(VoiceChain.FRAME); private set

    /** Runs one frame; [frozen] keeps the noise PSD as it is (the echo gate is closed). */
    fun run(frame: FloatArray, frozen: Boolean): VadFeatures {
        val x = hpf.process(frame)
        lastHpf = x
        stft.analyse(x)
        if (!frozen || noise.frames == 0) noise.update(stft.power)
        val f = features(x, stft.power, noise.lambda)
        last = f
        return f
    }

    companion object {
        fun features(x: FloatArray, p: DoubleArray, lambda: DoubleArray): VadFeatures {
            var e = 0.0
            for (v in x) e += v.toDouble() * v
            val eAbs = 10 * log10(e / x.size + 1e-12)
            var sp = 0.0; var sl = 0.0; var hi = 0.0
            for (k in 7..448) { sp += p[k]; sl += lambda[k]; if (k >= 128) hi += p[k] }
            val deltaE = 10 * log10((sp + 1e-20) / (sl + 1e-20))
            var zc = 0
            for (i in 1 until x.size) if ((x[i] >= 0) != (x[i - 1] >= 0)) zc++
            val zcr = zc / x.size.toDouble()
            var lnSum = 0.0; var gSum = 0.0
            val n = 256 - 20 + 1
            for (k in 20..256) { val g = p[k] / max(lambda[k], 1e-20); lnSum += ln(g + 1e-6); gSum += g }
            val sfm = 10 * log10(exp(lnSum / n) / (gSum / n + 1e-20) + 1e-20)
            return VadFeatures(eAbs, deltaE, zcr, sfm, hi / (sp + 1e-20))
        }
    }
}

/**
 * Speech detector on the chain's features (MATH §2.6), with the old [Vad]'s states: WAITING → SPEECH
 * when 6 of the last 7 frames are speech and 3 of them voiced (120 ms; the start is dated back to the
 * first of them); SPEECH → ENDED_AFTER_SPEECH after [endMs] of non-speech in a row; WAITING →
 * ENDED_NO_SPEECH after [noSpeechMs]; ENDED_MAX at [maxMs]. Lost frames freeze both counters; after
 * 10 in a row they count as silence. Fed audio itself ([feed]) it runs its own analysis.
 */
class VadPlus(
    val endMs: Int = 800,
    val noSpeechMs: Int = 8_000,
    val maxMs: Int = 15_000,
    startInSpeech: Boolean = false,
) : SpeechDetector {
    override var state = Vad.State.WAITING; private set
    override var heardSpeech = false; private set
    override var peakDb = -120.0; private set
    override var floorDb = -120.0; private set
    override var speechStartMs = -1.0; private set
    override var speechEndMs = -1.0; private set
    var totalMs = 0.0; private set
    override val ended: Boolean get() = state == Vad.State.ENDED_AFTER_SPEECH || state == Vad.State.ENDED_NO_SPEECH || state == Vad.State.ENDED_MAX

    private val window = ArrayDeque<Pair<Boolean, Boolean>>() // (speech, voiced) of the last 7 frames
    private var quietMs = 0.0
    private var lostRun = 0
    /** Frames since the last speech frame (AGC and NS hangover of 5 frames). */
    var sinceSpeech = Int.MAX_VALUE / 2; private set

    /**
     * Whether the noise floor should hold still (it may fall, not rise): in speech with a voiced frame in
     * the last 140 ms. A rise in plain noise has no voiced frames, so the floor still follows it.
     */
    val holdFloor: Boolean get() = state == Vad.State.SPEECH && window.any { it.second }
    private var own: Analyzer? = null
    private val frames = FrameBuffer()

    init {
        if (startInSpeech) { state = Vad.State.SPEECH; heardSpeech = true; speechStartMs = 0.0; sinceSpeech = 0 }
    }

    /** The hangover: treat the 5 frames after speech as speech for gain decisions. */
    val inSpeech: Boolean get() = state == Vad.State.SPEECH && sinceSpeech <= 5

    /** One analysed frame. */
    fun decide(f: VadFeatures, floor: Double) {
        if (ended) return
        val ms = VoiceChain.FRAME_MS
        totalMs += ms
        lostRun = 0
        floorDb = floor
        if (f.eAbs > peakDb) peakDb = f.eAbs
        val sp = f.speech
        window.addLast(sp to f.voiced)
        while (window.size > 7) window.removeFirst()
        if (sp) { sinceSpeech = 0; quietMs = 0.0; if (heardSpeech) speechEndMs = totalMs } else { sinceSpeech++; quietMs += ms }
        step()
    }

    /** A lost 20 ms (a gap in the glasses' packets). */
    fun lost() {
        if (ended) return
        totalMs += VoiceChain.FRAME_MS
        lostRun++
        if (lostRun > 10) { quietMs += VoiceChain.FRAME_MS; sinceSpeech++; window.addLast(false to false); while (window.size > 7) window.removeFirst() }
        step()
    }

    private fun step() {
        when (state) {
            Vad.State.WAITING -> {
                val speech = window.count { it.first }
                val voiced = window.count { it.first && it.second }
                if (window.size >= 6 && speech >= 6 && voiced >= 3) {
                    state = Vad.State.SPEECH
                    heardSpeech = true
                    val first = window.indexOfFirst { it.first }.coerceAtLeast(0)
                    speechStartMs = max(0.0, totalMs - (window.size - first) * VoiceChain.FRAME_MS)
                    speechEndMs = totalMs
                } else if (totalMs >= noSpeechMs) state = Vad.State.ENDED_NO_SPEECH
            }
            Vad.State.SPEECH -> if (quietMs >= endMs) state = Vad.State.ENDED_AFTER_SPEECH
            else -> {}
        }
        if (!ended && totalMs >= maxMs) state = Vad.State.ENDED_MAX
    }

    /** Fed raw audio (drop-in for [Vad]): runs its own high-pass and noise analysis. */
    override fun feed(s: ShortArray, count: Int): Vad.State {
        val a = own ?: Analyzer().also { own = it }
        frames.push(s, count) { fr ->
            a.noise.holdRise = holdFloor
            val f = a.run(fr, frozen = false)
            decide(f, a.noise.floorDb())
        }
        return state
    }

    companion object {
        /** Gemini speech-to-text: ends 900 ms after the speech, gives up after 8 s, at most 20 s. */
        fun gemini() = VadPlus(endMs = Vad.GEMINI_END_MS, noSpeechMs = 8_000, maxMs = Vad.GEMINI_MAX_MS)
        /** Voice chat turns: 800 ms of quiet ends the turn (MATH §2.6). */
        fun chat() = VadPlus(endMs = 800, noSpeechMs = 12_000, maxMs = Vad.GEMINI_MAX_MS)
        /** Hold to talk: only the release (or 20 s) ends it. */
        fun hold() = VadPlus(endMs = Int.MAX_VALUE / 2, noSpeechMs = Vad.GEMINI_MAX_MS, maxMs = Vad.GEMINI_MAX_MS)
    }
}

/** AGC to −20 dBFS RMS on speech frames (MATH §2.4), gain frozen outside speech so noise never pumps. */
class Agc(val targetDb: Double = -20.0) {
    var gainDb = 0.0; private set
    var levelDb = Double.NaN; private set
    private var speechFramesSeen = 0
    private var lastGain = 1.0

    /** Applies the gain to [x] in place, ramping per sample from the last frame's gain. */
    fun process(x: FloatArray, speech: Boolean, frozen: Boolean): FloatArray {
        var p = 0.0
        for (v in x) p += v.toDouble() * v
        p /= x.size
        if (speech && !frozen && p > 1e-10) {
            levelDb = if (levelDb.isNaN()) 10 * log10(p) else 10 * log10(ALPHA * 10.0.pow(levelDb / 10) + (1 - ALPHA) * p)
            speechFramesSeen++
            val want = (targetDb - levelDb).coerceIn(MIN_DB, MAX_DB)
            val up = if (speechFramesSeen <= FAST_FRAMES) FAST_UP_DB else UP_DB
            gainDb += (want - gainDb).coerceIn(DOWN_DB, up)
        }
        val g = 10.0.pow(gainDb / 20)
        val out = FloatArray(x.size)
        for (i in x.indices) {
            val gi = lastGain + (g - lastGain) * (i + 1) / x.size
            out[i] = (x[i] * gi).toFloat()
        }
        lastGain = g
        return out
    }

    companion object {
        const val ALPHA = 0.95
        const val MIN_DB = -15.0
        const val MAX_DB = 30.0
        const val UP_DB = 0.10
        const val DOWN_DB = -0.60
        const val FAST_UP_DB = 1.0
        const val FAST_FRAMES = 25
    }
}

/** Look-ahead limiter, ceiling −1 dBFS (0.891), 2 ms look-ahead, instant attack, 50 ms release. */
class Limiter(val ceiling: Float = 0.891f, val lookAhead: Int = 32, rate: Int = 16_000) {
    private val delay = FloatArray(lookAhead + 1)
    private val req = FloatArray(lookAhead + 1) { 1f }
    private var at = 0
    private var g = 1f
    private val release = (1 - exp(-1.0 / (0.05 * rate))).toFloat()

    fun process(x: FloatArray): FloatArray = FloatArray(x.size) { i ->
        val v = x[i]
        delay[at] = v
        req[at] = if (abs(v) > ceiling) ceiling / abs(v) else 1f
        at = (at + 1) % delay.size
        var tgt = 1f
        for (r in req) if (r < tgt) tgt = r
        g = if (tgt < g) tgt else g + release * (tgt - g)
        val y = delay[at] * g // the oldest sample: delayed by the look-ahead
        y.coerceIn(-ceiling, ceiling)
    }
}

/**
 * The whole chain on 20 ms frames. [process] returns one output frame (delayed by 352 samples: one
 * STFT frame plus the limiter's look-ahead) and the detector's state.
 */
class VoiceChain(val params: Params = Params()) {
    data class Params(
        val suppress: Boolean = true,
        /** Gain floor: −10 dB for speech-to-text (recognizers lose more to over-suppression than to noise). */
        val gMin: Double = 0.316,
        val agc: Boolean = true,
        val limiter: Boolean = true,
        val endMs: Int = 800,
        val noSpeechMs: Int = 12_000,
        val maxMs: Int = Vad.GEMINI_MAX_MS,
    ) {
        companion object {
            /** The glasses' Opus stream: no phone-side processing, so the whole chain. */
            fun opus() = Params()
            /** A route where the phone already suppresses noise: high-pass, AGC, limiter and VAD only. */
            fun platformCleaned() = Params(suppress = false)
        }
    }

    val analyzer = Analyzer()
    var vad = VadPlus(params.endMs, params.noSpeechMs, params.maxMs); private set
    private val agc = Agc()
    private val limiter = Limiter()
    private val gain = DoubleArray(analyzer.stft.bins) { 1.0 }
    private val prevG2Gamma = DoubleArray(analyzer.stft.bins)
    private var prevSpeech = false
    var frames = 0L; private set

    /** A new turn: a fresh detector; the noise floor and the AGC level carry over (same session). */
    fun newTurn(vadNew: VadPlus) { vad = vadNew }

    class Out(val pcm: FloatArray, val state: Vad.State, val speech: Boolean, val features: VadFeatures)

    fun process(frame: FloatArray, lost: Boolean = false, frozen: Boolean = false): Out {
        frames++
        val a = analyzer
        a.noise.holdRise = vad.holdFloor
        val f = a.run(frame, frozen)
        if (lost) vad.lost() else if (!frozen) vad.decide(f, a.noise.floorDb())
        val stft = a.stft
        val g = if (params.suppress) gains(stft.power, a.noise.lambda) else null
        var y = stft.synthesise(g)
        val speechNow = prevSpeech
        prevSpeech = vad.inSpeech || f.speech
        if (params.agc) y = agc.process(y, speechNow, frozen)
        if (params.limiter) y = limiter.process(y)
        return Out(y, vad.state, f.speech, f)
    }

    private fun gains(p: DoubleArray, lambda: DoubleArray): DoubleArray {
        val n = p.size
        val raw = DoubleArray(n)
        for (k in 0 until n) {
            val gamma = p[k] / max(lambda[k], 1e-20)
            var xi = ALPHA_DD * prevG2Gamma[k] + (1 - ALPHA_DD) * max(gamma - 1, 0.0)
            if (xi < XI_MIN) xi = XI_MIN
            raw[k] = max(xi / (1 + xi), params.gMin)
            prevG2Gamma[k] = raw[k] * raw[k] * gamma
        }
        for (k in 0 until n) {
            val l = raw[max(0, k - 1)]; val r = raw[min(n - 1, k + 1)]
            gain[k] = 0.25 * l + 0.5 * raw[k] + 0.25 * r
            if (k <= 5) gain[k] = params.gMin
            if (k > 480) gain[k] = min(gain[k], 0.5)
        }
        return gain
    }

    val agcGainDb: Double get() = agc.gainDb

    companion object {
        const val FRAME = 320
        const val FRAME_MS = 20.0
        const val ALPHA_DD = 0.98
        val XI_MIN = 10.0.pow(-25.0 / 10)
    }
}

/**
 * The chain on 16-bit chunks of any size: re-blocks to 20 ms, processes, and gives back processed
 * PCM16 (the 352-sample delay included). Its [detector] is the chain's own speech detector. Not
 * thread-safe: feed it from one thread.
 */
class MicDsp(params: VoiceChain.Params = VoiceChain.Params.opus(), vad: VadPlus? = null) {
    val chain = VoiceChain(params).also { c -> vad?.let { c.newTurn(it) } }
    private val frames = FrameBuffer()
    /** True while the echo gate is closed: nothing learned, counters frozen. */
    @Volatile var frozen = false
    var lastFrameDb = -120.0; private set

    val detector: SpeechDetector get() = chain.vad

    /** 4.1: every analysed 20 ms frame (barge-in, the level meter), on the feeding thread. */
    @Volatile var onFrame: ((VoiceChain.Out) -> Unit)? = null

    /** A new turn in the same session: a fresh detector, the noise floor and the gain carry over. */
    fun newTurn(vad: VadPlus) = chain.newTurn(vad)

    fun process(pcm: ShortArray, count: Int = pcm.size): ShortArray {
        val out = ArrayList<FloatArray>(2)
        frames.push(pcm, count) { fr ->
            val o = chain.process(fr, lost = false, frozen = frozen)
            lastFrameDb = o.features.eAbs
            onFrame?.invoke(o)
            out += o.pcm
        }
        return toShorts(out)
    }

    /** A lost packet: 20 ms of zeros flagged lost. */
    fun lost(): ShortArray {
        val o = chain.process(FloatArray(VoiceChain.FRAME), lost = true, frozen = frozen)
        return toShorts(listOf(o.pcm))
    }

    companion object {
        fun toShorts(frames: List<FloatArray>): ShortArray {
            val n = frames.sumOf { it.size }
            val s = ShortArray(n)
            var i = 0
            for (f in frames) for (v in f) s[i++] = (v * 32767f).let { if (it > 32767f) 32767f else if (it < -32768f) -32768f else it }.let { Math.round(it) }.toShort()
            return s
        }
    }
}
