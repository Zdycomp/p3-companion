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
 * Feeds 16 kHz mono PCM16 (little-endian) to the recognizer through a pipe, in real time (3.7,
 * [PipePacer]): nothing goes in until 200 ms have arrived, then at most real time plus 100 ms, every
 * 20 ms, so the glasses' 200 ms bursts come out as a steady stream; silence only when the input stalled
 * for over 300 ms, or after [endInput] until the end of speech. Our own end-of-speech detector ([Vad])
 * closes the write end 1 s after the speech ends (the recognizer then sees the end of the sound) and
 * calls [onEnd] on the main thread. The write end stays open until then. [onStop] runs once when stopped.
 */
class PcmPipe(private val onEnd: (Vad.State) -> Unit, private val onStop: (PcmPipe) -> Unit = {}) : AudioFeed {
    private val main = Handler(Looper.getMainLooper())
    private val pipe: Array<ParcelFileDescriptor> = ParcelFileDescriptor.createPipe()
    override val readFd: ParcelFileDescriptor get() = pipe[0]
    private val lock = Object()
    /** Waiting audio, at most [MAX_BUFFER] samples (20 s); a full buffer drops the oldest. */
    private val buf = ShortArray(MAX_BUFFER)
    private var head = 0
    private var count = 0
    @Volatile private var inputEnded = false
    private val vad = Vad(MicCapture.RATE)
    private val pacer = PipePacer(MicCapture.RATE)
    @Volatile private var stopped = false
    @Volatile var samplesWritten = 0L; private set
    @Volatile var silenceWritten = 0L; private set
    @Volatile private var endedAs: Vad.State? = null

    override val heardSpeech: Boolean get() = vad.heardSpeech

    /** PCM (any thread): 16 kHz mono. */
    fun offer(pcm: ShortArray) {
        if (stopped || inputEnded || pcm.isEmpty()) return
        synchronized(lock) {
            for (v in pcm) {
                if (count == MAX_BUFFER) { head = (head + 1) % MAX_BUFFER; count-- }
                buf[(head + count) % MAX_BUFFER] = v
                count++
            }
        }
    }

    /** No more audio is coming (a recorded clip): silence follows at real-time pace until the end of speech. */
    fun endInput() { inputEnded = true }

    override fun start(): Boolean {
        Thread({ pump() }, "p3-pcm-pipe").apply { isDaemon = true; start() }
        return true
    }

    private fun take(n: Int): ShortArray = synchronized(lock) {
        val k = minOf(n, count)
        ShortArray(k) { i -> buf[(head + i) % MAX_BUFFER] }.also { head = (head + k) % MAX_BUFFER; count -= k }
    }

    private fun pump() {
        val out = FileOutputStream(pipe[1].fileDescriptor)
        var afterEndMs = 0L
        try {
            while (!stopped) {
                val now = android.os.SystemClock.elapsedRealtime()
                val avail = synchronized(lock) { count }
                val plan = pacer.plan(now, samplesWritten, avail, inputEnded)
                val chunk = take(plan.real)
                if (chunk.isNotEmpty()) write(out, chunk)
                if (plan.silence > 0) {
                    write(out, ShortArray(plan.silence))
                    silenceWritten += plan.silence
                    if (inputEnded) afterEndMs += plan.silence * 1000L / MicCapture.RATE
                }
                if (vad.ended) {
                    endedAs = vad.state
                    val st = vad.state
                    main.post { onEnd(st) }
                    break
                }
                // A recorded clip with no clear end: stop 1.5 s after it, at real-time pace.
                if (inputEnded && avail == 0 && afterEndMs >= 1_500) {
                    endedAs = Vad.State.ENDED_AFTER_SPEECH
                    main.post { onEnd(Vad.State.ENDED_AFTER_SPEECH) }
                    break
                }
                Thread.sleep(20)
            }
        } catch (_: Exception) {
            // The recognizer closed its end: nothing more to write.
        } finally {
            runCatching { pipe[1].close() }
        }
    }

    private fun write(out: FileOutputStream, c: ShortArray) {
        out.write(toBytes(c))
        samplesWritten += c.size
        var p = 0
        while (p < c.size) { val n = minOf(320, c.size - p); vad.feed(c.copyOfRange(p, p + n)); p += n }
    }

    override fun stop() {
        if (stopped) return
        stopped = true
        runCatching { pipe[0].close() }
        synchronized(lock) { count = 0 }
        onStop(this)
    }

    override fun report(): String {
        val secs = samplesWritten.toDouble() / MicCapture.RATE
        val fill = silenceWritten.toDouble() / MicCapture.RATE
        return "%.1f s written in real time (%.1f s of it silence), peak %.0f dBFS, %s%s".format(
            java.util.Locale.US, secs, fill, vad.peakDb, if (vad.heardSpeech) "speech heard" else "no speech heard",
            when (endedAs) {
                Vad.State.ENDED_AFTER_SPEECH -> ", ended after the speech"
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

    companion object {
        const val MAX_BUFFER = MicCapture.RATE * 20

        /** A pipe that plays [clip] (16 kHz mono) into the recognizer in real time, for the pipe self-test and recorded clips. */
        fun ofClip(clip: ShortArray, onEnd: (Vad.State) -> Unit): PcmPipe = PcmPipe(onEnd).also { it.offer(clip); it.endInput() }
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
            // 3.7: pre-skip 312 (6.5 ms at 48 kHz) and the same codec delay; 3.6 said 0 for both.
            val (h, delay, preroll) = OpusCsd.csd(channels = 1, inputRate = MicCapture.RATE)
            setByteBuffer("csd-0", ByteBuffer.wrap(h))
            setByteBuffer("csd-1", ByteBuffer.wrap(delay))
            setByteBuffer("csd-2", ByteBuffer.wrap(preroll))
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

    /** 3.7: the last 8 s the glasses' mic sent, for "Play back what the glasses heard". */
    val ring = ClipRing(MicCapture.RATE * 8)

    fun addListener(l: (ShortArray) -> Unit) { listeners += l }

    /** 4.1: the glasses' own 40-byte Opus packets as they arrive (reader thread; must not block), for Ogg upload. */
    private val packetListeners = CopyOnWriteArrayList<(ByteArray) -> Unit>()
    fun addPacketListener(l: (ByteArray) -> Unit) { packetListeners += l }
    fun removePacketListener(l: (ByteArray) -> Unit) { packetListeners -= l }
    @Volatile var adopted = false; private set

    private fun onPacket(packet: ByteArray) {
        if (stopped) return
        decoder.offer(packet)
        packetListeners.forEach { runCatching { it(packet) } }
    }

    /**
     * 4.1: listens to a mic stream the glasses started by themselves (no 5750 sent; [XkGlasses.adoptMicStream]).
     * [done] like [start].
     */
    fun adopt(done: (String?) -> Unit) {
        startedAt = android.os.SystemClock.elapsedRealtime()
        checkFirst = {
            if (!reported) {
                reported = true
                glasses.addLog("Glasses mic stream (adopted): sound after ${android.os.SystemClock.elapsedRealtime() - startedAt} ms (${decoder.outFormat} decoded)")
                done(null)
            }
        }
        if (!decoder.start()) { finish(done, "this phone has no Opus decoder"); return }
        if (!glasses.adoptMicStream { packet -> onPacket(packet) }) { finish(done, "the glasses' own mic stream had already stopped"); return }
        adopted = true
        main.postDelayed({ if (!reported) finish(done, "no mic sound arrived within ${firstAudioMs / 1000.0} s (" + glasses.micStreamReport() + ")") }, firstAudioMs)
    }

    /**
     * 3.7: records one utterance from the stream with our own end-of-speech detector ([Vad.clip]: 800 ms
     * of quiet after speech, at most 15 s). [done] (main thread) gets the audio and how it ended; a
     * stream that goes quiet altogether ends it after the limit too.
     */
    fun captureClip(vad: SpeechDetector = Vad.clip(), limitMs: Long = 16_000, dsp: MicDsp? = null, done: (ShortArray, Vad.State) -> Unit): () -> Unit {
        val got = ArrayList<ShortArray>()
        var finished = false
        lateinit var l: (ShortArray) -> Unit
        fun end(st: Vad.State) {
            synchronized(got) { if (finished) return; finished = true }
            removeListener(l)
            val all = synchronized(got) { got.toList() }
            val flat = ShortArray(all.sumOf { it.size })
            var at = 0
            all.forEach { System.arraycopy(it, 0, flat, at, it.size); at += it.size }
            main.post { done(flat, st) }
        }
        l = { pcm ->
            val st = synchronized(got) {
                if (finished) null
                else if (dsp != null) { got += dsp.process(pcm); dsp.detector.state } // 4.1: the voice chain, its own detector
                else { got += pcm; vad.feed(pcm); vad.state }
            }
            if (st != null && (st == Vad.State.ENDED_AFTER_SPEECH || st == Vad.State.ENDED_NO_SPEECH || st == Vad.State.ENDED_MAX)) end(st)
        }
        addListener(l)
        val stopNow = { end(if (vad.heardSpeech) Vad.State.ENDED_MAX else Vad.State.ENDED_NO_SPEECH) }
        main.postDelayed(stopNow, limitMs)
        // Ends it now (a tap): what was recorded so far is used.
        return { main.post { end(if (vad.heardSpeech) Vad.State.ENDED_AFTER_SPEECH else Vad.State.ENDED_NO_SPEECH) } }
    }
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
        glasses.startMicStream({ packet -> onPacket(packet) }) { err ->
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
        ring.add(pcm)
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

    /** True once [stop] ran (or the start failed). */
    val isStopped: Boolean get() = stopped

    fun stop() {
        if (stopped) return
        stopped = true
        listeners.clear()
        packetListeners.clear()
        glasses.stopMicStream()
        decoder.stop()
    }
}
