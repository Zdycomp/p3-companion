package com.zdycomp.p3companion

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
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
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections

/**
 * 3.8 Wi-Fi controller (the Wi-Fi screen), 3.9 network controller. Reads the phone's Wi-Fi state and
 * connection details, lists every network and Wi-Fi Direct device nearby, looks for the glasses among
 * them, and joins one when the person taps it.
 *
 * Two ways to join, never mixed up (3.9):
 *  - An ordinary network: "Join on this phone" hands Android a [WifiNetworkSuggestion] through
 *    Settings.ACTION_WIFI_ADD_NETWORKS. Android asks the person, saves the network like any other, and
 *    uses it with internet. The answer comes back as Settings.ADD_WIFI_RESULT_* codes ([addIntent],
 *    [onAddResult]).
 *  - The glasses, or a network the person marked as a device's: "For this app only" uses a
 *    [WifiNetworkSpecifier] request (local only, no internet, 30 s), confirmed in Android's own dialog.
 *    When it fails it says why: timed out, declined, not in the scan, band or security the phone lacks.
 *
 * Rules: apps can't switch Wi-Fi on or off since Android 10, so the app never tries (the switch opens
 * Android's Wi-Fi panel). A password is typed by the person, handed straight to Android's builder, and
 * never stored or logged. Other networks' names go only into this screen's log, never the shared
 * glasses log. The app's process is bound to the glasses' network only while the port probe runs.
 * Framework only. One instance per app.
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
        val bssid: String? = null,
        val txMbps: Int = 0,
        val rxMbps: Int = 0,
        val standard: String? = null,
        val dns: List<String> = emptyList(),
        val privateDns: String? = null,
        val ipv6: List<String> = emptyList(),
        val metered: Boolean = false,
    )

    /** Something found by [scan]: an access point (or several with one name) or a Wi-Fi Direct peer. */
    data class Found(
        /** The SSID, "Hidden network", or the Wi-Fi Direct device's name. */
        val name: String,
        val p2p: Boolean,
        /** The strongest BSSID, or the peer's device address. */
        val address: String,
        val rssi: Int,
        val freqMhz: Int,
        val security: WifiText.Security,
        /** The glasses token it matched, or null. */
        val match: String?,
        /** 3.9: the SSID exactly as the scan gave it (null for a hidden network or a peer). */
        val ssid: String? = null,
        val capabilities: String = "",
        /** Access points seen with this name. */
        val count: Int = 1,
        /** Wi-Fi Direct primary device type, like "10-0050F204-5". */
        val deviceType: String? = null,
        /** WifiP2pDevice.status, or -1 for a network. */
        val p2pStatus: Int = -1,
        /** [WifiMatch.score]: higher is more likely the glasses. */
        val score: Int = 0,
    ) {
        val looksLikeGlasses: Boolean get() = match != null
        val hidden: Boolean get() = !p2p && ssid == null
        /** The key used for "device network" marks and the saved list. */
        val key: String get() = if (p2p) "p2p:$address" else ssid ?: "bssid:$address"
    }

    data class Scan(val found: List<Found>, val notes: List<String>, val at: Long) {
        val networks: List<Found> get() = found.filter { !it.p2p }
        val peers: List<Found> get() = found.filter { it.p2p }
    }

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
    /** 3.9: the "Wake the glasses' Wi-Fi" attempt is running. */
    @Volatile var waking = false; private set
    /** 3.9: scans since the app started, and how many of them saw anything like the glasses. */
    @Volatile var scans = 0; private set
    @Volatile var scansWithGlasses = 0; private set
    /** 3.9: the last "Join on this phone" or "For this app only" answer, in plain words. */
    @Volatile var lastJoinResult: String? = null; private set

    /** SSIDs saved through this app in this run (Android doesn't let apps read the phone's saved list). */
    private val savedHere = Collections.synchronizedSet(LinkedHashSet<String>())
    /** [Found.key]s the person marked as a device's network ("for this app only" allowed). */
    private val deviceMarks = Collections.synchronizedSet(LinkedHashSet<String>())

    private fun granted(p: String) = app.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** Network names and the scan list need location on every Android version (an Android rule). */
    fun hasLocation(): Boolean = granted(Manifest.permission.ACCESS_FINE_LOCATION)

    /** Wi-Fi Direct needs Nearby Wi-Fi devices on Android 13+, location before that. */
    fun hasNearby(): Boolean = if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.NEARBY_WIFI_DEVICES) else hasLocation()

    fun locationOn(): Boolean = runCatching { app.getSystemService(android.location.LocationManager::class.java)?.isLocationEnabled }.getOrNull() ?: true

    // ---------------- state ----------------

    /** One sample per call at most every 1.5 s, kept for 60 s, for the signal trace. */
    private val history = ArrayDeque<Pair<Long, Int>>()

    /** The last 60 s of signal readings (oldest first), -127 where there was no connection. */
    fun signalHistory(): List<Int> = synchronized(history) { history.map { it.second } }

    private fun sample(rssi: Int) {
        val now = SystemClock.elapsedRealtime()
        synchronized(history) {
            if (history.isNotEmpty() && now - history.last().first < 1_500) return
            history.addLast(now to rssi)
            while (history.isNotEmpty() && now - history.first().first > 60_000) history.removeFirst()
        }
    }

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
        val info: WifiInfo? = runCatching { w.connectionInfo }.getOrNull()
        val connected = enabled && (net != null || (info != null && info.networkId != -1))
        val lp = net?.let { runCatching { cm?.getLinkProperties(it) }.getOrNull() }
        val rssi = if (connected) info?.rssi ?: -127 else -127
        sample(rssi)
        return Status(
            hasWifi = true,
            enabled = enabled,
            connected = connected,
            ssid = if (connected) WifiText.ssid(info?.ssid) else null,
            rssi = rssi,
            linkMbps = info?.linkSpeed ?: 0,
            freqMhz = info?.frequency ?: 0,
            address = lp?.let { ipv4(it) },
            gateway = lp?.let { gatewayOf(it) },
            internet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            bssid = info?.bssid?.takeIf { connected && it != "02:00:00:00:00:00" && it.isNotBlank() },
            txMbps = info?.txLinkSpeedMbps?.takeIf { it > 0 } ?: 0,
            rxMbps = info?.rxLinkSpeedMbps?.takeIf { it > 0 } ?: 0,
            standard = info?.let { WifiText.standard(it.wifiStandard) },
            dns = lp?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty(),
            privateDns = lp?.privateDnsServerName,
            ipv6 = lp?.linkAddresses?.filter { it.address is java.net.Inet6Address && !it.address.isLinkLocalAddress }
                ?.mapNotNull { it.address.hostAddress }.orEmpty(),
            metered = caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        )
    }

    private fun ipv4(lp: LinkProperties): String? =
        lp.linkAddresses.firstOrNull { it.address is Inet4Address }?.let { "${it.address.hostAddress}/${it.prefixLength}" }

    private fun gatewayOf(lp: LinkProperties): String? =
        lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
            ?: lp.routes.firstOrNull { it.gateway is Inet4Address && it.gateway?.isAnyLocalAddress == false }?.gateway?.hostAddress

    /** True for an SSID saved through this app, or one this app suggested to Android. */
    fun isSaved(f: Found): Boolean {
        val ssid = f.ssid ?: return false
        if (ssid in savedHere) return true
        return runCatching { wm?.networkSuggestions?.any { it.ssid?.removeSurrounding("\"") == ssid } }.getOrNull() == true
    }

    /** True when [f] is the network the phone is on now. */
    fun isConnected(f: Found, st: Status): Boolean {
        if (f.p2p || !st.connected) return false
        if (st.ssid != null && f.ssid != null) return WifiText.ssid(f.ssid) == st.ssid
        return st.bssid != null && st.bssid.equals(f.address, ignoreCase = true)
    }

    /** "For this app only" is offered only for the glasses and networks marked as a device's. */
    fun appOnlyAllowed(f: Found): Boolean = f.p2p || f.looksLikeGlasses || f.key in deviceMarks

    fun isMarkedDevice(f: Found): Boolean = f.key in deviceMarks

    fun markDevice(f: Found, on: Boolean) {
        if (on) deviceMarks += f.key else deviceMarks -= f.key
        note((if (on) "Marked \"${f.name}\" as a device's network (for this app only allowed)" else "\"${f.name}\" is an ordinary network again"), glassesLog = false)
    }

    /** Whether this phone's Wi-Fi can use [freqMhz]'s band. */
    fun bandSupported(freqMhz: Int): Boolean {
        val w = wm ?: return false
        return runCatching {
            when (WifiText.bandOf(freqMhz)) {
                WifiText.Band.GHZ_5 -> w.is5GHzBandSupported
                WifiText.Band.GHZ_6 -> w.is6GHzBandSupported
                WifiText.Band.GHZ_60 -> if (Build.VERSION.SDK_INT >= 31) w.is60GHzBandSupported else false
                else -> true
            }
        }.getOrDefault(true)
    }

    /** Whether this phone's Wi-Fi can do [sec] the way the app would ask for it. */
    fun securitySupported(sec: WifiText.Security): Boolean {
        val w = wm ?: return false
        return runCatching {
            when (WifiText.join(sec)) {
                WifiText.Join.WPA3 -> w.isWpa3SaeSupported
                WifiText.Join.OWE -> w.isEnhancedOpenSupported
                WifiText.Join.PANEL_ONLY -> false
                else -> true
            }
        }.getOrDefault(true)
    }

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
            val bssids = HashMap<String, MutableSet<String>>()
            val peers = LinkedHashMap<String, Found>()
            val w = wm
            fun collectAps() {
                if (w == null) return
                try {
                    @Suppress("DEPRECATION")
                    w.scanResults?.forEach { r ->
                        // The SSID exactly as scanned (a quoted one only loses its quotes); blank means hidden.
                        @Suppress("DEPRECATION")
                        val exact = r.SSID?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() && WifiText.ssid(it) != null }
                        val bssid = r.BSSID ?: ""
                        val name = exact ?: "Hidden network"
                        val m = exact?.let { WifiMatch.match(it, tokens) }
                        val f = Found(name, false, bssid, r.level, r.frequency, WifiText.security(r.capabilities), m,
                            ssid = exact, capabilities = r.capabilities ?: "", score = WifiMatch.score(exact, null, tokens))
                        val key = exact ?: "bssid:$bssid"
                        val seen = bssids.getOrPut(key) { LinkedHashSet() }.apply { add(bssid) }.size
                        val old = aps[key]
                        aps[key] = if (old == null || f.rssi > old.rssi) f.copy(count = seen) else old.copy(count = seen)
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
                            peers[d.deviceAddress ?: name] = Found(name, true, d.deviceAddress ?: "", -127, 0, WifiText.Security.OPEN,
                                WifiMatch.match(name, tokens), deviceType = d.primaryDeviceType, p2pStatus = d.status,
                                score = WifiMatch.score(name, d.primaryDeviceType, tokens))
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
                    // Likely glasses first (score), then networks before devices, then the strongest.
                    val list = (aps.values + peers.values).sortedWith(
                        compareByDescending<Found> { it.looksLikeGlasses }.thenByDescending { it.score }
                            .thenBy { it.p2p }.thenBy { it.hidden }.thenByDescending { it.rssi })
                    val res = Scan(list, notes.distinct(), System.currentTimeMillis())
                    lastScan = res
                    scanning = false
                    val glasses = list.filter { it.looksLikeGlasses }
                    scans++
                    if (glasses.isNotEmpty()) scansWithGlasses++
                    // Names of other networks stay in this screen's log; the glasses log gets the counts only.
                    val line = WifiText.scanSummary(aps.size, peers.size,
                        glasses.map { it.name + if (it.p2p) " (Wi-Fi Direct)" else " (network, ${it.security.label})" }, scans, scansWithGlasses)
                    note(line)
                    res.notes.forEach { note(it, glassesLog = false) }
                    done(res)
                    changed()
                }, 700)
            }, ms)
        }
    }

    // ---------------- 3.9: wake the glasses' Wi-Fi ----------------

    /** Runs [scan] as soon as no scan is running. */
    private fun scanWhenFree(tokens: List<String>, ms: Long, done: (Scan) -> Unit) {
        if (scanning) { main.postDelayed({ scanWhenFree(tokens, ms, done) }, 500); return }
        scan(tokens, ms, done)
    }

    /**
     * "Wake the glasses' Wi-Fi": scans, and meanwhile asks the glasses over Bluetooth with read-only
     * requests ([WifiWake], [XkGlasses.askWifiWake]); then scans again 5 s and 15 s after those requests
     * end. The last log line says plainly whether the glasses ever showed up. [done] runs on the main thread.
     */
    fun wake(tokens: List<String>, done: (String) -> Unit) {
        if (waking) return
        waking = true
        changed()
        note("Wake the glasses' Wi-Fi: scanning while asking the glasses over Bluetooth")
        scanWhenFree(tokens, 8_000) {}
        val afterAsk: (List<String>) -> Unit = { lines ->
            lines.forEach { note(it, glassesLog = false) }
            note("Scanning again ${WifiWake.RESCAN_AFTER_MS.joinToString(" and ") { "${it / 1000} s" }} after that")
            val last = WifiWake.RESCAN_AFTER_MS.size - 1
            WifiWake.RESCAN_AFTER_MS.forEachIndexed { i, delay ->
                main.postDelayed({
                    scanWhenFree(tokens, WifiWake.RESCAN_MS) { res ->
                        if (i != last) return@scanWhenFree
                        val out = WifiWake.outcome(res.found.filter { it.looksLikeGlasses }.map { it.name }, scans, scansWithGlasses)
                        note(out)
                        waking = false
                        changed()
                        done(out)
                    }
                }, delay)
            }
        }
        val g = XkGlasses.peek()
        if (g == null || (g.state != XkGlasses.State.READY && g.state != XkGlasses.State.BUSY)) {
            main.post { afterAsk(listOf(WifiWake.intro(WifiWake.nodes()).substringBefore(". ") + ".",
                "The glasses aren't connected over Bluetooth, so nothing was asked; the phone only looks.")) }
        } else {
            g.askWifiWake(afterAsk)
        }
    }

    // ---------------- join on this phone (ordinary networks) ----------------

    /**
     * 3.9 "Join on this phone": the Settings.ACTION_WIFI_ADD_NETWORKS intent with one
     * [WifiNetworkSuggestion] built from scan result [f] (its exact SSID, the builder its security
     * needs, see [WifiText.join]). [passphrase] is checked first ([WifiText.passphraseProblem]) and goes
     * only into the builder. Returns the intent, or what is wrong in plain words.
     */
    fun addIntent(f: Found, passphrase: CharSequence?): Pair<Intent?, String?> {
        val ssid = f.ssid ?: return null to "A hidden network has no name to save. Add it in the Wi-Fi panel with its name."
        if (f.p2p) return null to "That's a Wi-Fi Direct device, not a network."
        val join = WifiText.join(f.security)
        if (join == WifiText.Join.PANEL_ONLY) return null to "${f.security.label} networks can only be added in Android's Wi-Fi panel."
        val pw = passphrase?.toString().orEmpty()
        if (f.security.needsPassword) WifiText.passphraseProblem(pw, f.security)?.let { return null to it }
        val sug = try {
            WifiNetworkSuggestion.Builder().setSsid(ssid).apply {
                when (join) {
                    WifiText.Join.OWE -> setIsEnhancedOpen(true)
                    WifiText.Join.WPA2 -> setWpa2Passphrase(pw)
                    WifiText.Join.WPA3 -> setWpa3Passphrase(pw)
                    else -> {}
                }
            }.build()
        } catch (e: IllegalArgumentException) {
            return null to "Android didn't accept that network's settings (${e.message ?: "check the password"})."
        }
        val i = Intent(Settings.ACTION_WIFI_ADD_NETWORKS)
            .putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(sug))
        note("Asking Android to save \"$ssid\" on this phone (${f.security.label}" +
            (if (f.security.needsPassword) ", with the password you typed" else "") + ")", glassesLog = false)
        return i to null
    }

    /** The Add networks dialog's answer: [ok] is RESULT_OK, [data] its result intent. */
    fun onAddResult(ssid: String, ok: Boolean, data: Intent?): String {
        val codes = runCatching { data?.getIntegerArrayListExtra(Settings.EXTRA_WIFI_NETWORK_RESULT_LIST)?.toList() }.getOrNull()
        val text = WifiText.addSummary(ok, codes, ssid)
        val c = codes?.firstOrNull()
        if (c == WifiText.ADD_SUCCESS || c == WifiText.ADD_ALREADY_EXISTS || (c == null && ok)) savedHere += ssid
        lastJoinResult = text
        note(text + (if (codes != null) " (result codes ${codes.joinToString(",")})" else ""), glassesLog = false)
        return text
    }

    // ---------------- for this app only (the glasses, device networks) ----------------

    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var p2pReceiver: BroadcastReceiver? = null
    private var lifecycle: Application.ActivityLifecycleCallbacks? = null
    @Volatile private var dialogShown = false
    @Volatile private var dialogClosed = false

    /** Watches for Android's dialog: the app's screen pauses while it is up and resumes when it closes. */
    private fun watchDialog() {
        unwatchDialog()
        dialogShown = false
        dialogClosed = false
        val cb = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPaused(activity: Activity) { if (connecting != null) dialogShown = true }
            override fun onActivityResumed(activity: Activity) { if (connecting != null && dialogShown) dialogClosed = true }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        lifecycle = cb
        (app as? Application)?.registerActivityLifecycleCallbacks(cb)
    }

    private fun unwatchDialog() {
        lifecycle?.let { (app as? Application)?.unregisterActivityLifecycleCallbacks(it) }
        lifecycle = null
    }

    private fun inLatestScan(f: Found): Boolean =
        lastScan?.networks?.any { (f.ssid != null && it.ssid == f.ssid) || (f.ssid == null && it.address.equals(f.address, true)) } == true

    private fun facts(f: Found, elapsed: Long) = WifiText.JoinFacts(
        ssid = f.name, elapsedMs = elapsed, timeoutMs = JOIN_TIMEOUT_MS.toLong(),
        dialogShown = dialogShown, dialogClosed = dialogClosed, inLatestScan = inLatestScan(f),
        bandSupported = bandSupported(f.freqMhz), securitySupported = securitySupported(f.security),
        band = WifiText.band(f.freqMhz), security = f.security.label,
    )

    /**
     * Joins [f] for this app only (Android shows its own confirmation): the glasses' network, or one the
     * person marked as a device's ([appOnlyAllowed]). Uses the scan's exact SSID and BSSID, the builder
     * its security needs and a 30 s limit. [passphrase] is the person's typed password, or null for an
     * open network; it goes into the request and nowhere else. A Wi-Fi Direct device goes to [connectP2p].
     */
    fun connect(f: Found, passphrase: CharSequence?, done: (String?) -> Unit) {
        if (f.p2p) { connectP2p(f, done); return }
        val c = cm ?: return done("This phone has no Wi-Fi.")
        val ssid = f.ssid ?: return done("A hidden network can't be joined from the app. Use the Wi-Fi panel.")
        if (!appOnlyAllowed(f)) return done("\"${f.name}\" is an ordinary network. Use Join on this phone, or mark it as a device's network first.")
        val join = WifiText.join(f.security)
        if (join == WifiText.Join.PANEL_ONLY) return done("${f.security.label} networks can't be joined from an app. Use the Wi-Fi panel.")
        if (f.security.needsPassword) WifiText.passphraseProblem(passphrase ?: "", f.security)?.let { return done(it) }
        if (!bandSupported(f.freqMhz) || !securitySupported(f.security)) {
            val why = WifiText.joinFailure(facts(f, 0))
            lastJoinResult = why
            note(why, glassesLog = f.looksLikeGlasses)
            return done(why)
        }
        disconnect(quiet = true)
        val spec = try {
            WifiNetworkSpecifier.Builder().setSsid(ssid).apply {
                if (f.address.isNotBlank() && f.address != "02:00:00:00:00:00") runCatching { setBssid(android.net.MacAddress.fromString(f.address)) }
                val p = passphrase?.toString().orEmpty()
                when (join) {
                    WifiText.Join.WPA3 -> setWpa3Passphrase(p)
                    WifiText.Join.WPA2 -> setWpa2Passphrase(p)
                    WifiText.Join.OWE -> setIsEnhancedOpen(true)
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
        lastJoinResult = null
        changed()
        val gl = f.looksLikeGlasses
        note("Asking Android to join \"${f.name}\" for this app only (${f.security.label}, ${WifiText.band(f.freqMhz)}" +
            (if (passphrase != null) ", with the password you typed" else "") + "; up to ${JOIN_TIMEOUT_MS / 1000} s)", glassesLog = gl)
        val t0 = SystemClock.elapsedRealtime()
        watchDialog()
        var answered = false
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                unwatchDialog()
                val lp = runCatching { c.getLinkProperties(network) }.getOrNull()
                val own = lp?.let { ipv4(it) }
                val host = WifiText.target(lp?.let { gatewayOf(it) }, lp?.dhcpServerAddress?.hostAddress, own)
                link = Link(f.name, false, network, host, own?.let { "phone $it" })
                connecting = null
                lastJoinResult = "Joined \"${f.name}\" for this app only."
                note("Joined \"${f.name}\" (local only). Phone ${own ?: "?"}, the other side ${host ?: "unknown"}", glassesLog = gl)
                if (!answered) { answered = true; main.post { done(null) } }
                changed()
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                val cur = link ?: return
                if (cur.network != network) return
                val own = ipv4(lp)
                val host = WifiText.target(gatewayOf(lp), lp.dhcpServerAddress?.hostAddress, own) ?: cur.host
                if (host != cur.host) { link = cur.copy(host = host, note = own?.let { "phone $it" }); changed() }
            }

            override fun onUnavailable() {
                val why = WifiText.joinFailure(facts(f, SystemClock.elapsedRealtime() - t0))
                unwatchDialog()
                connecting = null
                lastJoinResult = why
                note(why, glassesLog = gl)
                netCallback = null
                if (!answered) { answered = true; main.post { done(why) } }
                changed()
            }

            override fun onLost(network: Network) {
                if (link?.network == network) {
                    link = null
                    runCatching { c.bindProcessToNetwork(null) }
                    note("Left \"${f.name}\" (the network went away)", glassesLog = gl)
                    changed()
                }
            }
        }
        netCallback = cb
        try {
            c.requestNetwork(req, cb, JOIN_TIMEOUT_MS)
        } catch (e: Exception) {
            unwatchDialog()
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
        unwatchDialog()
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

        /** How long a "for this app only" request waits for Android. */
        const val JOIN_TIMEOUT_MS = 30_000

        fun p2pReason(r: Int): String = when (r) {
            WifiP2pManager.P2P_UNSUPPORTED -> "not supported on this phone"
            WifiP2pManager.BUSY -> "Wi-Fi Direct is busy"
            WifiP2pManager.ERROR -> "error"
            else -> "reason $r"
        }
    }
}
