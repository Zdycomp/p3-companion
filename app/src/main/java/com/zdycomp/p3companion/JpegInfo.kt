package com.zdycomp.p3companion

import java.util.Locale

/**
 * 3.8: reads a JPEG's real pixel size from its frame header (SOF), without decoding the picture.
 * Pure Kotlin, so it is JVM-tested. From FFD8 it walks the `FF xx [length 2 BE]` segments and stops at
 * the first SOS (`FFDA`): every SOF before it (`C0`-`CF` except `C4` DHT, `C8` JPG and `CC` DAC) gives
 * `[precision 1][height 2 BE][width 2 BE][components 1]`, then per component `[id][H<<4|V][table]`.
 * An EXIF block (APP1) is noted, and so is a thumbnail inside it (a second SOI), because an embedded
 * thumbnail or a second SOF means two pictures in one file. A bad or missing SOF gives null: the size
 * is then "unknown" and nothing else changes (a photo is always saved).
 */
object JpegInfo {

    data class Info(
        val width: Int,
        val height: Int,
        /** 1 grey, 3 colour (YCbCr). */
        val components: Int,
        val precision: Int,
        /** The SOF marker's low byte: 0xC0 baseline, 0xC1 extended, 0xC2 progressive… */
        val sof: Int,
        /** "4:2:0", "4:2:2", "4:4:4", "grey" or the raw H×V of each component. */
        val sampling: String,
        /** SOF segments seen before the image data (more than 1 is unusual). */
        val sofCount: Int,
        val hasExif: Boolean,
        /** The EXIF block holds a small JPEG of its own (a thumbnail). */
        val exifThumbnail: Boolean,
        /** APPn segments before the image data. */
        val appSegments: Int,
    ) {
        val progressive: Boolean get() = sof == 0xC2 || sof == 0xC6 || sof == 0xCA || sof == 0xCE
        val kind: String get() = when (sof) {
            0xC0 -> "baseline"
            0xC1 -> "extended"
            0xC2 -> "progressive"
            0xC3 -> "lossless"
            else -> if (progressive) "progressive" else "SOF%X".format(Locale.US, sof and 0x0F)
        }
        val megapixels: Double get() = width.toLong() * height / 1_000_000.0
        val size: String get() = "${width}x$height"
    }

    /** The frame header of the JPEG in [b] (only the first [len] bytes are read), or null. */
    fun parse(b: ByteArray, len: Int = b.size): Info? {
        val n = minOf(len, b.size)
        if (n < 4 || u(b, 0) != 0xFF || u(b, 1) != 0xD8) return null
        var i = 2
        var first: Info? = null
        var sofs = 0
        var exif = false
        var thumb = false
        var apps = 0
        while (i + 1 < n) {
            if (u(b, i) != 0xFF) return first?.copy(sofCount = sofs, hasExif = exif, exifThumbnail = thumb, appSegments = apps)
            var m = u(b, i + 1)
            // Fill bytes: FF FF … before a marker.
            while (m == 0xFF && i + 2 < n) { i++; m = u(b, i + 1) }
            i += 2
            if (m == 0x01 || m in 0xD0..0xD7) continue // markers without a length
            if (m == 0xD9 || m == 0xDA) break // end of image, or the image data begins
            if (i + 1 >= n) break
            val segLen = (u(b, i) shl 8) or u(b, i + 1)
            if (segLen < 2) break
            val body = i + 2
            val end = i + segLen
            if (m in 0xE0..0xEF) {
                apps++
                // A cut-off EXIF block still counts; its thumbnail is looked for in the part we have.
                if (m == 0xE1 && segLen >= 8 && startsWith(b, body, EXIF)) {
                    exif = true
                    if (indexOfSoi(b, body + 6, minOf(end, n)) >= 0) thumb = true
                }
            } else if (isSof(m)) {
                sofs++
                if (first == null && body + 6 <= n) {
                    val precision = u(b, body)
                    val h = (u(b, body + 1) shl 8) or u(b, body + 2)
                    val w = (u(b, body + 3) shl 8) or u(b, body + 4)
                    val nc = u(b, body + 5)
                    val hv = IntArray(nc) { k -> if (body + 7 + k * 3 < n) u(b, body + 7 + k * 3) else -1 }
                    if (w > 0 && h > 0 && nc in 1..4) {
                        first = Info(w, h, nc, precision, m, sampling(hv), 0, false, false, 0)
                    }
                }
            }
            i = end
            if (i > n) break
        }
        return first?.copy(sofCount = sofs, hasExif = exif, exifThumbnail = thumb, appSegments = apps)
    }

    /** True for a start-of-frame marker: C0-CF except C4 (DHT), C8 (JPG) and CC (DAC). */
    fun isSof(m: Int): Boolean = m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC

    /** Pictures in the whole file: each SOI (FF D8 FF). Entropy-coded data can't hold FF D8 (FF is stuffed as FF 00). */
    fun soiCount(b: ByteArray, len: Int = b.size): Int {
        var c = 0
        val n = minOf(len, b.size)
        for (k in 0 until n - 2) if (u(b, k) == 0xFF && u(b, k + 1) == 0xD8 && u(b, k + 2) == 0xFF) c++
        return c
    }

    internal fun sampling(hv: IntArray): String {
        if (hv.isEmpty() || hv.any { it < 0 }) return "?"
        if (hv.size == 1) return "grey"
        fun h(x: Int) = x shr 4
        fun v(x: Int) = x and 0x0F
        if (hv.size == 3 && h(hv[1]) == 1 && v(hv[1]) == 1 && hv[2] == hv[1]) {
            when {
                h(hv[0]) == 2 && v(hv[0]) == 2 -> return "4:2:0"
                h(hv[0]) == 2 && v(hv[0]) == 1 -> return "4:2:2"
                h(hv[0]) == 1 && v(hv[0]) == 2 -> return "4:4:0"
                h(hv[0]) == 1 && v(hv[0]) == 1 -> return "4:4:4"
                h(hv[0]) == 4 && v(hv[0]) == 1 -> return "4:1:1"
            }
        }
        return hv.joinToString("/") { "${h(it)}x${v(it)}" }
    }

    /** "71 KB" (rounded), or "0.4 KB" under 1 KB. */
    fun kb(bytes: Long): String = if (bytes in 1..1023) String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else "${(bytes + 512) / 1024} KB"

    /** "Photo 640x480, 71 KB" or "Photo size unknown, 71 KB": the start of every Bluetooth photo log line. */
    fun headline(info: Info?, bytes: Long): String =
        if (info == null) "Photo size unknown, ${kb(bytes)}" else "Photo ${info.size}, ${kb(bytes)}"

    /** The rest: "0.31 MP, 4:2:0, baseline, 1 SOF, EXIF with thumbnail, 1 picture". */
    fun details(info: Info?, pictures: Int? = null): String {
        if (info == null) return "size unknown (no readable frame header)"
        val parts = mutableListOf(String.format(Locale.US, "%.2f MP", info.megapixels), info.sampling, info.kind)
        parts += if (info.sofCount == 1) "1 SOF" else "${info.sofCount} SOFs"
        parts += when {
            info.exifThumbnail -> "EXIF with thumbnail"
            info.hasExif -> "EXIF"
            else -> "no EXIF"
        }
        if (pictures != null) parts += if (pictures == 1) "1 picture" else "$pictures pictures in the file"
        return parts.joinToString(", ")
    }

    private val EXIF = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0) // "Exif\0\0"

    private fun startsWith(b: ByteArray, at: Int, p: ByteArray): Boolean {
        if (at + p.size > b.size) return false
        for (k in p.indices) if (b[at + k] != p[k]) return false
        return true
    }

    private fun indexOfSoi(b: ByteArray, from: Int, to: Int): Int {
        for (k in from until minOf(to, b.size) - 1) if (u(b, k) == 0xFF && u(b, k + 1) == 0xD8) return k
        return -1
    }

    private fun u(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
}
