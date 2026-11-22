# 09 — The Bluetooth Low Energy side

Specialist 09 of 10. Scope: GATT services, whether LensMoo needs BLE for discovery, wake-up or
binding, LE advertising, LE vs BR/EDR addresses, transport selection, scan permissions on
Android 12–15, and whether any LensMoo function depends on BLE.

**Verdict: P3 Companion does not need a BLE data path.** Every validated LensMoo function
(bind, battery, photo, media counts, settings, the touch/voice trigger, the voice-assistant
credentials, microphone and speakers) runs over Classic (SPP ch. 8, HFP/SCO, A2DP, AVRCP).
BLE matters in only two places: (a) a phone can hold an **LE-only bond** to the glasses, which
the app must not pick for RFCOMM, and (b) **in-app pairing** (so the owner never needs LensMoo
or the Settings app to pair) needs `BLUETOOTH_SCAN`. One question is still open: whether LensMoo
*scans* over LE when it first finds the glasses. Change D below answers it on the phone alone.

---

## 1. Findings (with sources)

| # | Finding | Source | Conf. |
|---|---|---|---|
| F1 | The SDK docs disagree with each other. HARDWARE.md lists GATT `0x1812` (HID), `aaa0`, `fff0` as "auxiliary discovery and pairing triggers, VALIDATED". The later VALIDATION.md says *no BLE advertisement was observed … No GATT services were resolvable*. | xk-one-pro-sdk `docs/HARDWARE.md:31,39`, `docs/REVERSE_ENGINEERING.md:10`, `docs/VALIDATION.md:199-200` | High (that they disagree) |
| F2 | The two can both be true. `gatttool` on BlueZ can reach GATT over BR/EDR (ATT on L2CAP PSM 0x1F) or catch a short LE advertising window. Cheap dual-mode SoCs often advertise on LE **only while no Classic link is up**, and the VALIDATION scan was run while the glasses were bonded to a PC. In practice: GATT may exist, but no observed data or control traffic uses it. | inference from F1 + `VALIDATION.md:27` (link bonded) | Medium |
| F3 | Binding is **not** done over BLE. It is node `102E` inside SPP frames after RFCOMM ch. 8 opens. P3's own KeyFinder reads the key out of RFCOMM payloads in the owner's HCI log. | `docs/PROTOCOL.md:85-92`, `docs/BONDED_ENROLLMENT.md:67-71,167-174`; P3 `KeyFinder.kt:15-16,84-98` | High |
| F4 | No BLE wake-up or keepalive is needed. An idle SPP session survives 240 s with zero frames, and LensMoo's "keepalive" is Android process persistence, not a radio wake. Without an SPP session, the glasses say *"Please connect to the app"*, so they wait for SPP, not for a BLE trigger. | `docs/PROTOCOL.md:291-308`, `docs/HARDWARE.md:55` | High |
| F5 | The AI voice feature does not use BLE. Touch and button events arrive as `C101`/`C107` on ch. 8. The assistant's credential exchange (`FGS`/`FND`) runs on the `0x2B` SPP channel. Mic audio comes over HFP/SCO (16 kHz mono) or a protocol Opus stream, which also runs over SPP. | `docs/PROTOCOL.md:282-285,361-363,423-424`; lensmoo-crash-fix `docs/AUDIO-RESEARCH.md:11,18,176` | High |
| F6 | Settings (`1017`), storage (`5770`/`5780`), capture (`57B0`) and transfer (`7300`/`7500`) are all SPP nodes. None is documented on GATT. | `docs/VALIDATION.md:223-230,265-267`, `docs/NODES.md` §7 | High |
| F7 | The BLE words in the vendor code come from the shared smartwatch SDK: capability bits 27/28 "BLE delete" and 55 "BT/BLE same name", plus the class name `BleDeviceModel`. They are naming, not evidence of a BLE link for the glasses. | `docs/VALIDATION.md:214-215`, `docs/NODES.md:299,303-304`; lensmoo-crash-fix `docs/FIRMWARE-RESEARCH.md:36,150` | Medium-High |
| F8 | LensMoo's discovery transport is **not documented** in any local source. It finds devices by name prefix (`WmDiscoverDevice.deviceNamePrefix`) or by QR code (`bindType` 0 SCAN_QR / 1 DISCOVERY). Whatever it scans with, it then connects over Classic SPP (secure, then insecure, then reflected ch. 8). | `docs/PAYLOADS.md:173`, `docs/BONDED_ENROLLMENT.md:67-68,171-172` | High (data path); Unknown (scan transport) |
| F9 | Name pattern: `xk one Pro_XXXX`, where XXXX is the last 4 hex digits of the MAC. Example MAC `FA:00:11:12:F7:73`: first octet `0xFA` is locally administered (no IEEE OUI) and also has the top-two-bits-`11` form of an LE random-static address. So the chip may reuse one address for both sides (one DUAL record on the phone) or use a separate LE address (two records with the same name). | `docs/HARDWARE.md:10`, `docs/PROTOCOL.md:253` | Medium |
| F10 | The HID service 0x1812, if live, is consumed by the **OS** HID host, not by an app. A replacement app has nothing to implement for it. If the phone bonds it, Samsung may treat the glasses as a keyboard or remote. | `docs/HARDWARE.md:39` + Android HID-host behavior | Medium |
| F11 | The reference SDK client declares `BLUETOOTH_SCAN neverForLocation` but never scans. It only uses `bondedDevices`. | xk-one-pro-sdk `flutter_example/.../AndroidManifest.xml:7`, `.../MainActivity.kt:93,105` | High |

## 2. Defects in the current app

| # | Defect | Where | Impact | Conf. |
|---|---|---|---|---|
| D1 | `pairedDevices()` lists **every** bonded device, LE-only bonds included, and ranks them by name only. If the phone holds an LE bond named `xk one Pro_F773` (from an HID pairing pop-up or a separate LE address), the owner can tap it. All three RFCOMM attempts then fail with "glasses didn't answer", which is the wrong advice. | `XkGlasses.kt:84-88`, `GlassesScreen.kt:127-141` | High when it happens | Medium |
| D2 | `openSocket()` never checks `device.type` or whether the device lists SPP, so an LE-only address wastes about 3 × 12 s of connect timeouts. | `XkGlasses.kt:133-158` | Medium | High |
| D3 | `cancelDiscovery()` needs `BLUETOOTH_SCAN` on API 31+. The manifest lacks it, so the call always throws a SecurityException, which `runCatching` swallows. That is harmless today, but once in-app discovery exists (C3) a running inquiry will **not** be cancelled before RFCOMM connects. An active inquiry is a known cause of RFCOMM connect failures and low throughput. | `XkGlasses.kt:132`, `AndroidManifest.xml:5-7` | Latent | High |
| D4 | No in-app pairing. The empty-state text tells the owner to pair in system Settings "or connect them once in LensMoo", so a "LensMoo replacement" still depends on LensMoo or Settings to pair. | `GlassesScreen.kt:128-133` | Parity gap | High |
| D5 | Permissions are requested only for CONNECT (API 31+). There is no path for SCAN (API 31+) or FINE_LOCATION (API 30, needed by `startDiscovery`). | `MainActivity.kt:310-311` | Needed by C3 | High |

## 3. Recommended changes (framework APIs only)

### C1 — Drop LE-only bonds and rank SPP-capable devices first (fixes D1). Priority 1.
```diff
--- a/app/src/main/java/com/zdycomp/p3companion/XkGlasses.kt
-    data class Paired(val address: String, val name: String)
+    data class Paired(val address: String, val name: String, val hasSpp: Boolean = false)
@@
     fun pairedDevices(): List<Paired> {
         val list = runCatching { adapter?.bondedDevices?.toList() }.getOrNull() ?: emptyList()
-        return list.map { Paired(it.address, it.name ?: it.address) }
-            .sortedByDescending { looksLikeGlasses(it.name) }
+        return list
+            // RFCOMM cannot run on an LE-only bond (the glasses' GATT/HID side).
+            .filter { it.type != BluetoothDevice.DEVICE_TYPE_LE }
+            .map { d -> Paired(d.address, d.name ?: d.address,
+                hasSpp = d.uuids?.any { it.uuid == SPP } == true) }
+            .sortedWith(compareByDescending<Paired> { looksLikeGlasses(it.name) }.thenByDescending { it.hasSpp })
     }
```
`DEVICE_TYPE_UNKNOWN` is kept on purpose, because some stacks report it for cached Classic bonds.

### C2 — Fail fast and clearly on an LE-only address (fixes D2). Priority 1.
```diff
@@ private fun openSocket(address: String) {
         val device: BluetoothDevice = a.getRemoteDevice(address)
+        if (device.type == BluetoothDevice.DEVICE_TYPE_LE) error(
+            "That entry is the glasses' low-energy side. Pick the other \"${device.name}\" entry, " +
+                "or forget both in Bluetooth settings and pair again here.")
         addLog("Opening link to ${device.name ?: address}")
```
Always use `getRemoteDevice(addr)` (the Classic path). Never use `getRemoteLeDevice()`.
`createRfcommSocket*` is BR/EDR by definition, so no transport flag is needed for SPP.

### C3 — In-app pairing over BR/EDR, no BLE (fixes D3–D5). Priority 2.
Manifest (BLE scan results are not needed, so declare no location use on 31+):
```diff
     <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
+    <uses-permission android:name="android.permission.BLUETOOTH_SCAN"
+        android:usesPermissionFlags="neverForLocation" />
+    <!-- Android 11 (API 30) only: startDiscovery needs fine location + Location turned on -->
+    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />
```
`MainActivity.kt:310` becomes `arrayOf(BLUETOOTH_CONNECT, BLUETOOTH_SCAN)` on 31+ and
`arrayOf(ACCESS_FINE_LOCATION)` on 30. On 30, also check
`LocationManager.isLocationEnabled` before discovery. With SCAN granted, `cancelDiscovery()` at
`XkGlasses.kt:132` starts working (D3).

```kotlin
// XkGlasses.kt — find unpaired glasses and bond over Classic. Caller holds SCAN+CONNECT (31+).
private var finder: BroadcastReceiver? = null

@SuppressLint("MissingPermission")
fun findAndPair(onStatus: (String) -> Unit) {
    val a = adapter ?: return onStatus("This phone has no Bluetooth.")
    stopFinding()
    val r = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            @Suppress("DEPRECATION")
            val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            when (i.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val name = i.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: d.name ?: return
                    // startDiscovery also does an LE scan, so skip LE-only hits: bond on the BR/EDR record.
                    if (d.type == BluetoothDevice.DEVICE_TYPE_LE || !looksLikeGlasses(name)) return
                    a.cancelDiscovery()
                    onStatus("Found $name, pairing…")
                    if (d.bondState == BluetoothDevice.BOND_BONDED) onBonded(d, name, onStatus)
                    else if (!d.createBond()) onStatus("Couldn't start pairing. Try again.")
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> when (
                    i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                    BluetoothDevice.BOND_BONDED -> onBonded(d, d.name ?: d.address, onStatus)
                    BluetoothDevice.BOND_NONE -> onStatus("Pairing was cancelled or failed.")
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> onStatus("Search finished.")
            }
        }
    }
    val f = IntentFilter().apply {
        addAction(BluetoothDevice.ACTION_FOUND)
        addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
    }
    app.registerReceiver(r, f)   // system broadcasts; no export flag needed
    finder = r
    if (!a.startDiscovery()) onStatus("Couldn't start searching. Is Bluetooth on?")
    else onStatus("Searching… put the glasses in pairing mode.")
}

private fun onBonded(d: BluetoothDevice, name: String, onStatus: (String) -> Unit) {
    stopFinding(); onStatus("Paired with $name."); connect(d.address, name)
}

@SuppressLint("MissingPermission")
fun stopFinding() {
    finder?.let { runCatching { app.unregisterReceiver(it) } }; finder = null
    runCatching { adapter?.cancelDiscovery() }
}
```
Notes: `createBond()` on a record that came from BR/EDR inquiry bonds over BR/EDR. The
transport-specific `createBond(int)` is hidden, which is why the LE-only hits are filtered.
Discovery runs about 12 s of inquiry plus LE scanning, so call `stopFinding()` in `onPause`. Add
a "Find my glasses" button to the empty state (`GlassesScreen.kt:128`) and remove the
"connect them once in LensMoo" wording.

### C4 — Do **not** add a BLE data path. Optional debug-only GATT probe. Priority 3.
Keep BLE out of the product path. To settle F1 on the owner's own unit, a hidden debug button
can list the GATT services over **TRANSPORT_LE** and nothing else. Without that flag, a
dual-mode peer may be reached over BR/EDR ATT, which would muddy the answer.
```kotlin
@SuppressLint("MissingPermission")
fun probeGatt(address: String) {
    val dev = adapter?.getRemoteDevice(address) ?: return
    var gatt: BluetoothGatt? = null
    gatt = dev.connectGatt(app, false, object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, s: Int, st: Int) {
            addLog("LE GATT state=$st status=$s")
            if (st == BluetoothProfile.STATE_CONNECTED) g.discoverServices() else g.close()
        }
        override fun onServicesDiscovered(g: BluetoothGatt, s: Int) {
            g.services.forEach { addLog("LE service ${it.uuid}") }; g.disconnect()
        }
    }, BluetoothDevice.TRANSPORT_LE)
    main.postDelayed({ runCatching { gatt?.disconnect(); gatt?.close() } }, 20_000)
}
```
Run it only while SPP is **disconnected**, because F2 suggests the glasses may advertise only
then. It reads no characteristics, so it should not trigger a pairing pop-up.

### D — On-phone check: did LensMoo use BLE? (settles F8 without a PC). Priority 2.
The app already reads the owner's HCI snoop bug report (KeyFinder). The parser below counts LE
scans, LE connections and ATT packets in the same btsnoop file. Feed it the zip entry whose name
contains `btsnoop`.
```kotlin
object BleAudit {
    data class Report(val leScans: Int, val leConnAttempts: Int, val lePeers: Set<String>,
                      val attOnLe: Int, val classicAcl: Int)
    fun audit(input: java.io.InputStream): Report {
        val d = java.io.DataInputStream(java.io.BufferedInputStream(input, 1 shl 16))
        val hdr = ByteArray(16); d.readFully(hdr)
        require(String(hdr, 0, 7, Charsets.US_ASCII) == "btsnoop") { "Not a btsnoop log" }
        var scans = 0; var conns = 0; var att = 0; var acl = 0
        val peers = linkedSetOf<String>(); val le = HashSet<Int>(); val rec = ByteArray(16)
        while (true) {
            try { d.readInt() } catch (e: java.io.EOFException) { break }
            val n = d.readInt(); d.readFully(rec)            // flags, drops, timestamp
            if (n !in 1..70_000) break
            val p = ByteArray(n); d.readFully(p)
            fun u16(i: Int) = (p[i].toInt() and 0xFF) or ((p[i + 1].toInt() and 0xFF) shl 8)
            when (p[0].toInt()) {
                1 -> when (u16(1)) {                          // HCI command
                    0x200C, 0x2042 -> if (n > 4 && p[4].toInt() == 1) scans++
                    0x200D, 0x2043 -> conns++
                }
                4 -> if (n > 3 && (p[1].toInt() and 0xFF) == 0x3E && n >= 15) {   // LE meta event
                    val sub = p[3].toInt()
                    if ((sub == 0x01 || sub == 0x0A) && p[4].toInt() == 0) {
                        le += u16(5) and 0x0FFF
                        peers += (14 downTo 9).joinToString(":") { "%02X".format(p[it]) }
                    }
                } else if (n >= 6 && (p[1].toInt() and 0xFF) == 0x05) le -= u16(4) and 0x0FFF
                2 -> if (n >= 9) {                           // ACL; CID 0x0004 = ATT on LE
                    val h = u16(1) and 0x0FFF
                    if (h in le) { if (u16(7) == 0x0004) att++ } else acl++
                }
            }
        }
        return Report(scans, conns, peers, att, acl)
    }
}
```
How to read the result: `leConnAttempts == 0` and `attOnLe == 0` with a large `classicAcl`
means LensMoo used Classic only, so BLE is not needed. Any `lePeers` entry whose last 4 hex
digits match the glasses name means LensMoo did talk GATT to the glasses. Report the peer and
the ATT count to the Theoretical Physicist before anyone builds on it.

### E — Auto-reconnect trigger without BLE (cross-reference for the reconnect specialist)
The glasses reconnect HFP/A2DP to the phone on power-up. That raises
`BluetoothDevice.ACTION_ACL_CONNECTED` for the saved address, which is the Classic "wake"
signal; no LE presence scan is needed. Register the receiver at runtime while the app or its
service is alive. On API 33+, ignore events where
`getIntExtra(BluetoothDevice.EXTRA_TRANSPORT, -1) == BluetoothDevice.TRANSPORT_LE`
(confidence Medium on that extra being public).

## 4. On-device checks (phone only)

1. **Bond records.** After C1, add a debug log line per bonded device:
   `"${d.name} ${d.address} type=${d.type} uuids=${d.uuids?.joinToString()}"`. For the glasses,
   expect `type=3` (DUAL) or `1` (CLASSIC) with `00001101-…` (SPP) in the list. Two entries
   with the same name means separate LE and BR/EDR addresses (F9). Note both.
2. **HID side.** Settings › Connections › Bluetooth › gear icon next to the glasses. If an
   "Input device" toggle appears, the HID side is bonded. Turn it off if the on-screen keyboard
   ever disappears while the glasses are connected (F10).
3. **Did LensMoo use BLE?** Turn on HCI snoop, unpair the glasses, then pair and connect once in
   LensMoo and run the AI voice feature once. Take a bug report and run Change D on it. Turn off
   your watch and earbuds first so their LE traffic doesn't pollute the counts.
4. **In-app pairing (after C3).** Forget the glasses in Settings. Tap *Find my glasses*, then
   long-press the glasses' power button for pairing mode (per the W600 manual pattern, unverified
   for P3). Confirm the PIN pop-up, then check that it reaches *Connected* and a photo transfers.
5. **GATT probe (C4, optional).** With SPP disconnected, run the probe and read the log.
   Services listed means F1 HARDWARE.md is right. Status 133 or a timeout means VALIDATION.md is
   right. Either way, no app change follows.
6. **Permissions.** On Android 12–15, the app should ask only for "Nearby devices" (no location
   prompt). On Android 11, it should ask for location and need Location turned on to search.

## 5. Confidence summary

- BLE not needed for bind, photo, battery, settings, voice or audio: **High** (F3–F6).
- LensMoo's first-discovery scan transport: **Unknown** until check 3. It doesn't block the
  replacement, because C3 pairs over BR/EDR regardless.
- C1/C2 prevent a real failure mode on dual-mode units: **Medium** likelihood, **High** benefit,
  near-zero risk.
- C3 gives pairing parity with LensMoo: **High** for correctness of the framework calls;
  **Medium** on `createBond()` choosing BR/EDR for a DUAL record from inquiry.
