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

    // ---------------- 4.1.1: the detail (bitrate) ladder; 4.1.2: Smooth first ----------------

    /**
     * 4.1.2: the open data in the order the live view tries them, Smooth (the default) first. The owner's 4.1.1
     * quality test on the E13C-5 (fw 1.0.0): `[01]` 320×240 30.0 fps 378 kbps (12.6 kbit a picture), `[01 01]`
     * 16.0 fps 221 kbps (13.8), `[01 02]` 16.7 fps 238 kbps (14.3), `[01 03]` 16.0 fps 233 kbps (14.6). The second
     * byte caps the rate near 16 fps for only ~15% more bits a picture, so plain `[01]` goes first and the
     * second-byte settings are the fallbacks, most detail first (4.1.1 opened with `[01 03]`).
     */
    val LADDER: List<ByteArray> = listOf(STANDARD, byteArrayOf(1, 3), byteArrayOf(1, 2), byteArrayOf(1, 1))

    /** 4.1.2: the order when Settings › Live view is set to Detail: `[01 03]` first, stepping down to `[01]`. */
    val DETAIL_LADDER: List<ByteArray> = listOf(byteArrayOf(1, 3), byteArrayOf(1, 2), byteArrayOf(1, 1), STANDARD)

    /** The ladder for the Settings choice: Detail ([detail] true) or Smooth. */
    fun ladder(detail: Boolean): List<ByteArray> = if (detail) DETAIL_LADDER else LADDER

    /** How long an accepted open may go without a picture before the next setting down is tried. */
    const val NO_VIDEO_MS = 3_000L

    /** A short plain name for a ladder setting ("smooth", "most detail"), or null for other data. */
    fun detailName(data: ByteArray?): String? = when (data?.let { d -> DETAIL_LADDER.indexOfFirst { it.contentEquals(d) } }) {
        0 -> "most detail"
        1 -> "high detail"
        2 -> "medium detail"
        3 -> "smooth"
        else -> null
    }

    /** True for a second-byte (detail) setting, `[01 0x]`. */
    fun isDetailData(data: ByteArray?): Boolean = data != null && data.size == 2 && data[0] == STANDARD[0]

    /**
     * 4.1.2 migration: 4.1.1 remembered `[01 03]` (or the next one down) whenever it gave pictures. Unless the person
     * chose Detail in Settings, that becomes `[01]` so the live view opens Smooth again; anything else is kept.
     */
    fun migrateRemembered(remembered: ByteArray?, detailChosen: Boolean): ByteArray? =
        if (!detailChosen && isDetailData(remembered)) STANDARD.copyOf() else remembered

    /**
     * 4.1.2: a line for Sharp snapshot when the live view runs a detail setting: its 16 pictures then span about a
     * second instead of half a second, so Smooth suits it better. Null when the live view runs Smooth (or isn't open).
     */
    fun sharpSnapshotHint(open: ByteArray?): String? =
        if (isDetailData(open)) "Smooth (Settings › Capture › Live view) gives Sharp snapshot about twice the pictures a second." else null

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
     * whole ladder from its top when nothing is remembered or the remembered data isn't on it). 4.1.2: the
     * ladder is Smooth's (`[01]` first) unless [detail] (Settings › Live view › Detail, `[01 03]` first).
     * Pure state, so the JVM tests run exactly what the app runs.
     */
    class OpenLadder(high: ByteArray?, remembered: ByteArray?, detail: Boolean = false) {
        val order: List<ByteArray>
        var index = 0; private set
        /** The ladder setting that gave pictures, to save; null when none did (or it was the High data). */
        var remember: ByteArray? = null; private set

        init {
            val steps = ladder(detail)
            val start = remembered?.let { r -> steps.indexOfFirst { it.contentEquals(r) } }?.takeIf { it >= 0 } ?: 0
            val rest = steps.drop(start)
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
     * 4.1.1: what "Try higher quality" teaches the live view: the first setting of the chosen ladder (4.1.2: Smooth's
     * `[01]` first, or Detail's `[01 03]` first) that was accepted and sent pictures, or null when none did (the
     * remembered setting is then left alone).
     */
    fun detailFromProbe(standard: Result?, results: List<Result>, detail: Boolean = false): ByteArray? {
        val worked = (listOfNotNull(standard) + results).filter { it.accepted && it.pictures > 0 }
        return ladder(detail).firstOrNull { d -> worked.any { it.data.contentEquals(d) } }
    }

    /** One sentence for the report: which setting the live view now opens with. */
    fun detailSentence(standard: Result?, results: List<Result>, detail: Boolean = false): String {
        val d = detailFromProbe(standard, results, detail) ?: return "No setting sent pictures, so the live view keeps its setting."
        val r = (listOfNotNull(standard) + results).first { it.data.contentEquals(d) }
        return "Live view opens with [${hex(d)}] (${detailName(d)}, %.0f kbps).".format(Locale.US, r.kbps)
    }

    /** Kilobits a picture (0 with no pictures). */
    fun kbitPerFrame(r: Result): Double = if (r.fps > 0) r.kbps / r.fps else 0.0

    /**
     * 4.1.2: Smooth against Detail by pictures a second × kilobits a picture (the picture data a second). Smooth
     * is the `[01]` result; Detail is the `[01 0x]` result with the most bits a picture. Null unless both sent
     * pictures.
     */
    fun recommendation(standard: Result?, results: List<Result>): String? {
        val all = (listOfNotNull(standard) + results).filter { it.accepted && it.pictures > 0 }
        val smooth = all.firstOrNull { it.data.contentEquals(STANDARD) } ?: return null
        val det = all.filter { isDetailData(it.data) }.maxByOrNull { kbitPerFrame(it) } ?: return null
        val sk = kbitPerFrame(smooth)
        val dk = kbitPerFrame(det)
        val pct = if (sk > 0) Math.round((dk / sk - 1) * 100).toInt() else 0
        val frames = if (smooth.fps >= det.fps) "Smooth gives the most frames" else "Detail gave more frames this time"
        val more = if (pct > 0) "Detail gives ~$pct% more per frame" else "Detail gives no more per frame"
        val pick = if (smooth.fps * sk >= det.fps * dk) "Smooth" else "Detail"
        fun n(v: Double) = "%.1f".format(Locale.US, v)
        return "Smooth [${hex(smooth.data)}] ${n(smooth.fps)} fps × ${n(sk)} kbit, Detail [${hex(det.data)}] ${n(det.fps)} fps × ${n(dk)} kbit: " +
            "$frames; $more. Recommended by fps × bits: $pick."
    }

    /** 4.1.2: the quality test's live-view line: the recommendation (when there is one), then the setting used. */
    fun detailSummary(standard: Result?, results: List<Result>, detail: Boolean = false): String =
        listOfNotNull(recommendation(standard, results), detailSentence(standard, results, detail)).joinToString(" ")

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
