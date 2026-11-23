package com.zdycomp.p3companion

import java.util.Locale

/*
 * SupRevolt (docs/SUPREVOLT-ARCHITECTURE.md): the wire format between the app and Rick's scan
 * server, as pure Kotlin with no android.* imports, so the JVM tests run exactly the code the app
 * runs. Builds requests, checks server addresses, parses replies into [SrJob] / [SrVerdict] /
 * [SrResult], and turns a confidence into "218/256" and "85%" from the same integer.
 */

/** Something went wrong talking to the server; the message is for people (never holds the token). */
class SupRevoltException(message: String) : Exception(message)

data class SrHealth(
    val ok: Boolean,
    val version: String,
    val catalogVersion: String,
    val queueDepth: Int,
    val determinism: String,
)

data class SrScan(
    val id: String,
    val title: String,
    val version: String,
    val accepts: String,
    val deterministic: Boolean,
    val needsKey: Boolean,
    val keyPresent: Boolean,
    val enabled: Boolean,
) {
    /** The server can run it right now (has its AI key, and it is switched on). */
    val usable: Boolean get() = enabled && (!needsKey || keyPresent)
}

data class SrResult(
    val scan: String,
    val title: String,
    val state: String,
    val risk: Int?,
    val confidence: Int?,
    val deterministic: Boolean,
    val cached: Boolean,
    val ms: Long,
    val note: String,
    val fromPhotoText: Boolean,
    val details: List<String>,
) {
    val skipped: Boolean get() = state == "n/a"
    val unknown: Boolean get() = state == "unknown" || state == "error"
}

data class SrVerdict(
    val level: String,
    val risk: Int?,
    val confidence: Int,
    val reasons: List<String>,
) {
    val percent: Int get() = SupRevoltWire.percent(confidence)
    val isUnknown: Boolean get() = level == "UNKNOWN"
}

data class SrJob(
    val id: String,
    val state: String,
    val verdict: SrVerdict?,
    val results: List<SrResult>,
) {
    val done: Boolean get() = state == "done"
}

object SupRevoltWire {

    const val CERTAIN = 256
    /** Below this a verdict is "unconfirmed" (Ever's execute floor). */
    const val FLOOR = 128
    const val DEFAULT_WAIT = 20

    // ---------------- addresses ----------------

    enum class UrlKind { HTTPS, LOCAL_HTTP, BAD }

    data class UrlCheck(val kind: UrlKind, val url: String, val reason: String? = null)

    /**
     * Cleans what the person typed: trims, adds https:// when no scheme is given, drops a trailing
     * slash. HTTPS is required, except plain http:// to this phone or a private home network address
     * (127.*, 10.*, 192.168.*, 172.16-31.*, localhost), which the app allows after a warning.
     */
    fun checkUrl(raw: String): UrlCheck {
        var u = raw.trim()
        if (u.isEmpty()) return UrlCheck(UrlKind.BAD, u, "Type the server's address.")
        if (!u.contains("://")) u = "https://$u"
        while (u.endsWith("/")) u = u.dropLast(1)
        val m = Regex("^(https?)://([^/:?#@\\s]+)(?::(\\d{1,5}))?(/[^?#\\s]*)?$", RegexOption.IGNORE_CASE).matchEntire(u)
            ?: return UrlCheck(UrlKind.BAD, u, "That doesn't look like a web address.")
        val scheme = m.groupValues[1].lowercase(Locale.ROOT)
        val host = m.groupValues[2].lowercase(Locale.ROOT)
        val port = m.groupValues[3]
        if (port.isNotEmpty() && port.toInt() !in 1..65535) return UrlCheck(UrlKind.BAD, u, "The port number is out of range.")
        val clean = "$scheme://$host" + (if (port.isNotEmpty()) ":$port" else "") + m.groupValues[4]
        if (scheme == "https") return UrlCheck(UrlKind.HTTPS, clean)
        return if (isPrivateHost(host)) UrlCheck(UrlKind.LOCAL_HTTP, clean)
        else UrlCheck(UrlKind.BAD, clean, "Use https://. Plain http:// is only allowed for this phone or your home network.")
    }

    /** localhost or a private / loopback IPv4 literal (never a name, which could point anywhere). */
    fun isPrivateHost(host: String): Boolean {
        if (host == "localhost") return true
        val parts = host.split('.')
        if (parts.size != 4) return false
        val n = parts.map { p -> if (p.isEmpty() || p.length > 3 || !p.all(Char::isDigit)) return false else p.toInt() }
        if (n.any { it > 255 }) return false
        return n[0] == 127 || n[0] == 10 || (n[0] == 192 && n[1] == 168) || (n[0] == 172 && n[1] in 16..31)
    }

    /** host and port of a checked URL, and the path prefix it carries ("" when none). */
    fun hostPort(url: String): Triple<String, Int, String> {
        val m = Regex("^(https?)://([^/:]+)(?::(\\d+))?(/.*)?$").matchEntire(url) ?: throw SupRevoltException("Bad server address.")
        val port = m.groupValues[3].toIntOrNull() ?: if (m.groupValues[1] == "https") 443 else 80
        return Triple(m.groupValues[2], port, m.groupValues[4])
    }

    // ---------------- confidence ----------------

    /** 218/256 shown as 85%, rounded half up: both from the same integer. */
    fun percent(conf: Int): Int = (conf.coerceIn(0, CERTAIN) * 100 + 128) / 256

    fun confText(conf: Int?, unknown: Boolean = false): String =
        if (conf == null || unknown) "unknown" else "${conf.coerceIn(0, CERTAIN)}/256"

    /** "CAUTION_UNCONFIRMED" → "Caution, unconfirmed". */
    fun levelLabel(level: String): String = when (level) {
        "CLEAR" -> "Clear"
        "CAUTION" -> "Caution"
        "DANGER" -> "Danger"
        "CLEAR_UNCONFIRMED" -> "Clear, unconfirmed"
        "CAUTION_UNCONFIRMED" -> "Caution, unconfirmed"
        "DANGER_UNCONFIRMED" -> "Danger, unconfirmed"
        "UNKNOWN" -> "Unknown"
        else -> level.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

    enum class VerdictTone { GOOD, WARN, BAD, UNKNOWN }

    fun tone(level: String): VerdictTone = when (level.substringBefore("_")) {
        "CLEAR" -> VerdictTone.GOOD
        "CAUTION" -> VerdictTone.WARN
        "DANGER" -> VerdictTone.BAD
        else -> VerdictTone.UNKNOWN
    }

    // ---------------- requests ----------------

    /** JSON body for POST /v1/scans with typed or pasted text. */
    fun textBody(text: String, scans: List<String>, wait: Int = DEFAULT_WAIT, filename: String? = null): String =
        Json.write(linkedMapOf(
            "text" to text,
            "scans" to scansValue(scans),
            "wait" to wait.coerceIn(0, 20),
        ).apply { if (!filename.isNullOrBlank()) put("filename", filename) })

    /** Query for a raw upload: ?scans=all&wait=20 (ids are [a-z.]+, nothing to escape). */
    fun uploadQuery(scans: List<String>, wait: Int = DEFAULT_WAIT): String {
        val s = scansValue(scans).let { if (it is String) it else (it as List<*>).joinToString(",") }
        return "?scans=$s&wait=${wait.coerceIn(0, 20)}"
    }

    private fun scansValue(scans: List<String>): Any {
        val clean = scans.filter { Regex("^[a-z0-9_.]+$").matches(it) }.distinct()
        return if (clean.isEmpty() || "all" in clean) "all" else clean
    }

    /** A file name safe to send as X-Filename: the last path part, printable ASCII, 120 chars. */
    fun safeFilename(name: String?): String =
        (name ?: "").replace('\\', '/').substringAfterLast('/').filter { it in ' '..'~' }.trim().take(120)

    // ---------------- replies ----------------

    private fun obj(body: String): Map<*, *> = try {
        Json.parse(body) as? Map<*, *> ?: throw SupRevoltException("The server sent something unexpected.")
    } catch (e: AiException) {
        throw SupRevoltException("The server sent something unexpected.")
    }

    private fun Map<*, *>.str(k: String) = this[k] as? String ?: ""
    private fun Map<*, *>.int(k: String): Int? = (this[k] as? Number)?.toInt()
    private fun Map<*, *>.bool(k: String) = this[k] as? Boolean ?: false

    fun parseHealth(body: String): SrHealth {
        val m = obj(body)
        return SrHealth(m.bool("ok"), m.str("version"), m.str("catalog_version"), m.int("queue_depth") ?: 0,
            m.str("determinism").ifEmpty { "ok" })
    }

    fun parseCatalog(body: String): List<SrScan> {
        val list = obj(body)["scans"] as? List<*> ?: throw SupRevoltException("The server sent no scan list.")
        return list.mapNotNull { it as? Map<*, *> }.map { m ->
            SrScan(m.str("id"), m.str("title").ifEmpty { m.str("id") }, m.str("version"), m.str("accepts"),
                m.bool("deterministic"), m.bool("needs_key"), m.bool("key_present"), m["enabled"] as? Boolean ?: true)
        }.filter { it.id.isNotEmpty() }
    }

    /** A job reply: either the 202 stub ({job, state, poll}) or the full result. */
    fun parseJob(body: String): SrJob {
        val m = obj(body)
        val id = m.str("job")
        if (!Regex("^[0-9a-f]{24}$").matches(id)) throw SupRevoltException("The server sent no job id.")
        val v = m["verdict"] as? Map<*, *>
        val verdict = v?.let {
            SrVerdict(it.str("level").ifEmpty { "UNKNOWN" }, it.int("risk"), it.int("confidence") ?: 0,
                (it["reasons"] as? List<*>)?.mapNotNull { r -> r as? String } ?: emptyList())
        }
        val results = (m["results"] as? List<*>)?.mapNotNull { it as? Map<*, *> }?.map { r ->
            val scan = r.str("scan")
            SrResult(
                scan = scan,
                title = r.str("title").ifEmpty { scan },
                state = r.str("state"),
                risk = r.int("value"),
                confidence = r.int("confidence"),
                deterministic = r.bool("deterministic"),
                cached = r.bool("cached"),
                ms = (r["ms"] as? Number)?.toLong() ?: 0L,
                note = r.str("note"),
                fromPhotoText = r["parent"] is String,
                details = details(scan, r["output"] as? Map<*, *> ?: emptyMap<String, Any?>()),
            )
        } ?: emptyList()
        return SrJob(id, m.str("state"), verdict, results)
    }

    /** What the server said went wrong, in words for people. Never repeats the token. */
    fun errorMessage(code: Int, body: String): String {
        val said = runCatching { (Json.parse(body) as? Map<*, *>)?.get("error") as? String }.getOrNull()
        return when (code) {
            401 -> "The server didn't accept the token. Check it in Settings › SupRevolt."
            404 -> "The server doesn't know that address (is it a SupRevolt server?)."
            413 -> "That's too big for the server" + (said?.let { ": $it." } ?: ".")
            429 -> "The server is busy. Try again in a minute."
            in 500..599 -> "The server had a problem ($code). Try again."
            else -> "The server said: " + (said ?: "error $code") + "."
        }
    }

    // ---------------- result details ----------------

    /** A few readable lines from one scan's output, for the tap-to-expand row. */
    fun details(scan: String, o: Map<*, *>): List<String> {
        fun list(k: String) = (o[k] as? List<*>)?.mapNotNull { it as? Map<*, *> } ?: emptyList()
        fun s(m: Map<*, *>, k: String) = m[k]?.toString() ?: ""
        val out = mutableListOf<String>()
        when (scan) {
            "fineprint.lexicon" -> list("hits").take(12).forEach { h -> out += "${s(h, "subtype").replace('_', ' ')} (${s(h, "severity")}): \"${s(h, "phrase")}\"" }
            "fineprint.ai" -> {
                list("flags").take(12).forEach { f ->
                    out += "${s(f, "type").ifEmpty { s(f, "category") }.replace('_', ' ')} (${s(f, "severity")}" +
                        (if (f["corroborated"] == true) ", confirmed offline" else "") + "): \"${s(f, "phrase")}\""
                }
                (o["synthesis"] as? String)?.takeIf { it.isNotBlank() }?.let { out += it }
            }
            "jargonary.ai" -> {
                (o["key_takeaway"] as? String)?.takeIf { it.isNotBlank() }?.let { out += it }
                (o["tone"] as? String)?.takeIf { it.isNotBlank() }?.let { out += "Tone: $it" + if (o["sarcasm"] == true) ", sarcastic" else "" }
                (o["intent"] as? String)?.takeIf { it.isNotBlank() }?.let { out += "Intent: $it" }
                list("jargon_glossary").take(10).forEach { g -> out += "${s(g, "term")}: ${s(g, "plain_meaning")}" }
            }
            "image.read" -> {
                (o["text"] as? String)?.takeIf { it.isNotBlank() }?.let { out += it.take(600) }
                (o["translation"] as? String)?.takeIf { it.isNotBlank() && it != o["text"] }?.let { out += "English: ${it.take(600)}" }
            }
            "refix.gradle" -> list("hits").forEach { h -> out += "${s(h, "signature_id")}: ${s(h, "fixture_text")}" }
            "code.edapt" -> {
                list("fixes").take(12).forEach { f -> out += "line ${s(f, "line")}: ${s(f, "what")} → ${s(f, "became")}" }
                (o["bracket_problems"] as? List<*>)?.take(12)?.forEach { out += it.toString() }
            }
            "ever.heal" -> {
                list("corrections").take(12).forEach { c -> out += "line ${s(c, "line")}: ${s(c, "what")} → ${s(c, "became")}" }
                (o["refusal"] as? String)?.let { out += "Refused: $it" }
            }
            "ever.run" -> {
                list("shown").take(20).forEach { v -> out += s(v, "line") }
                (o["errors"] as? List<*>)?.take(5)?.forEach { out += "error: $it" }
            }
            "accord.check" -> (o["explanation"] as? String)?.let { out += it.lines().take(12) }
            "dna.sequence" -> {
                out += "Length ${s(o, "length")}, ${s(o, "codon_count")} codons, GC ${s(o, "gc_content")}, " +
                    if (o["valid"] == true) "valid" else "invalid"
                list("codons").take(8).let { c -> if (c.isNotEmpty()) out += c.joinToString(" ") { s(it, "bases") } }
            }
            "dna.binary" -> out += "${s(o, "dna_length")} bases" + (if (o["round_trip"] == true) ", decodes back exactly" else "") +
                ((o["dna"] as? String)?.take(48)?.let { ": $it…" } ?: "")
            "blob.fingerprint" -> out += listOfNotNull(
                o["type"]?.toString(), o["size"]?.let { "$it bytes" }, o["lines"]?.let { "$it lines" }, o["words"]?.let { "$it words" },
                (o["sha256"] as? String)?.take(16)?.let { "sha256 $it…" },
            ).joinToString(", ")
        }
        return out.filter { it.isNotBlank() }
    }

    // ---------------- speech and report ----------------

    private val WORDS = listOf("No", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten")

    /** What the voice command says: "Caution. Three flags. Confidence 182 of 256." */
    fun spoken(job: SrJob): String {
        val v = job.verdict ?: return "The scan didn't finish in time. Open SupRevolt to see it."
        if (v.isUnknown) {
            val r = v.reasons.firstOrNull() ?: ""
            return when {
                r.contains("no AI key") || r.contains("_KEY") -> "Unknown: no AI key on the server."
                r.isNotEmpty() -> "Unknown: ${r.substringBefore(':')} could not answer."
                else -> "Unknown."
            }
        }
        val flags = job.results.count { !it.skipped && (it.risk ?: 0) > 0 }
        val flagText = if (flags < WORDS.size) "${WORDS[flags]} flag${if (flags == 1) "" else "s"}" else "$flags flags"
        return "${levelLabel(v.level)}. $flagText. Confidence ${v.confidence} of 256."
    }

    /** A plain-text summary to share: verdict and each scan. No server address and no token. */
    fun report(job: SrJob): String = buildString {
        val v = job.verdict
        append("SupRevolt scan\n")
        if (v != null) {
            append("Verdict: ${levelLabel(v.level)}")
            v.risk?.let { append(", risk $it/100") }
            append(", confidence ${confText(v.confidence, v.isUnknown)}")
            if (!v.isUnknown) append(" (${v.percent}%)")
            append('\n')
            v.reasons.forEach { append("  - ").append(it).append('\n') }
        }
        append('\n')
        job.results.filter { !it.skipped }.forEach { r ->
            append(r.title).append(if (r.fromPhotoText) " (photo text)" else "").append(": ")
            append(if (r.unknown) "unknown" else "risk ${r.risk ?: 0}/100, ${confText(r.confidence)}")
            if (r.note.isNotBlank()) append(" · ").append(r.note)
            append('\n')
        }
    }.trim()

    /** "••••abcd": enough to recognise a token, never enough to use it. */
    fun maskToken(token: String): String = if (token.length <= 8) "••••" else "••••" + token.takeLast(4)
}
