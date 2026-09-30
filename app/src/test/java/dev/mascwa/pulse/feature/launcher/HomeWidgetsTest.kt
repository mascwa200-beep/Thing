package dev.mascwa.pulse.feature.launcher

import dev.mascwa.pulse.feature.launcher.HomeWidgets.MAX_HEIGHT_DP
import dev.mascwa.pulse.feature.launcher.HomeWidgets.MAX_TITLE_CHARS
import dev.mascwa.pulse.feature.launcher.HomeWidgets.MAX_WIDGETS
import dev.mascwa.pulse.feature.launcher.HomeWidgets.MIN_HEIGHT_DP
import dev.mascwa.pulse.feature.launcher.HomeWidgets.Offer
import dev.mascwa.pulse.feature.launcher.HomeWidgets.WIDGET_FEATURE_CONFIGURATION_OPTIONAL
import dev.mascwa.pulse.feature.launcher.HomeWidgets.WIDGET_FEATURE_HIDE_FROM_PICKER
import dev.mascwa.pulse.feature.launcher.HomeWidgets.WIDGET_FEATURE_RECONFIGURABLE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeWidgetsTest {

    // ── adding and removing ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a widget is added to the end`() {
        assertEquals(listOf(4, 9, 2), HomeWidgets.add(listOf(4, 9), 2))
    }

    @Test
    fun `adding a widget already on the console changes nothing`() {
        assertEquals(listOf(4, 9), HomeWidgets.add(listOf(4, 9), 4))
    }

    @Test
    fun `a full console refuses rather than dropping a widget somebody placed`() {
        val full = (1..MAX_WIDGETS).toList()
        assertNull(HomeWidgets.add(full, 99))
        // One short of full still takes it.
        assertEquals(full.dropLast(1) + 99, HomeWidgets.add(full.dropLast(1), 99))
    }

    @Test
    fun `a widget already there is not refused by a full console`() {
        // Re-adding is a no-op, not a new widget, so fullness does not come into it.
        val full = (1..MAX_WIDGETS).toList()
        assertEquals(full, HomeWidgets.add(full, 3))
    }

    @Test
    fun `removing keeps the others in their order`() {
        assertEquals(listOf(4, 2), HomeWidgets.remove(listOf(4, 9, 2), 9))
        assertEquals(listOf(4, 9, 2), HomeWidgets.remove(listOf(4, 9, 2), 7))
    }

    // ── reconciling with the host ───────────────────────────────────────────────────────────────

    @Test
    fun `a widget the host no longer holds is dropped from the console`() {
        val r = HomeWidgets.reconcile(stored = listOf(4, 9, 2), hostIds = listOf(2, 4), pending = null)
        assertEquals(listOf(4, 2), r.keep)
        assertTrue(r.orphans.isEmpty())
    }

    @Test
    fun `an id the host holds that the console does not list is handed back`() {
        val r = HomeWidgets.reconcile(stored = listOf(4), hostIds = listOf(4, 11, 12), pending = null)
        assertEquals(listOf(4), r.keep)
        assertEquals(listOf(11, 12), r.orphans)
    }

    @Test
    fun `the widget being added right now is not an orphan`() {
        // Android's bind prompt is answering for 11 when onStart runs, before its result arrives.
        val r = HomeWidgets.reconcile(stored = listOf(4), hostIds = listOf(4, 11, 12), pending = 11)
        assertEquals(listOf(12), r.orphans)
        // And it is not shown yet either: it is listed only once the flow finishes.
        assertEquals(listOf(4), r.keep)
    }

    // ── height ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a widget's minimum height is read in pixels and drawn in dp`() {
        // 440 px on a 2.75 density screen is 160 dp.
        assertEquals(160, HomeWidgets.heightDp(440, 2.75f))
    }

    @Test
    fun `a widget is drawn no shorter than the floor and no taller than the ceiling`() {
        assertEquals(MIN_HEIGHT_DP, HomeWidgets.heightDp(100, 3f))
        assertEquals(MAX_HEIGHT_DP, HomeWidgets.heightDp(3000, 2f))
    }

    @Test
    fun `a density Android did not report falls back to the floor`() {
        assertEquals(MIN_HEIGHT_DP, HomeWidgets.heightDp(440, 0f))
        assertEquals(MIN_HEIGHT_DP, HomeWidgets.heightDp(440, -1f))
        assertEquals(MIN_HEIGHT_DP, HomeWidgets.heightDp(440, Float.NaN))
    }

    // ── the tab's title ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `a name that fits the tab is written as it is`() {
        assertEquals("Clock", HomeWidgets.title("  Clock "))
        val exact = "x".repeat(MAX_TITLE_CHARS)
        assertEquals(exact, HomeWidgets.title(exact))
    }

    @Test
    fun `a name too long for the tab is cut to fit, with an ellipsis`() {
        val cut = HomeWidgets.title("Digital Wellbeing & parental controls")
        assertEquals(MAX_TITLE_CHARS, cut.length)
        assertEquals("Digital Wellbeing &…", cut)
    }

    @Test
    fun `a cut never leaves a space before the ellipsis`() {
        // The nineteenth character of this name is a space: the cut drops it rather than writing
        // "for my …", so the name comes out one shorter than the cap.
        assertEquals("Quick notes for my…", HomeWidgets.title("Quick notes for my day list"))
    }

    // ── the picker ──────────────────────────────────────────────────────────────────────────────

    private val own = "dev.mascwa.pulse.debug"

    @Test
    fun `a provider that asks to be hidden from pickers is not offered`() {
        val list = listOf(
            Offer("Clock", "com.clock", 0),
            Offer("Hidden", "com.hidden", WIDGET_FEATURE_HIDE_FROM_PICKER),
            Offer("Both", "com.both", WIDGET_FEATURE_HIDE_FROM_PICKER or WIDGET_FEATURE_RECONFIGURABLE),
        )
        assertEquals(listOf("Clock"), HomeWidgets.offered(list, own) { it }.map { it.label })
    }

    @Test
    fun `a reconfigurable provider is still offered`() {
        // The flag one bit below "hide" is not "hide".
        val list = listOf(Offer("Weather", "com.w", WIDGET_FEATURE_RECONFIGURABLE))
        assertEquals(1, HomeWidgets.offered(list, own) { it }.size)
    }

    @Test
    fun `none of LCARS's own widgets are offered`() {
        val list = listOf(Offer("LCARS", own, 0), Offer("Clock", "com.clock", 0))
        assertEquals(listOf("Clock"), HomeWidgets.offered(list, own) { it }.map { it.label })
    }

    @Test
    fun `the picker is in label order, ignoring case, then by package`() {
        // "apple" is what makes the case matter: sorted by raw code point every capital comes first,
        // so it would land after "Weather" rather than before "Clock".
        val list = listOf(
            Offer("weather", "com.b", 0),
            Offer("Clock", "com.z", 0),
            Offer("apple", "com.q", 0),
            Offer("Weather", "com.a", 0),
        )
        assertEquals(
            listOf("com.q", "com.z", "com.a", "com.b"),
            HomeWidgets.offered(list, own) { it }.map { it.packageName },
        )
    }

    // ── configuration ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `a widget with a configuration screen is configured before it is shown`() {
        assertTrue(HomeWidgets.needsConfigure(hasConfigure = true, features = 0))
        assertTrue(HomeWidgets.needsConfigure(hasConfigure = true, features = WIDGET_FEATURE_RECONFIGURABLE))
    }

    @Test
    fun `a widget that says configuring is optional is shown straight away`() {
        assertFalse(HomeWidgets.needsConfigure(hasConfigure = true, features = WIDGET_FEATURE_CONFIGURATION_OPTIONAL))
    }

    @Test
    fun `a widget with no configuration screen is never configured`() {
        assertFalse(HomeWidgets.needsConfigure(hasConfigure = false, features = 0))
    }
}
