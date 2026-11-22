# 06 — Photo / media transfer over SPP

Specialist 06 of 10. Scope: 57B0 → 57B1 → 7320 → 7300×N → 0x4A bursts → 4A0009 → 7500, element sizes,
throughput, per-element timeouts, loss handling, stored-photo download (7200/7310/7300/7400/7500/7600),
full-resolution originals. Reviewed `XkGlasses.takePhoto/download` and `Xk.PhotoAssembler`.
Sources: SDK = /home/claude/beneduzi/xk-one-pro-sdk, LF = /home/claude/joaosouz4dev/lensmoo-crash-fix.
App = app/src/main/java/com/zdycomp/p3companion/.

## 1. Findings (with sources)

| # | Finding | Source | Conf. |
|---|---|---|---|
| F1 | Pipeline: `57B0` (action 3) → device ACK + `57B0` reply → ~2.6 s processing → `57B1` push → `7320` push with element count N (1..20) → for i in 1..N: `7300` (action 2, data = 1-byte index) → `7300` reply → `0x4A` burst (divide 1/2/3, or a lone divide-0 frame) → host sends `4A0009` on divide 3 → after N: `7500` (action 3). Device then emits `5760`. | SDK docs/PROTOCOL.md:140-175, docs/NODES.md:52-57, :85 | High |
| F2 | Fragments 1/2/3 start with a 4-byte LE `cmd_idx` counting 0,1,2…; a lone divide-0 frame has no prefix and gets no `4A0009`. CRC covers 0x4A frames too; bad CRC = discard and resync, not abort. | SDK PROTOCOL.md:35, :190-194; python/xkglasses/reassembler.py:110-122 | High |
| F3 | Each reassembled element = `[media_type=0x00][total_len LE4]` + JPEG slice, `total_len` includes the 5 header bytes. Live capture: 6 elements of 617, 16389×4, 14414 B (≈16 KiB data per element). | SDK PROTOCOL.md:196-207, docs/PAYLOADS.md:99-111 | High |
| F4 | Result on the validated unit: one JPEG 640×480, ~74–84 KB, reproducible. No resolution/quality node exists; `C104` has no effect. | SDK docs/VALIDATION.md:33, :246-250; PROTOCOL.md:344-347 | High |
| F5 | If the host does not finish the transfer, "the glasses stay blocked waiting"; a host must always finish **or abort with `7500`**. | SDK NODES.md:94-97 | High |
| F6 | `7320` count is read from payload byte 17 (= first data byte) by both reference clients; valid range 1..20. The host can also *request* `7320` (action 3) to re-read the count of the photo still held. | SDK python/xkglasses/client.py:128-139, :260-283, protocol.py:180-182 | High (parse) / Low-Med (host query, not in the "confirmed" table) |
| F7 | Reference clients use 8 s for `7320` after `57B0`, and per-element waits; neither retries an element nor sends `7500` on failure. | SDK client.py:303, :358-373; flutter_example …/XkGlassesController.kt:171-184 | High |
| F8 | Album API in LensMoo (`AbPhotoLibrary`, class `xhc`): enumeration + transfer on `7200`, `7310`, `7300`, finish `7500`; **`7400` = delete photo**; `7600` unidentified. Entity `WmVideoFrameInfo` (`frameId, frameType, frameLen, frameData`). Payloads not decoded anywhere. | LF docs/VIDEO-QUALITY-RESEARCH.md:69; SDK PAYLOADS.md:131, NODES.md:131 | Med |
| F9 | These units report `noStorageDevice = 1` (node `7110`); LensMoo hides stored-media controls for it. `5713` showed `photo_num 0` with no stored media; vendor spec sheet: no external flash, no Wi-Fi. So photos are pushed once, not kept in an album on the glasses. | LF docs/VIDEO-RESEARCH.md:33, docs/FIRMWARE-RESEARCH.md:15; SDK PAYLOADS.md:85, VALIDATION.md:308 | Med-High |
| F10 | LensMoo's exported photo is 1280×960, 580 969 B, produced by phone-side `SRLib.processImage(…,2)` ("-sr.jpeg") = 2× super-resolution of the Bluetooth JPEG. It is not a bigger original. | LF docs/VALIDATION.md:36, docs/HARDWARE-VARIANT.md:20 | Med-High |
| F11 | USB: vendor-app reviews report USB as charge-only; recorded media is "not exposed through SPP". No Wi-Fi AP found. | SDK VALIDATION.md:197-206 | Med |
| F12 | Fragment payload size per 0x4A frame is not documented anywhere. | — (gap) | — |

**Throughput (estimate, not measured in sources — Conf. Med).** BR/EDR 2-DH5/3-DH5 gives 1.4–2.1 Mbit/s
air rate; RFCOMM to a low-cost SoC on Android typically sustains 40–120 KB/s, roughly halved while A2DP
streams and lower again with an SCO/eSCO (HFP mic) link reserving slots. Per-element stop-and-wait adds one
round trip (20–60 ms; 100+ ms if the link sits in sniff). Expected shutter-to-saved for an 80 KB photo:
~2.6 s capture + 1–3 s transfer ≈ 4–6 s. Worst case 20 elements (~320 KB) ≈ 8–12 s with audio active.

**Full-res answer.** Over Bluetooth the 640×480 JPEG *is* the original the firmware produces; nothing
higher exists on these units (F4, F10). USB originals: not available on the documented E13C-1 (F9, F11);
the P3 may be a different board, so check on-device (C7). Do not claim more than 640×480.

## 2. Defects in the current app

| # | Where | Defect | Impact |
|---|---|---|---|
| D1 | XkGlasses.kt:294 (inside `try` of 281-299) | `7500` is sent only on success. Any timeout/error leaves the glasses' image session open → glasses blocked (F5) until power-cycle/reconnect. | **High** |
| D2 | XkGlasses.kt:393-394 | `4A0009` is sent *after* `assembler.feed(f)`; if feed throws (out-of-order, short frame) the final fragment is never ACKed → glasses stall. | **High** |
| D3 | XkProtocol.kt:202, 206, 212, 217 | Assembler throws on loss; error is swallowed by `handle()` (XkGlasses.kt:427-429) as "Bad frame", so `download` waits the full 15 s (XkGlasses.kt:292) and then fails. No retry of the element. | High |
| D4 | XkGlasses.kt:290-291 | `7300` reply is mandatory (8 s); an element whose data arrived but whose reply was lost is reported as failure. Error-code replies (`FMT_ERRCODE`, e.g. INVALID_PARAM) are not checked. | Med |
| D5 | XkGlasses.kt:301-310 | Count parse uses BE16 of data[0..1] first; if the device sends `[N, 00]` this gives N·256 → falls back to `payload.last()` which may be 0 → "bad part count". Range 1..400 vs documented 1..20. | Med |
| D6 | XkGlasses.kt:403-419 | Button photo with `saveButtonPhotos == false`: `7320` is ACKed but never downloaded or released with `7500` → glasses blocked (F5). Also a button `7320` arriving *during* a download (`capturing == true`) is queued and forgotten. | **High** |
| D7 | XkProtocol.kt:216-220, 222-227 | Element header not validated (media type, declared length); JPEG end marker `FF D9` not checked → truncated images can be saved. | Med |
| D8 | XkProtocol.kt:153-159 | After a CRC drop the parser rescans byte-by-byte; a stray 0x30/0x4A/0x2B inside image data with a large bogus length makes it `break` and wait for up to 64 KB that never comes at the end of a burst → element stalls until timeout. | Med |
| D9 | XkGlasses.kt:238 | 20 s wait for `7320`; `57B0` error reply (e.g. busy) not checked at :237, so a refused shutter costs 20 s. | Low |
| D10 | XkGlasses.kt:423 | `waiting.getOrPut(node).offer(f)` creates/grows a queue for every pushed node (`57B1`, `5760`, `C101`…) that nobody polls — unbounded growth over a long session. | Low |
| D11 | XkGlasses.kt:331 | No timing/size/retry stats logged, so throughput problems can't be diagnosed from the owner's log. | Low |

## 3. Recommended changes (Code Specialist)

**3a. `Xk.PhotoAssembler` — never throw, report result, validate** (replace XkProtocol.kt:184-229)
```kotlin
class PhotoAssembler(private val count: Int) {
    enum class Result { MORE, DONE, FAILED }
    private val elements = arrayOfNulls<ByteArray>(count + 1)
    private val current = ByteArrayOutputStream()
    private var active = 0
    private var nextIdx = 0L
    var failReason: String? = null; private set
    var maxFragment = 0; private set

    fun start(index: Int) { require(index in 1..count); active = index; current.reset(); nextIdx = 0; failReason = null }
    fun has(index: Int) = elements[index] != null

    fun feed(f: Frame): Result {
        if (active == 0) return Result.MORE            // stray frame: ignore
        val p = f.payload
        maxFragment = maxOf(maxFragment, p.size)
        return when (val t = f.fragment) {
            0 -> store(p)
            1, 2, 3 -> {
                if (p.size < 4) return fail("short fragment")
                if (t == 1) { current.reset(); nextIdx = 0 }
                val idx = le32(p, 0)
                if (idx != nextIdx) return fail("fragment $idx, wanted $nextIdx")
                nextIdx++
                current.write(p, 4, p.size - 4)
                if (t == 3) store(current.toByteArray()) else Result.MORE
            }
            else -> fail("fragment type $t")
        }
    }
    private fun fail(why: String): Result { failReason = "part $active: $why"; active = 0; current.reset(); return Result.FAILED }
    private fun store(raw: ByteArray): Result {
        if (raw.size < 5) return fail("too small")
        if (raw[0].toInt() != 0) return fail("media type ${raw[0].toInt() and 0xFF}")
        val declared = le32(raw, 1)
        if (declared > raw.size) return fail("short: $declared declared, ${raw.size} got")
        elements[active] = raw.copyOfRange(5, raw.size); active = 0; current.reset()
        return Result.DONE
    }
    fun jpeg(): ByteArray {
        val out = ByteArrayOutputStream()
        for (i in 1..count) out.write(elements[i] ?: error("missing part $i of $count"))
        val img = out.toByteArray()
        if (img.size < 4 || img[0] != 0xFF.toByte() || img[1] != 0xD8.toByte()) error("not a JPEG")
        val tail = img.size - 1
        if ((maxOf(0, tail - 16)..tail - 1).none { img[it] == 0xFF.toByte() && img[it + 1] == 0xD9.toByte() })
            error("photo is cut off (no end marker)")
        return img
    }
    private fun le32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
        ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)
}
```
(EOI searched in the last 16 bytes to tolerate padding. Conf. High.)

**3b. Media branch of `handle()` — ACK first, signal failure at once** (replace XkGlasses.kt:391-396)
```kotlin
Xk.HEAD_MEDIA -> {
    if (f.isAck) return
    lastMediaAt = SystemClock.elapsedRealtime()
    burstOpen = f.fragment == 1 || f.fragment == 2
    if (f.fragment == 3) send(Xk.mediaAck(nextOrder()), logIt = false)   // glasses wait for this, always send
    when (assembler?.feed(f)) {
        Xk.PhotoAssembler.Result.DONE -> mediaDone.offer(true)
        Xk.PhotoAssembler.Result.FAILED -> mediaDone.offer(false)
        else -> {}
    }
}
```
Fields: `@Volatile private var lastMediaAt = 0L`, `@Volatile private var burstOpen = false`,
`@Volatile private var pendingAnnounce: Xk.Frame? = null`; import `android.os.SystemClock`. Conf. High.

**3c. `download()` — inactivity timeout, retry element, always close** (replace XkGlasses.kt:281-299)
```kotlin
private fun download(count: Int): Uri {
    val asm = Xk.PhotoAssembler(count)
    assembler = asm
    val t0 = SystemClock.elapsedRealtime(); var retries = 0
    try {
        for (i in 1..count) {
            var attempt = 0
            while (!asm.has(i)) {
                if (attempt > ELEMENT_RETRIES) error(asm.failReason ?: "part $i never arrived")
                if (attempt > 0) { retries++; addLog("Retry part $i ($attempt): ${asm.failReason ?: "timeout"}") }
                busyText = "Downloading photo ${(i - 1) * 100 / count}%"; changed()
                waitQuiet(1_500)                     // let any half-sent burst drain before asking again
                mediaDone.clear(); asm.start(i)
                val q = waiting.getOrPut("7300") { LinkedBlockingQueue() }.also { it.clear() }
                send(Xk.control(nextOrder(), "7300", Xk.ACTION_WRITE, nextRequestId(), byteArrayOf(i.toByte())), logIt = false)
                val sentAt = SystemClock.elapsedRealtime()
                while (true) {
                    val r = mediaDone.poll(250, TimeUnit.MILLISECONDS)
                    if (r != null) break                               // true = stored, false = failed -> retry
                    q.poll()?.let { if (it.replyType == 4 && it.data.isNotEmpty() && it.data[0].toInt() != 0)
                        error("glasses refused part $i (code ${it.data[0].toInt() and 0xFF})") }
                    val now = SystemClock.elapsedRealtime()
                    if (now - maxOf(sentAt, lastMediaAt) > ELEMENT_IDLE_MS || now - sentAt > ELEMENT_CAP_MS) break
                }
                attempt++
            }
        }
        val img = asm.jpeg()
        val ms = SystemClock.elapsedRealtime() - t0
        addLog("Photo ${img.size / 1024} KB, $count parts, ${ms} ms, ${if (ms > 0) img.size / ms else 0} KB/s, " +
            "max frame ${asm.maxFragment} B, $retries retries")
        return saveJpeg(img)
    } finally {
        assembler = null
        runCatching { request("7500", Xk.ACTION_EXECUTE, ByteArray(0), 2_000) }   // finish OR abort: never leave it open
    }
}

private fun waitQuiet(quietMs: Long) {      // returns at once unless a burst is still mid-flight
    val end = SystemClock.elapsedRealtime() + 4_000
    while (burstOpen && SystemClock.elapsedRealtime() < end &&
        SystemClock.elapsedRealtime() - lastMediaAt < quietMs) Thread.sleep(50)
    burstOpen = false
}
// companion: ELEMENT_RETRIES = 2, ELEMENT_IDLE_MS = 4_000L, ELEMENT_CAP_MS = 15_000L, MAX_ELEMENTS = 64
```
Notes: `7300` reply is no longer required (D4); refusal codes abort fast; a lost/out-of-order fragment
→ wait for the burst to drain → re-request the same index up to 2× → else abort with `7500`.
Re-requesting an index is supported by the design (elements fetched by index on demand, F1/F6) but not
explicitly validated — Conf. Med; check C5. `7500` in `finally` — Conf. High (F5).

**3d. Count parse** (replace XkGlasses.kt:301-310) — first data byte first, like both references:
```kotlin
private fun elementCount(f: Xk.Frame): Int {
    val d = f.data
    val c = buildList {
        if (d.isNotEmpty()) add(d[0].toInt() and 0xFF)
        if (d.size >= 2) { add(((d[0].toInt() and 0xFF) shl 8) or (d[1].toInt() and 0xFF))
                           add((d[0].toInt() and 0xFF) or ((d[1].toInt() and 0xFF) shl 8)) }
    }.firstOrNull { it in 1..MAX_ELEMENTS }
    return c ?: error("bad part count (${hex(d)})")
}
```
Conf. High.

**3e. Button `7320` — never leave it hanging** (replace XkGlasses.kt:403-419 branch)
```kotlin
"7320" -> if (!capturing) {
    if (prefs.saveButtonPhotos) { capturing = true; io.execute { fetchAnnounced(f) } }
    else io.execute { runCatching { request("7500", Xk.ACTION_EXECUTE, ByteArray(0), 2_000) }
                      addLog("Button photo skipped (turned off in Settings)") }
    return
} else if (assembler != null) { pendingAnnounce = f; return }   // arrived mid-download: fetch it next
```
`fetchAnnounced(f)` = the existing body of :406-417; at the end of it and of `takePhoto`'s `finally`,
`pendingAnnounce?.let { pendingAnnounce = null; if (prefs.saveButtonPhotos) fetchAnnounced(it) }`. Conf. Med.

**3f. takePhoto** (XkGlasses.kt:237-238): check the `57B0` reply — if `replyType == 4 && data[0] != 0`
fail with "camera busy (code n)"; on `57B1` set `busyText = "Processing photo…"`; wait 10 s (not 20) for
`7320` after `57B1`/`57B0`. Conf. Med-High.

**3g. Parser resync guard** (XkProtocol.kt:153-159): only while re-synchronising after a CRC drop, accept
a candidate header only if `cmd ∈ {0x0001,0x0002,0x0004,0x0009,0x8001,0x8002,0x8004,0x8009}` and
`plen ≤ 20_000`; otherwise `pos++`. Clear the flag after the next good frame. Normal (in-sync) parsing
is unchanged, so an unknown-but-valid cmd is never dropped. Conf. Med.
```kotlin
private var resync = false
// in loop, after reading h and plen:
if (resync && (plen > 20_000 || cmdAt(pos) !in RESYNC_CMDS)) { pos++; dropped++; continue }
// on CRC failure: resync = true ; after `out += Frame(...)`: resync = false
```

**3h. Queues** (XkGlasses.kt:423): `waiting[node]?.offer(f)` (requests/awaits already register with
`getOrPut` before sending). Conf. High.

**3i. Stored photos / album nodes.** Do **not** ship an album download now. The payloads of
`7200/7310/7600` are unknown and `7400` deletes (F8); on `noStorageDevice = 1` units there is nothing
stored (F9). Recommended, low risk:
- Read `5713` after connect (already done) and `7110` once; if `photo_num == 0` or `noStorageDevice == 1`,
  hide any "photos on glasses" UI and say photos come over only when taken while connected.
- Add a "Get last photo again" action: request `7320` (action 3, no data); if a count comes back, run
  `download(count)`. Conf. Low-Med (F6) — gate on C6.
- Debug-only, read-only probe: `7200` with `ACTION_READ`, empty data; log the raw reply. Never send
  `7400`, never send `7600`/`7310` writes without the owner's explicit OK in that moment.

**3j. Optional "LensMoo-size" copy**: LensMoo's 1280×960 is an upscale (F10). If the owner wants matching
file size, add a Setting that saves a 2× `Bitmap.createScaledBitmap(src, w*2, h*2, true)` copy at quality 92.
Label it "enlarged copy (no extra detail)". Conf. High that it adds no real detail.

## 4. On-device checks (owner, Samsung)

| # | Do this | Pass looks like |
|---|---|---|
| C1 | Connect, tap Take photo 10 times in a row, then Share log. | 10 saved photos; each log line "Photo NN KB, N parts, … KB/s, 0 retries". Write down KB/s. |
| C2 | Repeat C1 while music plays on the glasses, then during a phone call. | Still 10/10; slower KB/s is fine. |
| C3 | In Gallery, open a photo → Details. | 640×480 (or note the real size — tells us if the P3 differs from E13C-1). |
| C4 | Turn off "save button photos", press the glasses' photo button once, then tap Take photo in the app. | App photo works without reconnecting (proves the `7500` release, D6). |
| C5 | Take photos while walking 6–10 m away / behind a wall. | Log may show "Retry part i"; photo still saved, or a clear failure — and the very next photo works without reconnect (D1). |
| C6 | Tap "Get last photo again" right after a photo. | Same photo saved twice → host `7320` query works; if nothing, hide the button. |
| C7 | Plug glasses into the phone with a USB-C cable, open My Files. | A new drive = USB import is real for P3; nothing / only charging = Bluetooth is the only photo path. |
| C8 | Press the button 3× while the app is downloading. | All button photos saved one after another; no "stuck" glasses. |

## 5. Confidence summary
High: D1, D2, D3, D7, 3a, 3b, 3d, 3h, F1–F5, 640×480 limit. Med: 3c retry-same-index, 3e, 3g, throughput
numbers, album nodes (F8/F9), USB result for the P3 specifically. Low-Med: host-requested `7320` (3i).
