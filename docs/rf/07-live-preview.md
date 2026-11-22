# 07 — Live video preview over SPP, and recording it on the phone

Specialist 07 of 10. Scope: open and close the preview (5720, state 5711/57A0), how the H.264 arrives, SPS/PPS/IDR handling, MediaCodec onto a TextureView, MP4 with MediaMuxer into `Movies/<album>`, timestamps, drops and backlog, and the SCO conflict. Framework only, minSdk 30.
Paths: `LM` = /home/claude/joaosouz4dev/lensmoo-crash-fix, `SDK` = /home/claude/beneduzi/xk-one-pro-sdk, `APP` = app/src/main/java/com/zdycomp/p3companion.

## 1. Findings (sources)

| # | Finding | Source | Conf. |
|---|---|---|---|
| F1 | The preview **does** work over Bluetooth on firmware 1.0.2: 320x240 H.264, 295 frames in 10 s, then a 14.12 s / 427-frame MP4 made on the phone (about 30 fps). | LM docs/VIDEO-RESEARCH.md:27,29 | High |
| F2 | The open/close command is node **5720** with one byte (1 open, 0 close). The state query is **5711**. 57A0 is the recording ("shoot") state query, 5780 records on the glasses, 5790 pushes the shoot state, 57B0 takes a photo. Log strings say "5710", but the builder sends 5720. | LM docs/VIDEO-QUALITY-RESEARCH.md:81-88 | High (node), Low (action code, not stated) |
| F3 | The SDK tested **5710**, got INVALID_URN and concluded "no preview over SPP". That was the wrong node, so the result does not apply. The SDK also reads 57A0 as preview state (0/1), pushed with 0x8001. LM reads 57A0 as recording state. **Treat 57A0 as unclear.** Real frames arriving are the ground truth. | SDK docs/VALIDATION.md:184-192, NODES.md:74,84, PROTOCOL.md:115 | High |
| F4 | Each video element is `[type=0x02][len LE4][Annex-B]` with SPS(7), PPS(8) and IDR(5) or non-IDR(1) NALs. LM checks `len == size-5`, which **excludes** the header. Photo elements (type 0x00) use `len == size`, which **includes** the header. A parser must accept both. | LM android/src/dev/lensfix/video/PreviewPayload.java:51-54; SDK docs/PROTOCOL.md:197-200 | High / Med |
| F5 | The wire transport of those elements was never captured. The vendor SDK hands them over in its "JPEG" callback after its own reassembly. The SDK names `divideType == 5` frames as `WmVideoFrameInfo`. Most likely they come on 0x4A with fragments 0/1/2/3 (each fragment starting with a 4-byte cmd_idx) or as divide 5. | LM docs/VALIDATION.md:21; SDK docs/VALIDATION.md:184 | Med |
| F6 | The SPS is baseline (profile 66), level 1.1, `pic_order_cnt_type=2`, so there is no frame reordering and output can be low latency. Test vector (escaped): `67 42 C0 0B D9 01 41 FB 01 10 00 00 03 00 10 00 00 03 00 A0 F1 42 A4 80 00` gives 320x240. | LM tests/fixtures/synthetic-baseline-320x240.h264 (checked with a local parse) | High |
| F7 | Muxer: SPS goes in csd-0 and PPS in csd-1, both keeping `00 00 00 01`. Samples are Annex-B with 4-byte start codes and SPS/PPS/AUD removed. Mark IDR samples with KEY_FRAME. The FD constructor needs a seekable "rw" descriptor. Use IS_PENDING, then publish. | LM docs/RECORDING-API-RESEARCH.md:11-21,33 | High |
| F8 | The stream has no source timestamp, so use a monotonic arrival clock with strictly increasing values. LM finalises after 4 s with no frames. | LM RECORDING-API-RESEARCH.md:23; VideoSupport.java:140,336 | High |
| F9 | **SCO conflict.** Turning on SCO closed the preview (0 video frames). Reopening it during SCO gave 0 frames. Opening it while protocol audio (5750) was on returned **status 2 = busy**. Recordings are therefore silent. | LM docs/AUDIO-RESEARCH.md:5,147,161-162,219 | High (this unit) |
| F10 | Two producers on one Surface failed with a "Surface connection error". Only one decoder may own the Surface at a time. | LM docs/VALIDATION.md:21 | High |
| F11 | The SDK says some nodes (57A0, 7110, 2410…) answer only after the full setup sequence. P3 connects with only 0001/0002/102E. | SDK docs/NODES.md:103-105; APP/XkGlasses.kt:164-177 | Med |

## 2. Defects in current app code

| # | Defect | Where | Conf. |
|---|---|---|---|
| D1 | No preview at all: nothing sends 5720, and every 0x4A frame is thrown away when `assembler == null`. Video bytes reach the reader and vanish. | APP/XkGlasses.kt:391-396 | High |
| D2 | `PhotoAssembler.feed` throws on fragment type 5 and does not check the media type. A preview element arriving during a photo download would corrupt or abort the photo. `store()` strips 5 bytes blindly. | APP/XkProtocol.kt:199-219 | High |
| D3 | The "Start video" button sends 5780 (record **on the glasses**) and the text says "Videos stay on the glasses". This is not the LensMoo phone recording feature. On no-storage units it answers busy or INVALID_PARAM. | APP/XkGlasses.kt:252-279; GlassesScreen.kt:66-70,107 | High |
| D4 | The Glasses tab rebuilds its whole view on every state change (`listener → a.refresh() → show()`). A TextureView placed there would lose its SurfaceTexture several times a second. | APP/GlassesScreen.kt:16; MainActivity.kt:176-189 | High |
| D5 | Losing the link or disconnecting does not tear down any media consumer, and there is no hook for one. | APP/XkGlasses.kt:179-186,374-384 | High |
| D6 | The 8 KB read buffer and the per-frame `addLog`/`changed()` work on the reader thread will lag under about 1 Mbit/s of video. Video frames must never call `changed()`. | APP/XkGlasses.kt:363,401 | Med |
| D7 | Unsolicited pushes (57A0, 5711, 5790) pile up in `waiting` queues that nothing reads. | APP/XkGlasses.kt:423 | Low |

## 3. Design (Kotlin, framework only)

**Threads.** (1) `glasses-reader` (existing): frames go to `MediaDemux`, and a completed video element is stamped with `elapsedRealtimeNanos` and passed to `VideoPipeline.offer()`. That call never blocks. (2) The `p3-dec` HandlerThread owns the MediaCodec and the Surface hand-off. (3) The `p3-mux` HandlerThread owns MediaMuxer and MediaStore, so slow disk never stalls the preview. (4) Main: the activity and buttons.
**Lifecycle.** `PreviewActivity.onStart`: arbiter check, then `glasses.openPreview()`, then a first-frame timeout of 8 s. `onStop`: `recorder.stop()`, then `glasses.closePreview()`, then `decoder.release()`. Always send close, or the glasses keep flooding SPP. A link loss or a pushed state of 0 has the same effect as onStop. Use a separate Activity (D4) with `FLAG_KEEP_SCREEN_ON`.

### 3.1 `H264.kt` (pure, unit-testable)
```kotlin
object H264 {
    fun type(b: Byte) = b.toInt() and 0x1F
    /** NAL bodies (no start code). Returns null unless the data begins with a start code. */
    fun nals(a: ByteArray): List<IntRange>? {
        if (a.size < 4 || a[0].toInt() != 0 || a[1].toInt() != 0) return null
        val out = ArrayList<IntRange>(); var i = 0; var s = -1
        while (i + 2 < a.size) {
            if (a[i].toInt() == 0 && a[i + 1].toInt() == 0 && a[i + 2].toInt() == 1) {
                if (s >= 0) { var e = i; while (e > s && a[e - 1].toInt() == 0) e--; if (e > s) out += s until e }
                s = i + 3; i += 3
            } else i++
        }
        if (s in 0 until a.size) out += s until a.size
        return out.takeIf { it.isNotEmpty() && (s >= 0) }
    }
    private fun rbsp(n: ByteArray): ByteArray { val o = java.io.ByteArrayOutputStream(n.size); var z = 0
        for (i in 1 until n.size) { val v = n[i].toInt() and 0xFF
            if (z >= 2 && v == 3) { z = 0; continue }; o.write(v); z = if (v == 0) z + 1 else 0 }
        return o.toByteArray() }
    private class Bits(val b: ByteArray) { var p = 0
        fun u(n: Int): Int { var v = 0; repeat(n) { require(p < b.size * 8) { "short SPS" }
            v = (v shl 1) or ((b[p ushr 3].toInt() ushr (7 - (p and 7))) and 1); p++ }; return v }
        fun ue(): Int { var z = 0; while (u(1) == 0) require(++z <= 30) { "bad ue" }; return (1 shl z) - 1 + u(z) }
        fun se(): Int { val k = ue(); return if (k and 1 == 1) (k + 1) / 2 else -(k / 2) } }
    /** SPS NAL (header byte included, escaped) to display width/height. Baseline/main path; high profile skips chroma/scaling. */
    fun spsSize(sps: ByteArray): Pair<Int, Int>? = runCatching {
        require(type(sps[0]) == 7); val r = Bits(rbsp(sps))
        val profile = r.u(8); r.u(16); r.ue()
        var chroma = 1
        if (profile in intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
            chroma = r.ue(); if (chroma == 3) r.u(1); r.ue(); r.ue(); r.u(1)
            if (r.u(1) == 1) repeat(if (chroma == 3) 12 else 8) { i -> if (r.u(1) == 1) {
                var last = 8; var next = 8
                repeat(if (i < 6) 16 else 64) { if (next != 0) next = (last + r.se() + 256) % 256; if (next != 0) last = next } } }
        }
        r.ue(); when (r.ue()) { 0 -> r.ue(); 1 -> { r.u(1); r.se(); r.se(); repeat(r.ue().also { require(it <= 255) }) { r.se() } } }
        r.ue(); r.u(1)
        val wMbs = r.ue() + 1; val hMap = r.ue() + 1; val frameOnly = r.u(1)
        if (frameOnly == 0) r.u(1); r.u(1)
        var l = 0; var rt = 0; var t = 0; var bt = 0
        if (r.u(1) == 1) { l = r.ue(); rt = r.ue(); t = r.ue(); bt = r.ue() }
        val cx = if (chroma == 1 || chroma == 2) 2 else 1
        val cy = (if (chroma == 1) 2 else 1) * (2 - frameOnly)
        val w = wMbs * 16 - cx * (l + rt); val h = hMap * 16 * (2 - frameOnly) - cy * (t + bt)
        require(w in 16..4096 && h in 16..4096); w to h
    }.getOrNull()
}

/** One access unit. `annexB` is owned (already copied off the reader). */
class VideoAu(val annexB: ByteArray, val arrivalNs: Long) {
    private val units = H264.nals(annexB) ?: emptyList()
    val sps = units.firstOrNull { H264.type(annexB[it.first]) == 7 }?.let { annexB.copyOfRange(it.first, it.last + 1) }
    val pps = units.firstOrNull { H264.type(annexB[it.first]) == 8 }?.let { annexB.copyOfRange(it.first, it.last + 1) }
    val key = units.any { H264.type(annexB[it.first]) == 5 }
    val picture = units.any { H264.type(annexB[it.first]).let { t -> t == 1 || t == 5 } }
    /** Muxer sample: picture/SEI NALs only, each with 00 00 00 01. */
    fun sample(): ByteArray { val o = java.io.ByteArrayOutputStream(annexB.size)
        for (u in units) { val t = H264.type(annexB[u.first]); if (t == 7 || t == 8 || t == 9) continue
            o.write(START); o.write(annexB, u.first, u.last - u.first + 1) }
        return o.toByteArray() }
    companion object {
        val START = byteArrayOf(0, 0, 0, 1)
        /** Element [type][len LE4][body]. Accepts len = body size (LensMoo) or total size (photo style). */
        fun fromElement(e: ByteArray, at: Long): VideoAu? {
            if (e.size < 10 || e[0].toInt() != 2) return null
            val len = (e[1].toLong() and 0xFF) or ((e[2].toLong() and 0xFF) shl 8) or
                ((e[3].toLong() and 0xFF) shl 16) or ((e[4].toLong() and 0xFF) shl 24)
            if (len != e.size - 5L && len != e.size.toLong()) return null
            if (e.size > 2 * 1024 * 1024) return null
            return VideoAu(e.copyOfRange(5, e.size), at).takeIf { it.picture || it.sps != null }
        }
    }
}
```

### 3.2 `MediaDemux`: replaces `PhotoAssembler.feed` routing in `XkGlasses.handle` (D1, D2)
```kotlin
/** Rebuilds 0x4A elements whatever the consumer, then routes them by media type byte. */
class MediaDemux(val onPhotoElement: (ByteArray) -> Unit, val onVideo: (VideoAu) -> Unit, val log: (String) -> Unit) {
    private val cur = java.io.ByteArrayOutputStream(); private var next = 0L; private var active = false
    var unknown = 0; private set
    fun feed(f: Xk.Frame) {
        val p = f.payload
        when (f.fragment) {
            0 -> complete(p)
            1, 2, 3 -> {
                if (p.size < 4) return drop("short")
                val idx = (p[0].toLong() and 0xFF) or ((p[1].toLong() and 0xFF) shl 8) or ((p[2].toLong() and 0xFF) shl 16) or ((p[3].toLong() and 0xFF) shl 24)
                if (f.fragment == 1) { cur.reset(); next = 0; active = true }
                if (!active || idx != next) return drop("order $idx/$next")
                next++; cur.write(p, 4, p.size - 4)
                if (cur.size() > 2 * 1024 * 1024) return drop("oversize")
                if (f.fragment == 3) { active = false; complete(cur.toByteArray()) }
            }
            5 -> complete(if (p.size > 4 && p[0].toInt() != 2 && p[4].toInt() == 2) p.copyOfRange(4, p.size) else p) // SDK divideType 5, prefix unknown
            else -> { if (unknown++ < 20) log("media divide ${f.divide} ${p.size} B") }
        }
    }
    private fun drop(why: String) { active = false; cur.reset(); log("media element dropped: $why") }
    private fun complete(e: ByteArray) { when (e.firstOrNull()?.toInt()) {
        0 -> onPhotoElement(e)
        2 -> VideoAu.fromElement(e, android.os.SystemClock.elapsedRealtimeNanos())?.let(onVideo) ?: log("bad video element ${e.size} B")
        else -> if (unknown++ < 20) log("media type ${e.firstOrNull()} ${e.size} B") } }
}
```
`PhotoAssembler` keeps only `store(raw)` and `jpeg()`. `handle()` becomes: `Xk.HEAD_MEDIA -> { if (f.isAck) return; demux.feed(f); if (f.fragment == 3) send(Xk.mediaAck(nextOrder()), false) }`. Fragment 3 still gets the 4A0009 ack for video too. Check on device that this does not throttle the stream (§4).

### 3.3 `XkGlasses` additions
```kotlin
enum class Preview { OFF, OPENING, ON, BUSY, UNSUPPORTED }
@Volatile var preview = Preview.OFF; private set
@Volatile var videoSink: ((VideoAu) -> Unit)? = null       // set only by VideoPipeline
@Volatile var previewStateSink: ((Boolean) -> Unit)? = null // glasses closed preview, link lost
private var previewAction = Xk.ACTION_WRITE                  // remember in Prefs once confirmed

fun openPreview(done: (String?) -> Unit) = io.execute {
    if (mediaOwner == MediaOwner.MIC) {   // §3.6
 preview = Preview.BUSY; return@execute done("Turn off the glasses mic first.") }
    preview = Preview.OPENING
    var r = request("5720", previewAction, byteArrayOf(1), 4_000)
    val err = r?.takeIf { it.replyType == 4 && it.data.isNotEmpty() }?.data?.get(0)?.toInt()
    if (err == 3 || err == 6) {                              // INVALID_PARAM / INVALID_CMD: try other action
        previewAction = if (previewAction == Xk.ACTION_WRITE) Xk.ACTION_EXECUTE else Xk.ACTION_WRITE
        r = request("5720", previewAction, byteArrayOf(1), 4_000)
    }
    val code = r?.takeIf { it.replyType == 4 && it.data.isNotEmpty() }?.data?.get(0)?.toInt() ?: 0
    preview = when (code) { 0 -> Preview.ON; 2 -> Preview.BUSY; 4 -> Preview.UNSUPPORTED; else -> Preview.OFF }
    request("5711", Xk.ACTION_READ, ByteArray(0), 2_000)?.let { addLog("preview state ${hex(it.data)}") }
    done(when (preview) { Preview.ON -> null; Preview.BUSY -> "The glasses are busy (mic or call in use)."
        Preview.UNSUPPORTED -> "These glasses don't stream video."; else -> "No answer (code $code)." })
}
fun closePreview() = io.execute { if (socket != null) request("5720", previewAction, byteArrayOf(0), 2_000); preview = Preview.OFF }
// in handle(): "5711", "57A0" pushes -> if (data[0]==0 && preview==ON) previewStateSink?.invoke(false)
// in closeSocket(): preview = Preview.OFF; previewStateSink?.invoke(false)
```
Video frames must not call `addLog` or `changed()` per frame. Count them, and log one line per second at most. Raise the reader buffer to 32 KB (D6).

### 3.4 `VideoPipeline`, `PreviewDecoder`, `PreviewRecorder`
```kotlin
class VideoPipeline(private val ctx: Context, private val album: String) {
    private val decT = HandlerThread("p3-dec").apply { start() }; private val dec = Handler(decT.looper)
    private val muxT = HandlerThread("p3-mux").apply { start() }; private val mux = Handler(muxT.looper)
    private val pending = java.util.concurrent.atomic.AtomicInteger()   // AUs queued to decoder
    @Volatile private var needIdr = true; @Volatile var lastAuNs = 0L; private set
    @Volatile var received = 0; @Volatile var dropped = 0
    private var codec: MediaCodec? = null; private var surface: Surface? = null
    private var sps: ByteArray? = null; private var pps: ByteArray? = null; var size = 320 to 240; private set
    private val info = MediaCodec.BufferInfo()
    var recorder: PreviewRecorder? = null                                   // touched on mux thread only

    fun offer(au: VideoAu) {                                                // reader thread, never blocks
        received++; lastAuNs = au.arrivalNs
        if (pending.get() >= 30) { dropped++; needIdr = true; return }      // ~1 s backlog cap
        pending.incrementAndGet(); dec.post { pending.decrementAndGet(); decode(au) }
        mux.post { recorder?.write(au) }                                    // recorder has its own cap
    }
    fun setSurface(s: Surface?) = dec.post { releaseCodec(); surface = s; needIdr = true }   // one producer only (F10)
    private fun decode(au: VideoAu) {
        au.sps?.let { s -> if (!s.contentEquals(sps)) { sps = s; H264.spsSize(s)?.let { size = it }; releaseCodec() } }
        au.pps?.let { pps = it }
        val s = surface ?: return; val sp = sps ?: return; val pp = pps ?: return
        if (needIdr && !au.key) return
        try {
            val c = codec ?: MediaCodec.createDecoderByType("video/avc").also { c ->
                val f = MediaFormat.createVideoFormat("video/avc", size.first, size.second).apply {
                    setByteBuffer("csd-0", ByteBuffer.wrap(VideoAu.START + sp)); setByteBuffer("csd-1", ByteBuffer.wrap(VideoAu.START + pp))
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 512 * 1024); setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    setInteger(MediaFormat.KEY_PRIORITY, 0) }
                runCatching { c.configure(f, s, null, 0) }.getOrElse { f.removeKey(MediaFormat.KEY_LOW_LATENCY); c.configure(f, s, null, 0) }
                c.start(); codec = c }
            needIdr = false; drain(c)
            val i = c.dequeueInputBuffer(5_000)
            if (i < 0) { dropped++; needIdr = true; return }
            val b = c.getInputBuffer(i)!!; if (b.capacity() < au.annexB.size) { dropped++; c.queueInputBuffer(i, 0, 0, 0, 0); return }
            b.clear(); b.put(au.annexB); c.queueInputBuffer(i, 0, au.annexB.size, au.arrivalNs / 1000, 0)
            drain(c); dec.removeCallbacksAndMessages(DRAIN); dec.postAtTime({ codec?.let(::drain) }, DRAIN, SystemClock.uptimeMillis() + 20)
        } catch (e: Exception) { releaseCodec(); needIdr = true }           // CodecException / IllegalState: rebuild on next IDR
    }
    private fun drain(c: MediaCodec) { while (true) { val o = c.dequeueOutputBuffer(info, 0)
        if (o >= 0) c.releaseOutputBuffer(o, true) else if (o != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) break } }
    private fun releaseCodec() { codec?.let { runCatching { it.stop() }; runCatching { it.release() } }; codec = null }
    fun startRecording(onDone: (Uri?, String?) -> Unit) = mux.post { if (recorder?.ended != false) recorder = PreviewRecorder(ctx, album, onDone) }
    fun stopRecording(reason: String? = null) = mux.post { recorder?.finish(reason); recorder = null }
    fun close() { stopRecording("Preview closed"); dec.post { releaseCodec(); surface = null }; decT.quitSafely(); muxT.quitSafely() }
    private companion object { val DRAIN = Any() }
}

/** Owns one MP4. Starts at the first IDR with SPS+PPS known; every call on the mux thread. */
class PreviewRecorder(private val ctx: Context, private val album: String, private val onDone: (Uri?, String?) -> Unit) {
    private val cr = ctx.contentResolver
    private var uri: Uri? = null; private var pfd: ParcelFileDescriptor? = null; private var muxer: MediaMuxer? = null
    private var track = -1; private var t0 = -1L; private var lastPts = -1L; private var samples = 0
    private var sps: ByteArray? = null; private var pps: ByteArray? = null; private var size = 320 to 240
    var ended = false; private set
    fun write(au: VideoAu) {
        if (ended) return
        au.sps?.let { if (muxer != null && !it.contentEquals(sps)) return finish("Video size changed; saved.") ; sps = it; H264.spsSize(it)?.let { s -> size = s } }
        au.pps?.let { if (muxer == null) pps = it }
        if (muxer == null) { if (!au.key || sps == null || pps == null) return; open() ?: return }
        if (!au.picture) return
        val pts = maxOf(lastPts + 1_000, (au.arrivalNs - t0) / 1000)          // monotonic, ≥1 ms apart
        val data = au.sample()
        val bi = MediaCodec.BufferInfo().apply { set(0, data.size, pts, if (au.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0) }
        try { muxer!!.writeSampleData(track, ByteBuffer.wrap(data), bi); lastPts = pts; samples++ }
        catch (e: Exception) { finish("Recording failed: ${e.javaClass.simpleName}") }
    }
    private fun open(): Unit? = runCatching {
        val name = "P3_VID_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val v = ContentValues().apply { put(MediaStore.Video.Media.DISPLAY_NAME, name); put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + album)
            put(MediaStore.Video.Media.IS_PENDING, 1); put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis()) }
        uri = cr.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v) ?: error("no row")
        pfd = cr.openFileDescriptor(uri!!, "rw") ?: error("no fd")
        muxer = MediaMuxer(pfd!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { m ->
            track = m.addTrack(MediaFormat.createVideoFormat("video/avc", size.first, size.second).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(VideoAu.START + sps!!)); setByteBuffer("csd-1", ByteBuffer.wrap(VideoAu.START + pps!!)) })
            m.start() }
        t0 = android.os.SystemClock.elapsedRealtimeNanos()
    }.onFailure { finish("Couldn't start recording: ${it.message}") }.getOrNull()
    fun finish(reason: String?) {
        if (ended) return; ended = true
        val m = muxer; muxer = null; var ok = false
        if (m != null) { ok = samples >= 2 && runCatching { m.stop() }.isSuccess; runCatching { m.release() } }
        runCatching { pfd?.close() }; pfd = null
        val u = uri; uri = null
        if (u != null) if (ok) cr.update(u, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0)
            put(MediaStore.Video.Media.DURATION, lastPts / 1000); put(MediaStore.Video.Media.WIDTH, size.first); put(MediaStore.Video.Media.HEIGHT, size.second) }, null, null)
            else runCatching { cr.delete(u, null, null) }
        onDone(if (ok) u else null, reason)
    }
}
```
Backlog limits: the decoder holds at most 30 AUs (about 1 s). On overflow it drops and resyncs at the next IDR, so no P-frame smear. The recorder gets every AU, because the mux thread only does sequential writes. Also watch `mux` lag: if `received - written > 300`, call `finish("Phone too slow; saved.")`. Run a 1 s watchdog in the activity: if `now - lastAuNs > 4 s` while recording, call `stopRecording("Video stopped; saved.")` (F8). Also clean stale rows on app start: query our own `IS_PENDING=1` rows under `Movies/<album>` with `MediaStore.QUERY_ARG_MATCH_PENDING = MATCH_INCLUDE` and delete them.

### 3.5 `PreviewActivity` (new, add to the manifest; no new permissions on API 30+)
A FrameLayout holds a `TextureView` that keeps 4:3 with `setTransform` letterboxing from `pipeline.size`. Buttons: **Record / Stop** (label "Stop 00:12"), **Snapshot** (`texture.bitmap` saved to `Pictures/<album>`), and a stats line `"%d fps · %d dropped"` updated each second. `onSurfaceTextureAvailable` calls `pipeline.setSurface(Surface(st))`. `onSurfaceTextureDestroyed` calls `pipeline.setSurface(null)` and returns true. Wire `glasses.videoSink = pipeline::offer` and `previewStateSink = { if (!it) runOnUiThread { pipeline.stopRecording("Glasses stopped the video; saved."); showClosed() } }`. Clear both sinks in onStop, before `closePreview()`. On the Glasses tab, replace D3 with **"Live view & record to phone"**, which opens this activity. Move 5780 to a small **"Record on glasses (if supported)"** button that reports code 2 as busy and 3/4 as not supported. Fix the text at GlassesScreen.kt:107.

### 3.6 SCO / mic arbiter
The glasses cannot run the camera and the HFP mic together (F9). Add one owner in `XkGlasses`: `enum class MediaOwner { NONE, PREVIEW, MIC }`. `openPreview` refuses while MIC owns it and offers "Stop mic and start live view". That path calls `AudioManager.clearCommunicationDevice()`, waits for the communication-device change callback (≤2 s), then sends 5720. Starting the mic (specialist for audio) must first `stopRecording`, then `closePreview`. An incoming phone call (HFP SCO from the system) shows up as a pushed state of 0 or a frame stall, and is handled by §3.4. Recordings are **silent**. Tell the owner: "Video only — the glasses can't send sound at the same time." Do not record the phone mic silently in its place.

## 4. On-device checks (Samsung, P3 glasses, LensMoo closed)
1. Open Live view. The log must show `→ 5720 01` and a reply code. If it shows 3/6, the other action code is tried automatically. Record which action worked and the 5711/57A0 replies.
2. The first frame should appear within 3 s, and the stats line should read about 25-30 fps with 0 dropped while you hold still. If there is no picture but the log shows `media divide`/`media type` lines, share the log: it shows the real transport (F5).
3. Record 30 s, stop, and open it from Gallery > Movies/<album>. Duration should be about 30 s, size 320x240 (or whatever the SPS says), and motion smooth.
4. Walk 5 m away while recording. The dropped count rises, the picture freezes and resumes clean at an IDR with no grey smear, and the file still plays.
5. Turn the glasses off mid-recording. Within about 5 s you should get "saved", and the file plays.
6. Start music over A2DP, then open the preview. Note fps and drops (shared ACL airtime). Call the phone during the preview: the preview must close cleanly and the recording must save.
7. Take a photo (57B0) while the preview runs. The photo must save and the video must continue (D2 routing).
8. Leave the screen (Home) while the preview is on. The log must show `→ 5720 00`, and photos must download at normal speed afterwards.

## 5. Confidence summary
Node 5720/5711 and the H.264 element format: **high**. The 0x4A fragment transport for video: **medium**, so the demux also accepts divide 5 and logs unknowns. The 5720 action code: **low**, hence the auto-fallback. Whether 57A0 is preview or recording state: **unresolved**, so frames are the ground truth. Decoder/muxer/MediaStore recipe: **high** (validated in LM 1.1.0). SCO exclusivity: **high** on the LM unit, unverified on the P3.
