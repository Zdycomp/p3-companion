package com.zdycomp.p3companion

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

/**
 * Sends one question to Gemini or Claude and returns the answer text. Blocking: call it from a
 * background thread. Every failure becomes an [AiException] with a message for people.
 *
 * [askStreaming] (3.1) reads the answer as it's written, so the first sentence can be spoken
 * straight away; it falls back to [ask] when the service won't stream.
 */
object AiClient {

    private const val TIMEOUT_MS = 60_000

    /** Streaming didn't work for a reason a plain request may not share. */
    private class StreamUnsupported(message: String) : IOException(message)

    fun ask(
        provider: AiProvider,
        apiKey: String,
        model: String,
        system: String,
        turns: List<AiTurn>,
        imageJpeg: ByteArray? = null,
    ): String {
        val req = AiWire.build(provider, apiKey, model, system, turns, imageJpeg)
        val (code, body) = post(req, provider, apiKey)
        if (code !in 200..299) throw AiException(AiWire.errorMessage(provider, code, body, apiKey), code)
        return AiWire.parse(provider, body)
    }

    /** 4.0: an answer that may be words, the app functions the model chose, or both. */
    class Answer(val text: String, val calls: List<FnCall>)

    /**
     * 4.0 simple commands: a Gemini voice-chat turn with the app's functions (AUTO). Streams the words to
     * [onText] like [askStreaming]; function calls come back in [Answer.calls] (an answer may be only calls).
     */
    fun askWithTools(
        apiKey: String, model: String, system: String, turns: List<AiTurn>,
        onCall: (FnCall) -> Unit = {}, onText: (String) -> Unit,
    ): Answer {
        val calls = ArrayList<FnCall>()
        var got = false
        return try {
            val text = stream(AiProvider.GEMINI, apiKey, model, system, turns, tools = true, onCall = { calls += it; onCall(it) }) { got = true; onText(it) }
            Answer(text, calls)
        } catch (e: StreamUnsupported) {
            if (got || calls.isNotEmpty()) throw AiException(AiWire.redact("Google Gemini stopped in the middle of the answer.", apiKey))
            val req = AiWire.build(AiProvider.GEMINI, apiKey, model, system, turns, null, tools = true)
            val (code, body) = post(req, AiProvider.GEMINI, apiKey)
            if (code !in 200..299) throw AiException(AiWire.errorMessage(AiProvider.GEMINI, code, body, apiKey), code)
            val found = AiWire.parseGeminiCalls(body)
            val text = if (found.isNotEmpty()) runCatching { AiWire.parseGemini(body) }.getOrDefault("") else AiWire.parseGemini(body)
            if (text.isNotEmpty()) onText(text)
            Answer(text, found)
        }
    }

    // ---------------- 4.0: Gemini's model list ----------------

    /** Every model this key can see (up to three pages). */
    fun listModels(apiKey: String): List<GeminiVoice.Model> {
        val out = ArrayList<GeminiVoice.Model>()
        var token: String? = null
        repeat(3) {
            val (code, body) = get(GeminiVoice.modelsRequest(apiKey, token), AiProvider.GEMINI, apiKey)
            if (code !in 200..299) throw AiException(AiWire.errorMessage(AiProvider.GEMINI, code, body, apiKey), code)
            val (page, next) = GeminiVoice.parseModels(body)
            out += page
            token = next ?: return out
        }
        return out
    }

    /**
     * Reads the model list (at most once a week unless [force]) and keeps the newest Flash text model and
     * the speech model in [prefs]. Returns a line for people. Blocking; never logs the key.
     */
    fun refreshGeminiModels(prefs: Prefs, force: Boolean = false): String? {
        val key = prefs.aiKey(AiProvider.GEMINI) ?: return null
        val now = System.currentTimeMillis()
        if (!force && prefs.geminiTextAuto != null && now - prefs.geminiModelsAt < MODELS_EVERY_MS) return prefs.geminiModelsReport
        return try {
            val models = listModels(key)
            val text = GeminiVoice.pickText(models)
            val tts = GeminiVoice.pickTts(models)
            if (text != null) prefs.geminiTextAuto = text
            if (tts != null) prefs.geminiTtsAuto = tts
            prefs.geminiModelsAt = now
            val line = "${models.size} models: chat ${text ?: "no Flash model found"}, voice ${tts ?: "no speech model found"}"
            prefs.geminiModelsReport = line
            line
        } catch (e: AiException) {
            prefs.geminiModelsReport = "Couldn't read the model list: ${e.message}"
            null
        }
    }

    private const val MODELS_EVERY_MS = 7L * 24 * 3600 * 1000

    /** A Gemini model that's gone (404) when the app picked it: read the list again and say whether a new one was found. */
    fun modelGone(e: Throwable, prefs: Prefs): Boolean {
        if (e !is AiException || e.httpCode != 404 || prefs.aiModelTyped(AiProvider.GEMINI) != null) return false
        val before = prefs.geminiTextAuto
        refreshGeminiModels(prefs, force = true)
        return prefs.geminiTextAuto != null && prefs.geminiTextAuto != before
    }

    // ---------------- 4.0: Gemini's voice ----------------

    /**
     * Speaks [text] with Gemini's voice: a streamed request whose audio pieces go to [onAudio] as they
     * arrive (16-bit PCM). If the stream isn't accepted, one plain request is made instead. [cancelled]
     * stops reading early. Blocking.
     */
    fun speakGemini(apiKey: String, model: String, text: String, voice: String, languageTag: String, cancelled: () -> Boolean, onAudio: (GeminiVoice.Audio) -> Unit) {
        val req = GeminiVoice.ttsRequest(apiKey, model, text, voice, languageTag, stream = true)
        var conn: HttpsURLConnection? = null
        var got = false
        try {
            conn = open(req)
            val code = conn.responseCode
            if (code !in 200..299) {
                val body = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (code == 405 || code == 415 || code == 501) { speakGeminiUnary(apiKey, model, text, voice, languageTag, onAudio); return }
                throw AiException(AiWire.errorMessage(AiProvider.GEMINI, code, body, apiKey), code)
            }
            val type = conn.contentType?.lowercase() ?: ""
            if (!type.contains("event-stream")) {
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                onAudio(GeminiVoice.parseUnary(body))
                return
            }
            val sse = SseParser()
            fun handle(data: String) {
                for (ev in GeminiVoice.parseStreamEvent(data)) when (ev) {
                    is GeminiVoice.Event.Chunk -> { got = true; onAudio(ev.audio) }
                    is GeminiVoice.Event.Failed -> throw AiException(AiWire.redact(ev.message, apiKey))
                    is GeminiVoice.Event.Stop -> {}
                }
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                while (!cancelled()) {
                    val line = r.readLine() ?: break
                    sse.line(line)?.let { handle(it) }
                }
            }
            if (!cancelled()) sse.flush()?.let { handle(it) }
            if (!got && !cancelled()) throw AiException("Gemini's voice sent back no audio.")
        } catch (e: AiException) {
            throw e
        } catch (e: IOException) {
            throw netError(e, AiProvider.GEMINI, apiKey)
        } finally {
            conn?.disconnect()
        }
    }

    private fun speakGeminiUnary(apiKey: String, model: String, text: String, voice: String, languageTag: String, onAudio: (GeminiVoice.Audio) -> Unit) {
        val req = GeminiVoice.ttsRequest(apiKey, model, text, voice, languageTag, stream = false)
        val (code, body) = post(req, AiProvider.GEMINI, apiKey)
        if (code !in 200..299) throw AiException(AiWire.errorMessage(AiProvider.GEMINI, code, body, apiKey), code)
        onAudio(GeminiVoice.parseUnary(body))
    }

    /**
     * 3.7: transcribes a WAV clip of the glasses' own mic with Gemini ([AiWire.TRANSCRIBE_MODEL]). Null
     * when nothing was said. Blocking.
     */
    fun transcribe(apiKey: String, wav: ByteArray, languageName: String, model: String = AiWire.TRANSCRIBE_MODEL, mime: String = "audio/wav"): String? {
        val req = AiWire.geminiTranscribe(apiKey, wav, languageName, model, mime)
        val (code, body) = post(req, AiProvider.GEMINI, apiKey)
        if (code !in 200..299) throw AiException(AiWire.errorMessage(AiProvider.GEMINI, code, body, apiKey), code)
        return AiWire.parseTranscript(body)
    }

    /**
     * Asks with a streamed answer. [onText] gets each new piece of text on this (background) thread,
     * in order. Returns the whole answer. If the stream can't be used and nothing was received yet,
     * the question is asked again the plain way and [onText] gets the whole answer at once.
     */
    fun askStreaming(
        provider: AiProvider,
        apiKey: String,
        model: String,
        system: String,
        turns: List<AiTurn>,
        onText: (String) -> Unit,
    ): String {
        var got = false
        return try {
            stream(provider, apiKey, model, system, turns) { got = true; onText(it) }
        } catch (e: StreamUnsupported) {
            if (got) throw AiException(AiWire.redact("${provider.label} stopped in the middle of the answer.", apiKey))
            val whole = ask(provider, apiKey, model, system, turns)
            onText(whole)
            whole
        }
    }

    private fun stream(
        provider: AiProvider,
        apiKey: String,
        model: String,
        system: String,
        turns: List<AiTurn>,
        tools: Boolean = false,
        onCall: ((FnCall) -> Unit)? = null,
        onText: (String) -> Unit,
    ): String {
        val req = AiWire.buildStream(provider, apiKey, model, system, turns, tools)
        var conn: HttpsURLConnection? = null
        try {
            conn = open(req)
            val code = conn.responseCode
            if (code !in 200..299) {
                val body = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (code == 400 || code == 405 || code == 415 || code == 501) throw StreamUnsupported("HTTP $code")
                throw AiException(AiWire.errorMessage(provider, code, body, apiKey), code)
            }
            val type = conn.contentType?.lowercase() ?: ""
            if (!type.contains("event-stream")) {
                // Some proxies answer a stream request with the whole JSON: use it as is.
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val text = runCatching { AiWire.parse(provider, body) }.getOrElse { throw StreamUnsupported("not a stream") }
                onText(text)
                return text
            }
            val sse = SseParser()
            val sb = StringBuilder()
            var stop: String? = null
            var events = 0
            var calls = 0
            fun handle(data: String) {
                events++
                for (ev in AiWire.parseStreamData(provider, data)) when (ev) {
                    is AiWire.StreamEvent.Text -> { sb.append(ev.text); onText(ev.text) }
                    is AiWire.StreamEvent.Stop -> stop = ev.reason
                    is AiWire.StreamEvent.Failed -> throw AiException(AiWire.redact(ev.message, apiKey))
                    is AiWire.StreamEvent.Call -> { calls++; onCall?.invoke(ev.call) }
                }
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    sse.line(line)?.let { handle(it) }
                }
            }
            sse.flush()?.let { handle(it) }
            if (events == 0) throw StreamUnsupported("no events")
            val text = sb.toString().trim()
            if (text.isEmpty() && calls == 0) throw AiException(AiWire.emptyStreamMessage(provider, stop))
            return text
        } catch (e: AiException) {
            throw e
        } catch (e: StreamUnsupported) {
            throw e
        } catch (e: IOException) {
            throw netError(e, provider, apiKey)
        } finally {
            conn?.disconnect()
        }
    }

    private fun open(req: AiHttpRequest): HttpsURLConnection {
        val conn = URL(req.url).openConnection() as HttpsURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 20_000
        conn.readTimeout = TIMEOUT_MS
        conn.doOutput = true
        conn.useCaches = false
        req.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        val bytes = req.body.toByteArray(Charsets.UTF_8)
        conn.setFixedLengthStreamingMode(bytes.size)
        conn.outputStream.use { it.write(bytes) }
        return conn
    }

    private fun get(req: AiHttpRequest, provider: AiProvider, apiKey: String): Pair<Int, String> {
        var conn: HttpsURLConnection? = null
        try {
            conn = URL(req.url).openConnection() as HttpsURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.useCaches = false
            req.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return code to (stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "")
        } catch (e: IOException) {
            throw netError(e, provider, apiKey)
        } finally {
            conn?.disconnect()
        }
    }

    private fun post(req: AiHttpRequest, provider: AiProvider, apiKey: String): Pair<Int, String> {
        var conn: HttpsURLConnection? = null
        try {
            conn = open(req)
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return code to body
        } catch (e: AiException) {
            throw e
        } catch (e: IOException) {
            throw netError(e, provider, apiKey)
        } finally {
            conn?.disconnect()
        }
    }

    private fun netError(e: IOException, provider: AiProvider, apiKey: String): AiException = when (e) {
        is UnknownHostException -> AiException("No internet connection. Check Wi-Fi or mobile data and try again.")
        is SocketTimeoutException -> AiException("${provider.label} took too long to answer (over a minute). Try again.")
        is SSLException -> AiException("Couldn't make a secure connection to ${provider.label}. Check the phone's date and network.")
        else -> AiException(AiWire.redact("Couldn't reach ${provider.label}: ${e.message ?: "network error"}.", apiKey))
    }
}
