package com.zdycomp.p3companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records audio to the gallery's "Music/<album>" folder as .m4a, using the glasses' microphone when
 * they're connected as the phone's headset, otherwise the phone's own. Keeps recording with the
 * screen off through a small foreground service. One instance for the whole app; main thread only.
 */
class VoiceRecorder private constructor(context: Context, private val prefs: Prefs) {

    private val app = context.applicationContext
    private val route = AudioRoute.get(app, prefs)
    private val listeners = mutableSetOf<() -> Unit>()

    var recording = false; private set
    var starting = false; private set
    var throughGlasses = false; private set
    var lastSaved: Uri? = null; private set
    var lastSavedName: String? = null; private set
    var error: String? = null; private set
    private var startedAt = 0L

    private var recorder: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var target: Uri? = null
    private var targetName: String? = null
    private var holdingRoute = false

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() = listeners.toList().forEach { it() }

    fun elapsedMs(): Long = if (recording) SystemClock.elapsedRealtime() - startedAt else 0L

    /** Callers must hold RECORD_AUDIO. */
    fun start() {
        if (recording || starting) return
        starting = true
        error = null
        changed()
        val go = { via: Boolean ->
            starting = false
            try {
                begin(via)
            } catch (e: Exception) {
                cleanUp(deleteFile = true)
                if (holdingRoute) { holdingRoute = false; route.release() }
                error = "Couldn't start recording: ${e.message ?: "the microphone is busy"}."
            }
            changed()
        }
        if (route.willUseGlasses()) {
            holdingRoute = true
            route.acquire(go)
        } else {
            go(false)
        }
    }

    private fun begin(viaGlasses: Boolean) {
        val name = "P3_REC_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".m4a"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/" + prefs.albumName)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis())
        }
        val resolver = app.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IllegalStateException("couldn't create the file")
        target = uri
        targetName = name
        val fd = resolver.openFileDescriptor(uri, "w") ?: throw IllegalStateException("couldn't open the file")
        pfd = fd

        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(app) else @Suppress("DEPRECATION") MediaRecorder()
        recorder = r
        r.setAudioSource(if (viaGlasses) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioChannels(1)
        r.setAudioSamplingRate(if (viaGlasses) 16_000 else 44_100)
        r.setAudioEncodingBitRate(if (viaGlasses) 48_000 else 128_000)
        r.setOutputFile(fd.fileDescriptor)
        if (viaGlasses) route.glassesInput()?.let { r.setPreferredDevice(it) }
        r.setOnErrorListener { _, what, _ ->
            error = "Recording stopped because of a microphone problem ($what)."
            stop()
        }
        r.prepare()
        r.start()
        recording = true
        throughGlasses = viaGlasses
        startedAt = SystemClock.elapsedRealtime()
        RecorderService.start(app)
    }

    /** Stops and saves. Safe to call when not recording. */
    fun stop() {
        if (!recording) return
        val ok = runCatching { recorder?.stop() }.isSuccess
        recording = false
        val uri = target
        val name = targetName
        cleanUp(deleteFile = !ok)
        if (ok && uri != null) {
            runCatching {
                app.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            lastSaved = uri
            lastSavedName = name
        } else if (!ok && error == null) {
            error = "That recording was too short to save."
        }
        if (holdingRoute) { holdingRoute = false; route.release() }
        throughGlasses = false
        RecorderService.stop(app)
        changed()
    }

    private fun cleanUp(deleteFile: Boolean) {
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { pfd?.close() }
        pfd = null
        if (deleteFile) target?.let { u -> runCatching { app.contentResolver.delete(u, null, null) } }
        target = null
        targetName = null
    }

    companion object {
        @Volatile private var instance: VoiceRecorder? = null

        fun get(context: Context, prefs: Prefs): VoiceRecorder =
            instance ?: synchronized(this) { instance ?: VoiceRecorder(context, prefs).also { instance = it } }

        fun clock(ms: Long): String {
            val s = ms / 1000
            return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
            else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
        }
    }
}

/** Keeps a recording going while the screen is off, with a notification that can stop it. */
class RecorderService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rec = VoiceRecorder.get(this, Prefs(this))
        // Always go foreground first: Android requires it after startForegroundService.
        goForeground(rec.elapsedMs())
        if (intent?.action == ACTION_STOP) rec.stop()
        if (!rec.recording) stopSelf()
        return START_NOT_STICKY
    }

    private fun goForeground(elapsedMs: Long) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Voice recording", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows while P3 Companion is recording audio"
            }
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Recording audio")
            .setContentText("Tap Stop and save when you're done.")
            .setOngoing(true)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis() - elapsedMs)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Stop and save", stop
                ).build()
            )
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        } catch (e: Exception) {
            // Not allowed right now (for example started from the background); recording still
            // continues while the app is open.
        }
    }

    companion object {
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 7
        const val ACTION_STOP = "com.zdycomp.p3companion.STOP_RECORDING"

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, RecorderService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RecorderService::class.java)) }
        }
    }
}
