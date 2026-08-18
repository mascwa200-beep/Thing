package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two hand-rolled civil calendars in this module agree, always.
 *
 * ⚠️ **Why this is a separate file from `StardateTest`, and why that is not tidiness.** `Stardate` is
 * mirrored to the desktop companion and `MirrorDriftTest` requires the mirror to be byte-identical,
 * tests included — but [EconomyVintage] is *not* mirrored, because the companion has no economy
 * screen. A cross-check living inside the mirrored test would therefore not compile on the desktop
 * at all. It also would not mean anything there: the property under test is that two
 * implementations *which coexist in this module* cannot drift apart, and only one of them crosses.
 *
 * Both convert days-since-epoch to a civil date by hand rather than through `java.time`, so that
 * `:core:telemetry` keeps no platform dependency — each says so in its own KDoc. Neither can be
 * written in terms of the other without changing a working, shipped core for no functional gain
 * ([EconomyVintage] yields year and month; [Stardate] needs day-of-year and length-of-year), so the
 * honest safeguard against a second implementation quietly disagreeing is that disagreement fails
 * the build.
 */
class StardateCivilDriftTest {

    @Test fun theTwoCivilConversionsInThisCoreCanNeverDrift() {
        // ⚠️ This core now hand-rolls civil-from-days twice: here, and in EconomyVintage, which
        // needs year and month rather than day-of-year. Neither can be written in terms of the
        // other without changing a shipped core for no gain, so the safeguard is that disagreement
        // fails the build. Swept across the whole plausible range of the app, both sides of the
        // epoch, at offset 0 so the two are answering the same question.
        var checked = 0
        var day = -25_000L
        while (day < 30_000L) {
            val ms = day * 86_400_000L
            val fromVintage = EconomyVintage.yearOf(ms)
            // at() folds the year into the stardate, so recover it the way the scale defines it:
            // the thousands digit above the 2000 epoch.
            val fromStardate = Stardate.EPOCH_YEAR + kotlin.math.floor(Stardate.at(ms, 0) / 1000.0).toInt()
            assertEquals("civil year disagreed on epoch day $day", fromVintage, fromStardate)
            checked++
            day += 7
        }
        assertTrue("the sweep did not run", checked > 7_000)
    }
}
