package com.zdycomp.p3companion

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Live control of the glasses over Bluetooth Classic: connect, battery, take a photo and save it,
 * start and stop recording, and media counts. One instance for the whole app.
 *
 * Callers must hold BLUETOOTH_CONNECT (Android 12+) before calling connect.
 */
@SuppressLint("MissingPermission")
class XkGlasses private constructor(context: Context, private val prefs: Prefs) {

    enum class State { IDLE, CONNECTING, READY, BUSY }

    fun interface Listener { fun onGlassesChanged() }

    data class Paired(val address: String, val name: String)

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
    private val listeners = mutableSetOf<Listener>()
    private val io = Executors.newSingleThreadExecutor()

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

    val log = ArrayDeque<String>()

    private var socket: BluetoothSocket? = null
    private var out: OutputStream? = null
    private var reader: Thread? = null
    private val parser = Xk.Parser()
    private val waiting = ConcurrentHashMap<String, LinkedBlockingQueue<Xk.Frame>>()
    private val mediaDone = LinkedBlockingQueue<Boolean>()
    @Volatile private var assembler: Xk.PhotoAssembler? = null
    @Volatile private var capturing = false
    private var txOrder = 0x10
    private var requestId = 0x40
    private var userDisconnect = false

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }
    private fun changed() = main.post { listeners.toList().forEach { it.onGlassesChanged() } }

    fun bluetoothOn() = adapter?.isEnabled == true

    /** Paired Bluetooth devices, glasses-looking names first. */
    fun pairedDevices(): List<Paired> {
        val list = runCatching { adapter?.bondedDevices?.toList() }.getOrNull() ?: emptyList()
        return list.map { Paired(it.address, it.name ?: it.address) }
            .sortedByDescending { looksLikeGlasses(it.name) }
    }

    // ---------------- connect ----------------

    fun connect(address: String, name: String?) {
        if (state == State.CONNECTING) return
        val key = prefs.userKey
        if (key == null) {
            lastError = "Add your glasses key first (see Set up below)."
            changed(); return
        }
        prefs.glassesAddress = address
        if (name != null) prefs.glassesName = name
        userDisconnect = false
        lastError = null
        state = State.CONNECTING
        changed()
        io.execute {
            try {
                openSocket(address)
                bind(key)
                state = State.READY
                lastMessage = "Connected."
                changed()
                refreshNow()
            } catch (e: Exception) {
                closeSocket()
                state = State.IDLE
                lastError = e.message ?: "Couldn't connect."
                addLog("Connect failed: ${lastError}")
                changed()
            }
        }
    }

    fun reconnectSaved() {
        val addr = prefs.glassesAddress ?: return
        if (state != State.IDLE || prefs.userKey == null || !bluetoothOn()) return
        connect(addr, prefs.glassesName)
    }

    private fun openSocket(address: String) {
        val a = adapter ?: error("This phone has no Bluetooth.")
        if (!a.isEnabled) error("Bluetooth is off.")
        runCatching { a.cancelDiscovery() }
        val device: BluetoothDevice = a.getRemoteDevice(address)
        addLog("Opening link to ${device.name ?: address}")
        val attempts = listOf<Pair<String, () -> BluetoothSocket>>(
            "secure" to { device.createRfcommSocketToServiceRecord(SPP) },
            "insecure" to { device.createInsecureRfcommSocketToServiceRecord(SPP) },
            "channel 8" to {
                device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, 8) as BluetoothSocket
            },
        )
        var lastFail: Exception? = null
        for ((label, make) in attempts) {
            try {
                val s = make()
                s.connect()
                socket = s
                out = s.outputStream
                startReader(s.inputStream)
                addLog("Link open ($label)")
                return
            } catch (e: Exception) {
                lastFail = e
                addLog("Link $label failed: ${e.message}")
            }
        }
        throw IllegalStateException(
            "The glasses didn't answer. Make sure they're on, paired in Bluetooth settings, " +
                "and that LensMoo is closed. (${lastFail?.message ?: "no reply"})"
        )
    }

    private fun bind(userKey: String) {
        val token = (1..61).map { TOKEN_CHARS.random() }.joinToString("").toByteArray(Charsets.US_ASCII)
        val blob = ByteArray(64).also { java.util.Random().nextBytes(it) }
        send(Xk.control(nextOrder(), "0001", Xk.ACTION_EXECUTE, 1, token))
        Thread.sleep(200)
        send(Xk.control(nextOrder(), "0002", Xk.ACTION_EXECUTE, 4, blob))
        Thread.sleep(600)
        val reply = request("102E", Xk.ACTION_EXECUTE, Xk.userBindData(userKey), 6_000, logData = false)
            ?: error("The glasses didn't answer the sign-in step. Turn them off and on, then try again.")
        if (reply.replyType == 4 && reply.data.isNotEmpty() && reply.data[0].toInt() != 0) {
            error("The glasses refused this key. Check it in Set up.")
        }
        addLog("Bound")
    }

    fun disconnect() {
        userDisconnect = true
        closeSocket()
        state = State.IDLE
        recording = false
        addLog("Disconnected")
        changed()
    }

    private fun closeSocket() {
        runCatching { socket?.close() }
        socket = null
        out = null
        reader = null
        waiting.values.forEach { it.clear() }
    }

    // ---------------- actions ----------------

    fun refresh() = io.execute { refreshNow() }

    private fun refreshNow() {
        if (socket == null) return
        request("1003", Xk.ACTION_READ, ByteArray(0), 3_000)?.let { f ->
            val d = f.data
            if (d.size >= 2) {
                charging = d[0].toInt() == 1
                battery = (d[1].toInt() and 0xFF).takeIf { it in 0..100 }
            }
        }
        request("1001", Xk.ACTION_READ, ByteArray(0), 3_000)?.json()?.let { s ->
            runCatching {
                val j = JSONObject(s)
                deviceName = j.optString("dev_name").ifBlank { null }
                firmware = j.optString("soft_ver").ifBlank { null }
                model = j.optString("prod_mode").ifBlank { null }
                if (battery == null) battery = j.optString("battery_main").toIntOrNull()
            }
        }
        request("5713", Xk.ACTION_READ, ByteArray(0), 3_000)?.json()?.let { s ->
            runCatching {
                val j = JSONObject(s)
                photoCount = j.optString("photo_num").toIntOrNull()
                videoCount = j.optString("video_num").toIntOrNull()
                audioCount = j.optString("record_num").toIntOrNull()
            }
        }
        changed()
    }

    /** Fire the camera, pull the picture over Bluetooth, and save it to the gallery. */
    fun takePhoto() {
        if (state != State.READY) return
        io.execute {
            setBusy("Taking a photo…")
            try {
                waiting.getOrPut("7320") { LinkedBlockingQueue() }.clear()
                capturing = true
                request("57B0", Xk.ACTION_EXECUTE, ByteArray(0), 4_000)
                val announce = await("7320", 20_000) ?: error("The glasses didn't report a new photo.")
                val uri = download(elementCount(announce))
                lastPhoto = uri
                lastMessage = "Photo saved to your gallery."
            } catch (e: Exception) {
                lastError = "Photo failed: ${e.message}"
            } finally {
                capturing = false
                setReady()
                refreshNow()
            }
        }
    }

    /** Start or stop recording video on the glasses. The file stays on them until you import it. */
    fun setRecording(on: Boolean) {
        if (state != State.READY) return
        io.execute {
            setBusy(if (on) "Starting video…" else "Stopping video…")
            try {
                val reply = request("5780", Xk.ACTION_EXECUTE, byteArrayOf(if (on) 1 else 0), 5_000)
                    ?: error("No answer from the glasses.")
                if (reply.replyType == 4 && reply.data.isNotEmpty() && reply.data[0].toInt() != 0) {
                    val code = reply.data[0].toInt() and 0xFF
                    error(
                        when (code) {
                            4 -> "these glasses don't support recording from the app. Use the button on the glasses."
                            2 -> "the glasses are busy. Try again in a moment."
                            else -> "the glasses said no (code $code)."
                        }
                    )
                }
                recording = on
                lastMessage = if (on) "Recording on the glasses." else "Recording stopped. Import it over the cable."
            } catch (e: Exception) {
                lastError = "Video ${if (on) "start" else "stop"} failed: ${e.message}"
            } finally {
                setReady()
                if (!on) refreshNow()
            }
        }
    }

    private fun download(count: Int): Uri {
        val asm = Xk.PhotoAssembler(count)
        assembler = asm
        try {
            for (i in 1..count) {
                busyText = "Downloading photo ${i * 100 / count}%"
                changed()
                mediaDone.clear()
                asm.start(i)
                request("7300", Xk.ACTION_WRITE, byteArrayOf(i.toByte()), 8_000)
                    ?: error("No answer for part $i.")
                mediaDone.poll(15, TimeUnit.SECONDS) ?: error("Part $i never arrived.")
            }
            send(Xk.control(nextOrder(), "7500", Xk.ACTION_EXECUTE, nextRequestId()))
            return saveJpeg(asm.jpeg())
        } finally {
            assembler = null
        }
    }

    private fun elementCount(f: Xk.Frame): Int {
        val d = f.data
        val c = when {
            d.size >= 2 -> ((d[0].toInt() and 0xFF) shl 8) or (d[1].toInt() and 0xFF)
            d.size == 1 -> d[0].toInt() and 0xFF
            else -> 0
        }
        val fallback = f.payload.last().toInt() and 0xFF
        return if (c in 1..400) c else if (fallback in 1..400) fallback else error("bad part count")
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
            ?: error("couldn't create the gallery file")
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

    // ---------------- link plumbing ----------------

    private fun nextOrder(): Int = synchronized(this) { val o = txOrder; txOrder = (txOrder + 1) and 0xFF; o }
    private fun nextRequestId(): Int = synchronized(this) { val r = requestId; requestId = (requestId + 1) and 0xFFFF; r }

    private fun send(f: Xk.Frame, logIt: Boolean = true) {
        val o = out ?: error("Not connected.")
        synchronized(o) {
            o.write(f.encode())
            o.flush()
        }
        if (logIt && !f.isAck) addLog(f.toString())
    }

    private fun request(node: String, action: Int, data: ByteArray, timeoutMs: Long, logData: Boolean = true): Xk.Frame? {
        val q = waiting.getOrPut(node) { LinkedBlockingQueue() }
        q.clear()
        val frame = Xk.control(nextOrder(), node, action, nextRequestId(), data)
        send(frame, logIt = false)
        addLog("→ $node" + if (logData && data.isNotEmpty()) " " + hex(data) else "")
        return q.poll(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun await(node: String, timeoutMs: Long): Xk.Frame? =
        waiting.getOrPut(node) { LinkedBlockingQueue() }.poll(timeoutMs, TimeUnit.MILLISECONDS)

    private fun startReader(input: InputStream) {
        reader = Thread({
            val buf = ByteArray(8192)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    parser.feed(buf, n).forEach { handle(it) }
                }
            } catch (e: Exception) {
                // falls through to the lost-link handling below
            }
            if (socket != null) {
                closeSocket()
                state = State.IDLE
                recording = false
                addLog("Link lost")
                if (!userDisconnect) lastError = "Lost the connection to the glasses."
                changed()
                if (!userDisconnect && prefs.autoReconnect) {
                    main.postDelayed({ reconnectSaved() }, 4_000)
                }
            }
        }, "glasses-reader").apply { isDaemon = true; start() }
    }

    // ---- Assistant touch hook (begin) ----
    /** Called on the main thread when the glasses report a touch-pad tap (nodes C101 / C107). */
    @Volatile var onTouch: (() -> Unit)? = null
    // ---- Assistant touch hook (end) ----

    private fun handle(f: Xk.Frame) {
        try {
            when (f.head) {
                Xk.HEAD_MEDIA -> {
                    if (f.isAck) return
                    val done = assembler?.feed(f) ?: false
                    if (f.fragment == 3) send(Xk.mediaAck(nextOrder()), logIt = false)
                    if (done) mediaDone.offer(true)
                }
                Xk.HEAD_CONTROL -> {
                    if (f.isAck || !f.fromDevice) return
                    send(Xk.ack(nextOrder(), f.order), logIt = false)
                    val node = f.node
                    addLog("← $node" + (if (f.data.isNotEmpty() && f.data.size <= 24) " " + hex(f.data) else ""))
                    when (node) {
                        "7320" -> if (!capturing && prefs.saveButtonPhotos) {
                            // A photo taken with the button on the glasses: fetch it too.
                            capturing = true
                            io.execute {
                                setBusy("Saving the photo from the glasses…")
                                try {
                                    lastPhoto = download(elementCount(f))
                                    lastMessage = "Saved the photo you took with the glasses."
                                } catch (e: Exception) {
                                    lastError = "Couldn't fetch that photo: ${e.message}"
                                } finally {
                                    capturing = false
                                    setReady()
                                }
                            }
                            return
                        }
                        // Assistant touch hook: a tap on the glasses starts a voice question.
                        "C101", "C107" -> onTouch?.let { cb -> main.post { cb() } }
                        "5790" -> if (f.data.isNotEmpty()) { recording = f.data[0].toInt() == 1; changed() }
                        "1003" -> {}
                    }
                    waiting.getOrPut(node) { LinkedBlockingQueue() }.offer(f)
                }
                else -> {}
            }
        } catch (e: Exception) {
            addLog("Bad frame: ${e.message}")
        }
    }

    private fun setBusy(text: String) { state = State.BUSY; busyText = text; lastError = null; lastMessage = null; changed() }
    private fun setReady() { busyText = null; state = if (socket != null) State.READY else State.IDLE; changed() }

    fun clearMessages() { lastError = null; lastMessage = null; changed() }

    // ---------------- log ----------------

    @Synchronized
    fun addLog(line: String) {
        log.addLast(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  " + line)
        while (log.size > 300) log.removeFirst()
    }

    @Synchronized
    fun logText(): String = buildString {
        appendLine("P3 Companion glasses log")
        appendLine("Device: ${deviceName ?: prefs.glassesName ?: "-"}  Model: ${model ?: "-"}  Firmware: ${firmware ?: "-"}")
        log.forEach { appendLine(it) }
    }

    companion object {
        private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val TOKEN_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        @Volatile private var instance: XkGlasses? = null

        fun get(context: Context, prefs: Prefs): XkGlasses =
            instance ?: synchronized(this) { instance ?: XkGlasses(context, prefs).also { instance = it } }

        fun looksLikeGlasses(name: String): Boolean {
            val n = name.lowercase(Locale.US)
            return listOf("xk", "glass", "nova", "p3", "lens", "w20", "w202", "e13").any { n.contains(it) }
        }

        fun hex(b: ByteArray): String = b.joinToString(" ") { "%02X".format(it) }
    }
}
