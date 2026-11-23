package com.zdycomp.p3companion

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 3.3 live view test, Wi-Fi side: looks for the glasses as a Wi-Fi access point (scan results) or a
 * Wi-Fi Direct peer while their preview is open. It never connects; it only reports names that look
 * like the glasses, so the next round knows whether video might go over Wi-Fi. Other networks are
 * counted, never named. Framework only.
 */
class WifiFinder(context: Context) {

    data class Result(val matches: List<String>, val lines: List<String>)

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val aps = Collections.synchronizedSet(LinkedHashSet<String>())
    private val peers = Collections.synchronizedSet(LinkedHashSet<String>())
    private val notes = Collections.synchronizedList(mutableListOf<String>())
    @Volatile private var tokens: List<String> = emptyList()
    private var receiver: BroadcastReceiver? = null
    private var p2p: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null

    /** Starts the scan and the Wi-Fi Direct search; any thread. */
    fun start(tokens: List<String>) {
        this.tokens = tokens
        main.post { runCatching { begin() }.onFailure { notes += "couldn't start (${it.javaClass.simpleName})" } }
    }

    @SuppressLint("MissingPermission")
    private fun begin() {
        val wm = app.getSystemService(WifiManager::class.java)
        if (wm == null) { notes += "this phone has no Wi-Fi"; return }
        if (!wm.isWifiEnabled) notes += "Wi-Fi is off on the phone, so nothing could be found"
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    WifiManager.SCAN_RESULTS_AVAILABLE_ACTION -> collectAps(wm)
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                }
            }
        }
        receiver = rx
        AudioRoute.registerSystemReceiver(app, rx, IntentFilter().apply {
            addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        })
        @Suppress("DEPRECATION")
        val started = runCatching { wm.startScan() }.getOrDefault(false)
        if (!started) notes += "the phone didn't start a new Wi-Fi scan (Android limits how often apps may scan); using its last list"
        val m = app.getSystemService(WifiP2pManager::class.java)
        if (m == null) { notes += "this phone has no Wi-Fi Direct"; return }
        p2p = m
        val ch = m.initialize(app, Looper.getMainLooper(), null)
        channel = ch
        try {
            m.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}
                override fun onFailure(reason: Int) { notes += "Wi-Fi Direct search didn't start (reason $reason)" }
            })
        } catch (e: SecurityException) {
            notes += "Wi-Fi Direct search needs the Nearby devices permission"
        }
    }

    @SuppressLint("MissingPermission")
    private fun collectAps(wm: WifiManager) {
        try {
            @Suppress("DEPRECATION")
            wm.scanResults?.forEach { r -> r.SSID?.trim('"')?.takeIf { it.isNotBlank() }?.let { aps += it } }
        } catch (e: SecurityException) {
            if (notes.none { it.startsWith("the access point list") }) {
                notes += "the access point list needs the location permission, which this app doesn't ask for"
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestPeers() {
        val m = p2p ?: return
        val ch = channel ?: return
        try {
            m.requestPeers(ch) { list -> list?.deviceList?.forEach { d -> d.deviceName?.takeIf { it.isNotBlank() }?.let { peers += it } } }
        } catch (_: SecurityException) {
        }
    }

    /** Stops searching and reports; call off the main thread. Waits at most [waitMs]. */
    fun finish(waitMs: Long): Result {
        val latch = CountDownLatch(1)
        main.post {
            runCatching {
                app.getSystemService(WifiManager::class.java)?.let { collectAps(it) }
                requestPeers()
            }
            // requestPeers answers asynchronously: give it a moment before closing.
            main.postDelayed({ runCatching { end() }; latch.countDown() }, 600)
        }
        latch.await(waitMs, TimeUnit.MILLISECONDS)
        val t = tokens
        val apMatch = synchronized(aps) { aps.filter { matches(it, t) } }
        val peerMatch = synchronized(peers) { peers.filter { matches(it, t) } }
        val found = apMatch.map { "$it (access point)" } + peerMatch.map { "$it (Wi-Fi Direct)" }
        val lines = mutableListOf<String>()
        lines += "${aps.size} access point(s) and ${peers.size} Wi-Fi Direct device(s) seen; " +
            if (found.isEmpty()) "none looks like the glasses" else "looks like the glasses: ${found.joinToString(", ")}"
        lines += synchronized(notes) { notes.toList() }
        return Result(found, lines)
    }

    private fun end() {
        receiver?.let { runCatching { app.unregisterReceiver(it) } }
        receiver = null
        val m = p2p
        val ch = channel
        if (m != null && ch != null) {
            runCatching { m.stopPeerDiscovery(ch, null) }
            if (Build.VERSION.SDK_INT >= 27) runCatching { ch.close() }
        }
        channel = null
    }

    companion object {
        /** Words the glasses' Wi-Fi name might contain, lower case. */
        fun tokens(name: String?, model: String?, address: String?): List<String> {
            val out = LinkedHashSet<String>()
            name?.trim()?.lowercase(Locale.US)?.takeIf { it.length >= 3 }?.let { out += it }
            model?.trim()?.lowercase(Locale.US)?.takeIf { it.length >= 3 }?.let { out += it; out += it.substringBefore('-') }
            address?.replace(":", "")?.takeIf { it.length >= 4 }?.let { out += it.takeLast(4).lowercase(Locale.US) }
            out += listOf("p3", "e13c", "xk", "shenju", "novasight")
            return out.filter { it.length >= 2 }
        }

        /** True when [ssid] contains one of [tokens], ignoring case. */
        fun matches(ssid: String, tokens: List<String>): Boolean {
            val s = ssid.lowercase(Locale.US)
            return tokens.any { it.isNotEmpty() && s.contains(it) }
        }
    }
}
