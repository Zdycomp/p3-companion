package com.zdycomp.p3companion

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/**
 * 4.0: the on-phone picture enhancement for Bluetooth photos and live-view snapshots, in plain Kotlin on
 * ARGB [IntArray] pixels so the JVM tests run exactly the code the app runs (no Bitmap, no java.awt).
 * Call it off the main thread.
 *
 * The glasses only ever send 640×480 over Bluetooth (and 320×240 live), so this can't add detail the
 * camera never sent. It makes what is there look its best on a phone screen: a light edge-keeping
 * denoise (JPEG blocks and sensor grain), a Lanczos-3 upscale to 2× (or 3×), an adaptive unsharp mask
 * that sharpens edges but not noise and doesn't draw halos, and a touch of contrast and colour.
 */
object ImageEnhance {

    class Image(val px: IntArray, val w: Int, val h: Int)

    /** The whole pipeline. [scale] 2 or 3; [strength] 0..1 (0.6 is the default "natural" look). */
    fun enhance(px: IntArray, w: Int, h: Int, scale: Int = 2, strength: Float = DEFAULT_STRENGTH): Image {
        require(w > 0 && h > 0 && px.size >= w * h) { "bad image" }
        val s = strength.coerceIn(0f, 1f)
        val k = scale.coerceIn(1, 4)
        val clean = if (s > 0f) bilateral3(px, w, h, sigmaRange = 6f + 14f * s) else px.copyOf(w * h)
        val big = if (k > 1) upscale(clean, w, h, w * k, h * k) else clean
        val bw = w * k
        val bh = h * k
        val sharp = if (s > 0f) unsharp(big, bw, bh, amount = 0.35f + 0.9f * s, threshold = 2f + 2f * (1 - s)) else big
        val toned = if (s > 0f) tone(sharp, contrast = 1f + 0.08f * s, saturation = 1f + 0.12f * s) else sharp
        return Image(toned, bw, bh)
    }

    const val DEFAULT_STRENGTH = 0.6f

    // ---------------- Lanczos-3 resize ----------------

    /** Lanczos window with a = 3: sinc(x)·sinc(x/3) for |x| < 3. */
    fun lanczos3(x: Double): Double {
        val ax = abs(x)
        if (ax < 1e-9) return 1.0
        if (ax >= 3.0) return 0.0
        val px = PI * x
        return 3.0 * sin(px) * sin(px / 3.0) / (px * px)
    }

    /** For each of [dst] output positions: the first source index and 6 normalised weights (clamped at the edges). */
    class Taps(val start: IntArray, val weights: FloatArray, val n: Int = 6)

    fun taps(src: Int, dst: Int): Taps {
        val n = 6
        val start = IntArray(dst)
        val wts = FloatArray(dst * n)
        val ratio = src.toDouble() / dst
        for (o in 0 until dst) {
            val center = (o + 0.5) * ratio - 0.5
            val first = floor(center).toInt() - 2
            start[o] = first
            var sum = 0.0
            for (t in 0 until n) {
                val v = lanczos3(center - (first + t))
                wts[o * n + t] = v.toFloat()
                sum += v
            }
            for (t in 0 until n) wts[o * n + t] = (wts[o * n + t] / sum).toFloat()
        }
        return Taps(start, wts, n)
    }

    /** Separable Lanczos-3 resize to [dw]×[dh]; edge pixels repeat outwards. */
    fun upscale(px: IntArray, w: Int, h: Int, dw: Int, dh: Int): IntArray {
        val tx = taps(w, dw)
        val ty = taps(h, dh)
        // Horizontal pass into floats: 3 channels.
        val mid = FloatArray(dw * h * 3)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until dw) {
                var r = 0f; var g = 0f; var b = 0f
                val s0 = tx.start[x]
                for (t in 0 until tx.n) {
                    val sx = (s0 + t).coerceIn(0, w - 1)
                    val c = px[row + sx]
                    val wt = tx.weights[x * tx.n + t]
                    r += wt * ((c shr 16) and 0xFF); g += wt * ((c shr 8) and 0xFF); b += wt * (c and 0xFF)
                }
                val i = (y * dw + x) * 3
                mid[i] = r; mid[i + 1] = g; mid[i + 2] = b
            }
        }
        val out = IntArray(dw * dh)
        for (y in 0 until dh) {
            val s0 = ty.start[y]
            for (x in 0 until dw) {
                var r = 0f; var g = 0f; var b = 0f
                for (t in 0 until ty.n) {
                    val sy = (s0 + t).coerceIn(0, h - 1)
                    val i = (sy * dw + x) * 3
                    val wt = ty.weights[y * ty.n + t]
                    r += wt * mid[i]; g += wt * mid[i + 1]; b += wt * mid[i + 2]
                }
                out[y * dw + x] = argb(r, g, b)
            }
        }
        return out
    }

    // ---------------- denoise ----------------

    /**
     * A 3×3 bilateral filter: neighbours count less the more their colour differs ([sigmaRange] in 0..255
     * levels), so grain and JPEG blocks smooth out while real edges stay sharp.
     */
    fun bilateral3(px: IntArray, w: Int, h: Int, sigmaRange: Float = 12f): IntArray {
        val out = IntArray(w * h)
        val spatial = floatArrayOf(0.6065f, 1f, 0.6065f) // exp(-d²/2) for d = 1, 0, 1 (σ = 1 px)
        val lut = FloatArray(766) { d -> exp(-(d / 3.0).let { it * it } / (2.0 * sigmaRange * sigmaRange)).toFloat() }
        for (y in 0 until h) for (x in 0 until w) {
            val c = px[y * w + x]
            val cr = (c shr 16) and 0xFF; val cg = (c shr 8) and 0xFF; val cb = c and 0xFF
            var r = 0f; var g = 0f; var b = 0f; var sum = 0f
            for (dy in -1..1) {
                val yy = (y + dy).coerceIn(0, h - 1)
                for (dx in -1..1) {
                    val n = px[yy * w + (x + dx).coerceIn(0, w - 1)]
                    val nr = (n shr 16) and 0xFF; val ng = (n shr 8) and 0xFF; val nb = n and 0xFF
                    val diff = abs(nr - cr) + abs(ng - cg) + abs(nb - cb)
                    val wt = spatial[dx + 1] * spatial[dy + 1] * lut[diff]
                    r += wt * nr; g += wt * ng; b += wt * nb; sum += wt
                }
            }
            out[y * w + x] = argb(r / sum, g / sum, b / sum)
        }
        return out
    }

    /** A 3×3 median per channel (an alternative denoise for salt-and-pepper noise). */
    fun median3(px: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        val rs = IntArray(9); val gs = IntArray(9); val bs = IntArray(9)
        for (y in 0 until h) for (x in 0 until w) {
            var k = 0
            for (dy in -1..1) for (dx in -1..1) {
                val n = px[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)]
                rs[k] = (n shr 16) and 0xFF; gs[k] = (n shr 8) and 0xFF; bs[k] = n and 0xFF; k++
            }
            rs.sort(); gs.sort(); bs.sort()
            out[y * w + x] = (0xFF shl 24) or (rs[4] shl 16) or (gs[4] shl 8) or bs[4]
        }
        return out
    }

    // ---------------- sharpen ----------------

    /**
     * Adaptive unsharp mask: the detail (pixel minus a 3×3 Gaussian blur of the brightness) is added back
     * [amount] times, faded out for differences under [threshold] levels (noise stays as it is), and each
     * result is held within its 3×3 neighbourhood's range plus a small margin, so edges get crisper
     * without light or dark halos.
     */
    fun unsharp(px: IntArray, w: Int, h: Int, amount: Float = 0.8f, threshold: Float = 3f): IntArray {
        val lum = FloatArray(w * h) { luma(px[it]) }
        val out = IntArray(w * h)
        val margin = 6
        for (y in 0 until h) for (x in 0 until w) {
            var blur = 0f
            for (dy in -1..1) {
                val yy = (y + dy).coerceIn(0, h - 1)
                val wy = if (dy == 0) 2f else 1f
                for (dx in -1..1) {
                    val wx = if (dx == 0) 2f else 1f
                    blur += wx * wy * lum[yy * w + (x + dx).coerceIn(0, w - 1)]
                }
            }
            blur /= 16f
            val c = px[y * w + x]
            val d = lum[y * w + x] - blur
            val ad = abs(d)
            val gain = if (ad <= threshold) 0f else amount * ((ad - threshold) / (threshold * 2f)).coerceAtMost(1f)
            if (gain == 0f) { out[y * w + x] = c or (0xFF shl 24); continue }
            var minR = 255; var minG = 255; var minB = 255; var maxR = 0; var maxG = 0; var maxB = 0
            for (dy in -1..1) for (dx in -1..1) {
                val n = px[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)]
                val nr = (n shr 16) and 0xFF; val ng = (n shr 8) and 0xFF; val nb = n and 0xFF
                if (nr < minR) minR = nr; if (nr > maxR) maxR = nr
                if (ng < minG) minG = ng; if (ng > maxG) maxG = ng
                if (nb < minB) minB = nb; if (nb > maxB) maxB = nb
            }
            val add = gain * d
            val r = (((c shr 16) and 0xFF) + add).coerceIn((minR - margin).toFloat(), (maxR + margin).toFloat())
            val g = (((c shr 8) and 0xFF) + add).coerceIn((minG - margin).toFloat(), (maxG + margin).toFloat())
            val b = ((c and 0xFF) + add).coerceIn((minB - margin).toFloat(), (maxB + margin).toFloat())
            out[y * w + x] = argb(r, g, b)
        }
        return out
    }

    /**
     * The light 3×3 kernel for the live picture: centre 1 + 4·[amount], the four neighbours −[amount]
     * (the live view runs the same kernel on the GPU; this is its reference).
     */
    fun sharpen3x3(px: IntArray, w: Int, h: Int, amount: Float = 0.5f): IntArray {
        val out = IntArray(w * h)
        val c0 = 1f + 4f * amount
        for (y in 0 until h) for (x in 0 until w) {
            val c = px[y * w + x]
            val up = px[(y - 1).coerceAtLeast(0) * w + x]
            val dn = px[(y + 1).coerceAtMost(h - 1) * w + x]
            val lf = px[y * w + (x - 1).coerceAtLeast(0)]
            val rt = px[y * w + (x + 1).coerceAtMost(w - 1)]
            fun ch(sh: Int): Float = c0 * ((c shr sh) and 0xFF) - amount * (((up shr sh) and 0xFF) + ((dn shr sh) and 0xFF) + ((lf shr sh) and 0xFF) + ((rt shr sh) and 0xFF))
            out[y * w + x] = argb(ch(16), ch(8), ch(0))
        }
        return out
    }

    // ---------------- colour ----------------

    /** Contrast around mid-grey and saturation around each pixel's own brightness; grey stays grey. */
    fun tone(px: IntArray, contrast: Float = 1.05f, saturation: Float = 1.08f): IntArray = IntArray(px.size) { i ->
        val c = px[i]
        var r = ((c shr 16) and 0xFF).toFloat(); var g = ((c shr 8) and 0xFF).toFloat(); var b = (c and 0xFF).toFloat()
        val y = 0.299f * r + 0.587f * g + 0.114f * b
        r = y + (r - y) * saturation; g = y + (g - y) * saturation; b = y + (b - y) * saturation
        argb((r - 128f) * contrast + 128f, (g - 128f) * contrast + 128f, (b - 128f) * contrast + 128f)
    }

    fun luma(c: Int): Float = 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)

    private fun clamp8(v: Float): Int = if (v <= 0f) 0 else if (v >= 255f) 255 else (v + 0.5f).toInt()

    private fun argb(r: Float, g: Float, b: Float): Int = (0xFF shl 24) or (clamp8(r) shl 16) or (clamp8(g) shl 8) or clamp8(b)

    /** "Enhanced 1280×960 from 640×480" for the gallery's details. */
    fun label(w: Int, h: Int, srcW: Int, srcH: Int): String = "Enhanced ${w}×$h from ${srcW}×$srcH"
}

/**
 * 4.0: a JPEG comment (COM, FF FE) that says how a picture was enhanced, written right after the start
 * marker so the gallery's details can read it from the file's first bytes and say so honestly.
 */
object JpegCom {
    const val TAG = "P3 Companion: "

    /** [jpeg] with a COM segment holding [text] right after SOI. Not a JPEG: unchanged. */
    fun insert(jpeg: ByteArray, text: String): ByteArray {
        if (jpeg.size < 2 || (jpeg[0].toInt() and 0xFF) != 0xFF || (jpeg[1].toInt() and 0xFF) != 0xD8) return jpeg
        val body = (TAG + text).toByteArray(Charsets.UTF_8).let { if (it.size > 60_000) it.copyOf(60_000) else it }
        val len = body.size + 2
        val out = ByteArray(jpeg.size + 4 + body.size)
        out[0] = 0xFF.toByte(); out[1] = 0xD8.toByte()
        out[2] = 0xFF.toByte(); out[3] = 0xFE.toByte()
        out[4] = (len shr 8).toByte(); out[5] = (len and 0xFF).toByte()
        System.arraycopy(body, 0, out, 6, body.size)
        System.arraycopy(jpeg, 2, out, 6 + body.size, jpeg.size - 2)
        return out
    }

    /** The app's comment text (without [TAG]) from the start of a JPEG, or null. Stops at the first frame header. */
    fun read(b: ByteArray, len: Int = b.size): String? {
        if (len < 4 || (b[0].toInt() and 0xFF) != 0xFF || (b[1].toInt() and 0xFF) != 0xD8) return null
        var p = 2
        while (p + 4 <= len) {
            if ((b[p].toInt() and 0xFF) != 0xFF) return null
            val m = b[p + 1].toInt() and 0xFF
            if (m == 0xD8 || m == 0x01 || m in 0xD0..0xD7) { p += 2; continue }
            if (m == 0xDA || JpegInfo.isSof(m)) return null
            val segLen = ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
            if (segLen < 2) return null
            if (m == 0xFE && p + 2 + segLen <= len) {
                val text = String(b, p + 4, segLen - 2, Charsets.UTF_8)
                if (text.startsWith(TAG)) return text.removePrefix(TAG)
            }
            p += 2 + segLen
        }
        return null
    }
}
