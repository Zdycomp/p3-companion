package com.zdycomp.p3companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XkProtocolTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // Real frames captured from the glasses family (public, MIT-licensed xk-one-pro-sdk).
    private val captured7100 = "306C01000000110000000000388900009500FFFFFFFF0100000137313030000000"
    private val captured1001 = "307501000000110000000000465B0000A300FFFFFFFF0100000131303031000000"
    private val captured57A0 = "308201000000120000000000FFD20000B300FFFFFFFF030000013537413000010000"

    @Test fun encodesQueriesExactlyLikeTheOfficialApp() {
        assertArrayEquals(hex(captured7100), Xk.control(0x6C, "7100", Xk.ACTION_READ, 0x95).encode())
        assertArrayEquals(hex(captured1001), Xk.control(0x75, "1001", Xk.ACTION_READ, 0xA3).encode())
        assertArrayEquals(hex(captured57A0), Xk.control(0x82, "57A0", Xk.ACTION_EXECUTE, 0xB3, byteArrayOf(0)).encode())
    }

    @Test fun ackMatchesCapture() {
        assertArrayEquals(hex("306D04000000020000000000F84E00000106"), Xk.ack(0x6D, 0x06).encode())
    }

    @Test fun parserFindsFramesAcrossChunksAndSkipsNoise() {
        val p = Xk.Parser()
        val bytes = byteArrayOf(0x00, 0x11) + hex(captured7100) + hex(captured1001)
        val first = p.feed(bytes.copyOfRange(0, 20))
        val rest = p.feed(bytes.copyOfRange(20, bytes.size))
        val frames = first + rest
        assertEquals(2, frames.size)
        assertEquals("7100", frames[0].node)
        assertEquals("1001", frames[1].node)
    }

    @Test fun badChecksumIsDropped() {
        val b = hex(captured7100)
        b[20] = 0x00
        assertTrue(Xk.Parser().feed(b).isEmpty())
    }

    @Test fun userBindHasTheCapturedLayout() {
        val id = "0123456789abcdef0123456789abcdef"
        val f = Xk.control(1, "102E", Xk.ACTION_EXECUTE, 0x98, Xk.userBindData(id))
        val p = f.payload
        // len = 0x0032, then format 00, bind type 02 (reconnect), 16 zero bytes, 0x20, the id
        assertEquals(0x00, p[14].toInt()); assertEquals(0x32, p[15].toInt())
        assertEquals(0x00, p[16].toInt()); assertEquals(0x02, p[17].toInt())
        assertTrue((18 until 34).all { p[it].toInt() == 0 })
        assertEquals(0x20, p[34].toInt())
        assertEquals(id, String(p, 35, 32, Charsets.US_ASCII))
    }

    @Test fun keyFinderReadsTheKeyOutOfARawLog() {
        val id = "fedcba9876543210fedcba9876543210"
        val frame = Xk.control(0x6E, "102E", Xk.ACTION_EXECUTE, 0x98, Xk.userBindData(id)).encode()
        val log = ByteArray(5000) { (it * 31).toByte() } + frame + ByteArray(300)
        val counts = HashMap<String, Int>()
        KeyFinder.scanStream(log.inputStream(), counts)
        assertEquals(id, counts.maxByOrNull { it.value }?.key)
    }

    @Test fun keyFinderIgnoresLookalikes() {
        val counts = HashMap<String, Int>()
        val junk = "102E".toByteArray() + ByteArray(80) { 0x41 }
        KeyFinder.scanStream(junk.inputStream(), counts)
        assertNull(counts.keys.firstOrNull())
    }

    @Test fun photoAssemblerStripsHeaders() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        val element = byteArrayOf(0, 12, 0, 0, 0) + jpeg
        fun part(div: Int, idx: Int, body: ByteArray) =
            Xk.Frame(Xk.HEAD_MEDIA, 0, 0x8001, div, byteArrayOf(idx.toByte(), 0, 0, 0) + body)
        val asm = Xk.PhotoAssembler(1)
        asm.start(1)
        assertEquals(Xk.PhotoAssembler.Result.MORE, asm.feed(part(1, 0, element.copyOfRange(0, 4))))
        assertEquals(Xk.PhotoAssembler.Result.MORE, asm.feed(part(2, 1, element.copyOfRange(4, 8))))
        assertEquals(Xk.PhotoAssembler.Result.DONE, asm.feed(part(3, 2, element.copyOfRange(8, element.size))))
        assertArrayEquals(jpeg, asm.jpeg())
    }

    @Test fun mediaKinds() {
        assertEquals(MediaKind.VIDEO, MediaImporter.kindOf("VID_0001.MP4", ""))
        assertEquals(MediaKind.PHOTO, MediaImporter.kindOf("a.jpg", "application/octet-stream"))
        assertEquals(MediaKind.AUDIO, MediaImporter.kindOf("x", "audio/wav"))
        assertNull(MediaImporter.kindOf("readme.txt", "text/plain"))
    }
}
