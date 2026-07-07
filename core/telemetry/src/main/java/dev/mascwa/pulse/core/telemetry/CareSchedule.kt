package dev.mascwa.pulse.core.telemetry

/**
 * The smart self-care CHECK-IN scheduler — decides WHAT to prompt you about and WHEN, by priority + real-world
 * rhythm + context. Oral hygiene (brush, floss) and water are the TOP priorities; the rest run on typical daily
 * cadences. The signature move is CONTEXTUAL SEQUENCING: eating makes a "brush your teeth" check-in come due the
 * right interval later (you wait a bit after acidic food, for the enamel), and flossing follows a brush — so a
 * completed meal drives the exact "…okay, now brush, and then floss" sequence. A critically-low need jumps the
 * queue so the thing that needs doing most is asked first.
 *
 * Pure + deterministic → CI-testable. The on-device layer raises the top due check-in as a full-screen takeover
 * (over the lock screen), throttles re-asks, and feeds back completions (sensed or confirmed) as [lastConfirmed]
 * / [lastMealMs]. Most check-ins are gated to waking hours so it doesn't take over the screen at 3am.
 */
enum class CareCheckin(val need: NeedKind, val label: String, val prompt: String, val basePriority: Int) {
    BRUSH(NeedKind.BRUSHING, "Brush your teeth", "Time to brush — do it properly, a full two minutes.", 100),
    FLOSS(NeedKind.FLOSSING, "Floss", "Now floss — get between every tooth while you're at it.", 95),
    WATER(NeedKind.HYDRATION, "Drink water", "Have a proper drink of water.", 90),
    EAT(NeedKind.NOURISHMENT, "Eat something", "Have you eaten? Get a proper meal in.", 70),
    REST(NeedKind.ENERGY, "Rest", "You're running low — take a real break.", 55),
    WASH(NeedKind.HYGIENE, "Freshen up", "Wash your hands and face.", 45),
}

/** Everything the scheduler needs to decide the next check-in. [lastConfirmed]/[lastMealMs] are wall-clock ms. */
data class CareContext(
    val now: Long,
    val hourOfDay: Int,
    val lastConfirmed: Map<CareCheckin, Long>,
    /** When you last ate (sensed by the camera/mic or a confirmed EAT) — drives the post-meal brush. 0 = unknown. */
    val lastMealMs: Long,
    /** The live needs — a critically-low one escalates its check-in's priority so it's asked first. */
    val needs: LifeProfile,
)

object CareSchedule {
    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    private const val DAY = 24 * HOUR

    // Rhythms (how often each is expected, roughly).
    const val WATER_EVERY_MS = 2 * HOUR
    const val EAT_EVERY_MS = 5 * HOUR
    const val BRUSH_EVERY_MS = 12 * HOUR   // twice-daily fallback
    const val WASH_EVERY_MS = DAY
    const val FLOSS_EVERY_MS = DAY

    /** Wait this long after a meal before the "brush" check-in comes due (don't brush straight onto acid). */
    const val POST_MEAL_BRUSH_MS = 30 * MIN
    /** After a brush, "floss" stays due for this long — so flossing directly follows brushing. */
    const val FLOSS_AFTER_BRUSH_MS = 2 * HOUR

    /** Don't re-raise the same check-in within this window even if still due — no nagging every minute. */
    const val MIN_ASK_GAP_MS = 90 * MIN

    // Waking window — most check-ins stay quiet outside it so the takeover never fires while you sleep.
    const val WAKE_START = 7
    const val WAKE_END = 22

    /** Energy at/below this makes the REST check-in eligible. */
    const val REST_NEED_FLOOR = LifeStats.NEED_LOW

    /**
     * The check-ins due at [ctx].now, MOST IMPORTANT FIRST (base priority + escalation when the need is low).
     * The on-device layer raises the first as a full-screen takeover. [asked] maps each check-in → when it was
     * last prompted, to throttle re-asks.
     */
    fun due(ctx: CareContext, asked: Map<CareCheckin, Long> = emptyMap()): List<CareCheckin> {
        val waking = ctx.hourOfDay % 24 in WAKE_START..WAKE_END
        return CareCheckin.entries
            .filter { c ->
                (ctx.now - (asked[c] ?: 0L)) >= MIN_ASK_GAP_MS && isDue(c, ctx, waking)
            }
            .sortedByDescending { priority(it, ctx) }
    }

    /** The single top check-in to raise now, or null when nothing's due. */
    fun next(ctx: CareContext, asked: Map<CareCheckin, Long> = emptyMap()): CareCheckin? =
        due(ctx, asked).firstOrNull()

    private fun isDue(c: CareCheckin, ctx: CareContext, waking: Boolean): Boolean {
        val last = ctx.lastConfirmed[c] ?: 0L
        val since = ctx.now - last
        return when (c) {
            CareCheckin.WATER -> waking && since >= WATER_EVERY_MS
            CareCheckin.EAT -> waking && since >= EAT_EVERY_MS
            CareCheckin.WASH -> waking && since >= WASH_EVERY_MS
            CareCheckin.REST -> ctx.needs.energy <= REST_NEED_FLOOR && since >= MIN_ASK_GAP_MS
            CareCheckin.BRUSH -> {
                // Contextual: you ate → after the wait, and not brushed since that meal → brush now.
                val postMeal = ctx.lastMealMs > 0L &&
                    (ctx.now - ctx.lastMealMs) >= POST_MEAL_BRUSH_MS && last < ctx.lastMealMs
                val twiceDaily = waking && since >= BRUSH_EVERY_MS
                postMeal || twiceDaily
            }
            CareCheckin.FLOSS -> {
                // Follows a brush: brushed more recently than flossed, within the window → floss right after.
                val lastBrush = ctx.lastConfirmed[CareCheckin.BRUSH] ?: 0L
                val afterBrush = lastBrush > last && (ctx.now - lastBrush) <= FLOSS_AFTER_BRUSH_MS
                val daily = waking && since >= FLOSS_EVERY_MS
                afterBrush || daily
            }
        }
    }

    /** Base priority, escalated when the check-in's own need is running low — so the neediest is asked first. */
    private fun priority(c: CareCheckin, ctx: CareContext): Int {
        val v = c.need.value(ctx.needs)
        val escalation = when {
            v <= LifeStats.NEED_CRITICAL -> 60
            v <= LifeStats.NEED_LOW -> 25
            else -> 0
        }
        return c.basePriority + escalation
    }

    // ── "…ensure that you do it right": verify a claimed completion against what the camera/mic actually saw ──

    /** How far back to look for sensed corroboration when you claim a check-in done. */
    const val VERIFY_WINDOW_MS = 15 * MIN

    /**
     * The real-world activities whose sensed evidence can back up a claimed completion of [c]. Empty = nothing
     * the sensors can reliably see (flossing, resting), so a claim is taken on trust. Hygiene is corroborated by
     * either a shower or a hand/face wash.
     */
    fun verifyActivities(c: CareCheckin): Set<RealActivity> = when (c) {
        CareCheckin.WATER -> setOf(RealActivity.DRINKING)
        CareCheckin.EAT -> setOf(RealActivity.EATING)
        CareCheckin.BRUSH -> setOf(RealActivity.TOOTHBRUSH)
        CareCheckin.WASH -> setOf(RealActivity.SHOWER, RealActivity.HANDWASH)
        CareCheckin.FLOSS -> emptySet()
        CareCheckin.REST -> emptySet()
    }

    /**
     * Cross-reference a claimed "I did it" for [c] with the sensed [evidence] since [sinceMs] — the lie-catch that
     * makes the check-in ensure you actually do it. Returns the best verdict across [c]'s verifiable activities.
     *
     * Deliberately forgiving so it can never make a FALSE accusation: when [c] has no sensor-visible activity, or
     * the camera/mic weren't watching ([sensingActive] = false), it returns [ClaimVerdict.WEAK] (provisional
     * trust) rather than accusing. Only a claim the ACTIVE sensors flatly failed to corroborate comes back
     * [ClaimVerdict.NONE] — the one case the on-device layer bounces back with "sensors didn't catch that."
     */
    fun verifyClaim(
        c: CareCheckin,
        evidence: List<ActivityEvidence>,
        sinceMs: Long,
        sensingActive: Boolean,
    ): ClaimVerdict {
        val acts = verifyActivities(c)
        if (acts.isEmpty() || !sensingActive) return ClaimVerdict.WEAK
        val verdicts = acts.map { ActivitySensing.verifyClaim(it, evidence, sinceMs) }
        return when {
            verdicts.any { it == ClaimVerdict.CONFIRMED } -> ClaimVerdict.CONFIRMED
            verdicts.any { it == ClaimVerdict.WEAK } -> ClaimVerdict.WEAK
            else -> ClaimVerdict.NONE
        }
    }

    /** Whether a [verdict] credits the check-in (the completion stands). Only a flat contradiction (NONE) fails. */
    fun credits(verdict: ClaimVerdict): Boolean = verdict != ClaimVerdict.NONE
}
