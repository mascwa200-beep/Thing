package dev.mascwa.pulse.data.health

import kotlinx.serialization.Serializable

/**
 * The HEALTH tab: who you are, where you are going, and how fast.
 *
 * ⚠️ It lives beside the stores rather than with the rest of the settings because both
 * applications need it: the shared view model reads it on every recomputation, and a type the
 * shared code cannot name is a type the shared code cannot use. Moving it changed nothing on disk
 * -- it is a plain @Serializable data class with no @SerialName and no polymorphism, so the JSON
 * keys are the field names either way and the blob already saved on a device still decodes.
 *
 * These are the inputs [dev.mascwa.pulse.core.telemetry.MacroTargets] needs on every recomputation —
 * small, rarely changed, and useless without each other, which is what makes them settings rather than a
 * store. The weigh-ins and the food log are time series and live in `data/health`.
 *
 * ⚠️ Every field is a serialization key. Renaming one silently discards the *whole* blob's saved value on
 * every existing device, so a name that reads slightly wrong stays.
 *
 * ⚠️ These are personal but they are not credentials, so they are deliberately NOT added to
 * `allSecretValues()` or `SettingsBackup.redactSecrets` — a backup of your own app carrying your own
 * height is the point of a backup. Nothing here leaves the device by any other route.
 */
@Serializable
data class HealthSettings(
    /** Centimetres. 0 = not told, which every consumer must treat as "cannot compute" rather than zero. */
    val heightCm: Double = 0.0,

    /**
     * ⚠️ The YEAR of birth, not an age, and that is the whole reason this field is shaped like this. An
     * age stored as a number is wrong within twelve months and then stays wrong for ever, quietly
     * drifting the resting-rate floor that every calorie target sits on. A year is right until the
     * calendar says otherwise. 0 = not told.
     */
    val birthYear: Int = 0,

    /** [dev.mascwa.pulse.core.telemetry.Body.Sex] name. Unstated takes the higher resting rate — the safe direction. */
    val sex: String = "UNSPECIFIED",

    /** Kilograms. 0 = no goal, which means maintain. */
    val goalKg: Double = 0.0,

    /** Signed kilograms per week: negative loses, zero maintains, positive gains. */
    val ratePerWeekKg: Double = 0.0,

    /** [dev.mascwa.pulse.core.telemetry.MacroTargets.DietMode] name. */
    val dietMode: String = "BALANCED",

    /** Grams per kilogram of reference mass. 0 = whatever the diet mode says. */
    val proteinGPerKg: Double = 0.0,

    /** [dev.mascwa.pulse.core.telemetry.Expenditure.Activity] name — only used until the measurement takes over. */
    val activity: String = "LIGHT",

    /** [dev.mascwa.pulse.core.telemetry.BodyTrend.MassUnit] name. Display only; everything is stored in kg. */
    val massUnit: String = "KG",

    /** How far back the expenditure measurement looks. */
    val expenditureWindowDays: Int = 28,

    /**
     * Body fat as a percentage, or 0.0 for "not known".
     *
     * Knowing it selects a materially better resting-rate equation — one fitted on fat-free and fat
     * mass separately rather than on total weight — which is what the app leans on hardest during the
     * first weeks, before there are enough weigh-ins and logged days to measure anything.
     *
     * ⚠️ 0.0 is the unset sentinel and needs no separate "is it set" branch: it falls below
     * [dev.mascwa.pulse.core.telemetry.BmrEquations.MIN_BODY_FAT_PCT], so the plausibility check
     * already rejects it and the estimate falls back to the equation that does not need it. A typo
     * is refused the same way rather than clamped into range.
     */
    val bodyFatPct: Double = 0.0,

    /**
     * Seven or more hours a week of intense training, self-reported.
     *
     * ⚠️ A self-report and not an inference from body fat. The athlete equation's exponent is close to
     * linear because a lean, heavily-muscled population varies much less in tissue mix than the general
     * one does; applied to an untrained body it over-estimates badly. Being lean is not the same as
     * being trained, so this cannot be guessed from a body-fat figure.
     *
     * ⚠️ On its own it does nothing — that equation needs fat-free mass, so without [bodyFatPct] the
     * estimate falls back rather than inventing the input it is missing.
     */
    val athlete: Boolean = false,

    /**
     * [dev.mascwa.pulse.core.telemetry.WeeklyPlan.Mode] name — who is in charge of the calories.
     *
     * COACHED is the default and the honest one to start on: until there is enough logged to measure
     * anything, the app is the only party with a defensible opinion, and asking somebody to name
     * their own number on day one is asking them to guess.
     */
    val programMode: String = "COACHED",

    /**
     * Which days of the week are the heavy ones, as indices 0..6, under the collaborative mode.
     *
     * ⚠️ Indices rather than weekday names, matching
     * [dev.mascwa.pulse.core.telemetry.WeeklyPlan.Day.index]: which index is Monday is a calendar
     * question and neither this type nor that core has a calendar in it. The surface that draws the
     * week is the one place that knows.
     *
     * ⚠️ Ignored entirely outside the collaborative mode, and that is deliberate rather than tidy —
     * a coached week is flat because nothing has told it otherwise, and a manual week is flat because
     * the person owns the number. Redistributing under either would be the app taking back the thing
     * that mode hands over.
     */
    val heavyDays: List<Int> = emptyList(),

    /** Whether the tab has ever been set up — decides between the welcome and the dashboard. */
    val configured: Boolean = false,
)
