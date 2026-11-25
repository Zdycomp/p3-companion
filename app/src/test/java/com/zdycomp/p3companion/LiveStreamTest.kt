package com.zdycomp.p3companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3.4: the head-0x1E live video stream, checked against the owner's log of Nov 25 2026
 * (docs/LOG-2026-11-25-liveview.txt): exact prefixes, sizes and divides, completed into full frames.
 */
class LiveStreamTest {

    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val sps = hex("67 42 C0 28 B9 D0 28 3F 20")
    private val pps = hex("68 CE 38 80")
    private val idr = hex("65 88 84 21 A0")

    /**
     * One picture as the glasses sent it: [02][length LE4][Annex-B], cut in chunks of [chunks] bytes,
     * with the log's bytes at the start of each continuation chunk ([cont]).
     */
    private fun element(bodyLen: Int, chunks: List<Int>, cont: List<String>): ByteArray {
        val body = ByteArray(bodyLen) { 0x5A }
        val annexB = VideoAu.START + sps + VideoAu.START + pps + VideoAu.START + idr
        annexB.copyInto(body)
        val e = byteArrayOf(2, (bodyLen and 0xFF).toByte(), (bodyLen shr 8).toByte(), 0, 0) + body
        var at = 0
        chunks.forEachIndexed { i, n ->
            if (i > 0) hex(cont[i - 1]).copyInto(e, at)
            at += n
        }
        assertEquals(e.size, at)
        return e
    }

    private fun fragments(e: ByteArray, chunks: List<Int>, divides: List<Int>, order0: Int = 0x40): List<Xk.Frame> {
        var at = 0
        return chunks.mapIndexed { i, n ->
            val p = byteArrayOf(i.toByte(), 0, 0, 0) + e.copyOfRange(at, at + n)
            at += n
            Xk.Frame(Xk.HEAD_LIVE, order0 + i, 0x8001, divides[i], p)
        }
    }

    // Picture 1 of the log: 583 + 583 + 283 B payloads, divide 9D29 9D2A 9D2B, length 0x0598.
    private val chunks1 = listOf(579, 579, 279)
    private val pic1 = element(0x0598, chunks1, listOf(
        "BF FF FF D0 24 55 F5 6D 91 82 12 3C C8 4A 5A FF FE 9A 35 FF",
        "CF BE FE A7 AF FF FF 64 5F FF FE C8 6B 62 94 8F FF FF C8 6A",
    ))
    private val frames1 = fragments(pic1, chunks1, listOf(0x9D29, 0x9D2A, 0x9D2B))

    // Picture 2: 583 + 583 + 309 B, divide B729 B72A B72B, length 0x05B2.
    private val chunks2 = listOf(579, 579, 305)
    private val pic2 = element(0x05B2, chunks2, listOf("DF FF FF E4 04 7E F2 FF FF D8 23 75", "89 7B C9 75 5F FF FC 12 F7 E5 2E CD"))
    private val frames2 = fragments(pic2, chunks2, listOf(0xB729, 0xB72A, 0xB72B), 0x50)

    @Test fun framesMatchTheLogByteForByte() {
        assertEquals(listOf(583, 583, 283), frames1.map { it.payload.size })
        assertEquals(listOf(583, 583, 309), frames2.map { it.payload.size })
        assertArrayEquals(hex("00 00 00 00 02 98 05 00 00 00 00 00 01 67 42 C0 28 B9 D0 28 3F 20 00 00"), frames1[0].payload.copyOf(24))
        assertArrayEquals(hex("01 00 00 00 BF FF FF D0 24 55 F5 6D 91 82 12 3C C8 4A 5A FF FE 9A 35 FF"), frames1[1].payload.copyOf(24))
        assertArrayEquals(hex("02 00 00 00 CF BE FE A7 AF FF FF 64 5F FF FE C8 6B 62 94 8F FF FF C8 6A"), frames1[2].payload.copyOf(24))
        assertArrayEquals(hex("00 00 00 00 02 B2 05 00 00 00 00 00 01 67 42 C0 28 B9 D0 28 3F 20 00 00"), frames2[0].payload.copyOf(24))
        // The log's own arithmetic: the length closes the picture.
        assertEquals(1432, (583 + 583 + 283) - 3 * 4 - 5)
        assertEquals(1458, (583 + 583 + 309) - 3 * 4 - 5)
    }

    @Test fun divideIsThePhotoFragmentLayoutNotAPictureId() {
        // Low 3 bits: 1 first, 2 middle, 3 last; the rest is the element length with its header.
        assertEquals(listOf(1, 2, 3), frames1.map { it.fragment })
        assertEquals(listOf(1, 2, 3), frames2.map { it.fragment })
        assertEquals(1432 + 5, frames1[0].logicalLength)
        assertEquals(1458 + 5, frames2[0].logicalLength)
    }

    @Test fun strictParserNowTakesHead1E() {
        val raw = frames1.flatMap { it.encode().toList() }.toByteArray()
        val p = Xk.Parser()
        val out = mutableListOf<Xk.Frame>()
        for (b in raw) out += p.feed(byteArrayOf(b)) // one byte at a time, as a slow link would
        assertEquals(3, out.size)
        assertTrue(out.all { it.head == 0x1E && it.cmd == 0x8001 && it.fromDevice && !it.isAck })
        assertEquals(listOf(0x9D29, 0x9D2A, 0x9D2B), out.map { it.divide })
        assertEquals(0L, p.dropped)
    }

    @Test fun threeFragmentsMakeOnePicture() {
        val ls = LiveStream()
        val steps = frames1.map { ls.feed(it) }
        assertEquals(listOf(LiveStream.Step.STARTED, LiveStream.Step.MORE, LiveStream.Step.DONE), steps.map { it.step })
        assertEquals(listOf(false, false, true), steps.map { it.lastOfPicture })
        val e = steps[2].element!!
        assertEquals(Xk.MEDIA_H264, e.type)
        assertEquals(1432, e.body.size)
        assertArrayEquals(pic1.copyOfRange(5, pic1.size), e.body)
        assertArrayEquals(hex("00 00 00 01 67 42 C0 28"), e.body.copyOf(8))
        val au = VideoAu(e.body, 0)
        assertEquals(listOf(7, 8, 5), au.nalTypes)
        assertTrue(au.key && au.entry && au.picture)
        assertEquals(320 to 240, H264.spsSize(au.sps!!))
        assertEquals(1L, ls.videoUnits)

        val steps2 = frames2.map { ls.feed(it) }
        assertEquals(LiveStream.Step.DONE, steps2.last().step)
        assertEquals(1458, steps2.last().element!!.body.size)
        assertEquals(2L, ls.videoUnits)
        assertEquals(0L, ls.droppedPictures)
    }

    @Test fun spsFromTheLogIs320x240Baseline() {
        // 67 42 C0 28: Baseline, constraint flags C0, level 4.0. The SPS says 320×240, not 1001's 160×120.
        assertEquals(320 to 240, H264.spsSize(sps))
        assertNull(H264.spsSize(pps))
    }

    @Test fun aGapDropsThePictureAndTheNextOneStartsClean() {
        val ls = LiveStream()
        ls.feed(frames1[0])
        val gap = ls.feed(frames1[2])
        assertEquals(LiveStream.Step.DROPPED, gap.step)
        assertTrue(gap.dropped!!.contains("fragment 2, wanted 1"))
        assertTrue(gap.lastOfPicture)
        assertEquals(1L, ls.droppedPictures)
        // A continuation after the drop is an orphan, never a crash or a joined picture.
        assertEquals(LiveStream.Step.ORPHAN, ls.feed(frames1[1]).step)
        val done = frames2.map { ls.feed(it) }.last()
        assertEquals(1458, done.element!!.body.size)
        assertArrayEquals(pic2.copyOfRange(5, pic2.size), done.element!!.body)
    }

    @Test fun aNewFirstFragmentDropsTheUnfinishedPicture() {
        val ls = LiveStream()
        ls.feed(frames1[0])
        ls.feed(frames1[1])
        val restart = ls.feed(frames2[0])
        assertEquals(LiveStream.Step.STARTED, restart.step)
        assertNotNull(restart.dropped)
        assertEquals(1L, ls.droppedPictures)
        ls.feed(frames2[1])
        assertEquals(1458, ls.feed(frames2[2]).element!!.body.size)
    }

    @Test fun orphansBeforeAnyStartAreIgnored() {
        val ls = LiveStream()
        assertEquals(LiveStream.Step.ORPHAN, ls.feed(frames1[1]).step)
        assertEquals(LiveStream.Step.ORPHAN, ls.feed(frames1[2]).step)
        assertEquals(0L, ls.droppedPictures)
        assertEquals(2L, ls.orphanFragments)
        assertEquals(LiveStream.Step.DONE, frames1.map { ls.feed(it) }.last().step)
    }

    @Test fun aLastFragmentShortOfTheLengthIsDropped() {
        val ls = LiveStream()
        ls.feed(frames1[0])
        ls.feed(frames1[1])
        val short = frames1[2].let { Xk.Frame(it.head, it.order, it.cmd, it.divide, it.payload.copyOf(200)) }
        val r = ls.feed(short)
        assertEquals(LiveStream.Step.DROPPED, r.step)
        assertNull(r.element)
    }

    @Test fun otherElementTypesAreCounted() {
        val body = ByteArray(40) { 7 }
        val e = byteArrayOf(5, 40, 0, 0, 0) + body
        val f = Xk.Frame(Xk.HEAD_LIVE, 1, 0x8001, 0, byteArrayOf(0, 0, 0, 0) + e) // whole element in one frame
        val ls = LiveStream()
        val r = ls.feed(f)
        assertEquals(LiveStream.Step.DONE, r.step)
        assertEquals(5, r.element!!.type)
        assertArrayEquals(body, r.element!!.body)
        assertEquals(1L, ls.otherUnits[5])
        assertEquals(0L, ls.videoUnits)
    }

    @Test fun lengthCountingTheHeaderIsAcceptedOnTheLastFragment() {
        // Photo-style: the length includes the 5 header bytes.
        val body = VideoAu.START + sps + VideoAu.START + pps + VideoAu.START + idr + ByteArray(100) { 0x33 }
        val total = body.size + 5
        val e = byteArrayOf(2, (total and 0xFF).toByte(), (total shr 8).toByte(), 0, 0) + body
        val half = e.size / 2
        val ls = LiveStream()
        ls.feed(Xk.Frame(Xk.HEAD_LIVE, 1, 0x8001, 0x0001, byteArrayOf(0, 0, 0, 0) + e.copyOfRange(0, half)))
        val r = ls.feed(Xk.Frame(Xk.HEAD_LIVE, 2, 0x8001, 0x0003, byteArrayOf(1, 0, 0, 0) + e.copyOfRange(half, e.size)))
        assertEquals(LiveStream.Step.DONE, r.step)
        assertArrayEquals(body, r.element!!.body)
    }

    // ---------- acks ----------

    @Test fun perFragmentAckEchoesOrderAndDivide() {
        val f = frames1[1]
        val a = LiveAck.framesFor(LiveAck.Strategy.PER_FRAGMENT, f, false) { error("no order needed") }.single()
        assertEquals(0x1E, a.head)
        assertEquals(f.order, a.order)
        assertEquals(0x0009, a.cmd)
        assertEquals(0x9D2A, a.divide)
        assertArrayEquals(byteArrayOf(1), a.payload)
        assertTrue(a.isAck)
        assertArrayEquals(hex("1E 41 09 00 2A 9D 01 00 00 00 00 00") + Xk.crc16(byteArrayOf(1)).let { byteArrayOf((it and 0xFF).toByte(), (it shr 8).toByte(), 0, 0) } + byteArrayOf(1), a.encode())
    }

    @Test fun perPictureAckOnlyAfterTheLastFragment() {
        var n = 0x70
        val order = { n++ }
        assertTrue(LiveAck.framesFor(LiveAck.Strategy.PER_PICTURE, frames1[0], false, order).isEmpty())
        assertTrue(LiveAck.framesFor(LiveAck.Strategy.PER_PICTURE, frames1[1], false, order).isEmpty())
        val a = LiveAck.framesFor(LiveAck.Strategy.PER_PICTURE, frames1[2], true, order).single()
        assertEquals(0x1E, a.head); assertEquals(0x70, a.order); assertEquals(0x0009, a.cmd); assertEquals(0, a.divide)
        assertArrayEquals(byteArrayOf(1), a.payload)
    }

    @Test fun pushAckAndNone() {
        val a = LiveAck.framesFor(LiveAck.Strategy.PUSH_ACK, frames1[0], false) { 0x22 }.single()
        assertEquals(0x1E, a.head); assertEquals(0x22, a.order); assertEquals(0x0004, a.cmd)
        assertArrayEquals(byteArrayOf(1, frames1[0].order.toByte()), a.payload)
        assertTrue(LiveAck.framesFor(LiveAck.Strategy.NONE, frames1[2], true) { 0 }.isEmpty())
    }

    @Test fun liveAcksNeverTouchPhotoOrControlFrames() {
        val media = Xk.Frame(Xk.HEAD_MEDIA, 9, 0x8001, 0x0003, ByteArray(8))
        val control = Xk.Frame(Xk.HEAD_CONTROL, 9, 0x8001, 0, ByteArray(20))
        for (s in LiveAck.Strategy.values()) {
            assertTrue(LiveAck.framesFor(s, media, true) { 0 }.isEmpty())
            assertTrue(LiveAck.framesFor(s, control, true) { 0 }.isEmpty())
        }
        // The photo path is byte-identical to 3.3: 4A · 0009 · [01] on a last fragment, and nothing for 1E.
        assertArrayEquals(Xk.mediaAck(5).encode(), Xk.ackFor(media, 5)!!.encode())
        assertNull(Xk.ackFor(frames1[2], 5))
        assertEquals(LiveAck.Strategy.PER_PICTURE, LiveAck.parse("PER_PICTURE"))
        assertNull(LiveAck.parse("junk"))
    }

    // ---------- picking a strategy ----------

    private fun play(p: LiveAckPicker, from: Long, ms: Long, fps: Int): LiveAckPicker.Decision? {
        p.onFragment(from)
        var t = from
        val step = if (fps > 0) 1000L / fps else ms
        var last: LiveAckPicker.Decision? = null
        while (t < from + ms) {
            if (fps > 0) p.onPicture(t)
            t += step
            p.tick(t)?.let { last = it }
        }
        return p.tick(from + ms) ?: last
    }

    @Test fun pickerStartsPerFragmentAndMovesOnWhenSlow() {
        val p = LiveAckPicker(null)
        assertEquals(LiveAck.Strategy.PER_FRAGMENT, p.current)
        assertFalse(p.locked)
        val d = play(p, 0, 5_000, 1)!!
        assertEquals(LiveAckPicker.Kind.SWITCHED, d.kind)
        assertEquals(LiveAck.Strategy.PER_FRAGMENT, d.tried)
        assertTrue(d.fps < 2)
        assertEquals(LiveAck.Strategy.PER_PICTURE, p.current)
        val d2 = play(p, 5_000, 5_000, 10)!!
        assertEquals(LiveAckPicker.Kind.CHOSEN, d2.kind)
        assertEquals(LiveAck.Strategy.PER_PICTURE, d2.now)
        assertTrue(p.locked)
        assertTrue(p.report().startsWith("media ack per fragment 1.0 fps, media ack per picture"))
        assertEquals(LiveAckPicker.Kind.MEASURED, play(p, 10_000, 5_000, 1)!!.kind) // a slow spell later doesn't flip it
    }

    @Test fun pickerKeepsTheBestWhenAllAreSlow() {
        val p = LiveAckPicker(null)
        val rates = mapOf(LiveAck.Strategy.PER_FRAGMENT to 0, LiveAck.Strategy.PER_PICTURE to 1, LiveAck.Strategy.PUSH_ACK to 0, LiveAck.Strategy.NONE to 0)
        var t = 0L
        var d: LiveAckPicker.Decision? = null
        repeat(4) {
            d = play(p, t, 5_000, rates.getValue(p.current))
            t += 5_000
        }
        assertEquals(LiveAckPicker.Kind.CHOSEN, d!!.kind)
        assertEquals(LiveAck.Strategy.PER_PICTURE, p.current)
        assertTrue(p.locked)
    }

    @Test fun aSavedStrategyIsUsedAndSearchedAgainOnceIfSlow() {
        val p = LiveAckPicker(LiveAck.Strategy.PUSH_ACK)
        assertTrue(p.locked)
        assertEquals(LiveAck.Strategy.PUSH_ACK, p.current)
        assertEquals(LiveAckPicker.Kind.MEASURED, play(p, 0, 5_000, 8)!!.kind)
        val slow = play(p, 5_000, 5_000, 1)!!
        assertEquals(LiveAckPicker.Kind.SWITCHED, slow.kind)
        assertEquals(LiveAck.Strategy.PER_FRAGMENT, p.current)
    }

    @Test fun noWindowWithoutVideo() {
        val p = LiveAckPicker(null)
        assertNull(p.tick(60_000))
        p.onFragment(1_000)
        p.restart()
        assertNull(p.tick(60_000))
    }

    // ---------- H.264 entry points ----------

    @Test fun sliceTypesAndEntryPoints() {
        assertEquals(7, H264.sliceType(hex("65 88 84")))           // IDR, I slice
        assertEquals(5, H264.sliceType(hex("41 9A 00")))           // P slice
        assertEquals(7, H264.sliceType(hex("41 88 84")))           // non-IDR I slice
        assertNull(H264.sliceType(sps))
        val intra = VideoAu(VideoAu.START + sps + VideoAu.START + pps + VideoAu.START + hex("41 88 84 21"), 0)
        assertFalse(intra.key)
        assertTrue(intra.intra && intra.entry)
        val p = VideoAu(VideoAu.START + hex("41 9A 00 21"), 0)
        assertFalse(p.entry || p.intra)
        val iWithoutSps = VideoAu(VideoAu.START + hex("41 88 84 21"), 0)
        assertTrue(iWithoutSps.intra)
        assertFalse(iWithoutSps.entry)
        // The muxer sample keeps only the picture, with 4-byte start codes.
        assertArrayEquals(VideoAu.START + hex("41 88 84 21"), intra.sample())
    }
}
