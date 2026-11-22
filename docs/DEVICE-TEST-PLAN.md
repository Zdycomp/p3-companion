# P3 Companion: phone-only test checklist

Do these in order on your phone with the glasses. Before you start: charge the glasses, close LensMoo
(swipe it away), and in P3 Companion turn on **Settings > Show connection log**.

**If a step fails:** go to the **Glasses** tab, scroll to **Connection log**, tap **Share log**, and send
it with the test number and one line about what you saw. Send a screenshot too if something looks
wrong on screen. The log never contains your glasses key.

1. **Cable import.** Plug the glasses into the phone with their cable. Import tab > Choose glasses drive
   (first time only) > Import new files. *Expect:* photos, videos and voice recordings appear in the
   Gallery tab; tapping Import again copies nothing new. *If not:* send a screenshot of the Import tab.
2. **Key setup from a bug report.** Developer options > turn on **Bluetooth HCI snoop log** > turn
   Bluetooth off and on > open LensMoo, connect, take one photo > close LensMoo > Developer options >
   **Take bug report** (Full) > save it to Files. In P3 Companion: Glasses > Set up > **Open bug report
   or log file** and pick it. *Expect:* "Key saved" and a key of 32 characters (0-9, a-f).
   *If not:* tell us the bug report's size; do not send the bug report itself (it is private).
3. **Connect.** Glasses > tap your glasses > Connect. *Expect:* "Connected" within about 10 seconds;
   the log shows `Link open` then `Bound`. Repeat: Disconnect, Connect again. Both must work.
4. **Battery.** *Expect:* battery % shown after connecting. Put the glasses on the charger, tap
   **Refresh battery and counts** twice, 30 seconds apart. *Expect:* charging shows, the number is the
   same or higher, and each refresh takes under 5 seconds.
5. **Take photo.** Tap **Take photo**. *Expect:* "Photo saved" within about 15 seconds; the photo opens
   from **Open last photo** and is in the Gallery. Do it 3 times in a row; all 3 must save.
6. **Button photo auto-save.** Settings > turn on **Save photos taken with the glasses' button**. Press
   the glasses' photo button once. *Expect:* the photo arrives in the Gallery within 20 seconds and
   the glasses do not keep beeping or stay stuck. Then turn the setting off, press the button again:
   the glasses must still be usable afterwards (Take photo still works).
7. **Video on the glasses.** Tap **Start video**, wait 10 seconds, tap **Stop video**. *Expect:* no
   error, and "On the glasses" video count goes up by 1. Then do test 1 again: the video imports.
   If you get "these glasses don't support recording from the app", note it and send the log.
8. **Live view.** Tap **Live view** (when the button exists). *Expect:* moving picture from the glasses
   within 5 seconds, delay under 1 second when you wave a hand. Leave it on 60 seconds: no freeze.
9. **Phone-side recording.** In Live view tap **Record** for 15 seconds, then stop. *Expect:* a video
   about 15 seconds long in the Gallery that plays on the phone.
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

When everything passes, send "all 14 passed" plus one shared log from the end of the session.
