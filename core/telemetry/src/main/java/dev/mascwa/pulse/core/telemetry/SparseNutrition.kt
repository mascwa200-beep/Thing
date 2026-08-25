package dev.mascwa.pulse.core.telemetry

/**
 * The arithmetic of a nutrient record where a missing figure means nobody measured it.
 *
 * ## Why this is a file rather than a habit
 *
 * [Micronutrients] worked out these rules first, for the eight vitamins and minerals both bundled
 * sources publish, and its own KDoc argues them at length: absent is not zero, adding two records
 * takes the **union** and not the intersection, and a day has to carry how many of its foods
 * actually reported a figure or the total reads as the day's intake when it is only the intake of
 * the foods that happened to say.
 *
 * [NutrientSet] needs exactly those rules again, for twenty-nine more nutrients. ⚠️ **Writing them
 * a second time is the duplicated-definition defect this project has corrected six times** — four
 * drifted copies of a colour palette, three of a day boundary, two of a "how old is this" sentence
 * — and the failure mode here is worse than a wrong colour: the two would agree for months and then
 * one of them would start reporting a day's magnesium as if every food had been measured.
 *
 * So the operations live here once, generic over whatever enum keys the map, and both callers are
 * thin. There is nothing clever in them; the value is that there is one of each.
 */
internal object SparseNutrition {

    /**
     * Scale a per-100-gram record to the portion actually eaten. Absences stay absent.
     *
     * ⚠️ A nonsensical factor yields an EMPTY record rather than a scaled-by-garbage one. Returning
     * the input unchanged would be worse: it would silently claim a portion of unknown size contains
     * exactly the per-100-gram figures.
     */
    fun <K> scale(values: Map<K, Double>, factor: Double): Map<K, Double> {
        if (!factor.isFinite() || factor < 0.0) return emptyMap()
        if (values.isEmpty()) return values
        return values.mapValues { it.value * factor }
    }

    /**
     * Two records added — an ingredient onto a running total.
     *
     * ⚠️ **The union, and absent stays absent on both sides.** If one ingredient records magnesium
     * and another does not, the sum is the one figure there is, and the reported count beside it is
     * what says how much of the dish that figure was drawn from. Treating the silent ingredient as
     * zero understates the total; refusing to add at all reports nothing for a dish that partly knows.
     */
    fun <K> merge(a: Map<K, Double>, b: Map<K, Double>): Map<K, Double> {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val out = LinkedHashMap(a)
        for ((k, v) in b) out[k] = (out[k] ?: 0.0) + v
        return out
    }

    /**
     * Fold one eaten portion into a day's running tallies.
     *
     * ⚠️ Non-finite and negative figures are skipped rather than added, and skipping them also does
     * not raise [NutrientTally.reported] — a value that cannot be believed is not a measurement, and
     * counting it would inflate the very number that exists to say how well founded the total is.
     */
    fun <K> tally(
        into: Map<K, NutrientTally>,
        values: Map<K, Double>,
    ): Map<K, NutrientTally> {
        if (values.isEmpty()) return into
        val out = LinkedHashMap(into)
        for ((k, v) in values) {
            if (!v.isFinite() || v < 0.0) continue
            val prior = out[k]
            out[k] = NutrientTally((prior?.total ?: 0.0) + v, (prior?.reported ?: 0) + 1)
        }
        return out
    }
}

/**
 * One nutrient across a day: how much, and out of how many foods it could be counted.
 *
 * ⚠️ [reported] is the point of the type. Without it, [total] is a number the reader will take as
 * their day's intake, and it is only the intake of the foods that happened to say.
 */
data class NutrientTally(val total: Double, val reported: Int)
