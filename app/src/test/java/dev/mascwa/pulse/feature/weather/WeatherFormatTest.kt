package dev.mascwa.pulse.feature.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/**
 * Pins the two things that CHANGED when this moved off `SimpleDateFormat`, and the many that did not.
 *
 * ⚠️ Every test sets and restores the zone and the locale. Leaving either at whatever the build
 * machine happens to use is how a date test passes everywhere except the one place it matters.
 */
class WeatherFormatTest {

    private fun <T> at(zone: String, locale: Locale, body: () -> T): T {
        val z = TimeZone.getDefault()
        val l = Locale.getDefault()
        return try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            Locale.setDefault(locale)
            body()
        } finally {
            TimeZone.setDefault(z)
            Locale.setDefault(l)
        }
    }

    @Test
    fun `an hourly stamp round-trips to the same clock face it arrived as`() {
        // Open-Meteo returns wall-clock time local to the place asked about, and the label a reader
        // wants is that same face. Parsing in the device zone and formatting back in it round-trips.
        at("America/New_York", Locale.US) {
            assertEquals("14:00", WeatherFormat.hourLabel("2026-08-28T14:00", use24h = true))
            assertEquals("2 PM", WeatherFormat.hourLabel("2026-08-28T14:00", use24h = false))
        }
    }

    @Test
    fun `the day label follows the reader's language`() {
        // The display formatters are cached per (pattern, locale) rather than held as constants
        // precisely so this keeps working after a locale change without a process restart.
        at("UTC", Locale.US) { assertEquals("Fri", WeatherFormat.shortDayLabel("2026-03-27")) }
        at("UTC", Locale.GERMANY) { assertEquals("Fr.", WeatherFormat.shortDayLabel("2026-03-27")) }
    }

    @Test
    fun `the first day is Today and the rest are named`() {
        at("UTC", Locale.US) {
            assertEquals("Today", WeatherFormat.dayLabel("2026-03-27", 0))
            assertEquals("Sat", WeatherFormat.dayLabel("2026-03-28", 1))
        }
    }

    @Test
    fun `a spring-forward hour that does not exist still produces a usable instant`() {
        // 29 March 2026 is the UK spring-forward: 01:00 jumps to 02:00, so 01:30 never happens.
        // The old lenient parser invented one; java.time shifts it forward by the gap. Either way
        // the caller must get a time rather than a null, because the feed can and does list it.
        at("Europe/London", Locale.US) {
            assertNotNull(WeatherFormat.parseHourly("2026-03-29T01:30"))
        }
    }

    @Test
    fun `the ambiguous fall-back hour resolves to the EARLIER offset, and the label is unaffected`() {
        // ⚠️ THE ONE BEHAVIOUR CHANGE, and it is pinned rather than left to be rediscovered.
        // 25 October 2026 is the UK fall-back, so 01:00 occurs twice and the source string does not
        // say which. `SimpleDateFormat` picked GMT (the later offset); `java.time` picks BST (the
        // earlier), which is its documented, deterministic resolution rather than unspecified
        // lenient behaviour. Measured across five zones and three locales, this single input is the
        // ONLY one of 15,600 that differs — and the rendered label is "01:00" either way, so nothing
        // on screen moves. Only an instant comparison an hour apart, for one hour of one night a year.
        at("Europe/London", Locale.US) {
            val ms = WeatherFormat.parseHourly("2026-10-25T01:00")!!.time
            // 01:00 BST is 2026-10-25T00:00:00Z; 01:00 GMT would be an hour later.
            assertEquals(1792886400000L, ms)
            assertEquals("01:00", WeatherFormat.hourLabel("2026-10-25T01:00", use24h = true))
        }
    }

    @Test
    fun `nonsense is refused rather than turned into a plausible time`() {
        // ⚠️ The second behaviour change. `SimpleDateFormat` is lenient by default, so it read
        // month 13 day 45 hour 99 as an overflowed date and rendered a confident wrong time.
        // java.time refuses, and the caller's existing fallback shows the raw text instead — which
        // is the honest answer for a string that is not a date.
        at("UTC", Locale.US) {
            assertNull(WeatherFormat.parseHourly("2026-13-45T99:99"))
            assertEquals("99:99", WeatherFormat.hourLabel("2026-13-45T99:99", use24h = true))
            // The fallback is `takeLast(2)` and is unchanged by this move — a day chip is two
            // characters wide, so an unparseable date shows its own tail rather than a wrong day.
            assertEquals("e2", WeatherFormat.shortDayLabel("nonsense2"))
        }
    }

    @Test
    fun `nowIndex points at the slot before the first future hour`() {
        at("UTC", Locale.US) {
            val hours = (0..23).map { "2000-01-01T%02d:00".format(Locale.US, it) }
            // Every hour is in the past, so nothing is "after now" and the index floors at zero
            // rather than going negative.
            assertEquals(0, WeatherFormat.nowIndex(hours))
        }
    }

    @Test
    fun `the air quality bands are unchanged`() {
        assertEquals("—", WeatherFormat.aqiLabel(null))
        assertEquals("Good", WeatherFormat.aqiLabel(20.0))
        assertEquals("Fair", WeatherFormat.aqiLabel(40.0))
        assertEquals("Extremely poor", WeatherFormat.aqiLabel(101.0))
    }
}
