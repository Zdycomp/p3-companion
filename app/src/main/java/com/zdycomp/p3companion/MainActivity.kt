package com.zdycomp.p3companion

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference

class MainActivity : Activity() {

    lateinit var prefs: Prefs
        private set
    lateinit var ui: Ui
        private set
    lateinit var importer: MediaImporter
        private set
    lateinit var glasses: XkGlasses
        private set
    lateinit var assistant: Assistant
        private set
    lateinit var recorder: VoiceRecorder
        private set

    private lateinit var content: FrameLayout
    private lateinit var shell: LinearLayout
    private lateinit var navBar: LinearLayout
    private lateinit var overlay: FrameLayout
    private lateinit var screens: List<Screen>
    private val navItems = mutableListOf<NavItem>()
    private var current = -1
    private var pendingPermission: PendingPermission? = null
    private val askedOptional = mutableSetOf<String>()
    private var afterDelete: (() -> Unit)? = null
    private var lockCover: View? = null
    private var pendingAction: String? = null
    private var snackView: View? = null
    private var bottomInset = 0
    private val main = Handler(Looper.getMainLooper())

    /** A key search is unpacking a bug report into the cache (Clear temporary files must wait). */
    @Volatile var readingKey = false
        private set

    private val glassesListener = XkGlasses.Listener { updateBadges() }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        ui = Themes.ui(this, prefs)
        setTheme(if (ui.p.isDark) android.R.style.Theme_DeviceDefault_NoActionBar else android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        GlassesEvents.install(this)
        importer = App.importer(this)
        glasses = XkGlasses.get(this, prefs)
        assistant = Assistant.get(this, prefs)
        recorder = VoiceRecorder.get(this, prefs)

        setUpWindow()

        screens = listOf(
            ImportScreen(this), GalleryScreen(this), GlassesScreen(this), AssistantScreen(this), SettingsScreen(this),
            DiagnosticsScreen(this), LiveViewScreen(this), AboutScreen(this), SupRevoltScreen(this),
        )

        overlay = FrameLayout(this).apply { setBackgroundColor(ui.s.surface) }
        shell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content = FrameLayout(this)
        shell.addView(content, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        navBar = buildNav()
        shell.addView(navBar, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        overlay.addView(shell, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        content.id = View.generateViewId()
        navBar.accessibilityTraversalAfter = content.id

        // Edge to edge: the app draws behind the bars and keeps its content clear of them.
        overlay.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            val typing = insets.isVisible(WindowInsets.Type.ime())
            shell.setPadding(bars.left, bars.top, bars.right, if (typing) ime.bottom else 0)
            navBar.setPadding(0, 0, 0, bars.bottom)
            navBar.visibility = if (typing) View.GONE else View.VISIBLE
            bottomInset = if (typing) ime.bottom else bars.bottom
            WindowInsets.CONSUMED
        }
        setContentView(overlay)

        val start = savedInstanceState?.getInt("tab") ?: prefs.startTab
        show(start.coerceIn(0, TAB_COUNT - 1), animate = false)
        if (prefs.connectOnLaunch && prefs.glassesAddress != null && prefs.userKey != null && hasBluetoothPermission() && !prefs.userStopped) {
            // The service keeps the link up in the background; it connects if the glasses are paired.
            GlassesService.start(this)
            glasses.reconnectSaved()
        }
        if (!handleAction(intent)) handleShared(intent)
        pendingSnack?.let { msg -> pendingSnack = null; main.post { snack(msg) } }
        if (savedInstanceState == null) maybeShowWhatsNew()
    }

    private fun setUpWindow() {
        val s = ui.s
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        window.isNavigationBarContrastEnforced = false
        @Suppress("DEPRECATION")
        window.isStatusBarContrastEnforced = false
        window.decorView.setBackgroundColor(s.surface)
        val light = !s.isDark
        window.decorView.windowInsetsController?.setSystemBarsAppearance(
            if (light) (WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS) else 0,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )
        if (prefs.secureWindow) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!handleAction(intent)) handleShared(intent)
    }

    // ---------- shortcuts, the Quick Settings tile and notifications ----------

    /** Home-screen shortcuts and the tile. Returns true when [intent] was one of them. */
    private fun handleAction(intent: Intent?): Boolean {
        val action = intent?.action ?: return false
        if (action !in listOf(ACTION_PHOTO, ACTION_LIVE, ACTION_VOICE, ACTION_GLASSES)) return false
        setIntent(Intent(this, MainActivity::class.java))
        if (lockCover != null || AppLock.shouldLock(this, prefs)) { pendingAction = action; return true }
        runAction(action)
        return true
    }

    private fun runAction(action: String) {
        val id = when (action) { ACTION_PHOTO -> "photo"; ACTION_LIVE -> "live"; ACTION_VOICE -> "voice"; else -> null }
        if (id != null) runCatching { getSystemService(ShortcutManager::class.java)?.reportShortcutUsed(id) }
        when (action) {
            ACTION_GLASSES -> show(TAB_GLASSES)
            ACTION_PHOTO -> { show(TAB_GLASSES); whenConnected("take the photo") { glasses.takePhoto() } }
            ACTION_LIVE -> { show(TAB_GLASSES); whenConnected("open live view") { showLiveView() } }
            ACTION_VOICE -> {
                (screens[TAB_ASSISTANT] as? AssistantScreen)?.section = AssistantScreen.SECTION_VOICE
                show(TAB_ASSISTANT)
                withMic { if (assistant.phase == Assistant.Phase.IDLE) assistant.voiceChat() }
            }
        }
    }

    /** Runs [then] once the glasses are connected, connecting first when needed (25 s limit). */
    fun whenConnected(what: String, then: () -> Unit) {
        if (glasses.state == XkGlasses.State.READY) { then(); return }
        val addr = prefs.glassesAddress
        if (addr == null || prefs.userKey == null) { snack("Set up your glasses first to $what."); return }
        withBluetooth {
            if (glasses.state == XkGlasses.State.IDLE) glasses.connect(addr, prefs.glassesName)
            lateinit var watcher: XkGlasses.Listener
            val timeout = Runnable {
                glasses.removeListener(watcher)
                snack("The glasses didn't connect in time to $what.")
            }
            watcher = XkGlasses.Listener {
                when (glasses.state) {
                    XkGlasses.State.READY -> { glasses.removeListener(watcher); main.removeCallbacks(timeout); then() }
                    XkGlasses.State.IDLE -> { glasses.removeListener(watcher); main.removeCallbacks(timeout) }
                    else -> {}
                }
            }
            glasses.addListener(watcher)
            main.postDelayed(timeout, 25_000)
        }
    }

    /**
     * A bug report or log shared to the app. The bug report notification shares the zip and a
     * screenshot together (SEND_MULTIPLE), so the screenshot is skipped.
     */
    private fun handleShared(intent: Intent?) {
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            )
            Intent.ACTION_SEND_MULTIPLE ->
                (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()
            else -> return
        }
        val logs = uris.filterNot { runCatching { contentResolver.getType(it) }.getOrNull()?.startsWith("image/") == true }
        if (logs.isNotEmpty()) {
            show(TAB_GLASSES)
            readKeyFrom(logs)
        } else if (uris.isNotEmpty()) {
            toast("That was only a picture. Share the bug report itself.")
        }
        setIntent(Intent(this, MainActivity::class.java))
    }

    fun pickKeyFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        launchForResult(intent, REQ_KEYFILE, "This phone has no file picker.")
    }

    private fun readKeyFrom(uri: Uri) = readKeyFrom(listOf(uri))

    /** Tries each file until one holds the key. */
    private fun readKeyFrom(uris: List<Uri>) {
        val busy = ui.row().apply {
            addView(ui.spinner(24))
            addView(ui.body("Looking for your glasses key…", muted = true, sizeSp = 14f).apply { setPadding(ui.dp(16), 0, 0, 0) })
        }
        val dialog = ui.dialog("Reading the log", view = busy, confirm = null)
        dialog.setCancelable(false)
        readingKey = true
        Thread {
            val result = runCatching {
                var last: KeyFinder.Result? = null
                for (u in uris) {
                    val r = KeyFinder.find(contentResolver, u, cacheDir)
                    if (r.key != null) return@runCatching r
                    if (last == null || r.snoopFiles > last.snoopFiles || r.scannedBytes > last.scannedBytes) last = r
                }
                last ?: error("nothing to read")
            }
            runOnUiThread {
                readingKey = false
                runCatching { dialog.dismiss() }
                val r = result.getOrNull()
                when {
                    r == null -> toast("Couldn't read that file: ${result.exceptionOrNull()?.message}")
                    r.key != null -> {
                        prefs.userKey = r.key
                        onKeySaved()
                        ui.dialog(
                            "Key found",
                            "Saved ${prefs.maskForDisplay(r.key)}. You can turn the Bluetooth snoop log off now, then connect.",
                            icon = R.drawable.ic_check_circle,
                        )
                        show(TAB_GLASSES)
                    }
                    else -> ui.dialog(
                        if (r.filtered) "The log is filtered" else "No key in that file",
                        KeyFinder.explainMiss(r),
                        icon = R.drawable.ic_info,
                    )
                }
            }
        }.start()
    }

    // ---------- bottom navigation ----------

    private class NavItem(val root: LinearLayout, val pill: FrameLayout, val icon: ImageView, val label: TextView, val badge: View)

    private fun buildNav(): LinearLayout {
        val s = ui.s
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(s.surfaceContainer)
            minimumHeight = ui.dp(80)
            gravity = Gravity.CENTER_VERTICAL
        }
        val tabs = listOf(
            "Import" to R.drawable.ic_download,
            "Gallery" to R.drawable.ic_photo_library,
            "Glasses" to R.drawable.ic_glasses_tab,
            "Assistant" to R.drawable.ic_auto_awesome,
            "Settings" to R.drawable.ic_settings,
        )
        val labelScale = minOf(ui.textScale, 1.15f)
        tabs.forEachIndexed { i, (label, iconRes) ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, ui.dp(12), 0, ui.dp(16))
                minimumHeight = ui.dp(80)
                background = RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(s.onSurface, 31)), null, null).apply {
                    radius = ui.dp(32)
                }
                contentDescription = "$label, tab ${i + 1} of ${tabs.size}"
                setOnClickListener { v -> if (i != current) ui.haptic(v, Haptic.TICK); show(i) }
            }
            val pill = FrameLayout(this)
            val icon = ImageView(this).apply {
                setImageDrawable(ui.drawable(iconRes, s.onSurfaceVariant))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val pillBg = View(this).apply { background = ui.rounded(s.secondaryContainer, 16); alpha = 0f }
            pill.addView(pillBg, FrameLayout.LayoutParams(ui.dp(64), ui.dp(32)))
            pill.addView(icon, FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER))
            val badge = View(this).apply { background = ui.oval(s.error); visibility = View.GONE }
            pill.addView(badge, FrameLayout.LayoutParams(ui.dp(6), ui.dp(6), Gravity.TOP or Gravity.END).apply {
                topMargin = ui.dp(4); marginEnd = ui.dp(18)
            })
            item.addView(pill, LinearLayout.LayoutParams(ui.dp(64), ui.dp(32)))
            val text = TextView(this).apply {
                this.text = label
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f * labelScale)
                typeface = ui.font(500)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setPadding(ui.dp(2), ui.dp(4), ui.dp(2), 0)
            }
            item.addView(text, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
            navItems += NavItem(item, pill, icon, text, badge)
            bar.addView(item, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        }
        return bar
    }

    private fun paintNav(animate: Boolean) {
        val s = ui.s
        navItems.forEachIndexed { i, item ->
            val on = i == current || (current >= TAB_COUNT && i == returnTab)
            val pill = item.pill.getChildAt(0)
            val wasOn = item.root.isSelected
            item.root.isSelected = on
            item.root.stateDescription = if (on) "Selected" else null
            item.icon.setImageDrawable(ui.drawable(TAB_ICONS[i], if (on) s.onSecondaryContainer else s.onSurfaceVariant))
            item.label.setTextColor(if (on) s.onSurface else s.onSurfaceVariant)
            item.label.typeface = ui.font(if (on) 600 else 500)
            pill.animate().cancel()
            if (on && !wasOn && animate && !ui.reduceMotion) {
                pill.alpha = 0f; pill.scaleX = 0.5f
                pill.animate().alpha(1f).scaleX(1f).setDuration(200).setInterpolator(ui.decelerate).start()
            } else if (!on && wasOn && animate && !ui.reduceMotion) {
                pill.animate().alpha(0f).setDuration(100).start()
            } else {
                pill.alpha = if (on) 1f else 0f; pill.scaleX = 1f
            }
        }
        updateBadges()
    }

    private fun updateBadges() {
        if (navItems.size < TAB_COUNT) return
        val expected = prefs.glassesAddress != null && prefs.userKey != null && !prefs.userStopped
        val down = glasses.state == XkGlasses.State.IDLE && expected && glasses.lastError != null
        navItems[TAB_GLASSES].badge.visibility = if (down) View.VISIBLE else View.GONE
        navItems[TAB_GLASSES].root.contentDescription = "Glasses, tab 3 of 5" + if (down) ", not connected" else ""
        navItems[0].badge.visibility = if (importer.running) View.VISIBLE else View.GONE
    }

    /** The tab to go back to from Diagnostics, Live view or About. */
    private var returnTab = TAB_GLASSES

    fun show(index: Int) = show(index, animate = true)

    /** The tab or screen on show (TAB_* or SCREEN_*), for voice commands. */
    val currentScreen: Int get() = current

    /** The Live view screen, for "save a snapshot". */
    fun liveView(): LiveViewScreen? = screens.getOrNull(SCREEN_LIVE) as? LiveViewScreen

    /** Opens the Assistant tab on one of its sections. */
    fun openAssistant(section: Int) {
        (screens[TAB_ASSISTANT] as? AssistantScreen)?.section = section
        if (current == TAB_ASSISTANT) refresh() else show(TAB_ASSISTANT)
    }

    private fun show(index: Int, animate: Boolean) {
        val switching = index != current
        if (switching && current >= 0) assistant.clearPendingConfirm()
        val drillIn = switching && index >= TAB_COUNT && current < TAB_COUNT
        val drillOut = switching && current >= TAB_COUNT && index < TAB_COUNT
        if (current in screens.indices) screens[current].onHidden()
        if (current in 0 until TAB_COUNT && index >= TAB_COUNT) returnTab = if (index == SCREEN_ABOUT) TAB_SETTINGS else current
        current = index
        val view = screens[index].build()
        content.removeAllViews()
        content.addView(view, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        if (switching && animate) enter(view, drillIn, drillOut)
        paintNav(switching && animate)
    }

    /** Fade-through between tabs; shared-axis slide into and out of Diagnostics, Live view and About. */
    private fun enter(v: View, drillIn: Boolean, drillOut: Boolean) {
        if (ui.reduceMotion) {
            v.alpha = 0f
            v.animate().alpha(1f).setDuration(100).start()
            return
        }
        v.alpha = 0f
        when {
            drillIn -> { v.translationX = ui.dpf(32f); v.animate().translationX(0f).alpha(1f).setDuration(300).setInterpolator(ui.decelerate).start() }
            drillOut -> { v.translationX = -ui.dpf(32f); v.animate().translationX(0f).alpha(1f).setDuration(200).setInterpolator(ui.decelerate).start() }
            else -> {
                v.scaleX = 0.94f; v.scaleY = 0.94f
                v.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(60).setDuration(210).setInterpolator(ui.decelerate).start()
            }
        }
    }

    /** A key was just saved: sign in on the current link if it was opened without one. */
    fun onKeySaved() {
        if (glasses.state == XkGlasses.State.READY && !glasses.signedIn) glasses.signIn()
        refresh()
    }

    /** Leaves Diagnostics, Live view or About for the tab they were opened from. */
    fun goBack() { if (current >= TAB_COUNT) show(returnTab) }

    fun showDiagnostics() = show(SCREEN_DIAGNOSTICS)
    fun showLiveView() = show(SCREEN_LIVE)
    fun showAbout() = show(SCREEN_ABOUT)
    fun showSupRevolt() = show(SCREEN_SUPREVOLT)

    /** Scans one gallery photo or file with SupRevolt and shows the result screen. */
    fun sendToSupRevolt(uri: Uri) {
        if (!prefs.srReady) { snack("Set up your SupRevolt server first."); showSupRevolt(); return }
        SupRevolt.get(prefs).scanUri(contentResolver, uri)
        showSupRevolt()
    }

    /** System document picker (any type) for SupRevolt. */
    fun pickDocumentForSupRevolt() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        launchForResult(intent, REQ_SR_DOC, "This phone has no file picker.")
    }

    @Deprecated("Framework back handling")
    override fun onBackPressed() {
        if (current in screens.indices && screens[current].onBack()) return
        if (current >= TAB_COUNT) show(returnTab)
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    /** Rebuild the visible screen in place, for example after a setting changes. */
    fun refresh() { if (current >= 0) show(current, animate = false) }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", if (current >= TAB_COUNT) returnTab else current)
    }

    override fun onResume() {
        super.onResume()
        AppLock.expectingReturn = false
        refresh()
    }

    override fun onStart() {
        super.onStart()
        GlassesEvents.foreground = WeakReference(this)
        glasses.addListener(glassesListener)
        // Tapping the glasses starts a voice question while the app is on screen.
        glasses.onTouch = { onGlassesTapped() }
        // Offline voice commands, and the system speech dialog when no recognition service works.
        assistant.commands?.release()
        assistant.commands = CommandRunner(this)
        assistant.speech.activityFallback = { tag, deliver -> openSpeechDialog(tag, deliver) }
        if (!AppLock.prompting) { if (AppLock.shouldLock(this, prefs)) lock() else AppLock.onStarted() }
        if (!prefs.keepConnectedInBackground) glasses.resumeIfPaused()
    }

    override fun onStop() {
        if (GlassesEvents.foreground?.get() === this) GlassesEvents.foreground = null
        glasses.removeListener(glassesListener)
        glasses.onTouch = null
        // The microphone can't be used from the background, so end any voice turn.
        // (Not while the system speech dialog is open for this voice turn: it hands back the words.)
        if (speechDelivery == null) {
            if (assistant.phase != Assistant.Phase.IDLE) assistant.stop()
            assistant.commands?.release()
            assistant.commands = null
            assistant.speech.activityFallback = null
        }
        AppLock.onStop(isChangingConfigurations)
        if (!prefs.keepConnectedInBackground && !isChangingConfigurations && !AppLock.expectingReturn && !AppLock.prompting) {
            glasses.pause()
        }
        super.onStop()
    }

    private fun onGlassesTapped() {
        if (!prefs.tapToTalk || lockCover != null) return
        // A tap while a voice memo records stops the memo instead of starting a question.
        if (recorder.recording) { recorder.stop(); toast("Memo saved."); return }
        val screen = screens[TAB_ASSISTANT] as? AssistantScreen
        if (current != TAB_ASSISTANT || screen?.section != AssistantScreen.SECTION_VOICE) {
            screen?.section = AssistantScreen.SECTION_VOICE
            show(TAB_ASSISTANT)
        }
        if (assistant.phase != Assistant.Phase.IDLE) {
            assistant.tapWhileBusy()
            return
        }
        if (!hasMicPermission()) {
            toast("Allow the microphone on the Assistant tab to talk through the glasses.")
            return
        }
        assistant.voiceChat()
    }

    override fun onPause() {
        if (current in screens.indices) screens[current].onHidden()
        super.onPause()
    }

    // ---------- feedback from the glasses ----------

    /** Called by [GlassesEvents] on the main thread while the app is on screen. */
    fun onPhotoSaved(uri: Uri, fromButton: Boolean) {
        ui.haptic(content, Haptic.CONFIRM)
        (screens[TAB_GLASSES] as? GlassesScreen)?.photoArrived()
        if (!fromButton && prefs.openAfterCapture) { openMedia(uri, "image/jpeg"); return }
        snack(if (fromButton) "Saved the photo from the glasses' button." else "Photo saved.", "Open") { openMedia(uri, "image/jpeg") }
    }

    fun connectionFeedback(ok: Boolean) = ui.haptic(content, if (ok) Haptic.CONFIRM else Haptic.REJECT)

    fun openMedia(uri: Uri, mime: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { toast("No app on this phone can open it.") }
    }

    // ---------- app lock ----------

    private fun lock() {
        if (lockCover == null) {
            val s = ui.s
            val cover = ui.column().apply {
                setBackgroundColor(s.surface)
                gravity = Gravity.CENTER
                isClickable = true
                setPadding(ui.dp(32), ui.dp(32), ui.dp(32), ui.dp(32))
            }
            cover.addView(ui.emptyState(R.drawable.ic_lock, "P3 Companion is locked", "Unlock with your fingerprint, face or screen lock.", "Unlock") { unlock() })
            lockCover = cover
            overlay.addView(cover, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        }
        main.post { unlock() }
    }

    private fun unlock() {
        if (AppLock.prompting) return
        AppLock.prompt(this) { ok, message ->
            when (ok) {
                true -> {
                    lockCover?.let { overlay.removeView(it) }
                    lockCover = null
                    pendingAction?.let { a -> pendingAction = null; runAction(a) }
                }
                false -> finish()
                null -> message?.let { toast(it) }
            }
        }
    }

    // ---------- glasses drive ----------

    fun pickDrive() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        launchForResult(intent, REQ_TREE, "This phone has no file picker for drives.")
    }

    fun forgetDrive() {
        prefs.driveUri?.let { old ->
            runCatching {
                contentResolver.releasePersistableUriPermission(
                    Uri.parse(old),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        prefs.driveUri = null
        prefs.driveLabel = null
    }

    private fun launchForResult(intent: Intent, code: Int, missing: String) {
        try {
            AppLock.expectingReturn = true
            @Suppress("DEPRECATION")
            startActivityForResult(intent, code)
        } catch (e: Exception) {
            AppLock.expectingReturn = false
            toast(missing)
        }
    }

    @Deprecated("Framework activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_TREE -> {
                val tree = data?.data
                if (resultCode != RESULT_OK || tree == null) return
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                try {
                    contentResolver.takePersistableUriPermission(tree, flags)
                } catch (e: SecurityException) {
                    runCatching {
                        contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
                forgetDriveKeepingNew(tree)
                prefs.driveUri = tree.toString()
                prefs.driveLabel = driveLabel(tree)
                toast("Saved. The app will use this drive from now on.")
                refresh()
            }
            REQ_KEYFILE -> {
                val uri = data?.data
                if (resultCode == RESULT_OK && uri != null) readKeyFrom(uri)
            }
            REQ_DELETE -> {
                if (resultCode == RESULT_OK) afterDelete?.invoke()
                afterDelete = null
            }
            REQ_IMAGE -> {
                val uri = data?.data
                if (resultCode == RESULT_OK && uri != null) {
                    runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    askAboutPhoto(uri)
                }
            }
            REQ_SR_DOC -> {
                val uri = data?.data
                if (resultCode == RESULT_OK && uri != null) {
                    SupRevolt.get(prefs).scanUri(contentResolver, uri)
                    if (current != SCREEN_SUPREVOLT) showSupRevolt()
                }
            }
            REQ_BACKUP_SAVE -> data?.data?.takeIf { resultCode == RESULT_OK }?.let { writeBackup(it) }
            REQ_BACKUP_OPEN -> data?.data?.takeIf { resultCode == RESULT_OK }?.let { readBackup(it) }
            REQ_SPEECH -> {
                val cb = speechDelivery
                speechDelivery = null
                val text = data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                cb?.invoke(text.takeIf { resultCode == RESULT_OK }, if (resultCode == RESULT_OK) null else "Listening was cancelled.")
            }
        }
    }

    private var speechDelivery: ((String?, String?) -> Unit)? = null

    /** Last resort for voice input: the system speech dialog (Google's "Speak now"). */
    private fun openSpeechDialog(tag: String, deliver: (String?, String?) -> Unit): Boolean {
        val i = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Speak now")
            putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        return try {
            AppLock.expectingReturn = true
            speechDelivery = deliver
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_SPEECH)
            true
        } catch (e: Exception) {
            AppLock.expectingReturn = false
            speechDelivery = null
            false
        }
    }

    /** Opens the Assistant's photo section with [uri] chosen. */
    fun askAboutPhoto(uri: Uri) {
        assistant.photoUri = uri
        (screens[TAB_ASSISTANT] as? AssistantScreen)?.section = AssistantScreen.SECTION_PHOTO
        show(TAB_ASSISTANT)
    }

    /** System photo picker for the assistant; the choice lands in [Assistant.photoUri]. */
    fun pickPhotoForAssistant() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        launchForResult(intent, REQ_IMAGE, "This phone has no photo picker.")
    }

    private fun forgetDriveKeepingNew(newTree: Uri) {
        val old = prefs.driveUri ?: return
        if (old == newTree.toString()) return
        runCatching {
            contentResolver.releasePersistableUriPermission(
                Uri.parse(old),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    private fun driveLabel(tree: Uri): String = try {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "USB drive"
    } catch (e: Exception) {
        "USB drive"
    }

    /** Ask Android to delete gallery items; it shows its own confirmation. */
    fun requestDelete(uris: List<Uri>, then: () -> Unit) {
        if (uris.isEmpty()) return
        try {
            val pending = android.provider.MediaStore.createDeleteRequest(contentResolver, uris)
            afterDelete = then
            AppLock.expectingReturn = true
            @Suppress("DEPRECATION")
            startIntentSenderForResult(pending.intentSender, REQ_DELETE, null, 0, 0, 0)
        } catch (e: Exception) {
            AppLock.expectingReturn = false
            ui.haptic(content, Haptic.REJECT)
            toast("Couldn't delete: ${e.message}")
        }
    }

    // ---------- settings backup ----------

    fun appVersion(): String = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"

    fun saveSettingsFile() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, SettingsBackup.fileName())
        }
        launchForResult(intent, REQ_BACKUP_SAVE, "This phone has no file picker.")
    }

    fun restoreSettingsFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
        launchForResult(intent, REQ_BACKUP_OPEN, "This phone has no file picker.")
    }

    private fun writeBackup(uri: Uri) {
        val text = SettingsBackup.export(prefs.snapshot(), appVersion())
        val ok = runCatching { contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) } }.isSuccess
        snack(if (ok) "Settings saved. Keys aren't in the file." else "Couldn't write that file.")
    }

    private fun readBackup(uri: Uri) {
        val text = runCatching {
            contentResolver.openInputStream(uri)!!.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (out.size() < 512 * 1024) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                String(out.toByteArray(), Charsets.UTF_8)
            }
        }.getOrNull()
        if (text == null) { snack("Couldn't read that file."); return }
        try {
            val r = SettingsBackup.read(text)
            val n = prefs.restore(r.values)
            pendingSnack = "Restored $n settings" + if (r.ignored > 0) ", ignored ${r.ignored}." else "."
            recreate()
        } catch (e: SettingsBackup.BadFile) {
            ui.dialog("Can't restore", e.message, icon = R.drawable.ic_error)
        }
    }

    private fun maybeShowWhatsNew() {
        val code = runCatching { packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt() }.getOrDefault(0)
        if (code == 0) return
        val last = prefs.lastSeenVersion
        prefs.lastSeenVersion = code
        if (last >= code) return
        val updated = runCatching {
            val pi = packageManager.getPackageInfo(packageName, 0)
            pi.firstInstallTime != pi.lastUpdateTime
        }.getOrDefault(false)
        if (last == 0 && !updated) return // a fresh install has nothing "new"
        val list = ui.column()
        WhatsNew.headlines.forEach { line ->
            list.addView(ui.row().apply {
                gravity = Gravity.TOP
                setPadding(0, ui.dp(6), 0, ui.dp(6))
                addView(ui.icon(R.drawable.ic_check, ui.s.primary, 18), LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)).apply {
                    marginEnd = ui.dp(12); topMargin = ui.dp(2)
                })
                addView(ui.body(line, sizeSp = 14f), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            })
        }
        main.postDelayed({
            if (isFinishing || lockCover != null) return@postDelayed
            ui.dialog("What's new in ${WhatsNew.VERSION}", icon = R.drawable.ic_auto_awesome, view = list,
                confirm = "See all", dismiss = "Close") { showAbout(); true }
        }, 600)
    }

    // ---------- keep screen on while importing ----------

    fun keepAwake(on: Boolean) {
        if (on && prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---------- permissions ----------

    private class PendingPermission(val required: List<String>, val denied: String, val action: () -> Unit)

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /**
     * Runs [action] once every [required] permission is granted, asking first when needed.
     * [optional] ones are asked for once per visit but don't block the action.
     */
    private fun withPermissions(
        required: List<String>,
        optional: List<String> = emptyList(),
        denied: String,
        action: () -> Unit,
    ) {
        val need = required.filterNot(::granted) + optional.filter { !granted(it) && it !in askedOptional }
        if (need.isEmpty()) { action(); return }
        val missingRequired = required.filterNot(::granted)
        // Android stops showing the dialog after two refusals; send the person to app settings instead.
        if (missingRequired.isNotEmpty() && missingRequired.all { prefs.wasAsked(it) && !shouldShowRequestPermissionRationale(it) }) {
            confirm(
                "Permission needed",
                "$denied Android won't ask again, so open the app's settings › Permissions and allow it there.",
                "Open settings",
            ) {
                runCatching {
                    startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                }
            }
            return
        }
        missingRequired.forEach { prefs.markAsked(it) }
        askedOptional += optional
        pendingPermission = PendingPermission(required, denied, action)
        requestPermissions(need.toTypedArray(), REQ_PERMS)
    }

    private fun bluetoothPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()

    private fun scanPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasBluetoothPermission(): Boolean = bluetoothPermissions().all(::granted)

    fun hasMicPermission(): Boolean = granted(Manifest.permission.RECORD_AUDIO)

    /** Asks Android to turn Bluetooth on; returns false when it's off. */
    private fun bluetoothReady(): Boolean {
        if (glasses.bluetoothOn()) return true
        try {
            @Suppress("DEPRECATION")
            startActivity(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } catch (e: Exception) {
            toast("Turn on Bluetooth first.")
        }
        return false
    }

    /** Connecting also asks to show the connection notification (Android 13+), which doesn't block. */
    fun withBluetooth(action: () -> Unit) =
        withPermissions(
            bluetoothPermissions(),
            optional = if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList(),
            denied = "Bluetooth permission (Nearby devices) is needed to reach your glasses.",
        ) {
            if (bluetoothReady()) action()
        }

    /** For finding new glasses nearby: scan permission (Android 12+) or location (Android 11). */
    fun withScan(action: () -> Unit) =
        withPermissions(
            scanPermissions(),
            denied = if (Build.VERSION.SDK_INT >= 31) "Nearby devices permission is needed to find your glasses."
            else "Android 11 needs location permission to find Bluetooth devices. The app doesn't use your location.",
        ) {
            if (!bluetoothReady()) return@withPermissions
            if (Build.VERSION.SDK_INT < 31) {
                val lm = getSystemService(android.location.LocationManager::class.java)
                if (lm != null && !lm.isLocationEnabled) {
                    toast("Turn on Location so Android can search for Bluetooth devices.")
                    runCatching { startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                    return@withPermissions
                }
            }
            action()
        }

    /** Microphone for the assistant and the recorder. Recording also asks to show its notification. */
    fun withMic(forRecording: Boolean = false, action: () -> Unit) =
        withPermissions(
            listOf(Manifest.permission.RECORD_AUDIO),
            optional = if (forRecording && Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList(),
            denied = "Microphone permission is needed for this.",
            action = action,
        )

    /** Notifications for photo and battery alerts (Android 13+); asked once, never required. */
    fun withNotifications(action: () -> Unit) =
        withPermissions(
            emptyList(),
            optional = if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList(),
            denied = "",
            action = action,
        )

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        val pending = pendingPermission ?: return
        pendingPermission = null
        if (pending.required.all(::granted)) pending.action() else { ui.haptic(content, Haptic.REJECT); toast(pending.denied) }
    }

    // ---------- small helpers ----------

    /** Kept for every screen: messages now show as a snackbar above the navigation bar. */
    fun toast(msg: String) = snack(msg)

    /** Snackbar (DESIGN-SPEC 3.10): inverse surface, optional action, 4 s (7 s with an action). */
    fun snack(msg: String, action: String? = null, onAction: (() -> Unit)? = null) {
        if (isFinishing) return
        val s = ui.s
        snackView?.let { overlay.removeView(it) }
        val bar = ui.row().apply {
            background = ui.rounded(s.inverseSurface, 12)
            minimumHeight = ui.dp(48)
            setPadding(ui.dp(16), ui.dp(6), ui.dp(8), ui.dp(6))
            elevation = ui.dpf(6f)
            addView(ui.text(msg, Type.BODY_MEDIUM, s.inverseOnSurface).apply { maxLines = 3 },
                LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { topMargin = ui.dp(8); bottomMargin = ui.dp(8) })
            if (action != null && onAction != null) {
                addView(TextView(this@MainActivity).apply {
                    ui.style(this, Type.LABEL_LARGE, s.inversePrimary)
                    text = action
                    minHeight = ui.dp(48)
                    gravity = Gravity.CENTER
                    setPadding(ui.dp(12), 0, ui.dp(12), 0)
                    background = RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(s.inversePrimary, 31)), null, ui.rounded(Color.WHITE, 100))
                    setOnClickListener { dismissSnack(); onAction() }
                })
            }
        }
        snackView = bar
        val navH = if (navBar.visibility == View.VISIBLE && navBar.height > 0) navBar.height else bottomInset
        overlay.addView(bar, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM).apply {
            leftMargin = ui.dp(16); rightMargin = ui.dp(16); bottomMargin = navH + ui.dp(8)
        })
        lockCover?.bringToFront()
        bar.announceForAccessibility(msg)
        if (!ui.reduceMotion) {
            bar.alpha = 0f
            bar.translationY = ui.dpf(24f)
            bar.animate().alpha(1f).translationY(0f).setDuration(250).setInterpolator(ui.decelerate).start()
        }
        val talkback = getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
        val ms = (if (action != null) 7_000L else 4_000L) * (if (talkback) 2 else 1)
        main.postDelayed({ if (snackView === bar) dismissSnack() }, ms)
    }

    private fun dismissSnack() {
        val bar = snackView ?: return
        snackView = null
        if (ui.reduceMotion) { overlay.removeView(bar); return }
        bar.animate().alpha(0f).translationY(ui.dpf(12f)).setDuration(150).setInterpolator(ui.accelerate)
            .withEndAction { overlay.removeView(bar) }.start()
    }

    fun confirm(title: String, message: String, yes: String, action: () -> Unit) {
        val destructive = yes in listOf("Remove", "Forget", "Delete", "Erase", "Reset", "Clear")
        ui.dialog(title, message, confirm = yes, dismiss = "Cancel", destructive = destructive) { action(); true }
    }

    fun shareText(subject: String, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        AppLock.expectingReturn = true
        runCatching { startActivity(Intent.createChooser(send, subject)) }.onFailure { AppLock.expectingReturn = false }
    }

    /** Starts a share sheet or another app the person will come back from without re-locking. */
    fun launch(intent: Intent) {
        AppLock.expectingReturn = true
        runCatching { startActivity(intent) }.onFailure { AppLock.expectingReturn = false; toast("No app on this phone can do that.") }
    }

    fun spacer(heightDp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(Ui.MATCH, ui.dp(heightDp))
    }

    companion object {
        private const val REQ_TREE = 41
        private const val REQ_DELETE = 42
        private const val REQ_PERMS = 43
        private const val REQ_KEYFILE = 44
        private const val REQ_IMAGE = 45
        private const val REQ_BACKUP_SAVE = 46
        private const val REQ_BACKUP_OPEN = 47
        private const val REQ_SPEECH = 48
        private const val REQ_SR_DOC = 49

        const val TAB_IMPORT = 0
        const val TAB_GALLERY = 1
        const val TAB_GLASSES = 2
        const val TAB_ASSISTANT = 3
        const val TAB_SETTINGS = 4
        const val TAB_COUNT = 5
        const val SCREEN_DIAGNOSTICS = 5
        const val SCREEN_LIVE = 6
        const val SCREEN_ABOUT = 7
        const val SCREEN_SUPREVOLT = 8

        const val ACTION_PHOTO = "com.zdycomp.p3companion.action.PHOTO"
        const val ACTION_LIVE = "com.zdycomp.p3companion.action.LIVE"
        const val ACTION_VOICE = "com.zdycomp.p3companion.action.VOICE"
        const val ACTION_GLASSES = "com.zdycomp.p3companion.action.GLASSES"

        private val TAB_ICONS = intArrayOf(
            R.drawable.ic_download, R.drawable.ic_photo_library, R.drawable.ic_glasses_tab,
            R.drawable.ic_auto_awesome, R.drawable.ic_settings,
        )

        /** A message to show after recreate() (settings restore). */
        private var pendingSnack: String? = null
    }
}

/** App-wide singletons that must outlive a single screen (an import keeps running across tab changes). */
object App {
    @Volatile private var importer: MediaImporter? = null

    fun importer(activity: Activity): MediaImporter =
        importer ?: synchronized(this) {
            importer ?: MediaImporter(activity.applicationContext, Prefs(activity)).also { importer = it }
        }
}
