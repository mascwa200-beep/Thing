package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.roundToInt

/** A short, plain-language explanation of a number/metric: a one-line [headline] + a sentence of [detail]. */
data class Explainer(val headline: String, val detail: String)

/**
 * Turns raw space-weather numbers into intuitive, offline plain-English explanations. Pure (no Android),
 * so the thresholds are unit-tested in CI. Phrasing is deliberately concrete ("aurora may dip to the
 * northern-tier states") rather than jargon.
 */
object SpaceWeatherExplainers {

    /** Planetary K-index 0..9 — how disturbed Earth's magnetic field is right now. */
    fun kp(kp: Double): Explainer {
        val k = kp.coerceIn(0.0, 9.0)
        val band = when {
            k < 3 -> "Quiet" to "Earth's magnetic field is calm. Aurora stays near the poles."
            k < 5 -> "Unsettled" to "Minor disturbances. Aurora possible at high latitudes only."
            k < 6 -> "G1 · minor storm" to "Aurora may reach the northern-tier US / northern UK."
            k < 7 -> "G2 · moderate storm" to "Aurora pushes further south; minor satellite/grid effects."
            k < 8 -> "G3 · strong storm" to "Aurora to mid-latitudes; possible GPS and radio glitches."
            k < 9 -> "G4 · severe storm" to "Widespread aurora; power-grid voltage issues possible."
            else -> "G5 · extreme storm" to "Aurora near the tropics; major grid/satellite/HF impacts."
        }
        return Explainer("Kp ${trimNum(k)} — ${band.first}", band.second)
    }

    /** Solar wind bulk speed in km/s — how fast the stream of particles from the Sun is hitting us. */
    fun solarWind(kmS: Double): Explainer {
        val band = when {
            kmS < 350 -> "Calm" to "Background flow (~300 km/s). Little geomagnetic effect."
            kmS < 500 -> "Elevated" to "A faster stream is arriving — activity can pick up."
            kmS < 700 -> "Fast" to "High-speed stream; can stir up geomagnetic storms."
            else -> "Very fast" to "Often follows a solar eruption — storms more likely."
        }
        return Explainer("Solar wind ${kmS.roundToInt()} km/s — ${band.first}", band.second)
    }

    /** IMF Bz in nT — the north/south tilt of the Sun's magnetic field as it reaches Earth. The single
     *  best short-term storm predictor: strongly southward (negative) lets solar energy pour in. */
    fun bz(nT: Double): Explainer {
        val band = when {
            nT >= 1 -> "Northward" to "+Bz shields Earth — storms unlikely right now."
            nT > -5 -> "Near zero" to "Weak coupling; only mild activity expected."
            nT > -10 -> "Southward" to "Energy is coupling in — aurora chances rising."
            else -> "Strongly southward" to "Prime storm driver — aurora likely if it holds."
        }
        return Explainer("Bz ${trimNum(nT)} nT — ${band.first}", band.second)
    }

    /** Modelled chance (%) that aurora is overhead at the user's latitude. */
    fun aurora(pct: Int): Explainer {
        val band = when {
            pct < 15 -> "Low" to "Aurora unlikely where you are."
            pct < 50 -> "Possible" to "Worth a look north after dark, away from city light."
            else -> "Likely" to "Good odds overhead if skies are clear and dark."
        }
        return Explainer("Aurora $pct% — ${band.first}", band.second)
    }

    /** NOAA G-scale label (e.g. "G1 Minor", "None"). */
    fun stormScale(level: String): Explainer = when (level.trim().uppercase().substringBefore(' ')) {
        "G1" -> Explainer("G1 — Minor storm", "Weak power grid fluctuations; aurora at high latitudes.")
        "G2" -> Explainer("G2 — Moderate storm", "High-latitude power systems alarm; aurora further south.")
        "G3" -> Explainer("G3 — Strong storm", "Voltage corrections needed; GPS/radio may degrade.")
        "G4" -> Explainer("G4 — Severe storm", "Widespread voltage control issues; aurora to mid-latitudes.")
        "G5" -> Explainer("G5 — Extreme storm", "Grid collapse risk; aurora near the equator; HF blackout.")
        else -> Explainer("No storm", "Geomagnetic conditions are calm — nothing notable.")
    }

    /** GOES soft X-ray flux, W/m² — the channel the flare classes A/B/C/M/X are defined on. */
    fun xrayFlux(wattsPerSqM: Double?): Explainer {
        val flare = SolarActivity.flareClass(wattsPerSqM)
            ?: return Explainer(
                "X-rays — background",
                "The Sun's X-ray output is below the A-class floor. Nothing is flaring.",
            )
        val band = when (flare.letter) {
            'A', 'B' -> "Quiet" to "Background level. No effect on radio or anything else down here."
            'C' -> "Common flare" to "Small flares happen most days on an active Sun. Little practical effect."
            'M' -> "Medium flare" to
                "Brief HF radio fades on the Sun-facing side of Earth, and sometimes minor radiation storms."
            else -> "Major flare" to
                "Wide HF blackouts on the daylit side, navigation errors, and a real chance of a storm to follow."
        }
        return Explainer("${flare.label} — ${band.first}", band.second)
    }

    /** F10.7 cm solar radio flux, sfu — the long-running proxy for how active the Sun is overall. */
    fun solarFlux(sfu: Double): Explainer {
        val band = when {
            sfu < 80 -> "Solar minimum" to
                "A quiet Sun. The ionosphere is thin, so long-distance shortwave is limited to lower frequencies."
            sfu < 120 -> "Moderate" to "Middling activity. Decent daytime shortwave on the middle bands."
            sfu < 180 -> "Active" to "An energised ionosphere — the higher shortwave bands open up."
            else -> "Solar maximum" to
                "Very active. The best conditions for long-distance shortwave, and the most flares."
        }
        return Explainer("F10.7 ${sfu.roundToInt()} sfu — ${band.first}", band.second)
    }

    /** Integral proton flux at >=10 MeV, in pfu — what the NOAA S (radiation) scale is defined on. */
    fun protonFlux(pfu: Double): Explainer {
        val level = SolarActivity.radiationStorm(pfu)
        if (level == 0) {
            return Explainer(
                "Radiation — background",
                "Proton levels are normal. No effect on flights, satellites or anyone on the ground.",
            )
        }
        return Explainer(
            "${SolarActivity.scaleLabel('S', level)} — ${trimNum(pfu)} pfu",
            SolarActivity.effect('S', level),
        )
    }

    /** Any of the three NOAA scales as a number, which is how the JSON feed publishes them. */
    fun noaaScale(prefix: Char, level: Int): Explainer {
        val what = when (prefix) {
            'R' -> "Radio blackouts, caused by flares"
            'S' -> "Radiation storms, caused by solar protons"
            else -> "Geomagnetic storms, caused by the solar wind"
        }
        if (level <= 0) return Explainer("${prefix}0 — none", "$what: nothing happening right now.")
        return Explainer(SolarActivity.scaleLabel(prefix, level), SolarActivity.effect(prefix, level))
    }

    /**
     * Maximum usable frequency, MHz — the highest shortwave frequency the ionosphere will still bend
     * back to Earth. Above it a signal goes straight out to space instead of reaching anyone.
     */
    fun maxUsableFrequency(mhz: Double): Explainer {
        val band = when {
            mhz < 10 -> "Low" to "Only the lower bands are reflecting. Long-distance contacts will be limited."
            mhz < 18 -> "Fair" to "The middle shortwave bands are usable for long-distance paths."
            mhz < 28 -> "Good" to "The upper bands are open — long hops are working well."
            else -> "Excellent" to "Everything up to the top of shortwave is reflecting. Rare, and worth using."
        }
        return Explainer("MUF ~${mhz.roundToInt()} MHz — ${band.first}", band.second)
    }

    /** One shortwave band's day/night usability. */
    fun band(report: HfPropagation.BandReport): Explainer = Explainer(
        "${report.name} — ${report.day.name.lowercase()} by day, ${report.night.name.lowercase()} by night",
        "This band sits at about ${trimNum(report.megahertz)} MHz. " + when {
            report.day == HfPropagation.Quality.GOOD || report.night == HfPropagation.Quality.GOOD ->
                "It is one of the better choices right now."
            report.day == HfPropagation.Quality.CLOSED && report.night == HfPropagation.Quality.CLOSED ->
                "It is shut in both directions — signals pass straight through the ionosphere or are absorbed."
            else -> "It is workable but not at its best."
        },
    )

    // Locale.US, not the default: on a comma-decimal device the default renders 1.3 as "1,3", and
    // these strings are read as numbers. Same trap the news prompt's live figures already hit.
    private fun trimNum(v: Double): String =
        if (abs(v - v.roundToInt()) < 0.05) v.roundToInt().toString()
        else String.format(java.util.Locale.US, "%.1f", v)
}

/**
 * Plain-English explanations of market instruments and moves. Instrument blurbs are keyed by the app's
 * watchlist ids (with a sensible per-asset-class fallback). Pure + CI-tested.
 */
object MarketExplainers {

    /** Explain a daily change %. */
    fun changePercent(pct: Double): Explainer {
        val mag = abs(pct)
        val size = when {
            mag < 0.1 -> "essentially flat"
            mag < 1 -> "a small move"
            mag < 3 -> "a notable move"
            else -> "a big move"
        }
        val up = pct >= 0
        return Explainer(
            "${if (up) "▲" else "▼"} ${String.format("%.2f", abs(pct))}% today — $size",
            "In plain terms: it's worth ${String.format("%.2f", abs(pct))}% ${if (up) "more" else "less"} " +
                "than it was at yesterday's closing price.",
        )
    }

    /** A one-liner for what an instrument actually is, keyed by watchlist id, then asset class. */
    fun instrument(id: String, label: String, type: String): Explainer {
        BLURBS[id.lowercase()]?.let { return Explainer(label, it) }
        // Stocks: generic by company label.
        return Explainer(label, assetClass(type, label))
    }

    /** Generic definition for an asset class (used when a specific blurb isn't known). */
    fun assetClass(type: String, label: String = "this"): String = when (type.uppercase()) {
        "INDEX" -> "A stock-market index — one number that shows how a whole group of companies is doing today, " +
            "so you don't have to check each one."
        "STOCK" -> "Shares of $label — owning one means you own a tiny piece of the company. The price is what " +
            "a single piece costs to buy or sell right now."
        "FOREX" -> "A pair of currencies — it shows how much of one country's money it takes to buy another's."
        "COMMODITY" -> "A raw material (like oil or gold) bought and sold worldwide. Its price flows through to " +
            "everyday costs like fuel and food."
        "CRYPTO" -> "A digital currency that no bank or government runs. It can be bought and sold all day, every day."
        else -> "A market you can buy and sell."
    }

    private val BLURBS: Map<String, String> = mapOf(
        "^spx" to "The S&P 500 — tracks the 500 biggest U.S. companies together. When people say \"the U.S. " +
            "stock market,\" this is usually the number they mean.",
        "^ndq" to "The Nasdaq 100 — the 100 biggest U.S. companies outside of banking, mostly tech names like " +
            "Apple and Microsoft.",
        "^dji" to "The Dow Jones — 30 huge, household-name U.S. companies. One of the oldest and most-quoted " +
            "market numbers.",
        "^rut" to "The Russell 2000 — around 2,000 smaller U.S. companies. A read on how smaller businesses are doing.",
        "^ftm" to "The FTSE 100 — the 100 biggest companies on the London stock market; the UK's headline number.",
        "^dax" to "The DAX — 40 of Germany's biggest companies, traded in Frankfurt.",
        "^cac" to "The CAC 40 — 40 of France's biggest companies, traded in Paris.",
        "^nkx" to "The Nikkei 225 — 225 big Japanese companies; Japan's headline market number.",
        "^hsi" to "The Hang Seng — the main market number for big companies in Hong Kong.",
        "^vix" to "The VIX — the market's \"fear gauge.\" It rises when investors get nervous and expect big " +
            "swings, and falls when things feel calm.",
        "eurusd" to "Euro vs U.S. dollar — how many dollars one euro buys. The world's most-traded currency pair.",
        "gbpusd" to "British pound vs U.S. dollar — how many dollars one pound buys.",
        "usdjpy" to "U.S. dollar vs Japanese yen — how many yen one dollar buys.",
        "gc.f" to "Gold — people often buy it to feel safe when prices rise or markets get scary. Seen as a " +
            "steady place to park money.",
        "si.f" to "Silver — a precious metal that's also used in industry, so its price swings around more than gold's.",
        "cl.f" to "U.S. crude oil — the benchmark price for American oil. It feeds straight into fuel and shipping costs.",
        "cb.f" to "Brent crude — the benchmark used to price most of the world's oil.",
        "bitcoin" to "Bitcoin — the first and biggest cryptocurrency. Often called \"digital gold.\"",
        "ethereum" to "Ethereum — the network most crypto apps are built on; its coin is the second-biggest.",
        "solana" to "Solana — a fast, cheap-to-use blockchain popular for apps and trading.",
        "ripple" to "XRP — a coin built for sending money across borders quickly and cheaply.",
        "binancecoin" to "BNB — the coin of Binance, a big crypto exchange; used to pay fees and run apps.",
        "dogecoin" to "Dogecoin — a cryptocurrency that started as a meme but has a big, active fan base.",
    )
}
