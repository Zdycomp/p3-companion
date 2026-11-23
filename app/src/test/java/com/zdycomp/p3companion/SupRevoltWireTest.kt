package com.zdycomp.p3companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** SupRevolt's wire format (docs/SUPREVOLT-ARCHITECTURE.md §3): addresses, parsing, confidence text, speech. */
class SupRevoltWireTest {

    private val job = """
        {"job":"0123456789abcdef01234567","state":"done","blob":"ab","scans":["all"],
         "verdict":{"level":"CAUTION","risk":40,"confidence":182,"percent":71,
                    "reasons":["fineprint.lexicon: 2 flag(s): auto_renewal","refix.gradle: 1 fix(es)","code.edapt: 1 fix(es)"],"applicable":5},
         "results":[
          {"scan":"blob.fingerprint","title":"Fingerprint","version":"1+a","state":"ok","value":0,"confidence":256,
           "deterministic":true,"cached":true,"ms":0,"note":"text/plain, 40 bytes","output":{"type":"text/plain","size":40,"sha256":"0bede8e29d425bf8b4"},"blob":"ab","parent":null},
          {"scan":"refix.gradle","title":"Gradle build fixer","state":"n/a","value":null,"confidence":null,"deterministic":true,
           "cached":false,"ms":0,"note":"not this kind of input","output":{},"blob":"ab","parent":null},
          {"scan":"fineprint.lexicon","title":"Fine print (offline)","state":"ok","value":40,"confidence":200,"deterministic":true,
           "cached":false,"ms":3,"note":"2 flag(s): auto_renewal","output":{"hits":[{"subtype":"auto_renewal","severity":"high","phrase":"renews automatically"}]},
           "blob":"cd","parent":"ab"},
          {"scan":"code.edapt","title":"Code fixer","state":"ok","value":20,"confidence":182,"deterministic":true,"cached":false,"ms":1,
           "note":"1 fix(es)","output":{"fixes":[{"line":1,"what":"'def' line missing ':'","became":"':' appended"}],"bracket_problems":[]},"blob":"ab","parent":null},
          {"scan":"fineprint.ai","title":"Fine print (AI)","state":"unknown","value":null,"confidence":0,"deterministic":false,
           "cached":false,"ms":0,"note":"no AI key on the server","output":{},"blob":"ab","parent":null}
         ]}
    """.trimIndent()

    @Test fun httpsIsRequiredExceptForThisPhoneAndTheHomeNetwork() {
        assertEquals(SupRevoltWire.UrlKind.HTTPS, SupRevoltWire.checkUrl("https://scan.example.org/").kind)
        assertEquals("https://scan.example.org", SupRevoltWire.checkUrl("  https://scan.example.org/ ").url)
        assertEquals("https://rick.duckdns.org", SupRevoltWire.checkUrl("rick.duckdns.org").url)
        assertEquals("https://x.onrender.com:8443/api", SupRevoltWire.checkUrl("HTTPS://X.onrender.com:8443/api").url)
        for (ok in listOf("http://127.0.0.1:8787", "http://localhost:8787", "http://10.0.0.5:8787", "http://192.168.1.20:8787",
                "http://172.20.1.2")) {
            assertEquals(ok, SupRevoltWire.UrlKind.LOCAL_HTTP, SupRevoltWire.checkUrl(ok).kind)
        }
        for (bad in listOf("http://scan.example.org", "http://8.8.8.8", "http://172.32.0.1", "http://192.169.1.1",
                "http://10.0.0.256", "http://evil.com@192.168.1.2", "ftp://192.168.1.2", "", "https://", "https://a b",
                "http://127.0.0.1:99999", "http://127.0.0.1.nip.io")) {
            assertEquals(bad, SupRevoltWire.UrlKind.BAD, SupRevoltWire.checkUrl(bad).kind)
        }
    }

    @Test fun hostPortAndPrefix() {
        assertEquals(Triple("192.168.1.2", 8787, ""), SupRevoltWire.hostPort("http://192.168.1.2:8787"))
        assertEquals(Triple("localhost", 80, "/sr"), SupRevoltWire.hostPort("http://localhost/sr"))
        assertEquals(Triple("a.b", 443, ""), SupRevoltWire.hostPort("https://a.b"))
    }

    @Test fun confidenceAsFractionAndPercentFromTheSameInteger() {
        assertEquals("218/256", SupRevoltWire.confText(218))
        assertEquals(85, SupRevoltWire.percent(218))
        assertEquals(100, SupRevoltWire.percent(256))
        assertEquals(47, SupRevoltWire.percent(120))
        assertEquals(0, SupRevoltWire.percent(0))
        assertEquals("unknown", SupRevoltWire.confText(null))
        assertEquals("unknown", SupRevoltWire.confText(0, unknown = true))
        assertEquals("Danger, unconfirmed", SupRevoltWire.levelLabel("DANGER_UNCONFIRMED"))
        assertEquals(SupRevoltWire.VerdictTone.BAD, SupRevoltWire.tone("DANGER_UNCONFIRMED"))
        assertEquals(SupRevoltWire.VerdictTone.UNKNOWN, SupRevoltWire.tone("UNKNOWN"))
    }

    @Test fun requestBodies() {
        assertEquals("""{"text":"hi \"there\"","scans":"all","wait":20}""", SupRevoltWire.textBody("hi \"there\"", emptyList()))
        assertEquals("""{"text":"x","scans":["fineprint.lexicon","blob.fingerprint"],"wait":5,"filename":"a.py"}""",
            SupRevoltWire.textBody("x", listOf("fineprint.lexicon", "blob.fingerprint"), 5, "a.py"))
        assertEquals("?scans=all&wait=20", SupRevoltWire.uploadQuery(listOf("all")))
        assertEquals("?scans=dna.sequence,image.read&wait=20", SupRevoltWire.uploadQuery(listOf("dna.sequence", "image.read", "bad id&x=1")))
        assertEquals("?scans=all&wait=20", SupRevoltWire.uploadQuery(listOf("x&y")))
        assertEquals("lease.docx", SupRevoltWire.safeFilename("/storage/emulated/0/../lease.docx"))
        assertEquals("photo.jpg", SupRevoltWire.safeFilename("photo.jpg\r\nX-Evil: 1".substringBefore('\r')))
        assertEquals("ab.txt", SupRevoltWire.safeFilename("a\u0000b.txt"))
    }

    @Test fun parsesAFinishedJob() {
        val j = SupRevoltWire.parseJob(job)
        assertTrue(j.done)
        assertEquals("CAUTION", j.verdict!!.level)
        assertEquals(182, j.verdict!!.confidence)
        assertEquals(71, j.verdict!!.percent)
        assertEquals(5, j.results.size)
        assertTrue(j.results[1].skipped)
        assertTrue(j.results[2].fromPhotoText)
        assertTrue(j.results[4].unknown)
        assertEquals(listOf("auto renewal (high): \"renews automatically\""), j.results[2].details)
        assertEquals(listOf("line 1: 'def' line missing ':' → ':' appended"), j.results[3].details)
    }

    @Test fun parsesAQueuedJobHealthAndCatalog() {
        val q = SupRevoltWire.parseJob("""{"job":"0123456789abcdef01234567","state":"queued","poll":"/v1/scans/0123456789abcdef01234567"}""")
        assertFalse(q.done)
        assertNull(q.verdict)
        val h = SupRevoltWire.parseHealth("""{"ok":true,"version":"1.0.0","catalog_version":"abc","queue_depth":2,"determinism":"ok"}""")
        assertEquals(SrHealth(true, "1.0.0", "abc", 2, "ok"), h)
        val cat = SupRevoltWire.parseCatalog("""{"catalog_version":"abc","scans":[
            {"id":"fineprint.ai","title":"Fine print (AI)","version":"1","accepts":"text","deterministic":false,"needs_key":true,"key_present":false,"enabled":true},
            {"id":"ever.run","title":"Ever runner","version":"1","accepts":".ever","deterministic":true,"needs_key":false,"key_present":true,"enabled":false},
            {"id":"dna.sequence","title":"DNA","version":"1","accepts":"DNA","deterministic":true,"needs_key":false,"key_present":true}]}""")
        assertEquals(listOf(false, false, true), cat.map { it.usable })
    }

    @Test fun badRepliesBecomeMessagesForPeople() {
        for (bad in listOf("", "not json", "[]", """{"job":"nope"}""")) {
            try { SupRevoltWire.parseJob(bad); fail(bad) } catch (e: SupRevoltException) { assertTrue(e.message!!.isNotBlank()) }
        }
        assertTrue(SupRevoltWire.errorMessage(401, "{}").contains("token"))
        assertTrue(SupRevoltWire.errorMessage(413, """{"error":"image over 8 MB"}""").contains("image over 8 MB"))
        assertTrue(SupRevoltWire.errorMessage(429, "").contains("busy"))
    }

    @Test fun spokenSummary() {
        assertEquals("Caution. Two flags. Confidence 182 of 256.", SupRevoltWire.spoken(SupRevoltWire.parseJob(job)))
        val unknown = SrJob("0123456789abcdef01234567", "done",
            SrVerdict("UNKNOWN", 40, 0, listOf("fineprint.ai: no AI key on the server (set ANTHROPIC_API_KEY or GEMINI_API_KEY)")), emptyList())
        assertEquals("Unknown: no AI key on the server.", SupRevoltWire.spoken(unknown))
        val clear = SrJob("0123456789abcdef01234567", "done", SrVerdict("CLEAR", 0, 256, emptyList()), emptyList())
        assertEquals("Clear. No flags. Confidence 256 of 256.", SupRevoltWire.spoken(clear))
    }

    @Test fun reportHasNoTokenAndNoServerAddress() {
        val r = SupRevoltWire.report(SupRevoltWire.parseJob(job))
        assertTrue(r.contains("Verdict: Caution, risk 40/100, confidence 182/256 (71%)"))
        assertTrue(r.contains("Fine print (offline) (photo text): risk 40/100, 200/256"))
        assertTrue(r.contains("Fine print (AI): unknown"))
        assertFalse(r.contains("Gradle build fixer"))      // skipped scans are left out
        assertFalse(r.contains("http"))
        assertFalse(r.contains("Bearer"))
        assertEquals("••••wxyz", SupRevoltWire.maskToken("abcdefghijklmnopqrstuvwxyz"))
        assertEquals("••••", SupRevoltWire.maskToken("short"))
    }
}
