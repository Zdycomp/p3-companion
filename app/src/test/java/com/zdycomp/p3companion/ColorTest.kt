package com.zdycomp.p3companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Tonal generator (DESIGN-SPEC 1.3) and contrast of every scheme the app can show (1.4). */
class ColorTest {

    private val seeds = Accent.presets.map { it.seed!! } +
        // A few "wallpaper" seeds: muddy, neon, near-grey, very dark, very light.
        listOf(0xFF7A6A4F, 0xFF39FF14, 0xFF808080, 0xFF101820, 0xFFF5E6CC, 0xFFFF00FF, 0xFF0000FF).map { it.toInt() }

    @Test fun tonesMatchLabLightness() {
        for (seed in seeds) {
            val lch = Tonal.lch(seed)
            val pal = Tonal.palette(lch[2], maxOf(lch[1], 48.0))
            for (t in listOf(0, 4, 6, 10, 12, 17, 20, 22, 24, 30, 40, 50, 60, 70, 80, 87, 90, 92, 94, 95, 96, 98, 99, 100)) {
                val c = pal.tone(t)
                val l = Tonal.lab(c)[0]
                assertTrue("seed %08X tone %d gave L %.2f".format(seed, t, l), abs(l - t) <= 0.6)
            }
        }
    }

    @Test fun extremesAreExact() {
        val p = Tonal.palette(280.0, 60.0)
        assertEquals(0xFF000000.toInt(), p.tone(0))
        assertEquals(0xFFFFFFFF.toInt(), p.tone(100))
        assertEquals(p.tone(90), p.s(100))
        assertEquals(p.tone(40), p.s(600))
    }

    @Test fun zeroChromaIsNeutralGrey() {
        val p = Tonal.palette(123.0, 0.0)
        for (t in 5..95 step 5) {
            val lab = Tonal.lab(p.tone(t))
            assertTrue("tone $t a=${lab[1]}", abs(lab[1]) < 1)
            assertTrue("tone $t b=${lab[2]}", abs(lab[2]) < 1)
        }
    }

    @Test fun hueWraps() {
        val a = Tonal.palette(359.0, 40.0)
        val b = Tonal.palette(-1.0, 40.0)
        for (t in listOf(10, 40, 80)) assertEquals(a.tone(t), b.tone(t))
    }

    @Test fun contrastFormula() {
        assertEquals(21.0, Tonal.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        assertEquals(1.0, Tonal.contrast(0xFF777777.toInt(), 0xFF777777.toInt()), 0.0001)
        // #767676 on white is the classic 4.54:1.
        assertEquals(4.54, Tonal.contrast(0xFF767676.toInt(), 0xFFFFFFFF.toInt()), 0.02)
    }

    @Test fun ensureContrastFixesLowPairs() {
        val bg = 0xFF808080.toInt()
        val fixed = Tonal.ensureContrast(0xFF8A8A8A.toInt(), bg, 4.5)
        assertTrue(Tonal.contrast(fixed, bg) >= 4.5)
        val ok = 0xFF000000.toInt()
        assertEquals(ok, Tonal.ensureContrast(ok, 0xFFFFFFFF.toInt(), 4.5))
    }

    @Test fun harmonizeMovesAtMost15Degrees() {
        assertEquals(165.0, Tonal.harmonize(150.0, 280.0), 1e-9) // shortest way round is +130 → +15
        assertEquals(140.0, Tonal.harmonize(150.0, 130.0), 1e-9) // -20 → -10
        assertEquals(350.0, Tonal.harmonize(5.0, 330.0), 1e-9) // -35 wraps through 0 → -15
    }

    private fun allSchemes(): List<Pair<String, Scheme>> {
        val out = mutableListOf<Pair<String, Scheme>>()
        val sources = seeds.map { "%08X".format(it) to SchemeSource.fromSeed(it) } +
            (0 until 360 step 30).map { "hue $it" to Accent.source(Accent.CUSTOM, it) }
        for ((name, src) in sources) {
            for (dark in listOf(false, true)) for (black in listOf(false, true)) for (high in listOf(false, true)) {
                if (black && !dark) continue
                out += "$name dark=$dark black=$black high=$high" to Scheme.build(src, dark, black, high)
            }
        }
        return out
    }

    private fun check(name: String, what: String, fg: Int, bg: Int, min: Double) {
        val r = Tonal.contrast(fg, bg)
        assertTrue("$name: $what %.2f < %.1f".format(r, min), r >= min)
    }

    @Test fun everySchemeMeetsTextContrast() {
        for ((n, s) in allSchemes()) {
            check(n, "onPrimary/primary", s.onPrimary, s.primary, 4.5)
            check(n, "onPrimaryContainer", s.onPrimaryContainer, s.primaryContainer, 4.5)
            check(n, "onSecondaryContainer", s.onSecondaryContainer, s.secondaryContainer, 4.5)
            check(n, "onTertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer, 4.5)
            s.surfaces.forEachIndexed { i, bg -> check(n, "onSurface/surface$i", s.onSurface, bg, 4.5) }
            listOf(s.surface, s.surfaceContainerLowest, s.surfaceContainerLow, s.surfaceContainer, s.surfaceContainerHigh)
                .forEachIndexed { i, bg -> check(n, "onSurfaceVariant/surface$i", s.onSurfaceVariant, bg, 4.5) }
            check(n, "onError/error", s.onError, s.error, 4.5)
            check(n, "onErrorContainer", s.onErrorContainer, s.errorContainer, 4.5)
            check(n, "success/surface", s.success, s.surface, 4.5)
            check(n, "success/card", s.success, s.surfaceContainerLow, 4.5)
            check(n, "warning/surfaceContainer", s.warning, s.surfaceContainer, 4.5)
            check(n, "inverseOnSurface", s.inverseOnSurface, s.inverseSurface, 4.5)
        }
    }

    @Test fun everySchemeMeetsComponentContrast() {
        for ((n, s) in allSchemes()) {
            check(n, "primary/surface", s.primary, s.surface, 3.0)
            check(n, "outline/surface", s.outline, s.surface, 3.0)
            // White live-view text over the scrim on a mid-grey frame.
            val overGrey = Tonal.mix(0xFF808080.toInt(), 0xFF000000.toInt(), 0.6)
            check(n, "white/scrim", 0xFFFFFFFF.toInt(), overGrey, 3.0)
        }
    }

    @Test fun amoledIsPureBlack() {
        val s = Scheme.build(SchemeSource.fromSeed(Accent.VIOLET.seed!!), dark = true, amoled = true)
        assertEquals(0xFF000000.toInt(), s.surface)
        assertTrue(s.amoled)
        val notDark = Scheme.build(SchemeSource.fromSeed(Accent.VIOLET.seed!!), dark = false, amoled = true)
        assertTrue(notDark.surface != 0xFF000000.toInt())
    }

    @Test fun legacyPaletteKeepsFieldNames() {
        val p = Palette.DARK
        assertEquals(p.scheme.surface, p.bg)
        assertEquals(p.scheme.primary, p.accent)
        assertEquals(p.scheme.surfaceContainerLow, p.card)
        assertTrue(p.isDark)
        assertTrue(!Palette.LIGHT.isDark)
        assertTrue(Tonal.contrast(p.text, p.bg) >= 4.5)
        assertTrue(Tonal.contrast(p.onAccent, p.accent) >= 4.5)
    }
}
