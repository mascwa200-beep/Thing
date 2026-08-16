package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs

/**
 * What a quoted price actually means right now: which session the venue is in, how old the print is,
 * and where the price sits in its own year.
 *
 * All of this arrives in the same response the app already fetches for every quote and was being
 * thrown away — the venue's pre/regular/post windows, the exchange's own timestamp on the last
 * print, and the fifty-two-week extremes. Without them a closed market looks like a live one: the
 * screen shows a price and a "just now" that belongs to the fetch rather than the trade.
 *
 * Pure and deterministic — every input is passed in, including the clock — so CI can hold it.
 */
object MarketSession {

    /** One venue window: `[startMs, endMs)` in epoch milliseconds. */
    data class Window(val startMs: Long, val endMs: Long) {
        val valid: Boolean get() = endMs > startMs
        fun contains(ms: Long): Boolean = valid && ms >= startMs && ms < endMs
    }

    /**
     * A venue's three windows for the current day. Any may be absent: a great many instruments have
     * no pre or post session at all, and treating a missing window as an empty one would report
     * "closed" during hours the venue is trading.
     */
    data class Windows(
        val pre: Window? = null,
        val regular: Window? = null,
        val post: Window? = null,
    )

    /** Which session the venue is in. */
    enum class Phase { PRE, OPEN, AFTER, CLOSED, UNKNOWN }

    /**
     * The venue's state at [nowMs].
     *
     * [Phase.UNKNOWN] rather than a guess when there is no usable regular window. A quote whose
     * session cannot be established should say so; claiming CLOSED would be a specific assertion
     * about a venue we know nothing about, and it would be wrong every time the data was simply
     * missing rather than the market being shut.
     */
    fun phaseAt(w: Windows?, nowMs: Long): Phase {
        if (w == null) return Phase.UNKNOWN
        val regular = w.regular?.takeIf { it.valid } ?: return Phase.UNKNOWN
        if (regular.contains(nowMs)) return Phase.OPEN
        w.pre?.takeIf { it.valid }?.let { if (it.contains(nowMs)) return Phase.PRE }
        w.post?.takeIf { it.valid }?.let { if (it.contains(nowMs)) return Phase.AFTER }
        return Phase.CLOSED
    }

    /** Milliseconds until the regular session opens; null when it is open, past, or unknown. */
    fun msUntilOpen(w: Windows?, nowMs: Long): Long? {
        val start = w?.regular?.takeIf { it.valid }?.startMs ?: return null
        return (start - nowMs).takeIf { it > 0 }
    }

    /** Milliseconds until the regular session closes; null unless it is currently open. */
    fun msUntilClose(w: Windows?, nowMs: Long): Long? {
        val regular = w?.regular?.takeIf { it.valid } ?: return null
        if (!regular.contains(nowMs)) return null
        return (regular.endMs - nowMs).takeIf { it > 0 }
    }

    /**
     * A short label for the venue's state, with the useful number attached.
     *
     * "Open · 2h 14m to the bell" is worth more than "Open", and "Closed · last traded 3h ago" is the
     * difference between a stale-looking screen and an honest one. [lastPrintMs] is the venue's own
     * timestamp on the last trade — not the time the app fetched it, which is the substitution this
     * whole object exists to stop.
     */
    fun describe(w: Windows?, nowMs: Long, lastPrintMs: Long? = null): String = when (phaseAt(w, nowMs)) {
        Phase.OPEN -> msUntilClose(w, nowMs)
            ?.let { "Open · ${compactDuration(it)} to the bell" } ?: "Open"
        Phase.PRE -> msUntilOpen(w, nowMs)
            ?.let { "Pre-market · opens in ${compactDuration(it)}" } ?: "Pre-market"
        Phase.AFTER -> "After hours"
        Phase.CLOSED -> lastPrintMs
            ?.let { "Closed · last traded ${compactDuration(abs(nowMs - it))} ago" } ?: "Closed"
        Phase.UNKNOWN -> ""
    }

    /**
     * True when the shown price is a trade rather than a live market.
     *
     * The rule the UI wants: outside the regular session the number is the last thing that printed,
     * however long ago, and saying "updated just now" about it is a small lie the app told on every
     * quote outside market hours.
     */
    fun isStalePrint(w: Windows?, nowMs: Long): Boolean =
        phaseAt(w, nowMs).let { it == Phase.CLOSED || it == Phase.AFTER }

    // ---- fifty-two-week range --------------------------------------------------------------

    /**
     * Where [price] sits between [low] and [high], as `0f..1f`.
     *
     * Null when the range is unusable — either bound missing, inverted, or degenerate. A collapsed
     * range would divide by zero, and a range of exactly one price is not a range: reporting 0.5 for
     * it would invent a middle that does not exist.
     *
     * Values outside the band are clamped rather than rejected: a genuine new high arrives before
     * the venue's own fifty-two-week figure catches up, and "at the top" is the right answer then.
     */
    fun rangePosition(price: Double?, low: Double?, high: Double?): Float? {
        if (price == null || low == null || high == null) return null
        if (!price.isFinite() || !low.isFinite() || !high.isFinite()) return null
        val span = high - low
        if (span <= 0.0) return null
        return ((price - low) / span).toFloat().coerceIn(0f, 1f)
    }

    /**
     * The same position in words.
     *
     * The bands are deliberately uneven. The interesting facts about a yearly range are the ends —
     * a price at its high or its floor is news, and the wide middle is not — so the extremes get
     * narrow bands and the centre gets a broad one, rather than five equal fifths that would call
     * an unremarkable price "upper-mid" and imply a precision the number does not carry.
     */
    fun describeRange(position: Float?): String? = when {
        position == null -> null
        position >= 0.95f -> "at its 52-week high"
        position >= 0.80f -> "near its 52-week high"
        position <= 0.05f -> "at its 52-week low"
        position <= 0.20f -> "near its 52-week low"
        else -> "mid-range for the year"
    }

    // ---- venue calendar --------------------------------------------------------------------

    /**
     * Whether two instants fall on the same day *at the venue*.
     *
     * The reason this exists is narrow and worth stating. A daily chart's last candle is normally
     * today's session, so its opening price is today's open — but before the session begins, the
     * last candle is still *yesterday's*, and taking its open would put a stale number under a live
     * price with nothing to mark it as stale. Comparing the last candle's day against the venue's
     * own timestamp on the last trade settles it.
     *
     * Both instants are epoch **seconds** (the wire format for these fields) and [gmtOffsetSec] is
     * the venue's offset, so the comparison happens on the exchange's calendar rather than the
     * phone's — a New York close is the previous day in Auckland, and the phone's midnight has no
     * bearing on which session a print belongs to.
     */
    fun sameVenueDay(aEpochSec: Long, bEpochSec: Long, gmtOffsetSec: Long): Boolean =
        Math.floorDiv(aEpochSec + gmtOffsetSec, 86_400L) == Math.floorDiv(bEpochSec + gmtOffsetSec, 86_400L)

    // ---- helpers ---------------------------------------------------------------------------

    /**
     * A duration in the shortest form that stays honest.
     *
     * Never "0m": under a minute reads as "under a minute", because rounding a live countdown down
     * to zero tells the reader the bell has already gone.
     */
    internal fun compactDuration(ms: Long): String {
        val totalMinutes = ms / 60_000L
        return when {
            totalMinutes < 1L -> "under a minute"
            totalMinutes < 60L -> "${totalMinutes}m"
            else -> {
                val hours = totalMinutes / 60L
                val minutes = totalMinutes % 60L
                if (hours < 24L) {
                    if (minutes == 0L) "${hours}h" else "${hours}h ${minutes}m"
                } else {
                    val days = hours / 24L
                    val remHours = hours % 24L
                    if (remHours == 0L) "${days}d" else "${days}d ${remHours}h"
                }
            }
        }
    }
}
