package com.zdycomp.p3companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/** 4.0: the picture enhancement kernels on plain IntArray pixels (no java.awt or javax.imageio). */
class ImageEnhanceTest {

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun r(c: Int) = (c shr 16) and 0xFF
    private fun g(c: Int) = (c shr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF
    private fun flat(w: Int, h: Int, c: Int) = IntArray(w * h) { c }

    @Test fun upscalesToTwiceAndThreeTimesTheSize() {
        val px = IntArray(640 * 480) { rgb(it % 256, (it / 640) % 256, 128) }
        val two = ImageEnhance.enhance(px, 640, 480, scale = 2)
        assertEquals(1280, two.w); assertEquals(960, two.h); assertEquals(1280 * 960, two.px.size)
        val small = IntArray(64 * 48) { rgb(100, 150, 200) }
        val three = ImageEnhance.enhance(small, 64, 48, scale = 3)
        assertEquals(192, three.w); assertEquals(144, three.h)
        assertEquals(ImageEnhance.label(1280, 960, 640, 480), "Enhanced 1280×960 from 640×480")
    }

    @Test fun lanczosWeightsSumToOneAndTheWindowIsRight() {
        assertEquals(1.0, ImageEnhance.lanczos3(0.0), 1e-12)
        assertEquals(0.0, ImageEnhance.lanczos3(3.0), 1e-12)
        assertEquals(0.0, ImageEnhance.lanczos3(1.0), 1e-9)
        assertTrue(ImageEnhance.lanczos3(1.5) < 0) // the negative lobe that makes it sharp
        val t = ImageEnhance.taps(640, 1280)
        for (o in 0 until 1280) {
            var sum = 0.0
            for (k in 0 until t.n) sum += t.weights[o * t.n + k]
            assertEquals("output $o", 1.0, sum, 1e-4)
        }
    }

    @Test fun aFlatPictureStaysExactlyFlatThroughEveryStage() {
        val c = rgb(90, 140, 210)
        val px = flat(37, 23, c)
        ImageEnhance.upscale(px, 37, 23, 74, 46).forEach { assertEquals(c, it) }
        ImageEnhance.bilateral3(px, 37, 23).forEach { assertEquals(c, it) }
        ImageEnhance.median3(px, 37, 23).forEach { assertEquals(c, it) }
        ImageEnhance.unsharp(px, 37, 23).forEach { assertEquals(c, it) }
        ImageEnhance.sharpen3x3(px, 37, 23).forEach { assertEquals(c, it) }
        // Grey stays grey (tone moves contrast, and colour only where there is colour).
        val grey = rgb(128, 128, 128)
        assertEquals(grey, ImageEnhance.tone(intArrayOf(grey))[0])
    }

    @Test fun edgesRepeatOutwardsSoBordersDontDarken() {
        // Bright everywhere: the corners of the upscaled picture must be as bright as the middle.
        val px = flat(8, 6, rgb(250, 250, 250))
        val up = ImageEnhance.upscale(px, 8, 6, 24, 18)
        listOf(0, 23, 24 * 17, 24 * 18 - 1).forEach { assertEquals(rgb(250, 250, 250), up[it]) }
        // Tiny pictures work: 1×1 and 2×1.
        assertEquals(4, ImageEnhance.enhance(intArrayOf(rgb(1, 2, 3)), 1, 1).px.size)
        assertEquals(2 * 2 * 2, ImageEnhance.enhance(intArrayOf(rgb(0, 0, 0), rgb(255, 255, 255)), 2, 1).px.size)
    }

    @Test fun lanczosStaysInRangeOnAHardEdge() {
        val w = 20; val h = 4
        val px = IntArray(w * h) { if (it % w < 10) rgb(0, 0, 0) else rgb(255, 255, 255) }
        val up = ImageEnhance.upscale(px, w, h, 40, 8)
        up.forEach { assertTrue(r(it) in 0..255 && g(it) in 0..255) }
        // The step is still there, between the halves.
        assertTrue(r(up[2]) < 10)
        assertTrue(r(up[37]) > 245)
    }

    @Test fun sharpeningMakesAnEdgeCrisperWithoutHalos() {
        val w = 16; val h = 8
        // A soft edge from 60 to 190 around columns 5..7.
        val ramp = intArrayOf(60, 60, 60, 60, 60, 70, 125, 180, 190, 190, 190, 190, 190, 190, 190, 190)
        val px = IntArray(w * h) { val v = ramp[it % w]; rgb(v, v, v) }
        val out = ImageEnhance.unsharp(px, w, h, amount = 1f, threshold = 2f)
        val row = 3 * w
        val before = r(px[row + 7]) - r(px[row + 5])
        val after = r(out[row + 7]) - r(out[row + 5])
        assertTrue("edge $before → $after", after > before)
        // No halo: the flat parts far from the edge don't move, and nothing goes far past the local range.
        assertEquals(60, r(out[row + 1]))
        assertEquals(190, r(out[row + 14]))
        out.forEach { assertTrue(r(it) in 54..196) }
        // The 3×3 live kernel sharpens too, and leaves flat areas alone.
        val k = ImageEnhance.sharpen3x3(px, w, h, 0.5f)
        assertTrue(r(k[row + 7]) - r(k[row + 5]) > before)
        assertEquals(60, r(k[row + 2]))
    }

    @Test fun noiseInFlatAreasIsLeftAloneByTheSharpenerAndSmoothedByTheDenoise() {
        val rnd = Random(3)
        val w = 32; val h = 32
        val px = IntArray(w * h) { val v = 120 + rnd.nextInt(-1, 2); rgb(v, v, v) }
        // Under the threshold: unsharp doesn't amplify faint grain.
        val sharp = ImageEnhance.unsharp(px, w, h, amount = 1f, threshold = 3f)
        for (i in px.indices) assertEquals(r(px[i]), r(sharp[i]))
        // Bilateral: grain goes down, a strong edge stays.
        val noisy = IntArray(w * h) { val base = if (it % w < 16) 40 else 200; val v = (base + rnd.nextInt(-12, 13)).coerceIn(0, 255); rgb(v, v, v) }
        val clean = ImageEnhance.bilateral3(noisy, w, h, sigmaRange = 20f)
        fun spread(a: IntArray) = (4 until 12).sumOf { x -> (4 until 28).sumOf { y -> abs(r(a[y * w + x]) - 40) } }
        assertTrue(spread(clean) < spread(noisy))
        assertTrue(r(clean[10 * w + 20]) > 170)
        assertTrue(r(clean[10 * w + 10]) < 70)
    }

    @Test fun toneAddsALittleContrastAndColour() {
        val c = rgb(180, 120, 90)
        val t = ImageEnhance.tone(intArrayOf(c), contrast = 1.1f, saturation = 1.2f)[0]
        assertTrue(r(t) > r(c))
        assertTrue(b(t) < b(c))
        assertEquals(255, (t ushr 24) and 0xFF)
    }

    @Test fun strengthZeroOnlyResizes() {
        val px = IntArray(10 * 10) { rgb(it, 255 - it, 7) }
        val e = ImageEnhance.enhance(px, 10, 10, scale = 2, strength = 0f)
        val plain = ImageEnhance.upscale(px, 10, 10, 20, 20)
        assertTrue(e.px.contentEquals(plain))
    }

    // ---------------- the JPEG comment that says it was enhanced ----------------

    @Test fun jpegCommentIsWrittenAfterSoiAndReadBack() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDB.toByte(), 0x00, 0x04, 1, 2,
            0xFF.toByte(), 0xC0.toByte(), 0x00, 0x0B, 8, 0x03, 0xC0.toByte(), 0x05, 0x00, 0x03, 1, 0x11, 0)
        val text = "Enhanced 1280×960 from 640×480 on the phone"
        val withNote = JpegCom.insert(jpeg, text)
        assertEquals(0xFF, withNote[2].toInt() and 0xFF)
        assertEquals(0xFE, withNote[3].toInt() and 0xFF)
        assertEquals(text, JpegCom.read(withNote))
        assertNull(JpegCom.read(jpeg))
        // The rest of the file is unchanged after the comment, so the frame header is still found.
        assertEquals(jpeg.size + 4 + (JpegCom.TAG + text).toByteArray(Charsets.UTF_8).size, withNote.size)
        val info = JpegInfo.parse(withNote)
        assertEquals(1280, info?.width)
        assertEquals(960, info?.height)
        // Not a JPEG: untouched.
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        assertTrue(JpegCom.insert(png, text).contentEquals(png))
    }
}
