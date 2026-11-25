package com.zdycomp.p3companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.6 live view picture math: aspect ratio, fit and fill, rotation, zoom and pan, sharpening, AAC config. */
class ViewMathTest {

    private val eps = 0.01f

    @Test fun fitLetterboxesAndFillCrops() {
        // 320×240 in a 1080×1080 view: fit is 1080×810 (bars top and bottom), fill is 1440×1080.
        val fit = ViewMath.transform(1080f, 1080f, 320, 240)
        assertEquals(1080f, fit.shownW, eps)
        assertEquals(810f, fit.shownH, eps)
        assertEquals(1f, fit.scaleX, eps)
        assertEquals(0.75f, fit.scaleY, eps)
        val fill = ViewMath.transform(1080f, 1080f, 320, 240, fill = true)
        assertEquals(1440f, fill.shownW, eps)
        assertEquals(1080f, fill.shownH, eps)
        // A frame with the video's exact shape has no bars either way.
        val exact = ViewMath.transform(1000f, 750f, 320, 240)
        assertEquals(1f, exact.scaleX, eps)
        assertEquals(1f, exact.scaleY, eps)
    }

    @Test fun quarterTurnsSwapTheShownSize() {
        val t = ViewMath.transform(1080f, 810f, 320, 240, rotation = 90)
        // Turned, the picture is 240 wide and 320 tall: it fits the 810 height.
        assertEquals(607.5f, t.shownW, eps)
        assertEquals(810f, t.shownH, eps)
        assertEquals(90, t.rotation)
        // Before the turn the texture is 320k × 240k spread over the view.
        assertEquals(320 * 2.53125f / 1080f, t.scaleX, eps)
        assertEquals(240 * 2.53125f / 810f, t.scaleY, eps)
        assertEquals(180, ViewMath.transform(100f, 100f, 320, 240, rotation = -180).rotation)
        assertEquals(listOf(90, 180, 270, 0), generateSequence(0) { ViewMath.nextRotation(it) }.drop(1).take(4).toList())
        assertEquals(270, ViewMath.normRotation(-90))
        assertEquals(320 to 240, ViewMath.shownSize(320, 240, 180))
        assertEquals(240 to 320, ViewMath.shownSize(320, 240, 270))
    }

    @Test fun zoomIsClampedAndPanStaysInsideThePicture() {
        val t = ViewMath.transform(1000f, 750f, 320, 240, zoom = 2f, panX = 10_000f, panY = -10_000f)
        assertEquals(2f, t.zoom, eps)
        assertEquals(2000f, t.shownW, eps)
        assertEquals(500f, t.dx, eps) // (2000 - 1000) / 2
        assertEquals(-375f, t.dy, eps) // (1500 - 750) / 2
        assertEquals(ViewMath.MAX_ZOOM, ViewMath.transform(1000f, 750f, 320, 240, zoom = 50f).zoom, eps)
        val unzoomed = ViewMath.transform(1000f, 750f, 320, 240, zoom = 0.2f, panX = 80f, panY = 80f)
        assertEquals(1f, unzoomed.zoom, eps)
        assertEquals(0f, unzoomed.dx, eps)
        assertEquals(0f, unzoomed.dy, eps)
        assertEquals(1f, ViewMath.clampZoom(Float.NaN), eps)
    }

    @Test fun zoomAboutKeepsTheFocusPointStill() {
        // Content under the focus point (relative to the centre) stays there: fx = pan + z × c.
        val fx = 200f; val fy = -100f
        val (px, py) = ViewMath.zoomAbout(1f, 2f, 0f, 0f, fx, fy)
        val c = floatArrayOf(fx / 1f, fy / 1f)
        assertEquals(fx, px + 2f * c[0], eps)
        assertEquals(fy, py + 2f * c[1], eps)
        val (qx, qy) = ViewMath.zoomAbout(2f, 3f, px, py, 50f, 60f)
        val c2x = (50f - px) / 2f; val c2y = (60f - py) / 2f
        assertEquals(50f, qx + 3f * c2x, eps)
        assertEquals(60f, qy + 3f * c2y, eps)
    }

    @Test fun frameHeightFollowsTheVideoShape() {
        assertEquals(750, ViewMath.frameHeight(1000, 320, 240, 0, 2000))
        assertEquals(1333, ViewMath.frameHeight(1000, 320, 240, 90, 2000))
        assertEquals(900, ViewMath.frameHeight(1000, 320, 240, 270, 900))
        assertEquals(562, ViewMath.frameHeight(1000, 1280, 720, 0, 2000))
        assertEquals(400, ViewMath.frameHeight(0, 320, 240, 0, 400))
    }

    @Test fun unsharpLeavesFlatAreasAndSharpensEdges() {
        val w = 6; val h = 3
        val flat = IntArray(w * h) { 0xFF808080.toInt() }
        assertArrayEquals(flat, ViewMath.unsharp(flat, w, h))
        // Left half dark, right half light: the edge gets more contrast, alpha is kept.
        val edge = IntArray(w * h) { i -> if (i % w < 3) 0xFF404040.toInt() else 0x80C0C0C0.toInt() }
        val out = ViewMath.unsharp(edge, w, h, amount = 1f)
        fun g(c: Int) = (c shr 8) and 0xFF
        assertTrue(g(out[1 * w + 2]) < 0x40) // dark side of the edge got darker
        assertTrue(g(out[1 * w + 3]) > 0xC0) // light side got lighter
        assertEquals(0x40, g(out[1 * w + 0])) // away from the edge: unchanged
        assertEquals(0x80, (out[1 * w + 5] ushr 24) and 0xFF)
        assertEquals(0xFF, (out[1 * w + 0] ushr 24) and 0xFF)
    }

    @Test fun aacConfigForThePhoneMic() {
        assertArrayEquals(byteArrayOf(0x12, 0x08), AacCsd.of(44_100, 1))
        assertArrayEquals(byteArrayOf(0x11, 0x90.toByte()), AacCsd.of(48_000, 2))
        assertArrayEquals(byteArrayOf(0x14, 0x08), AacCsd.of(16_000, 1))
        assertTrue(runCatching { AacCsd.of(44_000, 1) }.isFailure)
    }
}
