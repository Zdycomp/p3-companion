package com.zdycomp.p3companion

import android.content.ClipData
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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/** Everything imported from the glasses: filter, sort, grid size, select several to share or delete. */
class GalleryScreen(private val a: MainActivity) : Screen {
    override val label = "Gallery"

    data class Item(
        val uri: Uri, val name: String, val mime: String, val kind: MediaKind,
        val date: Long, val durationMs: Long, val size: Long,
    )

    private var filter = 0 // 0 all, 1 photos, 2 videos, 3 audio
    private var selecting = false
    private val selected = LinkedHashSet<Uri>()
    private var shown: List<Item> = emptyList()
    private var adapter: Adapter? = null
    private var titleView: TextView? = null
    private var grid: GridView? = null
    private var firstVisible = 0
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newFixedThreadPool(2)
    private val cache = object : LruCache<Uri, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Uri, value: Bitmap) = value.byteCount
    }

    override fun build(): View {
        val ui = a.ui
        val items = sort(load())
        shown = when (filter) {
            1 -> items.filter { it.kind == MediaKind.PHOTO }
            2 -> items.filter { it.kind == MediaKind.VIDEO }
            3 -> items.filter { it.kind == MediaKind.AUDIO }
            else -> items
        }
        selected.retainAll(shown.map { it.uri }.toSet())
        if (selected.isEmpty() && selecting && shown.isEmpty()) selecting = false

        val root = ui.column()
        val bar = if (selecting) selectionBar() else ui.topBar("Gallery", null, buildList {
            if (shown.isNotEmpty()) add(BarAction(R.drawable.ic_check_circle, "Select") { enterSelection(null) })
            add(BarAction(R.drawable.ic_sort, "Sort") { sortSheet() })
            add(BarAction(R.drawable.ic_grid, "Grid size") { gridSheet() })
        })
        root.addView(bar, LinearLayout.LayoutParams(Ui.MATCH, ui.dp(64)))

        val body = ui.column().apply { setPadding(ui.dp(16), 0, ui.dp(16), 0) }
        val counts = listOf(
            "All ${items.size}",
            "Photos ${items.count { it.kind == MediaKind.PHOTO }}",
            "Videos ${items.count { it.kind == MediaKind.VIDEO }}",
            "Audio ${items.count { it.kind == MediaKind.AUDIO }}",
        )
        if (items.isNotEmpty()) body.addView(ui.choiceRow(null, counts, filter) { filter = it; firstVisible = 0; a.refresh() })

        if (shown.isEmpty()) {
            body.addView(if (items.isEmpty()) ui.emptyState(
                R.drawable.ic_photo_library, "Nothing here yet",
                "Import from the glasses over the cable, or take photos on the Glasses tab. Everything lands in the " +
                    "\"${a.prefs.albumName}\" album, so your phone's gallery shows it too.",
                "Go to Import",
            ) { a.show(MainActivity.TAB_IMPORT) } else ui.emptyState(
                R.drawable.ic_photo_library, "Nothing of this type", "Pick another filter above.",
            ))
            root.addView(body, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
            return root
        }

        val cols = a.prefs.galleryColumns
        val gap = ui.dp(4)
        val ad = Adapter(cols, gap)
        adapter = ad
        val gv = GridView(a).apply {
            numColumns = cols
            horizontalSpacing = gap
            verticalSpacing = gap
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            clipToPadding = false
            setPadding(0, ui.dp(4), 0, ui.dp(16))
            isVerticalScrollBarEnabled = false
            this.adapter = ad
            setOnItemClickListener { _, view, pos, _ ->
                val item = shown[pos]
                if (selecting) toggle(item, view) else open(item)
            }
            setOnItemLongClickListener { _, view, pos, _ ->
                ui.haptic(view, Haptic.LONG_PRESS)
                if (!selecting) enterSelection(shown[pos]) else toggle(shown[pos], view)
                true
            }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {}
                override fun onScroll(view: AbsListView?, first: Int, visible: Int, total: Int) { firstVisible = first }
            })
            setSelection(firstVisible.coerceAtMost(shown.lastIndex))
        }
        grid = gv
        body.addView(gv, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        root.addView(body, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        return root
    }

    // ---------------- selection ----------------

    private fun selectionBar(): LinearLayout {
        val ui = a.ui
        val bar = ui.topBar("", { exitSelection() }, listOf(
            BarAction(R.drawable.ic_select_all, "Select all") {
                if (selected.size == shown.size) selected.clear() else selected.addAll(shown.map { it.uri })
                updateSelection()
            },
            BarAction(R.drawable.ic_share, "Share") { shareSelected() },
            BarAction(R.drawable.ic_delete, "Delete") { deleteSelected() },
            BarAction(R.drawable.ic_more_vert, "More") { moreSheet() },
        ))
        bar.setBackgroundColor(a.ui.s.surfaceContainerHigh)
        titleView = (0 until bar.childCount).map { bar.getChildAt(it) }.filterIsInstance<TextView>().firstOrNull()
        titleView?.text = title()
        return bar
    }

    private fun title() = if (selected.isEmpty()) "Select items" else "${selected.size} selected"

    private fun enterSelection(first: Item?) {
        selecting = true
        selected.clear()
        first?.let { selected += it.uri }
        a.refresh()
    }

    private fun exitSelection() {
        selecting = false
        selected.clear()
        a.refresh()
    }

    private fun toggle(item: Item, view: View?) {
        if (!selected.remove(item.uri)) selected += item.uri
        a.ui.haptic(view, Haptic.TICK)
        updateSelection()
    }

    private fun updateSelection() {
        titleView?.text = title()
        titleView?.announceForAccessibility(title())
        adapter?.notifyDataSetChanged()
    }

    override fun onBack(): Boolean {
        if (!selecting) return false
        exitSelection()
        return true
    }

    private fun chosen(): List<Item> = shown.filter { it.uri in selected }

    private fun shareSelected() {
        val list = chosen()
        if (list.isEmpty()) { a.toast("Select something to share first."); return }
        if (list.size == 1) { share(list[0]); return }
        val uris = ArrayList(list.map { it.uri })
        val kinds = list.map { it.mime.substringBefore('/') }.toSet()
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = if (kinds.size == 1) kinds.first() + "/*" else "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        a.launch(Intent.createChooser(send, "Share ${list.size} items"))
    }

    private fun deleteSelected() {
        val list = chosen()
        if (list.isEmpty()) { a.toast("Select something to delete first."); return }
        a.requestDelete(list.map { it.uri }) {
            list.forEach { cache.remove(it.uri) }
            a.toast(if (list.size == 1) "Deleted." else "Deleted ${list.size} items.")
            exitSelection()
        }
    }

    private fun moreSheet() {
        val list = chosen()
        val one = list.singleOrNull()
        val items = buildList {
            if (one != null) add(SheetItem("Open", R.drawable.ic_open_in_new) { open(one) })
            if (one != null) add(SheetItem("Details", R.drawable.ic_info, "Pixel size and file size") { details(one) })
            if (one != null && one.kind == MediaKind.PHOTO) {
                add(SheetItem("Ask the Assistant about it", R.drawable.ic_auto_awesome) { selecting = false; selected.clear(); a.askAboutPhoto(one.uri) })
                add(SheetItem("Send to SupRevolt", R.drawable.ic_shield_scan, "Scan it on your own server") {
                    selecting = false; selected.clear(); a.sendToSupRevolt(one.uri)
                })
            }
            add(SheetItem(if (selected.size == shown.size) "Select none" else "Select all", R.drawable.ic_select_all) {
                if (selected.size == shown.size) selected.clear() else selected.addAll(shown.map { it.uri })
                updateSelection()
            })
            add(SheetItem("Share", R.drawable.ic_share) { shareSelected() })
            add(SheetItem("Delete from phone", R.drawable.ic_delete, danger = true) { deleteSelected() })
        }
        a.ui.sheet(one?.name ?: title(), items)
    }

    // ---------------- sort and grid ----------------

    private fun sort(list: List<Item>): List<Item> = when (a.prefs.gallerySort) {
        Prefs.GallerySort.NEWEST -> list.sortedByDescending { it.date }
        Prefs.GallerySort.OLDEST -> list.sortedBy { it.date }
        Prefs.GallerySort.LARGEST -> list.sortedByDescending { it.size }
        Prefs.GallerySort.NAME -> list.sortedBy { it.name.lowercase() }
    }

    private fun sortSheet() {
        val cur = a.prefs.gallerySort
        val names = mapOf(
            Prefs.GallerySort.NEWEST to "Newest first", Prefs.GallerySort.OLDEST to "Oldest first",
            Prefs.GallerySort.LARGEST to "Largest first", Prefs.GallerySort.NAME to "By name",
        )
        a.ui.sheet("Sort", names.map { (k, v) ->
            SheetItem(v, selected = k == cur) { a.prefs.gallerySort = k; firstVisible = 0; a.refresh() }
        })
    }

    private fun gridSheet() {
        val cur = a.prefs.galleryColumns
        a.ui.sheet("Grid size", (2..5).map { n ->
            SheetItem("$n columns", R.drawable.ic_grid, selected = n == cur) { a.prefs.galleryColumns = n; a.refresh() }
        })
    }

    // ---------------- media ----------------

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
                MediaStore.MediaColumns.SIZE,
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
                            if (c.isNull(6)) 0L else c.getLong(6),
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
        return out
    }

    private fun open(item: Item) = a.openMedia(item.uri, item.mime.ifBlank { "*/*" })

    /**
     * 3.8: the item's real pixel size (read from the photo's own frame header, not from a thumbnail),
     * its file size and date, so a Bluetooth photo and a cable photo can be told apart.
     */
    private fun details(item: Item) {
        loader.execute {
            var info: JpegInfo.Info? = null
            var pictures: Int? = null
            var w = 0
            var h = 0
            if (item.kind == MediaKind.PHOTO) {
                runCatching {
                    a.contentResolver.openInputStream(item.uri)?.use { input ->
                        val head = ByteArray(HEAD_BYTES)
                        var len = 0
                        while (len < head.size) { val n = input.read(head, len, head.size - len); if (n < 0) break; len += n }
                        info = JpegInfo.parse(head, len)
                        if (info != null && item.size in 1..len.toLong()) pictures = JpegInfo.soiCount(head, len)
                        if (info == null) {
                            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            android.graphics.BitmapFactory.decodeByteArray(head, 0, len, o)
                            w = maxOf(o.outWidth, 0); h = maxOf(o.outHeight, 0)
                        }
                    }
                }
            } else if (item.kind == MediaKind.VIDEO) {
                runCatching {
                    a.contentResolver.query(item.uri, arrayOf(MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT), null, null, null)?.use { c ->
                        if (c.moveToFirst()) { w = c.getInt(0); h = c.getInt(1) }
                    }
                }
            }
            val i = info
            val lines = buildList {
                when {
                    i != null -> {
                        add("Pixel size: ${i.size} (" + String.format(java.util.Locale.US, "%.2f MP", i.megapixels) + ")")
                        add("Picture: " + JpegInfo.details(i, pictures).substringAfter(", "))
                    }
                    w > 0 && h > 0 -> add("Pixel size: ${w}x$h")
                    item.kind != MediaKind.AUDIO -> add("Pixel size: unknown")
                }
                add("File size: " + JpegInfo.kb(item.size) + if (item.size >= 1_048_576) String.format(java.util.Locale.US, " (%.1f MB)", item.size / 1_048_576.0) else "")
                if (item.durationMs > 0) add("Length: " + duration(item.durationMs))
                add("Date: " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(item.date)))
                if (item.name.startsWith("P3_probe")) add("Saved by Find full-size photos (Diagnostics)")
            }
            main.post { a.ui.dialog(item.name, lines.joinToString("\n"), R.drawable.ic_info) }
        }
    }

    private fun share(item: Item) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = item.mime.ifBlank { "*/*" }
            putExtra(Intent.EXTRA_STREAM, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        a.launch(Intent.createChooser(send, "Share ${item.name}"))
    }

    private class Cell(
        val root: FrameLayout, val image: ImageView, val tile: LinearLayout, val pill: TextView,
        val check: ImageView,
    )

    private inner class Adapter(private val cols: Int, private val gap: Int) : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val ui = a.ui
            val s = ui.s
            val side = ((parent.width - parent.paddingLeft - parent.paddingRight - gap * (cols - 1)) / cols).takeIf { it > 0 } ?: ui.dp(110)
            val cell = (convertView?.tag as? Cell) ?: run {
                val root = FrameLayout(a).apply {
                    background = ui.rounded(s.surfaceContainerHigh, 8)
                    clipToOutline = true
                }
                val image = ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                root.addView(image, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
                val tile = ui.column().apply {
                    gravity = Gravity.CENTER
                    background = ui.rounded(s.tertiaryContainer, 8)
                    addView(ui.icon(R.drawable.ic_mic, s.onTertiaryContainer, 32), LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)))
                }
                root.addView(tile, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
                val pill = TextView(a).apply {
                    ui.style(this, Type.LABEL_SMALL, Color.WHITE)
                    fontFeatureSettings = "tnum"
                    background = ui.rounded(0x99000000.toInt(), 100)
                    setPadding(ui.dp(6), ui.dp(2), ui.dp(8), ui.dp(2))
                    gravity = Gravity.CENTER_VERTICAL
                }
                root.addView(pill, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM or Gravity.START).apply {
                    leftMargin = ui.dp(6); bottomMargin = ui.dp(6)
                })
                val check = ImageView(a)
                root.addView(check, FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.TOP or Gravity.END).apply {
                    topMargin = ui.dp(6); rightMargin = ui.dp(6)
                })
                Cell(root, image, tile, pill, check).also { root.tag = it }
            }
            cell.root.layoutParams = AbsListView.LayoutParams(Ui.MATCH, side)
            val item = shown[position]
            val label = when (item.kind) { MediaKind.PHOTO -> "Photo"; MediaKind.VIDEO -> "Video"; MediaKind.AUDIO -> "Recording" }
            cell.root.contentDescription = "$label ${item.name}" + (duration(item.durationMs).takeIf { it.isNotEmpty() }?.let { ", $it" } ?: "")

            // Badges
            cell.tile.visibility = if (item.kind == MediaKind.AUDIO) View.VISIBLE else View.GONE
            val dur = duration(item.durationMs)
            when (item.kind) {
                MediaKind.VIDEO -> {
                    cell.pill.visibility = View.VISIBLE
                    cell.pill.text = dur
                    cell.pill.setCompoundDrawablesRelative(ui.drawable(R.drawable.ic_play, Color.WHITE, 14), null, null, null)
                    cell.pill.compoundDrawablePadding = ui.dp(2)
                }
                MediaKind.AUDIO -> {
                    cell.pill.visibility = if (dur.isEmpty()) View.GONE else View.VISIBLE
                    cell.pill.text = dur
                    cell.pill.setCompoundDrawablesRelative(null, null, null, null)
                }
                MediaKind.PHOTO -> cell.pill.visibility = View.GONE
            }

            // Selection
            val on = item.uri in selected
            cell.check.visibility = if (selecting) View.VISIBLE else View.GONE
            if (selecting) {
                cell.check.setImageDrawable(
                    if (on) ui.drawable(R.drawable.ic_check_circle, s.primary)
                    else ui.oval(0x66000000, strokeColor = Color.WHITE, strokeDp = 2)
                )
                cell.check.background = if (on) ui.oval(s.onPrimary) else null
            }
            val k = if (on) 0.86f else 1f
            cell.image.scaleX = k; cell.image.scaleY = k
            cell.root.stateDescription = if (selecting) (if (on) "Selected" else "Not selected") else null

            // Thumbnail
            cell.image.tag = item.uri
            cell.image.setImageBitmap(null)
            if (item.kind != MediaKind.AUDIO) {
                val cached = cache.get(item.uri)
                if (cached != null) {
                    cell.image.setImageBitmap(cached)
                } else {
                    val px = if (cols <= 2) 480 else if (cols == 3) 320 else 240
                    val image = cell.image
                    loader.execute {
                        val bmp = try {
                            a.contentResolver.loadThumbnail(item.uri, Size(px, px), null)
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
            return cell.root
        }
    }

    override fun onHidden() {
        grid?.let { firstVisible = it.firstVisiblePosition }
        grid = null
        titleView = null
        adapter = null
    }

    private companion object {
        /** Enough of a photo's start to reach its frame header past a large EXIF block. */
        const val HEAD_BYTES = 512 * 1024
    }

    private fun duration(ms: Long): String {
        if (ms <= 0) return ""
        val s = ms / 1000
        return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        else String.format("%d:%02d", s / 60, s % 60)
    }
}
