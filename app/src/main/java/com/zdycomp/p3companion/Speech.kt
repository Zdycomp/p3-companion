package com.zdycomp.p3companion

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Listening (Android's speech recognizer, on the phone itself when it can) and talking
 * (Android's text-to-speech). Call everything on the main thread; callbacks arrive there too.
 */
class Speech(context: Context) {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    // ---------------- listening ----------------

    private var recognizer: SpeechRecognizer? = null
    private var listenToken = 0

    fun recognitionAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(app) ||
            (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(app))

    /**
     * Listens for one sentence in [languageTag]. Exactly one of [onResult] or [onError] is called,
     * unless [cancelListening] comes first.
     */
    fun listen(
        languageTag: String,
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val onDevice = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(app)
        start(languageTag, onDevice, ++listenToken, onPartial, onResult, onError)
    }

    private fun start(
        tag: String,
        onDevice: Boolean,
        token: Int,
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        destroyRecognizer()
        val r = try {
            if (onDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
            else SpeechRecognizer.createSpeechRecognizer(app)
        } catch (e: Exception) {
            onError("Speech recognition isn't available on this phone.")
            return
        }
        recognizer = r
        var heardAnything = false
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() { heardAnything = true }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                if (token != listenToken) return
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) onPartial(text)
            }

            override fun onResults(results: Bundle?) {
                if (token != listenToken) return
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                destroyRecognizer()
                if (text.isNullOrEmpty()) onError(errorText(SpeechRecognizer.ERROR_NO_MATCH)) else onResult(text)
            }

            override fun onError(error: Int) {
                if (token != listenToken) return
                destroyRecognizer()
                // The on-phone recognizer may lack this language: try the regular one once.
                val retryable = error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                    error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                    error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED ||
                    (error == SpeechRecognizer.ERROR_CLIENT && !heardAnything)
                if (onDevice && retryable && SpeechRecognizer.isRecognitionAvailable(app)) {
                    main.post { if (token == listenToken) start(tag, false, token, onPartial, onResult, onError) }
                } else {
                    onError(errorText(error))
                }
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, onDevice)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
        }
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            destroyRecognizer()
            onError("Couldn't start listening: ${e.message ?: "unknown problem"}.")
        }
    }

    /** Stop listening and use what was heard so far. */
    fun finishListening() {
        runCatching { recognizer?.stopListening() }
    }

    /** Stop listening and throw away what was heard. */
    fun cancelListening() {
        listenToken++
        destroyRecognizer()
    }

    private fun destroyRecognizer() {
        recognizer?.let { r ->
            runCatching { r.cancel() }
            runCatching { r.destroy() }
        }
        recognizer = null
    }

    // ---------------- talking ----------------

    private var tts: TextToSpeech? = null
    private var ttsState = 0 // 0 starting, 1 ready, -1 failed
    private val ttsWaiting = mutableListOf<(Boolean) -> Unit>()
    private var speakToken = 0
    private var speakDone: ((String?) -> Unit)? = null
    private var lastChunkId: String? = null

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
                            val list = ttsWaiting.toList()
                            ttsWaiting.clear()
                            list.forEach { it(ttsState == 1) }
                        }
                    }
                }
            }
        }
    }

    /**
     * Reads [text] aloud in [languageTag]. [throughCall] sends it down the hands-free link (the
     * glasses' speaker during a voice turn). [onDone] gets null when finished, or a message
     * saying why it couldn't be read aloud.
     */
    fun speak(text: String, languageTag: String, throughCall: Boolean, onDone: (String?) -> Unit) {
        val token = ++speakToken
        withTts { ok ->
            if (token != speakToken) return@withTts
            val engine = tts
            if (!ok || engine == null) {
                onDone("This phone has no text-to-speech voice. Add one in Settings › Accessibility › Text-to-speech.")
                return@withTts
            }
            val locale = Locale.forLanguageTag(languageTag)
            val res = engine.setLanguage(locale)
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                onDone(
                    "This phone has no ${locale.getDisplayLanguage(Locale.ENGLISH)} voice for reading aloud. " +
                        "You can add one in Settings › Text-to-speech."
                )
                return@withTts
            }
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(if (throughCall) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            val chunks = AiWire.speechChunks(AiWire.speakable(text), TextToSpeech.getMaxSpeechInputLength() - 100)
            if (chunks.isEmpty()) { onDone(null); return@withTts }
            speakDone = onDone
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    main.post { if (token == speakToken && utteranceId == lastChunkId) finishSpeaking(null) }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    main.post { if (token == speakToken) finishSpeaking("Couldn't read the answer aloud.") }
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    main.post { if (token == speakToken) finishSpeaking("Couldn't read the answer aloud (error $errorCode).") }
                }
            })
            chunks.forEachIndexed { i, chunk ->
                val id = "p3-$token-$i"
                if (i == chunks.lastIndex) lastChunkId = id
                val result = engine.speak(chunk, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
                if (result != TextToSpeech.SUCCESS) {
                    finishSpeaking("Couldn't read the answer aloud.")
                    return@withTts
                }
            }
        }
    }

    private fun finishSpeaking(error: String?) {
        val cb = speakDone
        speakDone = null
        cb?.invoke(error)
    }

    /** Stop talking without calling the pending done callback. */
    fun stopSpeaking() {
        speakToken++
        speakDone = null
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        cancelListening()
        stopSpeaking()
        runCatching { tts?.shutdown() }
        tts = null
        ttsState = 0
    }

    companion object {
        fun errorText(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                "I didn't catch that. Tap and try again."
            SpeechRecognizer.ERROR_AUDIO -> "The microphone isn't working right now. Is another app using it?"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow the microphone for P3 Companion to talk to the assistant."
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                "Speech recognition needs the internet for this language. Check your connection."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognition is busy. Try again in a moment."
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Speech recognition asked us to slow down. Try again in a minute."
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                "This phone can't recognise speech in that language yet. Add it in your phone's speech recognition settings."
            SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
                "The speech service stopped. Try again."
            else -> "Listening stopped (error $code). Try again."
        }
    }
}
