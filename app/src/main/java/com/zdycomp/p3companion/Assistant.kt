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
    var phase = Phase.IDLE; private set
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
        // The speech engine and the command patterns are ready before the first question.
        speech.warmUp()
        io.execute { runCatching { VoiceCommands.match("warm up") } }
    }

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }
    private fun changed() = listeners.toList().forEach { it.onAssistantChanged() }

    fun hasKey(): Boolean = prefs.aiKey() != null

    // ---------------- voice turns ----------------

    /** One spoken question to the AI, answered out loud. Tap again to finish or stop. */
    fun voiceChat() = startTurn(Mode.CHAT)

    /** One spoken sentence, translated and spoken in the other language. */
    fun translate() = startTurn(Mode.TRANSLATE)

    /** What a tap on the glasses or the big button does while a turn is running. */
    fun tapWhileBusy() {
        if (phase == Phase.LISTENING) speech.finishListening() else stop()
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

    private fun startTurn(m: Mode) {
        if (phase != Phase.IDLE) { tapWhileBusy(); return }
        val lines = log(m)
        if (m == Mode.CONVERSATION && (!convoOn || convoPaused)) return
        if (!hasKey() && m == Mode.TRANSLATE) {
            add(lines, Who.ERROR, "Add your ${prefs.aiProvider.label} key in AI setup first.")
            changed(); return
        }
        if (!speech.recognitionAvailable()) {
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
            linked = glasses.state == XkGlasses.State.READY && MicCapture.supported(), useGlasses = prefs.useGlassesAudio,
        )
        // 3.6: a conversation keeps the mic that worked for its last phrase.
        if (m == Mode.CONVERSATION) convoMic?.takeIf { it in routes }?.let { w -> routes = listOf(w) + routes.filter { it != w } }
        if (routes.size > 1 && (m != Mode.CONVERSATION || convoMic == null)) glasses.addLog("Voice: mics to try: " + routes.joinToString(" → ") { it.label })
        changed()
        tryRoute(my, m, 0, null)
    }

    /** The mic the next question starts with. */
    fun plannedMic(): MicRoute = VoiceChoice.routeOrder(
        prefs.micRouteScores, route.headsetConnected(),
        linked = glasses.state == XkGlasses.State.READY && MicCapture.supported(), useGlasses = prefs.useGlassesAudio,
    ).first()

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

    private fun stopSpp() {
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
                useMic(r, "phone (the speech service records it)")
                listenNow(my, m, null)
            }
            MicRoute.GLASSES_SCO -> {
                stopSpp()
                holdRoute { up ->
                    if (my != turn) return@holdRoute
                    if (!up) { releaseRouteNow(); routeFailed(my, m, i, r, route.lastReport ?: "the call audio didn't open"); return@holdRoute }
                    // 3.6: in a conversation the link checked for the last phrase is still up: listen at once.
                    if (m == Mode.CONVERSATION && convoMic == MicRoute.GLASSES_SCO && route.active) {
                        throughGlasses = true
                        useMic(r, "glasses through call audio (kept up for the conversation)")
                        listenNow(my, m, null)
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
                        useMic(r, "glasses through call audio, RMS %.0f dBFS (the speech service records it)".format(Locale.US, take.db))
                        listenNow(my, m, null)
                    }
                }
            }
            MicRoute.GLASSES_SPP -> {
                releaseRouteNow()
                stopSpp()
                val s = GlassesMicStream(glasses)
                spp = s
                s.start { err ->
                    if (my != turn || spp !== s) { s.stop(); return@start }
                    if (err != null) { stopSpp(); routeFailed(my, m, i, r, err); return@start }
                    throughGlasses = true
                    useMic(r, "glasses over the data link (Opus, decoded and fed to the speech service)")
                    listenNow(my, m) { s.newPipe { speech.finishListening() } }
                }
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

    // ---------------- voice commands ----------------

    private fun runCommand(my: Int, match: Match, confirmed: Boolean) {
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
        commandSay(my, match.id, reply.say, reply.languageTag, reply.after)
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
        if (m == Mode.CHAT) {
            system = AiWire.systemPrompt(prefs.replyLanguage, spoken = true)
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
        fun onPiece(piece: String) {
            if (my != turn) return
            if (firstTextAt == 0L) firstTextAt = SystemClock.elapsedRealtime()
            buf.append(piece)
            show(buf.toString().trim())
            if (speakIt) splitter.feed(piece).forEach { sentence -> startSpeech(); speech.streamSentence(sentence) }
            val now = SystemClock.elapsedRealtime()
            if (now - lastPaint > 150) { lastPaint = now; changed() }
        }

        io.execute {
            val result = runCatching { AiClient.askStreaming(provider, key, model, system, turns) { piece -> main.post { onPiece(piece) } } }
            main.post {
                if (my != turn) return@post
                result.onSuccess { reply ->
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
        } else if (callAudio() && holdingRoute) {
            // Call audio: keep the link up a little, so the next question in this session needn't wait for it.
            main.removeCallbacks(lingerRelease)
            main.postDelayed(lingerRelease, SESSION_LINGER_MS)
        } else releaseRouteNow()
        if (convoOn && !convoPaused) {
            main.removeCallbacks(convoNext)
            main.postDelayed(convoNext, CONVO_GAP_MS)
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
        if (!speech.recognitionAvailable()) {
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

        /** Call audio keeps the glasses' link this long after a turn, for the next question. */
        const val SESSION_LINGER_MS = 10_000L

        /** 3.6: the pause between one conversation phrase and listening for the next. */
        const val CONVO_GAP_MS = 250L
        /** 3.6: a conversation pauses after this many problems in a row (not counting silence). */
        const val CONVO_MAX_ERRORS = 3

        @Volatile private var instance: Assistant? = null

        fun get(context: Context, prefs: Prefs): Assistant =
            instance ?: synchronized(this) { instance ?: Assistant(context, prefs).also { instance = it } }
    }
}
