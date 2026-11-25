package com.zdycomp.p3companion

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** 3.5: the app's own audio for one recognizer attempt (EXTRA_AUDIO_SOURCE, 16 kHz mono PCM16). */
interface AudioFeed {
    /** Goes into the recognizer intent. */
    val readFd: ParcelFileDescriptor
    /** True once our own end-of-speech detector heard speech in what went to the recognizer. */
    val heardSpeech: Boolean
    fun start(): Boolean
    /** Ends the stream and frees it. Safe to call twice. */
    fun stop()
    /** "1.8 s written (0.1 s filled with silence), speech heard, ended after 1 s of quiet". */
    fun report(): String
}

/**
 * Feeds PCM to the recognizer through a pipe, continuously: a 20 ms piece of silence goes in whenever
 * nothing arrived for 60 ms, so the recognizer never waits on a stalled Bluetooth link. Our own
 * end-of-speech detector ([Vad]) closes the pipe 1 s after the speech ends (the recognizer then sees
 * the end of the sound) and calls [onEnd] on the main thread. [onStop] runs once when it's stopped.
 */
class PcmPipe(private val onEnd: (Vad.State) -> Unit, private val onStop: (PcmPipe) -> Unit = {}) : AudioFeed {
    private val main = Handler(Looper.getMainLooper())
    private val pipe: Array<ParcelFileDescriptor> = ParcelFileDescriptor.createPipe()
    override val readFd: ParcelFileDescriptor get() = pipe[0]
    private val queue = LinkedBlockingQueue<ShortArray>(250)
    private val vad = Vad(MicCapture.RATE)
    @Volatile private var stopped = false
    @Volatile var samplesWritten = 0L; private set
    @Volatile var silenceFills = 0L; private set
    @Volatile private var endedAs: Vad.State? = null

    override val heardSpeech: Boolean get() = vad.heardSpeech

    /** PCM from the decoder (any thread): 16 kHz mono. A full queue (5 s) drops the oldest piece. */
    fun offer(pcm: ShortArray) {
        if (stopped || pcm.isEmpty()) return
        if (!queue.offer(pcm)) { queue.poll(); queue.offer(pcm) }
    }

    override fun start(): Boolean {
        Thread({ pump() }, "p3-pcm-pipe").apply { isDaemon = true; start() }
        return true
    }

    private fun pump() {
        val out = FileOutputStream(pipe[1].fileDescriptor)
        val silence = ShortArray(MicCapture.RATE / 50)
        try {
            while (!stopped) {
                val chunk = queue.poll(60, TimeUnit.MILLISECONDS) ?: silence.also { silenceFills++ }
                out.write(toBytes(chunk))
                samplesWritten += chunk.size
                val st = vad.feed(chunk)
                if (vad.ended) {
                    endedAs = st
                    main.post { onEnd(st) }
                    break
                }
            }
        } catch (_: Exception) {
            // The recognizer closed its end: nothing more to write.
        } finally {
            runCatching { pipe[1].close() }
        }
    }

    override fun stop() {
        if (stopped) return
        stopped = true
        runCatching { pipe[0].close() }
        queue.clear()
        onStop(this)
    }

    override fun report(): String {
        val secs = samplesWritten.toDouble() / MicCapture.RATE
        val fill = silenceFills * 0.02
        return "%.1f s written (%.1f s filled with silence), peak %.0f dBFS, %s%s".format(
            java.util.Locale.US, secs, fill, vad.peakDb, if (vad.heardSpeech) "speech heard" else "no speech heard",
            when (endedAs) {
                Vad.State.ENDED_AFTER_SPEECH -> ", ended 1 s after the speech"
                Vad.State.ENDED_NO_SPEECH -> ", ended: no speech in 6 s"
                Vad.State.ENDED_MAX -> ", ended at the 12 s limit"
                else -> ""
            },
        )
    }

    private fun toBytes(c: ShortArray): ByteArray {
        val b = ByteArray(c.size * 2)
        for (i in c.indices) { b[2 * i] = (c[i].toInt() and 0xFF).toByte(); b[2 * i + 1] = ((c[i].toInt() shr 8) and 0xFF).toByte() }
        return b
    }
}

/**
 * Decodes the glasses' Opus packets with Android's own decoder (MediaCodec "audio/opus", with the
 * OpusHead, codec delay and pre-roll it needs) and hands on 16 kHz mono PCM16 ([Resampler]) on its
 * own thread.
 */
class OpusPcm(private val onPcm: (ShortArray) -> Unit, private val log: (String) -> Unit) {
    private val thread = HandlerThread("p3-opus").apply { start() }
    private val h = Handler(thread.looper)
    private var codec: MediaCodec? = null
    private var resampler: Resampler? = null
    private val info = MediaCodec.BufferInfo()
    private var pts = 0L
    @Volatile var packetsIn = 0L; private set
    @Volatile var samplesOut = 0L; private set
    @Volatile var errors = 0L; private set
    @Volatile var outFormat: String = "?"; private set

    fun start(): Boolean = runCatching {
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48_000, 1).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(OpusCsd.head(channels = 1, inputRate = MicCapture.RATE)))
            setByteBuffer("csd-1", ByteBuffer.wrap(OpusCsd.nanos(0)))
            setByteBuffer("csd-2", ByteBuffer.wrap(OpusCsd.nanos(OpusCsd.SEEK_PREROLL_NS)))
        }
        c.configure(f, null, null, 0)
        c.start()
        codec = c
        useFormat(c.outputFormat)
        true
    }.getOrElse { e ->
        log("Glasses mic stream: this phone's Opus decoder didn't start (${e.message ?: e.javaClass.simpleName})")
        release()
        false
    }

    /** One 40-byte packet (any thread; never blocks). */
    fun offer(packet: ByteArray) { h.post { decode(packet) } }

    private fun decode(p: ByteArray) {
        val c = codec ?: return
        try {
            packetsIn++
            val i = c.dequeueInputBuffer(10_000)
            if (i < 0) { errors++; return }
            val b = c.getInputBuffer(i) ?: return
            b.clear()
            b.put(p)
            c.queueInputBuffer(i, 0, p.size, pts, 0)
            pts += OpusCsd.PACKET_US
            drain(c)
        } catch (e: Exception) {
            if (errors++ < 3) log("Glasses mic stream: decode error (${e.message ?: e.javaClass.simpleName})")
        }
    }

    private fun drain(c: MediaCodec) {
        while (true) {
            val o = c.dequeueOutputBuffer(info, 2_000)
            when {
                o >= 0 -> {
                    val buf = c.getOutputBuffer(o)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val sb = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val raw = ShortArray(sb.remaining())
                        sb.get(raw)
                        val pcm = resampler?.process(raw) ?: raw
                        samplesOut += pcm.size
                        if (pcm.isNotEmpty()) onPcm(pcm)
                    }
                    c.releaseOutputBuffer(o, false)
                }
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> useFormat(c.outputFormat)
                else -> return
            }
        }
    }

    private fun useFormat(f: MediaFormat) {
        val rate = runCatching { f.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrDefault(48_000)
        val ch = runCatching { f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(1).coerceAtLeast(1)
        outFormat = "$rate Hz, $ch channel(s)"
        resampler = Resampler(rate, ch, MicCapture.RATE)
    }

    fun stop() {
        h.post { release() }
        thread.quitSafely()
    }

    private fun release() {
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null
    }
}

/**
 * 3.5: the glasses' own mic over the Bluetooth data link (node 5750, Opus), no call audio needed.
 * [start] turns the stream on and reports (main thread) once decoded sound arrives, or why not within
 * [firstAudioMs]. Listeners get 16 kHz mono PCM on the decoder thread. One use per instance.
 */
class GlassesMicStream(private val glasses: XkGlasses, private val firstAudioMs: Long = 4_500) {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(ShortArray) -> Unit>()
    private val decoder = OpusPcm({ pcm -> onPcm(pcm) }, { glasses.addLog(it) })
    @Volatile private var gotAudio = false
    @Volatile private var stopped = false
    private var reported = false
    private var startedAt = 0L

    fun addListener(l: (ShortArray) -> Unit) { listeners += l }
    fun removeListener(l: (ShortArray) -> Unit) { listeners -= l }

    /** A pipe for the recognizer fed from this stream, detached when it's stopped. */
    fun newPipe(onEnd: (Vad.State) -> Unit): PcmPipe {
        lateinit var pipe: PcmPipe
        val l: (ShortArray) -> Unit = { pipe.offer(it) }
        pipe = PcmPipe(onEnd) { removeListener(l) }
        addListener(l)
        return pipe
    }

    fun start(done: (String?) -> Unit) {
        startedAt = android.os.SystemClock.elapsedRealtime()
        checkFirst = {
            if (!reported) {
                reported = true
                glasses.addLog("Glasses mic stream: sound after ${android.os.SystemClock.elapsedRealtime() - startedAt} ms (${decoder.outFormat} decoded)")
                done(null)
            }
        }
        if (!decoder.start()) { finish(done, "this phone has no Opus decoder"); return }
        glasses.startMicStream({ packet -> if (!stopped) decoder.offer(packet) }) { err ->
            if (err != null) { finish(done, err); return@startMicStream }
            main.postDelayed({
                if (!reported) finish(done, "no mic sound arrived within ${firstAudioMs / 1000.0} s (" + glasses.micStreamReport() + ")")
            }, firstAudioMs)
        }
    }

    private var checkFirst: (() -> Unit)? = null

    private fun onPcm(pcm: ShortArray) {
        if (stopped) return
        if (!gotAudio) { gotAudio = true; main.post { checkFirst?.invoke() } }
        listeners.forEach { runCatching { it(pcm) } }
    }

    private fun finish(done: (String?) -> Unit, err: String) {
        if (reported) return
        reported = true
        stop()
        done(err)
    }

    fun report(): String = glasses.micStreamReport() + "; decoded %.1f s".format(java.util.Locale.US, decoder.samplesOut / MicCapture.RATE.toDouble()) +
        (if (decoder.errors > 0) ", ${decoder.errors} decode error(s)" else "")

    fun stop() {
        if (stopped) return
        stopped = true
        listeners.clear()
        glasses.stopMicStream()
        decoder.stop()
    }
}
