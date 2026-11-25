package com.zdycomp.p3companion

import java.util.Locale

/**
 * 3.8 "Find full-size photos" (docs/FULL-RES-PLAN.md section 2): the pure parts, JVM-tested. Which
 * requests the probe may send (a hard allow-list, checked in code before every send), which index
 * tries follow the read pass, and how replies and the result are written. The link work is
 * [XkGlasses.runFullResProbe].
 */
object FullRes {

    /** Never sent, whatever else changes: delete photo, the OTA channel and 9000/9001. */
    val DENY_NODES = setOf("7400", "102A", "102C", "5610", "5620", "9000", "9001")

    /** Action 1 (read) is allowed only on these. */
    val READ_NODES = setOf("7110", "1001", "5712", "5713", "7200", "7310", "7600")
    /** Action 2 only on these: 7300 [N+1] during a transfer, and the index pass. */
    val WRITE_NODES = setOf("7300", "7200", "7310", "7600")
    /** Action 3 only on the existing photo and release commands. */
    val EXEC_NODES = setOf("57B0", "7500")

    /** The album/transfer nodes the read pass asks, in order. */
    val READ_PASS = listOf("7200", "7310", "7600")

    const val ERR_INVALID_URN = 4

    /** True when the probe may send this request. Everything else is refused before it reaches the link. */
    fun allowed(node: String, action: Int, data: ByteArray = ByteArray(0)): Boolean {
        if (node.length != 4 || node in DENY_NODES) return false
        if (node == "1017" || node == "102E") return false // no 1017 write, no bind of any type
        return when (action) {
            Xk.ACTION_READ -> node in READ_NODES && (data.isEmpty() || (data.size == 1 && data[0].toInt() == 0))
            Xk.ACTION_WRITE -> node in WRITE_NODES && data.size in 1..2
            Xk.ACTION_EXECUTE -> node in EXEC_NODES && data.isEmpty()
            else -> false
        }
    }

    /** "7310/2 00 01", "7200/1", "7200/1 00": a request's name in the log and in the dropped list. */
    fun key(node: String, action: Int, data: ByteArray): String =
        "$node/$action" + if (data.isNotEmpty()) " " + XkGlasses.hex(data) else ""

    /** What a node said in the read pass (two reads: no data, then [00]). */
    data class ReadOutcome(val answered: Boolean, val errCode: Int?)

    /**
     * Step 5: the index tries for [node] after its read pass. 7200 and 7310 get `[00]`, `[01]`,
     * `[00 01]` and, when 5713 said `photo_num` = n > 0, `[n]` — unless a read said INVALID_URN (04).
     * 7600 gets only `[00]`, and only when it answered with something other than INVALID_URN.
     */
    fun indexTries(node: String, reads: List<ReadOutcome>, photoNum: Int?): List<ByteArray> {
        val urn = reads.any { it.errCode == ERR_INVALID_URN }
        return when (node) {
            "7200", "7310" -> if (urn) emptyList() else buildList {
                add(byteArrayOf(0)); add(byteArrayOf(1)); add(byteArrayOf(0, 1))
                if (photoNum != null && photoNum in 2..255) add(byteArrayOf(photoNum.toByte()))
            }
            "7600" -> if (!urn && reads.any { it.answered }) listOf(byteArrayOf(0)) else emptyList()
            else -> emptyList()
        }
    }

    /** 7300 action 2 with index N+1, sent once during the baseline transfer; null when N is already the last index. */
    fun extraPartIndex(count: Int): ByteArray? = if (count in 1 until Xk.MAX_ELEMENTS) byteArrayOf((count + 1).toByte()) else null

    /** Reply in plain words: type, status, bytes. [data] is the reply's data, [json] its text if any. */
    fun replyText(type: Int?, errCode: Int?, data: ByteArray, json: String?): String {
        if (type == null) return "silent"
        val head = "type %d (%s), %d B".format(Locale.US, type, typeName(type), data.size)
        return when {
            type == Xk.FMT_ERRCODE && errCode != null ->
                "$head, status %02X %s".format(Locale.US, errCode, errName(errCode))
            type == Xk.FMT_JSON && json != null -> "$head, JSON $json"
            data.isNotEmpty() -> "$head: " + XkGlasses.hex(data.take(64).toByteArray()) + if (data.size > 64) " …" else ""
            else -> head
        }
    }

    fun typeName(t: Int): String = when (t) {
        Xk.FMT_BIN -> "binary"
        Xk.FMT_JSON -> "JSON"
        Xk.FMT_NODATA -> "no data"
        Xk.FMT_ERRCODE -> "status"
        else -> "unknown"
    }

    /** The SDK's names for the status codes, as in the plan. */
    fun errName(code: Int): String = when (code) {
        0 -> "OK"
        1 -> "FAILED"
        2 -> "NO_DATA"
        3 -> "INVALID_PARAM"
        4 -> "INVALID_URN"
        5 -> "INVALID_DATA"
        else -> "code $code"
    }

    /** What arrived on the media channel while one request was open. */
    data class Media(val frames: Int, val bytes: Long, val largest: Int, val elements: Int, val jpeg: JpegInfo.Info?, val jpegBytes: Int) {
        val any: Boolean get() = frames > 0 || elements > 0
        fun text(): String = if (!any) "no media" else buildString {
            append("$frames media frame(s), $bytes B, largest $largest B")
            if (elements > 0) append(", $elements element(s)")
            if (jpegBytes > 0) append(", JPEG ${JpegInfo.headline(jpeg, jpegBytes.toLong()).removePrefix("Photo ")}")
        }
    }

    /** One line of the per-node summary: "7310/2 01: status 03 INVALID_PARAM" / "silent" / "photo 1280x960, 212 KB". */
    fun nodeLine(key: String, type: Int?, errCode: Int?, json: String?, dataSize: Int, photo: JpegInfo.Info?, photoBytes: Long): String {
        val what = when {
            photoBytes > 0 -> "photo " + JpegInfo.headline(photo, photoBytes).removePrefix("Photo ")
            type == null -> "silent"
            type == Xk.FMT_ERRCODE && errCode != null -> "status %02X %s".format(Locale.US, errCode, errName(errCode))
            type == Xk.FMT_JSON && json != null -> "JSON " + json.take(120)
            type == Xk.FMT_NODATA -> "no data"
            else -> "$dataSize B of ${typeName(type)}"
        }
        return "$key: $what"
    }

    /** A JPEG that arrived during the probe, other than the baseline photo. */
    data class Found(val key: String, val info: JpegInfo.Info?, val bytes: Long)

    /**
     * The one plain line at the end. [baseline] is the Bluetooth photo's frame header, [found] every
     * other JPEG, [storage] what 5713/5712 said (rose / didn't / unknown), [complete] false when it stopped early.
     */
    fun summary(baseline: JpegInfo.Info?, baselineBytes: Long, found: List<Found>, photoNumRose: Boolean?, complete: Boolean): String {
        val bt = when {
            baseline != null -> "Bluetooth photos are ${baseline.size}"
            baselineBytes > 0 -> "Bluetooth photos are ${JpegInfo.kb(baselineBytes)} (size unknown)"
            else -> "No Bluetooth photo came in"
        }
        val bigger = found.filter { f ->
            val i = f.info
            when {
                i != null && baseline != null -> i.width.toLong() * i.height > baseline.width.toLong() * baseline.height
                else -> f.bytes > baselineBytes && baselineBytes > 0
            }
        }
        val tail = when {
            bigger.isNotEmpty() -> "; a larger copy came from " + bigger.joinToString(", ") {
                it.key + " (" + (it.info?.size ?: "size unknown") + ", " + JpegInfo.kb(it.bytes) + ")"
            }
            found.isNotEmpty() -> "; other pictures came (" + found.joinToString(", ") { it.key + " " + (it.info?.size ?: JpegInfo.kb(it.bytes)) } +
                ") but none is larger"
            else -> "; no larger copy found"
        }
        val store = when (photoNumRose) {
            true -> ". The photo count rose, so the glasses may keep photos"
            false -> ". The photo count didn't rise: the glasses keep no photos"
            null -> ""
        }
        val end = if (complete) "." else ". The probe stopped early."
        return bt + tail + store + end
    }

    /** A number out of a 5713/5712 JSON reply ("photo_num":"3" or 3). */
    fun jsonNumber(json: String?, key: String): Long? {
        if (json == null) return null
        val m = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"?\\s*(-?\\d+)").find(json) ?: return null
        return m.groupValues[1].toLongOrNull()
    }

    /** Did the photo count rise (true), stay (false), or can't we tell (null)? */
    fun rose(before: Long?, after: Long?): Boolean? = if (before == null || after == null) null else after > before

    /**
     * The dropped list after a run starts: a request still marked as in flight ([current]) means the
     * link dropped on it last time, so it is added and skipped from now on.
     */
    fun droppedAfterStart(dropped: Set<String>, current: String?): Set<String> =
        if (current.isNullOrBlank()) dropped else dropped + current
}
