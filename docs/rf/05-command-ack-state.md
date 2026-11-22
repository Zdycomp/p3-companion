# 05 — Request / ACK state machine (RF specialist 05 of 10)

Scope: which device frames get a `0004` ACK, TX/RX counters, `cmd_order`, `requestId` (pk_id),
reply matching, timeouts/retries, serializing concurrent work, and the bind sequence
`0001`/`0002`/`102E` incl. how to see a rejected key. SDK paths below are relative to
`/home/claude/beneduzi/xk-one-pro-sdk/`. App paths relative to
`app/src/main/java/com/zdycomp/p3companion/`.

## 1. Findings (validated sources)

| # | Finding | Source | Conf. |
|---|---|---|---|
| F1 | ACK = head `0x30`, cmd `0x0004`, payload `[0x01, rx cmd_order]`; target is the **received** order, TX and RX counters are independent. | docs/PROTOCOL.md:177-180, docs/VALIDATION.md:107, docs/NODES.md:63, python/xkglasses/protocol.py:141-148 | High |
| F2 | Vendor capture: app TX `cmd_order` is ONE counter shared by every frame it sends (requests, ACKs, `0x2B` frames), **+1 per frame** (0x6C,6D,6E,6F,70,71,73...). "+2 per request" in the docs is just request + the ACK of its reply. | python/xkglasses/protocol.py:26-86 (decoded), docs/PROTOCOL.md:19 | High |
| F3 | Device RX orders acked in that capture: 06,08,0B,0E,10,13,16,1A,1C,1F,20,21,23,25. Gaps of 2 = device sends its own `0x8004` ACK + a data reply per request; the app ACKs only the data frame. **Never ACK a device ACK.** | protocol.py:29-86 | High |
| F4 | Device data frames that must be ACKed: every `0x30` reply and push (`57B0` reply, `57B1`, `7320`, `7300` reply, `C101`/`C107`, query replies). Media: `0x4A` cmd `0x0009` payload `[01]` only on fragment type 3; type 0 needs none. | PROTOCOL.md:150-170, 191-194; client.py:116-136, 343 | High (pushes) / Med (replies: vendor does it, harness proves device tolerates fixed/odd ACK orders) |
| F5 | If the host does not ACK `7320` **and** download, or abort with `7500`, the glasses stay blocked (button photos stop working). | docs/NODES.md:95-97 | High |
| F6 | Device does not validate app `cmd_order` monotonicity: the working Python client reuses fixed orders (0x24, 0x26, 0x27, 0x28...). | client.py:124,136,343; protocol.py:175-203 | High |
| F7 | `pk_id` (requestId, payload[0..1] LE) **appears to be echoed**: captured `1003` reply starts `a6 00` = request pk 0xA6 in the same capture. But a `57A0` reply carries `1e 00` vs request 0xB3, so echo is unproven. | python/tests/test_codec.py:306 vs protocol.py:50; test_codec.py:269 vs protocol.py:70 | Med/Low |
| F8 | Reply layout after node: `[type:1][len:2 **LE**][data]` (request len is BE). type 4 = `FMT_ERRCODE`, data[0] = ErrorCode: 0 OK, 1 FAIL, 2 NODATA, 3 INVALID_PARAM, 4 INVALID_URN, 5 INVALID_DATA. | docs/VALIDATION.md:349-362, docs/PAYLOADS.md:26-50 | High |
| F9 | "Device answers most nodes **once per session**"; some only inside the setup order. Yet `1001` answered twice in one session (setup + after 240 s idle). So repeat reads may silently time out. | docs/NODES.md:102-104, VALIDATION.md:420, 370-376 | Med |
| F10 | No keepalive needed; 240 s silence fine. | PROTOCOL.md:291-304 | High |
| F11 | Bind: `0001` (reqId 1, 61-char token) and `0002` (reqId 4, 64 B) content is not checked; frames + exact len are required. Validated harness waits 0.2 s + poll + 0.1 s per bind frame, then **1.5 s**, then setup at 150 ms spacing (`102E` is 3rd → ~1.8-2.1 s after `0002`). Vendor capture: `102E` reply ACKed ~36 ms after send. | client.py:193-208, 218-220; protocol.py:29-36; BONDED_ENROLLMENT.md:31-51 | High (timing worked) |
| F12 | Rejected key shows as `0x0401...` = `[type 04][len 01 00][code]` on later commands (e.g. `57B0`), or a dropped link for a bad len. Docs never show the `102E` reply itself for a bad key; "the device does not expose an explicit bind-accepted reply". | REVERSE_ENGINEERING.md:29,38-40; client.py:194-199; NODES.md:78-80 | Med |
| F13 | `0x2B` frames are sent with cmd `0x0004` too (payload `01 00 <seq> ...`), so "cmd==4 means ACK" is only true on head `0x30`. | protocol.py:38,46 | High |

## 2. Defects in the current app

| # | Where | Defect | Impact | Conf. |
|---|---|---|---|---|
| D1 | XkGlasses.kt:281-299 | `7500` is sent only on success. Any timeout/assembler error leaves the glasses mid-transfer → blocked (F5) until power cycle. | Photo + button dead after one hiccup | High |
| D2 | XkGlasses.kt:403-423 | Button `7320` with `saveButtonPhotos == false` is ACKed but never downloaded or aborted (`7500`). | Glasses blocked after first button photo | High |
| D3 | XkGlasses.kt:237 | `57B0` reply ignored. A `FMT_ERRCODE` (e.g. key rejected → 0x0401) still waits 20 s and reports "didn't report a new photo". | Wrong error, slow | High |
| D4 | XkGlasses.kt:168-171 | `102E` sent 600 ms after `0002` without waiting for its reply; validated timing is ≥1.5 s (F11). | Possible flaky bind | Med |
| D5 | XkGlasses.kt:171-175 | Bind check only rejects `type==4 && code!=0`; a 6 s timeout is a hard fail even though a successful 102E may not reply (F12). Raw reply never logged, so we cannot learn the accept/reject shape. | False "didn't answer"; no evidence | Med |
| D6 | XkGlasses.kt:349-356, 423 | Replies matched by **node only**; a late reply (after timeout) for `7300` index i is accepted as the reply for i+1. No requestId check. | Corrupt/misaligned photo parts | Med |
| D7 | XkGlasses.kt:188-194, 355, 292 | Link loss clears queues but blocked `poll`s keep waiting up to 20 s; queued reconnect sits behind them on the single `io` thread. | 20 s freeze after drop | High |
| D8 | XkGlasses.kt:200-227, 247, 276 | Refresh after every action sends `1003`+`1001`+`5713` at 3 s each; if "once per session" applies (F9) the `io` thread is blocked up to 9 s and user taps queue behind it. | Laggy taps, button photo delayed | Med |
| D9 | XkGlasses.kt:230-232, 253-255 | `state == READY` checked on the caller thread, BUSY set later on `io` → double tap queues two photos. | Duplicate capture | High |
| D10 | XkProtocol.kt:55 | `isAck` ignores head: a device `0x2B` frame (cmd 0x8004) is treated as an ACK (F13). | Latent (no 2B today) | High |
| D11 | XkGlasses.kt:262-267 | ErrorCode text wrong: 2 is NODATA (not "busy"); 3 INVALID_PARAM is what 5780 returns (VALIDATION.md:425). | Misleading message | High |
| D12 | XkGlasses.kt:388-424 | No duplicate guard: a retransmitted push (same RX order) re-triggers handling (e.g. second button download). | Rare double work | Low |

What the app already does right: one shared TX counter +1 per frame incl. ACKs (XkGlasses.kt:337, matches F2);
ACK target = `f.order` (XkGlasses.kt:399, F1); never ACKs device ACKs (XkGlasses.kt:398); media ACK only on
fragment 3 and always, even without an assembler (XkGlasses.kt:394); requests serialized on one executor.

## 3. Recommended changes (framework-only Kotlin)

### C1 (D10) head-aware ACK test + requestId accessor — XkProtocol.kt:55
```kotlin
val isAck get() = (head == HEAD_CONTROL && operation == 0x0004) || (head == HEAD_MEDIA && operation == 0x0009)
/** pk_id at payload[0..1] LE; the device seems to echo it in replies (unproven). */
val requestId: Int get() = if (head == HEAD_CONTROL && payload.size >= 2)
    (payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8) else -1
/** Reply status: null unless type==FMT_ERRCODE, then the ErrorCode byte. */
val errCode: Int? get() = if (replyType == 4 && payload.size > 17) payload[17].toInt() and 0xFF else null
```

### C2 (D6, D7) pending table matched by requestId, node fallback, link-down sentinel — XkGlasses.kt
Replace `waiting`/`request()`/`await()`:
```kotlin
private class Pending(val node: String, val reqId: Int) { val q = LinkedBlockingQueue<Xk.Frame>() }
private val pending = ConcurrentHashMap<String, Pending>()           // one outstanding per node (io is serial)
private val pushes = ConcurrentHashMap<String, LinkedBlockingQueue<Xk.Frame>>() // 57B1, 7320, 5790...
private val staleIds = java.util.Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
private val LINK_DOWN = Xk.Frame(0, 0, 0, 0, ByteArray(0))

private fun request(node: String, action: Int, data: ByteArray, timeoutMs: Long, logData: Boolean = true): Xk.Frame? {
    val rid = nextRequestId()
    val p = Pending(node, rid).also { pending[node] = it }
    try {
        send(Xk.control(nextOrder(), node, action, rid, data), logIt = false)
        addLog("→ $node rid=%04X".format(rid) + if (logData && data.isNotEmpty()) " " + hex(data) else "")
        val f = p.q.poll(timeoutMs, TimeUnit.MILLISECONDS)
        if (f === LINK_DOWN) error("Lost the connection to the glasses.")
        if (f == null) { staleIds += rid; addLog("✕ $node timeout ${timeoutMs}ms") }
        return f
    } finally { pending.remove(node, p) }
}

private fun awaitPush(node: String, timeoutMs: Long): Xk.Frame? {
    val f = pushes.getOrPut(node) { LinkedBlockingQueue() }.poll(timeoutMs, TimeUnit.MILLISECONDS)
    if (f === LINK_DOWN) error("Lost the connection to the glasses.")
    return f
}
```
In `handle()` replace line 423 with:
```kotlin
val p = pending[node]
when {
    p != null && f.requestId == p.reqId -> p.q.offer(f)
    p != null && f.requestId in staleIds -> addLog("drop stale $node rid=%04X".format(f.requestId))
    p != null -> { addLog("$node rid mismatch %04X≠%04X".format(f.requestId, p.reqId)); p.q.offer(f) }
    else -> pushes.getOrPut(node) { LinkedBlockingQueue() }.offer(f)
}
```
(The "rid mismatch" log line settles F7 on the owner's unit; if it never appears, tighten to exact match.)
In `closeSocket()` (line 193) wake every waiter instead of clearing:
```kotlin
pending.values.forEach { it.q.offer(LINK_DOWN) }
pushes.values.forEach { it.clear(); it.offer(LINK_DOWN) }
mediaDone.offer(false)   // download() must treat false as link down
```
and in `connect()` before `openSocket` call `pushes.clear(); staleIds.clear(); mediaDone.clear()`.
`takePhoto()` line 235/238: `pushes.getOrPut("7320"){LinkedBlockingQueue()}.clear()` and `awaitPush("7320", 20_000)`.

### C3 (D1) always close the transfer — XkGlasses.kt:281-299
```kotlin
private fun download(count: Int): Uri {
    val asm = Xk.PhotoAssembler(count); assembler = asm
    var ok = false
    try {
        for (i in 1..count) {
            busyText = "Downloading photo ${i * 100 / count}%"; changed()
            fetchElement(asm, i)                     // C6: one retry inside
        }
        ok = true
    } finally {
        assembler = null
        // NODES.md:95-97 — finish or abort, otherwise the glasses stay blocked.
        runCatching { request("7500", Xk.ACTION_EXECUTE, ByteArray(0), 2_000) }
        if (!ok) addLog("Transfer aborted with 7500")
    }
    return saveJpeg(asm.jpeg())
}
```

### C4 (D2) never leave a button photo hanging — XkGlasses.kt:403
```kotlin
"7320" -> if (!capturing) {
    if (prefs.saveButtonPhotos) { /* existing download block */ }
    else io.execute { runCatching { request("7500", Xk.ACTION_EXECUTE, ByteArray(0), 2_000) } }
    return
}
```

### C5 (D3, D5, D4) bind timing + rejection detection — XkGlasses.kt:164-177, 237
```kotlin
private fun bind(userKey: String) {
    val token = (1..61).map { TOKEN_CHARS.random() }.joinToString("").toByteArray(Charsets.US_ASCII)
    val blob = ByteArray(64).also { java.util.Random().nextBytes(it) }
    // 0001/0002 use the captured request ids 1 and 4. Wait for each reply, but never less than
    // the validated harness timing (~1.5 s before 102E).
    val t0 = System.currentTimeMillis()
    requestFixed("0001", 1, token, 1_000)
    requestFixed("0002", 4, blob, 1_000)
    val wait = 1_500 - (System.currentTimeMillis() - t0)
    if (wait > 0) Thread.sleep(wait)
    val r = request("102E", Xk.ACTION_EXECUTE, Xk.userBindData(userKey), 4_000, logData = false)
    addLog("102E reply: " + (r?.let { "type=${it.replyType} data=${hex(it.data.take(8).toByteArray())}" } ?: "none"))
    if (r?.errCode != null && r.errCode != 0) error("The glasses refused this key (code ${r.errCode}). Check it in Set up.")
    bindVerified = r != null   // null → proceed; confirmed or refused at first 57B0 (below)
}
```
`requestFixed` = `request()` with a caller-supplied rid (add an optional `rid: Int? = null` param, action EXECUTE).
Declare `@Volatile private var bindVerified = false` and reset it in `connect()`.
The 102E reply never contains the key, so logging type+first 8 data bytes is safe.
At XkGlasses.kt:237:
```kotlin
val r = request("57B0", Xk.ACTION_EXECUTE, ByteArray(0), 4_000)
r?.errCode?.takeIf { it != 0 }?.let {
    error(if (!bindVerified || it == 1) "the glasses didn't accept your key (code $it). Re-check it in Set up."
          else "the glasses refused the photo (code $it).")
}
```

### C6 retry policy (D6 follow-up) — only idempotent requests
- Retry once: `7300` (same index, new rid), `1003`/`1001`/`5713` reads. Never auto-retry `57B0`
  (second shutter), `5780` (toggles recording), `102E`, `0001`/`0002` (reconnect instead).
```kotlin
private fun fetchElement(asm: Xk.PhotoAssembler, i: Int) {
    repeat(2) { attempt ->
        mediaDone.clear(); asm.start(i)
        val r = request("7300", Xk.ACTION_WRITE, byteArrayOf(i.toByte()), 6_000)
        if (r?.errCode != null && r.errCode != 0) error("part $i refused (code ${r.errCode})")
        if (r != null) when (mediaDone.poll(12, TimeUnit.SECONDS)) {
            true -> return
            false -> error("Lost the connection to the glasses.")
            null -> {}
        }
        addLog("part $i retry ${attempt + 1}")
    }
    error("Part $i never arrived.")
}
```

### C7 (D8, D9) priority + tap guard
```kotlin
private val busy = java.util.concurrent.atomic.AtomicBoolean(false)
private val battOnly = java.util.concurrent.atomic.AtomicBoolean(false)
fun takePhoto() { if (state != State.READY || !busy.compareAndSet(false, true)) return
    setBusy("Taking a photo…"); io.execute { try { /* body */ } finally { busy.set(false) } } }
```
Same guard in `setRecording`. Telemetry: timeouts 3 000 → 1 500 ms; after a photo/video only send `1003`
(battery); send `1001` once per connection (static info) and `5713` only on the Refresh button.
Parse `1003`/`5790` inside `handle()` for any arrival (as client.py does), so unsolicited pushes update the UI.

### C8 (D11) ErrorCode text — XkGlasses.kt:263-266
`1 → "the glasses said it failed"`, `2 → "nothing to do (no data)"`, `3 → "these glasses don't accept
that command from the app (invalid parameter)"`, `4 → "these glasses don't support this"`, else `code $code`.

### C9 (D12) duplicate guard — top of HEAD_CONTROL branch in `handle()`
```kotlin
val key = (f.order shl 16) or f.requestId.and(0xFFFF)
if (key == lastRxKey && SystemClock.elapsedRealtime() - lastRxAt < 1_500) { send(Xk.ack(nextOrder(), f.order), false); return }
lastRxKey = key; lastRxAt = SystemClock.elapsedRealtime()
```
(Re-ACK the duplicate, do not re-handle. Needs `private var lastRxKey = -1; private var lastRxAt = 0L` and `import android.os.SystemClock`.)

## 4. On-device checks for the owner (Samsung, no PC needed)
1. **Bind evidence**: Connect, open the glasses log, share it. Look for `102E reply: type=… data=…`.
   Note it; if it ever says `type=4 data=0x..` with a non-zero code, the key is refused.
2. **Request-id echo**: in the same log count `rid mismatch` lines. Zero after a photo = device echoes
   pk_id (raise F7 to High; switch C2 to exact-match only).
3. **Abort path (C3)**: start Take photo, turn Bluetooth off mid-download, turn it on, reconnect,
   press the glasses' photo button once. It must take/prompt normally (not "please connect").
4. **Button with saving off (C4)**: Settings → turn off "save button photos", press the button twice
   ~5 s apart. Both presses should give the shutter sound; the log shows `→ 7500` after each.
5. **Responsiveness (C7)**: tap Refresh 3 times then Take photo; the photo must start within ~2 s,
   and double-tapping Take photo must give exactly one picture.
6. **Bind timing (C5)**: Disconnect/Connect 10 times in a row; all 10 should reach "Connected".

## 5. Confidence summary
C3/C4 High (documented blocking). C2 link-down sentinel High; requestId matching Medium (echo
partly evidenced). C5 timing Medium-High (copies validated harness); 102E reply shape Low-Medium
(undocumented) — hence the logging. C6/C7/C8/C9 Medium/High/High/Low.
