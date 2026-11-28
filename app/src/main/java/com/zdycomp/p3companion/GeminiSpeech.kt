package com.zdycomp.p3companion

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.PlaybackParams
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** 4.0: is there a working internet connection right now (so Gemini can be used)? */
object Net {
    fun online(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)
}

/**
 * 4.0: one answer spoken with Gemini's voice. Text is added piece by piece ([add], while the answer is
 * still arriving); a fetch thread asks the speech model for each piece (streamed, so playback starts at
 * the first chunk) while a play thread writes the 16-bit PCM to an [AudioTrack] on the current route:
 * the glasses' call audio when [throughCall], else media audio (the glasses when they're the phone's
 * audio device). The next piece is fetched while this one plays. Callbacks arrive on the main thread.
 *
 * If Gemini fails, [onFail] gets the reason once everything already received has been played, and
 * the text that wasn't spoken, so the phone's own voice can say the rest.
 */
class GeminiTtsSession(
    private val app: Context,
    private val apiKey: String,
    private val model: String,
    private val voice: String,
    private val languageTag: String,
    private val throughCall: Boolean,
    private val speed: Float,
    private val pitch: Float,
    private val leadInMs: Long,
    private val onFirstAudio: () -> Unit,
    private val onDone: () -> Unit,
    private val onFail: (String, List<String>) -> Unit,
    private val log: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val texts = LinkedBlockingQueue<Any>()
    private val audio = LinkedBlockingQueue<Any>(256)
    @Volatile private var cancelled = false
    @Volatile private var failedPiece: String? = null
    @Volatile private var failReason: String? = null
    private var ended = false
    private val startedAt = SystemClock.elapsedRealtime()
    private var track: AudioTrack? = null

    private object End
    private object Fail

    fun start() {
        Thread({ fetchLoop() }, "p3-gemini-tts-fetch").apply { isDaemon = true; start() }
        Thread({ playLoop() }, "p3-gemini-tts-play").apply { isDaemon = true; start() }
    }

    /** More text to speak (split into sentence groups). Main thread. */
    fun add(text: String) {
        if (cancelled || ended) return
        GeminiVoice.pieces(text).forEach { texts.put(it) }
    }

    /** No more text is coming. */
    fun end() {
        if (ended) return
        ended = true
        texts.put(End)
    }

    fun cancel() {
        cancelled = true
        texts.clear()
        texts.put(End)
        audio.clear()
        audio.offer(End)
        runCatching { track?.pause(); track?.flush() }
    }

    private fun fetchLoop() {
        var pieces = 0
        while (!cancelled) {
            val item = runCatching { texts.take() }.getOrNull() ?: break
            if (item === End) { put(End); return }
            val piece = item as String
            val t0 = SystemClock.elapsedRealtime()
            var firstChunk = 0L
            try {
                AiClient.speakGemini(apiKey, model, piece, voice, languageTag, { cancelled }) { a ->
                    if (firstChunk == 0L) firstChunk = SystemClock.elapsedRealtime() - t0
                    put(a)
                }
                pieces++
                if (pieces == 1) log("Gemini voice $voice ($model, $languageTag): first sound ${firstChunk} ms after asking, ${piece.length} characters")
            } catch (e: Exception) {
                if (cancelled) return
                failedPiece = piece
                failReason = e.message ?: e.javaClass.simpleName
                put(Fail)
                return
            }
        }
    }

    private fun put(x: Any) {
        while (!cancelled) {
            if (audio.offer(x, 200, TimeUnit.MILLISECONDS)) return
        }
    }

    @SuppressLint("MissingPermission")
    private fun makeTrack(rate: Int, channels: Int): AudioTrack {
        val mask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(2048)
        val usage = if (throughCall) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min * 2, rate / 5 * 2 * channels))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (throughCall) AudioRoute.get(app, Prefs(app)).glassesOutput()?.let { runCatching { t.preferredDevice = it } }
        if (speed != 1f || pitch != 1f) runCatching { t.playbackParams = PlaybackParams().setSpeed(speed.coerceIn(0.5f, 2f)).setPitch(pitch.coerceIn(0.5f, 2f)) }
        return t
    }

    private fun playLoop() {
        var written = 0L // frames
        var rate = 0
        var channels = 1
        var first = true
        var odd: Byte? = null
        try {
            while (!cancelled) {
                val item = audio.poll(250, TimeUnit.MILLISECONDS) ?: continue
                if (item === End) break
                if (item === Fail) {
                    drain(written, rate)
                    release()
                    val why = failReason ?: "error"
                    main.post {
                        if (cancelled) return@post
                        val rest = ArrayList<String>()
                        failedPiece?.let { rest += it }
                        while (true) {
                            val t = texts.poll() ?: break
                            if (t is String) rest += t
                        }
                        log("Gemini voice failed ($why); the phone's voice says the rest (${rest.size} piece(s))")
                        onFail(why, rest)
                    }
                    return
                }
                val a = item as GeminiVoice.Audio
                if (a.pcm.isEmpty()) continue
                if (track == null || a.rate != rate || a.channels != channels) {
                    if (track != null) { drain(written, rate); release(); written = 0 }
                    rate = a.rate; channels = a.channels
                    val t = makeTrack(rate, channels)
                    track = t
                    t.play()
                    if (leadInMs > 0 && first) {
                        val zeros = ByteArray((rate * leadInMs / 1000).toInt() * 2 * channels)
                        var z = 0
                        while (z < zeros.size && !cancelled) {
                            val n = t.write(zeros, z, zeros.size - z, AudioTrack.WRITE_NON_BLOCKING)
                            if (n < 0) break
                            if (n == 0) Thread.sleep(10) else z += n
                        }
                        written += zeros.size / 2 / channels
                    }
                }
                // Keep whole 16-bit samples across chunks.
                var bytes = a.pcm
                odd?.let { b -> bytes = byteArrayOf(b) + bytes; odd = null }
                if (bytes.size % 2 == 1) { odd = bytes.last(); bytes = bytes.copyOf(bytes.size - 1) }
                val t = track ?: break
                var off = 0
                // Non-blocking writes, so a stop never leaves this thread stuck in a full buffer.
                while (off < bytes.size && !cancelled) {
                    val n = t.write(bytes, off, bytes.size - off, AudioTrack.WRITE_NON_BLOCKING)
                    if (n < 0) break
                    if (n == 0) Thread.sleep(10) else off += n
                }
                written += bytes.size / 2 / channels
                if (first && !cancelled) {
                    first = false
                    val ms = SystemClock.elapsedRealtime() - startedAt
                    main.post { if (!cancelled) { log("Gemini voice: playing, first audio ${ms} ms after the first words${if (throughCall) ", through the glasses' call audio" else ", on media audio"}"); onFirstAudio() } }
                }
            }
            if (!cancelled) drain(written, rate)
        } catch (e: Exception) {
            log("Gemini voice player: ${e.javaClass.simpleName}")
        } finally {
            release()
        }
        if (!cancelled) main.post { if (!cancelled) onDone() }
    }

    /** Waits until everything written has been heard (or a little longer than it should take). */
    private fun drain(written: Long, rate: Int) {
        val t = track ?: return
        if (rate <= 0 || written <= 0) return
        val limit = SystemClock.elapsedRealtime() + (written * 1000f / rate / maxOf(speed, 0.5f)).toLong() + 1_500
        while (!cancelled && SystemClock.elapsedRealtime() < limit) {
            val head = runCatching { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(written)
            if (head >= written) break
            Thread.sleep(20)
        }
    }

    private fun release() {
        val t = track ?: return
        track = null
        runCatching { t.stop() }
        runCatching { t.release() }
    }
}

/**
 * 4.0: records one utterance for Gemini speech-to-text with [AudioRecord]: the phone's mic
 * (VOICE_RECOGNITION) or the glasses' call audio (VOICE_COMMUNICATION on the Bluetooth SCO input).
 * Noise suppression, automatic gain and echo cancelling are switched on when the phone has them. Our
 * own detector ([Vad]) ends it after 900 ms of quiet (or on [finish], for hold to talk), at most 20 s.
 * [onLevel] gets the level in dBFS about 15 times a second; [done] the clip. Main-thread callbacks.
 */
class VoiceClipRecorder(
    private val app: Context,
    private val callAudio: Boolean,
    private val device: AudioDeviceInfo?,
    private val vad: Vad,
    private val onLevel: (Double) -> Unit,
    private val done: (Result) -> Unit,
) {
    class Result(val samples: ShortArray, val state: Vad.State, val report: String, val speechStartMs: Double, val speechEndMs: Double, val peakDb: Double)

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var stopNow = false
    @Volatile private var cancelled = false
    private var thread: Thread? = null

    /** Starts recording; returns why it couldn't, or null. */
    @SuppressLint("MissingPermission")
    fun start(): String? {
        val rate = MicCapture.RATE
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val source = if (callAudio) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val r = try {
            AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate / 5 * 2) * 2)
        } catch (e: Exception) {
            return "the mic couldn't be opened (${e.javaClass.simpleName})"
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return "the mic couldn't be opened" }
        val target = device ?: if (!callAudio) app.getSystemService(AudioManager::class.java)
            ?.getDevices(AudioManager.GET_DEVICES_INPUTS)?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC } else null
        if (target != null) runCatching { r.setPreferredDevice(target) }
        val fx = ArrayList<String>()
        val ns = if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(r.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
        val agc = if (AutomaticGainControl.isAvailable()) runCatching { AutomaticGainControl.create(r.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
        val aec = if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(r.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
        fx += "noise suppression " + if (ns?.enabled == true) "on" else "not on this phone"
        fx += "auto gain " + if (agc?.enabled == true) "on" else "not on this phone"
        fx += "echo cancelling " + if (aec?.enabled == true) "on" else "not on this phone"
        try {
            r.startRecording()
        } catch (e: Exception) {
            ns?.release(); agc?.release(); aec?.release(); r.release()
            return "the mic didn't start (${e.javaClass.simpleName})"
        }
        val t = Thread({
            val chunk = ShortArray(rate / 50) // 20 ms
            var buf = ShortArray(rate * 4)
            var n = 0
            var lastLevel = 0L
            var routed: String? = null
            val t0 = SystemClock.elapsedRealtime()
            try {
                while (!stopNow && !cancelled && !vad.ended) {
                    val got = r.read(chunk, 0, chunk.size)
                    if (got < 0) break
                    if (got == 0) continue
                    if (routed == null) routed = r.routedDevice?.let { "${it.productName} (type ${it.type})" }
                    if (n + got > buf.size) buf = buf.copyOf(buf.size * 2)
                    System.arraycopy(chunk, 0, buf, n, got)
                    n += got
                    vad.feed(chunk, got)
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastLevel >= 66) {
                        lastLevel = now
                        val db = Level.of(chunk, got)
                        main.post { if (!cancelled) onLevel(db) }
                    }
                }
            } finally {
                runCatching { r.stop() }
                ns?.release(); agc?.release(); aec?.release()
                r.release()
            }
            if (cancelled) return@Thread
            val samples = buf.copyOf(n)
            val state = when {
                vad.ended -> vad.state
                vad.heardSpeech -> Vad.State.ENDED_AFTER_SPEECH
                else -> Vad.State.ENDED_NO_SPEECH
            }
            val report = (if (callAudio) "glasses call audio (VOICE_COMMUNICATION)" else "phone mic (VOICE_RECOGNITION)") +
                ", routed to ${routed ?: "unknown"}, " + fx.joinToString(", ") +
                String.format(Locale.US, ", %.1f s, peak %.0f dBFS, floor %.0f dBFS", n / rate.toDouble(), vad.peakDb, vad.floorDb) +
                ", ended: " + when (state) {
                    Vad.State.ENDED_AFTER_SPEECH -> if (stopNow) "released / tapped" else "${Vad.GEMINI_END_MS} ms of quiet"
                    Vad.State.ENDED_NO_SPEECH -> "no speech"
                    Vad.State.ENDED_MAX -> "the ${Vad.GEMINI_MAX_MS / 1000} s limit"
                    else -> "stopped"
                } + " after ${SystemClock.elapsedRealtime() - t0} ms"
            val res = Result(samples, state, report, vad.speechStartMs, vad.speechEndMs, vad.peakDb)
            main.post { if (!cancelled) done(res) }
        }, "p3-voice-clip")
        t.isDaemon = true
        thread = t
        t.start()
        return null
    }

    /** Ends it now and uses what was recorded (a tap, or letting go of hold to talk). */
    fun finish() { stopNow = true }

    /** Ends it and throws the audio away; [done] isn't called. */
    fun cancel() { cancelled = true }
}
