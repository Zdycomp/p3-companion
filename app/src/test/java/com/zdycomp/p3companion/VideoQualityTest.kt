package com.zdycomp.p3companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** 3.6 "Try higher quality": SPS sizes for probe results, the meter, and which result is offered. */
class VideoQualityTest {

    /** Writes an H.264 SPS NAL (header included, emulation prevention applied) for a progressive picture. */
    private fun sps(widthMbs: Int, heightMbs: Int, profile: Int = 66, cropBottom: Int = 0, cropRight: Int = 0): ByteArray {
        val bits = StringBuilder()
        fun u(n: Int, v: Int) { for (i in n - 1 downTo 0) bits.append(if ((v shr i) and 1 == 1) '1' else '0') }
        fun ue(v: Int) { val x = v + 1; val len = 32 - Integer.numberOfLeadingZeros(x); repeat(len - 1) { bits.append('0') }; u(len, x) }
        u(8, profile); u(8, 0xC0); u(8, 40)
        ue(0) // seq_parameter_set_id
        if (profile == 100) { ue(1); ue(0); ue(0); u(1, 0); u(1, 0) } // 4:2:0, 8-bit, no scaling lists
        ue(0) // log2_max_frame_num_minus4
        ue(2) // pic_order_cnt_type 2
        ue(1) // max_num_ref_frames
        u(1, 0) // gaps
        ue(widthMbs - 1)
        ue(heightMbs - 1)
        u(1, 1) // frame_mbs_only
        u(1, 1) // direct_8x8_inference
        if (cropBottom > 0 || cropRight > 0) { u(1, 1); ue(0); ue(cropRight); ue(0); ue(cropBottom) } else u(1, 0)
        u(1, 0) // no VUI
        bits.append('1') // rbsp stop bit
        while (bits.length % 8 != 0) bits.append('0')
        val raw = ByteArray(bits.length / 8) { i -> bits.substring(i * 8, i * 8 + 8).toInt(2).toByte() }
        val out = ByteArrayOutputStream()
        out.write(0x67)
        var zeros = 0
        for (b in raw) {
            val v = b.toInt() and 0xFF
            if (zeros >= 2 && v <= 3) { out.write(3); zeros = 0 }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    @Test fun spsSizesForTheProbe() {
        assertEquals(320 to 240, H264.spsSize(sps(20, 15)))
        assertEquals(640 to 480, H264.spsSize(sps(40, 30)))
        assertEquals(1280 to 720, H264.spsSize(sps(80, 45)))
        // 1080p is 68 macroblock rows cropped by 8 lines (crop units of 2 for 4:2:0).
        assertEquals(1920 to 1080, H264.spsSize(sps(120, 68, cropBottom = 4)))
        assertEquals(1920 to 1080, H264.spsSize(sps(120, 68, profile = 100, cropBottom = 4)))
        assertNull(H264.spsSize(byteArrayOf(0x68, 0x00)))
    }

    @Test fun theOwnersSpsStillReads320x240() {
        assertEquals(320 to 240, H264.spsSize(byteArrayOf(0x67, 0x42, 0xC0.toByte(), 0x28, 0xB9.toByte(), 0xD0.toByte(), 0x28, 0x3F, 0x20)))
    }

    @Test fun meterCountsPicturesBytesAndTheLastSize() {
        val m = VideoQuality.Meter()
        m.feed(sps(40, 30), picture = true, length = 4_000)
        repeat(44) { m.feed(null, picture = true, length = 1_000) }
        m.feed(null, picture = false, length = 100)
        val r = m.result(byteArrayOf(1, 2), code = null, answered = true, listenedMs = 3_000)
        assertEquals(640, r.width)
        assertEquals(480, r.height)
        assertEquals(45, r.pictures)
        assertEquals(15.0, r.fps, 0.001)
        assertEquals((4_000 + 44_000 + 100) * 8.0 / 3_000, r.kbps, 0.001)
        assertTrue(r.accepted)
    }

    private fun res(data: ByteArray, w: Int?, h: Int?, pictures: Long, code: Int? = null, answered: Boolean = true) =
        VideoQuality.Result(data, code, answered, w, h, pictures, pictures * 2_000, 3_000)

    @Test fun bestIsTheBiggestPictureAt15FpsOrMore() {
        val std = res(VideoQuality.STANDARD, 320, 240, 90)
        val results = listOf(
            res(byteArrayOf(1, 1), 320, 240, 90),
            res(byteArrayOf(1, 2), 640, 480, 60), // 20 fps
            res(byteArrayOf(1, 3), 1280, 720, 30), // 10 fps: too slow
            res(byteArrayOf(2), null, null, 0, code = 3), // refused
            res(byteArrayOf(3), 640, 480, 75), // 25 fps: same size, faster
        )
        val b = VideoQuality.best(std, results)!!
        assertArrayEquals(byteArrayOf(3), b.data)
        assertTrue(VideoQuality.summary(std, results).startsWith("Higher quality found: [03] gives 640×480 at 25 fps"))
    }

    @Test fun nothingBetterSaysSoPlainly() {
        val std = res(VideoQuality.STANDARD, 320, 240, 90)
        val same = VideoQuality.CANDIDATES.map { res(it, 320, 240, 90) }
        assertNull(VideoQuality.best(std, same))
        assertEquals("The glasses send 320×240 only: 6 settings were tried and none gave a bigger picture.", VideoQuality.summary(std, same))
        val slowBig = listOf(res(byteArrayOf(2), 1280, 720, 20))
        assertNull(VideoQuality.best(std, slowBig))
        assertTrue(VideoQuality.summary(std, slowBig).contains("under 15 pictures a second"))
        // An unanswered or refused open is never chosen, nor is the standard data itself.
        assertNull(VideoQuality.best(std, listOf(res(byteArrayOf(2), 640, 480, 90, answered = false), res(VideoQuality.STANDARD, 640, 480, 90))))
    }

    @Test fun linesForTheLog() {
        assertEquals("5720/3 [01 02]: accepted, 640×480, 15.0 fps, 80 kbps, 45 pictures",
            VideoQuality.line(VideoQuality.Result(byteArrayOf(1, 2), 0, true, 640, 480, 45, 30_000, 3_000)))
        assertEquals("5720/3 [02]: refused (status 0x03)", VideoQuality.line(res(byteArrayOf(2), null, null, 0, code = 3)))
        assertEquals("5720/3 [03]: no answer", VideoQuality.line(res(byteArrayOf(3), null, null, 0, answered = false)))
        assertEquals("5720/3 [01 01]: accepted, no video in 3.0 s", VideoQuality.line(res(byteArrayOf(1, 1), null, null, 0)))
    }

    @Test fun hexAndSizeRoundTripForPrefs() {
        assertEquals("01 02", VideoQuality.hex(byteArrayOf(1, 2)))
        assertArrayEquals(byteArrayOf(1, 2), VideoQuality.parseHex("01 02"))
        assertArrayEquals(byteArrayOf(3), VideoQuality.parseHex(" 03 "))
        assertNull(VideoQuality.parseHex(""))
        assertNull(VideoQuality.parseHex("zz"))
        assertNull(VideoQuality.parseHex("1"))
        assertNull(VideoQuality.parseHex("01 02 03 04 05"))
        assertEquals("640x480", VideoQuality.formatSize(640 to 480))
        assertEquals(640 to 480, VideoQuality.parseSize("640x480"))
        assertNull(VideoQuality.parseSize("640 by 480"))
    }

    @Test fun theProbeNeverWritesTheLanguageNodesAndAlwaysClosesWithZero() {
        assertArrayEquals(byteArrayOf(0), VideoQuality.CLOSE)
        assertArrayEquals(byteArrayOf(1), VideoQuality.STANDARD)
        assertEquals(5, VideoQuality.CANDIDATES.size)
        assertTrue(VideoQuality.CANDIDATES.none { it.contentEquals(VideoQuality.STANDARD) || it.contentEquals(VideoQuality.CLOSE) })
    }

    @Test fun qualityPrefsFallBackToStandard() {
        val p = Prefs(FakeSharedPreferences())
        assertEquals(false, p.liveQualityHigh)
        p.liveQualityHigh = true
        assertEquals("High needs a found setting", false, p.liveQualityHigh)
        p.liveQualityData = byteArrayOf(1, 2)
        p.liveQualitySize = 640 to 480
        assertEquals(true, p.liveQualityHigh)
        assertEquals(640 to 480, p.liveQualitySize)
        p.liveQualityData = null
        assertEquals(false, p.liveQualityHigh)
        assertEquals(0, p.liveRotation)
        p.liveRotation = 450
        assertEquals(90, p.liveRotation)
        assertTrue(SettingsBackup.isSecret("live_quality_data"))
        assertTrue(SettingsBackup.isSecret("live_hq"))
    }
}
