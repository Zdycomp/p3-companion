package com.zdycomp.p3companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder

/**
 * Keeps the glasses link alive while the app is in the background, so photos taken with the
 * glasses' button are still saved, and brings it back when the glasses return, Bluetooth turns on
 * or the phone is unlocked. Shows a notification with a Disconnect button.
 */
class GlassesService : Service(), XkGlasses.Listener {

    private lateinit var g: XkGlasses
    private lateinit var prefs: Prefs
    private var lastText: String? = null

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> if (ours(i)) g.onGlassesSeen("glasses in range")
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> if (ours(i)) g.linkDownHint()
                BluetoothAdapter.ACTION_STATE_CHANGED -> when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_ON -> g.onGlassesSeen("Bluetooth turned on")
                    BluetoothAdapter.STATE_TURNING_OFF -> g.linkDownHint()
                }
                Intent.ACTION_USER_PRESENT -> g.onGlassesSeen("phone unlocked")
            }
        }
    }

    private fun ours(i: Intent): Boolean {
        val d = XkGlasses.deviceExtra(i) ?: return false
        return d.address.equals(prefs.glassesAddress, ignoreCase = true)
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        g = XkGlasses.get(this, prefs)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Glasses connection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows while P3 Companion keeps your glasses connected"
            }
        )
        AudioRoute.registerSystemReceiver(this, rx, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        g.addListener(this)
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android requires startForeground soon after startForegroundService, so it always comes first.
        if (!goForeground()) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_STOP) {
            g.disconnect()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!g.wantsLink && g.state == XkGlasses.State.IDLE) { stopSelf(); return START_NOT_STICKY }
        g.reconnectSaved()
        return START_STICKY
    }

    private fun goForeground(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTE_ID, note(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTE_ID, note())
        }
        true
    } catch (e: Exception) {
        g.addLog("Background connection not allowed right now (${e.javaClass.simpleName})")
        false
    }

    private fun statusText(): String = when (g.state) {
        XkGlasses.State.READY -> "Connected" + (g.battery?.let { " · battery $it%" } ?: "")
        XkGlasses.State.BUSY -> g.busyText ?: "Working…"
        XkGlasses.State.CONNECTING -> "Connecting…"
        XkGlasses.State.IDLE -> if (g.wantsLink) "Waiting for the glasses" else "Not connected"
    }

    private fun note(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, GlassesService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(g.deviceName ?: prefs.glassesName ?: "Your glasses")
            .setContentText(statusText())
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Disconnect", stop).build())
            .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
            .build()
    }

    override fun onGlassesChanged() {
        if (!g.wantsLink && g.state == XkGlasses.State.IDLE) { stopSelf(); return }
        val text = statusText()
        if (text == lastText) return
        lastText = text
        runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTE_ID, note()) }
    }

    override fun onDestroy() {
        running = false
        g.removeListener(this)
        runCatching { unregisterReceiver(rx) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "glasses_link"
        private const val NOTE_ID = 8
        const val ACTION_STOP = "com.zdycomp.p3companion.STOP_GLASSES"

        @Volatile var running = false
            private set

        fun start(context: Context) {
            if (running) return
            runCatching { context.applicationContext.startForegroundService(Intent(context.applicationContext, GlassesService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.applicationContext.stopService(Intent(context.applicationContext, GlassesService::class.java)) }
        }
    }
}

/** Brings the link back after the app was closed, when the glasses' Bluetooth link reconnects. */
class GlassesWakeReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val p = Prefs(c)
        if (!p.autoReconnect || p.userKey == null || p.glassesAddress == null || p.userStopped) return
        val d = XkGlasses.deviceExtra(i) ?: return
        if (!d.address.equals(p.glassesAddress, ignoreCase = true)) return
        GlassesService.start(c)
    }
}
