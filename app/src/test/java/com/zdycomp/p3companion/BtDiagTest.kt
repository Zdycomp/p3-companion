package com.zdycomp.p3companion

import org.junit.Assert.assertEquals
import org.junit.Test

/** 3.9 Bluetooth drop diagnostics: how profile events and a lost link read in the log. */
class BtDiagTest {

    @Test fun profileAndAudioStates() {
        assertEquals("connected", BtDiag.profileState(2))
        assertEquals("disconnecting", BtDiag.profileState(3))
        assertEquals("state 9", BtDiag.profileState(9))
        assertEquals("audio on (SCO)", BtDiag.headsetAudio(12))
        assertEquals("audio off", BtDiag.headsetAudio(10))
        assertEquals("playing", BtDiag.a2dpPlaying(10))
        assertEquals("not playing", BtDiag.a2dpPlaying(11))
    }

    @Test fun eventLines() {
        assertEquals("BT event 01:21:51.204 hands-free connected → disconnected (SPP link up)",
            BtDiag.event("01:21:51.204", "hands-free", "disconnected", "connected", true))
        assertEquals("BT event 01:22:18.010 ACL disconnected (SPP link down)",
            BtDiag.event("01:22:18.010", "ACL", "disconnected", null, false))
    }

    @Test fun lostLineGivesTheGaps() {
        assertEquals("Link lost: read failed (bt socket closed, read return: -1); last byte received 64.2 s ago, " +
            "last request 7500 sent 64.5 s ago, last write 3.0 s ago, link up 120.0 s",
            BtDiag.lostLine("read failed (bt socket closed, read return: -1)", 64_200, "7500", 64_500, 3_000, 120_000))
        assertEquals("Link lost: x; last byte received never, no request sent on this link, last write never, link up 24.0 s",
            BtDiag.lostLine("x", -1, null, -1, -1, 24_000))
    }
}
