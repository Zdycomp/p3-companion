package com.zdycomp.p3companion

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
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
 */
class VideoPipeline(private val ctx: Context, private val album: String) {
    private val decT = HandlerThread("p3-dec").apply { start() }
    private val dec = Handler(decT.looper)
    private val muxT = HandlerThread("p3-mux").apply { start() }
    private val mux = Handler(muxT.looper)
    private val queued = AtomicInteger()
    private val muxQueued = AtomicInteger()
    @Volatile private var needKey = true
    @Volatile var lastAuAt = 0L; private set
    @Volatile var received = 0L; private set
    @Volatile var dropped = 0L; private set
    @Volatile var shown = 0L; private set
    @Volatile var size: Pair<Int, Int> = 320 to 240; private set
    @Volatile var recordingSince = 0L; private set
    var onSize: ((Pair<Int, Int>) -> Unit)? = null

    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private val info = MediaCodec.BufferInfo()
    private var recorder: PreviewRecorder? = null // mux thread only
    @Volatile private var closed = false

    init { mux.post { PreviewRecorder.cleanStale(ctx, album) } }

    /** Called on the reader thread for every access unit. */
    fun offer(au: VideoAu) {
        if (closed) return
        received++
        lastAuAt = SystemClock.elapsedRealtime()
        if (queued.get() >= 30) { dropped++; needKey = true } // about 1 s behind: skip to the next key frame
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
        if (needKey && !au.key) return
        try {
            val c = codec ?: MediaCodec.createDecoderByType("video/avc").also { c ->
                val f = MediaFormat.createVideoFormat("video/avc", size.first, size.second).apply {
                    setByteBuffer("csd-0", ByteBuffer.wrap(VideoAu.START + sp))
                    setByteBuffer("csd-1", ByteBuffer.wrap(VideoAu.START + pp))
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 512 * 1024)
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                }
                try {
                    c.configure(f, s, null, 0)
                } catch (e: Exception) {
                    f.removeKey(MediaFormat.KEY_LOW_LATENCY)
                    c.configure(f, s, null, 0)
                }
                c.start()
                codec = c
            }
            needKey = false
            drain(c)
            val i = c.dequeueInputBuffer(5_000)
            if (i < 0) { dropped++; needKey = true; return }
            val b = c.getInputBuffer(i) ?: return
            if (b.capacity() < au.annexB.size) { dropped++; c.queueInputBuffer(i, 0, 0, 0, 0); return }
            b.clear()
            b.put(au.annexB)
            c.queueInputBuffer(i, 0, au.annexB.size, au.arrivalNs / 1000, 0)
            drain(c)
            dec.removeCallbacksAndMessages(DRAIN)
            dec.postAtTime({ codec?.let { runCatching { drain(it) } } }, DRAIN, SystemClock.uptimeMillis() + 20)
        } catch (e: Exception) {
            releaseCodec()
            needKey = true
        }
    }

    private fun drain(c: MediaCodec) {
        while (true) {
            val o = c.dequeueOutputBuffer(info, 0)
            if (o >= 0) { c.releaseOutputBuffer(o, true); shown++ }
            else if (o != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) break
        }
    }

    private fun releaseCodec() {
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null
    }

    /** [onDone] runs on the main thread with the saved video, or null and a reason. */
    fun startRecording(onDone: (Uri?, String?) -> Unit) {
        if (recordingSince != 0L) return
        recordingSince = SystemClock.elapsedRealtime()
        mux.post {
            recorder = PreviewRecorder(ctx, album) { uri, why ->
                recordingSince = 0L
                Handler(Looper.getMainLooper()).post { onDone(uri, why) }
            }
        }
    }

    fun stopRecording(reason: String? = null) = mux.post { finishRecording(reason) }

    private fun finishRecording(reason: String?) {
        recorder?.finish(reason)
        recorder = null
        recordingSince = 0L
    }

    fun close() {
        closed = true
        stopRecording("Live view closed; the video was saved.")
        dec.post { releaseCodec(); surface = null }
        decT.quitSafely()
        muxT.quitSafely()
    }

    private companion object { val DRAIN = Any() }
}

/** Owns one MP4 in Movies/<album>. It starts at the first key frame; every call on the mux thread. */
class PreviewRecorder(
    private val ctx: Context,
    private val album: String,
    private val onDone: (Uri?, String?) -> Unit,
) {
    private val cr = ctx.contentResolver
    private var uri: Uri? = null
    private var pfd: ParcelFileDescriptor? = null
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var t0 = -1L
    private var lastPts = -1L
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
            if (!au.key || sps == null || pps == null) return
            if (!open()) return
            t0 = au.arrivalNs
        }
        if (!au.picture) return
        val pts = maxOf(lastPts + 1_000, (au.arrivalNs - t0) / 1000) // monotonic, at least 1 ms apart
        val data = au.sample()
        val bi = MediaCodec.BufferInfo().apply { set(0, data.size, pts, if (au.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0) }
        try {
            muxer!!.writeSampleData(track, ByteBuffer.wrap(data), bi)
            lastPts = pts
            samples++
        } catch (e: Exception) {
            finish("Recording stopped: ${e.javaClass.simpleName}")
        }
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
 */
class LiveViewScreen(private val a: MainActivity) : Screen {
    override val label = "Live view"

    private val main = Handler(Looper.getMainLooper())
    private var pipeline: VideoPipeline? = null
    private var texture: TextureView? = null
    private var status: TextView? = null
    private var stats: TextView? = null
    private var recordButton: Button? = null
    private var openedAt = 0L
    private var lastShown = 0L
    private var active = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!active) return
            update()
            main.postDelayed(this, 1_000)
        }
    }

    override fun build(): View {
        val ui = a.ui
        val g = a.glasses
        active = true
        val col = ui.column(16)
        val top = ui.row()
        top.addView(ui.title("Live view"), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        top.addView(ui.secondaryButton("Close") { a.goBack() }.apply {
            layoutParams = LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP)
        })
        col.addView(top)

        if (g.state != XkGlasses.State.READY && g.state != XkGlasses.State.BUSY) {
            val card = ui.card()
            card.addView(ui.body("Connect your glasses first, then open Live view again."))
            card.addView(ui.primaryButton("Go to the Glasses tab") { a.show(MainActivity.TAB_GLASSES) })
            col.addView(card)
            return ScrollView(a).apply { addView(col) }
        }

        val frame = FrameLayout(a).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        val tv = TextureView(a)
        texture = tv
        frame.addView(tv, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        val overlay = ui.body("Opening live view…").apply {
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
        }
        status = overlay
        frame.addView(overlay, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        col.addView(frame, LinearLayout.LayoutParams(Ui.MATCH, ui.dp(260)))

        stats = ui.body("", muted = true, sizeSp = 13f).apply { setPadding(0, ui.dp(8), 0, 0) }
        col.addView(stats)

        val rec = ui.primaryButton("Record to phone") { toggleRecording() }
        recordButton = rec
        col.addView(rec)
        col.addView(ui.secondaryButton("Save a snapshot") { snapshot() })
        col.addView(ui.body(
            "Recordings are video only: the glasses can't send sound while they stream. They're saved to Movies › ${a.prefs.albumName}.",
            muted = true, sizeSp = 13f,
        ).apply { setPadding(0, ui.dp(10), 0, 0) })

        val p = VideoPipeline(a.applicationContext, a.prefs.albumName)
        pipeline = p
        p.onSize = { fit() }
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { p.setSurface(Surface(st)); fit() }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { fit() }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { p.setSurface(null); return true }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        g.videoSink = { p.offer(it) }
        g.previewStateSink = { open ->
            if (!open && active) {
                p.stopRecording("The glasses stopped the video; the recording was saved.")
                status?.text = "The glasses closed the live view."
                status?.visibility = View.VISIBLE
            }
        }
        a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // The glasses can't stream video while their mic link is in use.
        val route = a.assistant.route
        if (route.active) {
            overlay.text = "The glasses' mic is in use (voice chat or recording)."
            col.addView(ui.primaryButton("Stop the mic and open live view") {
                a.assistant.stop()
                a.recorder.stop()
                overlay.text = "Opening live view…"
                main.postDelayed({ if (active) open() }, 1_000)
            }, 2)
        } else {
            open()
        }
        main.removeCallbacks(ticker)
        main.postDelayed(ticker, 1_000)
        return ScrollView(a).apply { addView(col) }
    }

    private fun open() {
        openedAt = SystemClock.elapsedRealtime()
        a.glasses.openPreview { msg ->
            if (!active) return@openPreview
            if (msg != null) {
                status?.text = msg
                status?.visibility = View.VISIBLE
            }
        }
    }

    private fun update() {
        val p = pipeline ?: return
        val shownNow = p.shown
        val fps = shownNow - lastShown
        lastShown = shownNow
        val (w, h) = p.size
        stats?.text = "$fps fps · ${w}×$h · ${p.dropped} dropped"
        if (p.received > 0) status?.visibility = View.GONE
        else if (openedAt != 0L && SystemClock.elapsedRealtime() - openedAt > 8_000 && a.glasses.preview != XkGlasses.Preview.BUSY &&
            a.glasses.preview != XkGlasses.Preview.UNSUPPORTED) {
            status?.text = "No video yet. Make sure LensMoo is closed, then try again. If it still doesn't come, share the log from Diagnostics."
            status?.visibility = View.VISIBLE
        }
        val since = p.recordingSince
        if (since != 0L) {
            recordButton?.text = "Stop recording " + VoiceRecorder.clock(SystemClock.elapsedRealtime() - since)
            if (p.received > 0 && SystemClock.elapsedRealtime() - p.lastAuAt > 4_000) {
                p.stopRecording("The video stopped, so the recording was saved.")
            }
        } else recordButton?.text = "Record to phone"
    }

    private fun toggleRecording() {
        val p = pipeline ?: return
        if (p.recordingSince != 0L) { p.stopRecording(null); return }
        p.startRecording { uri, why ->
            if (uri != null) a.toast(why ?: "Video saved to Movies › ${a.prefs.albumName}.")
            else a.toast(why ?: "Nothing was recorded.")
            update()
        }
        update()
    }

    private fun snapshot() {
        val bmp = texture?.bitmap ?: run { a.toast("No picture yet."); return }
        val album = a.prefs.albumName
        val resolver = a.contentResolver
        Thread {
            val ok = runCatching { saveSnapshot(resolver, bmp, album) }.isSuccess
            bmp.recycle()
            a.runOnUiThread { a.toast(if (ok) "Snapshot saved to the gallery." else "Couldn't save the snapshot.") }
        }.start()
    }

    private fun saveSnapshot(resolver: android.content.ContentResolver, bmp: Bitmap, album: String) {
        val name = "P3_SNAP_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + album)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val u = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v)
            ?: throw IllegalStateException("no row")
        try {
            resolver.openOutputStream(u, "w")!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            resolver.update(u, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(u, null, null) }
            throw e
        }
    }

    /** Letterboxes the video inside the view, keeping its shape. */
    private fun fit() {
        val tv = texture ?: return
        val p = pipeline ?: return
        val w = tv.width.toFloat()
        val h = tv.height.toFloat()
        if (w <= 0 || h <= 0) return
        val (vw, vh) = p.size
        val k = minOf(w / vw, h / vh)
        val m = Matrix()
        m.setScale(vw * k / w, vh * k / h, w / 2, h / 2)
        tv.setTransform(m)
    }

    override fun onHidden() {
        if (!active) return
        active = false
        main.removeCallbacks(ticker)
        val g = a.glasses
        g.videoSink = null
        g.previewStateSink = null
        pipeline?.close()
        pipeline = null
        g.closePreview()
        a.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        texture = null
        status = null
        stats = null
        recordButton = null
        openedAt = 0
        lastShown = 0
    }
}
