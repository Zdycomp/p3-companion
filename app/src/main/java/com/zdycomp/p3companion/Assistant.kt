package com.zdycomp.p3companion

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.SpeechRecognizer
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
    enum class Mode { CHAT, TRANSLATE, READ_ALOUD }
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
    private var retriedPhoneMic = false

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

    private fun log(m: Mode) = if (m == Mode.TRANSLATE) translations else chat

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

    /** Answers go down the call link only with "Call audio" and while the link is really up. */
    private fun throughCallNow() = callAudio() && holdingRoute && route.active

    /** A short silence first when the call link was let go a moment ago, so the headset can switch. */
    private fun leadIn(): Long = if (!throughCallNow() && SystemClock.elapsedRealtime() - route.releasedAt < 1_200) 350 else 0

    private fun replyTag() = Languages.byName(prefs.replyLanguage).tag

    private fun startTurn(m: Mode) {
        if (phase != Phase.IDLE) { tapWhileBusy(); return }
        val lines = log(m)
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
        retriedPhoneMic = false
        changed()
        holdRoute { via ->
            if (my != turn) return@holdRoute
            throughGlasses = via
            changed()
            listenNow(my, m)
        }
    }

    private fun listenNow(my: Int, m: Mode) {
        val lines = log(m)
        val tag = Languages.byName(if (m == Mode.CHAT) prefs.replyLanguage else prefs.translateFrom).tag
        speech.listen(
            tag,
            onPartial = { text ->
                if (my != turn) return@listen
                if (prefs.voiceCommands && VoiceCommands.isEarlyStop(text)) { stop(); return@listen }
                partial = text; changed()
            },
            onResult = { text -> if (my == turn) heard(my, m, text) },
            onError = { msg, code ->
                if (my != turn) return@listen
                // The glasses' mic may not be ready yet: try once more with the phone's mic.
                if (code == SpeechRecognizer.ERROR_AUDIO && throughGlasses && !retriedPhoneMic) {
                    retriedPhoneMic = true
                    releaseRouteNow()
                    throughGlasses = false
                    glasses.addLog("Voice: the glasses' mic failed, listening with the phone's mic")
                    changed()
                    listenNow(my, m)
                    return@listen
                }
                lastVoiceError = speech.lastErrorDetail?.let { "$msg ($it)" } ?: msg
                speech.lastErrorDetail?.let { glasses.addLog("Voice: $it") }
                if (pendingConfirm != null) {
                    pendingConfirm = null
                    add(lines, Who.NOTE, "Cancelled.")
                } else add(lines, Who.ERROR, msg + (speech.lastErrorDetail?.let { "\n[$it]" } ?: ""))
                endTurn()
            },
        )
    }

    private fun heard(my: Int, m: Mode, text: String) {
        val lines = log(m)
        partial = null
        add(lines, Who.YOU, text)
        // Glasses audio (fast): the call link was only for the mic; let it go so the answer plays on media audio.
        if (!callAudio()) releaseRouteNow()
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

    private fun logTiming(line: String) {
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
        phase = Phase.IDLE
        partial = null
        // Call audio: keep the link up a little, so the next question in this session needn't wait for it.
        if (callAudio() && holdingRoute) {
            main.removeCallbacks(lingerRelease)
            main.postDelayed(lingerRelease, SESSION_LINGER_MS)
        } else releaseRouteNow()
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
        /** Call audio keeps the glasses' link this long after a turn, for the next question. */
        const val SESSION_LINGER_MS = 10_000L

        @Volatile private var instance: Assistant? = null

        fun get(context: Context, prefs: Prefs): Assistant =
            instance ?: synchronized(this) { instance ?: Assistant(context, prefs).also { instance = it } }
    }
}
