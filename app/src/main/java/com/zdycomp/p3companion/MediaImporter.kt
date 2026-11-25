package com.zdycomp.p3companion

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32

enum class MediaKind { PHOTO, VIDEO, AUDIO }

/** One file found on the glasses' drive. */
data class DriveFile(
    val docUri: Uri,
    val name: String,
    val mime: String,
    val size: Long,
    val modified: Long,
    val kind: MediaKind,
) {
    /** Same name, size and time means we already have it. */
    val key: String get() = "$name|$size|$modified"
}

data class ImportProgress(
    val stage: Stage,
    val done: Int = 0,
    val total: Int = 0,
    val currentName: String = "",
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
) {
    enum class Stage { SCANNING, COPYING }

    val percent: Int
        get() = if (bytesTotal <= 0) 0 else ((bytesDone * 100) / bytesTotal).toInt().coerceIn(0, 100)
}

/**
 * 3.8: one file copied over the cable: its pixel size from the frame header (0 x 0 when unknown or not a
 * photo), its bytes, and whether reading the gallery copy back gave the very same bytes.
 */
data class ImportedFile(
    val name: String,
    val kind: MediaKind,
    val bytes: Long,
    val width: Int = 0,
    val height: Int = 0,
    /** true: same bytes read back; false: they differ; null: not checked (videos and recordings). */
    val sameBytes: Boolean? = null,
) {
    /** "IMG_0001.JPG: 1280x960, 400 KB, byte-for-byte copy". */
    fun line(): String {
        val parts = mutableListOf<String>()
        if (kind == MediaKind.PHOTO) parts += if (width > 0 && height > 0) "${width}x$height" else "size unknown"
        parts += if (bytes >= 10L * 1024 * 1024) String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0) else JpegInfo.kb(bytes)
        when (sameBytes) {
            true -> parts += "byte-for-byte copy"
            false -> parts += "the gallery copy reads back different (the phone may hide location data from apps)"
            null -> {}
        }
        return "$name: " + parts.joinToString(", ")
    }

    val size: PhotoStats.Size get() = PhotoStats.Size(width, height, bytes)
}

data class ImportResult(
    val photos: Int,
    val videos: Int,
    val audio: Int,
    val skipped: Int,
    val failed: List<String>,
    val deleted: Int,
    val cancelled: Boolean,
    /** 3.8: every file copied, in order, with its sizes. */
    val files: List<ImportedFile> = emptyList(),
) {
    val imported: Int get() = photos + videos + audio

    /** 3.8: the photos of this import, for the cable side of the size comparison. */
    fun photoSizes(): List<PhotoStats.Size> = files.filter { it.kind == MediaKind.PHOTO }.map { it.size }

    fun summary(): String {
        if (cancelled && imported == 0) return "Import stopped before anything was copied."
        if (imported == 0 && failed.isEmpty()) return "Nothing new on the glasses."
        val parts = mutableListOf<String>()
        if (photos > 0) parts += plural(photos, "photo")
        if (videos > 0) parts += plural(videos, "video")
        if (audio > 0) parts += plural(audio, "recording")
        val sb = StringBuilder()
        if (parts.isNotEmpty()) sb.append("Imported ").append(parts.joinToString(", ")).append('.')
        if (deleted > 0) sb.append(" Removed ").append(deleted).append(" from the glasses.")
        if (failed.isNotEmpty()) sb.append(" ").append(plural(failed.size, "file")).append(" failed.")
        if (cancelled) sb.append(" Stopped early.")
        return sb.toString().trim()
    }

    private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"
}

/**
 * Copies photos, videos and recordings from the glasses' USB drive into the phone's gallery.
 *
 * The glasses show up as an ordinary USB drive (the maker says to read them that way on a PC),
 * so this uses Android's standard storage picker and MediaStore. No vendor code or protocol.
 */
class MediaImporter(context: Context, private val prefs: Prefs) {
    private val app = context.applicationContext
    private val resolver: ContentResolver = app.contentResolver
    private val ledgerFile = File(app.filesDir, "imported.txt")
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cancelFlag = AtomicBoolean(false)

    @Volatile
    var running = false
        private set

    /** Latest progress, result and error, so any screen can show them, even after a tab change. */
    @Volatile
    var progress: ImportProgress? = null
        private set
    @Volatile
    var lastResult: ImportResult? = null
        private set
    @Volatile
    var lastError: String? = null
        private set

    /** True when the saved drive is plugged in and readable right now. */
    fun driveAvailable(): Boolean {
        val tree = prefs.driveUri?.let(Uri::parse) ?: return false
        val granted = resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
        if (!granted) return false
        return try {
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId)
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
                ?.use { true } ?: false
        } catch (e: Exception) {
            false
        }
    }

    fun cancel() = cancelFlag.set(true)

    fun start(onFinished: () -> Unit = {}) {
        if (running) return
        val tree = prefs.driveUri?.let(Uri::parse)
        if (tree == null) {
            lastError = "Choose the glasses' drive first."
            onFinished()
            return
        }
        running = true
        lastError = null
        lastResult = null
        progress = ImportProgress(ImportProgress.Stage.SCANNING)
        cancelFlag.set(false)
        worker.execute {
            val report: (ImportProgress) -> Unit = { progress = it }
            try {
                val result = runImport(tree, report)
                prefs.lastImportSummary = stamp() + " · " + result.summary()
                val sizes = result.photoSizes()
                if (sizes.isNotEmpty()) prefs.cablePhotoSizes = PhotoStats.append(prefs.cablePhotoSizes, sizes)
                main.post { lastResult = result; progress = null; running = false; onFinished() }
            } catch (e: SecurityException) {
                main.post {
                    lastError = "The phone no longer lets this app read that drive. Choose it again."
                    progress = null; running = false; onFinished()
                }
            } catch (e: Exception) {
                main.post {
                    lastError = "Couldn't read the glasses: ${e.message ?: e.javaClass.simpleName}"
                    progress = null; running = false; onFinished()
                }
            }
        }
    }

    private fun runImport(tree: Uri, onProgress: (ImportProgress) -> Unit): ImportResult {
        main.post { onProgress(ImportProgress(ImportProgress.Stage.SCANNING)) }
        val all = mutableListOf<DriveFile>()
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        walk(tree, rootId, all, depth = 0)

        val ledger = loadLedger()
        val wanted = all.filter {
            when (it.kind) {
                MediaKind.PHOTO -> prefs.importPhotos
                MediaKind.VIDEO -> prefs.importVideos
                MediaKind.AUDIO -> prefs.importAudio
            }
        }
        val fresh = wanted.filter { it.key !in ledger }.sortedBy { it.modified }
        val skipped = wanted.size - fresh.size
        val bytesTotal = fresh.sumOf { maxOf(it.size, 0L) }

        var photos = 0
        var videos = 0
        var audio = 0
        var deleted = 0
        val failed = mutableListOf<String>()
        val files = mutableListOf<ImportedFile>()
        var bytesDone = 0L
        var lastPost = 0L

        fresh.forEachIndexed { index, file ->
            if (cancelFlag.get()) {
                return ImportResult(photos, videos, audio, skipped, failed, deleted, cancelled = true, files = files.toList())
            }
            main.post {
                onProgress(ImportProgress(ImportProgress.Stage.COPYING, index, fresh.size, file.name, bytesDone, bytesTotal))
            }
            val base = bytesDone
            val copied = copyToGallery(file) { copied ->
                val now = System.currentTimeMillis()
                if (now - lastPost > 150) {
                    lastPost = now
                    val d = base + copied
                    main.post {
                        onProgress(ImportProgress(ImportProgress.Stage.COPYING, index, fresh.size, file.name, d, bytesTotal))
                    }
                }
            }
            bytesDone = base + maxOf(file.size, 0L)
            if (copied != null) {
                files += copied
                appendLedger(file.key)
                when (file.kind) {
                    MediaKind.PHOTO -> photos++
                    MediaKind.VIDEO -> videos++
                    MediaKind.AUDIO -> audio++
                }
                if (prefs.deleteAfterImport) {
                    val gone = try {
                        DocumentsContract.deleteDocument(resolver, file.docUri)
                    } catch (e: Exception) {
                        false
                    }
                    if (gone) deleted++
                }
            } else {
                failed += file.name
            }
        }
        return ImportResult(photos, videos, audio, skipped, failed, deleted, cancelled = false, files = files.toList())
    }

    private fun walk(tree: Uri, parentId: String, out: MutableList<DriveFile>, depth: Int) {
        if (depth > 8 || cancelFlag.get()) return
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val folders = mutableListOf<String>()
        resolver.query(childrenUri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                val size = if (c.isNull(3)) -1L else c.getLong(3)
                val modified = if (c.isNull(4)) 0L else c.getLong(4)
                if (name.startsWith(".")) continue
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (name.equals("System Volume Information", true) || name.equals("LOST.DIR", true)) continue
                    folders += id
                    continue
                }
                val kind = kindOf(name, mime) ?: continue
                if (size == 0L) continue // a clip still being written, or an empty stub
                out += DriveFile(
                    DocumentsContract.buildDocumentUriUsingTree(tree, id),
                    name, mime, size, modified, kind
                )
            }
        }
        folders.forEach { walk(tree, it, out, depth + 1) }
    }

    /**
     * Copies the file's bytes unchanged into the gallery (a plain stream copy: no decoding, no
     * recompression, EXIF kept). 3.8: for photos the first [HEAD_BYTES] are kept to read the pixel
     * size, and the gallery copy is read back and compared (CRC-32) with what came off the drive.
     * Returns the copied file, or null when it failed.
     */
    private fun copyToGallery(file: DriveFile, onBytes: (Long) -> Unit): ImportedFile? {
        val album = prefs.albumName
        val (collection, dir) = when (file.kind) {
            MediaKind.PHOTO -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_PICTURES
            MediaKind.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MOVIES
            MediaKind.AUDIO -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MUSIC
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, targetName(file))
            put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(file))
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/$album")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (file.modified > 0) {
                put(MediaStore.MediaColumns.DATE_MODIFIED, file.modified / 1000)
                if (file.kind != MediaKind.AUDIO) put(MediaStore.MediaColumns.DATE_TAKEN, file.modified)
            }
        }
        val target = try {
            resolver.insert(collection, values)
        } catch (e: Exception) {
            null
        } ?: return null

        val photo = file.kind == MediaKind.PHOTO
        return try {
            var copied = 0L
            val head = if (photo) ByteArray(HEAD_BYTES) else null
            var headLen = 0
            val crc = CRC32()
            resolver.openInputStream(file.docUri).use { input ->
                resolver.openOutputStream(target, "w").use { output ->
                    if (input == null || output == null) error("stream unavailable")
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (cancelFlag.get()) error("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        if (head != null) {
                            crc.update(buf, 0, n)
                            if (headLen < head.size) {
                                val k = minOf(n, head.size - headLen)
                                System.arraycopy(buf, 0, head, headLen, k)
                                headLen += k
                            }
                        }
                        copied += n
                        onBytes(copied)
                    }
                    output.flush()
                }
            }
            if (file.size > 0 && copied != file.size) error("short copy")
            resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            if (head == null) return ImportedFile(file.name, file.kind, copied)
            val (w, h) = pixelSize(head, headLen)
            val same = runCatching { readBackCrc(target) == crc.value }.getOrNull()
            ImportedFile(file.name, file.kind, copied, w, h, same)
        } catch (e: Exception) {
            runCatching { resolver.delete(target, null, null) }
            null
        }
    }

    /** Width and height from the JPEG frame header, or Android's header reader for PNG/WebP/HEIC; 0 x 0 when unknown. */
    private fun pixelSize(head: ByteArray, len: Int): Pair<Int, Int> {
        JpegInfo.parse(head, len)?.let { return it.width to it.height }
        return runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(head, 0, len, o)
            if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else 0 to 0
        }.getOrDefault(0 to 0)
    }

    private fun readBackCrc(uri: Uri): Long {
        val crc = CRC32()
        resolver.openInputStream(uri).use { input ->
            if (input == null) error("can't read back")
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                crc.update(buf, 0, n)
            }
        }
        return crc.value
    }

    private fun targetName(file: DriveFile): String {
        if (prefs.naming == Prefs.Naming.ORIGINAL || file.modified <= 0) return file.name
        val ext = file.name.substringAfterLast('.', "").lowercase(Locale.US)
        val prefix = when (file.kind) {
            MediaKind.PHOTO -> "IMG"
            MediaKind.VIDEO -> "VID"
            MediaKind.AUDIO -> "REC"
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(file.modified))
        return if (ext.isEmpty()) "P3_${prefix}_$stamp" else "P3_${prefix}_$stamp.$ext"
    }

    private fun mimeFor(file: DriveFile): String {
        if (file.mime.isNotBlank() && file.mime != "application/octet-stream") return file.mime
        return mimeFromExtension(file.name) ?: when (file.kind) {
            MediaKind.PHOTO -> "image/jpeg"
            MediaKind.VIDEO -> "video/mp4"
            MediaKind.AUDIO -> "audio/mpeg"
        }
    }

    @Synchronized
    private fun loadLedger(): MutableSet<String> =
        if (ledgerFile.exists()) ledgerFile.readLines().filter { it.isNotBlank() }.toHashSet() else HashSet()

    @Synchronized
    private fun appendLedger(key: String) = ledgerFile.appendText(key + "\n")

    /** Forget what was imported, so the next import copies everything again. */
    @Synchronized
    fun resetLedger() {
        ledgerFile.delete()
    }

    fun ledgerCount(): Int = loadLedger().size

    private fun stamp(): String = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date())

    companion object {
        /** Enough of a photo's start to reach its frame header past a large EXIF block. */
        private const val HEAD_BYTES = 512 * 1024
        private val PHOTO_EXT = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "dng")
        private val VIDEO_EXT = setOf("mp4", "mov", "m4v", "3gp", "avi", "mkv", "ts")
        private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "wav", "amr", "opus", "ogg", "flac")

        fun kindOf(name: String, mime: String): MediaKind? {
            when {
                mime.startsWith("image/") -> return MediaKind.PHOTO
                mime.startsWith("video/") -> return MediaKind.VIDEO
                mime.startsWith("audio/") -> return MediaKind.AUDIO
            }
            val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
            return when (ext) {
                in PHOTO_EXT -> MediaKind.PHOTO
                in VIDEO_EXT -> MediaKind.VIDEO
                in AUDIO_EXT -> MediaKind.AUDIO
                else -> null
            }
        }

        fun mimeFromExtension(name: String): String? =
            when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "heic" -> "image/heic"
                "heif" -> "image/heif"
                "webp" -> "image/webp"
                "dng" -> "image/x-adobe-dng"
                "mp4", "m4v" -> "video/mp4"
                "mov" -> "video/quicktime"
                "3gp" -> "video/3gpp"
                "avi" -> "video/x-msvideo"
                "mkv" -> "video/x-matroska"
                "ts" -> "video/mp2t"
                "mp3" -> "audio/mpeg"
                "m4a", "aac" -> "audio/mp4"
                "wav" -> "audio/x-wav"
                "amr" -> "audio/amr"
                "opus", "ogg" -> "audio/ogg"
                "flac" -> "audio/flac"
                else -> null
            }
    }
}
