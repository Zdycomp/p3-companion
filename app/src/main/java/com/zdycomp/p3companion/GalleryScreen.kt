package com.zdycomp.p3companion

import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/** Everything imported from the glasses, newest first. */
class GalleryScreen(private val a: MainActivity) : Screen {
    override val label = "Gallery"

    data class Item(val uri: Uri, val name: String, val mime: String, val kind: MediaKind, val date: Long, val durationMs: Long)

    private var filter = 0 // 0 all, 1 photos, 2 videos, 3 audio
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newFixedThreadPool(2)
    private val cache = object : LruCache<Uri, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Uri, value: Bitmap) = value.byteCount
    }

    override fun build(): View {
        val ui = a.ui
        val col = ui.column(16)
        col.addView(ui.title("Gallery"))

        val items = load()
        val shown = when (filter) {
            1 -> items.filter { it.kind == MediaKind.PHOTO }
            2 -> items.filter { it.kind == MediaKind.VIDEO }
            3 -> items.filter { it.kind == MediaKind.AUDIO }
            else -> items
        }
        val counts = listOf(
            "All ${items.size}",
            "Photos ${items.count { it.kind == MediaKind.PHOTO }}",
            "Videos ${items.count { it.kind == MediaKind.VIDEO }}",
            "Audio ${items.count { it.kind == MediaKind.AUDIO }}",
        )
        col.addView(ui.choiceRow("Show", counts, filter) { filter = it; a.refresh() })

        if (shown.isEmpty()) {
            val empty = ui.card()
            empty.addView(ui.heading(if (items.isEmpty()) "Nothing imported yet" else "Nothing of this type"))
            empty.addView(
                ui.body(
                    if (items.isEmpty()) "Plug your glasses in and import from the Import tab. " +
                        "Files land in the \"${a.prefs.albumName}\" album, so your phone's own gallery shows them too."
                    else "Pick another filter above.",
                    muted = true,
                )
            )
            if (items.isEmpty()) empty.addView(ui.primaryButton("Go to Import") { a.show(0) })
            col.addView(empty)
            return col
        }

        val grid = GridView(a).apply {
            numColumns = 3
            horizontalSpacing = ui.dp(6)
            verticalSpacing = ui.dp(6)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            adapter = Adapter(shown)
            setOnItemClickListener { _, _, pos, _ -> open(shown[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> actions(shown[pos]); true }
        }
        col.addView(ui.body("Tap to open. Press and hold to share or delete.", muted = true, sizeSp = 13f).apply {
            setPadding(0, 0, 0, ui.dp(8))
        })
        col.addView(grid, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        return col
    }

    private fun load(): List<Item> {
        val album = a.prefs.albumName
        val out = mutableListOf<Item>()
        fun query(collection: Uri, dir: String, kind: MediaKind) {
            val cols = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.DATE_TAKEN,
                MediaStore.MediaColumns.DURATION,
            )
            try {
                a.contentResolver.query(
                    collection, cols,
                    "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf("$dir/$album/"),
                    null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val taken = if (c.isNull(4)) 0L else c.getLong(4)
                        val added = c.getLong(3) * 1000
                        out += Item(
                            ContentUris.withAppendedId(collection, id),
                            c.getString(1) ?: "",
                            c.getString(2) ?: "",
                            kind,
                            if (taken > 0) taken else added,
                            if (c.isNull(5)) 0L else c.getLong(5),
                        )
                    }
                }
            } catch (e: Exception) {
                // A collection the phone won't let us read just stays empty.
            }
        }
        val vol = MediaStore.VOLUME_EXTERNAL_PRIMARY
        query(MediaStore.Images.Media.getContentUri(vol), Environment.DIRECTORY_PICTURES, MediaKind.PHOTO)
        query(MediaStore.Video.Media.getContentUri(vol), Environment.DIRECTORY_MOVIES, MediaKind.VIDEO)
        query(MediaStore.Audio.Media.getContentUri(vol), Environment.DIRECTORY_MUSIC, MediaKind.AUDIO)
        return out.sortedByDescending { it.date }
    }

    private fun open(item: Item) {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, item.mime.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            a.startActivity(view)
        } catch (e: Exception) {
            a.toast("No app on this phone can open ${item.name}.")
        }
    }

    private fun share(item: Item) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = item.mime.ifBlank { "*/*" }
            putExtra(Intent.EXTRA_STREAM, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        a.startActivity(Intent.createChooser(send, "Share ${item.name}"))
    }

    private fun actions(item: Item) {
        AlertDialog.Builder(a)
            .setTitle(item.name)
            .setItems(arrayOf("Open", "Share", "Delete from phone")) { _, which ->
                when (which) {
                    0 -> open(item)
                    1 -> share(item)
                    2 -> a.requestDelete(listOf(item.uri)) { cache.remove(item.uri); a.refresh() }
                }
            }
            .show()
    }

    private inner class Adapter(private val items: List<Item>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val ui = a.ui
            val cell = (convertView as? FrameLayout) ?: FrameLayout(a).apply {
                val side = (parent.width - ui.dp(12)) / 3
                layoutParams = ViewGroup.LayoutParams(Ui.MATCH, if (side > 0) side else ui.dp(110))
                background = ui.rounded(ui.p.cardAlt, 10)
                clipToOutline = true
                addView(ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP },
                    FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
                addView(TextView(a).apply {
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setShadowLayer(4f, 0f, 1f, Color.BLACK)
                    setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(4))
                }, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM or Gravity.END))
                addView(TextView(a).apply {
                    setTextColor(ui.p.muted)
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                }, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
            }
            val image = cell.getChildAt(0) as ImageView
            val badge = cell.getChildAt(1) as TextView
            val placeholder = cell.getChildAt(2) as TextView
            val item = items[position]

            badge.text = when (item.kind) {
                MediaKind.VIDEO -> "▶ " + duration(item.durationMs)
                MediaKind.AUDIO -> duration(item.durationMs)
                MediaKind.PHOTO -> ""
            }
            image.tag = item.uri
            image.setImageBitmap(null)
            placeholder.text = if (item.kind == MediaKind.AUDIO) "♪\nRecording" else ""

            if (item.kind != MediaKind.AUDIO) {
                val cached = cache.get(item.uri)
                if (cached != null) {
                    image.setImageBitmap(cached)
                } else {
                    loader.execute {
                        val bmp = try {
                            a.contentResolver.loadThumbnail(item.uri, Size(320, 320), null)
                        } catch (e: Exception) {
                            null
                        }
                        if (bmp != null) {
                            cache.put(item.uri, bmp)
                            main.post { if (image.tag == item.uri) image.setImageBitmap(bmp) }
                        }
                    }
                }
            }
            return cell
        }
    }

    private fun duration(ms: Long): String {
        if (ms <= 0) return ""
        val s = ms / 1000
        return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        else String.format("%d:%02d", s / 60, s % 60)
    }
}
