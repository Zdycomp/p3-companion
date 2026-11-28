package com.zdycomp.p3companion

/**
 * 4.1: the glasses' own Opus packets as an Ogg Opus file (RFC 3533 pages, RFC 7845 mapping), written
 * in pure Kotlin so the exact bits the glasses' mic produced go to Gemini with no re-encode
 * (docs/HARDWARE-MAP.md §4.4): about 2 KB per second against 32 KB/s for 16 kHz WAV.
 *
 * Page 0 holds the OpusHead (BOS), page 1 the OpusTags, then audio pages of up to [PACKETS_PER_PAGE]
 * packets; the last page is flagged EOS. Granule positions count 48 kHz samples including pre-skip.
 */
object OggOpus {
    const val PACKETS_PER_PAGE = 50
    /** Every glasses packet is 20 ms = 960 samples at 48 kHz. */
    const val SAMPLES_PER_PACKET = 960
    const val VENDOR = "P3 Companion"
    const val MIME = "audio/ogg"

    private const val FLAG_BOS = 0x02
    private const val FLAG_EOS = 0x04

    private val CRC_TABLE = IntArray(256) { i ->
        var r = i shl 24
        repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
        r
    }

    /** Ogg's CRC-32: polynomial 0x04C11DB7, initial 0, not reflected, no final XOR. */
    fun crc(b: ByteArray, off: Int = 0, len: Int = b.size - off): Int {
        var c = 0
        for (i in off until off + len) c = (c shl 8) xor CRC_TABLE[((c ushr 24) xor (b[i].toInt() and 0xFF)) and 0xFF]
        return c
    }

    /** The OpusTags packet: magic, vendor string, no user comments. */
    fun tags(vendor: String = VENDOR): ByteArray {
        val v = vendor.toByteArray(Charsets.UTF_8)
        val out = ByteArray(8 + 4 + v.size + 4)
        "OpusTags".forEachIndexed { i, c -> out[i] = c.code.toByte() }
        le32(out, 8, v.size)
        System.arraycopy(v, 0, out, 12, v.size)
        le32(out, 12 + v.size, 0)
        return out
    }

    /** Lacing values for one packet: 255s then the remainder (a 40-byte packet is the single value 40). */
    fun lacing(size: Int): IntArray {
        val n = size / 255 + 1
        return IntArray(n) { i -> if (i < n - 1) 255 else size % 255 }
    }

    /** One page: header, lacing table and the packets, with its CRC filled in. */
    fun page(packets: List<ByteArray>, flags: Int, granule: Long, serial: Int, sequence: Int): ByteArray {
        val lace = packets.flatMap { lacing(it.size).toList() }
        require(lace.size <= 255) { "too many segments for one page" }
        val body = packets.sumOf { it.size }
        val out = ByteArray(27 + lace.size + body)
        out[0] = 'O'.code.toByte(); out[1] = 'g'.code.toByte(); out[2] = 'g'.code.toByte(); out[3] = 'S'.code.toByte()
        out[4] = 0
        out[5] = flags.toByte()
        for (i in 0 until 8) out[6 + i] = ((granule ushr (8 * i)) and 0xFF).toByte()
        le32(out, 14, serial)
        le32(out, 18, sequence)
        // 22..25 CRC, zero while it's computed
        out[26] = lace.size.toByte()
        lace.forEachIndexed { i, v -> out[27 + i] = v.toByte() }
        var at = 27 + lace.size
        packets.forEach { System.arraycopy(it, 0, out, at, it.size); at += it.size }
        le32(out, 22, crc(out))
        return out
    }

    /**
     * The whole file for [packets] (40-byte glasses packets, or any Opus packets of 20 ms). The
     * OpusHead is [OpusCsd.head] (1 channel, 16 kHz input, pre-skip 312).
     */
    fun build(packets: List<ByteArray>, serial: Int = 0x50334F70, preSkip: Int = OpusCsd.PRE_SKIP, inputRate: Int = MicCapture.RATE): ByteArray {
        val out = java.io.ByteArrayOutputStream(27 * 4 + packets.sumOf { it.size + 1 } + 64)
        var seq = 0
        out.write(page(listOf(OpusCsd.head(1, inputRate, preSkip)), FLAG_BOS, 0, serial, seq++))
        out.write(page(listOf(tags()), 0, 0, serial, seq++))
        if (packets.isEmpty()) {
            out.write(page(emptyList(), FLAG_EOS, 0, serial, seq))
            return out.toByteArray()
        }
        var done = 0
        while (done < packets.size) {
            val n = minOf(PACKETS_PER_PAGE, packets.size - done)
            val chunk = packets.subList(done, done + n)
            done += n
            val last = done == packets.size
            out.write(page(chunk, if (last) FLAG_EOS else 0, granuleAfter(done), serial, seq++))
        }
        return out.toByteArray()
    }

    /** Granule position after [packets] packets: 960 samples each at 48 kHz (pre-skip is counted in). */
    fun granuleAfter(packets: Int): Long = packets.toLong() * SAMPLES_PER_PACKET

    /** Reads the pages back (for tests and Diagnostics): flags, granule, sequence, CRC ok, packets. */
    class Page(val flags: Int, val granule: Long, val serial: Int, val sequence: Int, val crcOk: Boolean, val packets: List<ByteArray>)

    fun parse(b: ByteArray): List<Page> {
        val pages = ArrayList<Page>()
        var at = 0
        while (at + 27 <= b.size) {
            if (b[at] != 'O'.code.toByte() || b[at + 1] != 'g'.code.toByte() || b[at + 2] != 'g'.code.toByte() || b[at + 3] != 'S'.code.toByte()) break
            val flags = b[at + 5].toInt() and 0xFF
            var granule = 0L
            for (i in 0 until 8) granule = granule or ((b[at + 6 + i].toLong() and 0xFF) shl (8 * i))
            val serial = rd32(b, at + 14)
            val seq = rd32(b, at + 18)
            val stored = rd32(b, at + 22)
            val segs = b[at + 26].toInt() and 0xFF
            var bodyLen = 0
            for (i in 0 until segs) bodyLen += b[at + 27 + i].toInt() and 0xFF
            val total = 27 + segs + bodyLen
            if (at + total > b.size) break
            val copy = b.copyOfRange(at, at + total)
            copy[22] = 0; copy[23] = 0; copy[24] = 0; copy[25] = 0
            val ok = crc(copy) == stored
            val packets = ArrayList<ByteArray>()
            var p = at + 27 + segs
            var cur = 0
            for (i in 0 until segs) {
                val v = b[at + 27 + i].toInt() and 0xFF
                cur += v
                if (v < 255) { packets += b.copyOfRange(p, p + cur); p += cur; cur = 0 }
            }
            pages += Page(flags, granule, serial, seq, ok, packets)
            at += total
        }
        return pages
    }

    /**
     * The packets that cover [startMs]..[endMs] of a capture whose first packet came at 0 ms (20 ms each),
     * with the same 300 ms before and 400 ms after the speech that the WAV trim keeps.
     */
    fun trim(packets: List<ByteArray>, speechStartMs: Double, speechEndMs: Double, preRollMs: Int = 300, tailMs: Int = 400): List<ByteArray> {
        if (packets.isEmpty() || speechStartMs < 0) return packets
        val from = ((speechStartMs - preRollMs) / 20.0).toInt().coerceIn(0, packets.size)
        val to = if (speechEndMs < 0) packets.size else Math.ceil((speechEndMs + tailMs) / 20.0).toInt().coerceIn(from, packets.size)
        return packets.subList(from, to)
    }

    private fun le32(b: ByteArray, at: Int, v: Int) { for (i in 0 until 4) b[at + i] = ((v ushr (8 * i)) and 0xFF).toByte() }
    private fun rd32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
}

/**
 * 4.1: what the bytes of one SILK packet say without decoding it (HARDWARE-MAP §4.2): the coded frame
 * size tracks loudness (digital silence is the 7-byte padded frame), a free voice-activity hint and
 * the basis of hardware test T2's histogram.
 */
object OpusToc {
    fun config(p: ByteArray): Int = if (p.isEmpty()) -1 else (p[0].toInt() and 0xFF) shr 3

    /** Bytes of coded audio in one packet (code 0: all but the TOC; code 3 with padding: minus the pad). */
    fun frameBytes(p: ByteArray): Int {
        if (p.isEmpty()) return 0
        val code = p[0].toInt() and 0x03
        return when (code) {
            0 -> p.size - 1
            1, 2 -> p.size - 1
            else -> {
                if (p.size < 2) return 0
                val cb = p[1].toInt() and 0xFF
                val padded = cb and 0x40 != 0
                var at = 2
                var pad = 0
                if (padded) {
                    while (at < p.size) {
                        val v = p[at].toInt() and 0xFF
                        at++
                        if (v == 255) pad += 254 else { pad += v; break }
                    }
                }
                (p.size - at - pad).coerceAtLeast(0)
            }
        }
    }

    /** Near silence: a SILK frame this small carries no speech (calibrated by hardware test T2). */
    fun quiet(p: ByteArray, limit: Int = 10): Boolean = frameBytes(p) <= limit

    /** A size histogram ("≤10 B: 40, 11-20: 3, 21-30: 0, 31-40: 7") for one second of packets. */
    fun histogram(packets: List<ByteArray>): IntArray {
        val h = IntArray(4)
        packets.forEach { val n = frameBytes(it); h[when { n <= 10 -> 0; n <= 20 -> 1; n <= 30 -> 2; else -> 3 }]++ }
        return h
    }

    fun histogramText(h: IntArray): String = "≤10 B: ${h[0]}, 11-20 B: ${h[1]}, 21-30 B: ${h[2]}, 31-40 B: ${h[3]}"
}
