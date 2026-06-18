package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserProfileTest {

    private fun entry(cat: ProfileCategory, text: String, weight: Int = 1, last: Long = 0L) =
        ProfileEntry(cat, text, createdMs = 0L, lastSeenMs = last, weight = weight)

    @Test
    fun classifyByCues() {
        assertEquals(ProfileCategory.PROJECT, UserProfile.classify("I'm working on the Pulse app"))
        assertEquals(ProfileCategory.PREFERENCE, UserProfile.classify("I prefer concise answers"))
        assertEquals(ProfileCategory.INTEREST, UserProfile.classify("I'm interested in astronomy"))
        assertEquals(ProfileCategory.FACT, UserProfile.classify("I live in Berlin"))
    }

    @Test
    fun detectOnlyConfidentSelfDeclarations() {
        assertEquals(ProfileCategory.PREFERENCE, UserProfile.detect("I prefer dark mode"))
        assertEquals(ProfileCategory.INTEREST, UserProfile.detect("I'm learning Spanish"))
        assertEquals(ProfileCategory.PROJECT, UserProfile.detect("I'm building an Android app"))
        // Questions, third-person remarks and plain facts are ignored.
        assertNull(UserProfile.detect("What's the weather today?"))
        assertNull(UserProfile.detect("Tesla stock is up 3%"))
        assertNull(UserProfile.detect("I live in Berlin")) // first-person but no preference/interest/project cue
        assertNull(UserProfile.detect(""))
    }

    @Test
    fun mergeAddsNewEntry() {
        val e = UserProfile.merge(emptyList(), "I like Kotlin", ProfileCategory.PREFERENCE, nowMs = 10)
        assertEquals(1, e.size)
        assertEquals("I like Kotlin", e.single().text)
        assertEquals(1, e.single().weight)
    }

    @Test
    fun mergeReaffirmsAndDedupesCaseInsensitively() {
        var e = UserProfile.merge(emptyList(), "I like Kotlin.", ProfileCategory.PREFERENCE, nowMs = 1)
        e = UserProfile.merge(e, "i like kotlin", ProfileCategory.PREFERENCE, nowMs = 5)
        assertEquals(1, e.size)
        assertEquals(2, e.single().weight)
        assertEquals(5L, e.single().lastSeenMs)
    }

    @Test
    fun mergeEvictsWeakestPastCap() {
        var e = listOf(
            entry(ProfileCategory.FACT, "weak", weight = 1, last = 0L),
            entry(ProfileCategory.FACT, "strong", weight = 9, last = 100L),
        )
        e = UserProfile.merge(e, "newcomer", ProfileCategory.FACT, nowMs = 200, cap = 2)
        assertEquals(2, e.size)
        assertTrue(e.none { it.text == "weak" })
        assertTrue(e.any { it.text == "newcomer" })
        assertTrue(e.any { it.text == "strong" })
    }

    @Test
    fun forgetRemovesByNormalizedKey() {
        val e = listOf(entry(ProfileCategory.INTEREST, "Astronomy"))
        assertTrue(UserProfile.forget(e, "astronomy").isEmpty())
    }

    @Test
    fun inCategoryRanksByWeightThenRecency() {
        val e = listOf(
            entry(ProfileCategory.PREFERENCE, "a", weight = 1, last = 50L),
            entry(ProfileCategory.PREFERENCE, "b", weight = 3, last = 10L),
            entry(ProfileCategory.INTEREST, "c", weight = 9, last = 99L),
        )
        val prefs = UserProfile.inCategory(e, ProfileCategory.PREFERENCE)
        assertEquals(listOf("b", "a"), prefs.map { it.text })
    }

    @Test
    fun digestGroupsByCategoryAndLabels() {
        val e = listOf(
            entry(ProfileCategory.PREFERENCE, "concise answers", weight = 2),
            entry(ProfileCategory.INTEREST, "astronomy"),
            entry(ProfileCategory.PROJECT, "Pulse app"),
        )
        val d = UserProfile.digest(e)
        assertTrue(d.contains("Preferences: concise answers"))
        assertTrue(d.contains("Interests: astronomy"))
        assertTrue(d.contains("Projects: Pulse app"))
    }

    @Test
    fun digestRespectsPerCategoryCap() {
        val e = (1..10).map { entry(ProfileCategory.INTEREST, "i$it", weight = it) }
        val d = UserProfile.digest(e, perCategory = 3)
        // Highest-weight three (i10, i9, i8) included; a low one excluded.
        assertTrue(d.contains("i10"))
        assertFalse(d.contains("i1;") || d.endsWith("i1"))
    }

    @Test
    fun digestEmptyWhenNoEntries() {
        assertEquals("", UserProfile.digest(emptyList()))
    }
}
