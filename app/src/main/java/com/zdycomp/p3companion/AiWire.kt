package com.zdycomp.p3companion

import java.util.Base64
import java.util.Locale

/*
 * Everything about talking to an AI service that doesn't need Android: request bodies, response
 * parsing, error wording, system prompts and the language table. Kept free of android.* and
 * org.json so plain JVM unit tests exercise exactly the code the app runs.
 */

enum class AiProvider(val label: String, val defaultModel: String) {
    GEMINI("Google Gemini", "gemini-2.5-flash"),
    CLAUDE("Anthropic Claude", "claude-sonnet-5-5"),
}

/** One message in a conversation. [fromUser] false means the AI said it. */
data class AiTurn(val fromUser: Boolean, val text: String)

/** A finished HTTP request description, ready for any HTTP client. */
data class AiHttpRequest(val url: String, val headers: List<Pair<String, String>>, val body: String)

/** Thrown with a message that can be shown to the user as is. */
class AiException(message: String) : Exception(message)

/** A spoken or written language: name for people, BCP 47 tag for speech engines. */
data class Language(val name: String, val tag: String)

object Languages {
    val all: List<Language> = listOf(
        Language("English", "en-US"),
        Language("Spanish", "es-ES"),
        Language("French", "fr-FR"),
        Language("German", "de-DE"),
        Language("Italian", "it-IT"),
        Language("Portuguese", "pt-BR"),
        Language("Dutch", "nl-NL"),
        Language("Polish", "pl-PL"),
        Language("Russian", "ru-RU"),
        Language("Ukrainian", "uk-UA"),
        Language("Turkish", "tr-TR"),
        Language("Arabic", "ar-SA"),
        Language("Hebrew", "he-IL"),
        Language("Hindi", "hi-IN"),
        Language("Bengali", "bn-IN"),
        Language("Chinese (Mandarin)", "zh-CN"),
        Language("Cantonese", "zh-HK"),
        Language("Japanese", "ja-JP"),
        Language("Korean", "ko-KR"),
        Language("Vietnamese", "vi-VN"),
        Language("Thai", "th-TH"),
        Language("Indonesian", "id-ID"),
        Language("Filipino", "fil-PH"),
        Language("Greek", "el-GR"),
        Language("Swedish", "sv-SE"),
    )

    val names: List<String> get() = all.map { it.name }

    /** Finds a language by its name (any case), falling back to English. */
    fun byName(name: String?): Language =
        all.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) } ?: all[0]
}

object AiWire {

    const val GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta/models/"
    const val CLAUDE_URL = "https://api.anthropic.com/v1/messages"
    const val CLAUDE_VERSION = "2023-06-01"
    private const val CLAUDE_FALLBACK_BETA = "server-side-fallback-2026-07-01"

    /** How many earlier messages travel with a voice question, so follow-ups make sense. */
    const val HISTORY_LIMIT = 12

    // ---------------- prompts ----------------

    /**
     * System prompt for chat and photo questions. The reply-language rule is always present, so
     * answers never drift into Chinese (or any other language) because of the question, earlier
     * messages or text inside a photo.
     */
    fun systemPrompt(replyLanguage: String, spoken: Boolean): String {
        val lang = Languages.byName(replyLanguage).name
        return buildString {
            append("You are the helpful assistant built into the user's camera glasses (P3 Companion app). ")
            append("Be accurate, friendly and practical. If you're not sure, say so.\n")
            append(languageRule(lang))
            if (spoken) {
                append("\nYour answer will be read aloud through the glasses, so keep it short: ")
                append("one to three sentences of plain text, with no lists, headings, markdown, emoji or links.")
            } else {
                append("\nKeep answers concise and easy to read on a phone screen. Use plain text with short paragraphs.")
            }
        }
    }

    /** The rule itself, shared by every prompt. */
    fun languageRule(lang: String): String =
        "LANGUAGE RULE: Always write your whole answer in $lang only. Never answer in Chinese or any other " +
            "language, even if the question, earlier messages, or text in a photo are in another language. " +
            "Quote foreign text only when asked to read it, and always explain it in $lang."

    /** System prompt for one live-translation turn. */
    fun translationPrompt(from: String, to: String): String {
        val a = Languages.byName(from).name
        val b = Languages.byName(to).name
        return "You are a live interpreter. The user's message is speech recognised in $a. " +
            "Translate it into natural, spoken $b. Output only the $b translation: no notes, quotes, " +
            "romanisation or explanations, and never use any language other than $b. " +
            "If the input is already in $b, repeat it in $b."
    }

    /** The preset photo questions, worded for the chosen reply language. */
    fun photoPreset(index: Int, replyLanguage: String): String {
        require(index in PRESET_QUESTIONS.indices) { "no preset $index" }
        return fillLanguage(PRESET_QUESTIONS[index], replyLanguage)
    }

    val photoPresetLabels = listOf(
        "Describe what I see",
        "Identify this plant or animal",
        "Food and calories estimate",
        "Read and translate the text",
    )

    /** "{language}" in a saved quick question becomes the reply language. */
    const val LANGUAGE_TOKEN = "{language}"

    fun fillLanguage(template: String, replyLanguage: String): String =
        template.replace(LANGUAGE_TOKEN, Languages.byName(replyLanguage).name)

    /** The built-in quick photo questions as (label, template) pairs, for the editable list. */
    fun photoPresetTemplates(): List<Pair<String, String>> = photoPresetLabels.zip(PRESET_QUESTIONS)

    private val PRESET_QUESTIONS = listOf(
        "Describe what I'm looking at in this photo, briefly and clearly. Mention anything important or unusual.",
        "Identify the plant or animal in this photo. Give its common name, its scientific name, how sure you are, " +
            "and one or two useful facts, such as whether it is safe to touch or eat.",
        "Identify the food in this photo and estimate its calories. List each item with the portion size you assumed " +
            "and its estimated calories, then the total. Make clear these are estimates.",
        "Read all the text in this photo exactly as written, then translate it into $LANGUAGE_TOKEN. " +
            "If it is already in $LANGUAGE_TOKEN, just give the text.",
    )

    // ---------------- speech ----------------

    /** Turns a written answer into text that sounds right when read aloud (no markdown symbols). */
    fun speakable(text: String): String =
        text.lines().joinToString("\n") { line ->
            line.trim()
                .replace(Regex("^#{1,6}\\s*"), "")
                .replace(Regex("^[-*•]\\s+"), "")
                .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
                .replace(Regex("[*_`#>|]+"), "")
                .replace(Regex("\\s{2,}"), " ")
        }.replace(Regex("\n{2,}"), "\n").trim()

    /** Splits text into pieces a speech engine accepts, breaking at sentence ends where it can. */
    fun speechChunks(text: String, max: Int): List<String> {
        val limit = max.coerceAtLeast(20)
        val out = mutableListOf<String>()
        var rest = text.trim()
        while (rest.length > limit) {
            val window = rest.substring(0, limit)
            val cut = listOf(". ", "! ", "? ", "\n", "; ", ", ", " ")
                .map { window.lastIndexOf(it).let { i -> if (i > limit / 3) i + it.length else -1 } }
                .firstOrNull { it > 0 } ?: limit
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out.filter { it.isNotEmpty() }
    }

    // ---------------- requests ----------------

    /** Removes "models/" and anything that can't be in a model name. */
    fun cleanModel(raw: String, provider: AiProvider): String {
        val m = raw.trim().removePrefix("models/").filter { it.isLetterOrDigit() || it in "-._:" }
        return m.ifBlank { provider.defaultModel }
    }

    /** Makes a valid conversation: starts with the user, roles alternate, no blank turns. */
    fun normalize(turns: List<AiTurn>): List<AiTurn> {
        val out = mutableListOf<AiTurn>()
        for (t in turns) {
            val text = t.text.trim()
            if (text.isEmpty()) continue
            if (out.isEmpty() && !t.fromUser) continue
            if (out.isNotEmpty() && out.last().fromUser == t.fromUser) {
                out[out.size - 1] = AiTurn(t.fromUser, out.last().text + "\n" + text)
            } else out += AiTurn(t.fromUser, text)
        }
        if (out.isEmpty() || !out.last().fromUser) throw AiException("There's no question to send.")
        return out
    }

    fun build(
        provider: AiProvider,
        apiKey: String,
        model: String,
        system: String,
        turns: List<AiTurn>,
        imageJpeg: ByteArray? = null,
    ): AiHttpRequest {
        val key = apiKey.trim()
        if (key.isEmpty()) throw AiException("Add your ${provider.label} key first.")
        val m = cleanModel(model, provider)
        return when (provider) {
            AiProvider.GEMINI -> AiHttpRequest(
                GEMINI_BASE + m + ":generateContent",
                listOf("x-goog-api-key" to key, "Content-Type" to "application/json"),
                geminiBody(system, turns, imageJpeg),
            )
            AiProvider.CLAUDE -> AiHttpRequest(
                CLAUDE_URL,
                buildList {
                    add("x-api-key" to key)
                    add("anthropic-version" to CLAUDE_VERSION)
                    add("Content-Type" to "application/json")
                    if (claudeFallbackSupported(m)) add("anthropic-beta" to CLAUDE_FALLBACK_BETA)
                },
                claudeBody(m, system, turns, imageJpeg),
            )
        }
    }

    fun geminiBody(system: String, turns: List<AiTurn>, imageJpeg: ByteArray?): String {
        val conv = normalize(turns)
        val contents = conv.mapIndexed { i, t ->
            val parts = mutableListOf<Any?>()
            if (imageJpeg != null && i == conv.lastIndex) {
                parts += mapOf("inline_data" to mapOf("mime_type" to "image/jpeg", "data" to b64(imageJpeg)))
            }
            parts += mapOf("text" to t.text)
            mapOf("role" to (if (t.fromUser) "user" else "model"), "parts" to parts)
        }
        return Json.write(
            mapOf(
                "system_instruction" to mapOf("parts" to listOf(mapOf("text" to system))),
                "contents" to contents,
            )
        )
    }

    fun claudeBody(model: String, system: String, turns: List<AiTurn>, imageJpeg: ByteArray?): String {
        val conv = normalize(turns)
        val messages = conv.mapIndexed { i, t ->
            val content: Any = if (imageJpeg != null && i == conv.lastIndex) {
                listOf(
                    mapOf(
                        "type" to "image",
                        "source" to mapOf("type" to "base64", "media_type" to "image/jpeg", "data" to b64(imageJpeg)),
                    ),
                    mapOf("type" to "text", "text" to t.text),
                )
            } else t.text
            mapOf("role" to (if (t.fromUser) "user" else "assistant"), "content" to content)
        }
        val body = linkedMapOf<String, Any?>(
            "model" to model,
            "max_tokens" to 4096,
            "system" to system,
            "messages" to messages,
        )
        if (claudeFallbackSupported(model)) body["fallbacks"] = "default"
        return Json.write(body)
    }

    /** Models that accept server-side fallback when a safety check declines a request. */
    fun claudeFallbackSupported(model: String): Boolean =
        model in setOf("claude-sonnet-5-5", "claude-opus-5-5", "claude-opus-5", "claude-fable-5-1", "claude-fable-5-2")

    private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

    // ---------------- responses ----------------

    /** The answer text, or an [AiException] that explains in plain words why there is none. */
    fun parseGemini(body: String): String {
        val root = parseObject(body)
        val candidates = root["candidates"] as? List<*>
        val first = candidates?.firstOrNull() as? Map<*, *>
        if (first == null) {
            val reason = (root["promptFeedback"] as? Map<*, *>)?.get("blockReason") as? String
            throw AiException(
                if (reason != null) "Gemini wouldn't answer that (${reason.lowercase(Locale.US)}). Try asking another way."
                else "Gemini sent back an empty answer. Try again."
            )
        }
        val parts = (first["content"] as? Map<*, *>)?.get("parts") as? List<*>
        val text = parts.orEmpty().mapNotNull { p ->
            val m = p as? Map<*, *> ?: return@mapNotNull null
            if (m["thought"] == true) null else m["text"] as? String
        }.joinToString("").trim()
        if (text.isNotEmpty()) return text
        val finish = first["finishReason"] as? String
        throw AiException(
            when (finish) {
                "SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "IMAGE_SAFETY" ->
                    "Gemini wouldn't answer that for safety reasons. Try asking another way."
                "RECITATION" -> "Gemini stopped because the answer would copy protected text."
                "MAX_TOKENS" -> "Gemini ran out of room before answering. Try a shorter question."
                else -> "Gemini sent back an empty answer. Try again."
            }
        )
    }

    fun parseClaude(body: String): String {
        val root = parseObject(body)
        if (root["stop_reason"] == "refusal") {
            throw AiException("Claude declined to answer that. Try asking another way.")
        }
        val content = root["content"] as? List<*>
        val text = content.orEmpty().mapNotNull { b ->
            val m = b as? Map<*, *> ?: return@mapNotNull null
            if (m["type"] == "text") m["text"] as? String else null
        }.joinToString("").trim()
        if (text.isEmpty()) {
            if (root["stop_reason"] == "max_tokens") throw AiException("Claude ran out of room before answering. Try a shorter question.")
            throw AiException("Claude sent back an empty answer. Try again.")
        }
        return text
    }

    fun parse(provider: AiProvider, body: String): String =
        when (provider) { AiProvider.GEMINI -> parseGemini(body); AiProvider.CLAUDE -> parseClaude(body) }

    /** Plain-English explanation of an HTTP error, never including the key. */
    fun errorMessage(provider: AiProvider, code: Int, body: String?, apiKey: String? = null): String {
        val detail = runCatching {
            val err = parseObject(body ?: "")["error"] as? Map<*, *>
            (err?.get("message") as? String)?.trim()
        }.getOrNull()
        val lower = detail?.lowercase(Locale.US) ?: ""
        val name = provider.label
        val friendly = when {
            lower.contains("api key not valid") || lower.contains("api_key_invalid") || code == 401 ->
                "$name didn't accept your key. Check it in Settings, or make a new one."
            code == 403 && provider == AiProvider.GEMINI && lower.contains("location") ->
                "Gemini isn't available in your country yet. Try Anthropic Claude instead."
            code == 403 -> "Your $name key isn't allowed to do this. Check the key's permissions."
            code == 402 || lower.contains("credit balance") || lower.contains("billing") ->
                "Your $name account needs credit or billing set up before it can answer."
            code == 429 || lower.contains("quota") || lower.contains("resource_exhausted") ->
                "You've hit your $name limit for now. Wait a minute and try again, or check your plan's quota."
            code == 404 -> "$name doesn't know the model name in Settings. Check it, or put back the default."
            code == 413 -> "That photo was too big to send. Try another one."
            code == 529 || code == 503 -> "$name is busy right now. Try again in a moment."
            code >= 500 -> "$name had a problem on its side (error $code). Try again in a moment."
            code == 400 -> "$name couldn't use that request" + (detail?.let { ": ${clip(it)}" } ?: ".")
            else -> "$name answered with error $code" + (detail?.let { ": ${clip(it)}" } ?: ".")
        }
        return redact(friendly, apiKey)
    }

    private fun clip(s: String) = if (s.length > 160) s.take(157) + "…" else s

    /** Makes sure a key can never appear in text shown on screen or in a log. */
    fun redact(text: String, apiKey: String?): String {
        val k = apiKey?.trim()
        return if (k.isNullOrEmpty() || k.length < 6) text else text.replace(k, maskKey(k))
    }

    /** Shows only the start and end of a secret, like "AIza…x9Q2". */
    fun maskKey(key: String): String {
        val k = key.trim()
        return if (k.length <= 10) "••••" else k.take(4) + "…" + k.takeLast(4)
    }

    private fun parseObject(body: String): Map<*, *> =
        Json.parse(body) as? Map<*, *> ?: throw AiException("The AI sent back something unexpected. Try again.")
}

/** A small, strict JSON reader and writer (maps, lists, strings, numbers, booleans, null). */
object Json {

    fun write(v: Any?): String = StringBuilder().also { write(v, it) }.toString()

    private fun write(v: Any?, sb: StringBuilder) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(v, sb)
            is Boolean -> sb.append(v)
            is Int, is Long -> sb.append(v)
            is Number -> {
                val d = v.toDouble()
                require(!d.isNaN() && !d.isInfinite()) { "bad number" }
                sb.append(if (d == Math.floor(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString())
            }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(k.toString(), sb)
                    sb.append(':')
                    write(value, sb)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(item, sb)
                }
                sb.append(']')
            }
            else -> quote(v.toString(), sb)
        }
    }

    private fun quote(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ' || c == ' ' || c == ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    /** Parses JSON text. Numbers come back as Long when whole, otherwise Double. */
    fun parse(text: String): Any? {
        val p = Reader(text)
        p.ws()
        val v = p.value(0)
        p.ws()
        if (p.i != text.length) throw AiException("The AI sent back something unexpected. Try again.")
        return v
    }

    private class Reader(val s: String) {
        var i = 0

        fun fail(): Nothing = throw AiException("The AI sent back something unexpected. Try again.")

        fun ws() { while (i < s.length && s[i] in " \t\r\n") i++ }

        fun value(depth: Int): Any? {
            if (depth > 64 || i >= s.length) fail()
            return when (s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail()
            i += word.length
            return v
        }

        fun obj(depth: Int): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++; ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') fail()
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') fail()
                i++; ws()
                m[k] = value(depth + 1)
                ws()
                if (i >= s.length) fail()
                when (s[i++]) { ',' -> continue; '}' -> return m; else -> fail() }
            }
        }

        fun arr(depth: Int): List<Any?> {
            val l = ArrayList<Any?>()
            i++; ws()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                ws()
                l += value(depth + 1)
                ws()
                if (i >= s.length) fail()
                when (s[i++]) { ',' -> continue; ']' -> return l; else -> fail() }
            }
        }

        fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail()
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) fail()
                        when (s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail()
                                sb.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: fail())
                                i += 4
                            }
                            else -> fail()
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun num(): Number {
            val start = i
            if (i < s.length && s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(start, i)
            if (t.isEmpty() || t == "-") fail()
            return t.toLongOrNull() ?: t.toDoubleOrNull() ?: fail()
        }
    }
}
