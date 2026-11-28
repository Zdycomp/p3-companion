package com.zdycomp.p3companion

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 4.1: radix-2 complex FFT, 1-D and 2-D, in place on separate real and imaginary arrays. Shared by the
 * mic noise suppressor (1024-point STFT) and the burst registration (256×256 phase correlation).
 * Pure Kotlin, no Android classes, so the JVM tests run the same code.
 */
class Fft(val n: Int) {
    init { require(n >= 2 && n and (n - 1) == 0) { "FFT size must be a power of two" } }

    private val cosT = DoubleArray(n / 2) { cos(2 * PI * it / n) }
    private val sinT = DoubleArray(n / 2) { sin(2 * PI * it / n) }
    private val rev = IntArray(n).also { r ->
        var bits = 0
        while (1 shl bits < n) bits++
        for (i in 0 until n) {
            var x = i; var y = 0
            for (b in 0 until bits) { y = (y shl 1) or (x and 1); x = x shr 1 }
            r[i] = y
        }
    }

    /** Forward transform (e^{-i}); [inverse] uses e^{+i} and divides by n. Arrays at [off] with [stride]. */
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean = false, off: Int = 0, stride: Int = 1) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                val a = off + i * stride; val b = off + j * stride
                val tr = re[a]; re[a] = re[b]; re[b] = tr
                val ti = im[a]; im[a] = im[b]; im[b] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var i = 0
            while (i < n) {
                for (k in 0 until half) {
                    val wr = cosT[k * step]
                    val wi = if (inverse) sinT[k * step] else -sinT[k * step]
                    val a = off + (i + k) * stride
                    val b = off + (i + k + half) * stride
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) { val a = off + i * stride; re[a] /= n; im[a] /= n }
    }

    companion object {
        /** 2-D transform of a [w]×[h] row-major grid (both powers of two). */
        fun transform2(re: DoubleArray, im: DoubleArray, w: Int, h: Int, inverse: Boolean = false) {
            val fw = Fft(w)
            for (y in 0 until h) fw.transform(re, im, inverse, off = y * w, stride = 1)
            val fh = if (h == w) fw else Fft(h)
            for (x in 0 until w) fh.transform(re, im, inverse, off = x, stride = w)
        }

        fun nextPow2(v: Int): Int { var n = 1; while (n < v) n = n shl 1; return n }
    }
}
