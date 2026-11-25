package com.zdycomp.p3companion

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Listening (Android's speech recognizer) and talking (Android's text-to-speech). Call everything
 * on the main thread; callbacks arrive there too.
 *
 * 3.1 listening: the phone's recognition services are tried in turn (the one that worked last,
 * then Google's, the on-phone one, then any other such as Samsung's, then the default), so one
 * broken service no longer means "voice doesn't work". As a last resort the system speech dialog
 * is opened through [activityFallback]. The service used and every error are kept for diagnostics.
 * 3.5: a service that listened and understood nothing hands over once to the next one (Google's
 * network recognizer after the on-phone one), and only words make a service "the one that works".
 * 3.7: one recognizer app-wide ([RecognizerGate]): each attempt makes a fresh one only after the last
 * one was cancelled and destroyed and 300 ms have passed, a new listen first ends the one before, and
 * a recognizer is destroyed as soon as its listen is over (3.6 kept it, and the call-audio test then got
 * ERROR_TOO_MANY_REQUESTS). The glasses' call audio and our own audio go to Google's network
 * recognizer first; our own audio never to the on-phone one.
 *
 * 3.1 talking: one engine is started when the app starts ([warmUp]), the language is only set when
 * it changes, and answers can be spoken sentence by sentence while they're still arriving
 * ([beginStream] / [streamSentence] / [endStream]).
 */
class Speech(context: Context, private val prefs: Prefs) {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    // ---------------- listening ----------------

    /** A recognition service to try: a component, the on-phone recognizer, or the phone's default. */
    data class Service(val key: String, val label: String, val component: ComponentName?, val onDevice: Boolean = false)

    /** 3.6: false while Conversation translate listens: a phrase nobody said must never open the speech dialog. */
    var dialogAllowed = true

    /** Set by the activity: opens the system speech dialog; delivers the text (or null and a reason). */
    var activityFallback: ((tag: String, deliver: (String?, String?) -> Unit) -> Boolean)? = null

    /** The service that answered the last listen, and why the last one failed, for the screen and diagnostics. */
    var lastService: String? = null; private set
    var lastErrorDetail: String? = null; private set
    /** Every service tried in the last listen, with what happened. */
    var lastAttempts: List<String> = emptyList(); private set
    /** When the last final result arrived (elapsedRealtime), for the timing lines. */
    var resultAt = 0L; private set

    private var recognizer: SpeechRecognizer? = null
    private var recognizerKey: String? = null
    /** 3.7: the single-owner bookkeeping (at most one recognizer, a cooldown between them). */
    private val gate = RecognizerGate(COOLDOWN_MS)
    /** 3.7: this listen asked for Google's network recognizer first (the glasses' call audio). */
    private var networkFirst = false
    /** 3.5: makes the app's own audio for one attempt (the glasses' data-link mic), or null for the recognizer's own mic. */
    private var audioFactory: (() -> AudioFeed?)? = null
    private var activeFeed: AudioFeed? = null
    /** True when the last listen was fed by the app's own audio. */
    var lastFed = false; private set
    /** 3.5: the last listen ended because the service doesn't take the app's own audio (it was marked "no pipe"). */
    var lastPipeRejected = false; private set
    /** 3.5: a service that gave "no match" in this listen; it is tried last from now on if another one understands. */
    private var suspectNoMatch: String? = null
    private var triedAnother = false

    private fun endFeed() {
        activeFeed?.stop()
        activeFeed = null
    }
    private var listenToken = 0
    private var attemptId = 0

    private fun services(): List<Pair<String, ComponentName>> = runCatching {
        app.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0).mapNotNull { ri ->
            val si = ri.serviceInfo ?: return@mapNotNull null
            val label = runCatching { ri.loadLabel(app.packageManager).toString() }.getOrDefault(si.packageName)
            label to ComponentName(si.packageName, si.name)
        }
    }.getOrDefault(emptyList())

    private fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(app) }.getOrDefault(false)

    private fun dialogAvailable(): Boolean = runCatching {
        app.packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty()
    }.getOrDefault(false)

    /**
     * The order services are tried in (3.5, [VoiceChoice.recognizerOrder]): the one that last gave words,
     * then Google's network recognizer, the other known ones, the on-phone one, the rest, the default.
     * A service that said "no match" while another understood goes last. [piped]: our own audio, so
     * services marked "no pipe" are left out.
     */
    fun candidates(piped: Boolean = false, networkFirst: Boolean = false): List<Service> {
        val found = services()
        val onDevice = onDeviceAvailable()
        val defaultOk = runCatching { SpeechRecognizer.isRecognitionAvailable(app) }.getOrDefault(false)
        val keys = VoiceChoice.recognizerOrder(
            found.map { VoiceChoice.Rec(it.second.flattenToString(), it.second.packageName) }, onDevice, defaultOk,
            prefs.recognizerService, prefs.recognizerNoMatch, prefs.recognizerNoPipe, piped, networkFirst,
        )
        return keys.mapNotNull { k ->
            when (k) {
                ON_DEVICE -> Service(ON_DEVICE, "on-phone recognizer", null, onDevice = true)
                DEFAULT -> Service(DEFAULT, "phone default", null)
                else -> found.firstOrNull { it.second.flattenToString() == k }?.let { Service(k, it.first, it.second) }
            }
        }
    }

    fun recognitionAvailable(): Boolean =
        services().isNotEmpty() || runCatching { SpeechRecognizer.isRecognitionAvailable(app) }.getOrDefault(false) ||
            onDeviceAvailable() || (activityFallback != null && dialogAvailable())

    /** Plain lines about the recognition services on this phone, for diagnostics. */
    fun serviceReport(): List<String> = buildList {
        val found = services()
        add("speech recognition services: " + if (found.isEmpty()) "none found" else found.joinToString(", ") { "${it.first} (${it.second.packageName})" })
        add("on-phone recognizer: " + if (onDeviceAvailable()) "available" else "not available")
        add("speech dialog fallback: " + if (dialogAvailable()) "available" else "not available")
        add("service that worked last: " + (prefs.recognizerService?.let { k -> if (k == ON_DEVICE) "on-phone recognizer" else k } ?: "none yet"))
        prefs.recognizerNoMatch.takeIf { it.isNotEmpty() }?.let { add("tried last (said \"no match\" while another understood): ${it.joinToString(", ")}") }
        prefs.recognizerNoPipe.takeIf { it.isNotEmpty() }?.let { add("don't take the app's own audio: ${it.joinToString(", ")}") }
        add("try order: " + candidates().joinToString(" → ") { it.label })
    }

    /**
     * Listens for one sentence in [languageTag]. Exactly one of [onResult] or [onError] is called,
     * unless [cancelListening] comes first. [onError] gets the message and the speech error code
     * (or -1); [lastErrorDetail] says which service gave it. Without [audio] the recognizer opens the
     * mic itself (3.5: the phone's mic, or the glasses' when their call audio is the call device);
     * with it, each attempt is fed the app's own audio (Android 13+), and a service that can't take it
     * ends the listen with [lastPipeRejected] set.
     */
    fun listen(
        languageTag: String,
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (String, Int) -> Unit,
        audio: (() -> AudioFeed?)? = null,
        networkFirst: Boolean = false,
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { listen(languageTag, onPartial, onResult, onError, audio, networkFirst) }
            return
        }
        // 3.7: never two listens at once: the one before ends here (its callbacks are dropped by the token).
        attemptId++
        endFeed()
        destroyRecognizer()
        this.networkFirst = networkFirst
        val piped = audio != null && Build.VERSION.SDK_INT >= 33
        audioFactory = if (piped) audio else null
        lastFed = false
        lastPipeRejected = false
        suspectNoMatch = null
        triedAnother = false
        val token = ++listenToken
        lastAttempts = emptyList()
        lastErrorDetail = null
        val chain = candidates(piped, networkFirst)
        if (piped && chain.isEmpty()) {
            lastPipeRejected = true
            note("no speech service takes the app's own audio")
            main.post { if (token == listenToken) onError("No speech service on this phone takes the glasses' own audio.", SpeechRecognizer.ERROR_CLIENT) }
            return
        }
        attempt(chain, 0, languageTag, token, null, onPartial, onResult, onError)
    }

    private fun note(line: String) { lastAttempts = (lastAttempts + line).takeLast(12) }

    private fun attempt(
        chain: List<Service>,
        index: Int,
        tag: String,
        token: Int,
        lastFailure: Pair<String, Int>?,
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (String, Int) -> Unit,
    ) {
        if (token != listenToken) return
        if (index >= chain.size) {
            // Every service failed: the system speech dialog, if there is one (it records the phone's mic itself).
            val fb = activityFallback
            if (fb != null && audioFactory == null && dialogAllowed && dialogAvailable()) {
                note("speech dialog: opened")
                lastService = "speech dialog"
                val opened = fb(tag) { text, why ->
                    if (token != listenToken) return@fb
                    resultAt = SystemClock.elapsedRealtime()
                    if (!text.isNullOrBlank()) onResult(text.trim())
                    else onError(why ?: errorText(SpeechRecognizer.ERROR_NO_MATCH), SpeechRecognizer.ERROR_NO_MATCH)
                }
                if (opened) return
            }
            val (msg, code) = lastFailure ?: ("This phone has no working speech recognition. Install or turn on Google's speech services." to -1)
            onError(msg, code)
            return
        }
        val svc = chain[index]
        // 3.7: one recognizer at a time, freshly made, 300 ms after the last one was destroyed.
        destroyRecognizer()
        val wait = gate.waitBeforeCreate(SystemClock.elapsedRealtime())
        if (wait > 0) {
            main.postDelayed({ attempt(chain, index, tag, token, lastFailure, onPartial, onResult, onError) }, wait)
            return
        }
        val r = try {
            when {
                svc.onDevice && Build.VERSION.SDK_INT >= 31 -> SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
                svc.component != null -> SpeechRecognizer.createSpeechRecognizer(app, svc.component)
                else -> SpeechRecognizer.createSpeechRecognizer(app)
            }
        } catch (e: Exception) {
            note("${svc.label}: couldn't start (${e.javaClass.simpleName})")
            gate.released(SystemClock.elapsedRealtime())
            main.post { attempt(chain, index + 1, tag, token, lastFailure, onPartial, onResult, onError) }
            return
        }
        gate.created(SystemClock.elapsedRealtime())
        recognizer = r
        recognizerKey = svc.key
        val kind = when {
            svc.onDevice -> "on-phone"
            svc.component?.packageName == VoiceChoice.GOOGLE_APP -> "Google network"
            else -> "service"
        }
        val myAttempt = ++attemptId
        val feedWanted = audioFactory != null
        var ready = false
        var partials = false
        var finished = false
        var feed: AudioFeed? = null
        fun next(code: Int, why: String, moveOn: Boolean = false) {
            if (finished) return
            finished = true
            endFeed()
            destroyRecognizer()
            note("${svc.label}: $why")
            // A service that only said "no match" stays remembered until another one understands.
            if (!moveOn && prefs.recognizerService == svc.key) prefs.recognizerService = null
            val failure = errorText(code) to code
            main.post { attempt(chain, index + 1, tag, token, failure, onPartial, onResult, onError) }
        }
        /** Words came back: this service is the one to try first, and a service that said "no match" before it goes last. */
        fun works() {
            lastService = svc.label
            if (prefs.recognizerService != svc.key) prefs.recognizerService = svc.key
            if (svc.key in prefs.recognizerNoMatch) prefs.recognizerNoMatch = prefs.recognizerNoMatch - svc.key
            suspectNoMatch?.takeIf { it != svc.key }?.let { bad ->
                prefs.recognizerNoMatch = prefs.recognizerNoMatch + bad
                note("${svc.label} understood after $bad said no match: $bad goes last from now on")
                suspectNoMatch = null
            }
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { ready = true }
            override fun onBeginningOfSpeech() { ready = true }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                if (token != listenToken || myAttempt != attemptId || finished) return
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) { partials = true; onPartial(text) }
            }

            override fun onResults(results: Bundle?) {
                if (token != listenToken || myAttempt != attemptId || finished) return
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                if (text.isNullOrEmpty()) { onError(SpeechRecognizer.ERROR_NO_MATCH); return }
                finished = true
                val heard = feed?.heardSpeech
                endFeed()
                // 3.7: the listen is over: let this recognizer go (unless a new listen already replaced it).
                gate.stoppedListening()
                main.post { if (recognizer === r) destroyRecognizer() }
                resultAt = SystemClock.elapsedRealtime()
                works()
                note("${svc.label} ($kind): OK, heard \"${text.take(60)}\"" + if (feed != null) " (fed the app's own audio${if (heard == true) ", speech heard" else ""})" else "")
                onResult(text)
            }

            override fun onError(error: Int) {
                if (token != listenToken || myAttempt != attemptId || finished) return
                gate.stoppedListening()
                val detail = "${errorName(error)} ($error) from ${svc.label} ($kind)" + if (feed != null) " fed the app's own audio" else ""
                lastErrorDetail = detail
                val micOk = app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                val vadHeard = feed?.heardSpeech == true
                when (VoiceChoice.onError(error, ready, partials, feed != null, vadHeard, triedAnother, micOk)) {
                    VoiceChoice.OnError.NEXT_SERVICE -> next(error, "${errorName(error)} ($error)" + if (!ready) " before listening" else "")
                    VoiceChoice.OnError.NEXT_SERVICE_ONCE -> {
                        // 3.5: it listened and understood nothing: the next service listens once more (often
                        // Google's network recognizer after the on-phone one). If that one understands, this one goes last.
                        triedAnother = true
                        if (suspectNoMatch == null) suspectNoMatch = svc.key
                        if (index + 1 < chain.size) {
                            next(error, "${errorName(error)} ($error), listening once more with ${chain[index + 1].label}", moveOn = true)
                        } else giveUp(error)
                    }
                    VoiceChoice.OnError.NO_PIPE -> {
                        finished = true
                        val fed = feed?.report()
                        endFeed()
                        destroyRecognizer()
                        prefs.recognizerNoPipe = prefs.recognizerNoPipe + svc.key
                        lastPipeRejected = true
                        note("${svc.label}: ${errorName(error)} ($error) with the app's own audio" + (fed?.let { " [$it]" } ?: "") +
                            "; marked as not taking it")
                        onError(errorText(error), error)
                    }
                    VoiceChoice.OnError.GIVE_UP -> giveUp(error)
                }
            }

            private fun giveUp(error: Int) {
                finished = true
                val fed = feed?.report()
                endFeed()
                destroyRecognizer()
                note("${svc.label}: ${errorName(error)} ($error)" + (fed?.let { " [$it]" } ?: ""))
                onError(errorText(error), error)
            }
        })
        // A broken service may never answer at all: move on after 4 s without "ready".
        main.postDelayed({
            if (token == listenToken && myAttempt == attemptId && !ready && !finished) next(SpeechRecognizer.ERROR_CLIENT, "no answer in 4 s")
        }, 4_000)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, svc.onDevice)
            // 3.7: the glasses' call audio and our own audio: Google's network recognizer, not offline.
            if (networkFirst || feedWanted) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
            // End the turn sooner after the person stops talking (engines that ignore these are fine).
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L)
        }
        // 3.5: only the glasses' data-link mic is fed through a pipe; the phone's mic and the glasses'
        // call audio are recorded by the recognizer itself (3.4's own phone recording heard nothing).
        endFeed()
        val factory = audioFactory
        if (factory != null && Build.VERSION.SDK_INT >= 33) {
            val f = runCatching { factory() }.getOrNull()
            if (f == null || !f.start()) {
                f?.stop()
                finished = true
                destroyRecognizer()
                lastPipeRejected = true
                note("${svc.label}: the app's own audio didn't start")
                onError("The glasses' mic stream stopped.", SpeechRecognizer.ERROR_AUDIO)
                return
            }
            feed = f
            activeFeed = f
            lastFed = true
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, f.readFd)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, MicCapture.RATE)
            note("${svc.label}: fed the app's own audio (16 kHz mono PCM16)")
        }
        if (!gate.startListening()) {
            next(SpeechRecognizer.ERROR_RECOGNIZER_BUSY, "already listening")
            return
        }
        note("${svc.label} ($kind): listening" + when {
            feed != null -> " to the app's own audio"
            networkFirst -> " (it records the call-audio mic itself)"
            else -> " (it records the mic itself)"
        })
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            next(SpeechRecognizer.ERROR_CLIENT, "couldn't start listening (${e.javaClass.simpleName})")
        }
    }

    /** 3.7: words came from elsewhere (Gemini): the timing lines count from now. */
    fun markResult() { resultAt = SystemClock.elapsedRealtime() }

    /** Stop listening and use what was heard so far. */
    fun finishListening() {
        runCatching { recognizer?.stopListening() }
    }

    /** Stop listening and throw away what was heard. */
    fun cancelListening() {
        listenToken++
        attemptId++
        endFeed()
        // 3.7: cancel() and destroy(), so nothing is left alive for the next listen.
        destroyRecognizer()
    }

    private fun destroyRecognizer() {
        recognizer?.let { r ->
            runCatching { r.cancel() }
            runCatching { r.destroy() }
            gate.released(SystemClock.elapsedRealtime())
        }
        recognizer = null
        recognizerKey = null
    }

    // ---------------- talking ----------------

    /** Speech rate and pitch (1.0 = normal), applied before every answer. */
    var rate = 1f
    var pitch = 1f

    private var tts: TextToSpeech? = null
    private var ttsState = 0 // 0 starting, 1 ready, -1 failed
    private val ttsWaiting = mutableListOf<(Boolean) -> Unit>()
    private var speakToken = 0
    private var currentLocale: Locale? = null
    private var currentUsage = -1
    private var currentRate = -1f
    private var currentPitch = -1f

    /** One answer being spoken, possibly while it's still arriving. */
    private inner class Session(
        val token: Int,
        val onFirstAudio: (() -> Unit)?,
        val onDone: (String?) -> Unit,
    ) {
        var queued = 0
        var done = 0
        var ended = false
        var started = false
        var finished = false
        var ready = false
        var error: String? = null
        val waiting = mutableListOf<String>()
        var leadInMs = 0L
    }

    private var session: Session? = null

    /** Starts the speech engine now, so the first answer doesn't wait for it. */
    fun warmUp() = withTts { }

    private fun withTts(then: (Boolean) -> Unit) {
        when (ttsState) {
            1 -> then(true)
            -1 -> {
                // Try again in case a voice was installed since.
                tts = null; ttsState = 0
                withTts(then)
            }
            else -> {
                ttsWaiting += then
                if (tts == null) {
                    tts = TextToSpeech(app) { status ->
                        main.post {
                            ttsState = if (status == TextToSpeech.SUCCESS) 1 else -1
                            if (ttsState == 1) tts?.setOnUtteranceProgressListener(progress)
                            currentLocale = null; currentUsage = -1; currentRate = -1f; currentPitch = -1f
                            val list = ttsWaiting.toList()
                            ttsWaiting.clear()
                            list.forEach { it(ttsState == 1) }
                        }
                    }
                }
            }
        }
    }

    private fun tokenOf(id: String?): Int? = id?.split('-')?.getOrNull(1)?.toIntOrNull()

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            if (utteranceId?.startsWith("p3-") != true) return
            val t = tokenOf(utteranceId)
            main.post {
                val s = session ?: return@post
                if (s.token == t && !s.started) { s.started = true; s.onFirstAudio?.invoke() }
            }
        }
        override fun onDone(utteranceId: String?) {
            if (utteranceId?.startsWith("p3-") != true) return
            val t = tokenOf(utteranceId)
            main.post {
                val s = session ?: return@post
                if (s.token != t) return@post
                s.done++
                if (s.ended && s.done >= s.queued) finish(s, null)
            }
        }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            val t = tokenOf(utteranceId)
            main.post { session?.takeIf { it.token == t }?.let { finish(it, "Couldn't read the answer aloud.") } }
        }
        override fun onError(utteranceId: String?, errorCode: Int) {
            val t = tokenOf(utteranceId)
            main.post { session?.takeIf { it.token == t }?.let { finish(it, "Couldn't read the answer aloud (error $errorCode).") } }
        }
    }

    private fun finish(s: Session, error: String?) {
        if (s.finished) return
        s.finished = true
        if (session === s) session = null
        s.onDone(error)
    }

    /** Sets language, voice usage, speed and pitch only when they changed. Returns a message if it can't speak. */
    private fun prepare(engine: TextToSpeech, languageTag: String, throughCall: Boolean): String? {
        val locale = Locale.forLanguageTag(languageTag)
        if (locale != currentLocale) {
            val res = engine.setLanguage(locale)
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                currentLocale = null
                return "This phone has no ${locale.getDisplayLanguage(Locale.ENGLISH)} voice for reading aloud. " +
                    "You can add one in Settings › Text-to-speech."
            }
            currentLocale = locale
        }
        if (rate != currentRate) { engine.setSpeechRate(rate); currentRate = rate }
        if (pitch != currentPitch) { engine.setPitch(pitch); currentPitch = pitch }
        val usage = if (throughCall) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT
        if (usage != currentUsage) {
            engine.setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            currentUsage = usage
        }
        return null
    }

    /**
     * Starts speaking an answer that arrives in pieces. [throughCall] sends it down the hands-free
     * link; otherwise it plays on the media stream (the glasses' stereo audio when connected).
     * [leadInMs] of silence first gives a Bluetooth headset time to switch from the call link.
     * [onFirstAudio] runs when the first words start; [onDone] gets null when all was spoken.
     */
    fun beginStream(
        languageTag: String,
        throughCall: Boolean,
        leadInMs: Long = 0,
        onFirstAudio: (() -> Unit)? = null,
        onDone: (String?) -> Unit,
    ) {
        session?.let { old -> session = null; old.finished = true }
        val s = Session(++speakToken, onFirstAudio, onDone)
        s.leadInMs = leadInMs
        session = s
        withTts { ok ->
            if (session !== s || s.finished) return@withTts
            val engine = tts
            if (!ok || engine == null) {
                finish(s, "This phone has no text-to-speech voice. Add one in Settings › Accessibility › Text-to-speech.")
                return@withTts
            }
            prepare(engine, languageTag, throughCall)?.let { finish(s, it); return@withTts }
            s.ready = true
            if (s.leadInMs > 0) engine.playSilentUtterance(s.leadInMs, TextToSpeech.QUEUE_FLUSH, "p3s-${s.token}")
            val pending = s.waiting.toList()
            s.waiting.clear()
            pending.forEach { enqueue(s, engine, it) }
            if (s.ended && s.done >= s.queued) finish(s, null)
        }
    }

    /** Adds one sentence (or any text) to the answer being spoken. */
    fun streamSentence(text: String) {
        val s = session ?: return
        if (s.finished) return
        val engine = tts
        if (!s.ready || engine == null) { s.waiting += text; return }
        enqueue(s, engine, text)
    }

    private fun enqueue(s: Session, engine: TextToSpeech, text: String) {
        val chunks = AiWire.speechChunks(AiWire.speakable(text), TextToSpeech.getMaxSpeechInputLength() - 100)
        for (chunk in chunks) {
            val id = "p3-${s.token}-${s.queued}"
            val mode = if (s.queued == 0 && s.leadInMs <= 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            s.queued++
            if (engine.speak(chunk, mode, null, id) != TextToSpeech.SUCCESS) {
                finish(s, "Couldn't read the answer aloud.")
                return
            }
        }
    }

    /** No more text is coming: [onDone] runs once everything queued has been spoken. */
    fun endStream() {
        val s = session ?: return
        s.ended = true
        if (s.ready && s.done >= s.queued) finish(s, null)
    }

    /**
     * Reads [text] aloud in [languageTag]. [throughCall] sends it down the hands-free link (the
     * glasses' speaker during a voice turn). [onDone] gets null when finished, or a message
     * saying why it couldn't be read aloud.
     */
    fun speak(text: String, languageTag: String, throughCall: Boolean, leadInMs: Long = 0, onFirstAudio: (() -> Unit)? = null, onDone: (String?) -> Unit) {
        beginStream(languageTag, throughCall, leadInMs, onFirstAudio, onDone)
        streamSentence(text)
        endStream()
    }

    /** Stop talking without calling the pending done callback. */
    fun stopSpeaking() {
        speakToken++
        session?.finished = true
        session = null
        runCatching { tts?.stop() }
    }

    val speaking: Boolean get() = session != null

    fun shutdown() {
        cancelListening()
        destroyRecognizer()
        stopSpeaking()
        runCatching { tts?.shutdown() }
        tts = null
        ttsState = 0
    }

    companion object {
        const val ON_DEVICE = VoiceChoice.ON_DEVICE
        /** 3.7: the wait between destroying one recognizer and making the next. */
        const val COOLDOWN_MS = 300L
        const val DEFAULT = VoiceChoice.DEFAULT

        fun errorName(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
            SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
            SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
            SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "ERROR_TOO_MANY_REQUESTS"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "ERROR_SERVER_DISCONNECTED"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "ERROR_LANGUAGE_NOT_SUPPORTED"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "ERROR_LANGUAGE_UNAVAILABLE"
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "ERROR_CANNOT_CHECK_SUPPORT"
            SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS -> "ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS"
            else -> "ERROR_$code"
        }

        fun errorText(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                "I didn't catch that. Tap and try again."
            SpeechRecognizer.ERROR_AUDIO -> "The microphone isn't working right now. Is another app using it?"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "The speech service isn't allowed to use the microphone. Allow the microphone for P3 Companion and for your speech app."
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                "Speech recognition needs the internet for this language. Check your connection."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognition is busy. Try again in a moment."
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Speech recognition asked us to slow down. Try again in a minute."
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                "This phone can't recognise speech in that language yet. Add it in your phone's speech recognition settings."
            SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
                "The speech service stopped. Try again."
            SpeechRecognizer.ERROR_CLIENT -> "The speech service on this phone didn't start. Try again, or set Google as the voice input app."
            else -> "Listening stopped (error $code). Try again."
        }
    }
}
