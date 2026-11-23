package com.zdycomp.p3companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3.3: why every request answered with status 00 timed out on the owner's E13C-5, at byte level.
 * Successful execute answers come back device-numbered (payload action 0x8004, the glasses' own
 * counter where our request id would be); error and read answers echo our id (action 0x8064).
 */
class ReplyMatchTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** A device control frame exactly as the glasses build it: [rid LE2][FF×4][action LE2][00 01][node][type][len LE2][data]. */
    private fun deviceFrame(order: Int, node: String, rid: Int, action: Int, type: Int, data: ByteArray): ByteArray {
        val p = ByteArray(17 + data.size)
        p[0] = rid.toByte(); p[1] = (rid shr 8).toByte()
        for (i in 2..5) p[i] = 0xFF.toByte()
        p[6] = action.toByte(); p[7] = (action shr 8).toByte()
        p[8] = 0; p[9] = 1
        node.toByteArray(Charsets.US_ASCII).copyInto(p, 10)
        p[14] = type.toByte(); p[15] = data.size.toByte(); p[16] = 0
        data.copyInto(p, 17)
        return Xk.Frame(Xk.HEAD_CONTROL, order, 0x8001, 0, p).encode()
    }

    private fun parse(bytes: ByteArray): Xk.Frame = Xk.Parser().feed(bytes).single()

    // CAPTURED payload (xk-one-pro-sdk test_codec): the answer to `57A0` executed with request id 0xB3.
    private val captured57A0Answer = "30530180000012000000000027B000001E00FFFFFFFF048000013537413000010000"

    @Test fun capturedSuccessAnswerIsDeviceNumbered() {
        val f = parse(hex(captured57A0Answer))
        assertEquals("57A0", f.node)
        assertEquals(0x001E, f.requestId) // not our 0xB3
        assertEquals(Xk.ACTION_DEVICE, f.action)
        assertEquals(Xk.FMT_BIN, f.replyType)
        assertArrayEquals(byteArrayOf(0), f.data)
        // 3.1 only accepted another id with action 0x8002: this answer was taken for a push, so the request timed out.
        assertTrue(f.action != 0x8002)
        assertEquals(Xk.ReplyMatch.BY_NODE, Xk.matchReply(0xB3, f, null))
    }

    @Test fun statusZeroAnswersCompleteTheirRequests() {
        // The log's "← 57B0 00", "← 7500 00", "← 5720 00" (action 3) and "← 102E 02 00".
        for ((node, data) in listOf("57B0" to byteArrayOf(0), "7500" to byteArrayOf(0), "5720" to byteArrayOf(0), "102E" to byteArrayOf(2, 0))) {
            val f = parse(deviceFrame(0x21, node, 0x0031, Xk.ACTION_DEVICE, Xk.FMT_BIN, data))
            assertEquals(node, f.node)
            assertEquals(Xk.ReplyMatch.BY_NODE, Xk.matchReply(0x0045, f, null))
            assertNull(f.errCode)
        }
    }

    @Test fun bindAnswerTwoZeroMeansBound() {
        val f = parse(deviceFrame(0x22, "102E", 0x0007, Xk.ACTION_DEVICE, Xk.FMT_BIN, byteArrayOf(2, 0)))
        assertEquals(Xk.Bind.BOUND, Xk.bindOutcome(f))
    }

    @Test fun errorAnswersEchoTheIdAndMatchExactly() {
        // "← 5720 03" for action 2, "← 102C 05": type 04 with our id.
        val f = parse(deviceFrame(0x23, "5720", 0x0047, Xk.ACTION_REPLY, Xk.FMT_ERRCODE, byteArrayOf(3)))
        assertEquals(3, f.errCode)
        assertEquals(Xk.ReplyMatch.EXACT, Xk.matchReply(0x0047, f, null))
    }

    @Test fun deviceCounterCollidingWithATimedOutIdIsStillAnAnswer() {
        // The log's "Ignored a late 7500 reply": the glasses' counter happened to equal an id that had timed out.
        val f = parse(deviceFrame(0x24, "7500", 0x0052, Xk.ACTION_DEVICE, Xk.FMT_BIN, byteArrayOf(0)))
        assertEquals(Xk.ReplyMatch.BY_NODE, Xk.matchReply(0x0060, f, staleNode = "7500"))
    }

    @Test fun lateEchoForTheSameNodeIsDropped() {
        val f = parse(deviceFrame(0x25, "1003", 0x0052, Xk.ACTION_REPLY, Xk.FMT_BIN, byteArrayOf(0, 77)))
        assertEquals(Xk.ReplyMatch.LATE, Xk.matchReply(0x0060, f, staleNode = "1003"))
        // A stale id from another node says nothing about this one.
        assertEquals(Xk.ReplyMatch.BY_NODE, Xk.matchReply(0x0060, f, staleNode = "57B0"))
    }

    @Test fun nothingPendingMeansPush() {
        val f = parse(deviceFrame(0x26, "5710", 0x0009, Xk.ACTION_DEVICE, Xk.FMT_BIN, byteArrayOf(1)))
        assertEquals(Xk.ReplyMatch.NONE, Xk.matchReply(null, f, null))
    }

    @Test fun answerBehindTheGlassesOwnAckIsFound() {
        // The glasses send their own link ack (cmd 8004) before the answer; the parser yields both, the ack is never matched.
        val ack = Xk.Frame(Xk.HEAD_CONTROL, 0x30, 0x8004, 0, byteArrayOf(1, 0x16)).encode()
        val frames = Xk.Parser().feed(ack + deviceFrame(0x31, "57B0", 0x0033, Xk.ACTION_DEVICE, Xk.FMT_BIN, byteArrayOf(0)))
        assertEquals(2, frames.size)
        assertTrue(frames[0].isAck)
        assertEquals("57B0", frames[1].node)
        assertEquals(Xk.ReplyMatch.BY_NODE, Xk.matchReply(0x0050, frames[1], null))
    }

    // ---------------- push acks ----------------

    @Test fun everyPreviewPushIsAckedWithItsOwnOrder() {
        for ((i, node) in listOf("5710", "5711", "57A0", "5750", "7320", "C107").withIndex()) {
            val push = parse(deviceFrame(0x40 + i, node, 0x0010 + i, Xk.ACTION_DEVICE, Xk.FMT_BIN, byteArrayOf(1)))
            val ack = Xk.ackFor(push, 0x77)!!
            assertArrayEquals(Xk.ack(0x77, 0x40 + i).encode(), ack.encode())
            assertEquals(0x0004, ack.cmd)
            assertArrayEquals(byteArrayOf(1, (0x40 + i).toByte()), ack.payload)
        }
    }

    @Test fun acksAreNotAckedAndOurFramesNeitherButLastMediaFragmentsAre() {
        assertNull(Xk.ackFor(Xk.Frame(Xk.HEAD_CONTROL, 5, 0x8004, 0, byteArrayOf(1, 2)), 1))
        assertNull(Xk.ackFor(Xk.control(5, "57B0", Xk.ACTION_EXECUTE, 0x41), 1))
        assertArrayEquals(Xk.mediaAck(9).encode(), Xk.ackFor(Xk.Frame(Xk.HEAD_MEDIA, 5, 0x8001, 3, ByteArray(8)), 9)!!.encode())
        assertNull(Xk.ackFor(Xk.Frame(Xk.HEAD_MEDIA, 5, 0x8001, 1, ByteArray(8)), 9))
        assertNull(Xk.ackFor(Xk.Frame(Xk.HEAD_CUSTOM, 5, 0x8004, 0, ByteArray(7)), 9))
    }

    // ---------------- receive sniffer ----------------

    @Test fun skippedRunsAreReportedWithTheirLength() {
        val runs = mutableListOf<Pair<ByteArray, Int>>()
        val p = Xk.Parser().apply { onSkip = { b, n -> runs += b to n } }
        val noise = ByteArray(37) { (0x50 + it).toByte() }
        val frames = p.feed(noise + deviceFrame(0x21, "5710", 1, Xk.ACTION_DEVICE, Xk.FMT_BIN, byteArrayOf(1)))
        assertEquals(1, frames.size)
        assertEquals(1, runs.size)
        assertEquals(37, runs[0].second)
        assertArrayEquals(noise, runs[0].first)
        assertTrue(Xk.describeRaw(runs[0].first, runs[0].second).startsWith("37 B: head 50 cmd 5352 divide 5554 len 22358: 50 51"))
    }

    @Test fun unknownHeadIsOnlyAcceptedWhileSniffing() {
        val payload = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1E)
        val raw = Xk.Frame(0x55, 7, 0x8001, 5, payload).encode()
        val strict = Xk.Parser()
        assertTrue(strict.feed(raw).isEmpty())
        val lenient = Xk.Parser().apply { this.lenient = true }
        val f = lenient.feed(raw).single()
        assertEquals(0x55, f.head)
        assertArrayEquals(payload, f.payload)
        // A known head with an unknown cmd (0x8003) too.
        val odd = Xk.Frame(Xk.HEAD_MEDIA, 8, 0x8003, 0, payload).encode()
        assertTrue(Xk.Parser().feed(odd).isEmpty())
        assertEquals(0x8003, Xk.Parser().apply { this.lenient = true }.feed(odd).single().cmd)
    }

    @Test fun lenientParserStillRejectsBadChecksums() {
        val raw = Xk.Frame(0x55, 7, 0x8001, 0, ByteArray(20) { it.toByte() }).encode()
        raw[20] = 0x7F
        assertTrue(Xk.Parser().apply { lenient = true }.feed(raw).isEmpty())
    }

    @Test fun jpegAndH264AreFoundBehindUnknownHeaders() {
        val jpeg = byteArrayOf(0, 9, 0, 0, 0, 0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 0xFF.toByte(), 0xD9.toByte())
        assertEquals(5, Xk.jpegStart(jpeg))
        assertEquals(-1, Xk.jpegStart(ByteArray(40) { 1 }))
        val sps = hex("6742C00BD90141FB0110000003001000000300A0F142A48000")
        val element = byteArrayOf(7, 1, 2, 3, 4, 5, 6) + VideoAu.START + sps + VideoAu.START + byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte(), 0x00)
        val au = VideoAu.scan(element, 0)!!
        assertTrue(au.key)
        assertEquals(320 to 240, H264.spsSize(au.sps!!))
        assertNull(VideoAu.scan(ByteArray(100) { 3 }, 0))
    }
}
