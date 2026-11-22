# Changelog

## 3.0.0 (versionCode 3)

The connection path (pairing, sign-in, link, photo transfer, live view) is unchanged from 2.0.0.

**Appearance**
- Material You colors from your wallpaper on Android 12+, built from `android.R.color.system_*`; on Android 11, or when
  turned off, a tonal palette generated from an accent preset (Violet, Ocean, Emerald, Sunset, Rose, Graphite) or any hue.
- Pure black (AMOLED), high contrast, four text sizes, reduce motion (match phone / on / off), vibration on taps.
- Every color pair is checked for WCAG contrast (4.5:1 text, 3:1 outlines) in unit tests, with a runtime guard for unusual wallpapers.
- New components: top app bar that tints on scroll, bottom navigation with vector icons and an animated indicator,
  tonal and outlined cards, filled / tonal / outlined / text buttons, chips and segmented buttons, M3 switches and sliders,
  snackbars, dialogs and bottom sheets, empty states, press scale and ripples. Refined launcher icon with a themed-icon layer.

**Glasses**
- Hero card with battery ring, connection chip and counts; capture card with a large shutter, record-on-glasses button,
  last-photo thumbnail and a 3 / 5 / 10 second self-timer.
- Options: connect when the app opens, reconnect after a drop, keep connected in the background, connection notification
  detail, photo-saved notification (off / text / with picture), low-battery alert (10-30 %), phone shutter sound,
  vibrate when a photo is saved, open the photo after taking it.
- Quick Settings tile "Glasses photo" and home-screen shortcuts: Take photo, Live view, Voice chat.

**Gallery**: multi-select share and delete, sort newest / oldest / largest / name, 2-5 columns, ask the Assistant about a photo.

**Assistant**: voice speed and pitch with a sample, how many messages are remembered (0 / 6 / 12 / 24), editable quick photo
questions, chat bubbles, speak or only show voice answers.

**Privacy, storage, backup**: app lock (fingerprint, face or screen lock), hide content in Recents, hide keys completely,
clear Assistant history and connection log, storage used and clear temporary files, save / restore settings as JSON
(glasses key, AI keys, saved glasses and drive are never written), reset to defaults, About screen with What's new.

## 2.0.0 (versionCode 2)

Reliable connect, pairing, background link, live view, diagnostics.
