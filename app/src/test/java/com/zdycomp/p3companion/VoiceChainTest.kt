package com.zdycomp.p3companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10

/** 4.1: the glasses-mic voice chain on synthetic signals (MATH §2.8). */
class VoiceChainTest {

    private fun runHpf(x: DoubleArray): DoubleArray {
        val b = Biquad()
        val out = DoubleArray(x.size)
        SynthVoice.frames(x).forEachIndexed { f, fr -> b.process(fr).forEachIndexed { i, v -> out[f * 320 + i] = v.toDouble() } }
        return out
    }

    @Test fun hpfMatchesTheSpecAndItsResponse() {
        val b = Biquad()
        assertEquals(0.978030479, b.b0, 1e-6); assertEquals(-1.956060958, b.b1, 1e-6)
        assertEquals(-1.955578240, b.a1, 1e-6); assertEquals(0.956543677, b.a2, 1e-6)
        fun gain(hz: Double): Double {
            val x = SynthVoice.sine(2.0, hz, 0.3)
            val y = runHpf(x)
            return SynthVoice.rmsDb(y, 16_000, 32_000) - SynthVoice.rmsDb(x, 16_000, 32_000)
        }
        assertEquals(0.0, gain(1000.0), 0.1)
        assertTrue(gain(200.0) >= -0.3)
        assertTrue(gain(50.0) <= -8.0)
        assertTrue(gain(20.0) <= -20.0)
        val dc = runHpf(DoubleArray(32_000) { 0.5 })
        assertTrue(abs(dc.copyOfRange(24_000, 32_000).average()) < 1e-4)
    }

    @Test fun stftIsTransparentAtUnityGain() {
        val st = Stft()
        val x = SynthVoice.white(1.0, -20.0)
        val out = DoubleArray(x.size)
        SynthVoice.frames(x).forEachIndexed { f, fr -> st.analyse(fr); st.synthesise(null).forEachIndexed { i, v -> out[f * 320 + i] = v.toDouble() } }
        var maxErr = 0.0
        for (i in 320 until x.size - 320) maxErr = maxOf(maxErr, abs(out[i + 320] - x[i]))
        assertTrue("max error $maxErr", maxErr < 1e-5)
    }

    @Test fun framesReblockFromOddChunks() {
        val fb = FrameBuffer()
        val got = ArrayList<FloatArray>()
        var v = 0
        for (size in listOf(7, 333, 1000, 7, 333, 1000)) fb.push(ShortArray(size) { (v++ % 30000).toShort() }) { got += it }
        assertEquals(2680 / 320, got.size)
        assertEquals(2680 % 320, fb.pending)
        var expect = 0
        got.forEach { fr -> fr.forEach { s -> assertEquals((expect++ % 30000) / 32768f, s, 1e-7f) } }
    }

    @Test fun noiseFloorTracksAndIsUnbiased() {
        val a = Analyzer()
        val trueDb = -40.0
        val x = SynthVoice.concat(SynthVoice.white(5.0, trueDb, 11), SynthVoice.white(3.0, trueDb + 10, 12), SynthVoice.white(4.0, trueDb, 13))
        val floors = ArrayList<Double>()
        SynthVoice.frames(x).forEach { a.run(it, frozen = false); floors += a.noise.floorDb() }
        fun at(s: Double) = floors[(s * 50).toInt() - 1]
        assertEquals(trueDb, at(3.0), 1.5)
        assertEquals(trueDb + 10, at(8.0), 3.0)
        assertEquals(trueDb, at(9.5), 3.0)
        assertTrue(a.stft.windowEnergy > 319 && a.stft.windowEnergy < 321)
    }

    @Test fun suppressorLowersNoiseAndKeepsSpeech() {
        val speech = SynthVoice.speech(4.0, -26.0, seed = 5)
        val noise = SynthVoice.pink(6.0, -31.0, 6)
        val x = SynthVoice.mix(SynthVoice.concat(SynthVoice.silence(2.0), speech), noise)
        val c = VoiceChain(VoiceChain.Params(agc = false, limiter = false))
        val out = DoubleArray(x.size)
        SynthVoice.frames(x).forEachIndexed { f, fr -> c.process(fr).pcm.forEachIndexed { i, v -> out[f * 320 + i] = v.toDouble() } }
        val inNoise = SynthVoice.rmsDb(x, 16_000, 32_000)
        val outNoise = SynthVoice.rmsDb(out, 16_000 + 320, 32_000 + 320)
        assertTrue("noise $inNoise → $outNoise", inNoise - outNoise >= 8.0)
        val clean = SynthVoice.concat(SynthVoice.silence(2.0), speech)
        val inSp = SynthVoice.rmsDb(clean, 40_000, 88_000)
        val outSp = SynthVoice.rmsDb(out, 40_320, 88_320)
        assertTrue("speech $inSp → $outSp", outSp > inSp - 2.0)
    }

    @Test fun cleanSpeechIsLeftAlone() {
        val x = SynthVoice.mix(SynthVoice.concat(SynthVoice.white(1.0, -70.0), SynthVoice.speech(3.0, -26.0, seed = 7)), SynthVoice.white(4.0, -70.0, 8))
        val c = VoiceChain(VoiceChain.Params(agc = false, limiter = false))
        val out = DoubleArray(x.size)
        SynthVoice.frames(x).forEachIndexed { f, fr -> c.process(fr).pcm.forEachIndexed { i, v -> out[f * 320 + i] = v.toDouble() } }
        val d = SynthVoice.rmsDb(out, 24_320, 60_320) - SynthVoice.rmsDb(x, 24_000, 60_000)
        assertTrue("difference $d dB", abs(d) < 0.5)
    }

    private fun speechFrameRms(c: VoiceChain, x: DoubleArray, fromS: Double, toS: Double): Double {
        val out = DoubleArray(x.size)
        val sp = BooleanArray(x.size / 320)
        SynthVoice.frames(x).forEachIndexed { f, fr -> val o = c.process(fr); sp[f] = o.speech; o.pcm.forEachIndexed { i, v -> out[f * 320 + i] = v.toDouble() } }
        var s = 0.0; var n = 0
        for (f in (fromS * 50).toInt() until (toS * 50).toInt()) if (sp.getOrElse(f - 1) { false }) for (i in 0 until 320) { val v = out[f * 320 + i]; s += v * v; n++ }
        return 10 * log10(s / maxOf(n, 1) + 1e-20)
    }

    @Test fun agcReachesTargetFromQuietAndLoud() {
        val quiet = SynthVoice.mix(SynthVoice.concat(SynthVoice.white(1.0, -65.0), SynthVoice.speech(4.0, -40.0, seed = 9)), SynthVoice.white(5.0, -65.0, 10))
        val q = speechFrameRms(VoiceChain(), quiet, 3.0, 5.0)
        assertEquals("quiet speech → $q", -20.0, q, 2.5)
        val loud = SynthVoice.mix(SynthVoice.concat(SynthVoice.white(1.0, -65.0), SynthVoice.speech(4.0, -8.0, seed = 9)), SynthVoice.white(5.0, -65.0, 10))
        val l = speechFrameRms(VoiceChain(), loud, 2.0, 5.0)
        assertEquals("loud speech → $l", -20.0, l, 2.5)
    }

    @Test fun agcDoesNotPumpNoise() {
        val c = VoiceChain()
        val x = SynthVoice.mix(SynthVoice.concat(SynthVoice.white(1.0, -60.0), SynthVoice.speech(2.0, -30.0, seed = 3), SynthVoice.white(5.0, -50.0, 4)), SynthVoice.white(8.0, -60.0, 5))
        var gainAfterSpeech = Double.NaN
        SynthVoice.frames(x).forEachIndexed { f, fr -> c.process(fr); if (f == 175) gainAfterSpeech = c.agcGainDb }
        assertTrue("gain ${c.agcGainDb} after $gainAfterSpeech", c.agcGainDb - gainAfterSpeech < 1.0)
    }

    @Test fun limiterHoldsTheCeilingWithoutDistortingQuietSound() {
        val burst = SynthVoice.sine(0.5, 440.0, 1.0).map { (it * 31.6).toFloat() }.toFloatArray()
        val out = Limiter().process(burst)
        assertTrue(out.all { abs(it) <= 0.891f + 1e-6f })
        val quiet = SynthVoice.sine(0.5, 440.0, 0.316).map { it.toFloat() }.toFloatArray()
        val q = Limiter().process(quiet)
        var err = 0.0; var pow = 0.0
        for (i in 32 until q.size) { val d = q[i] - quiet[i - 32]; err += d * d; pow += quiet[i - 32].toDouble() * quiet[i - 32] }
        assertTrue("distortion ${err / pow}", err / pow < 1e-6)
    }

    private fun vadRun(v: VadPlus, x: DoubleArray, lostFrames: Set<Int> = emptySet()): List<Vad.State> {
        val a = Analyzer()
        val states = ArrayList<Vad.State>()
        SynthVoice.frames(x).forEachIndexed { f, fr ->
            val feat = a.run(fr, frozen = false)
            if (f in lostFrames) v.lost() else v.decide(feat, a.noise.floorDb())
            states += v.state
        }
        return states
    }

    private fun firstFrame(states: List<Vad.State>, s: Vad.State) = states.indexOfFirst { it == s }

    @Test fun vadStartsAt120msAndEndsAfter800msOfQuiet() {
        val noise = SynthVoice.white(6.5, -50.0, 21)
        val x = SynthVoice.mix(SynthVoice.concat(SynthVoice.silence(1.0), SynthVoice.speech(1.5, -26.0, seed = 22), SynthVoice.silence(0.5),
            SynthVoice.speech(1.0, -26.0, seed = 23), SynthVoice.silence(2.5)), noise)
        val v = VadPlus(endMs = 800)
        val st = vadRun(v, x)
        val start = (firstFrame(st, Vad.State.SPEECH) + 1) * 20 - 1000
        assertTrue("start $start ms after onset", start in 100..220)
        assertTrue("no end in the pause", st.subList(0, 200).none { it == Vad.State.ENDED_AFTER_SPEECH })
        val end = (firstFrame(st, Vad.State.ENDED_AFTER_SPEECH) + 1) * 20
        assertTrue("end ${end - 4000} ms after the offset", end - 4000 in 650..880)
        assertTrue("start dated ${v.speechStartMs}", v.speechStartMs in 900.0..1160.0)
    }

    @Test fun vadEndIsConfigurable() {
        for (endMs in listOf(700, 900)) {
            val x = SynthVoice.mix(SynthVoice.concat(SynthVoice.silence(1.0), SynthVoice.speech(1.5, -26.0, seed = 24), SynthVoice.silence(2.0)), SynthVoice.white(4.5, -50.0, 25))
            val st = vadRun(VadPlus(endMs = endMs), x)
            val end = (firstFrame(st, Vad.State.ENDED_AFTER_SPEECH) + 1) * 20 - 2500
            assertTrue("endMs $endMs ended at $end", end in endMs - 150..endMs + 80)
        }
    }

    @Test fun vadIgnoresStationaryNoiseHumAndClicks() {
        for ((name, x) in listOf(
            "white" to SynthVoice.white(10.0, -30.0, 31),
            "pink" to SynthVoice.pink(10.0, -30.0, 32),
            "hum" to SynthVoice.mix(SynthVoice.hum(10.0, -25.0), SynthVoice.white(10.0, -60.0, 33)),
            "clicks" to SynthVoice.mix(SynthVoice.clicks(10.0, 0.3), SynthVoice.white(10.0, -55.0, 34)),
        )) {
            val st = vadRun(VadPlus(noSpeechMs = 60_000, maxMs = 60_000), x)
            assertFalse("$name started speech", st.drop(100).any { it == Vad.State.SPEECH })
        }
    }

    @Test fun lostPacketsNeitherEndNorStart() {
        val x = SynthVoice.mix(SynthVoice.concat(SynthVoice.silence(1.0), SynthVoice.speech(2.0, -26.0, seed = 41), SynthVoice.silence(2.0)), SynthVoice.white(5.0, -50.0, 42))
        val st = vadRun(VadPlus(endMs = 800), x, lostFrames = setOf(20, 21, 22, 100, 101, 102))
        assertTrue(st.subList(0, 50).none { it == Vad.State.SPEECH })
        assertTrue(st.subList(60, 150).none { it == Vad.State.ENDED_AFTER_SPEECH })
        // The link dies mid-speech (every frame lost from 2.0 s): the end comes 200 ms + endMs later.
        val st2 = vadRun(VadPlus(endMs = 800), x, lostFrames = (100 until 250).toSet())
        val end = (firstFrame(st2, Vad.State.ENDED_AFTER_SPEECH) + 1) * 20
        assertTrue("ended at $end", end in 2000 + 200 + 800 - 40..2000 + 200 + 800 + 60)
    }

    @Test fun vadPlusIsADropInForVad() {
        val det: SpeechDetector = VadPlus.gemini()
        val x = SynthVoice.mix(SynthVoice.concat(SynthVoice.silence(1.0), SynthVoice.speech(1.5, -26.0, seed = 51), SynthVoice.silence(1.5)), SynthVoice.white(4.0, -55.0, 52))
        val pcm = SynthVoice.shorts(x)
        val sizes = intArrayOf(160, 333, 480)
        var i = 0; var k = 0
        while (i < pcm.size) { val n = minOf(sizes[k++ % 3], pcm.size - i); det.feed(pcm.copyOfRange(i, i + n)); i += n }
        assertEquals(Vad.State.ENDED_AFTER_SPEECH, det.state)
        assertTrue(det.heardSpeech)
        assertTrue("start ${det.speechStartMs}", det.speechStartMs in 900.0..1200.0)
        val old: SpeechDetector = Vad.gemini()
        var j = 0
        while (j < pcm.size && !old.ended) { val n = minOf(320, pcm.size - j); old.feed(pcm.copyOfRange(j, j + n)); j += n }
        assertTrue(old.heardSpeech)
    }

    @Test fun micDspGivesPcmAndALevelForAnyChunkSize() {
        val d = MicDsp()
        val x = SynthVoice.shorts(SynthVoice.mix(SynthVoice.concat(SynthVoice.white(0.5, -60.0), SynthVoice.speech(2.0, -35.0, seed = 61)), SynthVoice.white(2.5, -60.0, 62)))
        var outN = 0
        var i = 0
        while (i < x.size) { val n = minOf(777, x.size - i); outN += d.process(x.copyOfRange(i, i + n)).size; i += n }
        assertEquals((x.size / 320) * 320, outN)
        assertTrue(d.detector.heardSpeech)
        assertEquals(320, d.lost().size)
    }
}
