package dev.mascwa.pulse.data.health

import dev.mascwa.pulse.core.telemetry.CheckIn
import dev.mascwa.pulse.core.telemetry.MacroTargets

/**
 * The one place [HealthSettings]'s flat `published*` fields become a [CheckIn.Published] and back.
 *
 * ⚠️ **It is a separate file with no Android imports so the encoding can be tested on the JVM.** The
 * fields it reads are a persisted shape and the type it produces is a domain one; getting the join
 * between them wrong shows up as a plan that silently reverts, or as adjustments attached to the
 * wrong numbers, and neither is visible from a screenshot. Same reasoning as `TranscriptSeal`.
 *
 * ⚠️ Nothing here validates that the stored numbers are sensible. They were written by the planner,
 * which has already applied every floor and cap it has; re-checking them here would be a second copy
 * of rules that live in `MacroTargets`, and the copy that drifts is always the second.
 */
object PublishedPlan {

    /**
     * The separator inside an encoded adjustment.
     *
     * ⚠️ **Correcting an overstatement I made here first:** the split is on the FIRST occurrence, so
     * a comma or a pipe would in fact decode correctly too — a kind name is `[A-Z_]+` and cannot
     * contain either, so the first one is always the right split point whatever it is. The negative
     * test proved that by failing to break anything, which is the right outcome for a claim that was
     * not true.
     *
     * A unit separator is still the right choice, for the smaller reason that survives: it cannot
     * occur in the prose either side, so the stored blob is unambiguous to read by eye and stays
     * correct if somebody later reaches for `split` instead of `indexOf`.
     */
    const val SEP = '\u001F'

    /** What was last handed down, or null when nothing has been. */
    fun from(p: HealthSettings): CheckIn.Published? {
        // ⚠️ `publishedAtMs == 0L` is the single definition of "nothing published". A blob written
        // before this feature existed decodes to exactly that, which is what makes the upgrade path
        // a check-in on first open rather than a migration.
        if (p.publishedAtMs <= 0L) return null
        if (p.publishedKcal <= 0) return null
        return CheckIn.Published(
            atMs = p.publishedAtMs,
            targets = MacroTargets.Targets(
                kcal = p.publishedKcal,
                proteinG = p.publishedProteinG,
                fatG = p.publishedFatG,
                carbG = p.publishedCarbG,
            ),
            statedFingerprint = p.publishedFingerprint,
            expenditureKcal = p.publishedExpenditureKcal,
            weightKg = p.publishedWeightKg,
            effectiveRatePerWeekKg = p.publishedEffectiveRatePerWeekKg,
            adjustments = p.publishedAdjustments.mapNotNull(::decodeAdjustment),
        )
    }

    /**
     * The published set as a plan, so a screen holding one renders exactly as it would a fresh one.
     *
     * ⚠️ This is what stops the hold from being visible as a different KIND of thing. Every surface
     * already handles [MacroTargets.Plan.Set]; returning something else would mean every one of them
     * learning about check-ins.
     */
    fun asPlan(published: CheckIn.Published): MacroTargets.Plan.Set = MacroTargets.Plan.Set(
        targets = published.targets,
        adjustments = published.adjustments,
        effectiveRatePerWeekKg = published.effectiveRatePerWeekKg,
        expenditureKcal = published.expenditureKcal,
    )

    /**
     * The settings fields for a set of targets being handed down now.
     *
     * Returned as a copy of [p] rather than as a bag of values so the caller cannot write half of
     * them: a fingerprint stored without its targets, or targets without their fingerprint, would
     * both leave the check-in permanently confused about what it last said.
     */
    fun store(
        p: HealthSettings,
        atMs: Long,
        plan: MacroTargets.Plan.Set,
        stated: CheckIn.Stated,
        weightKg: Double,
        report: List<String>,
    ): HealthSettings = p.copy(
        publishedAtMs = atMs,
        publishedKcal = plan.targets.kcal,
        publishedProteinG = plan.targets.proteinG,
        publishedFatG = plan.targets.fatG,
        publishedCarbG = plan.targets.carbG,
        publishedEffectiveRatePerWeekKg = plan.effectiveRatePerWeekKg,
        publishedExpenditureKcal = plan.expenditureKcal,
        publishedWeightKg = weightKg,
        publishedAdjustments = plan.adjustments.map(::encodeAdjustment),
        publishedFingerprint = stated.fingerprint(),
        publishedReport = report,
    )

    /** The stated half of the plan, read off the settings the person edits. */
    fun statedOf(p: HealthSettings): CheckIn.Stated = CheckIn.Stated(
        heightCm = p.heightCm,
        birthYear = p.birthYear,
        sex = p.sex,
        goalKg = p.goalKg,
        ratePerWeekKg = p.ratePerWeekKg,
        dietMode = p.dietMode,
        proteinGPerKg = p.proteinGPerKg,
        bodyFatPct = p.bodyFatPct,
        athlete = p.athlete,
        programMode = p.programMode,
    )

    internal fun encodeAdjustment(a: MacroTargets.Adjustment): String = "${a.kind.name}$SEP${a.sentence}"

    /**
     * ⚠️ Null on anything that does not decode, and the caller drops it. An unknown kind is what a
     * downgrade looks like — a blob written by a build that had an adjustment this one does not —
     * and losing one note is far better than refusing to show the targets it was attached to.
     *
     * ⚠️ **What the length guard actually catches, having been measured rather than assumed:** only
     * the trailing case, and only for a VALID kind. A missing separator, an empty kind and an
     * unknown kind all fall through the `runCatching` below and return null anyway. What would
     * otherwise get through is `FAT_RAISED` followed by nothing — a real adjustment with an empty
     * sentence, which renders as a blank line under the targets. `at <= 0` is defensive rather than
     * observable, and it stays because relying on a substring throwing is a poor way to express an
     * intent.
     */
    internal fun decodeAdjustment(s: String): MacroTargets.Adjustment? {
        val at = s.indexOf(SEP)
        if (at <= 0 || at == s.length - 1) return null
        val kind = runCatching { MacroTargets.AdjustmentKind.valueOf(s.substring(0, at)) }.getOrNull()
            ?: return null
        return MacroTargets.Adjustment(kind, s.substring(at + 1))
    }
}
