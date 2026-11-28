package com.zdycomp.p3companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OggOpusTest {
    /** A bit-by-bit CRC straight from the definition, to check the table version against. */
    private fun crcBitwise(b: ByteArray): Int {
        var c = 0
        for (x in b) {
            c = c xor ((x.toInt() and 0xFF) shl 24)
            repeat(8) { c = if (c and 0x80000000.toInt() != 0) (c shl 1) xor 0x04C11DB7 else c shl 1 }
        }
        return c
    }

    private fun packet(i: Int) = ByteArray(40) { k -> if (k == 0) 0x4B.toByte() else ((i * 7 + k) and 0xFF).toByte() }

    @Test fun crcMatchesTheDefinitionAndAKnownValue() {
        // "123456789" with polynomial 0x04C11DB7, init 0, not reflected, no final XOR (checked separately).
        assertEquals(0x89A1897F.toInt(), OggOpus.crc("123456789".toByteArray()))
        val rnd = java.util.Random(5)
        repeat(20) { val b = ByteArray(rnd.nextInt(300)).also { rnd.nextBytes(it) }; assertEquals(crcBitwise(b), OggOpus.crc(b)) }
    }

    @Test fun opusHeadIsTheNineteenByteHeader() {
        val h = OpusCsd.head(1, 16_000, 312)
        val want = byteArrayOf(0x4F, 0x70, 0x75, 0x73, 0x48, 0x65, 0x61, 0x64, 0x01, 0x01, 0x38, 0x01, 0x80.toByte(), 0x3E, 0x00, 0x00, 0x00, 0x00, 0x00)
        assertArrayEquals(want, h)
        val tags = OggOpus.tags("P3 Companion")
        assertEquals("OpusTags", String(tags, 0, 8, Charsets.US_ASCII))
        assertEquals(12, tags[8].toInt())
        assertEquals(8 + 4 + 12 + 4, tags.size)
    }

    @Test fun pagesGranulesAndFlags() {
        val packets = List(120) { packet(it) }
        val file = OggOpus.build(packets, serial = 0x1234)
        val pages = OggOpus.parse(file)
        // Head, tags, then 50 + 50 + 20 packets.
        assertEquals(5, pages.size)
        assertTrue(pages.all { it.crcOk })
        assertEquals(0x02, pages[0].flags)
        assertEquals(0, pages[1].flags)
        assertEquals(0x04, pages.last().flags)
        assertEquals(listOf(0, 1, 2, 3, 4), pages.map { it.sequence })
        assertTrue(pages.all { it.serial == 0x1234 })
        assertEquals(listOf(0L, 0L, 50L * 960, 100L * 960, 120L * 960), pages.map { it.granule })
        assertEquals(listOf(1, 1, 50, 50, 20), pages.map { it.packets.size })
        assertArrayEquals(OpusCsd.head(1, 16_000, OpusCsd.PRE_SKIP), pages[0].packets[0])
        // Every packet comes back byte for byte.
        val back = pages.drop(2).flatMap { it.packets }
        assertEquals(120, back.size)
        back.forEachIndexed { i, p -> assertArrayEquals(packets[i], p) }
        // A page is 27 + one lacing value per packet + the packets: 2077 bytes per second of glasses audio.
        val audio = file.size - OggOpus.page(listOf(OpusCsd.head()), 2, 0, 0x1234, 0).size - OggOpus.page(listOf(OggOpus.tags()), 0, 0, 0x1234, 1).size
        assertEquals(2 * (27 + 50 + 2000) + (27 + 20 + 800), audio)
    }

    @Test fun aCorruptPageFailsItsCrc() {
        val file = OggOpus.build(List(10) { packet(it) })
        file[file.size - 5] = (file[file.size - 5].toInt() xor 0x55).toByte()
        assertFalse(OggOpus.parse(file).last().crcOk)
    }

    @Test fun lacingOfLongPackets() {
        assertArrayEquals(intArrayOf(40), OggOpus.lacing(40))
        assertArrayEquals(intArrayOf(255, 0), OggOpus.lacing(255))
        assertArrayEquals(intArrayOf(255, 255, 10), OggOpus.lacing(520))
        val big = ByteArray(600) { it.toByte() }
        val pages = OggOpus.parse(OggOpus.build(listOf(big)))
        assertArrayEquals(big, pages.last().packets.single())
    }

    @Test fun emptyCaptureStillMakesAValidFile() {
        val pages = OggOpus.parse(OggOpus.build(emptyList()))
        assertEquals(3, pages.size)
        assertEquals(0x04, pages.last().flags)
        assertTrue(pages.all { it.crcOk })
    }

    @Test fun trimKeepsThePreRollAndTail() {
        val packets = List(250) { packet(it) } // 5 s
        // Speech 1.0-3.0 s: from 0.7 s to 3.4 s = packets 35 until 170.
        val t = OggOpus.trim(packets, 1000.0, 3000.0)
        assertEquals(135, t.size)
        assertArrayEquals(packets[35], t.first())
        assertEquals(packets, OggOpus.trim(packets, -1.0, -1.0))
    }

    @Test fun silkSizeHintsSilence() {
        // `4B 41 1E` + 7 bytes + 30 zero pad: the glasses' digital silence (HARDWARE-MAP §4.2).
        val silence = ByteArray(40).also { it[0] = 0x4B; it[1] = 0x41; it[2] = 0x1E; for (i in 3 until 10) it[i] = 0x11 }
        assertEquals(7, OpusToc.frameBytes(silence))
        assertTrue(OpusToc.quiet(silence))
        assertEquals(9, OpusToc.config(silence))
        val loud = ByteArray(40).also { it[0] = 0x48 }
        assertEquals(39, OpusToc.frameBytes(loud))
        assertFalse(OpusToc.quiet(loud))
        val cbr = ByteArray(40).also { it[0] = 0x4B; it[1] = 0x01 }
        assertEquals(38, OpusToc.frameBytes(cbr))
        assertArrayEquals(intArrayOf(1, 0, 0, 2), OpusToc.histogram(listOf(silence, loud, cbr)))
    }
}
