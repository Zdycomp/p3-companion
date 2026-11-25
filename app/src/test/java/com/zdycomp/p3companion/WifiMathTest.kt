package com.zdycomp.p3companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.8 Wi-Fi screen: glasses-name matching, how the phone's Wi-Fi reads, and the port probe's lines. */
class WifiMathTest {

    private val t = WifiMatch.tokens("NovaSight P3", "E13C-5", "AA:BB:CC:DD:D6:BE")

    @Test fun tokensCoverNameModelAddressAndBrand() {
        assertTrue("novasight p3" in t)
        assertTrue("e13c-5" in t && "e13c" in t)
        assertTrue("d6be" in t)
        listOf("p3", "xk", "shenju").forEach { assertTrue(it, it in t) }
        // Without anything known about the glasses the brand words still match.
        assertTrue(WifiMatch.matches("XK-One_7F21", WifiMatch.tokens(null, null, null)))
    }

    @Test fun namesThatLookLikeTheGlasses() {
        assertEquals("p3", WifiMatch.match("P3_cam", t))
        assertEquals("d6be", WifiMatch.match("P3_D6BE", listOf("d6be", "p3")))
        assertTrue(WifiMatch.matches("DIRECT-4F-E13C-5", t))
        assertTrue(WifiMatch.matches("Shenju_cam", t))
        assertTrue(WifiMatch.matches("xk one Pro_F773", t))
        assertTrue(WifiMatch.matches("NovaSight-P3", t))
        assertTrue(WifiMatch.matches("DIRECT-ab-P3", t))
        assertTrue(WifiMatch.matches("glasses-d6be", t))
    }

    @Test fun shortWordsOnlyMatchAsWords() {
        assertFalse(WifiMatch.matches("AP3000", t))
        assertFalse(WifiMatch.matches("XKCD-guest", t))
        assertFalse(WifiMatch.matches("HomeNetwork", t))
        assertFalse(WifiMatch.matches("Starbucks WiFi", t))
        assertFalse(WifiMatch.matches("", t))
        assertFalse(WifiMatch.matches(null, t))
        assertTrue("a digit may follow (P3 then a number)", WifiMatch.matches("P3-01", t))
    }

    @Test fun ssidText() {
        assertEquals("Home", WifiText.ssid("\"Home\""))
        assertNull(WifiText.ssid("<unknown ssid>"))
        assertNull(WifiText.ssid("\"\""))
        assertNull(WifiText.ssid(null))
    }

    @Test fun signalBandAndChannel() {
        assertEquals("-48 dBm (excellent)", WifiText.signal(-48))
        assertEquals("good", WifiText.quality(-60))
        assertEquals("fair", WifiText.quality(-72))
        assertEquals("weak", WifiText.quality(-85))
        assertEquals("no signal", WifiText.signal(-127))
        assertEquals("2.4 GHz", WifiText.band(2437))
        assertEquals("5 GHz", WifiText.band(5180))
        assertEquals("6 GHz", WifiText.band(5955))
        assertEquals(6, WifiText.channel(2437))
        assertEquals(14, WifiText.channel(2484))
        assertEquals(36, WifiText.channel(5180))
        assertEquals(165, WifiText.channel(5825))
        assertEquals(1, WifiText.channel(5955))
        assertEquals("5180 MHz (5 GHz, channel 36)", WifiText.frequency(5180))
        assertEquals("unknown", WifiText.frequency(0))
        assertEquals("433 Mbps", WifiText.linkSpeed(433))
        assertEquals("unknown", WifiText.linkSpeed(-1))
    }

    @Test fun securityFromCapabilities() {
        assertEquals(WifiText.Security.WPA2, WifiText.security("[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]"))
        assertEquals(WifiText.Security.WPA3, WifiText.security("[RSN-SAE-CCMP][ESS]"))
        assertEquals(WifiText.Security.WPA2_WPA3, WifiText.security("[RSN-PSK+SAE-CCMP][ESS]"))
        assertEquals(WifiText.Security.OPEN, WifiText.security("[ESS]"))
        assertEquals(WifiText.Security.OWE, WifiText.security("[RSN-OWE-CCMP][ESS]"))
        assertEquals(WifiText.Security.WEP, WifiText.security("[WEP][ESS]"))
        assertEquals(WifiText.Security.ENTERPRISE, WifiText.security("[WPA2-EAP-CCMP][ESS]"))
        assertFalse(WifiText.Security.WEP.specifierOk)
        assertTrue(WifiText.Security.WPA2.needsPassword)
        assertFalse(WifiText.Security.OPEN.needsPassword)
    }

    @Test fun passwordLength() {
        assertNull(WifiText.passphraseProblem("12345678", WifiText.Security.WPA2))
        assertTrue(WifiText.passphraseProblem("1234567", WifiText.Security.WPA2)!!.contains("at least 8"))
        assertTrue(WifiText.passphraseProblem("x".repeat(64), WifiText.Security.WPA2)!!.contains("at most 63"))
        assertNull("64 hex digits is a raw key", WifiText.passphraseProblem("a".repeat(64), WifiText.Security.WPA2))
        assertNull(WifiText.passphraseProblem("", WifiText.Security.OPEN))
    }

    @Test fun whereTheGlassesAre() {
        assertEquals("192.168.4.1", WifiText.target("192.168.4.1", null, "192.168.4.23/24"))
        assertEquals("192.168.4.254", WifiText.target(null, "192.168.4.254", "192.168.4.23/24"))
        assertEquals("192.168.4.1", WifiText.target("0.0.0.0", null, "192.168.4.23/24"))
        assertEquals("10.0.0.1", WifiText.target(null, null, "10.0.0.7"))
        assertNull(WifiText.target(null, null, null))
        assertNull(WifiText.target("fe80::1", null, "fe80::2/64"))
    }

    @Test fun portProbeRequestsSendNoCredentials() {
        val get = String(PortProbe.httpGet("192.168.4.1"))
        assertTrue(get.startsWith("GET / HTTP/1.0\r\nHost: 192.168.4.1\r\n"))
        assertTrue(get.endsWith("\r\n\r\n"))
        assertFalse(get.contains("Authorization", ignoreCase = true))
        assertFalse(get.contains("Cookie", ignoreCase = true))
        val opt = String(PortProbe.rtspOptions("192.168.4.1", 554))
        assertTrue(opt.startsWith("OPTIONS rtsp://192.168.4.1:554/ RTSP/1.0\r\nCSeq: 1\r\n"))
        assertTrue(PortProbe.request("h", 8554)!!.decodeToString().startsWith("OPTIONS"))
        assertTrue(PortProbe.request("h", 8080)!!.decodeToString().startsWith("GET"))
        assertEquals(listOf(80, 8080, 554, 8554, 7070, 5000), PortProbe.PORTS)
    }

    @Test fun portProbeLines() {
        val h = "192.168.4.1"
        val http = PortProbe.Result(80, PortProbe.State.OPEN, 12, PortProbe.snippet("HTTP/1.1 200 OK\r\nServer: cam\r\n\r\n<html>".toByteArray()))
        assertEquals("192.168.4.1:80 open (12 ms), HTTP: HTTP/1.1 200 OK | Server: cam | <html>", PortProbe.line(h, http))
        assertEquals("192.168.4.1:8080 closed", PortProbe.line(h, PortProbe.Result(8080, PortProbe.State.CLOSED, 3)))
        assertEquals("192.168.4.1:554 no answer in 1 s", PortProbe.line(h, PortProbe.Result(554, PortProbe.State.TIMEOUT, 1000)))
        assertEquals("192.168.4.1:8554 open (5 ms), RTSP: no reply", PortProbe.line(h, PortProbe.Result(8554, PortProbe.State.OPEN, 5)))
        assertEquals("192.168.4.1:5000 error (NoRouteToHostException)",
            PortProbe.line(h, PortProbe.Result(5000, PortProbe.State.ERROR, 1, note = "NoRouteToHostException")))
        val rtsp = PortProbe.Result(554, PortProbe.State.OPEN, 9, "RTSP/1.0 200 OK | CSeq: 1 | Public: DESCRIBE, SETUP, PLAY")
        assertEquals("Open on 192.168.4.1: 80, 554. An RTSP video server answered on 554.",
            PortProbe.summary(h, listOf(http, PortProbe.Result(8080, PortProbe.State.CLOSED, 1), rtsp)))
        assertEquals("Nothing answered on 192.168.4.1 (tried 80, 554).",
            PortProbe.summary(h, listOf(PortProbe.Result(80, PortProbe.State.CLOSED, 1), PortProbe.Result(554, PortProbe.State.TIMEOUT, 1000))))
        assertEquals("Nothing was tried on 192.168.4.1.", PortProbe.summary(h, emptyList()))
    }

    @Test fun snippetsAreShortAndPrintable() {
        val long = ByteArray(1000) { 'a'.code.toByte() }
        assertEquals(200, PortProbe.snippet(long).length)
        assertEquals("ab·c", PortProbe.snippet(byteArrayOf('a'.code.toByte(), 'b'.code.toByte(), 0x01, 'c'.code.toByte())))
        assertEquals("x | y", PortProbe.snippet("x\r\n\r\ny\r\n".toByteArray()))
        assertEquals("", PortProbe.snippet(ByteArray(0)))
    }
}
