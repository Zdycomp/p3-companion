package com.zdycomp.p3companion

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * 4.1: Diagnostics › Hardware tests: the device tests of docs/HARDWARE-MAP.md §8 (T1-T15), each with
 * its steps and, where the app can do the measuring, a Run button. Nothing here sends anything that
 * changes the glasses (T13 only reads at the next sign-in). Results go to the connection log, so
 * "Share log" carries them; T1 also teaches the app the touch codes it finds.
 */
class HardwareTestsScreen(private val a: MainActivity) : Screen {
    override val label = "Hardware tests"

    private val main = Handler(Looper.getMainLooper())
    private val results = HashMap<String, String>()
    @Volatile private var running: String? = null
    private var eventsView: TextView? = null
    private var visible = false

    private class Test(val id: String, val title: String, val steps: String, val run: Boolean)

    /** 4.1.1: redraw when the Bluetooth link to the glasses comes or goes. */
    private var lastConnected: Boolean? = null
    private val glassesListener = XkGlasses.Listener {
        main.post { if (visible && connected() != lastConnected) a.refresh() }
    }

    private fun connected(): Boolean = a.glasses.state.let { it == XkGlasses.State.READY || it == XkGlasses.State.BUSY }

    private val tests = listOf(
        Test("T1", "Touch pad, buttons and keys", "Run, then do each gesture when asked (single tap, double tap, long press, swipe forward, swipe back, rear button, play/pause). " +
            "The app logs every push with its bytes and timing and learns the codes it can tell apart, so those gestures get their own actions in Settings › Glasses buttons.", true),
        Test("T2", "Glasses mic: quiet and speech sizes", "Run, stay silent 3 s, then speak 3 s. Logs a per-second histogram of the glasses' Opus frame sizes (the free voice-activity hint).", true),
        Test("T3", "Mic and speaker together (echo)", "Start hands-free voice chat and ask something; while the answer plays, watch the level meter and check \"Play back what the glasses heard\" in Diagnostics.", false),
        Test("T4", "Gemini takes the glasses' own Opus", "Run and say \"this is the voice test\". The same clip goes to Gemini as Ogg Opus and as WAV; both transcripts, sizes and times are logged, and the answer is remembered.", true),
        Test("T5", "Call audio: wideband or narrowband", "Run and say \"six sixty six, sister Sue\" twice (once per mic). Logs how much energy is above 4 kHz on the call audio and on the data link.", true),
        Test("T6", "Mic versus live view", "Open live view, then start voice chat with \"Pause live view to listen\" on; note whether the video stops and what 5710 says. Then the other order.", false),
        Test("T7", "Photo button double press", "Run, then double-press the photo button within 15 s. Counts 5760 pushes and mic audio the glasses send by themselves.", true),
        Test("T8", "Volume", "Play the voice preview (Settings › Assistant) and step the phone's media volume from top to bottom. The log notes each step; listen whether the glasses get quieter smoothly.", false),
        Test("T9", "Charging push", "Put the glasses on the charger for 10 minutes with the app connected. Battery and charging changes are logged and shown below.", false),
        Test("T10", "Recording on the glasses", "Glasses › Record 10 s, then stop. Compare the counts before and after in the log.", false),
        Test("T11", "Wear and fold", "With this screen open, take the glasses off, fold them, wait 30 s, unfold and put them on. Any new push shows below.", false),
        Test("T12", "Indicator light", "Look at the frame while taking a photo, during live view and during voice chat. Note any light and its colour (observation only).", false),
        Test("T13", "Raw setup bytes", "Run, then disconnect and connect again on the Glasses tab. The next sign-in also reads 7100 (a read) and logs the whole device info (MAC hidden) and the 2B frames.", true),
        Test("T14", "Photo during live view", "With live view open, tap Take photo. Note whether the stream pauses and the photo arrives whole.", false),
        Test("T15", "Wake word", "With the app connected and this screen open, say \"Hey\", \"Hi\" or similar near the glasses. Any touch push or mic audio shows below.", false),
    )

    override fun build(): View {
        val ui = a.ui
        visible = true
        val bt = connected()
        lastConnected = bt
        a.glasses.removeListener(glassesListener)
        a.glasses.addListener(glassesListener)
        val col = ui.page()
        // 4.1.1: say it at the top when the glasses aren't connected, and don't run the tests that need them.
        if (!bt) col.addView(ui.banner(notConnectedText(), Tone.WARN, "Glasses tab") { a.show(MainActivity.TAB_GLASSES) },
            LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { bottomMargin = ui.dp(8) })
        val intro = ui.card()
        intro.addView(ui.body("These tests find out what your glasses really do. None of them changes anything on the glasses. " +
            "Results go to the connection log; share it from Diagnostics when you're done.", muted = true, sizeSp = 13f))
        intro.addView(ui.button("Share the log", Btn.TONAL, R.drawable.ic_share, full = false) {
            a.shareText("P3 Companion hardware tests", a.glasses.logLines(400).joinToString("\n"))
        })
        col.addView(intro)

        val live = ui.card()
        live.addView(ui.heading("Live", R.drawable.ic_bolt))
        val g = a.glasses
        live.addView(ui.text(listOf(
            "Glasses: " + g.state.name.lowercase(Locale.US) + (g.battery?.let { ", battery $it%" + if (g.charging == true) " (charging)" else "" } ?: ""),
            "Phone media volume: ${HardwareKeys.volumeText}",
            "Mic audio the glasses started themselves: ${g.unsolicitedMicFrames} frames",
            "Audio to Gemini: ${AudioUpload.label(a.prefs.geminiOggState)}",
            "Learned touch codes: " + a.prefs.gestureCodes.entries.joinToString { "${it.key} = ${it.value.label}" }.ifEmpty { "none yet" },
        ).joinToString("\n"), Type.BODY_SMALL, ui.s.onSurfaceVariant))
        val ev = ui.mono(eventsText())
        eventsView = ev
        live.addView(ev)
        col.addView(live)
        g.hwWatcher = { main.post { eventsView?.text = eventsText() } }

        for (t in tests) {
            val c = ui.card()
            c.addView(ui.heading("${t.id}  ${t.title}", null))
            c.addView(ui.body(t.steps, muted = true, sizeSp = 13f))
            results[t.id]?.let { c.addView(ui.text(it, Type.BODY_MEDIUM, ui.s.primary).apply { setPadding(0, ui.dp(6), 0, 0) }) }
            if (t.run) {
                val can = canRun(t.id, bt)
                c.addView(ui.button(if (running == t.id) "Running…" else if (!can) "Needs the glasses connected" else "Run ${t.id}",
                    Btn.OUTLINED, if (can) R.drawable.ic_play else R.drawable.ic_bluetooth, full = false) { start(t.id) }
                    .apply { isEnabled = running == null && can })
            }
            col.addView(c, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = ui.dp(8) })
        }
        return ui.screen("Hardware tests", col, back = { a.showDiagnostics() })
    }

    override fun onHidden() {
        visible = false
        a.glasses.removeListener(glassesListener)
        a.glasses.hwWatcher = null
        eventsView = null
    }

    private fun eventsText(): String = a.glasses.hardwareEvents().takeLast(12).joinToString("\n").ifEmpty { "No hardware events yet." }

    private fun show(id: String, text: String) {
        results[id] = text
        a.glasses.addLog("Hardware test $id: $text")
        if (visible) a.refresh()
    }

    private fun start(id: String) {
        if (running != null) return
        val g = a.glasses
        if (!canRun(id, connected())) { a.snack("Connect the glasses over Bluetooth first (Glasses tab)."); return }
        if (id in NEEDS_GLASSES && g.state != XkGlasses.State.READY) { a.snack("The glasses are busy. Try again in a moment."); return }
        running = id
        a.refresh()
        when (id) {
            "T1" -> t1()
            "T2" -> recordStream(6_000) { _, packets -> t2(packets) }
            "T4" -> recordStream(4_500) { pcm, packets -> t4(pcm, packets) }
            "T5" -> t5()
            "T7" -> t7()
            "T13" -> { g.rawSetupLog = true; done("T13", "Armed: disconnect and connect again on the Glasses tab; the log then has 7100, the device info and the 2B frames.") }
        }
    }

    private fun done(id: String, text: String) { running = null; show(id, text) }

    // ---- T1: learn the gestures ----
    private fun t1() {
        val order = listOf(Gesture.TAP, Gesture.DOUBLE_TAP, Gesture.LONG_PRESS, Gesture.SWIPE_FORWARD, Gesture.SWIPE_BACK, Gesture.REAR_BUTTON, Gesture.MEDIA_PLAY_PAUSE)
        val seen = LinkedHashMap<Gesture, MutableList<Pair<String, Long>>>()
        var current: Gesture? = null
        a.touchTap = { node, data -> current?.let { seen.getOrPut(it) { mutableListOf() } += GestureMap.codeKey(node, data) to SystemClock.elapsedRealtime() } }
        val keysBefore = HardwareKeys.onGesture
        HardwareKeys.setActive(a, true)
        HardwareKeys.onGesture = { k -> current?.let { seen.getOrPut(it) { mutableListOf() } += "KEY:${k.name}" to SystemClock.elapsedRealtime() } }
        fun finish() {
            a.touchTap = null
            HardwareKeys.onGesture = keysBefore
            HardwareKeys.setActive(a, a.prefs.mediaKeysAlways || a.assistant.voiceChatOn)
            val tapKeys = seen[Gesture.TAP]?.map { it.first }?.toSet().orEmpty()
            val learned = a.prefs.gestureCodes.toMutableMap()
            val lines = ArrayList<String>()
            for ((gst, list) in seen) {
                val codes = list.map { it.first }
                val gaps = list.zipWithNext { x, y -> y.second - x.second }
                lines += "${gst.label}: " + codes.joinToString(", ") + (if (gaps.isNotEmpty()) " (gaps ${gaps.joinToString("/")} ms)" else "")
                // A code no tap made, and only one code for this gesture: it's that gesture.
                val distinct = codes.filter { !it.startsWith("KEY:") && it !in tapKeys }.toSet()
                if (gst != Gesture.TAP && gst != Gesture.DOUBLE_TAP && distinct.size == 1 && learned.values.count { it == gst } <= 1) learned[distinct.first()] = gst
            }
            for (gst in order) if (gst !in seen) lines += "${gst.label}: nothing arrived"
            a.prefs.gestureCodes = learned
            a.gesturesChanged()
            done("T1", lines.joinToString("; ") + "; learned: " + learned.entries.joinToString { "${it.key} = ${it.value.name}" }.ifEmpty { "none (only timing tells gestures apart)" })
        }
        fun step(i: Int) {
            if (i >= order.size) { current = null; finish(); return }
            current = order[i]
            results["T1"] = "Now: ${order[i].label.lowercase()} (3 times, within 8 s)…"
            if (visible) a.refresh()
            main.postDelayed({ step(i + 1) }, 8_000)
        }
        step(0)
    }

    // ---- T2 / T4: a few seconds of the glasses' data-link mic ----
    private fun recordStream(ms: Long, then: (ShortArray, List<Pair<Long, ByteArray>>) -> Unit) {
        if (a.assistant.voiceChatOn) a.assistant.endVoiceChat("hardware test")
        val s = GlassesMicStream(a.glasses)
        val pcm = ArrayList<ShortArray>()
        val packets = ArrayList<Pair<Long, ByteArray>>()
        var t0 = 0L
        s.addListener { synchronized(pcm) { pcm += it } }
        s.addPacketListener { p -> synchronized(packets) { packets += (SystemClock.elapsedRealtime() - t0) to p } }
        results[running!!] = "Starting the glasses' mic (about 4 s)…"; a.refresh()
        s.start { err ->
            if (err != null) { s.stop(); done(running ?: "T2", "The glasses' mic didn't start: $err"); return@start }
            t0 = SystemClock.elapsedRealtime()
            synchronized(pcm) { pcm.clear() }; synchronized(packets) { packets.clear() }
            results[running!!] = "Recording ${ms / 1000} s…"; a.refresh()
            main.postDelayed({
                s.stop()
                val flat = synchronized(pcm) { val n = pcm.sumOf { it.size }; ShortArray(n).also { out -> var at = 0; pcm.forEach { System.arraycopy(it, 0, out, at, it.size); at += it.size } } }
                then(flat, synchronized(packets) { packets.toList() })
            }, ms)
        }
    }

    private fun t2(packets: List<Pair<Long, ByteArray>>) {
        val bySecond = packets.groupBy { (it.first / 1000).toInt() }.toSortedMap()
        val lines = bySecond.map { (sec, list) -> "${sec}-${sec + 1} s: " + OpusToc.histogramText(OpusToc.histogram(list.map { it.second })) }
        done("T2", if (lines.isEmpty()) "No packets arrived." else lines.joinToString("; "))
    }

    private fun t4(pcm: ShortArray, packets: List<Pair<Long, ByteArray>>) {
        val key = a.prefs.aiKey(AiProvider.GEMINI) ?: run { done("T4", "Needs a Gemini key (AI setup)."); return }
        val pk = packets.map { it.second }
        results["T4"] = "Sending to Gemini both ways…"; a.refresh()
        Thread {
            val model = a.prefs.geminiTextAuto ?: AiWire.TRANSCRIBE_MODEL
            val lang = Languages.byName(a.prefs.replyLanguage).name
            fun one(bytes: ByteArray, mime: String): String {
                val t0 = SystemClock.elapsedRealtime()
                val r = runCatching { AiClient.transcribe(key, bytes, lang, model, mime) }
                val ms = SystemClock.elapsedRealtime() - t0
                return "$mime ${bytes.size / 1024} KB, $ms ms: " + r.fold({ "\"${it ?: "(nothing)"}\"" }, { "failed (${(it as? AiException)?.httpCode ?: ""} ${it.message})" })
            }
            val ogg = OggOpus.build(pk)
            val oggLine = one(ogg, OggOpus.MIME)
            val wavLine = one(Wav.pcm16(pcm, MicCapture.RATE), "audio/wav")
            val oggOk = oggLine.contains(": \"") && !oggLine.contains("(nothing)")
            main.post {
                if (oggOk) a.prefs.geminiOggState = AudioUpload.OGG_WORKS
                else if (oggLine.contains("failed (400") || oggLine.contains("failed (415")) a.prefs.geminiOggState = AudioUpload.OGG_REFUSED
                done("T4", "$oggLine; $wavLine")
            }
        }.apply { isDaemon = true }.start()
    }

    // ---- T5: wideband or narrowband ----
    private fun t5() {
        val route = a.assistant.route
        results["T5"] = "Call audio: say the line now (3 s)…"; a.refresh()
        val sco = { then: (String) -> Unit ->
            if (!route.willUseGlasses()) then("call audio: the glasses aren't the phone's headset")
            else route.acquire({ up ->
                if (!up) { route.release(); then("call audio didn't open") }
                else MicCapture.record(a, route.glassesInput(), 3_000, callAudio = true) { take ->
                    route.release()
                    then("call audio: " + (take.error ?: Spectrum.verdict(Spectrum.shareAbove(take.samples, MicCapture.RATE, 4_000.0))))
                }
            })
        }
        sco { scoLine ->
            results["T5"] = "Data link: say the line again…"; a.refresh()
            recordStream(3_500) { pcm, _ ->
                done("T5", "$scoLine; data link: " + Spectrum.verdict(Spectrum.shareAbove(pcm, MicCapture.RATE, 4_000.0)))
            }
        }
    }

    // ---- T7: the photo button's double press ----
    private fun t7() {
        val g = a.glasses
        val before = g.unsolicitedMicFrames
        val evBefore = g.hardwareEvents().size
        results["T7"] = "Double-press the photo button now (15 s)…"; a.refresh()
        main.postDelayed({
            val ev = g.hardwareEvents().drop(evBefore.coerceAtMost(g.hardwareEvents().size)).filter { it.contains("5760") || it.contains("1F") }
            done("T7", "5760 pushes and mic audio: ${ev.size} (${ev.joinToString("; ").ifEmpty { "none" }}); unsolicited mic frames: ${g.unsolicitedMicFrames - before}")
        }, 15_000)
    }

    companion object {
        /** 4.1.1: the Run tests that talk to the glasses now (T13 only arms a read for the next sign-in). */
        val NEEDS_GLASSES = setOf("T1", "T2", "T4", "T5", "T7")

        /** Whether test [id]'s Run button may run with the glasses [bluetoothConnected] or not. */
        fun canRun(id: String, bluetoothConnected: Boolean): Boolean = bluetoothConnected || id !in NEEDS_GLASSES

        fun notConnectedText(): String =
            "The glasses aren't connected over Bluetooth, so the tests that need them (" + NEEDS_GLASSES.joinToString(", ") +
                ") won't run. Connect on the Glasses tab; the steps below can still be read, and T13 can be armed."
    }
}
