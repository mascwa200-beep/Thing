package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The operator's REAL LIFE, fed into the wasteland. The user enters their own body metrics (height,
 * weight, age), self-reports how much real money they have, and keeps two Sims-style needs topped up
 * (hydration, hygiene). All of it bleeds into the S.P.E.C.I.A.L. game: build and age shape which physical
 * attributes you favour, real-world wealth buys wasteland confidence + luck + fatter caps, and letting a
 * need slide taxes your checks until you drink / wash. Pure + deterministic → CI-testable; the on-device
 * layer persists it and never lets any of it (the money figure especially) leave the device.
 *
 * Everything is optional — 0 / unset means "not entered" and contributes nothing — so a blank profile is
 * fully neutral and existing saves load with no life effects at all.
 */
data class LifeProfile(
    /** Height in centimetres (0 = unset). Paired with [weightKg] for BMI → [Build]. */
    val heightCm: Int = 0,
    /** Weight in kilograms (0 = unset). */
    val weightKg: Int = 0,
    /** Age in years (0 = unset) → [AgeBand]. */
    val ageYears: Int = 0,
    /** Self-reported real-world balance → [MoneyTier]. ON-DEVICE ONLY — never transmitted or logged. */
    val realMoney: Double = 0.0,
    /** Display currency code for the money figure (cosmetic; the tier uses magnitude only). */
    val currency: String = "USD",
    /** Hydration need, 0..100 — decays with real time, restored by [LifeStats.drink]. */
    val hydration: Int = 100,
    /** Hygiene need, 0..100 — decays with real time, restored by [LifeStats.wash]. */
    val hygiene: Int = 100,
    /** Energy/rest need, 0..100 — decays with real time, restored by [LifeStats.rest]. */
    val energy: Int = 100,
    /** Nourishment/food need, 0..100 — decays with real time, restored by [LifeStats.eat]. */
    val nourishment: Int = 100,
    /** Mood, 0..100 (50 = neutral) — user-set; high spirits buff CHARISMA/LUCK, low spirits sap them. */
    val mood: Int = 50,
    /** Real steps walked today (from the device step counter) — an active day buffs ENDURANCE/AGILITY. */
    val stepsToday: Int = 0,
    /** Cosmetic operator name/callsign shown on the LIFE panel (no game effect). */
    val operatorName: String = "",
)

/** A single life-driven modifier to a stat check: [delta] to [stat], with a short human [reason]. */
data class LifeEffect(val stat: Special, val delta: Int, val reason: String)

/** Physical build archetype from BMI — each favours a different physical attribute (framed as a bonus). */
enum class Build(val label: String) {
    UNSET("—"),
    FEATHERWEIGHT("Featherweight"),
    ATHLETIC("Athletic"),
    RUGGED("Rugged"),
    POWERHOUSE("Powerhouse"),
}

/** Life stage from age — hard-won experience trades against youthful reflexes. */
enum class AgeBand(val label: String) {
    UNSET("—"),
    YOUNG("Young blood"),
    PRIME("In your prime"),
    SEASONED("Seasoned"),
    VETERAN("Grizzled veteran"),
}

/** Real-world wealth tier — more cash buys more wasteland confidence, luck, schooling and caps. */
enum class MoneyTier(val label: String, val capsBonusPct: Int) {
    BROKE("Scraping by", 0),
    STEADY("Getting by", 0),
    COMFORTABLE("Comfortable", 10),
    FLUSH("Flush", 20),
    LOADED("Loaded", 35),
}

/**
 * Pure logic mapping a [LifeProfile] to its game effects. [effects] is the single source of truth;
 * [statBonus] / [capsBonusPct] / [describe] are views over it, and the needs helpers evolve the profile.
 */
object LifeStats {
    // Needs thresholds (0..100).
    const val NEED_LOW = 30
    const val NEED_CRITICAL = 15

    // Need decay per hour of real time (points). Thirst builds faster than grime; energy sags between.
    const val HYDRATION_DECAY_PER_HR = 4.0
    const val HYGIENE_DECAY_PER_HR = 2.0
    const val ENERGY_DECAY_PER_HR = 3.0
    const val NOURISHMENT_DECAY_PER_HR = 3.5
    /** Energy recovered per hour while the phone is charging — plugged in ≈ resting up. */
    const val ENERGY_REGEN_PER_HR = 8.0
    private const val MS_PER_HOUR = 3_600_000.0

    // Real-world decay multipliers (the world drives how fast needs drain).
    private const val HOT_HYDRATION_X = 1.6
    private const val SCORCHING_HYDRATION_X = 2.2
    private const val COLD_ENERGY_X = 1.3
    private const val FRIGID_ENERGY_X = 1.5
    private const val NIGHT_ENERGY_X = 1.5
    private const val MOVING_HYDRATION_X = 1.3
    private const val MOVING_ENERGY_X = 1.4
    private const val MOVING_NOURISHMENT_X = 1.2
    private const val MOVING_HYGIENE_X = 1.3
    // Night is late evening / small hours (local).
    private const val NIGHT_START_HR = 22
    private const val NIGHT_END_HR = 6

    /** Max length of the cosmetic operator name. */
    const val MAX_NAME = 24

    // Mood band edges (0..100, 50 = neutral) — only the extremes bend checks.
    const val MOOD_HIGH = 75
    const val MOOD_LOW = 25

    // Real-world step thresholds — an active day keeps you fit (a full stride is the classic 10k goal).
    const val ACTIVE_STEPS = 5000
    const val STRIDE_STEPS = 10000

    // Money-tier thresholds, in the profile's currency units (magnitude only).
    const val STEADY_MONEY = 100.0
    const val COMFORTABLE_MONEY = 1_000.0
    const val FLUSH_MONEY = 10_000.0
    const val LOADED_MONEY = 100_000.0

    // Input clamps (defensive against absurd typed values).
    const val MAX_HEIGHT_CM = 300
    const val MAX_WEIGHT_KG = 700
    const val MAX_AGE = 130

    /** BMI (kg/m²), or null if height and weight aren't both set. */
    fun bmi(p: LifeProfile): Double? {
        if (p.heightCm <= 0 || p.weightKg <= 0) return null
        val m = p.heightCm / 100.0
        return p.weightKg / (m * m)
    }

    /** Build archetype from BMI ([Build.UNSET] when height/weight aren't both entered). */
    fun build(p: LifeProfile): Build {
        val b = bmi(p) ?: return Build.UNSET
        return when {
            b < 18.5 -> Build.FEATHERWEIGHT
            b < 25.0 -> Build.ATHLETIC
            b < 30.0 -> Build.RUGGED
            else -> Build.POWERHOUSE
        }
    }

    /** Life stage from age ([AgeBand.UNSET] when age isn't entered). */
    fun ageBand(p: LifeProfile): AgeBand = when {
        p.ageYears <= 0 -> AgeBand.UNSET
        p.ageYears < 25 -> AgeBand.YOUNG
        p.ageYears < 40 -> AgeBand.PRIME
        p.ageYears < 60 -> AgeBand.SEASONED
        else -> AgeBand.VETERAN
    }

    /** Wealth tier from the self-reported real-money magnitude. */
    fun moneyTier(p: LifeProfile): MoneyTier {
        val m = abs(p.realMoney)
        return when {
            m < STEADY_MONEY -> MoneyTier.BROKE
            m < COMFORTABLE_MONEY -> MoneyTier.STEADY
            m < FLUSH_MONEY -> MoneyTier.COMFORTABLE
            m < LOADED_MONEY -> MoneyTier.FLUSH
            else -> MoneyTier.LOADED
        }
    }

    /** The full stacked set of life-driven check modifiers. Deterministic; empty for a blank profile. */
    fun effects(p: LifeProfile): List<LifeEffect> {
        val out = mutableListOf<LifeEffect>()

        when (build(p)) {
            Build.FEATHERWEIGHT -> out += LifeEffect(Special.AGILITY, +1, "Featherweight build")
            Build.ATHLETIC -> out += LifeEffect(Special.ENDURANCE, +1, "Athletic build")
            Build.RUGGED -> out += LifeEffect(Special.STRENGTH, +1, "Rugged build")
            Build.POWERHOUSE -> {
                out += LifeEffect(Special.STRENGTH, +2, "Powerhouse build")
                out += LifeEffect(Special.AGILITY, -1, "Heavy on your feet")
            }
            Build.UNSET -> Unit
        }

        when (ageBand(p)) {
            AgeBand.YOUNG -> out += LifeEffect(Special.AGILITY, +1, "Young reflexes")
            AgeBand.PRIME -> out += LifeEffect(Special.ENDURANCE, +1, "In your prime")
            AgeBand.SEASONED -> out += LifeEffect(Special.INTELLIGENCE, +1, "Seasoned wits")
            AgeBand.VETERAN -> {
                out += LifeEffect(Special.INTELLIGENCE, +1, "Hard-won wisdom")
                out += LifeEffect(Special.PERCEPTION, +1, "Nothing surprises you")
                out += LifeEffect(Special.AGILITY, -1, "Old bones")
            }
            AgeBand.UNSET -> Unit
        }

        when (moneyTier(p)) {
            MoneyTier.BROKE -> Unit
            MoneyTier.STEADY -> out += LifeEffect(Special.CHARISMA, +1, "Coin in your pocket")
            MoneyTier.COMFORTABLE -> {
                out += LifeEffect(Special.CHARISMA, +1, "Comfortable means")
                out += LifeEffect(Special.LUCK, +1, "A cushion to fall back on")
            }
            MoneyTier.FLUSH -> {
                out += LifeEffect(Special.CHARISMA, +1, "Flush with cash")
                out += LifeEffect(Special.LUCK, +1, "Fortune favours the funded")
                out += LifeEffect(Special.INTELLIGENCE, +1, "Bought the good schooling")
            }
            MoneyTier.LOADED -> {
                out += LifeEffect(Special.CHARISMA, +2, "Loaded")
                out += LifeEffect(Special.LUCK, +2, "Money makes its own luck")
                out += LifeEffect(Special.INTELLIGENCE, +1, "Bought the good schooling")
            }
        }

        // Needs let slide tax the body + presence until you top them up.
        if (p.hydration <= NEED_CRITICAL) {
            out += LifeEffect(Special.ENDURANCE, -2, "Severely dehydrated")
            out += LifeEffect(Special.STRENGTH, -1, "Wrung out")
        } else if (p.hydration <= NEED_LOW) {
            out += LifeEffect(Special.ENDURANCE, -1, "Thirsty")
        }
        if (p.hygiene <= NEED_CRITICAL) {
            out += LifeEffect(Special.CHARISMA, -2, "Filthy")
        } else if (p.hygiene <= NEED_LOW) {
            out += LifeEffect(Special.CHARISMA, -1, "Unkempt")
        }
        if (p.energy <= NEED_CRITICAL) {
            out += LifeEffect(Special.AGILITY, -2, "Exhausted")
            out += LifeEffect(Special.INTELLIGENCE, -1, "Foggy-headed")
        } else if (p.energy <= NEED_LOW) {
            out += LifeEffect(Special.AGILITY, -1, "Weary")
        }
        if (p.nourishment <= NEED_CRITICAL) {
            out += LifeEffect(Special.STRENGTH, -2, "Starving")
            out += LifeEffect(Special.ENDURANCE, -1, "Running on empty")
        } else if (p.nourishment <= NEED_LOW) {
            out += LifeEffect(Special.STRENGTH, -1, "Hungry")
        }

        // Mood only tips the scales at the extremes.
        if (p.mood >= MOOD_HIGH) {
            out += LifeEffect(Special.CHARISMA, +1, "High spirits")
            out += LifeEffect(Special.LUCK, +1, "Feeling lucky")
        } else if (p.mood <= MOOD_LOW) {
            out += LifeEffect(Special.CHARISMA, -1, "Low spirits")
        }

        // A real active day keeps you fit — steps buff the body.
        if (p.stepsToday >= STRIDE_STEPS) {
            out += LifeEffect(Special.ENDURANCE, +1, "In stride (10k+ steps)")
            out += LifeEffect(Special.AGILITY, +1, "Light on your feet")
        } else if (p.stepsToday >= ACTIVE_STEPS) {
            out += LifeEffect(Special.ENDURANCE, +1, "Active today (5k+ steps)")
        }

        return out
    }

    /** Net life modifier to a check gated by [s] (sum of matching [effects]). */
    fun statBonus(p: LifeProfile, s: Special): Int = effects(p).filter { it.stat == s }.sumOf { it.delta }

    /** The caps-reward boost the wealth tier grants (a % applied to positive caps rewards). */
    fun capsBonusPct(p: LifeProfile): Int = moneyTier(p).capsBonusPct

    /** One-line UI labels for the active effects, e.g. "CHA +1 · Coin in your pocket". */
    fun describe(p: LifeProfile): List<String> = effects(p).map { e ->
        val sign = if (e.delta >= 0) "+" else "−"
        "${e.stat.display.take(3)} $sign${abs(e.delta)} · ${e.reason}"
    }

    // --- Needs upkeep ---

    /** Decay the needs for [elapsedMs] of real time at the base rates (no real-world context). */
    fun decayNeeds(p: LifeProfile, elapsedMs: Long): LifeProfile = decayNeeds(p, elapsedMs, null)

    /**
     * Decay the needs for [elapsedMs] of real time, with the real world ([env]) driving the rates: heat
     * spikes thirst, cold / night / motion drain energy faster, motion also burns food + fouls hygiene —
     * and while the phone is CHARGING, energy RECOVERS (plugged in ≈ resting). Clamped 0..100; deterministic.
     * [env] = null → the plain base rates (back-compatible).
     */
    fun decayNeeds(p: LifeProfile, elapsedMs: Long, env: EnvContext?): LifeProfile {
        if (elapsedMs <= 0) return p
        val hrs = elapsedMs / MS_PER_HOUR
        val tmp = env?.outdoorTempC
        val scorching = tmp != null && tmp >= Environment.SCORCHING_C
        val hot = tmp != null && tmp >= Environment.HOT_C
        val frigid = tmp != null && tmp <= Environment.FRIGID_C
        val cold = tmp != null && tmp <= Environment.COLD_C
        val night = env != null && (!env.isDay || env.hourOfDay >= NIGHT_START_HR || env.hourOfDay < NIGHT_END_HR)
        val moving = (env?.movement ?: 0f) >= Environment.MOVING_INTENSITY
        val charging = env?.charging == true

        val hydMult = (if (scorching) SCORCHING_HYDRATION_X else if (hot) HOT_HYDRATION_X else 1.0) *
            (if (moving) MOVING_HYDRATION_X else 1.0)
        val enMult = (if (frigid) FRIGID_ENERGY_X else if (cold) COLD_ENERGY_X else 1.0) *
            (if (night) NIGHT_ENERGY_X else 1.0) * (if (moving) MOVING_ENERGY_X else 1.0)
        val nourMult = if (moving) MOVING_NOURISHMENT_X else 1.0
        val hygMult = if (moving) MOVING_HYGIENE_X else 1.0

        val hyd = (p.hydration - (hrs * HYDRATION_DECAY_PER_HR * hydMult).roundToInt()).coerceIn(0, 100)
        val hyg = (p.hygiene - (hrs * HYGIENE_DECAY_PER_HR * hygMult).roundToInt()).coerceIn(0, 100)
        val nour = (p.nourishment - (hrs * NOURISHMENT_DECAY_PER_HR * nourMult).roundToInt()).coerceIn(0, 100)
        val en = if (charging) (p.energy + (hrs * ENERGY_REGEN_PER_HR).roundToInt()).coerceIn(0, 100)
            else (p.energy - (hrs * ENERGY_DECAY_PER_HR * enMult).roundToInt()).coerceIn(0, 100)
        return p.copy(hydration = hyd, hygiene = hyg, energy = en, nourishment = nour)
    }

    /** Human labels for the real-world factors currently driving need decay — for a live UI readout. */
    fun needDrivers(env: EnvContext?): List<String> {
        if (env == null) return emptyList()
        val out = mutableListOf<String>()
        val tmp = env.outdoorTempC
        when {
            tmp != null && tmp >= Environment.SCORCHING_C -> out += "Scorching — thirst spiking"
            tmp != null && tmp >= Environment.HOT_C -> out += "Heat — thirst rising"
            tmp != null && tmp <= Environment.FRIGID_C -> out += "Frigid — tiring fast"
            tmp != null && tmp <= Environment.COLD_C -> out += "Cold — tiring"
        }
        if (!env.isDay || env.hourOfDay >= NIGHT_START_HR || env.hourOfDay < NIGHT_END_HR) {
            out += "Late hours — weariness setting in"
        }
        if ((env.movement ?: 0f) >= Environment.MOVING_INTENSITY) out += "On the move — burning energy & water"
        if (env.charging) out += "Charging — resting up (energy recovering)"
        return out
    }

    /** Top up hydration (a drink). */
    fun drink(p: LifeProfile): LifeProfile = p.copy(hydration = 100)

    /** Freshen up (a wash). */
    fun wash(p: LifeProfile): LifeProfile = p.copy(hygiene = 100)

    /** Rest up — restore energy. */
    fun rest(p: LifeProfile): LifeProfile = p.copy(energy = 100)

    /** Eat — restore nourishment. */
    fun eat(p: LifeProfile): LifeProfile = p.copy(nourishment = 100)

    // --- Clamped setters (0 / blank clears an unset field) ---
    fun withHeight(p: LifeProfile, cm: Int): LifeProfile = p.copy(heightCm = cm.coerceIn(0, MAX_HEIGHT_CM))
    fun withWeight(p: LifeProfile, kg: Int): LifeProfile = p.copy(weightKg = kg.coerceIn(0, MAX_WEIGHT_KG))
    fun withAge(p: LifeProfile, years: Int): LifeProfile = p.copy(ageYears = years.coerceIn(0, MAX_AGE))
    fun withMoney(p: LifeProfile, amount: Double): LifeProfile = p.copy(realMoney = amount.coerceAtLeast(0.0))
    fun withMood(p: LifeProfile, mood: Int): LifeProfile = p.copy(mood = mood.coerceIn(0, 100))
    fun withName(p: LifeProfile, name: String): LifeProfile = p.copy(operatorName = name.take(MAX_NAME))
    fun withSteps(p: LifeProfile, steps: Int): LifeProfile = p.copy(stepsToday = steps.coerceAtLeast(0))
}
