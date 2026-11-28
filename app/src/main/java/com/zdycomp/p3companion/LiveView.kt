package com.zdycomp.p3companion

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Decodes the glasses' H.264 onto a Surface and, when asked, records it to an MP4 on the phone.
 * Video arrives on the Bluetooth reader thread through [offer], which never blocks: decoding and
 * writing run on their own threads, and a backlog is dropped back to the next key frame.
 *
 * 3.6 latency: every picture is decoded (later ones need it), but only the newest decoded picture is
 * shown; one that is already late when a newer one is ready is dropped instead of queued. Output is
 * collected every 4 ms while pictures are in the decoder, and the time from the last fragment of a
 * picture to its render is kept as [latencyMs].
 */
class VideoPipeline(private val ctx: Context, private val album: String) {
    private val decT = HandlerThread("p3-dec").apply { start() }
    private val dec = Handler(decT.looper)
    private val muxT = HandlerThread("p3-mux").apply { start() }
    private val mux = Handler(muxT.looper)
    private val queued = AtomicInteger()
    private val muxQueued = AtomicInteger()
    @Volatile private var needKey = true
    /** 3.5: when a broken picture was dropped (elapsedRealtime), 0 for none; the decoder waits for a key picture then. */
    @Volatile private var gapAt = 0L
    @Volatile var lastAuAt = 0L; private set
    @Volatile var received = 0L; private set
    /** 3.4: Annex-B bytes received, for the kbps on screen. */
    @Volatile var receivedBytes = 0L; private set
    /** 3.4: units dropped while waiting for the first picture the decoder can start on. */
    @Volatile var waitingForKey = 0L; private set
    @Volatile var dropped = 0L; private set
    @Volatile var shown = 0L; private set
    /** 3.6: decoded pictures not shown because a newer one was already ready. */
    @Volatile var lateDropped = 0L; private set
    /** 3.6: last fragment of a picture to its render, smoothed (ms), or -1 before the first. */
    @Volatile var latencyMs = -1.0; private set
    @Volatile var lastRenderAt = 0L; private set
    @Volatile var size: Pair<Int, Int> = 320 to 240; private set
    @Volatile var recordingSince = 0L; private set
    /** 3.6: the current recording has the phone's mic. */
    @Volatile var recordingAudio = false; private set
    var onSize: ((Pair<Int, Int>) -> Unit)? = null

    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private val info = MediaCodec.BufferInfo()
    private var inDecoder = 0 // pictures queued and not yet out (decoder thread only)
    private var recorder: PreviewRecorder? = null // mux thread only
    private var mic: MicAac? = null
    @Volatile private var closed = false

    init { mux.post { PreviewRecorder.cleanStale(ctx, album) } }

    /** Called on the reader thread for every access unit. */
    fun offer(au: VideoAu) {
        if (closed) return
        received++
        receivedBytes += au.annexB.size
        lastAuAt = SystemClock.elapsedRealtime()
        if (queued.get() >= MAX_QUEUE) { dropped++; needKey = true } // about 0.3 s behind: skip to the next key frame
        else {
            queued.incrementAndGet()
            dec.post { queued.decrementAndGet(); decode(au) }
        }
        if (recordingSince != 0L) {
            if (muxQueued.get() > 300) {
                mux.post { finishRecording("The phone couldn't keep up; the video so far was saved.") }
            } else {
                muxQueued.incrementAndGet()
                mux.post { muxQueued.decrementAndGet(); recorder?.write(au) }
            }
        }
    }

    /**
     * 3.5: a picture was dropped before it was whole. No command is known that asks the glasses for a
     * key picture, so the decoder waits for the next one, at most [LiveResync.MAX_WAIT_MS].
     */
    fun resync() {
        gapAt = SystemClock.elapsedRealtime()
        needKey = true
    }

    /** One producer per Surface: the old decoder is released first. */
    fun setSurface(s: Surface?) = dec.post { releaseCodec(); surface = s; needKey = true }

    private fun decode(au: VideoAu) {
        au.sps?.let { s ->
            if (!s.contentEquals(sps)) {
                sps = s
                H264.spsSize(s)?.let { if (it != size) { size = it; Handler(Looper.getMainLooper()).post { onSize?.invoke(it) } } }
                releaseCodec()
            }
        }
        au.pps?.let { pps = it }
        val s = surface ?: return
        val sp = sps ?: return
        val pp = pps ?: return
        // The decoder starts on an IDR, or on an intra picture carrying its own SPS and PPS (3.4).
        // Only a key picture starts the decoder; after a dropped picture (3.5) the running decoder waits for one, at most 2 s.
        val gap = gapAt.takeIf { it != 0L }?.let { SystemClock.elapsedRealtime() - it }
        val mayGo = if (codec == null) !needKey || au.entry else LiveResync.mayDecode(needKey, au.entry, gap)
        if (!mayGo) { waitingForKey++; return }
        try {
            val c = codec ?: MediaCodec.createDecoderByType("video/avc").also { c ->
                val f = MediaFormat.createVideoFormat("video/avc", size.first, size.second).apply {
                    setByteBuffer("csd-0", ByteBuffer.wrap(VideoAu.START + sp))
                    setByteBuffer("csd-1", ByteBuffer.wrap(VideoAu.START + pp))
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 512 * 1024)
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                    setInteger(MediaFormat.KEY_OPERATING_RATE, 60)
                }
                try {
                    c.configure(f, s, null, 0)
                } catch (e: Exception) {
                    f.removeKey(MediaFormat.KEY_LOW_LATENCY)
                    f.removeKey(MediaFormat.KEY_OPERATING_RATE)
                    c.configure(f, s, null, 0)
                }
                c.start()
                codec = c
                inDecoder = 0
            }
            needKey = false
            gapAt = 0L
            drain(c)
            val i = c.dequeueInputBuffer(5_000)
            if (i < 0) { dropped++; needKey = true; return }
            val b = c.getInputBuffer(i) ?: return
            if (b.capacity() < au.annexB.size) { dropped++; c.queueInputBuffer(i, 0, 0, 0, 0); return }
            b.clear()
            b.put(au.annexB)
            // The presentation time is the picture's arrival (elapsedRealtimeNanos / 1000), so the latency is known on render.
            c.queueInputBuffer(i, 0, au.annexB.size, au.arrivalNs / 1000, 0)
            inDecoder++
            drain(c)
            scheduleDrain(DRAIN_TRIES)
        } catch (e: Exception) {
            releaseCodec()
            needKey = true
        }
    }

    /** 3.6: looks for decoded pictures every 4 ms while some are still inside the decoder. */
    private fun scheduleDrain(tries: Int) {
        dec.removeCallbacksAndMessages(DRAIN)
        if (tries <= 0) return
        dec.postAtTime({
            val c = codec ?: return@postAtTime
            runCatching { drain(c) }
            if (inDecoder > 0) scheduleDrain(tries - 1)
        }, DRAIN, SystemClock.uptimeMillis() + DRAIN_EVERY_MS)
    }

    /** Shows only the newest decoded picture; older ones that are ready at the same time are late and dropped. */
    private fun drain(c: MediaCodec) {
        var pending = -1
        var pendingPts = 0L
        while (true) {
            val o = c.dequeueOutputBuffer(info, 0)
            if (o >= 0) {
                if (inDecoder > 0) inDecoder--
                if (pending >= 0) { c.releaseOutputBuffer(pending, false); lateDropped++ }
                pending = o
                pendingPts = info.presentationTimeUs
            } else if (o != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && o != MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) break
        }
        if (pending >= 0) {
            c.releaseOutputBuffer(pending, true)
            shown++
            val now = SystemClock.elapsedRealtimeNanos()
            lastRenderAt = SystemClock.elapsedRealtime()
            val ms = (now - pendingPts * 1000) / 1e6
            if (ms in 0.0..10_000.0) latencyMs = if (latencyMs < 0) ms else latencyMs * 0.85 + ms * 0.15
        }
    }

    private fun releaseCodec() {
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null
        inDecoder = 0
    }

    /**
     * [onDone] runs on the main thread with the saved video, or null and a reason. 3.6: [withMic] adds the
     * phone's mic as an AAC track (asked for RECORD_AUDIO first); if it can't start, the video is recorded alone.
     */
    fun startRecording(withMic: Boolean = false, onDone: (Uri?, String?) -> Unit) {
        if (recordingSince != 0L) return
        recordingSince = SystemClock.elapsedRealtime()
        var m: MicAac? = null
        if (withMic) {
            m = MicAac { data, ptsNs -> if (recordingSince != 0L) mux.post { recorder?.writeAudio(data, ptsNs) } }
            if (!m.start()) m = null
        }
        mic = m
        recordingAudio = m != null
        val rate = m?.let { MicAac.RATE }
        val noMic = withMic && m == null
        mux.post {
            recorder = PreviewRecorder(ctx, album, rate) { uri, why ->
                recordingSince = 0L
                recordingAudio = false
                val reason = if (noMic && uri != null) listOfNotNull(why, "The phone's mic couldn't start, so it has no sound.").joinToString(" ") else why
                Handler(Looper.getMainLooper()).post { onDone(uri, reason) }
            }
        }
    }

    fun stopRecording(reason: String? = null) = mux.post { finishRecording(reason) }

    private fun finishRecording(reason: String?) {
        mic?.stop()
        mic = null
        recorder?.finish(reason)
        recorder = null
        recordingSince = 0L
        recordingAudio = false
    }

    fun close() {
        closed = true
        stopRecording("Live view closed; the video was saved.")
        dec.post { releaseCodec(); surface = null }
        decT.quitSafely()
        muxT.quitSafely()
    }

    private companion object {
        val DRAIN = Any()
        /** Pictures waiting for the decoder before the backlog is dropped back to the next key frame. */
        const val MAX_QUEUE = 10
        const val DRAIN_EVERY_MS = 4L
        const val DRAIN_TRIES = 40
    }
}

/**
 * 3.6: the phone's mic as AAC-LC (44.1 kHz mono) for a live view recording. Its own thread reads the
 * mic, feeds the encoder and hands each AAC frame with its time (elapsedRealtimeNanos) to [onFrame].
 */
class MicAac(private val onFrame: (ByteArray, Long) -> Unit) {
    private var record: AudioRecord? = null
    private var enc: MediaCodec? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean = try {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(MediaRecorder.AudioSource.CAMCORDER, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192) * 2)
        record = r
        require(r.state == AudioRecord.STATE_INITIALIZED) { "mic not ready" }
        val e = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc = e
        e.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        e.start()
        r.startRecording()
        running = true
        thread = Thread({ loop() }, "p3-mic-aac").apply { start() }
        true
    } catch (e: Exception) {
        release()
        false
    }

    private fun loop() {
        val r = record ?: return
        val e = enc ?: return
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val i = e.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val buf = e.getInputBuffer(i)
                    if (buf != null) {
                        buf.clear()
                        val n = r.read(buf, minOf(buf.capacity(), 4096))
                        val end = SystemClock.elapsedRealtimeNanos()
                        val ns = end - (maxOf(n, 0) / 2) * 1_000_000_000L / RATE
                        e.queueInputBuffer(i, 0, maxOf(n, 0), ns / 1000, 0)
                    }
                }
                while (true) {
                    val o = e.dequeueOutputBuffer(info, 0)
                    if (o < 0) break
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val out = e.getOutputBuffer(o)
                        if (out != null) {
                            val bytes = ByteArray(info.size)
                            out.position(info.offset)
                            out.get(bytes)
                            onFrame(bytes, info.presentationTimeUs * 1000)
                        }
                    }
                    e.releaseOutputBuffer(o, false)
                }
            }
        } catch (_: Exception) {
        }
    }

    fun stop() {
        running = false
        runCatching { thread?.join(500) }
        thread = null
        release()
    }

    private fun release() {
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        runCatching { enc?.stop() }
        runCatching { enc?.release() }
        enc = null
    }

    companion object { const val RATE = 44_100 }
}

/**
 * Owns one MP4 in Movies/<album>, at the glasses' native size. It starts at the first key frame; every
 * call on the mux thread. 3.6: [audioRate] adds an AAC track for the phone's mic (null: video only).
 */
class PreviewRecorder(
    private val ctx: Context,
    private val album: String,
    private val audioRate: Int? = null,
    private val onDone: (Uri?, String?) -> Unit,
) {
    private val cr = ctx.contentResolver
    private var uri: Uri? = null
    private var pfd: ParcelFileDescriptor? = null
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var audioTrack = -1
    private var t0 = -1L
    private var lastPts = -1L
    private var lastAudioPts = -1L
    private var samples = 0
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var size = 320 to 240
    var ended = false
        private set

    fun write(au: VideoAu) {
        if (ended) return
        au.sps?.let {
            if (muxer != null && !it.contentEquals(sps)) { finish("The video size changed, so the recording was saved."); return }
            sps = it
            H264.spsSize(it)?.let { s -> size = s }
        }
        au.pps?.let { if (muxer == null) pps = it }
        if (muxer == null) {
            if (!au.entry || sps == null || pps == null) return
            if (!open()) return
            t0 = au.arrivalNs
        }
        if (!au.picture) return
        val pts = maxOf(lastPts + 1_000, (au.arrivalNs - t0) / 1000) // monotonic, at least 1 ms apart
        val data = au.sample()
        val bi = MediaCodec.BufferInfo().apply { set(0, data.size, pts, if (au.entry) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0) }
        try {
            muxer!!.writeSampleData(track, ByteBuffer.wrap(data), bi)
            lastPts = pts
            samples++
        } catch (e: Exception) {
            finish("Recording stopped: ${e.javaClass.simpleName}")
        }
    }

    /** 3.6: one AAC frame from the phone's mic; dropped until the video has started. */
    fun writeAudio(data: ByteArray, ptsNs: Long) {
        val m = muxer ?: return
        if (ended || audioTrack < 0 || t0 < 0) return
        val pts = (ptsNs - t0) / 1000
        if (pts < 0 || pts <= lastAudioPts) return
        val bi = MediaCodec.BufferInfo().apply { set(0, data.size, pts, 0) }
        runCatching { m.writeSampleData(audioTrack, ByteBuffer.wrap(data), bi); lastAudioPts = pts }
    }

    private fun open(): Boolean = try {
        val name = "P3_VID_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + album)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis())
        }
        val u = cr.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v)
            ?: throw IllegalStateException("couldn't create the file")
        uri = u
        val fd = cr.openFileDescriptor(u, "rw") ?: throw IllegalStateException("couldn't open the file")
        pfd = fd
        muxer = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { m ->
            track = m.addTrack(MediaFormat.createVideoFormat("video/avc", size.first, size.second).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(VideoAu.START + sps!!))
                setByteBuffer("csd-1", ByteBuffer.wrap(VideoAu.START + pps!!))
            })
            audioRate?.let { rate ->
                audioTrack = runCatching {
                    m.addTrack(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setByteBuffer("csd-0", ByteBuffer.wrap(AacCsd.of(rate, 1)))
                    })
                }.getOrDefault(-1)
            }
            m.start()
        }
        true
    } catch (e: Exception) {
        finish("Couldn't start recording: ${e.message}")
        false
    }

    fun finish(reason: String?) {
        if (ended) return
        ended = true
        val m = muxer
        muxer = null
        var ok = false
        if (m != null) {
            ok = samples >= 2 && runCatching { m.stop() }.isSuccess
            runCatching { m.release() }
        }
        runCatching { pfd?.close() }
        pfd = null
        val u = uri
        uri = null
        if (u != null) {
            if (ok) {
                runCatching {
                    cr.update(u, ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                        put(MediaStore.MediaColumns.WIDTH, size.first)
                        put(MediaStore.MediaColumns.HEIGHT, size.second)
                    }, null, null)
                }
            } else runCatching { cr.delete(u, null, null) }
        }
        onDone(if (ok) u else null, if (ok) reason else (reason ?: "No video arrived, so nothing was saved."))
    }

    companion object {
        /** Removes half-written videos left by a crash. */
        fun cleanStale(ctx: Context, album: String) = runCatching {
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val args = Bundle().apply {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                    "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.IS_PENDING} = 1")
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("${Environment.DIRECTORY_MOVIES}/$album/"))
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }
            ctx.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), args, null)?.use { c ->
                while (c.moveToNext()) {
                    runCatching { ctx.contentResolver.delete(android.content.ContentUris.withAppendedId(collection, c.getLong(0)), null, null) }
                }
            }
        }
    }
}

/**
 * Live view from the glasses, with recording to the phone. Built once when shown and then updated
 * in place, so the video surface is never torn down by a screen refresh. Leaving it always closes
 * the live view on the glasses.
 *
 * 3.1: when no video arrives within 6 seconds, the live view test ([XkGlasses.runLiveProbe]) tries
 * every open command the glasses might use. When none sends video, the screen switches to Snapshot
 * view: it takes a photo with the glasses, shows it, and takes the next one.
 *
 * 3.6: the frame has the video's exact shape; fit or fill, rotation, pinch zoom and pan; full screen
 * in landscape with the controls hidden after 3 s; "Live" with the latency in the stats; snapshots
 * from the decoded picture at native size (optionally sharpened or 2× upscaled); recording with the
 * phone's mic; "Try higher quality"; and live view opens again by itself after Bluetooth drops.
 */
class LiveViewScreen(private val a: MainActivity) : Screen {
    override val label = "Live view"

    private enum class Mode { VIDEO, TESTING, SNAPSHOT }

    private val main = Handler(Looper.getMainLooper())
    private var pipeline: VideoPipeline? = null
    private var texture: TextureView? = null
    private var status: TextView? = null
    private var stats: TextView? = null
    private var recordButton: RecordButton? = null
    private var recordLabel: TextView? = null
    private var recPill: TextView? = null
    private var statusBox: LinearLayout? = null
    private var gridView: View? = null
    private var flashView: View? = null
    private var modeBox: LinearLayout? = null
    private var probeLine: TextView? = null
    /** 3.4: rate, size and ack line under the mode chip while video plays. */
    private var rateLine: TextView? = null
    /** 3.4: (time, pictures shown, bytes received) once a second, for 5-second averages. */
    private val rateSamples = ArrayDeque<LongArray>()
    private var snapImage: ImageView? = null
    private var showGrid = false
    private var openedAt = 0L
    private var lastShown = 0L
    private var active = false

    // 3.6: layout, picture and full screen
    private var frame: FrameLayout? = null
    private var col: LinearLayout? = null
    private var root: LinearLayout? = null
    private var scroller: View? = null
    private var fsControls: LinearLayout? = null
    private var pictureBox: LinearLayout? = null
    private var fullscreen = false
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var colPadding = IntArray(4)
    private val hideControls = Runnable { fsControls?.animate()?.alpha(0f)?.setDuration(200)?.withEndAction { fsControls?.visibility = View.GONE }?.start() }
    /** 3.6: true while the Bluetooth link is down and the screen waits to open live view again. */
    private var linkDown = false
    private var qualityRun = false
    private val glassesListener = XkGlasses.Listener { onGlassesChanged() }

    private var mode = Mode.VIDEO
    private var autoProbed = false
    private var videoSeen = false
    /** 3.3: small JPEG live pictures shown (some firmware sends the preview as pictures). */
    @Volatile private var jpegShown = 0L
    @Volatile private var jpegDecoding = false
    private var lastJpegShown = 0L

    // Snapshot view
    private var snapRunning = false
    private var snapBusy = false
    private var snapCurrent: Uri? = null
    private var snapBitmap: Bitmap? = null
    private val snapKept = mutableSetOf<Uri>()
    private var snapCount = 0
    private var snapLastAt = 0L
    private var snapEveryMs = 0L
    private var snapNote: String? = null
    private var snapToken = 0

    private val ticker = object : Runnable {
        override fun run() {
            if (!active) return
            update()
            main.postDelayed(this, 1_000)
        }
    }

    // 4.0: live sharpening on the GPU (a 3×3 kernel at the video's own pixel size), kept only while the
    // phone still shows at least 25 pictures a second with it.
    @Volatile private var texFrames = 0L
    private val texSamples = ArrayDeque<LongArray>()
    private var sharpenOn = false
    private var sharpenSince = 0L
    private var sharpenStep = 1f

    private fun applyLiveSharpen() {
        val tv = texture ?: return
        if (android.os.Build.VERSION.SDK_INT < 33) return
        if (!a.prefs.liveSharpenLive || mode == Mode.SNAPSHOT) {
            if (sharpenOn) { tv.setRenderEffect(null); sharpenOn = false }
            return
        }
        runCatching {
            val shader = android.graphics.RuntimeShader(SHARPEN_AGSL)
            shader.setFloatUniform("stepPx", sharpenStep, sharpenStep)
            shader.setFloatUniform("amount", 0.45f)
            tv.setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"))
            if (!sharpenOn) { sharpenSince = SystemClock.elapsedRealtime(); texSamples.clear() }
            sharpenOn = true
        }.onFailure {
            sharpenOn = false
            a.glasses.addLog("Live view: live sharpening isn't available on this phone (${it.javaClass.simpleName})")
        }
    }

    /** Pictures the screen really showed per second over the last 3 s; turns live sharpening off below 25 when it's the cause. */
    private fun checkSharpenRate(decodedFps: Double) {
        val now = SystemClock.elapsedRealtime()
        texSamples.addLast(longArrayOf(now, texFrames))
        while (texSamples.size > 1 && now - texSamples.first()[0] > 3_000) texSamples.removeFirst()
        if (!sharpenOn || now - sharpenSince < 3_500) return
        val first = texSamples.first()
        val span = now - first[0]
        if (span < 2_500) return
        val shownFps = (texFrames - first[1]) * 1000.0 / span
        if (shownFps < 25.0 && shownFps < decodedFps - 2.0) {
            a.prefs.liveSharpenLive = false
            applyLiveSharpen()
            val msg = String.format(Locale.US, "Live sharpening turned off: the phone showed only %.0f of %.0f pictures a second with it (it needs 25).", shownFps, decodedFps)
            a.glasses.addLog("Live view: $msg")
            a.toast(msg)
        }
    }

    override fun build(): View {
        val ui = a.ui
        val s = ui.s
        val g = a.glasses
        active = true
        if (fullscreen && !a.liveFullscreen) fullscreen = false
        val col = ui.page()
        this.col = col
        colPadding = intArrayOf(col.paddingLeft, col.paddingTop, col.paddingRight, col.paddingBottom)

        if (g.state != XkGlasses.State.READY && g.state != XkGlasses.State.BUSY) {
            col.addView(ui.emptyState(R.drawable.ic_videocam, "Glasses not connected",
                "Connect your glasses first, then open Live view again.", "Go to the Glasses tab") { a.show(MainActivity.TAB_GLASSES) })
            return ui.screen("Live view", col, back = { a.goBack() })
        }
        autoProbed = false
        videoSeen = false
        linkDown = false
        qualityRun = false
        snapKept.clear()
        snapCount = 0
        snapEveryMs = 0
        snapNote = null
        zoom = 1f; panX = 0f; panY = 0f

        val frame = FrameLayout(a).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, if (fullscreen) 0f else ui.dpf(24f))
                }
            }
            clipToOutline = true
        }
        this.frame = frame
        val tv = TextureView(a)
        texture = tv
        frame.addView(tv, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        val img = ImageView(a).apply {
            scaleType = ImageView.ScaleType.MATRIX
            visibility = View.GONE
            contentDescription = "Latest snapshot from the glasses"
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyTransform() }
        }
        snapImage = img
        frame.addView(img, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        val thirds = object : View(a) {
            private val paint = android.graphics.Paint().apply { color = 0x59FFFFFF; strokeWidth = ui.dpf(1f) }
            override fun onDraw(c: android.graphics.Canvas) {
                for (i in 1..2) {
                    c.drawLine(width * i / 3f, 0f, width * i / 3f, height.toFloat(), paint)
                    c.drawLine(0f, height * i / 3f, width.toFloat(), height * i / 3f, paint)
                }
            }
        }.apply { visibility = if (showGrid) View.VISIBLE else View.GONE; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        gridView = thirds
        frame.addView(thirds, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        frame.addView(touchLayer(), FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        val top = FrameLayout(a).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x99000000.toInt(), 0x00000000)
            )
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
        }
        val rec = ui.text("", Type.LABEL_SMALL, android.graphics.Color.WHITE).apply {
            background = ui.rounded(s.error, 100)
            setTextColor(s.onError)
            fontFeatureSettings = "tnum"
            setPadding(ui.dp(8), ui.dp(3), ui.dp(10), ui.dp(3))
            visibility = View.GONE
        }
        recPill = rec
        top.addView(rec, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.TOP or Gravity.START))
        val chip = ui.text("", Type.LABEL_SMALL, android.graphics.Color.WHITE).apply {
            background = ui.rounded(0x66000000, 100)
            fontFeatureSettings = "tnum"
            setPadding(ui.dp(8), ui.dp(3), ui.dp(8), ui.dp(3))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
        }
        stats = chip
        top.addView(chip, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.TOP or Gravity.END))
        top.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        frame.addView(top, FrameLayout.LayoutParams(Ui.MATCH, ui.dp(72), Gravity.TOP))
        val overlay = ui.column().apply {
            gravity = Gravity.CENTER
            addView(ui.spinner(32, android.graphics.Color.WHITE))
            addView(ui.text("Opening live view…", Type.BODY_MEDIUM, android.graphics.Color.WHITE).apply {
                gravity = Gravity.CENTER
                setPadding(ui.dp(24), ui.dp(12), ui.dp(24), 0)
            })
        }
        status = overlay.getChildAt(1) as TextView
        statusBox = overlay
        frame.addView(overlay, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        val flash = View(a).apply { setBackgroundColor(android.graphics.Color.WHITE); alpha = 0f }
        flashView = flash
        frame.addView(flash, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        frame.addView(fullscreenControls(), FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM))
        col.addView(frame, LinearLayout.LayoutParams(Ui.MATCH, normalFrameHeight(320 to 240)))

        // What the screen is doing right now, and which mode is active.
        val mb = ui.column().apply { setPadding(0, ui.dp(12), 0, 0) }
        modeBox = mb
        col.addView(mb)

        // Controls under the frame: snapshot, record to phone, grid, full screen.
        val controls = ui.row().apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(16), 0, ui.dp(8)) }
        controls.addView(ui.iconButton(R.drawable.ic_photo_camera, "Save a snapshot", IconBtn.TONAL) { snapshot() }.apply {
            layoutParams = LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)); setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
        })
        val recBtn = RecordButton(a, ui, s.outline).apply {
            contentDescription = "Record to phone"
            setOnClickListener { toggleRecording() }
        }
        recordButton = recBtn
        controls.addView(recBtn, LinearLayout.LayoutParams(ui.dp(72), ui.dp(72)).apply { marginStart = ui.dp(24); marginEnd = ui.dp(24) })
        // 4.1: the sharp snapshot: the last 16 live pictures aligned and combined at once.
        controls.addView(ui.iconButton(R.drawable.ic_auto_awesome, "Sharp snapshot (last 16 pictures combined)", IconBtn.TONAL) { sharpSnapshot() }.apply {
            layoutParams = LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)).apply { marginEnd = ui.dp(8) }
            setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
        })
        controls.addView(ui.iconButton(R.drawable.ic_grid, "Rule-of-thirds grid", if (showGrid) IconBtn.TONAL else IconBtn.OUTLINED) {
            showGrid = !showGrid
            gridView?.visibility = if (showGrid) View.VISIBLE else View.GONE
        }.apply { layoutParams = LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)); setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14)) })
        controls.addView(ui.iconButton(R.drawable.ic_open_in_new, "Full screen", IconBtn.OUTLINED) { setFullscreen(true) }.apply {
            layoutParams = LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)).apply { marginStart = ui.dp(8) }
            setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
        })
        col.addView(controls)
        recordLabel = ui.text("Record to phone", Type.LABEL_LARGE, s.onSurfaceVariant).apply {
            gravity = Gravity.CENTER
            fontFeatureSettings = "tnum"
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        col.addView(recordLabel)

        val pb = ui.column().apply { setPadding(0, ui.dp(12), 0, 0) }
        pictureBox = pb
        col.addView(pb)
        renderPicture()

        col.addView(ui.banner(
            "The glasses can't send sound while they stream; turn on \"Record with the phone's mic\" for sound. " +
                "Saved to Movies › ${a.prefs.albumName}.",
            Tone.NEUTRAL,
        ).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(16) })

        val p = VideoPipeline(a.applicationContext, a.prefs.albumName)
        pipeline = p
        p.onSize = { size -> layoutFrame(size); applyTransform() }
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { p.setSurface(Surface(st)); applyTransform() }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { applyTransform() }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { p.setSurface(null); return true }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                texFrames++
                // 4.1: the last 16 pictures for a zero-lag sharp snapshot (a GPU copy of 320×240, about 1 ms).
                if (a.prefs.liveBurstRing && mode == Mode.VIDEO) { val (vw, vh) = p.size; liveRing.add(tv, vw, vh) }
            }
        }
        g.videoSink = { p.offer(it) }
        g.liveGapSink = { p.resync() }
        jpegShown = 0
        g.jpegSink = { bytes -> showJpegFrame(bytes) }
        g.previewStateSink = { open ->
            if (!open && active && mode == Mode.VIDEO && !qualityRun) {
                val down = g.state == XkGlasses.State.IDLE || g.state == XkGlasses.State.CONNECTING
                p.stopRecording(if (down) "Bluetooth dropped; the recording so far was saved." else "The glasses stopped the video; the recording was saved.")
                showStatus(if (down) "Bluetooth dropped. Reconnecting…" else "The glasses closed the live view.", spinning = down)
            }
        }
        g.removeListener(glassesListener)
        g.addListener(glassesListener)
        if (a.prefs.liveKeepScreenOn) a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // The glasses can't stream video while their mic link is in use (an idle voice session lets go of it).
        a.assistant.releaseAudio()
        val route = a.assistant.route
        mode = Mode.VIDEO
        if (route.active) {
            showStatus("The glasses' mic is in use (voice chat or recording).", spinning = false)
            col.addView(ui.banner("The mic is in use, so the glasses can't stream.", Tone.WARN), 1)
            col.addView(ui.button("Stop the mic and open live view", Btn.FILLED, R.drawable.ic_stop) {
                a.assistant.stop()
                a.recorder.stop()
                showStatus("Opening live view…", spinning = true)
                main.postDelayed({ if (active) start() }, 1_000)
            }, 2)
        } else {
            start()
        }
        renderMode()
        main.removeCallbacks(ticker)
        main.postDelayed(ticker, 1_000)
        val screen = ui.screen("Live view", col, back = { a.goBack() })
        root = screen
        scroller = screen.getChildAt(1)
        scroller?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (fullscreen) main.post { layoutFrame() } }
        if (fullscreen) main.post { if (active) setFullscreen(true) }
        return screen
    }

    /** Video when it can work on these glasses, otherwise straight to Snapshot view (a "none" from an older test is tested again). */
    private fun start() {
        val p = a.prefs
        if (p.livePath == XkGlasses.LIVE_NONE && p.liveProbeVersion >= XkGlasses.PROBE_VERSION) startSnapshots() else open()
    }

    /** A whole JPEG live picture from the glasses (reader thread): decoded off the main thread, extra ones dropped. */
    private fun showJpegFrame(bytes: ByteArray) {
        if (jpegDecoding || !active) return
        jpegDecoding = true
        Thread {
            val bmp = runCatching { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            a.runOnUiThread {
                jpegDecoding = false
                if (bmp == null) return@runOnUiThread
                if (!active || mode == Mode.SNAPSHOT) { bmp.recycle(); return@runOnUiThread }
                val old = snapBitmap
                snapImage?.setImageBitmap(bmp); (snapImage?.drawable as? android.graphics.drawable.BitmapDrawable)?.isFilterBitmap = true
                snapImage?.visibility = View.VISIBLE
                snapBitmap = bmp
                old?.recycle()
                jpegShown++
                statusBox?.visibility = View.GONE
                applyTransform()
            }
        }.start()
    }

    private fun showStatus(text: String, spinning: Boolean) {
        status?.text = text
        statusBox?.getChildAt(0)?.visibility = if (spinning) View.VISIBLE else View.GONE
        statusBox?.visibility = View.VISIBLE
    }

    private fun open() {
        mode = Mode.VIDEO
        snapImage?.visibility = View.GONE
        openedAt = SystemClock.elapsedRealtime()
        showStatus("Opening live view…", spinning = true)
        renderMode()
        a.glasses.openPreview { msg ->
            if (!active || mode != Mode.VIDEO) return@openPreview
            // 4.1.1: the open may have stepped down the detail settings; the 6 s wait starts now.
            openedAt = SystemClock.elapsedRealtime()
            if (msg != null) showStatus(msg, spinning = false)
            else renderPicture()
        }
    }

    // ---------------- 3.6: Bluetooth drops ----------------

    /** The link dropped while this screen is on: say so, and open live view again once the glasses are back. */
    private fun onGlassesChanged() {
        if (!active) return
        val g = a.glasses
        when (g.state) {
            XkGlasses.State.IDLE, XkGlasses.State.CONNECTING -> if (!linkDown) {
                linkDown = true
                pipeline?.stopRecording("Bluetooth dropped; the recording so far was saved.")
                if (mode == Mode.VIDEO) showStatus("Bluetooth dropped. Reconnecting…", spinning = true)
                // With auto-reconnect off in Settings, this screen still asks once.
                if (!a.prefs.autoReconnect) main.postDelayed({ if (active && linkDown && g.state == XkGlasses.State.IDLE) g.reconnectSaved() }, 3_000)
            }
            XkGlasses.State.READY -> if (linkDown) {
                linkDown = false
                g.addLog("Live view: the glasses are back; opening live view again")
                if (mode == Mode.VIDEO) {
                    showStatus("Reconnected. Opening live view…", spinning = true)
                    main.postDelayed({ if (active && !linkDown && mode == Mode.VIDEO && a.glasses.state == XkGlasses.State.READY) open() }, 1_000)
                }
            }
            XkGlasses.State.BUSY -> {}
        }
    }

    // ---------------- what the screen says it's doing ----------------

    private fun renderMode() {
        val box = modeBox ?: return
        val ui = a.ui
        val s = ui.s
        val p = a.prefs
        box.removeAllViews()
        probeLine = null
        rateLine = null
        val card = ui.tonalCard()
        when (mode) {
            Mode.VIDEO -> {
                card.addView(ui.statusChip(if (videoSeen) "Mode: live video" else "Mode: live video, waiting for the first picture",
                    if (videoSeen) Tone.GOOD else Tone.BUSY, pulse = !videoSeen))
                val via = p.livePath?.takeIf { it != XkGlasses.LIVE_NONE }
                card.addView(ui.body(
                    "Video comes straight from the glasses' camera over Bluetooth" + (via?.let { ", using command $it" } ?: "") + ". " +
                        if (videoSeen) "Pinch to zoom, drag to look around, double-tap to reset."
                        else "If nothing arrives within 6 seconds the app tests which live view command your glasses answer.",
                    muted = true, sizeSp = 13f,
                ).apply { setPadding(0, ui.dp(8), 0, 0) })
                val rl = ui.mono("").apply { setPadding(0, ui.dp(8), 0, 0); maxLines = 4; visibility = View.GONE }
                rateLine = rl
                card.addView(rl)
                card.addView(ui.button("Use snapshot view instead", Btn.TEXT, R.drawable.ic_photo_camera, full = false) { startSnapshots() })
            }
            Mode.TESTING -> {
                card.addView(ui.statusChip(if (qualityRun) "Mode: trying higher quality" else "Mode: testing live view", Tone.BUSY, pulse = true))
                card.addView(ui.body(
                    if (qualityRun) "Each setting is opened for 3 seconds and closed again; the size, pictures a second and " +
                        "kilobits a second of each are logged. This takes under a minute, then the standard picture comes back."
                    else "No video arrived, so the app is trying each live view command these glasses might use, listening up to " +
                        "10 seconds after each one the glasses accept, asking for frames and closing it again. This takes about a minute; " +
                        "taps on the glasses are ignored meanwhile.",
                    muted = true, sizeSp = 13f,
                ).apply { setPadding(0, ui.dp(8), 0, 0) })
                val line = ui.mono("Starting…").apply { setPadding(0, ui.dp(8), 0, 0); maxLines = 3 }
                probeLine = line
                card.addView(line)
            }
            Mode.SNAPSHOT -> {
                card.addView(ui.statusChip(if (snapRunning) "Mode: snapshot view" else "Mode: snapshot view, stopped",
                    if (snapRunning) Tone.INFO else Tone.NEUTRAL))
                card.addView(ui.body(
                    "Snapshot view, about one picture every few seconds. The app takes a photo with the glasses, shows it here " +
                        "and takes the next one. It isn't live video: " +
                        (p.liveProbeSummary ?: "your glasses don't send live video over Bluetooth."),
                    muted = true, sizeSp = 13f,
                ).apply { setPadding(0, ui.dp(8), 0, 0) })
                snapNote?.let { card.addView(ui.text(it, Type.BODY_SMALL, s.onSurfaceVariant).apply { setPadding(0, ui.dp(6), 0, 0) }) }
                a.glasses.wifiFound.takeIf { it.isNotEmpty() }?.let { w ->
                    card.addView(ui.text("On Wi-Fi the glasses show up as ${w.joinToString(", ")}. The app doesn't use that yet.",
                        Type.BODY_SMALL, s.onSurfaceVariant).apply { setPadding(0, ui.dp(6), 0, 0) })
                }
                card.addView(ui.buttonRow(
                    if (snapRunning) ui.button("Stop", Btn.TONAL, R.drawable.ic_stop, full = false) { stopSnapshots(); renderMode() }
                    else ui.button("Start", Btn.FILLED, R.drawable.ic_play, full = false) { startSnapshots() },
                    ui.button("Save this one", Btn.OUTLINED, R.drawable.ic_photo_library, full = false) { keepCurrent() },
                    ui.button("Test live video again", Btn.TEXT, R.drawable.ic_refresh, full = false) { runProbe(auto = false) },
                ).apply { setPadding(0, ui.dp(8), 0, 0) })
                card.addView(ui.switchRow(
                    "Keep every snapshot",
                    "Off: each snapshot is deleted after it's shown, unless you tap Save this one",
                    p.keepSnapshots, R.drawable.ic_photo_library,
                ) { p.keepSnapshots = it })
            }
        }
        box.addView(card)
        recordButton?.isEnabled = mode == Mode.VIDEO
        recordButton?.alpha = if (mode == Mode.VIDEO) 1f else 0.4f
    }

    /** 3.6: the Picture card: quality, fit or fill, rotation and what snapshots and recordings get. */
    private fun renderPicture() {
        val box = pictureBox ?: return
        val ui = a.ui
        val s = ui.s
        val p = a.prefs
        box.removeAllViews()
        val card = ui.card()
        card.addView(ui.heading("Picture", R.drawable.ic_videocam))
        val hq = p.liveQualitySize
        if (p.liveQualityData != null && hq != null) {
            card.addView(ui.choiceRow("Quality", listOf("Standard 320×240", "High ${hq.first}×${hq.second}"), if (p.liveQualityHigh) 1 else 0) { i ->
                val want = i == 1
                if (want != p.liveQualityHigh) { p.liveQualityHigh = want; switchQuality() }
            })
        } else {
            card.addView(ui.text("Quality: " + (pipeline?.size?.let { "${it.first}×${it.second}" } ?: "320×240") +
                (if (p.liveQualityAt > 0) ", the only size these glasses sent" else ""), Type.BODY_MEDIUM, s.onSurface).apply {
                setPadding(0, ui.dp(8), 0, 0)
            })
        }
        // 4.1.1: the detail (bitrate) setting the live view opens with.
        val detail = p.liveDetailData ?: VideoQuality.LADDER.first()
        card.addView(ui.text("Detail: [${VideoQuality.hex(detail)}] ${VideoQuality.detailName(detail) ?: ""}" +
            (if (p.liveDetailData == null) ", tried first; steps down by itself if no picture comes" else ", the most that worked") +
            ". Live view opens with the most detail that works.", Type.BODY_SMALL, s.onSurfaceVariant).apply { setPadding(0, ui.dp(4), 0, 0) })
        p.liveQualityReport?.let { card.addView(ui.text("Last try: $it", Type.BODY_SMALL, s.onSurfaceVariant).apply { setPadding(0, ui.dp(4), 0, 0) }) }
        card.addView(ui.button("Try higher quality", Btn.TEXT, R.drawable.ic_auto_awesome, full = false) { askTryQuality() })
        card.addView(ui.choiceRow("Shape", listOf("Fit", "Fill"), if (p.liveFill) 1 else 0) { i ->
            p.liveFill = i == 1
            zoom = 1f; panX = 0f; panY = 0f
            applyTransform()
        })
        card.addView(ui.buttonRow(
            ui.button("Rotate ${ViewMath.nextRotation(p.liveRotation)}°", Btn.TONAL, R.drawable.ic_refresh, full = false) {
                p.liveRotation = ViewMath.nextRotation(p.liveRotation)
                zoom = 1f; panX = 0f; panY = 0f
                layoutFrame()
                applyTransform()
                renderPicture()
            },
            ui.button("Reset zoom", Btn.TEXT, R.drawable.ic_restart, full = false) { zoom = 1f; panX = 0f; panY = 0f; applyTransform() },
        ).apply { setPadding(0, ui.dp(8), 0, 0) })
        if (p.liveRotation != 0) card.addView(ui.text("Turned ${p.liveRotation}°; snapshots are saved the same way.", Type.BODY_SMALL, s.onSurfaceVariant))
        card.addView(ui.switchRow("Sharpen snapshots", "A light sharpening on the saved copy; the live picture isn't touched",
            p.liveSharpen, R.drawable.ic_contrast) { p.liveSharpen = it })
        card.addView(ui.switchRow("Snapshots at 2× size", "Smoothly upscaled, for example 640×480 from 320×240",
            p.liveUpscale, R.drawable.ic_photo) { p.liveUpscale = it })
        // 4.0: the full enhancement for snapshots, and sharpening of the live picture itself.
        card.addView(ui.switchRow("Enhance snapshots", "Denoised, Lanczos ${p.enhanceScale}× upscaled and sharpened (Settings › Pictures); replaces the two above",
            p.enhancePhotos, R.drawable.ic_auto_awesome) { p.enhancePhotos = it })
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            card.addView(ui.switchRow("Sharpen the live picture", "A light 3×3 sharpening on the phone; turns itself off if it drops below 25 pictures a second",
                p.liveSharpenLive, R.drawable.ic_contrast) { on -> p.liveSharpenLive = on; applyLiveSharpen() })
        }
        card.addView(ui.switchRow("Record with the phone's mic", "Adds sound from the phone to recordings (the glasses can't send sound while streaming)",
            p.liveRecordAudio, R.drawable.ic_mic) { on -> if (on) a.withMic { p.liveRecordAudio = true } else p.liveRecordAudio = false })
        box.addView(card)
    }

    // ---------------- 3.6: picture math on screen ----------------

    private fun videoSize(): Pair<Int, Int> {
        val p = pipeline
        if (p != null && p.received > 0) return p.size
        snapBitmap?.takeIf { !it.isRecycled }?.let { if (mode == Mode.SNAPSHOT || jpegShown > 0) return it.width to it.height }
        return p?.size ?: (320 to 240)
    }

    private fun normalFrameHeight(size: Pair<Int, Int>): Int {
        val ui = a.ui
        val dm = a.resources.displayMetrics
        val w = dm.widthPixels - ui.dp(32)
        return ViewMath.frameHeight(w, size.first, size.second, a.prefs.liveRotation, (dm.heightPixels * 0.62f).toInt())
    }

    /** The frame takes the video's exact shape (or the whole screen in full screen). */
    private fun layoutFrame(size: Pair<Int, Int> = videoSize()) {
        val f = frame ?: return
        val lp = f.layoutParams ?: return
        val h = if (fullscreen) (scroller?.height?.takeIf { it > 0 } ?: a.resources.displayMetrics.heightPixels) else normalFrameHeight(size)
        if (lp.height != h) { lp.height = h; f.layoutParams = lp }
    }

    /** Fit or fill, rotation, zoom and pan, on the video and on a still picture alike. */
    private fun applyTransform() {
        val p = a.prefs
        val tv = texture
        if (tv != null && tv.width > 0 && tv.height > 0) {
            val (vw, vh) = pipeline?.size ?: (320 to 240)
            val t = ViewMath.transform(tv.width.toFloat(), tv.height.toFloat(), vw, vh, p.liveRotation, p.liveFill, zoom, panX, panY)
            zoom = t.zoom; panX = t.dx; panY = t.dy
            val cx = tv.width / 2f
            val cy = tv.height / 2f
            val m = Matrix()
            m.setScale(t.scaleX, t.scaleY, cx, cy)
            if (t.rotation != 0) m.postRotate(t.rotation.toFloat(), cx, cy)
            if (t.zoom != 1f) m.postScale(t.zoom, t.zoom, cx, cy)
            m.postTranslate(t.dx, t.dy)
            tv.setTransform(m)
            // 4.0: one step of the live sharpening kernel is one video pixel on screen (bilinear filtering does the rest).
            val step = maxOf(1f, (tv.width * t.scaleX + tv.height * t.scaleY) * t.zoom / (vw + vh))
            if (step != sharpenStep || (a.prefs.liveSharpenLive != sharpenOn)) { sharpenStep = step; applyLiveSharpen() }
        }
        val img = snapImage ?: return
        val bmp = snapBitmap?.takeIf { !it.isRecycled } ?: return
        if (img.width <= 0 || img.height <= 0) return
        val t = ViewMath.transform(img.width.toFloat(), img.height.toFloat(), bmp.width, bmp.height, p.liveRotation, p.liveFill, zoom, panX, panY)
        val (sw, _) = ViewMath.shownSize(bmp.width, bmp.height, t.rotation)
        val k = t.shownW / sw
        val m = Matrix()
        m.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
        if (t.rotation != 0) m.postRotate(t.rotation.toFloat())
        m.postScale(k, k)
        m.postTranslate(img.width / 2f + t.dx, img.height / 2f + t.dy)
        img.imageMatrix = m
    }

    /** Pinch to zoom, drag to pan when zoomed, double-tap to zoom in or reset, tap for the controls in full screen. */
    private fun touchLayer(): View {
        val v = View(a).apply { contentDescription = "Live picture. Pinch to zoom, double-tap to zoom in or reset." }
        val scale = ScaleGestureDetector(a, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val nz = ViewMath.clampZoom(zoom * d.scaleFactor)
                val (px, py) = ViewMath.zoomAbout(zoom, nz, panX, panY, d.focusX - v.width / 2f, d.focusY - v.height / 2f)
                zoom = nz; panX = px; panY = py
                applyTransform()
                return true
            }
        })
        val gestures = GestureDetector(a, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (zoom <= 1f) return false
                panX -= dx; panY -= dy
                applyTransform()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (zoom > 1.01f) { zoom = 1f; panX = 0f; panY = 0f }
                else {
                    val (px, py) = ViewMath.zoomAbout(1f, 2f, 0f, 0f, e.x - v.width / 2f, e.y - v.height / 2f)
                    zoom = 2f; panX = px; panY = py
                }
                applyTransform()
                return true
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (fullscreen) toggleControls()
                return true
            }
        })
        v.setOnTouchListener { view, e ->
            scale.onTouchEvent(e)
            gestures.onTouchEvent(e)
            if (zoom > 1f || scale.isInProgress || e.pointerCount > 1) view.parent?.requestDisallowInterceptTouchEvent(true)
            if (e.actionMasked == MotionEvent.ACTION_UP) view.performClick()
            true
        }
        return v
    }

    // ---------------- 3.6: full screen ----------------

    private fun fullscreenControls(): View {
        val ui = a.ui
        val bar = ui.row().apply {
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0x99000000.toInt(), 0x00000000)
            )
            setPadding(ui.dp(12), ui.dp(24), ui.dp(12), ui.dp(16))
            visibility = View.GONE
        }
        fun btn(icon: Int, label: String, onClick: () -> Unit) = ui.iconButton(icon, label, IconBtn.STANDARD, android.graphics.Color.WHITE) {
            onClick(); showControls()
        }.apply { layoutParams = LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)).apply { marginStart = ui.dp(6); marginEnd = ui.dp(6) } }
        bar.addView(btn(R.drawable.ic_close, "Leave full screen") { setFullscreen(false) })
        bar.addView(btn(R.drawable.ic_photo_camera, "Save a snapshot") { snapshot() })
        bar.addView(btn(R.drawable.ic_auto_awesome, "Sharp snapshot") { sharpSnapshot() })
        bar.addView(btn(R.drawable.ic_record, "Record to phone or stop") { toggleRecording() })
        bar.addView(btn(R.drawable.ic_refresh, "Rotate the picture") {
            a.prefs.liveRotation = ViewMath.nextRotation(a.prefs.liveRotation)
            zoom = 1f; panX = 0f; panY = 0f
            applyTransform()
        })
        bar.addView(btn(R.drawable.ic_contrast, "Fit or fill") {
            a.prefs.liveFill = !a.prefs.liveFill
            zoom = 1f; panX = 0f; panY = 0f
            applyTransform()
        })
        fsControls = bar
        return bar
    }

    private fun showControls() {
        val c = fsControls ?: return
        main.removeCallbacks(hideControls)
        c.animate().cancel()
        c.visibility = View.VISIBLE
        c.alpha = 1f
        main.postDelayed(hideControls, CONTROLS_HIDE_MS)
    }

    private fun toggleControls() {
        val c = fsControls ?: return
        if (c.visibility == View.VISIBLE && c.alpha > 0.5f) { main.removeCallbacks(hideControls); hideControls.run() } else showControls()
    }

    /** Full screen: landscape, no bars, only the picture; the controls show on a tap and hide after 3 s. */
    private fun setFullscreen(on: Boolean) {
        val c = col ?: return
        val f = frame ?: return
        fullscreen = on
        a.setLiveFullscreen(on)
        root?.getChildAt(0)?.visibility = if (on) View.GONE else View.VISIBLE
        for (i in 0 until c.childCount) {
            val v = c.getChildAt(i)
            if (v !== f) v.visibility = if (on) View.GONE else View.VISIBLE
        }
        if (on) c.setPadding(0, 0, 0, 0) else c.setPadding(colPadding[0], colPadding[1], colPadding[2], colPadding[3])
        f.invalidateOutline()
        if (on) { scroller?.scrollTo(0, 0); showControls() }
        else { main.removeCallbacks(hideControls); fsControls?.visibility = View.GONE }
        zoom = 1f; panX = 0f; panY = 0f
        layoutFrame()
        main.post { layoutFrame(); applyTransform() }
    }

    /** The phone turned (full screen asks for landscape): fit the frame again, nothing is rebuilt. */
    fun onConfigChanged() {
        main.post { if (active) { layoutFrame(); applyTransform() } }
    }

    override fun onBack(): Boolean {
        if (fullscreen) { setFullscreen(false); return true }
        return false
    }

    // ---------------- 3.6: try higher quality ----------------

    private fun askTryQuality() {
        val g = a.glasses
        if (g.state != XkGlasses.State.READY) { a.toast("Connect the glasses first (they must not be busy)."); return }
        a.ui.dialog(
            "Try higher quality?",
            "Live view closes for under a minute while 6 settings are tried, 3 seconds each, each closed again before the next. " +
                "Then the picture comes back. The most detailed setting that sends video is kept for live view; " +
                "a bigger picture is only used if one is found and you pick High.",
            icon = R.drawable.ic_videocam, confirm = "Try it", dismiss = "Cancel",
        ) { runQuality(); true }
    }

    private fun runQuality() {
        val g = a.glasses
        stopSnapshots()
        pipeline?.stopRecording("Stopped for the quality test; the video was saved.")
        qualityRun = true
        mode = Mode.TESTING
        renderMode()
        snapImage?.visibility = View.GONE
        showStatus("Trying higher quality settings…", spinning = true)
        g.runQualityProbe(onLine = { line -> if (active && qualityRun) probeLine?.text = line }) { res ->
            qualityRun = false
            if (!active) return@runQualityProbe
            a.toast(if (res.best != null) "Higher quality found: ${res.best.width}×${res.best.height}." else res.summary)
            mode = Mode.VIDEO
            renderMode()
            renderPicture()
            open()
        }
    }

    /** Quality changed: close the live view and open it again with the new setting, 1.6 s apart. */
    private fun switchQuality() {
        if (mode != Mode.VIDEO) return
        pipeline?.stopRecording("Quality changed; the video so far was saved.")
        a.glasses.closePreview(immediate = true)
        showStatus("Switching to ${a.glasses.qualityLabel}…", spinning = true)
        main.postDelayed({ if (active && mode == Mode.VIDEO) open() }, XkGlasses.LIVE_DEBOUNCE_MS + 100)
    }

    // ---------------- live view test ----------------

    private fun runProbe(auto: Boolean) {
        val g = a.glasses
        stopSnapshots()
        mode = Mode.TESTING
        renderMode()
        snapImage?.visibility = View.GONE
        showStatus(
            if (auto) "No video after 6 seconds. Testing which live view command your glasses answer…"
            else "Testing which live view command your glasses answer…",
            spinning = true,
        )
        a.withNearbyWifi { wifi ->
            // 3.5: the test runs inside this screen's session: video that works stays open, no close and reopen.
            g.runLiveProbe(onLine = { line -> if (active && mode == Mode.TESTING) probeLine?.text = line }, wifi = wifi, keepOpen = true) { res ->
                if (!active) { if (res.keptOpen) g.closePreview(); return@runLiveProbe }
                when {
                    res.path != null && res.keptOpen -> {
                        a.toast("Live video works with command ${res.path}.")
                        mode = Mode.VIDEO
                        openedAt = SystemClock.elapsedRealtime()
                        renderMode()
                    }
                    res.path != null -> { a.toast("Live video works with command ${res.path}."); open() }
                    res.complete -> startSnapshots()
                    else -> {
                        mode = Mode.VIDEO
                        renderMode()
                        showStatus(res.summary, spinning = false)
                    }
                }
            }
        }
    }

    // ---------------- snapshot view ----------------

    private fun startSnapshots() {
        val g = a.glasses
        if (mode == Mode.VIDEO) {
            pipeline?.stopRecording(null)
            g.closePreview(immediate = true)
        }
        mode = Mode.SNAPSHOT
        snapRunning = true
        snapNote = null
        snapImage?.visibility = View.VISIBLE
        if (snapBitmap == null) showStatus("Taking the first picture…", spinning = true) else statusBox?.visibility = View.GONE
        renderMode()
        nextSnap(++snapToken)
    }

    private fun stopSnapshots() {
        snapRunning = false
        snapToken++
    }

    /** Why a snapshot can't be taken right now, or null. Never during a recording or an import. */
    private fun snapBlocker(): String? {
        val g = a.glasses
        return when {
            g.state == XkGlasses.State.IDLE || g.state == XkGlasses.State.CONNECTING -> "Waiting for the glasses to connect…"
            g.recording -> "Paused: the glasses are recording video."
            a.recorder.recording || a.recorder.starting -> "Paused: the voice recorder is on."
            a.importer.running -> "Paused while files are being imported."
            g.probing -> "Paused while the live view test runs."
            g.state == XkGlasses.State.BUSY -> "Waiting for the glasses to finish…"
            else -> null
        }
    }

    private fun nextSnap(token: Int) {
        if (!active || !snapRunning || token != snapToken || snapBusy) return
        val blocker = snapBlocker()
        if (blocker != null) {
            if (snapNote != blocker) { snapNote = blocker; renderMode() }
            if (snapBitmap == null) showStatus(blocker, spinning = false)
            main.postDelayed({ nextSnap(token) }, 2_000)
            return
        }
        if (snapNote != null) { snapNote = null; renderMode() }
        snapBusy = true
        val startedAt = SystemClock.elapsedRealtime()
        a.glasses.takePhoto(onDone = { uri ->
            snapBusy = false
            if (!active || !snapRunning || token != snapToken) {
                if (uri != null && !a.prefs.keepSnapshots) deleteSnapshot(uri)
                return@takePhoto
            }
            if (uri == null) {
                snapNote = "That picture didn't arrive: ${a.glasses.lastError ?: "no answer"}. Trying again…"
                renderMode()
                main.postDelayed({ nextSnap(token) }, 1_000)
                return@takePhoto
            }
            val now = SystemClock.elapsedRealtime()
            snapEveryMs = if (snapLastAt == 0L) now - startedAt else now - snapLastAt
            snapLastAt = now
            showSnapshot(uri)
            // 3.3: replies no longer time out, so the next picture can be asked for at once.
            main.post { nextSnap(token) }
        }, announce = false, auto = true)
    }

    private fun showSnapshot(uri: Uri) {
        val cr = a.contentResolver
        Thread {
            val bmp = runCatching { ImagePrep.thumbnail(cr, uri, 1280) }.getOrNull()
            a.runOnUiThread {
                if (!active) { bmp?.recycle(); if (!a.prefs.keepSnapshots && uri !in snapKept) deleteSnapshot(uri); return@runOnUiThread }
                if (bmp != null) {
                    val old = snapBitmap
                    snapImage?.setImageBitmap(bmp); (snapImage?.drawable as? android.graphics.drawable.BitmapDrawable)?.isFilterBitmap = true
                    snapBitmap = bmp
                    old?.recycle()
                    statusBox?.visibility = View.GONE
                    layoutFrame(bmp.width to bmp.height)
                    applyTransform()
                }
                val previous = snapCurrent
                snapCurrent = uri
                snapCount++
                if (previous != null && previous != uri && !a.prefs.keepSnapshots && previous !in snapKept) deleteSnapshot(previous)
            }
        }.start()
    }

    private fun keepCurrent() {
        val u = snapCurrent ?: run { a.toast("No snapshot yet."); return }
        if (a.prefs.keepSnapshots) { a.toast("Every snapshot is being kept already."); return }
        snapKept += u
        a.toast("Kept in Pictures › ${a.prefs.albumName}.")
    }

    private fun deleteSnapshot(uri: Uri) {
        val cr = a.contentResolver
        Thread { runCatching { cr.delete(uri, null, null) } }.start()
    }

    // ---------------- updates ----------------

    private fun update() {
        val p = pipeline ?: return
        val g = a.glasses
        if (mode == Mode.SNAPSHOT) {
            stats?.text = if (snapCount == 0) "Snapshot view" else
                "Snapshot $snapCount" + if (snapEveryMs > 0) " · every ${(snapEveryMs + 500) / 1000} s" else ""
            recPill?.visibility = View.GONE
            return
        }
        val shownNow = p.shown
        lastShown = shownNow
        val (w, h) = p.size
        val jpegs = jpegShown
        val jfps = jpegs - lastJpegShown
        lastJpegShown = jpegs
        // 3.4: pictures shown a second and kilobits a second, averaged over the last 5 seconds.
        val now = SystemClock.elapsedRealtime()
        rateSamples.addLast(longArrayOf(now, shownNow, p.receivedBytes))
        while (rateSamples.size > 1 && now - rateSamples.first()[0] > 5_000) rateSamples.removeFirst()
        val first = rateSamples.first()
        val span = now - first[0]
        val fps = if (span > 0) (shownNow - first[1]) * 1000.0 / span else 0.0
        val kbps = if (span > 0) (p.receivedBytes - first[2]) * 8.0 / span else 0.0
        val fpsText = if (fps >= 10 || fps == 0.0) "%.0f".format(Locale.US, fps) else "%.1f".format(Locale.US, fps)
        checkSharpenRate(fps)
        // 3.6: "Live" while pictures render, with the time from the last fragment to the screen.
        val live = p.lastRenderAt != 0L && now - p.lastRenderAt < 1_500
        val lat = p.latencyMs.takeIf { it >= 0 }?.let { " · ~${it.toInt()} ms" } ?: ""
        stats?.text = when {
            mode == Mode.TESTING -> if (qualityRun) "Trying quality…" else "Testing…"
            linkDown -> "Reconnecting…"
            jpegs > 0 && p.received == 0L -> "Pictures · $jfps fps"
            else -> (if (live) "● Live$lat · " else if (p.received > 0) "Paused · " else "") +
                "${w}×$h · $fpsText fps · ${"%.0f".format(Locale.US, kbps)} kbps" + if (p.dropped > 0) " · ${p.dropped} dropped" else ""
        }
        rateLine?.let { rl ->
            if (mode == Mode.VIDEO && p.received > 0) {
                rl.visibility = View.VISIBLE
                rl.text = "${w}×$h · $fpsText fps · ${"%.0f".format(Locale.US, kbps)} kbps (5 s average) · ${g.qualityLabel}\n" +
                    "Latency: " + (p.latencyMs.takeIf { it >= 0 }?.let { "%.0f ms from the last fragment to the screen".format(Locale.US, it) } ?: "measuring") +
                    (if (p.lateDropped > 0) " · ${p.lateDropped} late picture(s) skipped" else "") + "\n" +
                    "Acks: ${g.liveAckLabel}" + (if (g.liveDropped > 0) " · ${g.liveDropped} broken picture(s) skipped" else "") +
                    if (shownNow == 0L && p.waitingForKey > 0) "\nWaiting for a picture the phone can start decoding on…" else ""
            } else rl.visibility = View.GONE
        }
        if (p.received > 0 || jpegs > 0) {
            if (!linkDown) statusBox?.visibility = View.GONE
            if (!videoSeen) { videoSeen = true; if (mode == Mode.VIDEO) renderMode() }
        } else if (mode == Mode.VIDEO && openedAt != 0L && !autoProbed && !linkDown && SystemClock.elapsedRealtime() - openedAt > AUTO_PROBE_MS &&
            !g.liveOpening && g.preview != XkGlasses.Preview.BUSY && g.state == XkGlasses.State.READY) {
            autoProbed = true
            runProbe(auto = true)
        }
        if (!linkDown && mode == Mode.VIDEO && p.received > 0 && now - p.lastAuAt < 1_000 && statusBox?.visibility == View.VISIBLE &&
            status?.text?.startsWith("Reconnected") == true) statusBox?.visibility = View.GONE
        val since = p.recordingSince
        if (since != 0L) {
            val clock = VoiceRecorder.clock(SystemClock.elapsedRealtime() - since)
            recordButton?.recording = true
            recordButton?.contentDescription = "Stop recording"
            recordLabel?.text = "Recording $clock" + (if (p.recordingAudio) " with sound" else "") + " · tap to stop"
            recPill?.text = "● REC $clock"
            recPill?.visibility = View.VISIBLE
            if (!a.ui.reduceMotion) recPill?.alpha = if ((SystemClock.elapsedRealtime() / 500) % 2 == 0L) 1f else 0.75f
            if (p.received > 0 && SystemClock.elapsedRealtime() - p.lastAuAt > 4_000) {
                p.stopRecording("The video stopped, so the recording was saved.")
            }
        } else {
            recordButton?.recording = false
            recordButton?.contentDescription = "Record to phone"
            recordLabel?.text = if (mode == Mode.VIDEO) "Record to phone" else "Recording needs live video"
            recPill?.visibility = View.GONE
        }
    }

    private fun toggleRecording() {
        val p = pipeline ?: return
        if (mode != Mode.VIDEO) { a.toast("Recording to the phone needs live video."); return }
        if (p.recordingSince != 0L) { a.ui.haptic(recordButton, Haptic.END); p.stopRecording(null); return }
        val go = { withMic: Boolean ->
            a.ui.haptic(recordButton, Haptic.START)
            p.startRecording(withMic) { uri, why ->
                if (uri != null) a.toast(why ?: "Video saved to Movies › ${a.prefs.albumName}.")
                else a.toast(why ?: "Nothing was recorded.")
                update()
            }
            update()
        }
        if (a.prefs.liveRecordAudio) a.withMic { go(true) } else go(false)
    }

    /**
     * Saves what's on screen, or keeps the current snapshot. Also used by "save a snapshot". 3.6: the
     * decoded picture at the glasses' native size (not the screen's), turned like the screen, optionally
     * 2× upscaled and sharpened on the copy.
     */
    /** 4.1: zero-lag live burst. */
    private val liveRing = LiveRing(16)

    /** 4.1: the last 16 live pictures, aligned and combined into one sharper 2× (or 3×) picture; the live view isn't touched. */
    internal fun sharpSnapshot() {
        if (mode != Mode.VIDEO) { a.toast("Sharp snapshot needs live video; in snapshot view use Burst photo instead."); return }
        if (!a.prefs.liveBurstRing) { a.toast("Turn on \"Keep the last 16 live pictures\" in Settings › Pictures first."); return }
        val frames = liveRing.snapshot()
        if (frames.size < 4) { a.toast("Live view hasn't shown enough pictures yet."); return }
        flashView?.let { f -> if (!a.ui.reduceMotion) { f.alpha = 0f; f.animate().alpha(0.6f).setDuration(60).withEndAction { f.animate().alpha(0f).setDuration(90).start() }.start() } }
        a.toast("Combining ${frames.size} live pictures…")
        BurstCapture.liveBurst(a, frames, liveRing.w, liveRing.h, a.prefs.liveRotation) { uri, line ->
            a.glasses.addLog(line)
            a.toast(if (uri != null) "Sharp snapshot saved." else line)
            if (uri != null) a.refreshGalleryIfShown()
        }
    }

    internal fun snapshot() {
        if (mode == Mode.SNAPSHOT) { keepCurrent(); return }
        val jpegOnly = jpegShown > 0 && (pipeline?.received ?: 0L) == 0L
        val (vw, vh) = pipeline?.size ?: (320 to 240)
        val raw = (if (jpegOnly) snapBitmap?.let { it.copy(Bitmap.Config.ARGB_8888, false) }
            else texture?.takeIf { it.isAvailable }?.let { runCatching { it.getBitmap(vw, vh) }.getOrNull() })
            ?: run { a.toast("No picture yet."); return }
        flashView?.let { f ->
            if (!a.ui.reduceMotion) {
                f.alpha = 0f
                f.animate().alpha(0.6f).setDuration(60).withEndAction { f.animate().alpha(0f).setDuration(90).start() }.start()
            }
        }
        val p = a.prefs
        val album = p.albumName
        val rotation = p.liveRotation
        val upscale = p.liveUpscale
        val sharpen = p.liveSharpen
        // 4.0: "Enhance photos" runs the full pipeline on snapshots too (denoise, Lanczos-3 2× or 3×, adaptive sharpen).
        val enhance = p.enhancePhotos
        val enhanceScale = p.enhanceScale
        val strength = p.enhanceStrength
        val resolver = a.contentResolver
        Thread {
            val result = runCatching {
                var bmp = raw
                if (rotation != 0) {
                    val m = Matrix().apply { postRotate(rotation.toFloat()) }
                    bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).also { if (it !== bmp) bmp.recycle() }
                }
                var note: String? = null
                if (enhance) {
                    val sw = bmp.width
                    val sh = bmp.height
                    val out = PhotoEnhancer.enhance(bmp, enhanceScale, strength)
                    bmp.recycle()
                    bmp = out
                    note = PhotoEnhancer.note(bmp.width, bmp.height, sw, sh, strength)
                }
                if (upscale && !enhance) bmp = Bitmap.createScaledBitmap(bmp, bmp.width * 2, bmp.height * 2, true).also { if (it !== bmp) bmp.recycle() }
                if (sharpen && !enhance) {
                    val w = bmp.width
                    val h = bmp.height
                    val px = IntArray(w * h)
                    bmp.getPixels(px, 0, w, 0, 0, w, h)
                    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    out.setPixels(ViewMath.unsharp(px, w, h), 0, w, 0, 0, w, h)
                    bmp.recycle()
                    bmp = out
                }
                saveSnapshot(resolver, bmp, album, note)
                val size = "${bmp.width}×${bmp.height}" + if (note != null) ", enhanced" else ""
                bmp.recycle()
                size
            }
            a.runOnUiThread { a.toast(result.fold({ "Snapshot saved ($it)." }, { "Couldn't save the snapshot." })) }
        }.start()
    }


    private fun saveSnapshot(resolver: android.content.ContentResolver, bmp: Bitmap, album: String, note: String? = null) {
        val name = "P3_SNAP_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".jpg"
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + album)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val u = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v)
            ?: throw IllegalStateException("no row")
        try {
            resolver.openOutputStream(u, "w")!!.use { it.write(PhotoEnhancer.jpeg(bmp, note)) }
            resolver.update(u, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(MediaStore.MediaColumns.WIDTH, bmp.width)
                put(MediaStore.MediaColumns.HEIGHT, bmp.height)
            }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(u, null, null) }
            throw e
        }
    }

    override fun onHidden() {
        if (!active) return
        active = false
        liveRing.clear()
        main.removeCallbacks(ticker)
        main.removeCallbacks(hideControls)
        // Full screen stays on through a rebuild (coming back to the app); MainActivity ends it when another screen opens.
        stopSnapshots()
        snapCurrent?.let { u -> if (!a.prefs.keepSnapshots && u !in snapKept) deleteSnapshot(u) }
        snapCurrent = null
        snapImage?.setImageDrawable(null)
        snapBitmap?.recycle()
        snapBitmap = null
        snapLastAt = 0L
        val g = a.glasses
        g.removeListener(glassesListener)
        g.videoSink = null
        g.liveGapSink = null
        g.jpegSink = null
        g.previewStateSink = null
        pipeline?.close()
        pipeline = null
        g.closePreview()
        a.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        texture = null
        status = null
        stats = null
        recordButton = null
        recordLabel = null
        recPill = null
        statusBox = null
        gridView = null
        flashView = null
        modeBox = null
        probeLine = null
        rateLine = null
        rateSamples.clear()
        snapImage = null
        frame = null
        col = null
        root = null
        scroller = null
        fsControls = null
        pictureBox = null
        openedAt = 0
        lastShown = 0
        linkDown = false
    }

    private companion object {
        /** No video this long after opening starts the live view test. */
        const val AUTO_PROBE_MS = 6_000L
        /** Full-screen controls hide this long after the last touch. */
        const val CONTROLS_HIDE_MS = 3_000L
        /** The live sharpening kernel: centre 1 + 4a, the four neighbours one video pixel away -a (as [ImageEnhance.sharpen3x3]). */
        const val SHARPEN_AGSL = """
            uniform shader content;
            uniform float2 stepPx;
            uniform float amount;
            half4 main(float2 p) {
                half4 c = content.eval(p);
                half4 n = content.eval(p + float2(0.0, -stepPx.y)) + content.eval(p + float2(0.0, stepPx.y)) +
                          content.eval(p + float2(-stepPx.x, 0.0)) + content.eval(p + float2(stepPx.x, 0.0));
                half3 o = c.rgb * half(1.0 + 4.0 * amount) - n.rgb * half(amount);
                return half4(clamp(o, half3(0.0), half3(1.0)), c.a);
            }
        """
    }
}
