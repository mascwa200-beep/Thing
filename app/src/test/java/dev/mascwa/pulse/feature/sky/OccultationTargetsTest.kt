package dev.mascwa.pulse.feature.sky

import dev.mascwa.pulse.core.telemetry.Ephemeris
import dev.mascwa.pulse.core.telemetry.StarNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The stars the occultation search looks for must exist in the catalogue it looks in.
 *
 * ⚠️ **This is the one linkage nothing else can catch.** `OrbitalViewModel` names its targets as
 * strings and resolves them against the bundled catalogue at runtime, and a name that stops matching
 * — because a proper-name key was retyped, because the catalogue was rebuilt with a different
 * designation, because somebody wrote "Aldeberan" — produces no error, no crash and no log line. It
 * produces a tab that quietly never mentions that star again, which looks exactly like a quiet sky.
 *
 * It also checks the choice itself. The Moon's path is confined to about five degrees either side of
 * the ecliptic, so it can only ever occult stars inside that band; a target outside it would be a
 * target that never produces an event, which is a different kind of silence and just as invisible.
 */
class OccultationTargetsTest {

    private val asset = File("../core/sky/src/main/assets/sky/stars.tsv")

    private class Row(
        val ra: Double,
        val dec: Double,
        val mag: Double,
        val bayer: String,
        val flamsteed: String,
        val constellation: String,
    ) {
        val name: String? get() = StarNames.label(bayer, flamsteed, constellation)
    }

    private fun load(): List<Row> {
        assertTrue("the catalogue is missing: ${asset.absolutePath}", asset.isFile)
        return asset.readLines()
            .filterNot { it.startsWith("#") || it.isBlank() }
            .map { line ->
                val f = line.split('\t')
                assertEquals("wrong column count in: $line", 9, f.size)
                Row(f[0].toDouble(), f[1].toDouble(), f[2].toDouble(), f[4], f[5], f[6])
            }
    }

    @Test
    fun `every star the occultation search asks for is in the bundled catalogue`() {
        val byName = load().mapNotNull { r -> r.name?.let { it to r } }.toMap()
        for (name in OrbitalViewModel.OCCULTABLE_STARS) {
            assertNotNull(
                "'$name' resolves to nothing in the catalogue, so the tab would silently never " +
                    "mention it — check StarNames and the bundled asset",
                byName[name],
            )
        }
    }

    /**
     * ⚠️ The Moon never leaves a narrow band, so a target outside it can never be occulted.
     *
     * Its orbit is inclined about 5.1 degrees to the ecliptic, so its ecliptic latitude stays inside
     * roughly ±5.3 degrees once the Earth's own small wobble is allowed for. Adding the Moon's own
     * radius and a margin gives 6.5 as the bound a real target has to satisfy.
     */
    @Test
    fun `every target lies inside the band the Moon can actually reach`() {
        val byName = load().mapNotNull { r -> r.name?.let { it to r } }.toMap()
        // Ecliptic latitude is not on the catalogue row, so it is derived the same way everything
        // else here derives one -- through the shipped ephemeris, at a fixed instant.
        val instant = 1781481600000L
        val eps = Ephemeris.trueObliquityDeg(instant) * Math.PI / 180.0
        for (name in OrbitalViewModel.OCCULTABLE_STARS) {
            val r = byName.getValue(name)
            val ra = r.ra * Math.PI / 180.0
            val dec = r.dec * Math.PI / 180.0
            val beta = Math.asin(
                Math.sin(dec) * Math.cos(eps) - Math.cos(dec) * Math.sin(eps) * Math.sin(ra),
            ) * 180.0 / Math.PI
            assertTrue(
                "$name sits $beta degrees off the ecliptic, where the Moon cannot reach it",
                Math.abs(beta) < 6.5,
            )
        }
    }

    /**
     * And bright enough to be worth telling somebody about. A fifth-magnitude star disappearing
     * behind a lit Moon is not an event anybody can see; the point of this list is that these are.
     */
    @Test
    fun `every target is bright enough to watch disappear`() {
        val byName = load().mapNotNull { r -> r.name?.let { it to r } }.toMap()
        for (name in OrbitalViewModel.OCCULTABLE_STARS) {
            val r = byName.getValue(name)
            assertTrue("$name is magnitude ${r.mag}, too faint to bother with", r.mag < 3.0)
        }
    }
}
