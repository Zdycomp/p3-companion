package com.zdycomp.p3companion

import java.util.Random
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Test-only speech-like and noise signals at 16 kHz (MATH §2.8), as doubles in [-1, 1). */
object SynthVoice {
    const val RATE = 16_000

    /** Voiced speech with three formants, a 4 Hz syllable envelope and fricative bursts. [speakerB] is higher. */
    fun speech(seconds: Double, rmsDb: Double, speakerB: Boolean = false, seed: Long = 1, fricatives: Boolean = true): DoubleArray {
        val n = (seconds * RATE).toInt()
        val rnd = Random(seed)
        val out = DoubleArray(n)
        var phase = 0.0
        val (f0a, f0b) = if (speakerB) 190.0 to 240.0 else 110.0 to 160.0
        for (i in 0 until n) {
            val t = i / RATE.toDouble()
            val f0 = f0a + (f0b - f0a) * ((t * 0.7) % 1.0)
            phase += 2 * PI * f0 / RATE
            var v = 0.0
            val hMax = (7000 / f0).toInt()
            for (h in 1..hMax) {
                val hf = h * f0
                var a = 0.0
                for (F in doubleArrayOf(500.0, 1500.0, 2500.0)) a += 1 / (1 + ((hf - F) / 120).pow(2))
                v += a * sin(h * phase)
            }
            val env = sqrt(0.5 - 0.5 * kotlin.math.cos(2 * PI * 4 * t))
            out[i] = v * env
        }
        if (fricatives) {
            // 30 ms of 3-6 kHz noise at each syllable start, 6 dB below the voiced level.
            val burst = (0.03 * RATE).toInt()
            var s = 0
            while (s < n) {
                val hp = bandNoise(burst, rnd)
                for (j in 0 until burst) if (s + j < n) out[s + j] += 0.5 * hp[j]
                s += RATE / 4
            }
        }
        return scale(out, rmsDb)
    }

    fun white(seconds: Double, rmsDb: Double, seed: Long = 2): DoubleArray {
        val rnd = Random(seed)
        return scale(DoubleArray((seconds * RATE).toInt()) { rnd.nextGaussian() }, rmsDb)
    }

    /** Pink noise (Voss-McCartney, 16 rows). */
    fun pink(seconds: Double, rmsDb: Double, seed: Long = 3): DoubleArray {
        val rnd = Random(seed)
        val rows = DoubleArray(16) { rnd.nextGaussian() }
        var sum = rows.sum()
        val n = (seconds * RATE).toInt()
        val out = DoubleArray(n)
        for (i in 0 until n) {
            val k = Integer.numberOfTrailingZeros(i + 1).coerceAtMost(15)
            sum -= rows[k]; rows[k] = rnd.nextGaussian(); sum += rows[k]
            out[i] = sum + rnd.nextGaussian()
        }
        return scale(out, rmsDb)
    }

    /** 120 Hz hum plus harmonics. */
    fun hum(seconds: Double, rmsDb: Double): DoubleArray {
        val n = (seconds * RATE).toInt()
        return scale(DoubleArray(n) { i -> val t = i / RATE.toDouble(); sin(2 * PI * 120 * t) + 0.5 * sin(2 * PI * 240 * t) + 0.25 * sin(2 * PI * 360 * t) }, rmsDb)
    }

    /** 10 ms clicks every 300 ms. */
    fun clicks(seconds: Double, peak: Double, seed: Long = 4): DoubleArray {
        val rnd = Random(seed)
        val n = (seconds * RATE).toInt()
        val out = DoubleArray(n)
        var s = RATE / 10
        while (s < n) { for (j in 0 until RATE / 100) if (s + j < n) out[s + j] = peak * rnd.nextGaussian() * kotlin.math.exp(-j / 30.0); s += (0.3 * RATE).toInt() }
        return out
    }

    fun sine(seconds: Double, hz: Double, amp: Double): DoubleArray = DoubleArray((seconds * RATE).toInt()) { amp * sin(2 * PI * hz * it / RATE) }

    fun silence(seconds: Double) = DoubleArray((seconds * RATE).toInt())

    fun concat(vararg parts: DoubleArray): DoubleArray {
        val out = DoubleArray(parts.sumOf { it.size }); var at = 0
        parts.forEach { System.arraycopy(it, 0, out, at, it.size); at += it.size }
        return out
    }

    fun mix(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(maxOf(a.size, b.size)) { (a.getOrElse(it) { 0.0 }) + (b.getOrElse(it) { 0.0 }) }

    fun rmsDb(x: DoubleArray, from: Int = 0, to: Int = x.size): Double {
        var s = 0.0
        for (i in from until to) s += x[i] * x[i]
        return 10 * kotlin.math.log10(s / maxOf(1, to - from) + 1e-20)
    }

    fun scale(x: DoubleArray, rmsDb: Double): DoubleArray {
        val now = rmsDb(x)
        val g = 10.0.pow((rmsDb - now) / 20)
        return DoubleArray(x.size) { x[it] * g }
    }

    fun shorts(x: DoubleArray): ShortArray = ShortArray(x.size) { (x[it] * 32767).coerceIn(-32768.0, 32767.0).toInt().toShort() }

    fun frames(x: DoubleArray): List<FloatArray> = (0 until x.size / 320).map { f -> FloatArray(320) { x[f * 320 + it].toFloat() } }

    private fun bandNoise(n: Int, rnd: Random): DoubleArray {
        // White noise through a crude 3-6 kHz band-pass (difference of two one-pole low-passes).
        var a = 0.0; var b = 0.0
        val ka = 1 - kotlin.math.exp(-2 * PI * 6000 / RATE); val kb = 1 - kotlin.math.exp(-2 * PI * 3000 / RATE)
        return DoubleArray(n) { val w = rnd.nextGaussian(); a += ka * (w - a); b += kb * (w - b); a - b }
    }
}
