package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ **Every expectation here was taken from running the shipped ranker over the real 13,186-food
 * corpus, not from reasoning about it.** The names below are verbatim entries from the bundled seed,
 * and the orderings asserted are the ones that measurement produced — which is the only reason the
 * head-coverage rule exists at all. Unit tests written against fixtures I invented passed happily
 * while the ranker answered "ground beef" with *Spanish rice with ground beef*.
 */
class FoodSearchTest {

    private fun e(name: String, brand: String = "") = FoodSearch.Entry(name, name, brand)

    private fun best(query: String, vararg names: String): String =
        FoodSearch.rank(names.map { e(it) }.asSequence(), query, 1).first().entry.name

    // ------------------------------------------------------------------ the measured defect

    /**
     * The five real orderings a brevity-only ranker got wrong.
     *
     * Each pair is a short, specific dish against a longer, generic food. Brevity preferred the dish
     * every time, because "few characters" is not "few qualifiers": in every one of these the query
     * word is a modifier hanging off some other head noun.
     */
    @Test
    fun aGenericFoodBeatsAShorterDishThatMerelyMentionsIt() {
        assertEquals("Egg, whole, raw, fresh", best("egg", "Egg burrito", "Egg, whole, raw, fresh"))
        assertEquals("Rice, white, long-grain, regular, cooked",
            best("rice", "Dirty rice", "Rice cake", "Rice, white, long-grain, regular, cooked"))
        assertEquals("Salmon, sockeye, canned, total can contents",
            best("salmon", "Lomi salmon", "Salmon, sockeye, canned, total can contents"))
        assertEquals("Milk, whole", best("milk", "Goat milk", "Oat milk", "Milk, whole"))
        assertEquals("Beef, ground",
            best("ground beef", "Spanish rice with ground beef", "Beef, ground"))
    }

    /**
     * The rule stated directly: the query accounts for all of "egg" and half of "egg burrito".
     *
     * ⚠️ Only the FIRST segment counts. A name's later segments are adjectives on it, and letting
     * them contribute is exactly what made a strained baby food a good answer for "banana".
     */
    @Test
    fun headCoverageIsTheFractionOfTheFirstSegmentTheQueryAccountsFor() {
        val egg = listOf("egg")
        assertEquals(1.0, FoodSearch.headCoverage("Egg, whole, raw, fresh", egg), 1e-9)
        assertEquals(0.5, FoodSearch.headCoverage("Egg burrito", egg), 1e-9)
        // A match that lives only in a later segment covers none of the head.
        assertEquals(0.0, FoodSearch.headCoverage("Babyfood, fruit, bananas with tapioca", egg), 1e-9)
        assertEquals(1.0,
            FoodSearch.headCoverage("Orange juice, 100%, NFS", listOf("orange", "juice")), 1e-9)
    }

    /** Brevity survives as a tie-break, where both heads are equally accounted for. */
    @Test
    fun brevityStillSeparatesTwoEquallyGenericFoods() {
        assertEquals("Bananas, raw",
            best("banana", "Bananas, dehydrated, or banana powder", "Bananas, raw"))
    }

    // ------------------------------------------------------------------------ hard rules

    /**
     * A term that appears nowhere drops the row entirely, rather than scoring it low.
     *
     * ⚠️ Zero has to mean "not a result". A search for "chicken breast" that returns every chicken
     * in the database because one word matched is not a search, and a partial match padded to the
     * bottom of the list still looks like an answer to whoever is reading it.
     */
    @Test
    fun everyQueryTermMustAppearOrTheRowIsNotAResult() {
        val terms = listOf("chicken", "breast")
        assertEquals(0.0, FoodSearch.score(e("Chicken, roasting, dark meat, raw"), terms), 1e-9)
        assertTrue(FoodSearch.score(e("Chicken breast, baked, skin not eaten"), terms) > 0.0)
        assertTrue(FoodSearch.rank(sequenceOf(e("Turkey breast, sliced")), "chicken breast").isEmpty())
    }

    /**
     * Word boundaries, never substrings — the trap this project has corrected five times.
     *
     * A food corpus is unusually rich in it: "oil" sits inside "boiled", "ham" inside "graham",
     * "corn" inside "popcorn". A substring matcher answers "oil" with boiled potatoes.
     */
    @Test
    fun matchingIsByWholeWordNotBySubstring() {
        val oil = listOf("oil")
        assertEquals(0.0, FoodSearch.score(e("Potatoes, boiled, cooked without skin"), oil), 1e-9)
        assertEquals(0.0, FoodSearch.score(e("Crackers, graham, plain"), listOf("ham")), 1e-9)
        assertTrue(FoodSearch.score(e("Oil, olive, salad or cooking"), oil) > 0.0)
    }

    /**
     * Plurals resolve in both directions, including where the singular is short.
     *
     * ⚠️ **Measured, not reasoned about: before [FoodSearch.singular] existed, "yams" returned
     * literally nothing** against a corpus holding "Yam, raw". The prefix rule's length guard —
     * there to stop "oat" reaching "oatmeal" — also blocked every four-letter plural of a
     * three-letter food, in the scorer and in the cheap reject alike. That "eggs" and "figs" worked
     * anyway is luck: the corpus happens to store those pluralised.
     */
    @Test
    fun pluralsResolveEvenWhenTheSingularIsShort() {
        // A plural is the SAME word (2), not a partial match — see wordMatch's note.
        assertEquals(2, FoodSearch.wordMatch("yam", "yams"))
        assertEquals(2, FoodSearch.wordMatch("yams", "yam"))
        assertEquals(2, FoodSearch.wordMatch("bananas", "banana"))
        assertEquals("Beans, NFS", best("bean", "Bean cake", "Beans, NFS"))
        assertEquals(2, FoodSearch.wordMatch("egg", "egg"))
        assertEquals("Yam, raw", best("yams", "Yam, raw"))
        // The cheap reject has to agree, or the row never reaches the scorer to be accepted.
        assertTrue(FoodSearch.couldMatch("sr1\tYam, raw\tVegetables", listOf("yams")))
    }

    /** The prefix rule stays length-guarded, and stripping an "s" cannot smuggle past it. */
    @Test
    fun aShortWordIsNeverStemmedIntoALongerOne() {
        assertEquals(0, FoodSearch.wordMatch("oatmeal", "oat"))
        assertEquals("Corn, raw", best("corn", "Cornbread, dry mix, enriched", "Corn, raw"))
        // ⚠️ "gas" must not become "ga" — below MIN_SINGULAR nothing is stripped.
        assertEquals(null, FoodSearch.singular("gas"))
        // A double "s" is not a plural marker.
        assertEquals(null, FoodSearch.singular("bass"))
        assertEquals("yam", FoodSearch.singular("yams"))
    }

    /**
     * ⚠️ **A compound word is not its first component**, and this test used to assert the
     * opposite.
     *
     * It read `assertEquals(1, wordMatch("cornbread", "corn"))`, with a comment saying an earlier
     * assertion of 0 "was simply wrong about the rule the code states". The rule the code stated was
     * the thing that was wrong: measured over the real corpus's 2,936 distinct words, that one line
     * matched milkshake to milk, cheeseburger to cheese, watermelon to water, meatballs to meat,
     * buttermilk to butter, grapefruit to grape, blueberry to blue and chickpeas to chick. Finding a
     * behaviour surprising and pinning it without measuring whether it is correct is how a defect
     * gets cemented, and this is the record of it happening.
     */
    @Test
    fun aCompoundWordIsNotItsFirstComponent() {
        for ((compound, component) in listOf(
            "cornbread" to "corn",
            "milkshake" to "milk",
            "cheeseburger" to "cheese",
            "watermelon" to "water",
            "meatballs" to "meat",
            "buttermilk" to "butter",
            "grapefruit" to "grape",
            "blueberry" to "blue",
            "beansprouts" to "bean",
            "chickpeas" to "chick",
        )) {
            assertEquals(
                "$compound must not match $component",
                0,
                FoodSearch.wordMatch(compound, component),
            )
            assertEquals(
                "$component must not match $compound",
                0,
                FoodSearch.wordMatch(component, compound),
            )
        }
        // The whole point of the change, end to end: a cheeseburger is no longer an antipasto that
        // happens to mention cheese.
        assertEquals(
            "Cheeseburger, NFS",
            best("cheeseburger", "Antipasto with ham, fish, cheese, vegetables", "Cheeseburger, NFS"),
        )
        assertEquals(
            "Watermelon, raw",
            best("watermelon", "Alcoholic beverage, whiskey sour, prepared with water", "Watermelon, raw"),
        )
    }

    /**
     * An inflection IS the same word, and the gap rule keeps every ending this corpus uses.
     *
     * ⚠️ These are what stops the fix above from being a blunt "no prefixes at all". Each
     * pair was taken from the real corpus vocabulary rather than invented.
     */
    @Test
    fun aShortSuffixIsStillTheSameWord() {
        for ((word, token) in listOf(
            "cooked" to "cook",
            "cooking" to "cook",
            "roasted" to "roast",
            "roasting" to "roast",
            "baked" to "bake",
            "boiled" to "boil",
            "grilled" to "grill",
            "steamed" to "steam",
            "smoked" to "smoke",
        )) {
            assertEquals("$word should stem to $token", 1, FoodSearch.wordMatch(word, token))
            assertEquals("$token should stem to $word", 1, FoodSearch.wordMatch(token, word))
        }
    }

    /**
     * ⚠️ **The cheap reject has to admit everything the scorer would accept**, and in one
     * direction it did not.
     *
     * `wordMatch` accepts a corpus word that the query token starts with — "cooked" finds "cook" —
     * but `couldMatch` tested for the whole token as a substring, which cannot see that. Measured
     * over the real 13,186-food corpus across two dozen ordinary queries, **3,031 foods were scored
     * as results and then refused before the scorer ever saw them**, with nothing on screen to show
     * anything had been dropped. That is the same defect the "yams" comment on [FoodSearch.singular]
     * records, one direction over.
     */
    @Test
    fun theCheapRejectAdmitsEverythingTheScorerWouldAccept() {
        // ⚠️ Real rows, copied out of the bundled corpus, because the first version of this test
        // used invented ones and was ASLEEP: it queried "cooking" against a row saying "cooked",
        // and neither word is a prefix of the other, so `score` returned 0 and the assertion below
        // was never reached. A fixture regular enough to reason about in your head is often too
        // regular to reach the branch. These pairs were found by running the old reject over the
        // whole corpus and asking which scored rows it refused.
        val corpus = listOf(
            "sr170791\tARBY'S, roast beef sandwich, classic\tFast Foods",
            "sr173010\tCereals, CREAM OF WHEAT, 1 minute cook time, dry\tBreakfast Cereals",
            "sr3\tYam, raw\tVegetables",
        )
        var reached = 0
        for (query in listOf("roasted", "roasting", "cooked", "cooking", "yams")) {
            val terms = FoodSearch.tokens(query)
            for (line in corpus) {
                val fields = line.split('\t')
                val entry = FoodSearch.Entry(fields[0], fields[1], "", fields[2])
                if (FoodSearch.score(entry, terms) > 0.0) {
                    reached++
                    assertTrue(
                        "'" + query + "' scores \"" + fields[1] + "\" but the cheap reject hides it",
                        FoodSearch.couldMatch(line, terms),
                    )
                }
            }
        }
        // ⚠️ And the guard on the guard: assert the branch was actually entered, so this can never
        // pass again by scoring nothing at all.
        assertTrue("no fixture row scored — the assertion above never ran", reached >= 4)
    }

    /** A match in the head outranks the same match buried in a qualifier. */
    @Test
    fun aMatchInTheHeadOutranksOneInAQualifier() {
        val terms = listOf("banana")
        val head = FoodSearch.score(e("Bananas, raw"), terms)
        val buried = FoodSearch.score(e("Babyfood, fruit, bananas with tapioca, strained"), terms)
        assertTrue("head $head should beat buried $buried", head > buried)
        assertTrue(FoodSearch.positionWeight(0) > FoodSearch.positionWeight(1))
        assertTrue(FoodSearch.positionWeight(1) > FoodSearch.positionWeight(3))
        // ⚠️ Never zero. A qualifier match is weak, not absent — "roasted" genuinely is what
        // somebody meant when they typed it.
        assertTrue(FoodSearch.positionWeight(9) > 0.0)
    }

    /** A brand is searchable, so a scanned product can be found by the name on the packet. */
    @Test
    fun aBrandIsSearchable() {
        val hits = FoodSearch.rank(
            sequenceOf(e("Hazelnut spread", brand = "Ferrero"), e("Hazelnut spread")),
            "ferrero hazelnut",
        )
        assertEquals(1, hits.size)
        assertEquals("Ferrero", hits.first().entry.brand)
    }

    // ------------------------------------------------------------------------- tokenising

    /**
     * ⚠️ A query of nothing but stop words still returns them.
     *
     * Somebody who typed "with" deserves whatever that matches rather than a silent empty screen —
     * the same rule `GuideSearch.tokens` states, and the same reason.
     */
    @Test
    fun tokenisingNeverEmptiesANonEmptyQuery() {
        assertEquals(listOf("chicken", "breast"), FoodSearch.tokens("Chicken, with breast!"))
        assertEquals(listOf("with"), FoodSearch.tokens("with"))
        assertEquals(emptyList<String>(), FoodSearch.tokens("   "))
        assertTrue(FoodSearch.rank(sequenceOf(e("Anything")), "  ").isEmpty())
    }

    /**
     * The cheap reject over-admits and never misses.
     *
     * ⚠️ That asymmetry is the whole design. A line this waves through is refused properly by
     * [FoodSearch.score] a moment later at the cost of one parse; a line it wrongly rejects is a food
     * that can never be found, and nothing downstream would ever know.
     */
    @Test
    fun theCheapRejectOverAdmitsRatherThanMissing() {
        val line = "sr171287\tEgg, whole, raw, fresh\tDairy and Egg Products\t143"
        assertTrue(FoodSearch.couldMatch(line, listOf("egg")))
        assertTrue("a plural must survive the raw scan", FoodSearch.couldMatch(line, listOf("eggs")))
        assertTrue("substrings are admitted here on purpose", FoodSearch.couldMatch(line, listOf("gg")))
        assertFalse(FoodSearch.couldMatch(line, listOf("egg", "salmon")))
    }

    /** Ranking is deterministic where scores tie, so the same query never reorders itself. */
    @Test
    fun tiedScoresAreOrderedByNameSoResultsDoNotShuffle() {
        val entries = listOf(e("Bread, rye"), e("Bread, nut"), e("Bread, egg"))
        val once = FoodSearch.rank(entries.asSequence(), "bread").map { it.entry.name }
        val again = FoodSearch.rank(entries.reversed().asSequence(), "bread").map { it.entry.name }
        assertEquals(once, again)
        assertEquals(listOf("Bread, egg", "Bread, nut", "Bread, rye"), once)
    }
}
