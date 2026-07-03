package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the AR wasteland-vision lore catalog. */
class WastelandLoreTest {

    @Test fun catalogIsNonEmptyAndTagged() {
        assertTrue(WastelandLore.LINES.size >= 20)
        WastelandLore.LINES.forEach {
            assertTrue("blank tag", it.tag.isNotBlank())
            assertTrue("blank text", it.text.isNotBlank())
        }
    }

    @Test fun scanWrapsSafely() {
        val n = WastelandLore.LINES.size
        assertEquals(WastelandLore.scan(0), WastelandLore.scan(n))       // wraps forward
        assertEquals(WastelandLore.scan(0), WastelandLore.scan(-n))      // wraps back
        assertEquals(WastelandLore.LINES[1], WastelandLore.scan(1))
        // A large or negative index never throws.
        WastelandLore.scan(999_999)
        WastelandLore.scan(-999_999)
    }

    @Test fun renderTagsTheLine() {
        val s = WastelandLore.scan(3)
        assertEquals("${s.tag} · ${s.text}", WastelandLore.render(3))
    }
}
