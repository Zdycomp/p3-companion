package com.zdycomp.p3companion

import java.io.ByteArrayOutputStream

/*
 * Wire format of the Shenju "XK" glasses family that the LensMoo app drives (NovaSight P3 and siblings).
 *
 * Written from the public, MIT-licensed protocol documentation in
 * github.com/beneduzi/xk-one-pro-sdk (validated on real hardware by its author) and the research notes in
 * github.com/joaosouz4dev/lensmoo-crash-fix. See THIRD_PARTY_NOTICES.md and docs/PROTOCOL-NOTES.md.
 *
 * Envelope, 16 bytes, little-endian:
 *   [0] head      0x30 control, 0x4A image/media, 0x2B custom
 *   [1] order     rolling sequence number (app and glasses each keep their own)
 *   [2..3] cmd    bit 0x8000 = from the glasses; low part 0x0001 data, 0x0004 ack, 0x0009 media ack
 *   [4..5] divide low 3 bits: 0 whole, 1 first, 2 middle, 3 last fragment (higher bits: length hint)
 *   [6..7] payload length
 *   [8..11] offset (always 0)
 *   [12..13] CRC-16/CCITT-FALSE of the payload only
 *   [14..15] reserved (0)
 *
 * Control payload:
 *   [reqId LE2][FF FF FF FF][action LE2][00 01][node 4 ASCII] then
 *   app -> glasses: [len BE2][format 00][data]          (identical to [00][len LE2] below 256 bytes)
 *   glasses -> app: [format 1][len LE2][data]           format 0 bin, 2 JSON, 3 none, 4 error code
 */
object Xk {
    const val HEAD_CONTROL = 0x30
    const val HEAD_MEDIA = 0x4A
    const val HEAD_CUSTOM = 0x2B

    const val ACTION_READ = 1
    const val ACTION_WRITE = 2
    const val ACTION_EXECUTE = 3

    const val FMT_BIN = 0
    const val FMT_JSON = 2
    const val FMT_NODATA = 3
    const val FMT_ERRCODE = 4

    const val BIND_CONNECT_BACK = 2

    /** Media element types (first byte of a reassembled 0x4A element). */
    const val MEDIA_PHOTO = 0
    const val MEDIA_H264 = 2

    /** Largest photo element count: 7300 takes a one-byte index. */
    const val MAX_ELEMENTS = 255

    /** Every cmd value seen from either side. Anything else is not a frame header. */
    private val KNOWN_CMDS = setOf(0x0001, 0x8001, 0x8002, 0x0004, 0x8004, 0x0009, 0x8009)

    private val CRC_TABLE = IntArray(256) { n ->
        var c = n shl 8
        repeat(8) { c = if (c and 0x8000 != 0) ((c shl 1) xor 0x1021) and 0xFFFF else (c shl 1) and 0xFFFF }
        c
    }

    fun crc16(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in from until to) {
            crc = ((crc shl 8) and 0xFFFF) xor CRC_TABLE[((crc shr 8) xor (data[i].toInt() and 0xFF)) and 0xFF]
        }
        return crc
    }

    class Frame(
        val head: Int,
        val order: Int,
        val cmd: Int,
        val divide: Int,
        val payload: ByteArray,
    ) {
        val fromDevice get() = cmd and 0x8000 != 0
        val operation get() = cmd and 0x7FFF
        /** A link-level acknowledgement. 0x2B frames reuse cmd 4 for data, so they never count. */
        val isAck get() = (head == HEAD_CONTROL && operation == 0x0004) || (head == HEAD_MEDIA && operation == 0x0009)
        val fragment get() = divide and 0x07
        /** Element length hint carried in the high divide bits (13 bits). */
        val logicalLength get() = (((divide and 0xF8) shl 5) or ((divide shr 8) and 0xFF)) and 0x1FFF

        /** The 4-letter node of a control frame, like "57B1", or "" for anything else. */
        val node: String
            get() = if (head == HEAD_CONTROL && payload.size >= 14 && !isAck)
                String(payload, 10, 4, Charsets.US_ASCII) else ""

        /** Request id at payload[0..1]; replies echo the id of the request they answer. */
        val requestId: Int
            get() = if (head == HEAD_CONTROL && payload.size >= 2)
                (payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8) else -1

        /** payload[6..7]: 0x8002 reply, 0x8001 push, 0x8004 ack (as sent by the glasses). */
        val action: Int
            get() = if (payload.size >= 8) (payload[6].toInt() and 0xFF) or ((payload[7].toInt() and 0xFF) shl 8) else -1

        /** Reply layout after the node: [format 1][len LE2][data]. */
        val replyType: Int get() = if (payload.size > 14) payload[14].toInt() and 0xFF else -1

        val replyLen: Int
            get() = if (payload.size >= 17) (payload[15].toInt() and 0xFF) or ((payload[16].toInt() and 0xFF) shl 8) else 0

        /** Exactly the reply's data, never more than its declared length. */
        val data: ByteArray
            get() = if (payload.size > 17) payload.copyOfRange(17, minOf(payload.size, 17 + replyLen)) else ByteArray(0)

        /** The error code of an error-code reply (0 = OK), or null for any other reply. */
        val errCode: Int?
            get() = if (replyType == FMT_ERRCODE && payload.size > 17 && replyLen >= 1) payload[17].toInt() and 0xFF else null

        /** Text of a JSON reply, taken only from the reply's data. */
        fun json(): String? {
            val d = data
            val start = d.indexOf('{'.code.toByte())
            val end = d.lastIndexOf('}'.code.toByte())
            return if (start >= 0 && end > start) String(d, start, end - start + 1, Charsets.UTF_8) else null
        }

        fun encode(): ByteArray {
            val out = ByteArray(16 + payload.size)
            out[0] = head.toByte()
            out[1] = order.toByte()
            putLe16(out, 2, cmd)
            putLe16(out, 4, divide)
            putLe16(out, 6, payload.size)
            putLe16(out, 12, crc16(payload))
            System.arraycopy(payload, 0, out, 16, payload.size)
            return out
        }

        override fun toString(): String {
            val kind = when {
                isAck -> "ack"
                head == HEAD_MEDIA -> "media part $fragment"
                head == HEAD_CUSTOM -> "custom"
                else -> node
            }
            return "${if (fromDevice) "←" else "→"} ${"%02X".format(head)} #$order $kind (${payload.size} B)"
        }
    }

    fun control(order: Int, node: String, action: Int, requestId: Int, data: ByteArray = ByteArray(0)): Frame {
        require(node.length == 4)
        val p = ByteArray(17 + data.size)
        putLe16(p, 0, requestId)
        p[2] = 0xFF.toByte(); p[3] = 0xFF.toByte(); p[4] = 0xFF.toByte(); p[5] = 0xFF.toByte()
        putLe16(p, 6, action)
        p[8] = 0; p[9] = 1
        node.toByteArray(Charsets.US_ASCII).copyInto(p, 10)
        p[14] = ((data.size shr 8) and 0xFF).toByte()
        p[15] = (data.size and 0xFF).toByte()
        p[16] = 0 // format byte
        data.copyInto(p, 17)
        return Frame(HEAD_CONTROL, order and 0xFF, 0x0001, 0, p)
    }

    fun ack(order: Int, targetOrder: Int, head: Int = HEAD_CONTROL): Frame =
        Frame(head, order and 0xFF, 0x0004, 0, byteArrayOf(0x01, targetOrder.toByte()))

    fun mediaAck(order: Int): Frame = Frame(HEAD_MEDIA, order and 0xFF, 0x0009, 0, byteArrayOf(0x01))

    /**
     * Data for node 102E: sign in with the owner's own LensMoo account id. Only types 0-2 are
     * allowed here: 3 disconnects, 4 removes the pairing on the glasses and 5 powers them off.
     */
    fun userBindData(userId: String, bindType: Int = BIND_CONNECT_BACK): ByteArray {
        require(bindType in 0..2) { "bind type $bindType would disconnect, unpair or power off the glasses" }
        val id = userId.toByteArray(Charsets.US_ASCII)
        val out = ByteArray(1 + 16 + 1 + id.size)
        out[0] = bindType.toByte()
        // 16-byte random code stays zero, as in the working captures
        out[17] = id.size.toByte()
        id.copyInto(out, 18)
        return out
    }

    fun isValidUserId(s: String): Boolean = Regex("^[0-9a-f]{32}$").matches(s)

    /** Number of photo elements announced by a 7320 push: one byte, or two bytes little-endian. */
    fun elementCount(f: Frame): Int? {
        val d = f.data
        if (d.isEmpty()) return null
        val first = d[0].toInt() and 0xFF
        if (d.size == 1) return first.takeIf { it in 1..MAX_ELEMENTS }
        val le = first or ((d[1].toInt() and 0xFF) shl 8)
        return when {
            le in 1..MAX_ELEMENTS -> le
            first in 1..MAX_ELEMENTS -> first
            else -> null
        }
    }

    /** Battery reply (node 1003): [charging 0/1][level 0..100]. */
    fun battery(f: Frame): Pair<Boolean, Int>? {
        if (f.node != "1003" || f.replyType != FMT_BIN) return null
        val d = f.data
        if (d.size < 2) return null
        val level = d[1].toInt() and 0xFF
        return if (level in 0..100) (d[0].toInt() == 1) to level else null
    }

    /** JSON reply as a map, or null. */
    fun jsonObject(f: Frame): Map<*, *>? = f.json()?.let { runCatching { Json.parse(it) as? Map<*, *> }.getOrNull() }

    enum class Bind { BOUND, REFUSED, UNKNOWN }

    /** What a 102E reply says, if one comes (none is documented for success). */
    fun bindOutcome(f: Frame?): Bind = when {
        f == null -> Bind.UNKNOWN
        f.errCode != null && f.errCode != 0 -> Bind.REFUSED
        f.errCode == 0 || f.replyType == FMT_NODATA || f.replyType == FMT_BIN -> Bind.BOUND
        else -> Bind.UNKNOWN
    }

    fun errorText(code: Int): String = when (code) {
        0 -> "OK"
        1 -> "the glasses said it failed"
        2 -> "nothing to do (no data)"
        3 -> "the glasses don't accept that command from the app"
        4 -> "the glasses don't support this"
        5 -> "the glasses rejected the data"
        else -> "code $code"
    }

    private fun putLe16(b: ByteArray, at: Int, v: Int) {
        b[at] = (v and 0xFF).toByte()
        b[at + 1] = ((v shr 8) and 0xFF).toByte()
    }

    internal fun le32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)

    /**
     * Splits a byte stream into frames. It only accepts a header that could be real (known cmd,
     * zero offset, sane length), so a stray 0x30 byte can never make it wait for data that will not
     * come; [skipStalled] releases anything still stuck when the link goes quiet. One per link.
     */
    class Parser(private val maxControlPayload: Int = 32 * 1024) {
        private var buf = ByteArray(16 * 1024)
        private var len = 0
        /** Bytes skipped while finding the next real frame. */
        var dropped = 0L
            private set
        var badCrc = 0L
            private set
        /** Largest 0x4A payload seen: the real fragment size, useful in logs. */
        var maxMediaPayload = 0
            private set

        /** Bytes waiting for the rest of a frame. */
        val pending: Int @Synchronized get() = len

        @Synchronized
        fun reset() { len = 0 }

        @Synchronized
        fun feed(data: ByteArray, count: Int = data.size): List<Frame> {
            require(count in 0..data.size)
            if (len + count > buf.size) buf = buf.copyOf(maxOf(len + count, buf.size * 2))
            System.arraycopy(data, 0, buf, len, count)
            len += count
            return drain()
        }

        /** The link went quiet with bytes pending: give up on what's waiting, keep real frames behind it. */
        @Synchronized
        fun skipStalled(): List<Frame> {
            val out = ArrayList<Frame>()
            while (len >= 16) {
                compact(1)
                dropped++
                out += drain()
            }
            dropped += len
            len = 0
            return out
        }

        private fun drain(): List<Frame> {
            val out = ArrayList<Frame>(2)
            var pos = 0
            while (len - pos >= 16) {
                val plen = plausibleLength(pos)
                if (plen < 0) { pos++; dropped++; continue }
                if (len - pos < 16 + plen) break
                if (crc16(buf, pos + 16, pos + 16 + plen) != u16(pos + 12)) { pos++; dropped++; badCrc++; continue }
                val head = buf[pos].toInt() and 0xFF
                if (head == HEAD_MEDIA && plen > maxMediaPayload) maxMediaPayload = plen
                out += Frame(head, buf[pos + 1].toInt() and 0xFF, u16(pos + 2), u16(pos + 4), buf.copyOfRange(pos + 16, pos + 16 + plen))
                pos += 16 + plen
            }
            compact(pos)
            if (len == 0 && buf.size > 256 * 1024) buf = ByteArray(16 * 1024)
            return out
        }

        /** Payload length if a real frame could start at [p], else -1. */
        private fun plausibleLength(p: Int): Int {
            val head = buf[p].toInt() and 0xFF
            if (head != HEAD_CONTROL && head != HEAD_MEDIA && head != HEAD_CUSTOM) return -1
            if (u16(p + 2) !in KNOWN_CMDS) return -1
            if (buf[p + 8].toInt() or buf[p + 9].toInt() or buf[p + 10].toInt() or buf[p + 11].toInt() != 0) return -1
            val plen = u16(p + 6)
            if (head != HEAD_MEDIA && plen > maxControlPayload) return -1
            return plen
        }

        private fun u16(at: Int) = (buf[at].toInt() and 0xFF) or ((buf[at + 1].toInt() and 0xFF) shl 8)

        private fun compact(n: Int) {
            if (n <= 0) return
            System.arraycopy(buf, n, buf, 0, len - n)
            len -= n
        }
    }

    /**
     * Rebuilds media elements from 0x4A frames, whoever wants them, and hands each finished element
     * on with its type byte (0 photo, 2 H.264 video).
     *   divide 0: the payload is one whole element.
     *   divide 1/2/3: first/middle/last, each starting with a 4-byte LE index 0, 1, 2, ...
     *   divide 4-7: video carriage named by the vendor SDK; taken as a whole element.
     * [onBroken] reports an element that couldn't be rebuilt, with its type if known.
     */
    class MediaDemux(
        private val onElement: (type: Int, element: ByteArray) -> Unit,
        private val onBroken: (type: Int?, reason: String) -> Unit = { _, _ -> },
        private val maxElement: Int = 2 * 1024 * 1024,
    ) {
        private val cur = ByteArrayOutputStream()
        private var next = 0L
        private var active = false
        private var curType: Int? = null

        fun reset() { cur.reset(); active = false; next = 0; curType = null }

        fun feed(f: Frame) {
            if (f.head != HEAD_MEDIA || f.isAck) return
            val p = f.payload
            when (f.fragment) {
                0 -> complete(p)
                1, 2, 3 -> {
                    if (p.size < 4) return broken("short fragment")
                    val idx = le32(p, 0)
                    if (f.fragment == 1) {
                        if (active) broken("new element before the last one finished")
                        cur.reset(); next = 0; active = true
                        curType = if (p.size > 4) p[4].toInt() and 0xFF else null
                    }
                    if (!active) return broken("fragment without a start")
                    if (idx != next) return broken("fragment $idx, wanted $next")
                    next++
                    cur.write(p, 4, p.size - 4)
                    if (cur.size() > maxElement) return broken("element too large")
                    if (f.fragment == 3) {
                        active = false
                        val e = cur.toByteArray()
                        cur.reset()
                        complete(e)
                    }
                }
                else -> complete(
                    if (p.size > 5 && p[0].toInt() != MEDIA_H264 && p[4].toInt() == MEDIA_H264) p.copyOfRange(4, p.size) else p
                )
            }
        }

        private fun broken(reason: String) {
            val t = curType
            active = false
            cur.reset()
            curType = null
            onBroken(t, reason)
        }

        private fun complete(e: ByteArray) {
            if (e.isEmpty()) return onBroken(null, "empty element")
            onElement(e[0].toInt() and 0xFF, e)
        }
    }

    /**
     * Collects the elements of one photo (fetched one at a time with 7300) and joins them into the JPEG.
     * Each element is [type 00][length LE4, counting these 5 bytes][JPEG slice].
     */
    class PhotoAssembler(private val count: Int) {
        enum class Result { MORE, DONE, FAILED }

        init { require(count in 1..MAX_ELEMENTS) { "bad part count $count" } }

        private val elements = arrayOfNulls<ByteArray>(count + 1)
        private var active = 0
        var failReason: String? = null
            private set

        private val demux = MediaDemux(
            onElement = { type, e -> lastResult = if (type == MEDIA_PHOTO) offer(e) else Result.MORE },
            onBroken = { type, why -> lastResult = if (type == null || type == MEDIA_PHOTO) fail(why) else Result.MORE },
        )
        private var lastResult = Result.MORE

        /** Expect element [index] next. */
        fun start(index: Int) {
            require(index in 1..count)
            active = index
            failReason = null
            demux.reset()
        }

        fun has(index: Int) = elements.getOrNull(index) != null

        val isComplete get() = (1..count).all { elements[it] != null }

        /** Feeds a raw 0x4A frame (for callers that don't run their own [MediaDemux]). */
        fun feed(f: Frame): Result {
            lastResult = Result.MORE
            demux.feed(f)
            return lastResult
        }

        /** A whole reassembled photo element for the active index. */
        fun offer(raw: ByteArray): Result {
            if (active == 0) return Result.MORE
            if (raw.size < 5) return fail("too small")
            if (raw[0].toInt() != MEDIA_PHOTO) return fail("not a photo element (type ${raw[0].toInt() and 0xFF})")
            val declared = le32(raw, 1)
            if (declared != raw.size.toLong()) return fail("length says $declared, got ${raw.size}")
            elements[active] = raw.copyOfRange(5, raw.size)
            active = 0
            return Result.DONE
        }

        /** The element being fetched couldn't be rebuilt. */
        fun fail(why: String): Result {
            if (active == 0) return Result.MORE
            failReason = "part $active: $why"
            active = 0
            return Result.FAILED
        }

        fun jpeg(): ByteArray {
            val out = ByteArrayOutputStream()
            for (i in 1..count) out.write(elements[i] ?: error("missing part $i of $count"))
            val img = out.toByteArray()
            if (img.size < 4 || img[0] != 0xFF.toByte() || img[1] != 0xD8.toByte()) error("not a JPEG")
            val last = img.size - 1
            if ((maxOf(0, last - 16) until last).none { img[it] == 0xFF.toByte() && img[it + 1] == 0xD9.toByte() }) {
                error("the photo is cut off (no end marker)")
            }
            return img
        }
    }
}
