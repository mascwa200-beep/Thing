package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdatePolicyTest {

    private data class Asset(val name: String, val createdAt: String)

    @Test
    fun theBuildNumberComesFromTheReleaseName() {
        assertEquals(1651, UpdatePolicy.buildNumberOf("Pulse — debug build #1651"))
        assertEquals(19, UpdatePolicy.buildNumberOf("LCARS desktop — build #19"))
    }

    @Test
    fun theBodyIsTheFallbackWhenTheNameDoesNotSay() {
        assertEquals(42, UpdatePolicy.buildNumberOf("LCARS", "Auto-built from build #42 at some commit"))
    }

    /**
     * A missing number must stay missing. Defaulting to 0 would compare as older than any installed
     * build, so the user would be told they are current forever and never see another update.
     */
    @Test
    fun aReleaseThatNamesNoBuildIsUnknownRatherThanZero() {
        assertNull(UpdatePolicy.buildNumberOf("LCARS", "no number here"))
        assertEquals(UpdatePolicy.Verdict.UNKNOWN, UpdatePolicy.verdict(100, null, true, hasInstaller = true))
    }

    @Test
    fun aFinishedGreenRunIsOfferable() {
        assertEquals(true, UpdatePolicy.runVerdict("completed", "success"))
    }

    @Test
    fun aRunStillGoingOrFailedIsNot() {
        assertEquals(false, UpdatePolicy.runVerdict("in_progress", null))
        assertEquals(false, UpdatePolicy.runVerdict("queued", null))
        assertEquals(false, UpdatePolicy.runVerdict("completed", "failure"))
    }

    /**
     * The edge the whole tri-state exists for. This repo cancels the in-flight run whenever a newer
     * commit lands, and the rolling release picks up the installer mid-workflow — so a cancelled run has
     * very often already published a good artifact. Withholding it would be wrong.
     */
    @Test
    fun aRunCancelledAfterPublishingIsUnknownNotBlocked() {
        assertNull(UpdatePolicy.runVerdict("completed", "cancelled"))
        assertEquals(
            UpdatePolicy.Verdict.AVAILABLE,
            UpdatePolicy.verdict(100, 101, green = null, hasInstaller = true),
        )
    }

    @Test
    fun anOlderOrEqualReleaseMeansCurrent() {
        assertEquals(UpdatePolicy.Verdict.CURRENT, UpdatePolicy.verdict(100, 100, true, hasInstaller = true))
        assertEquals(UpdatePolicy.Verdict.CURRENT, UpdatePolicy.verdict(100, 99, true, hasInstaller = true))
    }

    @Test
    fun aNewerBuildStillRunningIsPendingNotCurrent() {
        assertEquals(UpdatePolicy.Verdict.PENDING, UpdatePolicy.verdict(100, 101, green = false, hasInstaller = true))
    }

    /** A release whose installer has not been attached yet is not something to offer. */
    @Test
    fun aNewerBuildWithNoInstallerIsPending() {
        assertEquals(UpdatePolicy.Verdict.PENDING, UpdatePolicy.verdict(100, 101, green = true, hasInstaller = false))
    }

    /**
     * A build that does not know its own number cannot be compared against anything. Saying "out of
     * date" on every launch of a local build would be noise, and saying "current" would be a guess.
     */
    @Test
    fun aBuildThatDoesNotKnowItsOwnNumberClaimsNothing() {
        assertEquals(UpdatePolicy.Verdict.UNKNOWN, UpdatePolicy.verdict(0, 101, true, hasInstaller = true))
        assertEquals(UpdatePolicy.Verdict.UNKNOWN, UpdatePolicy.verdict(-1, 101, true, hasInstaller = true))
    }

    /**
     * Newest, not first: a rolling release can hold a stale asset beside the current one, and taking the
     * first would serve a downgrade. ISO-8601 sorts lexically, so no date parsing is needed.
     */
    @Test
    fun theNewestMatchingAssetWins() {
        val assets = listOf(
            Asset("LCARS-desktop.msi", "2026-08-01T10:00:00Z"),
            Asset("LCARS-desktop.msi", "2026-08-17T02:33:00Z"),
            Asset("app-release.apk", "2026-08-17T09:00:00Z"),
        )
        val picked = UpdatePolicy.newestAsset(assets, { it.createdAt }) { it.name.endsWith(".msi", true) }
        assertEquals("2026-08-17T02:33:00Z", picked?.createdAt)
    }

    @Test
    fun noMatchingAssetIsNull() {
        val assets = listOf(Asset("app-release.apk", "2026-08-17T09:00:00Z"))
        assertNull(UpdatePolicy.newestAsset(assets, { it.createdAt }) { it.name.endsWith(".msi", true) })
    }

    @Test
    fun theVersionNameMatchesHowTheInstallersAreVersioned() {
        assertEquals("1.0.19", UpdatePolicy.versionName(19))
    }
}
