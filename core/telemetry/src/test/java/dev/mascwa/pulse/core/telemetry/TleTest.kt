package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TleTest {

    // A live ISS element set, exactly as Celestrak served it.
    private val issName = "ISS (ZARYA)"
    private val issL1 = "1 25544U 98067A   26226.82569810  .00005167  00000+0  10032-3 0  9994"
    private val issL2 = "2 25544  51.6328   9.8801 0007568  46.9314 313.2307 15.49444701580849"

    @Test fun parsesEveryFieldOffTheRealIssElementSet() {
        val e = Tle.parse(issL1, issL2, issName)
        assertNotNull(e)
        e!!
        assertEquals(25544, e.noradId)
        assertEquals("ISS (ZARYA)", e.name)
        assertEquals(51.6328, Math.toDegrees(e.inclinationRad), 1e-9)
        assertEquals(9.8801, Math.toDegrees(e.raanRad), 1e-9)
        // Eccentricity carries an implied leading decimal point: "0007568" is 0.0007568.
        assertEquals(0.0007568, e.eccentricity, 1e-12)
        assertEquals(46.9314, Math.toDegrees(e.argPerigeeRad), 1e-9)
        assertEquals(313.2307, Math.toDegrees(e.meanAnomalyRad), 1e-9)
        // 15.49444701 revs/day -> radians/minute.
        assertEquals(15.49444701 * 2 * Math.PI / 1440.0, e.noKozai, 1e-15)
        // " 10032-3" is 0.10032e-3.
        assertEquals(0.10032e-3, e.bstar, 1e-15)
    }

    @Test fun theExponentFieldUnpacksItsImpliedDecimalPoint() {
        assertEquals(0.10032e-3, Tle.decimalPoint(" 10032-3")!!, 1e-18)
        assertEquals(0.0, Tle.decimalPoint(" 00000+0")!!, 1e-18)
        assertEquals(0.28098e-4, Tle.decimalPoint(" 28098-4")!!, 1e-18)
        assertEquals(-0.12345e-3, Tle.decimalPoint("-12345-3")!!, 1e-18)
        assertEquals(0.54127e-5, Tle.decimalPoint(" 54127-5")!!, 1e-18)
        // A blank field is a legitimate zero, not a parse failure.
        assertEquals(0.0, Tle.decimalPoint("        ")!!, 1e-18)
    }

    @Test fun mismatchedCatalogueNumbersAreRejected() {
        // Two lines from different satellites would otherwise propagate silently as one object.
        val wrongLine2 = "2 48274  51.6328   9.8801 0007568  46.9314 313.2307 15.49444701580849"
        assertNull(Tle.parse(issL1, wrongLine2))
    }

    @Test fun malformedInputReturnsNullRatherThanHalfAnElementSet() {
        assertNull(Tle.parse("", ""))
        assertNull(Tle.parse(issL1, "2 25544  51.6328"))          // truncated line 2
        assertNull(Tle.parse(issL2, issL1))                        // lines swapped
        assertNull(Tle.parse("nonsense line one padded out to the required width or so", issL2))
    }

    @Test fun theTwoDigitEpochYearFollowsTheFormatsOwnPivot() {
        // 57-99 is the twentieth century, 00-56 the twenty-first.
        val y2026 = Tle.epochToJulian(26, 1.0)
        val y1998 = Tle.epochToJulian(98, 1.0)
        assertTrue("2026 must be later than 1998", y2026 > y1998)
        // 1 January 2000, 00:00 UT is JD 2451544.5; day-of-year 1.0 is that instant.
        assertEquals(2451544.5, Tle.epochToJulian(0, 1.0), 0.5)
        // Sanity: the ISS epoch parses to a Julian date in the right era.
        val iss = Tle.parse(issL1, issL2)!!
        assertTrue("epoch JD looked wrong: ${iss.epochJulian}", iss.epochJulian in 2461000.0..2462000.0)
    }

    @Test fun parsesAThreeLineCatalogueBlock() {
        val block = """
            $issName
            $issL1
            $issL2
            CSS (TIANHE)
            1 48274U 21035A   26224.98627525  .00000101  00000+0  54127-5 0  9991
            2 48274  41.4709 337.2096 0001079 250.4973 109.5748 15.58975796302033
        """.trimIndent()
        val parsed = Tle.parseBlock(block)
        assertEquals(2, parsed.size)
        assertEquals(25544, parsed[0].noradId)
        assertEquals("ISS (ZARYA)", parsed[0].name)
        assertEquals(48274, parsed[1].noradId)
        assertEquals("CSS (TIANHE)", parsed[1].name)
    }

    @Test fun parsesBarePairsWithNoNameLineAndSkipsJunk() {
        val block = """
            $issL1
            $issL2
        """.trimIndent()
        assertEquals(1, Tle.parseBlock(block).size)
        // A stray line between entries must not desynchronise the walk.
        assertEquals(0, Tle.parseBlock("just some text\nand more text").size)
        assertEquals(0, Tle.parseBlock("").size)
    }
}
