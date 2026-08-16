package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeodesyTest {

    // A few well-known fixes to work against.
    private val londonLat = 51.5074; private val londonLon = -0.1278
    private val parisLat = 48.8566; private val parisLon = 2.3522

    @Test fun distanceMatchesTheKnownLondonParisLeg() {
        val d = Geodesy.distanceMeters(londonLat, londonLon, parisLat, parisLon)
        // Published great-circle distance is ~343.5 km; allow 1 km for the spherical approximation.
        assertEquals(343_500.0, d, 1_000.0)
        // Symmetric, and zero for a degenerate leg.
        assertEquals(d, Geodesy.distanceMeters(parisLat, parisLon, londonLat, londonLon), 0.001)
        assertEquals(0.0, Geodesy.distanceMeters(londonLat, londonLon, londonLat, londonLon), 0.001)
    }

    @Test fun bearingsAreTrueNorthAndTheFinalBearingDiffers() {
        val initial = Geodesy.initialBearing(londonLat, londonLon, parisLat, parisLon)
        assertEquals(148.0, initial, 1.0) // London -> Paris runs roughly SSE
        assertEquals("SSE", Geodesy.cardinal(initial))
        // A great circle curves, so arrival bearing is not the departure bearing.
        val fin = Geodesy.finalBearing(londonLat, londonLon, parisLat, parisLon)
        assertTrue("final bearing should differ from initial", kotlin.math.abs(fin - initial) > 0.5)
        // Due north is exactly 0/360 either way.
        assertEquals(0.0, Geodesy.initialBearing(0.0, 0.0, 10.0, 0.0), 1e-9)
        assertEquals(90.0, Geodesy.initialBearing(0.0, 0.0, 0.0, 10.0), 1e-9)
    }

    @Test fun destinationInvertsDistanceAndBearing() {
        val (lat, lon) = Geodesy.destination(londonLat, londonLon, 148.0, 343_500.0)
        // Walking the measured bearing/distance must land back within a kilometre of Paris.
        assertEquals(0.0, Geodesy.distanceMeters(lat, lon, parisLat, parisLon), 12_000.0)
        // And the round trip is exact: go out and come back on the reciprocal.
        val out = Geodesy.destination(10.0, 20.0, 33.0, 250_000.0)
        val back = Geodesy.destination(
            out.first, out.second,
            Geodesy.finalBearing(10.0, 20.0, out.first, out.second) - 180.0,
            250_000.0,
        )
        assertEquals(10.0, back.first, 1e-6)
        assertEquals(20.0, back.second, 1e-6)
    }

    @Test fun destinationWrapsTheDateLineInsteadOfRunningOffTheEnd() {
        val (_, lon) = Geodesy.destination(0.0, 179.0, 90.0, 400_000.0) // due east over the line
        assertTrue("longitude must stay in -180..180 but was $lon", lon in -180.0..180.0)
        assertTrue("should have wrapped to a negative longitude, got $lon", lon < 0)
    }

    @Test fun midpointSitsHalfwayAlongTheLeg() {
        val (mLat, mLon) = Geodesy.midpoint(londonLat, londonLon, parisLat, parisLon)
        val toStart = Geodesy.distanceMeters(mLat, mLon, londonLat, londonLon)
        val toEnd = Geodesy.distanceMeters(mLat, mLon, parisLat, parisLon)
        assertEquals(toStart, toEnd, 1.0)
    }

    @Test fun crossTrackIsSignedAndAlongTrackAdvances() {
        // Track due east along the equator; a point north of it is to the LEFT, so negative.
        val north = Geodesy.crossTrackMeters(1.0, 5.0, 0.0, 0.0, 0.0, 10.0)
        val south = Geodesy.crossTrackMeters(-1.0, 5.0, 0.0, 0.0, 0.0, 10.0)
        assertTrue("north of an eastbound track is left/negative, got $north", north < 0)
        assertTrue("south of an eastbound track is right/positive, got $south", south > 0)
        assertEquals(kotlin.math.abs(north), kotlin.math.abs(south), 1.0)
        // A point exactly on the track has no cross-track error.
        assertEquals(0.0, Geodesy.crossTrackMeters(0.0, 5.0, 0.0, 0.0, 0.0, 10.0), 1.0)
        // Along-track grows as the point moves down the leg.
        val near = Geodesy.alongTrackMeters(0.0, 2.0, 0.0, 0.0, 0.0, 10.0)
        val far = Geodesy.alongTrackMeters(0.0, 8.0, 0.0, 0.0, 0.0, 10.0)
        assertTrue("along-track should increase along the leg", far > near)
    }

    @Test fun cardinalCoversTheFullCircleIncludingTheWrap() {
        assertEquals("N", Geodesy.cardinal(0.0))
        assertEquals("N", Geodesy.cardinal(360.0))
        assertEquals("N", Geodesy.cardinal(-1.0))     // wraps rather than throwing
        assertEquals("E", Geodesy.cardinal(90.0))
        assertEquals("S", Geodesy.cardinal(180.0))
        assertEquals("W", Geodesy.cardinal(270.0))
        assertEquals("NW", Geodesy.cardinal(315.0))
    }

    @Test fun formattingIsLocaleStableAndCarriesCorrectly() {
        val previous = java.util.Locale.getDefault()
        try {
            // A comma-decimal locale must not produce "1,2 km" in a machine-read string.
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("1.2 km", Geodesy.formatDistance(1200.0))
            assertEquals("840 m", Geodesy.formatDistance(840.0))
            assertEquals("1.2 mi", Geodesy.formatDistance(1931.0, metric = false))
        } finally {
            java.util.Locale.setDefault(previous)
        }
        // Seconds that round to 60 carry into minutes rather than printing 60".
        assertEquals("1°00'00\"N 0°00'00\"E", Geodesy.formatDms(0.99999999, 0.0))
        assertTrue(Geodesy.formatDms(-33.9, 151.2).startsWith("33°"))
        assertTrue(Geodesy.formatDms(-33.9, 151.2).contains("S"))
    }

    /**
     * The guard on the SOS message.
     *
     * These coordinates are texted to emergency contacts. Under a comma-decimal locale the default
     * formatter renders them "48,85661, 2,35222" — four comma-separated numbers a rescuer cannot tell
     * apart, in the one message that has to be read correctly the first time.
     */
    @Test fun decimalCoordinatesKeepADotWhateverTheReadersLanguage() {
        assertEquals("48.85661, 2.35222", Geodesy.formatDecimal(48.85661, 2.35222))
        assertEquals("-33.90000, 151.20000", Geodesy.formatDecimal(-33.9, 151.2))

        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val s = Geodesy.formatDecimal(48.85661, 2.35222)
            assertEquals("48.85661, 2.35222", s)
            // Exactly one comma: the separator between the two numbers, and none inside them.
            assertEquals(1, s.count { it == ',' })
        } finally {
            java.util.Locale.setDefault(previous)
        }

        // %.0f of 48.85661 rounds to 49, of 2.35222 to 2.
        assertEquals("49, 2", Geodesy.formatDecimal(48.85661, 2.35222, decimals = 0))
    }

    // ---- UTM / MGRS ----

    @Test fun utmZonesIncludeTheNorwayAndSvalbardExceptions() {
        assertEquals(31, Geodesy.utmZone(0.0, 0.0))
        assertEquals(18, Geodesy.utmZone(38.8977, -77.0365))
        // South-west Norway: zone 32 is widened west into what would be 31.
        assertEquals(32, Geodesy.utmZone(60.0, 5.0))
        assertEquals(31, Geodesy.utmZone(50.0, 5.0)) // same longitude, ordinary latitude
        // Svalbard: 33 covers 9..21E.
        assertEquals(33, Geodesy.utmZone(78.0, 15.0))
        assertEquals(35, Geodesy.utmZone(78.0, 25.0))
    }

    @Test fun utmMatchesThePublishedOriginValueAndRoundTrips() {
        val origin = Geodesy.toUtm(0.0, 0.0)
        assertNotNull(origin)
        assertEquals(31, origin!!.zone)
        assertTrue(origin.northernHemisphere)
        // Published UTM of 0N 0E is 166021.44 E, 0.00 N in zone 31.
        assertEquals(166_021.44, origin.easting, 0.5)
        assertEquals(0.0, origin.northing, 0.5)

        // Round-trip a spread of points to sub-metre accuracy.
        for ((lat, lon) in listOf(
            51.5074 to -0.1278, 38.8977 to -77.0365, -33.8688 to 151.2093,
            -22.9068 to -43.1729, 64.1466 to -21.9426, 1.3521 to 103.8198,
        )) {
            val utm = Geodesy.toUtm(lat, lon)
            assertNotNull("no UTM for $lat,$lon", utm)
            val (backLat, backLon) = Geodesy.fromUtm(utm!!)
            assertEquals("lat round-trip at $lat,$lon", lat, backLat, 1e-6)
            assertEquals("lon round-trip at $lat,$lon", lon, backLon, 1e-6)
        }
    }

    @Test fun utmIsNullOutsideTheProjectionBand() {
        assertNull(Geodesy.toUtm(85.0, 10.0))
        assertNull(Geodesy.toUtm(-81.0, 10.0))
        assertNull(Geodesy.mgrsBand(85.0))
        assertNull(Geodesy.toMgrs(-81.0, 10.0))
    }

    @Test fun mgrsBandLettersSkipIAndO() {
        assertEquals('N', Geodesy.mgrsBand(0.0))
        assertEquals('S', Geodesy.mgrsBand(38.8977))
        assertEquals('C', Geodesy.mgrsBand(-79.0))
        assertEquals('X', Geodesy.mgrsBand(80.0))  // the 12-degree top band
        assertEquals('X', Geodesy.mgrsBand(83.9))
        val bands = (-79..83 step 2).mapNotNull { Geodesy.mgrsBand(it.toDouble()) }.toSet()
        assertTrue("MGRS bands never use I or O", 'I' !in bands && 'O' !in bands)
    }

    @Test fun mgrsMatchesThePublishedOriginReference() {
        // 0N 0E is the canonical worked example: zone 31, band N, 100 km square AA.
        assertEquals("31NAA6602100000", Geodesy.toMgrs(0.0, 0.0))
    }

    @Test fun mgrsPicksTheRightHundredKilometreSquare() {
        // The White House sits in 18S UJ — the column set rotates by zone and the row alphabet is
        // offset by five on even zones, so this pins both rules at once.
        val mgrs = Geodesy.toMgrs(38.8977, -77.0365)
        assertNotNull(mgrs)
        assertTrue("expected an 18SUJ reference, got $mgrs", mgrs!!.startsWith("18SUJ"))
        assertEquals(15, mgrs.length) // zone(2) + band(1) + square(2) + 5 + 5
    }

    @Test fun mgrsPrecisionTruncatesRatherThanRounding() {
        val full = Geodesy.toMgrs(51.5074, -0.1278, digits = 5)!!
        val tenM = Geodesy.toMgrs(51.5074, -0.1278, digits = 4)!!
        val tenKm = Geodesy.toMgrs(51.5074, -0.1278, digits = 1)!!
        assertEquals(15, full.length)
        assertEquals(13, tenM.length)
        assertEquals(7, tenKm.length)
        // Same square in every precision, and the coarser strings are prefixes of the finer digits.
        assertEquals(full.take(5), tenM.take(5))
        assertEquals(full.take(5), tenKm.take(5))
        assertEquals(full.substring(5, 9), tenM.substring(5, 9))
        // Out-of-range digit counts clamp instead of throwing.
        assertEquals(15, Geodesy.toMgrs(51.5074, -0.1278, digits = 99)!!.length)
        assertEquals(7, Geodesy.toMgrs(51.5074, -0.1278, digits = 0)!!.length)
    }

    @Test fun southernHemisphereUsesTheFalseNorthing() {
        val sydney = Geodesy.toUtm(-33.8688, 151.2093)!!
        assertTrue(!sydney.northernHemisphere)
        // Southern northings are measured from 10 000 km, so they stay large and positive.
        assertTrue("southern northing should use the false origin", sydney.northing > 6_000_000.0)
        assertTrue(sydney.format().contains("56S"))
    }
}
