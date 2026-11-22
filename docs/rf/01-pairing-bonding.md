# RF-01 — Bluetooth Classic discovery, pairing and bonding (Android 11–15, Samsung One UI)

Scope: how P3 Companion finds, pairs and keeps a bond with the glasses, and what LensMoo and node `102E` do to that bond.
Paths: `app/` = `/home/user/p3/app/src/main`, `kt/` = `app/java/com/zdycomp/p3companion`, `SDK/` = `/home/claude/beneduzi/xk-one-pro-sdk`.
Confidence: H = documented or seen in code, M = strong platform knowledge but not tested on this unit, L = needs a device test.

## 1. Findings (sources)

| # | Finding | Source | Conf |
|---|---|---|---|
| F1 | Name pattern is `xk one Pro_XXXX`. XXXX is the last 4 hex digits of the BR/EDR MAC (`xk one Pro_F773` = `FA:00:11:12:F7:73`). | SDK/docs/HARDWARE.md:10, PROTOCOL.md:253-254 | H |
| F2 | The glasses are dual-mode. BR/EDR carries SPP ch 8, HFP ch 1, A2DP and AVRCP. LE GATT carries HID `0x1812`, `aaa0` and `fff0`, which are used "during initial pairing". | HARDWARE.md:31-39, REVERSE_ENGINEERING.md:10 | H |
| F3 | The vendor capability table has the bit "BT/BLE same name" (55). The LE advertisement probably uses the same name as the classic side, so a mixed scan can show **two entries with one name**. | NODES.md:299 | M |
| F4 | `102E` bindType values: 0 = SCAN_QR, 1 = DISCOVERY, 2 = CONNECT_BACK, 3 = DISCONNECT, **4 = UNBIND (also removes the Bluetooth pairing on the glasses)**, 5 = POWEROFF. You must re-pair after UNBIND. The userId stays valid. | BONDED_ENROLLMENT.md:63-72,173-174; PROTOCOL.md:85-92 | H |
| F5 | The real userId with CONNECT_BACK still works after a fresh bond, after UNBIND and after a factory reset (the baseline passes after every mutation). | VALIDATION.md:80-87 | H |
| F6 | Android connect order: secure SPP first, then insecure, then reflected channel 8. The link was bonded in every validated run. | BONDED_ENROLLMENT.md:169-172; VALIDATION.md:27 | H |
| F7 | The SDK Android example could not find the glasses while they were bonded to another host. It only reads `bondedDevices`, so it needs a re-pair. | VALIDATION.md:170-172; flutter_example MainActivity.kt:90-98 | H |
| F8 | LensMoo's "keepalive" only keeps its process alive (autostart, wakelocks). It does not ping SPP. The glasses need no keepalive. | PROTOCOL.md:293-307 | H |
| F9 | The `1001` JSON returns `mac_addr` and `dev_name`, which lets you check that you reached the right radio. | HARDWARE.md:19-21 | H |
| F10 | Android platform: on API 31+, `startDiscovery`, `cancelDiscovery` and `isDiscovering` need **BLUETOOTH_SCAN**. On API 30 (Android 11), discovery needs ACCESS_FINE_LOCATION **and Location turned on**, or ACTION_FOUND never fires. `createBond` needs BLUETOOTH_CONNECT (API 31+) or BLUETOOTH_ADMIN (API 30 and lower). | Android docs | H |
| F11 | `startDiscovery()` mixes inquiry with an LE scan, so ACTION_FOUND also reports LE-only entries (`type == DEVICE_TYPE_LE`). A bond made on an LE entry is an LE (HID-over-GATT) bond. It does not give the BR/EDR link key that secure RFCOMM needs. RFCOMM itself is always BR/EDR. | Android behavior | M |
| F12 | Bonds belong to the phone, not to an app. LensMoo and P3 on the same phone share one bond. The conflict is the single SPP session: only one RFCOMM client can hold ch 8. | Android behavior + F6 | H |
| F13 | Hidden `UNBOND_REASON_*` in `"android.bluetooth.device.extra.REASON"`: 1 auth failed, 2 rejected, 3 canceled, 4 remote down, 5 discovery in progress, 6 auth timeout, 7 repeated attempts, 8 remote canceled, 9 removed. Third-party apps cannot call `removeBond()` or `setPairingConfirmation()` on API 31+ (privileged). `setPin()` is public. | AOSP BluetoothDevice | M |

## 2. Defects in the current app

| # | Defect | Where | Impact | Conf |
|---|---|---|---|---|
| D1 | The app has **no discovery and no pairing**. It only lists `bondedDevices` and asks the user to pair in system Settings or in LensMoo. This does not replace LensMoo. | kt/XkGlasses.kt:84-88, kt/GlassesScreen.kt:127-133 | High | H |
| D2 | The manifest has no `BLUETOOTH_SCAN` and no `ACCESS_FINE_LOCATION` (maxSdk 30). As a result, `cancelDiscovery()` throws SecurityException on Android 12+, and `runCatching` hides it, so the call does nothing. A discovery left running (by Settings, another app or LensMoo) then slows or breaks `connect()`. | app/AndroidManifest.xml:5-7, kt/XkGlasses.kt:132, kt/MainActivity.kt:310-311 | High | H |
| D3 | `connect()` never checks `bondState`. On an unbonded or stale-bonded device, the secure socket starts a **surprise system pairing popup** inside a blocking `connect()`. If that fails, a second secure attempt (reflected ch 8) can pop it up again. | kt/XkGlasses.kt:133-157 | High | H |
| D4 | A failed `BluetoothSocket` is never closed before the next attempt. The leaked RFCOMM fd and state cause "Device or resource busy" or "read failed, socket might closed" on the next attempt, and on Samsung until Bluetooth is toggled. | kt/XkGlasses.kt:144-156 | High | H |
| D5 | Cancel does not work. `socket` is set only after `connect()` succeeds, so `disconnect()` cannot abort a blocking connect. The io thread later sets READY **after the user cancelled** (a race on `state`). | kt/XkGlasses.kt:146-148,179-186,82 | Med | H |
| D6 | Auto-reconnect at app start and after link loss does not check the bond. If the glasses lost the pairing (LensMoo "Unbind", a reset, or `102E` type 4), launching the app pops a pairing request or fails with a misleading "paired in settings" text. | kt/MainActivity.kt:82-84, kt/XkGlasses.kt:123-127,381-383 | Med | H |
| D7 | `looksLikeGlasses()` is a loose substring test. "p3" matches "Flip3" and "lens" matches unrelated devices. There is no check of the MAC suffix, so with dual-mode or neighbor devices the wrong one can be picked. | kt/XkGlasses.kt:461-464 | Low-Med | H |
| D8 | `userBindData()` accepts any bindType. Passing 3, 4 or 5 by mistake would disconnect, **unpair** (F4) or power off the glasses. | kt/XkProtocol.kt:123 | Med (latent) | H |
| D9 | `1001` `mac_addr` is ignored, so there is no proof the link reached the intended radio. | kt/XkGlasses.kt:209-216 | Low | H |
| D10 | The connect failure text tells the user to "pair in Bluetooth settings" even when the device *is* bonded and the real cause is a stale key or LensMoo holding the SPP link. There is no diagnostic about type, class, UUIDs or bond in the log. | kt/XkGlasses.kt:158-161 | Med | H |

## 3. Recommended changes (framework APIs only)

### C1 Manifest (fixes D2)
```diff
     <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
+    <uses-permission android:name="android.permission.BLUETOOTH_SCAN"
+        android:usesPermissionFlags="neverForLocation" />
+    <!-- Android 11 only: classic discovery returns nothing without it -->
+    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />
```
Add `tools:targetApi="31"` on the BLUETOOTH_SCAN line if lint complains. `neverForLocation` only filters BLE beacons, not classic inquiry. (H)

### C2 MainActivity permissions (D2)
```kotlin
private fun bluetoothPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

/** Android 11: classic discovery needs Location services on. */
fun locationOkForScan(): Boolean = Build.VERSION.SDK_INT >= 31 ||
    getSystemService(android.location.LocationManager::class.java)?.isLocationEnabled == true
```
On API 30, if `!locationOkForScan()`, open `Settings.ACTION_LOCATION_SOURCE_SETTINGS` before scanning. Only the scanning path needs ACCESS_FINE_LOCATION on API 30. Connect and bond use the manifest-only BLUETOOTH and BLUETOOTH_ADMIN permissions. (H)

### C3 Strict matching (D7, F1, F3, F11) in `XkGlasses.companion`
```kotlin
private val SUFFIX = Regex("(?i)[_ -]([0-9a-f]{4})$")
/** True when the name ends in _XXXX and XXXX equals the last 4 hex digits of this MAC (F1). */
fun strongMatch(name: String?, address: String): Boolean {
    val m = SUFFIX.find(name?.trim() ?: return false) ?: return false
    return looksLikeGlasses(name) && address.replace(":", "").endsWith(m.groupValues[1], true)
}
fun looksLikeGlasses(name: String): Boolean {
    val n = name.lowercase(Locale.US)
    return n.startsWith("xk one") || n.startsWith("xk-") || n.startsWith("novasight") ||
        listOf("glass", "lensmoo", "w202", "e13c").any { n.contains(it) }
}
```
Rank by strongMatch, then looksLikeGlasses, then device class major `AUDIO_VIDEO` (0x0400) or `WEARABLE` (0x0700). The class is used for ranking only, never as a filter. (Name rule H, class L)

### C4 New `kt/GlassesScan.kt`: discovery that drops LE-only entries (D1, F10, F11)
```kotlin
@SuppressLint("MissingPermission")
class GlassesScan(private val ctx: Context, private val onUpdate: (List<BluetoothDevice>) -> Unit,
                  private val onDone: () -> Unit) {
    private val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private val found = LinkedHashMap<String, BluetoothDevice>()
    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothDevice.ACTION_FOUND, BluetoothDevice.ACTION_NAME_CHANGED -> {
                    val d = i.btDevice() ?: return
                    if (d.type == BluetoothDevice.DEVICE_TYPE_LE) return   // LE/HID face: never bond or connect SPP to it
                    val name = i.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: d.name ?: return
                    if (!XkGlasses.looksLikeGlasses(name)) return
                    found[d.address] = d
                    onUpdate(found.values.sortedByDescending { XkGlasses.strongMatch(it.name ?: name, it.address) })
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> { stop(); onDone() }
            }
        }
    }
    fun start(): Boolean {
        val a = adapter ?: return false
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ctx.registerBt(rx, f)
        if (a.isDiscovering) a.cancelDiscovery()
        return a.startDiscovery()            // ~12 s inquiry, then FINISHED
    }
    fun stop() { runCatching { adapter?.cancelDiscovery() }; runCatching { ctx.unregisterReceiver(rx) } }
}

fun Intent.btDevice(): BluetoothDevice? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    else @Suppress("DEPRECATION") getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

/** Bluetooth broadcasts come from the Bluetooth process; register as EXPORTED so none are dropped on 33+. */
fun Context.registerBt(r: BroadcastReceiver, f: IntentFilter) {
    if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED) else registerReceiver(r, f)
}
```
Call `stop()` in `onHidden()`. Show the same name only once: if two entries share a name, keep the one whose MAC passes `strongMatch`. Tapping an entry calls the existing `pick(d)`, and `connect` (C5) now bonds. (M)

### C5 `XkGlasses`: bond before socket, close failed sockets, real cancel (D3, D4, D5, D10)
```kotlin
@Volatile private var pending: BluetoothSocket? = null
@Volatile private var gen = 0

// connect(): val myGen = ++gen  …  after bind(): if (myGen != gen) { closeSocket(); return@execute }
// disconnect(): gen++; runCatching { pending?.close() }; pending = null; …existing body

private fun ensureBonded(d: BluetoothDevice) {
    if (d.bondState == BluetoothDevice.BOND_BONDED) return
    val result = LinkedBlockingQueue<Pair<Int, Int>>()
    val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val dev = i.btDevice() ?: return
            if (!dev.address.equals(d.address, true)) return
            when (i.action) {
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val s = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                    val why = i.getIntExtra("android.bluetooth.device.extra.REASON", 0)
                    addLog("Bond state $s" + if (why != 0) " (reason $why)" else "")
                    if (s == BluetoothDevice.BOND_BONDED || s == BluetoothDevice.BOND_NONE) result.offer(s to why)
                }
                BluetoothDevice.ACTION_PAIRING_REQUEST ->
                    addLog("Pairing popup, variant " + i.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1))
            }
        }
    }
    app.registerBt(rx, IntentFilter().apply {
        addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED); addAction(BluetoothDevice.ACTION_PAIRING_REQUEST) })
    try {
        if (d.bondState != BluetoothDevice.BOND_BONDING && !d.createBond())
            error("Couldn't start pairing. Turn the glasses off and on, then try again.")
        lastMessage = "Tap Pair on the phone's popup."; changed()
        val (s, why) = result.poll(45, TimeUnit.SECONDS) ?: error("Pairing timed out. Look for a Bluetooth pairing notification.")
        if (s != BluetoothDevice.BOND_BONDED) error(when (why) {
            3, 8 -> "Pairing was cancelled."
            4, 6 -> "The glasses didn't answer. Make sure they're on and not connected to another phone."
            else -> "Pairing failed (code $why). Turn the glasses off and on, then try again."
        })
        Thread.sleep(1_500)   // let Android finish SDP and its own HFP/A2DP auto-connect first
    } finally { runCatching { app.unregisterReceiver(rx) } }
}
```
Inside `openSocket`, replace lines 132-157 with:
```kotlin
if (Build.VERSION.SDK_INT < 31 || app.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PERMISSION_GRANTED)
    runCatching { if (a.isDiscovering) a.cancelDiscovery() }
val device = a.getRemoteDevice(address.uppercase(Locale.US))
val cls = device.bluetoothClass
addLog("Device type=${device.type} bond=${device.bondState} class=" +
    (cls?.let { "%04X/%04X".format(it.majorDeviceClass, it.deviceClass) } ?: "-") +
    " spp=" + (device.uuids?.any { it.uuid == SPP } ?: "unknown"))
ensureBonded(device)
for ((label, make) in attempts) {
    var s: BluetoothSocket? = null
    try {
        s = make(); pending = s
        s.connect()
        pending = null; socket = s; out = s.outputStream; startReader(s.inputStream)
        addLog("Link open ($label)"); return
    } catch (e: Exception) {
        runCatching { s?.close() }; pending = null
        lastFail = e; addLog("Link $label failed: ${e.message}")
        if (myGen != gen) error("Cancelled.")
        Thread.sleep(400)        // Samsung needs a gap after closing a failed RFCOMM socket
    }
}
throw IllegalStateException(
    "The glasses didn't answer. Close LensMoo (Settings › Apps › LensMoo › Force stop). If it still fails, " +
    "the glasses may have forgotten this phone: unpair them in Bluetooth settings, then tap Find glasses here.")
```
Pass `myGen` into `openSocket`. (Close and gap: H. 1.5 s settle: M.)

### C6 Auto-reconnect only when bonded (D6)
```kotlin
fun reconnectSaved() {
    val addr = prefs.glassesAddress ?: return
    if (state != State.IDLE || prefs.userKey == null || !bluetoothOn()) return
    val d = runCatching { adapter?.getRemoteDevice(addr) }.getOrNull() ?: return
    if (d.bondState != BluetoothDevice.BOND_BONDED) {
        lastError = "Your glasses aren't paired with this phone any more. Tap Find glasses to pair again."
        changed(); return
    }
    connect(addr, prefs.glassesName)
}
```
Also register one app-wide `ACTION_BOND_STATE_CHANGED` receiver while the app is alive. If the saved address drops to `BOND_NONE`, set the error above and stop auto-reconnect. (H)

### C7 `102E` guard (D8, F4) in kt/XkProtocol.kt:123
```kotlin
const val BIND_CONNECT_BACK = 2
fun userBindData(userId: String, bindType: Int = BIND_CONNECT_BACK): ByteArray {
    require(bindType in 0..2) { "bindType $bindType disconnects, unpairs or powers off the glasses" }
    …
```
The app must never offer "Unbind". "Forget these glasses" (kt/SettingsScreen.kt:79-86) must stay prefs-only. It may add a button that opens `Settings.ACTION_BLUETOOTH_SETTINGS` so the user can unpair on the phone side. (H)
Optional (L): if CONNECT_BACK returns FMT_ERRCODE right after a *fresh* bond made in this session, retry once with bindType 1 (DISCOVERY), which is the type LensMoo uses for first pairing (BONDED_ENROLLMENT.md:101-103). F5 says this should be unnecessary.

### C8 MAC check after `1001` (D9, F9) in kt/XkGlasses.kt:211
```kotlin
val mac = j.optString("mac_addr")
if (mac.isNotBlank()) addLog("MAC check: " + if (mac.equals(prefs.glassesAddress, true)) "matches" else "DIFFERS from saved link")
```
The log does not print the full MAC, because the log can be shared.

## 4. LensMoo coexistence and UNBIND (what the owner must know)
- **Same phone, LensMoo installed:** the bond is shared, so do not re-pair. Force stop LensMoo before connecting, and set it to "Put to sleep" or "Deep sleeping" in Settings › Battery › Background usage limits. Its service reconnects SPP on its own (F8, F12). (H)
- **Glasses were last connected to another phone:** they page that phone first. Turn Bluetooth off on it, power-cycle the glasses, then pair here. (M)
- **LensMoo "Unbind / Remove device"** sends `102E` type 4. That wipes the pairing **on the glasses** while this phone still shows them as paired. Every secure connect then fails with an auth error or a "Couldn't pair" notification, and Android may drop the bond itself (reason 1 or 9). Fix: unpair in phone Settings, then use Find glasses in P3. The account key still works (F4, F5). (H for the effect, M for Android's reaction)
- **Account bind:** P3 sends the same account id that LensMoo uses, with CONNECT_BACK. There is no need to "unbind from LensMoo" first, and the owner should **not** do it. (H)

## 5. On-device checks (phone only, no PC)
1. Go to Settings › Connections › Bluetooth and find the glasses under Paired devices. Note the exact name, and check that its last 4 characters match the Bluetooth address shown on the gear page. On the gear page, note which toggles exist (Calls, Audio, **Input device**). An "Input device" toggle means the LE HID bond (F2) exists too.
2. After C5: in P3 › Settings, turn on Show connection log and tap Connect. Read the `Device type=… bond=… class=… spp=…` line. Expected: type 3 (dual) or 1 (classic), bond 12 (BONDED), spp=true. Send the line to the team.
3. LensMoo conflict test: open LensMoo and connect it, then tap Connect in P3. A failure is expected. Force stop LensMoo, tap Connect again, and it should connect within about 5 s.
4. After C4/C5: unpair the glasses in Settings, power-cycle them, and in P3 tap Find glasses. They should be listed within about 15 s, with only one row for the name. Tap it and the system popup appears. Note the log line "Pairing popup, variant N" (0 = PIN, 2 = number match, 3 = just-works consent). If it asks for a PIN, try 0000, then 1234.
5. On Android 11 only: turn Location off and tap Find glasses. The app must ask you to turn Location on, not show an empty list.
6. Press Cancel while a connect is in progress. The status must stay "Not connected" and must not flip to Connected later (D5).
7. Do **not** run LensMoo's Unbind as a test. It is destructive (Section 4).

## 6. Priority
1. C5 (bond first, close failed sockets, real cancel) together with C1/C2 (BLUETOOTH_SCAN, so `cancelDiscovery` works).
2. C4 + C3 (in-app Find and pair that ignores LE-only entries and matches the MAC suffix). This is what replaces LensMoo's pairing screen.
3. C6 + C7 (no pairing popups on launch after a lost bond, and UNBIND made impossible).
