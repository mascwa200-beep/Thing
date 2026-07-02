package dev.mascwa.pulse.core.telemetry

/**
 * Survival check-ins for the S.P.E.C.I.A.L. life-sim: when one of the operator's real-decaying needs runs
 * low, the game pushes a themed nudge to keep them alive (drink, eat, rest, wash) — which, since the needs
 * bleed into reality, doubles as a real-world reminder to look after yourself. Pure + deterministic (the
 * message is picked by an injected seed, not a clock/RNG) → CI-testable; the on-device worker reads the
 * live profile, calls [evaluate], and posts throttled notifications.
 */

/** Which real-decaying need triggered a check-in. */
enum class SurvivalNeed(val label: String, val verb: String) {
    HYDRATION("HYDRATION", "DRINK"),
    NOURISHMENT("NOURISHMENT", "EAT"),
    ENERGY("ENERGY", "REST"),
    HYGIENE("HYGIENE", "WASH"),
}

/** How dire the need is. */
enum class AlertLevel { LOW, CRITICAL }

/** A survival check-in the game wants to push. */
data class SurvivalAlert(
    val need: SurvivalNeed,
    val level: AlertLevel,
    val title: String,
    val body: String,
)

object SurvivalAlerts {

    /**
     * Every survival check-in the current [life] warrants — one per need that's LOW (≤ [LifeStats.NEED_LOW])
     * or CRITICAL (≤ [LifeStats.NEED_CRITICAL]). [seed] rotates the message so a repeat nag doesn't read
     * identically. Deterministic given (life, seed).
     */
    fun evaluate(life: LifeProfile, seed: Long): List<SurvivalAlert> {
        val out = mutableListOf<SurvivalAlert>()
        add(out, SurvivalNeed.HYDRATION, life.hydration, seed)
        add(out, SurvivalNeed.NOURISHMENT, life.nourishment, seed)
        add(out, SurvivalNeed.ENERGY, life.energy, seed)
        add(out, SurvivalNeed.HYGIENE, life.hygiene, seed)
        return out
    }

    private fun add(out: MutableList<SurvivalAlert>, need: SurvivalNeed, value: Int, seed: Long) {
        val level = when {
            value <= LifeStats.NEED_CRITICAL -> AlertLevel.CRITICAL
            value <= LifeStats.NEED_LOW -> AlertLevel.LOW
            else -> return
        }
        val pool = messages(need, level)
        val body = pool[floorMod(seed + need.ordinal, pool.size)]
        out += SurvivalAlert(need, level, titleFor(need, level, value), body)
    }

    private fun titleFor(need: SurvivalNeed, level: AlertLevel, value: Int): String = when (level) {
        AlertLevel.CRITICAL -> "☠ ${need.label} CRITICAL · ${value}%"
        AlertLevel.LOW -> "⚠ ${need.label} LOW · ${value}%"
    }

    private fun floorMod(a: Long, n: Int): Int {
        if (n <= 0) return 0
        val m = (a % n).toInt()
        return if (m < 0) m + n else m
    }

    /** The themed message catalogs — a good spread so the check-ins stay fresh. */
    private fun messages(need: SurvivalNeed, level: AlertLevel): List<String> = when (need) {
        SurvivalNeed.HYDRATION -> when (level) {
            AlertLevel.LOW -> listOf(
                "Thirst is setting in. Find water and DRINK, operator.",
                "Your canteen's running low — hydrate before the wasteland dries you out.",
                "Mouth going dry. Take a drink; a parched operator makes mistakes.",
                "Water's low. A sip now saves a stumble later.",
                "The heat's pulling water out of you. Top up.",
            )
            AlertLevel.CRITICAL -> listOf(
                "DEHYDRATION CRITICAL — drink NOW or you'll go down out there.",
                "You're wrung out. Water. Immediately. This is how operators fall.",
                "Critical thirst. Every check is failing. DRINK.",
                "Bone dry and fading. Get water in you before it's too late.",
                "Severe dehydration — endurance is gone. Hydrate this instant.",
            )
        }
        SurvivalNeed.NOURISHMENT -> when (level) {
            AlertLevel.LOW -> listOf(
                "Hunger's gnawing. Time to EAT, operator.",
                "Rations running low — grab a bite before your strength dips.",
                "Stomach's empty. Eat something; you'll need the muscle.",
                "Low on fuel. A meal now keeps you swinging.",
                "The wasteland's long and you're getting hungry. Eat.",
            )
            AlertLevel.CRITICAL -> listOf(
                "STARVING — strength is failing. EAT before you collapse.",
                "Running on empty. Your body's eating itself. Get food NOW.",
                "Critical hunger — you can barely lift a thing. EAT.",
                "You're gaunt and weak. Food. Right now.",
                "Starvation set in. STR and END are shot. Eat immediately.",
            )
        }
        SurvivalNeed.ENERGY -> when (level) {
            AlertLevel.LOW -> listOf(
                "You're flagging. Find a spot and REST soon.",
                "Eyelids heavy. Rest up before your reflexes go.",
                "Energy dipping — a break now beats a blackout later.",
                "Wearing thin. Rest, or plug in and let it recover.",
                "You're dragging. Catch some rest, operator.",
            )
            AlertLevel.CRITICAL -> listOf(
                "RUNNING ON FUMES — rest NOW before you drop.",
                "Exhausted. Your head's fogged and your feet are slow. REST.",
                "Critical fatigue — you're a liability out there. Rest immediately.",
                "You're about to collapse. Sit down and recover.",
                "Dead on your feet. Rest, or charge up — but do it now.",
            )
        }
        SurvivalNeed.HYGIENE -> when (level) {
            AlertLevel.LOW -> listOf(
                "You're getting ragged. Clean up — presence counts out here.",
                "Grime's building. A wash keeps folk willing to deal with you.",
                "Looking rough. Freshen up before the traders turn away.",
                "Days of dust on you. Time to WASH.",
                "You could use a scrub, operator. Charisma's slipping.",
            )
            AlertLevel.CRITICAL -> listOf(
                "FILTHY — nobody'll trade with you like this. Wash NOW.",
                "You reek. Clean up before every conversation goes sideways.",
                "Caked in grime — your charisma's in the dirt. WASH.",
                "You're a mess and it shows. Get clean, immediately.",
                "Critical hygiene — people are recoiling. Wash up.",
            )
        }
    }
}
