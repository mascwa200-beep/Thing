package dev.mascwa.pulse.core.telemetry

import java.util.Locale

/**
 * Plain-English explanations of the World Bank macro indicators shown on the Economy screen, so
 * "Inflation (CPI) · annual %" or "Current Account · % of GDP" means something to a non-economist.
 * Pure (no Android) and keyed by the stable World Bank indicator code, so the bands are CI-tested.
 */
object EconomyExplainers {

    /** Explanation for a World Bank [indicatorId]; value-aware where a band adds meaning. */
    fun forIndicator(indicatorId: String, latest: Double?): Explainer = when (indicatorId) {
        "FP.CPI.TOTL.ZG" -> inflation(latest)
        "NY.GDP.MKTP.KD.ZG" -> gdpGrowth(latest)
        "SL.UEM.TOTL.ZS" -> unemployment(latest)
        "GC.DOD.TOTL.GD.ZS" -> govDebt(latest)
        "BN.CAB.XOKA.GD.ZS" -> currentAccount(latest)
        "NY.GDP.PCAP.CD" -> Explainer(
            "GDP per capita",
            "A country's total economic output divided by its population — a rough average of how much " +
                "is produced per person per year (US$). Higher usually means a wealthier economy, but " +
                "it's an average and doesn't show how evenly income is shared.",
        )
        "NY.GDP.MKTP.CD" -> Explainer(
            "GDP (total)",
            "Gross Domestic Product — the total market value of everything a country produces in a year " +
                "(US$). The headline measure of the size of an economy.",
        )
        "SP.POP.TOTL" -> Explainer(
            "Population",
            "The total number of people living in the country.",
        )
        "FR.INR.RINR" -> Explainer(
            "Real interest rate",
            "What lenders earn after inflation is taken out. If a loan charges 6% while prices rise 4%, " +
                "the real rate is about 2% — that is the part that actually gains buying power. " +
                "Negative real rates quietly punish savers and favour borrowers.",
        )
        "SH.XPD.CHEX.GD.ZS" -> Explainer(
            "Health spending",
            "How much of everything the country produces goes on health care, from all sources — " +
                "government, insurers and people paying out of their own pockets. A share rather than a " +
                "total, so it says how much of a priority health is rather than how good the care is.",
        )
        "SE.XPD.TOTL.GD.ZS" -> Explainer(
            "Education spending",
            "Government spending on education as a share of the whole economy. Many countries report " +
                "this late or only occasionally, so this one is often several years behind — check the " +
                "year on the card before reading anything into it.",
        )
        "MS.MIL.XPND.GD.ZS" -> Explainer(
            "Military spending",
            "Defence spending as a share of the whole economy. Deliberately shown without a good or bad " +
                "colour: whether a country should spend more or less on its armed forces is a political " +
                "question, not a statistical one.",
        )
        "SP.DYN.LE00.IN" -> Explainer(
            "Life expectancy",
            "How long a baby born this year would live if today's death rates held for its whole life. " +
                "It is the single broadest measure of a country's health, and because it is a snapshot " +
                "of current conditions it moves sharply in a bad year — a pandemic shows up here.",
        )
        "SI.POV.GINI" -> Explainer(
            "Income inequality (Gini)",
            "How unevenly income is shared, on a scale where 0 would mean everyone earns exactly the " +
                "same and 100 would mean one person earns everything. Most countries land between 25 " +
                "and 50; higher means the gap between top and bottom is wider.",
        )
        "SI.POV.DDAY" -> Explainer(
            "Extreme poverty",
            "The share of people living below the international extreme-poverty line — enough to cover " +
                "only the barest essentials, adjusted so it means the same thing in every country. In " +
                "rich countries this sits near zero, which is why it looks flat there.",
        )
        "IT.NET.USER.ZS" -> Explainer(
            "Internet users",
            "The share of people who have used the internet recently. A rough measure of how much of " +
                "daily life — banking, government, work — a person can reach at all.",
        )
        "SP.URB.TOTL.IN.ZS" -> Explainer(
            "Urban population",
            "The share of people living in towns and cities rather than the countryside. Shown without " +
                "a good or bad direction: cities concentrate opportunity and strain alike, and each " +
                "country defines 'urban' its own way, so compare a country with itself over time " +
                "rather than against its neighbours.",
        )
        "EN.GHG.CO2.PC.CE.AR5" -> Explainer(
            "CO₂ per person",
            "Carbon dioxide emitted per person per year, in tonnes. Per person rather than in total, so " +
                "a small country is not flattered by being small — it is a measure of how carbon-heavy " +
                "an average life here is.",
        )
        "EG.USE.PCAP.KG.OE" -> Explainer(
            "Energy use per person",
            "How much energy an average person uses in a year, expressed as the equivalent weight of " +
                "oil. Shown without a good or bad direction: rising use can mean a warming, better-lit, " +
                "more industrial country or simply a wasteful one, and the number alone cannot tell " +
                "you which.",
        )
        else -> Explainer(
            "Economic indicator",
            "A macroeconomic statistic from the World Bank Open Data set (annual; some series lag 1–2 years).",
        )
    }

    private fun pct(v: Double) = String.format(Locale.US, "%.1f%%", v)

    private fun inflation(v: Double?): Explainer {
        val what = "Inflation (CPI) is how fast consumer prices are rising year over year. Central banks " +
            "typically aim for about 2%."
        if (v == null) return Explainer("Inflation", what)
        val band = when {
            v < 0 -> "Deflation — prices are falling, which can signal a weak economy."
            v < 2 -> "Low — prices barely rising; below most central-bank targets."
            v < 3 -> "On target — near the ~2% most central banks aim for."
            v < 6 -> "Elevated — prices climbing faster than target; squeezes spending power."
            else -> "High — prices rising fast; erodes savings and wages."
        }
        return Explainer("Inflation ${pct(v)}", "$what\n$band")
    }

    private fun gdpGrowth(v: Double?): Explainer {
        val what = "GDP growth is how much the economy's output expanded (or shrank) versus the year before."
        if (v == null) return Explainer("GDP growth", what)
        val band = when {
            v < 0 -> "Contracting — output shrank; two such quarters in a row is the rule-of-thumb for recession."
            v < 2 -> "Sluggish — growing, but slowly."
            v < 4 -> "Healthy — a solid pace for a developed economy."
            else -> "Strong — rapid expansion (common in fast-developing economies)."
        }
        return Explainer("GDP growth ${pct(v)}", "$what\n$band")
    }

    private fun unemployment(v: Double?): Explainer {
        val what = "The share of people who want a job and are actively looking but can't find one."
        if (v == null) return Explainer("Unemployment", what)
        val band = when {
            v < 4 -> "Very low — close to full employment."
            v < 6 -> "Low — a generally healthy labour market."
            v < 9 -> "Elevated — meaningful slack; harder to find work."
            else -> "High — widespread joblessness; a strained economy."
        }
        return Explainer("Unemployment ${pct(v)}", "$what\n$band")
    }

    private fun govDebt(v: Double?): Explainer {
        val what = "Government debt as a share of the country's annual output (GDP). Higher means more of " +
            "the economy is owed by the state."
        if (v == null) return Explainer("Government debt", what)
        val band = when {
            v < 60 -> "Moderate — a commonly cited comfort threshold."
            v < 100 -> "Elevated — debt approaching the size of the whole economy."
            else -> "High — debt exceeds a year of total output."
        }
        return Explainer("Government debt ${pct(v)} of GDP", "$what\n$band")
    }

    private fun currentAccount(v: Double?): Explainer {
        val what = "The current account balance vs. GDP — broadly, whether the country earns more from the " +
            "rest of the world (trade + income) than it spends."
        if (v == null) return Explainer("Current account", what)
        val band = if (v >= 0) {
            "Surplus — the country earns more abroad than it spends (net lender to the world)."
        } else {
            "Deficit — the country spends more abroad than it earns (net borrower from the world)."
        }
        return Explainer("Current account ${pct(v)} of GDP", "$what\n$band")
    }
}
