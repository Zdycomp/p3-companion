package com.zdycomp.p3companion

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

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
    private lateinit var screens: List<Screen>
    private val tabViews = mutableListOf<TextView>()
    private var current = -1
    private var pendingPermission: PendingPermission? = null
    private val askedOptional = mutableSetOf<String>()
    private var afterDelete: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        ui = Ui(this, Palette.resolve(this, prefs.theme))
        importer = App.importer(this)
        glasses = XkGlasses.get(this, prefs)
        assistant = Assistant.get(this, prefs)
        recorder = VoiceRecorder.get(this, prefs)

        window.statusBarColor = ui.p.bg
        window.navigationBarColor = ui.p.card
        if (Build.VERSION.SDK_INT >= 30) {
            val light = ui.p == Palette.LIGHT
            window.decorView.windowInsetsController?.setSystemBarsAppearance(
                if (light) (android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS) else 0,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        }

        screens = listOf(
            ImportScreen(this), GalleryScreen(this), GlassesScreen(this), AssistantScreen(this), SettingsScreen(this),
            DiagnosticsScreen(this), LiveViewScreen(this),
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ui.p.bg)
        }
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        root.addView(buildTabBar(), LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))

        // Android 15 draws apps edge to edge, so keep content clear of the status and navigation bars.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            } else null
            if (bars != null) v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)

        val start = savedInstanceState?.getInt("tab") ?: prefs.startTab
        show(start.coerceIn(0, TAB_COUNT - 1))
        if (prefs.autoReconnect && prefs.glassesAddress != null && prefs.userKey != null && hasBluetoothPermission() && !prefs.userStopped) {
            // The service keeps the link up in the background; it connects if the glasses are paired.
            GlassesService.start(this)
            glasses.reconnectSaved()
        }
        handleShared(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShared(intent)
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
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_KEYFILE)
        } catch (e: Exception) {
            toast("This phone has no file picker.")
        }
    }

    private fun readKeyFrom(uri: Uri) = readKeyFrom(listOf(uri))

    /** Tries each file until one holds the key. */
    private fun readKeyFrom(uris: List<Uri>) {
        @Suppress("DEPRECATION")
        val dialog = android.app.ProgressDialog.show(this, "Reading the log", "Looking for your glasses key…", true)
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
                runCatching { dialog.dismiss() }
                val r = result.getOrNull()
                when {
                    r == null -> toast("Couldn't read that file: ${result.exceptionOrNull()?.message}")
                    r.key != null -> {
                        prefs.userKey = r.key
                        onKeySaved()
                        AlertDialog.Builder(this)
                            .setTitle("Key found")
                            .setMessage("Saved ${KeyFinder.mask(r.key)}. You can turn the Bluetooth snoop log off now, then connect.")
                            .setPositiveButton("OK", null)
                            .show()
                        show(TAB_GLASSES)
                    }
                    else -> AlertDialog.Builder(this)
                        .setTitle(if (r.filtered) "The log is filtered" else "No key in that file")
                        .setMessage(KeyFinder.explainMiss(r))
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }.start()
    }

    private fun buildTabBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(ui.p.card)
            setPadding(0, ui.dp(6), 0, ui.dp(6))
        }
        val names = listOf("Import" to "⇩", "Gallery" to "▦", "Glasses" to "◉", "Assistant" to "✦", "Settings" to "⚙")
        names.forEachIndexed { i, (label, icon) ->
            val tv = TextView(this).apply {
                text = "$icon\n$label"
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                maxLines = 2
                setPadding(0, ui.dp(6), 0, ui.dp(6))
                setOnClickListener { show(i) }
                contentDescription = label
            }
            tabViews += tv
            bar.addView(tv, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        }
        return bar
    }

    /** The tab to go back to from Diagnostics or Live view. */
    private var returnTab = TAB_GLASSES

    fun show(index: Int) {
        if (current in screens.indices) screens[current].onHidden()
        if (current in 0 until TAB_COUNT && index >= TAB_COUNT) returnTab = current
        current = index
        content.removeAllViews()
        content.addView(screens[index].build(), FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        tabViews.forEachIndexed { i, tv ->
            val on = i == index || (index >= TAB_COUNT && i == returnTab)
            tv.setTextColor(if (on) ui.p.accent else ui.p.muted)
            tv.typeface = if (on) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
        }
    }

    /** A key was just saved: sign in on the current link if it was opened without one. */
    fun onKeySaved() {
        if (glasses.state == XkGlasses.State.READY && !glasses.signedIn) glasses.signIn()
        refresh()
    }

    /** Leaves Diagnostics or Live view for the tab they were opened from. */
    fun goBack() { if (current >= TAB_COUNT) show(returnTab) }

    fun showDiagnostics() = show(SCREEN_DIAGNOSTICS)
    fun showLiveView() = show(SCREEN_LIVE)

    /** Back from Diagnostics or Live view returns to the tab it came from. */
    @Deprecated("Framework back handling")
    override fun onBackPressed() {
        if (current >= TAB_COUNT) show(returnTab)
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    /** Rebuild the visible screen, for example after a setting changes. */
    fun refresh() { if (current >= 0) show(current) }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", if (current >= TAB_COUNT) returnTab else current)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onStart() {
        super.onStart()
        // Tapping the glasses starts a voice question while the app is on screen.
        glasses.onTouch = { onGlassesTapped() }
    }

    override fun onStop() {
        glasses.onTouch = null
        // The microphone can't be used from the background, so end any voice turn.
        if (assistant.phase != Assistant.Phase.IDLE) assistant.stop()
        super.onStop()
    }

    private fun onGlassesTapped() {
        if (!prefs.tapToTalk) return
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

    // ---------- glasses drive ----------

    fun pickDrive() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_TREE)
        } catch (e: Exception) {
            toast("This phone has no file picker for drives.")
        }
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
                    assistant.photoUri = uri
                    (screens[TAB_ASSISTANT] as? AssistantScreen)?.section = AssistantScreen.SECTION_PHOTO
                    show(TAB_ASSISTANT)
                }
            }
        }
    }

    /** System photo picker for the assistant; the choice lands in [Assistant.photoUri]. */
    fun pickPhotoForAssistant() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_IMAGE)
        } catch (e: Exception) {
            toast("This phone has no photo picker.")
        }
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
            @Suppress("DEPRECATION")
            startIntentSenderForResult(pending.intentSender, REQ_DELETE, null, 0, 0, 0)
        } catch (e: Exception) {
            toast("Couldn't delete: ${e.message}")
        }
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        val pending = pendingPermission ?: return
        pendingPermission = null
        if (pending.required.all(::granted)) pending.action() else toast(pending.denied)
    }

    // ---------- small helpers ----------

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    fun confirm(title: String, message: String, yes: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(yes) { _, _ -> action() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun shareText(subject: String, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, subject))
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

        const val TAB_GLASSES = 2
        const val TAB_ASSISTANT = 3
        const val TAB_COUNT = 5
        const val SCREEN_DIAGNOSTICS = 5
        const val SCREEN_LIVE = 6
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
