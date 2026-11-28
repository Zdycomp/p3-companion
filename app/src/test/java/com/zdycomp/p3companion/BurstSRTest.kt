package com.zdycomp.p3companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

class BurstSRTest {
    private val scene = SynthScene(128, 128)

    @Test fun fftRoundTripAndKnownSpectrum() {
        val n = 64
        val rnd = Random(3)
        val re = DoubleArray(n) { rnd.nextGaussian() }; val im = DoubleArray(n)
        val keep = re.copyOf()
        val f = Fft(n)
        f.transform(re, im); f.transform(re, im, inverse = true)
        for (i in 0 until n) { assertEquals(keep[i], re[i], 1e-9); assertEquals(0.0, im[i], 1e-9) }
        val c = DoubleArray(n) { cos(2 * PI * 5 * it / n) }; val ci = DoubleArray(n)
        f.transform(c, ci)
        assertEquals(n / 2.0, c[5], 1e-9); assertEquals(n / 2.0, c[n - 5], 1e-9)
        assertEquals(0.0, c[4], 1e-9)
    }

    @Test fun phaseCorrelationIntegerShifts() {
        val ref = scene.render(0.0, 0.0)
        for ((dx, dy) in listOf(5 to -3, -17 to 11, 0 to 0)) {
            val img = scene.render(dx.toDouble(), dy.toDouble())
            val r = PhaseCorr.estimate(ref, img)
            // frame(i) shows scene(i + d): the reference point x is at x − d in the frame.
            assertEquals("dx for $dx,$dy", -dx.toDouble(), Math.rint(r.dx), 0.0)
            assertEquals("dy for $dx,$dy", -dy.toDouble(), Math.rint(r.dy), 0.0)
            assertTrue("ρ ${r.rho}", r.rho > 3)
        }
    }

    @Test fun phaseCorrelationSubpixel() {
        val rnd = Random(7)
        val ref = scene.render(0.0, 0.0)
        var worst = 0.0
        repeat(20) {
            val dx = rnd.nextDouble() * 8 - 4; val dy = rnd.nextDouble() * 8 - 4
            val r = PhaseCorr.estimate(ref, scene.render(dx, dy))
            worst = maxOf(worst, abs(r.dx + dx), abs(r.dy + dy))
        }
        assertTrue("worst phase-correlation error $worst px", worst < 0.25)
    }

    private val smoothScene = SynthScene(128, 128, smooth = true)

    private fun lkError(dx: Double, dy: Double, rot: Double, noise: Double, seed: Long, sc: SynthScene = scene): Triple<Double, Double, Double> {
        val ref = sc.render(0.0, 0.0, noise = noise, seed = seed)
        val img = sc.render(dx, dy, rotDeg = rot, noise = noise, seed = seed + 1000)
        val pc = PhaseCorr.estimate(ref, img)
        val pyr = Lk.pyramid(Sr.gauss(ref, 0.6), 3)
        val r = Lk.register(pyr, img, pc.dx, pc.dy, Quality.noise(ref))
        assertTrue(r.ok)
        // The true warp: W(x) = R(−θ)(x − c − d) + c.
        val cx = 63.5; val cy = 63.5
        val th = Math.toRadians(-rot)
        fun trueX(x: Double, y: Double) = cx + cos(th) * (x - cx - dx) - kotlin.math.sin(th) * (y - cy - dy)
        fun trueY(x: Double, y: Double) = cy + kotlin.math.sin(th) * (x - cx - dx) + cos(th) * (y - cy - dy)
        // Error at the centre (translation) and the worst corner displacement (rotation, scale, shear).
        val ex = r.warp.mapX(cx, cy) - trueX(cx, cy); val ey = r.warp.mapY(cx, cy) - trueY(cx, cy)
        var corner = 0.0
        for ((x, y) in listOf(0.0 to 0.0, 127.0 to 0.0, 0.0 to 127.0, 127.0 to 127.0)) {
            val dx2 = r.warp.mapX(x, y) - trueX(x, y); val dy2 = r.warp.mapY(x, y) - trueY(x, y)
            corner = maxOf(corner, sqrt(dx2 * dx2 + dy2 * dy2))
        }
        return Triple(sqrt(ex * ex + ey * ey), corner, abs(r.warp.rotationDeg + rot))
    }

    @Test fun lkRefinesToTenthPixel() {
        val rnd = Random(11)
        // Shifts up to ±4 px on the aliased scene: within 0.05 px RMS, at the centre and at the corners.
        var se = 0.0; var seC = 0.0
        repeat(20) { k ->
            val (e, corner, _) = lkError(rnd.nextDouble() * 8 - 4, rnd.nextDouble() * 8 - 4, 0.0, 0.0, k.toLong())
            se += e * e; seC += corner * corner
        }
        assertTrue("translation RMS ${sqrt(se / 20)} px", sqrt(se / 20) < 0.05)
        assertTrue("corner RMS ${sqrt(seC / 20)} px", sqrt(seC / 20) < 0.1)
        // Plus rotations up to ±1° (on detail the sampling doesn't alias, which otherwise biases the angle).
        var seR = 0.0; var worstRot = 0.0
        repeat(12) { k ->
            val (e, _, er) = lkError(rnd.nextDouble() * 8 - 4, rnd.nextDouble() * 8 - 4, rnd.nextDouble() * 2 - 1, 0.0, 50L + k, smoothScene)
            seR += e * e; worstRot = maxOf(worstRot, er)
        }
        assertTrue("rotated translation RMS ${sqrt(seR / 12)} px", sqrt(seR / 12) < 0.05)
        assertTrue("rotation error $worstRot°", worstRot < 0.02)
        var se2 = 0.0
        repeat(10) { k -> val (e, _, _) = lkError(rnd.nextDouble() * 6 - 3, rnd.nextDouble() * 6 - 3, 0.0, 2.0, 100L + k); se2 += e * e }
        assertTrue("noisy RMS ${sqrt(se2 / 10)} px", sqrt(se2 / 10) < 0.1)
    }

    @Test fun lkIgnoresAMovingObject() {
        val ref = scene.render(0.0, 0.0, noise = 1.0, seed = 1)
        val moving = SynthScene(128, 128).apply { square = doubleArrayOf(10.0, 0.0) }
        val img = moving.render(0.7, -0.4, noise = 1.0, seed = 2)
        val pc = PhaseCorr.estimate(ref, img)
        val r = Lk.register(Lk.pyramid(Sr.gauss(ref, 0.6), 3), img, pc.dx, pc.dy, 1.0)
        val ex = r.warp.mapX(63.5, 63.5) - (63.5 - 0.7); val ey = r.warp.mapY(63.5, 63.5) - (63.5 + 0.4)
        assertTrue("error ${sqrt(ex * ex + ey * ey)}", sqrt(ex * ex + ey * ey) < 0.1)
    }

    @Test fun tileWeightsRejectMotionKeepStatic() {
        val ref = scene.render(0.0, 0.0, noise = 2.0, seed = 1)
        val moving = SynthScene(128, 128).apply { square = doubleArrayOf(10.0, 0.0) }
        val img = moving.render(0.0, 0.0, noise = 2.0, seed = 2)
        val wts = BurstSR.tileWeights(ref, img, Affine.identity(63.5, 63.5), Quality.noise(ref), 0.0)
        // The square's moved edge sits near x = 0.55·128 + 10 … in rows 0.62·128 …; a static flat area is the gradient's corner.
        val movingW = wts[96, 96]
        val staticW = wts[8, 8]
        assertTrue("moving $movingW", movingW < 0.1)
        assertTrue("static $staticW", staticW > 0.8)
    }

    @Test fun h264SkipBlocksCarryNoWeight() {
        val a = IntArray(32 * 32) { it }
        val b = a.copyOf().also { for (y in 16 until 32) for (x in 0 until 32) it[y * 32 + x] += 1 }
        val skip = BurstSR.skipBlocks(b, a, 32, 32)
        assertTrue(skip[0]); assertTrue(skip[1]); assertFalse(skip[2]); assertFalse(skip[3])
    }

    private class Burst(val frames: List<IntArray>, val shifts: List<DoubleArray>)

    private fun burst(n: Int, noise: Double, seed: Long, gainOf: (Int) -> Double = { 1.0 }, offOf: (Int) -> Double = { 0.0 }): Burst {
        val rnd = Random(seed)
        val shifts = List(n) { if (it == 0) doubleArrayOf(0.0, 0.0) else doubleArrayOf(rnd.nextDouble() * 3 - 1.5, rnd.nextDouble() * 3 - 1.5) }
        val frames = shifts.mapIndexed { k, d -> SynthScene.argb(scene.render(d[0], d[1], noise = noise, seed = seed * 100 + k, gain = gainOf(k), offset = offOf(k))) }
        return Burst(frames, shifts)
    }

    private fun scores(b: Burst, res: BurstSR.Result): Pair<Double, Double> {
        val d = b.shifts[res.refIndex]
        val truth = scene.render(d[0], d[1], scale = 2, quantize = false)
        return Sr.psnr(res.luma, truth, 8) to Sr.psnr(res.bicubic, truth, 8)
    }

    @Test fun srBeatsBicubic() {
        val b = burst(8, 2.0, 5)
        val res = BurstSR.superResolve(b.frames, 128, 128, BurstSR.Source.JPEG, BurstSR.Params.of(BurstSR.Source.JPEG).copy(tone = false))
        val (sr, bic) = scores(b, res)
        println("SR %.2f dB, bicubic %.2f dB; ${res.report}".format(sr, bic))
        assertEquals(256, res.w); assertEquals(256, res.h)
        assertTrue("SR $sr vs bicubic $bic", sr >= bic + 1.5)
        // And over the 4.0 single-frame enhance.
        val d = b.shifts[res.refIndex]
        val truth = scene.render(d[0], d[1], scale = 2, quantize = false)
        val old = ImageEnhance.enhance(b.frames[res.refIndex], 128, 128, 2, 0.6f)
        val oldY = Ycc.of(old.px, old.w, old.h).y
        val oldPsnr = Sr.psnr(oldY, truth, 8)
        assertTrue("SR $sr vs 4.0 enhance $oldPsnr", sr >= oldPsnr + 1.0)
    }

    @Test fun srWithOracleShifts() {
        val b = burst(8, 2.0, 5)
        val oracle = b.shifts.map { d -> Affine.translation(b.shifts[0][0] - d[0], b.shifts[0][1] - d[1], 63.5, 63.5) }
        val res = BurstSR.superResolve(b.frames, 128, 128, BurstSR.Source.JPEG,
            BurstSR.Params.of(BurstSR.Source.JPEG).copy(tone = false, oracle = oracle, oracleRef = 0))
        val (sr, bic) = scores(b, res)
        println("oracle SR %.2f dB, bicubic %.2f dB".format(sr, bic))
        assertTrue("SR $sr vs bicubic $bic", sr >= bic + 2.5)
    }

    @Test fun srNeverWorseThanBicubicForOneFrame() {
        val b = burst(1, 2.0, 9)
        val res = BurstSR.superResolve(b.frames, 128, 128, BurstSR.Source.JPEG, BurstSR.Params.of(BurstSR.Source.JPEG).copy(tone = false))
        val (sr, bic) = scores(b, res)
        println("single SR %.2f dB, bicubic %.2f dB; ${res.report}".format(sr, bic))
        assertEquals(1, res.used)
        assertTrue("SR $sr vs bicubic $bic", sr >= bic - 0.3)
        assertTrue(res.report.startsWith("Single frame"))
    }

    @Test fun exposureChangeIsMatched() {
        // Frame 4 brighter and higher in contrast (1.2·f − 10); the reference stays frame 1.
        val b = burst(6, 2.0, 13, gainOf = { if (it == 3) 1.2 else 1.0 }, offOf = { if (it == 3) -10.0 else 0.0 })
        val res = BurstSR.superResolve(b.frames, 128, 128, BurstSR.Source.JPEG, BurstSR.Params.of(BurstSR.Source.JPEG).copy(tone = false, oracleRef = 0))
        val info = res.frames.first { it.index == 3 }
        assertTrue("frame 4 kept: ${res.report}", info.kept)
        assertEquals("gain to match frame 4", 1 / 1.2, info.gain, 0.03)
        val (sr, bic) = scores(b, res)
        assertTrue("SR $sr vs bicubic $bic", sr >= bic + 1.5)
    }

    @Test fun flatStaysFlat() {
        val flat = List(4) { IntArray(64 * 64) { 0xFF808080.toInt() } }
        val res = BurstSR.superResolve(flat, 64, 64, BurstSR.Source.H264, BurstSR.Params.of(BurstSR.Source.H264).copy(tone = false))
        res.luma.v.forEach { assertEquals(128f, it, 1f) }
        res.px.forEach { assertTrue(abs(((it shr 8) and 0xFF) - 128) <= 1) }
    }

    @Test fun deblockReducesBlockiness() {
        val clean = scene.render(0.0, 0.0, quantize = false)
        val blocky = SynthScene.jpegish(clean, 24.0)
        val r = Deblock.run(blocky, h264 = false)
        assertTrue("blockiness ${r.before}", r.before > 1.10)
        assertTrue(r.applied)
        assertTrue("B ${r.before} → ${r.after}", (r.after - 1) <= 0.7 * (r.before - 1))
        assertTrue(Sr.psnr(r.plane, clean, 4) >= Sr.psnr(blocky, clean, 4) - 0.05)
    }

    @Test fun deconvSharpensEdgeWithoutHalo() {
        val w = 64; val h = 16
        val step = Plane(w, h, FloatArray(w * h) { if (it % w < 32) 50f else 200f })
        val blurred = Sr.gauss(step, 1.2)
        val dec = Deconv.rlTv(blurred, 1.2, 8, 0.002)
        fun width(p: Plane): Double {
            val row = (0 until w).map { p[it, 8].toDouble() }
            fun cross(level: Double): Double {
                val i = (20 until 44).first { row[it] >= level }
                return i - 1 + (level - row[i - 1]) / (row[i] - row[i - 1])
            }
            return cross(50 + 0.9 * 150) - cross(50 + 0.1 * 150)
        }
        assertTrue("width ${width(blurred)} → ${width(dec)}", width(dec) <= 0.75 * width(blurred))
        // The pipeline's halo clamp keeps overshoot within 2 % of the step.
        var over = 0f
        for (x in 0 until w) {
            var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
            for (d in -1..1) { val q = blurred.at(x + d, 8); lo = minOf(lo, q); hi = maxOf(hi, q) }
            val y = dec[x, 8].coerceIn(lo - 0.02f * 255, hi + 0.02f * 255)
            over = maxOf(over, y - 200f, 50f - y)
        }
        assertTrue("overshoot $over", over <= 0.02f * 150 + 0.02f * 255)
    }

    @Test fun guidedChromaFollowsLuma() {
        val w = 32
        val guide = Plane(w, w, FloatArray(w * w) { if (it % w < 16) 40f else 200f })
        val coarse = Plane(w / 2, w / 2, FloatArray(w * w / 4) { if (it % (w / 2) < 8) 90f else 170f })
        val up = Sr.bilinearUp(coarse, 2)
        val g = Guided.filter(guide, up, 4, 650.0)
        // Two pixels from the edge the guided chroma is already at its plateau.
        assertEquals(90f, g[13, 16], 6f)
        assertEquals(170f, g[18, 16], 6f)
    }

    @Test fun labelIsHonest() {
        assertEquals("Burst ×4, 1280×960 from 640×480 (Bluetooth photos)", BurstSR.label(4, 1280, 960, 640, 480, live = false))
        assertTrue(BurstSR.label(1, 1280, 960, 640, 480, live = false).startsWith("Sharpened from 1 frame"))
        assertTrue(BurstSR.label(16, 640, 480, 320, 240, live = true).endsWith("(live view frames)"))
    }

    @Test fun liveBurstOfH264FramesAtThreeTimes() {
        // 16 live frames 33 ms apart: small sub-pixel jitter, then 3× like a "Sharp snapshot" at 3×.
        val small = SynthScene(64, 64)
        val rnd = Random(21)
        val shifts = List(16) { if (it == 0) doubleArrayOf(0.0, 0.0) else doubleArrayOf(rnd.nextDouble() * 1.6 - 0.8, rnd.nextDouble() * 1.6 - 0.8) }
        val frames = shifts.mapIndexed { k, d -> SynthScene.argb(small.render(d[0], d[1], noise = 3.0, seed = 700L + k)) }
        // The synthetic frames carry no H.264 smear, so the system blur is the pixel aperture alone (not the 0.70 default),
        // which the 64-px scene has too few straight edges to measure.
        val res = BurstSR.superResolve(frames, 64, 64, BurstSR.Source.H264, BurstSR.Params.of(BurstSR.Source.H264, 3).copy(tone = false, sigmaSysDefault = 0.35))
        assertEquals(192, res.w)
        val d = shifts[res.refIndex]
        val truth = small.render(d[0], d[1], scale = 3, quantize = false)
        val sr = Sr.psnr(res.luma, truth, 12); val bic = Sr.psnr(res.bicubic, truth, 12)
        println("live 3× SR %.2f dB, bicubic %.2f dB; ${res.report}".format(sr, bic))
        assertTrue("SR $sr vs bicubic $bic", sr >= bic + 1.5)
    }
}
