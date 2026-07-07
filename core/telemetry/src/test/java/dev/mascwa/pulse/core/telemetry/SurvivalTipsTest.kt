package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SurvivalTipsTest {

    @Test fun catalogHasAtLeast300DistinctNonBlankTips() {
        assertTrue("expected >= 300 tips, got ${SurvivalTips.size}", SurvivalTips.size >= 300)
        assertTrue("tips must be distinct", SurvivalTips.TIPS.toSet().size >= 300)
        assertTrue("no blank tips", SurvivalTips.TIPS.all { it.isNotBlank() })
    }

    @Test fun tipsAreNotificationSized() {
        // Keep bodies short enough to read at a glance in a notification.
        assertTrue(SurvivalTips.TIPS.all { it.length in 20..220 })
    }

    @Test fun tipRotationCyclesAndWraps() {
        assertEquals(SurvivalTips.TIPS[0], SurvivalTips.tip(0))
        assertEquals(SurvivalTips.tip(0), SurvivalTips.tip(SurvivalTips.size))       // full cycle wraps
        assertEquals(SurvivalTips.tip(5), SurvivalTips.tip(SurvivalTips.size + 5))
        assertEquals(SurvivalTips.tip(SurvivalTips.size - 1), SurvivalTips.tip(-1))  // negative wraps
    }

    @Test fun rotationVisitsEveryTipBeforeRepeating() {
        val seen = (0 until SurvivalTips.size).map { SurvivalTips.tip(it) }.toSet()
        assertEquals(SurvivalTips.size, seen.size) // one full pass shows every distinct tip
    }

    // --- tip → guide deep-link classification ---

    @Test fun everyTipMapsToaKnownGuideId() {
        // A tip notification taps straight into a guide, so every tip must resolve to a guide that exists.
        SurvivalTips.TIPS.forEach { tip ->
            val id = SurvivalTips.guideIdFor(tip)
            assertTrue("'$id' for tip '${tip.take(40)}' must be a known guide id", id in SurvivalTips.GUIDE_IDS)
        }
    }

    @Test fun guideIdAtMirrorsTheTipCycle() {
        assertEquals(SurvivalTips.guideIdFor(SurvivalTips.tip(0)), SurvivalTips.guideIdAt(0))
        assertEquals(SurvivalTips.guideIdFor(SurvivalTips.tip(-1)), SurvivalTips.guideIdAt(-1)) // negative wraps
        assertEquals(SurvivalTips.guideIdAt(0), SurvivalTips.guideIdAt(SurvivalTips.size))       // full cycle wraps
    }

    @Test fun distinctiveTipsClassifyToTheRightGuide() {
        // The flagship: a knot tip opens the knots guide.
        assertEquals("knots", SurvivalTips.guideIdFor("Learn a handful of knots cold: bowline, taut-line hitch, clove hitch."))
        assertEquals("water", SurvivalTips.guideIdFor("Boil water for 1 minute to purify it; chemical tablets or bleach also work."))
        assertEquals("fire", SurvivalTips.guideIdFor("Keep tinder bone dry and catch the first spark with char cloth."))
        assertEquals("first-aid", SurvivalTips.guideIdFor("Stop severe bleeding with firm direct pressure; a tourniquet is a last resort."))
        assertEquals("wildlife", SurvivalTips.guideIdFor("Back away slowly from a bear; never get between a mother and her young."))
        assertEquals("signaling", SurvivalTips.guideIdFor("A signal mirror can flash rescuers many kilometres away."))
        assertEquals("navigation", SurvivalTips.guideIdFor("Find Polaris, the North Star, off the Big Dipper's pointer stars."))
        assertEquals("hygiene", SurvivalTips.guideIdFor("Wash your hands before eating and after relieving yourself."))
        assertEquals("urban", SurvivalTips.guideIdFor("Keep a go-bag ready with water, food, radio and copies of documents."))
        assertEquals("food", SurvivalTips.guideIdFor("Never eat a wild mushroom or berry you can't identify with total certainty."))
    }

    @Test fun unclassifiableTipFallsBackToMindset() {
        assertEquals("mindset", SurvivalTips.guideIdFor("Believe in yourself and keep a positive outlook."))
    }
}
