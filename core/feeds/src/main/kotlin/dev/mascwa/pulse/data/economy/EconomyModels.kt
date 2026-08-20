package dev.mascwa.pulse.data.economy

import kotlinx.serialization.Serializable

/** How a series value should be rendered. */
enum class ValueFormat { PERCENT, CURRENCY_USD, NUMBER, COMPACT }

/** Which part of the picture an indicator belongs to, so nineteen cards are not one flat wall. */
enum class EconomyGroup(val label: String) {
    PRICES("Prices & money"),
    GROWTH("Growth & jobs"),
    GOVERNMENT("Government & trade"),
    PEOPLE("People & living standards"),
}

/**
 * The economic indicators surfaced by the Economy & Inflation screens.
 *
 * [higherIsBetter] is deliberately nullable. Most of these have an uncontroversial good direction —
 * longer lives, less poverty — but some genuinely do not: whether a country spends more of its
 * output on its military, how urban it is, or how much energy each person uses are political or
 * two-sided questions, and painting the year-on-year change green or red would be the app taking a
 * side it has no business taking. Null renders the change in a neutral colour: still shown, not
 * judged.
 */
enum class EconomyIndicator(
    val id: String,
    val title: String,
    val unit: String,
    val format: ValueFormat,
    val higherIsBetter: Boolean?,
    val group: EconomyGroup,
) {
    INFLATION("FP.CPI.TOTL.ZG", "Inflation (CPI)", "annual %", ValueFormat.PERCENT, false, EconomyGroup.PRICES),
    REAL_INTEREST("FR.INR.RINR", "Real Interest Rate", "%", ValueFormat.PERCENT, false, EconomyGroup.PRICES),

    GDP_GROWTH("NY.GDP.MKTP.KD.ZG", "GDP Growth", "annual %", ValueFormat.PERCENT, true, EconomyGroup.GROWTH),
    UNEMPLOYMENT("SL.UEM.TOTL.ZS", "Unemployment", "% labour force", ValueFormat.PERCENT, false, EconomyGroup.GROWTH),
    GDP_PER_CAPITA("NY.GDP.PCAP.CD", "GDP per Capita", "US$", ValueFormat.CURRENCY_USD, true, EconomyGroup.GROWTH),
    GDP_TOTAL("NY.GDP.MKTP.CD", "GDP (total)", "US$", ValueFormat.COMPACT, true, EconomyGroup.GROWTH),

    GOV_DEBT("GC.DOD.TOTL.GD.ZS", "Government Debt", "% of GDP", ValueFormat.PERCENT, false, EconomyGroup.GOVERNMENT),
    CURRENT_ACCOUNT("BN.CAB.XOKA.GD.ZS", "Current Account", "% of GDP", ValueFormat.PERCENT, true, EconomyGroup.GOVERNMENT),
    HEALTH_SPEND("SH.XPD.CHEX.GD.ZS", "Health Spending", "% of GDP", ValueFormat.PERCENT, true, EconomyGroup.GOVERNMENT),
    EDUCATION_SPEND("SE.XPD.TOTL.GD.ZS", "Education Spending", "% of GDP", ValueFormat.PERCENT, true, EconomyGroup.GOVERNMENT),
    MILITARY_SPEND("MS.MIL.XPND.GD.ZS", "Military Spending", "% of GDP", ValueFormat.PERCENT, null, EconomyGroup.GOVERNMENT),

    POPULATION("SP.POP.TOTL", "Population", "people", ValueFormat.COMPACT, null, EconomyGroup.PEOPLE),
    LIFE_EXPECTANCY("SP.DYN.LE00.IN", "Life Expectancy", "years", ValueFormat.NUMBER, true, EconomyGroup.PEOPLE),
    INEQUALITY("SI.POV.GINI", "Income Inequality", "Gini 0–100", ValueFormat.NUMBER, false, EconomyGroup.PEOPLE),
    POVERTY("SI.POV.DDAY", "Extreme Poverty", "% of people", ValueFormat.PERCENT, false, EconomyGroup.PEOPLE),
    INTERNET_USERS("IT.NET.USER.ZS", "Internet Users", "% of people", ValueFormat.PERCENT, true, EconomyGroup.PEOPLE),
    URBAN_SHARE("SP.URB.TOTL.IN.ZS", "Urban Population", "% of people", ValueFormat.PERCENT, null, EconomyGroup.PEOPLE),
    CO2_PER_CAPITA("EN.GHG.CO2.PC.CE.AR5", "CO₂ per Person", "tonnes/year", ValueFormat.NUMBER, false, EconomyGroup.PEOPLE),
    ENERGY_PER_CAPITA("EG.USE.PCAP.KG.OE", "Energy Use per Person", "kg oil eq.", ValueFormat.NUMBER, null, EconomyGroup.PEOPLE),
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
    val higherIsBetter: Boolean?,
    val points: List<IndicatorPoint>,   // ascending by year, non-null values only
    /**
     * When the World Bank last revised this series — its own date, not ours.
     *
     * Defaulted so dashboards cached before this field existed still decode. Distinct from both the
     * year a figure describes and the moment the app fetched it; conflating the three is what made a
     * two-year-old number read as current.
     */
    val lastUpdatedMs: Long? = null,
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
