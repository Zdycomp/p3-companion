# P3 Companion

A clean, English, offline Android app for NovaSight P3 camera glasses and other glasses that use the
LensMoo app (Shenju "XK" family). No account, no ads, no cloud.

## What it does

| Tab | What you get |
|---|---|
| **Import** | Plug the glasses into the phone with their magnetic USB cable. The app copies new photos, videos and voice recordings into your gallery (album "P3 Glasses"), skips ones it already has, and can start by itself when the glasses are plugged in. Optional: delete from the glasses after each verified copy. |
| **Gallery** | Everything imported, newest first, filter by photos, videos or audio. Tap to open, press and hold to share or delete. |
| **Glasses** | Bluetooth control: connect, battery and charging, counts of what's stored on the glasses, **Take photo** (saved straight to the gallery), **Start/Stop video** on the glasses, and auto-save of photos you take with the glasses' own button. |
| **Settings** | Theme, which file types to import, auto-import, delete-after-import, file naming, album name, reconnect, button-photo saving, connection log. Every change saves instantly. |

## One-time setup for Bluetooth control

The glasses only accept commands from an app that signs in with **your own LensMoo account ID**. The
app finds it on your phone from Android's Bluetooth log; it's stored only in the app's private storage
and never uploaded. Steps are on the Glasses tab. Cable import needs no setup beyond picking the drive once.

## Install on a phone

Every push to `main` builds the APK on GitHub Actions and posts it to the **Latest build** release.
Open the release on your phone, tap `P3-Companion.apk`, and allow installs from your browser when asked.

## Build it yourself

```
./gradlew assembleDebug
```

Requires JDK 17 and the Android SDK (compileSdk 35). The app uses no libraries, only Android's framework,
and supports Android 11 and newer.

## Credits

Bluetooth protocol from the MIT-licensed [XK One Pro SDK](https://github.com/beneduzi/xk-one-pro-sdk).
See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
