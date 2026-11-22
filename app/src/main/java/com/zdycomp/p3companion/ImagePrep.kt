package com.zdycomp.p3companion

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream

/** Shrinks photos before they're sent to the AI, and makes small previews for the screen. */
object ImagePrep {

    /** A JPEG no larger than [maxSide] pixels on its longest side, turned the right way up. */
    fun jpegForAi(resolver: ContentResolver, uri: Uri, maxSide: Int = 1280): ByteArray {
        val bmp = decode(resolver, uri, maxSide) ?: throw AiException("Couldn't open that photo. Pick another one.")
        try {
            val out = ByteArrayOutputStream()
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)) throw AiException("Couldn't prepare that photo.")
            return out.toByteArray()
        } finally {
            bmp.recycle()
        }
    }

    fun thumbnail(resolver: ContentResolver, uri: Uri, maxSide: Int = 640): Bitmap? =
        runCatching { decode(resolver, uri, maxSide) }.getOrNull()

    private fun decode(resolver: ContentResolver, uri: Uri, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (maxOf(w, h) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

        val rotation = runCatching {
            resolver.openInputStream(uri)?.use { s ->
                when (ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)

        val longest = maxOf(bmp.width, bmp.height)
        val scale = if (longest > maxSide) maxSide.toFloat() / longest else 1f
        if (scale < 1f || rotation != 0f) {
            val m = Matrix()
            if (scale < 1f) m.postScale(scale, scale)
            if (rotation != 0f) m.postRotate(rotation)
            val turned = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (turned !== bmp) bmp.recycle()
            bmp = turned
        }
        return bmp
    }
}
