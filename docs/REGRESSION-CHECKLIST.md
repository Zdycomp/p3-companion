# P3 Companion: regression checklist for the connection path

Version 2.0.0 connects, signs in, takes photos and streams on the owner's Samsung. This file lists what
the 2.1 polish work must not change, the options in `OPTIONS-PLAN.md` that come close to it, the JVM
tests to add first, and a short phone check to run after each update.

Rule for this round: **no behaviour change inside the sections marked FROZEN.** Moving code only to make
it testable is allowed if the moved code is byte-for-byte the same logic and the new tests pass before
and after the move.

---

## 1. FROZEN: `XkGlasses.kt`

### Connect and sign-in (in call order)

| Function | Behaviour that must stay |
|---|---|
| `connect(address, name, manual, withoutKey, testPhoto)` | Returns at once unless `state == IDLE`. Saves address/name, clears `userDisconnect`, `gaveUp`, `prefs.userStopped`, messages, `bindVerified`, `signedIn`, `keylessReport`, `infoRead`. Bumps `session`, removes pending `retry`, calls `GlassesService.start(app)`, then on the single `io` thread: `remoteDevice` → `ensureBonded` → `openSocket` → `startSession` → `signInWith(key)` or `probeWithoutKey` → `state = READY` → `readInfo()`. A cancelled session (`mine != session.get()`) drops its own link and never touches state. On failure: `dropLink`, `IDLE`, `lastError`, and `scheduleReconnect` only if the `LinkException.retry` flag allows it. |
| `remoteDevice` | No adapter → non-retryable. Bluetooth off → retryable message. Address upper-cased. |
| `ensureBonded` | Already bonded → return. Not bonded and not `manual` → non-retryable "aren't paired" (auto-reconnect must **never** pop a pairing dialog). LE-only entry refused. 45 s pairing wait polling every 500 ms and honouring cancel; reason codes 3/8, 4/6, 1/2 mapped to messages; `Thread.sleep(1_500)` after bonding so Android finishes its headset link. Receiver always unregistered. |
| `cancelDiscovery` | Only with BLUETOOTH_SCAN on 31+. |
| `attempts` | Exactly four, in this order and with these limits: `sdp-secure` 12 s, `ch8-secure` 8 s (reflection `createRfcommSocket(8)`), `ch8-insecure` 8 s, `sdp-insecure` 12 s. SPP UUID `00001101-0000-1000-8000-00805F9B34FB`. |
| `openSocket` | `dropLink("new connection")` first (never two links on channel 8). Saved `prefs.linkMethod` tried first. Gap 700 ms between attempts, 1 500 ms after a timeout. Every failed socket closed. Winner saved to `prefs.linkMethod`. All failed: "another app is holding their link" message only when `fast >= 2 && slow == 0` (fast = failed under 2 s); otherwise "didn't answer". `lastConnectReport` always set. |
| `connectBounded` | Hard time limit via `watchdog`; abort only by `close()` from the watchdog thread; `pendingSocket` set during connect so `disconnect()` can abort it. |
| `startSession` | `0001` action 3, request id **1**, 61 random alphanumerics; sleep 200 ms; `0002` action 3, request id **4**, 64 random bytes; sleep **1 500 ms**; cancel check. |
| `signInWith` | `102E` action 3, `Xk.userBindData(key)`, 2 500 ms, `logData = false` (key never logged). `REFUSED` → non-retryable. Then **immediately** `7110` (1 200 ms, only answers here), `1001` (2 000 ms), `1003` (2 500 ms). An error code on 1003/1001 → non-retryable "didn't accept your key". No answer to both and bind not `BOUND` → retryable "opened the link but didn't answer". Only then `bindVerified = signedIn = true`. **Nothing may be inserted between 102E and 1003** (setup-order-only nodes, PROTOCOL-NOTES §0.3). |
| `probeWithoutKey` | Read-only `1003`, `1001`, `5713`; `57B0` only with `testPhoto`, followed by `release()` if a photo is announced. |
| `readInfo` | `5713` once per session (`infoRead`). G11 (`5712`) may be added **after** this read only. |
| `signIn()` | Signs in on an open keyless link; no-op if no link or already signed in. |

### Link, framing and dispatch

| Function | Behaviour that must stay |
|---|---|
| `Link` (writer/reader threads, `outQ` 512) | Writer sets `writeStartedAt`; `trySend` closes the link on a full queue; `close()` idempotent; `onLinkLost` only for the current link. |
| `tick()` (every 500 ms) | `parser.skipStalled()` after 1 500 ms of silence with a partial frame; drops the link if one write hangs over 5 s. |
| `request()` | One `Pending` per **node**, matched by request id; timeout adds the id to `staleIds` (cleared above 64); `linkDown` sentinel throws "Lost the connection". |
| `nextRequestId()` | Wraps at 0xFFFF and never goes below 0x10 (ids 1 and 4 belong to the session start). |
| `handle()` | Media: skip acks; on fragment 3 send `Xk.mediaAck` **before** feeding the demuxer. Control: skip acks and our own frames; send `Xk.ack(order, f.order)` for every device frame; drop an exact repeat (same order + request id) within 1 500 ms. |
| `onControl()` | Reply matching: exact id → deliver; id in `staleIds` → ignore; action `0x8002` or `-1` with another id → deliver with a log line. `7320` routing: a waiter first, else hold as `pendingAnnounce` while a download runs, else `buttonPhoto`. `C101`/`C107` → `onTouch` on main. `57B1`, `5790`, `5710`, `5760` handling. |
| `afterReply()` | Every `1003` updates battery and charging. G5 hooks in here **read-only**. |
| `takePhoto` / `buttonPhoto` / `download` / `release` | `57B0` 4 s, `7320` 12 s; `download` always ends with `7500` (finally block), `ELEMENT_RETRIES = 1`, `ELEMENT_IDLE_MS = 4 000`, `ELEMENT_CAP_MS = 15 000`, `waitQuiet()` before a retry; preview closed during a photo and reopened after. Button photos with saving off are released with `7500` (glasses otherwise stay blocked). |
| `openPreviewNow` / `closePreviewNow` / `micWillStart` | 5720 `[01]` with the saved action, flip 3↔2 on codes 3/4/6 and save the winner; close always sends `5720 [00]` with the saved action; mic start closes the preview. |
| `disconnect()` | `session++`, `userDisconnect`, `prefs.userStopped = true`, cancel retry and wake lock, close `pendingSocket`, `dropLink`, `IDLE`, preview off, stop the service. |
| `dropLink` / `onLinkLost` / `wakeWaiters` | Waiters woken at once; link losses kept (last 20); reconnect scheduled unless the user disconnected. |
| `wantsLink` | `!userDisconnect && !gaveUp && !userStopped && address != null && userKey != null && (state != IDLE \|\| autoReconnect)`. G2 may add one more `&& !pausedForBackground` term and nothing else. |
| `reconnectSaved` | Only when `IDLE`, key saved, Bluetooth on, and still bonded; always `manual = false`. |
| `scheduleReconnect` / `onGlassesSeen` / `linkDownHint` | Backoff `2, 5, 10, 20, 40, 60 s` for `ACTIVE_RETRY_MS = 3 min` under the `p3c:reconnect` wake lock, then wait for a wake event; wake events retry after 1.5 s. |
| `XkGlasses.get` | One instance per process. The QS tile (X2) and widget (X3) must use `get()`, never a second instance. |

## 2. FROZEN: `XkProtocol.kt` (`object Xk`)

`crc16`, `Frame.encode`, `control()` (`[len BE2][00][data]` layout, identical to `[fmt][len LE]` under
256 bytes), `ack()`, `mediaAck()`, `userBindData()` (bindType 2 only; dangerous types refused),
`isValidUserId`, `elementCount`, `battery`, `jsonObject`, `bindOutcome`, `errorText`, `Parser` (feed,
skipStalled, reset, plausibleLength, compact, `maxControlPayload = 32 KB`), `MediaDemux`,
`PhotoAssembler`. Already covered by `XkProtocolTest` and `XkVectorsTest`; none of the options needs a
change here.

## 3. FROZEN: `GlassesService.kt`

- `onStartCommand`: `startForeground` **first** (type `CONNECTED_DEVICE` on 30+), then `ACTION_STOP`,
  then stop if `!wantsLink && IDLE`, else `reconnectSaved()`, `START_STICKY`.
- Receivers: `ACL_CONNECTED` (ours → `onGlassesSeen`), `ACL_DISCONNECTED` (ours → `linkDownHint`),
  `STATE_CHANGED` ON / TURNING_OFF, `USER_PRESENT`. `ours()` compares with the saved address.
- `onGlassesChanged` stops the service when not wanted; notification only re-posted when text changes.
- `start()` no-op while `running`. `GlassesWakeReceiver` gates on autoReconnect, key, address,
  `!userStopped` and address match.
- G3/G4/G5 add channels and change `statusText()` wording only. Channel id `glasses_link` and
  `NOTE_ID = 8` stay.

## 4. FROZEN: `KeyFinder.kt`

`find` (stream first, zip-file fallback into `cacheDir`), `scanAny` (zip / gzip / snoop / raw, depth
limit), `scanSnoop` (record by record, joins split ACL packets), `scanWindow` (pattern `102E` + length +
bind type + 32 lowercase hex), `Result.filtered` (`aclPackets > 20 && truncated*2 > aclPackets`),
`explainMiss`, `mask` (4 + dots + 4). T2 "Clear temporary files" must not run while `readKeyFrom` is
active (it deletes the zip the fallback is reading).

## 5. FROZEN: `MainActivity` permission and start-up flow

| Piece | Behaviour that must stay |
|---|---|
| `onCreate` reconnect block | Starts `GlassesService` and `reconnectSaved()` only with saved address, saved key, Bluetooth permission and `!userStopped`. G1 changes only the `autoReconnect` term to `connectOnLaunch`. |
| `withPermissions` | Missing required + optional not yet asked this visit; "don't ask again" detection = every missing required was asked before (`prefs.wasAsked`) and `!shouldShowRequestPermissionRationale` → dialog to app settings; `markAsked` before requesting; one `pendingPermission`. |
| `bluetoothPermissions` / `scanPermissions` | 31+: CONNECT (and SCAN for search); Android 11: none / FINE_LOCATION. |
| `withBluetooth` | POST_NOTIFICATIONS optional on 33+ (never blocks); `bluetoothReady()` asks to turn Bluetooth on. |
| `withScan` | Android 11 also needs Location services on. |
| `withMic` | RECORD_AUDIO required; POST_NOTIFICATIONS optional for recording. |
| `onRequestPermissionsResult` | Only `REQ_PERMS`; runs the action only if every required permission is granted. |
| `handleShared` / `readKeyFrom` | SEND / SEND_MULTIPLE; images skipped; `setIntent` reset afterwards. X1 shortcut actions must be handled in a separate function **before** it, because its `else -> return` ignores other actions. |
| `onStart` / `onStop` | `onTouch` hook set on start, cleared on stop; a running voice turn stopped on stop. P1 (app lock) and G2 hook in here; they must not run on `onResume` (permission dialogs, pairing popup, file pickers all pause the activity). |
| `launchMode="singleTop"` | Shortcut and tile intents arrive through `onNewIntent`. |

Options that touch frozen code, and the one allowed change each:

| Id | Allowed change |
|---|---|
| R-G1 | `MainActivity.onCreate`: `prefs.autoReconnect` → `prefs.connectOnLaunch` in that one condition. |
| R-G2 | New `XkGlasses.pause()` / `resumeIfPaused()` and one extra `wantsLink` term. `disconnect()` untouched. `GlassesService.start` gate only when the pref is off. |
| R-G5 | `afterReply("1003")` calls a read-only hook; periodic read posted to `io` only when READY, not busy, not capturing, preview OFF. |
| R-G6/G7/G8 | Hooks after the existing success lines in `buttonPhoto` / `takePhoto` and in the `57B1` branch. The "Don't save" path is today's release path unchanged. |
| R-C5 | `takePhoto(onDone: ((Boolean) -> Unit)? = null)` called from the existing `finally`. |
| R-X1/X2 | New entry points only call existing public functions (`connect`, `reconnectSaved`, `takePhoto`, `showLiveView`). |

---

## 6. JVM tests to add (no device, plain JUnit, `src/test`)

Existing: `XkProtocolTest`, `XkVectorsTest` (captured vectors in `src/test/resources/vectors`),
`AiWireTest`. Add in this order:

1. **`LinkPolicyTest`** (extract pure functions to a `LinkPolicy` object, no logic change):
   - attempt order: saved method first, the rest in the frozen order; unknown saved method keeps the order.
   - attempt timeouts are 12/8/8/12 s.
   - failure message: `fast=2, slow=0` → "another app is holding"; `fast=1`, or any `slow` → "didn't answer".
   - backoff sequence `2,5,10,20,40,60,60…` s and the 3-minute active window.
   - `wantsLink` truth table (each term false → false; `IDLE` with `autoReconnect` off → false; not IDLE → true).
   - `nextRequestId` wrap: from 0xFFFF goes to 0x10, never 1 or 4.
2. **`RxRulesTest`** (extract `shouldAck(frame)`, `isRepeat(lastKey, lastAt, frame, now)`, `matchReply(pending, frame, staleIds)`):
   - device control frame → ack with its order; ack frames and our own frames → no ack.
   - media fragment 3 → media ack; fragments 0/1/2/5 → none.
   - same order+id within 1 500 ms dropped, at 1 501 ms delivered, different id delivered.
   - reply with matching id delivered; stale id ignored; other id with action 0x8002 delivered; push with action 0x8001 and other id not delivered.
3. **`SessionBytesTest`**: `Xk.control` for `0001` id 1 and `0002` id 4 encode with the right node, action 3 and length; `userBindData` for a known key matches `reply_102E_bind`/`requests_app_captured` vectors; the bind frame encodes identically to the captured LensMoo request.
4. **`KeylessSummaryTest`**: move `keylessSummary()` text building to a pure function; all answered / none answered / mixed.
5. **`PrefsDefaultsTest`**: give `Prefs` an internal constructor taking `SharedPreferences`, and a small in-memory `FakeSharedPreferences` in the test. Lock today's defaults: theme DARK, autoImport true, deleteAfterImport false, naming DATE, album "P3 Glasses", autoReconnect true, saveButtonPhotos true, showLog false, tapToTalk true, glasses audio true, previewAction 3, reply language English. Then the 2.1 migrations: `connectOnLaunch` defaults to the stored `autoReconnect`; `saveButtonPhotos=false` maps to "Don't save"; `tapToTalk=false` maps to "Nothing".
6. **`SettingsBackupTest`**: export never contains `user_key`, any `ai_key_*`, `drive_uri`, `ble_addr`, `link_method`, `user_stopped`, `asked_*`; import rejects wrong `format` and a higher `version`, ignores unknown keys, rejects wrong types, sends album through `cleanAlbum`; export → import round trip equals the input for every whitelisted key.
7. **`PaletteTest`**: switch `Palette` colors from `Color.parseColor` to `0xFF…` int literals (with `isReturnDefaultValues`, `parseColor` returns 0 on the JVM), then check text/bg and onAccent/accent contrast ≥ 4.5:1 for every accent preset in dark, light and AMOLED.
8. **`KeyFinderTest` additions**: key in a zip inside a zip; gzip snoop; `filtered` at exactly 20 and 21 packets; `mask` on 7 and 8 characters.

Run with `./gradlew testDebugUnitTest`. All existing tests must still pass unchanged.

---

## 7. Phone check after each update (about 10 minutes)

Close LensMoo first. Turn on Settings > Show connection log.

1. Open the app: glasses connect by themselves within 15 s (log: `Link open`, `102E reply`, `Signed in, battery N%`).
2. Disconnect, then Connect, 3 times: all connect; Disconnect stays disconnected (no auto-retry).
3. Connect then Cancel at once: stays "Not connected".
4. Take photo 3 times: all 3 saved, each under about 15 s.
5. Press the glasses' photo button: photo arrives in Gallery within 20 s.
6. Home, lock phone 2 minutes, press the button again: photo still arrives (unless "Keep connected in the background" is off).
7. Live view: picture within 5 s, Close shows `→ 5720 00` in the log, Take photo still works after.
8. Turn the glasses off 20 s, back on: reconnects within 30 s by itself.
9. Turn phone Bluetooth off and on: reconnects by itself.
10. Tap the glasses' touch pad with the app open: voice chat starts.
11. New in 2.1: change Accent, Pure black, Text size: the app redraws, glasses stay connected.
12. New in 2.1: Quick Settings tile and the Take photo shortcut each save a photo.
13. New in 2.1: with Lock the app on, connect from a fresh start: the lock appears once; allowing a permission or the pairing popup does not lock again.
14. Diagnostics > Run the checks > Share report once.

If any line fails: Diagnostics > Share report, with the line number.


---

## 8. 3.1.0: additive changes near frozen code

None of these touch connect, pairing, sign-in, the link, framing or the photo transfer.

| Where | Change |
|---|---|
| `handle()` media branch | Two counters (`mediaFramesSeen`, `mediaFrag5`) before the existing logic. |
| `onControl()` | New `57A0` branch: records the video-preview state and logs it; changes nothing else. `57B1` skips `onShutter` only while a Snapshot-view photo is taken. |
| `applyInfo()` | Also keeps the `preview_*` fields of 1001. |
| `takePhoto(onDone, announce)` | R-C5. `announce = false` (Snapshot view) skips `lastPhoto`, the message and the photo-saved hook; everything else is the same path. |
| `openPreviewNow` / `closePreviewNow` | Only when the live view test saved a non-5720 command (`prefs.livePath`) is that command used; otherwise the 5720 path is byte-for-byte the same. A 5720 result found by the test is stored in `previewAction`. |
| `runLiveProbe` | New. Holds `busyFlag` while it runs; refuses while recording or capturing. |

Phone check additions: Live view on glasses without video → test runs after 6 s → Snapshot view; Diagnostics › Test
voice reports the service; one voice question shows a timing line in the connection log.

---

## 9. 3.3.0: changes to frozen code, and why

Evidence: `docs/LOG-2026-11-23.txt` and the captured 57A0 answer in `src/test/resources/vectors/replies_captured_misc.hex`.

| Where | Change |
|---|---|
| `onControl()` | Reply matching moved to `Xk.matchReply`: exact id → deliver; device-numbered frame (payload action `0x8004`) → deliver whatever its id; echoed id of a request **on the same node** that timed out in the last 15 s → ignore; any other frame for the node → deliver. Replaces "action `0x8002` or `-1` with another id". State nodes 5710/5711/57A0/5750/5790 also go through the push handling when they answer (error-code answers don't). |
| `request()` | `staleIds` keeps id → node and time. |
| `handle()` | Acks built by `Xk.ackFor` (same bytes as before). Counts bytes read; 2B and unknown-head frames go to the live view path while preview is wanted. Touches are ignored during the live view test. |
| `Parser` | Optional `onSkip` observer and `lenient` mode (sniffer); both off by default, so default parsing is byte-for-byte the same. |
| `signInWith` | 102E now completes on its device-numbered answer; at least 300 ms from sending 102E to 7110. Order unchanged. |
| `connect` | Records `readySince`; clears the per-node log counter. |
| `openPreviewNow` / `closePreviewNow` | Sniffer on/off; 5710 push awaited after an open (busy → wait 4 s, open once more). |
| `AudioRoute.acquire` | Refuses SCO (phone mic instead) while connecting, during a photo transfer or the live view test, and in the first 3 s of a link. |

Phone check additions: one photo shows no "no answer" line; the live view test log has "Sniffer" and "heard for"
lines with byte counts; a voice question logs "Voice mic: …" and a timing line with the mic.
