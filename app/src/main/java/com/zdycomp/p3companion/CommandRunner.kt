package com.zdycomp.p3companion

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Carries out an offline voice command (docs/VOICE-COMMANDS.md §2) on the phone and says what to
 * speak. Quick: anything slow (the glasses, the gallery, the AI) runs after the spoken reply, in
 * [Reply.after] or on its own. Lives with the activity; call everything on the main thread.
 */
class CommandRunner(private val a: MainActivity) {

    /** What to say now (null = silence), in which voice, and what to do once it's said. */
    data class Reply(val say: String?, val after: (() -> Unit)? = null, val languageTag: String? = null)

    private val main = Handler(Looper.getMainLooper())
    private val prefs get() = a.prefs
    private val glasses get() = a.glasses
    private val assistant get() = a.assistant
    private var timerPhoto: Runnable? = null
    private var torchOn = false
    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { if (cameraId == torchId) torchOn = enabled }
    }
    private val torchId: String? by lazy {
        val cm = a.getSystemService(CameraManager::class.java) ?: return@lazy null
        runCatching {
            val ids = cm.cameraIdList.filter { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ids.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK } ?: ids.firstOrNull()
        }.getOrNull()?.also { runCatching { cm.registerTorchCallback(torchCallback, main) } }
    }

    fun state() = CommandState(
        memoRecording = a.recorder.recording,
        videoRecording = glasses.recording,
        liveViewOpen = a.currentScreen == MainActivity.SCREEN_LIVE,
    )

    /** Stop also cancels a photo waiting on a voice timer. */
    fun cancelPending() {
        timerPhoto?.let { main.removeCallbacks(it) }
        timerPhoto = null
    }

    fun release() {
        cancelPending()
        runCatching { a.getSystemService(CameraManager::class.java)?.unregisterTorchCallback(torchCallback) }
    }

    /** The spoken question before a command that needs a yes. */
    fun question(m: Match): String = when (m.id) {
        "gallery.delete_last" -> "Delete the last photo? Say yes or no."
        "ai.clear" -> "Clear this conversation? Say yes or no."
        else -> "Are you sure? Say yes or no."
    }

    private fun connected() = glasses.state == XkGlasses.State.READY || glasses.state == XkGlasses.State.BUSY

    private fun n(m: Match, k: String) = m.slots[k]?.toIntOrNull()

    fun run(m: Match): Reply = when (m.id) {
        // ---- camera and glasses ----
        "glasses.photo" -> {
            val secs = prefs.selfTimer
            if (secs > 0) photoIn(secs) else Reply("Taking a photo.", after = { a.whenConnected("take the photo") { glasses.takePhoto() } })
        }
        "glasses.photo_timer" -> photoIn((n(m, "n") ?: 5).coerceIn(1, 30))
        "glasses.video_start" -> if (glasses.recording) Reply("The glasses are already recording.")
            else Reply("Recording video on the glasses.", after = { a.whenConnected("record video") { glasses.setRecording(true) } })
        "glasses.video_stop" -> if (!glasses.recording) Reply("The glasses aren't recording video.")
            else Reply("Video stopped.", after = { glasses.setRecording(false) })
        "glasses.live_open" -> Reply("Opening live view.", after = { a.whenConnected("open live view") { a.showLiveView() } })
        "glasses.live_close" -> if (a.currentScreen == MainActivity.SCREEN_LIVE) Reply("Live view closed.", after = { a.goBack() })
            else Reply("Live view isn't open.")
        "glasses.live_snapshot" -> {
            val lv = a.liveView()
            if (a.currentScreen != MainActivity.SCREEN_LIVE || lv == null) Reply("Open live view first.")
            else { lv.snapshot(); Reply("Snapshot saved.") }
        }
        "glasses.battery" -> glassesBattery()
        "glasses.status" -> Reply(when (glasses.state) {
            XkGlasses.State.READY, XkGlasses.State.BUSY -> "Connected to ${glasses.deviceName ?: prefs.glassesName ?: "your glasses"}."
            XkGlasses.State.CONNECTING -> "Connecting."
            XkGlasses.State.IDLE -> "Not connected."
        })
        "glasses.connect" -> when {
            connected() -> Reply("The glasses are already connected.")
            prefs.glassesAddress == null || prefs.userKey == null -> Reply("Pair the glasses on the Glasses tab first.", after = { a.show(MainActivity.TAB_GLASSES) })
            else -> Reply("Connecting to your glasses.", after = { a.withBluetooth { glasses.connect(prefs.glassesAddress!!, prefs.glassesName) } })
        }
        "glasses.counts" -> if (!connected()) Reply("The glasses aren't connected.") else {
            glasses.refresh()
            main.postDelayed({ assistant.say(countsText()) }, 1_500)
            Reply("Checking the glasses.")
        }

        // ---- recording ----
        "rec.memo_start" -> when {
            a.recorder.recording -> Reply("A memo is already recording.")
            else -> Reply("Recording. Tap the glasses to stop.", after = { a.withMic(forRecording = true) { a.recorder.start() } })
        }
        "rec.memo_stop" -> if (!a.recorder.recording) Reply("Nothing is recording.")
            else { a.recorder.stop(); Reply("Memo saved.") }
        "rec.status" -> Reply(when {
            a.recorder.recording -> "Memo running for ${spokenDuration(a.recorder.elapsedMs() / 1000)}."
            glasses.recording -> "Video recording on the glasses."
            else -> "Nothing is recording."
        })

        // ---- gallery and import ----
        "gallery.last_photo" -> lastPhoto()?.let { uri -> Reply("Opening your last photo.", after = { a.openMedia(uri, "image/jpeg") }) }
            ?: Reply("No photos yet.")
        "gallery.describe_last" -> lastPhoto()?.let { uri ->
            Reply("Looking at your last photo.", after = { assistant.askPhoto(uri, AiWire.photoPreset(0, prefs.replyLanguage), voice = true) })
        } ?: Reply("No photos yet.")
        "gallery.count" -> Reply(galleryCount())
        "gallery.delete_last" -> lastPhoto()?.let { uri ->
            Reply("Confirm on your phone.", after = { a.requestDelete(listOf(uri)) { a.toast("Deleted.") } })
        } ?: Reply("No photos yet.")
        "import.start" -> {
            val imp = a.importer
            when {
                imp.running -> Reply("An import is already running.", after = { a.show(MainActivity.TAB_IMPORT) })
                !imp.driveAvailable() -> Reply("Pick the glasses' drive on the Import tab first.", after = { a.show(MainActivity.TAB_IMPORT) })
                else -> Reply("Importing.", after = { a.show(MainActivity.TAB_IMPORT); imp.start { a.refresh() } })
            }
        }

        // ---- assistant ----
        "ai.look" -> photoQuestion("Looking.", AiWire.photoPreset(0, prefs.replyLanguage))
        "ai.read_text" -> photoQuestion("Reading.", AiWire.photoPreset(3, prefs.replyLanguage))
        "ai.identify" -> photoQuestion("Checking.", AiWire.photoPreset(1, prefs.replyLanguage))
        "ai.translate_view" -> {
            val lang = m.slots["lang"] ?: prefs.replyLanguage
            photoQuestion("Translating.", "Read all the text in this photo and translate it into $lang. Answer in $lang.")
        }
        "suprevolt.scan" -> when {
            !prefs.srReady -> Reply("Server not set up.", after = { a.showSupRevolt() })
            SupRevolt.get(prefs).busy -> Reply("A scan is already running.")
            else -> Reply("Scanning.", after = {
                a.whenConnected("scan") {
                    SupRevolt.get(prefs).scanNewGlassesPhoto(a) { job, err ->
                        assistant.say(job?.takeIf { it.done }?.let { SupRevoltWire.spoken(it) } ?: err ?: "The scan didn't finish.")
                    }
                }
            })
        }
        "ai.repeat" -> assistant.lastAiLine()?.let { Reply(it) } ?: Reply("Nothing to repeat yet.")
        "ai.clear" -> { assistant.clearChat(); Reply("Cleared.") }

        // ---- translation ----
        "tr.start" -> Reply("Go ahead, I'm listening in ${prefs.translateFrom}.", after = {
            a.openAssistant(AssistantScreen.SECTION_TRANSLATE)
            main.post { if (assistant.phase == Assistant.Phase.IDLE) assistant.translate() }
        })
        // 3.6: Conversation translate keeps listening and logs every phrase until "stop".
        "tr.conversation" -> if (assistant.convoOn) Reply("Already translating the conversation.", after = { a.showConversation() })
            else Reply(
                (if (prefs.convoTwoWay) "Translating both ways, ${prefs.translateFrom} and ${prefs.translateTo}." else "Translating ${if (prefs.convoAutoDetect) "any language" else prefs.translateFrom} into ${prefs.translateTo}.") +
                    " Say stop to end.",
                after = { a.showConversation(); main.post { a.withMic { assistant.startConversation() } } },
            )
        "tr.conversation_stop" -> if (!assistant.convoOn) Reply("Conversation translate isn't running.")
            else { assistant.stopConversation(); Reply("Stopped translating. The log is saved.") }
        "tr.set_pair" -> {
            m.slots["from"]?.let { prefs.translateFrom = it }
            m.slots["to"]?.let { prefs.translateTo = it }
            a.refresh()
            Reply("Translating ${prefs.translateFrom} to ${prefs.translateTo}.")
        }
        "tr.swap" -> {
            val f = prefs.translateFrom
            prefs.translateFrom = prefs.translateTo
            prefs.translateTo = f
            a.refresh()
            Reply("Now ${prefs.translateFrom} to ${prefs.translateTo}.")
        }
        "tr.say_in" -> {
            val phrase = m.slots["phrase"].orEmpty()
            val lang = m.slots["lang"] ?: prefs.translateTo
            Reply(null, after = { assistant.sayIn(phrase, lang) })
        }
        "tr.reply_language" -> {
            val lang = m.slots["lang"] ?: prefs.replyLanguage
            prefs.replyLanguage = lang
            a.refresh()
            Reply("$lang it is.", languageTag = Languages.byName(lang).tag)
        }

        // ---- phone ----
        "util.time" -> Reply("It's ${android.text.format.DateFormat.getTimeFormat(a).format(Date())}.")
        "util.date" -> Reply("Today is ${SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())}.")
        "util.phone_battery" -> {
            val bm = a.getSystemService(BatteryManager::class.java)
            val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            val charging = bm?.isCharging == true
            Reply(if (level in 0..100) "Phone at $level percent" + (if (charging) ", charging." else ".") else "I can't read the phone's battery.")
        }
        "util.timer" -> setTimer((n(m, "dur") ?: 60).coerceIn(1, 24 * 3600))
        "util.alarm" -> setAlarm(n(m, "h") ?: 7, n(m, "m") ?: 0, m.slots["ampm"] == "1")
        "util.torch" -> torch(m.slots["onoff"])
        "util.volume" -> volume(m.slots["dir"] == "up")

        // ---- navigation ----
        "nav.tab" -> {
            val (tab, name) = when (m.slots["tab"]) {
                "IMPORT" -> MainActivity.TAB_IMPORT to "Import"
                "GALLERY" -> MainActivity.TAB_GALLERY to "Gallery"
                "GLASSES" -> MainActivity.TAB_GLASSES to "Glasses"
                "ASSISTANT" -> MainActivity.TAB_ASSISTANT to "Assistant"
                else -> MainActivity.TAB_SETTINGS to "Settings"
            }
            Reply("$name.", after = { a.show(tab) })
        }
        "nav.diagnostics" -> Reply("Diagnostics.", after = { a.showDiagnostics() })

        // ---- settings ----
        "set.speak_answers" -> {
            val on = m.slots["onoff"] != "false"
            prefs.speakChatAnswers = on
            Reply(if (on) "I'll read answers aloud." else "Answers will be text only.")
        }
        "set.tap_to_talk" -> {
            val on = when (m.slots["onoff"]) { "true" -> true; "false" -> false; else -> !prefs.tapToTalk }
            prefs.tapToTalk = on
            Reply("Tap to talk ${if (on) "on" else "off"}.")
        }
        "set.speech_rate" -> {
            prefs.ttsRate = when (m.slots["dir"]) {
                "up" -> prefs.ttsRate + 0.15f
                "down" -> prefs.ttsRate - 0.15f
                else -> 1f
            }
            Reply(if (m.slots["dir"] == "normal") "Normal speed." else "Like this?")
        }
        "set.self_timer" -> {
            val want = n(m, "n") ?: 0
            val v = if (want <= 0) 0 else Prefs.TIMER_STEPS.filter { it > 0 }.minByOrNull { kotlin.math.abs(it - want) } ?: 0
            prefs.selfTimer = v
            Reply(if (v == 0) "Self-timer off." else "Self-timer $v seconds.")
        }

        // ---- accessibility ----
        "acc.text_size" -> {
            val sizes = Prefs.TEXT_SCALES
            val i = sizes.indexOf(prefs.textScale).coerceAtLeast(0)
            val j = when (m.slots["dir"]) { "up" -> i + 1; "down" -> i - 1; else -> sizes.indexOf(1f) }
            when {
                j >= sizes.size -> Reply("That's the largest size.")
                j < 0 -> Reply("That's the smallest size.")
                else -> {
                    prefs.textScale = sizes[j]
                    Reply("Text ${when { j > i -> "larger"; j < i -> "smaller"; else -> "normal" }}.", after = { a.recreate() })
                }
            }
        }
        "acc.high_contrast" -> {
            val on = when (m.slots["onoff"]) { "true" -> true; "false" -> false; else -> !prefs.highContrast }
            prefs.highContrast = on
            Reply("High contrast ${if (on) "on" else "off"}.", after = { a.recreate() })
        }
        "acc.where_am_i" -> {
            val where = when (a.currentScreen) {
                MainActivity.TAB_IMPORT -> "Import tab"
                MainActivity.TAB_GALLERY -> "Gallery tab"
                MainActivity.TAB_GLASSES -> "Glasses tab"
                MainActivity.TAB_ASSISTANT -> "Assistant tab"
                MainActivity.TAB_SETTINGS -> "Settings tab"
                MainActivity.SCREEN_DIAGNOSTICS -> "Diagnostics"
                MainActivity.SCREEN_LIVE -> "Live view"
                else -> "About"
            }
            Reply("$where. Glasses ${if (connected()) "connected" else "not connected"}.")
        }

        // ---- help ----
        "sys.help" -> {
            assistant.note("Voice commands you can say:\n" + VoiceCommands.helpText())
            Reply("Try: " + VoiceCommands.sampler.dropLast(1).joinToString(", ") + ", or say stop. The full list is on screen.")
        }
        else -> Reply("I don't know how to do that yet.")
    }

    // ---------------- helpers ----------------

    private fun photoIn(secs: Int): Reply {
        cancelPending()
        return Reply("Photo in $secs seconds.", after = {
            val r = Runnable {
                timerPhoto = null
                if (glasses.state == XkGlasses.State.READY) glasses.takePhoto() else a.toast("The glasses weren't ready for the photo.")
            }
            timerPhoto = r
            main.postDelayed(r, secs * 1000L)
        })
    }

    private fun photoQuestion(ack: String, question: String): Reply =
        if (!assistant.hasKey()) Reply("Add your AI key in AI setup first.")
        else if (glasses.state != XkGlasses.State.READY) Reply(if (glasses.state == XkGlasses.State.BUSY) "The glasses are busy." else "The glasses aren't connected.")
        else Reply(ack, after = { assistant.captureAndAsk(question, voice = true) })

    private fun glassesBattery(): Reply {
        if (!connected()) return Reply("The glasses aren't connected.")
        val level = glasses.battery
        if (level != null) return Reply(batteryText())
        glasses.refresh()
        main.postDelayed({ assistant.say(if (glasses.battery != null) batteryText() else "The glasses didn't report their battery.") }, 1_500)
        return Reply("Checking.")
    }

    private fun batteryText() = "Glasses at ${glasses.battery} percent" + if (glasses.charging == true) ", charging." else "."

    private fun countsText(): String {
        val p = glasses.photoCount
        val v = glasses.videoCount
        val r = glasses.audioCount
        if (p == null && v == null && r == null) return "The glasses didn't say what's on them."
        fun part(n: Int?, one: String, many: String) = "${n ?: 0} ${if (n == 1) one else many}"
        return "${part(p, "photo", "photos")}, ${part(v, "video", "videos")} and ${part(r, "recording", "recordings")} on the glasses."
    }

    private fun spokenDuration(totalSec: Long): String {
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return listOfNotNull(
            h.takeIf { it > 0 }?.let { "$it hour" + if (it == 1L) "" else "s" },
            m.takeIf { it > 0 }?.let { "$it minute" + if (it == 1L) "" else "s" },
            s.takeIf { it > 0 || (h == 0L && m == 0L) }?.let { "$it second" + if (it == 1L) "" else "s" },
        ).joinToString(" ")
    }

    /** The glasses' last photo, else the newest picture in the app's album. */
    private fun lastPhoto(): Uri? {
        glasses.lastPhoto?.let { return it }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val args = Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.RELATIVE_PATH} = ?")
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("${Environment.DIRECTORY_PICTURES}/${prefs.albumName}/"))
            putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_ADDED))
            putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, 1)
        }
        return runCatching {
            a.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), args, null)?.use { c ->
                if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null
            }
        }.getOrNull()
    }

    private fun count(collection: Uri, dir: String): Int = runCatching {
        val args = Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.RELATIVE_PATH} = ?")
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("$dir/${prefs.albumName}/"))
        }
        a.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), args, null)?.use { it.count } ?: 0
    }.getOrDefault(0)

    private fun galleryCount(): String {
        val photos = count(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), Environment.DIRECTORY_PICTURES)
        val videos = count(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), Environment.DIRECTORY_MOVIES)
        return "$photos photo${if (photos == 1) "" else "s"} and $videos video${if (videos == 1) "" else "s"} in ${prefs.albumName}."
    }

    private fun setTimer(secs: Int): Reply {
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, secs)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "P3 Companion")
        return try {
            a.startActivity(i)
            Reply("Timer set for ${spokenDuration(secs.toLong())}.")
        } catch (e: ActivityNotFoundException) {
            Reply("No clock app can take timers.")
        } catch (e: SecurityException) {
            Reply("The clock app didn't let me set a timer.")
        }
    }

    private fun setAlarm(hourIn: Int, minute: Int, saidAmPm: Boolean): Reply {
        var hour = hourIn
        // No am or pm with a 12-hour clock: the next time it comes round.
        if (!saidAmPm && !android.text.format.DateFormat.is24HourFormat(a) && hour in 1..12) {
            val now = Calendar.getInstance()
            val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            val am = (hour % 12) * 60 + minute
            hour = if (am > nowMin || am + 12 * 60 <= nowMin) hour % 12 else hour % 12 + 12
        }
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "P3 Companion")
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute) }
        val spoken = android.text.format.DateFormat.getTimeFormat(a).format(cal.time)
        return try {
            a.startActivity(i)
            Reply("Alarm set for $spoken.")
        } catch (e: ActivityNotFoundException) {
            Reply("No clock app can take alarms.")
        } catch (e: SecurityException) {
            Reply("The clock app didn't let me set an alarm.")
        }
    }

    private fun torch(onoff: String?): Reply {
        val id = torchId ?: return Reply("This phone has no flashlight.")
        val on = when (onoff) { "true" -> true; "false" -> false; else -> !torchOn }
        return try {
            a.getSystemService(CameraManager::class.java)!!.setTorchMode(id, on)
            torchOn = on
            Reply(if (on) "Flashlight on." else "Flashlight off.")
        } catch (e: Exception) {
            Reply("The flashlight is busy.")
        }
    }

    private fun volume(up: Boolean): Reply {
        val am = a.getSystemService(AudioManager::class.java) ?: return Reply(null)
        val stream = if (prefs.speakThrough == Prefs.SpeakThrough.CALL && assistant.route.active) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
        repeat(2) { runCatching { am.adjustStreamVolume(stream, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0) } }
        return Reply(if (up) "Louder." else "Quieter.")
    }
}
