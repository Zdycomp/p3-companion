package com.zdycomp.p3companion

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.SpeechRecognizer
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The AI assistant behind the Assistant tab: voice chat, live translation and questions about
 * photos. One instance for the whole app, so a conversation survives tab changes.
 *
 * 3.1: every voice-chat sentence first goes through the offline [VoiceCommands] (handled by
 * [commands] in a few milliseconds); AI answers are streamed and spoken sentence by sentence; with
 * "Speak through: Glasses audio (fast)" the call link is only used for the mic and answers play on
 * the media stream; with "Call audio" the link stays up for the whole voice session.
 *
 * Call everything on the main thread; listeners are told about changes there too.
 */
class Assistant private constructor(context: Context, private val prefs: Prefs) {

    enum class Phase { IDLE, LISTENING, THINKING, SPEAKING }
    enum class Mode { CHAT, TRANSLATE, READ_ALOUD, CONVERSATION }
    enum class Who { YOU, AI, NOTE, ERROR }

    data class Line(val who: Who, val text: String)

    fun interface Listener { fun onAssistantChanged() }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val listeners = mutableSetOf<Listener>()
    val speech = Speech(app, prefs)
    val route: AudioRoute = AudioRoute.get(app, prefs)
    private val glasses = XkGlasses.get(app, prefs)

    /** Runs offline voice commands; set by the activity while it's on screen. */
    var commands: CommandRunner? = null

    // Voice: chat and translation
    @Volatile var phase = Phase.IDLE; private set
    var mode = Mode.CHAT; private set
    var partial: String? = null; private set
    /** Whether the current turn's mic is the glasses'. */
    var throughGlasses = false; private set
    val chat = mutableListOf<Line>()
    val translations = mutableListOf<Line>()
    private val history = mutableListOf<AiTurn>()
    private var turn = 0
    private var holdingRoute = false

    /** The last listening problem in full (service and error code), for the screen. */
    var lastVoiceError: String? = null; private set
    /** The last voice timing line ("heard → first audio 640 ms"), for the screen and diagnostics. */
    var lastTiming: String? = null; private set

    private class Pending(val match: Match, val expiresAt: Long)
    private var pendingConfirm: Pending? = null

    // Photo questions
    var photoUri: Uri? = null
    var photoBusy = false; private set
    var photoStatus: String? = null; private set
    var photoQuestion: String? = null; private set
    var photoAnswer: String? = null; private set
    var photoError: String? = null; private set
    private var photoTurn = 0

    private val lingerRelease = Runnable { if (phase == Phase.IDLE) releaseRouteNow() }

    init {
        // 3.5: every change of the glasses' call audio goes to the connection log.
        route.log = { glasses.addLog("Voice: $it") }
        // 4.0: which voice speaks, and Gemini's voice timings, too.
        speech.log = { glasses.addLog(it) }
        // 4.0: the newest Gemini models for this key (at most once a week; the key is never logged).
        io.execute { runCatching { AiClient.refreshGeminiModels(prefs)?.let { main.post { glasses.addLog("Gemini models: $it") } } } }
        // The speech engine and the command patterns are ready before the first question.
        speech.warmUp()
        io.execute { runCatching { VoiceCommands.match("warm up") } }
    }

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }
    private fun changed() = listeners.toList().forEach { it.onAssistantChanged() }

    fun hasKey(): Boolean = prefs.aiKey() != null

    // ---------------- voice turns ----------------

    /** One spoken question to the AI, answered out loud. Tap again to finish or stop. [hold]: hold to talk (ends on release). */
    fun voiceChat(hold: Boolean = false) = startTurn(Mode.CHAT, hold)

    /** 4.0: the mic's level in dBFS while listening (-120 when quiet or not listening), for the level meter. */
    var micLevelDb = -120.0; private set
    /** 4.0: told about every level change (about 15 times a second) without rebuilding the screen. */
    var levelListener: ((Double) -> Unit)? = null
    /** 4.0: this turn is hold to talk: only letting go ends it. */
    var holding = false; private set
    private var releasedEarly = false

    /** 4.0: hold to talk was let go: the recording ends and is used. */
    fun releaseHold() {
        if (!holding) return
        holding = false
        if (phase != Phase.LISTENING) return
        val end = clipEnd
        // Let go before the mic was ready: the usual end of speech (a pause) ends it instead.
        if (end != null) end() else if (listeningLive) speech.finishListening() else releasedEarly = true
    }

    private fun level(db: Double) {
        micLevelDb = db
        levelListener?.invoke(db)
    }

    /** 4.0: true while the phone's recognizer (not our own recorder) is listening. */
    private var listeningLive = false
    /** 4.0: Gemini speech-to-text failed with no connection; the recognizer listens until this time. */
    private var sttBackoffUntil = 0L

    /** 4.0: Gemini writes down what's said: a Gemini key, the setting on, online, and no recent network failure. */
    fun geminiSttWanted(): Boolean = prefs.geminiStt && prefs.aiKey(AiProvider.GEMINI) != null &&
        SystemClock.elapsedRealtime() >= sttBackoffUntil && Net.online(app)

    /** 4.0: the model that writes down speech: the newest Flash model found, else the built-in one. */
    private fun sttModel(): String = prefs.geminiTextAuto ?: AiWire.TRANSCRIBE_MODEL

    /** One spoken sentence, translated and spoken in the other language. */
    fun translate(hold: Boolean = false) = startTurn(Mode.TRANSLATE, hold)

    /** What a tap on the glasses or the big button does while a turn is running. */
    fun tapWhileBusy() {
        if (session != null) { sessionTap(); return }
        if (phase == Phase.LISTENING) clipEnd?.invoke() ?: speech.finishListening() else stop()
    }

    private fun log(m: Mode) = when (m) {
        Mode.TRANSLATE -> translations
        Mode.CONVERSATION -> convoNotes
        else -> chat
    }

    private fun add(list: MutableList<Line>, who: Who, text: String): Line {
        val line = Line(who, text)
        list += line
        while (list.size > 60) list.removeAt(0)
        return line
    }

    /** Adds a note line to the conversation (the full command list, a later answer…). */
    fun note(text: String) { add(chat, Who.NOTE, text); changed() }

    /** The assistant's last answer, for "say that again". */
    fun lastAiLine(): String? = chat.lastOrNull { it.who == Who.AI }?.text

    private fun callAudio() = prefs.speakThrough == Prefs.SpeakThrough.CALL

    /**
     * 3.3: while this voice session's call link (SCO) is up, the answer is spoken on it right away: no
     * switch to media audio (that switch cost about 1.5 s). Media audio only when the link isn't up.
     */
    private fun throughCallNow() = holdingRoute && route.active

    /** Which mic the current turn listens with, for the log and the timing line. */
    private var micNote: String? = null

    /** A short silence first when the call link was let go a moment ago, so the headset can switch. */
    private fun leadIn(): Long = if (!throughCallNow() && SystemClock.elapsedRealtime() - route.releasedAt < 1_200) 350 else 0

    private fun replyTag() = Languages.byName(prefs.replyLanguage).tag

    /** 3.5: the mic the current (or last) question used, in plain words for the Assistant screen. */
    var micInUse: String? = null; private set
    private var routes: List<MicRoute> = emptyList()
    private var routeNow: MicRoute? = null
    private var routeIndex = 0
    private var spp: GlassesMicStream? = null

    private fun startTurn(m: Mode, hold: Boolean = false) {
        // 4.1: during hands-free voice chat the mic button (or a tap) controls the turn.
        if (session != null && m == Mode.CHAT) { sessionTap(); return }
        if (phase != Phase.IDLE) { tapWhileBusy(); return }
        holding = hold
        releasedEarly = false
        listeningLive = false
        micLevelDb = -120.0
        val lines = log(m)
        if (m == Mode.CONVERSATION && (!convoOn || convoPaused)) return
        if (!hasKey() && m == Mode.TRANSLATE) {
            add(lines, Who.ERROR, "Add your ${prefs.aiProvider.label} key in AI setup first.")
            changed(); return
        }
        if (!speech.recognitionAvailable() && !geminiSttWanted()) {
            add(lines, Who.ERROR, "This phone has no speech recognition. Install or turn on Google's speech services.")
            changed(); return
        }
        val my = ++turn
        mode = m
        phase = Phase.LISTENING
        partial = null
        lastVoiceError = null
        micNote = null
        throughGlasses = false
        // 3.5: the mic Test voice (or the last questions) found working first, then the others.
        routes = VoiceChoice.routeOrder(
            prefs.micRouteScores, route.headsetConnected(),
            linked = sppLinked(), useGlasses = prefs.useGlassesAudio,
        )
        // 3.7: the data-link mic proven by Test voice but nothing here can turn its audio into words.
        if (prefs.useGlassesAudio && glasses.state == XkGlasses.State.READY && (prefs.micRouteScores[MicRoute.GLASSES_SPP] ?: 0) > 0 &&
            sppMode() == VoiceChoice.SppMode.NONE) glasses.addLog("Voice mic: ${SPP_NEEDS_GEMINI}; using another mic")
        // 4.1: the dynamic policy: live view, a photo coming over, a fresh link or a low battery keep the glasses' mics free.
        if (m != Mode.CONVERSATION) {
            val d = routeDecision(session = false)
            if (d.order == listOf(MicRoute.PHONE) && routes.firstOrNull() != MicRoute.PHONE && glasses.state == XkGlasses.State.READY) {
                glasses.addLog("Voice mic: the phone's mic this time (${d.why})")
                routes = listOf(MicRoute.PHONE)
            }
        }
        // 3.6: a conversation keeps the mic that worked for its last phrase.
        if (m == Mode.CONVERSATION) convoMic?.takeIf { it in routes }?.let { w -> routes = listOf(w) + routes.filter { it != w } }
        if (routes.size > 1 && (m != Mode.CONVERSATION || convoMic == null)) glasses.addLog("Voice: mics to try: " + routes.joinToString(" → ") { it.label })
        changed()
        tryRoute(my, m, 0, null)
    }

    /** The mic the next question starts with. */
    fun plannedMic(): MicRoute = VoiceChoice.routeOrder(
        prefs.micRouteScores, route.headsetConnected(),
        linked = sppLinked(), useGlasses = prefs.useGlassesAudio,
    ).first()

    /** 3.7: how the glasses' data-link audio becomes words here: the pipe, Gemini, or not at all. */
    fun sppMode(): VoiceChoice.SppMode =
        VoiceChoice.sppMode(prefs.pipeState, android.os.Build.VERSION.SDK_INT, prefs.aiKey(AiProvider.GEMINI) != null, preferGemini = prefs.geminiStt)

    private fun sppLinked() = glasses.state == XkGlasses.State.READY && sppMode() != VoiceChoice.SppMode.NONE

    /** 3.7: a line for the Assistant screen about the glasses' data-link mic, or null when there's nothing to say. */
    fun glassesMicNote(): String? {
        if (!prefs.useGlassesAudio || (prefs.micRouteScores[MicRoute.GLASSES_SPP] ?: 0) <= 0) return null
        return when (sppMode()) {
            VoiceChoice.SppMode.GEMINI -> "The glasses' data-link mic is transcribed by Gemini (this phone's speech service doesn't take piped audio)."
            VoiceChoice.SppMode.NONE -> "${SPP_NEEDS_GEMINI.replaceFirstChar { it.uppercase() }}. Questions use the phone mic until a Gemini key is added in AI setup."
            VoiceChoice.SppMode.PIPE -> null
        }
    }

    private fun scoreRoute(r: MicRoute, worked: Boolean) {
        val scores = prefs.micRouteScores.toMutableMap()
        val old = scores[r] ?: 0
        val now = VoiceChoice.scoreAfter(old, worked)
        if (now != old || r !in scores) { scores[r] = now; prefs.micRouteScores = scores }
    }

    private fun useMic(r: MicRoute, note: String) {
        micNote = note
        micInUse = r.label
        glasses.addLog("Voice mic: $note")
        changed()
    }

    /** 3.7: ends the Gemini route's recording now (a tap while listening). */
    private var clipEnd: (() -> Unit)? = null

    private fun stopSpp() {
        clipEnd = null
        stopRecorder?.invoke()
        stopRecorder = null
        spp?.stop()
        spp = null
    }

    /** The mic [routes] index [i] didn't work: note it, count it against that mic, and try the next one. */
    private fun routeFailed(my: Int, m: Mode, i: Int, r: MicRoute, why: String) {
        scoreRoute(r, false)
        val next = routes.getOrNull(i + 1)
        glasses.addLog("Voice mic: the ${r.label} didn't work ($why)" + (next?.let { "; trying the ${it.label}" } ?: ""))
        tryRoute(my, m, i + 1, why)
    }

    private fun tryRoute(my: Int, m: Mode, i: Int, lastWhy: String?) {
        if (my != turn) return
        val r = routes.getOrNull(i)
        if (r == null) {
            stopSpp()
            releaseRouteNow()
            add(log(m), Who.ERROR, "No mic worked" + (lastWhy?.let { ": $it" } ?: ".") + " Run Test voice in Diagnostics to see which one does.")
            if (m == Mode.CONVERSATION) { convoErrors = CONVO_MAX_ERRORS; convoMic = null }
            endTurn()
            return
        }
        routeNow = r
        routeIndex = i
        when (r) {
            MicRoute.PHONE -> {
                stopSpp()
                releaseRouteNow()
                throughGlasses = false
                if (geminiSttWanted()) {
                    useMic(r, "phone (recorded by the app, written down by Gemini)")
                    listenGeminiMic(my, m, r, callAudio = false, device = null)
                } else {
                    useMic(r, "phone (the speech service records it)")
                    listenNow(my, m, null)
                }
            }
            MicRoute.GLASSES_SCO -> {
                stopSpp()
                holdRoute { up ->
                    if (my != turn) return@holdRoute
                    if (!up) { releaseRouteNow(); routeFailed(my, m, i, r, route.lastReport ?: "the call audio didn't open"); return@holdRoute }
                    // 3.6: in a conversation the link checked for the last phrase is still up: listen at once.
                    if (m == Mode.CONVERSATION && convoMic == MicRoute.GLASSES_SCO && route.active) {
                        throughGlasses = true
                        if (geminiSttWanted()) {
                            useMic(r, "glasses through call audio (kept up for the conversation; written down by Gemini)")
                            listenGeminiMic(my, m, r, callAudio = true, device = route.glassesInput())
                        } else {
                            useMic(r, "glasses through call audio (kept up for the conversation)")
                            listenNow(my, m, null)
                        }
                        return@holdRoute
                    }
                    // 3.5: a link that came up must also carry sound (3.4 recorded -83 dBFS digital silence).
                    MicCapture.record(app, route.glassesInput(), MicCapture.PROBE_MS, callAudio = true) { take ->
                        if (my != turn) return@record
                        if (take.db < Level.SILENT_DB) {
                            releaseRouteNow()
                            routeFailed(my, m, i, r, "silent, RMS %.0f dBFS".format(Locale.US, take.db))
                            return@record
                        }
                        route.rememberWorking()
                        throughGlasses = true
                        if (geminiSttWanted()) {
                            useMic(r, "glasses through call audio, RMS %.0f dBFS (recorded by the app, written down by Gemini)".format(Locale.US, take.db))
                            listenGeminiMic(my, m, r, callAudio = true, device = route.glassesInput())
                        } else {
                            useMic(r, "glasses through call audio, RMS %.0f dBFS (the speech service records it)".format(Locale.US, take.db))
                            listenNow(my, m, null)
                        }
                    }
                }
            }
            MicRoute.GLASSES_SPP -> {
                releaseRouteNow()
                stopSpp()
                val mode = sppMode()
                if (mode == VoiceChoice.SppMode.NONE) { routeFailed(my, m, i, r, SPP_NEEDS_GEMINI); return }
                val s = GlassesMicStream(glasses)
                spp = s
                s.start { err ->
                    if (my != turn || spp !== s) { s.stop(); return@start }
                    if (err != null) { stopSpp(); routeFailed(my, m, i, r, err); return@start }
                    throughGlasses = true
                    if (mode == VoiceChoice.SppMode.GEMINI) {
                        useMic(r, "glasses over the data link (Opus, decoded; Gemini transcribes it because the speech service doesn't take piped audio on this phone)")
                        listenGemini(my, m, s)
                    } else {
                        useMic(r, "glasses over the data link (Opus, decoded and fed to the speech service in real time)")
                        listenNow(my, m) { s.newPipe { speech.finishListening() } }
                    }
                }
            }
        }
    }

    /**
     * 3.7: the glasses' data-link audio without the pipe: one utterance recorded with our own
     * end-of-speech detector (800 ms of quiet, at most 15 s), sent to Gemini as a WAV, the words used
     * like a recognizer's. Nothing heard: like "no match"; Gemini failing: the next mic.
     */
    private fun listenGemini(my: Int, m: Mode, s: GlassesMicStream) {
        val key = prefs.aiKey(AiProvider.GEMINI) ?: run { stopSpp(); routeFailed(my, m, routeIndex, MicRoute.GLASSES_SPP, SPP_NEEDS_GEMINI); return }
        val langName = when (m) {
            Mode.CHAT -> prefs.replyLanguage
            Mode.CONVERSATION -> convoListenIn
            else -> prefs.translateFrom
        }
        partial = "(listening through the glasses; Gemini writes it down when you stop)"
        changed()
        // 4.0: our own detector ends it 900 ms after the speech (at most 20 s), or only on release for hold to talk.
        // 4.1: through the voice chain (its newer detector) when that's on; the glasses' own packets are kept for Ogg.
        val dsp = if (prefs.voiceChain) MicDsp(VoiceChain.Params.opus(), if (holding) VadPlus.hold() else VadPlus.gemini()) else null
        val vad: SpeechDetector = dsp?.detector ?: if (holding) Vad.hold() else Vad.gemini()
        var lastLevel = 0L
        val meter: (ShortArray) -> Unit = { pcm ->
            val now = SystemClock.elapsedRealtime()
            if (now - lastLevel >= 66) { lastLevel = now; val db = Level.of(pcm); main.post { if (my == turn) level(db) } }
        }
        val packets = ArrayList<ByteArray>()
        val keep: (ByteArray) -> Unit = { p -> synchronized(packets) { if (packets.size < 1_500) packets += p } }
        s.addListener(meter)
        s.addPacketListener(keep)
        clipEnd = s.captureClip(vad, limitMs = Vad.GEMINI_MAX_MS + 1_000L, dsp = dsp) { clip, how ->
            clipEnd = null
            s.removeListener(meter)
            s.removePacketListener(keep)
            if (my != turn || spp !== s) return@captureClip
            VoiceClips.save(app, VoiceClips.SPP, s.ring.snapshot())
            stopSpp()
            level(-120.0)
            glasses.addLog("Voice mic: glasses data link, %.1f s, peak %.0f dBFS, ended: %s".format(Locale.US, clip.size / MicCapture.RATE.toDouble(), vad.peakDb, how.name.lowercase(Locale.US)))
            if (how == Vad.State.ENDED_NO_SPEECH) { geminiHeard(my, m, MicRoute.GLASSES_SPP, null, null); return@captureClip }
            val pk = synchronized(packets) { OggOpus.trim(packets.toList(), vad.speechStartMs, vad.speechEndMs) }
            transcribeClip(my, m, MicRoute.GLASSES_SPP, Vad.trim(clip, MicCapture.RATE, vad.speechStartMs, vad.speechEndMs), langName, pk)
        }
    }

    /**
     * 4.0: the phone's mic or the glasses' call audio recorded by the app ([VoiceClipRecorder]: noise
     * suppression, auto gain and echo cancelling where the phone has them, our own 900 ms end of speech,
     * at most 20 s), written down by Gemini. If the mic won't open, the phone's speech recognizer listens.
     */
    private fun listenGeminiMic(my: Int, m: Mode, r: MicRoute, callAudio: Boolean, device: android.media.AudioDeviceInfo?) {
        val langName = when (m) {
            Mode.CHAT -> prefs.replyLanguage
            Mode.CONVERSATION -> convoListenIn
            else -> prefs.translateFrom
        }
        partial = if (holding) "(recording while you hold; Gemini writes it down when you let go)" else "(listening; Gemini writes it down when you stop)"
        changed()
        // 4.1: the voice chain on this route too; the phone already suppresses noise here, so only high-pass, AGC, limiter and detector.
        val dsp = if (prefs.voiceChain) MicDsp(VoiceChain.Params.platformCleaned(), if (holding) VadPlus.hold() else VadPlus.gemini()) else null
        val rec = VoiceClipRecorder(app, callAudio, device, if (holding) Vad.hold() else Vad.gemini(), onLevel = { db -> if (my == turn) level(db) }, dsp = dsp) { res ->
            clipEnd = null
            if (my != turn) return@VoiceClipRecorder
            level(-120.0)
            glasses.addLog("Voice mic: ${res.report}")
            if (callAudio) VoiceClips.save(app, VoiceClips.SCO, res.samples)
            if (res.state == Vad.State.ENDED_NO_SPEECH) { geminiHeard(my, m, r, null, null); return@VoiceClipRecorder }
            transcribeClip(my, m, r, Vad.trim(res.samples, MicCapture.RATE, res.speechStartMs, res.speechEndMs), langName)
        }
        val err = rec.start()
        if (err != null) {
            glasses.addLog("Voice mic: our own recording didn't start ($err); the phone's speech recognizer listens instead")
            listenNow(my, m, null)
            return
        }
        clipEnd = { rec.finish() }
        stopRecorder = { rec.cancel() }
    }

    /** 4.0: cancels our own recording when the turn is stopped. */
    private var stopRecorder: (() -> Unit)? = null

    /**
     * 4.0: sends a recorded clip to Gemini to be written down (16 kHz mono WAV), and logs how long it took.
     * 4.1: with the glasses' own Opus [packets], as Ogg Opus first (about 15× smaller, the exact bits the
     * glasses' mic made); refused (or heard as nothing), the WAV goes at once and the answer is remembered.
     */
    private fun transcribeClip(my: Int, m: Mode, r: MicRoute, clip: ShortArray, langName: String, packets: List<ByteArray>? = null) {
        val key = prefs.aiKey(AiProvider.GEMINI) ?: run { geminiHeard(my, m, r, null, SPP_NEEDS_GEMINI); return }
        partial = "(Gemini is writing down what you said…)"
        changed()
        val lang = Languages.byName(langName).name
        val t0 = SystemClock.elapsedRealtime()
        val state = prefs.geminiOggState
        val ogg = AudioUpload.useOgg(state, !packets.isNullOrEmpty())
        io.execute {
            var model = sttModel()
            var sentAs = "WAV"
            var bytes = 0
            var oggNote = ""
            fun send(audio: ByteArray, mime: String): Result<String?> {
                bytes = audio.size
                var res = runCatching { AiClient.transcribe(key, audio, lang, model, mime) }
                res.exceptionOrNull()?.let { e -> if (AiClient.modelGone(e, prefs)) { model = sttModel(); res = runCatching { AiClient.transcribe(key, audio, lang, model, mime) } } }
                return res
            }
            var res: Result<String?>
            if (ogg) {
                sentAs = "Ogg Opus"
                res = send(OggOpus.build(packets!!), OggOpus.MIME)
                val e = res.exceptionOrNull() as? AiException
                val refused = e != null && AudioUpload.isFormatRefusal(e.httpCode, e.message)
                val empty = res.isSuccess && res.getOrNull().isNullOrBlank()
                if (refused || empty) {
                    oggNote = if (refused) "; Gemini refused Ogg Opus (${e!!.httpCode}), sent again as WAV" else "; Gemini heard nothing in the Ogg Opus, sent again as WAV"
                    val wav = send(Wav.pcm16(clip, MicCapture.RATE), "audio/wav")
                    sentAs = "WAV"
                    val wavText = !wav.getOrNull().isNullOrBlank()
                    prefs.geminiOggState = AudioUpload.after(state, ok = false, refused = refused || (empty && wavText && state != AudioUpload.OGG_WORKS))
                    res = wav
                } else if (res.isSuccess) prefs.geminiOggState = AudioUpload.after(state, ok = true, refused = false)
            } else res = send(Wav.pcm16(clip, MicCapture.RATE), "audio/wav")
            val ms = SystemClock.elapsedRealtime() - t0
            main.post {
                if (my != turn) return@post
                glasses.addLog("Voice: Gemini ($model) wrote down %.1f s of audio sent as %s (%d KB) in %d ms%s%s".format(
                    Locale.US, clip.size / MicCapture.RATE.toDouble(), sentAs, (bytes + 1023) / 1024, ms, oggNote,
                    res.exceptionOrNull()?.let { "; failed: ${it.message}" } ?: ""))
                if (res.isFailure && res.exceptionOrNull() is AiException && res.exceptionOrNull()?.message?.startsWith("No internet") == true) {
                    sttBackoffUntil = SystemClock.elapsedRealtime() + 60_000
                }
                geminiHeard(my, m, r, res.getOrNull()?.takeIf { it.isNotBlank() }, res.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName })
            }
        }
    }

    private fun geminiHeard(my: Int, m: Mode, r: MicRoute, text: String?, error: String?) {
        if (my != turn) return
        partial = null
        speech.markResult()
        // 4.1: in voice chat a turn with nothing in it (or a failed transcription) just listens again.
        if (session != null && m == Mode.CHAT) {
            if (text != null) { glasses.addLog("Voice chat: Gemini heard \"$text\" from the ${r.label}"); scoreRoute(r, true); heard(my, m, text) }
            else { if (error != null) add(chat, Who.NOTE, "Didn't get that ($error)."); endTurn() }
            return
        }
        when {
            text != null -> {
                glasses.addLog("Voice: Gemini heard \"$text\" from the ${r.label}")
                scoreRoute(r, true)
                if (m == Mode.CONVERSATION) convoMic = r
                heard(my, m, text)
            }
            error != null && r == MicRoute.GLASSES_SPP -> { throughGlasses = false; routeFailed(my, m, routeIndex, MicRoute.GLASSES_SPP, "Gemini: $error") }
            error != null -> {
                // 4.0: the offline fallback: the phone's speech recognizer listens on the same mic.
                glasses.addLog("Voice: Gemini couldn't write it down ($error); the phone's speech recognizer listens instead")
                partial = "(Gemini couldn't write that down; say it again)"
                changed()
                listenNow(my, m, null)
            }
            m == Mode.CONVERSATION -> {
                convoListenIn = TwoWay.afterSilence(convoListenIn, prefs.translateFrom, prefs.translateTo, prefs.convoTwoWay)
                endTurn()
            }
            else -> {
                add(log(m), Who.ERROR, Speech.errorText(SpeechRecognizer.ERROR_NO_MATCH) + "\n[Gemini heard no speech from the glasses' data-link mic]")
                endTurn()
            }
        }
    }

    private fun listenNow(my: Int, m: Mode, feed: (() -> AudioFeed?)?) {
        val lines = log(m)
        val tag = Languages.byName(when (m) {
            Mode.CHAT -> prefs.replyLanguage
            Mode.CONVERSATION -> convoListenIn
            else -> prefs.translateFrom
        }).tag
        speech.dialogAllowed = m != Mode.CONVERSATION
        listeningLive = true
        speech.listen(
            tag,
            onPartial = { text ->
                if (my != turn) return@listen
                if (m != Mode.CONVERSATION && prefs.voiceCommands && VoiceCommands.isEarlyStop(text)) { stop(); return@listen }
                partial = text; changed()
            },
            onResult = { text ->
                if (my != turn) return@listen
                stopSpp()
                routeNow?.let { scoreRoute(it, true) }
                if (m == Mode.CONVERSATION) convoMic = routeNow
                heard(my, m, text)
            },
            onError = { msg, code ->
                if (my != turn) return@listen
                val r = routeNow
                // A glasses mic that gave nothing, or a speech service that won't take its audio: the next mic.
                // 3.6: in a conversation, silence on the glasses' mic that already gave words is just silence, not a broken mic.
                val silentInConvo = m == Mode.CONVERSATION && r != null && r == convoMic && !speech.lastPipeRejected &&
                    (code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                if (r != null && r != MicRoute.PHONE && !silentInConvo && (speech.lastPipeRejected || shouldRetryWithPhone(code, glassesOrFed = true, alreadyRetried = false))) {
                    stopSpp()
                    if (r == MicRoute.GLASSES_SCO) releaseRouteNow()
                    throughGlasses = false
                    val why = if (speech.lastPipeRejected) "the speech service doesn't take the glasses' own audio"
                    else "${Speech.errorName(code)} ($code)" + (speech.lastErrorDetail?.let { ", $it" } ?: "")
                    routeFailed(my, m, routeIndex, r, why)
                    return@listen
                }
                stopSpp()
                // 3.6: in a conversation silence isn't a problem: listen again (two-way: in the other language).
                if (m == Mode.CONVERSATION && (code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                    convoListenIn = TwoWay.afterSilence(convoListenIn, prefs.translateFrom, prefs.translateTo, prefs.convoTwoWay)
                    endTurn()
                    return@listen
                }
                if (m == Mode.CONVERSATION) { convoErrors++; convoMic = null }
                lastVoiceError = speech.lastErrorDetail?.let { "$msg ($it)" } ?: msg
                micNote?.let { glasses.addLog("Voice: that try used the $it mic") }
                speech.lastErrorDetail?.let { glasses.addLog("Voice: $it") }
                if (pendingConfirm != null) {
                    pendingConfirm = null
                    add(lines, Who.NOTE, "Cancelled.")
                } else add(lines, Who.ERROR, msg + (speech.lastErrorDetail?.let { "\n[$it]" } ?: ""))
                endTurn()
            },
            audio = feed,
            // 3.7: the glasses' call audio and piped audio: Google's network recognizer first.
            networkFirst = routeNow == MicRoute.GLASSES_SCO,
        )
    }

    private fun heard(my: Int, m: Mode, text: String) {
        if (m == Mode.CONVERSATION) { heardInConversation(my, text); return }
        val lines = log(m)
        partial = null
        add(lines, Who.YOU, text)
        // 3.3: a call link that is up stays up and carries the answer at once (released after speaking);
        // one that never came up is let go so the answer plays on media audio.
        if (holdingRoute && !route.active) releaseRouteNow()
        if (m == Mode.TRANSLATE) {
            if (prefs.voiceCommands && VoiceCommands.isStop(text)) { stop(); return }
            askAi(my, m, text)
            return
        }
        val pend = pendingConfirm?.takeIf { SystemClock.elapsedRealtime() < it.expiresAt }
        pendingConfirm = null
        val runner = commands
        if (runner != null && prefs.voiceCommands) {
            val match = runCatching { VoiceCommands.match(text, runner.state().copy(pendingConfirm = pend?.match?.id)) }.getOrNull()
            if (pend != null) {
                if (match?.id == "sys.yes") runCommand(my, pend.match, confirmed = true)
                else commandSay(my, "sys.no", "Cancelled.")
                return
            }
            if (match != null) { runCommand(my, match, confirmed = false); return }
        } else if (pend != null) {
            commandSay(my, "sys.no", "Cancelled.")
            return
        }
        if (!hasKey()) {
            add(lines, Who.ERROR, "Add your ${prefs.aiProvider.label} key in AI setup first. Voice commands like \"take a picture\" work without it.")
            endTurn(); return
        }
        askAi(my, m, text)
    }

    // ---------------- 4.1: hands-free voice chat through the glasses ----------------

    /**
     * One hands-free voice chat (docs/HARDWARE-MAP.md §5): the mic stays open across turns. On the
     * glasses' data-link mic the Opus stream is started once (about 4 s) and kept warm for the whole
     * chat, so replies stay on media audio with no call mode; every 20 ms goes through the voice chain
     * ([MicDsp]); each turn is sent as Ogg Opus (WAV if Gemini won't take it); while the reply plays the
     * echo gate keeps it out of the next question, and speaking clearly louder (or a tap) interrupts it.
     */
    private inner class ChatSession(val route: MicRoute, val why: String) {
        var stream: GlassesMicStream? = null
        val dsp = MicDsp(if (route == MicRoute.GLASSES_SPP) VoiceChain.Params.opus() else VoiceChain.Params.platformCleaned(), VadPlus.chat())
        val gate = EchoGate(prefs.echoDelayMs)
        @Volatile var barge = BargeIn(withReference = false)
        val lock = Object()
        var capturing = false
        var capTurn = 0
        var pcm = ShortArray(MicCapture.RATE * 4)
        var pcmN = 0
        val packets = ArrayList<ByteArray>()
        val preRoll = ClipRing(MicCapture.RATE * 3 / 10)
        val prePackets = ArrayDeque<ByteArray>()
        var preMs = 0.0
        @Volatile var wasSpeaking = false
        @Volatile var bargePosted = false
        var turnsDone = 0
        var lastHeardAt = SystemClock.elapsedRealtime()
        var recorder: VoiceClipRecorder? = null
        var lastLevelAt = 0L
        var savedVolume: Int? = null
        var noiseDb: Double? = null
    }

    private var session: ChatSession? = null
    private val sessionNext = Runnable { sessionListen() }

    /** 4.1: true while hands-free voice chat is on. */
    val voiceChatOn: Boolean get() = session != null

    /** 4.1: the active voice-chat route in plain words, for the Assistant screen. */
    val voiceChatStatus: String? get() = session?.let { ss ->
        "Voice chat on · " + when (ss.route) {
            MicRoute.GLASSES_SPP -> "glasses mic stream (Opus, kept warm)" + if (ss.stream?.adopted == true) ", started by the glasses" else ""
            MicRoute.GLASSES_SCO -> "glasses call audio"
            MicRoute.PHONE -> "phone mic"
        } + " · " + ss.why + " · sent as " + (if (ss.route == MicRoute.GLASSES_SPP) AudioUpload.label(prefs.geminiOggState) else "WAV")
    }

    /** 4.1: told when voice chat starts or ends (media keys, the screen). */
    var onVoiceChatChanged: ((Boolean) -> Unit)? = null

    /** The last turn's noise floor, for the route policy and the reply volume. */
    private var lastNoiseDb: Double? = null

    /** The dynamic route policy's view of the link, the camera, the battery and the noise right now. */
    fun routeDecision(session: Boolean, warm: Boolean = false): RoutePolicy.Decision = RoutePolicy.decide(RoutePolicy.Inputs(
        linked = glasses.state == XkGlasses.State.READY,
        useGlasses = prefs.useGlassesAudio,
        linkAgeMs = SystemClock.elapsedRealtime() - glasses.readySince,
        liveViewOn = glasses.preview != XkGlasses.Preview.OFF,
        photoTransfer = glasses.photoBusy,
        battery = glasses.battery,
        charging = glasses.charging == true,
        noiseDb = lastNoiseDb,
        scores = prefs.micRouteScores,
        sppUsable = sppMode() != VoiceChoice.SppMode.NONE,
        session = session,
        streamWarm = warm || glasses.glassesMicUnasked,
        pauseLiveViewToListen = prefs.pauseLiveToListen,
    ))

    /** Starts or ends hands-free voice chat. */
    fun toggleVoiceChat() { if (session != null) endVoiceChat("you ended it") else startVoiceChat() }

    /**
     * 4.1: starts hands-free voice chat on the mic the route policy picks (glasses data-link stream →
     * call audio → phone). [adopt]: the glasses started their mic themselves; that stream is used.
     */
    fun startVoiceChat(adopt: Boolean = false) {
        if (session != null) return
        if (phase != Phase.IDLE || convoOn) stop()
        if (!geminiSttWanted()) {
            add(chat, Who.ERROR, "Hands-free voice chat needs Gemini to write down what you say: a Gemini key (AI setup), \"Gemini writes down what you say\" on, and a connection. A single question still works: tap the mic.")
            changed(); return
        }
        val d = routeDecision(session = true, warm = adopt)
        glasses.addLog("Voice chat: mics in order: $d")
        tryStartSession(d.order.filter { !adopt || it == MicRoute.GLASSES_SPP }.ifEmpty { d.order }, 0, d.why, adopt)
    }

    private fun tryStartSession(order: List<MicRoute>, i: Int, why: String, adopt: Boolean) {
        val r = order.getOrNull(i) ?: run {
            add(chat, Who.ERROR, "No mic worked for voice chat. Run Test voice in Diagnostics to see which one does.")
            changed(); return
        }
        val next = { reason: String ->
            glasses.addLog("Voice chat: the ${r.label} didn't start ($reason)")
            scoreRoute(r, false)
            session = null
            tryStartSession(order, i + 1, why, adopt = false)
        }
        when (r) {
            MicRoute.GLASSES_SPP -> {
                releaseRouteNow()
                val ss = ChatSession(r, why)
                session = ss
                val s = GlassesMicStream(glasses)
                ss.stream = s
                s.addListener { pcm -> sessionPcm(ss, pcm) }
                s.addPacketListener { p -> sessionPacket(ss, p) }
                partial = "(starting the glasses' mic stream…)"
                changed()
                val cb: (String?) -> Unit = { err ->
                    if (session !== ss) s.stop()
                    else if (err != null) { s.stop(); next(err) }
                    else sessionReady(ss)
                }
                if (adopt) s.adopt(cb) else s.start(cb)
            }
            MicRoute.GLASSES_SCO -> holdRoute { up ->
                if (session != null) return@holdRoute
                if (!up) { releaseRouteNow(); next(route.lastReport ?: "the call audio didn't open"); return@holdRoute }
                route.rememberWorking()
                sessionReady(ChatSession(r, why).also { session = it })
            }
            MicRoute.PHONE -> sessionReady(ChatSession(r, why).also { session = it })
        }
    }

    private fun sessionReady(ss: ChatSession) {
        if (session !== ss) return
        scoreRoute(ss.route, true)
        ss.dsp.onFrame = { o -> sessionFrame(ss, o) }
        // On call audio the reply plays on the call stream: keep it as loud as media.
        HardwareKeys.syncCallVolume = ss.route == MicRoute.GLASSES_SCO
        if (ss.route == MicRoute.GLASSES_SCO) HardwareKeys.syncNow(app)
        throughGlasses = ss.route != MicRoute.PHONE
        micInUse = ss.route.label
        micNote = "voice chat: ${ss.route.label}"
        add(chat, Who.NOTE, "Voice chat on (${ss.route.label}). Just talk; tap the glasses to send, interrupt or cancel. Say \"stop voice chat\" to end it.")
        glasses.addLog("Voice chat on: ${voiceChatStatus}")
        onVoiceChatChanged?.invoke(true)
        changed()
        sessionListen()
    }

    /** Ends hands-free voice chat (no-op when it's off). */
    fun endVoiceChat(why: String) {
        val ss = session ?: return
        session = null
        HardwareKeys.syncCallVolume = false
        // A turn still on its way (being written down, thought about or spoken) is cancelled with the chat.
        if (phase != Phase.IDLE) { turn++; speech.stopSpeaking(); phase = Phase.IDLE; partial = null }
        main.removeCallbacks(sessionNext)
        synchronized(ss.lock) { ss.capturing = false }
        ss.stream?.stop()
        ss.recorder?.cancel()
        restoreReplyVolume(ss)
        if (ss.route == MicRoute.GLASSES_SCO) releaseRouteNow()
        if (phase == Phase.LISTENING) { phase = Phase.IDLE; partial = null }
        level(-120.0)
        glasses.addLog("Voice chat ended ($why) after ${ss.turnsDone} turn(s)")
        add(chat, Who.NOTE, "Voice chat ended ($why).")
        onVoiceChatChanged?.invoke(false)
        changed()
    }

    /** The next turn: listen on the session's mic (the warm stream, or a new recording on call audio or the phone). */
    private fun sessionListen(barge: Boolean = false) {
        val ss = session ?: return
        if (phase != Phase.IDLE) return
        val now = SystemClock.elapsedRealtime()
        if (now - ss.lastHeardAt > SESSION_IDLE_MS) { endVoiceChat("a minute without a question"); return }
        if (ss.stream?.isStopped == true) { endVoiceChat("the glasses' mic stream stopped"); return }
        // The policy again: live view opened or the battery ran low mid-chat moves it off the glasses' mic.
        if (ss.route != MicRoute.PHONE) {
            val d = routeDecision(session = true, warm = ss.route == MicRoute.GLASSES_SPP)
            if (d.order == listOf(MicRoute.PHONE)) {
                glasses.addLog("Voice chat: moving to the phone's mic (${d.why})")
                endVoiceChatQuietly()
                sessionReady(ChatSession(MicRoute.PHONE, d.why).also { session = it })
                return
            }
        }
        val my = ++turn
        mode = Mode.CHAT
        phase = Phase.LISTENING
        partial = if (barge) "(go ahead…)" else "(voice chat: listening…)"
        changed()
        when (ss.route) {
            MicRoute.GLASSES_SPP -> synchronized(ss.lock) {
                ss.dsp.newTurn(if (barge) VadPlus(endMs = 800, noSpeechMs = 12_000, maxMs = Vad.GEMINI_MAX_MS, startInSpeech = true) else VadPlus.chat())
                val pre = ss.preRoll.snapshot()
                if (ss.pcm.size < pre.size) ss.pcm = ShortArray(MicCapture.RATE * 4)
                System.arraycopy(pre, 0, ss.pcm, 0, pre.size)
                ss.pcmN = pre.size
                ss.preMs = pre.size * 1000.0 / MicCapture.RATE
                ss.packets.clear()
                ss.packets.addAll(ss.prePackets)
                ss.capTurn = my
                ss.capturing = true
                ss.bargePosted = false
            }
            else -> {
                val dsp = MicDsp(VoiceChain.Params.platformCleaned(), VadPlus.chat())
                val rec = VoiceClipRecorder(app, ss.route == MicRoute.GLASSES_SCO, if (ss.route == MicRoute.GLASSES_SCO) route.glassesInput() else null,
                    Vad.gemini(), onLevel = { db -> if (my == turn) level(db) }, dsp = dsp) { res ->
                    ss.recorder = null
                    sessionCaptured(ss, my, res.state, res.samples, emptyList(), res.speechStartMs, res.speechEndMs, 0.0, dsp.detector.floorDb)
                }
                val err = rec.start()
                if (err != null) { endVoiceChat("the mic didn't open: $err"); return }
                ss.recorder = rec
            }
        }
    }

    /** Leaves the current session without the "ended" note (moving to another mic). */
    private fun endVoiceChatQuietly() {
        val ss = session ?: return
        session = null
        synchronized(ss.lock) { ss.capturing = false }
        ss.stream?.stop(); ss.recorder?.cancel()
        restoreReplyVolume(ss)
        if (ss.route == MicRoute.GLASSES_SCO) releaseRouteNow()
    }

    /** The warm stream's packets (reader thread): the last 300 ms always, and the turn's while it records. */
    private fun sessionPacket(ss: ChatSession, p: ByteArray) {
        synchronized(ss.lock) {
            ss.prePackets.addLast(p)
            while (ss.prePackets.size > 15) ss.prePackets.removeFirst()
            if (ss.capturing && ss.gate.isOpen(SystemClock.elapsedRealtime()) && ss.packets.size < 1_500) ss.packets += p
        }
    }

    /** The warm stream's decoded 16 kHz audio (decoder thread). */
    private fun sessionPcm(ss: ChatSession, raw: ShortArray) {
        if (session !== ss) return
        val now = SystemClock.elapsedRealtime()
        val speaking = phase == Phase.SPEAKING
        if (speaking != ss.wasSpeaking) {
            ss.wasSpeaking = speaking
            if (speaking) { ss.gate.playbackStarted(now); ss.barge = BargeIn(withReference = false); main.post { raiseReplyVolume(ss) } }
            else { ss.gate.playbackEnded(now); main.post { restoreReplyVolume(ss) } }
        }
        val open = ss.gate.isOpen(now)
        ss.dsp.frozen = !open
        val out = ss.dsp.process(raw)
        ss.preRoll.add(out)
        var done: (() -> Unit)? = null
        synchronized(ss.lock) {
            if (ss.capturing && open && out.isNotEmpty()) {
                if (ss.pcmN + out.size > ss.pcm.size) ss.pcm = ss.pcm.copyOf(maxOf(ss.pcm.size * 2, ss.pcmN + out.size))
                System.arraycopy(out, 0, ss.pcm, ss.pcmN, out.size)
                ss.pcmN += out.size
                val det = ss.dsp.detector
                if (det.ended) {
                    ss.capturing = false
                    val clip = ss.pcm.copyOf(ss.pcmN); val pk = ss.packets.toList(); val my = ss.capTurn
                    val st = det.state; val a = det.speechStartMs; val b = det.speechEndMs; val pre = ss.preMs; val floor = det.floorDb
                    done = { sessionCaptured(ss, my, st, clip, pk, a, b, pre, floor) }
                }
            }
        }
        done?.let { main.post(it) }
    }

    /** Each analysed 20 ms (decoder thread): the level meter while listening, barge-in while the reply plays. */
    private fun sessionFrame(ss: ChatSession, o: VoiceChain.Out) {
        val now = SystemClock.elapsedRealtime()
        if (phase == Phase.LISTENING && now - ss.lastLevelAt >= 66) {
            ss.lastLevelAt = now
            val db = o.features.eAbs; val my = turn
            main.post { if (my == turn && phase == Phase.LISTENING) level(db) }
        }
        if (phase == Phase.SPEAKING && prefs.voiceBargeIn && !ss.bargePosted &&
            ss.barge.feed(o.features.eAbs, null, o.features.zcr, o.features.deltaE)) {
            ss.bargePosted = true
            main.post { if (session === ss && phase == Phase.SPEAKING) sessionBargeIn(ss, byVoice = true) }
        }
    }

    /** A turn's audio is in: nothing said listens again; otherwise Gemini writes it down and the chat goes on. */
    private fun sessionCaptured(ss: ChatSession, my: Int, state: Vad.State, pcm: ShortArray, packets: List<ByteArray>,
                                startMs: Double, endMs: Double, preMs: Double, floorDb: Double) {
        if (my != turn || session !== ss) return
        level(-120.0)
        if (floorDb > -119) { ss.noiseDb = floorDb; lastNoiseDb = floorDb }
        glasses.addLog("Voice chat: turn %d, %.1f s on the %s, floor %.0f dBFS, ended: %s".format(Locale.US, ss.turnsDone + 1,
            pcm.size / MicCapture.RATE.toDouble(), ss.route.label, floorDb, state.name.lowercase(Locale.US)))
        if (ss.route == MicRoute.GLASSES_SPP) VoiceClips.save(app, VoiceClips.SPP, ss.stream?.ring?.snapshot() ?: pcm)
        if (state == Vad.State.ENDED_NO_SPEECH) {
            phase = Phase.IDLE; partial = null; changed()
            main.removeCallbacks(sessionNext); main.postDelayed(sessionNext, SESSION_GAP_MS)
            return
        }
        ss.turnsDone++
        ss.lastHeardAt = SystemClock.elapsedRealtime()
        phase = Phase.THINKING
        changed()
        val a = if (startMs < 0) -1.0 else startMs + preMs; val b = if (endMs < 0 || a < 0) -1.0 else endMs + preMs
        transcribeClip(my, Mode.CHAT, ss.route, Vad.trim(pcm, MicCapture.RATE, a, b), prefs.replyLanguage,
            if (ss.route == MicRoute.GLASSES_SPP) OggOpus.trim(packets, a, b) else null)
    }

    /** What a tap (or the mic button, or a mapped key) does during voice chat. */
    fun sessionTap() {
        val ss = session ?: return
        when (phase) {
            Phase.LISTENING -> {
                // Send now: what was heard so far is the turn.
                ss.recorder?.let { it.finish(); return }
                var done: (() -> Unit)? = null
                synchronized(ss.lock) {
                    if (ss.capturing) {
                        ss.capturing = false
                        val det = ss.dsp.detector
                        val clip = ss.pcm.copyOf(ss.pcmN); val pk = ss.packets.toList(); val my = ss.capTurn
                        val st = if (det.heardSpeech) Vad.State.ENDED_AFTER_SPEECH else Vad.State.ENDED_NO_SPEECH
                        val a = det.speechStartMs; val pre = ss.preMs; val floor = det.floorDb
                        done = { sessionCaptured(ss, my, st, clip, pk, a, -1.0, pre, floor) }
                    }
                }
                done?.invoke()
            }
            Phase.THINKING -> {
                turn++
                speech.stopSpeaking()
                phase = Phase.IDLE
                partial = null
                add(chat, Who.NOTE, "Cancelled.")
                changed()
                sessionListen()
            }
            Phase.SPEAKING -> sessionBargeIn(ss, byVoice = false)
            Phase.IDLE -> { main.removeCallbacks(sessionNext); sessionListen() }
        }
    }

    /** Interrupts the reply: it stops at once and the next turn starts (by voice: already in speech, with the onset kept). */
    private fun sessionBargeIn(ss: ChatSession, byVoice: Boolean) {
        if (session !== ss) return
        glasses.addLog("Voice chat: reply interrupted " + if (byVoice) "by your voice (barge-in)" else "by a tap")
        turn++
        speech.stopSpeaking()
        ss.gate.openNow(SystemClock.elapsedRealtime())
        ss.wasSpeaking = false
        restoreReplyVolume(ss)
        phase = Phase.IDLE
        partial = null
        main.removeCallbacks(sessionNext)
        sessionListen(barge = byVoice && ss.route == MicRoute.GLASSES_SPP)
    }

    /** §2.8: one or two steps louder in a noisy place, one quieter in a silent room, within the cap; back after the reply. */
    private fun raiseReplyVolume(ss: ChatSession) {
        if (session !== ss || !prefs.replyVolumeAuto || ss.savedVolume != null || ss.route == MicRoute.GLASSES_SCO) return
        val am = app.getSystemService(android.media.AudioManager::class.java) ?: return
        runCatching {
            val st = android.media.AudioManager.STREAM_MUSIC
            val cur = am.getStreamVolume(st); val max = am.getStreamMaxVolume(st)
            val want = VolumePolicy.replyStep(cur, max, ss.noiseDb, prefs.replyVolumeCap)
            if (want != cur) {
                ss.savedVolume = cur
                am.setStreamVolume(st, want, 0)
                glasses.addLog("Voice chat: reply volume %d → %d of %d (noise floor %.0f dBFS)".format(Locale.US, cur, want, max, ss.noiseDb ?: -120.0))
            }
        }
    }

    private fun restoreReplyVolume(ss: ChatSession) {
        val v = ss.savedVolume ?: return
        ss.savedVolume = null
        runCatching { app.getSystemService(android.media.AudioManager::class.java)?.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, v, 0) }
    }


    // ---------------- voice commands ----------------

    private fun runCommand(my: Int, match: Match, confirmed: Boolean, next: (() -> Unit)? = null) {
        val runner = commands ?: run { endTurn(); return }
        val rule = VoiceCommands.rule(match.id)
        if (match.id == "ai.stop") {
            add(chat, Who.NOTE, "Command: stop")
            stop()
            return
        }
        if (rule?.needsConfirm == true && !confirmed) {
            pendingConfirm = Pending(match, SystemClock.elapsedRealtime() + 10_000)
            commandSay(my, match.id, runner.question(match)) {
                // Listen once more for the yes, without a tap.
                main.post { if (pendingConfirm != null && phase == Phase.IDLE) startTurn(Mode.CHAT) }
            }
            return
        }
        val reply = try {
            runner.run(match)
        } catch (e: Exception) {
            CommandRunner.Reply("That didn't work: ${e.message ?: e.javaClass.simpleName}.")
        }
        if (my != turn) return
        // 3.3: a command that keeps the Bluetooth link busy (a photo coming over) gets the call link let go first.
        if (holdingRoute && (glasses.state == XkGlasses.State.BUSY || glasses.audioBlocker() != null)) releaseRouteNow()
        val after = reply.after
        commandSay(my, match.id, reply.say, reply.languageTag, if (next == null) after else ({ after?.invoke(); next() }))
    }

    /** Speaks a command's reply right away (the call link it already has, or media audio). */
    private fun commandSay(my: Int, id: String, text: String?, languageTag: String? = null, after: (() -> Unit)? = null) {
        val label = VoiceCommands.rule(id)?.helpLabel ?: id
        add(chat, Who.NOTE, "Command: $label" + (text?.let { " → $it" } ?: ""))
        val t0 = speech.resultAt
        if (text == null) {
            logTiming("command $id handled in ${SystemClock.elapsedRealtime() - t0} ms (nothing to say)")
            endTurn(); after?.invoke(); return
        }
        phase = Phase.SPEAKING
        changed()
        tune()
        speech.speak(text, languageTag ?: replyTag(), throughCall = throughCallNow(), leadInMs = leadIn(),
            onFirstAudio = { logTiming("command $id: first audio ${SystemClock.elapsedRealtime() - t0} ms after the words were recognised" + audioPath()) },
        ) { err ->
            if (my != turn) return@speak
            if (err != null) add(chat, Who.NOTE, err)
            endTurn()
            after?.invoke()
        }
    }

    /** Says something outside a voice turn (a battery level that arrived a moment later). */
    fun say(text: String) {
        if (phase != Phase.IDLE) { note(text); return }
        val my = ++turn
        mode = Mode.CHAT
        phase = Phase.SPEAKING
        add(chat, Who.NOTE, text)
        changed()
        tune()
        speech.speak(text, replyTag(), throughCall = false, leadInMs = leadIn()) { if (my == turn) endTurn() }
    }

    fun clearPendingConfirm() { pendingConfirm = null }

    private fun audioPath(): String = when {
        throughCallNow() -> ", through the glasses' call audio"
        route.headsetConnected() -> ", through media audio (the glasses when they're the phone's audio device)"
        else -> ", through the phone"
    }

    private fun logTiming(raw: String) {
        val line = raw + (micNote?.let { "; mic: $it" } ?: "")
        lastTiming = line
        glasses.addLog("Voice timing: $line")
        changed()
    }

    // ---------------- AI answers, streamed ----------------

    private fun askAi(my: Int, m: Mode, text: String) {
        val lines = log(m)
        phase = Phase.THINKING
        changed()
        val provider = prefs.aiProvider
        val key = prefs.aiKey(provider) ?: ""
        val model = prefs.aiModel(provider)
        val system: String
        val turns: List<AiTurn>
        val speakTag: String
        // 4.0 simple commands: a Gemini voice-chat turn carries the app's functions, so free speech can work the app.
        val tools = m == Mode.CHAT && provider == AiProvider.GEMINI && commands != null
        if (m == Mode.CHAT) {
            system = if (tools) FnCalls.toolsPrompt(prefs.replyLanguage) else AiWire.systemPrompt(prefs.replyLanguage, spoken = true)
            turns = history.takeLast(prefs.historyLimit) + AiTurn(true, text)
            speakTag = replyTag()
        } else {
            system = AiWire.translationPrompt(prefs.translateFrom, prefs.translateTo)
            turns = listOf(AiTurn(true, text))
            speakTag = Languages.byName(prefs.translateTo).tag
        }
        val speakIt = m != Mode.CHAT || prefs.speakChatAnswers
        val t0 = speech.resultAt.takeIf { it > 0 && SystemClock.elapsedRealtime() - it < 5_000 } ?: SystemClock.elapsedRealtime()
        val buf = StringBuilder()
        val splitter = SentenceSplitter()
        var shown: Line? = null
        var firstTextAt = 0L
        var speaking = false
        var lastPaint = 0L

        fun finishAfterSpeech(err: String?) {
            if (my != turn) return
            if (err != null) add(lines, Who.NOTE, err)
            endTurn()
        }
        fun startSpeech() {
            if (speaking) return
            speaking = true
            phase = Phase.SPEAKING
            tune()
            speech.beginStream(speakTag, throughCall = throughCallNow(), leadInMs = leadIn(), onFirstAudio = {
                val now = SystemClock.elapsedRealtime()
                logTiming("heard → first text ${firstTextAt - t0} ms → first audio ${now - t0} ms (${provider.label}, streamed${audioPath()})")
            }) { err -> finishAfterSpeech(err) }
        }
        fun show(textNow: String) {
            val i = shown?.let { s -> lines.indexOfLast { it === s } } ?: -1
            val line = Line(Who.AI, textNow)
            if (i >= 0) lines[i] = line else { lines += line; while (lines.size > 60) lines.removeAt(0) }
            shown = line
        }
        var callSeen = false
        fun onCall() {
            if (my != turn || callSeen) return
            // The model chose a function: whatever it started saying is dropped; the phone speaks the result.
            callSeen = true
            if (speaking) { speech.stopSpeaking(); speaking = false }
        }
        fun onPiece(piece: String) {
            if (my != turn || callSeen) return
            if (firstTextAt == 0L) firstTextAt = SystemClock.elapsedRealtime()
            buf.append(piece)
            show(buf.toString().trim())
            if (speakIt) splitter.feed(piece).forEach { sentence -> startSpeech(); speech.streamSentence(sentence) }
            val now = SystemClock.elapsedRealtime()
            if (now - lastPaint > 150) { lastPaint = now; changed() }
        }

        io.execute {
            fun once(useModel: String): AiClient.Answer =
                if (tools) AiClient.askWithTools(key, useModel, system, turns, onCall = { main.post { onCall() } }) { piece -> main.post { onPiece(piece) } }
                else AiClient.Answer(AiClient.askStreaming(provider, key, useModel, system, turns) { piece -> main.post { onPiece(piece) } }, emptyList())
            if (provider == AiProvider.GEMINI) runCatching { AiClient.refreshGeminiModels(prefs) }
            var result = runCatching { once(if (provider == AiProvider.GEMINI) prefs.aiModel(provider) else model) }
            // 4.0: a model the app picked that Google has since retired (404): read the list again and ask once more.
            result.exceptionOrNull()?.let { e ->
                if (provider == AiProvider.GEMINI && buf.isEmpty() && AiClient.modelGone(e, prefs)) {
                    main.post { glasses.addLog("Gemini: ${model} is gone; now using ${prefs.aiModel(provider)}") }
                    result = runCatching { once(prefs.aiModel(provider)) }
                }
            }
            main.post {
                if (my != turn) return@post
                val calls = result.getOrNull()?.calls.orEmpty()
                if (calls.isNotEmpty()) { onCall(); handleCalls(my, text, calls, shown); return@post }
                result.map { it.text }.onSuccess { reply ->
                    show(reply)
                    if (m == Mode.CHAT) {
                        history += AiTurn(true, text)
                        history += AiTurn(false, reply)
                        while (history.size > maxOf(prefs.historyLimit, 1) * 2) history.removeAt(0)
                    }
                    if (speakIt) {
                        splitter.flush()?.let { startSpeech(); speech.streamSentence(it) }
                        if (speaking) { speech.endStream(); changed() } else endTurn()
                    } else {
                        logTiming("heard → answer shown ${SystemClock.elapsedRealtime() - t0} ms (${provider.label}, not spoken)")
                        endTurn()
                    }
                }.onFailure { e ->
                    val msg = e.message ?: "Something went wrong. Try again."
                    if (buf.isEmpty()) {
                        add(lines, Who.ERROR, msg)
                        if (speaking) speech.stopSpeaking()
                        endTurn()
                    } else {
                        add(lines, Who.NOTE, "The answer was cut off: $msg")
                        if (speaking) { splitter.flush()?.let { speech.streamSentence(it) }; speech.endStream(); changed() } else endTurn()
                    }
                }
            }
        }
    }

    /**
     * 4.0: the model chose app functions (docs/SIMPLE-COMMANDS.md §2.4): each becomes the same command the
     * offline matcher makes and runs through [runCommand] (so deleting or clearing still asks for a spoken
     * yes), at most three, in order. A call that can't be done as asked gets one short question back.
     */
    private fun handleCalls(my: Int, text: String, calls: List<FnCall>, shownLine: Line?) {
        shownLine?.let { s -> chat.removeAll { it === s } }
        calls.take(FnCalls.MAX_CALLS).forEach { add(chat, Who.NOTE, FnCalls.describe(it)) }
        glasses.addLog("Voice: " + calls.joinToString("; ") { FnCalls.describe(it) })
        val plan = FnCalls.plan(calls)
        history += AiTurn(true, text)
        history += AiTurn(false, if (plan.askBack != null && plan.matches.isEmpty()) plan.askBack else "(done: " + calls.joinToString(", ") { it.name } + ")")
        while (history.size > maxOf(prefs.historyLimit, 1) * 2) history.removeAt(0)
        if (plan.matches.isEmpty()) {
            val q = plan.askBack ?: "Sorry, I couldn't do that. Try saying it another way."
            add(chat, Who.AI, q)
            phase = Phase.SPEAKING
            changed()
            tune()
            speech.speak(q, replyTag(), throughCall = throughCallNow(), leadInMs = leadIn()) { err ->
                if (my != turn) return@speak
                if (err != null) add(chat, Who.NOTE, err)
                endTurn()
                // Listen once more for the missing piece; the answer goes back to Gemini with the history.
                if (plan.askBack != null) main.post { if (phase == Phase.IDLE && my == turn) startTurn(Mode.CHAT) }
            }
            return
        }
        runMatches(my, plan.matches)
    }

    /** Runs commands one after another, each spoken before the next starts. */
    private fun runMatches(my: Int, list: List<Match>) {
        if (my != turn) return
        val first = list.firstOrNull() ?: run { endTurn(); return }
        val rest = list.drop(1)
        runCommand(my, first, confirmed = false, next = if (rest.isEmpty()) null else ({ runMatches(my, rest) }))
    }

    /** "How do you say … in …": one translation, spoken in that language's voice, not kept in the history. */
    fun sayIn(phrase: String, language: String) {
        if (phase != Phase.IDLE) stop()
        if (!hasKey()) { note("Add your ${prefs.aiProvider.label} key in AI setup first."); return }
        val my = ++turn
        mode = Mode.CHAT
        phase = Phase.THINKING
        changed()
        val provider = prefs.aiProvider
        val key = prefs.aiKey(provider) ?: ""
        val model = prefs.aiModel(provider)
        val system = AiWire.translationPrompt(prefs.replyLanguage, language)
        io.execute {
            val result = runCatching { AiClient.ask(provider, key, model, system, listOf(AiTurn(true, phrase))) }
            main.post {
                if (my != turn) return@post
                result.onSuccess { reply ->
                    add(chat, Who.AI, reply)
                    phase = Phase.SPEAKING
                    changed()
                    tune()
                    speech.speak(reply, Languages.byName(language).tag, throughCall = false, leadInMs = leadIn()) { err ->
                        if (my != turn) return@speak
                        if (err != null) add(chat, Who.NOTE, err)
                        endTurn()
                    }
                }.onFailure { e ->
                    add(chat, Who.ERROR, e.message ?: "Something went wrong. Try again.")
                    endTurn()
                }
            }
        }
    }

    /** Reads any text aloud in the reply language, through the glasses when they're the headset. */
    fun readAloud(text: String) {
        if (phase != Phase.IDLE) stop()
        val my = ++turn
        mode = Mode.READ_ALOUD
        phase = Phase.SPEAKING
        changed()
        val go: (Boolean) -> Unit = { via ->
            if (my == turn) {
                throughGlasses = via
                tune()
                speech.speak(text, replyTag(), throughCall = throughCallNow(), leadInMs = leadIn()) { err ->
                    if (my == turn) {
                        if (err != null) { photoError = err }
                        endTurn()
                    }
                }
            }
        }
        // Glasses audio (fast) plays on media audio and needs no call link at all.
        if (callAudio()) holdRoute(go) else main.post { go(false) }
    }

    private fun tune() {
        speech.rate = prefs.ttsRate
        speech.pitch = prefs.ttsPitch
    }

    /** Stops listening, thinking or talking right away. */
    fun stop() {
        turn++
        endVoiceChat("stopped")
        if (convoOn) endConversation("stopped")
        stopSpp()
        pendingConfirm = null
        commands?.cancelPending()
        speech.cancelListening()
        speech.stopSpeaking()
        phase = Phase.IDLE
        partial = null
        releaseRouteNow()
        changed()
    }

    private fun endTurn() {
        stopSpp()
        phase = Phase.IDLE
        partial = null
        val loop = convoOn && !convoPaused
        if (loop && convoErrors >= CONVO_MAX_ERRORS) {
            // 3.6: a conversation that keeps failing pauses instead of trying for ever.
            convoPaused = true
            convoProblem = "Paused after repeated problems" + (lastVoiceError?.let { ": $it" } ?: "") + ". Tap Resume to listen again."
            glasses.addLog("Conversation translate: paused after $convoErrors problems")
            releaseRouteNow()
        } else if (loop && holdingRoute) {
            // 3.6: the call link stays up between phrases of a conversation.
            main.removeCallbacks(lingerRelease)
        } else if (session?.route == MicRoute.GLASSES_SCO && holdingRoute) {
            // 4.1: voice chat on call audio keeps the link up for the whole chat.
            main.removeCallbacks(lingerRelease)
        } else if (callAudio() && holdingRoute) {
            // Call audio: keep the link up a little, so the next question in this session needn't wait for it.
            main.removeCallbacks(lingerRelease)
            main.postDelayed(lingerRelease, SESSION_LINGER_MS)
        } else releaseRouteNow()
        if (convoOn && !convoPaused) {
            main.removeCallbacks(convoNext)
            main.postDelayed(convoNext, CONVO_GAP_MS)
        }
        // 4.1: hands-free voice chat listens again by itself.
        session?.let { ss ->
            main.removeCallbacks(sessionNext)
            main.postDelayed(sessionNext, if (ss.route == MicRoute.GLASSES_SPP) SESSION_GAP_MS else ss.gate.tailMs)
        }
        changed()
    }

    private fun holdRoute(then: (Boolean) -> Unit) {
        main.removeCallbacks(lingerRelease)
        if (holdingRoute) { val now = route.active; main.post { then(now) }; return }
        if (route.willUseGlasses()) {
            holdingRoute = true
            route.acquire(then)
        } else {
            main.post { then(false) }
        }
    }

    private fun releaseRouteNow() {
        main.removeCallbacks(lingerRelease)
        if (holdingRoute) {
            holdingRoute = false
            route.release()
        }
    }

    /** Lets go of the glasses' call link at once (Live view needs it free). */
    fun releaseAudio() { if (phase == Phase.IDLE) releaseRouteNow() }

    fun clearChat() {
        if (mode == Mode.CHAT && phase != Phase.IDLE) stop()
        chat.clear()
        history.clear()
        changed()
    }

    fun clearTranslations() {
        if (mode == Mode.TRANSLATE && phase != Phase.IDLE) stop()
        translations.clear()
        changed()
    }

    // ---------------- Conversation translate (3.6) ----------------

    /** Saved sessions: app-private JSON lines, never with an API key or the SupRevolt token in them. */
    val convoStore = TranslationStore(File(app.filesDir, "translations")) {
        AiProvider.values().mapNotNull { prefs.aiKey(it) } + listOfNotNull(prefs.srToken, prefs.userKey)
    }
    /** File work for [convoStore], in order, off the main thread. */
    private val disk = Executors.newSingleThreadExecutor()

    var convoOn = false; private set
    var convoPaused = false; private set
    /** The session being written, or null when it couldn't be created (entries still show). */
    var convoSession: String? = null; private set
    /** This session's entries, oldest first. */
    val convoEntries = mutableListOf<TranslationEntry>()
    /** Notes and problems of the conversation (the screen shows the last ones). */
    val convoNotes = mutableListOf<Line>()
    /** The language the next phrase is listened for in. */
    var convoListenIn: String = prefs.translateFrom; private set
    /** The phrase being translated right now, or null. */
    var convoTranslating: String? = null; private set
    /** Why the conversation can't translate or paused, in a sentence, or null. */
    var convoProblem: String? = null; private set
    /** True while saved entries are being read aloud. */
    var convoReading = false; private set
    private var convoErrors = 0
    private var convoMic: MicRoute? = null
    private val convoNext = Runnable { if (convoOn && !convoPaused && phase == Phase.IDLE) startTurn(Mode.CONVERSATION) }

    /** Runs [work] on the store's thread and hands the result to [done] on the main thread. */
    fun <T> withStore(work: (TranslationStore) -> T, done: (Result<T>) -> Unit) {
        disk.execute {
            val r = runCatching { work(convoStore) }
            main.post { done(r) }
        }
    }

    /** Starts listening continuously: every phrase is translated, logged, shown and (if on) spoken. */
    fun startConversation() {
        if (convoOn) { if (convoPaused) resumeConversation(); return }
        if (phase != Phase.IDLE) stop()
        if (!speech.recognitionAvailable() && !geminiSttWanted()) {
            convoProblem = "This phone has no speech recognition. Install or turn on Google's speech services."
            changed(); return
        }
        convoOn = true
        convoPaused = false
        convoErrors = 0
        convoMic = null
        convoReading = false
        convoProblem = if (hasKey()) null else "No AI key yet, so nothing is translated: the original words are logged. Add your key in AI setup."
        convoEntries.clear()
        convoNotes.clear()
        convoTranslating = null
        convoListenIn = prefs.translateFrom
        convoSession = null
        val at = System.currentTimeMillis()
        val pending = mutableListOf<TranslationEntry>()
        convoPendingWrites = pending
        withStore({ it.newSession(at) }) { r ->
            if (pending !== convoPendingWrites) return@withStore
            convoPendingWrites = null
            convoSession = r.getOrNull()
            r.exceptionOrNull()?.let { convoNotes += Line(Who.ERROR, "Couldn't create the log file: ${it.message}. Phrases still show here.") }
            convoSession?.let { id -> pending.forEach { e -> disk.execute { runCatching { convoStore.append(id, e) } } } }
            changed()
        }
        glasses.addLog("Conversation translate: started, " + when {
            prefs.convoTwoWay -> "two-way ${prefs.translateFrom} and ${prefs.translateTo}"
            prefs.convoAutoDetect -> "auto-detect into ${prefs.translateTo}"
            else -> "${prefs.translateFrom} into ${prefs.translateTo}"
        } + if (prefs.convoSpeak) ", spoken" else "")
        if (prefs.convoBackground) ConversationService.start(app)
        changed()
        startTurn(Mode.CONVERSATION)
    }
    private var convoPendingWrites: MutableList<TranslationEntry>? = null

    /** Stops listening for now; the session stays open. */
    fun pauseConversation() {
        if (!convoOn || convoPaused) return
        convoPaused = true
        main.removeCallbacks(convoNext)
        if (mode == Mode.CONVERSATION && phase != Phase.IDLE) {
            turn++
            stopSpp()
            speech.cancelListening()
            speech.stopSpeaking()
            phase = Phase.IDLE
            partial = null
        }
        releaseRouteNow()
        glasses.addLog("Conversation translate: paused")
        changed()
    }

    fun resumeConversation() {
        if (!convoOn || !convoPaused) return
        if (phase != Phase.IDLE) stopTurnOnly()
        convoPaused = false
        convoErrors = 0
        convoProblem = if (hasKey()) null else convoProblem
        glasses.addLog("Conversation translate: listening again")
        changed()
        startTurn(Mode.CONVERSATION)
    }

    /** Ends the conversation; its log is kept. */
    fun stopConversation() {
        if (!convoOn) return
        if (phase != Phase.IDLE) stop() else { endConversation("stopped"); releaseRouteNow(); changed() }
    }

    private fun endConversation(why: String) {
        convoOn = false
        convoPaused = false
        convoTranslating = null
        main.removeCallbacks(convoNext)
        ConversationService.stop(app)
        glasses.addLog("Conversation translate: $why, ${convoEntries.size} phrase(s) logged")
    }

    /** Ends whatever turn runs without touching the conversation. */
    private fun stopTurnOnly() {
        turn++
        stopSpp()
        speech.cancelListening()
        speech.stopSpeaking()
        phase = Phase.IDLE
        partial = null
        convoReading = false
    }

    /** Swap the two languages; a phrase being listened for starts again in the new language. */
    fun swapConversationLanguages() {
        val f = prefs.translateFrom
        prefs.translateFrom = prefs.translateTo
        prefs.translateTo = f
        convoListenIn = prefs.translateFrom
        restartListening()
    }

    /** Two-way: listen for the next phrase in [language] (one of the pair). */
    fun listenIn(language: String) {
        convoListenIn = Languages.byName(language).name
        restartListening()
    }

    private fun restartListening() {
        if (convoOn && !convoPaused && mode == Mode.CONVERSATION && phase == Phase.LISTENING) {
            turn++
            stopSpp()
            speech.cancelListening()
            phase = Phase.IDLE
            partial = null
            startTurn(Mode.CONVERSATION)
        } else changed()
    }

    /** Keeps the background notification in step with the setting while a conversation runs. */
    fun conversationBackgroundChanged() {
        if (!convoOn) return
        if (prefs.convoBackground) ConversationService.start(app) else ConversationService.stop(app)
    }

    private fun heardInConversation(my: Int, text: String) {
        partial = null
        if (prefs.voiceCommands && VoiceCommands.isConversationStop(text)) {
            stopConversation()
            return
        }
        convoErrors = 0
        if (holdingRoute && !route.active) releaseRouteNow()
        val heardIn = convoListenIn
        val from = prefs.translateFrom
        val to = prefs.translateTo
        val twoWay = prefs.convoTwoWay
        val auto = prefs.convoAutoDetect && !twoWay
        val at = System.currentTimeMillis()
        if (!hasKey()) {
            convoProblem = "No AI key yet, so nothing is translated: the original words are logged. Add your key in AI setup."
            recordConvo(TranslationEntry(at, text, null, heardIn, to, note = "Not translated: no AI key"))
            endTurn()
            return
        }
        phase = Phase.THINKING
        convoTranslating = text
        changed()
        val provider = prefs.aiProvider
        val key = prefs.aiKey(provider) ?: ""
        val model = prefs.aiModel(provider)
        val system = ConversationWire.prompt(if (auto) null else if (twoWay) from else heardIn, to, twoWay)
        val t0 = SystemClock.elapsedRealtime()
        io.execute {
            val result = runCatching { AiClient.ask(provider, key, model, system, listOf(AiTurn(true, text))) }
            main.post {
                convoTranslating = null
                result.onSuccess { raw ->
                    val r = ConversationWire.parse(raw)
                    val way = TwoWay.way(r.language, heardIn, from, to, twoWay)
                    recordConvo(TranslationEntry(at, text, r.text, r.language ?: way.from, way.to))
                    if (my != turn) return@post
                    convoListenIn = TwoWay.nextListen(way.from, from, to, twoWay)
                    logTiming("conversation: heard → translated ${SystemClock.elapsedRealtime() - t0} ms (${provider.label})")
                    if (prefs.convoSpeak && convoOn && !convoPaused) speakConvo(my, r.text, way.to) else endTurn()
                }.onFailure { e ->
                    val msg = e.message ?: "Something went wrong."
                    recordConvo(TranslationEntry(at, text, null, heardIn, to, note = "Not translated: $msg"))
                    if (my != turn) return@post
                    convoErrors++
                    convoNotes += Line(Who.ERROR, msg)
                    endTurn()
                }
            }
        }
    }

    private fun speakConvo(my: Int, text: String, language: String) {
        phase = Phase.SPEAKING
        changed()
        tune()
        speech.speak(text, Languages.byName(language).tag, throughCall = throughCallNow(), leadInMs = leadIn()) { err ->
            if (my != turn) return@speak
            if (err != null) convoNotes += Line(Who.NOTE, err)
            endTurn()
        }
    }

    /** Shows a phrase and appends it to the session's file (or holds it until the file exists). */
    private fun recordConvo(e: TranslationEntry) {
        convoEntries += e
        while (convoNotes.size > 20) convoNotes.removeAt(0)
        val id = convoSession
        val waiting = convoPendingWrites
        when {
            id != null -> disk.execute { runCatching { convoStore.append(id, e) } }
            waiting != null -> waiting += e
        }
        changed()
    }

    /**
     * Reads entries aloud one after another, each in its own language ("Read all", or one tapped entry).
     * A conversation that is listening pauses first, so the phone doesn't translate its own voice.
     */
    fun readBack(items: List<Pair<String, String>>) {
        if (items.isEmpty()) return
        if (convoOn && !convoPaused) pauseConversation()
        if (phase != Phase.IDLE) stopTurnOnly()
        val my = ++turn
        mode = Mode.READ_ALOUD
        phase = Phase.SPEAKING
        convoReading = true
        changed()
        tune()
        fun next(i: Int) {
            if (my != turn) return
            if (i >= items.size) { convoReading = false; endTurn(); return }
            val (text, language) = items[i]
            speech.speak(text, Languages.byName(language).tag, throughCall = throughCallNow(), leadInMs = if (i == 0) leadIn() else 0) { err ->
                if (my != turn) return@speak
                if (err != null) { convoNotes += Line(Who.NOTE, err); convoReading = false; endTurn(); return@speak }
                next(i + 1)
            }
        }
        next(0)
    }

    /** Stops reading entries aloud. */
    fun stopReading() {
        if (!convoReading) return
        stopTurnOnly()
        releaseRouteNow()
        changed()
    }

    // ---------------- photos ----------------

    /** [voice]: asked by a voice command, so the answer is read aloud and kept in the chat. */
    fun askPhoto(uri: Uri, question: String, voice: Boolean = false) {
        if (photoBusy) return
        val q = question.trim()
        if (q.isEmpty()) { photoError = "Type a question or pick one of the buttons."; changed(); return }
        if (!hasKey()) { photoError = "Add your ${prefs.aiProvider.label} key in AI setup first."; changed(); return }
        val my = ++photoTurn
        val provider = prefs.aiProvider
        val key = prefs.aiKey(provider) ?: ""
        val model = prefs.aiModel(provider)
        val system = AiWire.systemPrompt(prefs.replyLanguage, spoken = false)
        photoUri = uri
        photoQuestion = q
        photoAnswer = null
        photoError = null
        photoBusy = true
        photoStatus = "Preparing the photo…"
        changed()
        io.execute {
            val result = runCatching {
                val jpeg = ImagePrep.jpegForAi(app.contentResolver, uri, 1280)
                main.post { if (my == photoTurn) { photoStatus = "Asking ${provider.label}…"; changed() } }
                AiClient.ask(provider, key, model, system, listOf(AiTurn(true, q)), jpeg)
            }
            main.post {
                if (my != photoTurn) return@post
                photoBusy = false
                photoStatus = null
                result.onSuccess { answer ->
                    photoAnswer = answer
                    changed()
                    if (voice) { add(chat, Who.AI, answer); changed() }
                    if (voice || prefs.speakPhotoAnswers) readAloud(answer)
                }.onFailure { e ->
                    photoError = when (e) {
                        is AiException -> e.message
                        is SecurityException -> "The app lost permission to read that photo. Pick it again."
                        else -> "Couldn't use that photo: ${e.message ?: "unknown problem"}."
                    }
                    changed()
                    if (voice) photoError?.let { say(it) }
                }
            }
        }
    }

    /** Fires the glasses' camera, waits for the picture to arrive over Bluetooth, then asks about it. */
    fun captureAndAsk(question: String, voice: Boolean = false) {
        if (photoBusy) { if (voice) say("I'm still working on the last photo."); return }
        if (!hasKey()) { photoError = "Add your ${prefs.aiProvider.label} key in AI setup first."; changed(); if (voice) say(photoError!!); return }
        if (glasses.state != XkGlasses.State.READY) {
            photoError = if (glasses.state == XkGlasses.State.BUSY) "The glasses are busy. Try again in a moment."
            else "Connect the glasses on the Glasses tab first."
            changed(); if (voice) say(photoError!!); return
        }
        val my = ++photoTurn
        val before = glasses.lastPhoto
        var sawBusy = false
        photoBusy = true
        photoAnswer = null
        photoError = null
        photoQuestion = question.trim()
        photoStatus = "Taking a photo with the glasses…"
        changed()

        lateinit var watcher: XkGlasses.Listener
        val timeout = Runnable {
            glasses.removeListener(watcher)
            if (my == photoTurn && photoBusy) {
                photoBusy = false
                photoStatus = null
                photoError = "The glasses didn't send a photo in time. Try again."
                changed()
                if (voice) say(photoError!!)
            }
        }
        watcher = XkGlasses.Listener {
            if (my != photoTurn) { glasses.removeListener(watcher); main.removeCallbacks(timeout); return@Listener }
            val now = glasses.lastPhoto
            if (now != null && now != before) {
                glasses.removeListener(watcher)
                main.removeCallbacks(timeout)
                photoBusy = false
                askPhoto(now, question, voice)
                return@Listener
            }
            when (glasses.state) {
                XkGlasses.State.BUSY -> {
                    sawBusy = true
                    glasses.busyText?.let { photoStatus = it; changed() }
                }
                XkGlasses.State.READY, XkGlasses.State.IDLE -> if (sawBusy || glasses.state == XkGlasses.State.IDLE) {
                    glasses.removeListener(watcher)
                    main.removeCallbacks(timeout)
                    photoBusy = false
                    photoStatus = null
                    photoError = glasses.lastError ?: "The glasses didn't send a photo. Try again."
                    changed()
                    if (voice) say(photoError!!)
                }
                XkGlasses.State.CONNECTING -> {}
            }
        }
        glasses.addListener(watcher)
        main.postDelayed(timeout, 60_000)
        glasses.takePhoto()
    }

    fun cancelPhoto() {
        photoTurn++
        photoBusy = false
        photoStatus = null
        changed()
    }

    companion object {
        /**
         * 3.3: listen once more with the phone's mic after "didn't catch that" (no match, timeout) or an
         * audio error, when the first try used the glasses' mic or the app's own recording. Only once.
         */
        fun shouldRetryWithPhone(code: Int, glassesOrFed: Boolean, alreadyRetried: Boolean): Boolean =
            !alreadyRetried && glassesOrFed && code in setOf(
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_AUDIO,
            )

        /** 3.7: why the glasses' data-link mic can't be used with only a Claude key. */
        const val SPP_NEEDS_GEMINI = "the glasses' data-link mic needs a Gemini key (Claude doesn't take audio)"

        /** Call audio keeps the glasses' link this long after a turn, for the next question. */
        const val SESSION_LINGER_MS = 10_000L

        /** 4.1: voice chat ends after this long with no question. */
        const val SESSION_IDLE_MS = 60_000L
        /** 4.1: the pause before voice chat listens again (the echo gate covers the reply's tail itself). */
        const val SESSION_GAP_MS = 150L

        /** 3.6: the pause between one conversation phrase and listening for the next. */
        const val CONVO_GAP_MS = 250L
        /** 3.6: a conversation pauses after this many problems in a row (not counting silence). */
        const val CONVO_MAX_ERRORS = 3

        @Volatile private var instance: Assistant? = null

        fun get(context: Context, prefs: Prefs): Assistant =
            instance ?: synchronized(this) { instance ?: Assistant(context, prefs).also { instance = it } }
    }
}
