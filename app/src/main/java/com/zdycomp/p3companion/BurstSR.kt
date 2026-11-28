package com.zdycomp.p3companion

import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 4.1: multi-frame super-resolution (docs/SHARPEN-AND-AUDIO-MATH.md Part 1). Several pictures of the
 * same scene, each a little shifted by natural head motion, sample it at different sub-pixel phases;
 * combined on a finer grid they recover detail that one picture only aliased, and average down noise
 * and codec error. Pure Kotlin on [FloatArray]: no Android classes, so the JVM tests run this code.
 *
 * Pipeline: YCbCr → deblock (JPEG 8×8 / H.264 4×4 grid) → sharpest frame as reference, blurred frames
 * dropped → photometric match → phase correlation (FFT) → pyramidal inverse-compositional Lucas-Kanade
 * (affine, Huber-robust; translation fallback) → per-tile motion rejection → shift-and-add gather onto
 * the s× grid with a bicubic hole prior → light Richardson-Lucy with a TV term and a halo clamp →
 * chroma averaged at low resolution and upsampled guided by the new luma → ARGB.
 */
class Plane(val w: Int, val h: Int, val v: FloatArray = FloatArray(w * h)) {
    operator fun get(x: Int, y: Int): Float = v[y * w + x]
    fun at(x: Int, y: Int): Float = v[(if (y < 0) 0 else if (y >= h) h - 1 else y) * w + (if (x < 0) 0 else if (x >= w) w - 1 else x)]
    fun copy() = Plane(w, h, v.copyOf())

    fun bilinear(x: Double, y: Double): Float {
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        val fx = (x - x0).toFloat(); val fy = (y - y0).toFloat()
        val a = at(x0, y0); val b = at(x0 + 1, y0); val c = at(x0, y0 + 1); val d = at(x0 + 1, y0 + 1)
        return (a + (b - a) * fx) * (1 - fy) + (c + (d - c) * fx) * fy
    }

    /** Keys bicubic (a = −0.5), edges clamped. */
    fun bicubic(x: Double, y: Double): Float {
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        val fx = x - x0; val fy = y - y0
        var s = 0.0
        for (j in -1..2) {
            val wy = Sr.keys(j - fy)
            var r = 0.0
            for (i in -1..2) r += Sr.keys(i - fx) * at(x0 + i, y0 + j)
            s += wy * r
        }
        return s.toFloat()
    }

    fun mean(): Double = v.sumOf { it.toDouble() } / v.size
}

/** A warp from REFERENCE pixel coordinates to FRAME pixel coordinates, affine about the centre. */
class Affine(val a: DoubleArray, val cx: Double, val cy: Double) {
    // a = [a11 a12 tx a21 a22 ty]
    fun mapX(x: Double, y: Double) = cx + a[0] * (x - cx) + a[1] * (y - cy) + a[2]
    fun mapY(x: Double, y: Double) = cy + a[3] * (x - cx) + a[4] * (y - cy) + a[5]
    val tx get() = a[2]
    val ty get() = a[5]
    fun scaled(f: Double, ncx: Double, ncy: Double) = Affine(doubleArrayOf(a[0], a[1], a[2] * f, a[3], a[4], a[5] * f), ncx, ncy)

    /** This ∘ other⁻¹ (inverse-compositional update), both about the same centre. */
    fun composeInverse(d: DoubleArray): Affine {
        // M = [[a0 a1 a2][a3 a4 a5][0 0 1]], D = [[1+d0 d1 d2][d3 1+d4 d5][0 0 1]]
        val m00 = 1 + d[0]; val m01 = d[1]; val m02 = d[2]; val m10 = d[3]; val m11 = 1 + d[4]; val m12 = d[5]
        val det = m00 * m11 - m01 * m10
        val i00 = m11 / det; val i01 = -m01 / det; val i10 = -m10 / det; val i11 = m00 / det
        val i02 = -(i00 * m02 + i01 * m12); val i12 = -(i10 * m02 + i11 * m12)
        val r = DoubleArray(6)
        r[0] = a[0] * i00 + a[1] * i10; r[1] = a[0] * i01 + a[1] * i11; r[2] = a[0] * i02 + a[1] * i12 + a[2]
        r[3] = a[3] * i00 + a[4] * i10; r[4] = a[3] * i01 + a[4] * i11; r[5] = a[3] * i02 + a[4] * i12 + a[5]
        return Affine(r, cx, cy)
    }

    /** The inverse warp (frame → reference). */
    fun inverse(): Affine {
        val det = a[0] * a[4] - a[1] * a[3]
        val i0 = a[4] / det; val i1 = -a[1] / det; val i3 = -a[3] / det; val i4 = a[0] / det
        return Affine(doubleArrayOf(i0, i1, -(i0 * a[2] + i1 * a[5]), i3, i4, -(i3 * a[2] + i4 * a[5])), cx, cy)
    }

    /** The angle of the linear part, in degrees. */
    val rotationDeg: Double get() = Math.toDegrees(kotlin.math.atan2(a[3] - a[1], a[0] + a[4]))

    companion object {
        fun identity(cx: Double, cy: Double) = Affine(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0), cx, cy)
        fun translation(tx: Double, ty: Double, cx: Double, cy: Double) = Affine(doubleArrayOf(1.0, 0.0, tx, 0.0, 1.0, ty), cx, cy)
    }
}

/** Small numeric helpers shared by the stages. */
object Sr {
    fun keys(t: Double): Double {
        val x = abs(t)
        return when {
            x <= 1 -> 1.5 * x * x * x - 2.5 * x * x + 1
            x < 2 -> -0.5 * x * x * x + 2.5 * x * x - 4 * x + 2
            else -> 0.0
        }
    }

    fun median(v: FloatArray, n: Int = v.size): Float {
        if (n == 0) return 0f
        val c = v.copyOf(n); c.sort(); return c[n / 2]
    }

    fun percentile(v: FloatArray, n: Int, p: Double): Float {
        if (n == 0) return 0f
        val c = v.copyOf(n); c.sort(); return c[((n - 1) * p).toInt().coerceIn(0, n - 1)]
    }

    /** Separable Gaussian blur with reflect padding. */
    fun gauss(p: Plane, sigma: Double): Plane {
        if (sigma <= 0.0) return p.copy()
        val r = ceil(3 * sigma).toInt().coerceAtLeast(1)
        val k = FloatArray(2 * r + 1) { i -> exp(-((i - r) * (i - r)) / (2 * sigma * sigma)).toFloat() }
        val s = k.sum(); for (i in k.indices) k[i] /= s
        return conv(p, k)
    }

    /** Separable convolution with a symmetric kernel, reflect padding. */
    fun conv(p: Plane, k: FloatArray): Plane {
        val r = k.size / 2
        val w = p.w; val h = p.h
        val tmp = FloatArray(w * h)
        parallelRows(h) { y ->
            val row = y * w
            for (x in 0 until w) {
                var s = 0f
                for (i in -r..r) s += k[i + r] * p.v[row + reflect(x + i, w)]
                tmp[row + x] = s
            }
        }
        val out = FloatArray(w * h)
        parallelRows(h) { y ->
            for (x in 0 until w) {
                var s = 0f
                for (i in -r..r) s += k[i + r] * tmp[reflect(y + i, h) * w + x]
                out[y * w + x] = s
            }
        }
        return Plane(w, h, out)
    }

    fun reflect(i: Int, n: Int): Int {
        if (n == 1) return 0
        var x = i
        while (x < 0 || x >= n) x = if (x < 0) -x - 1 else 2 * n - x - 1
        return x
    }

    /** 2×2 mean (after the [1 4 6 4 1]/16 pre-blur when [blur]). */
    fun down2(p: Plane, blur: Boolean = true): Plane {
        val src = if (blur) conv(p, floatArrayOf(1 / 16f, 4 / 16f, 6 / 16f, 4 / 16f, 1 / 16f)) else p
        val w = p.w / 2; val h = p.h / 2
        val out = Plane(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            out.v[y * w + x] = 0.25f * (src.v[2 * y * p.w + 2 * x] + src.v[2 * y * p.w + 2 * x + 1] + src.v[(2 * y + 1) * p.w + 2 * x] + src.v[(2 * y + 1) * p.w + 2 * x + 1])
        }
        return out
    }

    /** Bicubic upscale by [s] with HR pixel (u, v) centred at LR ((u + 0.5)/s − 0.5, …). Separable. */
    fun bicubicUp(p: Plane, s: Int): Plane = resample(p, p.w * s, p.h * s, s.toDouble(), cubic = true)

    fun bilinearUp(p: Plane, s: Int): Plane = resample(p, p.w * s, p.h * s, s.toDouble(), cubic = false)

    private fun resample(p: Plane, dw: Int, dh: Int, s: Double, cubic: Boolean): Plane {
        val n = if (cubic) 4 else 2
        fun taps(dst: Int, src: Int): Pair<IntArray, FloatArray> {
            val idx = IntArray(dst * n); val wt = FloatArray(dst * n)
            for (u in 0 until dst) {
                val x = (u + 0.5) / s - 0.5
                val x0 = floor(x).toInt(); val f = x - x0
                for (t in 0 until n) {
                    val off = if (cubic) t - 1 else t
                    idx[u * n + t] = (x0 + off).coerceIn(0, src - 1)
                    wt[u * n + t] = (if (cubic) keys(off - f) else if (off == 0) 1 - f else f).toFloat()
                }
            }
            return idx to wt
        }
        val (ix, wx) = taps(dw, p.w)
        val (iy, wy) = taps(dh, p.h)
        val tmp = FloatArray(dw * p.h)
        for (y in 0 until p.h) for (u in 0 until dw) {
            var a = 0f
            for (t in 0 until n) a += wx[u * n + t] * p.v[y * p.w + ix[u * n + t]]
            tmp[y * dw + u] = a
        }
        val out = FloatArray(dw * dh)
        parallelRows(dh) { v ->
            for (u in 0 until dw) {
                var a = 0f
                for (t in 0 until n) a += wy[v * n + t] * tmp[iy[v * n + t] * dw + u]
                out[v * dw + u] = a
            }
        }
        return Plane(dw, dh, out)
    }

    private val pool by lazy {
        val n = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        Executors.newFixedThreadPool(n) { r -> Thread(r, "p3-sr").apply { isDaemon = true } }
    }
    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    /** Runs [body] for rows 0 until [h], split over up to 4 threads for big images. */
    fun parallelRows(h: Int, body: (Int) -> Unit) {
        if (h < 64 || threads == 1) { for (y in 0 until h) body(y); return }
        val chunk = (h + threads - 1) / threads
        val tasks = (0 until threads).map { t -> Callable { for (y in t * chunk until min(h, (t + 1) * chunk)) body(y) } }
        pool.invokeAll(tasks).forEach { it.get() }
    }

    /** Runs [body] for 0 until [n] (a few big jobs), each on its own pool thread. */
    fun parallelFor(n: Int, body: (Int) -> Unit) {
        if (threads == 1 || n <= 1) { for (i in 0 until n) body(i); return }
        pool.invokeAll((0 until n).map { i -> Callable { body(i) } }).forEach { it.get() }
    }

    fun psnr(a: Plane, b: Plane, border: Int): Double {
        var se = 0.0; var n = 0
        for (y in border until a.h - border) for (x in border until a.w - border) {
            val d = (a[x, y] - b[x, y]).toDouble(); se += d * d; n++
        }
        return 10 * log10(255.0 * 255.0 / (se / n).coerceAtLeast(1e-12))
    }
}

/** YCbCr (BT.601 full range) planes of ARGB pixels. */
class Ycc(val y: Plane, val cb: Plane, val cr: Plane) {
    fun toArgb(): IntArray {
        val n = y.v.size
        return IntArray(n) { i ->
            val yy = y.v[i]; val b = cb.v[i] - 128f; val r = cr.v[i] - 128f
            argb(yy + 1.402f * r, yy - 0.344136f * b - 0.714136f * r, yy + 1.772f * b)
        }
    }

    companion object {
        fun of(px: IntArray, w: Int, h: Int): Ycc {
            val y = Plane(w, h); val cb = Plane(w, h); val cr = Plane(w, h)
            for (i in px.indices) {
                val c = px[i]
                val r = ((c shr 16) and 0xFF).toFloat(); val g = ((c shr 8) and 0xFF).toFloat(); val b = (c and 0xFF).toFloat()
                y.v[i] = 0.299f * r + 0.587f * g + 0.114f * b
                cb.v[i] = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
                cr.v[i] = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b
            }
            return Ycc(y, cb, cr)
        }

        private fun c8(v: Float): Int = if (v <= 0f) 0 else if (v >= 255f) 255 else (v + 0.5f).toInt()
        fun argb(r: Float, g: Float, b: Float): Int = (0xFF shl 24) or (c8(r) shl 16) or (c8(g) shl 8) or c8(b)
    }
}

/** §1.3: deblocking on the codec's block grid. */
object Deblock {
    /** Mean step across grid lines over mean step elsewhere (both axes); about 1 without blocking. */
    fun blockiness(p: Plane, g: Int): Double {
        var on = 0.0; var nOn = 0; var off = 0.0; var nOff = 0
        for (y in 0 until p.h) for (x in 1 until p.w) {
            val d = abs(p[x, y] - p[x - 1, y]).toDouble()
            if (x % g == 0) { on += d; nOn++ } else { off += d; nOff++ }
        }
        for (y in 1 until p.h) for (x in 0 until p.w) {
            val d = abs(p[x, y] - p[x, y - 1]).toDouble()
            if (y % g == 0) { on += d; nOn++ } else { off += d; nOff++ }
        }
        if (nOn == 0 || nOff == 0 || off <= 0) return 1.0
        return (on / nOn) / (off / nOff)
    }

    /** Robust step size across grid edges: 1.4826·median|p0 − q0|. */
    fun stepSigma(p: Plane, g: Int): Double {
        val steps = ArrayList<Float>()
        for (y in 0 until p.h step 2) for (x in g until p.w step g) steps += abs(p[x, y] - p[x - 1, y])
        for (y in g until p.h step g) for (x in 0 until p.w step 2) steps += abs(p[x, y] - p[x, y - 1])
        if (steps.isEmpty()) return 0.0
        return 1.4826 * Sr.median(steps.toFloatArray())
    }

    class Result(val plane: Plane, val applied: Boolean, val before: Double, val after: Double, val sigmaStep: Double)

    /** Filters [p] when blockiness > 1.10 (H.264-style normal edge filter, only p0/q0 change). */
    fun run(p: Plane, h264: Boolean): Result {
        val g = if (h264) 4 else 8
        val before = blockiness(p, g)
        val sStep = stepSigma(p, g)
        if (before <= 1.10) return Result(p, false, before, before, sStep)
        val alphaBase = (3 * sStep).coerceIn(if (h264) 5.0 else 6.0, if (h264) 16.0 else 20.0)
        // β and t_c as specified (4 for JPEG, 3 for H.264), raised with a coarse quantiser so busy blocks are filtered too.
        val beta = max(if (h264) 3.0 else 4.0, 1.5 * sStep).toFloat()
        val tc = max(if (h264) 3.0 else 4.0, sStep).toFloat()
        val o = p.copy()
        // Across an edge: p2 p1 p0 | q0 q1 q2 (indices), as H.264's normal filter: p0/q0 always, p1/q1 when
        // their own side is flat too.
        fun edge(i0: Int, i1: Int, i2: Int, j0: Int, j1: Int, j2: Int, alpha: Float) {
            val p2 = o.v[i2]; val p1 = o.v[i1]; val p0 = o.v[i0]; val q0 = o.v[j0]; val q1 = o.v[j1]; val q2 = o.v[j2]
            if (abs(p0 - q0) < alpha && abs(p1 - p0) < beta && abs(q1 - q0) < beta) {
                val d = ((4 * (q0 - p0) + (p1 - q1) + 4) / 8f).coerceIn(-tc, tc)
                o.v[i0] = p0 + d; o.v[j0] = q0 - d
                val avg = (p0 + q0) / 2f
                if (abs(p2 - p0) < beta) o.v[i1] = p1 + ((p2 + avg - 2 * p1) / 2f).coerceIn(-tc / 2, tc / 2)
                if (abs(q2 - q0) < beta) o.v[j1] = q1 + ((q2 + avg - 2 * q1) / 2f).coerceIn(-tc / 2, tc / 2)
            }
        }
        // Two passes: the second catches what the first's t_c clip left of a larger step.
        repeat(2) {
            for (y in 0 until o.h) for (x in g until o.w - 2 step g) {
                val a = (if (h264 && x % 16 == 0) 1.5 * alphaBase else alphaBase).toFloat()
                val r = y * o.w
                edge(r + x - 1, r + x - 2, r + x - 3, r + x, r + x + 1, r + x + 2, a)
            }
            for (y in g until o.h - 2 step g) for (x in 0 until o.w) {
                val a = (if (h264 && y % 16 == 0) 1.5 * alphaBase else alphaBase).toFloat()
                val w = o.w
                edge((y - 1) * w + x, (y - 2) * w + x, (y - 3) * w + x, y * w + x, (y + 1) * w + x, (y + 2) * w + x, a)
            }
        }
        return Result(o, true, before, blockiness(o, g), sStep)
    }

    /** §1.3 block confidence: 0.7 on block-border samples. */
    fun confidence(x: Int, y: Int, g: Int): Float =
        if (x % g == 0 || x % g == g - 1 || y % g == 0 || y % g == g - 1) 0.7f else 1f
}

/** §1.4: sharpness (variance of the Laplacian) and noise (Immerkær-style MAD of the Laplacian). */
object Quality {
    private fun lap(p: Plane, x: Int, y: Int): Float = p[x, y - 1] + p[x - 1, y] - 4 * p[x, y] + p[x + 1, y] + p[x, y + 1]

    fun sharpness(p: Plane): Double {
        val x0 = max(1, p.w / 10); val x1 = min(p.w - 2, p.w - p.w / 10)
        val y0 = max(1, p.h / 10); val y1 = min(p.h - 2, p.h - p.h / 10)
        var s = 0.0; var n = 0
        for (y in y0..y1) for (x in x0..x1) { val l = lap(p, x, y).toDouble(); s += l * l; n++ }
        return if (n == 0) 0.0 else s / n
    }

    fun noise(p: Plane): Double {
        val vals = FloatArray((p.w - 2).coerceAtLeast(0) * (p.h - 2).coerceAtLeast(0))
        var n = 0
        for (y in 1 until p.h - 1) for (x in 1 until p.w - 1) vals[n++] = abs(lap(p, x, y))
        return (1.4826 * Sr.median(vals, n) / sqrt(20.0)).coerceIn(0.5, 12.0)
    }

    /** Mean and standard deviation over the centre 80 %. */
    fun moments(p: Plane): Pair<Double, Double> {
        var s = 0.0; var s2 = 0.0; var n = 0
        for (y in p.h / 10 until p.h - p.h / 10) for (x in p.w / 10 until p.w - p.w / 10) { val v = p[x, y].toDouble(); s += v; s2 += v * v; n++ }
        val m = s / n
        return m to sqrt(max(s2 / n - m * m, 1e-6))
    }
}

/** §1.6.1: phase correlation on a centred, Hann-windowed power-of-two crop. */
object PhaseCorr {
    class Result(val dx: Double, val dy: Double, val rho: Double)

    /**
     * The sub-pixel peak: a Gaussian 3-point fit, because the frequency weight makes the peak Gaussian
     * rather than sinc-shaped (Foroosh's closed form, kept as [FOROOSH], was off by up to 0.26 px on the
     * test scene; the Gaussian fit by under 0.1 px).
     */
    private const val fit = 1
    const val FOROOSH = 0

    private fun subPixel(c: Double, a: Double, b: Double, foroosh: (Double, Double, Double) -> Double): Double {
        if (fit == FOROOSH) return foroosh(c, a.coerceAtLeast(0.0), b.coerceAtLeast(0.0))
        val la = ln(max(a, 1e-9 * c)); val lb = ln(max(b, 1e-9 * c)); val lc = ln(max(c, 1e-12))
        val den = 2 * (2 * lc - la - lb)
        return if (den <= 1e-12) 0.0 else ((la - lb) / den).coerceIn(-0.5, 0.5)
    }

    /** The translation t with frame(x) ≈ ref(x − t), i.e. the reference point x appears at x + t in [img]. */
    fun estimate(ref: Plane, img: Plane, maxSize: Int = 256): Result {
        val m = min(maxSize, Fft.nextPow2(min(ref.w, ref.h)))
        val cw = min(m, ref.w); val ch = min(m, ref.h)
        val ox = (ref.w - cw) / 2; val oy = (ref.h - ch) / 2
        val n = m * m
        val ar = DoubleArray(n); val ai = DoubleArray(n); val br = DoubleArray(n); val bi = DoubleArray(n)
        val wx = DoubleArray(cw) { 0.5 - 0.5 * cos(2 * PI * it / (cw - 1)) }
        val wy = DoubleArray(ch) { 0.5 - 0.5 * cos(2 * PI * it / (ch - 1)) }
        val (ma, _) = Quality.moments(ref); val (mb, _) = Quality.moments(img)
        for (y in 0 until ch) for (x in 0 until cw) {
            val wgt = wx[x] * wy[y]
            ar[y * m + x] = (ref[ox + x, oy + y] - ma) * wgt
            br[y * m + x] = (img[ox + x, oy + y] - mb) * wgt
        }
        Fft.transform2(ar, ai, m, m)
        Fft.transform2(br, bi, m, m)
        var meanMag = 0.0
        for (i in 0 until n) meanMag += sqrt(ar[i] * ar[i] + ai[i] * ai[i]) * sqrt(br[i] * br[i] + bi[i] * bi[i])
        val eps = 1e-3 * meanMag / n + 1e-12
        val qr = DoubleArray(n); val qi = DoubleArray(n)
        for (y in 0 until m) for (x in 0 until m) {
            val i = y * m + x
            // F_r · conj(F_k)
            val re = ar[i] * br[i] + ai[i] * bi[i]
            val im = ai[i] * br[i] - ar[i] * bi[i]
            val mag = sqrt(re * re + im * im) + eps
            val fx = (if (x <= m / 2) x else x - m).toDouble() / m
            val fy = (if (y <= m / 2) y else y - m).toDouble() / m
            val fw = exp(-(fx * fx + fy * fy) / (2 * 0.3 * 0.3))
            qr[i] = re / mag * fw; qi[i] = im / mag * fw
        }
        Fft.transform2(qr, qi, m, m, inverse = true)
        var best = 0
        for (i in 1 until n) if (qr[i] > qr[best]) best = i
        val px = best % m; val py = best / m
        fun q(x: Int, y: Int) = qr[((y % m + m) % m) * m + ((x % m + m) % m)]
        fun sub(c: Double, a: Double, b: Double): Double =
            if (a >= b) (if (a + c != 0.0) a / (a + c) else 0.0) else (if (b + c != 0.0) -b / (b + c) else 0.0)
        val c = q(px, py)
        val dxs = subPixel(c, q(px + 1, py), q(px - 1, py), ::sub)
        val dys = subPixel(c, q(px, py + 1), q(px, py - 1), ::sub)
        val wxp = if (px > m / 2) px - m else px
        val wyp = if (py > m / 2) py - m else py
        var second = 0.0
        for (y in 0 until m) for (x in 0 until m) {
            var ddx = abs(x - px); if (ddx > m / 2) ddx = m - ddx
            var ddy = abs(y - py); if (ddy > m / 2) ddy = m - ddy
            if (ddx <= 2 && ddy <= 2) continue
            val v = qr[y * m + x]
            if (v > second) second = v
        }
        val rho = if (second <= 0) 99.0 else c / second
        // The peak sits at −t (F_r·conj(F_k) for a frame shifted by +t).
        return Result(-(wxp + dxs), -(wyp + dys), rho)
    }
}

/** §1.6.2: inverse-compositional Lucas-Kanade, affine (6) or translation (2), Huber-robust, pyramidal. */
object Lk {
    /** The affine model must bring the residual under this share of the translation model's. */
    const val AFFINE_GAIN = 0.8
    const val MAX_PIXELS = 40_000
    class Result(val warp: Affine, val rms: Double, val sumTx2: Double, val sumTy2: Double, val ok: Boolean, val model: Int)

    private fun gradX(p: Plane) = Plane(p.w, p.h, FloatArray(p.w * p.h) { i -> val x = i % p.w; val y = i / p.w; 0.5f * (p.at(x + 1, y) - p.at(x - 1, y)) })
    private fun gradY(p: Plane) = Plane(p.w, p.h, FloatArray(p.w * p.h) { i -> val x = i % p.w; val y = i / p.w; 0.5f * (p.at(x, y + 1) - p.at(x, y - 1)) })

    /**
     * Registers [img] to [tpl] (both already blurred), starting from [init] (about the image centre).
     * [params] 6 = affine, 2 = translation. [cubic]: bicubic sampling (level 0) or bilinear.
     */
    fun level(tpl: Plane, img: Plane, init: Affine, params: Int, iters: Int, cubic: Boolean, border: Int, gain: Float = 1f, offset: Float = 0f): Result {
        val w = tpl.w; val h = tpl.h
        val cx = init.cx; val cy = init.cy
        val tx = gradX(tpl); val ty = gradY(tpl)
        // Pixels with a gradient above the 30th percentile, inside the border.
        val mags = FloatArray(w * h)
        var nm = 0
        for (y in border until h - border) for (x in border until w - border) { val i = y * w + x; mags[nm++] = tx.v[i] * tx.v[i] + ty.v[i] * ty.v[i] }
        val cut = Sr.percentile(mags, nm, 0.30)
        val idx = IntArray(nm); var n = 0
        for (y in border until h - border) for (x in border until w - border) { val i = y * w + x; if (tx.v[i] * tx.v[i] + ty.v[i] * ty.v[i] >= cut && (cut > 0 || tx.v[i] != 0f || ty.v[i] != 0f)) idx[n++] = i }
        // At most MAX_PIXELS of them, evenly spread (tens of thousands already pin the shift far below 0.01 px).
        if (n > MAX_PIXELS) { val step = n.toDouble() / MAX_PIXELS; for (k in 0 until MAX_PIXELS) idx[k] = idx[(k * step).toInt()]; n = MAX_PIXELS }
        if (n < 3 * params) return Result(init, Double.MAX_VALUE, 0.0, 0.0, false, params)
        val sd = Array(n) { DoubleArray(params) }
        for (k in 0 until n) {
            val i = idx[k]; val x = (i % w) - cx; val y = (i / w) - cy
            val gx = tx.v[i].toDouble(); val gy = ty.v[i].toDouble()
            if (params == 6) { sd[k][0] = gx * x; sd[k][1] = gx * y; sd[k][2] = gx; sd[k][3] = gy * x; sd[k][4] = gy * y; sd[k][5] = gy }
            else { sd[k][0] = gx; sd[k][1] = gy }
        }
        var warp = init
        val e = DoubleArray(n)
        val valid = BooleanArray(n)
        val wt = DoubleArray(n) { 1.0 }
        var rms = 0.0
        var ok = true
        for (it in 0 until iters) {
            var cnt = 0; var se = 0.0
            for (k in 0 until n) {
                val i = idx[k]; val x = (i % w).toDouble(); val y = (i / w).toDouble()
                val fx = warp.mapX(x, y); val fy = warp.mapY(x, y)
                if (fx < 1 || fy < 1 || fx > w - 2 || fy > h - 2) { valid[k] = false; continue }
                valid[k] = true
                val iv = (if (cubic) img.bicubic(fx, fy) else img.bilinear(fx, fy)) * gain + offset
                e[k] = (iv - tpl.v[i]).toDouble()
                se += e[k] * e[k]; cnt++
            }
            if (cnt < 3 * params) { ok = false; break }
            rms = sqrt(se / cnt)
            if (it % 3 == 0) {
                val ae = FloatArray(cnt); var j = 0
                for (k in 0 until n) if (valid[k]) ae[j++] = abs(e[k]).toFloat()
                val sig = max(1.4826 * Sr.median(ae, cnt), 1e-3)
                val c = 1.345 * sig
                for (k in 0 until n) { val a = abs(e[k]); wt[k] = if (a <= c) 1.0 else c / a }
            }
            val hm = Array(params) { DoubleArray(params) }
            val b = DoubleArray(params)
            for (k in 0 until n) {
                if (!valid[k]) continue
                val s = sd[k]; val ww = wt[k]
                for (r in 0 until params) {
                    val sr = s[r] * ww
                    b[r] += sr * e[k]
                    for (q in r until params) hm[r][q] += sr * s[q]
                }
            }
            for (r in 0 until params) for (q in 0 until r) hm[r][q] = hm[q][r]
            if (it == 0 && !conditioned(hm)) { ok = false; break }
            val dp = Linear.solve(hm, b) ?: run { ok = false; null } ?: break
            val d6 = if (params == 6) dp else doubleArrayOf(0.0, 0.0, dp[0], 0.0, 0.0, dp[1])
            warp = warp.composeInverse(d6)
            val cornerMove = max(abs(d6[0]), abs(d6[1])) * w / 2 + max(abs(d6[3]), abs(d6[4])) * h / 2
            if (abs(d6[2]) < 0.005 && abs(d6[5]) < 0.005 && cornerMove < 0.005) break
        }
        var sx = 0.0; var sy = 0.0
        for (k in 0 until n) if (valid[k]) { val i = idx[k]; sx += wt[k] * tx.v[i] * tx.v[i]; sy += wt[k] * ty.v[i] * ty.v[i] }
        return Result(warp, rms, sx, sy, ok, params)
    }

    /** λ_min/λ_max ≥ 1e-6 (Jacobi eigenvalues of the symmetric Hessian). */
    fun conditioned(hm: Array<DoubleArray>): Boolean {
        val ev = Linear.eigenvaluesSym(hm)
        val mx = ev.maxOrNull() ?: return false
        val mn = ev.minOrNull() ?: return false
        return mx > 0 && mn / mx >= 1e-6
    }

    /** Pyramid of [levels] (level 0 first). */
    fun pyramid(p: Plane, levels: Int): List<Plane> {
        val out = arrayListOf(p)
        while (out.size < levels) out += Sr.down2(out.last())
        return out
    }

    /**
     * Full registration: [tpl] / [img] at level 0 (blurred σ 0.6), affine first, translation as the
     * fallback, from the translation [t0].
     */
    fun register(tplPyr: List<Plane>, img: Plane, t0x: Double, t0y: Double, noise: Double, affine: Boolean = true): Result {
        val imgPyr = pyramid(Sr.gauss(img, 0.6), tplPyr.size)
        fun run(params: Int): Result {
            var warp: Affine? = null
            var last: Result? = null
            for (L in tplPyr.indices.reversed()) {
                val t = tplPyr[L]
                val cx = (t.w - 1) / 2.0; val cy = (t.h - 1) / 2.0
                val f = 1.0 / (1 shl L)
                warp = warp?.scaled(2.0, cx, cy) ?: Affine.translation(t0x * f, t0y * f, cx, cy)
                val r = level(t, imgPyr[L], warp, params, 20, cubic = L == 0, border = max(2, 16 shr L))
                if (!r.ok) return r
                warp = r.warp
                last = r
            }
            return last!!
        }
        val limit = 3 * sqrt(2.0) * noise + 6.0
        // Model choice: the affine warp only when it explains the frame clearly better than a shift
        // (on aliased detail an affine fit otherwise picks up a spurious scale or shear).
        val t = run(2)
        if (affine) {
            val a = run(6)
            if (a.ok && a.rms <= limit && (!t.ok || a.rms < AFFINE_GAIN * t.rms)) return a
        }
        return Result(t.warp, t.rms, t.sumTx2, t.sumTy2, t.ok && t.rms <= limit * 1.5, 2)
    }
}

/** Tiny dense linear algebra for the 6×6 normal equations. */
object Linear {
    /** Cholesky solve of a symmetric positive-definite system; null when it isn't. */
    fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val l = Array(n) { DoubleArray(n) }
        for (i in 0 until n) for (j in 0..i) {
            var s = a[i][j]
            for (k in 0 until j) s -= l[i][k] * l[j][k]
            if (i == j) { if (s <= 1e-12) return null; l[i][i] = sqrt(s) } else l[i][j] = s / l[j][j]
        }
        val y = DoubleArray(n)
        for (i in 0 until n) { var s = b[i]; for (k in 0 until i) s -= l[i][k] * y[k]; y[i] = s / l[i][i] }
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) { var s = y[i]; for (k in i + 1 until n) s -= l[k][i] * x[k]; x[i] = s / l[i][i] }
        return x
    }

    fun eigenvaluesSym(m: Array<DoubleArray>): DoubleArray {
        val n = m.size
        val a = Array(n) { m[it].copyOf() }
        repeat(60) {
            var off = 0.0
            for (p in 0 until n) for (q in p + 1 until n) off += a[p][q] * a[p][q]
            if (off < 1e-18) return DoubleArray(n) { a[it][it] }
            for (p in 0 until n) for (q in p + 1 until n) {
                if (abs(a[p][q]) < 1e-300) continue
                val theta = (a[q][q] - a[p][p]) / (2 * a[p][q])
                val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
                val c = 1 / sqrt(t * t + 1); val s = t * c
                for (k in 0 until n) {
                    val akp = a[k][p]; val akq = a[k][q]
                    a[k][p] = c * akp - s * akq; a[k][q] = s * akp + c * akq
                }
                for (k in 0 until n) {
                    val apk = a[p][k]; val aqk = a[q][k]
                    a[p][k] = c * apk - s * aqk; a[q][k] = s * apk + c * aqk
                }
            }
        }
        return DoubleArray(n) { a[it][it] }
    }
}

/** §1.9 helpers: the PSF from straight edges, and Richardson-Lucy with a TV term. */
object Deconv {
    /** σ of the LR system blur from the 10-90 % rise of straight edges, or null with too few edges. */
    fun psfSigma(p: Plane): Double? {
        val w = p.w; val h = p.h
        val gx = FloatArray(w * h); val gy = FloatArray(w * h); val mag = FloatArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            gx[i] = 0.5f * (p.v[i + 1] - p.v[i - 1]); gy[i] = 0.5f * (p.v[i + w] - p.v[i - w])
            mag[i] = sqrt(gx[i] * gx[i] + gy[i] * gy[i])
        }
        // The top 0.5 % of gradients (at least 400 candidates on small pictures).
        val cut = Sr.percentile(mag, mag.size, 1 - max(0.005, 400.0 / mag.size).coerceAtMost(0.05))
        if (cut <= 4f) return null
        val sigmas = ArrayList<Double>()
        val used = BooleanArray(w * h)
        for (y in 6 until h - 6) for (x in 6 until w - 6) {
            val i = y * w + x
            if (mag[i] < cut || used[i]) continue
            // Structure-tensor coherence over 5×5.
            var jxx = 0.0; var jyy = 0.0; var jxy = 0.0
            for (dy in -2..2) for (dx in -2..2) { val k = i + dy * w + dx; jxx += gx[k] * gx[k]; jyy += gy[k] * gy[k]; jxy += gx[k] * gy[k] }
            val tr = jxx + jyy; val dt = sqrt((jxx - jyy) * (jxx - jyy) + 4 * jxy * jxy)
            if (tr <= 0 || dt / tr < 0.8) continue
            for (dy in -3..3) for (dx in -3..3) used[i + dy * w + dx] = true
            val nx = gx[i] / mag[i]; val ny = gy[i] / mag[i]
            val prof = DoubleArray(33) { k -> val t = (k - 16) * 0.25; p.bilinear(x + nx * t, y + ny * t).toDouble() }
            val lo = prof.take(5).sorted()[2]; val hi = prof.takeLast(5).sorted()[2]
            if (hi - lo < 10) continue
            fun cross(level: Double): Double? {
                for (k in 1 until prof.size) {
                    val a = (prof[k - 1] - lo) / (hi - lo); val b = (prof[k] - lo) / (hi - lo)
                    if (a < level && b >= level) return (k - 1 + (level - a) / (b - a)) * 0.25
                }
                return null
            }
            val t10 = cross(0.1) ?: continue
            val t90 = cross(0.9) ?: continue
            if (t90 <= t10) continue
            sigmas += (t90 - t10) / 2.563
        }
        // At least 50 edges on a photo-sized picture (fewer on a small one).
        if (sigmas.size < (w * h / 2000).coerceIn(15, 50)) return null
        sigmas.sort()
        return sigmas[sigmas.size / 2].coerceIn(0.35, 1.0)
    }

    /** RL-TV on z (0..255) with a Gaussian PSF of [sigma] HR px. Returns the deconvolved plane in 0..255. */
    fun rlTv(z: Plane, sigma: Double, iters: Int, lambdaTv: Double): Plane {
        val w = z.w; val h = z.h; val n = w * h
        val zz = FloatArray(n) { (z.v[it] + 1f) / 256f }
        var u = Plane(w, h, zz.copyOf())
        val zp = Plane(w, h, zz)
        val r = ceil(3 * sigma).toInt().coerceAtLeast(1)
        val k = FloatArray(2 * r + 1) { i -> exp(-((i - r) * (i - r)) / (2 * sigma * sigma)).toFloat() }
        val ks = k.sum(); for (i in k.indices) k[i] /= ks
        repeat(iters) {
            val hu = Sr.conv(u, k)
            val ratio = Plane(w, h)
            Sr.parallelRows(h) { y -> for (i in y * w until (y + 1) * w) ratio.v[i] = zp.v[i] / max(hu.v[i], 1e-4f) }
            val corr = Sr.conv(ratio, k)
            val tv = tvDiv(u)
            val next = FloatArray(n)
            val lt = lambdaTv.toFloat()
            Sr.parallelRows(h) { y -> for (i in y * w until (y + 1) * w) next[i] = u.v[i] * corr.v[i] / max(1f - lt * tv[i], 0.5f) }
            u = Plane(w, h, next)
        }
        return Plane(w, h, FloatArray(n) { 256f * u.v[it] - 1f })
    }

    /** div(∇u/|∇u|): forward differences for ∇, backward for div. */
    private fun tvDiv(u: Plane): FloatArray {
        val w = u.w; val h = u.h
        val px = FloatArray(w * h); val py = FloatArray(w * h)
        Sr.parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val dx = if (x < w - 1) u.v[i + 1] - u.v[i] else 0f
                val dy = if (y < h - 1) u.v[i + w] - u.v[i] else 0f
                val m = sqrt(dx * dx + dy * dy + 1e-6f)
                px[i] = dx / m; py[i] = dy / m
            }
        }
        val out = FloatArray(w * h)
        Sr.parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                out[i] = (px[i] - (if (x > 0) px[i - 1] else 0f)) + (py[i] - (if (y > 0) py[i - w] else 0f))
            }
        }
        return out
    }
}

/** §1.10: the guided filter (He et al.) with box filters on integral images. */
object Guided {
    fun box(p: Plane, r: Int): Plane {
        val w = p.w; val h = p.h
        val ii = DoubleArray((w + 1) * (h + 1))
        for (y in 0 until h) { var row = 0.0; for (x in 0 until w) { row += p.v[y * w + x]; ii[(y + 1) * (w + 1) + x + 1] = ii[y * (w + 1) + x + 1] + row } }
        val out = Plane(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val x0 = max(0, x - r); val x1 = min(w - 1, x + r); val y0 = max(0, y - r); val y1 = min(h - 1, y + r)
            val s = ii[(y1 + 1) * (w + 1) + x1 + 1] - ii[y0 * (w + 1) + x1 + 1] - ii[(y1 + 1) * (w + 1) + x0] + ii[y0 * (w + 1) + x0]
            out.v[y * w + x] = (s / ((x1 - x0 + 1) * (y1 - y0 + 1))).toFloat()
        }
        return out
    }

    fun filter(guide: Plane, p: Plane, r: Int, eps: Double): Plane {
        val n = guide.v.size
        val mI = box(guide, r); val mP = box(p, r)
        val ip = Plane(guide.w, guide.h, FloatArray(n) { guide.v[it] * p.v[it] })
        val ii = Plane(guide.w, guide.h, FloatArray(n) { guide.v[it] * guide.v[it] })
        val cIp = box(ip, r); val cII = box(ii, r)
        val a = Plane(guide.w, guide.h); val b = Plane(guide.w, guide.h)
        for (i in 0 until n) {
            val varI = cII.v[i] - mI.v[i] * mI.v[i]
            val cov = cIp.v[i] - mI.v[i] * mP.v[i]
            a.v[i] = (cov / (varI + eps)).toFloat()
            b.v[i] = mP.v[i] - a.v[i] * mI.v[i]
        }
        val ma = box(a, r); val mb = box(b, r)
        return Plane(guide.w, guide.h, FloatArray(n) { ma.v[it] * guide.v[it] + mb.v[it] })
    }
}

/**
 * Iterative back-projection (Irani-Peleg) on the HR grid: each kept LR sample is predicted from the
 * HR estimate through its exact pixel footprint (the s×s box the LR pixel integrated, mapped through
 * the warp), and the weighted errors are spread back over that footprint, normalised by how many
 * samples touch each HR pixel. A few iterations from the shift-and-add result make the HR picture
 * consistent with every frame, which is what turns aliasing back into detail; early stopping and the
 * tile weights keep noise and moving parts out.
 */
object BackProject {
    /** 1-D overlap weights of HR pixels with the interval [lo, hi] (pixel u covers [u − 0.5, u + 0.5]). */
    private inline fun footprint(lo: Double, hi: Double, n: Int, body: (Int, Float) -> Unit) {
        val u0 = floor(lo + 0.5).toInt(); val u1 = floor(hi + 0.5).toInt()
        for (u in u0..u1) {
            val ov = min(hi, u + 0.5) - max(lo, u - 0.5)
            if (ov > 1e-6) body(u.coerceIn(0, n - 1), ov.toFloat())
        }
    }

    fun run(x0: Plane, frames: List<Plane>, warps: List<Affine>, weights: List<Plane?>, s: Int, iters: Int, step: Float = 1f): Plane {
        val hw = x0.w; val hh = x0.h
        var x = x0.copy()
        val inv = warps.map { it.inverse() }
        val lw = frames[0].w; val lh = frames[0].h
        val half = s / 2.0
        // LR rows in 8 bands, the even bands at once and then the odd ones: bands that run together are a band
        // apart, so they never touch the same HR rows.
        val bands = 8
        val bandH = (lh + bands - 1) / bands
        repeat(iters) {
            val num = FloatArray(hw * hh); val den = FloatArray(hw * hh)
            for (k in frames.indices) for (phase in 0..1) Sr.parallelFor(bands / 2) { job ->
                val band = 2 * job + phase
                val yk = frames[k]; val iw = inv[k]; val wk = weights[k]
                for (qy in band * bandH until min(lh, (band + 1) * bandH)) for (qx in 0 until lw) {
                    val xr = iw.mapX(qx.toDouble(), qy.toDouble()); val yr = iw.mapY(qx.toDouble(), qy.toDouble())
                    if (xr < -0.5 || yr < -0.5 || xr > lw - 0.5 || yr > lh - 0.5) continue
                    val wq = (wk?.bilinear(xr, yr) ?: 1f)
                    if (wq <= 1e-3f) continue
                    val hu = (xr + 0.5) * s - 0.5; val hv = (yr + 0.5) * s - 0.5
                    var pred = 0f; var area = 0f
                    footprint(hv - half, hv + half, hh) { v, ay -> footprint(hu - half, hu + half, hw) { u, ax -> pred += ax * ay * x.v[v * hw + u]; area += ax * ay } }
                    if (area <= 0f) continue
                    val e = yk.v[qy * lw + qx] - pred / area
                    footprint(hv - half, hv + half, hh) { v, ay -> footprint(hu - half, hu + half, hw) { u, ax ->
                        val a = ax * ay / area * wq
                        num[v * hw + u] += a * e; den[v * hw + u] += a
                    } }
                }
            }
            val nx = FloatArray(hw * hh)
            for (i in nx.indices) nx[i] = x.v[i] + if (den[i] > 1e-3f) step * num[i] / den[i] else 0f
            x = Plane(hw, hh, nx)
        }
        return x
    }
}

/** The whole multi-frame pipeline. */
object BurstSR {
    enum class Source { JPEG, H264 }

    data class Params(
        val scale: Int = 2,
        val nRl: Int,
        val lambdaTv: Double,
        val sigmaSysDefault: Double,
        val tone: Boolean = true,
        /** Back-projection iterations after the shift-and-add, and their step. */
        val nBp: Int = 8,
        val bpStep: Float = 1f,
        /** How much of the remaining system blur the light RL takes on. */
        val rlShare: Double = 0.7,
        /** Tests: the true warps instead of registration (reference → frame), and which frame is the reference. */
        val oracle: List<Affine>? = null,
        val oracleRef: Int? = null,
        /** Tests: keep the intermediate planes in [Result.stages]. */
        val keepStages: Boolean = false,
    ) {
        companion object {
            fun of(source: Source, scale: Int = 2) = when (source) {
                Source.JPEG -> Params(scale, nRl = 8, lambdaTv = 0.002, sigmaSysDefault = 0.55)
                // Live frames come 16 at a time: fewer back-projection passes keep a sharp snapshot quick.
                Source.H264 -> Params(scale, nRl = 6, lambdaTv = 0.004, sigmaSysDefault = 0.70, nBp = 6)
            }
        }
    }

    class FrameInfo(val index: Int, val kept: Boolean, val why: String, val warp: Affine?, val sigmaT: Double, val gain: Double)

    class Result(
        val px: IntArray, val w: Int, val h: Int,
        /** Luma before tone (tests compare this with the truth). */
        val luma: Plane,
        val bicubic: Plane,
        val used: Int, val refIndex: Int, val meanNeff: Double,
        val frames: List<FrameInfo>,
        val report: String,
        /** Per-pixel effective frame count on the LR reference grid. */
        val neff: Plane?,
        /** Per-frame tile weights (LR reference grid, bilinear-ready), null for the reference. */
        val weights: List<Plane?>,
        val stages: Map<String, Plane> = emptyMap(),
    )

    const val TILE = 16
    const val EPS_REG = 0.15
    const val KAPPA = 1.5
    const val PRIOR = 0.05f
    const val COMPARE_SIGMA = 1.0
    const val SINGLE_RL_SIGMA = 0.5
    /** N_eff over which the multi-frame result fully replaces the single-frame one. */
    const val MULTI_RAMP = 1.5f

    /**
     * Super-resolves [frames] (ARGB, all [w]×[h]) by [Params.scale]. Never comes out worse than the
     * reference frame upscaled: areas no other frame covers fall back to it.
     */
    fun superResolve(frames: List<IntArray>, w: Int, h: Int, source: Source, p: Params = Params.of(source), progress: (String) -> Unit = {}): Result {
        require(frames.isNotEmpty())
        val s = p.scale
        val h264 = source == Source.H264
        val g = if (h264) 4 else 8
        val ycc = frames.map { Ycc.of(it, w, h) }
        progress("Deblocking")
        val deb = ycc.map { Deblock.run(it.y, h264) }
        val lumas = deb.map { it.plane }
        val sigmaStep = deb.map { it.sigmaStep }.average()
        // §1.4: the sharpest frame is the reference; motion-blurred frames are dropped.
        val sharp = lumas.map { Quality.sharpness(it) }
        val r = p.oracleRef ?: sharp.indices.maxByOrNull { sharp[it] }!!
        val ref = lumas[r]
        val noise = Quality.noise(ref)
        val cx = (w - 1) / 2.0; val cy = (h - 1) / 2.0
        val infos = ArrayList<FrameInfo>()
        val stages = HashMap<String, Plane>()
        val warps = arrayOfNulls<Affine>(frames.size)
        val planes = arrayOfNulls<Plane>(frames.size)
        val flagged = BooleanArray(frames.size)
        warps[r] = Affine.identity(cx, cy); planes[r] = ref
        val (mr, sr) = Quality.moments(ref)
        val tplPyr = Lk.pyramid(Sr.gauss(ref, 0.6), levelsFor(w, h))
        progress("Aligning")
        for (k in frames.indices) {
            if (k == r) { infos += FrameInfo(k, true, "reference", warps[r], 0.0, 1.0); continue }
            if (p.oracle == null && sharp[k] < 0.6 * sharp[r]) { infos += FrameInfo(k, false, "blurred", null, 0.0, 1.0); continue }
            // §1.5 moments first.
            val (mk, sk) = Quality.moments(lumas[k])
            val yk = Plane(w, h, FloatArray(w * h) { ((lumas[k].v[it] - mk) * (sr / sk) + mr).toFloat() })
            if (p.oracle != null) {
                warps[k] = p.oracle[k]; planes[k] = yk
                infos += FrameInfo(k, true, "oracle", warps[k], 0.0, sr / sk); continue
            }
            val pc = PhaseCorr.estimate(if (w > 400) Sr.down2(ref, false) else ref, if (w > 400) Sr.down2(yk, false) else yk)
            if (pc.rho < 1.5) { infos += FrameInfo(k, false, "no reliable match (ρ %.2f)".format(Locale.US, pc.rho), null, 0.0, 1.0); continue }
            val f = if (w > 400) 2.0 else 1.0
            val lk = Lk.register(tplPyr, yk, pc.dx * f, pc.dy * f, noise)
            if (!lk.ok || abs(lk.warp.tx) > w / 4.0 || abs(lk.warp.ty) > h / 4.0) {
                infos += FrameInfo(k, false, "alignment failed", null, 0.0, 1.0); continue
            }
            // §1.5 least-squares gain/offset on the warped overlap, then one more pass at level 0.
            val (alpha, beta) = gainOffset(ref, yk, lk.warp)
            if (alpha !in 0.7..1.4) { infos += FrameInfo(k, false, "lighting changed (gain %.2f)".format(Locale.US, alpha), null, 0.0, alpha); continue }
            val matched = Plane(w, h, FloatArray(w * h) { (yk.v[it] * alpha + beta).toFloat() })
            val fin = Lk.level(tplPyr[0], Sr.gauss(matched, 0.6), lk.warp, lk.model, 5, cubic = true, border = 16)
            val warp = if (fin.ok) fin.warp else lk.warp
            // §1.6.3 Cramér-Rao.
            val sumT = min(fin.sumTx2.takeIf { fin.ok } ?: lk.sumTx2, fin.sumTy2.takeIf { fin.ok } ?: lk.sumTy2)
            val sigT = if (sumT <= 0) 9.0 else noise * sqrt(2.0 / sumT)
            flagged[k] = sigT > 0.1
            warps[k] = warp; planes[k] = matched
            infos += FrameInfo(k, true, if (lk.model == 6) "affine" else "translation", warp, sigT, alpha * sr / sk)
        }
        val kept = frames.indices.filter { warps[it] != null }
        val bic = Sr.bicubicUp(ref, s)
        // With one usable frame the same path runs as a denoised, mildly deconvolved single frame (said so).
        progress("Weighing")
        // §1.7 per-tile weights.
        val sigmaQ2 = if (h264) sigmaStep * sigmaStep else 0.25 * sigmaStep * sigmaStep
        val weights = arrayOfNulls<Plane>(frames.size)
        val raw = frames.map { it }
        for (k in kept) if (k != r) weights[k] = tileWeights(ref, planes[k]!!, warps[k]!!, noise, sigmaQ2,
            if (h264 && k > 0) skipBlocks(raw[k], raw[k - 1], w, h) else null)
        val neff = Plane(w, h, FloatArray(w * h) { i ->
            var sum = 1f
            for (k in kept) if (k != r) sum += weights[k]!!.v[i]
            sum
        })
        val meanNeff = neff.mean()
        // §1.13 too little motion: all fractional shifts the same.
        val fracs = kept.filter { it != r }.map { warps[it]!! }
        val clustered = fracs.isNotEmpty() && fracs.all { a -> fracDist(a.tx, 0.0) < 0.1 && fracDist(a.ty, 0.0) < 0.1 }
        progress("Combining")
        // §1.8 gather.
        val sigK = (0.35 + 0.6 / sqrt(meanNeff)).coerceIn(0.45, 0.95)
        val wide = sigK > 0.7
        val hw = w * s; val hh = h * s
        val z = Plane(hw, hh)
        val dens = Plane(hw, hh)
        val neffHr = Plane(hw, hh)
        val inv2s2 = (1.0 / (2 * sigK * sigK)).toFloat()
        val order = kept.toIntArray()
        Sr.parallelRows(hh) { v ->
            val yr = (v + 0.5) / s - 0.5
            for (u in 0 until hw) {
                val xr = (u + 0.5) / s - 0.5
                var num = PRIOR * bic.v[v * hw + u]
                var den = PRIOR
                for (k in order) {
                    val wp = warps[k]!!
                    val px = wp.mapX(xr, yr); val py = wp.mapY(xr, yr)
                    var wk = if (k == r) 1f else weights[k]!!.bilinear(xr, yr)
                    if (wk <= 1e-4f) continue
                    if (flagged[k]) wk *= 0.5f
                    val pl = planes[k]!!
                    val bx = if (wide) floor(px).toInt() - 1 else px.roundToInt() - 1
                    val by = if (wide) floor(py).toInt() - 1 else py.roundToInt() - 1
                    val nt = if (wide) 4 else 3
                    for (j in 0 until nt) {
                        val qy = by + j
                        if (qy < 0 || qy >= h) continue
                        val dy = (qy - py).toFloat()
                        for (i in 0 until nt) {
                            val qx = bx + i
                            if (qx < 0 || qx >= w) continue
                            val dx = (qx - px).toFloat()
                            val om = wk * Deblock.confidence(qx, qy, g) * exp(-(dx * dx + dy * dy) * inv2s2)
                            num += om * pl.v[qy * w + qx]
                            den += om
                        }
                    }
                }
                z.v[v * hw + u] = num / den
                dens.v[v * hw + u] = den
                neffHr.v[v * hw + u] = neff.bilinear(xr, yr)
            }
        }
        progress("Sharpening")
        // §1.9 PSF and RL-TV.
        val sigSys = Deconv.psfSigma(ref) ?: p.sigmaSysDefault
        val single = meanNeff <= 1.5
        // Shift-and-add refined by back-projection through each sample's own pixel footprint.
        val zbp = if (kept.size >= 2 && p.nBp > 0) BackProject.run(z, kept.map { planes[it]!! }, kept.map { warps[it]!! },
            kept.map { if (it == r) null else weights[it] }, s, p.nBp, p.bpStep) else z
        // Light RL for what is left of the system blur beyond the pixel aperture the back-projection undid
        // (on a single frame: the whole blur of the gather, gently).
        val sigH = if (zbp !== z) min(s * sqrt(max(sigSys * sigSys - 1.0 / 12, 0.0)) * p.rlShare, 1.0 * s)
            else min(s * sqrt(sigSys * sigSys + sigK * sigK), 1.0 * s)
        val nRl = if (single) 4 else p.nRl
        val lam0 = if (single) 0.006 else p.lambdaTv
        val lam = (lam0 * (noise / 2) * sqrt(1 / meanNeff)).coerceIn(0.001, 0.02)
        val dec = if (sigH >= 0.3 && nRl > 0) Deconv.rlTv(zbp, sigH, nRl, lam) else zbp
        // Where no other frame adds samples, the reference alone: bicubic with a light RL (never worse than bicubic).
        val single1 = Deconv.rlTv(bic, SINGLE_RL_SIGMA * s / 2.0, 4, 0.004)
        val ySr = Plane(hw, hh)
        val margin = 0.02f * 255
        for (v in 0 until hh) for (u in 0 until hw) {
            val i = v * hw + u
            var beta = ((neffHr.v[i] - 1) / 3f).coerceIn(0.3f, 0.9f)
            if (clustered) beta = min(beta, 0.3f)
            var y = zbp.v[i] + beta * (dec.v[i] - zbp.v[i])
            var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
            for (dy in -1..1) for (dx in -1..1) { val q = zbp.at(u + dx, v + dy); if (q < lo) lo = q; if (q > hi) hi = q }
            y = y.coerceIn(lo - margin, hi + margin)
            var f = single1.v[i]
            var blo = Float.MAX_VALUE; var bhi = -Float.MAX_VALUE
            for (dy in -1..1) for (dx in -1..1) { val q = bic.at(u + dx, v + dy); if (q < blo) blo = q; if (q > bhi) bhi = q }
            f = f.coerceIn(blo - margin, bhi + margin)
            val gamma = ((neffHr.v[i] - 1f) / MULTI_RAMP).coerceIn(0f, 1f)
            ySr.v[i] = f + gamma * (y - f)
        }
        if (p.keepStages) { stages["z"] = z; stages["bp"] = zbp; stages["dec"] = dec; stages["single"] = single1; stages["neff"] = neffHr }
        progress("Colour")
        // §1.10 chroma: weighted LR average with the same warps and weights, then guided upsample.
        fun chroma(sel: (Ycc) -> Plane): Plane {
            val acc = Plane(w, h)
            for (y in 0 until h) for (x in 0 until w) {
                var num = 0.0; var den = 0.0
                for (k in kept) {
                    val wk = if (k == r) 1.0 else weights[k]!!.v[y * w + x].toDouble()
                    if (wk <= 1e-4) continue
                    val wp = warps[k]!!
                    num += wk * sel(ycc[k]).bilinear(wp.mapX(x.toDouble(), y.toDouble()), wp.mapY(x.toDouble(), y.toDouble()))
                    den += wk
                }
                acc.v[y * w + x] = (num / den).toFloat()
            }
            val pre = if (Quality.noise(acc) > 3) bilateral(acc) else acc
            return Guided.filter(ySr, Sr.bilinearUp(pre, s), 2 * s, 650.0)
        }
        val cb = chroma { it.cb }
        val cr = chroma { it.cr }
        var out = Ycc(ySr, cb, cr).toArgb()
        if (p.tone) out = ImageEnhance.tone(out, contrast = 1.04f, saturation = 1.08f)
        val shifts = kept.filter { it != r }.joinToString(", ") { k -> "%+.2f,%+.2f".format(Locale.US, warps[k]!!.tx, warps[k]!!.ty) }
        val dropped = infos.filter { !it.kept }
        val report = (if (kept.size < 2) "Single frame (1 usable of ${frames.size}; " else "Burst ×${kept.size} (") +
            "${hw}×$hh from ${w}×$h, N_eff %.1f, σ_n %.1f, PSF σ %.2f LR px%s)".format(Locale.US, meanNeff, noise, sigSys,
            if (clustered) ", frames too similar: denoise only" else "") +
            (if (shifts.isNotEmpty()) "; shifts $shifts" else "") +
            (if (dropped.isNotEmpty()) "; dropped " + dropped.joinToString(", ") { "${it.index + 1} (${it.why})" } else "")
        return Result(out, hw, hh, ySr, bic, kept.size, r, meanNeff, infos, report, neff, weights.toList(), stages)
    }

    private fun levelsFor(w: Int, h: Int): Int { var l = 1; var mw = w; var mh = h; while (l < 3 && mw / 2 >= 48 && mh / 2 >= 48) { l++; mw /= 2; mh /= 2 }; return l }

    private fun fracDist(a: Double, b: Double): Double { val d = abs((a - b) - Math.rint(a - b)); return d }

    /** Least squares α·Ŷ_k + β ≈ Y_r over the warped overlap. */
    fun gainOffset(ref: Plane, img: Plane, warp: Affine): Pair<Double, Double> {
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0; var n = 0
        for (y in 8 until ref.h - 8 step 2) for (x in 8 until ref.w - 8 step 2) {
            val fx = warp.mapX(x.toDouble(), y.toDouble()); val fy = warp.mapY(x.toDouble(), y.toDouble())
            if (fx < 1 || fy < 1 || fx > ref.w - 2 || fy > ref.h - 2) continue
            val a = img.bicubic(fx, fy).toDouble(); val b = ref[x, y].toDouble()
            sx += a; sy += b; sxx += a * a; sxy += a * b; n++
        }
        if (n < 10) return 1.0 to 0.0
        val d = n * sxx - sx * sx
        if (abs(d) < 1e-9) return 1.0 to 0.0
        val alpha = (n * sxy - sx * sy) / d
        return alpha to (sy - alpha * sx) / n
    }

    private fun gradMag2(p: Plane): Plane = Plane(p.w, p.h, FloatArray(p.w * p.h) { i ->
        val x = i % p.w; val y = i / p.w
        val gx = 0.5f * (p.at(x + 1, y) - p.at(x - 1, y)); val gy = 0.5f * (p.at(x, y + 1) - p.at(x, y - 1))
        gx * gx + gy * gy
    })

    /** §1.7 H.264 skip blocks: 16×16 macroblocks identical to the previous decoded frame. */
    fun skipBlocks(cur: IntArray, prev: IntArray, w: Int, h: Int): BooleanArray {
        val mw = (w + 15) / 16; val mh = (h + 15) / 16
        return BooleanArray(mw * mh) { b ->
            val bx = (b % mw) * 16; val by = (b / mw) * 16
            var same = true
            loop@ for (y in by until min(h, by + 16)) for (x in bx until min(w, bx + 16)) if (cur[y * w + x] != prev[y * w + x]) { same = false; break@loop }
            same
        }
    }

    /**
     * §1.7: per 16×16 tile of the reference, how well frame k (warped) matches: ρ = r/E with a budget E
     * for noise, tolerated registration error on edges and codec error; w = exp(−max(0, ρ−1)/κ), 0 past
     * ρ = 5; smoothed over 3×3 tiles and interpolated between tile centres. Returned per LR pixel.
     *
     * The residual is taken after a Gaussian blur of σ [COMPARE_SIGMA] LR px on both pictures (and the
     * noise budget scaled by the blur's 1/(4πσ²)): two correctly aligned frames of a detailed scene
     * still differ in the aliasing they carry, which is exactly what super-resolution uses, so only the
     * low frequencies, where moving objects show, are compared.
     */
    fun tileWeights(ref: Plane, img: Plane, warp: Affine, noise: Double, sigmaQ2: Double, skip: BooleanArray? = null): Plane {
        val w = ref.w; val h = ref.h
        val tw = (w + TILE - 1) / TILE; val th = (h + TILE - 1) / TILE
        val inside = BooleanArray(w * h)
        val warped = Plane(w, h)
        Sr.parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val fx = warp.mapX(x.toDouble(), y.toDouble()); val fy = warp.mapY(x.toDouble(), y.toDouble())
                inside[i] = fx >= 0 && fy >= 0 && fx <= w - 1 && fy <= h - 1
                warped.v[i] = img.bicubic(fx, fy)
            }
        }
        val a = Sr.gauss(ref, COMPARE_SIGMA); val b = Sr.gauss(warped, COMPARE_SIGMA)
        val grad2 = gradMag2(a)
        val noiseVar = 2 * noise * noise / (4 * PI * COMPARE_SIGMA * COMPARE_SIGMA)
        val wt = DoubleArray(tw * th)
        for (ty in 0 until th) for (tx in 0 until tw) {
            var r = 0.0; var gsum = 0.0; var n = 0
            for (y in ty * TILE until min(h, (ty + 1) * TILE)) for (x in tx * TILE until min(w, (tx + 1) * TILE)) {
                val i = y * w + x
                if (!inside[i]) continue
                val d = b.v[i] - a.v[i]
                r += d * d; gsum += grad2.v[i]; n++
            }
            var weight = if (n < TILE * TILE / 4) 0.0 else {
                val e = noiseVar + EPS_REG * EPS_REG * (gsum / n) + sigmaQ2
                val rho = (r / n) / e
                if (rho > 5) 0.0 else exp(-max(0.0, rho - 1) / KAPPA)
            }
            if (skip != null) {
                val mx = (warp.mapX(tx * TILE + TILE / 2.0, ty * TILE + TILE / 2.0) / 16).toInt()
                val my = (warp.mapY(tx * TILE + TILE / 2.0, ty * TILE + TILE / 2.0) / 16).toInt()
                val mw = (w + 15) / 16
                if (mx in 0 until mw && my >= 0 && my * mw + mx < skip.size && skip[my * mw + mx]) weight = 0.0
            }
            wt[ty * tw + tx] = weight
        }
        // 3×3 tile box smoothing (skip blocks and outright rejections stay rejected).
        val sm = DoubleArray(tw * th)
        for (ty in 0 until th) for (tx in 0 until tw) {
            if (wt[ty * tw + tx] == 0.0) { sm[ty * tw + tx] = 0.0; continue }
            var s = 0.0; var n = 0
            for (dy in -1..1) for (dx in -1..1) { val x = tx + dx; val y = ty + dy; if (x in 0 until tw && y in 0 until th) { s += wt[y * tw + x]; n++ } }
            sm[ty * tw + tx] = min(wt[ty * tw + tx], s / n).let { 0.5 * (it + wt[ty * tw + tx]) }
        }
        val out = Plane(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val gx = ((x + 0.5) / TILE - 0.5).coerceIn(0.0, (tw - 1).toDouble())
            val gy = ((y + 0.5) / TILE - 0.5).coerceIn(0.0, (th - 1).toDouble())
            val x0 = floor(gx).toInt(); val y0 = floor(gy).toInt()
            val x1 = min(tw - 1, x0 + 1); val y1 = min(th - 1, y0 + 1)
            val fx = gx - x0; val fy = gy - y0
            val a = sm[y0 * tw + x0]; val b = sm[y0 * tw + x1]; val c = sm[y1 * tw + x0]; val d = sm[y1 * tw + x1]
            out.v[y * w + x] = ((a + (b - a) * fx) * (1 - fy) + (c + (d - c) * fx) * fy).toFloat()
        }
        return out
    }

    private fun bilateral(p: Plane): Plane {
        val o = Plane(p.w, p.h)
        for (y in 0 until p.h) for (x in 0 until p.w) {
            val c = p[x, y]; var s = 0f; var ws = 0f
            for (dy in -1..1) for (dx in -1..1) {
                val q = p.at(x + dx, y + dy); val d = q - c
                val wgt = exp(-(dx * dx + dy * dy) / 2f - d * d / (2 * 6f * 6f))
                s += wgt * q; ws += wgt
            }
            o.v[y * p.w + x] = s / ws
        }
        return o
    }

    /** The JPEG comment (after [JpegCom.TAG]) and Gallery line: what was really done. */
    fun label(used: Int, w: Int, h: Int, srcW: Int, srcH: Int, live: Boolean): String =
        (if (used < 2) "Sharpened from 1 frame, ${w}×$h from ${srcW}×$srcH" else "Burst ×$used, ${w}×$h from ${srcW}×$srcH") +
            if (live) " (live view frames)" else " (Bluetooth photos)"

    /** Natural log helper kept for the report's dB numbers. */
    fun db(x: Double) = 10 * ln(x) / ln(10.0)
}
