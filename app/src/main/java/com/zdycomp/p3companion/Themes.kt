package com.zdycomp.p3companion

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.os.Build

/** Turns the Appearance settings into a [Scheme] (wallpaper colors on Android 12+, or an accent preset). */
object Themes {

    fun isDark(context: Context, theme: Prefs.Theme): Boolean = when (theme) {
        Prefs.Theme.DARK -> true
        Prefs.Theme.LIGHT -> false
        Prefs.Theme.SYSTEM -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    /** Wallpaper colors exist from Android 12. */
    val dynamicAvailable: Boolean get() = Build.VERSION.SDK_INT >= 31

    /** The accent actually in use (Wallpaper falls back to Violet on Android 11). */
    fun effectiveAccent(prefs: Prefs): Accent =
        if (prefs.accent == Accent.DYNAMIC && !dynamicAvailable) Accent.VIOLET else prefs.accent

    fun scheme(context: Context, prefs: Prefs): Scheme {
        val dark = isDark(context, prefs.theme)
        val accent = effectiveAccent(prefs)
        val src = if (accent == Accent.DYNAMIC) dynamicSource(context) ?: Accent.source(Accent.VIOLET, 0)
        else Accent.source(accent, prefs.accentHue)
        return Scheme.build(src, dark, amoled = prefs.amoled && dark, highContrast = prefs.highContrast)
    }

    fun palette(context: Context, prefs: Prefs): Palette = Palette.from(scheme(context, prefs))

    fun reduceMotion(prefs: Prefs): Boolean = when (prefs.reduceMotion) {
        Prefs.Motion.ON -> true
        Prefs.Motion.OFF -> false
        Prefs.Motion.SYSTEM -> !ValueAnimator.areAnimatorsEnabled()
    }

    fun ui(context: Context, prefs: Prefs): Ui =
        Ui(context, palette(context, prefs), prefs.textScale, reduceMotion(prefs), prefs.haptics)

    /** The five wallpaper palettes from android.R.color.system_* (API 31+). */
    private fun dynamicSource(context: Context): SchemeSource? {
        if (Build.VERSION.SDK_INT < 31) return null
        return runCatching {
            fun read(ids: IntArray) = IntArray(ids.size) { context.getColor(ids[it]) }
            SchemeSource(read(A1), read(A2), read(A3), read(N1), read(N2))
        }.getOrNull()
    }

    private val A1 = intArrayOf(
        android.R.color.system_accent1_0, android.R.color.system_accent1_10, android.R.color.system_accent1_50,
        android.R.color.system_accent1_100, android.R.color.system_accent1_200, android.R.color.system_accent1_300,
        android.R.color.system_accent1_400, android.R.color.system_accent1_500, android.R.color.system_accent1_600,
        android.R.color.system_accent1_700, android.R.color.system_accent1_800, android.R.color.system_accent1_900,
        android.R.color.system_accent1_1000,
    )
    private val A2 = intArrayOf(
        android.R.color.system_accent2_0, android.R.color.system_accent2_10, android.R.color.system_accent2_50,
        android.R.color.system_accent2_100, android.R.color.system_accent2_200, android.R.color.system_accent2_300,
        android.R.color.system_accent2_400, android.R.color.system_accent2_500, android.R.color.system_accent2_600,
        android.R.color.system_accent2_700, android.R.color.system_accent2_800, android.R.color.system_accent2_900,
        android.R.color.system_accent2_1000,
    )
    private val A3 = intArrayOf(
        android.R.color.system_accent3_0, android.R.color.system_accent3_10, android.R.color.system_accent3_50,
        android.R.color.system_accent3_100, android.R.color.system_accent3_200, android.R.color.system_accent3_300,
        android.R.color.system_accent3_400, android.R.color.system_accent3_500, android.R.color.system_accent3_600,
        android.R.color.system_accent3_700, android.R.color.system_accent3_800, android.R.color.system_accent3_900,
        android.R.color.system_accent3_1000,
    )
    private val N1 = intArrayOf(
        android.R.color.system_neutral1_0, android.R.color.system_neutral1_10, android.R.color.system_neutral1_50,
        android.R.color.system_neutral1_100, android.R.color.system_neutral1_200, android.R.color.system_neutral1_300,
        android.R.color.system_neutral1_400, android.R.color.system_neutral1_500, android.R.color.system_neutral1_600,
        android.R.color.system_neutral1_700, android.R.color.system_neutral1_800, android.R.color.system_neutral1_900,
        android.R.color.system_neutral1_1000,
    )
    private val N2 = intArrayOf(
        android.R.color.system_neutral2_0, android.R.color.system_neutral2_10, android.R.color.system_neutral2_50,
        android.R.color.system_neutral2_100, android.R.color.system_neutral2_200, android.R.color.system_neutral2_300,
        android.R.color.system_neutral2_400, android.R.color.system_neutral2_500, android.R.color.system_neutral2_600,
        android.R.color.system_neutral2_700, android.R.color.system_neutral2_800, android.R.color.system_neutral2_900,
        android.R.color.system_neutral2_1000,
    )
}
