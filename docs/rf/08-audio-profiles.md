# 08 — Audio over the glasses (HFP/SCO mic, A2DP TTS, AVRCP buttons, speech, m4a)

Specialist 08 of 10. Framework APIs only, minSdk 30 / target 35, Samsung phone.
Abbreviations: SDK = /home/claude/beneduzi/xk-one-pro-sdk, LF = /home/claude/joaosouz4dev/lensmoo-crash-fix.

## 1. Findings (with sources)

| # | Finding | Source | Conf. |
|---|---|---|---|
| F1 | Glasses expose HFP (RFCOMM ch 1, SCO for the mic), A2DP (speakers, TTS), AVRCP (media keys) — all marked validated. | SDK docs/HARDWARE.md:36-38; PROTOCOL.md:10 | High |
| F2 | Vendor app records the mic as `AudioRecord` source 7 (`VOICE_COMMUNICATION`), 16000 Hz, mono, PCM16, using legacy `startBluetoothSco` + `setBluetoothScoOn(true)` + `setMode(3)`, waiting on SCO state with a 10 s timeout and retries. | LF docs/AUDIO-RESEARCH.md:11 | High (static) |
| F3 | Physically confirmed on an XK-family unit: 10 s SCO capture, device type 7 (`TYPE_BLUETOOTH_SCO`), 16 kHz mono, clear audio. | LF AUDIO-RESEARCH.md:160; HARDWARE-VARIANT.md:56 | High |
| F4 | API 31+ route: `setCommunicationDevice()` with the glasses entry from `availableCommunicationDevices`, wait for `OnCommunicationDeviceChangedListener`, verify `AudioRecord.routedDevice`, watch `AudioRecordingCallback.isClientSilenced`, `clearCommunicationDevice()` on cleanup. Request acceptance ≠ route ready. | LF AUDIO-RESEARCH.md:38-44 | High |
| F5 | **Conflict:** turning SCO on closed the live video preview (status 0, 0 video frames). Reopening preview after SCO gave 0 frames. Protocol-audio (5750) active + camera request → status 2 "device busy". Treat mic and camera stream as mutually exclusive. | LF AUDIO-RESEARCH.md:161-162, 218-219; HARDWARE-VARIANT.md:58-60 | High for preview; **unknown for on-glasses 5780 recording** |
| F6 | A second mic path exists without SCO: node 5750 `toggleAudio(1/0)`, 5760 state notify, 5770 query; data arrives as framed Opus (0x28 length byte + 40-byte packet, 16 kHz mono, 320 samples = 20 ms). Confirmed 0.44 s and 6.8 s decoded on hardware. Side effect: LensMoo auto-opens its recording screen. | LF AUDIO-RESEARCH.md:14, 110-114, 175-179, 218 | Medium (belongs to protocol team) |
| F7 | 2 clicks on the glasses button = voice memo stored on the glasses (`record_num` in 5713). That file is imported, not streamed. | SDK docs/NODES.md:92, 99-100 | High |
| F8 | SDK recipe: after TTS finishes, wait ~150 ms before opening SCO so the tail of the TTS voice isn't recorded. | SDK docs/ANDROID_INTEGRATION.md:50 | Medium |
| F9 | Background button presses arrive as standard `KeyEvent`s (media keys / `KEYCODE_CAMERA` claimed) via `MediaSession`. AVRCP passthrough has no camera opcode, so expect play/pause/next/prev; photo button itself goes over SPP. | SDK REVERSE_ENGINEERING.md:66-67; HARDWARE.md:38 | Medium |
| F10 | Glasses voice prompts can't be muted; only phone media volume over A2DP might change them — not verified. | SDK VALIDATION.md:253-257 | Low |

## 2. Defects in the current app

| # | Where | Defect | Conf. |
|---|---|---|---|
| D1 | app/src/main/AndroidManifest.xml:4-6 | No `RECORD_AUDIO`, no `MODIFY_AUDIO_SETTINGS` (needed for `setMode`, `setCommunicationDevice`, SCO). Any mic feature fails silently. | High |
| D2 | AndroidManifest.xml (no `<queries>`) | Target 35 package visibility: without `<queries>` for `android.speech.RecognitionService` and `android.intent.action.TTS_SERVICE`, `SpeechRecognizer.isRecognitionAvailable()` returns false and TTS engine lookup can fail. | High |
| D3 | MainActivity.kt:310-311 | Runtime permission flow only asks `BLUETOOTH_CONNECT`; no `RECORD_AUDIO` request path. | High |
| D4 | whole app (grep: no `AudioManager`/`AudioRecord`/`TextToSpeech`/`MediaSession`) | No audio routing at all — LensMoo's assistant voice, phone-side memo, spoken feedback and temple buttons have no equivalent. | High |
| D5 | XkGlasses.kt:179-186 `disconnect()` | When audio is added, disconnect must also release the route; otherwise phone stays in `MODE_IN_COMMUNICATION` and music/calls are stuck on SCO. | High |
| D6 | XkGlasses.kt:253 `setRecording()` / 230 `takePhoto()` | No exclusivity guard vs. mic (F5). Must refuse/serialize camera actions while the SCO route is held, and vice-versa. | Medium |
| D7 | MediaImporter.kt:324 | Unknown-extension audio falls back to `audio/mpeg`; glasses memo format unverified — prefer sniffing header (`RIFF`→wav, `ftyp`→mp4, `OggS`→ogg, `ID3`/`0xFFFx`→mpeg). | Low |

## 3. Recommended changes

### 3a. Manifest (diff against AndroidManifest.xml)
```diff
     <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
+    <uses-permission android:name="android.permission.RECORD_AUDIO" />
+    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
+    <!-- only if mic must keep running with the screen off (assistant / long memo) -->
+    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
+    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
+    <queries>
+        <intent><action android:name="android.speech.RecognitionService" /></intent>
+        <intent><action android:name="android.intent.action.TTS_SERVICE" /></intent>
+    </queries>
```
A mic foreground service needs `android:foregroundServiceType="microphone"` and must be started while the app is visible (Android 14 while-in-use rule). Permission request: extend `bluetoothPermissions()` callers with a separate `withMic {}` that asks `RECORD_AUDIO` (same pattern as MainActivity.kt:323-344).

### 3b. New file `AudioRoute.kt` (framework only)
```kotlin
package com.zdycomp.p3companion

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.content.*
import android.media.*
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Single owner of the phone<->glasses voice route. Never falls back to the phone mic. */
@SuppressLint("MissingPermission")
class AudioRoute(context: Context) {
    private val app = context.applicationContext
    private val am = app.getSystemService(AudioManager::class.java)!!
    @Volatile var device: AudioDeviceInfo? = null; private set
    val active get() = device != null
    private var savedMode = AudioManager.MODE_NORMAL
    private var setComm = false
    private var legacySco = false
    private var commListener: Any? = null            // OnCommunicationDeviceChangedListener (API 31+)
    private val receivers = mutableListOf<BroadcastReceiver>()

    /** Worker thread only. Returns the glasses SCO input, or null (everything already cleaned up). */
    @Synchronized
    fun acquire(glassesAddress: String?, timeoutMs: Long = 8_000): AudioDeviceInfo? {
        device?.let { return it }
        check(Looper.myLooper() != Looper.getMainLooper()) { "AudioRoute.acquire blocks" }
        savedMode = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION   // set mode BEFORE routing (Samsung is picky)
        val d = runCatching {
            if (Build.VERSION.SDK_INT >= 31) acquire31(glassesAddress, timeoutMs) else acquireLegacy(timeoutMs)
        }.getOrNull()
        if (d == null) releaseLocked() else device = d
        return d
    }

    @android.annotation.TargetApi(31)
    private fun acquire31(addr: String?, timeoutMs: Long): AudioDeviceInfo? {
        val sco = am.availableCommunicationDevices.filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        val target = sco.firstOrNull { addr != null && it.address.equals(addr, true) } ?: sco.singleOrNull() ?: return null
        val routed = CountDownLatch(1)
        val l = AudioManager.OnCommunicationDeviceChangedListener { d -> if (d?.id == target.id) routed.countDown() }
        am.addOnCommunicationDeviceChangedListener(app.mainExecutor, l); commListener = l
        val linkUp = CountDownLatch(1)
        register(object : BroadcastReceiver() {          // real SCO link state from the headset profile
            override fun onReceive(c: Context, i: Intent) {
                @Suppress("DEPRECATION") val dev = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (i.getIntExtra(BluetoothHeadset.EXTRA_STATE, -1) == BluetoothHeadset.STATE_AUDIO_CONNECTED &&
                    (dev == null || dev.address.equals(target.address, true))) linkUp.countDown()
            }
        }, IntentFilter(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED))
        if (!am.setCommunicationDevice(target)) return null
        setComm = true
        if (am.communicationDevice?.id == target.id) routed.countDown()
        val t0 = SystemClock.elapsedRealtime()
        if (!routed.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
        val left = timeoutMs - (SystemClock.elapsedRealtime() - t0)
        linkUp.await(left.coerceIn(0, 2_000), TimeUnit.MILLISECONDS) // link may already be up: bounded grace
        return target
    }

    @Suppress("DEPRECATION")
    private fun acquireLegacy(timeoutMs: Long): AudioDeviceInfo? {   // API 30 only
        if (!am.isBluetoothScoAvailableOffCall) return null
        repeat(2) {                                                    // vendor app also retries
            val up = CountDownLatch(1)
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (isInitialStickyBroadcast) return               // sticky value may be stale
                    if (i.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) ==
                        AudioManager.SCO_AUDIO_STATE_CONNECTED) up.countDown()
                }
            }
            register(r, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
            am.startBluetoothSco(); legacySco = true
            val ok = up.await(timeoutMs / 2, TimeUnit.MILLISECONDS)
            if (ok) {
                am.isBluetoothScoOn = true
                return am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            }
            am.stopBluetoothSco(); legacySco = false; SystemClock.sleep(500)
        }
        return null
    }

    @Synchronized fun release() = releaseLocked()

    @Suppress("DEPRECATION")
    private fun releaseLocked() {
        if (Build.VERSION.SDK_INT >= 31) {
            (commListener as? AudioManager.OnCommunicationDeviceChangedListener)
                ?.let { runCatching { am.removeOnCommunicationDeviceChangedListener(it) } }
            if (setComm) runCatching { am.clearCommunicationDevice() }
        }
        if (legacySco) runCatching { am.isBluetoothScoOn = false; am.stopBluetoothSco() }
        receivers.forEach { runCatching { app.unregisterReceiver(it) } }; receivers.clear()
        commListener = null; setComm = false; legacySco = false; device = null
        if (am.mode == AudioManager.MODE_IN_COMMUNICATION) am.mode = savedMode
    }

    // Protected system actions: cannot be spoofed; EXPORTED avoids missing the Bluetooth-uid sender.
    private fun register(r: BroadcastReceiver, f: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_EXPORTED)
        else app.registerReceiver(r, f)
        receivers += r
    }

    /** 16 kHz mono PCM16 from the glasses. Call isOnGlasses() after the first read(). */
    fun openPcm(): AudioRecord {
        val d = device ?: error("Glasses mic not connected")
        val min = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min * 4, 6_400)).build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); error("Mic is busy (call or another app)") }
        rec.setPreferredDevice(d)
        rec.startRecording()
        return rec
    }
    fun isOnGlasses(rec: AudioRecord) = rec.routedDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO

    /** Voice memo from the glasses mic, saved as .m4a where GalleryScreen.kt:132 already looks. */
    fun startMemo(album: String): Memo {
        val d = device ?: error("Glasses mic not connected")
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val cr = app.contentResolver
        val uri = cr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "P3_REC_$stamp.m4a")
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/" + album)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: error("Couldn't create the recording file")
        val pfd = cr.openFileDescriptor(uri, "w") ?: error("Couldn't open the recording file")
        val mr = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(app) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            mr.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioSamplingRate(16_000); mr.setAudioChannels(1); mr.setAudioEncodingBitRate(32_000)
            mr.setOutputFile(pfd.fileDescriptor)
            mr.setPreferredDevice(d)
            mr.prepare(); mr.start()
        } catch (e: Exception) { mr.release(); pfd.close(); cr.delete(uri, null, null); throw e }
        return Memo(uri, mr, pfd)
    }

    inner class Memo(val uri: Uri, private val mr: MediaRecorder, private val pfd: ParcelFileDescriptor) {
        /** False if Android put the mic somewhere other than the glasses — caller should stop(false). */
        fun onGlasses() = mr.routedDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        fun stop(keep: Boolean) {
            val ok = runCatching { mr.stop() }.isSuccess    // throws if < ~1 s recorded
            mr.release(); pfd.close()
            val cr = app.contentResolver
            if (keep && ok) cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            else cr.delete(uri, null, null)
        }
    }
}
```
Ownership: one `AudioRoute` held by `XkGlasses` (singleton). Call `route.release()` in `XkGlasses.disconnect()` (XkGlasses.kt:179) and on reader-thread link loss (~XkGlasses.kt:379).

### 3c. Mic/camera exclusivity (D6)
```kotlin
// XkGlasses.kt, top of setRecording() (line 253) and takePhoto() (line 230):
if (audio.active) { lastError = "Stop the voice recording first — the glasses can't do mic and camera together."; changed(); return }
// AudioRoute caller, before acquire():
if (glasses.recording) error("Stop the video first — the glasses can't do mic and camera together.")
```
Photo-while-SCO and 5780-while-SCO are not proven to fail (F5 covers live preview only); keep the guard until test 4e says otherwise.

### 3d. TTS on the glasses speakers (A2DP)
```kotlin
tts = TextToSpeech(app) { ok -> ready = ok == TextToSpeech.SUCCESS }
tts.setAudioAttributes(AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
```
Rules: (a) speak only while `route.active == false` — during SCO the stack suspends A2DP and media lands on narrowband SCO or the phone speaker; (b) for multi-turn chat, either keep SCO and speak with `USAGE_VOICE_COMMUNICATION` (no 1-2 s route switch, lower quality), or release → speak → in `UtteranceProgressListener.onDone` wait 150 ms (F8) → `acquire()` again; (c) request `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` around each utterance and abandon after `onDone`.

### 3e. Speech recognition with the glasses mic
`SpeechRecognizer.createSpeechRecognizer(ctx)` on the main thread, after `acquire()`. With SCO as the communication device, the recognizer's `VOICE_RECOGNITION` capture follows the SCO input on stock policy (Medium). More robust on API 33+: feed our own verified PCM —
```kotlin
val (r, w) = ParcelFileDescriptor.createPipe()
intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, r)
intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
// worker: copy openPcm() reads into ParcelFileDescriptor.AutoCloseOutputStream(w) until onEndOfSpeech/onResults
```
Gate on `SpeechRecognizer.isRecognitionAvailable(ctx)` (needs D2 `<queries>`). Samsung may default to its own service; Google's works with the pipe (Medium).

### 3f. Temple buttons (AVRCP → MediaSession, framework `android.media.session`)
```kotlin
val session = MediaSession(app, "P3Glasses").apply {
    setCallback(object : MediaSession.Callback() {
        override fun onMediaButtonEvent(i: Intent): Boolean {
            @Suppress("DEPRECATION") val k = i.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
            if (k.action == KeyEvent.ACTION_UP) glasses.onHardwareKey(k.keyCode)   // log + map
            return true
        }
    })
    setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY_PAUSE or
        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
        .setState(PlaybackState.STATE_PAUSED, 0, 1f).build())
    isActive = true
}
```
Android routes media keys to the session that **last played audio**; play ~200 ms of silent `AudioTrack` (USAGE_MEDIA) once after connect so P3 owns the keys. Release the session on disconnect. Ship a "button log" first; don't hard-map until test 4d shows what keycodes arrive.

## 4. On-device checks (phone only, no adb)
Add a "Audio test" row in Settings that logs to the existing `XkGlasses.log`.
- 4a Route: tap test → log `route=SCO type 7`, `isOnGlasses=true`, peak level for 5 s. Put phone in another room, speak into the glasses → peak moves; tap the phone mic → it doesn't. Pass = both.
- 4b Cleanup: after test, play a YouTube video → must come out of the glasses at full quality (A2DP), not tinny. Tinny = mode/route not restored.
- 4c TTS: "Speak test" with route released → voice in temples. Repeat with route held → confirm what happens and pick 3d option.
- 4d Buttons: after connect, tap/hold/double-tap each temple; log shows keycodes. Also press photo button → confirm it still comes via SPP (57B1), not as a key.
- 4e Conflict: start video (5780), then try mic → app refuses. Temporarily bypass guard once: does 5780 video stop or lose audio? Same for photo while SCO. Record result in docs/DEVICE-TEST-PLAN.md.
- 4f Memo: record 10 s → file appears in Gallery "Audio", plays back clearly.
- 4g Speech: say "test one two" → text appears; confirm Samsung vs Google service in log.

## 5. Confidence summary
High: D1-D5, permissions/`<queries>`, setCommunicationDevice flow, restore mode, mic+preview conflict. Medium: SCO link grace timing, recognizer following SCO, AVRCP keycodes, TTS-over-SCO behavior. Low/unknown: 5780 video and 57B0 photo during SCO, D7 memo format.
