# 02 — RFCOMM / SPP socket establishment

Specialist 02 of 10. Scope: how `XkGlasses.openSocket()` opens, bounds, retries and closes the
RFCOMM link to the glasses (SPP, channel 8). All line numbers refer to
`app/src/main/java/com/zdycomp/p3companion/XkGlasses.kt` unless named otherwise.

## 1. Findings (sources)

| # | Finding | Source | Conf. |
|---|---|---|---|
| F1 | The control link is RFCOMM channel 8. Channel 1 is HFP (AT commands) and must not be used. | xk-one-pro-sdk `docs/HARDWARE.md:35-36`, `docs/PROTOCOL.md:9-10` | High |
| F2 | The vendor app tries the secure SDP socket (UUID 0x1101) first, then the insecure one, then the reflected channel-8 socket. The SDK says to prefer the secure path. | `docs/BONDED_ENROLLMENT.md:171-172` | High |
| F3 | On Linux a plain channel-8 socket to the bonded glasses works with no extra security settings. So channel 8 is reachable without an SDP lookup. | `docs/BONDED_ENROLLMENT.md:169-170`, `python/xkglasses/client.py:89-95` | High |
| F4 | The SDK's own Android client only falls back to channel 8 when *creating* the socket throws, not when `connect()` fails. In practice it has no real fallback, and it never bounds `connect()`. Do not copy that pattern. | `flutter_example/.../spp/XkSppClient.kt:55-65` | High |
| F5 | No keepalive is needed: the link stayed up after 240 s of silence. A dropped link therefore means a real loss (power, range, firmware drop), not idling. | `docs/PROTOCOL.md:289-306`, `docs/VALIDATION.md:366-381` | High |
| F6 | A malformed control frame (wrong `len`) makes the firmware drop the RFCOMM link. Reconnecting after that is correct behaviour, but it must not loop. | `docs/PROTOCOL.md:52-53` | High |
| F7 | `BluetoothSocket.connect()` has no timeout. It blocks through the page, SDP and RFCOMM setup. On failure the framework throws the generic `read failed, socket might closed or timeout, read ret: -1`. That text is raised whenever the stack closes the socket pair before it reports a channel, so it covers SDP miss, page timeout, a refused or busy channel, security failure and a close() from another thread. The cause has to be worked out from how long it took and which attempt failed. | AOSP `BluetoothSocket.connect()`/`readAll()` behaviour (general platform knowledge) | High |
| F8 | `BluetoothSocket.close()` from another thread is the only supported way to abort a blocking `connect()`. A socket whose `connect()` failed still holds a file descriptor until `close()`. | Android `BluetoothSocket` reference (platform knowledge) | High |
| F9 | Timing tells the causes apart. Glasses off or out of range: the page times out after about 5 s per attempt, and up to about 12 s with SDP. Glasses on but channel held by another client (LensMoo, or a second phone): a fast refusal, usually under 2 s. | Platform behaviour. Heuristic, verify with check C3. | Medium |
| F10 | Samsung (One UI): in the first 1–3 s after the glasses power on, the phone is busy auto-connecting HFP/A2DP, and RFCOMM attempts often fail with the read ret -1 error. A retry after 0.6–1.5 s usually succeeds. An immediate retry right after an aborted attempt often fails because the stack is still finishing the old page. | Field-known Samsung behaviour | Medium |
| F11 | `cancelDiscovery()` needs `BLUETOOTH_SCAN` on Android 12+. The manifest declares only `BLUETOOTH_CONNECT` (`AndroidManifest.xml:5-7`), so line 132 throws a `SecurityException` that is silently swallowed. The impact is low because this app never starts discovery. | `AndroidManifest.xml:5-7`, `XkGlasses.kt:132` | High (fact) / Low (impact) |
| F12 | Reflected `createRfcommSocket(int)` and `createInsecureRfcommSocket(int)` are non-SDK (greylisted, "unsupported") APIs. They are reachable at target 35 today, but they can disappear. Always keep the public SDP path in the list. | Android non-SDK interface lists | Medium |
| F13 | The device answers most nodes once per session (`docs/NODES.md:102`). That is relevant to `refreshNow()` (lines 200-227), but it belongs to the protocol topic. Flagged for the read/reply specialist. | `docs/NODES.md:102-105` | Medium |

## 2. Defects in the current code

| # | Where | Defect | Impact | Conf. |
|---|---|---|---|---|
| D1 | 147 | `s.connect()` is unbounded. Three attempts in a row can block the single `io` thread for 30–60 s with the glasses off. Every queued action (`refresh`, `takePhoto`) waits behind it. | Owner sees "Connecting…" for up to a minute | High |
| D2 | 146-156 | A socket whose `connect()` threw is never closed. It leaks a file descriptor and stack slot per failed attempt, which makes later attempts more likely to fail. | Gets worse with each retry | High |
| D3 | 148, 82, 181, 188-190 | The Cancel button (`GlassesScreen.kt:82` → `disconnect()`) cannot abort a connect in flight, because `socket` is only assigned *after* `connect()` returns. The `io` thread then carries on: it binds and sets `READY` after the user cancelled. | Cancel does nothing; a "ghost" connect follows | High |
| D4 | 92-93, 105, 129 | `connect()` only guards `CONNECTING`. After Cancel, or while `READY`/`BUSY`, a second `connect()` queues a second `openSocket()`, which overwrites `socket` without closing the first link. The app then holds channel 8 twice: it contends with itself, and the old reader thread is orphaned. | Self-inflicted channel-busy errors | High |
| D5 | 135-142 | The attempt order is secure SDP → insecure SDP → channel 8. When SDP is the problem, the insecure SDP attempt fails the same way and adds a full timeout before the attempt that works. The winning method is not remembered, so every connect pays again. | Slow connects; worst on units with a bad SDP record | Medium |
| D6 | 144-157 | No pause between attempts, so it collides with the old page and HFP/A2DP setup (F10). | Spurious failures on Samsung | Medium |
| D7 | 133-134 | No bond check. On an unpaired device the secure attempt pops a pairing dialog, which blocks with no bound, and the error text is misleading. | Confusing first run | High |
| D8 | 374-376, 188-190 | Race: `closeSocket()` closes first and nulls `socket` second. The reader wakes in between, sees `socket != null`, and runs the "Link lost" path. When the close came from a failed `bind()` (line 114, `userDisconnect=false`), that path schedules auto-reconnect (line 381-382). A wrong key can then loop every 4 s, each loop re-sending a bind. The reader also compares against the *field*, so a reader from an old socket can tear down a *new* link. | Reconnect loops; new link killed by an old reader | High |
| D9 | 65-67 | `socket`/`out` are written on the io thread, the main thread (`disconnect`) and the reader thread, but are not `@Volatile` and have no lock. | Visibility races | High |
| D10 | 139-140, 155 | A reflection failure arrives as `InvocationTargetException` with a null message, which logs "channel 8 failed: null". The final error (158-161) shows only the *last* failure. | Unhelpful logs | High |
| D11 | 158-161 | The same message is shown for "glasses off" and "LensMoo holds the channel". The timing evidence (F9) that tells them apart is thrown away. | Owner can't self-diagnose | Medium |

## 3. Recommended changes (Code Specialist)

Imports to add: `android.os.SystemClock`, `java.io.IOException`,
`java.util.concurrent.atomic.AtomicBoolean`.

### 3a. Fields (replace lines 65-66, add below line 75)

```kotlin
private val linkLock = Any()
@Volatile private var socket: BluetoothSocket? = null
@Volatile private var out: OutputStream? = null
@Volatile private var pending: BluetoothSocket? = null      // socket whose connect() is in flight
@Volatile private var session = 0                            // bumped by every connect()/disconnect()
private val watchdog = Executors.newSingleThreadScheduledExecutor()
```

`Prefs.kt`: add `var linkMethod: String?` (key `"link_method"`), in the same style as `glassesName`.

### 3b. connect() / disconnect() (replace 92-121 and 179-186)

```kotlin
fun connect(address: String, name: String?) {
    if (state == State.CONNECTING) return
    val key = prefs.userKey ?: run { lastError = "Add your glasses key first (see Set up below)."; changed(); return }
    prefs.glassesAddress = address
    if (name != null) prefs.glassesName = name
    userDisconnect = false; lastError = null; state = State.CONNECTING; changed()
    val mine = ++session                       // main thread only
    io.execute {
        try {
            openSocket(address, mine)
            bind(key)
            if (mine != session) { closeSocket(); return@execute }
            state = State.READY; lastMessage = "Connected."; changed()
            refreshNow()
        } catch (e: Exception) {
            if (mine != session) return@execute          // cancelled: disconnect() already reset state
            closeSocket()
            state = State.IDLE
            lastError = e.message ?: "Couldn't connect."
            addLog("Connect failed: $lastError"); changed()
        }
    }
}

fun disconnect() {
    session++
    userDisconnect = true
    runCatching { pending?.close() }           // aborts a connect() that is still blocking
    closeSocket()
    state = State.IDLE; recording = false
    addLog("Disconnected"); changed()
}

private fun closeSocket() {
    val s = synchronized(linkLock) { socket.also { socket = null; out = null } }
    runCatching { s?.close() }
    reader = null
    waiting.values.forEach { it.clear() }
}
```

### 3c. openSocket() (replace 129-162)

```kotlin
private class Attempt(val id: String, val timeoutMs: Long, val make: (BluetoothDevice) -> BluetoothSocket)

private fun reflect(d: BluetoothDevice, method: String, ch: Int): BluetoothSocket = try {
    d.javaClass.getMethod(method, Int::class.javaPrimitiveType).invoke(d, ch) as BluetoothSocket
} catch (e: java.lang.reflect.InvocationTargetException) { throw (e.cause ?: e) }

private val attempts = listOf(
    Attempt("sdp-secure", 12_000) { it.createRfcommSocketToServiceRecord(SPP) },       // vendor's first choice
    Attempt("ch8-secure", 8_000) { reflect(it, "createRfcommSocket", 8) },               // skips SDP
    Attempt("ch8-insecure", 8_000) { reflect(it, "createInsecureRfcommSocket", 8) },
    Attempt("sdp-insecure", 12_000) { it.createInsecureRfcommSocketToServiceRecord(SPP) },
)

private fun openSocket(address: String, mine: Int) {
    val a = adapter ?: error("This phone has no Bluetooth.")
    if (!a.isEnabled) error("Bluetooth is off.")
    runCatching { a.cancelDiscovery() }                    // effective only with BLUETOOTH_SCAN (see 3f)
    val device = a.getRemoteDevice(address)
    if (device.bondState != BluetoothDevice.BOND_BONDED)
        error("These glasses aren't paired with this phone. Pair them in Bluetooth settings, then connect.")
    closeSocket()                                          // never hold two links to channel 8
    addLog("Opening link to ${device.name ?: address}")
    val order = attempts.sortedByDescending { it.id == prefs.linkMethod }   // last winner first
    val fails = mutableListOf<String>()
    var fast = 0; var slow = 0
    for ((i, at) in order.withIndex()) {
        if (mine != session) error("Cancelled.")
        if (i > 0) Thread.sleep(if (fails.last().contains("timed out")) 1_500 else 700)
        val s = try { at.make(device) } catch (e: Exception) {
            fails += "${at.id}: unavailable (${e.javaClass.simpleName})"; continue
        }
        val t0 = SystemClock.elapsedRealtime()
        try {
            connectBounded(s, at.timeoutMs)
        } catch (e: IOException) {
            val ms = SystemClock.elapsedRealtime() - t0
            if (ms < 2_000) fast++ else slow++
            fails += "${at.id}: ${e.message} ($ms ms)"
            addLog("Link ${at.id} failed in $ms ms: ${e.message}")
            continue
        }
        if (mine != session) { runCatching { s.close() }; error("Cancelled.") }
        synchronized(linkLock) { socket = s; out = s.outputStream }
        startReader(s, s.inputStream)
        prefs.linkMethod = at.id
        addLog("Link open (${at.id}) in ${SystemClock.elapsedRealtime() - t0} ms")
        return
    }
    throw IllegalStateException(
        if (fast >= 2 && slow == 0)
            "The glasses are on but refused the control link. LensMoo (or another phone) is probably " +
                "connected to them. Force-stop LensMoo, then try again."
        else
            "The glasses didn't answer. Make sure they're on, close by, and paired in Bluetooth settings."
    ).also { addLog("All link attempts failed: ${fails.joinToString("; ")}") }
}

/** connect() with a hard time limit. close() from another thread is the only way to abort it. */
private fun connectBounded(s: BluetoothSocket, timeoutMs: Long) {
    val finished = AtomicBoolean(false)
    val timedOut = AtomicBoolean(false)
    pending = s
    val task = watchdog.schedule({
        if (finished.compareAndSet(false, true)) { timedOut.set(true); runCatching { s.close() } }
    }, timeoutMs, TimeUnit.MILLISECONDS)
    try {
        s.connect()
        if (!finished.compareAndSet(false, true)) throw IOException("timed out")   // watchdog won the race
    } catch (e: IOException) {
        finished.set(true)
        runCatching { s.close() }                         // D2: always release a failed socket
        throw if (timedOut.get()) IOException("timed out after ${timeoutMs / 1000} s", e) else e
    } finally {
        task.cancel(false)
        pending = null
    }
}
```

### 3d. Reader owns its socket (replace 361-386)

```kotlin
private fun startReader(own: BluetoothSocket, input: InputStream) {
    reader = Thread({
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) parser.feed(buf, n).forEach { handle(it) }
            }
        } catch (_: Exception) { }
        // Only the reader of the *current* socket may declare the link lost (fixes D8).
        val wasCurrent = synchronized(linkLock) {
            (socket === own).also { if (it) { socket = null; out = null } }
        }
        runCatching { own.close() }
        if (!wasCurrent) return@Thread
        waiting.values.forEach { it.clear() }
        state = State.IDLE; recording = false
        addLog("Link lost")
        if (!userDisconnect) lastError = "Lost the connection to the glasses."
        changed()
        if (!userDisconnect && prefs.autoReconnect) main.postDelayed({ reconnectSaved() }, 4_000)
    }, "glasses-reader").apply { isDaemon = true; start() }
}
```

### 3e. `send()` (line 341)

The current `val o = out ?: error("Not connected.")` already behaves correctly once `out` is volatile. No change is needed.

### 3f. Optional, low impact: real `cancelDiscovery`

`AndroidManifest.xml`: add
`<uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation" />`.
In `MainActivity.kt:311`, request `BLUETOOTH_SCAN` alongside `BLUETOOTH_CONNECT`. Both are in the
same "Nearby devices" group, so the owner sees one dialog. Skip this if it complicates the
permission flow, because this app never starts discovery itself.

### 3g. Optional: shortcut to force-stop LensMoo

When the "refused the control link" error is shown, offer a button that runs
`startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:com.lensmoo.app")))`
(package name from `docs/BONDED_ENROLLMENT.md:83`). Wrap it in `runCatching`, since LensMoo may not be installed.

## 4. On-device checks (owner, Samsung phone)

Turn on "Show log" in Settings, then use Glasses → log after each check.

- **C1 – Normal connect.** Glasses on, LensMoo force-stopped. Tap Connect. Expect `Link open (<method>) in N ms`. Note which method won and N. Connect a second time: that method should be tried first and be faster.
- **C2 – Glasses off.** Tap Connect. Expect each attempt to fail after roughly 5–12 s with `timed out` or a slow `read failed`, and the whole thing to end in under about 45 s with the "didn't answer" message.
- **C3 – LensMoo holding the link.** Open LensMoo and let it connect to the glasses, then tap Connect in P3. Expect fast failures (under 2000 ms) and the "LensMoo… Force-stop" message. Force-stop LensMoo (Settings → Apps → LensMoo → Force stop) and connect again: it should succeed. This check validates the F9 heuristic. Send the log if the times don't match.
- **C4 – Cancel.** With the glasses off, tap Connect and then Cancel within 3 s. The state should go back at once, and no `Link open` or `Bound` line should appear later. Tap Connect again and it should work normally.
- **C5 – Wrong key.** Temporarily enter a wrong key and connect. Expect one "refused this key" error and no repeating `Link lost` / reconnect lines over the next 30 s.
- **C6 – Link loss.** While connected, power the glasses off. Expect one `Link lost`, one reconnect attempt after 4 s, and no crash.
- **C7 – Power-on race.** Power the glasses on and tap Connect within 2 s, while the phone is still connecting audio. With the 0.7 s spacing, a later attempt should succeed.

## 5. Confidence summary

The connect timeout (D1), the leaked failed sockets (D2), Cancel being unable to abort (D3), the
double link (D4) and the reader race (D8) are certain from the code: **high**. The attempt order
and remembering the winner (D5) is **medium**: it helps a lot on units with a bad SDP record and
costs nothing otherwise. The LensMoo-versus-off diagnosis by timing (D11/F9) is **medium**, and
check C3 confirms it. The Samsung retry spacing (F10) is **medium**. `BLUETOOTH_SCAN` (3f) is
**low** impact.
