# 03 — Link supervision, auto-reconnect, background operation

Specialist 03 of 10. Scope: detecting a dropped link, reconnecting (backoff, wake from sleep, back in range), keeping the
SPP link alive with the app in the background on Android 14/15 (Samsung), and saving button photos in the background.

## 1. Findings (sources)

| # | Finding | Source | Conf. |
|---|---|---|---|
| F1 | No protocol keepalive is needed: the link sat idle for 240 s with zero frames, then answered `1001` and `57B0`. The glasses send nothing while idle. Multi-day idle and the glasses' own auto-power-off were not tested. | xk-one-pro-sdk `docs/PROTOCOL.md:289-310`, `docs/VALIDATION.md:366-384` | High |
| F2 | The vendor "keepalive" module is Android process persistence (autostart helpers, wake locks, alarms), not link traffic. So the work is all on the Android side. | `PROTOCOL.md:306-308` | High |
| F3 | The button only works during an active SPP session (otherwise the glasses say "please connect to the app"). If the host does not ACK `7320` and download, **the glasses stay blocked** until the transfer is finished or aborted with `7500`. | `docs/NODES.md:87-100`, `docs/HARDWARE.md:54-58` | High |
| F4 | 1 click = photo through the same path as `57B0` (`57B1` → `7320` → `7300`×N → `7500`). The button photo is pushed over SPP. A MediaSession is not needed for photos. | `NODES.md:89-92`, `PROTOCOL.md:140-160` | High |
| F5 | The reference sample says the button only captures "while the app FGS session is active". Its integration notes use a foreground service of type `connectedDevice` (or `dataSync`) for background buttons. | `python/examples/button_flow.py:11`, `docs/ANDROID_INTEGRATION.md:28-35` | Med |
| F6 | The reference clients detect loss the same way: `read()` returning `-1`/empty, or an exception, then tear down. Neither has a working reconnect (Python `auto_reconnect` flag is stored but not used). | `python/xkglasses/client.py:51-53,89-108`; flutter `XkSppClient.kt:81-112` | High |
| F7 | The rebind on reconnect uses `bindType 2 = CONNECT_BACK`, which is the "reconnect to a previously bound user" mode. So a reconnect is just open the socket, then `0001`/`0002`/`102E` again. | `PROTOCOL.md:83-86`, `BONDED_ENROLLMENT.md:69` | High |
| F8 | Android facts (framework): on Android 14+, cached apps are frozen a few seconds after leaving the screen, so a reader thread in a non-foreground process stops. A FGS of type `connectedDevice` needs `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` in the manifest and a granted `BLUETOOTH_CONNECT` when `startForeground` runs (otherwise `SecurityException`). Unlike `dataSync`, it has no 6 h limit on Android 15. `Handler.postDelayed` counts uptime, so it **does not advance while the CPU is asleep**. Doze ignores wake locks unless the process is at FGS level or exempt. `ACTION_ACL_CONNECTED`/`DISCONNECTED` are exempt from the implicit-broadcast ban, and a Bluetooth broadcast that requires BLUETOOTH_CONNECT is an exemption from the FGS background-start ban. | Android platform behavior | Med-High |
| F9 | BR/EDR link supervision timeout on Android defaults to about 20 s, so going out of range shows up as a read `IOException` up to roughly 20 s later. A power-off or RFCOMM close by the glasses shows up almost at once (`-1`/IOException). | Android BT stack default | Med |

## 2. Defects in current code

| # | Where | Defect | Impact | Conf. |
|---|---|---|---|---|
| D1 | `AndroidManifest.xml:4-39` | No service, no `FOREGROUND_SERVICE*`, `POST_NOTIFICATIONS`, `WAKE_LOCK`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. The link lives only in the activity's process. | When the app is in the background, the process is frozen or killed (Samsung is aggressive). The `7320` ACK is never sent, **button photos are lost and the glasses block** (F3). | High |
| D2 | `XkGlasses.kt:381-383` | Only **one** reconnect try, 4 s after the drop, using `main.postDelayed`. If that try fails (glasses still out of range or asleep), `connect()`'s catch (`:113-119`) goes to IDLE with no further retry. Uptime-based delay also stalls when the screen is off (F8). | Glasses that come back after 5 s+ never reconnect. | High |
| D3 | `XkGlasses.kt:374` | The reader checks the **global** `socket != null`, not its own socket. An old reader that exits after a new connect will `closeSocket()` the **new** link. | A random drop right after a reconnect. | High |
| D4 | `XkGlasses.kt:146-148,179-194` + `GlassesScreen.kt:82` | `socket` is set only after `connect()` succeeds, so **Cancel during CONNECTING does nothing**. The io thread then binds and sets READY after the user cancelled. | Cancel is ignored, and a ghost connection follows. | High |
| D5 | `XkGlasses.kt:92-93` | `connect()` only refuses when CONNECTING. If READY/BUSY, it opens a second socket and leaks the first. | Double links, confused parser. | Med |
| D6 | `XkGlasses.kt:355,359,292` | On link loss, waiters sleep their full timeout (up to 20 s / 15 s) before failing. | Slow failure. The next action stays queued on the single `io` thread. | High |
| D7 | `XkGlasses.kt:281-299` | No wake lock during a photo download. With the screen off, the CPU can sleep between `7300` parts, so a part times out. | Lost background photos, glasses left waiting. | Med |
| D8 | `MainActivity.kt:82-84` | The only auto-connect trigger is activity `onCreate`. Nothing reacts to Bluetooth turning on, the glasses coming back (ACL), or the phone being unlocked. | Has to be fixed by hand. | High |
| D9 | `XkGlasses.kt:374-384` | Nothing listens for `ACTION_ACL_DISCONNECTED` or `BluetoothAdapter` STATE_TURNING_OFF. Loss shows up only through the read. | Up to ~20 s late (F9). | Med |

## 3. Recommended changes (framework APIs only)

### 3.1 Manifest (`app/src/main/AndroidManifest.xml`)
```diff
     <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
+    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
+    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
+    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
+    <uses-permission android:name="android.permission.WAKE_LOCK" />
+    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
 ...
         </activity>
+        <service android:name=".GlassesService" android:exported="false"
+            android:foregroundServiceType="connectedDevice" />
+        <!-- Restarts the link after the process was killed, when the glasses' audio link comes back.
+             ACL_CONNECTED is a protected broadcast that needs BLUETOOTH_CONNECT, so exported is safe. -->
+        <receiver android:name=".GlassesWakeReceiver" android:exported="true">
+            <intent-filter><action android:name="android.bluetooth.device.action.ACL_CONNECTED" /></intent-filter>
+        </receiver>
     </application>
```
(`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is fine for a sideloaded app. Play restricts it, but a companion device is an accepted use case.)

### 3.2 New `GlassesService.kt`
```kotlin
package com.zdycomp.p3companion

import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Holds the glasses link while the app is in the background, so button photos are still saved. */
class GlassesService : Service(), XkGlasses.Listener {
    private lateinit var g: XkGlasses
    private lateinit var prefs: Prefs

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> if (ours(i)) g.onGlassesSeen("glasses in range")
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> if (ours(i)) g.linkDownHint()
                BluetoothAdapter.ACTION_STATE_CHANGED -> when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_ON -> g.onGlassesSeen("Bluetooth on")
                    BluetoothAdapter.STATE_TURNING_OFF -> g.linkDownHint()
                }
                Intent.ACTION_USER_PRESENT -> g.onGlassesSeen("phone unlocked")
            }
        }
    }

    private fun ours(i: Intent): Boolean {
        val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
            i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        return d != null && d.address.equals(prefs.glassesAddress, ignoreCase = true)
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this); g = XkGlasses.get(this, prefs)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH, "Glasses connection", NotificationManager.IMPORTANCE_LOW))
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED); addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED); addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rx, f, RECEIVER_NOT_EXPORTED) else registerReceiver(rx, f)
        g.addListener(this)
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { g.disconnect(); stopSelf(); return START_NOT_STICKY }
        if (!goForeground()) { stopSelf(); return START_NOT_STICKY }
        g.reconnectSaved()                       // no-op unless IDLE
        return START_STICKY
    }

    private fun goForeground(): Boolean = try {
        startForeground(NOTE_ID, note(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE); true
    } catch (e: Exception) {                     // BLUETOOTH_CONNECT revoked, or background start refused
        g.addLog("Background link not allowed: ${e.javaClass.simpleName}"); false
    }

    private fun note(): Notification {
        val text = when (g.state) {
            XkGlasses.State.READY -> "Connected" + (g.battery?.let { " · $it%" } ?: "")
            XkGlasses.State.BUSY -> g.busyText ?: "Working…"
            XkGlasses.State.CONNECTING -> "Connecting…"
            XkGlasses.State.IDLE -> if (g.wantsLink) "Waiting for the glasses" else "Not connected"
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, GlassesService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH).setSmallIcon(R.drawable.ic_glasses)
            .setContentTitle(g.deviceName ?: prefs.glassesName ?: "Glasses").setContentText(text)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Disconnect", stop).build())
            .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
            .build()
    }

    override fun onGlassesChanged() {
        if (!g.wantsLink) { stopSelf(); return }
        getSystemService(NotificationManager::class.java).notify(NOTE_ID, note())
    }

    override fun onDestroy() {
        running = false; g.removeListener(this); runCatching { unregisterReceiver(rx) }; super.onDestroy()
    }
    override fun onBind(i: Intent?): IBinder? = null

    companion object {
        private const val CH = "glasses_link"; private const val NOTE_ID = 7
        const val ACTION_STOP = "com.zdycomp.p3companion.STOP_GLASSES"
        @Volatile var running = false; private set
        fun start(c: Context) {
            if (running) return
            runCatching { c.applicationContext.startForegroundService(Intent(c, GlassesService::class.java)) }
        }
    }
}

/** Brings the link back after the process died, when the glasses' audio link reconnects. */
class GlassesWakeReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val p = Prefs(c)
        if (!p.autoReconnect || p.userKey == null || p.glassesAddress == null || p.userStopped) return
        GlassesService.start(c)                   // allowed: BT broadcast exemption (F8)
    }
}
```
`Prefs.userStopped` is a new boolean. Set it true in `disconnect()` and false in `connect()`, so a manual Disconnect survives a process restart.

### 3.3 `XkGlasses.kt` — reconnect, socket identity, cancel, wake locks
```kotlin
// new fields
private val pm = app.getSystemService(android.os.PowerManager::class.java)
private val retryLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "p3c:reconnect").apply { setReferenceCounted(false) }
private val xferLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "p3c:transfer").apply { setReferenceCounted(false) }
@Volatile private var opening: BluetoothSocket? = null
private var attempt = 0
private var retryStart = 0L
private val retry = Runnable { reconnectSaved() }

val wantsLink: Boolean get() = !userDisconnect && prefs.glassesAddress != null && prefs.userKey != null &&
    (state != State.IDLE || prefs.autoReconnect)

/** Fast retries for ~3 min after a drop (kept awake), then wait for a wake event (ACL, BT on, unlock). */
private fun scheduleReconnect(why: String) {
    if (!wantsLink || state != State.IDLE) return
    main.removeCallbacks(retry)
    val now = android.os.SystemClock.elapsedRealtime()
    if (attempt == 0) { retryStart = now; retryLock.acquire(ACTIVE_RETRY_MS) }
    if (now - retryStart > ACTIVE_RETRY_MS) { attempt = 0; retryLock.release(); addLog("Waiting for the glasses ($why)"); changed(); return }
    val d = BACKOFF_MS[minOf(attempt++, BACKOFF_MS.lastIndex)]
    addLog("Retry in ${d / 1000}s ($why)"); changed()
    main.postDelayed(retry, d)
}

/** Called on ACL_CONNECTED for our glasses, Bluetooth on, or phone unlock. */
fun onGlassesSeen(why: String) {
    if (!wantsLink || state != State.IDLE) return
    attempt = 0; main.removeCallbacks(retry)
    addLog("Trying again: $why")
    main.postDelayed(retry, 1_500)           // let HFP/A2DP finish first
}

/** ACL went down: close the current socket so the reader's lost-link path runs now. */
fun linkDownHint() { socket?.let { runCatching { it.close() } } }

companion object {   // add
    private val BACKOFF_MS = longArrayOf(2_000, 5_000, 10_000, 20_000, 40_000, 60_000)
    private const val ACTIVE_RETRY_MS = 3 * 60_000L
}
```
Edits to existing code:
```diff
@@ fun connect  (:92)
-        if (state == State.CONNECTING) return
+        if (state != State.IDLE) return
@@ after `changed()` (:104)
+        prefs.userStopped = false
+        GlassesService.start(app)
@@ success path (:109-112)
                 state = State.READY
+                attempt = 0; main.removeCallbacks(retry); retryLock.release()
@@ catch (:113-119)
                 changed()
+                if (!userDisconnect) scheduleReconnect("connect failed")
@@ openSocket loop (:146-150)
                 val s = make()
-                s.connect()
+                opening = s
+                try { s.connect() } finally { opening = null }
+                if (userDisconnect) { runCatching { s.close() }; error("Cancelled.") }
                 socket = s
                 out = s.outputStream
-                startReader(s.inputStream)
+                startReader(s)
@@ fun disconnect (:179-181)
         userDisconnect = true
+        prefs.userStopped = true
+        main.removeCallbacks(retry); retryLock.release(); attempt = 0
+        opening?.let { runCatching { it.close() } }        // aborts a blocking connect()
         closeSocket()
@@ startReader (:361-385)
-    private fun startReader(input: InputStream) {
+    private fun startReader(s: BluetoothSocket) {
+        val input = s.inputStream
         reader = Thread({
 ...
-            if (socket != null) {
+            val mine = synchronized(this) { socket === s }
+            if (!mine) { runCatching { s.close() }; return@Thread }
+            run {
                 closeSocket()
 ...
-                if (!userDisconnect && prefs.autoReconnect) {
-                    main.postDelayed({ reconnectSaved() }, 4_000)
-                }
+                if (!userDisconnect) main.post { scheduleReconnect("link lost") }
             }
@@ request/await/download (:355, :359, :292): replace bare poll with a link-aware poll
+    private fun <T> pollLinked(q: LinkedBlockingQueue<T>, ms: Long): T? {
+        val end = android.os.SystemClock.elapsedRealtime() + ms
+        while (true) {
+            val left = end - android.os.SystemClock.elapsedRealtime()
+            if (left <= 0) return null
+            q.poll(minOf(left, 250L), TimeUnit.MILLISECONDS)?.let { return it }
+            if (socket == null) throw java.io.IOException("Lost the connection to the glasses.")
+        }
+    }
-        return q.poll(timeoutMs, TimeUnit.MILLISECONDS)
+        return pollLinked(q, timeoutMs)
-                mediaDone.poll(15, TimeUnit.SECONDS) ?: error("Part $i never arrived.")
+                pollLinked(mediaDone, 15_000) ?: error("Part $i never arrived.")
@@ download (:281-298)
     private fun download(count: Int): Uri {
+        xferLock.acquire(120_000)               // CPU stays up between parts with the screen off
         val asm = Xk.PhotoAssembler(count)
 ...
         } finally {
             assembler = null
+            xferLock.release()
         }
```
Also, when a download fails while the link is **still up**, send `7500` from the catch blocks (`:242`, `:411`) so the glasses are not left blocked (F3): `runCatching { send(Xk.control(nextOrder(), "7500", Xk.ACTION_EXECUTE, nextRequestId())) }`.

### 3.4 `MainActivity.kt`
- `bluetoothPermissions()` (`:310`): on SDK 33+, also request `POST_NOTIFICATIONS`. Keep `hasBluetoothPermission()` checking only `BLUETOOTH_CONNECT`. The FGS runs without notification permission, but its status would then be hidden.
- `:82-84`: replace with `if (prefs.autoReconnect && prefs.glassesAddress != null && hasBluetoothPermission() && !prefs.userStopped) GlassesService.start(this)`. The activity is in the foreground here, so the start is always allowed.
- After the first successful connect, ask once for the battery exemption:
```kotlin
fun askBatteryExemption() {
    val pm = getSystemService(android.os.PowerManager::class.java)
    if (pm.isIgnoringBatteryOptimizations(packageName)) return
    runCatching { startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:$packageName"))) }
        .onFailure { runCatching { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
}
```
- `GlassesScreen.kt:105`: the hint text should say "even with the app closed, while the glasses notification shows".

### 3.5 What not to do
- **No periodic keepalive or polling** (F1). It costs radio time and battery and fixes nothing. Do not add an `AlarmManager` loop that pages absent glasses forever: each failed `openSocket` makes 3 page attempts of about 5 s each and disturbs A2DP. After the 3-minute burst, wait for ACL, Bluetooth on, or unlock.
- Do not use FGS type `dataSync` (6 h cap on Android 15, and `BOOT_COMPLETED` cannot start it).
- No MediaSession is needed for photos (F4). It is only relevant to the voice/AVRCP button (another specialist).

## 4. On-device checks (phone only, Samsung)

First: Settings > Apps > P3 Companion > Battery > **Unrestricted**. Then Settings > Battery > Background usage limits: make sure P3 Companion is **not** in Sleeping/Deep sleeping apps, and add it to **Never sleeping apps**. Allow notifications.

1. **Background button photo.** Connect, press Home, lock the phone, wait 2 min, press the glasses button once. Expected: the photo shows up in the album and the notification briefly reads "Saving…". Repeat after the phone has been still with the screen off for 60+ min (Doze). Pass = both saved. Glasses log: `← 7320`, then `Saved photo`.
2. **App swiped away.** Swipe P3 Companion out of Recents and repeat check 1. Expected: the notification stays and the photo is saved.
3. **Out of range.** While connected, take the glasses ~30 m away (or put them in a closed cookie tin). Expected: notification "Waiting/Retry" within about 20 s. Bring them back. Expected: "Connected" within about 15 s and no app tap needed.
4. **Glasses asleep/off.** Hold the power button 3 s to turn them off, wait 1 min, turn them on. Expected: reconnect within ~10 s of the glasses' start-up prompt. If it only reconnects after you unlock the phone, the glasses did not bring their audio link back (no ACL event). Report that.
5. **Bluetooth toggle.** Turn Bluetooth off, then on, from the quick panel. Expected: reconnect within ~10 s.
6. **Cancel.** Tap Connect, then Cancel at once. Expected: it stays disconnected (it does not become Connected a few seconds later).
7. **Long idle.** Leave it connected and untouched for 2 h, then press the button. Expected: photo saved. This extends the 240 s test in F1.
8. **Battery cost.** After a day, Settings > Battery > usage: P3 Companion should be about 1-2% or less while idle and connected.
9. **Disconnect from the notification.** Expected: the notification disappears and nothing reconnects until you tap Connect.

Send the glasses log (share button) after any failure.

## 5. Confidence summary
- D1/3.1/3.2 (FGS connectedDevice is required for background button photos): **High**.
- D2/D3/D4/D6 fixes: **High**. D5, D7, D9: **Medium-High**.
- ACL_CONNECTED firing when the glasses wake or come back (depends on the glasses reconnecting HFP/A2DP by themselves): **Medium**, checked by test 4. If it fails, the unlock and Bluetooth-on triggers still recover the link.
- FGS start from the manifest receiver (BT broadcast exemption) and the START_STICKY restart on Android 15: **Medium**. Both are wrapped in try/catch, so the worst case is no auto-restart after a process kill until the app is opened.
- 3-minute retry window and 1.5 s post-ACL delay: **Medium**. Tune from logs.
