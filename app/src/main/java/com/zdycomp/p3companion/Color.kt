package com.zdycomp.p3companion

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The app's color system, in plain Kotlin so it runs in JVM unit tests (no android imports).
 * Colors are packed ARGB ints, like @ColorInt.
 *
 * "Tone" is CIELAB L* (0 black … 100 white). A tonal palette is one hue and chroma at every tone, which
 * is what Android 12+ builds from the wallpaper (system_accent1_0 … system_accent1_1000). The same role
 * mapping is used for the wallpaper palettes and for the app's own accent presets.
 */

/** Tone generator and color math (DESIGN-SPEC 1.3, 1.4). */
object Tonal {
    private const val EPS = 216.0 / 24389.0
    private const val KAPPA = 24389.0 / 27.0
    private const val XN = 0.95047
    private const val YN = 1.0
    private const val ZN = 1.08883

    /** The 13 palette steps Android uses, and the tone each one stands for. */
    val SUFFIXES = intArrayOf(0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000)
    val SUFFIX_TONES = intArrayOf(100, 99, 95, 90, 80, 70, 60, 50, 40, 30, 20, 10, 0)

    fun argb(r: Int, g: Int, b: Int, a: Int = 255): Int =
        (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun red(c: Int) = (c shr 16) and 0xFF
    fun green(c: Int) = (c shr 8) and 0xFF
    fun blue(c: Int) = c and 0xFF
    fun alpha(c: Int) = (c ushr 24) and 0xFF

    fun withAlpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    private fun lin(c8: Int): Double {
        val c = c8 / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun gamma(l: Double): Int {
        val c = l.coerceIn(0.0, 1.0)
        val v = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055
        return (v * 255.0).roundToInt().coerceIn(0, 255)
    }

    private fun f(t: Double) = if (t > EPS) cbrt(t) else (KAPPA * t + 16) / 116
    private fun finv(t: Double): Double { val t3 = t * t * t; return if (t3 > EPS) t3 else (116 * t - 16) / KAPPA }

    /** CIELAB of an sRGB color: [L, a, b]. */
    fun lab(c: Int): DoubleArray {
        val r = lin(red(c)); val g = lin(green(c)); val b = lin(blue(c))
        val x = 0.4124564 * r + 0.3575761 * g + 0.1804375 * b
        val y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
        val z = 0.0193339 * r + 0.1191920 * g + 0.9503041 * b
        val fx = f(x / XN); val fy = f(y / YN); val fz = f(z / ZN)
        return doubleArrayOf(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

    /** [L, C, h] with h in degrees, 0 until 360. */
    fun lch(c: Int): DoubleArray {
        val (l, a, b) = lab(c).let { Triple(it[0], it[1], it[2]) }
        var h = Math.toDegrees(atan2(b, a))
        if (h < 0) h += 360.0
        return doubleArrayOf(l, hypot(a, b), h)
    }

    fun tone(c: Int): Double = lab(c)[0]

    fun normHue(h: Double): Double { val m = h % 360.0; return if (m < 0) m + 360.0 else m }

    /** Lab → linear RGB, or null when it lies outside sRGB. */
    private fun labToLinear(l: Double, a: Double, b: Double): DoubleArray? {
        val fy = (l + 16) / 116
        val fx = fy + a / 500
        val fz = fy - b / 200
        val x = finv(fx) * XN
        val y = (if (l > KAPPA * EPS) fy * fy * fy else l / KAPPA) * YN
        val z = finv(fz) * ZN
        val r = 3.2404542 * x - 1.5371385 * y - 0.4985314 * z
        val g = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z
        val bl = 0.0556434 * x - 0.2040259 * y + 1.0572252 * z
        val lo = -0.0001; val hi = 1.0001
        if (r < lo || r > hi || g < lo || g > hi || bl < lo || bl > hi) return null
        return doubleArrayOf(r, g, bl)
    }

    /** The most colorful in-gamut sRGB color at [tone] and [hue], up to [chroma]. */
    fun colorAt(tone: Double, hue: Double, chroma: Double): Int {
        if (tone <= 0.0) return argb(0, 0, 0)
        if (tone >= 100.0) return argb(255, 255, 255)
        val h = Math.toRadians(normHue(hue))
        var lo = 0.0
        var hiC = max(0.0, chroma)
        var best = labToLinear(tone, 0.0, 0.0) ?: doubleArrayOf(tone / 100, tone / 100, tone / 100)
        // Full chroma may already fit.
        labToLinear(tone, hiC * cos(h), hiC * sin(h))?.let { return pack(it) }
        repeat(24) {
            val mid = (lo + hiC) / 2
            val rgb = labToLinear(tone, mid * cos(h), mid * sin(h))
            if (rgb != null) { best = rgb; lo = mid } else hiC = mid
        }
        return pack(best)
    }

    private fun pack(rgb: DoubleArray) = argb(gamma(rgb[0]), gamma(rgb[1]), gamma(rgb[2]))

    /** Per-channel sRGB blend: t = weight of [b]. Alpha comes from [a]. */
    fun mix(a: Int, b: Int, t: Double): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * t).roundToInt()
        return argb(ch(red(a), red(b)), ch(green(a), green(b)), ch(blue(a), blue(b)), alpha(a))
    }

    /** WCAG relative luminance. */
    fun luminance(c: Int): Double = 0.2126 * lin(red(c)) + 0.7152 * lin(green(c)) + 0.0722 * lin(blue(c))

    /** WCAG 2.x contrast ratio, 1 … 21. Alpha is ignored. */
    fun contrast(fg: Int, bg: Int): Double {
        val a = luminance(fg); val b = luminance(bg)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    /**
     * Returns [fg], or [fg] stepped toward black or white (whichever can reach more contrast) until it
     * reaches [min] against [bg]. Used as a runtime guard for unusual wallpaper palettes.
     */
    fun ensureContrast(fg: Int, bg: Int, min: Double): Int {
        if (contrast(fg, bg) >= min) return fg
        val towardWhite = contrast(0xFFFFFFFF.toInt(), bg) >= contrast(0xFF000000.toInt(), bg)
        val target = if (towardWhite) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        for (i in 1..20) {
            val c = mix(fg, target, i / 20.0)
            if (contrast(c, bg) >= min) return c
        }
        return target
    }

    /** One hue and chroma at every tone, cached. */
    class Palette(val hue: Double, val chroma: Double) {
        private val cache = HashMap<Int, Int>()

        /** Color at an exact tone 0..100. */
        fun tone(t: Int): Int = cache.getOrPut(t) { colorAt(t.toDouble(), hue, chroma) }

        /** Color at an Android palette suffix (0, 10, 50, 100 … 1000). */
        fun s(suffix: Int): Int = tone(suffixTone(suffix))

        fun toArray(): IntArray = IntArray(SUFFIXES.size) { s(SUFFIXES[it]) }
    }

    fun palette(hue: Double, chroma: Double) = Palette(normHue(hue), chroma)

    fun suffixTone(suffix: Int): Int {
        val i = SUFFIXES.indexOf(suffix)
        require(i >= 0) { "no palette suffix $suffix" }
        return SUFFIX_TONES[i]
    }

    /** Shifts a semantic hue part of the way toward the primary hue, so status colors sit well with any accent. */
    fun harmonize(semanticHue: Double, primaryHue: Double): Double {
        var d = normHue(primaryHue) - normHue(semanticHue)
        if (d > 180) d -= 360
        if (d < -180) d += 360
        val step = min(abs(d) * 0.5, 15.0)
        return normHue(semanticHue + Math.signum(d) * step)
    }
}

/** The five palettes a scheme is built from, each as 13 colors in [Tonal.SUFFIXES] order. */
class SchemeSource(
    val a1: IntArray,
    val a2: IntArray,
    val a3: IntArray,
    val n1: IntArray,
    val n2: IntArray,
) {
    init { listOf(a1, a2, a3, n1, n2).forEach { require(it.size == Tonal.SUFFIXES.size) } }

    companion object {
        /** The app's own palettes from one seed color (DESIGN-SPEC 1.3 step 6). */
        fun fromSeed(seed: Int): SchemeSource {
            val lch = Tonal.lch(seed)
            return fromHueChroma(lch[2], lch[1])
        }

        fun fromHueChroma(hue: Double, chroma: Double): SchemeSource {
            val c1 = if (chroma < 15) chroma else max(chroma, 48.0)
            return SchemeSource(
                Tonal.palette(hue, c1).toArray(),
                Tonal.palette(hue, 16.0).toArray(),
                Tonal.palette(hue + 60, 28.0).toArray(),
                Tonal.palette(hue, 4.0).toArray(),
                Tonal.palette(hue, 8.0).toArray(),
            )
        }
    }
}

/** Every color role the UI uses (DESIGN-SPEC 1.2). */
data class Scheme(
    val isDark: Boolean,
    val amoled: Boolean,
    val highContrast: Boolean,
    val primary: Int, val onPrimary: Int, val primaryContainer: Int, val onPrimaryContainer: Int, val inversePrimary: Int,
    val secondary: Int, val onSecondary: Int, val secondaryContainer: Int, val onSecondaryContainer: Int,
    val tertiary: Int, val onTertiary: Int, val tertiaryContainer: Int, val onTertiaryContainer: Int,
    val surface: Int,
    val surfaceContainerLowest: Int, val surfaceContainerLow: Int, val surfaceContainer: Int,
    val surfaceContainerHigh: Int, val surfaceContainerHighest: Int,
    val onSurface: Int, val onSurfaceVariant: Int,
    val outline: Int, val outlineVariant: Int,
    val inverseSurface: Int, val inverseOnSurface: Int,
    val scrim: Int,
    val error: Int, val onError: Int, val errorContainer: Int, val onErrorContainer: Int,
    val success: Int, val onSuccess: Int, val successContainer: Int, val onSuccessContainer: Int,
    val warning: Int, val onWarning: Int, val warningContainer: Int, val onWarningContainer: Int,
) {
    /** Every surface the text roles are drawn on. */
    val surfaces: List<Int>
        get() = listOf(surface, surfaceContainerLowest, surfaceContainerLow, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest)

    companion object {
        private const val BLACK = 0xFF000000.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val SUCCESS_SEED = 0xFF2E9D5B.toInt()
        private const val WARNING_SEED = 0xFFE0A100.toInt()

        /** Builds the scheme for one look, then guards every text pair so it always stays readable. */
        fun build(src: SchemeSource, dark: Boolean, amoled: Boolean = false, highContrast: Boolean = false): Scheme {
            fun a1(s: Int) = src.a1[Tonal.SUFFIXES.indexOf(s)]
            fun a2(s: Int) = src.a2[Tonal.SUFFIXES.indexOf(s)]
            fun a3(s: Int) = src.a3[Tonal.SUFFIXES.indexOf(s)]
            fun n1(s: Int) = src.n1[Tonal.SUFFIXES.indexOf(s)]
            fun n2(s: Int) = src.n2[Tonal.SUFFIXES.indexOf(s)]
            val black = dark && amoled

            val primaryHue = Tonal.lch(a1(if (dark) 200 else 600))[2]
            val sp = Tonal.palette(Tonal.harmonize(Tonal.lch(SUCCESS_SEED)[2], primaryHue), 40.0)
            val wp = Tonal.palette(Tonal.harmonize(Tonal.lch(WARNING_SEED)[2], primaryHue), 40.0)

            var primary = if (dark) a1(200) else a1(600)
            var onPrimary = if (dark) a1(800) else a1(0)
            var primaryContainer = if (dark) (if (black) a1(800) else a1(700)) else a1(100)
            var onPrimaryContainer = if (dark) a1(100) else a1(900)
            val inversePrimary = if (dark) a1(600) else a1(200)
            val secondary = if (dark) a2(200) else a2(600)
            val onSecondary = if (dark) a2(800) else a2(0)
            val secondaryContainer = if (dark) (if (black) a2(800) else a2(700)) else a2(100)
            val onSecondaryContainer = if (dark) a2(100) else a2(900)
            val tertiary = if (dark) a3(200) else a3(600)
            val onTertiary = if (dark) a3(800) else a3(0)
            val tertiaryContainer = if (dark) (if (black) a3(800) else a3(700)) else a3(100)
            val onTertiaryContainer = if (dark) a3(100) else a3(900)

            val surface: Int
            val lowest: Int
            val low: Int
            val mid: Int
            val high: Int
            val highest: Int
            if (!dark) {
                surface = n1(10); lowest = n1(0); low = n1(50)
                mid = Tonal.mix(n1(50), n1(100), 0.4); high = Tonal.mix(n1(50), n1(100), 0.7); highest = n1(100)
            } else if (!black) {
                surface = Tonal.mix(n1(900), n1(1000), 0.4); lowest = Tonal.mix(n1(900), n1(1000), 0.6); low = n1(900)
                mid = Tonal.mix(n1(900), n1(800), 0.2); high = Tonal.mix(n1(900), n1(800), 0.5); highest = Tonal.mix(n1(900), n1(800), 0.8)
            } else {
                surface = BLACK; lowest = BLACK; low = Tonal.mix(n1(900), n1(1000), 0.5)
                mid = n1(900); high = Tonal.mix(n1(900), n1(800), 0.3); highest = Tonal.mix(n1(900), n1(800), 0.6)
            }
            val onSurface = if (dark) n1(100) else n1(900)
            var onSurfaceVariant = if (dark) n2(200) else n2(700)
            var outline = if (dark) n2(400) else n2(500)
            var outlineVariant = if (dark) (if (black) n2(800) else n2(700)) else n2(200)
            if (highContrast) {
                onSurfaceVariant = onSurface
                outline = if (dark) n2(200) else n2(600)
                outlineVariant = outline
                primaryContainer = primary
                onPrimaryContainer = onPrimary
            }

            // Runtime guard: odd wallpapers can produce pairs below the WCAG minimums.
            primary = Tonal.ensureContrast(primary, surface, 3.0)
            onPrimary = Tonal.ensureContrast(onPrimary, primary, 4.5)
            onPrimaryContainer = Tonal.ensureContrast(onPrimaryContainer, primaryContainer, 4.5)
            onSurfaceVariant = Tonal.ensureContrast(onSurfaceVariant, high, 4.5)
            outline = Tonal.ensureContrast(outline, surface, 3.0)
            val guardedOnSurface = Tonal.ensureContrast(onSurface, highest, 4.5)

            val success = Tonal.ensureContrast(if (dark) sp.tone(80) else sp.tone(40), high, 4.5)
            val warning = Tonal.ensureContrast(if (dark) wp.tone(80) else wp.tone(40), high, 4.5)

            return Scheme(
                isDark = dark, amoled = black, highContrast = highContrast,
                primary = primary, onPrimary = onPrimary,
                primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer, inversePrimary = inversePrimary,
                secondary = secondary, onSecondary = Tonal.ensureContrast(onSecondary, secondary, 4.5),
                secondaryContainer = secondaryContainer,
                onSecondaryContainer = Tonal.ensureContrast(onSecondaryContainer, secondaryContainer, 4.5),
                tertiary = tertiary, onTertiary = Tonal.ensureContrast(onTertiary, tertiary, 4.5),
                tertiaryContainer = tertiaryContainer,
                onTertiaryContainer = Tonal.ensureContrast(onTertiaryContainer, tertiaryContainer, 4.5),
                surface = surface,
                surfaceContainerLowest = lowest, surfaceContainerLow = low, surfaceContainer = mid,
                surfaceContainerHigh = high, surfaceContainerHighest = highest,
                onSurface = guardedOnSurface, onSurfaceVariant = onSurfaceVariant,
                outline = outline, outlineVariant = outlineVariant,
                inverseSurface = if (dark) n1(100) else n1(800),
                inverseOnSurface = Tonal.ensureContrast(if (dark) n1(800) else n1(50), if (dark) n1(100) else n1(800), 4.5),
                scrim = Tonal.withAlpha(BLACK, if (black) 179 else if (dark) 153 else 82),
                error = if (dark) 0xFFF2B8B5.toInt() else 0xFFB3261E.toInt(),
                onError = if (dark) 0xFF601410.toInt() else WHITE,
                errorContainer = if (dark) 0xFF8C1D18.toInt() else 0xFFF9DEDC.toInt(),
                onErrorContainer = if (dark) 0xFFF9DEDC.toInt() else 0xFF410E0B.toInt(),
                success = success,
                onSuccess = if (dark) sp.tone(20) else sp.tone(100),
                successContainer = if (dark) sp.tone(30) else sp.tone(90),
                onSuccessContainer = if (dark) sp.tone(90) else sp.tone(10),
                warning = warning,
                onWarning = if (dark) wp.tone(20) else wp.tone(100),
                warningContainer = if (dark) wp.tone(30) else wp.tone(90),
                onWarningContainer = if (dark) wp.tone(90) else wp.tone(10),
            )
        }
    }
}

/** Accent choices in Settings › Appearance (DESIGN-SPEC 1.5). */
enum class Accent(val label: String, val seed: Int?) {
    DYNAMIC("Wallpaper", null),
    VIOLET("Violet", 0xFF8B5CF6.toInt()),
    OCEAN("Ocean", 0xFF0EA5E9.toInt()),
    EMERALD("Emerald", 0xFF10B981.toInt()),
    SUNSET("Sunset", 0xFFF97316.toInt()),
    ROSE("Rose", 0xFFF43F5E.toInt()),
    GRAPHITE("Graphite", 0xFF64748B.toInt()),
    CUSTOM("Custom", null);

    companion object {
        val presets: List<Accent> get() = values().filter { it.seed != null }

        /** The palettes for an accent that doesn't come from the wallpaper. */
        fun source(accent: Accent, customHue: Int): SchemeSource = when {
            accent == CUSTOM -> SchemeSource.fromHueChroma(customHue.toDouble(), 60.0)
            accent.seed != null -> SchemeSource.fromSeed(accent.seed)
            else -> SchemeSource.fromSeed(VIOLET.seed!!)
        }
    }
}
