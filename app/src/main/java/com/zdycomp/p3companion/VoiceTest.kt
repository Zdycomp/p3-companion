package com.zdycomp.p3companion

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.Locale

/**
 * 3.5: Diagnostics › Test voice. For each mic route (the phone's mic, the glasses through call audio,
 * the glasses over the data link) it records 3 s and reports the level in dBFS, then asks the person
 * to say "hello glasses" and logs what the speech service heard, or its error, and which service it
 * was. The result decides which mic voice questions use ([Prefs.micRouteScores]) and ends in one
 * summary line. Main thread only; [line] gets every report line, [done] the summary.
 *
 * 3.7: the routes run one after the other with [ROUTE_GAP_MS] between them (one recognizer at a time,
 * [RecognizerGate]); call audio asks Google's network recognizer first, then the on-phone one. The data
 * link records one utterance with our own end-of-speech detector, then: the pipe self-test (the phone's
 * mic recorded by the app and piped into the recognizer: does piping work on this phone at all?), the
 * glasses' clip through the pipe if it does, and Gemini if it doesn't (or the pipe heard nothing). What
 * each glasses route heard is saved for "Play back what the glasses heard" ([VoiceClips]).
 */
class VoiceTest(private val a: MainActivity, private val line: (String) -> Unit, private val done: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val asst = a.assistant
    private val prefs = a.prefs
    private val g = a.glasses
    private val route = asst.route
    private val results = LinkedHashMap<MicRoute, Pair<Boolean, String>>()
    private val lang = Languages.byName(prefs.replyLanguage)
    private val tag = lang.tag
    /** Whether the recognizer's own mic (the phone's) worked: the pipe self-test needs that to judge the pipe. */
    private var phoneOk = false

    companion object {
        /** 3.7: the pause between one route's test and the next. */
        const val ROUTE_GAP_MS = 1_500L
    }

    fun run() {
        if (asst.phase != Assistant.Phase.IDLE) asst.stop()
        asst.releaseAudio()
        line("Voice test")
        line("INFO  phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        asst.speech.serviceReport().forEach { line("INFO  $it") }
        line("INFO  piping the app's own audio into the speech service: " + when (prefs.pipeState) {
            VoiceChoice.PipeState.OK -> "works (pipe self-test)"
            VoiceChoice.PipeState.UNUSABLE -> "doesn't work on this phone (pipe self-test); Gemini transcribes the glasses' data link"
            else -> "not tested yet"
        })
        phone()
    }

    private fun level(take: MicCapture.Take): String {
        val tagWord = if (take.db < Level.SILENT_DB) "WARN" else "PASS"
        return "$tagWord  3 s recorded: RMS %.0f dBFS".format(Locale.US, take.db) +
            (take.routedTo?.let { ", recorded from $it" } ?: "") + (take.error?.let { " ($it)" } ?: "") +
            if (take.db < Level.SILENT_DB) " — silent" else ""
    }

    private fun next(step: () -> Unit) {
        line("INFO  waiting ${ROUTE_GAP_MS} ms before the next mic")
        main.postDelayed(step, ROUTE_GAP_MS)
    }

    // ---------------- 1. the phone's mic ----------------

    private fun phone() {
        line("1. Phone mic")
        line("INFO  recording 3 s: say anything now")
        MicCapture.record(a, null, 3_000, callAudio = false) { take ->
            line(level(take))
            recognize(MicRoute.PHONE, null, networkFirst = false) { ok, what, _ ->
                phoneOk = ok
                results[MicRoute.PHONE] = ok to what
                next { sco() }
            }
        }
    }

    // ---------------- 2. the glasses through call audio (SCO) ----------------

    private fun sco() {
        line("2. Glasses mic through call audio (SCO)")
        when {
            !prefs.useGlassesAudio -> { line("SKIP  glasses audio is off in Settings"); spp(); return }
            !route.headsetConnected() -> { line("SKIP  the glasses aren't connected as the phone's Bluetooth headset"); spp(); return }
        }
        scoWith(AudioRoute.methodOrder(null, prefs.scoMethod, Build.VERSION.SDK_INT), 0, null)
    }

    private fun methodName(m: String) = if (m == AudioRoute.COMM) "setCommunicationDevice" else "startBluetoothSco"

    private fun scoWith(methods: List<String>, i: Int, lastWhy: String?) {
        val m = methods.getOrNull(i)
        if (m == null) {
            results[MicRoute.GLASSES_SCO] = false to (lastWhy ?: "the call audio didn't open")
            line("FAIL  glasses call audio: ${lastWhy ?: "didn't open"}")
            next { spp() }
            return
        }
        line("INFO  opening call audio with ${methodName(m)} (up to ${AudioRoute.SCO_WAIT_MS / 1000} s)")
        route.acquire({ up ->
            route.lastReport?.let { line("INFO  $it") }
            if (!up) { route.release(); main.postDelayed({ scoWith(methods, i + 1, "no SCO link signal") }, 400); return@acquire }
            line("INFO  recording 3 s: say anything now")
            MicCapture.record(a, route.glassesInput(), 3_000, callAudio = true) { take ->
                line(level(take))
                VoiceClips.save(a, VoiceClips.SCO, take.samples)?.let { line("INFO  saved %.1f s of the call audio for Play back".format(Locale.US, VoiceClips.seconds(it))) }
                if (take.db < Level.SILENT_DB) {
                    route.release()
                    main.postDelayed({ scoWith(methods, i + 1, "silent (RMS %.0f dBFS) with ${methodName(m)}".format(Locale.US, take.db)) }, 600)
                    return@record
                }
                route.rememberWorking()
                // 3.7: the recognizer records the call-audio mic itself (the glasses are the call device);
                // Google's network recognizer first, then the on-phone one.
                recognize(MicRoute.GLASSES_SCO, null, networkFirst = true) { ok, what, _ ->
                    route.release()
                    results[MicRoute.GLASSES_SCO] = ok to "$what, ${methodName(m)}"
                    next { spp() }
                }
            }
        }, onlyMethod = m)
    }

    // ---------------- 3. the glasses over the data link (5750, Opus) ----------------

    private fun spp() {
        line("3. Glasses mic over the data link (5750, Opus)")
        if (g.state != XkGlasses.State.READY) { line("SKIP  the glasses aren't connected (or are busy)"); finish(); return }
        val mode = VoiceChoice.sppMode(prefs.pipeState, Build.VERSION.SDK_INT, prefs.aiKey(AiProvider.GEMINI) != null)
        if (mode == VoiceChoice.SppMode.NONE && Build.VERSION.SDK_INT < 33) line("INFO  piping audio into the speech service needs Android 13; without a Gemini key the words can't be read")
        line("INFO  starting the glasses' mic stream (it can take about 4 s)")
        val s = GlassesMicStream(g)
        s.start { err ->
            if (err != null) {
                line("FAIL  $err")
                line("INFO  ${g.micStreamReport()}")
                results[MicRoute.GLASSES_SPP] = false to err
                finish()
                return@start
            }
            line("Say: hello glasses (into the glasses; recording stops 0.8 s after you stop, at most 15 s)")
            val t0 = SystemClock.elapsedRealtime()
            s.captureClip { clip, how ->
                val take = MicCapture.Take(clip, Level.of(clip), "the glasses' data link, %.1f s of sound decoded".format(Locale.US, clip.size / MicCapture.RATE.toDouble()))
                line(level(take).replaceFirst("3 s recorded", "%.1f s recorded".format(Locale.US, clip.size / MicCapture.RATE.toDouble())) +
                    ", " + when (how) {
                        Vad.State.ENDED_AFTER_SPEECH -> "ended 0.8 s after the speech"
                        Vad.State.ENDED_NO_SPEECH -> "no speech heard"
                        Vad.State.ENDED_MAX -> "stopped at the limit"
                        else -> "stopped"
                    } + " (${SystemClock.elapsedRealtime() - t0} ms)")
                line("INFO  ${s.report()}")
                VoiceClips.save(a, VoiceClips.SPP, s.ring.snapshot())?.let {
                    line("INFO  saved the last %.1f s of the data-link audio for Play back".format(Locale.US, VoiceClips.seconds(it)))
                }
                s.stop()
                if (take.db < Level.SILENT_DB) {
                    results[MicRoute.GLASSES_SPP] = false to "silent (RMS %.0f dBFS)".format(Locale.US, take.db)
                    finish()
                    return@captureClip
                }
                main.postDelayed({ pipeSelfTest { pipeGlasses(clip) } }, 500)
            }
        }
    }

    /**
     * 3.7: the pipe self-test. 3 s of the phone's mic recorded by the app, piped into the recognizer in
     * real time. "No match" while the recognizer's own phone mic worked means the pipe is unusable here.
     */
    private fun pipeSelfTest(then: () -> Unit) {
        when {
            Build.VERSION.SDK_INT < 33 -> { line("SKIP  pipe self-test: piping needs Android 13"); then(); return }
            !phoneOk -> { line("SKIP  pipe self-test: the phone mic didn't work, so a failed pipe would prove nothing"); then(); return }
        }
        // Test voice always tests the pipe afresh: 3.5/3.6 marked services "no pipe" with the old bursty pipe.
        if (prefs.recognizerNoPipe.isNotEmpty()) {
            line("INFO  pipe self-test: forgetting the old \"doesn't take the app's own audio\" marks (${prefs.recognizerNoPipe.joinToString(", ")})")
            prefs.recognizerNoPipe = emptySet()
        }
        line("Pipe self-test: say hello glasses to the PHONE now (3 s)")
        MicCapture.record(a, null, 3_000, callAudio = false) { take ->
            line(level(take))
            val v = Vad()
            take.samples.toList().chunked(320).forEach { v.feed(it.toShortArray()) }
            val spoke = take.db >= Level.SILENT_DB && v.heardSpeech
            if (!spoke) { line("WARN  pipe self-test: no speech in the recording, so it proves nothing"); then(); return@record }
            val samples = take.samples
            recognize(MicRoute.PHONE, { PcmPipe.ofClip(samples) { asst.speech.finishListening() } }, networkFirst = true, label = "pipe self-test (phone mic piped)") { ok, _, code ->
                val verdict = VoiceChoice.pipeVerdict(ok, code, phoneOk, spoke)
                if (verdict != VoiceChoice.PipeState.UNKNOWN) prefs.pipeState = verdict
                line(when (verdict) {
                    VoiceChoice.PipeState.OK -> "PASS  pipe self-test: piped audio is understood on this phone"
                    VoiceChoice.PipeState.UNUSABLE -> "FAIL  pipe self-test: the speech service hears the phone mic itself but not the same sound piped in; " +
                        "the pipe is marked unusable and no longer used"
                    else -> "WARN  pipe self-test: inconclusive (${Speech.errorName(code)})"
                })
                main.postDelayed(then, 600)
            }
        }
    }

    /** The glasses' clip through the pipe when piping works (or isn't known not to), then Gemini if needed. */
    private fun pipeGlasses(clip: ShortArray) {
        if (Build.VERSION.SDK_INT < 33 || prefs.pipeState == VoiceChoice.PipeState.UNUSABLE) { gemini(clip, null); return }
        line("INFO  feeding the glasses' clip to the speech service through the pipe, in real time")
        recognize(MicRoute.GLASSES_SPP, { PcmPipe.ofClip(clip) { asst.speech.finishListening() } }, networkFirst = true) { ok, what, _ ->
            if (ok) {
                results[MicRoute.GLASSES_SPP] = true to "$what, piped"
                finish()
            } else main.postDelayed({ gemini(clip, what) }, 600)
        }
    }

    /** 3.7: the captured glasses clip transcribed by Gemini (Claude doesn't take audio). */
    private fun gemini(clip: ShortArray, pipeWhy: String?) {
        val key = prefs.aiKey(AiProvider.GEMINI)
        if (key == null) {
            val why = "the data-link mic needs a Gemini key to turn its audio into words (Claude doesn't take audio)" +
                (pipeWhy?.let { "; the pipe gave $it" } ?: "")
            line("FAIL  ${MicRoute.GLASSES_SPP.label}: $why")
            results[MicRoute.GLASSES_SPP] = false to why
            finish()
            return
        }
        line("INFO  sending the glasses' clip (%.1f s, WAV) to Gemini (${AiWire.TRANSCRIBE_MODEL}) to transcribe".format(Locale.US, clip.size / MicCapture.RATE.toDouble()))
        val t0 = SystemClock.elapsedRealtime()
        Thread({
            val res = runCatching { AiClient.transcribe(key, Wav.pcm16(clip, MicCapture.RATE), lang.name) }
            main.post {
                val ms = SystemClock.elapsedRealtime() - t0
                res.onSuccess { text ->
                    if (text != null) {
                        line("PASS  ${MicRoute.GLASSES_SPP.label}: Gemini heard \"$text\" in $ms ms")
                        results[MicRoute.GLASSES_SPP] = true to "heard \"$text\", Gemini"
                    } else {
                        line("FAIL  ${MicRoute.GLASSES_SPP.label}: Gemini heard no speech in the clip ($ms ms); play it back to hear what the glasses sent")
                        results[MicRoute.GLASSES_SPP] = false to "Gemini heard no speech" + (pipeWhy?.let { "; the pipe gave $it" } ?: "")
                    }
                }.onFailure { e ->
                    line("FAIL  ${MicRoute.GLASSES_SPP.label}: Gemini: ${e.message ?: e.javaClass.simpleName}")
                    results[MicRoute.GLASSES_SPP] = false to "Gemini: ${e.message ?: "failed"}"
                }
                finish()
            }
        }, "p3-transcribe").apply { isDaemon = true; start() }
    }

    // ---------------- recognition ----------------

    private fun recognize(
        r: MicRoute, feed: (() -> AudioFeed?)?, networkFirst: Boolean, label: String = r.label,
        then: (Boolean, String, Int) -> Unit,
    ) {
        val sp = asst.speech
        if (feed == null) line("Say: hello glasses")
        val t0 = SystemClock.elapsedRealtime()
        sp.listen(
            tag,
            onPartial = {},
            onResult = { text ->
                sp.lastAttempts.forEach { line("INFO  tried $it") }
                val via = sp.lastService ?: "?"
                line("PASS  $label: heard \"$text\" in ${SystemClock.elapsedRealtime() - t0} ms, speech service $via" +
                    if (sp.lastFed) " (fed the app's own audio)" else " (it recorded the mic itself)")
                then(true, "heard \"$text\", $via", 0)
            },
            onError = { _, code ->
                sp.lastAttempts.forEach { line("INFO  tried $it") }
                val detail = sp.lastErrorDetail ?: Speech.errorName(code)
                line("FAIL  $label: $detail" + if (sp.lastPipeRejected) "; that service is marked as not taking the app's own audio" else "")
                then(false, detail, code)
            },
            audio = feed,
            networkFirst = networkFirst,
        )
    }

    private fun finish() {
        val scores = prefs.micRouteScores.toMutableMap()
        results.forEach { (r, res) -> scores[r] = if (res.first) 1 else VoiceChoice.SKIP_AT }
        prefs.micRouteScores = scores
        val summary = VoiceChoice.summary(results)
        prefs.voiceTestSummary = summary
        g.addLog("Voice test: $summary")
        val clips = VoiceClips.saved(a)
        if (clips.isNotEmpty()) line("INFO  Play back what the glasses heard: " + clips.joinToString(", ") { "%s %.1f s".format(Locale.US, VoiceClips.label(it), VoiceClips.seconds(it)) })
        line(if (results.values.any { it.first }) "PASS  $summary" else "FAIL  $summary")
        line("Done.")
        done(summary)
    }
}
