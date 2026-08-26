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

    /** Whether the tab has ever been set up — decides between the welcome and the dashboard. */
    val configured: Boolean = false,
)
