package com.zdycomp.p3companion

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

/**
 * Talks to a SupRevolt server. Blocking: call it from a background thread, like [AiClient]. Every
 * failure becomes a [SupRevoltException] with a message for people; the token is never in one.
 *
 * https:// goes through HttpsURLConnection. Plain http:// is only ever used for an address that
 * [SupRevoltWire.checkUrl] accepted as this phone or a private home-network IP (the person was
 * warned), and it goes over a small HTTP/1.1 exchange on a socket, so the app's cleartext policy
 * stays closed for everything else and no network security exception is needed.
 */
class SupRevoltClient(baseUrl: String, private val token: String) {

    private val check = SupRevoltWire.checkUrl(baseUrl)
    private val base: String = check.url

    init {
        if (check.kind == SupRevoltWire.UrlKind.BAD) throw SupRevoltException(check.reason ?: "Bad server address.")
    }

    class Response(val code: Int, val body: String)

    fun health(): SrHealth = ok(request("GET", "/v1/health", auth = false)).let { SupRevoltWire.parseHealth(it) }

    fun catalog(): List<SrScan> = SupRevoltWire.parseCatalog(ok(request("GET", "/v1/catalog")))

    fun job(id: String): SrJob {
        require(Regex("^[0-9a-f]{24}$").matches(id))
        return SupRevoltWire.parseJob(ok(request("GET", "/v1/scans/$id")))
    }

    fun scanText(text: String, scans: List<String>, filename: String? = null, wait: Int = SupRevoltWire.DEFAULT_WAIT): SrJob {
        val body = SupRevoltWire.textBody(text, scans, wait, filename).toByteArray(Charsets.UTF_8)
        return finish(request("POST", "/v1/scans", body, "application/json"))
    }

    fun scanBytes(data: ByteArray, contentType: String, filename: String?, scans: List<String>,
                  wait: Int = SupRevoltWire.DEFAULT_WAIT): SrJob {
        val headers = SupRevoltWire.safeFilename(filename).takeIf { it.isNotEmpty() }?.let { mapOf("X-Filename" to it) } ?: emptyMap()
        return finish(request("POST", "/v1/scans" + SupRevoltWire.uploadQuery(scans, wait), data,
            contentType.ifBlank { "application/octet-stream" }, headers))
    }

    /** A 200 is the finished job; a 202 is polled every second for up to 30 seconds more. */
    private fun finish(r: Response): SrJob {
        var job = SupRevoltWire.parseJob(ok(r))
        var tries = 0
        while (!job.done && tries < POLL_TRIES) {
            Thread.sleep(1_000)
            tries++
            job = job(job.id)
        }
        return job
    }

    private fun ok(r: Response): String {
        if (r.code in 200..299) return r.body
        throw SupRevoltException(SupRevoltWire.errorMessage(r.code, r.body))
    }

    private fun request(method: String, path: String, body: ByteArray? = null, contentType: String? = null,
                        extra: Map<String, String> = emptyMap(), auth: Boolean = true): Response {
        val headers = LinkedHashMap<String, String>()
        if (auth) headers["Authorization"] = "Bearer $token"
        if (contentType != null) headers["Content-Type"] = contentType
        headers.putAll(extra)
        return try {
            if (check.kind == SupRevoltWire.UrlKind.HTTPS) https(method, base + path, headers, body)
            else LocalHttp.exchange(base, method, path, headers, body, TIMEOUT_MS)
        } catch (e: SupRevoltException) {
            throw e
        } catch (e: UnknownHostException) {
            throw SupRevoltException("Couldn't find the server. Check the address and the phone's internet.")
        } catch (e: SocketTimeoutException) {
            throw SupRevoltException("The server took too long. A free server may be asleep: try again in a minute.")
        } catch (e: ConnectException) {
            throw SupRevoltException("Couldn't reach the server. It may be off or asleep: try again in a minute.")
        } catch (e: SSLException) {
            throw SupRevoltException("Couldn't make a secure connection to the server. Check its HTTPS setup and the phone's date.")
        } catch (e: IOException) {
            throw SupRevoltException("Couldn't reach the server (${e.javaClass.simpleName}).")
        }
    }

    private fun https(method: String, url: String, headers: Map<String, String>, body: ByteArray?): Response {
        val conn = URL(url).openConnection() as HttpsURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 20_000
            conn.readTimeout = TIMEOUT_MS
            conn.useCaches = false
            conn.instanceFollowRedirects = false   // never carry the token to another address
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return Response(code, stream?.let { readCapped(it) } ?: "")
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val TIMEOUT_MS = 45_000
        const val POLL_TRIES = 30
        const val MAX_REPLY = 8 * 1024 * 1024

        fun readCapped(input: InputStream, limit: Int = MAX_REPLY): String {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16_384)
            input.use { s ->
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    if (out.size() + n > limit) throw SupRevoltException("The server's reply was too large.")
                    out.write(buf, 0, n)
                }
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }
}

/**
 * A minimal HTTP/1.1 client for SupRevolt on this phone or the home network (plain http only to
 * addresses [SupRevoltWire.isPrivateHost] accepts). One request per connection, Content-Length bodies.
 */
object LocalHttp {

    fun exchange(base: String, method: String, path: String, headers: Map<String, String>, body: ByteArray?,
                 timeoutMs: Int): SupRevoltClient.Response {
        val (host, port, prefix) = SupRevoltWire.hostPort(base)
        if (!SupRevoltWire.isPrivateHost(host)) throw SupRevoltException("Plain http:// is only for this phone or your home network.")
        Socket().use { sock ->
            sock.connect(InetSocketAddress(if (host == "localhost") "127.0.0.1" else host, port), 10_000)
            sock.soTimeout = timeoutMs
            val head = StringBuilder()
            head.append(method).append(' ').append(prefix).append(path).append(" HTTP/1.1\r\n")
            head.append("Host: ").append(host).append(':').append(port).append("\r\n")
            head.append("Connection: close\r\n")
            head.append("Content-Length: ").append(body?.size ?: 0).append("\r\n")
            headers.forEach { (k, v) ->
                require(k.none { it == '\r' || it == '\n' } && v.none { it == '\r' || it == '\n' }) { "bad header" }
                head.append(k).append(": ").append(v).append("\r\n")
            }
            head.append("\r\n")
            val out = sock.getOutputStream()
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            if (body != null) out.write(body)
            out.flush()
            return read(sock.getInputStream())
        }
    }

    /** Parses a status line, headers and a Content-Length (or read-to-close) body. */
    fun read(input: InputStream): SupRevoltClient.Response {
        val status = line(input) ?: throw IOException("no reply")
        val code = Regex("^HTTP/1\\.[01] (\\d{3})").find(status)?.groupValues?.get(1)?.toInt() ?: throw IOException("not HTTP")
        var length = -1
        while (true) {
            val h = line(input) ?: break
            if (h.isEmpty()) break
            val colon = h.indexOf(':')
            if (colon > 0 && h.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) {
                length = h.substring(colon + 1).trim().toIntOrNull() ?: -1
            }
        }
        if (length > SupRevoltClient.MAX_REPLY) throw SupRevoltException("The server's reply was too large.")
        val body = if (length >= 0) {
            val buf = ByteArray(length)
            var got = 0
            while (got < length) {
                val n = input.read(buf, got, length - got)
                if (n < 0) break
                got += n
            }
            String(buf, 0, got, Charsets.UTF_8)
        } else SupRevoltClient.readCapped(input)
        return SupRevoltClient.Response(code, body)
    }

    private fun line(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8_192) throw IOException("header too long")
            sb.append(c.toChar())
        }
    }
}
