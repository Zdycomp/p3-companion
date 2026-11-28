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

    // ---------------- 4.1.1: the detail (bitrate) ladder; 4.1.2: Smooth first ----------------

    private fun hexes(l: List<ByteArray>) = l.map { VideoQuality.hex(it) }

    @Test fun ladderStartsSmoothAndFallsBackToTheDetailSettings() {
        assertEquals(listOf("01", "01 03", "01 02", "01 01"), hexes(VideoQuality.LADDER))
        assertArrayEquals(VideoQuality.STANDARD, VideoQuality.LADDER.first())
        assertEquals(listOf("01 03", "01 02", "01 01", "01"), hexes(VideoQuality.DETAIL_LADDER))
        assertTrue(VideoQuality.LADDER.none { it.contentEquals(VideoQuality.CLOSE) })
        assertTrue(VideoQuality.DETAIL_LADDER.none { it.contentEquals(VideoQuality.CLOSE) })
        assertEquals(3_000L, VideoQuality.NO_VIDEO_MS)
        val l = VideoQuality.OpenLadder(high = null, remembered = null)
        assertEquals(listOf("01", "01 03", "01 02", "01 01"), hexes(l.order))
        assertArrayEquals(byteArrayOf(1), l.current)
        val d = VideoQuality.OpenLadder(high = null, remembered = null, detail = true)
        assertEquals(listOf("01 03", "01 02", "01 01", "01"), hexes(d.order))
        assertArrayEquals(byteArrayOf(1, 3), d.current)
    }

    @Test fun refusedAndNoVideoFallBackInOrderAndTheWorkingOneIsRemembered() {
        val l = VideoQuality.OpenLadder(null, null)
        assertArrayEquals(byteArrayOf(1, 3), l.next(VideoQuality.Outcome.REFUSED))
        assertArrayEquals(byteArrayOf(1, 2), l.next(VideoQuality.Outcome.NO_VIDEO))
        assertArrayEquals(byteArrayOf(1, 1), l.next(VideoQuality.Outcome.REFUSED))
        assertTrue(l.isLast)
        assertNull(l.next(VideoQuality.Outcome.VIDEO))
        assertArrayEquals(byteArrayOf(1, 1), l.remember)
        // Detail steps down the same way, to the standard open last.
        val d = VideoQuality.OpenLadder(null, null, detail = true)
        assertArrayEquals(byteArrayOf(1, 2), d.next(VideoQuality.Outcome.REFUSED))
        assertArrayEquals(byteArrayOf(1, 1), d.next(VideoQuality.Outcome.NO_VIDEO))
        assertArrayEquals(byteArrayOf(1), d.next(VideoQuality.Outcome.REFUSED))
        assertNull(d.next(VideoQuality.Outcome.VIDEO))
        assertArrayEquals(byteArrayOf(1), d.remember)
        // The last setting with no video: nothing more to try, nothing remembered.
        val m = VideoQuality.OpenLadder(null, byteArrayOf(1, 1))
        assertEquals(listOf("01 01"), hexes(m.order))
        assertNull(m.next(VideoQuality.Outcome.NO_VIDEO))
        assertNull(m.remember)
    }

    @Test fun theOwnersGlassesOpenSmoothAtOnce() {
        val l = VideoQuality.OpenLadder(null, null)
        assertNull(l.next(VideoQuality.Outcome.VIDEO))
        assertArrayEquals(byteArrayOf(1), l.remember)
        assertEquals("smooth", VideoQuality.detailName(byteArrayOf(1)))
        assertEquals("most detail", VideoQuality.detailName(byteArrayOf(1, 3)))
        assertEquals("high detail", VideoQuality.detailName(byteArrayOf(1, 2)))
        assertEquals("medium detail", VideoQuality.detailName(byteArrayOf(1, 1)))
        assertNull(VideoQuality.detailName(byteArrayOf(2)))
    }

    @Test fun theRememberedSettingGoesFirstThenTheOnesBelowIt() {
        assertEquals(listOf("01 02", "01 01"), hexes(VideoQuality.OpenLadder(null, byteArrayOf(1, 2)).order))
        assertEquals(listOf("01 03", "01 02", "01 01"), hexes(VideoQuality.OpenLadder(null, byteArrayOf(1, 3)).order))
        assertEquals(listOf("01 02", "01 01", "01"), hexes(VideoQuality.OpenLadder(null, byteArrayOf(1, 2), detail = true).order))
        assertEquals(listOf("01"), hexes(VideoQuality.OpenLadder(null, byteArrayOf(1), detail = true).order))
        // Data that isn't on the ladder is ignored: the whole ladder from the top.
        assertEquals(listOf("01", "01 03", "01 02", "01 01"), hexes(VideoQuality.OpenLadder(null, byteArrayOf(2)).order))
        assertEquals(listOf("01 03", "01 02", "01 01", "01"), hexes(VideoQuality.OpenLadder(null, byteArrayOf(2), detail = true).order))
    }

    @Test fun highGoesFirstAndIsNeverRememberedAsTheDetailSetting() {
        val l = VideoQuality.OpenLadder(high = byteArrayOf(3), remembered = byteArrayOf(1, 2))
        assertEquals(listOf("03", "01 02", "01 01"), hexes(l.order))
        assertNull(l.next(VideoQuality.Outcome.VIDEO))
        assertNull("High's data is kept as High, not as the detail setting", l.remember)
        // High data that is itself on the ladder isn't tried twice.
        assertEquals(listOf("01 03", "01", "01 02", "01 01"), hexes(VideoQuality.OpenLadder(byteArrayOf(1, 3), null).order))
        assertEquals(listOf("01 03", "01 02", "01 01", "01"), hexes(VideoQuality.OpenLadder(byteArrayOf(1, 3), null, detail = true).order))
        val h = VideoQuality.OpenLadder(byteArrayOf(3), null)
        assertArrayEquals(byteArrayOf(1), h.next(VideoQuality.Outcome.REFUSED))
    }

    @Test fun busyOrLeavingNeverStepsDownOrRemembers() {
        for (detail in listOf(false, true)) for (o in listOf(VideoQuality.Outcome.BUSY, VideoQuality.Outcome.STOPPED)) {
            val l = VideoQuality.OpenLadder(null, null, detail)
            assertNull(l.next(o))
            assertEquals(0, l.index)
            assertNull(l.remember)
        }
    }

    private fun meas(d: ByteArray, fps: Double, kbps: Double, code: Int? = null) =
        VideoQuality.Result(d, code, true, if (fps > 0) 320 else null, if (fps > 0) 240 else null,
            Math.round(fps * 3), Math.round(kbps * 3_000 / 8), 3_000)

    @Test fun theProbeFeedsTheRememberedSetting() {
        // The owner's 4.1.0 quality test.
        val std = meas(byteArrayOf(1), 10.7, 162.0)
        val results = listOf(meas(byteArrayOf(1, 1), 14.0, 180.0), meas(byteArrayOf(1, 2), 15.7, 199.0), meas(byteArrayOf(1, 3), 15.3, 203.0),
            meas(byteArrayOf(2), 0.0, 0.0), meas(byteArrayOf(3), 0.0, 0.0))
        // 4.1.2: Smooth remembers [01]; Detail remembers [01 03].
        assertArrayEquals(byteArrayOf(1), VideoQuality.detailFromProbe(std, results))
        assertEquals("Live view opens with [01] (smooth, 162 kbps).", VideoQuality.detailSentence(std, results))
        assertArrayEquals(byteArrayOf(1, 3), VideoQuality.detailFromProbe(std, results, detail = true))
        assertEquals("Live view opens with [01 03] (most detail, 203 kbps).", VideoQuality.detailSentence(std, results, detail = true))
        assertNull("no bigger picture, so High stays off", VideoQuality.best(std, results))
        // [01] silent: Smooth falls back to [01 03]. [01 03] refused and [01 02] silent: Detail's next is [01 01].
        val noStd = meas(byteArrayOf(1), 0.0, 0.0)
        assertArrayEquals(byteArrayOf(1, 3), VideoQuality.detailFromProbe(noStd, results))
        val worse = listOf(meas(byteArrayOf(1, 1), 14.0, 180.0), meas(byteArrayOf(1, 2), 0.0, 0.0), meas(byteArrayOf(1, 3), 0.0, 0.0, code = 3))
        assertArrayEquals(byteArrayOf(1, 1), VideoQuality.detailFromProbe(noStd, worse, detail = true))
        // Nothing sent pictures: the remembered setting is left alone.
        assertNull(VideoQuality.detailFromProbe(meas(byteArrayOf(1), 0.0, 0.0), listOf(meas(byteArrayOf(1, 3), 0.0, 0.0))))
        assertEquals("No setting sent pictures, so the live view keeps its setting.",
            VideoQuality.detailSentence(null, listOf(meas(byteArrayOf(1, 3), 0.0, 0.0))))
    }

    @Test fun theOwners411TestRecommendsSmoothByFpsTimesBits() {
        // 4.1.1 on the E13C-5: [01] 30.0 fps 378 kbps, [01 01] 16.0/221, [01 02] 16.7/238, [01 03] 16.0/233.
        val std = meas(byteArrayOf(1), 30.0, 378.0)
        val results = listOf(meas(byteArrayOf(1, 1), 16.0, 221.0), meas(byteArrayOf(1, 2), 16.7, 238.0), meas(byteArrayOf(1, 3), 16.0, 233.0),
            meas(byteArrayOf(2), 0.0, 0.0), meas(byteArrayOf(3), 0.0, 0.0))
        assertEquals(12.6, VideoQuality.kbitPerFrame(std), 0.05)
        val rec = VideoQuality.recommendation(std, results)!!
        assertEquals("Smooth [01] 30.0 fps × 12.6 kbit, Detail [01 03] 16.0 fps × 14.6 kbit: Smooth gives the most frames; " +
            "Detail gives ~16% more per frame. Recommended by fps × bits: Smooth.", rec)
        assertEquals("$rec Live view opens with [01] (smooth, 378 kbps).", VideoQuality.detailSummary(std, results))
        // The 4.1.0 numbers ([01] at 10.7 fps) would have recommended Detail.
        val old = VideoQuality.recommendation(meas(byteArrayOf(1), 10.7, 162.0), listOf(meas(byteArrayOf(1, 3), 15.3, 203.0)))!!
        assertTrue(old, old.contains("Detail gave more frames this time"))
        assertTrue(old, old.endsWith("Recommended by fps × bits: Detail."))
        // Only one side sent pictures: no comparison, just the setting.
        assertNull(VideoQuality.recommendation(std, listOf(meas(byteArrayOf(1, 3), 0.0, 0.0))))
        assertEquals("Live view opens with [01] (smooth, 378 kbps).", VideoQuality.detailSummary(std, listOf(meas(byteArrayOf(1, 3), 0.0, 0.0))))
    }

    @Test fun sharpSnapshotPrefersSmooth() {
        assertNull(VideoQuality.sharpSnapshotHint(byteArrayOf(1)))
        assertNull(VideoQuality.sharpSnapshotHint(null))
        assertTrue(VideoQuality.sharpSnapshotHint(byteArrayOf(1, 3))!!.startsWith("Smooth"))
        assertTrue(VideoQuality.isDetailData(byteArrayOf(1, 1)))
        assertTrue(!VideoQuality.isDetailData(byteArrayOf(1)) && !VideoQuality.isDetailData(byteArrayOf(2)) && !VideoQuality.isDetailData(byteArrayOf(2, 3)))
    }

    @Test fun theDetailSettingIsSavedPerDeviceAndNotBackedUp() {
        val p = Prefs(FakeSharedPreferences())
        assertNull(p.liveDetailData)
        p.liveDetailData = byteArrayOf(1, 2)
        assertArrayEquals(byteArrayOf(1, 2), p.liveDetailData)
        assertEquals(listOf("01 02", "01 01"), hexes(VideoQuality.OpenLadder(null, p.liveDetailData).order))
        p.liveDetailData = null
        assertNull(p.liveDetailData)
        assertTrue(SettingsBackup.isSecret("live_quality_open"))
        assertTrue("the Smooth/Detail choice is the person's, so it's backed up", !SettingsBackup.isSecret("live_detail_first"))
    }

    // ---------------- 4.1.2: Smooth by default, Detail by choice, and the migration ----------------

    @Test fun smoothIsTheDefaultAndChoosingStartsAfresh() {
        val p = Prefs(FakeSharedPreferences())
        assertEquals(false, p.liveDetailFirst)
        p.liveDetailData = byteArrayOf(1)
        p.chooseLiveDetail(true)
        assertEquals(true, p.liveDetailFirst)
        assertNull("a new choice starts from its own top", p.liveDetailData)
        p.liveDetailData = byteArrayOf(1, 3)
        p.chooseLiveDetail(true)
        assertArrayEquals("the same choice again keeps what worked", byteArrayOf(1, 3), p.liveDetailData)
        p.chooseLiveDetail(false)
        assertEquals(false, p.liveDetailFirst)
        assertNull(p.liveDetailData)
    }

    @Test fun migrationResetsA411DetailSettingToSmooth() {
        for (old in listOf(byteArrayOf(1, 3), byteArrayOf(1, 2), byteArrayOf(1, 1))) {
            val p = Prefs(FakeSharedPreferences())
            p.liveDetailData = old
            assertTrue(p.migrateLiveDetail())
            assertArrayEquals(byteArrayOf(1), p.liveDetailData)
            assertEquals(listOf("01", "01 03", "01 02", "01 01"), hexes(VideoQuality.OpenLadder(null, p.liveDetailData, p.liveDetailFirst).order))
            // Once only: a setting remembered later (say [01] failed and [01 03] worked) stays.
            p.liveDetailData = byteArrayOf(1, 3)
            assertTrue(!p.migrateLiveDetail())
            assertArrayEquals(byteArrayOf(1, 3), p.liveDetailData)
        }
    }

    @Test fun migrationKeepsAnExplicitDetailChoiceAndEverythingElse() {
        val chose = Prefs(FakeSharedPreferences())
        chose.liveDetailFirst = true
        chose.liveDetailData = byteArrayOf(1, 3)
        assertTrue(!chose.migrateLiveDetail())
        assertArrayEquals(byteArrayOf(1, 3), chose.liveDetailData)
        val none = Prefs(FakeSharedPreferences())
        assertTrue(!none.migrateLiveDetail())
        assertNull(none.liveDetailData)
        val smooth = Prefs(FakeSharedPreferences())
        smooth.liveDetailData = byteArrayOf(1)
        assertTrue(!smooth.migrateLiveDetail())
        assertArrayEquals(byteArrayOf(1), smooth.liveDetailData)
        // The pure rule.
        assertArrayEquals(byteArrayOf(1), VideoQuality.migrateRemembered(byteArrayOf(1, 2), detailChosen = false))
        assertArrayEquals(byteArrayOf(1, 2), VideoQuality.migrateRemembered(byteArrayOf(1, 2), detailChosen = true))
        assertArrayEquals(byteArrayOf(3), VideoQuality.migrateRemembered(byteArrayOf(3), detailChosen = false))
        assertNull(VideoQuality.migrateRemembered(null, detailChosen = false))
    }

    // ---------------- 4.1.2: the 0x2B frame's bytes are always logged ----------------

    @Test fun customFramesLogTheirBytesOnEverySignIn() {
        val owner = byteArrayOf(0x01, 0x10, 0x03, 0xF0.toByte(), 0x60, 0x00, 0x00)
        assertEquals("← 2B frame (7 B): 01 10 03 F0 60 00 00", Xk.customFrameLine(owner, raw = false))
        assertEquals("← 2B frame (7 B): 01 10 03 F0 60 00 00", Xk.customFrameLine(owner, raw = true))
        assertEquals("← 2B frame (0 B)", Xk.customFrameLine(ByteArray(0), raw = false))
        val long = ByteArray(40) { it.toByte() }
        assertTrue(Xk.customFrameLine(long, raw = false).endsWith("1F (first 32 B)"))
        assertTrue(Xk.customFrameLine(long, raw = true).endsWith(" 27"))
    }
}
