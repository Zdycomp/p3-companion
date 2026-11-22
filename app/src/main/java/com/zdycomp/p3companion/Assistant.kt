package com.zdycomp.p3companion

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * The AI assistant behind the Assistant tab: voice chat, live translation and questions about
 * photos. One instance for the whole app, so a conversation survives tab changes.
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
    val speech = Speech(app)
    val route: AudioRoute = AudioRoute.get(app, prefs)
    private val glasses = XkGlasses.get(app, prefs)

    // Voice: chat and translation
    var phase = Phase.IDLE; private set
    var mode = Mode.CHAT; private set
    var partial: String? = null; private set
    /** Whether the current turn's sound goes through the glasses. */
    var throughGlasses = false; private set
    val chat = mutableListOf<Line>()
    val translations = mutableListOf<Line>()
    private val history = mutableListOf<AiTurn>()
    private var turn = 0
    private var holdingRoute = false

    // Photo questions
    var photoUri: Uri? = null
    var photoBusy = false; private set
    var photoStatus: String? = null; private set
    var photoQuestion: String? = null; private set
    var photoAnswer: String? = null; private set
    var photoError: String? = null; private set
    private var photoTurn = 0

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

    private fun add(list: MutableList<Line>, who: Who, text: String) {
        list += Line(who, text)
        while (list.size > 60) list.removeAt(0)
    }

    private fun startTurn(m: Mode) {
        if (phase != Phase.IDLE) { tapWhileBusy(); return }
        val lines = log(m)
        if (!hasKey()) {
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
        changed()
        holdRoute { via ->
            if (my != turn) return@holdRoute
            throughGlasses = via
            changed()
            val tag = Languages.byName(if (m == Mode.CHAT) prefs.replyLanguage else prefs.translateFrom).tag
            speech.listen(
                tag,
                onPartial = { if (my == turn) { partial = it; changed() } },
                onResult = { text -> if (my == turn) heard(my, m, text) },
                onError = { msg -> if (my == turn) { add(lines, Who.ERROR, msg); endTurn() } },
            )
        }
    }

    private fun heard(my: Int, m: Mode, text: String) {
        val lines = log(m)
        partial = null
        add(lines, Who.YOU, text)
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
            turns = history.takeLast(AiWire.HISTORY_LIMIT) + AiTurn(true, text)
            speakTag = Languages.byName(prefs.replyLanguage).tag
        } else {
            system = AiWire.translationPrompt(prefs.translateFrom, prefs.translateTo)
            turns = listOf(AiTurn(true, text))
            speakTag = Languages.byName(prefs.translateTo).tag
        }
        io.execute {
            val result = runCatching { AiClient.ask(provider, key, model, system, turns) }
            main.post {
                if (my != turn) return@post
                result.onSuccess { reply ->
                    if (m == Mode.CHAT) {
                        history += AiTurn(true, text)
                        history += AiTurn(false, reply)
                        while (history.size > AiWire.HISTORY_LIMIT * 2) history.removeAt(0)
                    }
                    add(lines, Who.AI, reply)
                    phase = Phase.SPEAKING
                    changed()
                    speech.speak(reply, speakTag, throughCall = throughGlasses) { err ->
                        if (my != turn) return@speak
                        if (err != null) add(lines, Who.NOTE, err)
                        endTurn()
                    }
                }.onFailure { e ->
                    add(lines, Who.ERROR, e.message ?: "Something went wrong. Try again.")
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
        holdRoute { via ->
            if (my != turn) return@holdRoute
            throughGlasses = via
            speech.speak(text, Languages.byName(prefs.replyLanguage).tag, throughCall = via) { err ->
                if (my != turn) return@speak
                if (err != null) { photoError = err }
                endTurn()
            }
        }
    }

    /** Stops listening, thinking or talking right away. */
    fun stop() {
        turn++
        speech.cancelListening()
        speech.stopSpeaking()
        endTurn()
    }

    private fun endTurn() {
        phase = Phase.IDLE
        partial = null
        releaseRoute()
        changed()
    }

    private fun holdRoute(then: (Boolean) -> Unit) {
        if (route.willUseGlasses()) {
            holdingRoute = true
            route.acquire(then)
        } else {
            main.post { then(false) }
        }
    }

    private fun releaseRoute() {
        if (holdingRoute) {
            holdingRoute = false
            route.release()
        }
        throughGlasses = false
    }

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

    fun askPhoto(uri: Uri, question: String) {
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
                    if (prefs.speakPhotoAnswers) readAloud(answer)
                }.onFailure { e ->
                    photoError = when (e) {
                        is AiException -> e.message
                        is SecurityException -> "The app lost permission to read that photo. Pick it again."
                        else -> "Couldn't use that photo: ${e.message ?: "unknown problem"}."
                    }
                    changed()
                }
            }
        }
    }

    /** Fires the glasses' camera, waits for the picture to arrive over Bluetooth, then asks about it. */
    fun captureAndAsk(question: String) {
        if (photoBusy) return
        if (!hasKey()) { photoError = "Add your ${prefs.aiProvider.label} key in AI setup first."; changed(); return }
        if (glasses.state != XkGlasses.State.READY) {
            photoError = if (glasses.state == XkGlasses.State.BUSY) "The glasses are busy. Try again in a moment."
            else "Connect the glasses on the Glasses tab first."
            changed(); return
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
            }
        }
        watcher = XkGlasses.Listener {
            if (my != photoTurn) { glasses.removeListener(watcher); main.removeCallbacks(timeout); return@Listener }
            val now = glasses.lastPhoto
            if (now != null && now != before) {
                glasses.removeListener(watcher)
                main.removeCallbacks(timeout)
                photoBusy = false
                askPhoto(now, question)
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
        @Volatile private var instance: Assistant? = null

        fun get(context: Context, prefs: Prefs): Assistant =
            instance ?: synchronized(this) { instance ?: Assistant(context, prefs).also { instance = it } }
    }
}
