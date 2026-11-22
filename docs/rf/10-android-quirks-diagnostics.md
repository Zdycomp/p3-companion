# 10 — Android stack quirks, permissions, Diagnostics harness, KeyFinder

Scope: P3 Companion, minSdk 30 / targetSdk 35, Samsung phone. Note: the reference LensFix tests ran on a
Samsung S23 Ultra at **API 37** (lensmoo-crash-fix docs/VALIDATION.md:3), so the owner's phone may be newer
than our target; everything below is written to hold on API 30-37.
Confidence: H = high, M = medium, L = low.

## 1. Findings (with sources)

| # | Finding | Source | Conf |
|---|---|---|---|
| F1 | Manifest declares only `BLUETOOTH_CONNECT` (+ legacy BLUETOOTH/ADMIN maxSdk 30, correct). No SCAN, RECORD_AUDIO, POST_NOTIFICATIONS, FOREGROUND_SERVICE*, no service. | AndroidManifest.xml:5-7 | H |
| F2 | Share filter accepts only `ACTION_SEND`. Android's Shell bug-report share (BugreportProgressService) sends `ACTION_SEND_MULTIPLE` (bug report zip + optional screenshot, type `application/vnd.android.bugreport`), so **P3 Companion does not appear in the share sheet** of the bug-report notification — step 5 of the setup text cannot work as written. | AndroidManifest.xml:30-37, MainActivity.kt:94-107, GlassesScreen.kt:177 | M-H |
| F3 | `cancelDiscovery()` needs `BLUETOOTH_SCAN` on API 31+; it throws SecurityException, swallowed by runCatching, so a running discovery (Samsung Bluetooth settings scans the whole time it is open) is never stopped and RFCOMM page/connect slows or fails. | XkGlasses.kt:132 | H |
| F4 | Failed sockets are never closed: `s.connect()` throws, `s` is dropped. On Samsung/Broadcom stacks a leaked RFCOMM socket keeps the DLC busy; the next attempt fails with "read failed, socket might closed or timeout, read ret: -1". | XkGlasses.kt:144-157 | H |
| F5 | Three sequential connect attempts with no timing; each failure can cost one page timeout (~5 s) plus SDP. Worst case 15-30 s before the error. The mode that worked is not remembered. Vendor order (secure, insecure, ch 8) is the same. | XkGlasses.kt:135-157; xk-one-pro-sdk docs/BONDED_ENROLLMENT.md:171 | M |
| F6 | Link lives in a process singleton with no foreground service. When the app is backgrounded Samsung's "Put unused apps to sleep" / process freezer kills it, so button photos (7320, XkGlasses.kt:403) and auto-reconnect (:381-383) stop. LensMoo replacement needs the vendor pattern: FGS type connectedDevice. | XkGlasses.kt:361-386; xk-one-pro-sdk docs/ANDROID_INTEGRATION.md:32 | H |
| F7 | Permission denied twice = permanently denied on API 30+. `withBluetooth` keeps calling `requestPermissions`, which returns DENIED instantly with no dialog: the "Allow Bluetooth" button does nothing visible. | MainActivity.kt:316-343, GlassesScreen.kt:122-125 | H |
| F8 | Edge-to-edge: `statusBarColor`/`navigationBarColor` are ignored on API 35+ (deprecated). On API 30-34 the decor still fits system windows, so behavior differs between versions; `displayCutout` is not included (Samsung punch-hole in landscape). | MainActivity.kt:48-49, 71-77 | M |
| F9 | Mic path (HFP/SCO, RFCOMM ch 1) and A2DP exist on the glasses; API 31+ uses `AudioManager.setCommunicationDevice` (startBluetoothSco deprecated). Needs RECORD_AUDIO (runtime) + MODIFY_AUDIO_SETTINGS (normal). Mic FGS must start while the app is visible. | xk-one-pro-sdk docs/HARDWARE.md:36, ANDROID_INTEGRATION.md:41-50 | H |
| F10 | Live preview is H.264 320x240 over Bluetooth, ~30 fps observed (295 frames/10 s; 427 frames/14.1 s), toggled by 5720, state 5710. Diagnostics should measure fps once preview exists. | lensmoo-crash-fix docs/VIDEO-RESEARCH.md:27, VALIDATION.md (1.1.0 section), AUDIO-RESEARCH.md:198-202 | M |
| F11 | No SPP keepalive needed (idle 240 s OK). So a link drop is RF/power/process, never protocol idle — diagnostics should log *why* (ACL disconnect broadcast, reader EOF, process death). | xk-one-pro-sdk docs/VALIDATION.md:366-381 | H |

### KeyFinder vs real bug reports (KeyFinder.kt)
- Pattern offsets check out against `Xk.control` + `Xk.userBindData` (XkProtocol.kt:102-131): node at i, BE len `00 32`, format `00`, type, 16 bytes, `0x20`, key at i+25. **H**
- Paths: Pixel/AOSP zip has `FS/data/misc/bluetooth/logs/btsnoop_hci.log` and `btsnoop_hci.log.last`. Samsung One UI keeps snoop under `/data/log/bt/` (zip path `FS/data/log/bt/btsnoop_hci.log*`) and may omit it from the Developer-options report; the Samsung fallback is `*#9900#` (SysDump) → "Copy to sdcard", then pick `/sdcard/log/.../btsnoop_hci.log` directly. KeyFinder scans every entry so paths only matter for messages and speed. **M (Pixel) / L-M (Samsung)**
- Main `bugreport-*.txt` holds a `BTSNOOP_LOG_SUMMARY` base64 "btsnooz" ring (zlib, payloads truncated). Useless for the key; harmless. **M**
- **Filtered mode** (Android 13+: Disabled / Filtered / Enabled; 14+ adds sub-filters) truncates ACL payloads: btsnoop record `incl_len < orig_len`. KeyFinder can't tell the user this — it just says "not found". This is detectable. **H**
- **Rotation**: the stack rotates the log at ~65 535 packets per file, keeping only `.last`. A2DP music or a long photo session after connecting can push the 102E bind out of both files. **M**
- **Fragmentation**: the bind frame is 83 bytes, normally one RFCOMM frame in one ACL packet (BR/EDR ACL buffers are ~1021 B), so the raw scan works. If it straddles an ACL boundary, H4/ACL/btsnoop headers split it and the raw scan misses it. Low frequency, cheap to fix. **M**
- `ZipInputStream` throws on a STORED entry with a data descriptor and aborts the whole scan (KeyFinder.kt:42-50). A per-entry catch plus a `ZipFile` fallback (copy to cacheDir) makes it robust. **M**

## 2. Defects to fix (file:line)
1. AndroidManifest.xml:30-37 + MainActivity.kt:94-107 — no `SEND_MULTIPLE` (F2).
2. XkGlasses.kt:146-156 — leaked socket on failed connect (F4); no per-attempt timing (F5).
3. XkGlasses.kt:132 + Manifest — missing `BLUETOOTH_SCAN` (F3).
4. MainActivity.kt:316-343 — permanently-denied state not handled (F7).
5. Manifest/no service — no FGS, link dies in background (F6).
6. MainActivity.kt:48-49, 71-77 — inconsistent insets 30-34 vs 35+, no cutout (F8).
7. KeyFinder.kt:35-57, 62-98 — no filtered-mode detection, no ACL reassembly, zip robustness; MainActivity.kt:143-145 generic message.
8. GlassesScreen.kt:174-179 — setup text: add "take the bug report within ~10 min, no music", Samsung fallback.

## 3. Recommended changes

### 3.1 Manifest (unified diff)
```diff
     <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
+    <uses-permission android:name="android.permission.BLUETOOTH_SCAN"
+        android:usesPermissionFlags="neverForLocation" />
+    <uses-permission android:name="android.permission.RECORD_AUDIO" />
+    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
+    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
+    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
+    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
+    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
+    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
 ...
             <intent-filter>
                 <action android:name="android.intent.action.SEND" />
+                <action android:name="android.intent.action.SEND_MULTIPLE" />
                 <category android:name="android.intent.category.DEFAULT" />
 ...
         </activity>
+        <service android:name=".GlassesLinkService" android:exported="false"
+            android:foregroundServiceType="connectedDevice|microphone" />
     </application>
```
Request at runtime only when needed: CONNECT(+SCAN) at first connect; POST_NOTIFICATIONS when starting the service (API 33+); RECORD_AUDIO only for mic features. Start the FGS with type `CONNECTED_DEVICE` only; add `MICROPHONE` only while recording and only from a visible activity (API 34 while-in-use rule). Android 15 forbids mic FGS from BOOT_COMPLETED; connectedDevice is allowed. (H)

### 3.2 Shared bug report (MainActivity.handleShared)
```kotlin
private fun handleShared(intent: Intent?) {
    val uris: List<Uri> = when (intent?.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM))
        Intent.ACTION_SEND_MULTIPLE ->
            (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
             else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()
        else -> return
    }
    // Skip the screenshot that rides along with the zip.
    val logs = uris.filterNot { contentResolver.getType(it)?.startsWith("image/") == true }
    if (logs.isNotEmpty()) { show(2); readKeyFrom(logs) }   // readKeyFrom tries each until a key is found
    setIntent(Intent(this, MainActivity::class.java))
}
```

### 3.3 Socket attempts: close on failure, time each, remember winner (XkGlasses.openSocket)
```kotlin
val order = attempts.sortedByDescending { it.first == prefs.lastSocketMode }  // add Prefs.lastSocketMode: String?
for ((label, make) in order) {
    var s: BluetoothSocket? = null
    val t0 = android.os.SystemClock.elapsedRealtime()
    try {
        s = make(); s.connect()
        socket = s; out = s.outputStream; startReader(s.inputStream)
        prefs.lastSocketMode = label
        lastConnectTimings += "$label ok ${android.os.SystemClock.elapsedRealtime() - t0} ms"
        return
    } catch (e: Exception) {
        runCatching { s?.close() }                       // F4: free the RFCOMM DLC before the next try
        lastConnectTimings += "$label fail ${android.os.SystemClock.elapsedRealtime() - t0} ms: ${e.message}"
        lastFail = e
        Thread.sleep(300)                                // let the stack tear the ACL/DLC down
    }
}
```
And replace `runCatching { a.cancelDiscovery() }` with
`if (hasScan()) runCatching { if (a.isDiscovering) a.cancelDiscovery() }` where `hasScan()` checks `BLUETOOTH_SCAN` on 31+. (H)

### 3.4 Permanently denied permission (MainActivity.withBluetooth)
```kotlin
} else {
    val asked = prefs.btAsked                            // new Boolean pref, set true after first request
    val canAsk = bluetoothPermissions().any { shouldShowRequestPermissionRationale(it) }
    if (asked && !canAsk) {
        confirm("Allow Nearby devices", "Android stopped asking. Open app settings › Permissions › " +
            "Nearby devices › Allow.", "Open settings") {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)))
        }
        return
    }
    prefs.btAsked = true
    afterBluetoothGrant = action
    requestPermissions(bluetoothPermissions(), REQ_BT)   // bluetoothPermissions(): CONNECT + SCAN on 31+
}
```

### 3.5 Same insets on API 30-37 (MainActivity.onCreate)
```kotlin
if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)   // identical to forced 35+ behavior
root.setOnApplyWindowInsetsListener { v, insets ->
    val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
    v.setPadding(b.left, b.top, b.right, 0)
    tabBar.setPadding(0, ui.dp(6), 0, ui.dp(6) + b.bottom)          // tab bar colour runs under the nav bar
    insets
}
```
Keep the two `window.*BarColor` lines for API 30-34 only (`if (SDK_INT < 35)`). (M)

### 3.6 Foreground service skeleton (new GlassesLinkService.kt, framework only)
```kotlin
class GlassesLinkService : android.app.Service() {
    override fun onBind(i: Intent?) = null
    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel("link", "Glasses link",
            android.app.NotificationManager.IMPORTANCE_LOW))
        val n = android.app.Notification.Builder(this, "link").setSmallIcon(R.drawable.ic_glasses)
            .setContentTitle("Connected to your glasses").setOngoing(true).build()
        startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        return START_STICKY
    }
}
```
Start it (`startForegroundService`) when state becomes READY, stop on user disconnect. Register Bluetooth
broadcast receivers (ACL_DISCONNECTED, BOND_STATE_CHANGED, SCO state) with `RECEIVER_EXPORTED` on API 33+
(protected system broadcasts; API 34 throws if no flag is given for non-system-only filters). (H)
Samsung: also offer `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and tell the owner to add the app
to Settings › Battery › Background usage limits › **Never sleeping apps**. (H)
Optional, high value for reconnect: associate the glasses with `CompanionDeviceManager`
(`BluetoothDeviceFilter` by address). An associated app is exempt from background-FGS-start limits, so an
ACL_CONNECTED receiver can restart the link with the screen off. (M)

### 3.7 KeyFinder: btsnoop-aware scan (keep the raw scan as fallback)
btsnoop: 16-byte header `"btsnoop\0"`, version u32 BE, datalink u32 BE (1002 = H4). Each record: orig_len,
incl_len, flags, drops (u32 BE), timestamp (i64 BE), then `incl_len` bytes; byte 0 = H4 type (0x02 = ACL).
ACL header: handle+flags u16 LE (PB = bits 12-13, 01 = continuation), length u16 LE.
```kotlin
/** Returns true if the stream is a btsnoop file and was scanned record by record. */
fun scanSnoop(input: java.io.DataInputStream, counts: MutableMap<String, Int>, stats: Stats): Boolean {
    val magic = ByteArray(8); input.readFully(magic)
    if (String(magic, Charsets.ISO_8859_1) != "btsnoop\u0000") return false
    input.readInt(); val link = input.readInt(); if (link != 1002) return false
    val tails = HashMap<Int, ByteArray>()                // key = handle*2 + direction; last 96 bytes of stream
    while (true) {
        val orig = try { input.readInt() } catch (e: java.io.EOFException) { break }
        val incl = input.readInt(); val flags = input.readInt(); input.readInt(); input.readLong()
        if (incl < 0 || incl > 70_000) break
        val p = ByteArray(incl); input.readFully(p)
        if (incl < 5 || p[0].toInt() != 0x02) continue
        stats.acl++; if (incl < orig) stats.truncated++
        val hf = (p[1].toInt() and 0xFF) or ((p[2].toInt() and 0xFF) shl 8)
        val key = (hf and 0x0FFF) * 2 + (flags and 1)
        val body = p.copyOfRange(5, incl)                // ACL payload (L2CAP + RFCOMM + data)
        val joined = (tails[key] ?: ByteArray(0)) + body  // a continuation joins its first fragment
        scanWindow(joined, joined.size, counts)
        tails[key] = joined.copyOfRange(maxOf(0, joined.size - 96), joined.size)
    }
    stats.snoopFiles++; return true
}
```
Joining tails means a key split by ACL headers is found; a match already counted in the tail can be counted
twice — harmless, `find` uses the max. In `scanAny`, peek 8 bytes: if `btsnoop\0`, use `scanSnoop`, else the
existing raw scan. Wrap each zip entry in `try/catch (ZipException)`; if the zip stream fails, copy the URI to
`cacheDir` and retry with `java.util.zip.ZipFile` (reads the central directory), scanning `btsnoop*` entries
first and stopping when a key is found. Extend `Result` with `snoopFiles`, `acl`, `truncated`. (M)

Messages in `readKeyFrom` (MainActivity.kt:140-147):
- `snoopFiles == 0` → "This bug report has no Bluetooth log. Check the snoop log was on, then turn Bluetooth off and on before connecting LensMoo. On Samsung, if it still says this, dial *#9900#, tap Copy to sdcard, then pick btsnoop_hci.log from the log folder."
- `truncated > acl / 2` → "Your snoop log is set to Filtered. Pick Enabled, turn Bluetooth off and on, connect LensMoo again."
- else → "The log rolled over. Take the bug report within 10 minutes of connecting LensMoo, without playing music."

## 4. Diagnostics screen design
Entry: Settings › "Run diagnostics" (new `DiagnosticsScreen`, shown via `show()`; tab bar unchanged).
Runs on the `XkGlasses.io` executor; each step logs `PASS/FAIL/SKIP name value (ms)`; never stops on a FAIL.
Needs one hook in XkGlasses: `internal fun probe(node, action, data, timeoutMs): Pair<Xk.Frame?, Long>` (wraps
`request`, returns elapsed ms) plus `lastConnectTimings`, `parser.dropped`, last link-loss reason.

| Step | How (framework API) | Report line |
|---|---|---|
| 1 Phone | `Build.MANUFACTURER/MODEL/DISPLAY`, `SDK_INT`, app versionName | env |
| 2 Permissions | `checkSelfPermission` for CONNECT, SCAN, RECORD_AUDIO, POST_NOTIFICATIONS; `NotificationManager.areNotificationsEnabled()` | granted / denied / denied-permanently |
| 3 Background | `PowerManager.isIgnoringBatteryOptimizations`, `ActivityManager.isBackgroundRestricted` | yes/no |
| 4 Adapter | `isEnabled`, `isDiscovering` (if SCAN), `getProfileConnectionState(HEADSET / A2DP)` | state |
| 5 Bond | `getRemoteDevice(addr).bondState`, `.type` (CLASSIC/DUAL), `.uuids` contains 0x1101 | bonded, type, SPP in SDP cache |
| 6 SPP connect | if IDLE: run `connect` and print `lastConnectTimings` per mode; if READY: "already up"; also `socket.maxTransmitPacketSize/maxReceivePacketSize` | mode, ms |
| 7 Bind | time 102E request→reply, replyType, data[0] | ms, result code |
| 8 Battery RTT | 5 × `probe("1003", READ)` | min/median/max ms, level |
| 9 Photo | 57B0 sent → 7320 announce ms, part count, download ms, bytes, KB/s, JPEG SOI/EOI + `BitmapFactory` bounds | w×h, KB/s |
| 10 Preview fps | only when preview exists: 5720 on, count H.264 frames 10 s, 5720 off; expect ~30 fps (F10) | fps, gaps > 200 ms |
| 11 SCO route | needs RECORD_AUDIO: find `TYPE_BLUETOOTH_SCO` in `availableCommunicationDevices`, `setCommunicationDevice`, wait for `OnCommunicationDeviceChangedListener` (≤4 s), record 2 s `AudioRecord(VOICE_COMMUNICATION, 16 kHz)`, RMS dBFS, `clearCommunicationDevice()` | route ms, level dBFS |
| 12 A2DP | `getDevices(GET_DEVICES_OUTPUTS)` has `TYPE_BLUETOOTH_A2DP` | yes/no |
| 13 Link health | `parser.dropped`, link-loss count + reasons since launch | counts |

Report: plain text header + table, then the last 60 log lines; shared with the existing `shareText`.
**Redaction is mandatory**: never print the key (log already hides it, XkGlasses.kt:171 `logData=false`);
show MAC as `XX:XX:XX:XX:AB:CD`; device name allowed. Steps 9-11 ask before running (take a photo, use mic).

## 5. On-device checks for the owner
1. Share sheet: Developer options › Take bug report › Interactive → notification → Share. P3 Companion must be listed (fails today, passes after 3.1/3.2).
2. Snoop mode: set Filtered, BT off/on, connect LensMoo, bug report → app must say "set to Filtered".
3. Socket leak: power glasses off, tap Connect (all 3 modes fail), power on, tap Connect → must connect first try; log shows per-mode ms.
4. Samsung Bluetooth settings open (scanning) in split screen, tap Connect → should still connect within ~5 s once SCAN is granted.
5. Deny Nearby devices twice → button must open app settings.
6. Background: connect, lock the phone 10 min, press the glasses photo button → photo appears in album (needs FGS + Never sleeping).
7. Rotate to landscape on the punch-hole phone → no content under the camera cutout or nav bar.
8. Run Diagnostics, share the report text into the project chat.

## 6. Confidence summary
SEND_MULTIPLE gap M-H · socket leak H · SCAN for cancelDiscovery H · FGS need H · permanently-denied H ·
insets M · filtered detection H · ACL reassembly need M · Samsung snoop path L-M · rotation size M ·
CDM background exemption M.
