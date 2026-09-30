package dev.mascwa.pulse.feature.launcher

import dev.mascwa.pulse.feature.launcher.RecentApps.Use
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentAppsTest {

    private val launchable = setOf("a", "b", "c", "d", "e")

    @Test
    fun `newest first, one entry per app`() {
        val uses = listOf(Use("a", 1), Use("b", 2), Use("a", 3), Use("b", 4), Use("a", 5))
        assertEquals(listOf("a", "b"), RecentApps.order(uses, launchable, emptySet(), limit = 8))
    }

    @Test
    fun `an app the home screen cannot open is not a recent`() {
        val uses = listOf(Use("com.android.systemui", 9), Use("a", 1))
        assertEquals(listOf("a"), RecentApps.order(uses, launchable, emptySet(), limit = 8))
    }

    @Test
    fun `LCARS and other home screens are never recents`() {
        val uses = listOf(Use("c", 9), Use("a", 5), Use("d", 3))
        assertEquals(listOf("a"), RecentApps.order(uses, launchable, exclude = setOf("c", "d"), limit = 8))
    }

    @Test
    fun `the strip holds at most the limit`() {
        val uses = listOf(Use("a", 1), Use("b", 2), Use("c", 3), Use("d", 4), Use("e", 5))
        assertEquals(listOf("e", "d", "c"), RecentApps.order(uses, launchable, emptySet(), limit = 3))
    }

    @Test
    fun `apps used in the same millisecond keep a stable order`() {
        val one = RecentApps.order(listOf(Use("b", 7), Use("a", 7)), launchable, emptySet(), limit = 8)
        val two = RecentApps.order(listOf(Use("a", 7), Use("b", 7)), launchable, emptySet(), limit = 8)
        assertEquals(listOf("a", "b"), one)
        assertEquals(one, two)
    }
}
