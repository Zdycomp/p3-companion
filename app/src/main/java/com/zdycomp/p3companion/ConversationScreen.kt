package com.zdycomp.p3companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 3.6 Conversation translate: listens continuously with the mic 3.5 found working, translates every
 * phrase with the AI, shows it in a live log (time, original, translation, language), speaks it if
 * asked, and saves the session as JSON lines in app-private storage. Saved sessions can be read,
 * read aloud, shared as text and deleted. Leaving the screen stops listening unless "Keep translating
 * in the background" is on ([ConversationService] then shows a notification with Stop).
 */
class ConversationScreen(private val a: MainActivity) : Screen {
    override val label = "Conversation translate"

    private enum class View { LIVE, SESSIONS, SESSION }

    private var view = View.LIVE
    private var openId: String? = null
    private var openEntries: List<TranslationEntry>? = null
    private var sessions: List<TranslationStore.Session>? = null
    private var showSetup = false
    private var visible = false
    private var liveBox: LinearLayout? = null
    private val listener = Assistant.Listener { if (visible && view == View.LIVE) renderLive() else if (visible && view == View.SESSION) renderReading() }
    private var readingBox: LinearLayout? = null

    override fun build(): android.view.View {
        val ui = a.ui
        visible = true
        a.assistant.removeListener(listener)
        a.assistant.addListener(listener)
        val col = ui.page()
        val title: String
        val actions = mutableListOf<BarAction>()
        when (view) {
            View.LIVE -> {
                title = "Conversation translate"
                actions += BarAction(R.drawable.ic_history, "Saved conversations") { openSessions() }
                buildLive(col)
            }
            View.SESSIONS -> {
                title = "Saved conversations"
                buildSessions(col)
            }
            View.SESSION -> {
                title = openId?.let { id -> TranslationStore.startedAt(id)?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } } ?: "Conversation"
                actions += BarAction(R.drawable.ic_share, "Share as text") { shareOpen() }
                actions += BarAction(R.drawable.ic_delete, "Delete this conversation") { openId?.let { confirmDelete(it) } }
                buildSession(col)
            }
        }
        return ui.screen(title, col, back = { if (!onBack()) a.goBack() }, actions = actions, key = "convo-${view.name}")
    }

    override fun onBack(): Boolean = when (view) {
        View.LIVE -> false
        View.SESSIONS -> { view = View.LIVE; a.refresh(); true }
        View.SESSION -> { a.assistant.stopReading(); view = View.SESSIONS; openEntries = null; loadSessions(); true }
    }

    // ---------------- live ----------------

    private fun buildLive(col: LinearLayout) {
        val ui = a.ui
        val p = a.prefs
        val asst = a.assistant
        if (!asst.hasKey() || showSetup) {
            col.addView(ui.banner(
                "No AI key is set up, so phrases can't be translated yet. The original words are still logged. " +
                    "Add a free Gemini key (or Claude or OpenAI) below.",
                Tone.WARN,
            ).apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(8) })
            col.addView(AiSetup.card(a))
            if (asst.hasKey()) col.addView(ui.button("Done", Btn.FILLED, R.drawable.ic_check) { showSetup = false; a.refresh() })
        }

        val card = ui.card()
        card.addView(ui.heading("Languages", R.drawable.ic_translate))
        val langs = ui.row().apply { setPadding(0, ui.dp(8), 0, 0) }
        val auto = p.convoAutoDetect && !p.convoTwoWay
        val from = ui.button(if (auto) "Auto-detect" else p.translateFrom, Btn.OUTLINED, full = false) {
            if (auto) { a.toast("Turn off auto-detect to pick the spoken language."); return@button }
            AiSetup.pickLanguage(a, if (p.convoTwoWay) "First language" else "Listen in", p.translateFrom) { p.translateFrom = it; restartIfListening() }
        }
        val swap = ui.iconButton(R.drawable.ic_swap_horiz, "Swap languages", IconBtn.TONAL) {
            if (asst.convoOn) asst.swapConversationLanguages() else { val f = p.translateFrom; p.translateFrom = p.translateTo; p.translateTo = f }
            a.refresh()
        }
        val to = ui.button(p.translateTo, Btn.OUTLINED, full = false) {
            AiSetup.pickLanguage(a, if (p.convoTwoWay) "Second language" else "Translate into", p.translateTo) { p.translateTo = it; restartIfListening() }
        }
        langs.addView(from, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        langs.addView(swap, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)).apply { marginStart = ui.dp(8); marginEnd = ui.dp(8) })
        langs.addView(to, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        card.addView(langs)
        card.addView(ui.switchRow("Two-way", "${p.translateFrom} is translated into ${p.translateTo} and back, by the language the AI hears",
            p.convoTwoWay, R.drawable.ic_swap_horiz) { p.convoTwoWay = it; restartIfListening() })
        card.addView(ui.switchRow("Auto-detect the spoken language", if (p.convoTwoWay) "Two-way already works out which of the two was spoken"
            else "The AI works out the language; the phone still listens in ${p.translateFrom}", p.convoAutoDetect, R.drawable.ic_auto_awesome,
            enabled = !p.convoTwoWay) { p.convoAutoDetect = it; a.refresh() })
        card.addView(ui.switchRow("Speak each translation", "Through the glasses when they're connected; listening pauses while it speaks",
            p.convoSpeak, R.drawable.ic_volume_up) { p.convoSpeak = it })
        card.addView(ui.switchRow("Show the original too", "Off: only the translation in the log", p.convoShowOriginal, R.drawable.ic_text_fields) {
            p.convoShowOriginal = it; renderLive()
        })
        card.addView(ui.switchRow("Keep translating in the background", "A notification with Stop shows while it runs",
            p.convoBackground, R.drawable.ic_notifications) { on ->
            if (on) a.withNotifications { p.convoBackground = true; asst.conversationBackgroundChanged() }
            else { p.convoBackground = false; asst.conversationBackgroundChanged() }
        })
        col.addView(card)

        val live = ui.column().apply { setPadding(0, ui.dp(12), 0, 0) }
        liveBox = live
        col.addView(live)
        renderLive()
    }

    private fun restartIfListening() {
        val asst = a.assistant
        if (asst.convoOn) asst.listenIn(if (a.prefs.convoTwoWay) asst.convoListenIn.let { l ->
            if (TwoWay.same(l, a.prefs.translateFrom) || TwoWay.same(l, a.prefs.translateTo)) l else a.prefs.translateFrom
        } else a.prefs.translateFrom)
        a.refresh()
    }

    private fun renderLive() {
        val box = liveBox ?: return
        val ui = a.ui
        val s = ui.s
        val p = a.prefs
        val asst = a.assistant
        box.removeAllViews()

        val on = asst.convoOn
        val paused = asst.convoPaused
        val inConvo = on && asst.mode == Assistant.Mode.CONVERSATION
        val statusText = when {
            asst.convoReading -> "Reading aloud…"
            !on -> "Tap Start and talk. Every phrase is translated and saved."
            paused -> "Paused"
            asst.convoTranslating != null -> "Translating…"
            inConvo && asst.phase == Assistant.Phase.SPEAKING -> "Speaking the translation…"
            inConvo && asst.phase == Assistant.Phase.LISTENING -> "Listening in ${asst.convoListenIn}…"
            else -> "Listening…"
        }
        val tone = when {
            !on -> Tone.NEUTRAL
            paused -> Tone.WARN
            else -> Tone.GOOD
        }
        val head = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(ui.statusChip(statusText, tone, pulse = on && !paused && asst.phase == Assistant.Phase.LISTENING))
        box.addView(head)
        if (on && !paused) {
            val micLine = listOfNotNull(asst.micInUse?.let { "Mic: the $it" }, if (asst.throughGlasses) "through the glasses" else null)
            if (micLine.isNotEmpty()) box.addView(ui.text(micLine.joinToString(" · "), Type.BODY_SMALL, s.onSurfaceVariant).apply { setPadding(ui.dp(4), ui.dp(6), 0, 0) })
        }

        // Start / Pause / Resume, and Stop.
        val buttons = when {
            asst.convoReading -> listOf(ui.button("Stop reading", Btn.TONAL, R.drawable.ic_stop, full = false) { asst.stopReading() })
            !on -> listOf(ui.button("Start", Btn.FILLED, R.drawable.ic_mic, full = false) { start() })
            paused -> listOf(
                ui.button("Resume", Btn.FILLED, R.drawable.ic_play, full = false) { a.withMic { asst.resumeConversation() } },
                ui.button("Stop", Btn.OUTLINED, R.drawable.ic_stop, full = false) { asst.stopConversation() },
            )
            else -> listOf(
                ui.button("Pause", Btn.TONAL, R.drawable.ic_stop, full = false) { asst.pauseConversation() },
                ui.button("Stop", Btn.OUTLINED, R.drawable.ic_close, full = false) { asst.stopConversation() },
            )
        }
        box.addView(ui.buttonRow(*buttons.toTypedArray()).apply { setPadding(0, ui.dp(8), 0, 0) })

        // Two-way: which language the next phrase is listened for in, and a tap to switch.
        if (on && p.convoTwoWay && !asst.convoReading) {
            val row = Flow(a, ui.dp(8), 0)
            row.addView(ui.text("Listening for:", Type.BODY_SMALL, s.onSurfaceVariant).apply { setPadding(0, ui.dp(12), ui.dp(4), 0) })
            listOf(p.translateFrom, p.translateTo).forEach { l ->
                row.addView(ui.chip(l, TwoWay.same(l, asst.convoListenIn)) { asst.listenIn(l) })
            }
            box.addView(row)
        }

        // The words being heard right now, greyed until the phrase is finished.
        val partial = if (inConvo && asst.phase == Assistant.Phase.LISTENING) asst.partial else null
        val pending = asst.convoTranslating
        if (partial != null || pending != null) {
            box.addView(ui.text(pending ?: partial ?: "", Type.BODY_LARGE, s.onSurfaceVariant).apply {
                setTypeface(typeface, Typeface.ITALIC)
                alpha = 0.7f
                setPadding(ui.dp(8), ui.dp(12), ui.dp(8), 0)
            })
        }

        asst.convoProblem?.let { box.addView(ui.banner(it, Tone.WARN, if (!asst.hasKey()) "Set up" else null) { showSetup = true; a.refresh() }
            .apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(12) }) }
        asst.convoNotes.lastOrNull()?.let { n ->
            if (n.who == Assistant.Who.ERROR) box.addView(ui.banner(n.text, Tone.BAD).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(8) })
            else box.addView(ui.text(n.text, Type.BODY_SMALL, s.onSurfaceVariant).apply { setPadding(ui.dp(4), ui.dp(8), 0, 0) })
        }

        val entries = asst.convoEntries
        box.addView(ui.row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ui.dp(16), 0, ui.dp(4))
            addView(ui.sectionLabel(if (entries.isEmpty()) "This conversation" else "This conversation · ${entries.size}"), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            if (entries.isNotEmpty()) {
                addView(ui.iconButton(R.drawable.ic_volume_up, "Read all aloud") { asst.readBack(readItems(entries)) })
                addView(ui.iconButton(R.drawable.ic_share, "Share as text") { shareLive() })
            }
        })
        if (entries.isEmpty()) {
            box.addView(ui.text(if (on) "Phrases show up here as they're heard." else "Nothing yet. Saved conversations are under the history button.",
                Type.BODY_MEDIUM, s.onSurfaceVariant).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(16), 0, ui.dp(8)) })
            return
        }
        // Newest first, right under the controls.
        entries.asReversed().take(100).forEach { box.addView(entryView(it)) }
        if (entries.size > 100) box.addView(ui.text("Older phrases are in the saved conversation.", Type.BODY_SMALL, s.onSurfaceVariant))
    }

    private fun start() {
        val asst = a.assistant
        a.withMic { asst.startConversation() }
    }

    // ---------------- one entry ----------------

    private val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private fun entryView(e: TranslationEntry): android.view.View {
        val ui = a.ui
        val s = ui.s
        val p = a.prefs
        val card = ui.column().apply {
            background = ui.rounded(s.surfaceContainerHigh, 16)
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
            isClickable = true
            isFocusable = true
            contentDescription = (e.translation ?: e.original) + ". Tap to hear the translation, long-press for the original."
            setOnClickListener { speakEntry(e, original = e.translation == null) }
            setOnLongClickListener { speakEntry(e, original = true); true }
        }
        card.addView(ui.text("${time.format(Date(e.at))} · ${e.from} → ${e.to}", Type.LABEL_MEDIUM, s.primary).apply { fontFeatureSettings = "tnum" })
        if (p.convoShowOriginal || e.translation == null) {
            card.addView(ui.text(e.original, Type.BODY_MEDIUM, s.onSurfaceVariant).apply { setPadding(0, ui.dp(4), 0, 0); setTextIsSelectable(false) })
        }
        if (e.translation != null) card.addView(ui.text(e.translation, Type.BODY_LARGE, s.onSurface).apply { setPadding(0, ui.dp(4), 0, 0) })
        e.note?.let { card.addView(ui.text(it, Type.BODY_SMALL, s.error).apply { setPadding(0, ui.dp(4), 0, 0) }) }
        val wrap = FrameLayout(a).apply { setPadding(0, ui.dp(4), 0, ui.dp(4)) }
        wrap.addView(card, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        return wrap
    }

    private fun speakEntry(e: TranslationEntry, original: Boolean) {
        val item = if (original || e.translation == null) e.original to e.from else e.translation to e.to
        a.assistant.readBack(listOf(item))
    }

    /** Each entry's translation in its language, or the original where there is none. */
    private fun readItems(list: List<TranslationEntry>): List<Pair<String, String>> =
        list.map { e -> if (e.translation != null) e.translation to e.to else e.original to (TwoWay.known(e.from) ?: a.prefs.translateFrom) }

    private fun shareLive() {
        val asst = a.assistant
        val id = asst.convoSession ?: run { a.toast("This conversation isn't saved yet."); return }
        asst.withStore({ it.exportText(id, "Conversation translate") }) { r ->
            r.onSuccess { a.shareText("Conversation translate", it) }.onFailure { a.toast("Couldn't read the log.") }
        }
    }

    // ---------------- saved sessions ----------------

    private fun openSessions() {
        view = View.SESSIONS
        loadSessions()
    }

    private fun loadSessions() {
        sessions = null
        a.refresh()
        a.assistant.withStore({ it.list() }) { r ->
            sessions = r.getOrDefault(emptyList())
            if (visible && view == View.SESSIONS) a.refresh()
        }
    }

    private fun buildSessions(col: LinearLayout) {
        val ui = a.ui
        val s = ui.s
        val list = sessions
        if (list == null) {
            col.addView(ui.spinner(32), LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = ui.dp(32) })
            return
        }
        if (list.isEmpty()) {
            col.addView(ui.emptyState(R.drawable.ic_history, "No saved conversations",
                "Every Conversation translate session is saved here on the phone.", "Start one") { view = View.LIVE; a.refresh() })
            return
        }
        col.addView(ui.body("Saved on this phone only (app storage). Tap one to read it, hear it again or share it.", muted = true, sizeSp = 13f)
            .apply { setPadding(ui.dp(4), 0, 0, ui.dp(8)) })
        val group = ui.group()
        val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val current = a.assistant.convoSession.takeIf { a.assistant.convoOn }
        list.forEach { sess ->
            val title = fmt.format(Date(sess.startedAt))
            val hint = "${sess.entries} phrase" + (if (sess.entries == 1) "" else "s") + if (sess.id == current) " · running now" else ""
            group.addView(ui.listRow(title, hint, R.drawable.ic_translate,
                ui.iconButton(R.drawable.ic_delete, "Delete $title") { confirmDelete(sess.id) }) { openSession(sess.id) })
        }
        col.addView(group)
        col.addView(ui.button("Delete all", Btn.DANGER, R.drawable.ic_delete_sweep, full = false) { confirmClearAll() }.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = ui.dp(16)
        })
        col.addView(ui.text("API keys are never written into these logs.", Type.BODY_SMALL, s.onSurfaceVariant).apply { setPadding(ui.dp(4), ui.dp(8), 0, 0) })
    }

    private fun openSession(id: String) {
        view = View.SESSION
        openId = id
        openEntries = null
        a.refresh()
        a.assistant.withStore({ it.read(id) }) { r ->
            openEntries = r.getOrDefault(emptyList())
            if (visible && view == View.SESSION && openId == id) a.refresh()
        }
    }

    private fun buildSession(col: LinearLayout) {
        val ui = a.ui
        val s = ui.s
        val entries = openEntries
        if (entries == null) {
            col.addView(ui.spinner(32), LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = ui.dp(32) })
            return
        }
        val rb = ui.column()
        readingBox = rb
        col.addView(rb)
        renderReading()
        if (entries.isEmpty()) {
            col.addView(ui.text("Nothing was heard in this conversation.", Type.BODY_MEDIUM, s.onSurfaceVariant).apply { setPadding(0, ui.dp(16), 0, 0) })
            return
        }
        col.addView(ui.text("Tap a phrase to hear its translation, long-press for the original.", Type.BODY_SMALL, s.onSurfaceVariant)
            .apply { setPadding(ui.dp(4), ui.dp(8), 0, ui.dp(4)) })
        entries.forEach { col.addView(entryView(it)) }
    }

    /** Read all / Stop reading, in step with the assistant. */
    private fun renderReading() {
        val box = readingBox ?: return
        val ui = a.ui
        val entries = openEntries ?: return
        box.removeAllViews()
        val asst = a.assistant
        box.addView(ui.buttonRow(
            if (asst.convoReading) ui.button("Stop reading", Btn.TONAL, R.drawable.ic_stop, full = false) { asst.stopReading() }
            else ui.button("Read all", Btn.FILLED, R.drawable.ic_volume_up, full = false) { asst.readBack(readItems(entries)) }.apply { isEnabled = entries.isNotEmpty() },
            ui.button("Share", Btn.OUTLINED, R.drawable.ic_share, full = false) { shareOpen() },
        ))
    }

    private fun shareOpen() {
        val id = openId ?: return
        a.assistant.withStore({ it.exportText(id, "Conversation translate") }) { r ->
            r.onSuccess { a.shareText("Conversation translate", it) }.onFailure { a.toast("Couldn't read the log.") }
        }
    }

    private fun confirmDelete(id: String) {
        val asst = a.assistant
        if (asst.convoOn && asst.convoSession == id) { a.toast("Stop the conversation first."); return }
        a.ui.dialog("Delete this conversation?", "Its saved log is removed from the phone. This can't be undone.",
            icon = R.drawable.ic_delete, confirm = "Delete", dismiss = "Cancel", destructive = true) {
            asst.withStore({ it.delete(id) }) { r ->
                a.toast(if (r.getOrDefault(false)) "Deleted." else "Couldn't delete it.")
                if (view == View.SESSION) { view = View.SESSIONS; openId = null; openEntries = null }
                loadSessions()
            }
            true
        }
    }

    private fun confirmClearAll() {
        val asst = a.assistant
        if (asst.convoOn) { a.toast("Stop the conversation first."); return }
        a.ui.dialog("Delete every saved conversation?", "All conversation logs are removed from the phone. This can't be undone.",
            icon = R.drawable.ic_delete_sweep, confirm = "Delete all", dismiss = "Cancel", destructive = true) {
            asst.withStore({ it.clearAll() }) { r ->
                a.toast("Deleted ${r.getOrDefault(0)}.")
                loadSessions()
            }
            true
        }
    }

    override fun onHidden() {
        // Listening is not stopped here (a rebuild calls this too): MainActivity stops it when another
        // screen opens or the app leaves the screen, unless "Keep translating in the background" is on.
        visible = false
        a.assistant.removeListener(listener)
        liveBox = null
        readingBox = null
    }
}

/**
 * 3.6: keeps Conversation translate listening with the screen closed: a microphone foreground
 * service with a notification and a Stop action. It does nothing else; the work is in [Assistant].
 */
class ConversationService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        val asst = Assistant.get(this, Prefs(this))
        if (intent?.action == ACTION_STOP) asst.stopConversation()
        if (!asst.convoOn) stopSelf()
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Conversation translate", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows while P3 Companion translates a conversation in the background"
            }
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_CONVERSATION).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, ConversationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Translating the conversation")
            .setContentText("Every phrase is translated and saved. Tap Stop to end.")
            .setOngoing(true)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Stop", stop).build())
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIFICATION_ID, n)
        } catch (e: Exception) {
            // Not allowed right now (started from the background); listening still runs while the app is open.
        }
    }

    companion object {
        private const val CHANNEL = "conversation"
        private const val NOTIFICATION_ID = 9
        const val ACTION_STOP = "com.zdycomp.p3companion.STOP_CONVERSATION"

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, ConversationService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ConversationService::class.java)) }
        }
    }
}
