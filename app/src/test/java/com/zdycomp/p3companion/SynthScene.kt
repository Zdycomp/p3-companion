package com.zdycomp.p3companion

import java.util.Random
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Test-only scene renderer (MATH §1.12): a continuous scene f(x, y) in LR pixel units, rendered
 * analytically with a box aperture, so tests never depend on the interpolation being tested. It has
 * a zone plate (chirp past the LR Nyquist limit, so it aliases), a 36-spoke Siemens star, thin bars
 * and a smooth gradient.
 */
class SynthScene(val w: Int, val h: Int, chirp: Double = 0.7, private val smooth: Boolean = false) {
    private val zx = w * 0.30; private val zy = h * 0.35; private val zr = w * 0.22
    private val zk = chirp / zr
    private val sx = w * 0.72; private val sy = h * 0.32; private val sr = w * 0.18
    /** A moving square (for the motion test): its offset in scene units, or null. */
    var square: DoubleArray? = null

    fun f(x: Double, y: Double): Double {
        if (smooth) {
            // Band-limited texture (blobs and a soft star) for alignment tests with little aliasing.
            var v = 128.0
            for (k in 0 until 9) {
                val bx = w * (0.15 + 0.35 * (k % 3)); val by = h * (0.15 + 0.35 * (k / 3))
                val d2 = (x - bx) * (x - bx) + (y - by) * (y - by)
                v += (if (k % 2 == 0) 70 else -60) * kotlin.math.exp(-d2 / (2 * (4.0 + k) * (4.0 + k)))
            }
            return v + 25 * sin(0.21 * x + 0.13 * y) * sin(0.17 * y - 0.05 * x)
        }
        var v = 30 + 190 * (0.5 * x / w + 0.5 * y / h)
        val dzx = x - zx; val dzy = y - zy; val rz = sqrt(dzx * dzx + dzy * dzy)
        if (rz < zr) v = 128 + 90 * sin(PI * zk * rz * rz)
        val dsx = x - sx; val dsy = y - sy; val rs = sqrt(dsx * dsx + dsy * dsy)
        if (rs < sr) v = if (sin(18 * atan2(dsy, dsx)) > 0) 220.0 else 40.0
        // Bars of 1, 1.5 and 2 px width.
        val by0 = h * 0.70
        if (y > by0 && y < by0 + h * 0.2) {
            var bx = w * 0.10
            for (width in doubleArrayOf(1.0, 1.5, 2.0)) {
                repeat(5) { if (x >= bx && x < bx + width) v = 25.0; bx += 2 * width }
                bx += 6
            }
        }
        square?.let { q -> if (x >= w * 0.55 + q[0] && x < w * 0.55 + q[0] + w * 0.25 && y >= h * 0.62 + q[1] && y < h * 0.62 + q[1] + h * 0.25) v = 235.0 }
        return v
    }

    /**
     * One frame: pixel (i, j) integrates f over the unit square around the scene point
     * R(θ)·((i, j) − c) + c + (dx, dy), plus Gaussian noise, rounded to 8 bit. [scale] > 1 renders the
     * ground truth on the HR grid for the same geometry (pixel (u, v) at LR ((u+0.5)/s − 0.5, …)).
     */
    fun render(dx: Double, dy: Double, rotDeg: Double = 0.0, noise: Double = 0.0, seed: Long = 1, scale: Int = 1,
               shear: Double = 0.0, gain: Double = 1.0, offset: Double = 0.0, sub: Int = 8, quantize: Boolean = true): Plane {
        val rnd = Random(seed)
        val ow = w * scale; val oh = h * scale
        val out = Plane(ow, oh)
        val cx = (w - 1) / 2.0; val cy = (h - 1) / 2.0
        val th = Math.toRadians(rotDeg); val c = cos(th); val sn = sin(th)
        val ss = if (scale == 1) sub else maxOf(2, sub / scale)
        for (v in 0 until oh) for (u in 0 until ow) {
            val x0 = (u + 0.5) / scale - 0.5; val y0 = (v + 0.5) / scale - 0.5
            var acc = 0.0
            for (b in 0 until ss) for (a in 0 until ss) {
                val px = x0 + ((a + 0.5) / ss - 0.5) / scale
                val py = y0 + ((b + 0.5) / ss - 0.5) / scale
                val rx = px - cx; val ry = py - cy
                val sxp = cx + c * rx - sn * ry + dx + shear * ry
                val syp = cy + sn * rx + c * ry + dy
                acc += f(sxp, syp)
            }
            var value = gain * acc / (ss * ss) + offset
            if (noise > 0) value += noise * rnd.nextGaussian()
            out.v[v * ow + u] = if (quantize) value.coerceIn(0.0, 255.0).roundToInt().toFloat() else value.toFloat()
        }
        return out
    }

    companion object {
        fun argb(p: Plane): IntArray = IntArray(p.v.size) { i ->
            val g = p.v[i].roundToInt().coerceIn(0, 255)
            (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }

        /** 8×8 DCT-II, uniform quantiser of step [q], IDCT: a pure Kotlin blocking simulator. */
        fun jpegish(p: Plane, q: Double): Plane {
            val out = p.copy()
            val cs = Array(8) { k -> DoubleArray(8) { n -> cos(PI * (n + 0.5) * k / 8) * (if (k == 0) sqrt(1.0 / 8) else sqrt(2.0 / 8)) } }
            for (by in 0 until p.h / 8) for (bx in 0 until p.w / 8) {
                val blk = Array(8) { y -> DoubleArray(8) { x -> p[bx * 8 + x, by * 8 + y] - 128.0 } }
                val co = Array(8) { DoubleArray(8) }
                for (v in 0 until 8) for (u in 0 until 8) {
                    var s = 0.0
                    for (y in 0 until 8) for (x in 0 until 8) s += cs[u][x] * cs[v][y] * blk[y][x]
                    co[v][u] = Math.rint(s / q) * q
                }
                for (y in 0 until 8) for (x in 0 until 8) {
                    var s = 0.0
                    for (v in 0 until 8) for (u in 0 until 8) s += cs[u][x] * cs[v][y] * co[v][u]
                    out.v[(by * 8 + y) * p.w + bx * 8 + x] = (s + 128).coerceIn(0.0, 255.0).toFloat()
                }
            }
            return out
        }
    }
}
