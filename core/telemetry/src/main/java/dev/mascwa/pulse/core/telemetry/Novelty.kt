package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToLong

/**
 * Is this reading normal?
 *
 * Every screen in this app answers *"what is X right now?"* — the price, the temperature, the Kp index,
 * the magnitude. None of them answers whether that number is ordinary, which is the question that
 * actually creates awareness. A Kp of 4 means nothing to a person; *"Kp 4 — highest in 23 days"* means
 * something instantly.
 *
 * This scores one reading against that metric's **own recorded history** and says how surprising it is.
 * It is deliberately domain-agnostic: an earthquake, a share price and a pollutant concentration share
 * no unit, but they all share *how improbable this reading is against what that thing usually does*.
 * Reduce them to that and they can be ranked against each other on one axis, which is the whole point
 * of the wall this feeds.
 *
 * Pure, clock-free (time is a parameter everywhere) and I/O-free, so every case below is reachable in a
 * test.
 *
 * ## The four choices that decide whether this is an instrument or a horoscope
 *
 * **1 · Robust statistics, never mean and standard deviation.** World data is heavy-tailed and the
 * outliers *are what this is hunting*. A mean/sd z-score is contaminated by them: one large earthquake
 * inflates the standard deviation so much that the next one scores as ordinary. [describe] uses the
 * median and the MAD, so the estimate of "normal" cannot be dragged around by the anomalies it exists
 * to detect.
 *
 * **2 · Surprisal in bits, because it is the only axis comparable across domains.** A z-score is
 * comparable only between similar distributions and is meaningless for a bounded or bimodal one. A
 * percentile always means something but *saturates* — p=0.999 and p=0.99999 both read as ≈1.0 while the
 * second is a hundred times rarer. Surprisal, −log₂(p), does neither, and it is directly sayable:
 * *"a 1-in-340 reading."*
 *
 * **3 · ⚠️ The ceiling, which is the most important honesty constraint here.** With 500 samples you
 * cannot claim a 1-in-5000 event — the empirical tail simply does not reach that far. The tail estimate
 * `(count + 1) / (n + 1)` enforces this by construction: with nothing in the tail it bottoms out at
 * `1/(n+1)`, so surprisal can never exceed [ceilingBitsFor]. A reading pinned there reports
 * [Novelty.cappedAtCeiling] and the caller must say so rather than implying precision the sample
 * cannot support.
 *
 * **4 · Like-for-like before quantity.** A backfilled observation and a self-recorded one are not the
 * same measurement — reanalysis is modelled and gridded and will not agree exactly with a live endpoint
 * for the same hour, and a systematic offset shifts the median everything is judged against. So when
 * there is enough recorded-live history, only that is used; otherwise the backfilled history is used
 * and [Novelty.basis] says so, so the caller can label it.
 */
object Novelty {

    /**
     * One recorded value.
     *
     * ⚠️ `(Long, Double)` already exists three times in this repo — `ChartSeries.points`,
     * `SpaceWeather.TimePoint` and `MarketWindow.MarketBar`. This is a fourth **by decision**:
     * converging them is a refactor across three unrelated call-site families with no behavioural gain,
     * and this one carries a third field the others do not need. [chartPoints] means drawing a series
     * costs no conversion.
     */
    data class Observation(
        val atMs: Long,
        val value: Double,
        /** True when this came from a provider's history endpoint rather than from watching it happen. */
        val backfilled: Boolean = false,
    )

    /** What the compared history looked like. Robust throughout — see choice 1 above. */
    data class Distribution(
        val n: Int,
        val median: Double,
        /** Median absolute deviation, already scaled by [MAD_TO_SD] so it is comparable to a standard deviation. */
        val mad: Double,
        val min: Double,
        val max: Double,
    )

    /** Which history the score was computed against. Never hidden from the caller — see choice 4. */
    enum class Basis {
        /** Only observations this machine watched happen. The one to trust. */
        RECORDED,

        /** Only history fetched from a provider. Usable, and must be labelled as modelled. */
        BACKFILLED,

        /** Both, because there was not enough of either alone. The weakest basis. */
        MIXED,
    }

    /** A scored reading. [sentence] is the whole verdict in one line, ready to render. */
    data class Reading(
        val bits: Double,
        /** True when the reading is rarer than the sample can resolve, so [bits] is a floor, not a measurement. */
        val cappedAtCeiling: Boolean,
        val ceilingBits: Double,
        /** Fraction of the compared history strictly below this reading, 0..1. */
        val percentile: Double,
        /** `(value − median) / MAD`. 0.0 when the history is perfectly flat, which [bits] handles instead. */
        val robustZ: Double,
        /** +1 above the median, −1 below, 0 exactly on it. */
        val direction: Int,
        val basis: Basis,
        val n: Int,
        /**
         * When the history was last this extreme **in this direction**, or null when it never was —
         * which is what makes a reading a record rather than merely a high one.
         */
        val extremeSinceMs: Long?,
        val sentence: String,
    )

    /** The outcome of asking. A refusal is a first-class answer, not an exception or a zero. */
    sealed interface Score {
        data class Scored(val reading: Reading) : Score

        /**
         * Not enough history to judge, and how much more is needed.
         *
         * ⚠️ Callers must not sort these to either end of a ranked list. On a new install almost
         * everything lands here, and quietly ordering them among scored readings makes the world look
         * either uniformly calm or uniformly alarming. They belong in their own section.
         */
        data class TooLittleHistory(val have: Int, val need: Int, val sentence: String) : Score
    }

    /** Consistency factor making the MAD comparable to a standard deviation on normally distributed data. */
    const val MAD_TO_SD: Double = 1.4826

    /**
     * How much history before this will judge at all.
     *
     * ⚠️ Note what this implies rather than hiding it: at exactly [MIN_SAMPLES] the ceiling is about
     * 3.6 bits — roughly a 1-in-12 reading — so early scores are honestly weak. That is the correct
     * behaviour and the alternative is inventing confidence out of a small sample.
     */
    const val MIN_SAMPLES: Int = 24

    /**
     * Hours either side of the reading's own hour kept when a metric is diurnal.
     *
     * A hard 24-way split puts 13:59 and 14:01 in different buckets and divides the usable sample by
     * 24. A ±1-hour window triples what is compared and softens the boundary — the same idiom
     * `UsageInsights.windowCount` already uses for its hour histogram.
     */
    const val HOUR_WINDOW: Int = 1

    /**
     * Extra bits credited for a reading that has held across consecutive collections.
     *
     * ⚠️ Deliberately far below what independence would give. Two independent 1-in-100 readings are a
     * 1-in-10,000 event, which would be a full doubling of the bits — but consecutive samples of a
     * world-data series are heavily autocorrelated and nowhere near independent, so doubling would
     * manufacture confidence. Half a bit a repeat, capped, credits persistence without pretending it is
     * new evidence.
     */
    const val PERSISTENCE_BITS: Double = 0.5

    /** Most repeats that can earn the [PERSISTENCE_BITS] credit. */
    const val PERSISTENCE_CAP: Int = 4

    /**
     * How much wider than the median gap a sampling interval may be before the difference across it is
     * discarded by [changeSeries].
     */
    const val MAX_GAP_FACTOR: Double = 2.5

    private const val HOUR_MS = 60L * 60L * 1000L
    private const val DAY_MS = 24L * HOUR_MS

    // ---------------------------------------------------------------- describing a history

    /** Median, MAD, min and max of [series], or null when it is empty. */
    fun describe(series: List<Observation>): Distribution? {
        if (series.isEmpty()) return null
        val values = series.map { it.value }.filter { it.isFinite() }.sorted()
        if (values.isEmpty()) return null
        val med = medianOfSorted(values)
        val deviations = values.map { abs(it - med) }.sorted()
        return Distribution(
            n = values.size,
            median = med,
            mad = medianOfSorted(deviations) * MAD_TO_SD,
            min = values.first(),
            max = values.last(),
        )
    }

    /** The most surprising reading a sample of [n] can express, in bits. See choice 3. */
    fun ceilingBitsFor(n: Int): Double {
        if (n < 1) return 0.0
        // The two-sided tail bottoms out at 2/(n+1), so the surprisal tops out at log2((n+1)/2).
        return log2(((n + 1).toDouble() / 2.0).coerceAtLeast(1.0))
    }

    // ---------------------------------------------------------------- scoring

    /**
     * Score [latest] against [series].
     *
     * @param series the metric's history. [latest] may or may not be in it; it is excluded either way,
     *   because a reading compared against a sample containing itself is judged partly against itself.
     * @param diurnal true when the metric swings with time of day. Aircraft counts, air quality and
     *   market activity do; Kp index and earthquake magnitude do not. ⚠️ Comparing a 3 a.m. aircraft
     *   count against a whole-day distribution flags every single night, and bucketing a metric that
     *   has no daily cycle divides its sample by 24 for nothing — so this is declared per metric rather
     *   than guessed at here.
     * @param utcOffsetSeconds the reader's offset from UTC, needed only when [diurnal]. Supplied by the
     *   caller for the same reason `Stardate.at` takes one: a "day" derived from UTC inside a pure
     *   module is the wrong day for most of the planet.
     */
    fun score(
        series: List<Observation>,
        latest: Observation,
        diurnal: Boolean = false,
        utcOffsetSeconds: Int = 0,
    ): Score {
        val history = series
            .filter { it.atMs < latest.atMs && it.value.isFinite() }
            .let { if (diurnal) sameTimeOfDay(it, latest.atMs, utcOffsetSeconds) else it }

        // Choice 4: prefer a like-for-like comparison, fall back only when it is too thin to use.
        val recorded = history.filter { !it.backfilled }
        val compared = if (recorded.size >= MIN_SAMPLES) recorded else history
        val basis = basisOf(compared)

        if (compared.size < MIN_SAMPLES) {
            val need = MIN_SAMPLES - compared.size
            return Score.TooLittleHistory(
                have = compared.size,
                need = MIN_SAMPLES,
                sentence = if (diurnal) {
                    "Not enough history for this hour yet — $need more reading${plural(need)} needed."
                } else {
                    "Not enough history yet — $need more reading${plural(need)} needed."
                },
            )
        }

        val dist = describe(compared) ?: return Score.TooLittleHistory(0, MIN_SAMPLES, "No history yet.")
        val values = compared.map { it.value }
        val n = values.size
        val v = latest.value

        val atOrAbove = values.count { it >= v }
        val atOrBelow = values.count { it <= v }
        val pUpper = (atOrAbove + 1).toDouble() / (n + 1).toDouble()
        val pLower = (atOrBelow + 1).toDouble() / (n + 1).toDouble()
        // Two-sided: a reading is unusual whether it is high or low, and testing both tails without
        // paying for both would report every metric as twice as surprising as it is.
        val p = (2.0 * minOf(pUpper, pLower)).coerceAtMost(1.0)
        val bits = -log2(p)
        val ceiling = ceilingBitsFor(n)

        val direction = when {
            v > dist.median -> 1
            v < dist.median -> -1
            else -> 0
        }
        val below = values.count { it < v }
        val extremeUp = direction >= 0 && atOrAbove == 0
        val extremeDown = direction <= 0 && atOrBelow == 0

        val since = lastMatching(compared, v, up = direction >= 0)
        val z = if (dist.mad > 0.0) (v - dist.median) / dist.mad else 0.0

        return Score.Scored(
            Reading(
                bits = bits,
                cappedAtCeiling = extremeUp || extremeDown,
                ceilingBits = ceiling,
                percentile = below.toDouble() / n.toDouble(),
                robustZ = z,
                direction = direction,
                basis = basis,
                n = n,
                extremeSinceMs = since,
                sentence = sentence(
                    p = p,
                    n = n,
                    direction = direction,
                    extreme = extremeUp || extremeDown,
                    diurnal = diurnal,
                    basis = basis,
                    sinceMs = since,
                    nowMs = latest.atMs,
                ),
            ),
        )
    }

    // ---------------------------------------------------------------- rate of change

    /**
     * First differences, so a reading unremarkable in level but remarkable in **speed** can be caught —
     * a barometric pressure drop being the textbook case.
     *
     * ⚠️ Computed, never stored. Persisting a parallel derivative series would double the ledger for
     * something derivable in a single pass. Precedent: `VitalsAnalyzer` already scores each derivative
     * against the distribution of *prior* derivatives, and calls that a fair z-score in its own comment.
     *
     * ⚠️ Differences across an unusually long gap are dropped. Raw differences are only comparable when
     * the sampling interval is, and a metric's cadence is regular by declaration — but the app gets
     * closed and fetches fail, so real series have holes. A six-hour gap in a fifteen-minute series is
     * not the same measurement as the ones around it, and treating it as one manufactures a spike out
     * of an outage.
     */
    fun changeSeries(series: List<Observation>): List<Observation> {
        val ordered = series.filter { it.value.isFinite() }.sortedBy { it.atMs }
        if (ordered.size < 2) return emptyList()

        val gaps = ordered.zipWithNext { a, b -> b.atMs - a.atMs }.filter { it > 0L }
        if (gaps.isEmpty()) return emptyList()
        val medianGap = medianOfSorted(gaps.map { it.toDouble() }.sorted())
        val maxGap = (medianGap * MAX_GAP_FACTOR).toLong().coerceAtLeast(1L)

        return ordered.zipWithNext { a, b ->
            val gap = b.atMs - a.atMs
            if (gap <= 0L || gap > maxGap) null
            else Observation(atMs = b.atMs, value = b.value - a.value, backfilled = a.backfilled || b.backfilled)
        }.filterNotNull()
    }

    // ---------------------------------------------------------------- ranking a whole wall

    /**
     * How many of the readings at or above [thresholdBits] are expected to be chance, given that
     * [scored] metrics were tested.
     *
     * ⚠️ This exists because it is the difference between an instrument and a horoscope. Score a
     * hundred metrics every quarter of an hour and a "1-in-1000" reading turns up constantly by chance
     * alone; a wall that never says so is training its reader to believe in noise. The caller is
     * expected to render this, not to log it.
     */
    fun expectedFalseAlarms(scored: Int, thresholdBits: Double): Double {
        if (scored <= 0 || thresholdBits <= 0.0) return 0.0
        return scored * Math.pow(2.0, -thresholdBits)
    }

    /**
     * Order a wall: most surprising first, with a bounded credit for readings that have persisted.
     *
     * [persistence] maps a metric id to how many consecutive collections it has been anomalous for; an
     * absent or 1 entry earns nothing. An anomaly present in one sample and gone the next is usually
     * noise, and one that has held is usually not — but see [PERSISTENCE_BITS] for why the credit is
     * deliberately small.
     */
    fun rank(scored: List<Pair<String, Reading>>, persistence: Map<String, Int> = emptyMap()): List<String> =
        scored
            .sortedByDescending { (id, n) -> n.bits + persistenceCredit(persistence[id] ?: 1) }
            .map { it.first }

    /** The bonus [rank] gives a reading that has held for [consecutive] collections. */
    fun persistenceCredit(consecutive: Int): Double =
        PERSISTENCE_BITS * (consecutive - 1).coerceIn(0, PERSISTENCE_CAP)

    // ---------------------------------------------------------------- helpers

    /** [Observation]s as the chart kit's own point shape, so drawing a series costs no conversion. */
    fun List<Observation>.chartPoints(): List<Pair<Long, Double>> = map { it.atMs to it.value }

    private fun basisOf(compared: List<Observation>): Basis {
        val anyBackfilled = compared.any { it.backfilled }
        val anyRecorded = compared.any { !it.backfilled }
        return when {
            anyBackfilled && anyRecorded -> Basis.MIXED
            anyBackfilled -> Basis.BACKFILLED
            else -> Basis.RECORDED
        }
    }

    /** Observations whose local hour is within [HOUR_WINDOW] of the reading's, wrapping across midnight. */
    private fun sameTimeOfDay(series: List<Observation>, atMs: Long, utcOffsetSeconds: Int): List<Observation> {
        val target = localHour(atMs, utcOffsetSeconds)
        return series.filter { hourDistance(localHour(it.atMs, utcOffsetSeconds), target) <= HOUR_WINDOW }
    }

    private fun localHour(atMs: Long, utcOffsetSeconds: Int): Int {
        val localMs = atMs + utcOffsetSeconds * 1000L
        // floorDiv/floorMod rather than `/` and `%` so a pre-epoch instant yields 0..23 instead of a
        // negative hour.
        //
        // ⚠️ Belt-and-braces, and the comment says so rather than overclaiming: [hourDistance]'s
        // wraparound makes an off-by-24 hour provably harmless (for d = h − t, |d − 24| = 24 − d, and
        // min(24 − d, d) is the same answer), and real timestamps are positive anyway. It is kept
        // because it is correct in isolation, so a future change to [hourDistance] cannot quietly
        // depend on that coincidence.
        val hours = Math.floorDiv(localMs, HOUR_MS)
        return Math.floorMod(hours, 24L).toInt()
    }

    /** Circular distance between two hours of the day: 23 and 0 are one apart, not twenty-three. */
    private fun hourDistance(a: Int, b: Int): Int {
        val d = abs(a - b)
        return minOf(d, 24 - d)
    }

    /** When the history last reached [v] on the given side, or null when it never did. */
    private fun lastMatching(history: List<Observation>, v: Double, up: Boolean): Long? =
        history
            .filter { if (up) it.value >= v else it.value <= v }
            .maxByOrNull { it.atMs }
            ?.atMs

    private fun medianOfSorted(sorted: List<Double>): Double {
        if (sorted.isEmpty()) return 0.0
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    private fun log2(x: Double): Double = ln(x) / ln(2.0)

    private fun plural(n: Int) = if (n == 1) "" else "s"

    /**
     * How long ago the last equal-or-more-extreme reading has to be before *"highest in a month"* is
     * worth saying instead of *"unusually high"*.
     *
     * ⚠️ Without this the phrase is technically true and worthless: a reading a little above the median
     * was last matched yesterday, and *"highest in 18 hours"* is noise dressed as a finding.
     */
    private const val NOTABLE_SPAN_MS = 3L * DAY_MS

    /**
     * How surprising a reading has to be before its direction is worth mentioning at all.
     *
     * ⚠️ Found by running this over a real year of hourly weather rather than over fixtures. Half of
     * all readings sit above the median, so leading with the direction produced sentences that argue
     * with themselves — *"Unusually high — a 1-in-2 reading"*, and worse, *"Unusually low — a 1-in-1
     * reading"*. Two bits is p ≤ 0.25: outside the middle half of the distribution, which is the
     * loosest bar at which "unusual" is a defensible word. It also puts the phrasing floor at 1-in-4,
     * so [oneIn] can no longer produce the absurd "1-in-1".
     */
    private const val NOTABLE_BITS = 2.0

    private fun sentence(
        p: Double,
        n: Int,
        direction: Int,
        extreme: Boolean,
        diurnal: Boolean,
        basis: Basis,
        sinceMs: Long?,
        nowMs: Long,
    ): String {
        // Only the rarity clause carries this. On both clauses it reads "Unusually high for this hour
        // — a 1-in-13 reading for this hour", which says it twice.
        val whenPhrase = if (diurnal) " for this hour" else ""
        val span = sinceMs?.let { nowMs - it }
        val longUnmatched = span != null && span >= NOTABLE_SPAN_MS

        val note = when (basis) {
            Basis.RECORDED -> ""
            Basis.BACKFILLED -> " Judged against fetched history, not readings taken here."
            Basis.MIXED -> " Judged against a mix of fetched and recorded history."
        }

        // ⚠️ A reading can sit above the median and still be entirely ordinary — half of them do. When
        // there is nothing to report, the direction is not worth mentioning: see [NOTABLE_BITS], which
        // exists because real data produced "Unusually low — a 1-in-1 reading".
        //
        // A long-unmatched reading is exempt. On a trending series both can be true at once, and
        // "highest in two months" is a fact about the record worth stating whatever the tail says.
        if (-log2(p) < NOTABLE_BITS && !longUnmatched) return "Right in its usual range.$note"

        val lead = when {
            extreme && direction >= 0 -> "Highest on record"
            extreme -> "Lowest on record"
            direction > 0 && longUnmatched -> "Highest in ${spanPhrase(span!!)}"
            direction < 0 && longUnmatched -> "Lowest in ${spanPhrase(span!!)}"
            direction > 0 -> "Unusually high"
            else -> "Unusually low"
        }

        val rarity =
            // At the ceiling the number is a floor, not a measurement — say what the sample can show.
            if (extreme) "as rare as $n readings can show" else "a 1-in-${oneIn(p)} reading$whenPhrase"

        return "$lead — $rarity.$note"
    }

    /** A bare duration, where [ElapsedPhrase] would say "ago". "23 days", "3 hours", "2 months". */
    private fun spanPhrase(ms: Long): String {
        val v = abs(ms)
        return when {
            v < 90L * 60L * 1000L -> "${(v.toDouble() / (60L * 1000L)).roundToLong()} minutes"
            v < 36L * HOUR_MS -> "${(v.toDouble() / HOUR_MS).roundToLong()} hours"
            v < 25L * DAY_MS -> "${(v.toDouble() / DAY_MS).roundToLong()} days"
            v < 320L * DAY_MS -> "${(v.toDouble() / (30L * DAY_MS)).roundToLong()} months"
            else -> "${(v.toDouble() / (365L * DAY_MS)).roundToLong()} years"
        }
    }

    /** The "1-in-N" a tail probability describes, rounded the way a person would say it. */
    private fun oneIn(p: Double): Long {
        val raw = 1.0 / p
        return when {
            raw < 20 -> raw.roundToLong()
            raw < 100 -> (raw / 5.0).roundToLong() * 5
            raw < 1000 -> (raw / 10.0).roundToLong() * 10
            else -> (floor(raw / 100.0) * 100).roundToLong()
        }
    }
}
