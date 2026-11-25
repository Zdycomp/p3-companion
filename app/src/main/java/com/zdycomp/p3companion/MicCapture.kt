package com.zdycomp.p3companion

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Short mic recordings to measure a mic's level: the glasses' call-audio check before a voice
 * question, and Test voice's 3-second recordings. 16 kHz, mono, 16-bit PCM.
 *
 * 3.5: the app no longer records the phone's or the glasses' call-audio mic for the recognizer: 3.4
 * piped that recording in (EXTRA_AUDIO_SOURCE) and Google's on-phone recognizer answered "no match"
 * even for the phone's mic. The recognizer now opens the mic itself; only the glasses' data-link
 * stream ([GlassesMicStream]) is piped.
 */
object MicCapture {
    enum class Mic { GLASSES, PHONE }

    const val RATE = 16_000
    /** How long the glasses' call-audio mic is measured before a question is asked through it. */
    const val PROBE_MS = 400
    /** Below this a mic counts as silent (a dead SCO input reads as digital zero or hiss). */
    const val SILENCE_DB = Level.SILENT_DB

    /** Which mic to use: the glasses' only when their call audio is up and they aren't silent. */
    fun decide(scoUp: Boolean, glassesRmsDb: Double?): Mic = when {
        !scoUp -> Mic.PHONE
        glassesRmsDb == null -> Mic.PHONE
        glassesRmsDb < SILENCE_DB -> Mic.PHONE
        else -> Mic.GLASSES
    }

    /** RMS level in dBFS from a sum of squared 16-bit samples; -120 for nothing. */
    fun dbfs(sumSquares: Double, count: Int): Double = Level.dbfs(sumSquares, count)

    fun rmsDb(samples: ShortArray, count: Int = samples.size): Double = Level.of(samples, count)

    /** What a recording got: the samples, their level, and the input Android really used. */
    class Take(val samples: ShortArray, val db: Double, val routedTo: String?, val error: String? = null)

    /**
     * Records [ms] from [device] (null: the phone's built-in mic) on a background thread; [done] runs on
     * the main thread. [callAudio] uses the voice-call source, which is what reaches a Bluetooth headset mic.
     */
    @SuppressLint("MissingPermission")
    fun record(context: Context, device: AudioDeviceInfo?, ms: Int, callAudio: Boolean, done: (Take) -> Unit) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread({
            val take = runCatching {
                val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val source = if (callAudio) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION
                val r = AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE / 5 * 2))
                if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return@runCatching Take(ShortArray(0), -120.0, null, "the mic couldn't be opened") }
                val target = device ?: if (!callAudio) builtInMic(app) else null
                if (target != null) r.setPreferredDevice(target)
                val out = ShortArray(RATE * ms / 1000)
                var n = 0
                var routed: String? = null
                try {
                    r.startRecording()
                    while (n < out.size) {
                        val got = r.read(out, n, minOf(RATE / 50, out.size - n))
                        if (got < 0) break
                        n += got
                        if (routed == null) routed = r.routedDevice?.let { "${it.productName} (type ${it.type})" }
                    }
                } finally {
                    runCatching { r.stop() }
                    r.release()
                }
                Take(out.copyOf(n), Level.of(out, n), routed)
            }.getOrElse { Take(ShortArray(0), -120.0, null, it.message ?: it.javaClass.simpleName) }
            main.post { done(take) }
        }, "p3-mic-level").apply { isDaemon = true; start() }
    }

    private fun builtInMic(app: Context): AudioDeviceInfo? =
        app.getSystemService(AudioManager::class.java)?.getDevices(AudioManager.GET_DEVICES_INPUTS)
            ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

    /** The app's own audio for the recognizer needs its audio-source extra (Android 13+). */
    fun supported(): Boolean = Build.VERSION.SDK_INT >= 33
}
