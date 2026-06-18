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

    private fun trimNum(v: Double): String =
        if (abs(v - v.roundToInt()) < 0.05) v.roundToInt().toString() else String.format("%.1f", v)
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
        val dir = if (pct >= 0) "up" else "down"
        return Explainer(
            "${if (pct >= 0) "▲" else "▼"} ${String.format("%.2f", abs(pct))}% today — $size",
            "Price is $dir ${String.format("%.2f", abs(pct))}% versus the previous close.",
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
        "INDEX" -> "A stock-market index — a basket that tracks a whole market's direction in one number."
        "STOCK" -> "Shares of $label — you own a slice of the company; the price is what one share trades at."
        "FOREX" -> "A currency pair — how much of one currency it takes to buy another."
        "COMMODITY" -> "A raw material traded globally; its price feeds through to real-world costs."
        "CRYPTO" -> "A cryptocurrency — a decentralised digital asset that trades 24/7."
        else -> "A tradable market instrument."
    }

    private val BLURBS: Map<String, String> = mapOf(
        "^spx" to "The S&P 500 — the 500 biggest U.S. companies; the headline gauge of the U.S. market.",
        "^ndq" to "The Nasdaq 100 — 100 largest non-financial Nasdaq firms; tech-heavy and growth-tilted.",
        "^dji" to "The Dow Jones — 30 large U.S. blue-chip companies; price-weighted and widely quoted.",
        "^rut" to "The Russell 2000 — 2,000 U.S. small-cap companies; a read on smaller, domestic firms.",
        "^ftm" to "The FTSE 100 — the 100 biggest companies on the London Stock Exchange.",
        "^dax" to "The DAX — 40 major German blue-chips traded in Frankfurt.",
        "^cac" to "The CAC 40 — 40 major French companies traded in Paris.",
        "^nkx" to "The Nikkei 225 — 225 large Japanese companies; Tokyo's headline index.",
        "^hsi" to "The Hang Seng — the main index of large companies listed in Hong Kong.",
        "^vix" to "The VIX — the market's 'fear gauge'; expected S&P 500 swings. It spikes when markets get nervous.",
        "eurusd" to "Euro vs U.S. dollar — how many dollars one euro buys. The world's most-traded pair.",
        "gbpusd" to "British pound vs U.S. dollar ('cable') — how many dollars one pound buys.",
        "usdjpy" to "U.S. dollar vs Japanese yen — how many yen one dollar buys.",
        "gc.f" to "Gold — the classic safe-haven store of value; tends to rise when fear or inflation climbs.",
        "si.f" to "Silver — a precious metal that's also an industrial input, so it's more volatile than gold.",
        "cl.f" to "WTI crude — the U.S. oil benchmark; a major driver of fuel and shipping costs.",
        "cb.f" to "Brent crude — the global oil benchmark used to price most of the world's crude.",
        "bitcoin" to "Bitcoin — the original and largest cryptocurrency; often treated as 'digital gold'.",
        "ethereum" to "Ethereum — the leading smart-contract platform; powers most of crypto's apps.",
        "solana" to "Solana — a fast, low-fee blockchain popular for apps and trading.",
        "ripple" to "XRP — a token built for fast, cheap cross-border payments.",
        "binancecoin" to "BNB — the token of the Binance ecosystem, used for fees and apps.",
        "dogecoin" to "Dogecoin — a meme-origin cryptocurrency with a large, active community.",
    )
}
