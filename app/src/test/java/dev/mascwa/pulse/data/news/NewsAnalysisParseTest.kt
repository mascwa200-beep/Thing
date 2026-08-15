package dev.mascwa.pulse.data.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards [NewsAnalysisEngine.parse]'s length bounds — the thing that decides whether the MARKET desk note
 * (the owner-specified 180-240-word "MARKET REACTION + IMPACT" paragraph) survives parsing or the card
 * silently falls back to heuristic copy. Before the desk-note spec, MARKET shared the 600-char sentence
 * cap; a regression back to that cap would reject every valid desk note while all four lines still
 * "parse", which no compile gate would catch.
 */
class NewsAnalysisParseTest {

    /** ~7 chars/word incl. separator — 220 words ≈ 1.6k chars, the realistic desk-note size. */
    private fun words(n: Int) = (1..n).joinToString(" ") { "word$it" }

    private fun response(market: String) = """
        MOOD: A steady day with a hopeful undertone to it.
        MARKET: $market
        WIDER: Most readers likely shrug at this one. Social media probably amplifies the conflict angle, and desks abroad likely file it under domestic politics.
        NEXT: Expect a follow-up when the vote actually lands.
    """.trimIndent()

    @Test
    fun aFullDeskNoteLengthMarketLineParses() {
        val market = words(220)
        val a = NewsAnalysisEngine.parse(response(market))
        assertNotNull("a 220-word desk note must parse", a)
        assertEquals(market, a!!.marketLine)
        assertEquals(NewsAnalysisEngine.CURRENT_VERSION, a.version)
    }

    @Test
    fun aMarketLineOverTheOldSentenceCapStillParses() {
        // 100 words ≈ 690 chars — rejected under the pre-desk-note 600-char cap, must pass now.
        val a = NewsAnalysisEngine.parse(response(words(100)))
        assertNotNull("the old 600-char MARKET cap must be gone", a)
    }

    @Test
    fun aRunawayMarketLineIsRejected() {
        // ~2.9k chars — over the desk-note bound, so the whole analysis is discarded (never partial).
        assertNull(NewsAnalysisEngine.parse(response(words(400))))
    }

    @Test
    fun aMissingLineYieldsNullNotAPartialAnalysis() {
        val threeLines = response(words(200)).lines().filterNot { it.startsWith("NEXT") }.joinToString("\n")
        assertNull(NewsAnalysisEngine.parse(threeLines))
    }

    @Test
    fun theOtherLinesKeepTheSentenceCap() {
        val bloatedMood = response(words(200)).replace(
            "MOOD: A steady day with a hopeful undertone to it.",
            "MOOD: ${words(120)}",
        )
        assertNull("MOOD must still be capped at sentence length", NewsAnalysisEngine.parse(bloatedMood))
    }
}
