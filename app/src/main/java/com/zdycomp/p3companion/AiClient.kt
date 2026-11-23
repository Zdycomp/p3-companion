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
        if (code !in 200..299) throw AiException(AiWire.errorMessage(provider, code, body, apiKey))
        return AiWire.parse(provider, body)
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
        onText: (String) -> Unit,
    ): String {
        val req = AiWire.buildStream(provider, apiKey, model, system, turns)
        var conn: HttpsURLConnection? = null
        try {
            conn = open(req)
            val code = conn.responseCode
            if (code !in 200..299) {
                val body = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (code == 400 || code == 405 || code == 415 || code == 501) throw StreamUnsupported("HTTP $code")
                throw AiException(AiWire.errorMessage(provider, code, body, apiKey))
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
            fun handle(data: String) {
                events++
                for (ev in AiWire.parseStreamData(provider, data)) when (ev) {
                    is AiWire.StreamEvent.Text -> { sb.append(ev.text); onText(ev.text) }
                    is AiWire.StreamEvent.Stop -> stop = ev.reason
                    is AiWire.StreamEvent.Failed -> throw AiException(AiWire.redact(ev.message, apiKey))
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
            if (text.isEmpty()) throw AiException(AiWire.emptyStreamMessage(provider, stop))
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
