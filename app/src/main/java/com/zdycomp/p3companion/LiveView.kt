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
import android.widget.ImageView
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
 *
 * 3.1: when no video arrives within 6 seconds, the live view test ([XkGlasses.runLiveProbe]) tries
 * every open command the glasses might use. When none sends video, the screen switches to Snapshot
 * view: it takes a photo with the glasses, shows it, and takes the next one.
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
    private var snapImage: ImageView? = null
    private var showGrid = false
    private var openedAt = 0L
    private var lastShown = 0L
    private var active = false

    private var mode = Mode.VIDEO
    private var autoProbed = false
    private var videoSeen = false

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

    override fun build(): View {
        val ui = a.ui
        val s = ui.s
        val g = a.glasses
        active = true
        val col = ui.page()

        if (g.state != XkGlasses.State.READY && g.state != XkGlasses.State.BUSY) {
            col.addView(ui.emptyState(R.drawable.ic_videocam, "Glasses not connected",
                "Connect your glasses first, then open Live view again.", "Go to the Glasses tab") { a.show(MainActivity.TAB_GLASSES) })
            return ui.screen("Live view", col, back = { a.goBack() })
        }
        autoProbed = false
        videoSeen = false
        snapKept.clear()
        snapCount = 0
        snapEveryMs = 0
        snapNote = null

        val frame = FrameLayout(a).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, ui.dpf(24f))
                }
            }
            clipToOutline = true
        }
        val tv = TextureView(a)
        texture = tv
        frame.addView(tv, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        val img = ImageView(a).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            contentDescription = "Latest snapshot from the glasses"
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
        }
        stats = chip
        top.addView(chip, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.TOP or Gravity.END))
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
        val w = a.resources.displayMetrics.widthPixels - ui.dp(32)
        col.addView(frame, LinearLayout.LayoutParams(Ui.MATCH, (w * 3 / 4).coerceAtMost(ui.dp(420))))

        // What the screen is doing right now, and which mode is active.
        val mb = ui.column().apply { setPadding(0, ui.dp(12), 0, 0) }
        modeBox = mb
        col.addView(mb)

        // Controls under the frame: snapshot, record to phone, grid.
        val controls = ui.row().apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(16), 0, ui.dp(8)) }
        controls.addView(ui.iconButton(R.drawable.ic_photo_camera, "Save a snapshot", IconBtn.TONAL) { snapshot() }.apply {
            layoutParams = LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)); setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
        })
        val recBtn = RecordButton(a, ui, s.outline).apply {
            contentDescription = "Record to phone"
            setOnClickListener { toggleRecording() }
        }
        recordButton = recBtn
        controls.addView(recBtn, LinearLayout.LayoutParams(ui.dp(72), ui.dp(72)).apply { marginStart = ui.dp(32); marginEnd = ui.dp(32) })
        controls.addView(ui.iconButton(R.drawable.ic_grid, "Rule-of-thirds grid", if (showGrid) IconBtn.TONAL else IconBtn.OUTLINED) {
            showGrid = !showGrid
            gridView?.visibility = if (showGrid) View.VISIBLE else View.GONE
        }.apply { layoutParams = LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)); setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14)) })
        col.addView(controls)
        recordLabel = ui.text("Record to phone", Type.LABEL_LARGE, s.onSurfaceVariant).apply {
            gravity = Gravity.CENTER
            fontFeatureSettings = "tnum"
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        col.addView(recordLabel)
        col.addView(ui.banner(
            "Video only: the glasses can't send sound while they stream. Saved to Movies › ${a.prefs.albumName}.",
            Tone.NEUTRAL,
        ).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(16) })

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
            if (!open && active && mode == Mode.VIDEO) {
                p.stopRecording("The glasses stopped the video; the recording was saved.")
                showStatus("The glasses closed the live view.", spinning = false)
            }
        }
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
        return ui.screen("Live view", col, back = { a.goBack() })
    }

    /** Video when it can work on these glasses, otherwise straight to Snapshot view. */
    private fun start() {
        if (a.prefs.livePath == XkGlasses.LIVE_NONE) startSnapshots() else open()
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
            if (msg != null) showStatus(msg, spinning = false)
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
        val card = ui.tonalCard()
        when (mode) {
            Mode.VIDEO -> {
                card.addView(ui.statusChip(if (videoSeen) "Mode: live video" else "Mode: live video, waiting for the first picture",
                    if (videoSeen) Tone.GOOD else Tone.BUSY, pulse = !videoSeen))
                val via = p.livePath?.takeIf { it != XkGlasses.LIVE_NONE }
                card.addView(ui.body(
                    "Video comes straight from the glasses' camera over Bluetooth" + (via?.let { ", using command $it" } ?: "") + ". " +
                        if (videoSeen) "You can record it to the phone or save a snapshot."
                        else "If nothing arrives within 6 seconds the app tests which live view command your glasses answer.",
                    muted = true, sizeSp = 13f,
                ).apply { setPadding(0, ui.dp(8), 0, 0) })
                card.addView(ui.button("Use snapshot view instead", Btn.TEXT, R.drawable.ic_photo_camera, full = false) { startSnapshots() })
            }
            Mode.TESTING -> {
                card.addView(ui.statusChip("Mode: testing live view", Tone.BUSY, pulse = true))
                card.addView(ui.body(
                    "No video arrived, so the app is trying each live view command these glasses might use, listening about " +
                        "5 seconds for each and closing it again. This takes about 40 seconds.",
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
        g.runLiveProbe(onLine = { line -> if (active && mode == Mode.TESTING) probeLine?.text = line }) { res ->
            if (!active) return@runLiveProbe
            when {
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

    // ---------------- snapshot view ----------------

    private fun startSnapshots() {
        val g = a.glasses
        if (mode == Mode.VIDEO) {
            pipeline?.stopRecording(null)
            g.closePreview()
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
                main.postDelayed({ nextSnap(token) }, 2_000)
                return@takePhoto
            }
            val now = SystemClock.elapsedRealtime()
            snapEveryMs = if (snapLastAt == 0L) now - startedAt else now - snapLastAt
            snapLastAt = now
            showSnapshot(uri)
            main.postDelayed({ nextSnap(token) }, 300)
        }, announce = false)
    }

    private fun showSnapshot(uri: Uri) {
        val cr = a.contentResolver
        Thread {
            val bmp = runCatching { ImagePrep.thumbnail(cr, uri, 1280) }.getOrNull()
            a.runOnUiThread {
                if (!active) { bmp?.recycle(); if (!a.prefs.keepSnapshots && uri !in snapKept) deleteSnapshot(uri); return@runOnUiThread }
                if (bmp != null) {
                    val old = snapBitmap
                    snapImage?.setImageBitmap(bmp)
                    snapBitmap = bmp
                    old?.recycle()
                    statusBox?.visibility = View.GONE
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
        val fps = shownNow - lastShown
        lastShown = shownNow
        val (w, h) = p.size
        stats?.text = if (mode == Mode.TESTING) "Testing…" else "${w}×$h · $fps fps" + if (p.dropped > 0) " · ${p.dropped} dropped" else ""
        if (p.received > 0) {
            statusBox?.visibility = View.GONE
            if (!videoSeen) { videoSeen = true; if (mode == Mode.VIDEO) renderMode() }
        } else if (mode == Mode.VIDEO && openedAt != 0L && !autoProbed && SystemClock.elapsedRealtime() - openedAt > AUTO_PROBE_MS &&
            g.preview != XkGlasses.Preview.BUSY && g.state == XkGlasses.State.READY) {
            autoProbed = true
            runProbe(auto = true)
        }
        val since = p.recordingSince
        if (since != 0L) {
            val clock = VoiceRecorder.clock(SystemClock.elapsedRealtime() - since)
            recordButton?.recording = true
            recordButton?.contentDescription = "Stop recording"
            recordLabel?.text = "Recording $clock · tap to stop"
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
        a.ui.haptic(recordButton, Haptic.START)
        p.startRecording { uri, why ->
            if (uri != null) a.toast(why ?: "Video saved to Movies › ${a.prefs.albumName}.")
            else a.toast(why ?: "Nothing was recorded.")
            update()
        }
        update()
    }

    /** Saves what's on screen (live video), or keeps the current snapshot. Also used by "save a snapshot". */
    internal fun snapshot() {
        if (mode == Mode.SNAPSHOT) { keepCurrent(); return }
        val bmp = texture?.bitmap ?: run { a.toast("No picture yet."); return }
        flashView?.let { f ->
            if (!a.ui.reduceMotion) {
                f.alpha = 0f
                f.animate().alpha(0.6f).setDuration(60).withEndAction { f.animate().alpha(0f).setDuration(90).start() }.start()
            }
        }
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
        stopSnapshots()
        snapCurrent?.let { u -> if (!a.prefs.keepSnapshots && u !in snapKept) deleteSnapshot(u) }
        snapCurrent = null
        snapImage?.setImageDrawable(null)
        snapBitmap?.recycle()
        snapBitmap = null
        snapLastAt = 0L
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
        recordLabel = null
        recPill = null
        statusBox = null
        gridView = null
        flashView = null
        modeBox = null
        probeLine = null
        snapImage = null
        openedAt = 0
        lastShown = 0
    }

    private companion object {
        /** No video this long after opening starts the live view test. */
        const val AUTO_PROBE_MS = 6_000L
    }
}
