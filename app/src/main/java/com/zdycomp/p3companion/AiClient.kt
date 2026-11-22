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
 */
object AiClient {

    private const val TIMEOUT_MS = 60_000

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

    private fun post(req: AiHttpRequest, provider: AiProvider, apiKey: String): Pair<Int, String> {
        var conn: HttpsURLConnection? = null
        try {
            conn = URL(req.url).openConnection() as HttpsURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = TIMEOUT_MS
            conn.doOutput = true
            conn.useCaches = false
            req.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val bytes = req.body.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return code to body
        } catch (e: AiException) {
            throw e
        } catch (e: UnknownHostException) {
            throw AiException("No internet connection. Check Wi-Fi or mobile data and try again.")
        } catch (e: SocketTimeoutException) {
            throw AiException("${provider.label} took too long to answer (over a minute). Try again.")
        } catch (e: SSLException) {
            throw AiException("Couldn't make a secure connection to ${provider.label}. Check the phone's date and network.")
        } catch (e: IOException) {
            throw AiException(AiWire.redact("Couldn't reach ${provider.label}: ${e.message ?: "network error"}.", apiKey))
        } finally {
            conn?.disconnect()
        }
    }
}
