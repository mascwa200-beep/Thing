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

    private val self = "dev.mascwa.pulse"

    private fun call(
        dialer: String? = "com.android.dialer",
        launcher: String? = "com.android.launcher3",
        systemDialer: String? = "com.android.dialer",
        settings: String? = "com.android.settings",
        keyboard: String? = "com.example.keyboard",
        alsoKeep: Set<String> = emptySet(),
        launchable: List<String> = everything,
    ) = AppSuspension.suspendable(
        launchable = launchable, self = self,
        dialer = dialer, launcher = launcher, systemDialer = systemDialer,
        settings = settings, keyboard = keyboard, alsoKeep = alsoKeep,
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
        val dialers = listOf("com.android.dialer", self, null)
        val launchers = listOf("com.android.launcher3", null)
        val systemDialers = listOf("com.android.dialer", null)
        for (d in dialers) for (l in launchers) for (sd in systemDialers) {
            val can = AppSuspension.canSuspend(d, l, self, sd)
            val why = AppSuspension.whyCannot(d, l, self, sd)
            assertEquals("canSuspend and whyCannot disagree for $d / $l / $sd", can, why == null)
            val out = call(dialer = d, launcher = l, systemDialer = sd)
            assertEquals("the list disagrees with canSuspend for $d / $l / $sd", can, out.isNotEmpty())
        }
        assertNull(AppSuspension.whyCannot("com.android.dialer", "com.android.launcher3", self, null))
        assertNotNull(AppSuspension.whyCannot(null, null, self, null))
    }

    // ── when this app is the phone app ─────────────────────────────────────────────────────────

    @Test
    fun `the built-in dialler is never paused, even when this app is the phone app`() {
        // ⚠️ Telecom hands every emergency call to the BUILT-IN dialler's call screen, whatever the
        // default phone app is. A paused package cannot start an activity.
        val out = call(dialer = self, systemDialer = "com.android.dialer")
        assertFalse("com.android.dialer" in out)
        assertTrue("the ordinary apps are still paused", "com.example.social" in out)
    }

    @Test
    fun `this app as the phone app with the built-in dialler unknown refuses the whole list`() {
        assertEquals(emptyList<String>(), call(dialer = self, systemDialer = null))
        assertEquals(emptyList<String>(), call(dialer = self, systemDialer = " "))
        assertNotNull(AppSuspension.whyCannot(self, "com.android.launcher3", self, null))
    }

    @Test
    fun `another app as the phone app does not need the built-in dialler identified`() {
        // It is kept under its own name already; not knowing the built-in one is no reason to refuse.
        val out = call(dialer = "com.example.phone", systemDialer = null, launchable = everything + "com.example.phone")
        assertFalse("com.example.phone" in out)
        assertTrue("com.example.social" in out)
    }
}
