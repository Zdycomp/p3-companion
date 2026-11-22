# P3 Companion

A clean, English Android app that replaces LensMoo for NovaSight P3 camera glasses and other glasses
that use it (Shenju "XK" family). No account, no ads. Everything works offline except the Assistant,
which talks only to the AI service you choose, with your own key.

## What it does

| Tab | What you get |
|---|---|
| **Import** | Plug the glasses into the phone with their magnetic USB cable. The app copies new photos, videos and voice recordings into your gallery (album "P3 Glasses"), skips ones it already has, and can start by itself when the glasses are plugged in. Optional: delete from the glasses after each verified copy. |
| **Gallery** | Everything imported, newest first, filter by photos, videos or audio. Tap to open, press and hold to share or delete. |
| **Glasses** | **Pair new glasses** in the app (Classic Bluetooth search, low-energy duplicates hidden), then connect (even before the key is added: the app shows what the glasses answer and walks you through finishing setup) and sign in with your own key. Battery and charging, **Take photo** (saved straight to the gallery), photos from the glasses' own button saved automatically, even with the app closed (a notification keeps the link up and it reconnects by itself when the glasses come back). **Live view and record to phone**: the glasses' camera streamed live, recorded as MP4 to Movies › album, with snapshots. **Voice recorder** using the glasses' microphone when they're your phone's headset. Record on the glasses for models that store video. **Diagnostics** checks every step and makes a shareable report (key and address hidden). |
| **Assistant** | AI with Google Gemini (free key from Google AI Studio) or Anthropic Claude. **Voice chat**: tap, ask, hear the answer through the glasses (or tap the glasses' touch pad). **Live translation** between two languages with a swap button. **Ask about a photo**: pick one, use the glasses' last photo, or take a new one with the glasses; presets for describing a scene, identifying plants and animals, food and calories, and reading and translating text. Answers always come in your chosen reply language. |
| **Settings** | Grouped into Appearance, Glasses, Capture, Import, Assistant, Privacy, Storage, Backup and About. Every change saves instantly. |

## New in 3.0

- **Look**: Material You colors from your wallpaper (Android 12+) or an accent preset (Violet, Ocean, Emerald, Sunset, Rose, Graphite, any hue), pure black for OLED, high contrast, four text sizes, reduce motion, vibration on taps. New icons, bottom bar with an animated indicator, tonal cards, snackbars and sheets.
- **Glasses tab**: hero card with a battery ring and connection chip, a big shutter, a record-on-glasses button, a 3/5/10 s self-timer, last-photo thumbnail.
- **Glasses options**: connect when the app opens and reconnect after a drop as separate switches, keep connected in the background (on by default), low-battery alert, photo-saved notification with the picture, phone shutter sound and vibration, open the photo after taking it, minimal connection notification.
- **Shortcuts**: a Quick Settings tile (one tap takes a photo while connected) and long-press app shortcuts for Take photo, Live view and Voice chat.
- **Gallery**: select several to share or delete, sort (newest, oldest, largest, name), 2 to 5 columns, ask the Assistant about a photo.
- **Assistant**: voice speed and pitch with a sample, how many messages are remembered, editable quick photo questions, chat bubbles, option to show voice answers without reading them aloud.
- **Privacy and more**: lock the app with fingerprint, face or screen lock; hide the app in Recents; save and restore settings to a file (keys are never written); storage used and clear temporary files; About screen with What's new.

See [CHANGELOG.md](CHANGELOG.md).

## One-time setup for Bluetooth control

The glasses only accept commands from an app that signs in with **your own LensMoo account ID**. The
app finds it on your phone from Android's Bluetooth log: turn on the Bluetooth HCI snoop log (Enabled,
not Filtered), connect LensMoo once, take a bug report and share it straight to P3 Companion from the
bug report notification. The key is stored only in the app's private storage and never uploaded or
shown in full. Steps are on the Glasses tab. If anything doesn't work, open **Diagnostics** (Glasses tab
or Settings) and share the report.

## Setting up the Assistant

1. Get a key: a free Gemini key at https://aistudio.google.com/apikey, or a Claude key from your Anthropic account.
2. Open the **Assistant** tab and paste it. The key is kept only in the app's private storage and shown masked.
3. To talk and listen through the glasses, keep them connected as your phone's Bluetooth headset
   (they usually are after pairing). Without that, the phone's mic and speaker are used.

Defaults: Gemini model `gemini-2.5-flash`, Claude model `claude-sonnet-5-5`, replies in English. You can change all
three in Settings. Speech recognition and voices come from your phone (Google's speech services and text-to-speech).

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
