package dev.mascwa.pulse.core.telemetry

/**
 * Getting from what a frightened person types to the protocol that helps.
 *
 * [GuideSearch] ranks the whole library well, and on an emergency it is dangerous. Run against the
 * real corpus it answers *"stroke symptoms"* with **How a Two-Stroke Engine Works**, *"not breathing"*
 * with **Uphill Walking Technique and Breathing**, and *"severe allergic reaction"* with **Severe
 * Weather: Storms, Tornadoes and Hurricanes** — because a scorer sees letters, and "stroke" is a
 * word two unrelated subjects share. That is a tolerable failure for a question about bread and an
 * intolerable one here.
 *
 * So this layer is **curated, not inferred**, and it is consulted *before* ranking. Symptom-to-protocol
 * routing is the one part of a safety library that must never be a lexical accident; every mapping
 * below was written by hand against the real section headings, and a test resolves each one against
 * the bundled index so a retitled section breaks the build instead of the routing.
 *
 * ⚠️ **Where the library has nothing, this says so** ([UNCOVERED]) rather than offering the nearest
 * lexical match. A wrong protocol read out in an emergency is worse than an admitted gap, and the
 * first action — call for help — is the same either way.
 *
 * None of this is a substitute for training or for emergency services. It is an index.
 */
object EmergencyTriage {

    /**
     * One recognised emergency.
     *
     * [firstAction] leads every answer. It is the instruction that does not change with the
     * situation's details and does not wait for the rest of the text to be read.
     */
    data class Emergency(
        val id: String,
        val label: String,
        val firstAction: String,
        /** The guide holding the protocol, or null when the library does not cover this yet. */
        val guideId: String? = null,
        /** The exact section heading inside [guideId]. */
        val section: String? = null,
        /** What someone actually types or says. Matched as phrases, longest first. */
        val cues: List<String> = emptyList(),
    ) {
        val covered: Boolean get() = guideId != null && section != null
    }

    private const val CALL = "Call emergency services now — or have someone else call while you work."

    /**
     * The routing table.
     *
     * Ordered by how fast the situation kills, because [match] returns the first entry whose cue is
     * present and a query like "he's not breathing and bleeding" should surface the airway, not the
     * bleeding.
     */
    val EMERGENCIES: List<Emergency> = listOf(
        Emergency(
            id = "cardiac_arrest",
            label = "Not breathing / no pulse",
            firstAction = "$CALL Start chest compressions: centre of the chest, hard and fast, " +
                "about twice a second. Send someone for a defibrillator.",
            guideId = "first-aid", section = "CPR (adult)",
            cues = listOf(
                "not breathing", "isn't breathing", "is not breathing", "stopped breathing",
                "no pulse", "no heartbeat", "cardiac arrest", "heart stopped", "unresponsive and not breathing",
                "cpr", "resuscitate", "resuscitation", "chest compressions", "collapsed and not breathing",
            ),
        ),
        Emergency(
            id = "choking",
            label = "Choking",
            firstAction = "If they can cough, let them cough. If they cannot cough, speak or breathe: " +
                "five sharp back blows between the shoulder blades, then five abdominal thrusts. $CALL",
            guideId = "first-aid", section = "Choking",
            cues = listOf("choking", "choked", "can't breathe food", "something stuck in throat", "airway blocked"),
        ),
        Emergency(
            id = "severe_bleeding",
            label = "Severe bleeding",
            firstAction = "$CALL Press hard directly on the wound and keep pressing. Do not lift off to look.",
            guideId = "first-aid", section = "Severe bleeding",
            cues = listOf(
                "bleeding badly", "bleeding heavily", "severe bleeding", "won't stop bleeding",
                "bleeding out", "lots of blood", "arterial bleed", "tourniquet", "deep cut bleeding",
            ),
        ),
        Emergency(
            id = "anaphylaxis",
            label = "Severe allergic reaction",
            firstAction = "$CALL If an adrenaline auto-injector is available, use it now — outer thigh. " +
                "Lie them flat with legs raised unless breathing is easier sitting up.",
            guideId = "med-common-illnesses-red-flags",
            section = "Serious systemic red flags: sepsis and anaphylaxis",
            cues = listOf(
                "anaphylaxis", "anaphylactic", "severe allergic reaction", "allergic reaction",
                "epipen", "epi pen", "auto injector", "throat closing", "face swelling allergy",
                "swelling and can't breathe",
            ),
        ),
        Emergency(
            id = "stroke",
            label = "Stroke",
            firstAction = "$CALL Check FAST — Face drooping, Arm weakness, Speech difficulty, Time to call. " +
                "Note when the symptoms started; treatment depends on it.",
            guideId = "med-common-illnesses-red-flags",
            section = "Headaches and neurological red flags (stroke: FAST)",
            cues = listOf(
                "stroke symptoms", "having a stroke", "face drooping", "face is drooping",
                "slurred speech", "sudden weakness one side", "can't speak suddenly", "fast test",
            ),
        ),
        Emergency(
            id = "heart_attack",
            label = "Heart attack",
            firstAction = "$CALL Sit them down and keep them still and calm. Do not let them walk it off.",
            guideId = "med-common-illnesses-red-flags",
            section = "Chest pain, heart attack, and cardiac red flags",
            cues = listOf(
                "heart attack", "chest pain", "crushing chest", "pain in chest and arm",
                "cardiac", "angina", "pain down left arm",
            ),
        ),
        Emergency(
            id = "unconscious",
            label = "Unconscious but breathing",
            firstAction = "$CALL If they are breathing, roll them into the recovery position and " +
                "keep watching that the breathing continues.",
            guideId = "first-aid", section = "Recovery position",
            cues = listOf(
                "unconscious", "passed out", "won't wake up", "wont wake up", "unresponsive",
                "fainted", "recovery position", "knocked out",
            ),
        ),
        Emergency(
            id = "poisoning",
            label = "Poisoning or overdose",
            firstAction = "$CALL Do not make them vomit. Have the container or the name of what was " +
                "taken ready to read out.",
            guideId = "med-poisoning-overdose-response", section = "The First Response — What to Do, and What NOT to Do",
            cues = listOf(
                "poisoned", "poisoning", "overdose", "swallowed bleach", "swallowed chemicals",
                "took too many pills", "drank something", "naloxone", "narcan",
            ),
        ),
        Emergency(
            id = "burn",
            label = "Burn",
            firstAction = "Cool the burn under cool running water for twenty minutes. Nothing else on " +
                "it — no ice, no butter, no cream. Remove tight items before swelling starts.",
            guideId = "first-aid", section = "Burns",
            // Not a bare "burn": that fires on calorie burn and controlled burns. These are all
            // phrasings about a person having been burned.
            cues = listOf(
                "burned", "burnt", "scalded", "scald", "burn on", "burn my", "burn his", "burn her",
                "hot oil", "boiling water on", "touched something hot", "treat a burn", "burn blister",
            ),
        ),
        Emergency(
            id = "drowning",
            label = "Drowning",
            firstAction = "$CALL Do not swim out. Reach, throw, row — go only as the last resort and " +
                "only if trained.",
            guideId = "rescue-water-rescue-basics", section = "The Rescue Hierarchy: Reach, Throw, Row, Go",
            cues = listOf("drowning", "drowned", "fell in the water", "under the water", "went under"),
        ),
        Emergency(
            id = "hypothermia",
            label = "Hypothermia",
            firstAction = "Get them out of the cold and out of wet clothing. Warm the core gradually; " +
                "handle them gently. $CALL",
            guideId = "cold", section = "Spot hypothermia",
            cues = listOf("hypothermia", "freezing to death", "too cold shivering", "cold and confused"),
        ),
        Emergency(
            id = "snake_bite",
            label = "Snake or scorpion bite",
            firstAction = "$CALL Keep them still and keep the bite below heart level. No tourniquet, " +
                "no cutting, no sucking, no ice.",
            guideId = "wildlife", section = "Snakes & scorpions",
            cues = listOf("snake bite", "snakebite", "bitten by a snake", "scorpion sting", "venomous bite"),
        ),
        Emergency(
            id = "fracture",
            label = "Broken bone",
            firstAction = "Do not straighten it. Support the limb in the position you found it and " +
                "immobilise it before moving them.",
            guideId = "first-aid", section = "Splinting a fracture",
            cues = listOf("broken arm", "broken leg", "broken bone", "fracture", "bone sticking out", "broken wrist"),
        ),
        Emergency(
            id = "shock",
            label = "Shock",
            firstAction = "$CALL Lie them down, raise the legs, keep them warm. Nothing to eat or drink.",
            guideId = "first-aid", section = "Shock",
            cues = listOf("in shock", "going into shock", "pale clammy", "cold and clammy"),
        ),

        // ---- the protocols written for this arc ---------------------------------------------------
        // These four had no page anywhere in the library -- not in a title, a summary or a heading --
        // which is why they were answered by articles on osmosis, two-stroke engines and soap-making.
        Emergency(
            id = "seizure",
            label = "Seizure",
            firstAction = "Do not restrain them and do not put anything in their mouth. Move hard " +
                "objects away, cushion the head, and time it. $CALL if it lasts over five minutes, " +
                "repeats, or they do not wake up afterwards.",
            guideId = "med-seizure-first-aid", section = "What to do while it is happening",
            cues = listOf("seizure", "seizing", "convulsing", "convulsion", "having a fit", "epileptic fit", "epilepsy"),
        ),
        Emergency(
            id = "head_injury",
            label = "Head injury",
            firstAction = "$CALL if they lost consciousness, are confused, vomiting, or worsening. " +
                "Keep them still and do not let them sleep it off unwatched.",
            guideId = "med-head-injury-concussion", section = "The red flags that mean call now",
            cues = listOf("head injury", "concussion", "hit their head", "hit his head", "hit her head", "banged head"),
        ),
        Emergency(
            id = "electric_shock",
            label = "Electric shock",
            firstAction = "Do not touch them until the power is off. Cut the supply at the source, " +
                "then $CALL",
            guideId = "med-electric-shock", section = "Do not touch them",
            cues = listOf("electric shock", "electrocuted", "electrocution", "shocked by electricity", "touched a live wire"),
        ),
        Emergency(
            id = "heat_stroke",
            label = "Heat stroke",
            firstAction = "$CALL Move them into shade, cool them aggressively — water on the skin, " +
                "fanning, ice at the neck, armpits and groin.",
            guideId = "med-heat-stroke-illness", section = "Cooling, properly and fast",
            cues = listOf("heat stroke", "heatstroke", "heat exhaustion", "overheating collapsed", "too hot collapsed"),
        ),
        Emergency(
            id = "low_blood_sugar",
            label = "Diabetic emergency",
            firstAction = "If they are awake and can swallow, give sugar — juice, glucose, sweets. " +
                "If they cannot swallow or do not improve in ten minutes, $CALL",
            cues = listOf(
                "low blood sugar", "hypoglycemia", "hypoglycaemia", "diabetic emergency",
                "insulin reaction", "hypo", "blood sugar crash",
            ),
        ),
    )

    /** Recognised emergencies the library has no protocol for. Their first action still stands. */
    val UNCOVERED: List<Emergency> get() = EMERGENCIES.filter { !it.covered }

    /**
     * The emergency [query] describes, or null.
     *
     * Phrase matching, deliberately: a cue is a whole phrase inside the normalised query, so
     * *"having a stroke"* cannot be beaten by an engine and *"burn"* cannot be triggered by
     * *"Burnley"*. Longest cue first within an entry, and table order decides between entries —
     * which is why the table is ordered by how fast the situation kills.
     */
    fun match(query: String): Emergency? {
        val q = normalise(query)
        if (q.isBlank()) return null
        return EMERGENCIES.firstOrNull { e ->
            e.cues.sortedByDescending { it.length }.any { cue -> containsPhrase(q, normalise(cue)) }
        }
    }

    /** Lowercase, punctuation to spaces, runs collapsed — and padded, so a phrase test is word-safe. */
    internal fun normalise(s: String): String =
        " " + s.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
            .split(' ').filter { it.isNotBlank() }.joinToString(" ") + " "

    /** True when [needle] appears in [haystack] on whole-word boundaries. Both must be normalised. */
    private fun containsPhrase(haystack: String, needle: String): Boolean =
        needle.isNotBlank() && haystack.contains(needle)

    /**
     * What to show for a matched emergency: the action first, then where to read, then the caveat.
     *
     * The order is the whole point. Someone reading this has seconds of attention, and the sentence
     * that has to survive being the only one read is the one that gets them help coming.
     */
    fun brief(e: Emergency): String = buildString {
        append(e.label.uppercase()).append("\n")
        append(e.firstAction)
        if (!e.covered) {
            append("\n\nThe library has no page on this yet — that is why there is no guide named ")
            append("below. Do not substitute a page about something else.")
        }
    }
}
