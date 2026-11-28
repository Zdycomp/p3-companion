package com.zdycomp.p3companion

import java.util.Locale

/**
 * 3.8 Wi-Fi screen, the pure parts (JVM-tested): which names look like the glasses, how signal,
 * band, channel and security read, where the glasses' network gateway probably is, and how a port
 * probe is written in the log.
 */
object WifiMatch {

    /** Words the glasses' Wi-Fi or Wi-Fi Direct name might contain, lower case, besides their own name and model. */
    val BRAND = listOf("p3", "e13c", "d6be", "xk", "shenju", "novasight")

    /** The glasses' Bluetooth name, model (and its family before '-'), the address's last 4 hex digits, and [BRAND]. */
    fun tokens(name: String?, model: String?, address: String?): List<String> {
        val out = LinkedHashSet<String>()
        name?.trim()?.lowercase(Locale.US)?.takeIf { it.length >= 3 }?.let { out += it }
        model?.trim()?.lowercase(Locale.US)?.takeIf { it.length >= 3 }?.let { out += it; out += it.substringBefore('-') }
        address?.replace(":", "")?.takeIf { it.length >= 4 }?.let { out += it.takeLast(4).lowercase(Locale.US) }
        out += BRAND
        return out.filter { it.length >= 2 }
    }

    /**
     * The token [name] matches, or null. Short tokens (3 letters or fewer, like "p3" and "xk") must
     * stand at the start of a word and not run on into letters, so "P3_D6BE" and "XK-One" match but
     * "AP3000" and "XKCD-guest" don't.
     */
    fun match(name: String?, tokens: List<String>): String? {
        val s = name?.lowercase(Locale.US)?.trim() ?: return null
        if (s.isEmpty()) return null
        for (t in tokens) {
            if (t.isEmpty()) continue
            if (t.length > 3) { if (s.contains(t)) return t; continue }
            var from = 0
            while (true) {
                val i = s.indexOf(t, from)
                if (i < 0) break
                val before = if (i == 0) ' ' else s[i - 1]
                val after = if (i + t.length >= s.length) ' ' else s[i + t.length]
                if (!before.isLetterOrDigit() && !after.isLetter()) return t
                from = i + 1
            }
        }
        return null
    }

    fun matches(name: String?, tokens: List<String>): Boolean = match(name, tokens) != null

    /** The Wi-Fi Direct primary device type's category, from "10-0050F204-5" (category-OUI-subcategory). */
    fun category(primaryType: String?): Int? = primaryType?.trim()?.substringBefore('-')?.toIntOrNull()

    /** The Wi-Fi Direct device category in words. */
    fun typeName(primaryType: String?): String = when (category(primaryType)) {
        1 -> "computer"
        2 -> "input device"
        3 -> "printer or scanner"
        4 -> "camera"
        5 -> "storage"
        6 -> "network equipment"
        7 -> "TV or display"
        8 -> "media device"
        9 -> "game console"
        10 -> "phone"
        11 -> "audio device"
        12 -> "dock"
        null -> "unknown type"
        else -> "other (type ${category(primaryType)})"
    }

    /**
     * 3.9: how likely a Wi-Fi Direct device (or network) is the glasses, for sorting: a name match is worth
     * 100 less 5 per place down [tokens] (their own name first, the brand words last); a camera, audio or
     * media type adds a little, a phone, computer or printer takes some away.
     */
    fun score(name: String?, primaryType: String?, tokens: List<String>): Int {
        var s = 0
        val t = match(name, tokens)
        if (t != null) s += 100 - 5 * tokens.indexOf(t).coerceIn(0, 10)
        s += when (category(primaryType)) {
            4 -> 15
            8, 11 -> 10
            7 -> 5
            1, 3, 10 -> -20
            else -> 0
        }
        return s
    }

    /** The "Is this the glasses?" answer for one device. */
    fun verdict(name: String?, primaryType: String?, tokens: List<String>): String {
        val t = match(name, tokens)
        val type = typeName(primaryType)
        return when {
            t != null -> "Likely the glasses: its name matches \"$t\" (type: $type)."
            category(primaryType) in setOf(1, 3, 7, 10) -> "Probably not the glasses: it says it's a $type, and its name doesn't match them."
            else -> "Nothing in its name or type ($type) points to the glasses. Connect only if you know it's them."
        }
    }
}

object WifiText {

    /**
     * 4.1.1: what the owner's tests showed: the E13C-5 makes no Wi-Fi network and no Wi-Fi Direct group.
     * Said wherever the app offers to mark a network as a device's, so a home router isn't taken for the glasses.
     */
    const val NO_GLASSES_WIFI = "Your glasses have no Wi-Fi: 8 scans and Bluetooth probes never found a glasses network or Wi-Fi Direct device. " +
        "They talk to the phone over Bluetooth only."

    /** 4.1.1: the "device's network" mark, only under an advanced note. */
    const val MARK_TITLE = "Advanced: another device's own network"
    const val MARK_NOTE = "Not for the glasses, and never for your home router. Only for a different device that makes its own Wi-Fi " +
        "(a camera, a drone, a test hotspot): it allows \"For this app only\" (local, no internet)."

    /** The warning on a network that is marked: most likely a mistake, since the glasses have no Wi-Fi. */
    fun markedWarning(name: String): String =
        "\"$name\" is marked as a device's network. The glasses have no Wi-Fi, so if this is your router or home network, turn the mark off."

    /** The short tag in a network's row. */
    const val MARKED_TAG = "marked as a device's network (not the glasses)"

    /** 4.1.1: the Wake button: it asks the glasses over Bluetooth, so without them it says so instead of scanning. */
    fun wakeLabel(bluetoothConnected: Boolean, waking: Boolean): String = when {
        !bluetoothConnected -> "Connect the glasses first"
        waking -> "Waking…"
        else -> "Wake the glasses' Wi-Fi"
    }

    /** The SSID without its quotes; null for none, blank or Android's "<unknown ssid>" (no location permission). */
    fun ssid(raw: String?): String? {
        val s = raw?.trim()?.removeSurrounding("\"") ?: return null
        return if (s.isBlank() || s == "<unknown ssid>" || s == "0x") null else s
    }

    /** "excellent" ≥ -55 dBm, "good" ≥ -67, "fair" ≥ -75, "weak" below; "no signal" for Android's -127 placeholder. */
    fun quality(rssi: Int): String = when {
        rssi <= -127 || rssi >= 0 -> "no signal"
        rssi >= -55 -> "excellent"
        rssi >= -67 -> "good"
        rssi >= -75 -> "fair"
        else -> "weak"
    }

    fun signal(rssi: Int): String = if (rssi <= -127 || rssi >= 0) "no signal" else "$rssi dBm (${quality(rssi)})"

    fun band(freqMhz: Int): String = when (freqMhz) {
        in 2400..2500 -> "2.4 GHz"
        in 4900..5899 -> "5 GHz"
        in 5925..7125 -> "6 GHz"
        in 57000..71000 -> "60 GHz"
        else -> if (freqMhz > 0) "$freqMhz MHz" else "?"
    }

    /** The channel number for a frequency, or null. */
    fun channel(freqMhz: Int): Int? = when {
        freqMhz == 2484 -> 14
        freqMhz in 2412..2472 -> (freqMhz - 2407) / 5
        freqMhz in 5160..5885 -> (freqMhz - 5000) / 5
        freqMhz == 5935 -> 2
        freqMhz in 5955..7115 -> (freqMhz - 5950) / 5
        else -> null
    }

    /** "5180 MHz (5 GHz, channel 36)". */
    fun frequency(freqMhz: Int): String {
        if (freqMhz <= 0) return "unknown"
        val ch = channel(freqMhz)
        return "$freqMhz MHz (${band(freqMhz)}" + (ch?.let { ", channel $it" } ?: "") + ")"
    }

    fun linkSpeed(mbps: Int): String = if (mbps > 0) "$mbps Mbps" else "unknown"

    enum class Security(val label: String, val needsPassword: Boolean, val specifierOk: Boolean) {
        OPEN("open", false, true),
        OWE("enhanced open", false, true),
        WEP("WEP (old)", true, false),
        WPA2("WPA/WPA2", true, true),
        WPA3("WPA3", true, true),
        WPA2_WPA3("WPA2/WPA3", true, true),
        ENTERPRISE("enterprise", true, false),
    }

    /** From a scan result's capabilities, like "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]" or "[RSN-SAE-CCMP][ESS]". */
    fun security(capabilities: String?): Security {
        val c = capabilities?.uppercase(Locale.US).orEmpty()
        return when {
            c.contains("EAP") -> Security.ENTERPRISE
            c.contains("SAE") && c.contains("PSK") -> Security.WPA2_WPA3
            c.contains("SAE") -> Security.WPA3
            c.contains("PSK") || c.contains("WPA") -> Security.WPA2
            c.contains("OWE") -> Security.OWE
            c.contains("WEP") -> Security.WEP
            else -> Security.OPEN
        }
    }

    /**
     * 3.9: checked before anything is sent to Android. A Wi-Fi password is 8 to 63 plain characters
     * (letters, digits, spaces, symbols); Android's builders take nothing else. Null when fine.
     */
    fun passphraseProblem(p: CharSequence, security: Security): String? = when {
        !security.needsPassword -> null
        p.length < 8 -> "A Wi-Fi password has at least 8 characters."
        p.length > 63 -> "A Wi-Fi password has at most 63 characters."
        p.any { it.code !in 0x20..0x7E } -> "Android takes only plain letters, digits, spaces and symbols in a Wi-Fi password."
        else -> null
    }

    // ---------------- 3.9: joining, lists, results ----------------

    /** Which Android builder method a network is joined with. */
    enum class Join { OPEN, OWE, WPA2, WPA3, PANEL_ONLY }

    /**
     * Open → no password; enhanced open → setIsEnhancedOpen; WPA2 → setWpa2Passphrase; WPA3 (SAE only) →
     * setWpa3Passphrase; a WPA2/WPA3 transition network → setWpa2Passphrase (every phone can do that);
     * WEP and enterprise → only Android's Wi-Fi panel can join them.
     */
    fun join(security: Security): Join = when (security) {
        Security.OPEN -> Join.OPEN
        Security.OWE -> Join.OWE
        Security.WPA2, Security.WPA2_WPA3 -> Join.WPA2
        Security.WPA3 -> Join.WPA3
        Security.WEP, Security.ENTERPRISE -> Join.PANEL_ONLY
    }

    enum class Band(val label: String) { GHZ_2_4("2.4 GHz"), GHZ_5("5 GHz"), GHZ_6("6 GHz"), GHZ_60("60 GHz"), UNKNOWN("?") }

    fun bandOf(freqMhz: Int): Band = when (freqMhz) {
        in 2400..2500 -> Band.GHZ_2_4
        in 4900..5899 -> Band.GHZ_5
        in 5925..7125 -> Band.GHZ_6
        in 57000..71000 -> Band.GHZ_60
        else -> Band.UNKNOWN
    }

    /** 0 to 4 bars: 4 at -55 dBm or better, 3 at -67, 2 at -75, 1 at -85, else 0. */
    fun bars(rssi: Int): Int = when {
        rssi <= -127 || rssi >= 0 -> 0
        rssi >= -55 -> 4
        rssi >= -67 -> 3
        rssi >= -75 -> 2
        rssi >= -85 -> 1
        else -> 0
    }

    /** "▂▄▆·" for 3 bars. */
    fun barsText(rssi: Int): String {
        val n = bars(rssi)
        return (0 until 4).joinToString("") { if (it < n) "▂▄▆█"[it].toString() else "·" }
    }

    /** "▂▄▆· -60 dBm", or "···· no signal". */
    fun signalShort(rssi: Int): String = barsText(rssi) + " " + if (rssi <= -127 || rssi >= 0) "no signal" else "$rssi dBm"

    /** The values Android puts in Settings.EXTRA_WIFI_NETWORK_RESULT_LIST (Settings.ADD_WIFI_RESULT_*). */
    const val ADD_SUCCESS = 0
    const val ADD_FAILED = 1
    const val ADD_ALREADY_EXISTS = 2

    /** One add result in plain words. */
    fun addResult(code: Int, ssid: String): String = when (code) {
        ADD_SUCCESS -> "Saved \"$ssid\" on this phone. Android joins it whenever it's in range, with internet as usual."
        ADD_ALREADY_EXISTS -> "\"$ssid\" was already saved on this phone, so nothing changed. If its password changed, forget it in the Wi-Fi panel and add it again."
        ADD_FAILED -> "Android couldn't save \"$ssid\" (it refused the network or its settings). Try the Wi-Fi panel."
        else -> "Android gave an unknown answer ($code) for \"$ssid\"."
    }

    /** What the Add networks dialog came back with: [ok] is RESULT_OK, [codes] the result list (null when none). */
    fun addSummary(ok: Boolean, codes: List<Int>?, ssid: String): String {
        val c = codes?.firstOrNull()
        if (c != null) return addResult(c, ssid)
        return if (ok) "Android saved \"$ssid\"." else "Nothing was saved: Android's dialog was closed without saving \"$ssid\"."
    }

    /** What is known when a "for this app only" join ends without a connection. */
    data class JoinFacts(
        val ssid: String,
        val elapsedMs: Long,
        val timeoutMs: Long,
        /** The app went to the background while waiting, which is when Android's own dialog is up. */
        val dialogShown: Boolean,
        /** The app came back before the answer (the dialog was closed). */
        val dialogClosed: Boolean,
        val inLatestScan: Boolean,
        val bandSupported: Boolean,
        val securitySupported: Boolean,
        val band: String,
        val security: String,
    )

    /** The real reason, as far as Android lets an app tell, in one or two plain sentences. */
    fun joinFailure(f: JoinFacts): String {
        val head = "Couldn't join \"${f.ssid}\": "
        val scanNote = if (f.inLatestScan) "" else " It wasn't in the latest scan either, so it may be out of range or switched off."
        val timedOut = f.elapsedMs >= f.timeoutMs - 1_500
        val secs = f.timeoutMs / 1000
        return head + when {
            !f.securitySupported -> "this phone's Wi-Fi can't do ${f.security} networks."
            !f.bandSupported -> "this phone's Wi-Fi can't use the ${f.band} band that network is on."
            timedOut && f.dialogShown && !f.dialogClosed -> "Android's dialog stayed open for $secs s without a choice, so the request timed out."
            timedOut && !f.dialogShown -> "Android looked for $secs s and never showed its dialog, so it never found the network in its own scan.$scanNote"
            timedOut -> "the request timed out after $secs s.$scanNote"
            f.dialogShown -> "Android's dialog closed before the $secs s were up: it was declined, or Android tried and the network refused the password."
            else -> "Android ended the request at once without showing its dialog (another app's request may be waiting, or the phone refused this kind of request).$scanNote"
        }
    }

    /** A 60 s signal trace: one block per sample, -90 dBm and below lowest, -30 and above highest, gaps blank. */
    fun sparkline(rssis: List<Int>, width: Int = 30): String =
        rssis.takeLast(width).joinToString("") { r ->
            if (r <= -127 || r >= 0) " " else "▁▂▃▄▅▆▇█"[((r + 90) * 8 / 61).coerceIn(0, 7)].toString()
        }

    /** The Scan line in the log. [ever] is how many scans since the app started saw anything like the glasses. */
    fun scanSummary(networks: Int, peers: Int, glasses: List<String>, scans: Int, ever: Int): String {
        val now = "Scan: $networks network(s), $peers Wi-Fi Direct device(s); " +
            if (glasses.isEmpty()) "none looks like the glasses" else "looks like the glasses: " + glasses.joinToString(", ")
        return now + when {
            glasses.isNotEmpty() -> ""
            ever == 0 -> ". No glasses network or Wi-Fi Direct name has been seen in any of the $scans scan(s) so far."
            else -> ". Seen in $ever of $scans scan(s) so far, not this time."
        }
    }

    /** WifiP2pDevice.status in words. */
    fun p2pStatus(status: Int): String = when (status) {
        0 -> "connected"
        1 -> "invited"
        2 -> "failed"
        3 -> "available"
        4 -> "unavailable"
        else -> "unknown"
    }

    /** WifiInfo.getWifiStandard: "Wi-Fi 6 (802.11ax)". */
    fun standard(code: Int): String? = when (code) {
        1 -> "802.11a/b/g"
        4 -> "Wi-Fi 4 (802.11n)"
        5 -> "Wi-Fi 5 (802.11ac)"
        6 -> "Wi-Fi 6 (802.11ax)"
        7 -> "802.11ad"
        8 -> "Wi-Fi 7 (802.11be)"
        else -> null
    }

    /**
     * Where to look for the glasses on their own network: the gateway the phone was given, else the
     * DHCP server, else the ".1" of the phone's own address (a hotspot's usual address).
     */
    fun target(gateway: String?, dhcpServer: String?, ownAddress: String?): String? {
        fun ok(s: String?) = s != null && s.isNotBlank() && s != "0.0.0.0" && IPV4.matches(s)
        if (ok(gateway)) return gateway
        if (ok(dhcpServer)) return dhcpServer
        val own = ownAddress?.substringBefore('/') ?: return null
        if (!IPV4.matches(own)) return null
        return own.substringBeforeLast('.') + ".1"
    }

    private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
}

/** The small port probe run on the glasses' network once the phone has joined it. */
object PortProbe {
    /** Ports tried, in order: web, RTSP video, and the usual camera ports. */
    val PORTS = listOf(80, 8080, 554, 8554, 7070, 5000)
    val WEB_PORTS = setOf(80, 8080, 5000, 7070)
    val RTSP_PORTS = setOf(554, 8554)
    const val CONNECT_TIMEOUT_MS = 1_000
    const val READ_TIMEOUT_MS = 2_000
    const val SNIPPET = 200

    enum class State { OPEN, CLOSED, TIMEOUT, ERROR }

    data class Result(val port: Int, val state: State, val ms: Long, val reply: String? = null, val note: String? = null)

    /** "GET / HTTP/1.0" with only a Host header: no credentials, no cookies. */
    fun httpGet(host: String): ByteArray = "GET / HTTP/1.0\r\nHost: $host\r\nUser-Agent: P3Companion\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)

    /** "OPTIONS" asks an RTSP server what it can do; it changes nothing. */
    fun rtspOptions(host: String, port: Int): ByteArray = "OPTIONS rtsp://$host:$port/ RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: P3Companion\r\n\r\n".toByteArray(Charsets.US_ASCII)

    /** What the request for [port] is, or null when only the connection is tried. */
    fun request(host: String, port: Int): ByteArray? = when (port) {
        in RTSP_PORTS -> rtspOptions(host, port)
        in WEB_PORTS -> httpGet(host)
        else -> null
    }

    /** The first [max] characters of a reply, printable only: line breaks become " | ", others "·". */
    fun snippet(b: ByteArray, len: Int = b.size, max: Int = SNIPPET): String {
        val sb = StringBuilder()
        var lastBreak = false
        for (i in 0 until minOf(len, b.size)) {
            if (sb.length >= max) break
            val c = b[i].toInt() and 0xFF
            when {
                c == '\r'.code || c == '\n'.code -> { if (!lastBreak && sb.isNotEmpty()) sb.append(" | "); lastBreak = true; continue }
                c in 0x20..0x7E -> sb.append(c.toChar())
                else -> sb.append('·')
            }
            lastBreak = false
        }
        return sb.toString().take(max).trimEnd(' ', '|')
    }

    /** "192.168.4.1:80 open (12 ms), HTTP: HTTP/1.1 200 OK | Server: …" / "closed" / "no answer in 1 s". */
    fun line(host: String, r: Result): String {
        val what = when (r.state) {
            State.OPEN -> "open (${r.ms} ms)" + when {
                r.reply != null && r.reply.isNotEmpty() -> ", " + kind(r.port) + ": " + r.reply
                r.port in WEB_PORTS || r.port in RTSP_PORTS -> ", " + kind(r.port) + ": no reply"
                else -> ""
            }
            State.CLOSED -> "closed"
            State.TIMEOUT -> "no answer in ${CONNECT_TIMEOUT_MS / 1000} s"
            State.ERROR -> "error" + (r.note?.let { " ($it)" } ?: "")
        }
        return "$host:${r.port} $what"
    }

    private fun kind(port: Int) = if (port in RTSP_PORTS) "RTSP" else "HTTP"

    /** One line for the end: what answered on [host]. */
    fun summary(host: String, results: List<Result>): String {
        val open = results.filter { it.state == State.OPEN }
        if (results.isEmpty()) return "Nothing was tried on $host."
        if (open.isEmpty()) return "Nothing answered on $host (tried ${results.joinToString(", ") { it.port.toString() }})."
        val video = open.filter { it.port in RTSP_PORTS && it.reply?.startsWith("RTSP/") == true }
        return "Open on $host: " + open.joinToString(", ") { it.port.toString() } +
            if (video.isNotEmpty()) ". An RTSP video server answered on ${video.joinToString(", ") { it.port.toString() }}." else "."
    }
}

/**
 * 3.9 "Wake the glasses' Wi-Fi": what may be asked over Bluetooth while the phone scans, and when it
 * looks again. The SDK docs (NODES.md, PAYLOADS.md) name no Wi-Fi or network command for these
 * glasses. The transfer nodes they do name are asked with read-only requests (action 1, no data), and
 * only the ones the full-size probe's safety list already allows: 7200, 7310, 7600. 7300 (fetch a
 * part) and 7500 (end a transfer) are not reads, and 9000/9001 (media transfer control) and 7400
 * (delete) stay on that list's never-send side.
 */
object WifiWake {
    const val NO_COMMAND = "No Wi-Fi command is known for these glasses"

    val CANDIDATES = listOf("7200", "7310", "7600")

    /** The candidates [FullRes.allowed] lets through as a plain read. */
    fun nodes(): List<String> = CANDIDATES.filter { FullRes.allowed(it, Xk.ACTION_READ) }

    /** Re-scans this long after the requests end. */
    val RESCAN_AFTER_MS = listOf(5_000L, 15_000L)

    /** How long each re-scan listens. */
    const val RESCAN_MS = 6_000L

    /** The first log line of an attempt. */
    fun intro(nodes: List<String>): String =
        if (nodes.isEmpty()) "$NO_COMMAND (the SDK docs name none), so nothing was sent."
        else "$NO_COMMAND (the SDK docs name none). Asking the transfer nodes ${nodes.joinToString(", ")} " +
            "with read-only requests (action 1, no data) in case one switches the glasses' Wi-Fi on."

    /** The last line: plain about whether the glasses ever showed up. */
    fun outcome(seenNow: List<String>, scans: Int, ever: Int): String = when {
        seenNow.isNotEmpty() -> "Wake attempt done: the glasses showed up as ${seenNow.joinToString(", ")}."
        ever > 0 -> "Wake attempt done: nothing like the glasses this time (seen in $ever earlier scan(s))."
        else -> "Wake attempt done: no glasses network or Wi-Fi Direct name was seen in any of the $scans scan(s). " +
            "These glasses don't seem to switch Wi-Fi on for the phone."
    }
}
