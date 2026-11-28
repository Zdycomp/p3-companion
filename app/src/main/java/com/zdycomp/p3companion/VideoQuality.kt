package com.zdycomp.p3companion

import java.util.Locale

/**
 * 3.6: the opt-in "Try higher quality" probe for live video. Pure Kotlin (no android.*), so the JVM
 * tests run exactly what the app runs.
 *
 * The owner's E13C-5 opens live video with `5720` action 3 data `[01]` and sends 320×240 H.264.
 * Nothing in the protocol notes or the logs names a resolution setting: 2410 and 2420 hold the
 * translation languages ("en", "zh-cn"), so they are never written. What can be tried safely is other
 * data on the same open command, in case the firmware reads a second byte or a larger value as a
 * profile. Each one is opened for 3 seconds with the preview otherwise off, the SPS size, pictures a
 * second and kilobits a second are read, and the same node and action are closed with `[00]`.
 */
object VideoQuality {

    /** The open the app has always sent, measured first as the baseline. */
    val STANDARD: ByteArray = byteArrayOf(1)

    /** Other open data that might select a profile, tried in this order after the standard one. */
    val CANDIDATES: List<ByteArray> = listOf(
        byteArrayOf(1, 1), byteArrayOf(1, 2), byteArrayOf(1, 3), byteArrayOf(2), byteArrayOf(3),
    )

    /** The close for every open, whatever data opened it. */
    val CLOSE: ByteArray = byteArrayOf(0)

    /** How long each setting is listened to. */
    const val LISTEN_MS = 3_000L

    /** Least pictures a second for a "High" choice to be worth offering. */
    const val MIN_FPS = 15.0

    /** The size the glasses are known to send with the standard open. */
    val NATIVE = 320 to 240

    /** What one open did. [code] is the glasses' status (null or 0 = accepted). */
    data class Result(
        val data: ByteArray,
        val code: Int?,
        val answered: Boolean,
        val width: Int?,
        val height: Int?,
        val pictures: Long,
        val bytes: Long,
        val listenedMs: Long,
    ) {
        val accepted: Boolean get() = answered && (code == null || code == 0)
        val fps: Double get() = if (listenedMs > 0) pictures * 1000.0 / listenedMs else 0.0
        val kbps: Double get() = if (listenedMs > 0) bytes * 8.0 / listenedMs else 0.0
        val pixels: Int get() = (width ?: 0) * (height ?: 0)
        val size: Pair<Int, Int>? get() = if (width != null && height != null) width to height else null
    }

    /**
     * Counts what arrives in one listening window (reader thread). Every picture is fed with its SPS
     * when it carries one; the last size seen is the window's size.
     */
    class Meter {
        @Volatile var pictures = 0L; private set
        @Volatile var bytes = 0L; private set
        @Volatile var size: Pair<Int, Int>? = null; private set

        fun feed(sps: ByteArray?, picture: Boolean, length: Int) {
            bytes += length
            if (picture) pictures++
            sps?.let { H264.spsSize(it) }?.let { size = it }
        }

        fun result(data: ByteArray, code: Int?, answered: Boolean, listenedMs: Long) =
            Result(data, code, answered, size?.first, size?.second, pictures, bytes, listenedMs)
    }

    /**
     * The best setting bigger than the standard one at [MIN_FPS] or more, or null when none is.
     * Ties on size go to the higher picture rate. The standard open itself is never "better".
     */
    fun best(standard: Result?, results: List<Result>): Result? {
        val base = standard?.pixels?.takeIf { it > 0 } ?: (NATIVE.first * NATIVE.second)
        return results
            .filter { it.accepted && it.size != null && it.pixels > base && it.fps >= MIN_FPS && !it.data.contentEquals(STANDARD) }
            .maxWithOrNull(compareBy<Result>({ it.pixels }, { it.fps }))
    }

    // ---------------- 4.1.1: the detail (bitrate) ladder ----------------

    /**
     * 4.1.1: open data by detail, most first. The owner's 4.1.0 quality test on the E13C-5 (fw 1.0.0):
     * `[01]` 320×240 10.7 fps 162 kbps, `[01 01]` 14.0 fps 180 kbps, `[01 02]` 15.7 fps 199 kbps,
     * `[01 03]` 15.3 fps 203 kbps; `[02]` and `[03]` accepted with no video. The second byte reads as a
     * bitrate level: more bits for each picture at the same size, so less blur from compression.
     */
    val LADDER: List<ByteArray> = listOf(byteArrayOf(1, 3), byteArrayOf(1, 2), byteArrayOf(1, 1), STANDARD)

    /** How long an accepted open may go without a picture before the next setting down is tried. */
    const val NO_VIDEO_MS = 3_000L

    /** A short plain name for a ladder setting ("most detail"), or null for other data. */
    fun detailName(data: ByteArray?): String? = when (data?.let { d -> LADDER.indexOfFirst { it.contentEquals(d) } }) {
        0 -> "most detail"
        1 -> "high detail"
        2 -> "medium detail"
        3 -> "basic"
        else -> null
    }

    /** What one open with one setting came to. */
    enum class Outcome {
        /** Pictures arrived: this setting is used and remembered. */
        VIDEO,
        /** The glasses refused the data (any status but busy): try the next setting down. */
        REFUSED,
        /** Accepted, but no picture within [NO_VIDEO_MS]: close `[00]` and try the next setting down. */
        NO_VIDEO,
        /** The glasses are busy (mic or call): not about the setting, so stop and remember nothing. */
        BUSY,
        /** The person left or the link dropped: stop. */
        STOPPED,
    }

    /**
     * 4.1.1: the order the live view tries open data in, and what to remember. [high] (Quality set to
     * High, a bigger picture the probe found) goes first; then the ladder from [remembered] down (the
     * whole ladder from `[01 03]` when nothing is remembered or the remembered data isn't on it).
     * Pure state, so the JVM tests run exactly what the app runs.
     */
    class OpenLadder(high: ByteArray?, remembered: ByteArray?) {
        val order: List<ByteArray>
        var index = 0; private set
        /** The ladder setting that gave pictures, to save; null when none did (or it was the High data). */
        var remember: ByteArray? = null; private set

        init {
            val start = remembered?.let { r -> LADDER.indexOfFirst { it.contentEquals(r) } }?.takeIf { it >= 0 } ?: 0
            val rest = LADDER.drop(start)
            order = (listOfNotNull(high) + rest).fold(mutableListOf()) { acc, d -> if (acc.none { it.contentEquals(d) }) acc += d; acc }
        }

        val current: ByteArray get() = order[index]
        val isLast: Boolean get() = index >= order.size - 1

        /** After [current] came to [o]: the next data to open with, or null to stay with what's open (or stop). */
        fun next(o: Outcome): ByteArray? = when (o) {
            Outcome.VIDEO -> { if (LADDER.any { it.contentEquals(current) }) remember = current; null }
            Outcome.REFUSED, Outcome.NO_VIDEO -> if (isLast) null else { index++; current }
            Outcome.BUSY, Outcome.STOPPED -> null
        }
    }

    /**
     * 4.1.1: what "Try higher quality" teaches the live view: the most-detail ladder setting that was
     * accepted and sent pictures, or null when none did (the remembered setting is then left alone).
     */
    fun detailFromProbe(standard: Result?, results: List<Result>): ByteArray? {
        val worked = (listOfNotNull(standard) + results).filter { it.accepted && it.pictures > 0 }
        return LADDER.firstOrNull { d -> worked.any { it.data.contentEquals(d) } }
    }

    /** One sentence for the report: which setting the live view now opens with. */
    fun detailSentence(standard: Result?, results: List<Result>): String {
        val d = detailFromProbe(standard, results) ?: return "No setting sent pictures, so the live view keeps its setting."
        val r = (listOfNotNull(standard) + results).first { it.data.contentEquals(d) }
        return "Live view opens with [${hex(d)}] (${detailName(d)}, %.0f kbps).".format(Locale.US, r.kbps)
    }

    fun hex(b: ByteArray): String = b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    /** "01 02" back to bytes; null for anything that isn't 1 to 4 hex bytes. */
    fun parseHex(s: String?): ByteArray? {
        val parts = s?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: return null
        if (parts.isEmpty() || parts.size > 4) return null
        return runCatching { ByteArray(parts.size) { i -> require(parts[i].length == 2); parts[i].toInt(16).toByte() } }.getOrNull()
    }

    /** "640x480" for prefs, and back. */
    fun formatSize(s: Pair<Int, Int>?): String? = s?.let { "${it.first}x${it.second}" }
    fun parseSize(s: String?): Pair<Int, Int>? {
        val m = Regex("^(\\d{2,4})x(\\d{2,4})$").matchEntire(s?.trim() ?: return null) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    /** One log line per setting. */
    fun line(r: Result, node: String = "5720", action: Int = 3): String {
        val head = "$node/$action [${hex(r.data)}]"
        return when {
            !r.answered -> "$head: no answer"
            !r.accepted -> "$head: refused (status 0x%02X)".format(r.code ?: 0)
            r.pictures == 0L -> "$head: accepted, no video in %.1f s".format(Locale.US, r.listenedMs / 1000.0)
            else -> "$head: accepted, " + (r.size?.let { "${it.first}×${it.second}" } ?: "size not seen") +
                ", %.1f fps, %.0f kbps, %d pictures".format(Locale.US, r.fps, r.kbps, r.pictures)
        }
    }

    /** One plain sentence for the screen. */
    fun summary(standard: Result?, results: List<Result>): String {
        val b = best(standard, results)
        val tried = results.size + if (standard != null) 1 else 0
        val std = standard?.size?.let { "${it.first}×${it.second}" } ?: "320×240"
        return when {
            b != null -> "Higher quality found: [${hex(b.data)}] gives ${b.width}×${b.height} at %.0f fps. Pick High under Quality to use it.".format(Locale.US, b.fps)
            results.any { it.accepted && it.pixels > (standard?.pixels ?: 0) && it.pixels > 0 } ->
                "A bigger picture came with one setting, but under ${MIN_FPS.toInt()} pictures a second, so it isn't offered. The glasses send $std for live video."
            else -> "The glasses send $std only: $tried settings were tried and none gave a bigger picture."
        }
    }
}
