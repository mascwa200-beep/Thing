package dev.mascwa.pulse.sky

import dev.mascwa.pulse.core.telemetry.Ephemeris
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

class SkyCardTextTest {

    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun `right ascension and declination print in sexagesimal with the seconds carried`() {
        // Betelgeuse, J2000: 88.79294° = 5h 55m 10.3s; +7.40706° = 7° 24′ 25.4″.
        assertEquals("5h 55m 10s", SkyCardText.raText(88.79294))
        assertEquals("+7° 24′ 25″", SkyCardText.decText(7.40706))
        assertEquals("6h 0m 0s", SkyCardText.raText(90.0))
        assertEquals("0h 0m 0s", SkyCardText.raText(360.0))
        assertEquals("23h 59m 59s", SkyCardText.raText(359.9958))
        // A negative RA is the same direction round the other way.
        assertEquals("18h 0m 0s", SkyCardText.raText(-90.0))
        assertEquals("−45° 30′ 0″", SkyCardText.decText(-45.5))
        // 59.7″ rounds up and carries: 10° 0′ 59.7″ is 10° 1′ 0″, not 10° 0′ 60″.
        assertEquals("+10° 1′ 0″", SkyCardText.decText(10.0 + 59.7 / 3600.0))
        assertEquals("+0° 0′ 0″", SkyCardText.decText(0.0))
    }

    @Test
    fun `sizes and distances pick the unit worth reading`() {
        assertEquals("1.5°", SkyCardText.sizeText(1.5))
        assertEquals("31.2′", SkyCardText.sizeText(31.2 / 60.0))
        assertEquals("39.1″", SkyCardText.sizeText(39.1 / 3600.0))
        assertEquals("384,400 km", SkyCardText.distanceText(384_400.0))
        assertEquals("1.00 AU", SkyCardText.distanceText(149_597_870.7))
        assertEquals("4.21 AU", SkyCardText.distanceText(4.21 * 149_597_870.7))
        assertEquals("4.21 AU · 39.1″ across", SkyCardText.distanceLine(4.21 * 149_597_870.7, 39.1 / 3600.0))
        assertEquals("384,400 km", SkyCardText.distanceLine(384_400.0, null))
        assertNull(SkyCardText.distanceLine(null, null))
        assertNull(SkyCardText.distanceLine(0.0, 0.0))
    }

    @Test
    fun `the where line is signed, to a tenth, and says when a thing is under the horizon`() {
        assertEquals("Alt +35.2° · Az 225.4° SW", SkyCardText.whereLine(225.4, 35.2))
        assertEquals("Alt -2.0° · Az 0.0° N · below the horizon", SkyCardText.whereLine(360.0, -2.0))
        assertEquals("J2000  RA 6h 0m 0s · Dec +0° 0′ 0″", SkyCardText.coordinateLine("J2000", 90.0, 0.0))
    }

    @Test
    fun `sixteen compass points`() {
        assertEquals("N", SkyCardText.cardinal(0.0))
        assertEquals("NNE", SkyCardText.cardinal(22.5))
        assertEquals("NE", SkyCardText.cardinal(45.0))
        assertEquals("W", SkyCardText.cardinal(270.0))
        assertEquals("NNW", SkyCardText.cardinal(337.5))
        assertEquals("N", SkyCardText.cardinal(359.0))
        assertEquals("N", SkyCardText.cardinal(-1.0))
    }

    @Test
    fun `rise and set read as local times, and the polar cases say so instead of inventing one`() {
        // 2026-09-23: 18:42Z, 01:15Z next day as transit, 07:48Z set.
        val rise = 1_790_188_920_000L
        val transit = 1_790_212_500_000L
        val set = 1_790_236_080_000L
        assertEquals(
            "Rises 18:42 · highest 01:15 · sets 07:48",
            SkyCardText.riseSetLine(Ephemeris.RiseSet(rise, transit, set), utc),
        )
        assertEquals(
            "Highest 01:15 · sets 07:48",
            SkyCardText.riseSetLine(Ephemeris.RiseSet(null, transit, set), utc),
        )
        assertEquals(
            "Up all day today · highest 01:15",
            SkyCardText.riseSetLine(Ephemeris.RiseSet(null, transit, null, alwaysUp = true), utc),
        )
        assertEquals(
            "Below the horizon all day today",
            SkyCardText.riseSetLine(Ephemeris.RiseSet(null, null, null, alwaysDown = true), utc),
        )
        assertNull(SkyCardText.riseSetLine(null, utc))
    }
}
