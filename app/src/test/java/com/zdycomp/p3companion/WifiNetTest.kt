package com.zdycomp.p3companion

import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.9 network controller: security and builder from scan capabilities, passwords, add results, failures, lists, wake. */
class WifiNetTest {

    private val t = WifiMatch.tokens("NovaSight P3", "E13C-5", "AA:BB:CC:DD:D6:BE")

    @Test fun securityAndBuilderFromCapabilities() {
        fun join(c: String) = WifiText.join(WifiText.security(c))
        assertEquals(WifiText.Security.WPA2, WifiText.security("[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]"))
        assertEquals(WifiText.Join.WPA2, join("[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]"))
        assertEquals(WifiText.Security.WPA3, WifiText.security("[RSN-SAE-CCMP][ESS]"))
        assertEquals(WifiText.Join.WPA3, join("[RSN-SAE-CCMP][ESS]"))
        assertEquals(WifiText.Security.WPA2_WPA3, WifiText.security("[WPA2-PSK+SAE-CCMP]"))
        assertEquals("transition network uses the WPA2 builder", WifiText.Join.WPA2, join("[WPA2-PSK+SAE-CCMP]"))
        assertEquals(WifiText.Join.WPA2, join("[RSN-PSK+SAE-CCMP][ESS]"))
        assertEquals(WifiText.Security.OPEN, WifiText.security("[ESS]"))
        assertEquals(WifiText.Join.OPEN, join("[ESS]"))
        assertEquals(WifiText.Join.OPEN, join(""))
        assertEquals(WifiText.Join.OWE, join("[RSN-OWE-CCMP][ESS]"))
        assertEquals(WifiText.Join.PANEL_ONLY, join("[WEP][ESS]"))
        assertEquals(WifiText.Join.PANEL_ONLY, join("[WPA2-EAP-CCMP][ESS]"))
        assertEquals(WifiText.Join.PANEL_ONLY, join("[RSN-EAP_SUITE_B_192-GCMP-256][ESS]"))
        assertEquals(WifiText.Join.WPA2, join("[WPA-PSK-TKIP][ESS]"))
    }

    @Test fun passphraseRules() {
        val wpa2 = WifiText.Security.WPA2
        assertNull(WifiText.passphraseProblem("abcdefgh", wpa2))
        assertNull(WifiText.passphraseProblem("x".repeat(63), wpa2))
        assertNull(WifiText.passphraseProblem("with space & sym!", wpa2))
        assertTrue(WifiText.passphraseProblem("abcdefg", wpa2)!!.contains("at least 8"))
        assertTrue(WifiText.passphraseProblem("", wpa2)!!.contains("at least 8"))
        assertTrue(WifiText.passphraseProblem("x".repeat(64), wpa2)!!.contains("at most 63"))
        assertTrue(WifiText.passphraseProblem("pässwörd123", wpa2)!!.contains("plain letters"))
        assertTrue(WifiText.passphraseProblem("tab\there1", wpa2)!!.contains("plain letters"))
        assertTrue(WifiText.passphraseProblem("short", WifiText.Security.WPA3)!!.contains("at least 8"))
        assertNull(WifiText.passphraseProblem("", WifiText.Security.OPEN))
        assertNull(WifiText.passphraseProblem("", WifiText.Security.OWE))
    }

    @Test fun addResultCodesMatchAndRead() {
        assertEquals(Settings.ADD_WIFI_RESULT_SUCCESS, WifiText.ADD_SUCCESS)
        assertEquals(Settings.ADD_WIFI_RESULT_ADD_OR_UPDATE_FAILED, WifiText.ADD_FAILED)
        assertEquals(Settings.ADD_WIFI_RESULT_ALREADY_EXISTS, WifiText.ADD_ALREADY_EXISTS)
        assertTrue(WifiText.addResult(0, "Home").startsWith("Saved \"Home\" on this phone"))
        assertTrue(WifiText.addResult(2, "Home").contains("already saved"))
        assertTrue(WifiText.addResult(1, "Home").startsWith("Android couldn't save \"Home\""))
        assertTrue(WifiText.addResult(9, "Home").contains("unknown answer (9)"))
        assertEquals(WifiText.addResult(0, "Home"), WifiText.addSummary(true, listOf(0), "Home"))
        assertEquals(WifiText.addResult(1, "Home"), WifiText.addSummary(false, listOf(1), "Home"))
        assertTrue(WifiText.addSummary(false, null, "Home").startsWith("Nothing was saved"))
        assertTrue(WifiText.addSummary(false, emptyList(), "Home").contains("closed without saving"))
        assertEquals("Android saved \"Home\".", WifiText.addSummary(true, null, "Home"))
    }

    @Test fun bandFromFrequency() {
        assertEquals(WifiText.Band.GHZ_2_4, WifiText.bandOf(2412))
        assertEquals(WifiText.Band.GHZ_2_4, WifiText.bandOf(2484))
        assertEquals(WifiText.Band.GHZ_5, WifiText.bandOf(5180))
        assertEquals(WifiText.Band.GHZ_5, WifiText.bandOf(5825))
        assertEquals(WifiText.Band.GHZ_6, WifiText.bandOf(5955))
        assertEquals(WifiText.Band.GHZ_6, WifiText.bandOf(6115))
        assertEquals(WifiText.Band.GHZ_60, WifiText.bandOf(60480))
        assertEquals(WifiText.Band.UNKNOWN, WifiText.bandOf(0))
        assertEquals("2.4 GHz", WifiText.band(2462))
        assertEquals("6 GHz", WifiText.band(6415))
        assertEquals("?", WifiText.band(0))
    }

    @Test fun barsAndSparkline() {
        assertEquals(4, WifiText.bars(-50)); assertEquals(3, WifiText.bars(-60)); assertEquals(2, WifiText.bars(-70))
        assertEquals(1, WifiText.bars(-80)); assertEquals(0, WifiText.bars(-90)); assertEquals(0, WifiText.bars(-127))
        assertEquals("▂▄▆·", WifiText.barsText(-60))
        assertEquals("▂▄▆· -60 dBm", WifiText.signalShort(-60))
        assertEquals("···· no signal", WifiText.signalShort(-127))
        assertEquals("▁█ ", WifiText.sparkline(listOf(-95, -30, -127)))
        assertEquals(30, WifiText.sparkline(List(45) { -60 }).length)
        assertEquals("", WifiText.sparkline(emptyList()))
    }

    @Test fun joinFailureSaysWhy() {
        val base = WifiText.JoinFacts("P3_cam", 30_000, 30_000, dialogShown = false, dialogClosed = false,
            inLatestScan = true, bandSupported = true, securitySupported = true, band = "2.4 GHz", security = "WPA/WPA2")
        assertTrue(WifiText.joinFailure(base).contains("never showed its dialog"))
        assertTrue(WifiText.joinFailure(base.copy(inLatestScan = false)).contains("wasn't in the latest scan"))
        assertTrue(WifiText.joinFailure(base.copy(dialogShown = true)).contains("stayed open for 30 s"))
        val declined = WifiText.joinFailure(base.copy(elapsedMs = 6_000, dialogShown = true, dialogClosed = true))
        assertTrue(declined, declined.contains("declined"))
        assertTrue(WifiText.joinFailure(base.copy(elapsedMs = 200)).contains("at once"))
        assertTrue(WifiText.joinFailure(base.copy(bandSupported = false, band = "6 GHz")).contains("can't use the 6 GHz band"))
        assertTrue(WifiText.joinFailure(base.copy(securitySupported = false, security = "WPA3")).contains("can't do WPA3"))
        assertTrue(WifiText.joinFailure(base).startsWith("Couldn't join \"P3_cam\": "))
    }

    @Test fun glassesScoringAndVerdict() {
        val own = WifiMatch.score("NovaSight P3", null, t)
        val brand = WifiMatch.score("XK-One_7F21", null, t)
        val phone = WifiMatch.score("Galaxy S24", "10-0050F204-5", t)
        val printer = WifiMatch.score("DIRECT-12-HP OfficeJet", "3-0050F204-1", t)
        val tv = WifiMatch.score("[TV] Living room", "7-0050F204-1", t)
        assertTrue("own name beats brand words", own > brand)
        assertTrue(brand > tv && tv > phone)
        assertEquals(-20, printer)
        assertTrue("a camera type adds a little", WifiMatch.score("P3_cam", "4-0050F204-1", t) > WifiMatch.score("P3_cam", null, t))
        assertEquals("phone", WifiMatch.typeName("10-0050F204-5"))
        assertEquals("camera", WifiMatch.typeName("4-0050F204-1"))
        assertEquals("unknown type", WifiMatch.typeName(null))
        assertEquals("unknown type", WifiMatch.typeName("garbage"))
        assertTrue(WifiMatch.verdict("DIRECT-ab-P3", "10-0050F204-5", t).startsWith("Likely the glasses"))
        assertTrue(WifiMatch.verdict("Galaxy S24", "10-0050F204-5", t).startsWith("Probably not"))
        assertTrue(WifiMatch.verdict("Thing", null, t).startsWith("Nothing in its name"))
        assertEquals("available", WifiText.p2pStatus(3))
        assertEquals("connected", WifiText.p2pStatus(0))
        assertEquals("unknown", WifiText.p2pStatus(-1))
        assertEquals("Wi-Fi 6 (802.11ax)", WifiText.standard(6))
        assertNull(WifiText.standard(0))
    }

    @Test fun scanSummaryIsPlainAboutTheGlasses() {
        assertEquals("Scan: 14 network(s), 2 Wi-Fi Direct device(s); none looks like the glasses. " +
            "No glasses network or Wi-Fi Direct name has been seen in any of the 2 scan(s) so far.",
            WifiText.scanSummary(14, 2, emptyList(), 2, 0))
        assertEquals("Scan: 3 network(s), 1 Wi-Fi Direct device(s); looks like the glasses: P3_cam (Wi-Fi Direct)",
            WifiText.scanSummary(3, 1, listOf("P3_cam (Wi-Fi Direct)"), 4, 1))
        assertTrue(WifiText.scanSummary(3, 0, emptyList(), 5, 1).endsWith("Seen in 1 of 5 scan(s) so far, not this time."))
    }

    @Test fun wakeSendsOnlySafeReads() {
        val nodes = WifiWake.nodes()
        assertEquals(listOf("7200", "7310", "7600"), nodes)
        nodes.forEach { assertTrue(it, FullRes.allowed(it, Xk.ACTION_READ)); assertFalse(it, it in FullRes.DENY_NODES) }
        listOf("9000", "9001", "7400", "7300", "7500").forEach { assertFalse(it, it in WifiWake.CANDIDATES) }
        assertTrue(WifiWake.intro(nodes).startsWith(WifiWake.NO_COMMAND))
        assertTrue(WifiWake.intro(nodes).contains("read-only"))
        assertEquals("No Wi-Fi command is known for these glasses (the SDK docs name none), so nothing was sent.", WifiWake.intro(emptyList()))
        assertEquals(listOf(5_000L, 15_000L), WifiWake.RESCAN_AFTER_MS)
        assertTrue(WifiWake.outcome(emptyList(), 3, 0).contains("no glasses network or Wi-Fi Direct name was seen in any of the 3 scan(s)"))
        assertTrue(WifiWake.outcome(listOf("P3_cam"), 3, 1).contains("showed up as P3_cam"))
        assertEquals(30_000, WifiControl.JOIN_TIMEOUT_MS)
    }
}
