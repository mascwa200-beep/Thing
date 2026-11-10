package dev.mascwa.pulse.feature.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneBookTest {

    private fun row(
        id: Long,
        name: String?,
        number: String,
        label: String = "Mobile",
        starred: Boolean = false,
        primary: Boolean = false,
    ) = PhoneBook.Row(id, "key$id", name, starred, number, label, primary)

    @Test
    fun `one number synced from two accounts is one number`() {
        val people = PhoneBook.contacts(
            listOf(
                row(1, "Ann Lee", "+1 616 555 0100"),
                row(1, "Ann Lee", "(616) 555-0100"),
                row(1, "Ann Lee", "616-555-0199", label = "Work"),
            ),
        )
        assertEquals(1, people.size)
        assertEquals(listOf("+1 616 555 0100", "616-555-0199"), people.single().numbers.map { it.number })
    }

    @Test
    fun `the number marked primary comes first`() {
        val ann = PhoneBook.contacts(
            listOf(
                row(1, "Ann", "616 555 0100", label = "Home"),
                row(1, "Ann", "616 555 0199", label = "Mobile", primary = true),
            ),
        ).single()
        assertEquals("616 555 0199", ann.numbers.first().number)
    }

    @Test
    fun `the directory is sorted on the folded name`() {
        val names = PhoneBook.contacts(
            listOf(row(1, "Zed", "1110001"), row(2, "Élodie", "1110002"), row(3, "de Vries", "1110003")),
        ).map { it.name }
        assertEquals(listOf("de Vries", "Élodie", "Zed"), names)
    }

    @Test
    fun `a person with no name is named by their number, and a blank number is no number`() {
        val people = PhoneBook.contacts(
            listOf(row(1, " ", "555 0100"), row(1, null, "555 0100"), row(2, "Ghost", "  ")),
        )
        assertEquals(listOf("555 0100"), people.map { it.name })
    }

    @Test
    fun `starred on any of a person's rows stars the person`() {
        val ann = PhoneBook.contacts(
            listOf(row(1, "Ann", "5550100"), row(1, "Ann", "5550199", starred = true)),
        ).single()
        assertTrue(ann.starred)
    }

    @Test
    fun `search finds a name by any word, and a number only from three digits`() {
        val people = PhoneBook.contacts(listOf(row(1, "Ann Lee", "616 555 0100"), row(2, "Rob", "616 555 7285")))
        assertEquals(listOf("Ann Lee"), PhoneBook.search(people, "lee").map { it.name })
        assertEquals(listOf("Rob"), PhoneBook.search(people, "728").map { it.name })
        // Two digits are in nearly every number, so they find nobody by number.
        assertEquals(emptyList<String>(), PhoneBook.search(people, "72").map { it.name })
        assertEquals(2, PhoneBook.search(people, " ").size)
    }

    @Test
    fun `typed digits find the name they spell before the number they end`() {
        // 7285 is P-A-U-L, and also the last four digits of Rob's number.
        val people = PhoneBook.contacts(listOf(row(1, "Rob", "616 555 7285"), row(2, "Paul", "616 555 0100")))
        assertEquals(listOf("Paul", "Rob"), PhoneBook.suggestions(people, "7285").map { it.contact.name })
    }

    @Test
    fun `nothing typed suggests nobody, and the limit holds`() {
        val people = PhoneBook.contacts((1L..10L).map { row(it, "Pat $it", "555 01$it") })
        assertEquals(emptyList<PhoneBook.Suggestion>(), PhoneBook.suggestions(people, ""))
        assertEquals(PhoneBook.SUGGESTIONS, PhoneBook.suggestions(people, "7").size)
    }

    @Test
    fun `a number match offers the number that matched`() {
        val rob = PhoneBook.contacts(
            listOf(row(1, "Rob", "616 555 0100", primary = true), row(1, "Rob", "616 555 7285")),
        )
        val s = PhoneBook.suggestions(rob, "7285").single()
        assertEquals("616 555 7285", s.number.number)
    }

    @Test
    fun `the book names a number however it is written`() {
        val people = PhoneBook.contacts(listOf(row(1, "Ann", "(616) 555-0100")))
        assertEquals("Ann", PhoneBook.nameFor(people, "+16165550100"))
        assertNull(PhoneBook.nameFor(people, ""))
        assertNull(PhoneBook.nameFor(people, "616 555 0199"))
    }
}
