package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageInsightsTest {

    private fun hist(vararg pairs: Pair<Int, Int>): List<Int> {
        val h = MutableList(24) { 0 }
        for ((hour, n) in pairs) h[hour] = n
        return h
    }

    @Test
    fun emptySnapshotYieldsNoRecommendations() {
        assertTrue(UsageInsights.recommend(UsageSnapshot.EMPTY, nowHour = 9).isEmpty())
    }

    @Test
    fun topFeaturesRankedByCountAndZeroDropped() {
        val snap = UsageSnapshot(
            listOf(
                FeatureUsage("markets", 12),
                FeatureUsage("weather", 5),
                FeatureUsage("nav", 0),
            ),
            totalEvents = 17,
        )
        val top = UsageInsights.topFeatures(snap)
        assertEquals(listOf("markets", "weather"), top.map { it.key })
    }

    @Test
    fun goToRecommendationNamesTheMostUsedFeature() {
        val snap = UsageSnapshot(listOf(FeatureUsage("markets", 9), FeatureUsage("news", 3)), 12)
        val catalog = listOf(FeatureMeta("markets", "Markets"), FeatureMeta("news", "News"))
        val recs = UsageInsights.recommend(snap, nowHour = 14, catalog = catalog)
        assertTrue(recs.first().headline.contains("Markets"))
        assertEquals("markets", recs.first().routeKey)
    }

    @Test
    fun rightNowHabitSurfacesWhenDistinctFromGoTo() {
        // News is the go-to overall, but weather dominates the 8am window.
        val snap = UsageSnapshot(
            listOf(
                FeatureUsage("news", 20, hourHistogram = hist(20 to 20)),
                FeatureUsage("weather", 6, hourHistogram = hist(8 to 6)),
            ),
            26,
        )
        val catalog = listOf(FeatureMeta("news", "News"), FeatureMeta("weather", "Weather"))
        val recs = UsageInsights.recommend(snap, nowHour = 8, catalog = catalog)
        assertTrue(recs.any { it.routeKey == "weather" && it.headline.contains("Around now") })
    }

    @Test
    fun windowWrapsMidnight() {
        val snap = UsageSnapshot(listOf(FeatureUsage("sky", 3, hourHistogram = hist(23 to 3))), 3)
        // Hour 0 should still pick up activity at hour 23 (one hour earlier).
        assertEquals("sky", UsageInsights.featureForHour(snap, 0)?.key)
    }

    @Test
    fun untriedFeatureWithPitchGetsRecommended() {
        val snap = UsageSnapshot(listOf(FeatureUsage("home", 4)), 4)
        val catalog = listOf(
            FeatureMeta("home", "Pulse"),
            FeatureMeta("jarvis", "J.A.R.V.I.S.", pitch = "Your private on-device assistant."),
        )
        val recs = UsageInsights.recommend(snap, nowHour = 10, catalog = catalog)
        assertTrue(recs.any { it.routeKey == "jarvis" && it.detail.contains("assistant") })
    }

    @Test
    fun untriedWithoutPitchIsNotSuggested() {
        val snap = UsageSnapshot(listOf(FeatureUsage("home", 4)), 4)
        val catalog = listOf(FeatureMeta("home", "Pulse"), FeatureMeta("nav", "Nav"))
        val recs = UsageInsights.recommend(snap, nowHour = 10, catalog = catalog)
        assertTrue(recs.none { it.routeKey == "nav" })
    }

    @Test
    fun respectsMaxCap() {
        val snap = UsageSnapshot(
            listOf(
                FeatureUsage("a", 10, hourHistogram = hist(9 to 10)),
                FeatureUsage("b", 8, hourHistogram = hist(9 to 8)),
            ),
            18,
        )
        val catalog = listOf(
            FeatureMeta("a", "A"), FeatureMeta("b", "B"),
            FeatureMeta("c", "C", pitch = "try c"), FeatureMeta("d", "D", pitch = "try d"),
        )
        assertTrue(UsageInsights.recommend(snap, nowHour = 9, catalog = catalog, max = 2).size <= 2)
    }

    @Test
    fun nullWhenNoActivityInHourWindow() {
        val snap = UsageSnapshot(listOf(FeatureUsage("markets", 5, hourHistogram = hist(9 to 5))), 5)
        assertNull(UsageInsights.featureForHour(snap, 18))
    }
}
