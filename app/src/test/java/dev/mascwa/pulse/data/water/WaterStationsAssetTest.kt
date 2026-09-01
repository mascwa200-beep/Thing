package dev.mascwa.pulse.data.water

import dev.mascwa.pulse.core.telemetry.WaterStations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bundled NOAA station list, checked against itself and against the core that reads it.
 *
 * ⚠️ **Every guard here exists because its failure would be silent.** The list is three and a half
 * thousand rows of numbers nobody proofreads: a swapped latitude and longitude column parses
 * perfectly and puts every American station in Kazakhstan, a station whose product letter is wrong
 * asks NOAA for data it does not publish and draws a permanently empty block, and a list rebuilt
 * with the level stations dropped leaves the whole Great Lakes with no reading at all. None of that
 * fails a compile or looks wrong in a diff.
 *
 * ⚠️ The pure core cannot do this half: it has no way to read an asset, and this module cannot do
 * the core's half either, because the arithmetic belongs with the rule. Both are needed.
 */
class WaterStationsAssetTest {

    private val asset = File("src/main/assets/water/stations.tsv")

    /** Where NOAA publishes water levels that are not on the International Great Lakes Datum. */
    private val OUTSIDE_IGLD = listOf(", PR", ", TX", ", LA")

    private fun load(): List<WaterStations.Station> {
        assertTrue("the station list is missing: ${asset.absolutePath}", asset.isFile)
        val lines = asset.readLines().filter { it.isNotBlank() }
        val parsed = lines.mapNotNull { WaterStations.parse(it) }
        assertEquals(
            "every line of the bundled list must parse — a row the core refuses is a station the " +
                "app can never reach, and nothing else would ever say so",
            lines.size,
            parsed.size,
        )
        return parsed
    }

    @Test
    fun `the list is there and every row of it reads`() {
        val stations = load()
        // A floor rather than an exact count: NOAA opens and closes stations, and a rebuild that
        // gained a few must not fail a build. Losing most of them is the failure worth catching.
        assertTrue("only ${stations.size} stations — the list looks truncated", stations.size > 3_000)
    }

    @Test
    fun `both products are present, and the lakes are not the rounding error`() {
        val stations = load()
        val tide = stations.count { it.kind == WaterStations.Kind.TIDE }
        val level = stations.count { it.kind == WaterStations.Kind.LEVEL }
        // ⚠️ The level stations are a small minority of the file and the entire reason the block
        // works where the owner lives. A rebuild that quietly kept only the tide stations would
        // still pass every other check here.
        //
        // ⚠️ The floor is well under the 52 actually bundled, deliberately: NOAA opens and closes
        // gauges, and a build must not fail because one went offline. Losing the lot — which is
        // what a builder change would do — is the failure worth catching, and that goes to zero.
        assertTrue("no tide stations at all", tide > 3_000)
        assertTrue("only $level water-level stations — the Great Lakes would go dark", level >= 40)
    }

    @Test
    fun `every station is somewhere on Earth, and somewhere NOAA operates`() {
        for (s in load()) {
            assertTrue("${s.id} latitude ${s.lat}", s.lat >= -90.0 && s.lat <= 90.0)
            assertTrue("${s.id} longitude ${s.lon}", s.lon >= -180.0 && s.lon <= 180.0)
            // A latitude/longitude swap is the classic column mistake and would leave every
            // American station with a latitude near -80, which is inside the valid range and
            // nowhere near anything. NOAA's reach is the Americas, Hawaii and the Pacific
            // territories, so nothing should sit at a latitude further south than Antarctic waters.
            assertTrue("${s.id} at ${s.lat},${s.lon} is not anywhere NOAA measures", s.lat > -60.0)
            assertTrue("${s.id} has no name", s.name.isNotEmpty())
        }
    }

    @Test
    fun `every level station is on the datum the app asks for`() {
        // ⚠️ The repository requests IGLD for every LEVEL station, which is correct for the Great
        // Lakes–St. Lawrence system and meaningless anywhere else. NOAA publishes water levels at
        // twelve stations outside that system — Mississippi river stages in Louisiana, Laguna Madre
        // gauges in Texas, and six in Puerto Rico — and they are deliberately NOT bundled.
        //
        // Dropping them costs no coverage, and that was measured rather than assumed: every one of
        // the twelve has a tide station within 37 km, and four of the six Puerto Rico ones are the
        // SAME station publishing both products, at 0.0 km. A tide prediction is the better reading
        // in all twelve places, and `nearest` finds it well inside TIDE_REACH_KM.
        //
        // This guard is the shape of that decision: a rebuild that let them back in would ask for a
        // datum they do not have, and the block would fail in exactly one part of the country with
        // nothing on screen to say why.
        val outside = load()
            .filter { it.kind == WaterStations.Kind.LEVEL }
            .filter { s -> OUTSIDE_IGLD.any { s.name.endsWith(it) } }
        assertTrue(
            "water levels outside the Great Lakes system, which IGLD does not describe: " +
                outside.joinToString { "${it.id} ${it.name}" },
            outside.isEmpty(),
        )
    }

    @Test
    fun `station ids are unique`() {
        val stations = load()
        val ids = stations.map { it.id }.toSet()
        assertEquals("two rows share an id", stations.size, ids.size)
    }

    @Test
    fun `where the owner lives, the answer is the lake and not the tide`() {
        // ⚠️ THE case this whole block was reshaped around, and the one a reader will want to
        // confirm. Holland, Michigan: the nearest tide-prediction station is 879 km away in
        // Washington DC, because the Great Lakes are not tidal — a TIDES block would have sat
        // permanently empty. The nearest water-level gauge is 8.6 km away and is named for the town.
        val near = WaterStations.nearest(load(), 42.7875, -86.1089)
        assertNotNull("nothing in range of Holland, MI", near)
        assertEquals(WaterStations.Kind.LEVEL, near!!.station.kind)
        assertEquals("9087031", near.station.id)
        assertTrue("the nearest gauge is ${near.km} km away", near.km < 10.0)
    }

    @Test
    fun `on a coast the answer is the tide`() {
        val stations = load()
        // Battery Park, Manhattan. Tidal, densely gauged, and about as far from the Great Lakes
        // case as the same list gets.
        val ny = WaterStations.nearest(stations, 40.7003, -74.0142)
        assertNotNull(ny)
        assertEquals(WaterStations.Kind.TIDE, ny!!.station.kind)
        assertTrue("the nearest gauge is ${ny.km} km away", ny.km < 20.0)
    }

    @Test
    fun `far inland there is no water reading and the block is absent`() {
        // Wichita, Kansas — a thousand kilometres from either coast and not on a Great Lake.
        // ⚠️ This must return null rather than the nearest thing it can find: a block reporting
        // the tide several hundred kilometres away would be worse than no block.
        assertNull(WaterStations.nearest(load(), 37.6889, -97.3361))
    }

    @Test
    fun `the file is a table and not prose`() {
        // Guards the format itself: exactly five tab-separated fields, no header row, no comments.
        // The core's parse tolerates a bad line by design, so without this a builder that started
        // emitting a header or a sixth column would lose rows silently.
        for (line in asset.readLines().filter { it.isNotBlank() }) {
            val f = line.split('\t')
            assertEquals("wrong column count: $line", 5, f.size)
            assertTrue("a name carries a tab or is empty: $line", f[4].isNotBlank())
        }
    }
}
