package dev.mascwa.pulse.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The directory is the only way into most of this application, and nothing else checks it.
 *
 * ⚠️ Half of the wiring is already compiler-enforced and half is not. `ScreenHost` switches over
 * [Screen] with no `else` branch, so adding a value there fails the build until it is given something
 * to draw — but nothing at all requires a [DeskEntry], and a screen without one is simply
 * **unreachable**: it draws perfectly, it is in the enum, and no route in the program leads to it.
 * That is the shape of defect this project has shipped at least six times, and it is invisible
 * precisely because everything compiles.
 */
class DirectoryTest {

    private val listed = DESK_GROUPS.flatMap { it.entries }

    @Test
    fun everyScreenIsReachableFromTheDirectory() {
        val missing = Screen.entries.toSet() - listed.map { it.screen }.toSet()
        assertTrue("no way to reach $missing — add a DeskEntry", missing.isEmpty())
    }

    /** Two entries for one screen would put it in the list twice and highlight both at once. */
    @Test
    fun noScreenIsListedTwice() {
        val screens = listed.map { it.screen }
        assertEquals(screens.size, screens.toSet().size)
    }

    @Test
    fun everyEntryIsWorthReading() {
        for (e in listed) {
            assertTrue("${e.screen} has no label", e.label.isNotBlank())
            assertTrue("${e.screen} has no description", e.description.isNotBlank())
            assertTrue(
                "${e.screen}'s description should say what the page is for, not repeat its name",
                e.description.length > e.label.length,
            )
        }
    }

    /**
     * ⚠️ Search terms are matched lowercase, so an upper-case one can never be found — and it would
     * look like a perfectly good entry sitting in the list doing nothing.
     */
    @Test
    fun searchTermsAreLowercase() {
        for (e in listed) {
            for (t in e.searchTerms) {
                assertEquals("'${t}' on ${e.screen} will never match", t.lowercase(), t)
            }
        }
    }
}
