# Full-resolution photos: evidence, probe plan, honest assessment

Theoretical Physicist, 2026-11-25. Docs only. Abbreviations: **B** = /home/claude/beneduzi/xk-one-pro-sdk,
**L** = /home/claude/joaosouz4dev/lensmoo-crash-fix, **A** = /home/user/p3/app/src/main/java/com/zdycomp/p3companion.

## 0. Bottom line

- No source shows a resolution command or a separate "original" transfer. B: no node, entity field or string for
  resolution (B/docs/NODES.md:200-201, B/docs/VALIDATION.md:246-250); C104 has no effect (B/docs/HARDWARE.md:17-18).
- **New lead:** LensMoo's own album export decoded as **1280x960** (L/docs/VALIDATION.md:51), exactly 2x B's
  640x480 per axis (B/docs/HARDWARE.md:15). Either LensMoo upscales 2x on the phone (most likely; L found a
  local JPEG-header helper `wlc.i(width,height,quality)`, L/docs/VIDEO-QUALITY-RESEARCH.md:28), or it fetches a
  bigger image by a path we don't use. **We have never measured our own photo's pixel size.** Step 1 is to log it.
- Our 7110 reply `10 37 00…00 64 00 02 08 0C` (docs/LOG-2026-11-23.txt:67) is byte-for-byte B's
  (B/docs/NODES.md:268), and L's unit with that reply reports `noStorageDevice = 1`
  (L/docs/HARDWARE-VARIANT.md:17). So the P3 most likely **keeps no photos on board**: nothing for an album
  node, a cable, or Wi-Fi to fetch later. The photo exists only during the 57B0 → 7500 transfer.

## 1. Signs of a larger-image path

| Lead | Evidence | Verdict |
|---|---|---|
| `7200` | AbPhotoLibrary "enumerate stored images" (L/docs/VIDEO-QUALITY-RESEARCH.md:69; docs/PROTOCOL-NOTES.md:316). Silent to an argument-less query (B/docs/NODES.md:188-191). Entity `WmVideoFrameInfo` (B/docs/PAYLOADS.md:131) | Album list. Useful only if storage exists. Probe READ. |
| `7310` | "Select item k for transfer", likely ends in a 7320 push (docs/PROTOCOL-NOTES.md:317). Silent without args | Best candidate for "fetch stored/original photo". Probe READ, then index. |
| `7400` | **Delete photo** (L/docs/VIDEO-QUALITY-RESEARCH.md:69; docs/PROTOCOL-NOTES.md:321) | **Never send.** |
| `7600` | Same transfer family, function unknown, maybe video (docs/PROTOCOL-NOTES.md:322). Silent | READ only, no arguments beyond `[00]`. |
| 7320 / 7300 | 7320 carries a count only, 5-6 parts (docs/LOG-2026-11-23.txt:73-84); every element is type `00` + LE length + slice (B/docs/PROTOCOL.md:197; A/XkProtocol.kt:501-571). No thumbnail/original split seen | Check: raw 7320 bytes, element type bytes, a 7300 index N+1 before 7500, number of SOI/SOF markers in the JPEG. |
| 1001 | `preview_width/height = 160x120`, which matches neither the 320x240 H.264 nor the JPEG (docs/PROTOCOL-NOTES.md:153,195). SDK model has `remain_memory/total_memory/photo_num` served by other nodes (B/docs/PAYLOADS.md:79) | Display-related, not a capture size. No resolution field. |
| 5713 / 5712 | B's unit: `photo_num` stays 0 after many photos; 5712 total = remain (B/docs/VALIDATION.md:307-308) | Read before and after a capture. A rise means storage exists. |
| 7110 | `[ver 10][37 = 0011 0111][zeros][64 00][02][08][0C]`: five of the seven glasses flags set (B/docs/NODES.md:309-318). Bit order unknown (docs/PROTOCOL-NOTES.md:279) | Same as the E13C-1 that reports `noStorageDevice = 1`. |
| Wi-Fi | Vendors say "WiFi 6 instant transfer", but B found no AP (B/docs/VALIDATION.md:197-198); no SSID/AP/RTSP in the SDK (L/docs/VIDEO-QUALITY-RESEARCH.md:71); the xk one listing says **no Wi-Fi, no external flash, 1 MP 1280x720 camera** (L/docs/FIRMWARE-RESEARCH.md:15). The W20 SoC is Bluetooth dual-mode (L/docs/HARDWARE-VARIANT.md:24) | Unlikely on this SKU. Our WifiFinder (A/WifiFinder.kt:18-23) can confirm cheaply. |
| USB | Import tab reads a drive (A/MediaImporter.kt:78-81). B: the cable is reported **charge-only** (B/docs/VALIDATION.md:201-203); charging is a magnetic pogo connector (B/docs/HARDWARE.md:48). The W20 chip has USB 2.0, but a chip feature isn't a wired port (L/docs/HARDWARE-VARIANT.md:24-26) | Owner test only: plug into a PC. If no drive appears, the cable has no data. |

## 2. "Find full-size photos" probe (Diagnostics)

**Preconditions:** state READY, `busyFlag` taken (as A/XkGlasses.kt:1284), live view closed, no mic stream, no
pending 7320. Show one line first: "Takes one photo and asks the glasses about stored photos. Deletes nothing."
Persist the step index before each step. If the link drops, the next run skips that step and logs "dropped the link".

**Hard denylist (code-enforced, not only commented):** `7400` (delete), `102A` `102C` `5610` `5620` (OTA
channel, B/docs/PAYLOADS.md:132), `9000` `9001`, any `1017` write, any `102E` bind type, any action 3 other
than the existing `57B0` and `7500`. Use `Xk.control` (A/XkProtocol.kt:152) so the `len = rest-1` rule holds
(a wrong len drops RFCOMM, B/docs/PROTOCOL.md:46-53).

**Per-request rules:** reply timeout 3 s, then listen 3 s more. Log node/action/data hex, reply `type` (0 bin,
2 JSON, 3 no data, 4 error), `len`, error text (`Xk.errorText`), first 64 bytes hex (full JSON), unsolicited
pushes, and every 0x4A frame: count, total bytes, largest payload (A/XkProtocol.kt:337). **Restore:** if any
reply, 7320 or 0x4A arrived, handle a 7320 with the normal `download()` (A/XkGlasses.kt:1378) into a file named
`probe-<node>`; send `7500` (action 3, 2 s) after any 0x4A burst; then wait 1.5 s of quiet.
Stray 0x4A with no 7320: collect for at most 20 s or 2 MB, then 7500.

Steps (about 90 s):
1. Baseline: log the 7110 hex, 1001 preview fields, `5712` and `5713` (action 1).
2. Baseline photo: the normal 57B0 path. Log the raw 7320 bytes, each element's type byte and size, SOF size.
   Before its 7500, send **`7300` action 2 `[N+1]`** once (expect INVALID_PARAM; any data = hidden extra part).
3. `5713` and `5712` again. Did `photo_num` rise? Did `remain_memory` fall?
4. READ pass, action 1: `7200` data none, then `[00]`; `7310` none, `[00]`; `7600` none, `[00]`.
5. Index pass, action 2, **only for 7200 and 7310**, and only if step 4 did not give `INVALID_URN (04)` for
   that node: data `[00]`, `[01]` (oldest), `[00 01]` (2-byte BE, like B's 7300 capture, B/docs/PROTOCOL.md:50),
   and, if step 3 showed `photo_num = n > 0`, `[n]` (last photo). 7600 gets no action 2 unless it answered in
   step 4 with something other than INVALID_URN; then only `[00]`.
6. While steps 4-5 run, run `WifiFinder` (A/WifiFinder.kt) in case a command raises an AP or a P2P peer.
7. Final: `7500` once more (harmless), `5713` again, release `busyFlag`, reopen live view if a screen wants it.
8. Summary line per node: `7310/2 [01]: error 03 INVALID_PARAM` / `silent` / `JSON {...}` / `photo 1280x960, 212 KB`.

**Always (not only in the probe):** after `asm.jpeg()` (A/XkProtocol.kt:559) parse SOF and add it to the photo log
line (A/XkGlasses.kt:1427): `Photo 71 KB, 640x480 (0.31 MP), 4:2:0, baseline, 6 parts…`. Parser: from FFD8 walk
`FF xx [len 2 BE]` segments; stop at SOS (`FFDA`); for `C0`-`CF` except `C4`, `C8`, `CC` read
`[precision 1][height 2 BE][width 2 BE][components 1]`, then per component `[id][HxV sampling][tq]`. Also log the
SOF count and the APP1/EXIF presence (an embedded thumbnail or a second SOF means two images in one file). Bad or
missing SOF logs "size unknown" and never blocks saving. Add a unit test with a 16x8 JPEG fixture.

## 3. For the owner, in plain words

**What "20 MP" means here.** It is almost surely the size of a picture the *app* produces by stretching a small
photo (interpolation), not what the camera sees. Three reasons:
- File size. A real 20 MP JPEG is about 3-8 MB. Your Bluetooth photos are 54-75 KB, about 1/100 of that, and the
  glasses send the whole file. 75 KB fits a 0.3 MP (640x480) photo at normal quality, or at most a 1.2 MP
  (1280x960) photo at low quality.
- Parts. A camera this small in a glasses arm (the listing for this family says a ~1 MP SC101 sensor, 1280x720,
  L/docs/HARDWARE-VARIANT.md:13,32-34) can't hold 20 million pixels that each see real detail: with a lens of
  about 1-2 mm, the blur spot from diffraction alone is larger than a 20 MP pixel would be.
- Other units. Every measured unit gives 640x480 over Bluetooth; the vendor's app saved 1280x960, which is the
  same picture doubled.

**What you can realistically get:**
- **Bluetooth:** what the glasses' encoder makes. Most likely 640x480; we will know in one photo once the size is
  logged. If the probe finds a 7310 "original", that is the only real gain possible, and the evidence is against it.
  At the measured 48-65 KB/s, a true 20 MP file would take 1-2 minutes per photo, so no vendor sends one this way.
- **Cable:** only if a PC sees a drive *and* the glasses store photos. Signs point to no data wires and no storage.
- **Wi-Fi:** this model's listing says it has no Wi-Fi radio. The probe will check once.
- **Upscaling:** the app could add an optional "Enlarge 2x/4x" (like LensMoo's 1280x960). It makes the picture
  bigger and smoother, but **it adds no real detail**. Say so wherever it is offered.
