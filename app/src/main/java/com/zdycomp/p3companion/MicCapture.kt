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
import android.os.ParcelFileDescriptor
import java.io.FileOutputStream
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 3.3: the app records the microphone itself and hands the sound to the speech recognizer through a
 * pipe (RecognizerIntent.EXTRA_AUDIO_SOURCE, Android 13+). The glasses' mic (the Bluetooth SCO input)
 * is tried first; its first 400 ms are measured, and if it's silent the phone's mic takes over in the
 * same stream, so the recognizer never waits on a dead headset mic. 16 kHz, mono, 16-bit PCM.
 *
 * [onChoice] runs on the main thread once the mic is chosen, with the level of the glasses' mic in
 * dBFS when it was measured.
 */
class MicCapture(
    context: Context,
    private val glassesInput: AudioDeviceInfo?,
    private val onChoice: (Mic, Double?) -> Unit,
) {
    enum class Mic { GLASSES, PHONE }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val pipe: Array<ParcelFileDescriptor> = ParcelFileDescriptor.createPipe()
    /** Goes into the recognizer intent. */
    val readFd: ParcelFileDescriptor get() = pipe[0]
    @Volatile private var stopped = false
    private var thread: Thread? = null
    /**
     * Sound on its way to the recognizer. The mic thread never writes to the pipe itself, so a
     * recognizer that stops reading can't keep the mic open; a full queue (2 s) drops sound instead.
     */
    private val queue = LinkedBlockingQueue<ByteArray>(100)
    private val endMark = ByteArray(0)

    /** Starts recording; false when no recorder could be opened (the recognizer's own mic is used then). */
    fun start(): Boolean {
        val first = open(glassesInput ?: phoneMic()) ?: open(null) ?: run { closeAll(); return false }
        thread = Thread({ run(first) }, "p3-mic").apply { isDaemon = true; start() }
        Thread({ pump() }, "p3-mic-pipe").apply { isDaemon = true; start() }
        return true
    }

    /** Ends the stream (the recognizer sees the end of the sound) and frees the mic. Safe to call twice. */
    fun stop() {
        stopped = true
        // Our copy of the read end goes now, so a recognizer that already let go can't leave the pipe thread waiting.
        runCatching { pipe[0].close() }
        if (thread == null) closeAll()
    }

    /** Copies queued sound into the pipe until the end mark or the recognizer closes its side. */
    private fun pump() {
        val out = FileOutputStream(pipe[1].fileDescriptor)
        try {
            while (true) {
                val b = queue.take()
                if (b === endMark) break
                out.write(b)
            }
        } catch (_: Exception) {
        } finally {
            closeAll()
        }
    }

    private fun send(b: ByteArray) { if (!queue.offer(b)) { queue.poll(); queue.offer(b) } }

    private fun run(firstRec: AudioRecord) {
        var rec = firstRec
        val chunk = ShortArray(RATE / 50) // 20 ms
        try {
            rec.startRecording()
            if (glassesInput != null && rec.preferredDevice?.id == glassesInput.id) {
                // Measure the glasses' mic for 400 ms; keep what it heard unless it's silent.
                val held = ArrayList<ByteArray>()
                var sum = 0.0
                var n = 0
                while (!stopped && n < RATE * PROBE_MS / 1000) {
                    val got = rec.read(chunk, 0, chunk.size)
                    if (got <= 0) break
                    for (i in 0 until got) sum += chunk[i].toDouble() * chunk[i]
                    n += got
                    held += toBytes(chunk, got)
                }
                val db = dbfs(sum, n)
                val mic = decide(scoUp = true, glassesRmsDb = db)
                main.post { onChoice(mic, db) }
                if (mic == Mic.GLASSES) {
                    held.forEach { send(it) }
                } else {
                    runCatching { rec.stop() }; rec.release()
                    rec = open(phoneMic()) ?: open(null) ?: return
                    rec.startRecording()
                }
            } else {
                main.post { onChoice(Mic.PHONE, null) }
            }
            while (!stopped) {
                val got = rec.read(chunk, 0, chunk.size)
                if (got < 0) break
                if (got == 0) continue
                send(toBytes(chunk, got))
            }
        } catch (_: Exception) {
        } finally {
            runCatching { rec.stop() }
            runCatching { rec.release() }
            queue.clear()
            queue.offer(endMark)
        }
    }

    @SuppressLint("MissingPermission")
    private fun open(device: AudioDeviceInfo?): AudioRecord? = runCatching {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val source = if (device?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) MediaRecorder.AudioSource.VOICE_COMMUNICATION
        else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val r = AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE / 5 * 2))
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return@runCatching null }
        if (device != null) r.setPreferredDevice(device)
        r
    }.getOrNull()

    private fun phoneMic(): AudioDeviceInfo? =
        app.getSystemService(AudioManager::class.java)?.getDevices(AudioManager.GET_DEVICES_INPUTS)
            ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

    private fun closeAll() {
        runCatching { pipe[1].close() }
        runCatching { pipe[0].close() }
    }

    companion object {
        const val RATE = 16_000
        /** How long the glasses' mic is measured before it's trusted. */
        const val PROBE_MS = 400
        /** Below this the glasses' mic counts as silent (a dead SCO input reads as digital zero or hiss). */
        const val SILENCE_DB = -60.0

        /** Our own capture needs the recognizer's audio-source extra (Android 13+). */
        fun supported(): Boolean = Build.VERSION.SDK_INT >= 33

        /** Which mic to use: the glasses' only when their call audio is up and they aren't silent. */
        fun decide(scoUp: Boolean, glassesRmsDb: Double?): Mic = when {
            !scoUp -> Mic.PHONE
            glassesRmsDb == null -> Mic.PHONE
            glassesRmsDb < SILENCE_DB -> Mic.PHONE
            else -> Mic.GLASSES
        }

        /** RMS level in dBFS from a sum of squared 16-bit samples; -120 for nothing. */
        fun dbfs(sumSquares: Double, count: Int): Double {
            if (count <= 0) return -120.0
            val rms = sqrt(sumSquares / count)
            return if (rms <= 0.0) -120.0 else maxOf(-120.0, 20 * log10(rms / 32768.0))
        }

        fun rmsDb(samples: ShortArray, count: Int = samples.size): Double {
            var s = 0.0
            for (i in 0 until count) s += samples[i].toDouble() * samples[i]
            return dbfs(s, count)
        }

        private fun toBytes(c: ShortArray, n: Int): ByteArray {
            val b = ByteArray(n * 2)
            for (i in 0 until n) { b[2 * i] = (c[i].toInt() and 0xFF).toByte(); b[2 * i + 1] = ((c[i].toInt() shr 8) and 0xFF).toByte() }
            return b
        }
    }
}
