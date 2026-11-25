package com.zdycomp.p3companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * 3.8: the JPEG frame-header reader behind "Photo 640x480, 71 KB". The fixtures are built byte by byte
 * (SOI, APP0 JFIF, DQT, SOF0/SOF2, DHT, SOS, stuffed entropy bytes, EOI), so the tests need nothing
 * beyond the Android unit-test classpath.
 */
class JpegInfoTest {

    /** A structurally complete JPEG of [w] x [h]: baseline (SOF0) or progressive (SOF2), colour 4:2:0 or grey. */
    private fun jpeg(w: Int, h: Int, progressive: Boolean = false, grey: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        // APP0 JFIF 1.01, no thumbnail
        out.write(segment(0xE0, "JFIF".toByteArray() + byteArrayOf(0, 1, 1, 0, 0, 1, 0, 1, 0, 0)))
        // DQT: table 0, 64 entries
        out.write(segment(0xDB, byteArrayOf(0) + ByteArray(64) { 1 }))
        // SOF: precision 8, height, width, components (id, HxV, table)
        val comps = if (grey) listOf(0x11) else listOf(0x22, 0x11, 0x11)
        val sof = byteArrayOf(8, (h shr 8).toByte(), h.toByte(), (w shr 8).toByte(), w.toByte(), comps.size.toByte()) +
            comps.flatMapIndexed { k, hv -> listOf((k + 1).toByte(), hv.toByte(), (if (k == 0) 0 else 1).toByte()) }.toByteArray()
        out.write(segment(if (progressive) 0xC2 else 0xC0, sof))
        // DHT stub: one DC table with no codes
        out.write(segment(0xC4, ByteArray(17)))
        // SOS: components, then Ss/Se/AhAl
        val sos = byteArrayOf(comps.size.toByte()) + comps.indices.flatMap { listOf((it + 1).toByte(), 0) }.toByteArray() +
            byteArrayOf(0, 63, 0)
        out.write(segment(0xDA, sos))
        // Entropy-coded bytes, FF stuffed as FF 00, sized roughly like the picture.
        repeat(maxOf(64, w * h / 40)) { i -> out.write(i * 37 and 0xFF); if (i * 37 and 0xFF == 0xFF) out.write(0) }
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        return out.toByteArray()
    }

    /** [jpeg] with extra segments put right after SOI (before the JFIF block and the frame header). */
    private fun withSegments(j: ByteArray, vararg segs: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(j, 0, 2)
        segs.forEach { out.write(it) }
        out.write(j, 2, j.size - 2)
        return out.toByteArray()
    }

    private fun segment(marker: Int, body: ByteArray): ByteArray {
        val len = body.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (len shr 8).toByte(), len.toByte()) + body
    }

    /** An EXIF APP1 block holding a small JPEG thumbnail, like a camera writes. */
    private fun exifWithThumb(thumb: ByteArray): ByteArray {
        val tiff = byteArrayOf(0x49, 0x49, 0x2A, 0, 8, 0, 0, 0, 0, 0) // little-endian TIFF header, empty IFD
        return segment(0xE1, "Exif".toByteArray() + byteArrayOf(0, 0) + tiff + thumb)
    }

    @Test fun baseline16x8() {
        val j = jpeg(16, 8)
        val i = JpegInfo.parse(j)!!
        assertEquals(16, i.width)
        assertEquals(8, i.height)
        assertEquals(3, i.components)
        assertEquals(8, i.precision)
        assertEquals(0xC0, i.sof)
        assertEquals("baseline", i.kind)
        assertFalse(i.progressive)
        assertEquals(1, i.sofCount)
        assertFalse(i.hasExif)
        assertTrue("the JFIF block counts as an APP segment", i.appSegments >= 1)
        assertEquals("4:2:0", i.sampling)
        assertEquals("16x8", i.size)
        assertEquals(1, JpegInfo.soiCount(j))
    }

    @Test fun progressive640x480() {
        val j = jpeg(640, 480, progressive = true)
        val i = JpegInfo.parse(j)!!
        assertEquals(640, i.width)
        assertEquals(480, i.height)
        assertEquals(0xC2, i.sof)
        assertTrue(i.progressive)
        assertEquals("progressive", i.kind)
        assertEquals(0.3072, i.megapixels, 1e-9)
    }

    @Test fun greyPictureHasOneComponent() {
        val i = JpegInfo.parse(jpeg(24, 16, grey = true))!!
        assertEquals(1, i.components)
        assertEquals("grey", i.sampling)
    }

    @Test fun exifAndOtherAppSegmentsBeforeTheFrameHeader() {
        val thumb = jpeg(160, 120)
        val big = withSegments(jpeg(1280, 960),
            exifWithThumb(thumb),
            segment(0xE2, ByteArray(300) { 7 }),     // ICC-like APP2
            segment(0xED, ByteArray(40)),            // APP13
            segment(0xFE, "made by a test".toByteArray()), // comment
        )
        val i = JpegInfo.parse(big)!!
        // The thumbnail's own frame header (160x120) sits inside APP1 and must not be read as the photo's.
        assertEquals(1280, i.width)
        assertEquals(960, i.height)
        assertTrue(i.hasExif)
        assertTrue(i.exifThumbnail)
        assertEquals(1, i.sofCount)
        assertTrue(i.appSegments >= 4)
        assertEquals("two pictures in the file: the photo and its thumbnail", 2, JpegInfo.soiCount(big))
        assertTrue(JpegInfo.details(i, 2).contains("EXIF with thumbnail"))
    }

    @Test fun exifWithoutThumbnail() {
        val tiff = byteArrayOf(0x4D, 0x4D, 0, 0x2A, 0, 0, 0, 8)
        val j = withSegments(jpeg(32, 24), segment(0xE1, "Exif".toByteArray() + byteArrayOf(0, 0) + tiff))
        val i = JpegInfo.parse(j)!!
        assertTrue(i.hasExif)
        assertFalse(i.exifThumbnail)
        assertEquals(32, i.width)
    }

    @Test fun onlyTheFirstPartIsNeeded() {
        val j = jpeg(640, 480)
        val i = JpegInfo.parse(j, 1024)!!
        assertEquals(640, i.width)
    }

    @Test fun fillBytesBeforeAMarker() {
        val j = jpeg(16, 8)
        // FF FF FF E0 … : extra FF fill bytes are allowed before any marker.
        val padded = byteArrayOf(j[0], j[1], 0xFF.toByte(), 0xFF.toByte()) + j.copyOfRange(2, j.size)
        assertEquals(16, JpegInfo.parse(padded)!!.width)
    }

    @Test fun handBuiltFrameHeaders() {
        fun sof(marker: Int, w: Int, h: Int, comps: List<Int>): ByteArray {
            val body = byteArrayOf(8, (h shr 8).toByte(), h.toByte(), (w shr 8).toByte(), w.toByte(), comps.size.toByte()) +
                comps.flatMapIndexed { k, hv -> listOf((k + 1).toByte(), hv.toByte(), 0) }.toByteArray()
            return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + segment(marker, body) + byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 2)
        }
        val a = JpegInfo.parse(sof(0xC0, 640, 480, listOf(0x22, 0x11, 0x11)))!!
        assertEquals("4:2:0", a.sampling)
        assertEquals("baseline", a.kind)
        assertEquals("4:2:2", JpegInfo.parse(sof(0xC1, 1280, 720, listOf(0x21, 0x11, 0x11)))!!.sampling)
        assertEquals("4:4:4", JpegInfo.parse(sof(0xC2, 8, 8, listOf(0x11, 0x11, 0x11)))!!.sampling)
        assertEquals(5184, JpegInfo.parse(sof(0xC0, 5184, 3888, listOf(0x22, 0x11, 0x11)))!!.width)
        // C4 (Huffman tables) is not a frame header.
        val dhtOnly = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + segment(0xC4, ByteArray(10)) + byteArrayOf(0xFF.toByte(), 0xDA.toByte())
        assertNull(JpegInfo.parse(dhtOnly))
    }

    @Test fun badInputGivesNull() {
        assertNull(JpegInfo.parse(ByteArray(0)))
        assertNull(JpegInfo.parse(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A))) // PNG
        assertNull(JpegInfo.parse(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00))) // cut off
        val j = jpeg(16, 8)
        assertNull("cut before the frame header", JpegInfo.parse(j, 20))
        assertNull(JpegInfo.parse(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())))
    }

    @Test fun sofMarkers() {
        assertTrue(JpegInfo.isSof(0xC0)); assertTrue(JpegInfo.isSof(0xC2)); assertTrue(JpegInfo.isSof(0xCF))
        assertFalse(JpegInfo.isSof(0xC4)); assertFalse(JpegInfo.isSof(0xC8)); assertFalse(JpegInfo.isSof(0xCC)); assertFalse(JpegInfo.isSof(0xDB))
    }

    @Test fun logLineText() {
        val i = JpegInfo.parse(jpeg(640, 480))!!
        assertEquals("Photo 640x480, 71 KB", JpegInfo.headline(i, 71 * 1024L + 100))
        assertEquals("Photo size unknown, 54 KB", JpegInfo.headline(null, 54 * 1024L))
        assertTrue(JpegInfo.details(i, 1).startsWith("0.31 MP, "))
        assertTrue(JpegInfo.details(i, 1).endsWith("1 SOF, no EXIF, 1 picture"))
        assertEquals("size unknown (no readable frame header)", JpegInfo.details(null))
        assertEquals("0.5 KB", JpegInfo.kb(512))
        assertEquals("1 KB", JpegInfo.kb(1024))
        assertEquals("400 KB", JpegInfo.kb(400 * 1024L))
    }
}
