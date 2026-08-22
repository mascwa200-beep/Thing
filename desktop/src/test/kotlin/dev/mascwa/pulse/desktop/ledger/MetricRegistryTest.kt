package dev.mascwa.pulse.desktop.ledger

import dev.mascwa.pulse.data.markets.Quote
import dev.mascwa.pulse.data.orbital.MoonInfo
import dev.mascwa.pulse.data.orbital.OrbitalData
import dev.mascwa.pulse.data.radar.Contact
import dev.mascwa.pulse.data.radar.ContactKind
import dev.mascwa.pulse.data.radar.RadarData
import dev.mascwa.pulse.data.safety.SafetyResult
import dev.mascwa.pulse.data.weather.AirQuality
import dev.mascwa.pulse.data.weather.CurrentWeather
import dev.mascwa.pulse.data.weather.WeatherData
import dev.mascwa.pulse.data.space.SpaceWeather
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricRegistryTest {

    // ---------------------------------------------------------------- fully-populated models

    private fun weather() = WeatherData(
        locationName = "Somewhere",
        latitude = 51.5,
        longitude = -0.12,
        timezone = "UTC",
        current = CurrentWeather(
            temperature = 62.0, // deliberately a Fahrenheit-looking display value
            apparentTemperature = 60.0,
            humidity = 71.0,
            weatherCode = 3,
            isDay = true,
            precipitation = 0.0,
            windSpeed = 9.0,
            windDirection = 210.0,
            pressure = 1013.2,
            cloudCover = 44.0,
            surfacePressure = 1011.8,
            temperatureC = 16.7,
            dewPointC = 11.2,
            windKmh = 14.5,
            gustKmh = 31.0,
            visibilityMetres = 24_000.0,
        ),
        hourly = emptyList(),
        daily = emptyList(),
        airQuality = AirQuality(
            europeanAqi = 31.0,
            usAqi = 44.0,
            pm10 = 18.0,
            pm25 = 9.4,
            carbonMonoxide = 190.0,
            nitrogenDioxide = 22.0,
            sulphurDioxide = 3.1,
            ozone = 61.0,
        ),
        tempUnitSymbol = "°F",
        windUnitSymbol = "mph",
        precipUnitSymbol = "in",
    )

    private fun space() = SpaceWeather(
        kp = 3.33,
        solarWindSpeed = 412.0,
        bz = -4.2,
        xrayFlux = 1.4e-7,
        protonFlux = 0.31,
        f107 = 136.0,
        auroraProbabilityPct = 12,
    )

    private fun quotes() = MetricRegistry.INSTRUMENTS.mapIndexed { i, item ->
        Quote(id = item.id, label = item.label, type = item.type.name, price = 100.0 + i, changePercent = -1.5 + i * 0.4)
    }

    private fun radar() = RadarData(
        contacts = listOf(
            Contact(id = "a", label = "AAA1", latitude = 51.4, longitude = -0.1, kind = ContactKind.AIRCRAFT.name, altitudeM = 9000.0),
            Contact(id = "b", label = "BBB2", latitude = 51.6, longitude = -0.2, kind = ContactKind.AIRCRAFT.name, altitudeM = 11000.0),
            Contact(id = "q", label = "M4.2", latitude = 40.0, longitude = 20.0, kind = ContactKind.QUAKE.name, magnitude = 4.2),
        ),
    )

    private fun safety() = SafetyResult(incidents = emptyList())

    private fun orbital() = OrbitalData(moon = MoonInfo("Waxing", 0.42, "🌔"), neoHazardousCount = 2)

    private fun everythingExtracted(): Set<String> =
        (
            MetricRegistry.fromWeather(weather()) +
                MetricRegistry.fromSpace(space()) +
                MetricRegistry.fromQuotes(quotes()) +
                MetricRegistry.fromRadar(radar()) +
                MetricRegistry.fromSafety(safety()) +
                MetricRegistry.fromOrbital(orbital())
            ).map { it.first }.toSet()

    // ---------------------------------------------------------------- the guard

    /**
     * ⚠️ THE GUARD THIS FILE EXISTS FOR. A registry is exactly the shape that rots: something gets
     * declared, nothing ever feeds it, and the wall shows a metric that is permanently "not enough
     * history yet" with no way to tell that from a genuinely young one.
     *
     * This repo has shipped the declared-but-never-fed defect at least six times — `tempoNudge`,
     * `windKmh`, `mastery()`, `savedAtMs` and three widget enum constants. Feeding every extractor a
     * fully populated model and comparing both directions is what makes it impossible here.
     */
    @Test
    fun everyDeclaredMetricIsActuallyProducedBySomeExtractor() {
        val declared = MetricRegistry.ALL.map { it.id }.toSet()
        val extracted = everythingExtracted()

        assertEquals("declared but never fed", emptySet<String>(), declared - extracted)
        assertEquals("produced but never declared", emptySet<String>(), extracted - declared)
    }

    @Test
    fun metricIdsAreUniqueAndLegalAsALedgerKey() {
        val ids = MetricRegistry.ALL.map { it.id }
        assertEquals("two metrics sharing an id would share a series", ids.size, ids.toSet().size)
        // Throws if the id is not in the ledger's alphabet — which is what stops a silent merge.
        ids.forEach { WorldLedger.requireSafeId(it) }
        MetricRegistry.ALL.forEach { WorldLedger.requireSafeId(it.key("n515-w1")) }
    }

    @Test
    fun everyDomainHasAtLeastOneMetricAndAWorkableCadence() {
        MetricRegistry.Domain.entries.forEach { d ->
            assertTrue("$d declares no metrics, so its cadence is dead code", MetricRegistry.of(d).isNotEmpty())
            assertTrue("$d must be asked no more than four times an hour", d.cadenceMs >= 15 * 60_000L)
        }
    }

    // ---------------------------------------------------------------- keys and places

    /**
     * ⚠️ Move city and the weather outside is a different measurement. Without the place in the key,
     * the old city's readings silently become part of the new city's baseline.
     */
    @Test
    fun aLocationBoundMetricCarriesThePlaceInItsKey() {
        val temp = MetricRegistry.BY_ID.getValue("weather.temp")
        val kp = MetricRegistry.BY_ID.getValue("space.kp")

        assertEquals("weather.temp.n515-w1", temp.key("n515-w1"))
        assertEquals("space.kp", kp.key("n515-w1"))
        assertEquals("with no place known, the base id is used", "weather.temp", temp.key(null))
    }

    @Test
    fun placeKeysAreStableAtAboutTenKilometres() {
        // A geolocation wobble of a few hundred metres must not start a new series.
        assertEquals(MetricRegistry.placeKey(51.5074, -0.1278), MetricRegistry.placeKey(51.5091, -0.1301))
        // A different city must.
        assertTrue(MetricRegistry.placeKey(51.5, -0.12) != MetricRegistry.placeKey(48.85, 2.35))
        // Hemispheres are distinguished rather than collapsed onto their absolute value.
        assertTrue(MetricRegistry.placeKey(33.9, 18.4) != MetricRegistry.placeKey(-33.9, 18.4))
        assertTrue(MetricRegistry.placeKey(33.9, 18.4) != MetricRegistry.placeKey(33.9, -18.4))
    }

    @Test
    fun aStoredKeyResolvesBackToItsSpec() {
        assertEquals("weather.temp", MetricRegistry.specForKey("weather.temp.n515-w1")?.id)
        assertEquals("space.kp", MetricRegistry.specForKey("space.kp")?.id)
        assertNull("an unknown key resolves to nothing rather than a guess", MetricRegistry.specForKey("nonsense.x"))
    }

    // ---------------------------------------------------------------- what is and is not scored

    /**
     * ⚠️ A price level is non-stationary — an index drifts upward for years, so "highest on record" is
     * true most months and carries no information. The level is kept for the chart; the daily move is
     * what gets ranked.
     */
    @Test
    fun priceLevelsAreRecordedButNeverRanked() {
        val price = MetricRegistry.BY_ID.getValue("market.spx.price")
        val move = MetricRegistry.BY_ID.getValue("market.spx.move")

        assertTrue("a drifting level on the wall would be a permanent false record", !price.scored)
        assertTrue(move.scored)
        assertTrue(
            "every unscored metric today is a price level",
            MetricRegistry.ALL.filter { !it.scored }.all { it.id.endsWith(".price") },
        )
    }

    @Test
    fun onlyUnitStableWeatherFieldsAreRecorded() {
        // The model above is deliberately built with imperial display values and metric canonical ones.
        val byId = MetricRegistry.fromWeather(weather()).toMap()

        assertEquals("must read the canonical field, not the displayed one", 16.7, byId.getValue("weather.temp"), 1e-9)
        assertEquals(14.5, byId.getValue("weather.wind"), 1e-9)
        assertEquals(24_000.0, byId.getValue("weather.visibility"), 1e-9)
        assertTrue(
            "precipitation is unit-toggleable and mostly zero — deliberately absent",
            byId.keys.none { it.contains("precip") || it.contains("rain") || it.contains("snow") },
        )
    }

    // ---------------------------------------------------------------- missing data

    @Test
    fun anAbsentFieldProducesNoRowRatherThanAZero() {
        val bare = weather().copy(current = weather().current!!.copy(gustKmh = null), airQuality = null)
        val ids = MetricRegistry.fromWeather(bare).map { it.first }

        assertTrue("a null gust is not a gust of zero", "weather.gust" !in ids)
        assertTrue("and no air quality means no air rows at all", ids.none { it.startsWith("air.") })
        assertTrue("what is present still comes through", "weather.temp" in ids)
    }

    @Test
    fun noCurrentReadingProducesNothingAtAll() {
        assertTrue(MetricRegistry.fromWeather(weather().copy(current = null)).isEmpty())
    }

    @Test
    fun aQuietSkyStillRecordsTheZero() {
        val empty = MetricRegistry.fromRadar(RadarData(contacts = emptyList())).toMap()
        assertEquals("no aircraft overhead is a real reading", 0.0, empty.getValue("aviation.aircraft"), 1e-9)
        assertTrue("but the mean altitude of nothing is not", "aviation.altitude" !in empty)
    }

    @Test
    fun onlyAircraftCountTowardsTheAviationMetrics() {
        val m = MetricRegistry.fromRadar(radar()).toMap()
        assertEquals("the earthquake contact is not an aeroplane", 2.0, m.getValue("aviation.aircraft"), 1e-9)
        assertEquals(10_000.0, m.getValue("aviation.altitude"), 1e-9)
    }

    @Test
    fun instrumentIdsBecomeLegalSlugs() {
        assertEquals("spx", MetricRegistry.slugOf("^spx"))
        assertEquals("cb-f", MetricRegistry.slugOf("cb.f"))
        assertEquals("eurusd", MetricRegistry.slugOf("eurusd"))
        MetricRegistry.INSTRUMENTS.forEach { WorldLedger.requireSafeId("market.${MetricRegistry.slugOf(it.id)}.move") }
    }

    @Test
    fun theBasketStaysSmallEnoughToBePolite() {
        // Ten instruments hourly is 240 requests a day to one provider that has already banned this
        // repo's IP once. Growing the basket without lowering the cadence is a decision, not a detail.
        val perDay = MetricRegistry.INSTRUMENTS.size *
            (24 * 60 * 60_000L / MetricRegistry.Domain.MARKETS.cadenceMs)
        assertTrue("$perDay market requests a day is too many", perDay <= 300)
    }

    @Test
    fun theRecordedVolumeStaysInsideTheDiskBudget() {
        // Roughly 20 bytes a record; the owner's ceiling is 100 MB with a year kept at full resolution.
        val perDay = MetricRegistry.Domain.entries.sumOf { d ->
            MetricRegistry.of(d).size * (24 * 60 * 60_000L / d.cadenceMs)
        }
        val bytesPerYear = perDay * 20 * 365
        assertTrue(
            "$perDay records a day is ${bytesPerYear / 1_000_000} MB a year — past the 100 MB ceiling",
            bytesPerYear < 60_000_000,
        )
    }

    @Test
    fun everyMetricNamesAScreenToOpen() {
        MetricRegistry.ALL.forEach {
            assertNotNull("${it.id} has nowhere to click through to", it.domain.screen)
        }
    }
}
