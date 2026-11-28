package com.zdycomp.p3companion

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 4.0: the Android side of [ImageEnhance]: decodes a photo, runs the pipeline off the main thread and
 * saves the result as a separate "-enhanced" copy in the same album (the original is never changed),
 * with a JPEG comment that says what was done, so the gallery's details can say "Enhanced 1280×960
 * from 640×480".
 */
object PhotoEnhancer {

    private val work = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Enhances a bitmap (any size) and returns a new one; [bmp] is left as it is. */
    fun enhance(bmp: Bitmap, scale: Int, strength: Float): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val out = ImageEnhance.enhance(px, w, h, scale, strength)
        val b = Bitmap.createBitmap(out.w, out.h, Bitmap.Config.ARGB_8888)
        b.setPixels(out.px, 0, out.w, 0, 0, out.w, out.h)
        return b
    }

    /** What the comment says: the honest line the gallery shows. */
    fun note(w: Int, h: Int, srcW: Int, srcH: Int, strength: Float): String =
        ImageEnhance.label(w, h, srcW, srcH) + String.format(Locale.US, " on the phone (denoise, Lanczos-3 upscale, adaptive sharpen; strength %d%%)", (strength * 100).toInt())

    /** JPEG bytes of [bmp] with the comment [note]. */
    fun jpeg(bmp: Bitmap, note: String?, quality: Int = 95): ByteArray {
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        val bytes = bos.toByteArray()
        return if (note != null) JpegCom.insert(bytes, note) else bytes
    }

    /** Saves [bytes] as a new JPEG in Pictures/[album]. */
    fun save(resolver: ContentResolver, bytes: ByteArray, name: String, album: String, w: Int, h: Int, takenAt: Long = System.currentTimeMillis()): Uri {
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + album)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.DATE_TAKEN, takenAt)
        }
        val u = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v)
            ?: throw IllegalStateException("couldn't create the gallery file")
        try {
            resolver.openOutputStream(u, "w")!!.use { it.write(bytes) }
            resolver.update(u, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(MediaStore.MediaColumns.WIDTH, w)
                put(MediaStore.MediaColumns.HEIGHT, h)
            }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(u, null, null) }
            throw e
        }
        return u
    }

    /**
     * Makes the "-enhanced" copy of a saved Bluetooth photo in the background. [done] (main thread) gets
     * the new picture (or null) and a line for the connection log.
     */
    fun enhanceCopy(context: Context, uri: Uri, done: (Uri?, String) -> Unit) {
        val app = context.applicationContext
        val prefs = Prefs(app)
        val scale = prefs.enhanceScale
        val strength = prefs.enhanceStrength
        val album = prefs.albumName
        work.execute {
            val t0 = SystemClock.elapsedRealtime()
            val res = runCatching {
                val cr = app.contentResolver
                val bytes = cr.openInputStream(uri)!!.use { it.readBytes() }
                val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                var src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: error("not a picture")
                val rot = runCatching {
                    when (ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270
                        else -> 0
                    }
                }.getOrDefault(0)
                if (rot != 0) src = Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
                val sw = src.width
                val sh = src.height
                val out = enhance(src, scale, strength)
                src.recycle()
                val name = displayName(cr, uri)?.substringBeforeLast('.')?.let { "$it-enhanced.jpg" }
                    ?: ("P3_IMG_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date()) + "-enhanced.jpg")
                val jpeg = jpeg(out, note(out.width, out.height, sw, sh, strength))
                val saved = save(cr, jpeg, name, album, out.width, out.height)
                val line = "Photo enhanced: ${out.width}×${out.height} from ${sw}×$sh in ${SystemClock.elapsedRealtime() - t0} ms, ${jpeg.size / 1024} KB, saved as $name (the original is kept)"
                out.recycle()
                saved to line
            }
            main.post {
                res.onSuccess { (u, line) -> done(u, line) }
                    .onFailure { e -> done(null, "Photo enhancement failed: ${e.message ?: e.javaClass.simpleName} (the original is saved)") }
            }
        }
    }

    private fun displayName(cr: ContentResolver, uri: Uri): String? = runCatching {
        cr.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()
}
