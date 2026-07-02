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
    private const val MS_PER_HOUR = 3_600_000.0

    /** Max length of the cosmetic operator name. */
    const val MAX_NAME = 24

    // Mood band edges (0..100, 50 = neutral) — only the extremes bend checks.
    const val MOOD_HIGH = 75
    const val MOOD_LOW = 25

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

    /** Decay hydration + hygiene + energy for [elapsedMs] of real time (clamped to 0..100). Deterministic. */
    fun decayNeeds(p: LifeProfile, elapsedMs: Long): LifeProfile {
        if (elapsedMs <= 0) return p
        val hrs = elapsedMs / MS_PER_HOUR
        val hyd = (p.hydration - (hrs * HYDRATION_DECAY_PER_HR).roundToInt()).coerceIn(0, 100)
        val hyg = (p.hygiene - (hrs * HYGIENE_DECAY_PER_HR).roundToInt()).coerceIn(0, 100)
        val en = (p.energy - (hrs * ENERGY_DECAY_PER_HR).roundToInt()).coerceIn(0, 100)
        val nour = (p.nourishment - (hrs * NOURISHMENT_DECAY_PER_HR).roundToInt()).coerceIn(0, 100)
        return p.copy(hydration = hyd, hygiene = hyg, energy = en, nourishment = nour)
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
}
