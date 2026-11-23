package com.zdycomp.p3companion

import java.io.ByteArrayOutputStream

/**
 * Small H.264 helpers for the live view: split Annex-B into NAL units and read the picture size from
 * an SPS. Pure Kotlin so it can be unit tested. Written from the H.264 spec (ITU-T H.264 7.3.2.1).
 */
object H264 {
    fun type(b: Byte) = b.toInt() and 0x1F

    /** NAL unit ranges (without start codes). Null unless the data begins with a start code. */
    fun nals(a: ByteArray): List<IntRange>? {
        if (a.size < 4 || a[0].toInt() != 0 || a[1].toInt() != 0) return null
        val out = ArrayList<IntRange>()
        var i = 0
        var s = -1
        while (i + 2 < a.size) {
            if (a[i].toInt() == 0 && a[i + 1].toInt() == 0 && a[i + 2].toInt() == 1) {
                if (s >= 0) {
                    var e = i
                    while (e > s && a[e - 1].toInt() == 0) e--
                    if (e > s) out += s until e
                }
                s = i + 3
                i += 3
            } else i++
        }
        if (s in 0 until a.size) out += s until a.size
        return out.takeIf { it.isNotEmpty() }
    }

    private fun rbsp(n: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(n.size)
        var zeros = 0
        for (i in 1 until n.size) {
            val v = n[i].toInt() and 0xFF
            if (zeros >= 2 && v == 3) { zeros = 0; continue }
            o.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return o.toByteArray()
    }

    private class Bits(val b: ByteArray) {
        var p = 0
        fun u(n: Int): Int {
            var v = 0
            repeat(n) {
                require(p < b.size * 8) { "short SPS" }
                v = (v shl 1) or ((b[p ushr 3].toInt() ushr (7 - (p and 7))) and 1)
                p++
            }
            return v
        }
        fun ue(): Int {
            var z = 0
            while (u(1) == 0) require(++z <= 30) { "bad ue" }
            return (1 shl z) - 1 + u(z)
        }
        fun se(): Int { val k = ue(); return if (k and 1 == 1) (k + 1) / 2 else -(k / 2) }
    }

    /** Display width and height from an SPS NAL (header byte included, still escaped). */
    fun spsSize(sps: ByteArray): Pair<Int, Int>? = runCatching {
        require(sps.isNotEmpty() && type(sps[0]) == 7)
        val r = Bits(rbsp(sps))
        val profile = r.u(8)
        r.u(16)
        r.ue()
        var chroma = 1
        if (profile in intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
            chroma = r.ue()
            if (chroma == 3) r.u(1)
            r.ue(); r.ue(); r.u(1)
            if (r.u(1) == 1) repeat(if (chroma == 3) 12 else 8) { i ->
                if (r.u(1) == 1) {
                    var last = 8
                    var next = 8
                    repeat(if (i < 6) 16 else 64) {
                        if (next != 0) next = (last + r.se() + 256) % 256
                        if (next != 0) last = next
                    }
                }
            }
        }
        r.ue()
        when (r.ue()) {
            0 -> r.ue()
            1 -> { r.u(1); r.se(); r.se(); repeat(r.ue().also { require(it <= 255) }) { r.se() } }
        }
        r.ue(); r.u(1)
        val wMbs = r.ue() + 1
        val hMap = r.ue() + 1
        val frameOnly = r.u(1)
        if (frameOnly == 0) r.u(1)
        r.u(1)
        var l = 0; var rt = 0; var t = 0; var bt = 0
        if (r.u(1) == 1) { l = r.ue(); rt = r.ue(); t = r.ue(); bt = r.ue() }
        val cx = if (chroma == 1 || chroma == 2) 2 else 1
        val cy = (if (chroma == 1) 2 else 1) * (2 - frameOnly)
        val w = wMbs * 16 - cx * (l + rt)
        val h = hMap * 16 * (2 - frameOnly) - cy * (t + bt)
        require(w in 16..4096 && h in 16..4096)
        w to h
    }.getOrNull()
}

/** One access unit of live video. [annexB] is owned (already copied off the reader). */
class VideoAu(val annexB: ByteArray, val arrivalNs: Long) {
    private val units = H264.nals(annexB) ?: emptyList()
    val nalTypes: List<Int> = units.map { H264.type(annexB[it.first]) }
    val sps: ByteArray? = units.firstOrNull { H264.type(annexB[it.first]) == 7 }?.let { annexB.copyOfRange(it.first, it.last + 1) }
    val pps: ByteArray? = units.firstOrNull { H264.type(annexB[it.first]) == 8 }?.let { annexB.copyOfRange(it.first, it.last + 1) }
    val key: Boolean = nalTypes.contains(5)
    val picture: Boolean = nalTypes.any { it == 1 || it == 5 }

    /** A muxer sample: everything but SPS, PPS and AUD, each NAL with a 4-byte start code. */
    fun sample(): ByteArray {
        val o = ByteArrayOutputStream(annexB.size)
        for (u in units) {
            val t = H264.type(annexB[u.first])
            if (t == 7 || t == 8 || t == 9) continue
            o.write(START)
            o.write(annexB, u.first, u.last - u.first + 1)
        }
        return o.toByteArray()
    }

    companion object {
        val START = byteArrayOf(0, 0, 0, 1)

        /**
         * A video element is [02][length LE4][Annex-B]. The length excludes the 5-byte header in the
         * known captures; the photo-style "including" reading is accepted too. If the data doesn't start
         * with a start code, the first one found is used (covers an unknown prefix).
         */
        fun fromElement(e: ByteArray, arrivalNs: Long): VideoAu? {
            if (e.size < 10 || e.size > 2 * 1024 * 1024 || e[0].toInt() != Xk.MEDIA_H264) return null
            val len = Xk.le32(e, 1)
            var body = if (len == e.size - 5L || len == e.size.toLong()) e.copyOfRange(5, e.size) else null
            if (body == null || H264.nals(body) == null) {
                val at = findStart(e, 1) ?: return null
                body = e.copyOfRange(at, e.size)
            }
            return VideoAu(body, arrivalNs).takeIf { it.picture || it.sps != null }
        }

        /**
         * 3.3: H.264 behind an unknown header (another element type, a 2B or unknown-head frame):
         * the first start code within the first 64 bytes, if what follows holds a picture or an SPS.
         */
        fun scan(e: ByteArray, arrivalNs: Long): VideoAu? {
            if (e.size < 8 || e.size > 2 * 1024 * 1024) return null
            val at = findStart(e, 0)?.takeIf { it <= 64 } ?: return null
            return VideoAu(e.copyOfRange(at, e.size), arrivalNs).takeIf { it.picture || it.sps != null }
        }

        private fun findStart(b: ByteArray, from: Int): Int? {
            var i = from
            while (i + 3 < b.size) {
                if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 &&
                    (b[i + 2].toInt() == 1 || (b[i + 2].toInt() == 0 && b[i + 3].toInt() == 1))) return i
                i++
            }
            return null
        }
    }
}
