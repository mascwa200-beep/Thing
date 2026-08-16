package dev.mascwa.pulse.core.telemetry

/**
 * Which safety sources can see the place you are standing, and which cannot.
 *
 * The nearby-safety feed draws on four sources, and two of them are region-locked: US weather alerts
 * and England/Wales/NI street crime. Everywhere else, those two return nothing by construction — and
 * the screen said "No incidents reported near you right now", which is a claim about the world when
 * it should have been a statement about our reach. Three different situations were rendering
 * identically: the source looked and found nothing, the source does not operate here, and the source
 * failed.
 *
 * Pure and deterministic — the caller reports what each source actually did — so CI can hold it.
 */
object SafetyCoverage {

    /** Where each source can see, and how confidently we can tell. */
    enum class Source(val label: String, val scope: String) {
        QUAKES("Earthquakes", "worldwide"),
        DISASTERS("Major disasters", "worldwide"),
        WEATHER_ALERTS("Weather alerts", "United States only"),
        STREET_CRIME("Street crime", "England, Wales and Northern Ireland only"),
    }

    /** What happened to a source on this fetch. */
    enum class Availability {
        /** It covers here and answered. */
        COVERED,

        /** It does not operate here — an absence of results means nothing. */
        NOT_COVERED,

        /** It covers here and the request failed, so we genuinely do not know. */
        FAILED,

        /** Not yet established. */
        UNKNOWN,
    }

    /**
     * Whether street-crime data is published where you are.
     *
     * ⚠️ Estimated, and it has to be. Asked about Berlin, about Edinburgh, and about a genuinely
     * quiet English village, `data.police.uk` answers `200 []` to all three — the API cannot tell us
     * whether it covers a place, so geography is the only signal left. (The US weather feed is the
     * opposite: it returns an explicit "out of bounds" error, so that one is taken from the source
     * rather than guessed. Where a source will say, we ask it.)
     *
     * Three boxes rather than one, because a single rectangle cannot describe this. Stretching the
     * England/Wales box west far enough to reach the Isles of Scilly also reaches Dublin, which sits
     * at Welsh latitudes across the sea — a test caught exactly that. So Scilly gets its own small
     * box and the mainland's western edge stops at Land's End.
     *
     * Where the boxes are still wrong they deliberately **over**-claim rather than under-claim.
     * Getting it wrong generously leaves a user exactly where they are today, seeing an empty list
     * with no note; the stingy direction would tell someone in England that street crime is not
     * published for their area while the app is holding data that says otherwise. What that costs:
     * the Scottish southern uplands read as covered, as does Irish territory along the Northern
     * Ireland border. What it does not cost: every city on either side lands correctly — Glasgow at
     * 55.86, Edinburgh at 55.95 and Dundee at 56.46 all sit north of the mainland box, and Dublin
     * falls outside all three.
     */
    fun crimeCoverage(lat: Double, lon: Double): Availability {
        if (!lat.isFinite() || !lon.isFinite()) return Availability.UNKNOWN
        val mainland = lat in 49.90..55.81 && lon in -5.75..1.77
        val scilly = lat in 49.85..49.99 && lon in -6.45..-6.25
        val northernIreland = lat in 54.02..55.31 && lon in -8.18..-5.43
        return if (mainland || scilly || northernIreland) Availability.COVERED else Availability.NOT_COVERED
    }

    /**
     * The line under an empty list.
     *
     * Null when every source that covers this place answered — then "nothing reported nearby" is
     * simply true and needs no footnote. It appears only when the silence is partly ours: a source
     * that does not reach here, or one that failed.
     *
     * Failures are named before gaps, because a failure might resolve on a retry and a gap will not.
     */
    fun explainSilence(states: Map<Source, Availability>): String? {
        val failed = Source.entries.filter { states[it] == Availability.FAILED }
        val absent = Source.entries.filter { states[it] == Availability.NOT_COVERED }
        if (failed.isEmpty() && absent.isEmpty()) return null

        val parts = mutableListOf<String>()
        if (failed.isNotEmpty()) {
            parts += "${list(failed.map { it.label })} couldn't be reached just now"
        }
        if (absent.isNotEmpty()) {
            val verb = if (absent.size == 1) "isn't published" else "aren't published"
            parts += "${list(absent.map { it.label.lowercase() })} $verb for your area"
        }
        return parts.joinToString("; ").replaceFirstChar { it.uppercase() } + "."
    }

    /**
     * A one-line note for the sources that do reach here, so the reader knows what WAS checked.
     *
     * Silence is only reassuring if you know what was listening.
     */
    fun describeChecked(states: Map<Source, Availability>): String? {
        val covered = Source.entries.filter { states[it] == Availability.COVERED }
        if (covered.isEmpty()) return null
        return "Checked: ${list(covered.map { it.label.lowercase() })}."
    }

    /** "a", "a and b", "a, b and c" — an Oxford-comma-free list, as the rest of the app writes them. */
    private fun list(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        2 -> "${items[0]} and ${items[1]}"
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }
}
