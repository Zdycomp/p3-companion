package com.zdycomp.p3companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.8 "Find full-size photos": the safety list, the plan's index tries, and the plain result line. */
class FullResTest {

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun neverSendsTheDangerousNodes() {
        for (node in listOf("7400", "102A", "102C", "5610", "5620", "9000", "9001", "1017", "102E")) {
            for (action in 1..3) {
                assertFalse("$node/$action", FullRes.allowed(node, action))
                assertFalse("$node/$action [00]", FullRes.allowed(node, action, b(0)))
                assertFalse("$node/$action [01]", FullRes.allowed(node, action, b(1)))
            }
        }
    }

    @Test fun onlyThePlansRequests() {
        // Reads
        listOf("7110", "1001", "5712", "5713", "7200", "7310", "7600").forEach { assertTrue(it, FullRes.allowed(it, Xk.ACTION_READ)) }
        assertTrue(FullRes.allowed("7310", Xk.ACTION_READ, b(0)))
        assertFalse("reads take no data beyond [00]", FullRes.allowed("7310", Xk.ACTION_READ, b(1)))
        // Writes: 7300 [N+1] and the index pass
        assertTrue(FullRes.allowed("7300", Xk.ACTION_WRITE, b(7)))
        assertTrue(FullRes.allowed("7310", Xk.ACTION_WRITE, b(0, 1)))
        assertFalse(FullRes.allowed("7310", Xk.ACTION_WRITE))
        assertFalse(FullRes.allowed("7310", Xk.ACTION_WRITE, b(0, 0, 1)))
        assertFalse(FullRes.allowed("5720", Xk.ACTION_WRITE, b(1)))
        // Execute: only the existing photo and release commands, without data
        assertTrue(FullRes.allowed("57B0", Xk.ACTION_EXECUTE))
        assertTrue(FullRes.allowed("7500", Xk.ACTION_EXECUTE))
        assertFalse(FullRes.allowed("7500", Xk.ACTION_EXECUTE, b(1)))
        assertFalse(FullRes.allowed("7310", Xk.ACTION_EXECUTE))
        assertFalse(FullRes.allowed("5780", Xk.ACTION_EXECUTE))
        assertFalse(FullRes.allowed("7200", 4))
        assertFalse(FullRes.allowed("720", Xk.ACTION_READ))
    }

    @Test fun indexTriesFollowThePlan() {
        val ok = listOf(FullRes.ReadOutcome(false, null), FullRes.ReadOutcome(true, 3))
        val urn = listOf(FullRes.ReadOutcome(true, 4), FullRes.ReadOutcome(false, null))
        val t = FullRes.indexTries("7310", ok, photoNum = 0)
        assertEquals(3, t.size)
        assertArrayEquals(b(0), t[0]); assertArrayEquals(b(1), t[1]); assertArrayEquals(b(0, 1), t[2])
        val withN = FullRes.indexTries("7200", ok, photoNum = 5)
        assertEquals(4, withN.size)
        assertArrayEquals(b(5), withN[3])
        assertEquals("photo_num 1 is already [01]", 3, FullRes.indexTries("7200", ok, 1).size)
        assertTrue("INVALID_URN: no action 2", FullRes.indexTries("7310", urn, 5).isEmpty())
        // 7600: only [00], and only when it answered with something other than INVALID_URN
        assertEquals(1, FullRes.indexTries("7600", ok, 5).size)
        assertArrayEquals(b(0), FullRes.indexTries("7600", ok, 5)[0])
        assertTrue(FullRes.indexTries("7600", urn, 5).isEmpty())
        assertTrue(FullRes.indexTries("7600", listOf(FullRes.ReadOutcome(false, null)), 5).isEmpty())
        assertTrue(FullRes.indexTries("7400", ok, 5).isEmpty())
        // Every try passes the safety list
        for (node in FullRes.READ_PASS) FullRes.indexTries(node, ok, 9).forEach { assertTrue(FullRes.allowed(node, Xk.ACTION_WRITE, it)) }
    }

    @Test fun onePartPastTheLast() {
        assertArrayEquals(b(7), FullRes.extraPartIndex(6))
        assertArrayEquals(b(2), FullRes.extraPartIndex(1))
        assertNull("255 is the last one-byte index", FullRes.extraPartIndex(255))
        assertNull(FullRes.extraPartIndex(0))
    }

    @Test fun replyText() {
        assertEquals("silent", FullRes.replyText(null, null, ByteArray(0), null))
        assertEquals("type 4 (status), 1 B, status 03 INVALID_PARAM", FullRes.replyText(4, 3, b(3), null))
        assertEquals("type 2 (JSON), 20 B, JSON {\"photo_num\":\"0\"}", FullRes.replyText(2, null, ByteArray(20), "{\"photo_num\":\"0\"}"))
        assertEquals("type 0 (binary), 2 B: 10 37", FullRes.replyText(0, null, b(0x10, 0x37), null))
        assertTrue(FullRes.replyText(0, null, ByteArray(100), null).endsWith(" …"))
        assertEquals("type 3 (no data), 0 B", FullRes.replyText(3, null, ByteArray(0), null))
    }

    @Test fun nodeLines() {
        assertEquals("7310/2 01: status 03 INVALID_PARAM", FullRes.nodeLine("7310/2 01", 4, 3, null, 1, null, 0))
        assertEquals("7200/1: silent", FullRes.nodeLine("7200/1", null, null, null, 0, null, 0))
        assertEquals("7600/1 00: JSON {\"a\":1}", FullRes.nodeLine("7600/1 00", 2, null, "{\"a\":1}", 7, null, 0))
        val big = JpegInfo.Info(1280, 960, 3, 8, 0xC0, "4:2:0", 1, false, false, 1)
        assertEquals("7310/2 01: photo 1280x960, 212 KB", FullRes.nodeLine("7310/2 01", 0, null, null, 0, big, 212 * 1024L))
        assertEquals("no media", FullRes.Media(0, 0, 0, 0, null, 0).text())
        assertEquals("3 media frame(s), 2000 B, largest 990 B, 1 element(s)", FullRes.Media(3, 2000, 990, 1, null, 0).text())
    }

    @Test fun theOnePlainLine() {
        val vga = JpegInfo.Info(640, 480, 3, 8, 0xC0, "4:2:0", 1, false, false, 1)
        val big = JpegInfo.Info(1280, 960, 3, 8, 0xC0, "4:2:0", 1, false, false, 1)
        assertEquals("Bluetooth photos are 640x480; no larger copy found. The photo count didn't rise: the glasses keep no photos.",
            FullRes.summary(vga, 70_000, emptyList(), photoNumRose = false, complete = true))
        assertEquals("Bluetooth photos are 640x480; a larger copy came from 7310/2 01 (1280x960, 212 KB).",
            FullRes.summary(vga, 70_000, listOf(FullRes.Found("7310/2 01", big, 212 * 1024L)), null, true))
        assertEquals("Bluetooth photos are 640x480; other pictures came (7200/2 00 640x480) but none is larger. The probe stopped early.",
            FullRes.summary(vga, 70_000, listOf(FullRes.Found("7200/2 00", vga, 60_000)), null, false))
        assertTrue(FullRes.summary(null, 0, emptyList(), null, false).startsWith("No Bluetooth photo came in; no larger copy found"))
        assertTrue(FullRes.summary(vga, 1, emptyList(), true, true).contains("may keep photos"))
    }

    @Test fun countsFromJson() {
        assertEquals(3L, FullRes.jsonNumber("{\"photo_num\":\"3\",\"video_num\":\"0\"}", "photo_num"))
        assertEquals(1024L, FullRes.jsonNumber("{\"total_memory\":2048,\"remain_memory\": 1024}", "remain_memory"))
        assertNull(FullRes.jsonNumber("{\"video_num\":\"0\"}", "photo_num"))
        assertNull(FullRes.jsonNumber(null, "photo_num"))
        assertEquals(true, FullRes.rose(0, 1))
        assertEquals(false, FullRes.rose(0, 0))
        assertNull(FullRes.rose(null, 1))
    }

    @Test fun aRequestThatDroppedTheLinkIsSkippedNextTime() {
        assertEquals(setOf("7310/2 01"), FullRes.droppedAfterStart(emptySet(), "7310/2 01"))
        assertEquals(setOf("a"), FullRes.droppedAfterStart(setOf("a"), null))
        assertEquals("7310/2 00 01", FullRes.key("7310", 2, b(0, 1)))
        assertEquals("7200/1", FullRes.key("7200", 1, ByteArray(0)))
        val p = Prefs(FakeSharedPreferences())
        p.fullResCurrent = "7310/2 01"
        p.fullResDropped = FullRes.droppedAfterStart(p.fullResDropped, p.fullResCurrent)
        assertEquals(setOf("7310/2 01"), p.fullResDropped)
    }
}
