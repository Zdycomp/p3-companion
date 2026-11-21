package com.zdycomp.p3companion

import java.io.ByteArrayOutputStream

/*
 * Wire format of the Shenju "XK" glasses family that the LensMoo app drives.
 *
 * Written from the public, MIT-licensed protocol documentation in
 * github.com/beneduzi/xk-one-pro-sdk (validated on real hardware by its author).
 * See THIRD_PARTY_NOTICES.md.
 *
 * Envelope, 16 bytes, little-endian:
 *   [0] head      0x30 control, 0x4A image/media, 0x2B custom
 *   [1] order     rolling sequence number
 *   [2..3] cmd    0x0001 app data, 0x8001 device push, 0x8002 device reply, 0x0004 ack, 0x0009 media ack
 *   [4..5] divide low 3 bits: 0 whole, 1 first, 2 middle, 3 last fragment
 *   [6..7] payload length
 *   [8..11] offset (0)
 *   [12..13] CRC-16/CCITT-FALSE of the payload only
 *   [14..15] reserved (0)
 *
 * Control payload:
 *   [reqId LE2][FF FF FF FF][action LE2][00 01][node 4 ASCII][len BE2][format 00][data]
 *   where len counts the data only (the format byte is not counted).
 */
object Xk {
    const val HEAD_CONTROL = 0x30
    const val HEAD_MEDIA = 0x4A
    const val HEAD_CUSTOM = 0x2B

    const val ACTION_READ = 1
    const val ACTION_WRITE = 2
    const val ACTION_EXECUTE = 3

    fun crc16(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in from until to) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF
            }
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
        val isAck get() = operation == 0x0004 || operation == 0x0009
        val fragment get() = divide and 0x07

        /** The 4-letter node of a control frame, like "57B1", or "" for anything else. */
        val node: String
            get() = if (head == HEAD_CONTROL && payload.size >= 14 && !isAck)
                String(payload, 10, 4, Charsets.US_ASCII) else ""

        /** Reply layout after the node: [type 1][len 2][data]. */
        val replyType: Int get() = if (payload.size > 14) payload[14].toInt() and 0xFF else -1

        val data: ByteArray
            get() = if (payload.size > 17) payload.copyOfRange(17, payload.size) else ByteArray(0)

        /** Text of a JSON reply, if the reply carries one. */
        fun json(): String? {
            val start = payload.indexOf('{'.code.toByte())
            val end = payload.lastIndexOf('}'.code.toByte())
            return if (start >= 0 && end > start) String(payload, start, end - start + 1, Charsets.UTF_8) else null
        }

        fun encode(): ByteArray {
            val out = ByteArray(16 + payload.size)
            out[0] = head.toByte()
            out[1] = order.toByte()
            putLe16(out, 2, cmd)
            putLe16(out, 4, divide)
            putLe16(out, 6, payload.size)
            // 8..11 offset stays zero
            putLe16(out, 12, crc16(payload))
            // 14..15 reserved stays zero
            System.arraycopy(payload, 0, out, 16, payload.size)
            return out
        }

        override fun toString(): String {
            val n = node
            val kind = when {
                isAck -> "ack"
                head == HEAD_MEDIA -> "media part ${fragment}"
                head == HEAD_CUSTOM -> "custom"
                else -> n
            }
            return "${if (fromDevice) "←" else "→"} ${"%02X".format(head)} #${order} $kind (${payload.size} B)"
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

    /** Data for node 102E: bind with the owner's own LensMoo account id. */
    fun userBindData(userId: String, bindType: Int = 2): ByteArray {
        val id = userId.toByteArray(Charsets.US_ASCII)
        val out = ByteArray(1 + 16 + 1 + id.size)
        out[0] = bindType.toByte()
        // 16-byte random code stays zero, as in the working captures
        out[17] = id.size.toByte()
        id.copyInto(out, 18)
        return out
    }

    fun isValidUserId(s: String): Boolean = Regex("^[0-9a-f]{32}$").matches(s)

    private fun putLe16(b: ByteArray, at: Int, v: Int) {
        b[at] = (v and 0xFF).toByte()
        b[at + 1] = ((v shr 8) and 0xFF).toByte()
    }

    /** Splits a byte stream into frames, skipping noise and frames with a bad checksum. */
    class Parser {
        private var buf = ByteArray(64 * 1024)
        private var len = 0
        var dropped = 0
            private set

        fun feed(data: ByteArray, count: Int = data.size): List<Frame> {
            ensure(len + count)
            System.arraycopy(data, 0, buf, len, count)
            len += count
            val out = mutableListOf<Frame>()
            var pos = 0
            while (len - pos >= 16) {
                val h = buf[pos].toInt() and 0xFF
                if (h != HEAD_CONTROL && h != HEAD_MEDIA && h != HEAD_CUSTOM) { pos++; dropped++; continue }
                val plen = (buf[pos + 6].toInt() and 0xFF) or ((buf[pos + 7].toInt() and 0xFF) shl 8)
                if (len - pos < 16 + plen) break
                val crc = (buf[pos + 12].toInt() and 0xFF) or ((buf[pos + 13].toInt() and 0xFF) shl 8)
                if (crc16(buf, pos + 16, pos + 16 + plen) != crc) { pos++; dropped++; continue }
                val order = buf[pos + 1].toInt() and 0xFF
                val cmd = (buf[pos + 2].toInt() and 0xFF) or ((buf[pos + 3].toInt() and 0xFF) shl 8)
                val div = (buf[pos + 4].toInt() and 0xFF) or ((buf[pos + 5].toInt() and 0xFF) shl 8)
                out += Frame(h, order, cmd, div, buf.copyOfRange(pos + 16, pos + 16 + plen))
                pos += 16 + plen
            }
            if (pos > 0) {
                System.arraycopy(buf, pos, buf, 0, len - pos)
                len -= pos
            }
            return out
        }

        private fun ensure(n: Int) {
            if (n <= buf.size) return
            buf = buf.copyOf(maxOf(n, buf.size * 2))
        }
    }

    /**
     * Rebuilds a photo from its elements. Each element arrives as media frames: a lone frame
     * (fragment 0), or first/middle/last fragments that each start with a 4-byte index.
     * Every finished element starts with [media type 1][length LE4], which is removed.
     */
    class PhotoAssembler(private val count: Int) {
        private val elements = arrayOfNulls<ByteArray>(count + 1)
        private val current = ByteArrayOutputStream()
        private var active = 0
        private var nextIdx = 0L

        fun start(index: Int) {
            active = index
            current.reset()
            nextIdx = 0
        }

        /** Returns true when this frame finished the active element. */
        fun feed(f: Frame): Boolean {
            if (active == 0) return false
            when (f.fragment) {
                0 -> { store(f.payload); return true }
                1, 2, 3 -> {
                    if (f.payload.size < 4) error("short media fragment")
                    if (f.fragment == 1) { current.reset(); nextIdx = 0 }
                    val idx = (f.payload[0].toLong() and 0xFF) or ((f.payload[1].toLong() and 0xFF) shl 8) or
                        ((f.payload[2].toLong() and 0xFF) shl 16) or ((f.payload[3].toLong() and 0xFF) shl 24)
                    if (idx != nextIdx) error("media fragment out of order ($idx, wanted $nextIdx)")
                    nextIdx++
                    current.write(f.payload, 4, f.payload.size - 4)
                    if (f.fragment == 3) { store(current.toByteArray()); return true }
                    return false
                }
                else -> error("unknown fragment type ${f.fragment}")
            }
        }

        private fun store(raw: ByteArray) {
            if (raw.size < 5) error("element $active too small")
            elements[active] = raw.copyOfRange(5, raw.size)
            active = 0
        }

        fun jpeg(): ByteArray {
            val out = ByteArrayOutputStream()
            for (i in 1..count) out.write(elements[i] ?: error("missing part $i of $count"))
            val img = out.toByteArray()
            if (img.size < 4 || img[0] != 0xFF.toByte() || img[1] != 0xD8.toByte()) error("not a JPEG")
            return img
        }
    }
}
