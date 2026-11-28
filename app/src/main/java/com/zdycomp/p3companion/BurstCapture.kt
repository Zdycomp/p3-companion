package com.zdycomp.p3companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 4.1: the Android side of [BurstSR]. "Burst photo" takes [Prefs.burstCount] Bluetooth photos one after
 * another (about 6 s each; hold still), then aligns and combines them off the main thread; "Sharp
 * snapshot" does the same at once with the last 16 live-view pictures. Each result is a separate
 * "-burst" JPEG next to the originals, which are kept, with a comment that says exactly what was done
 * (Gallery › Details shows it). The 4.0 single-photo enhance is unchanged.
 */
object BurstCapture {
    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "p3-burst").apply { isDaemon = true } }

    @Volatile var running = false; private set
    /** "Burst photo 2 of 4…", for the screen; null when idle. */
    @Volatile var status: String? = null; private set

    /**
     * Takes the burst and combines it. [progress] and [done] run on the main thread; [done] gets the new
     * picture (or null) and a line to show or say.
     */
    fun photoBurst(context: Context, glasses: XkGlasses, progress: (String) -> Unit, done: (Uri?, String) -> Unit) {
        val app = context.applicationContext
        val prefs = Prefs(app)
        if (running) { done(null, "A burst is already running."); return }
        if (glasses.state != XkGlasses.State.READY) { done(null, "Connect the glasses first."); return }
        val batt = glasses.battery
        if (batt != null && batt < RoutePolicy.LOW_BATTERY && glasses.charging != true) {
            done(null, "The glasses' battery is at $batt%, so no burst; a single photo still works."); return
        }
        val n = prefs.burstCount
        running = true
        val shots = ArrayList<Uri>()
        var fails = 0
        val t0 = SystemClock.elapsedRealtime()
        fun step(text: String) { status = text; progress(text) }
        fun next() {
            if (shots.size >= n || fails >= 2) {
                if (shots.size < 2) { running = false; status = null; done(null, "The glasses sent only ${shots.size} of $n photos, so there's nothing to combine."); return }
                step("Combining ${shots.size} photos…")
                glasses.addLog("Burst photo: ${shots.size} photos in ${(SystemClock.elapsedRealtime() - t0) / 1000} s; combining")
                combine(app, prefs, shots.map { decodePhoto(app, it) }, BurstSR.Source.JPEG, live = false, rotation = 0) { uri, line ->
                    running = false; status = null
                    glasses.addLog(line)
                    done(uri, if (uri != null) "Burst photo saved: ${line.substringAfter("saved: ", line)}" else line)
                }
                return
            }
            step("Burst photo ${shots.size + 1} of $n… hold still")
            glasses.takePhoto(onDone = { uri ->
                if (uri != null) shots += uri else fails++
                main.post { next() }
            }, announce = false)
        }
        next()
    }

    /** Combines live-view frames (ARGB, all [w]×[h]) right away. */
    fun liveBurst(context: Context, frames: List<IntArray>, w: Int, h: Int, rotation: Int, done: (Uri?, String) -> Unit) {
        val app = context.applicationContext
        if (running) { done(null, "A burst is already running."); return }
        if (frames.size < 2) { done(null, "Live view hasn't shown enough pictures yet."); return }
        running = true
        status = "Sharp snapshot: combining ${frames.size} live pictures…"
        combine(app, Prefs(app), frames.map { Frame(it, w, h) }, BurstSR.Source.H264, live = true, rotation = rotation) { uri, line ->
            running = false; status = null
            done(uri, line)
        }
    }

    class Frame(val px: IntArray, val w: Int, val h: Int)

    private fun decodePhoto(app: Context, uri: Uri): Frame? = runCatching {
        val bytes = app.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
            ?: return@runCatching null
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        Frame(px, bmp.width, bmp.height).also { bmp.recycle() }
    }.getOrNull()

    private fun combine(app: Context, prefs: Prefs, raw: List<Frame?>, source: BurstSR.Source, live: Boolean, rotation: Int, done: (Uri?, String) -> Unit) {
        val scale = prefs.enhanceScale
        val album = prefs.albumName
        work.execute {
            val t0 = SystemClock.elapsedRealtime()
            val res = runCatching {
                val first = raw.firstOrNull { it != null } ?: error("no picture could be read")
                // Only frames of the same size combine (the glasses always send one size; this is a guard).
                val frames = raw.filterNotNull().filter { it.w == first.w && it.h == first.h }
                val sr = BurstSR.superResolve(frames.map { it.px }, first.w, first.h, source, BurstSR.Params.of(source, scale))
                var bmp = Bitmap.createBitmap(sr.w, sr.h, Bitmap.Config.ARGB_8888)
                bmp.setPixels(sr.px, 0, sr.w, 0, 0, sr.w, sr.h)
                if (rotation != 0) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
                val label = BurstSR.label(sr.used, sr.w, sr.h, first.w, first.h, live) +
                    String.format(Locale.US, " on the phone (aligned and combined, effective frames %.1f)", sr.meanNeff)
                val jpeg = PhotoEnhancer.jpeg(bmp, label)
                val name = "P3_" + (if (live) "LIVE" else "IMG") + "_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "-burst${sr.used}.jpg"
                val uri = PhotoEnhancer.save(app.contentResolver, jpeg, name, album, bmp.width, bmp.height)
                val line = (if (live) "Sharp snapshot" else "Burst photo") + " saved: ${bmp.width}×${bmp.height} from ${frames.size} × ${first.w}×${first.h} in " +
                    "${SystemClock.elapsedRealtime() - t0} ms, ${jpeg.size / 1024} KB, $name (originals kept). ${sr.report}"
                bmp.recycle()
                uri to line
            }
            main.post {
                res.onSuccess { (u, line) -> done(u, line) }
                    .onFailure { e -> done(null, (if (live) "Sharp snapshot" else "Burst photo") + " failed: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }
}

/**
 * 4.1: the last [capacity] live-view pictures as ARGB, kept while live view shows, so a sharp snapshot
 * has zero shutter lag. Filled on the main thread from the TextureView ([add]).
 */
class LiveRing(val capacity: Int = 16) {
    private val frames = ArrayList<IntArray>(capacity)
    private var next = 0
    var w = 0; private set
    var h = 0; private set
    private var bmp: Bitmap? = null

    /** Copies the current picture of [tv] (at [vw]×[vh]) into the ring. */
    fun add(tv: android.view.TextureView, vw: Int, vh: Int) {
        if (vw <= 0 || vh <= 0 || !tv.isAvailable) return
        if (vw != w || vh != h) { synchronized(frames) { frames.clear(); next = 0 }; w = vw; h = vh; bmp?.recycle(); bmp = null }
        val b = bmp ?: Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888).also { bmp = it }
        runCatching { tv.getBitmap(b) } .getOrNull() ?: return
        synchronized(frames) {
            val px = if (frames.size < capacity) IntArray(vw * vh).also { frames += it } else frames[next]
            b.getPixels(px, 0, vw, 0, 0, vw, vh)
            next = (next + 1) % capacity
        }
    }

    /** Oldest first, copied. */
    fun snapshot(): List<IntArray> = synchronized(frames) {
        if (frames.size < capacity) frames.map { it.copyOf() } else (0 until capacity).map { frames[(next + it) % capacity].copyOf() }
    }

    val size: Int get() = synchronized(frames) { frames.size }

    fun clear() { synchronized(frames) { frames.clear(); next = 0 }; bmp?.recycle(); bmp = null }
}
