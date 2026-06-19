package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporalReasonerTest {

    private val sec = 1_000L
    private val min = 60 * sec
    private val hour = 60 * min
    private val day = 24 * hour

    private fun mem(id: Long, created: Long, text: String = "m$id") =
        Memory(id, text, MemoryKind.OBSERVATION, 5, createdMs = created, lastAccessedMs = created, embedding = emptyList())

    @Test
    fun chronologicalSortsByCreation() {
        val mems = listOf(mem(1, 300), mem(2, 100), mem(3, 200))
        assertEquals(listOf(2L, 3L, 1L), TemporalReasoner.chronological(mems).map { it.id })
    }

    @Test
    fun sinceIsInclusiveAndOrdered() {
        val mems = listOf(mem(1, 50), mem(2, 100), mem(3, 200))
        assertEquals(listOf(2L, 3L), TemporalReasoner.since(mems, sinceMs = 100).map { it.id })
    }

    @Test
    fun inWindowInclusiveAndEmptyOnInverted() {
        val mems = listOf(mem(1, 50), mem(2, 150), mem(3, 250))
        assertEquals(listOf(2L), TemporalReasoner.inWindow(mems, 100, 200).map { it.id })
        assertTrue(TemporalReasoner.inWindow(mems, 200, 100).isEmpty())
    }

    @Test
    fun newestOldestAndSpan() {
        val mems = listOf(mem(1, 300), mem(2, 100), mem(3, 200))
        assertEquals(1L, TemporalReasoner.newest(mems)!!.id)
        assertEquals(2L, TemporalReasoner.oldest(mems)!!.id)
        assertEquals(200L, TemporalReasoner.spanMs(mems))
        assertEquals(0L, TemporalReasoner.spanMs(listOf(mem(1, 100))))
        assertEquals(0L, TemporalReasoner.spanMs(emptyList()))
        assertNull(TemporalReasoner.newest(emptyList()))
    }

    @Test
    fun elapsedClampsFutureTimestamps() {
        val m = mem(1, created = 10_000)
        assertEquals(5_000L, TemporalReasoner.elapsedMs(m, nowMs = 15_000))
        assertEquals(0L, TemporalReasoner.elapsedMs(m, nowMs = 9_000)) // future → clamped
    }

    @Test
    fun describeElapsedBuckets() {
        assertEquals("just now", TemporalReasoner.describeElapsed(30 * sec))
        assertEquals("a minute ago", TemporalReasoner.describeElapsed(min))
        assertEquals("5 minutes ago", TemporalReasoner.describeElapsed(5 * min))
        assertEquals("an hour ago", TemporalReasoner.describeElapsed(hour))
        assertEquals("3 hours ago", TemporalReasoner.describeElapsed(3 * hour))
        assertEquals("yesterday", TemporalReasoner.describeElapsed(day))
        assertEquals("3 days ago", TemporalReasoner.describeElapsed(3 * day))
        assertEquals("4 weeks ago", TemporalReasoner.describeElapsed(30 * day))
        assertEquals("3 months ago", TemporalReasoner.describeElapsed(100 * day))
        assertEquals("a year ago", TemporalReasoner.describeElapsed(400 * day))
        assertEquals("2 years ago", TemporalReasoner.describeElapsed(800 * day))
    }

    @Test
    fun timelineIsNewestFirstStampedAndCapped() {
        val now = 10 * day
        val mems = listOf(
            mem(1, now - 1 * day, "saw the dentist"),
            mem(2, now - 3 * hour, "bought groceries"),
            mem(3, now - 30 * day, "started the project"),
        )
        val out = TemporalReasoner.timeline(mems, now, max = 2)
        val lines = out.lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("- 3 hours ago: bought groceries"))
        assertTrue(lines[1].startsWith("- yesterday: saw the dentist"))
        assertEquals("", TemporalReasoner.timeline(emptyList(), now))
    }
}
