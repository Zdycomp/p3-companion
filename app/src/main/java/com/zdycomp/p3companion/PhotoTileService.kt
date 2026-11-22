package com.zdycomp.p3companion

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile "Glasses photo" (OPTIONS-PLAN X2). Connected: one tap takes a photo through the
 * running link (no app needed). Not connected: opens the app on the Glasses tab, which connects and
 * then takes the photo. Uses the one [XkGlasses] instance, never a second one.
 */
class PhotoTileService : TileService() {

    private val listener = XkGlasses.Listener { update() }
    private var listening = false

    private fun glasses(): XkGlasses {
        GlassesEvents.install(this)
        return XkGlasses.get(this, Prefs(this))
    }

    override fun onStartListening() {
        super.onStartListening()
        listening = true
        glasses().addListener(listener)
        update()
    }

    override fun onStopListening() {
        listening = false
        glasses().removeListener(listener)
        super.onStopListening()
    }

    private fun update() {
        val tile = qsTile ?: return
        val prefs = Prefs(this)
        val g = glasses()
        val saved = prefs.glassesAddress != null && prefs.userKey != null
        tile.label = "Glasses photo"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_photo_camera)
        tile.state = when {
            !saved -> Tile.STATE_UNAVAILABLE
            g.state == XkGlasses.State.READY || g.state == XkGlasses.State.BUSY -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.subtitle = when {
            !saved -> "Set up in the app"
            g.state == XkGlasses.State.BUSY -> g.busyText?.takeIf { it.length < 24 } ?: "Taking…"
            g.state == XkGlasses.State.READY -> g.battery?.let { "Battery $it%" } ?: "Connected"
            g.state == XkGlasses.State.CONNECTING -> "Connecting…"
            else -> "Not connected"
        }
        tile.stateDescription = tile.subtitle
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val g = glasses()
        if (g.state == XkGlasses.State.READY) {
            g.takePhoto()
            update()
            return
        }
        if (g.state == XkGlasses.State.BUSY) return
        if (isLocked) unlockAndRun { openApp() } else openApp()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_PHOTO)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 7, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
