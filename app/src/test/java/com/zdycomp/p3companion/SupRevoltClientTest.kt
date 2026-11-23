package com.zdycomp.p3companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.ServerSocket
import java.util.Collections

/** The SupRevolt client against a tiny fake server on 127.0.0.1 (the plain-http home-network path). */
class SupRevoltClientTest {

    private val id = "0123456789abcdef01234567"
    private val done = """{"job":"$id","state":"done","verdict":{"level":"CLEAR","risk":0,"confidence":256,"reasons":[]},"results":[]}"""

    /** Answers each connection with the next (code, body); records each request's text. */
    private fun server(vararg replies: Pair<Int, String>, seen: MutableList<String>): Int {
        val ss = ServerSocket(0)
        Thread {
            ss.use {
                for ((code, body) in replies) {
                    it.accept().use { c ->
                        val inp = c.getInputStream()
                        val head = StringBuilder()
                        while (!head.endsWith("\r\n\r\n")) head.append(inp.read().toChar())
                        val len = Regex("Content-Length: (\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
                        val bodyIn = ByteArray(len)
                        var got = 0
                        while (got < len) got += inp.read(bodyIn, got, len - got)
                        seen += head.toString() + String(bodyIn)
                        val bytes = body.toByteArray()
                        c.getOutputStream().write(("HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n").toByteArray() + bytes)
                    }
                }
            }
        }.apply { isDaemon = true }.start()
        return ss.localPort
    }

    @Test fun postsTextThenPollsUntilDone() {
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val port = server(202 to """{"job":"$id","state":"queued","poll":"/v1/scans/$id"}""", 200 to done, seen = seen)
        val job = SupRevoltClient("http://127.0.0.1:$port", "tok-123456789012345").scanText("hello", listOf("fineprint.lexicon"))
        assertTrue(job.done)
        assertEquals("CLEAR", job.verdict!!.level)
        assertTrue(seen[0].startsWith("POST /v1/scans HTTP/1.1\r\n"))
        assertTrue(seen[0].contains("Authorization: Bearer tok-123456789012345\r\n"))
        assertTrue(seen[0].endsWith("""{"text":"hello","scans":["fineprint.lexicon"],"wait":20}"""))
        assertTrue(seen[1].startsWith("GET /v1/scans/$id HTTP/1.1\r\n"))
    }

    @Test fun uploadsBytesWithFilenameAndQuery() {
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val port = server(200 to done, seen = seen)
        SupRevoltClient("http://localhost:$port", "tok-123456789012345").scanBytes("ACGT".toByteArray(), "text/plain", "/x/dna.txt", emptyList())
        assertTrue(seen[0].startsWith("POST /v1/scans?scans=all&wait=20 HTTP/1.1\r\n"))
        assertTrue(seen[0].contains("X-Filename: dna.txt\r\n"))
        assertTrue(seen[0].endsWith("ACGT"))
    }

    @Test fun healthSendsNoTokenAndErrorsNeverRepeatIt() {
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val port = server(200 to """{"ok":true,"version":"1.0.0","catalog_version":"c","queue_depth":0,"determinism":"ok"}""",
            401 to """{"error":"missing or wrong token"}""", seen = seen)
        val c = SupRevoltClient("http://127.0.0.1:$port", "secret-token-value-123")
        assertEquals("1.0.0", c.health().version)
        assertFalse(seen[0].contains("Authorization"))
        try {
            c.catalog(); fail()
        } catch (e: SupRevoltException) {
            assertTrue(e.message!!.contains("token"))
            assertFalse(e.message!!.contains("secret-token-value-123"))
        }
    }

    @Test fun refusesPlainHttpOnTheInternet() {
        try { SupRevoltClient("http://scan.example.org", "t"); fail() } catch (e: SupRevoltException) { assertTrue(e.message!!.contains("https")) }
    }

    @Test fun unreachableServerSaysItMayBeAsleep() {
        val port = ServerSocket(0).use { it.localPort }   // closed again: nothing listens there
        try {
            SupRevoltClient("http://127.0.0.1:$port", "t").health(); fail()
        } catch (e: SupRevoltException) { assertTrue(e.message, e.message!!.contains("asleep")) }
    }

    @Test fun readsAResponseWithAndWithoutContentLength() {
        val a = LocalHttp.read(ByteArrayInputStream("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}extra".toByteArray()))
        assertEquals(200, a.code)
        assertEquals("{}", a.body)
        val b = LocalHttp.read(ByteArrayInputStream("HTTP/1.0 413 Too big\r\n\r\n{\"error\":\"x\"}".toByteArray()))
        assertEquals(413, b.code)
        assertEquals("{\"error\":\"x\"}", b.body)
    }
}
