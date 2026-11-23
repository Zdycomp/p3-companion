package com.zdycomp.p3companion

import android.content.ContentResolver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns

/**
 * The SupRevolt scan in progress and its last answer, shared by the SupRevolt screen and the
 * "scan this" voice command. Network work runs on a background thread; state changes and
 * listeners run on the main thread.
 */
class SupRevolt private constructor(private val prefs: Prefs) {

    fun interface Listener { fun onSupRevoltChanged() }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<Listener>()
    private var turn = 0

    var busy = false; private set
    var status: String? = null; private set
    var error: String? = null; private set
    var job: SrJob? = null; private set
    /** What the last scan was of, for the results header ("Glasses photo", "lease.docx", "Typed text"). */
    var subject: String? = null; private set

    var testing = false; private set
    var health: SrHealth? = null; private set
    var catalog: List<SrScan>? = null; private set
    var testMessage: String? = null; private set
    var testOk = false; private set

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }
    private fun changed() = listeners.toList().forEach { it.onSupRevoltChanged() }

    private fun client(): SupRevoltClient {
        val url = prefs.srUrl ?: throw SupRevoltException("Server not set up. Add its address and token in Settings › SupRevolt.")
        val token = prefs.srToken ?: throw SupRevoltException("Server not set up. Add its token in Settings › SupRevolt.")
        val check = SupRevoltWire.checkUrl(url)
        if (check.kind == SupRevoltWire.UrlKind.LOCAL_HTTP && !prefs.srHttpOk) {
            throw SupRevoltException("Confirm the plain http:// address in Settings › SupRevolt first.")
        }
        return SupRevoltClient(url, token)
    }

    /** Health, then the catalog (which proves the token). */
    fun test() {
        if (testing) return
        testing = true
        testMessage = "Checking the server…"
        changed()
        Thread {
            val (msg, ok) = try {
                val c = client()
                val h = c.health()
                val cat = c.catalog()
                main.post { health = h; catalog = cat }
                val usable = cat.count { it.usable }
                val det = if (h.determinism == "ok") "determinism checks pass" else "determinism: ${h.determinism}"
                "Connected to SupRevolt ${h.version}. $usable of ${cat.size} scans ready, $det." to true
            } catch (e: SupRevoltException) {
                (e.message ?: "Couldn't reach the server.") to false
            } catch (e: Exception) {
                "Couldn't reach the server." to false
            }
            main.post { testing = false; testMessage = msg; testOk = ok; changed() }
        }.start()
    }

    /** Fetches the catalog quietly (for the scan chips) when it hasn't been loaded yet. */
    fun loadCatalog() {
        if (catalog != null || testing || !prefs.srReady) return
        Thread {
            val cat = runCatching { client().catalog() }.getOrNull()
            if (cat != null) main.post { catalog = cat; changed() }
        }.start()
    }

    fun forgetServer() {
        health = null; catalog = null; testMessage = null; testOk = false
        changed()
    }

    private fun scans(): List<String> = prefs.srScans.ifEmpty { listOf("all") }

    private fun begin(what: String, first: String): Int {
        val my = ++turn
        busy = true; error = null; status = first; subject = what
        changed()
        return my
    }

    private fun end(my: Int, result: SrJob?, err: String?, onDone: ((SrJob?, String?) -> Unit)?) {
        main.post {
            if (my != turn) return@post
            busy = false; status = null
            if (result != null) job = result
            error = err
            changed()
            onDone?.invoke(result, err)
        }
    }

    private fun run(what: String, first: String, onDone: ((SrJob?, String?) -> Unit)?, task: (SupRevoltClient) -> SrJob) {
        if (busy) { onDone?.invoke(null, "A scan is already running."); return }
        launch(begin(what, first), onDone, task)
    }

    private fun launch(my: Int, onDone: ((SrJob?, String?) -> Unit)?, task: (SupRevoltClient) -> SrJob) {
        Thread {
            try {
                val j = task(client())
                end(my, j, if (j.done) null else "The server is still working on it. Open SupRevolt to see it later.", onDone)
            } catch (e: SupRevoltException) {
                end(my, null, e.message, onDone)
            } catch (e: AiException) {
                end(my, null, e.message, onDone)
            } catch (e: Exception) {
                end(my, null, "The scan failed (${e.javaClass.simpleName}).", onDone)
            }
        }.start()
    }

    fun scanText(text: String, onDone: ((SrJob?, String?) -> Unit)? = null) {
        val t = text.trim()
        if (t.isEmpty()) { onDone?.invoke(null, "Type or paste some text first."); return }
        run("Typed text", "Scanning the text…", onDone) { it.scanText(t, scans()) }
    }

    /** A photo or any document: photos are shrunk to 2048 px JPEG first, other files go as they are. */
    fun scanUri(resolver: ContentResolver, uri: Uri, label: String? = null, onDone: ((SrJob?, String?) -> Unit)? = null) {
        run(label ?: displayName(resolver, uri) ?: "Document", "Sending to the server…", onDone) { upload(it, resolver, uri) }
    }

    private fun upload(c: SupRevoltClient, resolver: ContentResolver, uri: Uri): SrJob {
        val type = resolver.getType(uri) ?: "application/octet-stream"
        val name = displayName(resolver, uri)
        if (type.startsWith("image/")) {
            val jpeg = ImagePrep.jpegForAi(resolver, uri, maxSide = 2048)
            return c.scanBytes(jpeg, "image/jpeg", (name?.substringBeforeLast('.') ?: "photo") + ".jpg", scans())
        }
        val size = sizeOf(resolver, uri)
        if (size != null && size > MAX_UPLOAD) throw SupRevoltException("That file is over 8 MB, too big for the server.")
        val bytes = resolver.openInputStream(uri)?.use { s ->
            val buf = java.io.ByteArrayOutputStream()
            val b = ByteArray(65_536)
            while (true) {
                val n = s.read(b)
                if (n < 0) break
                if (buf.size() + n > MAX_UPLOAD) throw SupRevoltException("That file is over 8 MB, too big for the server.")
                buf.write(b, 0, n)
            }
            buf.toByteArray()
        } ?: throw SupRevoltException("Couldn't open that file.")
        if (bytes.isEmpty()) throw SupRevoltException("That file is empty.")
        return c.scanBytes(bytes, type, name, scans())
    }

    /**
     * Takes a new photo with the glasses, waits for it to arrive, and scans it (the "scan this" voice
     * command). The glasses must already be connected; [MainActivity.whenConnected] does that first.
     */
    fun scanNewGlassesPhoto(a: MainActivity, onDone: ((SrJob?, String?) -> Unit)? = null) {
        val g = a.glasses
        if (busy) { onDone?.invoke(null, "A scan is already running."); return }
        if (!prefs.srReady) { onDone?.invoke(null, "Server not set up."); return }
        if (g.state != XkGlasses.State.READY) {
            onDone?.invoke(null, if (g.state == XkGlasses.State.BUSY) "The glasses are busy." else "The glasses aren't connected.")
            return
        }
        val my = begin("Glasses photo", "Taking a photo with the glasses…")
        val before = g.lastPhoto
        var sawBusy = false
        lateinit var watcher: XkGlasses.Listener
        val timeout = Runnable {
            g.removeListener(watcher)
            if (my == turn && busy) end(my, null, "The glasses didn't send a photo in time.", onDone)
        }
        watcher = XkGlasses.Listener {
            if (my != turn) { g.removeListener(watcher); main.removeCallbacks(timeout); return@Listener }
            val now = g.lastPhoto
            if (now != null && now != before) {
                g.removeListener(watcher)
                main.removeCallbacks(timeout)
                status = "Sending the photo to the server…"
                changed()
                val resolver = a.contentResolver
                launch(my, onDone) { upload(it, resolver, now) }
                return@Listener
            }
            when (g.state) {
                XkGlasses.State.BUSY -> sawBusy = true
                XkGlasses.State.READY, XkGlasses.State.IDLE -> if (sawBusy || g.state == XkGlasses.State.IDLE) {
                    g.removeListener(watcher)
                    main.removeCallbacks(timeout)
                    end(my, null, g.lastError ?: "The glasses didn't send a photo.", onDone)
                }
                XkGlasses.State.CONNECTING -> {}
            }
        }
        g.addListener(watcher)
        main.postDelayed(timeout, 60_000)
        g.takePhoto()
    }

    fun clear() {
        if (busy) return
        job = null; error = null; subject = null
        changed()
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun sizeOf(resolver: ContentResolver, uri: Uri): Long? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()

    companion object {
        const val MAX_UPLOAD = 8 * 1024 * 1024

        @Volatile private var instance: SupRevolt? = null

        fun get(prefs: Prefs): SupRevolt =
            instance ?: synchronized(this) { instance ?: SupRevolt(prefs).also { instance = it } }
    }
}
