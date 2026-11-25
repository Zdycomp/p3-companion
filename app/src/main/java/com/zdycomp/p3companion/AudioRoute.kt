package com.zdycomp.p3companion

import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Routes the microphone and the assistant's voice through the glasses when they are connected as
 * the phone's Bluetooth headset (the hands-free "SCO" link). Shared by voice chat, translation and
 * the voice recorder, so it counts users and only lets the link go when the last one is done.
 *
 * 3.5: the link counts as up only on a real signal: the SCO audio state turning CONNECTED, the
 * headset's audio connecting, or the phone's call device switching to the glasses. 3.4 also went on
 * after 1.5 s with no signal at all, which recorded digital silence (-83 dBFS). Two ways are tried,
 * each for up to 2 s: setCommunicationDevice (Android 12+), then the older startBluetoothSco. The one
 * that works is remembered ([Prefs.scoMethod]); every state change goes to [log].
 *
 * Call everything on the main thread.
 */
class AudioRoute private constructor(context: Context, private val prefs: Prefs) {

    private val app = context.applicationContext
    private val am: AudioManager = app.getSystemService(AudioManager::class.java)!!
    private val main = Handler(Looper.getMainLooper())

    private var users = 0
    private var connecting = false
    /** True while sound really goes through the glasses. */
    var active = false
        private set
    private var generation = 0
    private val waiting = mutableListOf<(Boolean) -> Unit>()
    private var scoReceiver: BroadcastReceiver? = null
    private var deviceListener: AudioManager.OnCommunicationDeviceChangedListener? = null
    private var acquireStartedAt = 0L
    private var methodsLeft = ArrayList<String>()
    private var method: String? = null
    private var legacyStarted = false

    /** Where the state changes go (the connection log). */
    var log: ((String) -> Unit)? = null

    /** When the call link was last let go (elapsedRealtime), so speech can wait for the headset to switch. */
    var releasedAt = 0L
        private set
    /** How the last [acquire] went, in plain words with its time, for the screen and diagnostics. */
    var lastReport: String? = null
        private set
    /** True when the last link came up on an SCO or headset audio signal, not only a device switch. */
    var lastConfirmed = false
        private set
    /** The way the last link was opened: [COMM] or [LEGACY]. */
    var lastMethod: String? = null
        private set

    /** The glasses' hands-free output, if they're connected as a headset right now. */
    fun glassesOutput(): AudioDeviceInfo? = pick(am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList())

    /** The glasses' microphone, if they're connected as a headset right now. */
    fun glassesInput(): AudioDeviceInfo? = pick(am.getDevices(AudioManager.GET_DEVICES_INPUTS).toList())

    fun headsetConnected(): Boolean = glassesOutput() != null

    fun headsetName(): String? = glassesOutput()?.productName?.toString()?.takeIf { it.isNotBlank() }

    /** Whether the next voice feature will use the glasses. */
    fun willUseGlasses(): Boolean = prefs.useGlassesAudio && headsetConnected()

    private fun pick(devices: List<AudioDeviceInfo>): AudioDeviceInfo? {
        val sco = devices.filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        val saved = prefs.glassesAddress
        return sco.firstOrNull { saved != null && it.address.equals(saved, ignoreCase = true) }
            ?: sco.firstOrNull { XkGlasses.looksLikeGlasses(it.productName?.toString() ?: "") }
            ?: sco.firstOrNull()
    }

    private fun say(line: String) { log?.invoke("SCO: $line") }

    private fun elapsed() = SystemClock.elapsedRealtime() - acquireStartedAt

    /**
     * Asks for the glasses' audio. [onReady] gets true when sound now goes through the glasses,
     * false when the phone's own mic and speaker will be used. Always pair with [release].
     * [onlyMethod] tries just that way ([COMM] or [LEGACY]), for Test voice.
     */
    fun acquire(onReady: (Boolean) -> Unit, onlyMethod: String? = null) {
        users++
        if (users > 1 && !connecting) {
            val now = active
            main.post { onReady(now) }
            return
        }
        waiting += onReady
        if (connecting) return
        if (!willUseGlasses()) { finish(false); return }
        val glasses = XkGlasses.get(app, prefs)
        // 3.3: never during a connect, a photo transfer or the live view test, nor in the first 3 s of a link.
        glasses.audioBlocker()?.let { why ->
            lastReport = "glasses mic not used: $why; using the phone's mic"
            say(lastReport!!)
            finish(false)
            return
        }
        // The glasses can't stream live video while their mic link is up: close the live view first.
        glasses.micWillStart()
        connecting = true
        lastConfirmed = false
        acquireStartedAt = SystemClock.elapsedRealtime()
        methodsLeft = ArrayList(methodOrder(onlyMethod, prefs.scoMethod, Build.VERSION.SDK_INT))
        val gen = ++generation
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        say("audio mode set to in-communication")
        listenForSco(gen)
        nextMethod(gen)
    }

    private fun nextMethod(gen: Int) {
        if (gen != generation || !connecting) return
        val m = methodsLeft.removeFirstOrNull()
        if (m == null) {
            lastReport = "the glasses' call audio didn't open (no SCO signal in ${elapsed()} ms); using the phone's mic"
            say(lastReport!!)
            tearDown()
            finish(false)
            return
        }
        method = m
        if (m == COMM && Build.VERSION.SDK_INT >= 31) {
            val device = pick(am.availableCommunicationDevices)
            if (device == null) { say("the glasses aren't offered as a call device"); nextMethod(gen); return }
            say("asking for ${device.productName} as the call device (setCommunicationDevice)")
            val ok = runCatching { am.setCommunicationDevice(device) }.getOrDefault(false)
            if (!ok) { say("the phone refused setCommunicationDevice"); nextMethod(gen); return }
            if (am.communicationDevice?.id == device.id) deviceSwitched(gen, device.id)
        } else {
            @Suppress("DEPRECATION")
            if (!am.isBluetoothScoAvailableOffCall) { say("SCO isn't available outside calls on this phone"); nextMethod(gen); return }
            if (Build.VERSION.SDK_INT >= 31) runCatching { am.clearCommunicationDevice() }
            say("starting SCO (startBluetoothSco)")
            @Suppress("DEPRECATION")
            runCatching { am.startBluetoothSco() }
            legacyStarted = true
        }
        val forMethod = m
        main.postDelayed({
            if (gen == generation && connecting && method == forMethod) {
                say("no link signal ${SCO_WAIT_MS} ms after ${if (forMethod == COMM) "setCommunicationDevice" else "startBluetoothSco"}")
                nextMethod(gen)
            }
        }, SCO_WAIT_MS)
    }

    /**
     * Ready signals: the SCO audio state broadcast or the headset's audio-connected broadcast end the
     * wait at once; the call device switching to the glasses ends it [DEVICE_GRACE_MS] later if no
     * broadcast comes (the caller then checks the mic isn't silent). Kept registered while the link is
     * up, so drops are logged too.
     */
    private fun listenForSco(gen: Int) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (gen != generation) return
                when (intent.action) {
                    AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                        val st = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                        say("SCO audio state ${scoStateName(st)} (${elapsed()} ms)")
                        if (st == AudioManager.SCO_AUDIO_STATE_CONNECTED && connecting) ready(gen, confirmed = true, why = "SCO audio connected")
                    }
                    BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED -> {
                        val st = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                        say("headset audio ${headsetStateName(st)} (${elapsed()} ms)")
                        if (st == BluetoothHeadset.STATE_AUDIO_CONNECTED && connecting) ready(gen, confirmed = true, why = "headset audio connected")
                    }
                }
            }
        }
        scoReceiver = receiver
        runCatching {
            registerSystemReceiver(app, receiver, IntentFilter().apply {
                addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
            })
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val l = AudioManager.OnCommunicationDeviceChangedListener { d ->
                if (gen != generation) return@OnCommunicationDeviceChangedListener
                say("call device is now " + (d?.let { "${it.productName} (type ${it.type})" } ?: "none") + " (${elapsed()} ms)")
                val glasses = pick(am.availableCommunicationDevices)
                if (d != null && glasses != null && d.id == glasses.id) deviceSwitched(gen, d.id)
            }
            deviceListener = l
            runCatching { am.addOnCommunicationDeviceChangedListener({ main.post(it) }, l) }
        }
    }

    private var deviceSetGen = -1

    private fun deviceSwitched(gen: Int, id: Int) {
        if (deviceSetGen == gen) return
        deviceSetGen = gen
        main.postDelayed({
            if (gen == generation && connecting && am.communicationDevice?.id == id) ready(gen, confirmed = false, why = "call device switched")
        }, DEVICE_GRACE_MS)
    }

    private fun ready(gen: Int, confirmed: Boolean, why: String) {
        if (gen != generation || !connecting) return
        lastConfirmed = confirmed
        lastMethod = method
        lastReport = "glasses mic ready in ${elapsed()} ms ($why, ${if (method == COMM) "setCommunicationDevice" else "startBluetoothSco"})"
        say(lastReport!!)
        finish(true)
    }

    /** The way that gave a mic that isn't silent: tried first from now on. */
    fun rememberWorking() { lastMethod?.let { prefs.scoMethod = it } }

    private fun finish(ok: Boolean) {
        connecting = false
        active = ok
        val list = waiting.toList()
        waiting.clear()
        list.forEach { cb -> main.post { cb(ok) } }
    }

    fun release() {
        if (users == 0) return
        users--
        if (users > 0) return
        generation++
        connecting = false
        waiting.clear()
        val wasActive = active
        tearDown()
        active = false
        if (wasActive) releasedAt = SystemClock.elapsedRealtime()
    }

    private fun tearDown() {
        scoReceiver?.let { runCatching { app.unregisterReceiver(it) } }
        scoReceiver = null
        if (Build.VERSION.SDK_INT >= 31) deviceListener?.let { runCatching { am.removeOnCommunicationDeviceChangedListener(it) } }
        deviceListener = null
        if (Build.VERSION.SDK_INT >= 31) runCatching { am.clearCommunicationDevice() }
        if (legacyStarted || Build.VERSION.SDK_INT < 31) {
            @Suppress("DEPRECATION")
            runCatching { am.stopBluetoothSco() }
            legacyStarted = false
        }
        method = null
        runCatching { if (am.mode == AudioManager.MODE_IN_COMMUNICATION) am.mode = AudioManager.MODE_NORMAL }
    }

    companion object {
        /** Longest wait for a link signal after each way of opening it. */
        const val SCO_WAIT_MS = 2_000L
        /** After the call device switched with no SCO broadcast, wait this long before recording. */
        const val DEVICE_GRACE_MS = 300L
        const val COMM = "comm"
        const val LEGACY = "legacy"

        /** The ways to try, in order: [only] alone; else the one that worked last, then the other (the old way alone before Android 12). */
        fun methodOrder(only: String?, remembered: String?, sdk: Int): List<String> {
            if (sdk < 31) return listOf(LEGACY)
            if (only == COMM || only == LEGACY) return listOf(only)
            return if (remembered == LEGACY) listOf(LEGACY, COMM) else listOf(COMM, LEGACY)
        }

        fun scoStateName(st: Int): String = when (st) {
            AudioManager.SCO_AUDIO_STATE_CONNECTED -> "CONNECTED"
            AudioManager.SCO_AUDIO_STATE_CONNECTING -> "CONNECTING"
            AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> "DISCONNECTED"
            AudioManager.SCO_AUDIO_STATE_ERROR -> "ERROR"
            else -> "$st"
        }

        fun headsetStateName(st: Int): String = when (st) {
            BluetoothHeadset.STATE_AUDIO_CONNECTED -> "connected"
            BluetoothHeadset.STATE_AUDIO_CONNECTING -> "connecting"
            BluetoothHeadset.STATE_AUDIO_DISCONNECTED -> "disconnected"
            else -> "$st"
        }

        @Volatile private var instance: AudioRoute? = null

        fun get(context: Context, prefs: Prefs): AudioRoute =
            instance ?: synchronized(this) { instance ?: AudioRoute(context, prefs).also { instance = it } }

        /** Registers for system broadcasts; Android 13+ wants the exported flag spelled out. */
        fun registerSystemReceiver(context: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        }
    }
}
