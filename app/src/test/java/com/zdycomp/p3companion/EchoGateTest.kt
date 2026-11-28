package com.zdycomp.p3companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoGateTest {
    @Test fun gateClosesForTheReplyPlusItsTail() {
        val g = EchoGate()
        assertTrue(g.isOpen(0))
        g.playbackStarted(1_000)
        assertFalse(g.isOpen(5_000))
        assertEquals(Long.MAX_VALUE, g.waitMs(5_000))
        g.playbackEnded(6_000)
        assertFalse(g.isOpen(6_499))
        assertTrue(g.isOpen(6_500))
        // A measured round trip sets the tail: max(300, D + 150).
        assertEquals(430, EchoGate(280.0).tailMs)
        assertEquals(300, EchoGate(50.0).tailMs)
        g.playbackStarted(7_000); g.openNow(7_100)
        assertTrue(g.isOpen(7_100))
    }

    @Test fun echoDelayIsMeasured() {
        // Mic = half the reference 280 ms later, plus noise at −50 dBFS.
        val ref = SynthVoice.speech(3.0, -20.0, seed = 3)
        val delay = (0.28 * SynthVoice.RATE).toInt()
        val mic = SynthVoice.mix(SynthVoice.concat(DoubleArray(delay), ref.map { 0.5 * it }.toDoubleArray()), SynthVoice.white(3.4, -50.0, 4))
        val r = EchoDelay.measure(EchoDelay.frameDb(SynthVoice.shorts(ref), SynthVoice.RATE), EchoDelay.frameDb(SynthVoice.shorts(mic), SynthVoice.RATE))
        assertNotNull(r)
        assertEquals(280.0, r!!.delayMs, 10.0)
        assertTrue(r.ncc > 0.5)
        // Unrelated sound gives no delay.
        assertNull(EchoDelay.measure(EchoDelay.frameDb(SynthVoice.shorts(ref), SynthVoice.RATE),
            EchoDelay.frameDb(SynthVoice.shorts(SynthVoice.white(3.0, -30.0, 9)), SynthVoice.RATE)))
        assertEquals(272.0, EchoDelay.blend(270.0, 280.0), 1e-9)
    }

    /** Feeds [mic] through the chain's analysis (gate closed, as during a reply) into a barge-in detector. */
    private fun bargeAt(mic: DoubleArray): Int {
        val chain = VoiceChain()
        // Learn the room first (gate open), then the reply plays.
        SynthVoice.frames(SynthVoice.white(1.0, -55.0, 21)).forEach { chain.process(it) }
        val b = BargeIn(withReference = false)
        SynthVoice.frames(mic).forEachIndexed { i, f ->
            val o = chain.process(f, frozen = true)
            if (b.feed(o.features.eAbs, null, o.features.zcr, o.features.deltaE)) return i
        }
        return -1
    }

    @Test fun bargeInFiresOnRealSpeechOverTheEcho() {
        // The reply's echo (speaker A at −36 dBFS) for 3 s; from 1 s you talk over it 12 dB louder (speaker B).
        val echo = SynthVoice.mix(SynthVoice.speech(3.0, -36.0, seed = 31), SynthVoice.white(3.0, -55.0, 32))
        val you = SynthVoice.concat(SynthVoice.silence(1.0), SynthVoice.speech(2.0, -24.0, speakerB = true, seed = 33))
        val at = bargeAt(SynthVoice.mix(echo, you))
        assertTrue("barge-in at frame $at", at in 50..50 + 25)
    }

    @Test fun echoAloneNeverBargesIn() {
        // 10 s of the reply's echo with ±3 dB level wobble.
        val parts = (0 until 10).map { k -> SynthVoice.speech(1.0, -36.0 + if (k % 2 == 0) 3.0 else -3.0, seed = 40L + k) }
        val echo = SynthVoice.mix(SynthVoice.concat(*parts.toTypedArray()), SynthVoice.white(10.0, -55.0, 50))
        assertEquals(-1, bargeAt(echo))
    }
}
