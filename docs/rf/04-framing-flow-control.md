# RF-04 — SPP byte-stream framing, parsing and flow control

Scope: `Xk.Parser`, `Xk.PhotoAssembler`, reader/writer threads in `XkGlasses`, RFCOMM write sizing,
back-pressure during `0x4A` bursts. Sources are cited by path; `xk/` = `/home/claude/beneduzi/xk-one-pro-sdk`,
`lf/` = `/home/claude/joaosouz4dev/lensmoo-crash-fix`. Simulations: a faithful Python port of the
current parser was run against synthetic streams (scripts in the session scratchpad, not in the repo).

## 1. Findings from the sources

| # | Finding | Source | Conf. |
|---|---|---|---|
| F1 | `payload_len` is u16 LE, so one frame is at most 16 + 65535 bytes. | xk/docs/PROTOCOL.md:22 | High |
| F2 | CRC covers the payload only; the 16-byte header (order, cmd, divide, len) is **not** protected. A bad CRC means "discard and resync", never "drop the link". | xk/docs/PROTOCOL.md:33-35 | High |
| F3 | `divide_type` low 3 bits: 0 whole, 1 first, 2 middle, 3 last; app must send `4A 0009` on type 3 only. | xk/docs/PROTOCOL.md:191-194 | High |
| F4 | Type **5** = video frame (`WmVideoFrameInfo`) in the vendor SDK; not seen on that unit. | xk/docs/VALIDATION.md:184,192 | Med |
| F5 | High bits of `divide_type` are a 13-bit `logical_length_mod`: `((d & 0xF8) << 5) \| (d >> 8)`, mask `0x1FFF`. | xk/flutter_example/.../XkFrame.kt:19; xk/python/xkglasses/reassembler.py:23 | High |
| F6 | One **real device 0x4A frame**: header `4A 2A 0180 1369 2A00 00000000 C1FF 0000`: cmd `0x8001`, divide raw `0x6913` → type 3, logical length **617** (= element 1's total length), payload **42** B, `cmd_idx`=1, offset and reserved = 0. So element 1 came in exactly 2 fragments; the first carried 617−38 = **579 data bytes (payload 583, frame 599 B)**. | xk/flutter_example/android/app/src/test/.../XkFrameTest.kt:35-47 | Med (one sample) |
| F7 | Elements are 617, 16389×4, 14414 bytes, with a 5-byte header `[media_type][len LE4]`, where len counts the header. The JPEG must start `FFD8` and end `FFD9`. | xk/docs/PROTOCOL.md:199-212 | High |
| F8 | Photo size 74–84 KB at 640×480 (xk unit); LensMoo exported 1280×960 = 581 KB on the lf unit, which is about 36 elements at 16 KB. | xk/docs/VALIDATION.md:33; lf/docs/VALIDATION.md:36 | Med |
| F9 | Live H.264 preview reached the phone as `[type=2][len LE32][Annex-B]` (same 5-byte shape as photo elements, type 2), 295 frames in 10 s, 684 472 B in 14.1 s ≈ **48 KB/s sustained**. Preview toggled with node `5720`. Wire `divide_type` not recorded. | lf/docs/RECORDING-API-RESEARCH.md:7; lf/docs/VALIDATION.md:23; lf/docs/VIDEO-QUALITY-RESEARCH.md:36 | Med |
| F10 | lf's own preview parser rejects packets over 2 MB and any whose declared length ≠ actual. Good model for our caps. | lf/android/src/dev/lensfix/video/PreviewPayload.java:51-54 | High |
| F11 | No keepalive. Idle links send **zero** bytes, so a stalled parser is never unstuck by later traffic. | xk/docs/PROTOCOL.md:291-302 | High |
| F12 | App-side `0x2B` frames use cmd `0x0004` **with data** (e.g. `F0600100`). On 0x2B, op 4 is not an ack. | xk/python/xkglasses/protocol.py:37-38 | High |
| F13 | Reference clients are not safe to copy here. The flutter parser returns `null` on a bad CRC and so stalls forever (XkFrame.kt:160). The Python parser raises, and its reader then exits (frames.py:30-31, client.py:106-108). Both write on the caller's thread (XkSppClient.kt:120-126). | as cited | High |

No source documents RFCOMM MTU, credits, or a max 0x4A fragment size. F6 gives the only measured
fragment (≈583 B payload). Android's own stack segments writes to the negotiated RFCOMM frame size,
so app-level chunking is not required for correctness (general Android knowledge, Med).

## 2. Defects in the current app

| # | Defect | Where | Conf. |
|---|---|---|---|
| D1 | **False-header stall.** After any resync, the parser accepts any `0x30/0x4A/0x2B` byte as a header. It then waits for up to 65 551 bytes (`if (len-pos < 16+plen) break`). Those bytes are common: `0x30` is ASCII `'0'` in JSON, and JPEG data is random. Real frames already buffered behind the candidate are never delivered, and because of F11 the glasses send nothing more. **Simulated:** a corrupted `5713` JSON reply followed by good `1003` and `1001` replies gave **0 frames out**. One random byte flip in a photo burst lost the trailing control frame in **1952 of 2000** runs. With the header rules in C1: 2 of 2 frames, and 44 of 2000 lost (40 of which were the corrupted frame itself). Adding `skipStalled()` lost 0 frames in 3000 runs. | XkProtocol.kt:153-159 | High |
| D2 | CRC-fail resync is `pos++` and then a full CRC over `plen` bytes at each offset. In the worst case that is O(n·plen) bit-loop work on the reader thread, which slows draining and throttles the link. | XkProtocol.kt:159, 35-44 | Med |
| D3 | One `parser` serves the whole app and is never reset. A partial frame left from a dead link becomes the front of the next session's stream (and triggers D1). | XkGlasses.kt:68, 188-194 | High |
| D4 | The reader is not tied to its session. After a quick disconnect and reconnect, the old reader wakes from `read()`, sees the **new** `socket != null`, and closes the new link (lines 374-383). It can also run `parser.feed` at the same time as the new reader, and `Parser` is not thread-safe. | XkGlasses.kt:361-386, 188-194 | High (race) |
| D5 | `socket` and `out` are not `@Volatile` but are read by 3 threads. | XkGlasses.kt:65-66 | Med |
| D6 | **Burst ACK skipped on error.** `assembler.feed(f)` runs before `send(mediaAck)`. If feed throws (out-of-order, short, unknown type), the catch at 427 skips the `4A 0009` for divide 3, which can leave the glasses waiting. | XkGlasses.kt:393-394, 427-428 | High (code), Med (device effect) |
| D7 | Socket writes run on the **reader** thread (acks) under `synchronized(o)`. `BluetoothSocket` writes have no timeout. If the io thread blocks in a write, the reader blocks on the lock, stops draining, the RFCOMM credits run out, and both ends wait. | XkGlasses.kt:340-347, 394, 399 | Med |
| D8 | `PhotoAssembler`: no per-element size cap (`current` grows while type-2 frames keep coming), no check of the declared element length (F7) or the `logical_length_mod` (F5), `FFD9` is not checked, and type 5 or any error throws. One lost fragment means a 15 s wait and then the whole photo fails, with no retry. | XkProtocol.kt:197-228; XkGlasses.kt:285-293 | High |
| D9 | `isAck` ignores the head byte, so 0x2B op-4 frames with data count as acks (F12). | XkProtocol.kt:55 | Low |
| D10 | The `7300` index is one byte (`i.toByte()`), but `elementCount` allows up to 400. Elements above 255 would wrap. | XkGlasses.kt:290, 309 | Med |
| D11 | Per-frame `SimpleDateFormat` allocation and hex formatting run on the reader thread. That is minor, but it is reader latency. | XkGlasses.kt:401, 441 | Low |

Memory is bounded today: the buffer never holds more than 16+65535+8192 bytes, so it is at most 128 KB after
doubling (XkProtocol.kt:173-176). The defect is liveness, not memory.

## 3. Recommended changes (framework only)

**C1 — Parser with header plausibility, reset, and stall release (fixes D1, D2, D3).** Replace `class Parser` in XkProtocol.kt:

```kotlin
/** Splits a byte stream into frames. Never waits behind a header that cannot be real. */
class Parser(private val maxControlPayload: Int = 16 * 1024) {
    private var buf = ByteArray(4096)
    private var len = 0
    var dropped = 0L; private set        // bytes skipped while resyncing
    var badCrc = 0L; private set
    var maxMediaPayload = 0; private set // largest 0x4A payload seen (log it: real fragment size)
    val pending: Int @Synchronized get() = len

    @Synchronized fun reset() { len = 0 }

    @Synchronized fun feed(data: ByteArray, off: Int = 0, count: Int = data.size - off): List<Frame> {
        require(off >= 0 && count >= 0 && off + count <= data.size)
        if (len + count > buf.size) buf = buf.copyOf(maxOf(len + count, buf.size * 2))
        System.arraycopy(data, off, buf, len, count); len += count
        return drain()
    }

    /** Link has been quiet with bytes pending: give up on whatever is waiting, keep any real frames behind it. */
    @Synchronized fun skipStalled(): List<Frame> {
        val out = ArrayList<Frame>()
        while (len >= 16) { compact(1); dropped++; out += drain() }
        dropped += len; len = 0
        return out
    }

    private fun drain(): List<Frame> {
        val out = ArrayList<Frame>(2)
        var pos = 0
        while (len - pos >= 16) {
            val plen = plausibleLength(pos)
            if (plen < 0) { pos++; dropped++; continue }
            if (len - pos < 16 + plen) break
            if (crc16(buf, pos + 16, pos + 16 + plen) != u16(pos + 12)) { pos++; dropped++; badCrc++; continue }
            val head = buf[pos].toInt() and 0xFF
            if (head == HEAD_MEDIA && plen > maxMediaPayload) maxMediaPayload = plen
            out += Frame(head, buf[pos + 1].toInt() and 0xFF, u16(pos + 2), u16(pos + 4),
                buf.copyOfRange(pos + 16, pos + 16 + plen))
            pos += 16 + plen
        }
        compact(pos)
        if (len == 0 && buf.size > 128 * 1024) buf = ByteArray(4096)
        return out
    }

    /** Payload length if a real frame could start at [p], else -1. */
    private fun plausibleLength(p: Int): Int {
        val head = buf[p].toInt() and 0xFF
        if (head != HEAD_CONTROL && head != HEAD_MEDIA && head != HEAD_CUSTOM) return -1
        val cmd = u16(p + 2)
        if (cmd and 0x7F00 != 0) return -1                       // only 0x00xx / 0x80xx exist
        when (cmd and 0xFF) { 1, 2, 4, 9 -> {} else -> return -1 }
        val div = u16(p + 4)
        when (div and 0x07) { 0, 1, 2, 3, 5 -> {} else -> return -1 }
        if (head != HEAD_MEDIA && div != 0) return -1
        if (buf[p + 8].toInt() or buf[p + 9].toInt() or buf[p + 10].toInt() or buf[p + 11].toInt() != 0) return -1
        val plen = u16(p + 6)
        if (head != HEAD_MEDIA && plen > maxControlPayload) return -1
        return plen
    }

    private fun u16(at: Int) = (buf[at].toInt() and 0xFF) or ((buf[at + 1].toInt() and 0xFF) shl 8)
    private fun compact(n: Int) { if (n > 0) { System.arraycopy(buf, n, buf, 0, len - n); len -= n } }
}
```
The rules come from F6 and F12 (offset 0, cmd 0x8001/0x8002/0x8004/0x0009, divide only on 0x4A). If the device log
ever shows `dropped > 0` on a clean session, relax the rule that rejected the frame (start with `div != 0` on 0x30).
Optional: a 256-entry table CRC (same polynomial) is 5–8× faster than the bit loop at XkProtocol.kt:35-44.

Also: `val isAck get() = head != HEAD_CUSTOM && (operation == 0x0004 || operation == 0x0009)` (D9) and
`val logicalLength get() = ((divide and 0xF8) shl 5) or ((divide shr 8) and 0xFF)` on `Frame` (F5).

**C2 — Session-scoped link with a dedicated writer (fixes D3, D4, D5, D7).** In XkGlasses.kt, replace `socket/out/reader/parser` with:

```kotlin
private inner class Link(val socket: BluetoothSocket) {
    val parser = Xk.Parser()
    private val outQ = LinkedBlockingQueue<ByteArray>(256)
    @Volatile var lastRxAt = SystemClock.elapsedRealtime()
    @Volatile var writeStartedAt = 0L
    @Volatile var closed = false
    fun send(bytes: ByteArray) {                       // never blocks the caller
        if (closed || !outQ.offer(bytes)) { addLog("Send queue full, dropping link"); close(); throw IOException("link busy") }
    }
    fun close() { if (!closed) { closed = true; runCatching { socket.close() }; writer.interrupt() } }
    private val writer = Thread({
        try { val os = socket.outputStream
            while (!closed) { val b = outQ.take(); writeStartedAt = SystemClock.elapsedRealtime(); os.write(b); writeStartedAt = 0 }
        } catch (_: Exception) {} finally { close() }
    }, "glasses-writer").apply { isDaemon = true }
    val reader: Thread = Thread({
        try { val ins = socket.inputStream; val b = ByteArray(16 * 1024)
            while (true) { val n = ins.read(b); if (n < 0) break; lastRxAt = SystemClock.elapsedRealtime()
                parser.feed(b, 0, n).forEach { handle(this, it) } }
        } catch (_: Exception) {} finally { close(); if (link === this) onLinkLost(this) }
    }, "glasses-reader").apply { isDaemon = true }
    fun start() { writer.start(); reader.start() }   // usage: val l = Link(s); link = l; l.start()
}
@Volatile private var link: Link? = null
```
`send(f)` becomes `(link ?: error("Not connected.")).send(f.encode())`. Write one whole frame per `write()` call. All
frames are under 100 B and the stack segments them to the RFCOMM MTU, so do no extra chunking and no `flush()` loops.
`onLinkLost(l)` holds the old lines 375-383 and runs only when `link === l`, so a stale reader can never touch a new
session. At connect, log `socket.maxTransmitPacketSize`, `maxReceivePacketSize` and `connectionType` (API 23+).

**C3 — Watchdog (D1 residue, D7).** One `Executors.newSingleThreadScheduledExecutor()`, every 500 ms:
```kotlin
val l = link ?: return; val now = SystemClock.elapsedRealtime()
if (l.parser.pending > 0 && now - l.lastRxAt > 1_500) l.parser.skipStalled().forEach { handle(l, it) }
val w = l.writeStartedAt
if (w != 0L && now - w > 5_000) { addLog("Write stuck 5 s, dropping link"); l.close() }
```

**C4 — Media path: ACK first, never throw, retry one element (fixes D6, D8, D10).** In `handle`:
```kotlin
Xk.HEAD_MEDIA -> {
    if (f.isAck) return
    if (f.fragment == 3) l.send(Xk.mediaAck(nextOrder()).encode())   // before anything can fail
    when (assembler?.feed(f)) { Xk.Part.DONE -> mediaDone.offer(true); Xk.Part.FAILED -> mediaDone.offer(false); else -> {} }
}
```
In `PhotoAssembler`, `feed` returns `enum class Part { MORE, DONE, FAILED }`. It ignores types 4–7 (preview frames,
F4/F9). Errors set `failed` and are reported once, on the type-3 or type-0 frame, so the burst drains before a retry.
It caps each element at 1 MiB, and on finish requires the 5-byte header length to equal `raw.size` (F7). It logs, but
does not fail, when `raw.size and 0x1FFF != logicalLength` (F5/F6, one sample). `jpeg()` also requires `FFD9` at the end.
The download loop does `for (attempt in 1..2) { mediaDone.clear(); asm.start(i); request(7300) ; ok = mediaDone.poll(15 s) == true }`.
It caps `count` at 255 (one-byte index). Whether the glasses accept a repeated `7300 i` is unverified (Low): check it on the device (§5.3).

**Reader rules (back-pressure).** The phone is the receiver, and RFCOMM credit flow control throttles the glasses
automatically when our reader lags. So the reader must **never block**: no socket write (queue only), no lock that
the io thread holds, no disk or MediaStore work, no main-thread waits. Keep `handle()` to parsing, `offer()`, and a
cheap log (cache one `SimpleDateFormat`, D11). At the 48 KB/s preview rate (F9) this is about 50 reads/s of ~1 KB, well
within budget.

## 4. Property-style tests for the Code Specialist (JUnit 4, `java.util.Random` with fixed seeds, no new deps)

Generator: `randFrame(r)` returns `Xk.Frame` with head in {30, 4A, 2B}, cmd in {0x0001, 0x0004, 0x0009, 0x8001, 0x8002, 0x8004},
divide 0 for 30/2B and {0..3, 5} | (r.nextInt(0x2000) packed per F5) for 4A, and a payload of 0..1000 B (4A sometimes up to 65535).
`chunks(r, bytes)` splits a byte array into random pieces of 1..9000 B.

1. **Round trip / chunk invariance**: 500 seeds × 20 frames. Feed the encoded stream in random chunks and expect the same frames in the same order (compare head, order, cmd, divide, payload), with `dropped == 0`.
2. **Noise between frames**: insert 0..300 random bytes before, between and after frames. Every real frame is recovered in order, and no frame comes out whose payload differs from an input frame.
3. **Single-byte corruption**: flip one byte in frame k, feed in chunks, then call `skipStalled()`. Every frame except k must come out. Frame k is either absent or has the same payload (the header is not CRC-protected, F2). The Python port met this in 3000 of 3000 cases.
4. **Stall regression (D1)**: a `5713` JSON reply with one payload byte flipped, followed by valid `1003` and `1001` replies. Both replies come out **without** `skipStalled()`.
5. **Huge declared length**: a plausible 4A header with plen=65535 and 100 bytes of body gives no frames and `pending == 116`. `skipStalled()` makes `pending == 0`. Then a valid frame parses.
6. **Memory bound**: `@Test(timeout = 5000)`. Feed 8 MB of random bytes in random chunks of 1..16384. After each feed, `pending <= 16 + 65535 + chunk`, and the call never throws.
7. **Reset**: half of a frame, then `reset()`, then a full frame gives exactly that one frame.
8. **Captured vector** (F6): `4A2A01801369 2A00 00000000 C1FF 0000` + payload from XkFrameTest.kt:35 gives fragment 3, `logicalLength == 617`, payload 42, `fromDevice`, `isAck == false`.
9. **Assembler round trip**: random JPEG-like body (`FFD8`..`FFD9`, 5..60 000 B) with element header `[0][len LE4]`, split into random fragments (type 1, 2…, 3 with cmd_idx 0..n), or a single type 0. Expect `DONE` and identical bytes. Drop or swap any middle fragment: `FAILED` exactly once, on the type-3 frame, and never wrong bytes. A type-5 frame interleaved anywhere has no effect.
10. **ACK-before-feed** (D6): a fake `Link` that records sends. Feeding a corrupt type-3 frame still records one `4A 0009`.

## 5. On-device checks (Samsung phone, app log screen)

1. **Link sizes**: connect and read the log line with max TX/RX packet size. Report the numbers; they set realistic read sizes.
2. **Ten photos in a row**: after each photo the log shows `dropped=0 badCrc=0 maxMedia=N` and the elapsed time. N is the real 0x4A fragment size (expect about 583 per F6). Any `dropped > 0` on a close-range clean link means a C1 rule is too strict, so send the log.
3. **Weak link**: hold the phone about 8–10 m away or behind a wall and take 5 photos. Each must finish, or fail within about 35 s, and the **next** command (battery refresh) must answer within 3 s, so the link is not stuck. Look for "attempt 2" lines, which also show whether repeated `7300` works.
4. **Reconnect race**: tap Disconnect then Connect 5 times quickly, then toggle Bluetooth off and on once. The app must end Connected. The log must never show "Link lost" straight after "Link open".
5. **Idle**: leave it connected 5 min, then Refresh. Battery must update within 3 s (F11).
6. **Button photo during app photo**: take one with the app and immediately press the glasses' button. Both photos are saved, or the second is skipped with a clear message, and the link stays usable.

## 6. Confidence summary

C1 High (simulated). C2 High (standard pattern; fixes real races). C3 Med (1.5 s and 5 s thresholds need the
device numbers from 5.2). C4 High for ACK-first and caps, Med for the length checks, Low for element retry until 5.3 confirms it.
