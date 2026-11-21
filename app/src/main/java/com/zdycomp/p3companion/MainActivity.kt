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

    private lateinit var content: FrameLayout
    private lateinit var screens: List<Screen>
    private val tabViews = mutableListOf<TextView>()
    private var current = -1
    private var afterBluetoothGrant: (() -> Unit)? = null
    private var afterDelete: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        ui = Ui(this, Palette.resolve(this, prefs.theme))
        importer = App.importer(this)
        glasses = XkGlasses.get(this, prefs)

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

        screens = listOf(ImportScreen(this), GalleryScreen(this), GlassesScreen(this), SettingsScreen(this))

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
        show(start.coerceIn(0, screens.size - 1))
        if (prefs.autoReconnect && prefs.glassesAddress != null && hasBluetoothPermission()) {
            glasses.reconnectSaved()
        }
        handleShared(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShared(intent)
    }

    /** A bug report or log shared to the app from the bug report notification. */
    private fun handleShared(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (uri != null) {
            show(2)
            readKeyFrom(uri)
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

    private fun readKeyFrom(uri: Uri) {
        val dialog = android.app.ProgressDialog.show(this, "Reading the log", "Looking for your glasses key…", true)
        Thread {
            val result = runCatching { KeyFinder.find(contentResolver, uri) }
            runOnUiThread {
                runCatching { dialog.dismiss() }
                val r = result.getOrNull()
                when {
                    r == null -> toast("Couldn't read that file: ${result.exceptionOrNull()?.message}")
                    r.key != null -> {
                        prefs.userKey = r.key
                        AlertDialog.Builder(this)
                            .setTitle("Key found")
                            .setMessage("Saved ${KeyFinder.mask(r.key)}. You can turn the Bluetooth snoop log off now, then connect.")
                            .setPositiveButton("OK", null)
                            .show()
                        show(2)
                    }
                    else -> AlertDialog.Builder(this)
                        .setTitle("No key in that file")
                        .setMessage(
                            "Read ${r.scannedBytes / 1_048_576} MB but didn't find the glasses' sign-in. " +
                                "Make sure the snoop log was on (Enabled, not Filtered), Bluetooth was turned off and on " +
                                "after switching it on, and LensMoo connected to the glasses before you took the bug report."
                        )
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
        val names = listOf("Import" to "⇩", "Gallery" to "▦", "Glasses" to "◉", "Settings" to "⚙")
        names.forEachIndexed { i, (label, icon) ->
            val tv = TextView(this).apply {
                text = "$icon\n$label"
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, ui.dp(6), 0, ui.dp(6))
                setOnClickListener { show(i) }
                contentDescription = label
            }
            tabViews += tv
            bar.addView(tv, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        }
        return bar
    }

    fun show(index: Int) {
        if (current in screens.indices) screens[current].onHidden()
        current = index
        content.removeAllViews()
        content.addView(screens[index].build(), FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        tabViews.forEachIndexed { i, tv ->
            val on = i == index
            tv.setTextColor(if (on) ui.p.accent else ui.p.muted)
            tv.typeface = if (on) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
        }
    }

    /** Rebuild the visible screen, for example after a setting changes. */
    fun refresh() { if (current >= 0) show(current) }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", current)
    }

    override fun onResume() {
        super.onResume()
        refresh()
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

    // ---------- Bluetooth permissions ----------

    private fun bluetoothPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()

    fun hasBluetoothPermission(): Boolean =
        bluetoothPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun withBluetooth(action: () -> Unit) {
        if (hasBluetoothPermission()) {
            if (!glasses.bluetoothOn()) {
                try {
                    @Suppress("DEPRECATION")
                    startActivity(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE))
                } catch (e: Exception) {
                    toast("Turn on Bluetooth first.")
                }
                return
            }
            action()
        } else {
            afterBluetoothGrant = action
            requestPermissions(bluetoothPermissions(), REQ_BT)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_BT) return
        val action = afterBluetoothGrant
        afterBluetoothGrant = null
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            action?.let { withBluetooth(it) }
        } else {
            toast("Bluetooth permission is needed to reach your glasses.")
        }
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
        private const val REQ_BT = 43
        private const val REQ_KEYFILE = 44
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
