package com.zdycomp.p3companion

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Live control of the glasses over Bluetooth Classic (RFCOMM SPP, channel 8): pair, connect and sign
 * in, battery, photos, recording on the glasses, live view, and reconnecting after a drop. One
 * instance for the whole app; a foreground service keeps it alive in the background.
 *
 * Callers must hold BLUETOOTH_CONNECT (Android 12+) before calling connect.
 * See docs/PROTOCOL-NOTES.md and docs/rf/ for the reasoning behind every timing and rule here.
 */
@SuppressLint("MissingPermission")
class XkGlasses private constructor(context: Context, private val prefs: Prefs) {

    enum class State { IDLE, CONNECTING, READY, BUSY }
    enum class Preview { OFF, OPENING, ON, BUSY, UNSUPPORTED }

    fun interface Listener { fun onGlassesChanged() }

    data class Paired(val address: String, val name: String)

    /** A failure with a message for people; [retry] says whether trying again later can help. */
    class LinkException(message: String, val retry: Boolean = true) : IOException(message)

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? = app.getSystemService(BluetoothManager::class.java)?.adapter
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val io = Executors.newSingleThreadExecutor()
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    private val power = app.getSystemService(PowerManager::class.java)
    private val retryLock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "p3c:reconnect")?.apply { setReferenceCounted(false) }
    private val xferLock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "p3c:transfer")?.apply { setReferenceCounted(false) }

    @Volatile var state = State.IDLE; private set
    @Volatile var busyText: String? = null; private set
    @Volatile var lastError: String? = null; private set
    @Volatile var lastMessage: String? = null; private set
    @Volatile var battery: Int? = null; private set
    @Volatile var charging: Boolean? = null; private set
    @Volatile var deviceName: String? = null; private set
    @Volatile var firmware: String? = null; private set
    @Volatile var model: String? = null; private set
    @Volatile var photoCount: Int? = null; private set
    @Volatile var videoCount: Int? = null; private set
    @Volatile var audioCount: Int? = null; private set
    @Volatile var recording = false; private set
    @Volatile var lastPhoto: Uri? = null; private set

    // Live view
    @Volatile var preview = Preview.OFF; private set
    /** Receives every H.264 access unit on the reader thread. Must never block. */
    @Volatile var videoSink: ((VideoAu) -> Unit)? = null
    /** Told (on the main thread) when the glasses or the link close the live view. */
    @Volatile var previewStateSink: ((Boolean) -> Unit)? = null
    @Volatile var videoFrames = 0L; private set

    // Live view probe (3.1): what the glasses said about preview, and every media frame seen.
    /** The preview_* fields of the 1001 device info, as the glasses sent them. */
    @Volatile var previewInfo: Map<String, String> = emptyMap(); private set
    /** Last video-preview state the glasses reported on node 57A0 (0 off, 1 on), if any. */
    @Volatile var previewState57A0: Int? = null; private set
    @Volatile var mediaFramesSeen = 0L; private set
    @Volatile var mediaFrag5 = 0L; private set
    /** True while [runLiveProbe] is trying the live view commands. */
    @Volatile var probing = false; private set
    /** The probe path the open live view used ("5710/3"…), or null for the standard 5720 open. */
    @Volatile private var openedWith: String? = null
    // Receive sniffer (3.3): every byte read, and what the parser skipped or couldn't route.
    /** Every byte read from the socket since the app started. */
    @Volatile var rxBytes = 0L; private set
    @Volatile var skippedRuns = 0L; private set
    @Volatile var skippedBytes = 0L; private set
    /** Frames with a head other than 30/4A/2B (only accepted while the sniffer runs). */
    @Volatile var otherFrames = 0L; private set
    /** Whole JPEG pictures that arrived as live view frames (a small preview, 160×120 on some firmware). */
    @Volatile var jpegFrames = 0L; private set
    /** Gets each JPEG live view frame (reader thread; must not block). */
    @Volatile var jpegSink: ((ByteArray) -> Unit)? = null
    @Volatile private var sniffing = false
    private var sniffLines = 0
    private var sniffLastAt = 0L
    /** What the last Wi-Fi check during the live view test found (names only). */
    @Volatile var wifiFound: List<String> = emptyList(); private set
    /** When the link last became ready after a connect (elapsedRealtime), for the audio gate. */
    @Volatile var readySince = 0L; private set
    /** Snapshot view takes photos without the shutter sound, notification or "last photo" update. */
    @Volatile private var quietCapture = false

    // Diagnostics
    @Volatile var lastConnectReport: List<String> = emptyList(); private set
    @Volatile var lastBind: String? = null; private set
    @Volatile var bindVerified = false; private set
    /** True once the glasses accepted the owner's key in this session. */
    @Volatile var signedIn = false; private set
    /** Plain-English lines saying what answered when connected without a key. */
    @Volatile var keylessReport: List<String> = emptyList(); private set
    val linkLosses = Collections.synchronizedList(mutableListOf<String>())
    @Volatile var parserDropped = 0L; private set
    @Volatile var parserBadCrc = 0L; private set
    @Volatile var maxMediaFrame = 0; private set

    private val log = ArrayDeque<String>()
    private val logTime = SimpleDateFormat("HH:mm:ss", Locale.US)

    // ---------------- link (one per connection) ----------------

    private inner class Link(val socket: BluetoothSocket, val session: Int) {
        val parser = Xk.Parser().apply { onSkip = ::onSkipped; lenient = sniffing }
        val demux = Xk.MediaDemux(::onMediaElement, ::onMediaBroken)
        private val outQ = LinkedBlockingQueue<ByteArray>(512)
        @Volatile var lastRxAt = SystemClock.elapsedRealtime()
        @Volatile var writeStartedAt = 0L
        @Volatile var closed = false
        @Volatile var closeReason = "the link closed"

        fun send(bytes: ByteArray) {
            if (closed) throw LinkException("Not connected.")
            if (!outQ.offer(bytes, 2, TimeUnit.SECONDS)) {
                close("send queue full")
                throw LinkException("The link to the glasses is stuck.")
            }
        }

        /** For ACKs from the reader thread: never blocks, never throws. */
        fun trySend(bytes: ByteArray) { if (!closed && !outQ.offer(bytes)) close("send queue full") }

        fun close(reason: String) {
            if (closed) return
            closed = true
            closeReason = reason
            runCatching { socket.close() }
            writer.interrupt()
        }

        private val writer = Thread({
            try {
                val os = socket.outputStream
                while (!closed) {
                    val b = outQ.take()
                    writeStartedAt = SystemClock.elapsedRealtime()
                    os.write(b)
                    writeStartedAt = 0
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                close("write failed (${e.message})")
            }
        }, "glasses-writer").apply { isDaemon = true }

        private val reader = Thread({
            val buf = ByteArray(32 * 1024)
            try {
                val ins = socket.inputStream
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) { close("the glasses closed the link"); break }
                    if (n == 0) continue
                    rxBytes += n
                    lastRxAt = SystemClock.elapsedRealtime()
                    parser.feed(buf, n).forEach { f -> synchronized(this) { handle(this, f) } }
                }
            } catch (e: Exception) {
                close(if (closed) closeReason else "read failed (${e.message})")
            }
            onLinkLost(this)
        }, "glasses-reader").apply { isDaemon = true }

        fun start() { writer.start(); reader.start() }
    }

    @Volatile private var link: Link? = null
    @Volatile private var pendingSocket: BluetoothSocket? = null
    private val session = AtomicInteger(0)
    @Volatile private var userDisconnect = false
    /** The last connect failed in a way only the person can fix (key, pairing). */
    @Volatile private var gaveUp = false
    @Volatile private var infoRead = false

    init {
        watchdog.scheduleWithFixedDelay({ runCatching { tick() } }, 500, 500, TimeUnit.MILLISECONDS)
    }

    /** Releases a parser stuck on a partial frame, and drops a link whose writes hang. */
    private fun tick() {
        val l = link ?: return
        val now = SystemClock.elapsedRealtime()
        if (l.parser.pending > 0 && now - l.lastRxAt > 1_500) {
            val freed = l.parser.skipStalled()
            if (freed.isNotEmpty()) addLog("Freed ${freed.size} frame(s) behind a broken one")
            freed.forEach { f -> synchronized(l) { handle(l, f) } }
        }
        val w = l.writeStartedAt
        if (w != 0L && now - w > 5_000) {
            addLog("A write to the glasses hung for 5 s; dropping the link")
            l.close("write stuck")
        }
        parserDropped = l.parser.dropped
        parserBadCrc = l.parser.badCrc
        maxMediaFrame = l.parser.maxMediaPayload
    }

    // ---------------- requests and replies ----------------

    private class Pending(val node: String, val rid: Int) { val q = LinkedBlockingQueue<Xk.Frame>() }

    private val pending = ConcurrentHashMap<String, Pending>()
    private val pushWaiters = ConcurrentHashMap<String, LinkedBlockingQueue<Xk.Frame>>()
    /** Request ids that timed out: id → node and when, so only a late echo for the same node is dropped. */
    private val staleIds = ConcurrentHashMap<Int, Pair<String, Long>>()
    /** How many node-matched replies were logged per node this session (the log keeps the first few). */
    private val byNodeLogged = ConcurrentHashMap<String, Int>()
    private val linkDown = Xk.Frame(0, 0, 0, 0, ByteArray(0))
    private val mediaDone = LinkedBlockingQueue<Boolean>()

    private var txOrder = 0x10
    private var requestId = 0x40
    private var lastRxKey = -1
    private var lastRxKeyAt = 0L

    private fun nextOrder(): Int = synchronized(this) { val o = txOrder; txOrder = (txOrder + 1) and 0xFF; o }
    private fun nextRequestId(): Int = synchronized(this) {
        requestId = (requestId + 1) and 0xFFFF
        if (requestId < 0x10) requestId = 0x10 // keep clear of the fixed ids used by the sign-in
        requestId
    }

    private fun send(f: Xk.Frame) = (link ?: throw LinkException("Not connected.")).send(f.encode())

    /**
     * Sends a request and waits for its reply. 3.3: any reply for the node completes it (see
     * [Xk.matchReply]): the glasses answer a successful execute with a device-numbered frame
     * (action 0x8004, their own counter in the id field), and only error and read replies echo our id.
     */
    private fun request(
        node: String, action: Int, data: ByteArray, timeoutMs: Long,
        logData: Boolean = true, rid: Int = nextRequestId(),
    ): Xk.Frame? {
        val l = link ?: throw LinkException("Not connected.")
        val p = Pending(node, rid)
        pending[node] = p
        try {
            l.send(Xk.control(nextOrder(), node, action, rid, data).encode())
            addLog("→ $node" + if (logData && data.isNotEmpty()) " " + hex(data.take(24).toByteArray()) else "")
            val f = p.q.poll(timeoutMs, TimeUnit.MILLISECONDS)
            if (f === linkDown) throw LinkException("Lost the connection to the glasses.")
            if (f == null) {
                if (staleIds.size > 64) staleIds.clear()
                staleIds[rid] = node to SystemClock.elapsedRealtime()
                addLog("✕ $node: no answer in $timeoutMs ms")
            }
            return f
        } finally {
            pending.remove(node, p)
        }
    }

    /** Registers interest in a push before the command that causes it is sent. */
    private fun expectPush(node: String): LinkedBlockingQueue<Xk.Frame> =
        LinkedBlockingQueue<Xk.Frame>().also { pushWaiters[node] = it }

    private fun awaitPush(node: String, q: LinkedBlockingQueue<Xk.Frame>, timeoutMs: Long): Xk.Frame? {
        val f = q.poll(timeoutMs, TimeUnit.MILLISECONDS)
        if (f === linkDown) throw LinkException("Lost the connection to the glasses.")
        return f
    }

    /** Wakes everything waiting on the link at once instead of letting it time out. */
    private fun wakeWaiters() {
        pending.values.forEach { it.q.offer(linkDown) }
        pushWaiters.values.forEach { it.offer(linkDown) }
        mediaDone.offer(false)
    }

    // ---------------- listeners ----------------

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }
    private fun changed() = main.post { listeners.forEach { it.onGlassesChanged() } }

    fun bluetoothOn() = adapter?.isEnabled == true

    /** Paired Classic devices (low-energy-only entries can't carry the control link), likely glasses first. */
    fun pairedDevices(): List<Paired> {
        val list = runCatching { adapter?.bondedDevices?.toList() }.getOrNull() ?: emptyList()
        return list.filter { runCatching { it.type }.getOrDefault(BluetoothDevice.DEVICE_TYPE_UNKNOWN) != BluetoothDevice.DEVICE_TYPE_LE }
            .map { Paired(it.address, it.name ?: it.address) }
            .sortedWith(compareByDescending<Paired> { strongMatch(it.name, it.address) }.thenByDescending { looksLikeGlasses(it.name) })
    }

    fun isBonded(address: String): Boolean =
        runCatching { adapter?.getRemoteDevice(address)?.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)

    // ---------------- connect ----------------

    /**
     * Connects to the glasses at [address]. [manual] is true when the person asked for it: only then
     * does it pair (Android shows its pairing popup) if the phone isn't paired with them yet.
     * Without a saved key (or with [withoutKey]) it still opens the link, starts the session and
     * asks the read-only questions, and reports which ones the glasses answered. [testPhoto] also
     * tries the shutter without a key (diagnostics only).
     */
    fun connect(address: String, name: String?, manual: Boolean = true, withoutKey: Boolean = false, testPhoto: Boolean = false) {
        if (state != State.IDLE) return
        val key = if (withoutKey) null else prefs.userKey
        prefs.glassesAddress = address
        if (name != null) prefs.glassesName = name
        userDisconnect = false
        gaveUp = false
        prefs.userStopped = false
        lastError = null
        lastMessage = null
        busyText = null
        bindVerified = false
        signedIn = false
        keylessReport = emptyList()
        infoRead = false
        byNodeLogged.clear()
        state = State.CONNECTING
        val mine = session.incrementAndGet()
        main.removeCallbacks(retry)
        changed()
        GlassesService.start(app)
        io.execute {
            try {
                val device = remoteDevice(address)
                ensureBonded(device, manual, mine)
                openSocket(device, mine)
                startSession(mine)
                if (key != null) signInWith(key) else probeWithoutKey(testPhoto)
                if (mine != session.get()) { dropLink("cancelled"); return@execute }
                state = State.READY
                readySince = SystemClock.elapsedRealtime()
                lastMessage = if (signedIn) "Connected." else "Connected without your key. " + keylessSummary()
                attempt = 0
                main.removeCallbacks(retry)
                runCatching { retryLock?.release() }
                changed()
                readInfo()
            } catch (e: Exception) {
                if (mine != session.get()) {
                    if (link?.session == mine) dropLink("cancelled") // never leave a cancelled link open
                    return@execute
                }
                dropLink("connect failed")
                state = State.IDLE
                lastError = e.message ?: "Couldn't connect."
                addLog("Connect failed: $lastError")
                changed()
                val retryable = (e as? LinkException)?.retry ?: true
                gaveUp = !retryable
                if (!userDisconnect && retryable) main.post { scheduleReconnect("connect failed") }
                else changed()
            }
        }
    }

    private fun remoteDevice(address: String): BluetoothDevice {
        val a = adapter ?: throw LinkException("This phone has no Bluetooth.", retry = false)
        if (!a.isEnabled) throw LinkException("Bluetooth is off. Turn it on, then connect.")
        return runCatching { a.getRemoteDevice(address.uppercase(Locale.US)) }.getOrNull()
            ?: throw LinkException("The saved glasses address isn't valid. Pick your glasses again.", retry = false)
    }

    /** Pairs first when needed, so the socket never pops up a surprise pairing request. */
    private fun ensureBonded(d: BluetoothDevice, manual: Boolean, mine: Int) {
        if (d.bondState == BluetoothDevice.BOND_BONDED) return
        if (!manual) {
            throw LinkException(
                "Your glasses aren't paired with this phone any more. Tap Pair new glasses on the Glasses tab.", retry = false
            )
        }
        if (d.type == BluetoothDevice.DEVICE_TYPE_LE) {
            throw LinkException(
                "That entry is the glasses' low-energy side, which can't carry the control link. Pick the other entry with the same name.",
                retry = false,
            )
        }
        val result = LinkedBlockingQueue<Pair<Int, Int>>()
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val dev = deviceExtra(i) ?: return
                if (!dev.address.equals(d.address, ignoreCase = true)) return
                when (i.action) {
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val s = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                        val why = i.getIntExtra("android.bluetooth.device.extra.REASON", 0)
                        addLog("Bond state $s" + if (why != 0) " (reason $why)" else "")
                        if (s == BluetoothDevice.BOND_BONDED || s == BluetoothDevice.BOND_NONE) result.offer(s to why)
                    }
                    BluetoothDevice.ACTION_PAIRING_REQUEST ->
                        addLog("Pairing popup, variant " + i.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1))
                }
            }
        }
        AudioRoute.registerSystemReceiver(app, rx, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
        })
        try {
            cancelDiscovery()
            if (d.bondState != BluetoothDevice.BOND_BONDING && !d.createBond()) {
                throw LinkException("Couldn't start pairing. Turn the glasses off and on, then try again.")
            }
            busyText = null
            lastMessage = "Pairing… tap Pair on the phone's popup if one appears."
            changed()
            val end = SystemClock.elapsedRealtime() + 45_000
            var outcome: Pair<Int, Int>? = null
            while (outcome == null) {
                if (mine != session.get()) throw LinkException("Cancelled.", retry = false)
                if (SystemClock.elapsedRealtime() > end) {
                    throw LinkException("Pairing timed out. Look for a Bluetooth pairing notification and try again.", retry = false)
                }
                outcome = result.poll(500, TimeUnit.MILLISECONDS)
            }
            if (outcome.first != BluetoothDevice.BOND_BONDED) {
                throw LinkException(
                    when (outcome.second) {
                        3, 8 -> "Pairing was cancelled."
                        4, 6 -> "The glasses didn't answer the pairing. Make sure they're on and not connected to another phone."
                        1, 2 -> "The glasses refused the pairing. Turn them off and on, then try again."
                        else -> "Pairing failed (code ${outcome.second}). Turn the glasses off and on, then try again."
                    },
                    retry = false,
                )
            }
            lastMessage = null
            Thread.sleep(1_500) // let Android finish its own headset and audio connection first
        } finally {
            runCatching { app.unregisterReceiver(rx) }
        }
    }

    private fun cancelDiscovery() {
        val canScan = Build.VERSION.SDK_INT < 31 ||
            app.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        if (canScan) runCatching { if (adapter?.isDiscovering == true) adapter.cancelDiscovery() }
    }

    private class Attempt(val id: String, val timeoutMs: Long, val make: (BluetoothDevice) -> BluetoothSocket)

    private fun reflect(d: BluetoothDevice, method: String, ch: Int): BluetoothSocket = try {
        d.javaClass.getMethod(method, Int::class.javaPrimitiveType).invoke(d, ch) as BluetoothSocket
    } catch (e: java.lang.reflect.InvocationTargetException) {
        throw (e.cause ?: e)
    }

    private val attempts = listOf(
        Attempt("sdp-secure", 12_000) { it.createRfcommSocketToServiceRecord(SPP) },
        Attempt("ch8-secure", 8_000) { reflect(it, "createRfcommSocket", 8) },
        Attempt("ch8-insecure", 8_000) { reflect(it, "createInsecureRfcommSocket", 8) },
        Attempt("sdp-insecure", 12_000) { it.createInsecureRfcommSocketToServiceRecord(SPP) },
    )

    /** Opens RFCOMM with a time limit per attempt, closing every failed socket, last winner first. */
    private fun openSocket(device: BluetoothDevice, mine: Int) {
        cancelDiscovery()
        dropLink("new connection") // never hold two links to channel 8
        val cls = device.bluetoothClass
        addLog(
            "Opening link to ${device.name ?: "glasses"} (${maskAddress(device.address)}) type=${device.type} " +
                "bond=${device.bondState} class=" + (cls?.let { "%04X".format(it.deviceClass) } ?: "-") +
                " spp=" + (device.uuids?.any { it.uuid == SPP }?.toString() ?: "unknown")
        )
        val report = mutableListOf<String>()
        val order = attempts.sortedByDescending { it.id == prefs.linkMethod }
        var fast = 0
        var slow = 0
        try {
            for ((i, at) in order.withIndex()) {
                if (mine != session.get()) throw LinkException("Cancelled.", retry = false)
                if (i > 0) Thread.sleep(if (report.lastOrNull()?.contains("timed out") == true) 1_500 else 700)
                val s = try {
                    at.make(device)
                } catch (e: Exception) {
                    report += "${at.id}: unavailable (${e.javaClass.simpleName})"
                    continue
                }
                val t0 = SystemClock.elapsedRealtime()
                try {
                    connectBounded(s, at.timeoutMs)
                } catch (e: IOException) {
                    val ms = SystemClock.elapsedRealtime() - t0
                    if (ms < 2_000) fast++ else slow++
                    report += "${at.id}: failed in $ms ms (${e.message})"
                    addLog("Link ${at.id} failed in $ms ms: ${e.message}")
                    continue
                }
                if (mine != session.get()) { runCatching { s.close() }; throw LinkException("Cancelled.", retry = false) }
                val ms = SystemClock.elapsedRealtime() - t0
                val l = Link(s, mine)
                link = l
                l.start()
                prefs.linkMethod = at.id
                report += "${at.id}: open in $ms ms"
                addLog("Link open (${at.id}) in $ms ms, packet sizes ${s.maxTransmitPacketSize}/${s.maxReceivePacketSize}")
                return
            }
        } finally {
            lastConnectReport = report.toList()
        }
        addLog("All link attempts failed: ${report.joinToString("; ")}")
        throw LinkException(
            if (fast >= 2 && slow == 0)
                "The glasses are on but another app is holding their link, usually LensMoo or another phone. " +
                    "Force-stop LensMoo (or turn Bluetooth off on the other phone), then try again."
            else
                "The glasses didn't answer. Make sure they're switched on and close to the phone."
        )
    }

    /** connect() with a hard time limit; close() from another thread is the only way to abort it. */
    private fun connectBounded(s: BluetoothSocket, timeoutMs: Long) {
        val finished = AtomicBoolean(false)
        val timedOut = AtomicBoolean(false)
        pendingSocket = s
        val task = watchdog.schedule({
            if (finished.compareAndSet(false, true)) { timedOut.set(true); runCatching { s.close() } }
        }, timeoutMs, TimeUnit.MILLISECONDS)
        try {
            s.connect()
            if (!finished.compareAndSet(false, true)) throw IOException("timed out")
        } catch (e: IOException) {
            finished.set(true)
            runCatching { s.close() }
            throw if (timedOut.get()) IOException("timed out after ${timeoutMs / 1000} s", e) else e
        } finally {
            task.cancel(false)
            pendingSocket = null
        }
    }

    /** Starts the session the way the validated clients do: 0001 and 0002, then wait at least 1.5 s. */
    private fun startSession(mine: Int) {
        val token = (1..61).map { TOKEN_CHARS.random() }.joinToString("").toByteArray(Charsets.US_ASCII)
        val blob = ByteArray(64).also { java.util.Random().nextBytes(it) }
        send(Xk.control(nextOrder(), "0001", Xk.ACTION_EXECUTE, 1, token))
        addLog("→ 0001")
        Thread.sleep(200)
        send(Xk.control(nextOrder(), "0002", Xk.ACTION_EXECUTE, 4, blob))
        addLog("→ 0002")
        Thread.sleep(1_500)
        if (mine != session.get()) throw LinkException("Cancelled.", retry = false)
    }

    /**
     * Signs in with the owner's account id (102E). The glasses may not answer 102E at all, so the
     * session is then proved with the normal setup reads; an error-code answer means the key was refused.
     */
    private fun signInWith(userKey: String) {
        val sentAt = SystemClock.elapsedRealtime()
        val r = request("102E", Xk.ACTION_EXECUTE, Xk.userBindData(userKey), 2_500, logData = false)
        // 3.3: the 102E answer (device-numbered, "02 00") now completes the request at once instead of
        // after 2.5 s; keep at least the validated setup spacing before 7110 so the glasses can settle.
        val gap = BIND_SETTLE_MS - (SystemClock.elapsedRealtime() - sentAt)
        if (r != null && gap > 0) Thread.sleep(gap)
        val outcome = Xk.bindOutcome(r)
        lastBind = "102E reply: " + (r?.let { "type ${it.replyType}, code ${it.errCode ?: "-"}" } ?: "none") + " → $outcome"
        addLog(lastBind!!)
        if (outcome == Xk.Bind.REFUSED) {
            throw LinkException("The glasses refused your key (${Xk.errorText(r?.errCode ?: 1)}). Check it in Set up.", retry = false)
        }

        // The vendor's setup order; 7110 only answers here. Its bytes are logged for the record.
        request("7110", Xk.ACTION_READ, ByteArray(0), 1_200)?.let { addLog("7110 flags: ${hex(it.data.take(16).toByteArray())}") }
        val info = request("1001", Xk.ACTION_READ, ByteArray(0), 2_000)
        info?.let { applyInfo(it) }
        val batt = request("1003", Xk.ACTION_READ, ByteArray(0), 2_500)
        val code = batt?.errCode ?: info?.errCode
        if (code != null && code != 0) {
            throw LinkException("The glasses didn't accept your key (${Xk.errorText(code)}). Check it in Set up.", retry = false)
        }
        batt?.let { f -> Xk.battery(f)?.let { charging = it.first; battery = it.second } }
        if (batt == null && info == null && outcome != Xk.Bind.BOUND) {
            throw LinkException(
                "The glasses opened the link but didn't answer after sign-in. Turn them off and on, make sure LensMoo is closed, then try again."
            )
        }
        bindVerified = true
        signedIn = true
        addLog("Signed in" + (battery?.let { ", battery $it%" } ?: ""))
    }

    private fun answerText(f: Xk.Frame?, what: String): String = when {
        f == null -> "$what: no answer"
        f.errCode != null && f.errCode != 0 -> "$what: refused (${Xk.errorText(f.errCode!!)})"
        else -> "$what: answered"
    }

    /** Without a key: ask only read-only questions and record which ones the glasses answer. */
    private fun probeWithoutKey(testPhoto: Boolean) {
        val lines = mutableListOf<String>()
        val batt = request("1003", Xk.ACTION_READ, ByteArray(0), 2_500)
        batt?.let { f -> Xk.battery(f)?.let { charging = it.first; battery = it.second } }
        lines += answerText(batt, "Battery") + (if (batt?.errCode == null && battery != null) " ($battery%)" else "")
        val info = request("1001", Xk.ACTION_READ, ByteArray(0), 2_000)
        info?.let { applyInfo(it) }
        lines += answerText(info, "Device info") + (if (info?.errCode == null && deviceName != null) " ($deviceName)" else "")
        val counts = request("5713", Xk.ACTION_READ, ByteArray(0), 2_000)
        counts?.let { applyCounts(it) }
        lines += answerText(counts, "Media counts")
        infoRead = true
        if (testPhoto) {
            val q = expectPush("7320")
            try {
                val r = request("57B0", Xk.ACTION_EXECUTE, ByteArray(0), 4_000)
                val announce = if (r != null && (r.errCode ?: 0) == 0) awaitPush("7320", q, 10_000) else null
                lines += answerText(r, "Take photo") + when {
                    announce != null -> ", and a photo was announced"
                    r != null && (r.errCode ?: 0) == 0 -> ", but no photo followed"
                    else -> ""
                }
                if (announce != null) release("test photo without a key")
            } finally {
                pushWaiters.remove("7320")
            }
        }
        keylessReport = lines.toList()
        lines.forEach { addLog("Without key: $it") }
    }

    /** One plain sentence about what answered without a key. */
    fun keylessSummary(): String {
        val yes = keylessReport.filter { it.contains("answered") && !it.contains("no answer") }.map { it.substringBefore(":").lowercase(Locale.US) }
        val no = keylessReport.filterNot { it.contains(": answered") }.map { it.substringBefore(":").lowercase(Locale.US) }
        return when {
            keylessReport.isEmpty() -> ""
            no.isEmpty() -> "The glasses answered everything: ${yes.joinToString(", ")}."
            yes.isEmpty() -> "The glasses didn't answer anything without your key."
            else -> "Answered: ${yes.joinToString(", ")}. Not answered: ${no.joinToString(", ")}."
        }
    }

    /** Signs in on the current link after the key was added while connected without it. */
    fun signIn() {
        val key = prefs.userKey ?: return
        if (link == null || signedIn) return
        io.execute {
            setBusy("Signing in…")
            try {
                signInWith(key)
                lastMessage = "Signed in. Everything is ready."
            } catch (e: Exception) {
                lastError = e.message ?: "Couldn't sign in."
            } finally {
                setReady()
            }
        }
    }

    /** Device info is answered once per session, so it's read at connect only. */
    private fun readInfo() {
        if (link == null) return
        if (!infoRead) {
            request("5713", Xk.ACTION_READ, ByteArray(0), 1_500)?.let { applyCounts(it) }
            infoRead = true
        }
        changed()
    }

    private fun applyInfo(f: Xk.Frame) {
        val j = Xk.jsonObject(f) ?: return
        fun str(k: String) = (j[k] as? String)?.takeIf { it.isNotBlank() } ?: (j[k] as? Number)?.toString()
        deviceName = str("dev_name") ?: deviceName
        firmware = str("soft_ver") ?: firmware
        model = str("prod_mode") ?: model
        if (battery == null) battery = str("battery_main")?.toIntOrNull()?.takeIf { it in 0..100 }
        val pv = j.entries.mapNotNull { (k, v) ->
            val key = k as? String ?: return@mapNotNull null
            if (key.startsWith("preview_") && v != null) key to v.toString() else null
        }.toMap()
        if (pv.isNotEmpty()) previewInfo = pv
        str("mac_addr")?.let { mac ->
            val saved = prefs.glassesAddress
            addLog("Address check: " + if (saved != null && mac.equals(saved, ignoreCase = true)) "matches" else "differs from the saved link")
        }
    }

    private fun applyCounts(f: Xk.Frame) {
        val j = Xk.jsonObject(f) ?: return
        fun num(k: String) = (j[k] as? String)?.trim()?.toIntOrNull() ?: (j[k] as? Number)?.toInt()
        photoCount = num("photo_num")
        videoCount = num("video_num")
        audioCount = num("record_num")
    }

    fun disconnect() {
        session.incrementAndGet()
        userDisconnect = true
        prefs.userStopped = true
        main.removeCallbacks(retry)
        runCatching { retryLock?.release() }
        attempt = 0
        runCatching { pendingSocket?.close() } // aborts a connect() still blocking
        dropLink("you disconnected")
        state = State.IDLE
        busyText = null
        recording = false
        setPreviewOff()
        addLog("Disconnected")
        changed()
        GlassesService.stop(app)
    }

    /** Closes the current link without the lost-link path (which only runs for the current link). */
    private fun dropLink(reason: String) {
        val l = link
        link = null
        l?.close(reason)
        if (l != null) wakeWaiters()
    }

    private fun onLinkLost(l: Link) {
        if (link !== l) return
        link = null
        wakeWaiters()
        val reason = l.closeReason
        synchronized(linkLosses) { linkLosses += "${logTime.format(Date())} $reason"; while (linkLosses.size > 20) linkLosses.removeAt(0) }
        state = State.IDLE
        busyText = null
        recording = false
        setPreviewOff()
        addLog("Link lost: $reason")
        if (!userDisconnect) lastError = "Lost the connection to the glasses."
        changed()
        if (!userDisconnect) main.post { scheduleReconnect("link lost") }
    }

    // ---------------- reconnecting ----------------

    private var attempt = 0
    private var retryStart = 0L
    private val retry = Runnable { reconnectSaved() }

    /** True while the app should keep the glasses connected. */
    val wantsLink: Boolean
        get() = !userDisconnect && !gaveUp && !prefs.userStopped && prefs.glassesAddress != null && prefs.userKey != null &&
            (state != State.IDLE || prefs.autoReconnect) && !pausedForBackground

    /**
     * 2.1 "Keep connected in the background" off: the app went to the background, so the link was
     * closed without marking a user disconnect. In memory only; [resumeIfPaused] clears it.
     */
    @Volatile var pausedForBackground = false
        private set

    /**
     * Closes the link because the app left the screen (only when the person turned off "Keep connected
     * in the background"). Unlike [disconnect] it doesn't set userStopped, so [resumeIfPaused] brings it
     * back. Never interrupts a photo transfer (BUSY).
     */
    fun pause() {
        if (state == State.BUSY || pausedForBackground || !wantsLink) return
        pausedForBackground = true
        session.incrementAndGet()
        main.removeCallbacks(retry)
        runCatching { retryLock?.release() }
        attempt = 0
        runCatching { pendingSocket?.close() }
        dropLink("app in background")
        state = State.IDLE
        busyText = null
        recording = false
        setPreviewOff()
        addLog("Paused while the app is in the background")
        changed()
        GlassesService.stop(app)
    }

    /** The app is back on screen: reconnect if [pause] closed the link. */
    fun resumeIfPaused() {
        if (!pausedForBackground) return
        pausedForBackground = false
        if (prefs.userStopped || userDisconnect) return
        addLog("App is back: reconnecting")
        reconnectSaved()
    }

    /** Connects to the saved glasses if allowed: auto-reconnect only while they're still paired. */
    fun reconnectSaved() {
        val addr = prefs.glassesAddress ?: return
        if (state != State.IDLE || prefs.userKey == null || !bluetoothOn()) return
        if (!isBonded(addr)) {
            lastError = "Your glasses aren't paired with this phone any more. Tap Pair new glasses on the Glasses tab."
            changed(); return
        }
        connect(addr, prefs.glassesName, manual = false)
    }

    /** Quick retries for about 3 minutes after a drop (kept awake), then wait for a wake event. */
    private fun scheduleReconnect(why: String) {
        if (!wantsLink || !prefs.autoReconnect || state != State.IDLE) return
        val addr = prefs.glassesAddress ?: return
        if (!isBonded(addr)) return
        main.removeCallbacks(retry)
        val now = SystemClock.elapsedRealtime()
        if (attempt == 0) { retryStart = now; runCatching { retryLock?.acquire(ACTIVE_RETRY_MS) } }
        if (now - retryStart > ACTIVE_RETRY_MS) {
            attempt = 0
            runCatching { retryLock?.release() }
            addLog("Waiting for the glasses to come back ($why)")
            changed(); return
        }
        val d = BACKOFF_MS[minOf(attempt++, BACKOFF_MS.lastIndex)]
        addLog("Trying again in ${d / 1000} s ($why)")
        changed()
        main.postDelayed(retry, d)
    }

    /** The glasses came back in range, Bluetooth turned on, or the phone was unlocked. */
    fun onGlassesSeen(why: String) {
        if (!wantsLink || !prefs.autoReconnect || state != State.IDLE) return
        attempt = 0
        main.removeCallbacks(retry)
        addLog("Trying again: $why")
        main.postDelayed(retry, 1_500)
    }

    /** The radio link dropped: close the socket now instead of waiting for the read to time out. */
    fun linkDownHint() { link?.close("Bluetooth link dropped") }

    // ---------------- frames from the glasses ----------------

    private fun handle(l: Link, f: Xk.Frame) {
        try {
            when (f.head) {
                Xk.HEAD_MEDIA -> {
                    if (f.isAck) return
                    mediaFramesSeen++
                    if (f.fragment == 5) mediaFrag5++
                    lastMediaAt = SystemClock.elapsedRealtime()
                    burstOpen = f.fragment == 1 || f.fragment == 2
                    // The glasses wait for this on every last fragment, so it goes first, whatever happens next.
                    if (f.fragment == 3) l.trySend(Xk.mediaAck(nextOrder()).encode())
                    if (sniffing && assembler == null) sniffLog("0x4A frame divide %04X, %d B: %s".format(f.divide, f.payload.size, hex(f.payload.take(24).toByteArray())))
                    l.demux.feed(f)
                }
                Xk.HEAD_CONTROL -> {
                    if (f.isAck || !f.fromDevice) return
                    // Replies and pushes alike (5710, 5711, 57A0, 5750, 7320, C107…): ack with the frame's own order.
                    Xk.ackFor(f, nextOrder())?.let { l.trySend(it.encode()) }
                    val key = (f.order shl 16) or (f.requestId and 0xFFFF)
                    val now = SystemClock.elapsedRealtime()
                    if (key == lastRxKey && now - lastRxKeyAt < 1_500) return // a repeat of the frame just handled
                    lastRxKey = key
                    lastRxKeyAt = now
                    val node = f.node
                    addLog("← $node" + (if (f.data.isNotEmpty() && f.data.size <= 24) " " + hex(f.data) else "") +
                        (f.errCode?.let { " (${Xk.errorText(it)})" } ?: ""))
                    onControl(f, node)
                }
                Xk.HEAD_CUSTOM -> {
                    addLog("← 2B frame (${f.payload.size} B)")
                    if (sniffing) sniffLog("2B " + Xk.describeRaw(f.payload))
                    if (previewWanted) liveElement(f.payload, "a 2B frame")
                }
                else -> {
                    otherFrames++
                    sniffLog("frame with head %02X cmd %04X divide %04X, %d B: %s".format(
                        f.head, f.cmd, f.divide, f.payload.size, hex(f.payload.take(24).toByteArray())))
                    if (previewWanted) liveElement(f.payload, "a head-%02X frame".format(f.head))
                }
            }
        } catch (e: Exception) {
            addLog("Bad frame: ${e.message}")
        }
    }

    /** A state node the glasses also push: a non-error frame on it still goes through the push handling. */
    private fun alsoPush(f: Xk.Frame, node: String) = node in PUSH_NODES && f.replyType != Xk.FMT_ERRCODE

    /** The node of a request that timed out in the last 15 s with this id, or null. */
    private fun staleNodeFor(rid: Int): String? {
        val e = staleIds[rid] ?: return null
        if (SystemClock.elapsedRealtime() - e.second > STALE_MS) { staleIds.remove(rid); return null }
        return e.first
    }

    private fun onControl(f: Xk.Frame, node: String) {
        // A reply to something we're waiting for?
        val p = pending[node]
        when (Xk.matchReply(p?.rid, f, staleNodeFor(f.requestId))) {
            Xk.ReplyMatch.EXACT -> { p!!.q.offer(f); afterReply(f, node); if (!alsoPush(f, node)) return }
            Xk.ReplyMatch.BY_NODE -> {
                val n = byNodeLogged.merge(node, 1, Int::plus) ?: 1
                if (n <= 3) addLog("$node answered in the device-numbered form (id %04X, ours %04X, action %04X)".format(f.requestId, p!!.rid, f.action and 0xFFFF))
                p!!.q.offer(f); afterReply(f, node)
                // 5710/57A0/5790… are pushes too: the live view and recording state must still see them.
                if (!alsoPush(f, node)) return
            }
            Xk.ReplyMatch.LATE -> { addLog("Ignored a late $node reply"); return }
            Xk.ReplyMatch.NONE -> afterReply(f, node)
        }
        when (node) {
            "7320" -> {
                val waiter = pushWaiters["7320"]
                when {
                    waiter != null -> waiter.offer(f)
                    assembler != null -> { pendingAnnounce = f; addLog("Button photo waiting for the current download") }
                    else -> buttonPhoto(f)
                }
                return
            }
            // Assistant touch hook: a tap on the glasses starts a voice question.
            "C101", "C107" -> if (probing) addLog("Touch ignored: the live view test is running")
                else onTouch?.let { cb -> main.post { cb() } }
            "57B1" -> {
                if (!quietCapture) onShutter?.let { cb -> main.post { cb() } } // 2.1 hook: phone-side shutter sound
                if (capturing) { busyText = "Processing the photo…"; changed() }
            }
            "5790" -> if (f.data.isNotEmpty()) { recording = f.data[0].toInt() == 1; changed() }
            "5710" -> if (f.data.isNotEmpty()) onPreviewState(f.data[0].toInt() and 0xFF)
            "5760" -> addLog("Glasses voice memo state ${f.data.firstOrNull()?.toInt()}")
            // Video-preview state per the vendor SDK (0 off, 1 on). Recorded only; the live view decides by frames.
            "57A0" -> if (f.replyType == Xk.FMT_BIN && f.data.isNotEmpty()) {
                previewState57A0 = f.data[0].toInt() and 0xFF
                addLog("Video preview state (57A0): $previewState57A0")
            }
        }
        pushWaiters[node]?.offer(f)
    }

    /** Values worth keeping from any reply or push, whoever asked. */
    private fun afterReply(f: Xk.Frame, node: String) {
        when (node) {
            "1003" -> Xk.battery(f)?.let { charging = it.first; battery = it.second; changed() }
        }
    }

    // ---- Assistant touch hook (begin) ----
    /** Called on the main thread when the glasses report a touch-pad tap (nodes C101 / C107). */
    @Volatile var onTouch: (() -> Unit)? = null
    // ---- Assistant touch hook (end) ----

    // ---- 2.1 hooks (read-only; called after the existing success lines) ----
    /** Main thread: the glasses reported the shutter fired (node 57B1). */
    @Volatile var onShutter: (() -> Unit)? = null
    /** Main thread: a photo was saved; the flag is true for photos taken with the glasses' own button. */
    @Volatile var onPhotoSaved: ((Uri, Boolean) -> Unit)? = null
    private fun photoSaved(uri: Uri, fromButton: Boolean) { onPhotoSaved?.let { cb -> main.post { cb(uri, fromButton) } } }

    // ---------------- media ----------------

    @Volatile private var assembler: Xk.PhotoAssembler? = null
    @Volatile private var capturing = false
    @Volatile private var pendingAnnounce: Xk.Frame? = null
    @Volatile private var lastMediaAt = 0L
    @Volatile private var burstOpen = false
    private val busyFlag = AtomicBoolean(false)
    private var mediaNotes = 0

    /** Live view is open, opening or being tested: media that isn't a photo download goes to it. */
    private val previewWanted get() = preview != Preview.OFF || probing

    private fun onMediaElement(type: Int, e: ByteArray) {
        when (type) {
            Xk.MEDIA_PHOTO -> {
                val asm = assembler
                if (asm == null) {
                    if (previewWanted && liveElement(e, "a photo-type element")) return
                    note("Photo data arrived with no download running (${e.size} B)"); return
                }
                when (asm.offer(e)) {
                    Xk.PhotoAssembler.Result.DONE -> mediaDone.offer(true)
                    Xk.PhotoAssembler.Result.FAILED -> mediaDone.offer(false)
                    Xk.PhotoAssembler.Result.MORE -> {}
                }
            }
            Xk.MEDIA_H264 -> {
                val au = VideoAu.fromElement(e, SystemClock.elapsedRealtimeNanos())
                if (au == null) {
                    if (previewWanted && liveElement(e, "a type-2 element")) return
                    note("Unreadable video element (${e.size} B)"); return
                }
                videoFrames++
                if (preview != Preview.ON) { preview = Preview.ON; changed() }
                videoSink?.invoke(au)
            }
            else -> if (!(previewWanted && liveElement(e, "a type-$type element"))) note("Unknown media type $type (${e.size} B)")
        }
    }

    /**
     * 3.3: anything that might be a live view picture, whatever carried it: a whole JPEG (small
     * previews may be sent as pictures) or H.264 behind an unknown header. True when it was used.
     */
    private fun liveElement(e: ByteArray, what: String): Boolean {
        val j = Xk.jpegStart(e)
        if (j >= 0) {
            val last = e.size - 1
            val whole = (maxOf(j, last - 16) until last).any { e[it] == 0xFF.toByte() && e[it + 1] == 0xD9.toByte() }
            if (!whole) { sniffLog("part of a JPEG in $what (${e.size} B)"); return false }
            jpegFrames++
            if (jpegFrames <= 3) addLog("Live view: a whole JPEG picture arrived in $what (${e.size - j} B)")
            if (preview != Preview.ON && preview != Preview.OFF) { preview = Preview.ON; changed() }
            jpegSink?.invoke(e.copyOfRange(j, e.size))
            return true
        }
        val au = VideoAu.scan(e, SystemClock.elapsedRealtimeNanos())
        if (au != null) {
            videoFrames++
            if (videoFrames <= 3) addLog("Live view: H.264 found in $what (${e.size} B)")
            if (preview != Preview.ON && preview != Preview.OFF) { preview = Preview.ON; changed() }
            videoSink?.invoke(au)
            return true
        }
        sniffLog("$what not used: " + Xk.describeRaw(e))
        return false
    }

    // ---------------- receive sniffer (3.3) ----------------

    private fun onSkipped(first: ByteArray, length: Int) {
        skippedRuns++
        skippedBytes += length
        sniffLog("skipped " + Xk.describeRaw(first, length))
    }

    /** Logs while the sniffer runs: at most 60 lines a session, one per 150 ms after the first ten. */
    private fun sniffLog(line: String) {
        if (!sniffing) return
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (sniffLines >= 60) return
            if (sniffLines >= 10 && now - sniffLastAt < 150) return
            sniffLines++
            sniffLastAt = now
        }
        addLog("Sniffer: $line")
    }

    private fun startSniff(why: String) {
        synchronized(this) { sniffLines = 0 }
        mediaNotes = 0
        sniffing = true
        link?.parser?.lenient = true
        addLog("Sniffer on ($why): ${rxBytes} B read so far, ${skippedBytes} B skipped")
    }

    private fun stopSniff() {
        if (!sniffing) return
        sniffing = false
        link?.parser?.lenient = false
    }

    /** Counters for one listening window, so the log shows what the radio carried. */
    private inner class RxWindow {
        val rx0 = rxBytes; val skip0 = skippedBytes; val other0 = otherFrames; val media0 = mediaFramesSeen
        val video0 = videoFrames; val jpeg0 = jpegFrames; val f50 = mediaFrag5
        val frameBytes0 = link?.parser?.frameBytes ?: 0L
        val media get() = mediaFramesSeen - media0
        val pictures get() = (videoFrames - video0) + (jpegFrames - jpeg0)
        fun text(): String {
            val fb = (link?.parser?.frameBytes ?: 0L) - frameBytes0
            return "${rxBytes - rx0} B read, $fb B in frames, ${skippedBytes - skip0} B skipped, $media media frame(s), " +
                "${videoFrames - video0} video picture(s), ${jpegFrames - jpeg0} JPEG picture(s), ${mediaFrag5 - f50} divideType-5, " +
                "${otherFrames - other0} unknown-head frame(s)"
        }
    }

    // ---------------- audio gate (3.3) ----------------

    /**
     * Why the glasses' call audio (SCO) must not start right now, or null. Starting it during a
     * connect, a photo transfer or the live view test, or in the first seconds of a new link, has
     * dropped the Bluetooth link on the owner's phone.
     */
    fun audioBlocker(): String? = audioBlockerFor(
        state, probing, capturing || assembler != null, SystemClock.elapsedRealtime() - readySince,
    )

    /** How long a glasses tap should wait before starting a voice question: 0 now, null to drop it. */
    fun tapDelayMs(): Long? = tapDelayFor(state, probing, SystemClock.elapsedRealtime() - readySince)

    private fun onMediaBroken(type: Int?, why: String) {
        note("Media element dropped: $why")
        if (type == null || type == Xk.MEDIA_PHOTO) {
            if (assembler?.fail(why) == Xk.PhotoAssembler.Result.FAILED) mediaDone.offer(false)
        }
    }

    private fun note(line: String) { if (mediaNotes++ < 40) addLog(line) }

    /**
     * Fire the camera, pull the picture over Bluetooth, and save it to the gallery. [onDone] (main
     * thread) gets the saved photo or null. [announce] false is the Snapshot view: no shutter sound,
     * no photo notification and the "last photo" stays as it was.
     */
    fun takePhoto(onDone: ((Uri?) -> Unit)? = null, announce: Boolean = true) {
        if (state != State.READY || probing || !busyFlag.compareAndSet(false, true)) {
            onDone?.let { cb -> main.post { cb(null) } }
            return
        }
        quietCapture = !announce
        val loud = announce
        setBusy(if (loud) "Taking a photo…" else "Snapshot…")
        io.execute {
            var reopen = false
            var saved: Uri? = null
            try {
                if (preview == Preview.ON || preview == Preview.OPENING) { closePreviewNow(); reopen = true }
                capturing = true
                val q = expectPush("7320")
                val r = request("57B0", Xk.ACTION_EXECUTE, ByteArray(0), 4_000)
                r?.errCode?.takeIf { it != 0 }?.let {
                    throw LinkException(
                        if (!signedIn) "the glasses need your key for photos (${Xk.errorText(it)}). Finish setup on the Glasses tab."
                        else if (it == 1) "the glasses didn't accept your key (${Xk.errorText(it)}). Check it in Set up."
                        else "the camera is busy (${Xk.errorText(it)})."
                    )
                }
                val announce = awaitPush("7320", q, 12_000) ?: throw LinkException("the glasses didn't report a new photo.")
                val count = Xk.elementCount(announce)
                if (count == null) { release("bad part count"); throw LinkException("the glasses announced an unreadable photo.") }
                val uri = download(count)
                saved = uri
                if (loud) {
                    lastPhoto = uri
                    lastMessage = "Photo saved to your gallery."
                    lastPhoto?.let { photoSaved(it, false) }
                }
            } catch (e: Exception) {
                lastError = "Photo failed: ${e.message}"
            } finally {
                pushWaiters.remove("7320")
                capturing = false
                quietCapture = false
                busyFlag.set(false)
                setReady()
                fetchPendingAnnounce()
                if (reopen && link != null) openPreviewNow()
                onDone?.let { cb -> val u = saved; main.post { cb(u) } }
            }
        }
    }

    /** A photo taken with the button on the glasses. It must be downloaded or released with 7500. */
    private fun buttonPhoto(f: Xk.Frame) {
        if (!prefs.saveButtonPhotos) {
            io.execute { release("button photo saving is off"); addLog("Button photo skipped (turned off in Settings)") }
            return
        }
        capturing = true
        io.execute {
            setBusy("Saving the photo from the glasses…")
            try {
                val count = Xk.elementCount(f)
                if (count == null) { release("bad part count"); throw LinkException("unreadable photo announcement") }
                lastPhoto = download(count)
                lastMessage = "Saved the photo you took with the glasses."
                lastPhoto?.let { photoSaved(it, true) }
            } catch (e: Exception) {
                lastError = "Couldn't fetch that photo: ${e.message}"
            } finally {
                capturing = false
                setReady()
                fetchPendingAnnounce()
            }
        }
    }

    private fun fetchPendingAnnounce() {
        val f = pendingAnnounce ?: return
        pendingAnnounce = null
        if (link != null) buttonPhoto(f)
    }

    /** Ends (or aborts) the glasses' current transfer so they never stay blocked. */
    private fun release(why: String) {
        if (link == null) return
        addLog("Releasing the transfer: $why")
        runCatching { request("7500", Xk.ACTION_EXECUTE, ByteArray(0), 2_000) }
    }

    private fun download(count: Int): Uri {
        val asm = Xk.PhotoAssembler(count)
        assembler = asm
        runCatching { xferLock?.acquire(120_000) }
        val t0 = SystemClock.elapsedRealtime()
        var retries = 0
        var ok = false
        try {
            for (i in 1..count) {
                var tries = 0
                while (!asm.has(i)) {
                    if (tries > ELEMENT_RETRIES) throw LinkException(asm.failReason ?: "part $i never arrived")
                    if (tries > 0) {
                        retries++
                        addLog("Retry part $i: ${asm.failReason ?: "no data"}")
                        waitQuiet()
                    }
                    busyText = "Downloading photo ${(i - 1) * 100 / count}%"
                    changed()
                    mediaDone.clear()
                    asm.start(i)
                    val rid = nextRequestId()
                    val p = Pending("7300", rid)
                    pending["7300"] = p
                    try {
                        send(Xk.control(nextOrder(), "7300", Xk.ACTION_WRITE, rid, byteArrayOf(i.toByte())))
                        val sentAt = SystemClock.elapsedRealtime()
                        while (true) {
                            val r = mediaDone.poll(250, TimeUnit.MILLISECONDS)
                            if (link == null) throw LinkException("Lost the connection to the glasses.")
                            if (r != null) break
                            p.q.poll()?.let { reply ->
                                if (reply === linkDown) throw LinkException("Lost the connection to the glasses.")
                                reply.errCode?.takeIf { it != 0 }?.let { throw LinkException("the glasses refused part $i (${Xk.errorText(it)})") }
                            }
                            val now = SystemClock.elapsedRealtime()
                            if (now - maxOf(sentAt, lastMediaAt) > ELEMENT_IDLE_MS || now - sentAt > ELEMENT_CAP_MS) {
                                asm.fail("no data for ${ELEMENT_IDLE_MS / 1000} s")
                                break
                            }
                        }
                    } finally {
                        pending.remove("7300", p)
                    }
                    tries++
                }
            }
            val img = asm.jpeg()
            ok = true
            val ms = SystemClock.elapsedRealtime() - t0
            addLog("Photo ${img.size / 1024} KB in $count parts, $ms ms, ${if (ms > 0) img.size / ms else 0} KB/s, $retries retries")
            return saveJpeg(img)
        } finally {
            assembler = null
            if (link != null) runCatching { request("7500", Xk.ACTION_EXECUTE, ByteArray(0), 2_000) }
            if (!ok) addLog("Transfer ended early (sent 7500)")
            runCatching { xferLock?.release() }
        }
    }

    /** Lets a half-sent burst drain before asking for the same part again. */
    private fun waitQuiet() {
        val end = SystemClock.elapsedRealtime() + 4_000
        while (burstOpen && SystemClock.elapsedRealtime() < end && SystemClock.elapsedRealtime() - lastMediaAt < 1_500) Thread.sleep(50)
        burstOpen = false
    }

    private fun saveJpeg(bytes: ByteArray): Uri {
        val name = "P3_IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + prefs.albumName)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis())
        }
        val resolver = app.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("couldn't create the gallery file")
        try {
            resolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        addLog("Saved photo, ${bytes.size / 1024} KB")
        return uri
    }

    // ---------------- other actions ----------------

    fun refresh() = io.execute {
        if (link == null) return@execute
        runCatching {
            request("1003", Xk.ACTION_READ, ByteArray(0), 1_500)
            request("5713", Xk.ACTION_READ, ByteArray(0), 1_500)?.let { applyCounts(it) }
        }
        changed()
    }

    /** Start or stop recording on the glasses themselves (only some models store video). */
    fun setRecording(on: Boolean) {
        if (state != State.READY || !busyFlag.compareAndSet(false, true)) return
        setBusy(if (on) "Starting video…" else "Stopping video…")
        io.execute {
            try {
                if (on && preview != Preview.OFF) closePreviewNow()
                val before = videoCount
                val reply = request("5780", Xk.ACTION_EXECUTE, byteArrayOf(if (on) 1 else 0), 5_000)
                    ?: throw LinkException("no answer from the glasses.")
                reply.errCode?.takeIf { it != 0 }?.let { code ->
                    throw LinkException(
                        when (code) {
                            2 -> "the glasses are busy (live view or mic in use). Try again in a moment."
                            3, 4 -> "these glasses can't record from the app. Use Live view to record on the phone."
                            else -> Xk.errorText(code) + "."
                        }
                    )
                }
                recording = on
                if (!on) {
                    request("5713", Xk.ACTION_READ, ByteArray(0), 1_500)?.let { applyCounts(it) }
                    val after = videoCount
                    lastMessage = if (before != null && after != null && after > before) "Recording saved on the glasses."
                    else "Recording stopped. If no new video shows up, your glasses don't store videos: use Live view to record on the phone."
                } else lastMessage = "Recording on the glasses."
            } catch (e: Exception) {
                lastError = "Video ${if (on) "start" else "stop"} failed: ${e.message}"
            } finally {
                busyFlag.set(false)
                setReady()
            }
        }
    }

    // ---------------- live view ----------------

    /** Opens the glasses' live video. [done] gets null when it's open, or a message. */
    fun openPreview(done: (String?) -> Unit) = io.execute {
        val msg = try { openPreviewNow() } catch (e: Exception) { e.message ?: "Couldn't open live view." }
        main.post { done(msg) }
    }

    private fun openPreviewNow(): String? {
        if (link == null) return "Connect the glasses first."
        preview = Preview.OPENING
        videoFrames = 0
        changed()
        startSniff("live view")
        val q5710 = expectPush("5710")
        try {
            val msg = openPreviewInner()
            if (preview == Preview.ON) afterOpen(q5710)
            return msg
        } finally {
            pushWaiters.remove("5710", q5710)
        }
    }

    /**
     * After an accepted open: the glasses push 5710 (01 on, 02 busy). Busy right after a touch means
     * the mic had them: wait up to 4 s for 00/01 and open once more. A pull the test found useful is sent.
     */
    private fun afterOpen(q: LinkedBlockingQueue<Xk.Frame>) {
        val st = awaitPush("5710", q, 2_000)?.data?.firstOrNull()?.toInt()
        if (st == 2) {
            addLog("Live view: the glasses are busy (5710 = 02); waiting for them")
            val end = SystemClock.elapsedRealtime() + 4_000
            var v: Int? = 2
            while (v == 2 && SystemClock.elapsedRealtime() < end) {
                v = awaitPush("5710", q, maxOf(1L, end - SystemClock.elapsedRealtime()))?.data?.firstOrNull()?.toInt()
            }
            if (v == 0 || v == 1) {
                addLog("Live view: 5710 = %02X, opening once more".format(v))
                val via = openedWith
                val r = if (via != null) request(probeNode(via), probeAction(via), byteArrayOf(1), 4_000)
                else request("5720", prefs.previewAction, byteArrayOf(1), 4_000)
                if ((r?.errCode ?: 0) == 0) preview = Preview.ON
            }
        }
        prefs.livePull?.let { name ->
            PULL_TRIES.firstOrNull { pullName(it) == name }?.let { t ->
                request(t.first, t.second, t.third, 1_500)?.let { addLog("Live view: ${pullName(t)} → ${describeReply(it)}") }
            }
        }
        changed()
    }

    private fun openPreviewInner(): String? {
        // 3.1: a command the live view probe found working on these glasses goes first.
        val learned = prefs.livePath
        if (learned != null && learned in PROBE_PATHS && !learned.startsWith("5720/")) return openVia(learned)
        openedWith = null
        var action = prefs.previewAction
        var r = request("5720", action, byteArrayOf(1), 4_000)
        if (r?.errCode in setOf(3, 4, 6)) {
            action = if (action == Xk.ACTION_EXECUTE) Xk.ACTION_WRITE else Xk.ACTION_EXECUTE
            addLog("Live view: trying action $action")
            r = request("5720", action, byteArrayOf(1), 4_000)
        }
        val code = r?.errCode
        preview = when (code) {
            null, 0 -> Preview.ON
            2 -> Preview.BUSY
            3, 4, 6 -> Preview.UNSUPPORTED
            else -> Preview.OFF
        }
        if (preview == Preview.ON && r != null) prefs.previewAction = action
        request("5711", Xk.ACTION_EXECUTE, byteArrayOf(0), 1_500)?.let { addLog("Live view state ${hex(it.data)}") }
        changed()
        return when (preview) {
            Preview.ON -> null
            Preview.BUSY -> "The glasses are busy (the mic or a call is using them). Stop that, then try again."
            Preview.UNSUPPORTED -> "These glasses didn't accept the live view command."
            else -> "The glasses said no (${Xk.errorText(code ?: 1)})."
        }
    }

    /** Always close the live view when leaving it, or the glasses keep flooding the link. */
    fun closePreview() = io.execute { closePreviewNow() }

    private fun closePreviewNow() {
        if (!probing) stopSniff()
        if (preview == Preview.OFF) return
        preview = Preview.OFF
        val via = openedWith
        openedWith = null
        if (via != null) {
            if (link != null) runCatching { request(probeNode(via), probeAction(via), byteArrayOf(0), 2_000) }
        } else if (link != null) runCatching { request("5720", prefs.previewAction, byteArrayOf(0), 2_000) }
        changed()
    }

    /** Opens the live view with a command the probe learned (node and action, data [01]). */
    private fun openVia(path: String): String? {
        openedWith = path
        addLog("Live view: opening with $path (found by the live view test)")
        val r = request(probeNode(path), probeAction(path), byteArrayOf(1), 4_000)
        val code = r?.errCode
        preview = when (code) {
            null, 0 -> Preview.ON
            2 -> Preview.BUSY
            3, 4, 6 -> Preview.UNSUPPORTED
            else -> Preview.OFF
        }
        if (preview != Preview.ON) openedWith = null
        changed()
        return when (preview) {
            Preview.ON -> null
            Preview.BUSY -> "The glasses are busy (the mic or a call is using them). Stop that, then try again."
            Preview.UNSUPPORTED -> "These glasses didn't accept the live view command."
            else -> "The glasses said no (${Xk.errorText(code ?: 1)})."
        }
    }

    // ---------------- live view probe (3.1) ----------------

    /** Result of [runLiveProbe]: the command that produced video, or null, and every line it logged. */
    data class LiveProbe(val path: String?, val summary: String, val log: List<String>, val complete: Boolean)

    private fun describeReply(f: Xk.Frame?): String {
        if (f == null) return "no answer"
        val type = f.replyType
        val base = "type 0x%02X, %d byte(s)".format(type and 0xFF, f.data.size)
        return when {
            type == Xk.FMT_ERRCODE && f.data.isNotEmpty() ->
                "$base, status 0x%02X (%s)".format(f.data[0].toInt() and 0xFF, Xk.errorText(f.data[0].toInt() and 0xFF))
            type == Xk.FMT_JSON -> "$base, JSON " + (f.json()?.take(160) ?: "?")
            f.data.isNotEmpty() -> "$base, data " + hex(f.data.take(16).toByteArray())
            else -> base
        }
    }

    /**
     * Learns how (and whether) these glasses send live video over Bluetooth: reads the preview info
     * (1001 preview_*, 57A0, 2410, 2420), then tries each open command in turn. 3.3: a refused command
     * is skipped at once; after an accepted one it waits for the 5710 push (busy → waits and opens once
     * more), listens 10 s when the glasses say the preview is on (5 s otherwise) with the receive
     * sniffer on, then tries requests that might pull frames ([PULL_TRIES]) and, when [wifi] is
     * allowed, looks for the glasses on Wi-Fi. Touches on the glasses are ignored meanwhile. The working
     * command is saved for next time, or "none". [onLine] and [done] run on the main thread.
     */
    fun runLiveProbe(onLine: ((String) -> Unit)?, wifi: Boolean = false, done: (LiveProbe) -> Unit) {
        if (state != State.READY || recording || capturing || !busyFlag.compareAndSet(false, true)) {
            val why = when {
                state == State.IDLE || state == State.CONNECTING -> "Connect the glasses first."
                recording -> "The glasses are recording video. Stop that first."
                else -> "The glasses are busy. Try again in a moment."
            }
            main.post { done(LiveProbe(null, why, emptyList(), complete = false)) }
            return
        }
        probing = true
        setBusy("Testing live view…")
        io.execute {
            val lines = mutableListOf<String>()
            fun say(s: String) {
                synchronized(lines) { lines += s }
                addLog("Live test: $s")
                onLine?.let { cb -> main.post { cb(s) } }
            }
            var found: String? = null
            var foundPull: String? = null
            var complete = false
            var anyMedia = false
            var wifiTried = false
            try {
                if (preview != Preview.OFF) closePreviewNow()
                startSniff("live view test")
                say("Phone model ${Build.MANUFACTURER} ${Build.MODEL}; glasses ${model ?: "?"}, firmware ${firmware ?: "?"}")
                say("1001 preview fields at sign-in: " +
                    (previewInfo.takeIf { it.isNotEmpty() }?.entries?.joinToString(", ") { "${it.key}=${it.value}" } ?: "none"))
                val info = request("1001", Xk.ACTION_READ, ByteArray(0), 1_500)
                info?.let { applyInfo(it) }
                say("1001 read again: " + if (info == null) "no answer (it usually answers once per session)" else
                    describeReply(info).take(80) + if (previewInfo.isNotEmpty()) " → " + previewInfo.entries.joinToString(", ") { "${it.key}=${it.value}" } else "")
                for (node in listOf("57A0", "2410", "2420")) {
                    if (link == null) throw LinkException("Lost the connection to the glasses.")
                    val r = request(node, Xk.ACTION_READ, ByteArray(0), 1_500)
                    if (node == "57A0" && r != null && r.replyType == Xk.FMT_BIN && r.data.isNotEmpty()) previewState57A0 = r.data[0].toInt() and 0xFF
                    say("$node read: ${describeReply(r)}")
                }
                for (path in PROBE_PATHS) {
                    if (link == null) throw LinkException("Lost the connection to the glasses.")
                    val node = probeNode(path)
                    val action = probeAction(path)
                    preview = Preview.OPENING
                    changed()
                    val q5710 = expectPush("5710")
                    try {
                        var w = RxWindow()
                        var r = request(node, action, byteArrayOf(1), 3_000)
                        say("$path open [01]: ${describeReply(r)}")
                        val code = r?.errCode
                        if (code != null && code != 0 && code != 2) {
                            say("  refused, not listening")
                            preview = Preview.OFF
                            changed()
                            continue
                        }
                        // What the glasses say about the preview: 01 on, 02 busy (the mic had them), 00 off.
                        var st = awaitPush("5710", q5710, 2_500)?.data?.firstOrNull()?.toInt()
                        say("  5710 after open: " + (st?.let { "%02X".format(it) } ?: "no push"))
                        if (st == 2 || code == 2) {
                            say("  busy: waiting up to 4 s for the glasses, then opening once more")
                            val end = SystemClock.elapsedRealtime() + 4_000
                            while (st == 2 && SystemClock.elapsedRealtime() < end) {
                                st = awaitPush("5710", q5710, maxOf(1L, end - SystemClock.elapsedRealtime()))?.data?.firstOrNull()?.toInt() ?: st
                            }
                            r = request(node, action, byteArrayOf(1), 3_000)
                            say("  $path open again [01]: ${describeReply(r)}")
                            st = awaitPush("5710", q5710, 2_500)?.data?.firstOrNull()?.toInt() ?: st
                            say("  5710 after the second open: " + (st?.let { "%02X".format(it) } ?: "no push"))
                        }
                        // Wi-Fi: the preview might go over the glasses' own access point or Wi-Fi Direct.
                        val finder = if (wifi && !wifiTried) { wifiTried = true; WifiFinder(app).also { it.start(wifiTokens()) } } else null
                        val listen = if (st == 1) PROBE_LISTEN_ON_MS else PROBE_LISTEN_MS
                        val end = SystemClock.elapsedRealtime() + listen
                        while (SystemClock.elapsedRealtime() < end && w.pictures < 3 && link != null) Thread.sleep(100)
                        say("  heard for ${listen / 1000} s: ${w.text()}")
                        if (w.media > 0) anyMedia = true
                        if (w.pictures >= 3) { found = path; break }
                        // Maybe the preview is fetched like photos are: ask, then listen.
                        if (w.media == 0L) {
                            for (t in PULL_TRIES) {
                                if (link == null) break
                                w = RxWindow()
                                val pr = request(t.first, t.second, t.third, 1_500)
                                val listenEnd = SystemClock.elapsedRealtime() + 1_500
                                while (SystemClock.elapsedRealtime() < listenEnd && w.pictures < 3 && link != null) Thread.sleep(100)
                                say("  pull ${pullName(t)}: ${describeReply(pr)}; ${w.text()}")
                                if (t.first == "7300") release("live view test asked for a photo part")
                                if (w.media > 0) anyMedia = true
                                if (w.pictures >= 2) { foundPull = pullName(t); break }
                            }
                            if (foundPull != null) { found = path; say("  frames came after ${foundPull}") }
                        }
                        if (finder != null) {
                            val res = finder.finish(3_000)
                            wifiFound = res.matches
                            res.lines.forEach { say("  Wi-Fi: $it") }
                        }
                        if (link != null) {
                            val st57 = request("57A0", Xk.ACTION_READ, ByteArray(0), 1_000)
                            if (st57 != null) say("  57A0 while open: ${describeReply(st57)}")
                            val c = runCatching { request(node, action, byteArrayOf(0), 2_000) }.getOrNull()
                            say("  close [00]: ${describeReply(c)}")
                        }
                    } finally {
                        pushWaiters.remove("5710", q5710)
                    }
                    preview = Preview.OFF
                    changed()
                    Thread.sleep(400)
                    if (found != null) break
                }
                complete = true
            } catch (e: Exception) {
                say("Stopped early: ${e.message}")
            } finally {
                preview = Preview.OFF
                openedWith = null
                probing = false
                stopSniff()
                busyFlag.set(false)
                setReady()
            }
            val wifiNote = wifiFound.takeIf { it.isNotEmpty() }?.let { " On Wi-Fi the glasses show up as ${it.joinToString(", ")}; the app doesn't connect to that yet." } ?: ""
            val summary = when {
                found != null -> "Live video works with command $found" + (foundPull?.let { " (frames follow $it)" } ?: "") + ". The app will use it from now on."
                !complete -> "The test didn't finish, so nothing was changed. Try again with the glasses connected."
                anyMedia -> "The glasses sent some media data but no video the phone could show. Snapshot view will be used." + wifiNote
                else -> "The glasses sent no video over Bluetooth. Snapshot view will be used instead." + wifiNote
            }
            say(summary)
            if (complete) {
                found?.takeIf { it.startsWith("5720/") }?.let { prefs.previewAction = probeAction(it) }
                prefs.livePath = found ?: LIVE_NONE
                prefs.livePull = foundPull
                prefs.liveProbeSummary = summary
                prefs.liveProbeAt = System.currentTimeMillis()
                prefs.liveProbeVersion = PROBE_VERSION
            }
            val result = LiveProbe(found, summary, synchronized(lines) { lines.toList() }, complete)
            main.post { done(result) }
        }
    }

    /** Names the glasses might use on Wi-Fi: their Bluetooth name, model, last 4 hex of the address, brand words. */
    private fun wifiTokens(): List<String> = WifiFinder.tokens(deviceName ?: prefs.glassesName, model, prefs.glassesAddress)

    private fun onPreviewState(v: Int) {
        when (v) {
            0 -> if (preview == Preview.ON || preview == Preview.OPENING) {
                preview = Preview.OFF
                main.post { previewStateSink?.invoke(false) }
            }
            1 -> preview = Preview.ON
            2 -> preview = Preview.BUSY
        }
        changed()
    }

    private fun setPreviewOff() {
        if (!probing) stopSniff()
        if (preview == Preview.OFF) return
        preview = Preview.OFF
        main.post { previewStateSink?.invoke(false) }
    }

    /** The mic link (SCO) is about to start: the glasses can't stream video at the same time. */
    fun micWillStart() {
        if (preview == Preview.OFF) return
        addLog("Closing live view: the mic is starting")
        setPreviewOff()
        closePreview()
    }

    // ---------------- diagnostics ----------------

    /** Runs [block] on the link thread and waits for it, for the diagnostics checks. */
    fun <T> onLinkThread(timeoutMs: Long, block: () -> T): T = io.submit(Callable(block)).get(timeoutMs, TimeUnit.MILLISECONDS)

    /** Battery read round trip in ms, or null without an answer. Call through [onLinkThread]. */
    fun probeBattery(): Long? {
        if (link == null) return null
        val t0 = SystemClock.elapsedRealtime()
        return request("1003", Xk.ACTION_READ, ByteArray(0), 2_000)?.let { SystemClock.elapsedRealtime() - t0 }
    }

    // ---------------- small helpers ----------------

    private fun setBusy(text: String) { state = State.BUSY; busyText = text; lastError = null; lastMessage = null; changed() }
    private fun setReady() { busyText = null; state = if (link != null) State.READY else State.IDLE; changed() }

    fun clearMessages() { lastError = null; lastMessage = null; changed() }

    /** Settings › Privacy: empties the connection log and the list of link losses. */
    @Synchronized
    fun clearLog() {
        log.clear()
        synchronized(linkLosses) { linkLosses.clear() }
    }

    @Synchronized
    fun addLog(line: String) {
        log.addLast(logTime.format(Date()) + "  " + line)
        while (log.size > 400) log.removeFirst()
    }

    @Synchronized
    fun logLines(max: Int): List<String> = log.toList().takeLast(max)

    @Synchronized
    fun logText(): String = buildString {
        appendLine("P3 Companion glasses log")
        appendLine("Device: ${deviceName ?: prefs.glassesName ?: "-"}  Model: ${model ?: "-"}  Firmware: ${firmware ?: "-"}")
        log.forEach { appendLine(it) }
    }

    companion object {
        private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val TOKEN_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        private val BACKOFF_MS = longArrayOf(2_000, 5_000, 10_000, 20_000, 40_000, 60_000)
        private const val ACTIVE_RETRY_MS = 3 * 60_000L
        private const val ELEMENT_RETRIES = 1
        private const val ELEMENT_IDLE_MS = 4_000L
        private const val ELEMENT_CAP_MS = 15_000L

        /** Live view open commands tried by the probe, in order: node/action, data [01] to open, [00] to close. */
        /**
         * 3.3 dropped 102A (it starts the glasses' own mic: a touch, then the live view closes) and 102C
         * (rejected the data). 5720/3 is the one the owner's E13C-5 accepts.
         */
        val PROBE_PATHS = listOf("5720/3", "5720/2", "5710/3", "5710/2")
        /** Bumped when the probe learns new tricks, so an old "none" result is tested again. */
        const val PROBE_VERSION = 3
        /** Saved when the probe found that no command makes the glasses send video. */
        const val LIVE_NONE = "none"
        /** Least time between sending 102E and the first setup read (validated harness: 150 ms spacing). */
        private const val BIND_SETTLE_MS = 300L
        /** A timed-out request id only marks a reply as late for this long. */
        private const val STALE_MS = 15_000L
        /** State nodes the glasses push by themselves (also when one of them answers a request). */
        private val PUSH_NODES = setOf("5710", "5711", "57A0", "5750", "5790")
        private const val PROBE_LISTEN_MS = 5_000L
        /** Listening time after the glasses pushed 5710 = 01 (preview on). */
        private const val PROBE_LISTEN_ON_MS = 10_000L
        /** Requests that might pull preview frames, tried after an accepted open: node, action, data. */
        val PULL_TRIES: List<Triple<String, Int, ByteArray>> = listOf(
            Triple("5711", Xk.ACTION_EXECUTE, byteArrayOf(0)),
            Triple("5711", Xk.ACTION_EXECUTE, byteArrayOf(1)),
            Triple("5711", Xk.ACTION_READ, ByteArray(0)),
            Triple("5750", Xk.ACTION_READ, ByteArray(0)),
            Triple("7300", Xk.ACTION_WRITE, byteArrayOf(1)),
        )

        fun pullName(t: Triple<String, Int, ByteArray>) = "${t.first}/${t.second}" + if (t.third.isNotEmpty()) " " + hex(t.third) else ""

        /** The glasses' call audio may start only this long after the link became ready. */
        const val AUDIO_SETTLE_MS = 3_000L

        /** Pure rule behind [audioBlocker]. [readyForMs] is the time since the link became ready. */
        fun audioBlockerFor(state: State, probing: Boolean, transferring: Boolean, readyForMs: Long): String? = when {
            state == State.CONNECTING -> "the glasses are still connecting"
            probing -> "the live view test is running"
            transferring -> "a photo is coming over Bluetooth"
            (state == State.READY || state == State.BUSY) && readyForMs < AUDIO_SETTLE_MS -> "the glasses connected a moment ago"
            else -> null
        }

        /** Pure rule behind [tapDelayMs]. */
        fun tapDelayFor(state: State, probing: Boolean, readyForMs: Long): Long? = when {
            probing -> null
            state == State.CONNECTING -> 500L
            (state == State.READY || state == State.BUSY) && readyForMs < AUDIO_SETTLE_MS -> AUDIO_SETTLE_MS - readyForMs
            else -> 0L
        }

        fun probeNode(path: String) = path.substringBefore('/')
        fun probeAction(path: String) = path.substringAfter('/').toIntOrNull() ?: Xk.ACTION_EXECUTE

        @Volatile private var instance: XkGlasses? = null

        fun get(context: Context, prefs: Prefs): XkGlasses =
            instance ?: synchronized(this) { instance ?: XkGlasses(context, prefs).also { instance = it } }

        /** Loose on purpose: the glasses are sold under several names. Used to rank, never to hide. */
        fun looksLikeGlasses(name: String): Boolean {
            val n = name.lowercase(Locale.US)
            return listOf("xk", "glass", "nova", "p3", "lens", "w20", "e13", "shenju", "camera").any { n.contains(it) }
        }

        private val SUFFIX = Regex("(?i)[_ -]([0-9a-f]{4})$")

        /** "xk one Pro_F773" style: the name ends in the last 4 hex digits of the address. */
        fun strongMatch(name: String?, address: String): Boolean {
            val m = SUFFIX.find(name?.trim() ?: return false) ?: return false
            return address.replace(":", "").endsWith(m.groupValues[1], ignoreCase = true)
        }

        fun maskAddress(a: String?): String =
            if (a == null || a.length < 5) "-" else "XX:XX:XX:XX:" + a.takeLast(5).uppercase(Locale.US)

        fun deviceExtra(i: Intent): BluetoothDevice? =
            if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

        fun hex(b: ByteArray): String = b.joinToString(" ") { "%02X".format(it) }
    }
}
