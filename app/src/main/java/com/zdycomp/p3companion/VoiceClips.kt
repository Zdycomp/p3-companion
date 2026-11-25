package com.zdycomp.p3companion

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * 3.7: "Play back what the glasses heard". The last 8 s of the glasses' audio from each route (call
 * audio and data link) is kept as a WAV in the app's cache (cache/voice/), played through the phone's
 * own speaker (not the glasses, which are the media output while connected) and shared through
 * [ClipProvider]. Main thread.
 */
object VoiceClips {
    const val SCO = "glasses-call-audio.wav"
    const val SPP = "glasses-data-link.wav"
    const val MAX_SAMPLES = MicCapture.RATE * 8

    private var player: MediaPlayer? = null

    fun dir(context: Context): File = File(context.cacheDir, "voice").apply { mkdirs() }

    /** Saves the last 8 s of [samples] (16 kHz mono) as [name]. Returns the file, or null if there was nothing. */
    fun save(context: Context, name: String, samples: ShortArray): File? {
        if (samples.isEmpty()) return null
        val last = if (samples.size > MAX_SAMPLES) samples.copyOfRange(samples.size - MAX_SAMPLES, samples.size) else samples
        return runCatching { File(dir(context), name).apply { writeBytes(Wav.pcm16(last, MicCapture.RATE)) } }.getOrNull()
    }

    /** The saved clips, data link first. */
    fun saved(context: Context): List<File> = listOf(SPP, SCO).map { File(dir(context), it) }.filter { it.isFile && it.length() > Wav.HEADER }

    fun label(f: File): String = when (f.name) {
        SPP -> "glasses mic (data link)"
        SCO -> "glasses mic (call audio)"
        else -> f.name
    }

    fun seconds(f: File): Double = (f.length() - Wav.HEADER).coerceAtLeast(0) / 2.0 / MicCapture.RATE

    /** Plays [files] one after the other on the phone's speaker; [onEach] before each, [done] at the end (or with an error). */
    fun play(context: Context, files: List<File>, onEach: (File) -> Unit, done: (String?) -> Unit) {
        stop()
        val f = files.firstOrNull() ?: run { done(null); return }
        val app = context.applicationContext
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(f.absolutePath)
            speaker(app)?.let { mp.setPreferredDevice(it) }
            mp.setOnCompletionListener {
                it.release()
                if (player === it) player = null
                play(app, files.drop(1), onEach, done)
            }
            mp.setOnErrorListener { p, what, _ ->
                p.release()
                if (player === p) player = null
                done("couldn't play ${f.name} (error $what)")
                true
            }
            mp.prepare()
            onEach(f)
            mp.start()
        } catch (e: Exception) {
            runCatching { mp.release() }
            player = null
            done("couldn't play ${f.name} (${e.message ?: e.javaClass.simpleName})")
        }
    }

    fun stop() {
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
    }

    private fun speaker(app: Context): AudioDeviceInfo? =
        app.getSystemService(AudioManager::class.java)?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

    /** A share sheet with the saved clips (audio/wav). False when there are none. */
    fun share(context: Context): Boolean {
        val files = saved(context)
        if (files.isEmpty()) return false
        val uris = ArrayList(files.map { ClipProvider.uri(context, it.name) })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "audio/wav"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, "What the glasses heard")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching {
            context.startActivity(Intent.createChooser(send, "Share what the glasses heard").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.isSuccess
    }
}

/**
 * 3.7: hands the voice clips in cache/voice/ to a share target, read-only (no library FileProvider:
 * the app uses only the framework). Only plain `name.wav` files in that folder are served.
 */
class ClipProvider : ContentProvider() {
    companion object {
        fun authority(context: Context) = context.packageName + ".clips"
        fun uri(context: Context, name: String): Uri = Uri.parse("content://${authority(context)}/$name")
        private val NAME = Regex("^[a-z0-9-]{1,64}\\.wav$")

        /** True for a file name this provider will serve. */
        fun nameOk(name: String?): Boolean = name != null && NAME.matches(name)
    }

    override fun onCreate(): Boolean = true

    private fun file(uri: Uri): File? {
        val ctx = context ?: return null
        val name = uri.lastPathSegment
        if (!nameOk(name) || uri.pathSegments.size != 1) return null
        return File(VoiceClips.dir(ctx), name!!).takeIf { it.isFile }
    }

    override fun getType(uri: Uri): String? = if (file(uri) != null) "audio/wav" else null

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("read only")
        val f = file(uri) ?: throw java.io.FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val f = file(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        c.addRow(cols.map { when (it) { OpenableColumns.DISPLAY_NAME -> f.name; OpenableColumns.SIZE -> f.length(); else -> null } }.toTypedArray())
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
