package dev.mascwa.pulse.feature.launcher

import dev.mascwa.pulse.feature.launcher.HomeGuardPolicy.FAILURES_TO_STAND_DOWN
import dev.mascwa.pulse.feature.launcher.HomeGuardPolicy.State
import dev.mascwa.pulse.feature.launcher.HomeGuardPolicy.WINDOW_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLauncherCoreTest {

    // ── the crash-loop guard ────────────────────────────────────────────────────────────────────

    private val t0 = 1_800_000_000_000L

    /** Start the home screen [times] times, [gapMs] apart, none of them ever becoming healthy. */
    private fun startsThatDie(times: Int, gapMs: Long, from: State = State()): HomeGuardPolicy.Verdict {
        var state = from
        var verdict = HomeGuardPolicy.Verdict(state, false)
        repeat(times) { i ->
            verdict = HomeGuardPolicy.onStart(state, t0 + i * gapMs)
            state = verdict.state
        }
        return verdict
    }

    @Test
    fun `a first start never stands down`() {
        assertFalse(HomeGuardPolicy.onStart(State(), t0).standDown)
    }

    @Test
    fun `a crash loop stands down on the start after the third failure`() {
        // Starts 1..3 leave three failures behind only once start 4 finds start 3 still pending.
        assertFalse(startsThatDie(FAILURES_TO_STAND_DOWN, gapMs = 1_000).standDown)
        assertTrue(startsThatDie(FAILURES_TO_STAND_DOWN + 1, gapMs = 1_000).standDown)
    }

    @Test
    fun `a healthy start clears the record`() {
        val nearly = startsThatDie(FAILURES_TO_STAND_DOWN, gapMs = 1_000)
        val cleared = HomeGuardPolicy.onHealthy()
        assertEquals(State(), cleared)
        // After a start that worked, the next one begins from nothing.
        assertFalse(HomeGuardPolicy.onStart(cleared, t0 + 10_000).standDown)
        assertTrue(nearly.state.failuresMs.isNotEmpty())
    }

    @Test
    fun `failures spread over longer than the window never add up`() {
        // One death every three minutes, forever: a busy phone, not a crash loop.
        val spread = startsThatDie(10, gapMs = WINDOW_MS + 60_000)
        assertFalse(spread.standDown)
        assertTrue(spread.state.failuresMs.size < FAILURES_TO_STAND_DOWN)
    }

    @Test
    fun `a failure stamped in the future is forgotten rather than kept for ever`() {
        val poisoned = State(failuresMs = List(FAILURES_TO_STAND_DOWN) { t0 + 3_600_000L })
        val v = HomeGuardPolicy.onStart(poisoned, t0)
        assertFalse(v.standDown)
        assertTrue(v.state.failuresMs.isEmpty())
        assertEquals(t0, v.state.pendingStartMs)
    }

    // ── the directory ───────────────────────────────────────────────────────────────────────────

    private data class App(val key: String, val label: String)

    @Test
    fun `apps file under their first letter, with anything else last`() {
        assertEquals('G', HomeDirectory.section("Gmail"))
        assertEquals('E', HomeDirectory.section("Élan"))
        assertEquals('E', HomeDirectory.section("eBay"))
        assertEquals(HomeDirectory.OTHER, HomeDirectory.section("1Password"))
        assertEquals(HomeDirectory.OTHER, HomeDirectory.section(""))

        val apps = listOf(
            App("a", "Zoom"), App("b", "elephant"), App("c", "Élan"), App("d", "1Password"),
            App("e", "Calendar"), App("f", "eBay"),
        )
        val groups = HomeDirectory.grouped(apps) { it.label }
        assertEquals(listOf('C', 'E', 'Z', HomeDirectory.OTHER), groups.map { it.first })
        // Folded order inside a group. A raw string compare puts "Élan" after "elephant" (É sorts
        // after every plain letter), which is the order this exists to prevent.
        assertEquals(listOf("eBay", "Élan", "elephant"), groups.first { it.first == 'E' }.second.map { it.label })
    }

    @Test
    fun `search finds a word anywhere in the name, ignoring case and accents`() {
        assertTrue(HomeDirectory.matches("Gmail", "mail"))
        assertTrue(HomeDirectory.matches("Google Maps", "maps goo"))
        assertTrue(HomeDirectory.matches("Élan", "elan"))
        assertFalse(HomeDirectory.matches("Google Maps", "maps weather"))
        // Nothing typed matches everything, so an empty box shows the whole directory.
        assertTrue(HomeDirectory.matches("Anything", "   "))
    }

    @Test
    fun `the dock keeps pin order and skips apps that are gone`() {
        val apps = listOf(App("a", "Alpha"), App("b", "Bravo"), App("c", "Charlie"))
        val docked = HomeDirectory.docked(listOf("c", "gone", "a", "c"), apps) { it.key }
        assertEquals(listOf("Charlie", "Alpha"), docked.map { it.label })
        assertNull(HomeDirectory.docked(emptyList(), apps) { it.key }.firstOrNull())
    }

    @Test
    fun `pinning appends at the end and unpinning removes only that pin`() {
        assertEquals(listOf("a", "b", "c"), HomeDirectory.togglePin(listOf("a", "b"), "c"))
        assertEquals(listOf("a", "c"), HomeDirectory.togglePin(listOf("a", "b", "c"), "b"))
    }

    @Test
    fun `a full dock refuses a new pin but still lets one go`() {
        val full = (1..HomeDirectory.MAX_PINS).map { "p$it" }
        assertNull(HomeDirectory.togglePin(full, "new"))
        // Unpinning is never refused, or a full dock could never be changed at all.
        assertEquals(full - "p3", HomeDirectory.togglePin(full, "p3"))
    }

    // ── the one-time default ────────────────────────────────────────────────────────────────────

    @Test
    fun `the default applies to a device owner that has never had it and never stood down`() {
        assertTrue(HomeDefaultPolicy.shouldApply(applied = false, deviceOwner = true, stoodDownAtMs = null))
    }

    @Test
    fun `the default is applied once and never again`() {
        // Otherwise switching the home screen off would be undone at the next launch.
        assertFalse(HomeDefaultPolicy.shouldApply(applied = true, deviceOwner = true, stoodDownAtMs = null))
    }

    @Test
    fun `a phone that is not device owner is never offered a Home unasked`() {
        assertFalse(HomeDefaultPolicy.shouldApply(applied = false, deviceOwner = false, stoodDownAtMs = null))
    }

    @Test
    fun `a stand-down by the crash-loop guard is never overridden by the default`() {
        assertFalse(HomeDefaultPolicy.shouldApply(applied = false, deviceOwner = true, stoodDownAtMs = t0))
    }

    @Test
    fun `every other combination leaves the Home alone`() {
        // Exhaustive over the eight combinations, so no future edit can make a second one true.
        val yes = listOf(false, true).flatMap { applied ->
            listOf(false, true).flatMap { owner ->
                listOf(null, t0).map { stood -> Triple(applied, owner, stood) }
            }
        }.filter { (a, o, s) -> HomeDefaultPolicy.shouldApply(a, o, s) }
        assertEquals(listOf(Triple(false, true, null as Long?)), yes)
    }
}
