package dev.mascwa.pulse.data.weather

/**
 * The last place this phone knew it was, kept for the readers that cannot ask: anything running in
 * the background.
 *
 * ⚠️ **Why this exists.** The app holds foreground-only location (no `ACCESS_BACKGROUND_LOCATION`,
 * and no foreground service declares the `location` type), so the platform's app-op answers every
 * location read from the background with nothing — the fused provider, `LocationManager` and even
 * `getLastKnownLocation`. A home-screen widget renders from a background broadcast, so the widget
 * went without weather, air, water, safety and sky for good, while telling the owner to "open LCARS
 * once" — advice that changed nothing, because nothing kept the fix LCARS took. Now opening LCARS
 * records it, and that sentence is true.
 *
 * ⚠️ **Rounded to [DECIMALS] places (~1 km) before it is kept.** Every reader of this — weather,
 * air quality, the nearest water gauge, incidents within a radius, a satellite pass — is a regional
 * question, and a kilometre is well inside what any of them can tell apart. It is also far less to
 * keep on disk about where somebody sleeps.
 *
 * ⚠️ **A reader must check [usableAt], and the bound is the whole point.** A fix is a claim about
 * where the phone WAS. Within a day it is almost always still where it is for anybody who is not
 * travelling; beyond that, weather "here" would quietly be weather wherever the phone last had a
 * screen open, which is the app being more confident than its data.
 *
 * Pure — no Android import — so the rule is held by a JVM test rather than by whoever next edits
 * the store.
 */
data class RecordedFix(
    val latitude: Double,
    val longitude: Double,
    val name: String,
    val atMs: Long,
) {
    /** How long ago the fix was taken. Negative when the clock has moved backwards since. */
    fun ageMs(nowMs: Long): Long = nowMs - atMs

    /**
     * Young enough to stand in for "here".
     *
     * ⚠️ A stamp slightly in the FUTURE is tolerated, one far in the future is not. A clock corrected
     * backwards by a few minutes is ordinary; one that was hours fast when the fix was taken would
     * otherwise leave this "fresh" for as long as the error — and the next foreground fix overwrites
     * it anyway.
     */
    fun usableAt(nowMs: Long): Boolean = ageMs(nowMs) in -CLOCK_SLACK_MS..MAX_AGE_MS

    companion object {
        /** A day. Past that, "here" is a guess about where the phone was, not where it is. */
        const val MAX_AGE_MS = 24 * 3_600_000L

        /** How far in the future a stamp may sit and still be believed. */
        const val CLOCK_SLACK_MS = 5 * 60_000L

        /** Two decimal places of a degree — about a kilometre of latitude. */
        const val DECIMALS = 2

        /**
         * A fix as it is kept: coordinates rounded to [DECIMALS] places.
         *
         * ⚠️ `Math.round`, which rounds halves up, not `kotlin.math.round`, which is `Math.rint` —
         * banker's rounding — and would move a coordinate one way or the other depending on the
         * parity of the digit before it.
         */
        fun of(latitude: Double, longitude: Double, name: String, atMs: Long): RecordedFix =
            RecordedFix(roundCoordinate(latitude), roundCoordinate(longitude), name, atMs)

        fun roundCoordinate(v: Double): Double = Math.round(v * SCALE) / SCALE

        private const val SCALE = 100.0
    }
}
