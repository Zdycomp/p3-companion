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
 */
class VoiceTest(private val a: MainActivity, private val line: (String) -> Unit, private val done: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val asst = a.assistant
    private val prefs = a.prefs
    private val g = a.glasses
    private val route = asst.route
    private val results = LinkedHashMap<MicRoute, Pair<Boolean, String>>()
    private val tag = Languages.byName(prefs.replyLanguage).tag

    fun run() {
        if (asst.phase != Assistant.Phase.IDLE) asst.stop()
        asst.releaseAudio()
        line("Voice test")
        line("INFO  phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        asst.speech.serviceReport().forEach { line("INFO  $it") }
        phone()
    }

    private fun level(take: MicCapture.Take): String {
        val tagWord = if (take.db < Level.SILENT_DB) "WARN" else "PASS"
        return "$tagWord  3 s recorded: RMS %.0f dBFS".format(Locale.US, take.db) +
            (take.routedTo?.let { ", recorded from $it" } ?: "") + (take.error?.let { " ($it)" } ?: "") +
            if (take.db < Level.SILENT_DB) " — silent" else ""
    }

    // ---------------- 1. the phone's mic ----------------

    private fun phone() {
        line("1. Phone mic")
        line("INFO  recording 3 s: say anything now")
        MicCapture.record(a, null, 3_000, callAudio = false) { take ->
            line(level(take))
            recognize(MicRoute.PHONE, null) { ok, what ->
                results[MicRoute.PHONE] = ok to what
                main.postDelayed({ sco() }, 400)
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
            main.postDelayed({ spp() }, 600)
            return
        }
        line("INFO  opening call audio with ${methodName(m)} (up to ${AudioRoute.SCO_WAIT_MS / 1000} s)")
        route.acquire({ up ->
            route.lastReport?.let { line("INFO  $it") }
            if (!up) { route.release(); main.postDelayed({ scoWith(methods, i + 1, "no SCO link signal") }, 400); return@acquire }
            line("INFO  recording 3 s: say anything now")
            MicCapture.record(a, route.glassesInput(), 3_000, callAudio = true) { take ->
                line(level(take))
                if (take.db < Level.SILENT_DB) {
                    route.release()
                    main.postDelayed({ scoWith(methods, i + 1, "silent (RMS %.0f dBFS) with ${methodName(m)}".format(Locale.US, take.db)) }, 600)
                    return@record
                }
                route.rememberWorking()
                recognize(MicRoute.GLASSES_SCO, null) { ok, what ->
                    route.release()
                    results[MicRoute.GLASSES_SCO] = ok to "$what, ${methodName(m)}"
                    main.postDelayed({ spp() }, 600)
                }
            }
        }, onlyMethod = m)
    }

    // ---------------- 3. the glasses over the data link (5750, Opus) ----------------

    private fun spp() {
        line("3. Glasses mic over the data link (5750, Opus)")
        when {
            g.state != XkGlasses.State.READY -> { line("SKIP  the glasses aren't connected (or are busy)"); finish(); return }
            !MicCapture.supported() -> { line("SKIP  feeding the speech service the app's own audio needs Android 13"); finish(); return }
        }
        line("INFO  starting the glasses' mic stream (it can take about 3 s)")
        val s = GlassesMicStream(g)
        s.start { err ->
            if (err != null) {
                line("FAIL  $err")
                line("INFO  ${g.micStreamReport()}")
                results[MicRoute.GLASSES_SPP] = false to err
                finish()
                return@start
            }
            line("INFO  recording 3 s: say anything now")
            val got = ArrayList<ShortArray>()
            val l: (ShortArray) -> Unit = { pcm -> synchronized(got) { got += pcm } }
            s.addListener(l)
            main.postDelayed({
                s.removeListener(l)
                val all = synchronized(got) { got.toList() }
                val n = all.sumOf { it.size }
                val flat = ShortArray(n)
                var at = 0
                all.forEach { System.arraycopy(it, 0, flat, at, it.size); at += it.size }
                val take = MicCapture.Take(flat, Level.of(flat), "the glasses' data link, %.1f s of sound decoded".format(Locale.US, n / MicCapture.RATE.toDouble()))
                line(level(take))
                line("INFO  ${s.report()}")
                if (take.db < Level.SILENT_DB) {
                    s.stop()
                    results[MicRoute.GLASSES_SPP] = false to "silent (RMS %.0f dBFS)".format(Locale.US, take.db)
                    finish()
                    return@postDelayed
                }
                recognize(MicRoute.GLASSES_SPP, { s.newPipe { asst.speech.finishListening() } }) { ok, what ->
                    s.stop()
                    results[MicRoute.GLASSES_SPP] = ok to what
                    finish()
                }
            }, 3_000)
        }
    }

    // ---------------- recognition ----------------

    private fun recognize(r: MicRoute, feed: (() -> AudioFeed?)?, then: (Boolean, String) -> Unit) {
        val sp = asst.speech
        line("Say: hello glasses")
        val t0 = SystemClock.elapsedRealtime()
        sp.listen(
            tag,
            onPartial = {},
            onResult = { text ->
                sp.lastAttempts.forEach { line("INFO  tried $it") }
                val via = sp.lastService ?: "?"
                line("PASS  ${r.label}: heard \"$text\" in ${SystemClock.elapsedRealtime() - t0} ms, speech service $via" +
                    if (sp.lastFed) " (fed the app's own audio)" else " (it recorded the mic itself)")
                then(true, "heard \"$text\", $via")
            },
            onError = { _, code ->
                sp.lastAttempts.forEach { line("INFO  tried $it") }
                val detail = sp.lastErrorDetail ?: Speech.errorName(code)
                line("FAIL  ${r.label}: $detail" + if (sp.lastPipeRejected) "; that service is marked as not taking the app's own audio" else "")
                then(false, detail)
            },
            audio = feed,
        )
    }

    private fun finish() {
        val scores = prefs.micRouteScores.toMutableMap()
        results.forEach { (r, res) -> scores[r] = if (res.first) 1 else VoiceChoice.SKIP_AT }
        prefs.micRouteScores = scores
        val summary = VoiceChoice.summary(results)
        prefs.voiceTestSummary = summary
        g.addLog("Voice test: $summary")
        line(if (results.values.any { it.first }) "PASS  $summary" else "FAIL  $summary")
        line("Done.")
        done(summary)
    }
}
