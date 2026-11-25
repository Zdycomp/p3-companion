# P3 Companion: phone-only test checklist

Do these in order on your phone with the glasses. Before you start: charge the glasses, close LensMoo
(swipe it away), and in P3 Companion turn on **Settings > Show connection log**.

**If a step fails:** Glasses tab > **Diagnostics and connection log** > **Run the checks** > **Share
report**, and send it with the test number and one line about what you saw. Send a screenshot too if
something looks wrong on screen. The report never contains your glasses key or full Bluetooth address.

1. **Cable import.** Plug the glasses into the phone with their cable. Import tab > Choose glasses drive
   (first time only) > Import new files. *Expect:* photos, videos and voice recordings appear in the
   Gallery tab; tapping Import again copies nothing new. *If not:* send a screenshot of the Import tab.
2. **Connect before the key (new).** With no key saved, Glasses tab > **Connect** (or Pair new glasses).
   *Expect:* "Connected, not signed in" and a list saying which of Battery / Device info / Media counts
   the glasses answered, with the orange **Finish setup** card right below. Then Diagnostics > **Test
   without key** and share the report: it tells us whether your P3 really needs the key.
2b. **Key setup from a bug report.** Developer options > turn on **Bluetooth HCI snoop log** > turn
   Bluetooth off and on > open LensMoo, connect, take one photo > close LensMoo > Developer options >
   **Take bug report** (Interactive). When the notification says it's ready, tap it and choose
   **P3 Companion** in the share list (or save it and use Glasses > Set up > **Open bug report or log
   file**). *Expect:* "Key found" and a masked key. If the app says the log is **Filtered**, set the
   snoop log to Enabled and repeat. *If not:* send the message text; never send the bug report itself.
3. **Pair and connect.** If the glasses aren't in Your glasses, use **Pair new glasses** > Search > tap
   them > accept the popup. Then Connect. *Expect:* "Connected" within about 10 seconds; the log
   shows `Link open (...) in N ms`, `102E reply: ...` and `Signed in, battery N%`. Repeat Disconnect /
   Connect 5 times: all must work. With LensMoo open and connected, Connect must fail quickly with a
   message naming LensMoo; after force-stopping LensMoo it must connect. Tap Connect then Cancel at
   once: it must stay "Not connected".
4. **Battery.** *Expect:* battery % shown after connecting. Put the glasses on the charger, tap
   **Refresh battery and counts** twice, 30 seconds apart. *Expect:* charging shows, the number is the
   same or higher, and each refresh takes under 5 seconds.
5. **Take photo.** Tap **Take photo**. *Expect:* "Photo saved" within about 15 seconds; the photo opens
   from **Open last photo** and is in the Gallery. Do it 3 times in a row; all 3 must save.
6. **Button photo auto-save.** Settings > turn on **Save photos taken with the glasses' button**. Press
   the glasses' photo button once. *Expect:* the photo arrives in the Gallery within 20 seconds and
   the glasses do not keep beeping or stay stuck. Press Home, lock the phone for 2 minutes, press the
   button again: the photo must still arrive (the "Your glasses" notification keeps the link). Then
   turn the setting off, press the button again: the log shows `Releasing the transfer`, and Take
   photo still works.
7. **Video on the glasses.** Tap **Record on the glasses (if supported)**, wait 10 seconds, tap
   **Stop recording on the glasses**. *Expect:* either "Recording saved on the glasses" (video count
   goes up) or a clear message that your glasses don't store videos. Send the log either way.
8. **Live view.** Tap **Live view and record to phone**. *Expect:* moving picture from the glasses
   within 5 seconds, about 25-30 fps in the stats line, delay under 1 second when you wave a hand.
   Leave it on 60 seconds: no freeze. Press Close: the log shows `→ 5720 00`. If there's no picture,
   share the Diagnostics report (it lists what video data arrived).
9. **Phone-side recording.** In Live view tap **Record to phone** for 15 seconds, then stop. *Expect:*
   a video about 15 seconds long in Movies > your album that plays on the phone. Also tap **Save a
   snapshot** once: a picture appears in the Gallery.
10. **Audio through the glasses' mic.** Start a voice recording with the glasses connected; speak with
    the phone in another room or covered. *Expect:* your voice is clear in the playback (it came
    from the glasses). Also check: music played on the phone afterwards still comes out of the glasses.
11. **AI photo question.** Assistant tab (AI key added first): ask "What am I looking at?" with the
    glasses option. *Expect:* the glasses take a photo, and an answer appears and is spoken within
    about 20 seconds. *If not:* send the log and the error text (never your AI key).
12. **Voice chat from the touch panel.** With the app open, tap the glasses' side touch panel once.
    *Expect:* the app starts listening (you hear or see it), answers through the glasses' speakers.
    The log shows `← C101`. Then lock the phone and try again; note whether it still works.
13. **Live translation.** Assistant tab > Translate, pick two languages, say one sentence. *Expect:*
    the translation is shown and spoken in the other language within about 10 seconds. Try 5
    sentences in a row.
14. **Drop and reconnect.** With Settings > **Reconnect by itself** on, turn the glasses off for
    20 seconds, then on. *Expect:* "Connected" again within 30 seconds without touching the phone,
    and Take photo works.

15. **Diagnostics.** Glasses > Diagnostics and connection log > Run the checks. *Expect:* mostly PASS
    lines and "Done."; share the report once even if everything works.

When everything passes, send "all 15 passed" plus one Diagnostics report from the end of the session.

## 3.6 additions

16. **Live view picture.** Open Live view. *Expect:* the frame has the picture's shape (no black bars),
    the chip reads "● Live · ~N ms · 320×240 · 30 fps · … kbps". Pinch to zoom, drag, double-tap to reset;
    Rotate 90°; Fit/Fill; the full-screen button turns the phone sideways with only the picture; a tap
    shows the controls and they hide after 3 s; Back leaves full screen.
17. **Snapshot and recording.** Save a snapshot (toast says "320×240", or "640×480" with 2× on). Turn on
    "Record with the phone's mic", record 10 s while talking. *Expect:* the MP4 in Movies › album plays with
    your voice. Turn the phone during a recording: it keeps recording.
18. **Bluetooth drop in live view.** Turn the glasses off for 10 s while on Live view, then on. *Expect:*
    "Bluetooth dropped. Reconnecting…", then the video comes back by itself.
19. **Try higher quality.** Live view > Picture > Try higher quality (or Diagnostics). *Expect:* under a
    minute, then either a Quality choice (Standard / High W×H) or "The glasses send 320×240 only".
    Share the test's log from Diagnostics either way. Live view must open normally afterwards.
20. **Conversation translate.** Assistant > Translate > Start Conversation translate (or say "translate
    conversation"). Speak 5 phrases with pauses. *Expect:* grey words while speaking, then each entry with
    time, original, translation and language, spoken aloud. Turn on Two-way and answer in the other
    language. Say "stop translating". Open the history button: the session is there; tap a phrase to hear
    it, Read all, Share, Delete. Without an AI key the words are still logged.
