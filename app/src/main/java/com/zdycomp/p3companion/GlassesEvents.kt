package com.zdycomp.p3companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.MediaActionSound
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.service.quicksettings.TileService
import java.lang.ref.WeakReference

/**
 * Phone-side reactions to what the glasses do, for the whole process (app open or not): photo-saved
 * notification and vibration, shutter sound, low-battery alert, Quick Settings tile refresh. It only
 * listens to [XkGlasses]; nothing here sends anything to the glasses except the existing battery
 * refresh, every 10 minutes while connected and idle, when the low-battery alert is on.
 */
object GlassesEvents {

    const val CHANNEL_PHOTOS = "photos"
    const val CHANNEL_BATTERY = "battery"
    private const val NOTE_BATTERY = 21
    private const val BATTERY_READ_MS = 10 * 60_000L

    @Volatile private var installed = false
    private lateinit var app: Context
    private lateinit var prefs: Prefs
    private lateinit var g: XkGlasses
    private val main = Handler(Looper.getMainLooper())
    private var sound: MediaActionSound? = null

    /** The activity while it's on screen (set in onStart, cleared in onStop). */
    @Volatile var foreground: WeakReference<MainActivity>? = null

    private var armed = true
    private var lastState: XkGlasses.State? = null
    private var lastBattery: Int? = null

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            app = context.applicationContext
            prefs = Prefs(app)
            g = XkGlasses.get(app, prefs)
            createChannels()
            g.onShutter = { if (prefs.shutterSound) shutterSound() }
            g.onPhotoSaved = { uri, fromButton -> onPhoto(uri, fromButton) }
            // 4.0: flashlight changes go to the connection log too.
            TorchController.get(app).log = { g.addLog(it) }
            g.addListener { onChanged() }
            main.postDelayed(batteryRead, BATTERY_READ_MS)
            installed = true
        }
    }

    private fun createChannels() {
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_PHOTOS, "Photos from your glasses", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a photo from the glasses is saved while the app isn't open"
            })
            nm.createNotificationChannel(NotificationChannel(CHANNEL_BATTERY, "Glasses battery", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Low battery alert for your glasses"
            })
        }
    }

    private fun activity(): MainActivity? = foreground?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    // ---------------- photos ----------------

    private fun onPhoto(uri: Uri, fromButton: Boolean) {
        if (prefs.vibrateOnPhoto) vibrate()
        // 4.0: a sharper "-enhanced" copy next to the photo (the original stays as the glasses sent it).
        if (prefs.enhancePhotos) PhotoEnhancer.enhanceCopy(app, uri) { _, line ->
            g.addLog(line)
            activity()?.refreshGalleryIfShown()
        }
        val a = activity()
        if (a != null) a.onPhotoSaved(uri, fromButton)
        else if (prefs.photoNote != Prefs.PhotoNote.OFF) photoNotification(uri)
    }

    private fun vibrate() {
        runCatching {
            @Suppress("DEPRECATION")
            val v = app.getSystemService(Vibrator::class.java) ?: return
            if (v.hasVibrator()) v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 40, 80, 40), -1))
        }
    }

    private fun shutterSound() {
        runCatching {
            val s = sound ?: MediaActionSound().also { it.load(MediaActionSound.SHUTTER_CLICK); sound = it }
            s.play(MediaActionSound.SHUTTER_CLICK)
        }
    }

    private fun photoNotification(uri: Uri) {
        val withPicture = prefs.photoNote == Prefs.PhotoNote.PICTURE
        Thread {
            val bmp = if (withPicture) ImagePrep.thumbnail(app.contentResolver, uri, 512) else null
            val id = 1000 + (uri.lastPathSegment?.toIntOrNull() ?: uri.hashCode()).let { if (it < 0) -it else it } % 100_000
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            val share = Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share photo"
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val b = Notification.Builder(app, CHANNEL_PHOTOS)
                .setSmallIcon(R.drawable.ic_photo_camera)
                .setContentTitle("Photo saved")
                .setContentText("From your glasses, in the \"${prefs.albumName}\" album")
                .setContentIntent(PendingIntent.getActivity(app, id, view, flags))
                .setAutoCancel(true)
                .addAction(Notification.Action.Builder(Icon.createWithResource(app, R.drawable.ic_share), "Share",
                    PendingIntent.getActivity(app, id + 1, share, flags)).build())
            if (bmp != null) {
                b.setLargeIcon(bmp)
                b.setStyle(Notification.BigPictureStyle().bigPicture(bmp).bigLargeIcon(null as Icon?))
            }
            runCatching { app.getSystemService(NotificationManager::class.java)?.notify(id, b.build()) }
        }.start()
    }

    // ---------------- battery, tile, connection feedback ----------------

    private fun onChanged() {
        val state = g.state
        val battery = g.battery
        val prev = lastState
        if (state != prev || battery != lastBattery) {
            lastState = state
            lastBattery = battery
            runCatching { TileService.requestListeningState(app, ComponentName(app, PhotoTileService::class.java)) }
        }
        if (prev == XkGlasses.State.CONNECTING && state != prev) {
            activity()?.connectionFeedback(state == XkGlasses.State.READY)
        }
        checkBattery(battery, g.charging == true)
    }

    private fun checkBattery(level: Int?, charging: Boolean) {
        val at = prefs.lowBatteryAt
        if (level == null || at <= 0) return
        if (charging || level >= at + 5) {
            if (!armed) runCatching { app.getSystemService(NotificationManager::class.java)?.cancel(NOTE_BATTERY) }
            armed = true
            return
        }
        if (level <= at && armed) {
            armed = false
            val open = PendingIntent.getActivity(
                app, NOTE_BATTERY, Intent(app, MainActivity::class.java).setAction(MainActivity.ACTION_GLASSES)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val note = Notification.Builder(app, CHANNEL_BATTERY)
                .setSmallIcon(R.drawable.ic_battery)
                .setContentTitle("Glasses battery low: $level%")
                .setContentText("Charge your glasses soon.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            runCatching { app.getSystemService(NotificationManager::class.java)?.notify(NOTE_BATTERY, note) }
        }
    }

    /** A battery read every 10 minutes while connected, idle and not streaming, for the low-battery alert. */
    private val batteryRead = object : Runnable {
        override fun run() {
            if (prefs.lowBatteryAt > 0 && g.state == XkGlasses.State.READY && g.preview == XkGlasses.Preview.OFF) g.refresh()
            main.postDelayed(this, BATTERY_READ_MS)
        }
    }
}
