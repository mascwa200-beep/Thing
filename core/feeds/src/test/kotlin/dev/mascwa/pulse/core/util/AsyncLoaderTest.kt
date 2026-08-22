package dev.mascwa.pulse.core.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The folding of a fetch into [Async], and in particular the three ways it can end.
 *
 * ⚠️ The states this produces are read as a **diagnosis** by every screen that shows a feed: "no data,
 * no error, not loading" is supposed to be impossible once a load has been entered, and a surface
 * that hits it is entitled to conclude nothing ever ran. That inference is only sound if the exits
 * here really are exhaustive, which is what these pin.
 */
class AsyncLoaderTest {

    @Test
    fun `a successful load carries data and clears the busy flag`() = runBlocking {
        val state = MutableStateFlow(Async<String>())
        state.load(force = false) { Fetched("hello", fromCache = false, timestampEpochMs = 1_700L) }

        val s = state.value
        assertEquals("hello", s.data)
        assertFalse("a finished load must not still report itself busy", s.loading)
        assertNull(s.error)
        assertEquals(1_700L, s.lastUpdatedEpochMs)
    }

    @Test
    fun `a failed load always writes a reason, and never a blank one`() = runBlocking {
        val state = MutableStateFlow(Async<String>())
        state.load(force = false) { throw IOException("socket died") }

        val s = state.value
        assertFalse(s.loading)
        assertNotNull("a failure with no reason is the silent-failure shape this exists to prevent", s.error)
        assertTrue(s.error!!.isNotBlank())
        assertNull(s.data)
    }

    /**
     * ⚠️ The regression this class was added for.
     *
     * `loading = true` is set *above* the `try`, and the cancellation branch used to rethrow without
     * touching it — so a cancelled load left the flag set for good. That is worse than a cosmetic
     * spinner: callers guard on "is one already running" to avoid stacking fetches, and a flag that
     * never clears turns that guard into a permanent refusal to ever load again.
     */
    @Test
    fun `a cancelled load clears the busy flag on its way out`() = runBlocking {
        val state = MutableStateFlow(Async<String>())

        var propagated = false
        try {
            state.load(force = false) { throw CancellationException("superseded") }
        } catch (e: CancellationException) {
            propagated = true
        }

        assertTrue("cancellation must still propagate — swallowing it breaks structured concurrency", propagated)
        assertFalse("a cancelled load left the feed marked busy forever", state.value.loading)
    }

    /**
     * ⚠️ Cancellation must not be mistaken for a failure either.
     *
     * Writing an `error` here would put "Something went wrong." on screen every time a screen was left
     * quickly enough to cancel its own opening fetch, which is an ordinary thing to do and not a fault.
     */
    @Test
    fun `a cancelled load reports no error`() = runBlocking {
        val state = MutableStateFlow(Async<String>())
        runCatching { state.load(force = false) { throw CancellationException("superseded") } }
        assertNull(state.value.error)
    }

    /**
     * A refresh that fails keeps what was already on screen, and says it is no longer current.
     */
    @Test
    fun `a failed refresh keeps the old data and marks it stale`() = runBlocking {
        val state = MutableStateFlow(Async<String>())
        state.load(force = false) { Fetched("first", fromCache = false, timestampEpochMs = 100L) }
        state.load(force = true) { throw IOException("gone") }

        val s = state.value
        assertEquals("first", s.data)
        assertTrue("a failed refresh that does not mark itself stale reads as a live number", s.stale)
        assertEquals("the timestamp still describes the data being shown", 100L, s.lastUpdatedEpochMs)
    }
}
