package com.zdycomp.p3companion

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * 3.6 Conversation translate: the pure parts (no android.*), unit tested on the JVM.
 * - [TranslationEntry]: one heard phrase and its translation.
 * - [TranslationStore]: one app-private JSON-lines file per session, listed by date.
 * - [TwoWay]: which language a phrase is translated into, and which one to listen in next.
 * - [ConversationWire]: the prompt that asks for the detected language plus the translation, and its reader.
 */

/** One phrase: when it was heard, what was said, its translation (null when none) and the languages. */
data class TranslationEntry(
    val at: Long,
    val original: String,
    val translation: String?,
    /** The language the phrase was in: the AI's answer, or the language listened in. */
    val from: String,
    val to: String,
    /** Why there is no translation ("No AI key", an error), or null. */
    val note: String? = null,
) {
    fun toJson(): String = Json.write(linkedMapOf(
        "at" to at, "original" to original, "translation" to translation, "from" to from, "to" to to, "note" to note,
    ))

    companion object {
        fun fromJson(line: String): TranslationEntry? = runCatching {
            val m = Json.parse(line) as Map<*, *>
            TranslationEntry(
                at = (m["at"] as Number).toLong(),
                original = m["original"] as String,
                translation = m["translation"] as String?,
                from = m["from"] as? String ?: "",
                to = m["to"] as? String ?: "",
                note = m["note"] as String?,
            )
        }.getOrNull()
    }
}

/**
 * Sessions saved as `conv-<yyyyMMdd-HHmmss>-<n>.jsonl` in [dir] (app-private storage), one entry a line,
 * appended as each phrase is translated. Text passes through [redact] first, so an API key that ends up
 * in a phrase or an error is never written. Call from one thread at a time (the app uses its io thread).
 */
class TranslationStore(private val dir: File, private val secrets: () -> List<String> = { emptyList() }) {

    data class Session(val id: String, val startedAt: Long, val entries: Int, val bytes: Long)

    private fun file(id: String): File {
        require(ID.matches(id)) { "bad session id" }
        return File(dir, "$id.jsonl")
    }

    /** A new, empty session starting at [at] (ms since 1970). */
    fun newSession(at: Long): String {
        dir.mkdirs()
        val base = "conv-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(at))
        var n = 1
        while (File(dir, "$base-$n.jsonl").exists()) n++
        val id = "$base-$n"
        File(dir, "$id.jsonl").createNewFile()
        return id
    }

    fun append(id: String, e: TranslationEntry) {
        val clean = e.copy(
            original = redact(e.original),
            translation = e.translation?.let(::redact),
            note = e.note?.let(::redact),
        )
        file(id).appendText(clean.toJson() + "\n", Charsets.UTF_8)
    }

    /** The entries in the order they were heard. Lines that can't be read are skipped. */
    fun read(id: String): List<TranslationEntry> {
        val f = file(id)
        if (!f.exists()) return emptyList()
        return f.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.mapNotNull { TranslationEntry.fromJson(it) }
    }

    /** Newest first. Empty sessions are listed too (a session that is still listening). */
    fun list(): List<Session> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") && ID.matches(f.name.removeSuffix(".jsonl")) } ?: return emptyList()
        return files.map { f ->
            val id = f.name.removeSuffix(".jsonl")
            Session(id, startedAt(id) ?: f.lastModified(), f.readLines(Charsets.UTF_8).count { it.isNotBlank() }, f.length())
        }.sortedWith(compareByDescending<Session> { it.startedAt }.thenByDescending { it.id })
    }

    fun delete(id: String): Boolean = file(id).delete()

    /** Deletes every session; returns how many. */
    fun clearAll(): Int = (dir.listFiles { f -> f.name.endsWith(".jsonl") } ?: emptyArray()).count { it.delete() }

    /** Plain text for sharing: a title line, then time, original and translation for each entry. */
    fun exportText(id: String, title: String = "Conversation"): String = buildString {
        val entries = read(id)
        val time = SimpleDateFormat("HH:mm:ss", Locale.US)
        appendLine("$title, " + (startedAt(id)?.let { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(it)) } ?: id))
        entries.forEach { e ->
            appendLine()
            appendLine("[${time.format(Date(e.at))}] ${e.from}: ${e.original}")
            when {
                e.translation != null -> appendLine("  ${e.to}: ${e.translation}")
                e.note != null -> appendLine("  (${e.note})")
            }
        }
    }.trimEnd() + "\n"

    /** Replaces every known secret and anything shaped like an API key with "[hidden]". */
    fun redact(text: String): String {
        var s = text
        secrets().filter { it.length >= 8 }.forEach { s = s.replace(it, "[hidden]") }
        KEY_SHAPES.forEach { rx -> s = rx.replace(s, "[hidden]") }
        return s
    }

    companion object {
        val ID = Regex("^conv-\\d{8}-\\d{6}-\\d{1,4}$")

        /** Shapes of the AI keys the app knows (Gemini, Anthropic, OpenAI-style) and bearer tokens. */
        val KEY_SHAPES = listOf(
            Regex("AIza[0-9A-Za-z_\\-]{20,}"),
            Regex("sk-ant-[0-9A-Za-z_\\-]{10,}"),
            Regex("sk-[0-9A-Za-z_\\-]{20,}"),
            Regex("(?i)bearer\\s+[0-9A-Za-z._\\-]{16,}"),
        )

        fun startedAt(id: String): Long? = runCatching {
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).parse(id.substring(5, 20))?.time
        }.getOrNull()
    }
}

/**
 * Which way a phrase goes. One-way: always into [to]. Two-way between A ([from]) and B ([to]): A goes
 * into B and B into A, by the language the AI detected (or the one listened in when it couldn't tell);
 * anything else goes into B. The next phrase is listened for in the other language.
 */
object TwoWay {

    data class Way(val from: String, val to: String)

    fun same(x: String?, y: String?): Boolean = known(x)?.let { it == known(y) } ?: false

    /** The language a detected name stands for, or null when it isn't one the app knows. */
    fun known(name: String?): String? = name?.trim()?.let { n -> Languages.all.firstOrNull { it.name.equals(n, ignoreCase = true) }?.name }

    fun way(detected: String?, listenedIn: String, from: String, to: String, twoWay: Boolean): Way {
        val src = known(detected) ?: listenedIn
        if (!twoWay) return Way(src, to)
        return when {
            same(src, to) -> Way(to, from)
            same(src, from) -> Way(from, to)
            else -> Way(src, to)
        }
    }

    /** The language to listen in after a phrase in [heardIn]: the other one in two-way, [from] otherwise. */
    fun nextListen(heardIn: String, from: String, to: String, twoWay: Boolean): String = when {
        !twoWay -> from
        same(heardIn, from) -> to
        same(heardIn, to) -> from
        else -> from
    }

    /** Two-way: nothing understood in [listenedIn], so the other person may be talking: try the other language. */
    fun afterSilence(listenedIn: String, from: String, to: String, twoWay: Boolean): String =
        if (!twoWay) from else if (same(listenedIn, from)) to else from
}

/** The conversation prompt and its reply format: a "[Language]" line, then the translation. */
object ConversationWire {

    /**
     * [from] null means auto-detect (one-way into [to]). Two-way translates between [from] and [to]
     * in whichever direction the input needs.
     */
    fun prompt(from: String?, to: String, twoWay: Boolean): String {
        val b = Languages.byName(to).name
        val rule = when {
            twoWay && from != null -> {
                val a = Languages.byName(from).name
                "If the input is in $a, translate it into $b. If it is in $b, translate it into $a. " +
                    "If it is in any other language, translate it into $b."
            }
            from == null -> "Work out which language the input is in and translate it into $b. If it is already in $b, repeat it in $b."
            else -> {
                val a = Languages.byName(from).name
                "The input was recognised as $a; work out its real language and translate it into $b. If it is already in $b, repeat it in $b."
            }
        }
        return "You are a live interpreter for a spoken conversation. $rule " +
            "Reply in exactly this form: the first line is the input's language name in English inside square brackets, " +
            "for example [Spanish]; the next line is only the translation, natural and spoken, with no notes, quotes, " +
            "romanisation or explanations."
    }

    data class Reply(val language: String?, val text: String)

    private val BRACKET = Regex("^\\s*\\[([^\\]\\n]{2,40})]\\s*(.*)$", RegexOption.DOT_MATCHES_ALL)
    private val LABEL = Regex("^\\s*(?:language|lang|detected)\\s*:\\s*([^\\n]{2,40})\\n(.*)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /** Reads "[Spanish]\nHola" (or "Language: Spanish" on the first line). Without either, all of it is the translation. */
    fun parse(raw: String): Reply {
        val t = raw.trim()
        val m = BRACKET.matchEntire(t) ?: LABEL.matchEntire(t)
        if (m != null) {
            val lang = m.groupValues[1].trim()
            val body = m.groupValues[2].trim().removeSurrounding("\"").trim()
            if (body.isNotEmpty()) return Reply(TwoWay.known(lang) ?: lang, body)
        }
        return Reply(null, t)
    }
}
