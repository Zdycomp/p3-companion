package com.zdycomp.p3companion

import com.zdycomp.p3companion.DropRecovery.Loss
import com.zdycomp.p3companion.DropRecovery.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.9.1: telling a Bluetooth drop from an ordinary link loss, and when to try again after one. */
class DropRecoveryTest {

    private val upLong = 6_831_100L // the 3.9.0 log: link up 1.9 h

    @Test fun ordinaryLossKeepsTheBackoff() {
        val r = DropRecovery()
        assertEquals(Loss.NORMAL, r.linkLost(100_000, upLong, reconnect = true))
        assertFalse(r.settling)
        assertFalse(r.dropped)
        assertEquals(listOf(2_000L, 5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L, 60_000L),
            (0..7).map(DropRecovery::backoffMs))
        assertEquals(2_000L, DropRecovery.backoffMs(-1))
    }

    @Test fun aclDownJustBeforeTheLossIsADrop() {
        val r = DropRecovery()
        r.aclDisconnected(100_000)
        assertEquals(Loss.DROPPED_BLUETOOTH, r.linkLost(101_500, upLong, reconnect = false))
        assertTrue(r.settling)
        assertTrue(r.dropped)
    }

    @Test fun aclDownLongBeforeTheLossIsNot() {
        val r = DropRecovery()
        r.aclDisconnected(100_000)
        r.aclConnected(100_500)
        assertEquals(Loss.NORMAL, r.linkLost(102_001, upLong, reconnect = false))
        assertFalse(r.settling)
    }

    @Test fun aclDownJustAfterTheLossTurnsItIntoADrop() {
        // 3.9.0 log: link lost 19:17:08, ACL disconnected 19:17:09.982 (the 2 s retry was already scheduled).
        val r = DropRecovery()
        assertEquals(Loss.NORMAL, r.linkLost(100_000, upLong, reconnect = true))
        assertTrue(r.aclDisconnected(101_982))
        assertTrue(r.settling)
        assertTrue(r.dropped)
        assertFalse("a second ACL event doesn't mark it again", r.aclDisconnected(101_990))
    }

    @Test fun aclDownMoreThanTwoSecondsAfterTheLossIsNot() {
        val r = DropRecovery()
        r.linkLost(100_000, upLong, reconnect = true)
        assertFalse(r.aclDisconnected(102_001))
        assertFalse(r.settling)
    }

    @Test fun afterADropItWaitsForTheAclThenHandsFreeThenOneAndAHalfSeconds() {
        val r = DropRecovery()
        r.linkLost(100_000, upLong, reconnect = true)
        r.aclDisconnected(101_982)
        // No ACL yet: wait for it, at most 10 s from the drop.
        assertEquals(DropRecovery.Step(Phase.WAIT_ACL, 110_000), r.step(102_000))
        r.aclConnected(106_728)
        // ACL is back: wait up to 4 s for hands-free.
        assertEquals(DropRecovery.Step(Phase.WAIT_HANDS_FREE, 110_728), r.step(106_800))
        r.handsFree(1, 107_388) // connecting doesn't count
        assertEquals(Phase.WAIT_HANDS_FREE, r.step(107_400).phase)
        r.handsFree(2, 107_392)
        assertEquals(DropRecovery.Step(Phase.GO, 108_892, handsFreeConnected = true), r.step(107_400))
    }

    @Test fun handsFreeThatNeverConnectsSettlesAfterFourSeconds() {
        val r = DropRecovery()
        r.aclDisconnected(100_000)
        r.linkLost(100_100, upLong, reconnect = true)
        r.aclConnected(103_000)
        assertEquals(Phase.WAIT_HANDS_FREE, r.step(106_999).phase)
        assertEquals(DropRecovery.Step(Phase.GO, 108_500), r.step(107_000))
        assertEquals("late checks connect at once", DropRecovery.Step(Phase.GO, 120_000), r.step(120_000))
    }

    @Test fun handsFreeFromBeforeTheAclCameBackDoesNotCount() {
        val r = DropRecovery()
        r.handsFree(2, 99_000)
        r.aclDisconnected(100_000)
        r.linkLost(100_100, upLong, reconnect = true)
        r.aclConnected(103_000)
        assertEquals(Phase.WAIT_HANDS_FREE, r.step(103_100).phase)
    }

    @Test fun aclThatNeverComesBackTriesAnywayAfterTenSeconds() {
        val r = DropRecovery()
        r.linkLost(100_000, upLong, reconnect = true)
        r.aclDisconnected(100_500)
        assertEquals(Phase.WAIT_ACL, r.step(109_999).phase)
        val go = r.step(110_000)
        assertEquals(Phase.GO, go.phase)
        assertTrue(go.aclTimedOut)
        assertEquals(110_000L, go.atMs)
    }

    @Test fun theSettleWaitIsUsedOnceThenTheBackoffIsBack() {
        val r = DropRecovery()
        r.aclDisconnected(100_000)
        r.linkLost(100_000, upLong, reconnect = true)
        r.attemptStarted()
        assertFalse(r.settling)
        assertTrue("still recovering for the summary", r.dropped)
    }

    @Test fun aReconnectThatDiesWithinFiveSecondsWaitsToo() {
        // 3.9.0 log 19:17:16: the reconnect opened, bound, then died 2.1 s in, with the ACL still up.
        val r = DropRecovery()
        r.aclDisconnected(100_000)
        r.linkLost(100_000, upLong, reconnect = true)
        r.aclConnected(106_728)
        r.handsFree(2, 107_392)
        r.attemptStarted()
        r.ready(108_500)
        assertEquals(Loss.DIED_EARLY, r.linkLost(108_600, 2_100, reconnect = true))
        assertTrue(r.settling)
        // The ACL is up and hands-free connected before this loss: wait 4 s for it to settle again, then 1.5 s.
        assertEquals(DropRecovery.Step(Phase.WAIT_HANDS_FREE, 112_600), r.step(108_700))
        assertEquals(DropRecovery.Step(Phase.GO, 114_100), r.step(112_600))
    }

    @Test fun earlyDeathRules() {
        assertEquals(Loss.NORMAL, DropRecovery().linkLost(100_000, 5_000, reconnect = true))
        assertEquals(Loss.DIED_EARLY, DropRecovery().linkLost(100_000, 4_999, reconnect = true))
        assertEquals("a connect the person tapped keeps the usual backoff",
            Loss.NORMAL, DropRecovery().linkLost(100_000, 2_100, reconnect = false))
    }

    @Test fun earlyDeathFollowedByAnAclDropBecomesADrop() {
        val r = DropRecovery()
        assertEquals(Loss.DIED_EARLY, r.linkLost(100_000, 2_000, reconnect = true))
        assertFalse(r.dropped)
        assertTrue(r.aclDisconnected(101_000))
        assertTrue(r.dropped)
        assertEquals(Phase.WAIT_ACL, r.step(101_100).phase)
    }

    @Test fun summaryOnceTheLinkHasStayedUp() {
        val r = DropRecovery()
        r.linkLost(100_000, upLong, reconnect = true)
        r.aclDisconnected(101_982)
        r.attemptStarted() // 19:17:11 try, died early
        r.ready(108_000)
        r.linkLost(108_100, 2_100, reconnect = true)
        r.attemptStarted() // second try
        r.ready(119_000)
        assertNull("not until it has been up 5 s", r.stableSummary(123_000, 118_100))
        assertEquals("Back after the glasses dropped Bluetooth: 19 s, 2 tries", r.stableSummary(123_100, 118_100))
        assertFalse(r.dropped)
        assertNull("only once", r.stableSummary(130_000, 118_100))
    }

    @Test fun aRetryAlreadyUnderwayWhenTheDropIsSeenCounts() {
        val r = DropRecovery()
        r.linkLost(100_000, upLong, reconnect = true)
        r.attemptStarted() // the short retry fired before the ACL event
        assertTrue(r.aclDisconnected(101_900))
        r.ready(105_000)
        assertEquals("Back after the glasses dropped Bluetooth: 5 s, 1 try", r.stableSummary(111_000, 104_000))
    }

    @Test fun noSummaryWithoutADrop() {
        val r = DropRecovery()
        r.linkLost(100_000, 2_000, reconnect = true)
        r.attemptStarted()
        r.ready(105_000)
        assertNull(r.stableSummary(120_000, 104_000))
    }

    @Test fun resetForgetsTheDrop() {
        val r = DropRecovery()
        r.aclDisconnected(100_000)
        r.linkLost(100_000, upLong, reconnect = true)
        r.reset()
        assertFalse(r.settling)
        assertFalse(r.dropped)
        assertFalse(r.aclDisconnected(101_000))
    }

    @Test fun noAclEventSeenCountsAsUp() {
        val r = DropRecovery()
        r.linkLost(100_000, 1_000, reconnect = true)
        assertEquals(DropRecovery.Step(Phase.WAIT_HANDS_FREE, 104_000), r.step(100_100))
    }

    @Test fun plainWords() {
        assertEquals("The glasses dropped Bluetooth; reconnecting when they're ready", DropRecovery.WAIT_TEXT)
        assertEquals("Back after the glasses dropped Bluetooth: 1 s, 0 tries", DropRecovery.summary(1_400, 0))
        assertEquals("The new link died 2.1 s after opening; the next try waits for the glasses to settle",
            DropRecovery.lossLine(Loss.DIED_EARLY, 2_100))
        assertTrue(DropRecovery.lossLine(Loss.DROPPED_BLUETOOTH, 0)!!.startsWith("The glasses dropped Bluetooth"))
        assertNull(DropRecovery.lossLine(Loss.NORMAL, 0))
        assertEquals("Hands-free connected; connecting in 1.5 s",
            DropRecovery.stepLine(DropRecovery.Step(Phase.GO, 0, handsFreeConnected = true)))
        assertEquals("The glasses didn't come back on Bluetooth in 10 s; trying anyway",
            DropRecovery.stepLine(DropRecovery.Step(Phase.GO, 0, aclTimedOut = true)))
    }
}
