package com.zdycomp.p3companion

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent

/**
 * 4.1: the glasses' Bluetooth headset keys and volume, the Android side of [Gesture] / [VolumePolicy].
 *
 * - Media keys (AVRCP play/pause, next, previous, and the headset hook) reach a [MediaSession] that is
 *   active only during voice chat, or always when Settings asks for it, so music apps keep their keys
 *   otherwise. Each key press is logged with its key code (hardware test T1) and handed to [onGesture].
 * - The phone's media and call volume are watched; each change is logged with the glasses' name
 *   (hardware test T8: whether the glasses follow it as absolute volume); during voice chat on call
 *   audio the call stream is kept at the same loudness as media, so the reply sounds the same.
 */
object HardwareKeys {
    private val main = Handler(Looper.getMainLooper())
    private var session: MediaSession? = null
    private var observer: ContentObserver? = null
    private var lastMusic = -1
    private var lastCall = -1

    /** Main thread: a key from the glasses (or any headset) turned into a gesture. */
    @Volatile var onGesture: ((Gesture) -> Unit)? = null
    /** Where the lines go (the connection log). */
    @Volatile var log: ((String) -> Unit)? = null
    /** While true (voice chat on call audio), call volume follows media volume; the user's call volume is left alone otherwise. */
    @Volatile var syncCallVolume = false

    /** Sets call volume to media's loudness now. */
    fun syncNow(context: Context) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            val want = VolumePolicy.mapStep(am.getStreamVolume(AudioManager.STREAM_MUSIC), am.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)).coerceAtLeast(1)
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, want, 0)
        }
    }

    /** The phone's media volume as "9 of 15", for the Hardware tests screen. */
    @Volatile var volumeText: String = "?"; private set

    fun gestureFor(keyCode: Int): Gesture? = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> Gesture.MEDIA_PLAY_PAUSE
        KeyEvent.KEYCODE_MEDIA_NEXT -> Gesture.MEDIA_NEXT
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> Gesture.MEDIA_PREVIOUS
        KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_ASSIST -> Gesture.VOICE_KEY
        else -> null
    }

    /** Takes the media keys while [on] (voice chat, or the "always" setting). */
    fun setActive(context: Context, on: Boolean) {
        main.post {
            if (on && session == null) {
                runCatching {
                    val s = MediaSession(context.applicationContext, "P3 Companion")
                    s.setCallback(object : MediaSession.Callback() {
                        override fun onMediaButtonEvent(intent: Intent): Boolean {
                            @Suppress("DEPRECATION")
                            val ev = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
                            if (ev.action != KeyEvent.ACTION_DOWN || ev.repeatCount > 0) return true
                            val g = gestureFor(ev.keyCode)
                            log?.invoke("Key from the headset: ${KeyEvent.keyCodeToString(ev.keyCode)} (${ev.keyCode})" + (g?.let { " → ${it.label}" } ?: ", not used"))
                            if (g != null) onGesture?.invoke(g)
                            return true
                        }
                    })
                    // Media buttons go to the session that played last; "playing" while it's active, no audio of its own.
                    s.setPlaybackState(PlaybackState.Builder()
                        .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                        .setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
                    s.isActive = true
                    session = s
                    log?.invoke("Headset keys: the glasses' media keys now control the app")
                }.onFailure { log?.invoke("Headset keys: couldn't take the media keys (${it.javaClass.simpleName})") }
            } else if (!on && session != null) {
                runCatching { session?.isActive = false; session?.release() }
                session = null
                log?.invoke("Headset keys: media keys handed back")
            }
        }
    }

    /** Starts watching the phone's volume (idempotent). */
    fun watchVolume(context: Context, glassesName: () -> String?) {
        if (observer != null) return
        val app = context.applicationContext
        val am = app.getSystemService(AudioManager::class.java) ?: return
        val o = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                val music = am.getStreamVolume(AudioManager.STREAM_MUSIC); val mMax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val call = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL); val cMax = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                volumeText = "$music of $mMax"
                if (music != lastMusic && lastMusic >= 0) {
                    log?.invoke("Volume: media $music of $mMax (${VolumePolicy.percent(music, mMax)}%)" +
                        (glassesName()?.let { "; $it plays at this level if it supports absolute volume" } ?: ""))
                    // During voice chat on call audio, keep call audio as loud as media, so the reply sounds the same.
                    val want = VolumePolicy.mapStep(music, mMax, cMax).coerceAtLeast(1)
                    if (syncCallVolume && want != call) runCatching { am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, want, 0) }
                } else if (call != lastCall && lastCall >= 0) log?.invoke("Volume: call audio $call of $cMax")
                lastMusic = music; lastCall = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            }
        }
        runCatching {
            app.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, o)
            observer = o
            lastMusic = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            lastCall = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            volumeText = "$lastMusic of ${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}"
        }
    }

    /** One step of the phone's media volume (the glasses' speakers when they're the audio device). */
    fun stepVolume(context: Context, up: Boolean): String {
        val am = context.getSystemService(AudioManager::class.java) ?: return "No volume control."
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
        val v = am.getStreamVolume(AudioManager.STREAM_MUSIC); val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return "Volume ${VolumePolicy.percent(v, max)}%."
    }
}
