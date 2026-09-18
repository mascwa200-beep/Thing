package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The only place safety rule 5 is enforced, so it is the only place it can be tested.
 */
class AppSuspensionTest {

    private val everything = listOf(
        "dev.mascwa.pulse", "com.android.dialer", "com.android.launcher3",
        "com.android.settings", "com.example.keyboard",
        "com.example.social", "com.example.game", "com.example.shop",
    )

    private fun call(
        dialer: String? = "com.android.dialer",
        launcher: String? = "com.android.launcher3",
        settings: String? = "com.android.settings",
        keyboard: String? = "com.example.keyboard",
        alsoKeep: Set<String> = emptySet(),
        launchable: List<String> = everything,
    ) = AppSuspension.suspendable(
        launchable = launchable, self = "dev.mascwa.pulse",
        dialer = dialer, launcher = launcher, settings = settings, keyboard = keyboard,
        alsoKeep = alsoKeep,
    )

    @Test
    fun `it pauses the ordinary apps and nothing else`() {
        assertEquals(listOf("com.example.social", "com.example.game", "com.example.shop"), call())
    }

    @Test
    fun `it never pauses the dialler`() {
        // ⚠️ Safety rule 5. Everything else in this file is about a phone being usable; this one is
        // about somebody being able to call for help.
        assertFalse("com.android.dialer" in call())
    }

    @Test
    fun `it never pauses this app, the launcher, settings or the keyboard`() {
        val out = call()
        for (p in listOf(
            "dev.mascwa.pulse", "com.android.launcher3", "com.android.settings",
            "com.example.keyboard",
        )) {
            assertFalse("$p must survive", p in out)
        }
    }

    @Test
    fun `an unidentifiable dialler refuses the whole list`() {
        // ⚠️ NOT "suspend everything else". Without knowing which package the dialler is, any
        // suspension might be the one that takes it out.
        assertEquals(emptyList<String>(), call(dialer = null))
        assertEquals(emptyList<String>(), call(dialer = ""))
    }

    @Test
    fun `an unidentifiable launcher refuses the whole list`() {
        assertEquals(emptyList<String>(), call(launcher = null))
        assertEquals(emptyList<String>(), call(launcher = "  "))
    }

    @Test
    fun `an unknown settings package or keyboard is survivable and does not refuse`() {
        // Neither is a way back in the sense the other two are: the notification shade still works
        // and the emergency dialler carries its own keypad. Losing them costs convenience, so they
        // are kept when known and do not veto when not.
        val out = call(settings = null, keyboard = null)
        assertTrue("com.example.social" in out)
        assertTrue("it must still spare the two that matter", "com.android.dialer" !in out)
    }

    @Test
    fun `the caller's own exceptions are spared`() {
        assertFalse("com.example.shop" in call(alsoKeep = setOf("com.example.shop")))
    }

    @Test
    fun `a package listed twice is asked for once`() {
        val out = call(launchable = everything + "com.example.social")
        assertEquals(1, out.count { it == "com.example.social" })
    }

    @Test
    fun `a phone with nothing launchable pauses nothing`() {
        assertEquals(emptyList<String>(), call(launchable = emptyList()))
    }

    @Test
    fun `blank entries are not asked for`() {
        assertEquals(emptyList<String>(), call(launchable = listOf("", "   ")))
    }

    @Test
    fun `canSuspend and whyCannot agree with each other and with the list`() {
        // ⚠️ Stated over the cases rather than asserted one at a time: a surface that says it can
        // while the list refuses, or the reverse, is worse than either being wrong alone.
        val cases = listOf(
            "com.android.dialer" to "com.android.launcher3",
            null to "com.android.launcher3",
            "com.android.dialer" to null,
            null to null,
        )
        for ((d, l) in cases) {
            val can = AppSuspension.canSuspend(d, l)
            val why = AppSuspension.whyCannot(d, l)
            assertEquals("canSuspend and whyCannot disagree for $d / $l", can, why == null)
            val out = call(dialer = d, launcher = l)
            assertEquals("the list disagrees with canSuspend for $d / $l", can, out.isNotEmpty())
        }
        assertNull(AppSuspension.whyCannot("com.android.dialer", "com.android.launcher3"))
        assertNotNull(AppSuspension.whyCannot(null, null))
    }
}
