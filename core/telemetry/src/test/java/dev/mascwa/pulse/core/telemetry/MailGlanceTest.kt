package dev.mascwa.pulse.core.telemetry

import dev.mascwa.pulse.core.telemetry.MailGlance.Kind
import dev.mascwa.pulse.core.telemetry.MailGlance.Notice
import dev.mascwa.pulse.core.telemetry.MailGlance.Watch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every rule here is a way the count comes out wrong on a real phone, stated as a property.
 *
 * The number this produces is read at a glance and believed, so the failures that matter are the
 * quiet ones — a stuck "1 new" from a foreground service, a badge multiplied by its own children, a
 * text counted twice. None of those looks like a bug on the screen; they look like a number.
 */
class MailGlanceTest {

    private var seq = 0

    private fun notice(
        pkg: String = GMAIL,
        group: String = "$pkg|inbox",
        kind: Kind = Kind.MAIL,
        summary: Boolean = false,
        ongoing: Boolean = false,
        number: Int = 0,
        key: String = "k${seq++}",
    ) = Notice(
        key = key,
        pkg = pkg,
        groupKey = group,
        kind = kind,
        isGroupSummary = summary,
        isOngoing = ongoing,
        number = number,
    )

    private fun watching(vararg pkgs: String, barred: Set<String> = emptySet()) =
        Watch(chosen = pkgs.toSet(), barred = barred)

    @Test
    fun `three notifications from a ticked mail app are three waiting`() {
        val g = MailGlance.summarise(List(3) { notice() }, watching(GMAIL))
        assertEquals(3, g.total)
        assertEquals(listOf(MailGlance.App(GMAIL, 3)), g.apps)
    }

    @Test
    fun `a replayed notification is not counted twice`() {
        // ⚠️ The listener recomputes from `getActiveNotifications()` on every trigger and a rebind
        // hands back what is already on the shade. Without the key dedupe, reconnecting after an OS
        // kill would double every count — and it would look like a burst of new mail.
        val one = notice(key = "same")
        val g = MailGlance.summarise(listOf(one, one.copy(number = 9)), watching(GMAIL))
        assertEquals(1, g.total)
    }

    @Test
    fun `a permanent foreground notification is not mail waiting`() {
        // K-9 and FairEmail run a service with an ongoing notification. Counted, it would be a "1
        // new" that never goes away and never corresponds to anything.
        val g = MailGlance.summarise(
            listOf(notice(ongoing = true), notice(ongoing = true, number = 40)),
            watching(GMAIL),
        )
        assertEquals(0, g.total)
        assertNull("nothing waiting says nothing at all", g.line)
    }

    @Test
    fun `a text message contributes nothing however it is posted`() {
        // Texts are counted exactly, from the content provider. One counted here as well would show
        // up twice on the same widget, with two different numbers for one inbox.
        val messages = listOf(
            notice(pkg = MESSAGES, kind = Kind.MESSAGE),
            notice(pkg = MESSAGES, kind = Kind.MESSAGE, number = 4),
        )
        assertEquals(0, MailGlance.summarise(messages, watching(MESSAGES)).total)
    }

    @Test
    fun `the default texts app is barred even when it is ticked`() {
        // ⚠️ The second half of the same guard, and it is not redundant: RCS through Google Messages
        // can post with NO category at all, which the Kind check reads as OTHER and would count. A
        // package the platform names as the SMS app is barred whatever the picker says.
        val rcs = listOf(notice(pkg = MESSAGES, kind = Kind.OTHER), notice(pkg = MESSAGES, kind = Kind.OTHER))
        assertEquals(2, MailGlance.summarise(rcs, watching(MESSAGES)).total)  // ticked, not barred
        assertEquals(0, MailGlance.summarise(rcs, watching(MESSAGES, barred = setOf(MESSAGES))).total)
    }

    @Test
    fun `nothing is counted until somebody ticks the app`() {
        // The picker starts empty on purpose: neither available signal is trustworthy enough to
        // decide this for the user, so a fresh install counts nothing rather than guessing.
        val g = MailGlance.summarise(List(5) { notice() }, Watch())
        assertEquals(0, g.total)
        assertTrue(g.apps.isEmpty())
    }

    @Test
    fun `a mail app that sets no category still counts`() {
        // Requiring CATEGORY_EMAIL would under-count badly — plenty of mail apps never set it — and
        // is why the user's tick is what decides, not the metadata.
        assertEquals(2, MailGlance.summarise(List(2) { notice(kind = Kind.OTHER) }, watching(GMAIL)).total)
    }

    @Test
    fun `a badge on every child is not multiplied by the children`() {
        // ⚠️ THE trap. Some apps put the whole inbox unread on every child, so three children each
        // carrying 57 would read 171 — a figure that grows with how much mail has been ignored, and
        // that looks plausible right up until you open the app.
        val group = listOf(
            notice(summary = true, number = 57),
            notice(number = 57),
            notice(number = 57),
            notice(number = 57),
        )
        assertEquals(3, MailGlance.summarise(group, watching(GMAIL)).total)
    }

    @Test
    fun `a summary alone is worth the number it states`() {
        val g = MailGlance.summarise(listOf(notice(summary = true, number = 12)), watching(GMAIL))
        assertEquals(12, g.total)
    }

    @Test
    fun `a summary that states nothing is worth one, not none`() {
        // Zero means the app declined to say how much, not that nothing is there — it posted a
        // notification, so something is. Reading it as none would drop the whole app silently.
        assertEquals(1, MailGlance.summarise(listOf(notice(summary = true, number = 0)), watching(GMAIL)).total)
    }

    @Test
    fun `two accounts in one app are two groups, not one`() {
        // ⚠️ Grouping by package alone loses an account: with children under one account and only a
        // summary under the other, the children branch would answer for the whole app and the
        // second mailbox would vanish. The platform's own group key keeps them apart.
        val g = MailGlance.summarise(
            listOf(
                notice(group = "gm|work", summary = true),
                notice(group = "gm|work"),
                notice(group = "gm|work"),
                notice(group = "gm|home", summary = true, number = 8),
            ),
            watching(GMAIL),
        )
        assertEquals("two children at work, eight stated at home", 10, g.total)
    }

    @Test
    fun `apps are ordered by how much is waiting, then by name`() {
        // ⚠️ The busiest app is deliberately the one that sorts LAST alphabetically, and a third is
        // tied with the first. With Gmail ahead of Outlook on both counts the fixture would pass
        // against a plain sort by package name and prove nothing about the ordering at all.
        val g = MailGlance.summarise(
            listOf(
                notice(pkg = GMAIL, group = "g|1"),
                notice(pkg = OUTLOOK, group = "o|1"),
                notice(pkg = OUTLOOK, group = "o|1"),
                notice(pkg = OUTLOOK, group = "o|1"),
                notice(pkg = PROTON, group = "p|1"),
            ),
            watching(GMAIL, OUTLOOK, PROTON),
        )
        // Tied at one each, Proton comes first: "ch." sorts before "com." ('h' < 'o').
        assertEquals(
            listOf(MailGlance.App(OUTLOOK, 3), MailGlance.App(PROTON, 1), MailGlance.App(GMAIL, 1)),
            g.apps,
        )
        assertEquals(5, g.total)
    }

    @Test
    fun `the wording is new, never unread`() {
        // ⚠️ Not a style preference. This counts what was notified and not cleared, which is a
        // different quantity from unread — a mail read on a laptop can still be sitting on the
        // shade — so "unread" would be a claim about the mailbox the method cannot support.
        val line = MailGlance.summarise(List(3) { notice() }, watching(GMAIL)).line
        assertEquals("3 new", line)
        assertFalse("the one phrasing must never say unread", line.orEmpty().contains("unread"))
    }

    @Test
    fun `the picker's seed list never offers a messaging app`() {
        // Seeding one would put a texts app at the top of a list of mail apps, and one hurried tick
        // would then count every text twice. The list orders the picker and does nothing else, so
        // being stale is survivable and being wrong in THIS direction is not.
        val messaging = setOf(
            MESSAGES, "com.samsung.android.messaging", "com.android.mms",
            "org.thoughtcrime.securesms", "com.whatsapp", "org.telegram.messenger",
            "com.facebook.orca", "com.discord", "com.Slack",
        )
        val overlap = MailGlance.LIKELY_MAIL intersect messaging
        assertTrue("a messaging package is seeded as mail: $overlap", overlap.isEmpty())
        assertTrue("the seed list is worth having", MailGlance.LIKELY_MAIL.size >= 8)
    }

    private companion object {
        const val GMAIL = "com.google.android.gm"
        const val OUTLOOK = "com.microsoft.office.outlook"
        const val PROTON = "ch.protonmail.android"
        const val MESSAGES = "com.google.android.apps.messaging"
    }
}
