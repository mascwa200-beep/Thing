package dev.mascwa.pulse.navigation

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A bottom-nav tab is declared in three hand-maintained places, and this is what makes them agree.
 *
 * ⚠️ **Written because adding the seventh tab drifted two of the three within an hour.** The three
 * are genuinely independent statements — which is the point, they answer different questions — but
 * nothing compared them, so a tab could be in the bar and missing from the other two while the app
 * compiled perfectly:
 *
 *  - `TOP_DESTINATIONS` (Destinations.kt) — what is drawn in the bar.
 *  - `TOP_LEVEL` (Directory.kt) — which section a route belongs to, which is what the header's
 *    location readout shows on every screen. Absent, and the readout is blank there.
 *  - `OFF_MENU` (FeatureCatalog.kt) — the feature's NAME. Absent, and `labelFor` falls back to the
 *    raw route key, so six surfaces at once print `health` as if it were English: device search,
 *    Home's recommendations and MOST USED row, the Oracle's habitual-route line, the `open` tool's
 *    vocabulary, and the activity log. This app has already shipped a raw key as user copy once.
 *
 * ⚠️ Textual on purpose, in the shape of `WidgetLinkageTest`: the real inventories initialise
 * Compose `ImageVector`s, and a gate that can fail for an environmental reason is worse than one
 * that cannot — nobody can tell a real break from a broken harness.
 */
class NavigationInventoryTest {

    private val nav = File("src/main/java/dev/mascwa/pulse/navigation")
    private val catalog = File("src/main/java/dev/mascwa/pulse/data/usage/FeatureCatalog.kt")

    /** `TopDestination(Routes.HEALTH, "HEALTH", …)` → `HEALTH`. */
    private fun barRoutes(): List<String> =
        Regex("""TopDestination\(\s*Routes\.([A-Z_]+)""")
            .findAll(File(nav, "Destinations.kt").readText())
            .map { it.groupValues[1] }
            .toList()

    /** The `TOP_LEVEL` map's keys, read from inside that declaration only. */
    private fun topLevelRoutes(): List<String> {
        val text = File(nav, "Directory.kt").readText()
        val start = text.indexOf("private val TOP_LEVEL")
        assertTrue("TOP_LEVEL has been renamed — this gate is reading nothing", start >= 0)
        val body = text.substring(start, text.indexOf(")", start))
        return Regex("""Routes\.([A-Z_]+)\s+to""").findAll(body).map { it.groupValues[1] }.toList()
    }

    /** The `OFF_MENU` list's routes, read from inside that declaration only. */
    private fun offMenuRoutes(): List<String> {
        val text = catalog.readText()
        val start = text.indexOf("private val OFF_MENU")
        assertTrue("OFF_MENU has been renamed — this gate is reading nothing", start >= 0)
        val body = text.substring(start, text.indexOf("\n    )", start))
        return Regex("""FeatureMeta\(\s*Routes\.([A-Z_]+)""").findAll(body).map { it.groupValues[1] }.toList()
    }

    /**
     * The harness has to be shown to work before its verdicts mean anything — a regex that matched
     * nothing would pass every assertion below.
     */
    @Test
    fun theGateIsActuallyReadingTheThreeInventories() {
        assertTrue("the bar should hold several tabs, found ${barRoutes().size}", barRoutes().size >= 5)
        assertTrue(topLevelRoutes().size >= 5)
        assertTrue(offMenuRoutes().size >= 5)
        assertTrue("HOME is in all three or the patterns are wrong", "HOME" in barRoutes())
        assertTrue("HOME" in topLevelRoutes())
        assertTrue("HOME" in offMenuRoutes())
    }

    @Test
    fun everyBottomNavTabHasASection() {
        val missing = barRoutes() - topLevelRoutes().toSet()
        assertTrue(
            "these tabs are missing from Directory.kt's TOP_LEVEL, so the header's location " +
                "readout is blank on them: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun everyBottomNavTabHasAName() {
        val missing = barRoutes() - offMenuRoutes().toSet()
        assertTrue(
            "these tabs are missing from FeatureCatalog's OFF_MENU, so six surfaces will print " +
                "the raw route key instead of a name: $missing",
            missing.isEmpty(),
        )
    }

    /**
     * The reverse direction, which catches the other half: a route deleted from the bar but left
     * behind in a list. Both lists legitimately carry more than the bar does — TOP_LEVEL nothing
     * extra today, OFF_MENU the two Markets sub-screens — so this checks only that the extras are
     * routes that still exist, not that the sets are equal.
     */
    @Test
    fun nothingNamesARouteThatNoLongerExists() {
        val declared = Regex("""const val ([A-Z_]+) =""")
            .findAll(File(nav, "Destinations.kt").readText())
            .map { it.groupValues[1] }
            .toSet()
        assertTrue("Routes has been restructured — this gate is reading nothing", declared.size >= 20)
        val strays = (topLevelRoutes() + offMenuRoutes()).toSet() - declared
        assertTrue("these name a Routes constant that does not exist: $strays", strays.isEmpty())
    }
}
