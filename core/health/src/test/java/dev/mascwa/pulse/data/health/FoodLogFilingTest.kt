package dev.mascwa.pulse.data.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodLogFilingTest {

    // ------------------------------------------------------------------ which keys are month shards

    @Test
    fun `a month key gives up its month`() {
        assertEquals("2026-08", FoodLogFiling.legacyMonth("food_2026-08"))
        assertEquals("1999-12", FoodLogFiling.legacyMonth("food_1999-12"))
        assertEquals("2026-01", FoodLogFiling.legacyMonth("food_2026-01"))
    }

    @Test
    fun `THE INDEX IS NOT A MONTH`() {
        // ⚠️ The one that matters. `food_index` starts with the shard prefix, so a prefix test would
        // have called this a month named "index", written the index JSON out as a shard, and then
        // deleted the real index — every day's totals gone while the entries sat there intact.
        assertNull(FoodLogFiling.legacyMonth("food_index"))
    }

    @Test
    fun `nothing else in the store is mistaken for a month`() {
        assertNull(FoodLogFiling.legacyMonth("food"))
        assertNull(FoodLogFiling.legacyMonth("food_"))
        assertNull(FoodLogFiling.legacyMonth("water_2026-08"))
        assertNull(FoodLogFiling.legacyMonth("recipes"))
        // A future key that happens to start the same way.
        assertNull(FoodLogFiling.legacyMonth("food_favourites"))
        assertNull(FoodLogFiling.legacyMonth("food_settings"))
    }

    @Test
    fun `the shape has to be the whole key, not part of it`() {
        // ⚠️ A BEHAVIOUR PIN as much as a guard, and worth saying which. What enforces this TODAY is
        // `Regex.matches`, which requires the whole input — the `^`/`$` in the pattern are a second,
        // independent statement of the same rule and change nothing on their own (measured). Both
        // are kept: with only one of them, swapping `matches` for `containsMatchIn` passes every
        // test here and starts accepting `food_2026-08-extra`, which becomes a file no reader ever
        // asks for, because every reader asks for `monthOf(day)`.
        assertNull(FoodLogFiling.legacyMonth("food_2026-08-extra"))
        assertNull(FoodLogFiling.legacyMonth("food_x2026-08"))
        assertNull(FoodLogFiling.legacyMonth("food_2026-8"))
        assertNull(FoodLogFiling.legacyMonth("food_26-08"))
    }

    // -------------------------------------------------------------------------------- what is evicted

    @Test
    fun `nothing is dropped while the cache is inside its bound`() {
        val resident = listOf("2026-01", "2026-02", "2026-03", "2026-04")
        assertEquals(emptyList<String>(), FoodLogFiling.evictable(resident, emptySet(), 4))
    }

    @Test
    fun `the least recently used go first, and only enough of them`() {
        val resident = listOf("2026-01", "2026-02", "2026-03", "2026-04", "2026-05", "2026-06")
        // Six resident, four allowed: exactly the two oldest, in that order.
        assertEquals(
            listOf("2026-01", "2026-02"),
            FoodLogFiling.evictable(resident, emptySet(), 4),
        )
    }

    @Test
    fun `A DIRTY MONTH IS NEVER DROPPED`() {
        // ⚠️ The other one that matters. A dirty shard holds entries that exist nowhere else until
        // the flush writes them, so evicting one loses a logged meal — silently, because the next
        // read finds the file without it and reports a smaller day that looks perfectly ordinary.
        val resident = listOf("2026-01", "2026-02", "2026-03", "2026-04", "2026-05", "2026-06")
        val dirty = setOf("2026-01", "2026-02")
        val dropped = FoodLogFiling.evictable(resident, dirty, 4)

        assertTrue("a dirty month was dropped: $dropped", dropped.none { it in dirty })
        // It skips past the two dirty ones and takes the next two oldest instead.
        assertEquals(listOf("2026-03", "2026-04"), dropped)
    }

    @Test
    fun `every month dirty means nothing is dropped, over the bound or not`() {
        // The import case: sixty months touched, none written yet. Holding all of them is correct.
        // ⚠️ Built without `String.format`, whose `%d` follows the DEFAULT LOCALE and would emit
        // Eastern Arabic digits on a device set to one — disagreeing with the literals below it.
        val resident = (1..12).map { "2026-" + it.toString().padStart(2, '0') }
        assertEquals(
            emptyList<String>(),
            FoodLogFiling.evictable(resident, resident.toSet(), 4),
        )
    }

    @Test
    fun `enough are dropped to reach the bound, counting only what actually goes`() {
        // ⚠️ Computed from the shipped rule rather than guessed: eight resident, four allowed, so
        // four must go — but the first two are dirty, so it must reach PAST them and still return
        // four. A version that counted the skipped ones toward its total would return two and leave
        // the cache at six.
        val resident = (1..8).map { "2026-" + it.toString().padStart(2, '0') }
        val dirty = setOf("2026-01", "2026-02")
        val dropped = FoodLogFiling.evictable(resident, dirty, 4)
        assertEquals(listOf("2026-03", "2026-04", "2026-05", "2026-06"), dropped)
        assertEquals(4, resident.size - dropped.size)
    }
}
