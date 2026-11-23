package com.zdycomp.p3companion

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
    private var savedMode = AudioManager.MODE_NORMAL
    private var deviceListener: AudioManager.OnCommunicationDeviceChangedListener? = null
    private var acquireStartedAt = 0L

    /** When the call link was last let go (elapsedRealtime), so speech can wait for the headset to switch. */
    var releasedAt = 0L
        private set
    /** How the last [acquire] went, in plain words with its time, for the screen and diagnostics. */
    var lastReport: String? = null
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

    /**
     * Asks for the glasses' audio. [onReady] gets true when sound now goes through the glasses,
     * false when the phone's own mic and speaker will be used. Always pair with [release].
     */
    fun acquire(onReady: (Boolean) -> Unit) {
        users++
        if (users > 1 && !connecting) {
            val now = active
            main.post { onReady(now) }
            return
        }
        waiting += onReady
        if (connecting) return
        if (!willUseGlasses()) { finish(false); return }
        // The glasses can't stream live video while their mic link is up: close the live view first.
        XkGlasses.get(app, prefs).micWillStart()
        connecting = true
        savedMode = am.mode
        val gen = ++generation
        if (Build.VERSION.SDK_INT >= 31) {
            val device = pick(am.availableCommunicationDevices)
            if (device == null) { finish(false); return }
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            acquireStartedAt = SystemClock.elapsedRealtime()
            listenForSco(gen, device)
            val ok = runCatching { am.setCommunicationDevice(device) }.getOrDefault(false)
            if (!ok) {
                tearDown()
                am.mode = savedMode
                lastReport = "the phone refused to switch the mic to the glasses; using the phone's mic"
                finish(false)
            } else {
                if (am.communicationDevice?.id == device.id) deviceSetAt(gen)
                // Never wait longer than 1.5 s: then the phone's own mic is used.
                main.postDelayed({
                    if (gen == generation && connecting) {
                        if (am.communicationDevice?.id == device.id) {
                            lastReport = "glasses mic assumed ready after 1500 ms (no link signal)"
                            finish(true)
                        } else {
                            tearDown()
                            lastReport = "the glasses' mic link didn't open within 1.5 s; using the phone's mic"
                            finish(false)
                        }
                    }
                }, SCO_WAIT_MS)
            }
        } else {
            startScoLegacy(gen)
        }
    }

    /**
     * Ready signals for the hands-free link on Android 12+: the SCO audio state broadcast or the
     * headset's audio-connected broadcast ends the wait at once; the communication device changing
     * to the glasses ends it 700 ms later (the 3.0 delay) if no broadcast comes.
     */
    private fun listenForSco(gen: Int, device: AudioDeviceInfo) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (gen != generation || !connecting) return
                val up = when (intent.action) {
                    AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED ->
                        intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) == AudioManager.SCO_AUDIO_STATE_CONNECTED
                    android.bluetooth.BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED ->
                        intent.getIntExtra(android.bluetooth.BluetoothProfile.EXTRA_STATE, -1) == android.bluetooth.BluetoothHeadset.STATE_AUDIO_CONNECTED
                    else -> false
                }
                if (up) {
                    lastReport = "glasses mic ready in ${SystemClock.elapsedRealtime() - acquireStartedAt} ms"
                    finish(true)
                }
            }
        }
        scoReceiver = receiver
        runCatching {
            registerSystemReceiver(app, receiver, IntentFilter().apply {
                addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                addAction(android.bluetooth.BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
            })
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val l = AudioManager.OnCommunicationDeviceChangedListener { d ->
                if (d?.id == device.id) deviceSetAt(gen)
            }
            deviceListener = l
            runCatching { am.addOnCommunicationDeviceChangedListener({ main.post(it) }, l) }
        }
    }

    private var deviceSetGen = -1

    private fun deviceSetAt(gen: Int) {
        if (deviceSetGen == gen) return
        deviceSetGen = gen
        main.postDelayed({
            if (gen == generation && connecting) {
                lastReport = "glasses mic ready in ${SystemClock.elapsedRealtime() - acquireStartedAt} ms (device switched)"
                finish(true)
            }
        }, 700)
    }

    @Suppress("DEPRECATION")
    private fun startScoLegacy(gen: Int) {
        if (!am.isBluetoothScoAvailableOffCall) { finish(false); return }
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
                if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED && connecting && gen == generation) {
                    am.isBluetoothScoOn = true
                    finish(true)
                }
            }
        }
        scoReceiver = receiver
        registerSystemReceiver(app, receiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
        runCatching { am.startBluetoothSco() }
        main.postDelayed({
            if (connecting && gen == generation) {
                tearDown()
                finish(false)
            }
        }, 4_000)
    }

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

    @Suppress("DEPRECATION")
    private fun tearDown() {
        scoReceiver?.let { runCatching { app.unregisterReceiver(it) } }
        scoReceiver = null
        if (Build.VERSION.SDK_INT >= 31) deviceListener?.let { runCatching { am.removeOnCommunicationDeviceChangedListener(it) } }
        deviceListener = null
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching { am.clearCommunicationDevice() }
        } else {
            runCatching { am.stopBluetoothSco() }
            runCatching { am.isBluetoothScoOn = false }
        }
        runCatching { if (am.mode == AudioManager.MODE_IN_COMMUNICATION) am.mode = AudioManager.MODE_NORMAL }
    }

    companion object {
        /** Longest wait for the glasses' mic link before using the phone's mic. */
        const val SCO_WAIT_MS = 1_500L

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
