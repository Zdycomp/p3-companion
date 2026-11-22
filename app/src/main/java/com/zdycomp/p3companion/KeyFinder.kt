package com.zdycomp.p3companion

import android.content.ContentResolver
import android.net.Uri
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Finds the owner's glasses key (their LensMoo account id) inside a Bluetooth log that their own
 * phone recorded while LensMoo connected to their glasses.
 *
 * Android writes that log when "Bluetooth HCI snoop log" is on in Developer options, and puts it
 * inside a bug report. The key travels in the glasses' bind command (node 102E):
 *   "102E" [len 00 32] [format 00] [bind type 0..5] [16 bytes] [0x20] [32 lowercase hex characters]
 *
 * Snoop files are read record by record, joining Bluetooth packets that split the command, and
 * "Filtered" logs (which cut every packet short) are recognised so the message can say so.
 * Everything happens on the phone; nothing is uploaded.
 */
object KeyFinder {

    /** What was seen, so the app can explain a miss. */
    class Stats {
        var scannedBytes = 0L
        var snoopFiles = 0
        var aclPackets = 0L
        var truncated = 0L
        var zipProblems = 0
    }

    data class Result(
        val key: String?,
        val scannedBytes: Long,
        val hits: Int,
        val snoopFiles: Int,
        val aclPackets: Long,
        val truncated: Long,
    ) {
        /** Most packets were cut short: the snoop log was set to Filtered. */
        val filtered: Boolean get() = aclPackets > 20 && truncated * 2 > aclPackets
    }

    /** Scans one file (zip, gzip, snoop log or anything else). [cacheDir] enables the zip fallback. */
    fun find(resolver: ContentResolver, uri: Uri, cacheDir: File? = null): Result {
        val counts = HashMap<String, Int>()
        val stats = Stats()
        val opened = resolver.openInputStream(uri) ?: error("Couldn't open that file.")
        var streamFailed = false
        opened.use { raw ->
            try {
                scanAny(BufferedInputStream(raw, 1 shl 16), counts, stats, depth = 0)
            } catch (e: Exception) {
                streamFailed = true
            }
        }
        // A zip the streaming reader can't walk (stored entries with data descriptors): read it properly.
        if (counts.isEmpty() && (streamFailed || stats.zipProblems > 0) && cacheDir != null) {
            val tmp = File(cacheDir, "keyfinder.zip")
            try {
                resolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                scanZipFile(tmp, counts, stats)
            } catch (_: Exception) {
            } finally {
                tmp.delete()
            }
        }
        return result(counts, stats)
    }

    fun result(counts: Map<String, Int>, stats: Stats): Result {
        val best = counts.maxByOrNull { it.value }
        return Result(best?.key, stats.scannedBytes, counts.values.sum(), stats.snoopFiles, stats.aclPackets, stats.truncated)
    }

    private val SNOOP_MAGIC = "btsnoop\u0000".toByteArray(Charsets.ISO_8859_1)

    /** Looks inside zips and gzip files (bug reports nest them), reads snoop logs, otherwise scans bytes. */
    fun scanAny(input: BufferedInputStream, counts: MutableMap<String, Int>, stats: Stats, depth: Int) {
        input.mark(16)
        val head = ByteArray(16)
        val got = readUpTo(input, head)
        input.reset()
        val b0 = if (got > 0) head[0].toInt() and 0xFF else -1
        val b1 = if (got > 1) head[1].toInt() and 0xFF else -1
        if (depth < 3 && b0 == 'P'.code && b1 == 'K'.code) {
            val zip = ZipInputStream(input)
            while (true) {
                val entry = try { zip.nextEntry } catch (e: Exception) { stats.zipProblems++; null } ?: break
                if (entry.isDirectory) continue
                val name = entry.name.lowercase()
                if (skipEntry(name)) continue
                try {
                    scanAny(BufferedInputStream(NonClosing(zip), 1 shl 16), counts, stats, depth + 1)
                } catch (e: Exception) {
                    stats.zipProblems++
                    break
                }
            }
            return
        }
        if (depth < 3 && b0 == 0x1F && b1 == 0x8B) {
            scanAny(BufferedInputStream(GZIPInputStream(NonClosing(input)), 1 shl 16), counts, stats, depth + 1)
            return
        }
        if (got == 16 && head.copyOfRange(0, 8).contentEquals(SNOOP_MAGIC)) {
            val datalink = ((head[12].toInt() and 0xFF) shl 24) or ((head[13].toInt() and 0xFF) shl 16) or
                ((head[14].toInt() and 0xFF) shl 8) or (head[15].toInt() and 0xFF)
            if (datalink == 1002) {
                readUpTo(input, ByteArray(16))
                scanSnoop(DataInputStream(input), counts, stats)
                return
            }
        }
        stats.scannedBytes += scanStream(input, counts)
    }

    private fun skipEntry(name: String) =
        name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".mp4") || name.endsWith(".apk") || name.endsWith(".so")

    private fun scanZipFile(file: File, counts: MutableMap<String, Int>, stats: Stats) {
        ZipFile(file).use { zf ->
            val entries = zf.entries().toList().filter { !it.isDirectory && !skipEntry(it.name.lowercase()) }
                .sortedByDescending { it.name.lowercase().contains("btsnoop") }
            for (e in entries) {
                runCatching { zf.getInputStream(e).use { scanAny(BufferedInputStream(it, 1 shl 16), counts, stats, 1) } }
                if (counts.isNotEmpty() && e.name.lowercase().contains("btsnoop")) return
            }
        }
    }

    /**
     * btsnoop records: orig_len, incl_len, flags, drops (u32 BE), timestamp (i64), then the packet.
     * Packet byte 0 is the H4 type (2 = ACL); ACL header is handle+flags u16 LE and length u16 LE.
     * Each connection and direction keeps the tail of its last packet, so a command split between two
     * packets is still found.
     */
    fun scanSnoop(input: DataInputStream, counts: MutableMap<String, Int>, stats: Stats) {
        stats.snoopFiles++
        val tails = HashMap<Int, ByteArray>()
        while (true) {
            val orig = try { input.readInt() } catch (e: EOFException) { break }
            val incl = input.readInt()
            val flags = input.readInt()
            input.readInt()
            input.readLong()
            if (incl < 0 || incl > 70_000) break
            val p = ByteArray(incl)
            input.readFully(p)
            stats.scannedBytes += 24 + incl
            if (incl < 5 || p[0].toInt() != 0x02) continue
            stats.aclPackets++
            if (incl < orig) stats.truncated++
            val hf = (p[1].toInt() and 0xFF) or ((p[2].toInt() and 0xFF) shl 8)
            val key = (hf and 0x0FFF) * 2 + (flags and 1)
            val tail = tails[key] ?: ByteArray(0)
            val joined = ByteArray(tail.size + incl - 5)
            System.arraycopy(tail, 0, joined, 0, tail.size)
            System.arraycopy(p, 5, joined, tail.size, incl - 5)
            scanWindow(joined, joined.size, counts)
            tails[key] = joined.copyOfRange(maxOf(0, joined.size - 96), joined.size)
        }
    }

    private fun readUpTo(input: InputStream, b: ByteArray): Int {
        var n = 0
        while (n < b.size) {
            val r = input.read(b, n, b.size - n)
            if (r <= 0) break
            n += r
        }
        return n
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

    /** Plain-English reason for a miss. */
    fun explainMiss(r: Result): String = when {
        r.snoopFiles == 0 ->
            "That file has no Bluetooth log in it. Check the snoop log was on (Enabled), then turn Bluetooth off and on " +
                "before connecting LensMoo. On Samsung, if this keeps happening, dial *#9900#, tap Copy to sdcard, then pick " +
                "btsnoop_hci.log from the log folder."
        r.filtered ->
            "Your Bluetooth snoop log is set to Filtered, which cuts out the part we need. In Developer options pick Enabled, " +
                "turn Bluetooth off and on, connect LensMoo again, then take a new bug report."
        else ->
            "Read ${r.scannedBytes / 1_048_576} MB of Bluetooth log but the sign-in wasn't in it. The log may have rolled over: " +
                "take the bug report within 10 minutes of connecting LensMoo, without playing music in between."
    }

    fun mask(key: String): String = if (key.length < 8) "••••" else key.take(4) + "••••••••" + key.takeLast(4)
}
