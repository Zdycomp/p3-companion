package com.zdycomp.p3companion

import java.util.Locale

/**
 * 3.9 Bluetooth drop diagnostics, the pure parts (JVM-tested): how the glasses' ACL and profile events
 * (hands-free, A2DP) and a lost SPP link read in the connection log, so the next log shows whether a
 * profile change happens at the same moment as an SPP drop. Logging only: nothing here changes the
 * connect order, timings or retries.
 */
object BtDiag {

    /** BluetoothProfile.STATE_*: 0 disconnected, 1 connecting, 2 connected, 3 disconnecting. */
    fun profileState(s: Int): String = when (s) {
        0 -> "disconnected"
        1 -> "connecting"
        2 -> "connected"
        3 -> "disconnecting"
        else -> "state $s"
    }

    /** BluetoothHeadset.STATE_AUDIO_*: 10 disconnected, 11 connecting, 12 connected. */
    fun headsetAudio(s: Int): String = when (s) {
        10 -> "audio off"
        11 -> "audio connecting"
        12 -> "audio on (SCO)"
        else -> "audio state $s"
    }

    /** BluetoothA2dp.STATE_PLAYING (10) / STATE_NOT_PLAYING (11). */
    fun a2dpPlaying(s: Int): String = when (s) {
        10 -> "playing"
        11 -> "not playing"
        else -> "playing state $s"
    }

    /** "12:01:02.345 BT hands-free connected → disconnected (SPP link up)". [prev] < 0 when unknown. */
    fun event(clock: String, what: String, state: String, prev: String?, sppUp: Boolean): String =
        "BT event $clock $what " + (if (prev != null) "$prev → " else "") + state + if (sppUp) " (SPP link up)" else " (SPP link down)"

    /** Seconds with one decimal, or "never" for a negative age. */
    fun ago(ms: Long): String = if (ms < 0) "never" else String.format(Locale.US, "%.1f s ago", ms / 1000.0)

    /**
     * "Link lost: read failed (…); last byte received 64.2 s ago, last request 7500 sent 64.5 s ago,
     * last write 3.1 s ago, link up 88.0 s". Ages are -1 when nothing happened on this link.
     */
    fun lostLine(reason: String, sinceRxMs: Long, lastRequest: String?, sinceRequestMs: Long, sinceWriteMs: Long, upMs: Long): String =
        "Link lost: $reason; last byte received ${ago(sinceRxMs)}, " +
            (if (lastRequest != null) "last request $lastRequest sent ${ago(sinceRequestMs)}" else "no request sent on this link") +
            ", last write ${ago(sinceWriteMs)}, link up " + String.format(Locale.US, "%.1f s", upMs / 1000.0)
}
