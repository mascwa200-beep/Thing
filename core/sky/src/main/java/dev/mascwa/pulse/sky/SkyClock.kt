package dev.mascwa.pulse.sky

/**
 * The one rule for which instant the map is drawing.
 *
 * ⚠️ **This exists because the chart shipped with its clock frozen at the moment the screen
 * composed, and with a second clock for the tap.** `SkyChart` held the instant in
 * `remember(hours, site)`, so it changed only when the scrubber moved or the fix did; the Sun,
 * Moon and planets were recomputed only then too; and `identify` read `System.currentTimeMillis()`
 * live. The sky turns at 15.04 arcseconds a second — **a quarter of a degree a minute, a whole
 * quarter-degree field in one minute, two and a half degrees in ten** — so a map left open while
 * somebody found their way round the constellations was drawing a sky that was no longer overhead,
 * and after about ten minutes a tap on a drawn star resolved, in the live frame, to a place the star
 * was no longer drawn at. Two clocks for one screen is exactly what `refreshDeep`'s note warns
 * against, and this is where the single derivation now lives.
 *
 * Pure and Android-free so the arithmetic is held by a test that runs here rather than only in CI.
 */
object SkyClock {

    /**
     * How often the drawn instant advances while the chart is on screen.
     *
     * ⚠️ Half a second, and the trade is stated rather than hidden. At the widest field the sky
     * moves a fifth of a pixel per tick, so the cadence is invisible; at the quarter-degree floor a
     * pixel is 0.8 arcseconds, so each tick is a nine-pixel step — visible if you stare, and the
     * price of not recomposing the whole canvas sixty times a second for a drift most fields cannot
     * show. A frame is two vectors, so the cost of the tick is the recomposition, not the maths.
     */
    const val TICK_MS = 500L

    /**
     * How far the instant may move before the deep field and the constellation cut are asked to
     * re-check what they hold.
     *
     * A minute. Neither depends on the clock except through proper motion, which `StarField`
     * already tolerates for a tenth of a year, so re-planning on every tick would be a coroutine
     * launched twice a second to answer "unchanged".
     */
    const val RELOAD_BUCKET_MS = 60_000L

    /** The instant to draw: the wall clock, offset by the scrubber. */
    fun instantOf(nowMs: Long, offsetMs: Long): Long = nowMs + offsetMs

    /** Which reload bucket an instant falls in — see [RELOAD_BUCKET_MS]. */
    fun reloadBucket(instantMs: Long): Long = Math.floorDiv(instantMs, RELOAD_BUCKET_MS)
}
