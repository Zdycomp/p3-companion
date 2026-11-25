package com.zdycomp.p3companion

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections

/**
 * 3.8 Wi-Fi controller (the Wi-Fi screen). Reads the phone's Wi-Fi state, looks for the glasses as an
 * access point (scan results) or a Wi-Fi Direct peer, joins one when the person taps Connect, and then
 * asks the glasses' network which simple services answer (web, RTSP, camera ports).
 *
 * Rules: it never changes a phone-wide Wi-Fi setting (no Wi-Fi on/off, no saved networks). Joining an
 * access point uses [WifiNetworkSpecifier], which Android confirms in its own dialog, only for this app,
 * local-only (no internet capability asked). A password is typed by the person, handed straight to
 * that request, and never stored or logged. The app's process is bound to the glasses' network only
 * while the port probe runs, and released right after. Framework only. One instance per app.
 */
class WifiControl private constructor(context: Context) {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val wm: WifiManager? = app.getSystemService(WifiManager::class.java)
    private val cm: ConnectivityManager? = app.getSystemService(ConnectivityManager::class.java)

    /** The phone's Wi-Fi as it is now. */
    data class Status(
        val hasWifi: Boolean,
        val enabled: Boolean,
        val connected: Boolean,
        /** Null when not connected or Android hides it (no location permission, or Location off). */
        val ssid: String?,
        val rssi: Int,
        val linkMbps: Int,
        val freqMhz: Int,
        val address: String?,
        val gateway: String?,
        val internet: Boolean,
    )

    /** Something found by [scan]: an access point or a Wi-Fi Direct peer. */
    data class Found(
        val name: String,
        val p2p: Boolean,
        /** BSSID or the peer's device address. */
        val address: String,
        val rssi: Int,
        val freqMhz: Int,
        val security: WifiText.Security,
        /** The glasses token it matched, or null. */
        val match: String?,
    ) {
        val looksLikeGlasses: Boolean get() = match != null
    }

    data class Scan(val found: List<Found>, val notes: List<String>, val at: Long)

    /** What the app has joined: an access point (with its [Network]) or a Wi-Fi Direct group. */
    data class Link(val name: String, val p2p: Boolean, val network: Network?, val host: String?, val note: String?)

    fun interface Listener { fun onWifiChanged() }

    private val listeners = Collections.synchronizedSet(LinkedHashSet<Listener>())
    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }
    private fun changed() = main.post { synchronized(listeners) { listeners.toList() }.forEach { it.onWifiChanged() } }

    /** Lines for the screen's log (the person's own view; other networks are named here, never in the shared glasses log). */
    private val log = Collections.synchronizedList(mutableListOf<String>())
    fun logLines(): List<String> = synchronized(log) { log.toList() }
    fun clearLog() { log.clear(); changed() }
    private fun note(s: String, glassesLog: Boolean = true) {
        synchronized(log) { log += s; while (log.size > 200) log.removeAt(0) }
        if (glassesLog) runCatching { XkGlasses.peek()?.addLog("Wi-Fi: $s") }
        changed()
    }

    @Volatile var scanning = false; private set
    @Volatile var lastScan: Scan? = null; private set
    @Volatile var link: Link? = null; private set
    @Volatile var connecting: String? = null; private set
    @Volatile var probing = false; private set
    @Volatile var lastProbe: List<String> = emptyList(); private set

    private fun granted(p: String) = app.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** Network names and the scan list need location on every Android version (an Android rule). */
    fun hasLocation(): Boolean = granted(Manifest.permission.ACCESS_FINE_LOCATION)

    /** Wi-Fi Direct needs Nearby Wi-Fi devices on Android 13+, location before that. */
    fun hasNearby(): Boolean = if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.NEARBY_WIFI_DEVICES) else hasLocation()

    fun locationOn(): Boolean = runCatching { app.getSystemService(android.location.LocationManager::class.java)?.isLocationEnabled }.getOrNull() ?: true

    // ---------------- state ----------------

    @SuppressLint("MissingPermission")
    fun status(): Status {
        val w = wm ?: return Status(false, false, false, null, -127, 0, 0, null, null, false)
        val enabled = runCatching { w.isWifiEnabled }.getOrDefault(false)
        @Suppress("DEPRECATION")
        val net = runCatching { cm?.allNetworks?.firstOrNull { n ->
            val caps = cm.getNetworkCapabilities(n)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true && n != link?.network
        } }.getOrNull()
        val caps = net?.let { runCatching { cm?.getNetworkCapabilities(it) }.getOrNull() }
        @Suppress("DEPRECATION")
        val info = runCatching { w.connectionInfo }.getOrNull()
        val connected = net != null || (info != null && info.networkId != -1)
        val lp = net?.let { runCatching { cm?.getLinkProperties(it) }.getOrNull() }
        return Status(
            hasWifi = true,
            enabled = enabled,
            connected = enabled && connected,
            ssid = if (connected) WifiText.ssid(info?.ssid) else null,
            rssi = info?.rssi ?: -127,
            linkMbps = info?.linkSpeed ?: 0,
            freqMhz = info?.frequency ?: 0,
            address = lp?.let { ipv4(it) },
            gateway = lp?.let { gatewayOf(it) },
            internet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
        )
    }

    private fun ipv4(lp: LinkProperties): String? =
        lp.linkAddresses.firstOrNull { it.address is Inet4Address }?.let { "${it.address.hostAddress}/${it.prefixLength}" }

    private fun gatewayOf(lp: LinkProperties): String? =
        lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
            ?: lp.routes.firstOrNull { it.gateway is Inet4Address && it.gateway?.isAnyLocalAddress == false }?.gateway?.hostAddress

    // ---------------- scan ----------------

    private var p2p: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null

    @SuppressLint("MissingPermission")
    private fun p2pChannel(): Pair<WifiP2pManager, WifiP2pManager.Channel>? {
        val m = p2p ?: app.getSystemService(WifiP2pManager::class.java)?.also { p2p = it } ?: return null
        val ch = channel ?: m.initialize(app, Looper.getMainLooper(), null)?.also { channel = it } ?: return null
        return m to ch
    }

    /**
     * Looks for about [ms] ms: a fresh scan (Android may refuse a new one and give its last list) and a
     * Wi-Fi Direct search. Matches use [tokens] ([WifiMatch.tokens]). [done] runs on the main thread.
     */
    @SuppressLint("MissingPermission")
    fun scan(tokens: List<String>, ms: Long = 8_000, done: (Scan) -> Unit) {
        if (scanning) return
        scanning = true
        changed()
        main.post {
            val notes = mutableListOf<String>()
            val aps = LinkedHashMap<String, Found>()
            val peers = LinkedHashMap<String, Found>()
            val w = wm
            fun collectAps() {
                if (w == null) return
                try {
                    @Suppress("DEPRECATION")
                    w.scanResults?.forEach { r ->
                        @Suppress("DEPRECATION")
                        val name = WifiText.ssid(r.SSID) ?: return@forEach
                        val f = Found(name, false, r.BSSID ?: "", r.level, r.frequency, WifiText.security(r.capabilities), WifiMatch.match(name, tokens))
                        val old = aps[name]
                        if (old == null || f.rssi > old.rssi) aps[name] = f
                    }
                } catch (_: SecurityException) {
                    if (notes.none { it.startsWith("The list of networks") }) notes += "The list of networks needs the Location permission (an Android rule; the app doesn't use your location)."
                }
            }
            fun collectPeers() {
                val (m, ch) = p2pChannel() ?: return
                try {
                    m.requestPeers(ch) { list ->
                        list?.deviceList?.forEach { d ->
                            val name = d.deviceName?.takeIf { it.isNotBlank() } ?: d.deviceAddress ?: return@forEach
                            peers[d.deviceAddress ?: name] = Found(name, true, d.deviceAddress ?: "", -127, 0, WifiText.Security.OPEN, WifiMatch.match(name, tokens))
                        }
                    }
                } catch (_: SecurityException) {
                    if (notes.none { it.startsWith("Wi-Fi Direct") }) notes += "Wi-Fi Direct needs the Nearby devices permission."
                }
            }
            if (w == null) notes += "This phone has no Wi-Fi."
            else if (!runCatching { w.isWifiEnabled }.getOrDefault(false)) notes += "Wi-Fi is off on the phone. Turn it on in the Wi-Fi panel (the app doesn't switch it for you)."
            if (!locationOn()) notes += "Location is off on the phone, so Android hides the list of networks."
            val rx = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    when (i.action) {
                        WifiManager.SCAN_RESULTS_AVAILABLE_ACTION -> collectAps()
                        WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> collectPeers()
                    }
                }
            }
            AudioRoute.registerSystemReceiver(app, rx, IntentFilter().apply {
                addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            })
            @Suppress("DEPRECATION")
            val started = w != null && runCatching { w.startScan() }.getOrDefault(false)
            if (w != null && !started) notes += "Android didn't start a new scan (it limits how often apps scan), so this is the phone's last list."
            collectAps()
            p2pChannel()?.let { (m, ch) ->
                try {
                    m.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {}
                        override fun onFailure(reason: Int) { notes += "The Wi-Fi Direct search didn't start (${p2pReason(reason)})." }
                    })
                } catch (_: SecurityException) {
                    notes += "Wi-Fi Direct needs the Nearby devices permission."
                }
            } ?: notes.add("This phone has no Wi-Fi Direct.")
            main.postDelayed({
                collectAps()
                collectPeers()
                main.postDelayed({
                    runCatching { app.unregisterReceiver(rx) }
                    p2pChannel()?.let { (m, ch) -> runCatching { m.stopPeerDiscovery(ch, null) } }
                    val list = (aps.values + peers.values).sortedWith(
                        compareByDescending<Found> { it.looksLikeGlasses }.thenBy { it.p2p }.thenByDescending { it.rssi })
                    val res = Scan(list, notes.distinct(), System.currentTimeMillis())
                    lastScan = res
                    scanning = false
                    val glasses = list.filter { it.looksLikeGlasses }
                    note("Scan: ${aps.size} network(s), ${peers.size} Wi-Fi Direct device(s); " +
                        if (glasses.isEmpty()) "none looks like the glasses" else "looks like the glasses: " +
                            glasses.joinToString(", ") { it.name + if (it.p2p) " (Wi-Fi Direct)" else " (network, ${it.security.label})" })
                    res.notes.forEach { note(it) }
                    done(res)
                    changed()
                }, 700)
            }, ms)
        }
    }

    // ---------------- connect ----------------

    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var p2pReceiver: BroadcastReceiver? = null

    /**
     * Joins access point [f] for this app only (Android shows its own confirmation). [passphrase] is the
     * person's typed password, or null for an open network; it goes into the request and nowhere else.
     */
    fun connect(f: Found, passphrase: CharSequence?, done: (String?) -> Unit) {
        if (f.p2p) { connectP2p(f, done); return }
        val c = cm ?: return done("This phone has no Wi-Fi.")
        if (!f.security.specifierOk) return done("${f.security.label} networks can't be joined from an app. Use the Wi-Fi panel.")
        disconnect(quiet = true)
        val spec = try {
            WifiNetworkSpecifier.Builder().setSsid(f.name).apply {
                if (f.address.isNotBlank() && f.address != "02:00:00:00:00:00") runCatching { setBssid(android.net.MacAddress.fromString(f.address)) }
                val p = passphrase?.toString()
                when (f.security) {
                    WifiText.Security.WPA3 -> setWpa3Passphrase(p ?: "")
                    WifiText.Security.WPA2, WifiText.Security.WPA2_WPA3 -> setWpa2Passphrase(p ?: "")
                    WifiText.Security.OWE -> setIsEnhancedOpen(true)
                    else -> {}
                }
            }.build()
        } catch (e: IllegalArgumentException) {
            return done("Android didn't accept that: ${e.message ?: "check the password"}")
        }
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        connecting = f.name
        changed()
        note("Asking Android to join \"${f.name}\" for this app only (${f.security.label}" + (if (passphrase != null) ", with the password you typed" else "") + ")")
        var answered = false
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val lp = runCatching { c.getLinkProperties(network) }.getOrNull()
                val own = lp?.let { ipv4(it) }
                val host = WifiText.target(lp?.let { gatewayOf(it) },
                    if (Build.VERSION.SDK_INT >= 30) lp?.dhcpServerAddress?.hostAddress else null, own)
                link = Link(f.name, false, network, host, own?.let { "phone $it" })
                connecting = null
                note("Joined \"${f.name}\" (local only). Phone ${own ?: "?"}, glasses' side ${host ?: "unknown"}")
                if (!answered) { answered = true; main.post { done(null) } }
                changed()
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                val cur = link ?: return
                if (cur.network != network) return
                val own = ipv4(lp)
                val host = WifiText.target(gatewayOf(lp), if (Build.VERSION.SDK_INT >= 30) lp.dhcpServerAddress?.hostAddress else null, own) ?: cur.host
                if (host != cur.host) { link = cur.copy(host = host, note = own?.let { "phone $it" }); changed() }
            }

            override fun onUnavailable() {
                connecting = null
                note("Couldn't join \"${f.name}\" (it wasn't found, the password was wrong, or the request was declined)")
                netCallback = null
                if (!answered) { answered = true; main.post { done("Couldn't join \"${f.name}\". Check the password, or use the Wi-Fi panel.") } }
                changed()
            }

            override fun onLost(network: Network) {
                if (link?.network == network) {
                    link = null
                    runCatching { c.bindProcessToNetwork(null) }
                    note("Left \"${f.name}\" (the network went away)")
                    changed()
                }
            }
        }
        netCallback = cb
        try {
            c.requestNetwork(req, cb, 30_000)
        } catch (e: Exception) {
            netCallback = null
            connecting = null
            changed()
            done("Android refused the request: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectP2p(f: Found, done: (String?) -> Unit) {
        val (m, ch) = p2pChannel() ?: return done("This phone has no Wi-Fi Direct.")
        disconnect(quiet = true)
        connecting = f.name
        changed()
        note("Asking \"${f.name}\" to connect over Wi-Fi Direct (the glasses may need to accept)")
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action != WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) return
                try {
                    m.requestConnectionInfo(ch) { info: WifiP2pInfo? ->
                        if (info?.groupFormed == true) {
                            val host = if (info.isGroupOwner) null else info.groupOwnerAddress?.hostAddress
                            val was = link
                            link = Link(f.name, true, null, host,
                                if (info.isGroupOwner) "the phone leads the group, so the glasses' address isn't known" else "glasses at $host")
                            connecting = null
                            if (was == null) note("Wi-Fi Direct group with \"${f.name}\": " + (link?.note ?: ""))
                            changed()
                        } else if (link?.p2p == true) {
                            link = null
                            note("Wi-Fi Direct group with \"${f.name}\" ended")
                            changed()
                        }
                    }
                } catch (_: SecurityException) {}
            }
        }
        p2pReceiver = rx
        AudioRoute.registerSystemReceiver(app, rx, IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION))
        val cfg = WifiP2pConfig().apply {
            deviceAddress = f.address
            wps.setup = WpsInfo.PBC
        }
        try {
            m.connect(ch, cfg, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { done(null) }
                override fun onFailure(reason: Int) {
                    connecting = null
                    note("Wi-Fi Direct connect failed (${p2pReason(reason)})")
                    changed()
                    done("Wi-Fi Direct connect failed (${p2pReason(reason)}).")
                }
            })
        } catch (e: SecurityException) {
            connecting = null
            changed()
            done("Wi-Fi Direct needs the Nearby devices permission.")
        }
        // Nothing within 30 s: give up so the screen doesn't wait forever.
        main.postDelayed({ if (connecting == f.name && link == null) { connecting = null; note("No Wi-Fi Direct group with \"${f.name}\" after 30 s"); cancelP2p() } }, 30_000)
    }

    @SuppressLint("MissingPermission")
    private fun cancelP2p() {
        p2pChannel()?.let { (m, ch) ->
            runCatching { m.cancelConnect(ch, null) }
            runCatching { m.removeGroup(ch, null) }
        }
        p2pReceiver?.let { runCatching { app.unregisterReceiver(it) } }
        p2pReceiver = null
        changed()
    }

    /** Leaves whatever the app joined and lets go of the process binding. */
    fun disconnect(quiet: Boolean = false) {
        val l = link
        val hadRequest = netCallback != null
        netCallback?.let { cb -> runCatching { cm?.unregisterNetworkCallback(cb) } }
        netCallback = null
        runCatching { cm?.bindProcessToNetwork(null) }
        if (l?.p2p == true || p2pReceiver != null) cancelP2p()
        link = null
        connecting = null
        if (!quiet && (l != null || hadRequest)) note("Disconnected" + (l?.let { " from \"${it.name}\"" } ?: ""))
        changed()
    }

    // ---------------- discovery ----------------

    /**
     * Tries [PortProbe.PORTS] on the glasses' side of the joined network, 1 s each; sends `GET /` to open
     * web ports and `OPTIONS` to open RTSP ports, and logs the first 200 characters of each answer. The
     * app's process is bound to that network only while this runs. [onLine] and [done] run on the main thread.
     */
    fun probe(onLine: (String) -> Unit, done: (String) -> Unit) {
        val l = link
        val host = l?.host
        if (l == null || host == null) { done(if (l == null) "Join the glasses' network first." else "The glasses' address isn't known on this network."); return }
        if (probing) return
        probing = true
        lastProbe = emptyList()
        changed()
        Thread {
            val out = mutableListOf<String>()
            fun say(s: String) { out += s; lastProbe = out.toList(); note(s); main.post { onLine(s) } }
            val results = mutableListOf<PortProbe.Result>()
            val bound = l.network?.let { n -> runCatching { cm?.bindProcessToNetwork(n) }.getOrNull() == true } ?: false
            try {
                say("Looking at $host on \"${l.name}\"" + if (bound) " (the app uses only this network meanwhile)" else "")
                for (port in PortProbe.PORTS) {
                    if (link?.name != l.name) { say("The connection ended; stopped."); break }
                    val r = tryPort(l.network, host, port)
                    results += r
                    say(PortProbe.line(host, r))
                }
            } finally {
                if (bound) runCatching { cm?.bindProcessToNetwork(null) }
            }
            val sum = PortProbe.summary(host, results)
            say(sum)
            lastProbe = out.toList()
            probing = false
            changed()
            main.post { done(sum) }
        }.apply { name = "wifi-probe"; isDaemon = true }.start()
    }

    private fun tryPort(network: Network?, host: String, port: Int): PortProbe.Result {
        val t0 = SystemClock.elapsedRealtime()
        val s: Socket = try { network?.socketFactory?.createSocket() ?: Socket() } catch (e: Exception) { Socket() }
        try {
            s.connect(InetSocketAddress(host, port), PortProbe.CONNECT_TIMEOUT_MS)
            val ms = SystemClock.elapsedRealtime() - t0
            val req = PortProbe.request(host, port) ?: return PortProbe.Result(port, PortProbe.State.OPEN, ms)
            s.soTimeout = PortProbe.READ_TIMEOUT_MS
            s.getOutputStream().apply { write(req); flush() }
            val buf = ByteArray(2048)
            var len = 0
            try {
                val input = s.getInputStream()
                while (len < buf.size) {
                    val n = input.read(buf, len, buf.size - len)
                    if (n < 0) break
                    len += n
                    if (len >= PortProbe.SNIPPET) break
                }
            } catch (_: SocketTimeoutException) {}
            return PortProbe.Result(port, PortProbe.State.OPEN, ms, if (len > 0) PortProbe.snippet(buf, len) else null)
        } catch (e: SocketTimeoutException) {
            return PortProbe.Result(port, PortProbe.State.TIMEOUT, SystemClock.elapsedRealtime() - t0)
        } catch (e: java.net.ConnectException) {
            return PortProbe.Result(port, PortProbe.State.CLOSED, SystemClock.elapsedRealtime() - t0)
        } catch (e: Exception) {
            return PortProbe.Result(port, PortProbe.State.ERROR, SystemClock.elapsedRealtime() - t0, note = e.javaClass.simpleName)
        } finally {
            runCatching { s.close() }
        }
    }

    companion object {
        @Volatile private var instance: WifiControl? = null

        fun get(context: Context): WifiControl =
            instance ?: synchronized(this) { instance ?: WifiControl(context).also { instance = it } }

        fun p2pReason(r: Int): String = when (r) {
            WifiP2pManager.P2P_UNSUPPORTED -> "not supported on this phone"
            WifiP2pManager.BUSY -> "Wi-Fi Direct is busy"
            WifiP2pManager.ERROR -> "error"
            else -> "reason $r"
        }
    }
}
