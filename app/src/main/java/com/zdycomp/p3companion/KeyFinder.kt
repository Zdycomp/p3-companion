package com.zdycomp.p3companion

import android.content.ContentResolver
import android.net.Uri
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Finds the owner's glasses key (their LensMoo account id) inside a Bluetooth log that their own
 * phone recorded while LensMoo connected to their glasses.
 *
 * Android writes that log when "Bluetooth HCI snoop log" is on in Developer options, and puts it
 * inside a bug report. The key travels in the glasses' bind command (node 102E):
 *   "102E" [len 00 32] [format 00] [bind type 0..5] [16 bytes] [0x20] [32 lowercase hex characters]
 *
 * Everything happens on the phone; nothing is uploaded.
 */
object KeyFinder {

    data class Result(val key: String?, val scannedBytes: Long, val hits: Int)

    fun find(resolver: ContentResolver, uri: Uri): Result {
        val counts = HashMap<String, Int>()
        var scanned = 0L
        resolver.openInputStream(uri)?.use { raw ->
            scanned = scanAny(BufferedInputStream(raw, 1 shl 16), counts, depth = 0)
        } ?: error("Couldn't open that file.")
        val best = counts.maxByOrNull { it.value }
        return Result(best?.key, scanned, counts.values.sum())
    }

    /** Looks inside zips and gzip files (bug reports nest them), otherwise scans the bytes. */
    private fun scanAny(input: BufferedInputStream, counts: MutableMap<String, Int>, depth: Int): Long {
        input.mark(4)
        val b0 = input.read()
        val b1 = input.read()
        input.reset()
        if (depth < 3 && b0 == 'P'.code && b1 == 'K'.code) {
            var total = 0L
            val zip = ZipInputStream(input)
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.lowercase()
                // Skip big media and app files that can't hold the log.
                if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".mp4") || name.endsWith(".apk")) continue
                total += scanAny(BufferedInputStream(NonClosing(zip), 1 shl 16), counts, depth + 1)
            }
            return total
        }
        if (depth < 3 && b0 == 0x1F && b1 == 0x8B) {
            return scanAny(BufferedInputStream(GZIPInputStream(NonClosing(input)), 1 shl 16), counts, depth + 1)
        }
        return scanStream(input, counts)
    }

    private const val TAIL = 64

    /** Scans in chunks, keeping a small overlap so a match split across chunks is still found. */
    fun scanStream(input: InputStream, counts: MutableMap<String, Int>): Long {
        val chunk = ByteArray(1 shl 16)
        val window = ByteArray(chunk.size + TAIL)
        var carry = 0
        var total = 0L
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            if (n == 0) continue
            total += n
            System.arraycopy(chunk, 0, window, carry, n)
            val len = carry + n
            scanWindow(window, len, counts)
            carry = minOf(TAIL, len)
            System.arraycopy(window, len - carry, window, 0, carry)
        }
        return total
    }

    // "102E" in ASCII
    private val NODE = byteArrayOf(0x31, 0x30, 0x32, 0x45)

    fun scanWindow(b: ByteArray, len: Int, counts: MutableMap<String, Int>) {
        // Need 4 (node) + 2 (len) + 1 (format) + 1 (type) + 16 + 1 + 32 = 57 bytes from the match.
        var i = 0
        while (i + 57 <= len) {
            if (b[i] == NODE[0] && b[i + 1] == NODE[1] && b[i + 2] == NODE[2] && b[i + 3] == NODE[3]) {
                val ok = b[i + 4].toInt() == 0x00 && b[i + 5].toInt() == 0x32 && b[i + 6].toInt() == 0x00 &&
                    (b[i + 7].toInt() and 0xFF) <= 5 && b[i + 24].toInt() == 0x20
                if (ok) {
                    val key = String(b, i + 25, 32, Charsets.ISO_8859_1)
                    if (Xk.isValidUserId(key)) counts[key] = (counts[key] ?: 0) + 1
                }
            }
            i++
        }
    }

    /** Lets a zip entry be read without closing the whole zip. */
    private class NonClosing(private val inner: InputStream) : InputStream() {
        override fun read(): Int = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
        override fun available(): Int = inner.available()
        override fun close() {}
    }

    fun mask(key: String): String = if (key.length < 8) "••••" else key.take(4) + "••••••••" + key.takeLast(4)
}
