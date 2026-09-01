package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The shower table is checked against JPL, not against itself.
 *
 * Every expected instant below came from Skyfield reading NASA/JPL's DE421 — the same ephemeris
 * professional astronomy uses — solved for the moment the Sun's apparent geocentric ecliptic
 * longitude reaches each shower's stored λ☉. Two independent things are being tested at once:
 *
 *  - that [Ephemeris.sunApparentLongitudeDeg] and the Newton solver agree with JPL, and
 *  - that the **stored λ☉ values themselves are right**, because a wrong one would land the peak on
 *    a date that is not the published one. The JPL run reproduces the published peak date of all
 *    ten showers checked here, so the table and the source it came from agree.
 */
class MeteorShowersTest {

    /** Solved from DE421; see the class note. Millisecond values are UTC epoch. */
    private class Ref(val name: String, val lambda: Double, val expectMs: Long, val utc: String)

    private val refs = listOf(
        Ref("March equinox", 0.0, 1_774_017_957_451L, "2026-03-20 14:46"),
        Ref("Quadrantids", 283.15, 1_767_444_505_654L, "2026-01-03 12:48"),
        Ref("Lyrids", 32.32, 1_776_854_455_464L, "2026-04-22 10:41"),
        Ref("Eta Aquariids", 45.5, 1_778_026_326_689L, "2026-05-06 00:12"),
        Ref("Perseids", 140.0, 1_786_553_243_369L, "2026-08-12 16:47"),
        Ref("Draconids", 195.4, 1_791_475_959_406L, "2026-10-08 16:13"),
        Ref("Orionids", 208.0, 1_792_574_537_209L, "2026-10-21 09:22"),
        Ref("Leonids", 235.27, 1_794_927_127_806L, "2026-11-17 14:52"),
        Ref("Geminids", 262.2, 1_797_223_952_299L, "2026-12-14 04:53"),
        Ref("Ursids", 270.7, 1_797_945_622_220L, "2026-12-22 13:20"),
    )

    /** 2026-01-01T00:00Z, the start of the search window every reference was solved from. */
    private val jan1 = 1_767_225_600_000L

    @Test
    fun `every solar-longitude crossing agrees with JPL`() {
        // Meeus chapter 25 is quoted as good to about 0.01 deg, and the Sun moves 0.9856 deg a day,
        // so 0.01 deg is 14.6 minutes of time. Anything inside half an hour is the series behaving
        // as advertised; the measured worst case is far smaller.
        val toleranceMs = 30 * 60 * 1000L
        refs.forEach { r ->
            val got = MeteorShowers.solarLongitudeCrossing(r.lambda, jan1)
            val deltaMin = (got - r.expectMs) / 60_000.0
            assertTrue(
                "${r.name}: expected ${r.utc} UTC, off by $deltaMin min",
                abs(got - r.expectMs) < toleranceMs,
            )
        }
    }

    @Test
    fun `the solver lands on the longitude it was asked for`() {
        refs.forEach { r ->
            val got = MeteorShowers.solarLongitudeCrossing(r.lambda, jan1)
            val lambda = Ephemeris.sunApparentLongitudeDeg(got)
            var err = (lambda - r.lambda) % 360.0
            if (err > 180) err -= 360.0
            if (err < -180) err += 360.0
            assertTrue("${r.name}: solver left ${err} deg of error", abs(err) < 0.001)
        }
    }

    @Test
    fun `sunApparentLongitudeDeg is the longitude sunEquatorial uses`() {
        // ⚠️ The point of extracting it was that the two cannot disagree. Recovering lambda from the
        // published right ascension and declination is the check that they did not drift apart:
        // tan(lambda) = (sin(ra) cos(eps) + tan(dec) sin(eps)) / cos(ra).
        val eps = 23.4366 * Math.PI / 180.0
        listOf(jan1, 1_786_553_243_369L, 1_797_223_952_299L).forEach { ms ->
            val eq = Ephemeris.sunEquatorial(ms)
            val ra = eq.rightAscensionDeg * Math.PI / 180.0
            val dec = eq.declinationDeg * Math.PI / 180.0
            val lambda = Math.toDegrees(
                Math.atan2(
                    Math.sin(ra) * Math.cos(eps) + Math.tan(dec) * Math.sin(eps),
                    Math.cos(ra),
                ),
            ).let { if (it < 0) it + 360.0 else it }
            val direct = Ephemeris.sunApparentLongitudeDeg(ms)
            assertTrue("recovered $lambda vs direct $direct", abs(lambda - direct) < 0.01)
        }
    }

    @Test
    fun `the table is well formed`() {
        assertEquals("ids must be unique", MeteorShowers.ALL.size, MeteorShowers.ALL.map { it.id }.toSet().size)
        MeteorShowers.ALL.forEach { s ->
            assertTrue("${s.name}: lambda in range", s.peakSolarLongitudeDeg in 0.0..360.0)
            assertTrue("${s.name}: RA in range", s.radiantRaDeg in 0.0..360.0)
            assertTrue("${s.name}: dec in range", s.radiantDecDeg in -90.0..90.0)
            assertTrue("${s.name}: zhr positive", s.zhr > 0)
            assertTrue("${s.name}: speed positive", s.speedKmS > 0)
            assertTrue("${s.name}: window before", s.activeDaysBefore > 0)
            assertTrue("${s.name}: window after", s.activeDaysAfter > 0)
            assertTrue("${s.name}: named a parent", s.parent.isNotBlank())
            assertNotNull(MeteorShowers.byId(s.id))
        }
    }

    // ---- seeing it ------------------------------------------------------------------------

    // ⚠️ Every instant below was found by asking Skyfield where the radiant and the Moon actually
    // are, not by reasoning about it. The first draft of this file guessed that the Eta Aquariid
    // radiant would be below the horizon from London at midday in May; it is 14° up and setting.
    // Two assertions were wrong where the code was right, which is exactly the habit this note
    // exists to stop.
    private val londonLat = 51.5074
    private val londonLon = -0.1278

    /** 2026-08-13 01:00 UTC. Perseid radiant 49.9° up, Sun −22.7°, Moon 25° BELOW the horizon. */
    private val perseidNight = 1_786_582_800_000L

    /**
     * 2026-08-23 02:00 UTC. Perseid radiant 62.5° up, Sun −22.1°, Moon 21.4° DOWN and **77% lit**.
     *
     * ⚠️ The brightness is the point. The first version of this fixture used a Moon-down night that
     * also happened to be new moon, so the illuminated fraction was 0.001 and removing the
     * "only while it is up" guard changed the answer by nothing — the guard came back ASLEEP because
     * the fixture never reached the branch.
     */
    private val perseidMoonDownButBright = 1_787_450_400_000L

    /** 2026-08-01 01:00 UTC. Perseid radiant 44.3° up, Sun −19.5°, Moon 28.1° up and 94% lit. */
    private val perseidUnderAMoon = 1_785_546_000_000L

    /** 2026-05-06 23:00 UTC. Eta Aquariid radiant 23.6° BELOW the horizon, and the sky properly dark. */
    private val etaRadiantDown = 1_778_108_400_000L

    /** 2026-08-01 12:00 UTC. Perseid radiant 45.6° UP — in broad daylight, Sun 56° high. */
    private val perseidAtMidday = 1_785_585_600_000L

    /**
     * 2026-05-06 18:00 UTC. Eta Aquariid radiant 35.5° DOWN **and** the Sun still well up.
     *
     * ⚠️ The only fixture where both hindrances are true at once, and so the only one that can
     * observe which of them is reported. Without it the ordering rule is untestable and a
     * perturbation swapping the two branches comes back asleep.
     */
    private val etaBothAtOnce = 1_778_090_400_000L

    @Test
    fun `a radiant below the horizon yields null rather than zero`() {
        val eta = MeteorShowers.byId("eta")!!
        val v = MeteorShowers.viewing(eta, londonLat, londonLon, etaRadiantDown)
        assertTrue("radiant should be below the horizon, was ${v.radiantAltitudeDeg}", v.radiantAltitudeDeg < 0)
        assertEquals(MeteorShowers.Hindrance.RADIANT_DOWN, v.hindrance)
        assertNull("a rate below the horizon is not a number", v.perHour)
    }

    @Test
    fun `daylight is a hindrance even with the radiant high`() {
        // ⚠️ Found by using the code rather than reading it: at midday the Perseid radiant is 46 deg
        // up and the geometry factor cheerfully returned a count for a sky in which nothing at all
        // is visible. The Sun is the largest sky-brightness term there is.
        val per = MeteorShowers.byId("per")!!
        val v = MeteorShowers.viewing(per, londonLat, londonLon, perseidAtMidday)
        assertTrue("radiant is genuinely up: ${v.radiantAltitudeDeg}", v.radiantAltitudeDeg > 40)
        assertTrue("and the Sun is high: ${v.sunAltitudeDeg}", v.sunAltitudeDeg > 40)
        assertEquals(MeteorShowers.Hindrance.DAYLIGHT, v.hindrance)
        assertNull("no count may be offered in daylight", v.perHour)
        val text = MeteorShowers.advice(MeteorShowers.Occurrence(per, perseidAtMidday, 0, true), v)
        assertTrue("should say it is too bright: $text", text.contains("dark"))
        assertTrue("and must not quote a rate: $text", !text.contains("an hour"))
    }

    @Test
    fun `when the radiant is down in daylight it is the daylight that is reported`() {
        // ⚠️ At midday both are usually true, and "come back after dark" is the sentence that helps.
        // Reporting the radiant instead would send somebody outside at two in the afternoon.
        val eta = MeteorShowers.byId("eta")!!
        val v = MeteorShowers.viewing(eta, londonLat, londonLon, etaBothAtOnce)
        assertTrue("radiant is down: ${v.radiantAltitudeDeg}", v.radiantAltitudeDeg < 0)
        assertTrue("and the Sun is up: ${v.sunAltitudeDeg}", v.sunAltitudeDeg > 0)
        assertEquals(MeteorShowers.Hindrance.DAYLIGHT, v.hindrance)
    }

    @Test
    fun `the horizon check agrees with JPL`() {
        // Skyfield reading DE421 puts these three at -23.6, +49.9 and +56.4 deg from London at these
        // instants. Agreement here is really a check on Ephemeris.toHorizontal and sunPosition,
        // which everything on this screen rests on.
        val eta = MeteorShowers.viewing(MeteorShowers.byId("eta")!!, londonLat, londonLon, etaRadiantDown)
        assertEquals(-23.6, eta.radiantAltitudeDeg, 0.5)
        val per = MeteorShowers.viewing(MeteorShowers.byId("per")!!, londonLat, londonLon, perseidNight)
        assertEquals(49.9, per.radiantAltitudeDeg, 0.5)
        val day = MeteorShowers.viewing(MeteorShowers.byId("per")!!, londonLat, londonLon, perseidAtMidday)
        assertEquals(56.4, day.sunAltitudeDeg, 0.5)
    }

    @Test
    fun `a radiant that is up yields a rate below the ideal`() {
        val per = MeteorShowers.byId("per")!!
        val v = MeteorShowers.viewing(per, londonLat, londonLon, perseidNight)
        assertTrue("radiant should be up, was ${v.radiantAltitudeDeg}", v.radiantAltitudeDeg > 0)
        val rate = v.perHour
        assertNotNull(rate)
        // ⚠️ The whole point of the geometry factor: nobody standing on the Earth ever sees the ZHR,
        // because that figure assumes the radiant is straight overhead.
        assertTrue("$rate should be under the ZHR of ${per.zhr}", rate!! < per.zhr)
        assertTrue("$rate should still be worth going out for", rate > 0)
    }

    @Test
    fun `a Moon below the horizon costs nothing however bright it is`() {
        val per = MeteorShowers.byId("per")!!
        val v = MeteorShowers.viewing(per, londonLat, londonLon, perseidMoonDownButBright)
        assertTrue("the Moon should be down at this instant", !v.moonAboveHorizon)
        assertTrue("and bright enough to matter if it were up: ${v.moonIlluminatedFraction}", v.moonIlluminatedFraction > 0.7)
        val geometric = Math.round(per.zhr * Math.sin(Math.toRadians(v.radiantAltitudeDeg))).toInt()
        assertEquals("Moon down: no penalty may be applied", geometric, v.perHour)
    }

    @Test
    fun `a bright Moon that is up takes most of the count away`() {
        val per = MeteorShowers.byId("per")!!
        val v = MeteorShowers.viewing(per, londonLat, londonLon, perseidUnderAMoon)
        assertTrue("the Moon should be up at this instant", v.moonAboveHorizon)
        assertTrue("and 94% lit, was ${v.moonIlluminatedFraction}", v.moonIlluminatedFraction > 0.7)
        val geometric = Math.round(per.zhr * Math.sin(Math.toRadians(v.radiantAltitudeDeg))).toInt()
        // 0.8 illumination x the 0.8 ceiling means roughly a third of the geometric count survives.
        assertTrue(
            "penalised ${v.perHour} should be well under the geometric $geometric",
            v.perHour!! < geometric / 2,
        )
        assertTrue("but not zero — a bright Moon does not end a shower", v.perHour!! > 0)
    }

    @Test
    fun `advice never quotes a rate it does not have`() {
        val eta = MeteorShowers.byId("eta")!!
        val v = MeteorShowers.viewing(eta, londonLat, londonLon, etaRadiantDown)
        val occ = MeteorShowers.Occurrence(eta, etaRadiantDown, 0, true)
        val text = MeteorShowers.advice(occ, v)
        assertTrue("should say the radiant is down: $text", text.contains("below the horizon"))
        assertTrue("must not claim an hourly count: $text", !text.contains("an hour"))
    }

    // ---- the calendar ---------------------------------------------------------------------

    @Test
    fun `upcoming finds the Perseids from late July`() {
        val july20 = 1_784_505_600_000L // 2026-07-20 00:00 UTC
        val list = MeteorShowers.upcoming(july20, withinDays = 45)
        val per = list.firstOrNull { it.shower.id == "per" }
        assertNotNull("the Perseids should be listed in late July", per)
        assertTrue("peak is ahead, so daysFromPeak is negative: ${per!!.daysFromPeak}", per.daysFromPeak < 0)
        assertTrue("and the window is already open by 20 July", per.active)
    }

    @Test
    fun `a shower stays listed while its window is still open after the peak`() {
        // ⚠️ The Southern Taurids run for two weeks past maximum. Dropping a shower the morning
        // after its peak would hide most of what a long, low shower actually is.
        val sta = MeteorShowers.byId("sta")!!
        val peak = MeteorShowers.solarLongitudeCrossing(sta.peakSolarLongitudeDeg, 1_767_225_600_000L)
        val week = peak + 7 * 86_400_000L
        val list = MeteorShowers.upcoming(week, withinDays = 3)
        val found = list.firstOrNull { it.shower.id == "sta" }
        assertNotNull("still active a week after maximum", found)
        assertTrue("and reported as past its peak: ${found!!.daysFromPeak}", found.daysFromPeak > 0)
        assertTrue(found.active)
    }

    @Test
    fun `nothing is listed twice and the soonest comes first`() {
        val list = MeteorShowers.upcoming(1_786_553_243_369L, withinDays = 60)
        assertEquals(list.size, list.map { it.shower.id }.toSet().size)
        val gaps = list.map { abs(it.daysFromPeak) }
        assertEquals("sorted by distance from the peak", gaps.sorted(), gaps)
    }
}
