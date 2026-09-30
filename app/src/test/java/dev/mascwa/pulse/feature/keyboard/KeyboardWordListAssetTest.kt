package dev.mascwa.pulse.feature.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bundled word list, checked against itself and against the cores that read it.
 *
 * ⚠️ Every guard here is against a silent failure. [Dictionary] binary-searches the file in the order it
 * is written, and a list out of that order does not throw — it simply stops finding words, so every
 * word would read as a typo and autocorrect would start "correcting" real ones. [Dictionary.of] sorts
 * whatever it is given, so the app would survive it; this test is what says the file itself is right.
 *
 * The behaviours at the bottom run against the REAL list, not a fixture, because the list is what
 * decides them: a fixture proves the rule, only the real list proves "teh" becomes "the" on the phone.
 */
class KeyboardWordListAssetTest {

    private val asset = File("src/main/assets/keyboard/en_US.tsv")
    private val notice = File("src/main/assets/keyboard/NOTICE.txt")

    private val lines: List<String> by lazy {
        assertTrue("the word list is missing: ${asset.absolutePath}", asset.isFile)
        asset.readLines()
    }

    private val dict: Dictionary by lazy { Dictionary.parse(lines.asSequence()) }

    @Test
    fun `every line is a word and a frequency`() {
        for (line in lines) {
            val f = line.split('\t')
            assertEquals("wrong column count: '$line'", 2, f.size)
            assertTrue("empty word: '$line'", f[0].isNotBlank())
            val freq = f[1].toIntOrNull()
            assertTrue("frequency is not 0-255: '$line'", freq != null && freq in 0..255)
        }
    }

    @Test
    fun `the list is the size it was built to be`() {
        // A floor rather than an exact count, so a rebuild from a newer source is not a failure; losing
        // most of it is. 91,541 at the time of writing.
        assertTrue("only ${lines.size} words — the list looks truncated", lines.size >= 90_000)
        assertEquals("the parser dropped lines the file holds", lines.size, dict.size)
    }

    @Test
    fun `the file is in the order the keyboard searches it, with no word twice`() {
        val words = lines.map { it.substringBefore('\t') }
        for (i in 1 until words.size) {
            assertTrue(
                "out of order at line ${i + 1}: '${words[i - 1]}' then '${words[i]}'",
                Dictionary.ORDER.compare(words[i - 1], words[i]) < 0,
            )
        }
    }

    @Test
    fun `the licence travels with the list`() {
        assertTrue("the word list's NOTICE is missing", notice.isFile)
        val text = notice.readText()
        for (needed in listOf("LatinIME", "The Android Open Source Project", "CHANGES MADE", "TERMS AND CONDITIONS")) {
            assertTrue("NOTICE does not mention '$needed'", needed in text)
        }
    }

    // ── what the real list does ─────────────────────────────────────────────────────────────────

    private fun corrected(typed: String): String? =
        Autocorrect.decide(typed, Suggest.candidates(typed, dict), { dict.contains(it) }, allowed = true)

    @Test
    fun `the slips people make are corrected to the word they meant`() {
        val expected = mapOf(
            "teh" to "the", "hte" to "the", "tge" to "the", "yhe" to "the", "Teh" to "The",
            "dont" to "don't", "wgat" to "what", "recieve" to "receive", "becuase" to "because",
            "keybaord" to "keyboard", "adn" to "and", "tommorow" to "tomorrow", "definately" to "definitely",
        )
        for ((typed, want) in expected) assertEquals("'$typed'", want, corrected(typed))
    }

    @Test
    fun `a near tie, and a real word, are left alone`() {
        // "helo" is help or hello, "im" is in or I'm, "wierd" is weird or wired: the strip offers them
        // rather than the keyboard deciding.
        for (typed in listOf("helo", "im", "thr", "wierd", "og")) assertNull("'$typed' was corrected", corrected(typed))
        for (word in listOf("the", "receive", "keyboard", "don't", "London")) {
            assertTrue("'$word' is not in the list", dict.contains(word))
            assertNull("'$word' was corrected", corrected(word))
        }
    }
}
