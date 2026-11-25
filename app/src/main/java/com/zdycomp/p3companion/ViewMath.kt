package com.zdycomp.p3companion

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 3.6: the live view's picture math, pure Kotlin so it is unit tested: fit or fill, rotation by
 * quarter turns, pinch zoom with pan kept inside the picture, the frame height for the video's exact
 * shape, and a cheap unsharp mask for snapshots (never the live path).
 */
object ViewMath {

    const val MIN_ZOOM = 1f
    const val MAX_ZOOM = 5f

    /** Rotation in quarter turns only: 0, 90, 180 or 270. */
    fun normRotation(deg: Int): Int = (((deg / 90) % 4 + 4) % 4) * 90

    fun nextRotation(deg: Int): Int = normRotation(normRotation(deg) + 90)

    /** The video's size as it is shown (width and height swap at 90 and 270). */
    fun shownSize(videoW: Int, videoH: Int, rotation: Int): Pair<Int, Int> =
        if (normRotation(rotation) % 180 == 0) videoW to videoH else videoH to videoW

    /**
     * How the TextureView's content is drawn: [scaleX] and [scaleY] stretch the texture (which the view
     * first spreads over its whole area) about the view's centre, then it is rotated by [rotation] about
     * the centre, zoomed, and moved by [dx], [dy]. [shownW] × [shownH] is the picture's size on screen.
     */
    data class Transform(
        val scaleX: Float, val scaleY: Float, val rotation: Int, val zoom: Float,
        val dx: Float, val dy: Float, val shownW: Float, val shownH: Float,
    )

    /**
     * Letterboxed ([fill] false) or cropped to fill the view ([fill] true), at [zoom] (clamped to 1 to 5)
     * and moved by [panX], [panY] (clamped so the picture's edge never comes inside the view).
     */
    fun transform(
        viewW: Float, viewH: Float, videoW: Int, videoH: Int,
        rotation: Int = 0, fill: Boolean = false, zoom: Float = 1f, panX: Float = 0f, panY: Float = 0f,
    ): Transform {
        val rot = normRotation(rotation)
        if (viewW <= 0 || viewH <= 0 || videoW <= 0 || videoH <= 0) return Transform(1f, 1f, rot, 1f, 0f, 0f, viewW, viewH)
        val (sw, sh) = shownSize(videoW, videoH, rot)
        val k = if (fill) max(viewW / sw, viewH / sh) else min(viewW / sw, viewH / sh)
        // Before the rotation the texture is videoW × videoH scaled by k, spread over the view.
        val sx = videoW * k / viewW
        val sy = videoH * k / viewH
        val z = clampZoom(zoom)
        val shownW = sw * k * z
        val shownH = sh * k * z
        val (px, py) = clampPan(panX, panY, shownW, shownH, viewW, viewH)
        return Transform(sx, sy, rot, z, px, py, shownW, shownH)
    }

    fun clampZoom(z: Float): Float = if (z.isNaN()) 1f else z.coerceIn(MIN_ZOOM, MAX_ZOOM)

    /** A pan that keeps the zoomed picture covering as much of the view as it can. */
    fun clampPan(panX: Float, panY: Float, shownW: Float, shownH: Float, viewW: Float, viewH: Float): Pair<Float, Float> {
        val mx = max(0f, (shownW - viewW) / 2f)
        val my = max(0f, (shownH - viewH) / 2f)
        return panX.coerceIn(-mx, mx) to panY.coerceIn(-my, my)
    }

    /**
     * The video frame's height for [width] so the picture fits exactly (no bars), at most [maxHeight].
     */
    fun frameHeight(width: Int, videoW: Int, videoH: Int, rotation: Int, maxHeight: Int): Int {
        if (width <= 0 || videoW <= 0 || videoH <= 0) return maxHeight
        val (sw, sh) = shownSize(videoW, videoH, rotation)
        return min(maxHeight, (width.toLong() * sh / sw).toInt())
    }

    /**
     * Zoom about a focus point: the new pan keeps the content under ([fx], [fy]), measured from the
     * view's centre, where it was.
     */
    fun zoomAbout(oldZoom: Float, newZoom: Float, panX: Float, panY: Float, fx: Float, fy: Float): Pair<Float, Float> {
        val z0 = clampZoom(oldZoom)
        val z1 = clampZoom(newZoom)
        if (abs(z0) < 1e-6f) return panX to panY
        val r = z1 / z0
        return (fx - (fx - panX) * r) to (fy - (fy - panY) * r)
    }

    // ---------------- snapshot sharpening ----------------

    /**
     * Unsharp mask on ARGB pixels: each channel + [amount] × (channel − 3×3 box blur), alpha kept.
     * Edges get crisper, flat areas stay the same. Returns a new array.
     */
    fun unsharp(px: IntArray, w: Int, h: Int, amount: Float = 0.6f): IntArray {
        require(px.size >= w * h && w > 0 && h > 0)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0; var g = 0; var b = 0; var n = 0
                for (yy in max(0, y - 1)..min(h - 1, y + 1)) for (xx in max(0, x - 1)..min(w - 1, x + 1)) {
                    val c = px[yy * w + xx]
                    r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
                }
                val c = px[y * w + x]
                fun ch(v: Int, blur: Int): Int = (v + amount * (v - blur.toFloat() / n)).toInt().coerceIn(0, 255)
                val nr = ch((c shr 16) and 0xFF, r)
                val ng = ch((c shr 8) and 0xFF, g)
                val nb = ch(c and 0xFF, b)
                out[y * w + x] = (c and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        return out
    }
}

/** 3.6: the AAC-LC AudioSpecificConfig (csd-0) for the phone-mic track of a live view recording. */
object AacCsd {
    private val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    /** Two bytes: object type 2 (AAC LC), the sample-rate index and the channel count, then three zero bits. */
    fun of(sampleRate: Int, channels: Int): ByteArray {
        val idx = RATES.indexOf(sampleRate)
        require(idx >= 0) { "unsupported rate $sampleRate" }
        require(channels in 1..7) { "unsupported channels $channels" }
        val bits = (2 shl 11) or (idx shl 7) or (channels shl 3)
        return byteArrayOf((bits shr 8).toByte(), bits.toByte())
    }
}
