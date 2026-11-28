package com.zdycomp.p3companion

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 4.1: the reply plays 2 cm from the glasses' mic (MATH §2.7). A full echo canceller needs a stable,
 * sample-accurate delay that A2DP doesn't give, so voice chat is half-duplex: the mic is gated from
 * the start of a reply until its end plus a measured tail, and a barge-in detector lets you interrupt
 * by speaking clearly louder than the echo (a tap on the glasses always does). Pure Kotlin.
 */
class EchoGate(var delayMs: Double? = null) {
    private var closedSince = -1L
    private var reopenAt = Long.MIN_VALUE

    /** max(300 ms, D̂ + 150 ms); 500 ms until the delay has been measured. */
    val tailMs: Long get() = delayMs?.let { max(300.0, it + 150).toLong() } ?: DEFAULT_TAIL_MS

    fun playbackStarted(now: Long) { closedSince = now; reopenAt = Long.MAX_VALUE }
    fun playbackEnded(now: Long) { if (closedSince >= 0) reopenAt = now + tailMs; closedSince = -1 }
    /** Barge-in: the gate opens at once. */
    fun openNow(now: Long) { closedSince = -1; reopenAt = now }

    fun isOpen(now: Long): Boolean = now >= reopenAt

    /** How long until the gate opens (0 when open). */
    fun waitMs(now: Long): Long = if (reopenAt == Long.MAX_VALUE) Long.MAX_VALUE else max(0L, reopenAt - now)

    companion object {
        const val DEFAULT_TAIL_MS = 500L
    }
}

/** Round-trip delay from the reply's 20 ms log-energies to the mic's (MATH §2.7.2). */
object EchoDelay {
    class Result(val delayMs: Double, val ncc: Double)

    /** Frame energies in dB (20 ms frames of [rate] Hz PCM16). */
    fun frameDb(pcm: ShortArray, rate: Int): DoubleArray {
        val n = rate / 50
        return DoubleArray(pcm.size / n) { f ->
            var s = 0.0
            for (i in 0 until n) { val v = pcm[f * n + i] / 32768.0; s += v * v }
            10 * log10(s / n + 1e-12)
        }
    }

    /**
     * NCC of reference [ref] and mic [mic] energies at lags 0..[maxLag] frames; the best lag, refined by a
     * parabola, in ms. Accepted only when NCC > 0.5 (else null).
     */
    fun measure(ref: DoubleArray, mic: DoubleArray, maxLag: Int = 40, minNcc: Double = 0.5): Result? {
        if (ref.size < 20 || mic.size < 20) return null
        val ncc = DoubleArray(maxLag + 1) { lag -> ncc(ref, mic, lag) }
        var best = 0
        for (i in ncc.indices) if (ncc[i] > ncc[best]) best = i
        if (ncc[best] <= minNcc) return null
        var frac = 0.0
        if (best in 1 until maxLag) {
            val a = ncc[best - 1]; val b = ncc[best]; val c = ncc[best + 1]
            val den = a - 2 * b + c
            if (den < 0) frac = (0.5 * (a - c) / den).coerceIn(-0.5, 0.5)
        }
        return Result((best + frac) * 20.0, ncc[best])
    }

    private fun ncc(r: DoubleArray, m: DoubleArray, lag: Int): Double {
        val n = minOf(r.size, m.size - lag)
        if (n < 10) return -1.0
        var mr = 0.0; var mm = 0.0
        for (l in 0 until n) { mr += r[l]; mm += m[l + lag] }
        mr /= n; mm /= n
        var num = 0.0; var dr = 0.0; var dm = 0.0
        for (l in 0 until n) { val a = r[l] - mr; val b = m[l + lag] - mm; num += a * b; dr += a * a; dm += b * b }
        return if (dr <= 0 || dm <= 0) 0.0 else num / sqrt(dr * dm)
    }

    /** D̂ ← 0.8·D̂_old + 0.2·D̂_new. */
    fun blend(old: Double?, new: Double): Double = old?.let { 0.8 * it + 0.2 * new } ?: new
}

/**
 * Barge-in while a reply plays (MATH §2.7.3). With a reference (Gemini's PCM, frame energies aligned by
 * D̂): a frame counts when the mic is over the predicted echo (reference + echo return loss) by 10 dB,
 * with speech-like zero crossings and energy over the noise floor; 8 of the last 10 frames fire it.
 * Without a reference (the phone's own voice, or Gemini's while its timing is unknown): over the
 * running 80th percentile of the mic by [NOREF_MARGIN_DB], [NOREF_NEED] of the last [NOREF_WINDOW].
 */
class BargeIn(private val withReference: Boolean) {
    /** Echo return loss in dB (mic − reference), learned on echo-only frames. */
    var erlDb = -6.0; private set
    private val recent = ArrayDeque<Boolean>()
    private val history = ArrayList<Double>()
    var fired = false; private set

    private val need get() = if (withReference) 8 else NOREF_NEED
    private val window get() = if (withReference) 10 else NOREF_WINDOW
    private val margin get() = if (withReference) 10.0 else NOREF_MARGIN_DB

    /**
     * One 20 ms mic frame: its level [micDb], the aligned reference level [refDb] (null without one), its
     * zero-crossing rate and its energy over the noise floor. True once barge-in fires.
     */
    fun feed(micDb: Double, refDb: Double?, zcr: Double, deltaE: Double): Boolean {
        if (fired) return true
        val predicted = if (withReference && refDb != null) refDb + erlDb else p80()
        // Without a reference the echo's level is learned over the first half second of the reply first.
        val learning = !withReference && history.size < NOREF_LEARN_FRAMES
        val frame = !learning && micDb - predicted > margin && zcr < 0.35 && deltaE > 6
        if (!frame) {
            history += micDb
            if (history.size > 500) history.removeAt(0)
            if (withReference && refDb != null && refDb > -60) erlDb = 0.98 * erlDb + 0.02 * (micDb - refDb)
        }
        recent.addLast(frame)
        while (recent.size > window) recent.removeFirst()
        if (recent.count { it } >= need) fired = true
        return fired
    }

    private fun p80(): Double {
        if (history.isEmpty()) return 0.0 // nothing heard yet: never fire on the first frames
        val s = history.sorted()
        return s[(0.8 * (s.size - 1)).toInt()]
    }

    /** True when the echo is louder than ordinary speech: voice barge-in will rarely work. */
    val echoTooLoud: Boolean get() = erlDb > -3

    companion object {
        /**
         * Without a reference the spec's 10 dB over the echo's 80th percentile in 10 of 12 frames almost
         * never fires on real speech (syllables dip every 250 ms); 8 dB in 7 of 10 frames (140 ms) fires on
         * speech 12 dB over the echo and still never on the echo alone with ±3 dB wobble (EchoGateTest).
         */
        const val NOREF_MARGIN_DB = 8.0
        const val NOREF_NEED = 7
        const val NOREF_WINDOW = 10
        /** Frames of the reply heard before a no-reference barge-in can fire (500 ms). */
        const val NOREF_LEARN_FRAMES = 25
    }
}
