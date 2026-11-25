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
}

object WifiText {

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

    /** WPA2 passphrases are 8 to 63 characters (or 64 hex digits). Null when fine, else what is wrong. */
    fun passphraseProblem(p: CharSequence, security: Security): String? = when {
        !security.needsPassword -> null
        p.length == 64 && p.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } -> null
        p.length < 8 -> "A Wi-Fi password has at least 8 characters."
        p.length > 63 -> "A Wi-Fi password has at most 63 characters."
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
