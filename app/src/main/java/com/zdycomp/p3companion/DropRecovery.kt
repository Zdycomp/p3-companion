package com.zdycomp.p3companion

import java.util.Locale

/**
 * 3.9.1: reconnecting after the glasses drop their whole Bluetooth connection (pure logic, JVM-tested).
 *
 * The owner's 3.9.0 log: after 1.9 h up, a touch (C107) and then the SPP link, the ACL and hands-free
 * all went down within 2 s. The 2 s retry reached the glasses while they were still coming back, so the
 * new link died 2.1 s after opening. So:
 *  - a link loss within [DROP_WINDOW_MS] of an ACL disconnect (either order) is "the glasses dropped
 *    Bluetooth", and the next try waits for the ACL to come back, then for hands-free to settle
 *    (connected, or [HANDS_FREE_MS] after the ACL, whichever first), then [SETTLE_EXTRA_MS] more;
 *  - a reconnect whose link dies within [EARLY_DEATH_MS] of opening makes the next try wait the same way;
 *  - every other case keeps the usual backoff ([backoffMs]).
 * If the ACL hasn't come back [ACL_WAIT_MAX_MS] after the drop, it tries anyway: our connect pages the
 * glasses, which some firmware needs before they reconnect at all.
 *
 * Times are elapsedRealtime milliseconds; -1 means "never". Thread-safe: events arrive on the main
 * thread, link losses on the reader thread.
 */
class DropRecovery {

    enum class Loss { NORMAL, DROPPED_BLUETOOTH, DIED_EARLY }
    enum class Phase { WAIT_ACL, WAIT_HANDS_FREE, GO }

    /** What to do next while [settling]: wait until [atMs] (or an event), or connect at [atMs]. */
    data class Step(val phase: Phase, val atMs: Long, val handsFreeConnected: Boolean = false, val aclTimedOut: Boolean = false)

    private var aclUpAt = -1L
    private var aclDownAt = -1L
    private var handsFreeUpAt = -1L
    private var lastLossAt = -1L

    /** True from a drop (or an early death) until the next connect attempt starts. */
    @Volatile var settling = false; private set
    private var settleSince = -1L

    /** A drop is being recovered from: set at the drop, cleared once the link has stayed up. */
    @Volatile var dropped = false; private set
    private var dropAt = -1L
    private var tries = 0
    /** Connect attempts since the last link loss (an ACL event can mark a drop after a retry started). */
    private var attemptsSinceLoss = 0
    private var readyAt = -1L

    @Synchronized fun aclConnected(now: Long) { aclUpAt = now }

    /**
     * The glasses' ACL went down. Returns true when this turns a link loss of the last [DROP_WINDOW_MS]
     * into a drop (the ACL event came after the loss), so the caller cancels the short retry.
     */
    @Synchronized fun aclDisconnected(now: Long): Boolean {
        aclDownAt = now
        if (lastLossAt >= 0 && now - lastLossAt <= DROP_WINDOW_MS && !(dropped && settling)) {
            markDrop(lastLossAt)
            return true
        }
        return false
    }

    /** BluetoothProfile.STATE_*: only "connected" (2) matters here. */
    @Synchronized fun handsFree(state: Int, now: Long) { if (state == 2) handsFreeUpAt = now }

    /**
     * The SPP link was lost after being up [upMs]. [reconnect] is true when that link came from an
     * automatic reconnect (not a connect the person tapped).
     */
    @Synchronized fun linkLost(now: Long, upMs: Long, reconnect: Boolean): Loss {
        lastLossAt = now
        attemptsSinceLoss = 0
        readyAt = -1L
        if (aclDownAt >= 0 && now - aclDownAt in 0..DROP_WINDOW_MS) { markDrop(now); return Loss.DROPPED_BLUETOOTH }
        if (reconnect && upMs in 0 until EARLY_DEATH_MS) {
            settling = true
            settleSince = now
            return Loss.DIED_EARLY
        }
        return Loss.NORMAL
    }

    private fun markDrop(at: Long) {
        if (!dropped) { dropped = true; dropAt = at; tries = attemptsSinceLoss }
        settling = true
        settleSince = at
    }

    /** A connect attempt starts: the settle wait (if any) is used up, and it counts as a try. */
    @Synchronized fun attemptStarted() {
        settling = false
        attemptsSinceLoss++
        if (dropped) tries++
    }

    /** The link is ready again (signed in). */
    @Synchronized fun ready(now: Long) {
        settling = false
        readyAt = now
    }

    /**
     * Once the link has stayed up [EARLY_DEATH_MS] after a drop, the one-line summary (and the drop is
     * over); null otherwise. [openedAt] is when the current link opened.
     */
    @Synchronized fun stableSummary(now: Long, openedAt: Long): String? {
        if (!dropped || readyAt < 0 || now - openedAt < EARLY_DEATH_MS) return null
        val line = summary(readyAt - dropAt, tries)
        reset()
        return line
    }

    /** The person disconnected or the app paused: forget the drop. */
    @Synchronized fun reset() {
        settling = false
        settleSince = -1L
        dropped = false
        dropAt = -1L
        tries = 0
        readyAt = -1L
        lastLossAt = -1L
        attemptsSinceLoss = 0
    }

    /** True when the last ACL event says the glasses are connected (or no ACL event was seen yet). */
    private fun aclIsUp() = aclDownAt < 0 || aclUpAt > aclDownAt

    /** The next step while [settling]. */
    @Synchronized fun step(now: Long): Step {
        if (!aclIsUp()) {
            val cap = settleSince + ACL_WAIT_MAX_MS
            return if (now >= cap) Step(Phase.GO, now, aclTimedOut = true) else Step(Phase.WAIT_ACL, cap)
        }
        val from = maxOf(aclUpAt, settleSince)
        if (handsFreeUpAt >= from) return Step(Phase.GO, maxOf(now, handsFreeUpAt + SETTLE_EXTRA_MS), handsFreeConnected = true)
        val limit = from + HANDS_FREE_MS
        return if (now < limit) Step(Phase.WAIT_HANDS_FREE, limit) else Step(Phase.GO, maxOf(now, limit + SETTLE_EXTRA_MS))
    }

    companion object {
        const val DROP_WINDOW_MS = 2_000L
        const val HANDS_FREE_MS = 4_000L
        const val SETTLE_EXTRA_MS = 1_500L
        const val EARLY_DEATH_MS = 5_000L
        const val ACL_WAIT_MAX_MS = 10_000L

        /** The usual retry backoff (unchanged since 1.x): 2, 5, 10, 20, 40, then 60 s. */
        private val BACKOFF_MS = longArrayOf(2_000, 5_000, 10_000, 20_000, 40_000, 60_000)
        fun backoffMs(attempt: Int): Long = BACKOFF_MS[attempt.coerceIn(0, BACKOFF_MS.lastIndex)]

        const val WAIT_TEXT = "The glasses dropped Bluetooth; reconnecting when they're ready"

        fun summary(ms: Long, tries: Int): String =
            "Back after the glasses dropped Bluetooth: ${(ms + 500) / 1000} s, $tries " + if (tries == 1) "try" else "tries"

        fun lossLine(loss: Loss, upMs: Long): String? = when (loss) {
            Loss.DROPPED_BLUETOOTH -> "The glasses dropped Bluetooth (the whole connection went down, not just the app's link). " +
                "Reconnecting once they're back: waiting for them, then hands-free, then 1.5 s"
            Loss.DIED_EARLY -> "The new link died " + String.format(Locale.US, "%.1f s", upMs / 1000.0) +
                " after opening; the next try waits for the glasses to settle"
            Loss.NORMAL -> null
        }

        fun stepLine(s: Step): String = when (s.phase) {
            Phase.WAIT_ACL -> "Waiting for the glasses to come back on Bluetooth"
            Phase.WAIT_HANDS_FREE -> "The glasses are back on Bluetooth; waiting for hands-free to settle"
            Phase.GO -> when {
                s.aclTimedOut -> "The glasses didn't come back on Bluetooth in ${ACL_WAIT_MAX_MS / 1000} s; trying anyway"
                s.handsFreeConnected -> "Hands-free connected; connecting in 1.5 s"
                else -> "Hands-free didn't settle in 4 s; connecting in 1.5 s"
            }
        }
    }
}
