package dev.mascwa.pulse.feature.shade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadeDigestTest {

    private fun entry(
        key: String,
        pkg: String = "com.chat",
        title: String = "Title $key",
        text: String = "",
        at: Long = 0L,
        clearable: Boolean = true,
        group: String = key,
        summary: Boolean = false,
    ) = ShadeDigest.Entry(key, pkg, pkg.substringAfterLast('.'), title, text, at, clearable, group, summary)

    @Test
    fun `our own notices are not shown`() {
        val groups = ShadeDigest.digest(listOf(entry("a", pkg = "dev.lcars"), entry("b")), self = "dev.lcars")
        assertEquals(listOf("com.chat"), groups.map { it.pkg })
    }

    @Test
    fun `a summary is dropped while a child of its group is present`() {
        val groups = ShadeDigest.digest(
            listOf(
                entry("s", group = "g", summary = true, title = "3 new messages"),
                entry("c1", group = "g"),
                entry("c2", group = "g"),
            ),
            self = "x",
        )
        assertEquals(listOf("c1", "c2"), groups.single().entries.map { it.key }.sorted())
    }

    @Test
    fun `a summary standing alone is kept, because it is the only record left`() {
        val groups = ShadeDigest.digest(listOf(entry("s", group = "g", summary = true)), self = "x")
        assertEquals(listOf("s"), groups.single().entries.map { it.key })
    }

    @Test
    fun `a summary whose only children are blank still stands alone`() {
        val groups = ShadeDigest.digest(
            listOf(entry("s", group = "g", summary = true), entry("c", group = "g", title = "", text = "")),
            self = "x",
        )
        assertEquals(listOf("s"), groups.single().entries.map { it.key })
    }

    @Test
    fun `an entry with no title and no text is dropped`() {
        val groups = ShadeDigest.digest(
            listOf(entry("a", title = "", text = " "), entry("b", title = "", text = "hello")),
            self = "x",
        )
        assertEquals(listOf("b"), groups.single().entries.map { it.key })
    }

    @Test
    fun `newest first within an app and across apps`() {
        val groups = ShadeDigest.digest(
            listOf(
                entry("old", pkg = "com.mail", at = 100),
                entry("mid", pkg = "com.chat", at = 200),
                entry("new", pkg = "com.mail", at = 300),
            ),
            self = "x",
        )
        assertEquals(listOf("com.mail", "com.chat"), groups.map { it.pkg })
        assertEquals(listOf("new", "old"), groups.first().entries.map { it.key })
    }

    @Test
    fun `a tie is broken by key so the order cannot flicker`() {
        val a = ShadeDigest.digest(listOf(entry("b", at = 5), entry("a", at = 5)), self = "x")
        val b = ShadeDigest.digest(listOf(entry("a", at = 5), entry("b", at = 5)), self = "x")
        assertEquals(listOf("a", "b"), a.single().entries.map { it.key })
        assertEquals(a, b)
    }

    @Test
    fun `clear all names only what Android would let the shade clear`() {
        val groups = ShadeDigest.digest(listOf(entry("a"), entry("b", clearable = false)), self = "x")
        assertEquals(listOf("a"), ShadeDigest.clearableKeys(groups))
        assertEquals(2, ShadeDigest.count(groups))
    }

    @Test
    fun `the lock summary carries app names and counts, never text`() {
        val groups = ShadeDigest.digest(
            listOf(
                entry("a", pkg = "com.chat", title = "secret", text = "secret", at = 2),
                entry("b", pkg = "com.chat", at = 1),
                entry("c", pkg = "com.mail", at = 0),
            ),
            self = "x",
        )
        val summary = ShadeDigest.lockSummary(groups, limit = 5)
        assertEquals(listOf("chat" to 2, "mail" to 1), summary)
        assertTrue(summary.none { (label, _) -> "secret" in label })
        assertEquals(1, ShadeDigest.lockSummary(groups, limit = 1).size)
    }

    @Test
    fun `status separates switched off from not yet connected`() {
        assertEquals(ShadeDigest.Status.OFF, ShadeDigest.status(granted = false, connected = false))
        assertEquals(ShadeDigest.Status.CONNECTING, ShadeDigest.status(granted = true, connected = false))
        assertEquals(ShadeDigest.Status.READY, ShadeDigest.status(granted = true, connected = true))
        // Connected is a fact; the settings string can lag it, so connected wins.
        assertEquals(ShadeDigest.Status.READY, ShadeDigest.status(granted = false, connected = true))
    }

    @Test
    fun `age reads in the shade's shorthand`() {
        val now = 10_000_000L
        assertEquals("now", ShadeDigest.age(now, now - 59_000))
        assertEquals("1m", ShadeDigest.age(now, now - 60_000))
        assertEquals("59m", ShadeDigest.age(now, now - 3_599_000))
        assertEquals("1h", ShadeDigest.age(now, now - 3_600_000))
        assertEquals("1d", ShadeDigest.age(now, now - 86_400_000))
    }

    @Test
    fun `a post time in the future reads as now, never a negative age`() {
        assertEquals("now", ShadeDigest.age(1_000L, 5_000_000L))
    }
}
