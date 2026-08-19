package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPlanTest {

    private val all = SearchPlan.Availability(library = true, encyclopaedia = true, web = true)
    private val keyless = SearchPlan.Availability(library = true, encyclopaedia = true, web = false)

    // ---- shape -----------------------------------------------------------------------------------

    @Test
    fun practicalQuestionsGoToTheLibraryFirst() {
        for (q in listOf(
            "how do I treat a burn",
            "how to tie a bowline",
            "what should I do if the power goes out",
            "best way to purify water",
        )) {
            assertEquals(q, SearchPlan.Shape.PRACTICAL, SearchPlan.shapeOf(q))
            assertEquals(q, SearchPlan.Tier.LIBRARY, SearchPlan.plan(q, all).order.first())
        }
    }

    @Test
    fun bareNounsAreEntitiesAndGoToTheEncyclopaediaFirst() {
        // Exactly the shape that was measured to be the ONLY thing the old instant-answer endpoint
        // ever returned anything for: 6 of 6 bare-noun lookups answered, 8 of 8 questions did not.
        for (q in listOf("caldera", "the Treaty of Westphalia", "photosynthesis", "Grace Hopper")) {
            assertEquals(q, SearchPlan.Shape.ENTITY, SearchPlan.shapeOf(q))
            assertEquals(q, SearchPlan.Tier.ENCYCLOPAEDIA, SearchPlan.plan(q, all).order.first())
        }
    }

    @Test
    fun questionsThatNeedTodayGoToTheWebFirst() {
        for (q in listOf(
            "election results",
            "bitcoin price today",
            "latest news on the strike",
            "who won the match",
            "is the museum open right now",
            // The contraction, which is how anyone actually types this. It only classifies because
            // the normaliser deletes the apostrophe — see apostrophesCollapseOntoTheWrittenForm.
            "what's today's forecast",
        )) {
            assertEquals(q, SearchPlan.Shape.CURRENT, SearchPlan.shapeOf(q))
            assertEquals(q, SearchPlan.Tier.WEB, SearchPlan.plan(q, all).order.first())
        }
    }

    /**
     * ⚠️ An honest limit, pinned so it is a decision rather than an accident.
     *
     * "what happened in the election" has no time word in it, and neither does "what happened at
     * Chernobyl" — they are the same sentence to any keyword rule, and one is current while the
     * other is history. So neither is classified CURRENT. GENERAL is the right answer: it tries
     * every tier, so the web is still reached, just not first.
     */
    @Test
    fun anEventQuestionWithNoTimeWordIsNotGuessedAtEitherWay() {
        assertEquals(SearchPlan.Shape.GENERAL, SearchPlan.shapeOf("what happened in the election"))
        assertEquals(SearchPlan.Shape.GENERAL, SearchPlan.shapeOf("what happened at Chernobyl"))
        assertTrue(SearchPlan.plan("what happened in the election", all).order
            .contains(SearchPlan.Tier.WEB))
    }

    /**
     * ⚠️ The apostrophe trap, in the direction that bit here.
     *
     * Every word list is written without apostrophes, so a retained one keeps "what's" away from
     * `whats` and "today's" away from `todays` — a breaking-news query silently read as an
     * encyclopaedia lookup. Deleting it is only safe if no two entries collide once collapsed, and
     * that is asserted rather than eyeballed, because the lists will grow.
     */
    @Test
    fun apostrophesCollapseOntoTheWrittenForm() {
        assertEquals(SearchPlan.Shape.CURRENT, SearchPlan.shapeOf("today's headlines"))
        assertEquals("caldera", SearchPlan.searchTerm("what's a caldera"))
        // The typographic apostrophe too — phone keyboards produce it by default.
        assertEquals("caldera", SearchPlan.searchTerm("what’s a caldera"))

        val everyWord = (SearchPlan.CURRENT_MARKERS + SearchPlan.PRACTICAL_MARKERS)
            .flatMap { it.split(' ') }
        val collapsed = everyWord.map { it.replace("'", "") }
        assertEquals(
            "two list entries collide once the apostrophe is dropped — pick one spelling",
            everyWord.toSet().size, collapsed.toSet().size,
        )
    }

    /**
     * ⚠️ The rule this pins is the whole reason CURRENT is checked before PRACTICAL. A guide about
     * storms would answer this confidently and be wrong about the only part that matters — which is
     * exactly the failure mode the tier system exists to prevent, not a stylistic preference.
     */
    @Test
    fun currentBeatsPracticalWhenAQuestionIsBoth() {
        val q = "what should I do about the storm warning today"
        assertTrue("PRACTICAL_MARKERS must genuinely match this, or the test proves nothing",
            SearchPlan.PRACTICAL_MARKERS.any { q.lowercase().contains(it) })
        assertEquals(SearchPlan.Shape.CURRENT, SearchPlan.shapeOf(q))
    }

    /**
     * ⚠️ Word-boundary matching, not `contains`.
     *
     * A bare "current" inside "electric current" and "news" inside "newsagent" would both classify a
     * perfectly ordinary encyclopaedia lookup as breaking news and send it to a tier that may not
     * even be configured. This is the same substring trap that once put a two-stroke engine under
     * "stroke symptoms".
     */
    @Test
    fun aMarkerInsideALongerWordDoesNotFire() {
        assertEquals(SearchPlan.Shape.ENTITY, SearchPlan.shapeOf("electric current"))
        assertEquals(SearchPlan.Shape.ENTITY, SearchPlan.shapeOf("newsagent"))
        assertEquals(SearchPlan.Shape.ENTITY, SearchPlan.shapeOf("scoreboard"))
        // And the real phrase still fires, so the boundary rule has not simply disabled the marker.
        assertEquals(SearchPlan.Shape.CURRENT, SearchPlan.shapeOf("current affairs"))
    }

    @Test
    fun aLongQuestionWithNoMarkersIsGeneralAndStartsCheap() {
        val q = "why does the moon look bigger near the horizon"
        assertEquals(SearchPlan.Shape.GENERAL, SearchPlan.shapeOf(q))
        assertEquals(SearchPlan.Tier.LIBRARY, SearchPlan.plan(q, all).order.first())
    }

    @Test
    fun anEmptyQueryDoesNotThrowAndPlansNothingSpecial() {
        assertEquals(SearchPlan.Shape.GENERAL, SearchPlan.shapeOf("   "))
        assertEquals(SearchPlan.Shape.GENERAL, SearchPlan.shapeOf(""))
    }

    // ---- the refusal -----------------------------------------------------------------------------

    /**
     * ⚠️ The most valuable rule in the file. Without a web key, a question about today cannot be
     * answered, and saying so is a better answer than an encyclopaedia article on the general
     * subject — which is what the old tool effectively did.
     */
    @Test
    fun aCurrentQuestionWithNoWebTierSaysSoByName() {
        val plan = SearchPlan.plan("latest news on the strike", keyless)
        assertEquals(SearchPlan.Tier.WEB, plan.missing)
        assertFalse("the other tiers are still tried — a gap is not a refusal to look",
            plan.order.isEmpty())
        val verdict = SearchPlan.emptyVerdict("the strike", plan.order, plan.unavailable)
        assertTrue(verdict, verdict.contains("Brave"))
        assertTrue(verdict, verdict.contains("live web search"))
    }

    /**
     * And the counterpart: a question the library is the right shelf for is NOT harmed by the web
     * being unconfigured, so it must not carry the notice. A line printed on every answer is a line
     * nobody reads.
     */
    @Test
    fun aPracticalQuestionWithNoWebTierReportsNoGap() {
        val plan = SearchPlan.plan("how do I treat a burn", keyless)
        assertNull(plan.missing)
        assertEquals(listOf(SearchPlan.Tier.LIBRARY, SearchPlan.Tier.ENCYCLOPAEDIA), plan.order)
        // ⚠️ `missing` is what a SUCCESSFUL answer prints beside itself, and it must stay quiet
        // here. The empty verdict is a different question — nothing was found, so an untried tier is
        // now the relevant fact — and it does mention the web, deliberately.
        val beside = plan.missing
        assertNull(beside)
        val verdict = SearchPlan.emptyVerdict("burns", plan.order, plan.unavailable)
        assertTrue(verdict, verdict.contains("Brave"))
    }

    @Test
    fun nothingConfiguredIsDistinctFromNothingFound() {
        val none = SearchPlan.Availability(library = false, encyclopaedia = false, web = false)
        val plan = SearchPlan.plan("caldera", none)
        assertFalse(plan.canSearch)
        assertTrue(SearchPlan.emptyVerdict("caldera", plan.order, plan.unavailable)
            .contains("Nothing is configured"))
    }

    // ---- the term --------------------------------------------------------------------------------

    /**
     * ⚠️ Half the original defect. "what is the capital of France" returns nothing from an entity
     * endpoint and "capital of France" returns the answer — the difference is four words carrying no
     * subject.
     */
    @Test
    fun questionScaffoldingComesOffTheFront() {
        assertEquals("capital of france", SearchPlan.searchTerm("What is the capital of France?"))
        assertEquals("photosynthesis", SearchPlan.searchTerm("Tell me about photosynthesis"))
        assertEquals("caldera", SearchPlan.searchTerm("what's a caldera"))
    }

    /**
     * ⚠️ Interior connectives are load-bearing and must stay. "capital France" is a worse query than
     * "capital of France" for a phrase engine, even though the ranker would not care — which is
     * exactly why this cannot just delegate to `GuideSearch.tokens`.
     */
    @Test
    fun interiorWordsAndOrderAreKept() {
        // ⚠️ Both fixtures repeat a scaffold word AFTER the subject begins, and that is the whole
        // point of them. An earlier pair — "what was the Declaration of Independence" and "the Bank
        // of England" — read like good tests and proved nothing: "of" is not scaffolding, and their
        // only scaffold words were at the front, so stripping front-only and stripping everywhere
        // give byte-identical output. The perturbation sweep caught it; reading the test did not.
        assertEquals("tallest building in the world",
            SearchPlan.searchTerm("what is the tallest building in the world"))
        assertEquals("win the election", SearchPlan.searchTerm("who will win the election"))
        // And the plain front-strip still works, so this has not merely disabled the stripping.
        assertEquals("declaration of independence",
            SearchPlan.searchTerm("what was the Declaration of Independence"))
        assertEquals("bank of england", SearchPlan.searchTerm("the Bank of England"))
    }

    /**
     * ⚠️ Never strip to nothing. A search box handed an empty string returns everything, which is the
     * worst possible answer to a real question.
     */
    @Test
    fun aQueryThatIsAllScaffoldingSurvivesIntact() {
        assertEquals("what is the", SearchPlan.searchTerm("what is the"))
        assertEquals("who", SearchPlan.searchTerm("who"))
        assertTrue(SearchPlan.searchTerm("   ").isEmpty())
    }

    // ---- merging ---------------------------------------------------------------------------------

    private fun a(tier: SearchPlan.Tier, title: String, url: String? = null) =
        SearchPlan.Answer(tier, title, "snippet for $title", url)

    /**
     * ⚠️ Tier order decides precedence, NOT a score. A library relevance figure and a web engine's
     * rank are not the same quantity, and interleaving them numerically would be arithmetic on units
     * that share no scale.
     */
    @Test
    fun theTierOrderDecidesPrecedence() {
        val answers = listOf(
            a(SearchPlan.Tier.WEB, "w1"), a(SearchPlan.Tier.LIBRARY, "l1"),
            a(SearchPlan.Tier.ENCYCLOPAEDIA, "e1"), a(SearchPlan.Tier.LIBRARY, "l2"),
        )
        val libraryFirst = SearchPlan.merge(answers, listOf(
            SearchPlan.Tier.LIBRARY, SearchPlan.Tier.ENCYCLOPAEDIA, SearchPlan.Tier.WEB))
        assertEquals(listOf("l1", "l2", "e1", "w1"), libraryFirst.map { it.title })

        val webFirst = SearchPlan.merge(answers, listOf(
            SearchPlan.Tier.WEB, SearchPlan.Tier.ENCYCLOPAEDIA, SearchPlan.Tier.LIBRARY))
        assertEquals(listOf("w1", "e1", "l1", "l2"), webFirst.map { it.title })
    }

    /** No single tier may crowd the others out of a short list. */
    @Test
    fun aTierIsCappedSoTheOthersStillAppear() {
        val answers = (1..8).map { a(SearchPlan.Tier.LIBRARY, "l$it") } +
            a(SearchPlan.Tier.ENCYCLOPAEDIA, "e1")
        val merged = SearchPlan.merge(answers, SearchPlan.preference(SearchPlan.Shape.PRACTICAL),
            limit = 5, perTier = 3)
        assertEquals(4, merged.size)
        assertEquals(3, merged.count { it.tier == SearchPlan.Tier.LIBRARY })
        assertTrue(merged.any { it.tier == SearchPlan.Tier.ENCYCLOPAEDIA })
    }

    /** The same page arriving from two tiers is read once. */
    @Test
    fun theSameUrlFromTwoTiersAppearsOnce() {
        val u = "https://en.wikipedia.org/wiki/Caldera"
        val merged = SearchPlan.merge(
            listOf(a(SearchPlan.Tier.ENCYCLOPAEDIA, "Caldera", u), a(SearchPlan.Tier.WEB, "Caldera", u)),
            listOf(SearchPlan.Tier.ENCYCLOPAEDIA, SearchPlan.Tier.WEB),
        )
        assertEquals(1, merged.size)
        assertEquals(SearchPlan.Tier.ENCYCLOPAEDIA, merged.first().tier)
    }

    /** Library answers have no URL, so they must not all collapse into one another. */
    @Test
    fun urllessAnswersAreNotTreatedAsDuplicates() {
        val merged = SearchPlan.merge(
            listOf(a(SearchPlan.Tier.LIBRARY, "First Aid"), a(SearchPlan.Tier.LIBRARY, "Burns")),
            listOf(SearchPlan.Tier.LIBRARY),
        )
        assertEquals(2, merged.size)
    }

    // ---- provenance ------------------------------------------------------------------------------

    /**
     * ⚠️ Every tier names itself, and they are all different. A model handed an offline guide, a
     * keyless encyclopaedia summary and a live web result as undifferentiated text will present the
     * encyclopaedia sentence as the current state of the world.
     */
    @Test
    fun everyTierIntroducesItselfDistinctly() {
        val said = SearchPlan.Tier.entries.map { SearchPlan.provenance(it) }
        assertEquals(said.toSet().size, said.size)
        said.forEach { assertTrue(it, it.isNotBlank()) }
    }
}
