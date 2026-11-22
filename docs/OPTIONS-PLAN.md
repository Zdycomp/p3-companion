# P3 Companion 2.1: options plan

Scope: new options and polish on top of 2.0.0, which connects and works on the owner's Samsung.
Rules for every item below:

- Android framework only, no libraries. minSdk 30, targetSdk 35.
- Only glasses commands that are already in use or listed as safe in `docs/PROTOCOL-NOTES.md`. Nothing
  here sends a new node on the connect path. Anything that does is marked **EXPERIMENTAL** and is off by
  default.
- Every new setting is a `Prefs` property, saved the moment it changes, like the existing ones. The
  default for anything that already exists keeps today's behaviour.
- Priority: **MUST** (build this round), **SHOULD** (build if time allows), **NICE** (later).

Settings layout after this round (cards on `SettingsScreen`, in this order): Look, Glasses, Capture,
Import, Assistant, Privacy, Storage, Backup, About. Long cards get a small "Show more" row so the page
doesn't become a wall.

---

## 1. Look (Settings > Look)

All of these live in `Palette` / `Ui` (`Ui.kt`), are read in `MainActivity.onCreate`
(`Palette.resolve(this, prefs.theme)`), and apply with `a.recreate()` exactly like Theme does today.

| # | Name | Default | Exact behaviour | Touches | Pri |
|---|---|---|---|---|---|
| A1 | **Colors from your wallpaper** (dynamic color) | On (Android 12+); hidden on Android 11 | Builds the palette from the framework system colors (`android.R.color.system_accent1_*`, `system_neutral1_*`, `system_neutral2_*`, API 31). Dark: bg `neutral1_900`, card `neutral1_800`, cardAlt `neutral2_800`, text `neutral1_50`, muted `neutral2_200`, accent `accent1_200`, onAccent `accent1_800`, line `neutral2_700`. Light: bg `neutral1_10`, card `neutral1_0`, cardAlt `accent2_50`, text `neutral1_900`, muted `neutral2_600`, accent `accent1_600`, onAccent `accent1_0`, line `neutral2_100`. `good`/`warn`/`bad` stay fixed so status colors keep their meaning. When on, Accent (A2) is greyed out with "Using your wallpaper colors". Wallpaper change: Android recreates the activity on a theme overlay change, so nothing extra is needed. | `Palette.resolve` gets a `dynamic(context, dark)` branch; new `Prefs.dynamicColor` | MUST |
| A2 | **Accent color** | Violet (today's `#8B5CF6` / `#6D28D9`) | Chips: Violet, Blue, Teal, Green, Amber, Rose, Graphite. Each preset has a dark and a light value. `onAccent` is picked by contrast (white unless the accent's luminance is above 0.45, then near-black), so Amber stays readable. Applies to buttons, switches, the selected tab, progress bars, chips. | `Palette.copy(accent=…, onAccent=…)`; new `Prefs.accent` enum | MUST |
| A3 | **Pure black (AMOLED)** | Off | Only when the resolved look is dark (Dark, or Match phone at night): bg `#000000`, card `#0A0A0A`, cardAlt `#141414`, line `#1E1E1E`. Status and navigation bar follow (`window.statusBarColor`, `navigationBarColor`). Works with A1 and A2. | `Palette.resolve`; `MainActivity.onCreate` bar colors | MUST |
| A4 | **Text size** | Normal | Small 0.9×, Normal 1.0×, Large 1.15×, Extra large 1.3×. Multiplies every `setTextSize` in `Ui` (title 24, heading 17, body 15, caption 12/13/14, tab labels 12). Stacks with the phone's own font scale. Tab bar labels cap at 1.15× so five tabs still fit. | `Ui` constructor takes a `textScale`; `MainActivity.buildTabBar` | MUST |
| A5 | **Compact layout** | Off | Card inner padding 16→10 dp, gap between cards 16→8 dp, row min height 48→40 dp (never under 40, for touch targets), gallery spacing 4→2 dp. | `Ui.column`, `Ui.card`, `Ui.switchRow`, `Ui.choiceRow`; `GalleryScreen` | SHOULD |
| A6 | **Reduce motion** | Match phone | Match phone / On / Off. "Match phone" reads `ValueAnimator.areAnimatorsEnabled()`. When motion is allowed, add: 150 ms cross-fade when switching tabs, 120 ms press scale on primary buttons, progress bars animate between values. When reduced: none of these, and Live view's "recording" dot stops pulsing. | `MainActivity.show`, `Ui.button`, `LiveView` record dot | SHOULD |
| A7 | **Vibrate on taps** (haptics) | On | `performHapticFeedback(HapticFeedbackConstants.CONFIRM)` on a successful action (photo saved, connected, import done), `REJECT` on a failure message, `CLOCK_TICK` on switch and chip changes, `LONG_PRESS` when gallery selection starts. All go through one `Ui.haptic(view, kind)` that checks the pref. Uses view haptics, so no VIBRATE permission. | `Ui`, `GlassesScreen` (listener on state change), `GalleryScreen`, `ImportScreen` | MUST |
| A8 | **Tab bar style** | Icons and labels | Icons and labels / Icons only (labels become `contentDescription` only) / Labels only. Selected tab gets a pill background in the accent at 18% alpha, not only a color change. | `MainActivity.buildTabBar`, `show()` | SHOULD |
| A9 | **Live theme preview** | — | Under the Look card, a small sample card (heading, body, primary button, switch, status line in good/warn/bad) redrawn in place when A1-A5 change, before `recreate()`. Recreate happens when leaving Settings, so the page doesn't jump while choosing. | `SettingsScreen` | NICE |

Already exists, keep: Theme (Dark / Light / Match phone), Open the app on.

---

## 2. Glasses (Settings > Glasses)

| # | Name | Default | Exact behaviour | Touches | Pri |
|---|---|---|---|---|---|
| G1 | **Connect when the app opens** | Same as today's "Reconnect by itself" value (migrate once) | Splits today's `autoReconnect`. Only the `MainActivity.onCreate` block (`if (prefs.autoReconnect && … ) { GlassesService.start; reconnectSaved() }`) reads the new `Prefs.connectOnLaunch`. `autoReconnect` keeps its current meaning for drops and wake events, and `wantsLink` keeps using it unchanged. | `Prefs`, `MainActivity.onCreate` (one condition), `SettingsScreen` | MUST |
| G2 | **Keep connected in the background** | On (today's behaviour) | On: unchanged (the foreground service keeps the link, button photos save with the app closed). Off: when the activity stops, the link is closed **without** setting `userStopped` (a new `XkGlasses.pause()` that does `session++`, `dropLink("app in background")`, stops the service, and sets an in-memory `pausedForBackground` flag that `wantsLink` honours); `onStart` reconnects if `pausedForBackground`. Subtitle when off: "Button photos are saved only while the app is open." | New `XkGlasses.pause()/resumeIfPaused()`; `MainActivity.onStart/onStop`; `GlassesService.start` gate. **Regression risk: see REGRESSION-CHECKLIST R-G2.** | MUST |
| G3 | **Connection notification** | Detailed | Detailed: today's text ("Connected · battery 82%"), plus "charging" when charging. Minimal: "Glasses connected" only, no battery. The channel stays `IMPORTANCE_LOW`; a link to the phone's channel settings ("Hide this notification") opens `Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS` for `glasses_link`. | `GlassesService.statusText`, `note()` | MUST |
| G4 | **Photo saved notification** | With picture | Off / Text only / With picture. New channel `photos` (`IMPORTANCE_DEFAULT`, "Photos from your glasses"). Posted only when the app is **not** on screen (button photos while away). With picture: `BigPictureStyle` from a 512 px decode of the saved JPEG. Actions: Open (ACTION_VIEW on the Uri with read grant), Share. One notification per photo, grouped under "Glasses photos" with a summary after 3. | `XkGlasses.buttonPhoto` success path calls a new `Notes.photoSaved(uri)` on the main thread; `GlassesService` creates the channel | MUST |
| G5 | **Low battery alert** | On, at 20% | Threshold chips 10 / 15 / 20 / 30%. One heads-up notification (channel `battery`, `IMPORTANCE_HIGH`) when the reading first goes at or below the threshold while not charging; re-armed only after the level rises 5 points above it or charging starts. Also **Full charge alert** (default Off): one notification when charging and level reaches 100. Readings come from every `1003` reply (`afterReply`) plus a background read every 10 minutes while `state == READY` and not busy, not capturing, and `preview == OFF` (posted on `io` like `refresh()`). | `XkGlasses.afterReply` (call a `BatteryWatch.onReading`), new periodic read on `watchdog` that posts to `io`; `GlassesService` channel | MUST |
| G6 | **When you press the glasses' photo button** | Save to gallery | Save to gallery (today) / Save and open it (only if the app is on screen) / Save and ask the Assistant ("Describe what I'm looking at", spoken, needs AI key and network) / Don't save (release). "Don't save" is today's `saveButtonPhotos = false` path (7500 release), so the old switch becomes this choice (migrate: false → Don't save). | `XkGlasses.buttonPhoto`, `Prefs.saveButtonPhotos` → `Prefs.buttonPhotoAction` | MUST |
| G7 | **Shutter sound on the phone** | Off | Plays `MediaActionSound.SHUTTER_CLICK` on the phone when the glasses report the shutter fired (`57B1` push). Through the glasses' speakers if they're the media output. Note in subtitle: "The glasses' own beep can't be turned off" (node 1017 writes have no effect, PROTOCOL-NOTES §6). | `XkGlasses.onControl` "57B1" branch calls a main-thread hook `onShutter` | MUST |
| G8 | **Vibrate when a photo is saved** | On | Phone vibration pattern (2 short pulses) via `Vibrator.vibrate(VibrationEffect.createWaveform)` when a Bluetooth photo finishes saving, also in the background. Needs `android.permission.VIBRATE` (normal permission). Separate from A7 because it fires without the app on screen. | `XkGlasses.takePhoto/buttonPhoto` success → hook | SHOULD |
| G9 | **Open photo after taking it** | Off | After **Take photo** (in-app) succeeds and the app is on screen, opens it full screen (ACTION_VIEW, read grant). Off keeps today's "Photo saved" message plus the Open last photo button. | `GlassesScreen` listener reacting to a new `lastPhoto` | SHOULD |
| G10 | **Touch pad tap does** | Voice chat | Voice chat (today) / Take photo / Translate / Nothing. Applies to `C101`/`C107` while the app is on screen (today's `onTouch` hook). Take photo also works from the background **only when G2 is on**, because it needs no microphone. Voice options stay foreground-only (Android blocks starting the mic from the background). Replaces the `tapToTalk` switch (migrate: false → Nothing). | `MainActivity.onGlassesTapped`, `XkGlasses.onControl` | SHOULD |
| G11 | **Glasses storage** (read only) | — | Shows "Space on the glasses: N MB free of M MB" on the Glasses tab info card from `5712` (validated read, PROTOCOL-NOTES §3.1), read once in `readInfo()` after `5713`. Hidden if no answer. | `XkGlasses.readInfo`, `applyCounts` sibling | NICE |
| G12 | **Voice prompt language on the glasses** **EXPERIMENTAL** | Off / not shown | 2410 list + 2420 set. These only answer inside the setup order (§0.3), so it must be sent inside `signInWith` right after 1001/1003. Do not build until a phone log proves it; listed so no one adds it casually. | `XkGlasses.signInWith` (connect path) | NICE |
| G13 | **Turn the glasses off** **EXPERIMENTAL** | — | `102E` bindType 5 is INFERRED only. Two confirmations. Not before a device test. Never offer Unbind (bindType 4). | `XkGlasses`, `Xk.userBindData` | NICE |

Already exists, keep: Reconnect by itself, Show connection log, Run diagnostics, Forget these glasses.

---

## 3. Capture (Settings > Capture, and controls on the Glasses tab and Live view)

| # | Name | Default | Exact behaviour | Touches | Pri |
|---|---|---|---|---|---|
| C1 | **Photo names** | Date and time | Pattern for Bluetooth photos and live snapshots: `P3_IMG_20261122_143005` (today) / `2026-11-22 14.30.05` / `Glasses 0001` (counter in Prefs, padded to 4) / custom prefix + date. Applies to `saveJpeg` and `LiveViewScreen.saveSnapshot`. Cable import keeps its own "File names" setting. Names are sanitised like `Prefs.cleanAlbum`. | `XkGlasses.saveJpeg`, `LiveViewScreen.saveSnapshot`, new `Prefs.photoNaming` | SHOULD |
| C2 | **Album** | Shared "P3 Glasses" (today) | Today all three paths use `prefs.albumName`. Add **Separate folders**: Off (today) / On = photos go to `<album>/Bluetooth`, snapshots to `<album>/Snapshots`, live recordings to `Movies/<album>`. Move the album name field from the Import card to Capture, since it applies to everything. | `saveJpeg`, `saveSnapshot`, `MediaImporter.copyToGallery`, `PreviewRecorder.open` | SHOULD |
| C3 | **Live view recording quality** (label only) | — | There is no resolution command (PROTOCOL-NOTES §7.2), so this is **information, not a choice**: the Live view header shows "320×240 · 27 fps · recording as sent by the glasses" from the SPS size (`H264.kt` already parses it) and the measured frame rate. A one-line note in Settings explains why there's no quality picker. | `LiveViewScreen.update`, `H264` | MUST |
| C4 | **Self-timer** | Off | Off / 3 / 5 / 10 s chips next to **Take photo**. Counts down on screen (large number), with a `ToneGenerator.TONE_PROP_BEEP` each second on the phone (media stream, so it plays in the glasses when they're the output), then calls `takePhoto()`. Cancel button during the count. If the link drops during the count, cancel with a message. | `GlassesScreen`; `XkGlasses.takePhoto` unchanged | MUST |
| C5 | **Photo series** (burst) | 1 | 1 / 3 / 5 photos per press. Runs `takePhoto` again as soon as the previous one has saved (each takes about 10-15 s over Bluetooth, said in the subtitle: "a series of 5 takes about a minute"). Progress "Photo 2 of 5". Stops on the first failure and says how many saved. Needs a completion callback from `takePhoto` (today it only reports through `lastMessage`/`lastError`). | `XkGlasses.takePhoto(onDone)`, `GlassesScreen` | SHOULD |
| C6 | **Auto-save snapshots in Live view** | Off | Off / every 5 / 10 / 30 / 60 s while Live view is open, using the existing snapshot code. Counter in the header ("12 snapshots saved"). Stops when Live view closes. | `LiveViewScreen` (Handler loop calling `snapshot()`) | SHOULD |
| C7 | **Stop recording after** | 10 min | 1 / 5 / 10 / 30 min / No limit. Phone-side Live view recording stops by itself and saves; protects against a forgotten recording filling the phone. | `LiveViewScreen.toggleRecording`, `VideoPipeline.stopRecording` | SHOULD |
| C8 | **Record phone microphone with live video** | Off | Adds an AAC track from the **phone** mic (`AudioRecord` + `MediaCodec` AAC encoder into the existing `MediaMuxer`). Not the glasses mic: video plus glasses mic is refused as busy (§7.2), and `micWillStart()` closes the preview. Must force the phone's built-in mic, never SCO. | `PreviewRecorder` (MediaMuxer), `withMic` permission | NICE |
| C9 | **Keep screen on in Live view** | On | `FLAG_KEEP_SCREEN_ON` while Live view is open. | `LiveViewScreen` build/onHidden | SHOULD |

---

## 4. Import over the cable (Settings > Import)

Already exists, keep: Import when plugged in, Photos / Videos / Voice recordings, Free up the glasses
(delete after verified copy), Keep screen on while importing, File names, Choose a different glasses
drive, Forget imported history.

| # | Name | Default | Exact behaviour | Touches | Pri |
|---|---|---|---|---|---|
| I1 | **Notify when the import finishes** | On | Notification (channel `import`, default importance) "Imported 12 photos, 2 videos" with Open gallery, only when the app is not on screen when it ends. Uses `MediaImporter.summary()`. | `MediaImporter.start` onFinished, `ImportScreen` | SHOULD |
| I2 | **Only import files from the last** | Everything | Everything / 7 days / 30 days. Filters `DriveFile.modified` in `runImport` before copying. Skipped files are not added to the ledger, so changing it back imports them. | `MediaImporter.runImport` | NICE |
| I3 | **Ask before deleting from the glasses** | Off | When "Free up the glasses" is on, show one confirmation per import run with the count ("Delete 14 files from the glasses after copying?"). | `ImportScreen`, `MediaImporter` | NICE |

---

## 5. Assistant (Settings > Assistant, AiSetup cards)

Already exists, keep: AI service (Gemini / Claude), key, model, reply language, tap to talk (becomes
G10), use glasses audio, read photo answers aloud, translation pair.

| # | Name | Default | Exact behaviour | Touches | Pri |
|---|---|---|---|---|---|
| S1 | **Voice speed** | 1.0× | Slider 0.5-2.0 in 0.1 steps, with a "Play sample" button that speaks one sentence in the reply language. `tts.setSpeechRate` before every `speak`. | `Speech.speak`, `Prefs.ttsRate` | MUST |
| S2 | **Voice pitch** | 1.0 | Slider 0.5-2.0, same sample button. `tts.setPitch`. | `Speech.speak`, `Prefs.ttsPitch` | MUST |
| S3 | **Voice** | Phone default | Lists `tts.voices` for the reply language that don't need network (`!isNetworkConnectionRequired`) first, then the rest marked "online". `tts.setVoice`. Falls back to default if the saved voice disappears. | `Speech`, `AiSetup.optionsCard` | NICE |
| S4 | **Read answers aloud** | Voice chat and photos | Always / Voice chat and photos (today) / Voice chat only / Never. Generalises `speakPhotoAnswers`. | `Assistant.heard`, `askPhoto`, `Prefs.speakPhotoAnswers` migrate | SHOULD |
| S5 | **Remember the last** | 12 turns | 0 (each question alone) / 6 / 12 / 24 turns. Replaces the constant `AiWire.HISTORY_LIMIT` in `Assistant` lines 124 and 139. Note: more turns send more text each time. | `Assistant.heard`, `AiWire.HISTORY_LIMIT` | MUST |
| S6 | **Quick photo questions** (editable) | The 4 presets today | List of up to 8 label + question pairs; edit, reorder (up/down), delete, add, Reset to defaults. Stored as a JSON array (the app's own `Json`). The reply-language rule stays enforced by `AiWire` system prompt, not by the preset text. | `AiWire.photoPresetLabels/photoPreset`, `AssistantScreen` photo section, `Prefs.photoPresets` | MUST |
| S7 | **Keep listening after an answer** (conversation mode) | Off | After the spoken answer ends, starts listening again once; stops after one silent turn or a tap. Foreground only. | `Assistant.endTurn` | SHOULD |
| S8 | **Model picker** | Current default | Chips with the known models for the chosen service plus "Other…" (today's text field). `AiWire.cleanModel` stays the validator. | `AiSetup.optionsCard` | SHOULD |
| S9 | **Show a "listening" chime** | On | `ToneGenerator` short beep when the recognizer is ready, so you know through the glasses when to speak. | `Speech.listen` (`onReadyForSpeech`) | SHOULD |

Not feasible: voice chat started from a glasses tap while the phone is locked or the app is in the
background. Android 11+ does not allow a background app to start using the microphone (while-in-use
restriction), even from a foreground service started later. Say this in the tap setting's subtitle.

---

## 6. Privacy (Settings > Privacy)

| # | Name | Default | Exact behaviour | Touches | Pri |
|---|---|---|---|---|---|
| P1 | **Lock the app** | Off | Uses the framework `android.hardware.biometrics.BiometricPrompt` (API 28+) with `setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)` (API 30+, matches minSdk), so fingerprint, face or the phone's PIN/pattern all work. Option disabled with an explanation if `KeyguardManager.isDeviceSecure` is false. A full-screen cover view hides content until success; Cancel closes the app (`finish()`). Needs `<uses-permission android:name="android.permission.USE_BIOMETRIC"/>`. Lock triggers on `onStart` only when the app was in the background longer than the timeout, **not** on `onResume` (permission dialogs, the file picker and Android's pairing popup pause the activity and must not re-lock it). | `MainActivity.onStart/onStop`, new `AppLock` | MUST |
| P2 | **Lock after** | Immediately | Immediately / 1 min / 5 min / 15 min in the background. | `AppLock` | MUST |
| P3 | **Hide the app's content in Recents and screenshots** | Off | `FLAG_SECURE` on the window. Subtitle warns it also blocks screenshots of Live view. | `MainActivity.onCreate` | SHOULD |
| P4 | **Hide keys completely** | Off | Off: masked as today (`KeyFinder.mask`: first 4 + last 4). On: shows only "Saved" with no characters. Applies to the glasses key and AI keys, and to Diagnostics. | `KeyFinder.mask` callers, `AiSetup`, `GlassesScreen`, `DiagnosticsScreen` | SHOULD |
| P5 | **Clear Assistant history** | — | Button: `assistant.clearChat()` + `clearTranslations()`, confirm. | `SettingsScreen` | MUST |
| P6 | **Clear connection log** | — | Button: empties `XkGlasses.log` and `linkLosses`. | new `XkGlasses.clearLog()` | SHOULD |
| P7 | **Remove glasses key / Remove AI key** | — | Separate danger buttons with confirm. Removing the glasses key while connected disconnects first. | `Prefs.userKey`, `Prefs.setAiKey` | SHOULD |
| P8 | **Erase everything** | — | Two confirmations, then `ActivityManager.clearApplicationUserData()` (removes settings, keys, ledger, log; photos in the gallery stay). | `SettingsScreen` | NICE |

---

## 7. Storage (Settings > Storage)

| # | Name | Default | Exact behaviour | Touches | Pri |
|---|---|---|---|---|---|
| T1 | **Storage used** | — | Three lines, computed on a background thread when the card opens: "Photos and videos from the glasses: 1.2 GB (342 files)" (sum of `MediaStore.MediaColumns.SIZE` where `RELATIVE_PATH` contains `/<album>/`, across Images, Video, Audio), "App data: 3 MB" (`filesDir`), "Temporary files: 40 MB" (`cacheDir`). | new `StorageInfo` | MUST |
| T2 | **Clear temporary files** | — | Deletes `cacheDir` contents and runs `PreviewRecorder.cleanStale`. Disabled (with reason) while a key search, an import or a Live view recording is running, because `KeyFinder` unzips bug reports into `cacheDir`. | `SettingsScreen`, `KeyFinder`, `PreviewRecorder.cleanStale` | MUST |
| T3 | **Glasses storage** | — | Same line as G11 when connected. | `XkGlasses` | NICE |

---

## 8. Gallery (Gallery tab, plus Settings > Gallery for defaults)

| # | Name | Default | Exact behaviour | Touches | Pri |
|---|---|---|---|---|---|
| L1 | **Sort** | Newest first | Newest / Oldest / Largest / Name. Chip row next to the existing Show filter; remembered. | `GalleryScreen.load` (today `sortedByDescending { it.date }`) | MUST |
| L2 | **Grid size** | 3 columns | 2 / 3 / 4 / 5 columns (`GridView.numColumns`, today fixed 3). Thumbnail decode size follows the column width so the `LruCache` stays bounded. Pinch is NICE later. | `GalleryScreen` | MUST |
| L3 | **Source filter** | All | All / Bluetooth photos / Cable imports / Live view (by folder from C2, or by name prefix `P3_IMG_` vs imported names until C2 ships). Joins the existing photo/video/audio filter. | `GalleryScreen.load` | SHOULD |
| L4 | **Select several** | — | Long-press starts selection (today long-press opens the single-item menu; that menu moves to a ⋮ on the full-screen view and to "Select" mode with one item). Tap toggles; a bar shows "3 selected" with Share, Delete, Select all, Cancel. Share: `ACTION_SEND_MULTIPLE` with `ClipData` and `FLAG_GRANT_READ_URI_PERMISSION`, MIME `*/*` when kinds are mixed. Delete: the existing `MainActivity.requestDelete(uris)` (Android's own confirmation via `createDeleteRequest`), then refresh. Back exits selection. | `GalleryScreen`, `MainActivity.requestDelete`, `onBackPressed` | MUST |
| L5 | **Favourites** | — | Star action via `MediaStore.createFavoriteRequest` (API 30), filter "Favourites". | `GalleryScreen` | NICE |
| L6 | **Ask the Assistant about this photo** | — | Action on a photo: sets `assistant.photoUri` and opens the Assistant photo section (same as `REQ_IMAGE` result). | `GalleryScreen`, `MainActivity` | SHOULD |
| L7 | **Date headers** | Off | "Today / Yesterday / Nov 18" section headers. Needs switching from `GridView` to a `ListView` of rows; do after L1-L4. | `GalleryScreen` | NICE |

---

## 9. Outside the app

| # | Name | Default | Exact behaviour | Touches | Pri |
|---|---|---|---|---|---|
| X1 | **Home screen quick actions** | Always present | Static shortcuts (`res/xml/shortcuts.xml`, `<meta-data android:name="android.app.shortcuts">` on `MainActivity`): **Take photo**, **Live view**, **Voice chat**. Each is an explicit intent to `MainActivity` with action `com.zdycomp.p3companion.action.PHOTO` / `.LIVE` / `.VOICE`. Handling: new `handleAction(intent)` called from `onCreate` and `onNewIntent` **before** `handleShared` (whose `else -> return` ignores them today). Take photo: if READY, `takePhoto()` and show Glasses tab; if not, `withBluetooth { connect saved }` then a one-shot listener takes the photo when READY (20 s limit, then a message). Live view: `showLiveView()` after the same connect step. Voice chat: Assistant tab, `withMic { assistant.voiceChat() }`. `ShortcutManager.reportShortcutUsed(id)` on use. Icons: monochrome vector drawables. | `AndroidManifest.xml`, new `res/xml/shortcuts.xml`, 3 drawables, `MainActivity` | MUST |
| X2 | **Quick Settings tile: Glasses photo** | User adds it | `PhotoTileService : android.service.quicksettings.TileService`, declared with `BIND_QUICK_SETTINGS_TILE`, `QS_TILE` intent filter and `ACTIVE_TILE` meta-data. State: Active when `XkGlasses.state == READY`, Inactive when idle, Unavailable when no glasses are saved. Subtitle (API 29+): battery %, or "Taking…" while busy. `onClick`: READY → `XkGlasses.get(this, Prefs(this)).takePhoto()` (no activity needed; the link already runs under `GlassesService`). Not connected → open the app on the Glasses tab with the PHOTO action: on API 34+ use `startActivityAndCollapse(PendingIntent)`; below 34 the `Intent` overload (the Intent overload throws on API 34+ for apps targeting 34+). Wrap in `unlockAndRun` when `isLocked`. `XkGlasses` listener calls `TileService.requestListeningState` on state changes. | new `PhotoTileService.kt`, manifest, `XkGlasses` listener | MUST |
| X3 | **Home screen widget** | — | 2×1 `AppWidgetProvider` with `RemoteViews`: battery %, connection dot, Take photo button (PendingIntent to a receiver that calls `takePhoto()` when READY, otherwise opens the app with the PHOTO action). | new `GlassesWidget.kt`, `res/xml`, layout | NICE |

---

## 10. Backup and About

| # | Name | Default | Exact behaviour | Touches | Pri |
|---|---|---|---|---|---|
| B1 | **Save settings to a file** | — | `ACTION_CREATE_DOCUMENT`, `application/json`, name `P3-Companion-settings-2026-11-22.json`. Content: `{"format":"p3c-settings","version":1,"app":"2.1.0","prefs":{…}}`. **Never written:** `user_key`, every `ai_key_*`, `drive_uri`, `drive_label`, `ble_addr`, `ble_name`, `link_method`, `user_stopped`, `preview_action`, `asked_*`, `last_import`. Subtitle says keys are never saved to the file. | new `SettingsBackup` (pure Kotlin export/import over a `Map<String, Any?>` so it can be unit-tested), `MainActivity` request code | MUST |
| B2 | **Restore settings from a file** | — | `ACTION_OPEN_DOCUMENT`. Accepts only `format == "p3c-settings"` and `version <= 1`. Applies only whitelisted keys whose JSON type matches the expected type; every value goes through its `Prefs` setter (so `cleanAlbum`, `Languages.byName`, enum fallbacks, `AiWire.cleanModel` still apply). Unknown keys are ignored and counted. Shows "Restored 23 settings, ignored 2" then `recreate()`. | `SettingsBackup`, `Prefs` | MUST |
| B3 | **Reset settings to default** | — | Clears everything except the glasses key, AI keys, saved glasses, drive and import ledger. Confirm. | `Prefs` | SHOULD |
| B4 | **About screen** | — | Its own screen (index after `SCREEN_LIVE`, back returns to Settings): app name and version (`versionName` + `versionCode`), "Updated on" (`PackageInfo.lastUpdateTime`), glasses model and firmware when known (`XkGlasses.model/firmware`), What's new list for the current version, Credits and third-party notices (text from `THIRD_PARTY_NOTICES.md` copied into a string), "Not made by or connected to NovaSight, Shenju or LensMoo", button "Copy app and glasses info" (version, Android version, phone model, glasses firmware; never key or full address). | new `AboutScreen.kt`, `MainActivity` screens list, `SettingsScreen` About card becomes a row that opens it | MUST |
| B5 | **What's new after an update** | On | On first launch of a new `versionCode` (compare with `Prefs.lastSeenVersion`), a one-time dialog with the 3-6 headline changes and "See all" (opens About). Not shown on a fresh install. | `MainActivity.onCreate`, `Prefs` | SHOULD |

---

## 11. Not feasible (say so in the UI where the option would be expected)

| Item | Why |
|---|---|
| Turn off the glasses' own beeps / shutter sound | `1017` writes are acked but change nothing (PROTOCOL-NOTES §6). |
| Choose live view or recording resolution | No such command; 320×240 is what arrives (§7.2). C3 shows it instead. |
| Glasses mic audio in live view recordings | Glasses refuse video and mic together (§7.2). C8 uses the phone mic. |
| Download videos stored on the glasses over Bluetooth | No known transfer path (§3). Cable import covers it. |
| Set the glasses' clock | `1007` is not implemented in the firmware. |
| Voice chat from a tap while the phone is locked | Android blocks starting the mic from the background. |
| Firmware updates | Brick risk, never send OTA nodes (§7.2). |
