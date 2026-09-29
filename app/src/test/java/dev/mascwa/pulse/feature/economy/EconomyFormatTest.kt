package dev.mascwa.pulse.feature.economy

import dev.mascwa.pulse.data.economy.EconomyIndicator
import dev.mascwa.pulse.data.economy.IndicatorPoint
import dev.mascwa.pulse.data.economy.IndicatorSeries
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Locale

class EconomyFormatTest {

    private var saved: Locale = Locale.getDefault()

    // The formatter renders for a PERSON, in their locale, on purpose; the test pins the US form the
    // owner reads, and puts the default back so no other test inherits it.
    @Before fun pinLocale() { saved = Locale.getDefault(); Locale.setDefault(Locale.US) }
    @After fun restoreLocale() { Locale.setDefault(saved) }

    @Test
    fun `the widget's three economy lines read as the screen reads them`() {
        // The screenshot's own figures, which the widget printed as "3annual %", "2.9annual %" and
        // "4.8% labour f".
        assertEquals("Inflation (CPI) 3.0% · 2025", economyLine(series(EconomyIndicator.INFLATION, 3.0)))
        assertEquals("GDP Growth 2.9% · 2025", economyLine(series(EconomyIndicator.GDP_GROWTH, 2.9)))
        assertEquals("Unemployment 4.8% · 2025", economyLine(series(EconomyIndicator.UNEMPLOYMENT, 4.8)))
    }

    @Test
    fun `a series with no figure has no line`() {
        assertNull(economyLine(series(EconomyIndicator.INFLATION, null)))
    }

    private fun series(ind: EconomyIndicator, value: Double?) = IndicatorSeries(
        indicatorId = ind.id,
        indicatorTitle = ind.title,
        countryCode = "US",
        countryName = "United States",
        unit = ind.unit,
        format = ind.format,
        higherIsBetter = ind.higherIsBetter,
        points = if (value == null) emptyList() else listOf(IndicatorPoint(2024, 1.0), IndicatorPoint(2025, value)),
    )
}
