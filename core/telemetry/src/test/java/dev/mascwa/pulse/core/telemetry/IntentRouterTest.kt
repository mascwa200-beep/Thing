package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentRouterTest {

    private val router = IntentRouter()

    @Test
    fun statusKeywordsRouteToStatus() {
        assertTrue(router.route("what's the status?") is JarvisIntent.Status)
        assertTrue(router.route("battery level") is JarvisIntent.Status)
        assertTrue(router.route("run a diagnostic") is JarvisIntent.Status)
    }

    @Test
    fun lockdownKeywordsRouteToLockdown() {
        assertTrue(router.route("lockdown now") is JarvisIntent.Lockdown)
        assertTrue(router.route("go dark") is JarvisIntent.Lockdown)
        assertTrue(router.route("PANIC") is JarvisIntent.Lockdown)
    }

    @Test
    fun generalTextRoutesToChatPreservingInput() {
        val intent = router.route("Tell me a joke")
        assertTrue(intent is JarvisIntent.Chat)
        assertEquals("Tell me a joke", (intent as JarvisIntent.Chat).text)
    }

    @Test
    fun lockdownTakesPriorityOverStatusWords() {
        // Both "status" and "lockdown" present → lockdown wins (safety action first).
        assertTrue(router.route("status then lockdown") is JarvisIntent.Lockdown)
    }
}
