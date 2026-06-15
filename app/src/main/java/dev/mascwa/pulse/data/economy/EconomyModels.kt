package dev.mascwa.pulse.data.economy

import kotlinx.serialization.Serializable

/** How a series value should be rendered. */
enum class ValueFormat { PERCENT, CURRENCY_USD, NUMBER, COMPACT }

/** The economic indicators surfaced by the Economy & Inflation screens. */
enum class EconomyIndicator(
    val id: String,
    val title: String,
    val unit: String,
    val format: ValueFormat,
    val higherIsBetter: Boolean,
) {
    INFLATION("FP.CPI.TOTL.ZG", "Inflation (CPI)", "annual %", ValueFormat.PERCENT, false),
    GDP_GROWTH("NY.GDP.MKTP.KD.ZG", "GDP Growth", "annual %", ValueFormat.PERCENT, true),
    UNEMPLOYMENT("SL.UEM.TOTL.ZS", "Unemployment", "% labour force", ValueFormat.PERCENT, false),
    GDP_PER_CAPITA("NY.GDP.PCAP.CD", "GDP per Capita", "US$", ValueFormat.CURRENCY_USD, true),
    GDP_TOTAL("NY.GDP.MKTP.CD", "GDP (total)", "US$", ValueFormat.COMPACT, true),
    REAL_INTEREST("FR.INR.RINR", "Real Interest Rate", "%", ValueFormat.PERCENT, false),
    GOV_DEBT("GC.DOD.TOTL.GD.ZS", "Government Debt", "% of GDP", ValueFormat.PERCENT, false),
    CURRENT_ACCOUNT("BN.CAB.XOKA.GD.ZS", "Current Account", "% of GDP", ValueFormat.PERCENT, true),
    POPULATION("SP.POP.TOTL", "Population", "people", ValueFormat.COMPACT, true),
}

@Serializable
data class IndicatorPoint(val year: Int, val value: Double)

@Serializable
data class IndicatorSeries(
    val indicatorId: String,
    val indicatorTitle: String,
    val countryCode: String,
    val countryName: String,
    val unit: String,
    val format: ValueFormat,
    val higherIsBetter: Boolean,
    val points: List<IndicatorPoint>,   // ascending by year, non-null values only
) {
    val latest: IndicatorPoint? get() = points.lastOrNull()
    val previous: IndicatorPoint? get() = points.getOrNull(points.size - 2)
    val yoyChange: Double? get() {
        val l = latest ?: return null
        val p = previous ?: return null
        return l.value - p.value
    }
}

@Serializable
data class EconomyDashboard(
    val countryCode: String,
    val countryName: String,
    val series: List<IndicatorSeries>,
)
