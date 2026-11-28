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

    // ---------------- 4.1.1: the detail (bitrate) ladder ----------------

    private fun hexes(l: List<ByteArray>) = l.map { VideoQuality.hex(it) }

    @Test fun ladderStartsAtMostDetailAndStepsDownToTheStandardOpen() {
        assertEquals(listOf("01 03", "01 02", "01 01", "01"), hexes(VideoQuality.LADDER))
        assertArrayEquals(VideoQuality.STANDARD, VideoQuality.LADDER.last())
        assertTrue(VideoQuality.LADDER.none { it.contentEquals(VideoQuality.CLOSE) })
        assertEquals(3_000L, VideoQuality.NO_VIDEO_MS)
        val l = VideoQuality.OpenLadder(high = null, remembered = null)
        assertEquals(listOf("01 03", "01 02", "01 01", "01"), hexes(l.order))
        assertArrayEquals(byteArrayOf(1, 3), l.current)
    }

    @Test fun refusedAndNoVideoFallBackInOrderAndTheWorkingOneIsRemembered() {
        val l = VideoQuality.OpenLadder(null, null)
        assertArrayEquals(byteArrayOf(1, 2), l.next(VideoQuality.Outcome.REFUSED))
        assertArrayEquals(byteArrayOf(1, 1), l.next(VideoQuality.Outcome.NO_VIDEO))
        assertArrayEquals(byteArrayOf(1), l.next(VideoQuality.Outcome.REFUSED))
        assertTrue(l.isLast)
        assertNull(l.next(VideoQuality.Outcome.VIDEO))
        assertArrayEquals(byteArrayOf(1), l.remember)
        // The last setting with no video: nothing more to try, nothing remembered.
        val m = VideoQuality.OpenLadder(null, byteArrayOf(1))
        assertEquals(listOf("01"), hexes(m.order))
        assertNull(m.next(VideoQuality.Outcome.NO_VIDEO))
        assertNull(m.remember)
    }

    @Test fun theOwnersGlassesOpenWithMostDetailAtOnce() {
        val l = VideoQuality.OpenLadder(null, null)
        assertNull(l.next(VideoQuality.Outcome.VIDEO))
        assertArrayEquals(byteArrayOf(1, 3), l.remember)
        assertEquals("most detail", VideoQuality.detailName(byteArrayOf(1, 3)))
        assertEquals("basic", VideoQuality.detailName(byteArrayOf(1)))
        assertNull(VideoQuality.detailName(byteArrayOf(2)))
    }

    @Test fun theRememberedSettingGoesFirstThenTheOnesBelowIt() {
        assertEquals(listOf("01 02", "01 01", "01"), hexes(VideoQuality.OpenLadder(null, byteArrayOf(1, 2)).order))
        assertEquals(listOf("01 01", "01"), hexes(VideoQuality.OpenLadder(null, byteArrayOf(1, 1)).order))
        // Data that isn't on the ladder is ignored: the whole ladder from the top.
        assertEquals(listOf("01 03", "01 02", "01 01", "01"), hexes(VideoQuality.OpenLadder(null, byteArrayOf(2)).order))
    }

    @Test fun highGoesFirstAndIsNeverRememberedAsTheDetailSetting() {
        val l = VideoQuality.OpenLadder(high = byteArrayOf(3), remembered = byteArrayOf(1, 2))
        assertEquals(listOf("03", "01 02", "01 01", "01"), hexes(l.order))
        assertNull(l.next(VideoQuality.Outcome.VIDEO))
        assertNull("High's data is kept as High, not as the detail setting", l.remember)
        // High data that is itself on the ladder isn't tried twice.
        assertEquals(listOf("01 03", "01 02", "01 01", "01"), hexes(VideoQuality.OpenLadder(byteArrayOf(1, 3), null).order))
        val h = VideoQuality.OpenLadder(byteArrayOf(3), null)
        assertArrayEquals(byteArrayOf(1, 3), h.next(VideoQuality.Outcome.REFUSED))
    }

    @Test fun busyOrLeavingNeverStepsDownOrRemembers() {
        for (o in listOf(VideoQuality.Outcome.BUSY, VideoQuality.Outcome.STOPPED)) {
            val l = VideoQuality.OpenLadder(null, null)
            assertNull(l.next(o))
            assertEquals(0, l.index)
            assertNull(l.remember)
        }
    }

    @Test fun theProbeFeedsTheRememberedSetting() {
        // The owner's 4.1.0 quality test.
        fun r(d: ByteArray, fps: Double, kbps: Double, code: Int? = null) =
            VideoQuality.Result(d, code, true, if (fps > 0) 320 else null, if (fps > 0) 240 else null,
                (fps * 3).toLong(), (kbps * 3_000 / 8).toLong(), 3_000)
        val std = r(byteArrayOf(1), 10.7, 162.0)
        val results = listOf(r(byteArrayOf(1, 1), 14.0, 180.0), r(byteArrayOf(1, 2), 15.7, 199.0), r(byteArrayOf(1, 3), 15.3, 203.0),
            r(byteArrayOf(2), 0.0, 0.0), r(byteArrayOf(3), 0.0, 0.0))
        assertArrayEquals(byteArrayOf(1, 3), VideoQuality.detailFromProbe(std, results))
        assertEquals("Live view opens with [01 03] (most detail, 203 kbps).", VideoQuality.detailSentence(std, results))
        assertNull("no bigger picture, so High stays off", VideoQuality.best(std, results))
        // [01 03] refused and [01 02] silent: the most detail that sent pictures is [01 01].
        val worse = listOf(r(byteArrayOf(1, 1), 14.0, 180.0), r(byteArrayOf(1, 2), 0.0, 0.0), r(byteArrayOf(1, 3), 0.0, 0.0, code = 3))
        assertArrayEquals(byteArrayOf(1, 1), VideoQuality.detailFromProbe(std, worse))
        // Nothing sent pictures: the remembered setting is left alone.
        assertNull(VideoQuality.detailFromProbe(r(byteArrayOf(1), 0.0, 0.0), listOf(r(byteArrayOf(1, 3), 0.0, 0.0))))
        assertEquals("No setting sent pictures, so the live view keeps its setting.",
            VideoQuality.detailSentence(null, listOf(r(byteArrayOf(1, 3), 0.0, 0.0))))
    }

    @Test fun theDetailSettingIsSavedPerDeviceAndNotBackedUp() {
        val p = Prefs(FakeSharedPreferences())
        assertNull(p.liveDetailData)
        p.liveDetailData = byteArrayOf(1, 2)
        assertArrayEquals(byteArrayOf(1, 2), p.liveDetailData)
        assertEquals(listOf("01 02", "01 01", "01"), hexes(VideoQuality.OpenLadder(null, p.liveDetailData).order))
        p.liveDetailData = null
        assertNull(p.liveDetailData)
        assertTrue(SettingsBackup.isSecret("live_quality_open"))
    }
}
