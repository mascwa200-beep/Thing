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
}
