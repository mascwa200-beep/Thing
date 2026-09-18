package dev.mascwa.pulse.core.telemetry

import dev.mascwa.pulse.core.telemetry.RingerPolicy.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The PHONE tier's only stateful decision, which is otherwise unreachable by any gate: the code
 * that calls it needs an `AudioManager`, and CI has no handset.
 */
class RingerPolicyTest {

    @Test
    fun `asserting moves a ringing phone to vibrate`() {
        assertEquals(Mode.VIBRATE, RingerPolicy.target(Mode.NORMAL, asserting = true))
    }

    @Test
    fun `asserting over a phone somebody already silenced does nothing`() {
        // ⚠️ The direction that matters. Setting VIBRATE here would make a deliberately silent phone
        // buzz — the rule turning somebody's phone UP, under a name that says it quietened it.
        assertNull(RingerPolicy.target(Mode.SILENT, asserting = true))
    }

    @Test
    fun `asserting when the hold is already in effect does nothing`() {
        // Transition-only: re-asserting a held action must be a no-op, or every heartbeat writes.
        assertNull(RingerPolicy.target(Mode.VIBRATE, asserting = true))
    }

    @Test
    fun `releasing restores the ringer this hold silenced`() {
        assertEquals(Mode.NORMAL, RingerPolicy.target(Mode.VIBRATE, asserting = false))
    }

    @Test
    fun `releasing does not undo a silence somebody chose themselves`() {
        // ⚠️ The half that is easy to get backwards and impossible to notice on a device: "put it
        // back to what it was" reads as unconditional. A phone on SILENT got there AFTER the hold
        // went on — the only state this layer can have set is VIBRATE — so restoring NORMAL would
        // countermand a person with a more recent and more deliberate say than any rule.
        assertNull(RingerPolicy.target(Mode.SILENT, asserting = false))
    }

    @Test
    fun `releasing a ringer nothing touched does nothing`() {
        assertNull(RingerPolicy.target(Mode.NORMAL, asserting = false))
    }

    @Test
    fun `the only state this policy can ever set is one it can also undo`() {
        // The property the no-capture design rests on, stated over the whole input space rather than
        // inferred from the cases above: everything it asserts, it can release from.
        val asserted = Mode.entries.mapNotNull { RingerPolicy.target(it, asserting = true) }.toSet()
        val releasable = Mode.entries.filter { RingerPolicy.target(it, asserting = false) != null }.toSet()
        assertEquals(
            "a state it can set but not restore from would need a persisted capture",
            asserted,
            releasable,
        )
        assertEquals("and that state is vibrate", setOf(Mode.VIBRATE), asserted)
    }

    @Test
    fun `it never reaches silent, in either direction`() {
        // The grant this app does not hold. SILENT is an input only.
        val everything = Mode.entries.flatMap { m ->
            listOf(RingerPolicy.target(m, asserting = true), RingerPolicy.target(m, asserting = false))
        }
        assertEquals(emptyList<Mode>(), everything.filter { it == Mode.SILENT })
    }
}
