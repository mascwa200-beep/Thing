package dev.mascwa.pulse.feature.phone

import dev.mascwa.pulse.feature.launcher.HomeDirectory

/**
 * The phone book as the phone screen uses it: Android's one-row-per-number table turned into people,
 * searched, and matched against what is typed on the keypad.
 *
 * Pure — `ContactsRepository` reads the rows — so every rule runs under JUnit. The rules, each of which
 * has a plausible wrong answer:
 *
 * - ⚠️ **One number once per person.** A contact synced from two accounts carries the same number
 *   twice, often written differently (`+1 616 555 0100` and `(616) 555-0100`). They are one number by
 *   [PhoneMatch]'s rule, and showing both would offer two buttons that ring the same phone.
 * - **The number marked primary comes first**, so tapping a person calls the number they chose.
 * - **A person with no name is named by their number**, never left blank: a row with nothing on it is
 *   a row nobody can choose.
 * - **Sorted on the folded name** (lower case, accents removed), the launcher's own rule
 *   ([HomeDirectory.fold]), so "Élodie" files under E and "de Vries" among the Ds.
 * - **Typed digits find names before numbers.** "7285" is the start of Paul and the end of Rob's
 *   number; it is Paul somebody meant, because that is what T9 is for.
 */
object PhoneBook {

    /** One row of Android's phone table: one number, and whose it is. */
    data class Row(
        val contactId: Long,
        val lookupKey: String,
        val name: String?,
        val starred: Boolean,
        val number: String,
        /** The number's label as Android words it ("Mobile", "Work", a custom one). */
        val label: String,
        /** The number this person marked as their default. */
        val primary: Boolean,
    )

    data class Number(val number: String, val label: String)

    data class Contact(
        val id: Long,
        val lookupKey: String,
        val name: String,
        val starred: Boolean,
        /** Never empty: a person with no number is not in a phone's book. */
        val numbers: List<Number>,
    )

    /** A person the keypad digits match, and the number calling them would dial. */
    data class Suggestion(val contact: Contact, val number: Number, val score: Int)

    /** How many people the keypad offers above the number. More than this covers the keys. */
    const val SUGGESTIONS = 3

    /** Android's rows, one person each, sorted for a directory. */
    fun contacts(rows: List<Row>): List<Contact> {
        val out = ArrayList<Contact>()
        for ((id, mine) in rows.filter { it.number.isNotBlank() }.groupBy { it.contactId }) {
            val first = mine.first()
            val numbers = mine.sortedByDescending { it.primary }
                .distinctBy { PhoneMatch.key(it.number) ?: it.number.trim() }
                .map { Number(it.number.trim(), it.label) }
            val name = mine.firstNotNullOfOrNull { it.name?.takeIf(String::isNotBlank)?.trim() }
                ?: numbers.first().number
            out += Contact(id, first.lookupKey, name, mine.any { it.starred }, numbers)
        }
        return out.sortedWith(compareBy({ HomeDirectory.fold(it.name) }, { it.name }, { it.id }))
    }

    /**
     * The people [query] finds: every word in the name ([HomeDirectory.matches], the launcher's search),
     * or — for a query that is a run of at least [DialPad.NUMBER_MATCH_MIN] digits — a number holding it.
     * A blank query is everybody.
     */
    fun search(contacts: List<Contact>, query: String): List<Contact> {
        if (query.isBlank()) return contacts
        val digits = PhoneMatch.digits(query)
        val numeric = digits.length >= DialPad.NUMBER_MATCH_MIN && query.none { it.isLetter() }
        return contacts.filter { c ->
            HomeDirectory.matches(c.name, query) ||
                (numeric && c.numbers.any { PhoneMatch.digits(it.number).contains(digits) })
        }
    }

    /**
     * The people the keypad's [digits] match, best first: a name match ([DialPad.NAME_WORD],
     * [DialPad.NAME_RUN]) above a number match ([DialPad.NUMBER]), then by name. A name match offers
     * the person's first number; a number match offers the number that matched — the one the digits
     * were typed from.
     */
    fun suggestions(contacts: List<Contact>, digits: String, limit: Int = SUGGESTIONS): List<Suggestion> {
        if (digits.isEmpty() || limit <= 0) return emptyList()
        val out = ArrayList<Suggestion>()
        for (c in contacts) {
            val byName = DialPad.score(digits, c.name, null)
            if (byName > 0) {
                out += Suggestion(c, c.numbers.first(), byName)
                continue
            }
            val hit = c.numbers.firstOrNull { DialPad.score(digits, null, it.number) > 0 } ?: continue
            out += Suggestion(c, hit, DialPad.NUMBER)
        }
        return out.sortedWith(
            compareByDescending<Suggestion> { it.score }
                .thenBy { HomeDirectory.fold(it.contact.name) }
                .thenBy { it.contact.id },
        ).take(limit)
    }

    /** The name the phone book has for [number], however either is written; null for none. */
    fun nameFor(contacts: List<Contact>, number: String): String? =
        contacts.firstOrNull { c -> c.numbers.any { PhoneMatch.same(it.number, number) } }?.name
}
