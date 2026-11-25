package com.zdycomp.p3companion

import java.io.ByteArrayOutputStream

/**
 * 3.4: rebuilds live view pictures from head-0x1E frames. Pure Kotlin (JVM-tested), cheap, and it
 * never blocks: it runs on the Bluetooth reader thread and only copies bytes.
 *
 * Decoded from the owner's first live video log (docs/LOG-2026-11-25-liveview.txt), byte for byte:
 *   envelope   head 1E, cmd 8001 (push), divide LE2 exactly as on 0x4A photo frames:
 *              low 3 bits 1 first / 2 middle / 3 last (0 = the whole element in one frame),
 *              higher bits the element length hint ([Xk.Frame.logicalLength], 13 bits, header included).
 *              Captured: 9D29 9D2A 9D2B → first, middle, last, hint 0x59D = 1437 = 1432 + 5.
 *              (The high byte is not a picture id: it is the low byte of the length.)
 *   payload    [fragment index LE4: 0, 1, 2…][chunk]
 *   element    the chunks joined: [type 1][length LE4][body]; type 2 is H.264 and body is Annex-B,
 *              starting 00 00 00 01 67 (SPS). The length counts the body only (1432 = 583+583+283 − 3×4 − 5);
 *              the photo-style reading that counts the 5 header bytes is accepted too.
 *
 * Index 0 starts a picture. A gap, an index without a start, or a new index 0 before the picture
 * closed drops the unfinished picture cleanly; the next index 0 starts afresh.
 */
class LiveStream(private val maxPicture: Int = 1 shl 20) {

    enum class Step {
        /** A fragment that started a picture (index 0) and didn't finish it. */
        STARTED,
        /** A fragment that added to the picture. */
        MORE,
        /** This fragment finished a picture: [Fed.element] holds it. */
        DONE,
        /** This fragment broke the picture being built (gap, size, bad header). */
        DROPPED,
        /** A fragment of a picture that was already dropped (or never started). */
        ORPHAN,
    }

    /** One reassembled element: [type] 2 is H.264, [body] the bytes after the 5-byte header. */
    class Element(val type: Int, val body: ByteArray)

    /** What one frame did. [dropped] says why an unfinished picture was given up, if one was. */
    class Fed(val step: Step, val lastOfPicture: Boolean, val element: Element? = null, val dropped: String? = null)

    private val buf = ByteArrayOutputStream(4096)
    private var active = false
    private var next = 0L
    private var type = -1
    private var declared = -1L

    var fragments = 0L; private set
    var bytes = 0L; private set
    /** Whole H.264 pictures (type 2). */
    var videoUnits = 0L; private set
    /** Whole elements of any other type, by type. */
    val otherUnits = sortedMapOf<Int, Long>()
    var droppedPictures = 0L; private set
    var orphanFragments = 0L; private set
    var lastDropReason: String? = null; private set

    val otherCount: Long get() = otherUnits.values.sum()

    fun reset() {
        active = false
        buf.reset()
        next = 0
        type = -1
        declared = -1
    }

    fun feed(f: Xk.Frame): Fed {
        fragments++
        bytes += f.payload.size
        val p = f.payload
        val pos = f.fragment
        val last = pos == 3
        if (p.size < 4) return drop("short fragment (${p.size} B)", last)
        val idx = Xk.le32(p, 0)
        var replaced: String? = null
        if (idx == 0L) {
            if (active) replaced = dropNow("a new picture started at ${buf.size()} B of ${expected()}")
            active = true
            buf.reset()
            next = 0
            type = -1
            declared = -1
        } else if (!active) {
            orphanFragments++
            return Fed(Step.ORPHAN, last)
        } else if (idx != next) {
            return drop("fragment $idx, wanted $next", last)
        }
        next++
        buf.write(p, 4, p.size - 4)
        if (declared < 0 && buf.size() >= 5) {
            val b = buf.toByteArray()
            type = b[0].toInt() and 0xFF
            declared = Xk.le32(b, 1)
            if (declared > maxPicture) return drop("length field says $declared B", last)
        }
        val have = buf.size()
        if (have > maxPicture + 5) return drop("picture larger than $maxPicture B", last)
        if (declared >= 0) {
            when {
                have >= declared + 5 -> return complete(5, (declared + 5).toInt(), replaced)
                last && have.toLong() == declared && declared >= 5 -> return complete(5, have, replaced) // length counts the header
                last || pos == 0 -> return drop("the picture ended at $have B of ${declared + 5}", true)
            }
        } else if (last || pos == 0) {
            return drop("the picture ended before its header", true)
        }
        return Fed(if (idx == 0L) Step.STARTED else Step.MORE, last, dropped = replaced)
    }

    private fun expected() = if (declared >= 0) "${declared + 5}" else "?"

    private fun complete(from: Int, to: Int, replaced: String?): Fed {
        val all = buf.toByteArray()
        val e = Element(type, all.copyOfRange(from, to))
        if (type == Xk.MEDIA_H264) videoUnits++ else otherUnits[type] = (otherUnits[type] ?: 0L) + 1
        reset()
        return Fed(Step.DONE, true, e, replaced)
    }

    private fun dropNow(reason: String): String {
        droppedPictures++
        lastDropReason = reason
        reset()
        return reason
    }

    private fun drop(reason: String, last: Boolean): Fed {
        if (!active) { orphanFragments++; return Fed(Step.ORPHAN, last) }
        return Fed(Step.DROPPED, last, dropped = dropNow(reason))
    }
}

/**
 * 3.4: how the app acknowledges live view fragments. The photo path acks the last fragment of each
 * 0x4A element with `4A · 0009 · [01]`; the glasses may want the same for 0x1E. Tried in [ORDER].
 */
object LiveAck {
    enum class Strategy(val label: String) {
        /** `1E · order and divide of the fragment · 0009 · [01]` after every fragment. */
        PER_FRAGMENT("media ack per fragment"),
        /** `1E · our order · 0009 · divide 0 · [01]` after the last fragment of a picture (the photo ack, on 1E). */
        PER_PICTURE("media ack per picture"),
        /** `1E · our order · 0004 · [01, fragment order]` after every fragment (the push ack, on 1E). */
        PUSH_ACK("push ack per fragment"),
        /** Nothing sent (3.3 behaviour). */
        NONE("no acks"),
    }

    val ORDER = listOf(Strategy.PER_FRAGMENT, Strategy.PER_PICTURE, Strategy.PUSH_ACK, Strategy.NONE)

    fun parse(name: String?): Strategy? = name?.let { n -> Strategy.values().firstOrNull { it.name == n } }

    /** The frames to send for [f] under [s]. [order] hands out our next send order when one is needed. */
    fun framesFor(s: Strategy, f: Xk.Frame, lastOfPicture: Boolean, order: () -> Int): List<Xk.Frame> {
        if (f.head != Xk.HEAD_LIVE || f.isAck || !f.fromDevice) return emptyList()
        return when (s) {
            Strategy.PER_FRAGMENT -> listOf(Xk.Frame(Xk.HEAD_LIVE, f.order, 0x0009, f.divide, byteArrayOf(0x01)))
            Strategy.PER_PICTURE -> if (lastOfPicture) listOf(Xk.Frame(Xk.HEAD_LIVE, order() and 0xFF, 0x0009, 0, byteArrayOf(0x01))) else emptyList()
            Strategy.PUSH_ACK -> listOf(Xk.ack(order(), f.order, Xk.HEAD_LIVE))
            Strategy.NONE -> emptyList()
        }
    }
}

/**
 * 3.4: picks the ack strategy at run time. Each strategy gets [windowMs] of video; one that gives at
 * least [minFps] pictures a second is kept, otherwise the next one in [LiveAck.ORDER] is tried. When
 * none reaches it, the best one measured is kept. A saved strategy starts locked; if it falls under
 * [minFps] the search runs again, once per session. Time comes from the caller (elapsedRealtime).
 */
class LiveAckPicker(saved: LiveAck.Strategy?, private val windowMs: Long = 5_000, private val minFps: Double = 2.0) {

    enum class Kind { MEASURED, SWITCHED, CHOSEN }

    /** [tried] gave [fps] over the last window; [now] is the strategy in use from here on. */
    data class Decision(val kind: Kind, val tried: LiveAck.Strategy, val fps: Double, val now: LiveAck.Strategy)

    @Volatile var current: LiveAck.Strategy = saved ?: LiveAck.ORDER.first(); private set
    @Volatile var locked = saved != null; private set
    private var fromSaved = saved != null
    private var researched = false
    /** fps each strategy gave in this search. */
    val results = LinkedHashMap<LiveAck.Strategy, Double>()
    private var windowStart = -1L
    private var pictures = 0

    /** The stream stopped or reopened: the next fragment starts a new window. */
    @Synchronized fun restart() { windowStart = -1; pictures = 0 }

    @Synchronized fun onFragment(now: Long) { if (windowStart < 0) { windowStart = now; pictures = 0 } }

    @Synchronized fun onPicture(now: Long) { onFragment(now); pictures++ }

    @Synchronized fun tick(now: Long): Decision? {
        if (windowStart < 0 || now - windowStart < windowMs) return null
        val fps = pictures * 1000.0 / (now - windowStart)
        windowStart = now
        pictures = 0
        val was = current
        if (locked) {
            if (fps >= minFps || !fromSaved || researched) return Decision(Kind.MEASURED, was, fps, was)
            // The saved one is slow today: search again, once.
            researched = true
            fromSaved = false
            locked = false
            results.clear()
        }
        results[was] = fps
        if (fps >= minFps) {
            locked = true
            return Decision(Kind.CHOSEN, was, fps, was)
        }
        val next = LiveAck.ORDER.firstOrNull { it !in results }
        if (next != null) {
            current = next
            return Decision(Kind.SWITCHED, was, fps, next)
        }
        val best = results.maxByOrNull { it.value }!!.key
        current = best
        locked = true
        return Decision(Kind.CHOSEN, was, fps, best)
    }

    /** "media ack per fragment 1.2 fps, media ack per picture 5.0 fps". */
    @Synchronized fun report(): String = results.entries.joinToString(", ") { "${it.key.label} %.1f fps".format(java.util.Locale.US, it.value) }
}
