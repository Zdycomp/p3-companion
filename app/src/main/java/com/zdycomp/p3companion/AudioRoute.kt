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
        connecting = true
        savedMode = am.mode
        val gen = ++generation
        if (Build.VERSION.SDK_INT >= 31) {
            val device = pick(am.availableCommunicationDevices)
            if (device == null) { finish(false); return }
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            val ok = runCatching { am.setCommunicationDevice(device) }.getOrDefault(false)
            if (!ok) {
                am.mode = savedMode
                finish(false)
            } else {
                // Give the hands-free link a moment to open before anyone talks into it.
                main.postDelayed({ if (gen == generation && connecting) finish(true) }, 700)
            }
        } else {
            startScoLegacy(gen)
        }
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
        tearDown()
        active = false
    }

    @Suppress("DEPRECATION")
    private fun tearDown() {
        scoReceiver?.let { runCatching { app.unregisterReceiver(it) } }
        scoReceiver = null
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching { am.clearCommunicationDevice() }
        } else {
            runCatching { am.stopBluetoothSco() }
            runCatching { am.isBluetoothScoOn = false }
        }
        runCatching { if (am.mode == AudioManager.MODE_IN_COMMUNICATION) am.mode = AudioManager.MODE_NORMAL }
    }

    companion object {
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
