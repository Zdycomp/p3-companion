package com.zdycomp.p3companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 3.6 Conversation translate: the session store, two-way language choice and the reply format. */
class ConversationTest {

    private fun tempDir(): File = Files.createTempDirectory("p3-convo").toFile().apply { deleteOnExit() }

    private fun entry(at: Long, original: String, translation: String? = "t-$original", from: String = "English", to: String = "Spanish", note: String? = null) =
        TranslationEntry(at, original, translation, from, to, note)

    // ---------------- store ----------------

    @Test fun appendThenReadBackKeepsTheOrderHeard() {
        val store = TranslationStore(tempDir())
        val id = store.newSession(1_764_000_000_000L)
        val entries = (1..25).map { entry(1_764_000_000_000L + it * 1000, "phrase $it") }
        entries.forEach { store.append(id, it) }
        assertEquals(entries, store.read(id))
    }

    @Test fun entriesSurviveQuotesNewlinesUnicodeAndNulls() {
        val store = TranslationStore(tempDir())
        val id = store.newSession(0L)
        val tricky = listOf(
            entry(1, "He said \"hola\"\nthen left", "Dijo \"hola\"\ny se fue"),
            entry(2, "你好，世界 😀", "Hello, world", from = "Chinese (Mandarin)", to = "English"),
            entry(3, "nothing heard well", null, note = "Not translated: no AI key"),
            entry(4, "back\\slash and \t tab", "ok"),
        )
        tricky.forEach { store.append(id, it) }
        assertEquals(tricky, store.read(id))
        assertNull(store.read(id)[2].translation)
    }

    @Test fun listIsNewestFirstWithCountsAndDeleteRemovesOne() {
        val dir = tempDir()
        val store = TranslationStore(dir)
        val older = store.newSession(1_700_000_000_000L)
        val newer = store.newSession(1_760_000_000_000L)
        val sameSecond = store.newSession(1_760_000_000_000L)
        assertNotEquals(newer, sameSecond)
        store.append(older, entry(1, "a"))
        store.append(newer, entry(2, "b"))
        store.append(newer, entry(3, "c"))
        val list = store.list()
        assertEquals(listOf(sameSecond, newer, older), list.map { it.id })
        assertEquals(listOf(0, 2, 1), list.map { it.entries })
        assertEquals(1_700_000_000_000L, list.last().startedAt)

        assertTrue(store.delete(newer))
        assertEquals(listOf(sameSecond, older), store.list().map { it.id })
        assertTrue(store.read(newer).isEmpty())
        assertFalse(store.delete(newer))
    }

    @Test fun clearAllDeletesEverySessionAndNothingElse() {
        val dir = tempDir()
        val other = File(dir, "keep.txt").apply { writeText("not a session") }
        val store = TranslationStore(dir)
        repeat(3) { store.append(store.newSession(it * 1000L), entry(it.toLong(), "x$it")) }
        assertEquals(3, store.list().size)
        assertEquals(3, store.clearAll())
        assertTrue(store.list().isEmpty())
        assertTrue(other.exists())
    }

    @Test fun unreadableLinesAreSkippedAndBadIdsRefused() {
        val dir = tempDir()
        val store = TranslationStore(dir)
        val id = store.newSession(5_000L)
        store.append(id, entry(1, "one"))
        File(dir, "$id.jsonl").appendText("{broken\n\n")
        store.append(id, entry(2, "two"))
        assertEquals(listOf("one", "two"), store.read(id).map { it.original })
        assertTrue(runCatching { store.read("../../etc/passwd") }.isFailure)
        assertTrue(runCatching { store.delete("conv-x") }.isFailure)
    }

    @Test fun apiKeysAreNeverWritten() {
        val dir = tempDir()
        val key = "my-own-secret-key-123456"
        val store = TranslationStore(dir) { listOf(key) }
        val id = store.newSession(0L)
        store.append(id, entry(1, "the key is $key", "AIzaSyA1234567890abcdefghijklmnop leaked",
            note = "Error from sk-ant-api03-abcdefghijklmnop and Bearer abcdefghijklmnopqrstuvwxyz"))
        val raw = File(dir, "$id.jsonl").readText()
        assertFalse(raw.contains(key))
        assertFalse(raw.contains("AIzaSyA1234567890"))
        assertFalse(raw.contains("sk-ant-api03"))
        assertFalse(raw.contains("abcdefghijklmnopqrstuvwxyz"))
        assertTrue(raw.contains("[hidden]"))
        assertEquals("the key is [hidden]", store.read(id)[0].original)
    }

    @Test fun exportIsPlainTextInOrder() {
        val store = TranslationStore(tempDir())
        val id = store.newSession(0L)
        store.append(id, entry(1_000, "Good morning", "Buenos días"))
        store.append(id, entry(2_000, "Gracias", "Thank you", from = "Spanish", to = "English"))
        store.append(id, entry(3_000, "mumble", null, note = "Not translated: no AI key"))
        val text = store.exportText(id, "Talk")
        assertTrue(text.startsWith("Talk, "))
        val a = text.indexOf("English: Good morning")
        val b = text.indexOf("Spanish: Buenos días")
        val c = text.indexOf("Spanish: Gracias")
        val d = text.indexOf("(Not translated: no AI key)")
        assertTrue(text, a in 0 until b && b < c && c < d)
    }

    // ---------------- two-way ----------------

    @Test fun oneWayAlwaysTranslatesIntoTheTarget() {
        assertEquals(TwoWay.Way("English", "Spanish"), TwoWay.way(null, "English", "English", "Spanish", twoWay = false))
        assertEquals(TwoWay.Way("French", "Spanish"), TwoWay.way("French", "English", "English", "Spanish", twoWay = false))
        assertEquals("English", TwoWay.nextListen("English", "English", "Spanish", twoWay = false))
        assertEquals("English", TwoWay.afterSilence("Spanish", "English", "Spanish", twoWay = false))
    }

    @Test fun twoWayTranslatesEachLanguageIntoTheOther() {
        assertEquals(TwoWay.Way("English", "Spanish"), TwoWay.way("English", "English", "English", "Spanish", twoWay = true))
        assertEquals(TwoWay.Way("Spanish", "English"), TwoWay.way("spanish", "English", "English", "Spanish", twoWay = true))
        // The AI couldn't tell: the language listened in decides.
        assertEquals(TwoWay.Way("Spanish", "English"), TwoWay.way(null, "Spanish", "English", "Spanish", twoWay = true))
        assertEquals(TwoWay.Way("Spanish", "English"), TwoWay.way("Klingon", "Spanish", "English", "Spanish", twoWay = true))
        // A third known language goes into the second one.
        assertEquals(TwoWay.Way("French", "Spanish"), TwoWay.way("French", "English", "English", "Spanish", twoWay = true))
    }

    @Test fun twoWayListensForTheOtherPersonNext() {
        assertEquals("Spanish", TwoWay.nextListen("English", "English", "Spanish", twoWay = true))
        assertEquals("English", TwoWay.nextListen("Spanish", "English", "Spanish", twoWay = true))
        assertEquals("English", TwoWay.nextListen("French", "English", "Spanish", twoWay = true))
        // Nothing understood: maybe the other person is talking.
        assertEquals("Spanish", TwoWay.afterSilence("English", "English", "Spanish", twoWay = true))
        assertEquals("English", TwoWay.afterSilence("Spanish", "English", "Spanish", twoWay = true))
    }

    @Test fun sameAndKnownOnlyMatchRealLanguages() {
        assertTrue(TwoWay.same("english", "English"))
        assertFalse(TwoWay.same("Klingon", "English"))
        assertFalse(TwoWay.same(null, "English"))
        assertEquals("Chinese (Mandarin)", TwoWay.known(" chinese (mandarin) "))
        assertNull(TwoWay.known("Elvish"))
    }

    // ---------------- reply format ----------------

    @Test fun readsTheLanguageLineAndTheTranslation() {
        assertEquals(ConversationWire.Reply("Spanish", "Hola, ¿cómo estás?"), ConversationWire.parse("[Spanish]\nHola, ¿cómo estás?"))
        assertEquals(ConversationWire.Reply("English", "Thank you very much"), ConversationWire.parse("  [english] \n\"Thank you very much\"\n"))
        assertEquals(ConversationWire.Reply("Japanese", "おはよう"), ConversationWire.parse("Language: Japanese\nおはよう"))
        assertEquals(ConversationWire.Reply("Swahili", "Habari"), ConversationWire.parse("[Swahili] Habari"))
        assertEquals(ConversationWire.Reply(null, "Just a translation"), ConversationWire.parse("Just a translation"))
        assertEquals(ConversationWire.Reply(null, "[Spanish]"), ConversationWire.parse("[Spanish]"))
    }

    @Test fun promptsSayWhichWayToTranslate() {
        val two = ConversationWire.prompt("English", "Spanish", twoWay = true)
        assertTrue(two.contains("If the input is in English, translate it into Spanish"))
        assertTrue(two.contains("If it is in Spanish, translate it into English"))
        assertTrue(ConversationWire.prompt(null, "French", twoWay = false).contains("Work out which language"))
        assertTrue(ConversationWire.prompt("German", "French", twoWay = false).contains("recognised as German"))
        listOf(two, ConversationWire.prompt(null, "French", false)).forEach { assertTrue(it.contains("[Spanish]")) }
    }
}
