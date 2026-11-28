package com.zdycomp.p3companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * 4.0: the phone's flashlight as a real, lasting light: the back camera's torch
 * ([CameraManager.setTorchMode]) stays on until it's turned off (the big button on the Glasses tab,
 * the Quick Settings tile, the notification's Turn off, "flashlight off", or the optional auto-off
 * timer). It never touches the glasses: no flashlight command takes a photo or flashes anything.
 *
 * [TorchLogic] is the pure state machine (JVM-tested); [TorchController] does the Android side.
 */
class TorchLogic(var autoOffMs: Long = 0) {

    enum class State { OFF, ON, BUSY, UNAVAILABLE, NO_FLASH }
    enum class Fail { CAMERA_IN_USE, UNAVAILABLE, NO_FLASH, ERROR }

    var state = State.OFF; private set
    /** When it went on (elapsedRealtime), or 0. */
    var onSince = 0L; private set
    /** Why the last change didn't happen, for people. */
    var problem: String? = null; private set

    val on: Boolean get() = state == State.ON

    /** What a command asks for: "true", "false" or anything else (toggle). Null: nothing to do (no flashlight). */
    fun target(onoff: String?): Boolean? {
        if (state == State.NO_FLASH) return null
        return when (onoff) { "true" -> true; "false" -> false; else -> !on }
    }

    /** The torch really changed (our own call succeeded, or Android's torch callback said so). */
    fun changed(enabled: Boolean, now: Long) {
        if (enabled) {
            if (state != State.ON) onSince = now
            state = State.ON
        } else {
            state = State.OFF
            onSince = 0
        }
        problem = null
    }

    /** Turning it on or off failed. */
    fun failed(why: Fail) {
        problem = when (why) {
            Fail.CAMERA_IN_USE -> "The flashlight can't turn on while another app is using the camera."
            Fail.UNAVAILABLE -> "The flashlight isn't available right now."
            Fail.NO_FLASH -> "This phone has no flashlight."
            Fail.ERROR -> "The flashlight didn't respond."
        }
        state = when (why) {
            Fail.CAMERA_IN_USE -> State.BUSY
            Fail.NO_FLASH -> State.NO_FLASH
            Fail.UNAVAILABLE -> State.UNAVAILABLE
            Fail.ERROR -> if (state == State.ON) State.ON else State.OFF
        }
        if (state != State.ON) onSince = 0
    }

    /** Android says the torch can't be used now (the camera was opened by an app). */
    fun unavailable() {
        if (state == State.NO_FLASH) return
        state = State.BUSY
        onSince = 0
    }

    /** When the auto-off timer ends it, or null (off by default, or not on). */
    fun deadline(): Long? = if (on && autoOffMs > 0) onSince + autoOffMs else null

    /** True when the auto-off time has come. */
    fun due(now: Long): Boolean = deadline()?.let { now >= it } == true

    /** What to say after a command. */
    fun spoken(wanted: Boolean): String = problem ?: if (wanted) {
        "Flashlight on." + if (autoOffMs > 0) " It turns off in ${minutes(autoOffMs)}." else ""
    } else "Flashlight off."

    companion object {
        /** The auto-off choices in Settings: off, 1, 5, 15, 30 minutes. */
        val AUTO_OFF_MIN = listOf(0, 1, 5, 15, 30)

        fun minutes(ms: Long): String {
            val m = (ms / 60_000).toInt()
            return if (m == 1) "1 minute" else "$m minutes"
        }
    }
}

/** The one flashlight for the whole app. Call on the main thread. */
class TorchController private constructor(context: Context) {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val cm: CameraManager? = app.getSystemService(CameraManager::class.java)
    val logic = TorchLogic()
    private val listeners = mutableSetOf<() -> Unit>()
    var log: ((String) -> Unit)? = null

    /** The back camera with a flash (any camera with one if there's no back one), or null. */
    val cameraId: String? by lazy {
        runCatching {
            val c = cm ?: return@runCatching null
            val ids = c.cameraIdList.filter { c.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ids.firstOrNull { c.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK } ?: ids.firstOrNull()
        }.getOrNull()
    }

    private val callback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(id: String, enabled: Boolean) {
            if (id != cameraId) return
            val was = logic.on
            logic.changed(enabled, SystemClock.elapsedRealtime())
            if (was != enabled) afterChange()
        }
        override fun onTorchModeUnavailable(id: String) {
            if (id != cameraId) return
            val was = logic.on
            logic.unavailable()
            if (was) afterChange()
        }
    }

    init {
        if (cameraId == null) logic.failed(TorchLogic.Fail.NO_FLASH)
        else runCatching { cm?.registerTorchCallback(callback, main) }
    }

    private val autoOff = Runnable {
        if (logic.due(SystemClock.elapsedRealtime())) { log?.invoke("Flashlight: auto-off after ${TorchLogic.minutes(logic.autoOffMs)}"); set("false") }
        else schedule()
    }

    private fun schedule() {
        main.removeCallbacks(autoOff)
        logic.deadline()?.let { main.postDelayed(autoOff, (it - SystemClock.elapsedRealtime()).coerceAtLeast(250)) }
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    val on: Boolean get() = logic.on
    val available: Boolean get() = cameraId != null

    /**
     * Turns the torch on, off or over ([onoff] "true", "false", else toggle) and returns what to say.
     * Only the phone's torch: the glasses are never asked for anything here.
     */
    fun set(onoff: String?): String {
        val prefs = Prefs(app)
        logic.autoOffMs = prefs.torchAutoOffMin * 60_000L
        val want = logic.target(onoff) ?: return logic.spoken(false)
        val id = cameraId ?: run { logic.failed(TorchLogic.Fail.NO_FLASH); return logic.spoken(want) }
        try {
            cm!!.setTorchMode(id, want)
            logic.changed(want, SystemClock.elapsedRealtime())
            log?.invoke("Flashlight: ${if (want) "on" else "off"}" + if (want && logic.autoOffMs > 0) " (auto-off in ${TorchLogic.minutes(logic.autoOffMs)})" else "")
        } catch (e: CameraAccessException) {
            logic.failed(when (e.reason) {
                CameraAccessException.CAMERA_IN_USE, CameraAccessException.MAX_CAMERAS_IN_USE -> TorchLogic.Fail.CAMERA_IN_USE
                CameraAccessException.CAMERA_DISABLED, CameraAccessException.CAMERA_DISCONNECTED -> TorchLogic.Fail.UNAVAILABLE
                else -> TorchLogic.Fail.ERROR
            })
            log?.invoke("Flashlight: couldn't turn ${if (want) "on" else "off"} (${e.reason})")
        } catch (e: Exception) {
            logic.failed(TorchLogic.Fail.ERROR)
            log?.invoke("Flashlight: couldn't turn ${if (want) "on" else "off"} (${e.javaClass.simpleName})")
        }
        afterChange()
        return logic.spoken(want)
    }

    fun toggle(): String = set("toggle")

    private fun afterChange() {
        schedule()
        if (logic.on) TorchService.start(app) else TorchService.stop(app)
        runCatching { TileService.requestListeningState(app, ComponentName(app, FlashlightTileService::class.java)) }
        listeners.toList().forEach { runCatching { it() } }
    }

    companion object {
        @Volatile private var instance: TorchController? = null
        fun get(context: Context): TorchController =
            instance ?: synchronized(this) { instance ?: TorchController(context).also { instance = it } }
    }
}

/**
 * 4.0: keeps the app alive while the flashlight is on (Android turns the torch off when the app that
 * turned it on goes away) and shows the "Flashlight on" notification with Turn off.
 */
class TorchService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A start with startForegroundService must reach startForeground, even when it's about to stop.
        goForeground()
        val torch = TorchController.get(this)
        if (intent?.action == ACTION_OFF) torch.set("false")
        if (!torch.on) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        runCatching {
            nm?.createNotificationChannel(NotificationChannel(CHANNEL, "Flashlight", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows while the phone's flashlight is on, with a Turn off button"
            })
        }
        val off = PendingIntent.getService(this, 3, Intent(this, TorchService::class.java).setAction(ACTION_OFF),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 4, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val autoOff = Prefs(this).torchAutoOffMin
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_flashlight)
            .setContentTitle("Flashlight on")
            .setContentText(if (autoOff > 0) "Turns off by itself after ${TorchLogic.minutes(autoOff * 60_000L)}." else "Stays on until you turn it off.")
            .setOngoing(true)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_flashlight), "Turn off", off).build())
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION_ID, n)
        } catch (e: Exception) {
            // Not allowed from the background right now: still show the notification so Turn off is there.
            runCatching { nm?.notify(NOTIFICATION_ID, n) }
        }
    }

    override fun onDestroy() {
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "flashlight"
        private const val NOTIFICATION_ID = 31
        const val ACTION_OFF = "com.zdycomp.p3companion.FLASHLIGHT_OFF"

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, TorchService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TorchService::class.java)) }
            runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
        }
    }
}

/** 4.0: Quick Settings tile "Flashlight": one tap turns the phone's torch on or off. */
class FlashlightTileService : TileService() {

    private val listener: () -> Unit = { update() }

    override fun onStartListening() {
        super.onStartListening()
        TorchController.get(this).addListener(listener)
        update()
    }

    override fun onStopListening() {
        TorchController.get(this).removeListener(listener)
        super.onStopListening()
    }

    private fun update() {
        val tile = qsTile ?: return
        val t = TorchController.get(this)
        tile.label = "Flashlight"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_flashlight)
        tile.state = when {
            !t.available -> Tile.STATE_UNAVAILABLE
            t.on -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.subtitle = when {
            !t.available -> "No flashlight"
            t.logic.state == TorchLogic.State.BUSY -> "Camera in use"
            t.on -> "On"
            else -> "Off"
        }
        tile.stateDescription = tile.subtitle
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        TorchController.get(this).toggle()
        update()
    }
}
