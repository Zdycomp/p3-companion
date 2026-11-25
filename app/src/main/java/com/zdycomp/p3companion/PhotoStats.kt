package com.zdycomp.p3companion

import java.util.Locale

/**
 * 3.8: photo sizes from the cable and from Bluetooth, so the owner can compare them in one line:
 * "Cable photos: 1280x960 avg 400 KB; Bluetooth photos: 640x480 avg 70 KB". Pure Kotlin.
 */
object PhotoStats {

    /** One photo: pixel size (0 x 0 when unknown) and file size. */
    data class Size(val width: Int, val height: Int, val bytes: Long) {
        val known: Boolean get() = width > 0 && height > 0
        val pixels: String get() = if (known) "${width}x$height" else "size unknown"
    }

    /** How many of each kind are kept (the newest). */
    const val KEEP = 30

    /** "640x480x71234;1280x960x401000" ↔ sizes. Bad entries are dropped. */
    fun encode(list: List<Size>): String = list.takeLast(KEEP).joinToString(";") { "${it.width}x${it.height}x${it.bytes}" }

    fun decode(s: String?): List<Size> = s.orEmpty().split(';').mapNotNull { e ->
        val p = e.trim().split('x')
        if (p.size != 3) return@mapNotNull null
        val w = p[0].toIntOrNull() ?: return@mapNotNull null
        val h = p[1].toIntOrNull() ?: return@mapNotNull null
        val b = p[2].toLongOrNull() ?: return@mapNotNull null
        if (w < 0 || h < 0 || b < 0) null else Size(w, h, b)
    }

    /** [old] plus [add], newest last, at most [KEEP]. */
    fun append(old: List<Size>, add: List<Size>): List<Size> = (old + add).takeLast(KEEP)

    /** The most common pixel size ("640x480"), "mixed: 640x480, 1280x960" when no size has a majority, or null. */
    fun typicalSize(list: List<Size>): String? {
        val known = list.filter { it.known }
        if (known.isEmpty()) return null
        val groups = known.groupingBy { it.pixels }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        if (groups.size == 1) return groups[0].key
        return if (groups[0].value * 2 > known.size) "mostly ${groups[0].key}"
        else "mixed: " + groups.take(3).joinToString(", ") { it.key }
    }

    /** Average file size in whole KB (rounded), or null for an empty list. */
    fun averageKb(list: List<Size>): Long? = if (list.isEmpty()) null else (list.sumOf { it.bytes } + list.size * 512L) / (list.size * 1024L)

    /** "1280x960 avg 400 KB" / "size unknown, avg 70 KB" / null when there are none. */
    fun describe(list: List<Size>): String? {
        val kb = averageKb(list) ?: return null
        val size = typicalSize(list)
        return if (size != null) "$size avg $kb KB" else "size unknown, avg $kb KB"
    }

    /** The comparison line. Either side may be empty; null when both are. */
    fun summary(cable: List<Size>, bluetooth: List<Size>): String? {
        val c = describe(cable)
        val b = describe(bluetooth)
        if (c == null && b == null) return null
        return "Cable photos: ${c ?: "none yet"}; Bluetooth photos: ${b ?: "none yet"}"
    }

    /** "1280x960 is 4.0x the pixels of 640x480", when both sides have one clear size. */
    fun ratio(cable: List<Size>, bluetooth: List<Size>): String? {
        fun one(l: List<Size>) = l.filter { it.known }.groupBy { it.width to it.height }.maxByOrNull { it.value.size }?.key
        val c = one(cable) ?: return null
        val b = one(bluetooth) ?: return null
        val r = c.first.toDouble() * c.second / (b.first.toDouble() * b.second)
        return String.format(Locale.US, "%dx%d is %.1fx the pixels of %dx%d", c.first, c.second, r, b.first, b.second)
    }
}
