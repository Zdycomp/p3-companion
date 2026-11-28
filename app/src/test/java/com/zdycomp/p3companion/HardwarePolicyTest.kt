package com.zdycomp.p3companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwarePolicyTest {
    private val proven = mapOf(MicRoute.GLASSES_SPP to 1, MicRoute.GLASSES_SCO to 1, MicRoute.PHONE to 1)

    private fun inputs(
        linked: Boolean = true, useGlasses: Boolean = true, age: Long = 60_000, live: Boolean = false, photo: Boolean = false,
        battery: Int? = 80, charging: Boolean = false, noise: Double? = -60.0, scores: Map<MicRoute, Int> = proven,
        spp: Boolean = true, session: Boolean = true, warm: Boolean = false, pauseLive: Boolean = false,
    ) = RoutePolicy.Inputs(linked, useGlasses, age, live, photo, battery, charging, noise, scores, spp, session, warm, pauseLive)

    @Test fun voiceChatKeepsTheGlassesStreamWarm() {
        val d = RoutePolicy.decide(inputs())
        assertEquals(listOf(MicRoute.GLASSES_SPP, MicRoute.GLASSES_SCO, MicRoute.PHONE), d.order)
        assertTrue(d.why.contains("kept warm"))
    }

    @Test fun oneQuestionUsesCallAudioFirst() {
        assertEquals(listOf(MicRoute.GLASSES_SCO, MicRoute.GLASSES_SPP, MicRoute.PHONE), RoutePolicy.decide(inputs(session = false)).order)
        // Nothing proven yet: the data-link stream, then the phone.
        assertEquals(listOf(MicRoute.GLASSES_SPP, MicRoute.PHONE), RoutePolicy.decide(inputs(session = false, scores = emptyMap())).order)
    }

    @Test fun linkStateBatteryLiveViewAndTransfersFallBackToThePhone() {
        assertEquals(listOf(MicRoute.PHONE), RoutePolicy.decide(inputs(linked = false)).order)
        assertEquals(listOf(MicRoute.PHONE), RoutePolicy.decide(inputs(useGlasses = false)).order)
        assertEquals(listOf(MicRoute.PHONE), RoutePolicy.decide(inputs(age = 1_000)).order)
        val low = RoutePolicy.decide(inputs(battery = 12))
        assertEquals(listOf(MicRoute.PHONE), low.order)
        assertTrue(low.why.contains("12%"))
        // Charging lifts the battery rule.
        assertEquals(MicRoute.GLASSES_SPP, RoutePolicy.decide(inputs(battery = 12, charging = true)).first)
        assertEquals(listOf(MicRoute.PHONE), RoutePolicy.decide(inputs(photo = true)).order)
        assertEquals(listOf(MicRoute.PHONE), RoutePolicy.decide(inputs(live = true)).order)
        val paused = RoutePolicy.decide(inputs(live = true, pauseLive = true))
        assertTrue(paused.pauseLiveView)
        assertEquals(MicRoute.GLASSES_SPP, paused.first)
    }

    @Test fun aStreamTheGlassesStartedIsUsedEvenRightAfterConnecting() {
        assertEquals(MicRoute.GLASSES_SPP, RoutePolicy.decide(inputs(age = 500, warm = true, session = false)).first)
    }

    @Test fun noisyPlacesPreferTheCallAudioForOneQuestion() {
        val d = RoutePolicy.decide(inputs(session = false, noise = -30.0))
        assertEquals(MicRoute.GLASSES_SCO, d.first)
        assertTrue(d.why.contains("noisy"))
    }

    @Test fun failedRoutesAreSkipped() {
        val s = mapOf(MicRoute.GLASSES_SPP to VoiceChoice.SKIP_AT, MicRoute.GLASSES_SCO to 1)
        assertEquals(listOf(MicRoute.GLASSES_SCO, MicRoute.PHONE), RoutePolicy.decide(inputs(scores = s)).order)
        // No way to turn the data link into words: not tried.
        assertEquals(listOf(MicRoute.GLASSES_SCO, MicRoute.PHONE), RoutePolicy.decide(inputs(spp = false)).order)
    }

    @Test fun gestureMapRoundTripsAndKeepsDefaults() {
        val m = GestureMap.parse("TAP:TAKE_PHOTO,BOGUS:NOTHING,DOUBLE_TAP:NOPE")
        assertEquals(HwAction.TAKE_PHOTO, m[Gesture.TAP])
        assertEquals(HwAction.VOICE_CHAT_TOGGLE, m[Gesture.DOUBLE_TAP])
        assertEquals(Gesture.values().size, m.size)
        assertEquals(m, GestureMap.parse(GestureMap.format(m)))
        assertEquals(HwAction.VOICE_TURN, GestureMap.actionFor(GestureMap.parse(null), Gesture.TAP))
        val codes = mapOf("C107:01" to Gesture.LONG_PRESS, "C101:" to Gesture.TAP)
        assertEquals(codes, GestureMap.parseCodes(GestureMap.formatCodes(codes)))
        assertEquals("C107:0A01", GestureMap.codeKey("C107", byteArrayOf(0x0A, 0x01)))
    }

    @Test fun touchesBecomeTapsAndDoubleTaps() {
        val c = TouchClassifier()
        assertTrue(c.touch("C107", ByteArray(0), 1_000).isEmpty())
        assertTrue(c.waiting)
        assertEquals(listOf(Gesture.DOUBLE_TAP), c.touch("C107", ByteArray(0), 1_300))
        assertTrue(c.touch("C107", ByteArray(0), 3_000).isEmpty())
        assertTrue(c.poll(3_200).isEmpty())
        assertEquals(listOf(Gesture.TAP), c.poll(3_450))
        // Exact repeats within the debounce are dropped.
        val d = TouchClassifier(waitForDouble = false)
        assertEquals(listOf(Gesture.TAP), d.touch("C101", ByteArray(0), 0))
        assertTrue(d.touch("C101", ByteArray(0), 50).isEmpty())
        assertEquals(listOf(Gesture.TAP), d.touch("C101", ByteArray(0), 700))
        assertEquals(700, d.lastGapMs)
    }

    @Test fun learnedCodesWinOverTiming() {
        val c = TouchClassifier(mapOf("C107:02" to Gesture.LONG_PRESS, "C107:03" to Gesture.SWIPE_FORWARD))
        assertEquals(listOf(Gesture.LONG_PRESS), c.touch("C107", byteArrayOf(2), 0))
        // A pending tap is decided first, then the learned gesture.
        assertTrue(c.touch("C107", byteArrayOf(1), 1_000).isEmpty())
        assertEquals(listOf(Gesture.TAP, Gesture.SWIPE_FORWARD), c.touch("C107", byteArrayOf(3), 1_200))
        assertFalse(c.waiting)
    }

    @Test fun replyVolumeFollowsTheNoiseWithinTheCap() {
        assertEquals(9, VolumePolicy.replyStep(8, 15, -40.0, 100))
        assertEquals(10, VolumePolicy.replyStep(8, 15, -30.0, 100))
        assertEquals(9, VolumePolicy.replyStep(8, 15, -30.0, 60))
        assertEquals(7, VolumePolicy.replyStep(8, 15, -70.0, 100))
        assertEquals(8, VolumePolicy.replyStep(8, 15, -55.0, 100))
        assertEquals(8, VolumePolicy.replyStep(8, 15, null, 100))
        // Already above the cap: never pushed higher, never pulled down by the cap.
        assertEquals(14, VolumePolicy.replyStep(14, 15, -30.0, 50))
        assertEquals(1, VolumePolicy.replyStep(1, 15, -80.0, 100))
        assertEquals(5, VolumePolicy.mapStep(10, 15, 7))
        assertEquals(7, VolumePolicy.mapStep(15, 15, 7))
        assertEquals(67, VolumePolicy.percent(10, 15))
    }

    @Test fun uploadFormatIsRememberedAndFallsBack() {
        assertTrue(AudioUpload.useOgg(AudioUpload.UNKNOWN, havePackets = true))
        assertFalse(AudioUpload.useOgg(AudioUpload.UNKNOWN, havePackets = false))
        assertFalse(AudioUpload.useOgg(AudioUpload.OGG_REFUSED, havePackets = true))
        assertTrue(AudioUpload.isFormatRefusal(400, "Unsupported MIME type: audio/ogg"))
        assertTrue(AudioUpload.isFormatRefusal(400, "Request contains an invalid argument."))
        assertTrue(AudioUpload.isFormatRefusal(415, null))
        assertFalse(AudioUpload.isFormatRefusal(403, "API key not valid"))
        assertFalse(AudioUpload.isFormatRefusal(429, "quota"))
        assertEquals(AudioUpload.OGG_WORKS, AudioUpload.after(AudioUpload.UNKNOWN, ok = true, refused = false))
        assertEquals(AudioUpload.OGG_REFUSED, AudioUpload.after(AudioUpload.UNKNOWN, ok = false, refused = true))
        assertEquals(AudioUpload.OGG_WORKS, AudioUpload.after(AudioUpload.OGG_WORKS, ok = false, refused = false))
    }

    @Test fun promptLanguageWritesOnlyAListedDifferentTag() {
        val list = PromptLanguage.tags("en\u0000\u0000\u0000\u0000en\u0000\u0000\u0000\u0000zh-cn\u0000".toByteArray(Charsets.US_ASCII))
        assertEquals(listOf("en", "en", "zh-cn"), list)
        assertEquals(listOf("en"), PromptLanguage.tags("\"en\"".toByteArray()))
        assertFalse(PromptLanguage.mayWrite(list, "en", "en"))
        assertTrue(PromptLanguage.mayWrite(list, "zh-cn", "en"))
        assertFalse(PromptLanguage.mayWrite(listOf("zh-cn"), "zh-cn", "en"))
        assertEquals(listOf<Byte>(0x65, 0x6E, 0, 0, 0, 0), PromptLanguage.payload("en").toList())
    }

    @Test fun spectrumTellsWidebandFromNarrowband() {
        val wide = SynthVoice.shorts(SynthVoice.mix(SynthVoice.speech(1.0, -26.0, seed = 3), SynthVoice.sine(1.0, 5_000.0, 0.02)))
        val narrow = SynthVoice.shorts(SynthVoice.sine(1.0, 1_000.0, 0.3))
        assertTrue(Spectrum.shareAbove(wide, 16_000, 4_000.0) > 0.01)
        assertTrue(Spectrum.shareAbove(narrow, 16_000, 4_000.0) < 0.003)
        assertTrue(Spectrum.verdict(0.0001).contains("narrowband"))
    }

    /** 4.1.1: without the glasses on Bluetooth, the tests that need them don't run (T13 only arms a read). */
    @Test fun hardwareTestsThatNeedTheGlassesWaitForBluetooth() {
        for (id in listOf("T1", "T2", "T4", "T5", "T7")) {
            assertFalse(id, HardwareTestsScreen.canRun(id, bluetoothConnected = false))
            assertTrue(id, HardwareTestsScreen.canRun(id, bluetoothConnected = true))
        }
        assertTrue(HardwareTestsScreen.canRun("T13", bluetoothConnected = false))
        assertTrue(HardwareTestsScreen.notConnectedText().startsWith("The glasses aren't connected over Bluetooth"))
    }
}
