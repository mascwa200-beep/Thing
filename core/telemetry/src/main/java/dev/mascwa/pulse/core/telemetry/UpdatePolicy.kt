package dev.mascwa.pulse.core.telemetry

/**
 * When a newly published build should actually be offered to the user.
 *
 * Both editions ship the same way — CI pushes an installer to a rolling pre-release whose *name* carries
 * the build number — and both therefore need the same three judgements: which build the release
 * describes, whether the run that produced it is finished, and which asset to take. That is a set of
 * decisions with genuinely non-obvious edges, and the phone's updater learned them the hard way; writing
 * them a second time for the desktop is how the two would drift apart.
 *
 * Deliberately knows nothing about HTTP, files or platforms. Callers fetch, this decides.
 */
object UpdatePolicy {

    /** Where a release's build number is written: "… build #1651". */
    private val BUILD_NUM = Regex("#(\\d+)")

    /**
     * The build number a release describes, from its name, falling back to its body.
     *
     * Null when neither says — which must be treated as "cannot tell", never as zero. A zero would
     * compare as older than everything installed and silently report the user as up to date forever.
     */
    fun buildNumberOf(releaseName: String, releaseBody: String = ""): Int? =
        BUILD_NUM.find(releaseName)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: BUILD_NUM.find(releaseBody)?.groupValues?.getOrNull(1)?.toIntOrNull()

    /**
     * Whether the CI run that produced a build blocks it from being offered.
     *
     * ⚠️ Three states, not two, and the third is the one that matters. The rolling release picks up the
     * new installer *mid-workflow*, so a run can publish a perfectly good artifact and then be cancelled
     * — this repo's own concurrency rule cancels the in-flight run whenever a newer commit lands, which
     * happens routinely. Treating cancelled as "not green" would suppress a build that exists and works.
     *
     * - `false` — genuinely not ready: still building or queued, or a hard failure. Suppress.
     * - `true` — completed successfully.
     * - `null` — anything else (cancelled, neutral, skipped, run not found, API unreachable). Unknown,
     *   and the caller offers anyway, because an artifact was published and unknown is not a reason to
     *   withhold it.
     */
    fun runVerdict(status: String, conclusion: String?): Boolean? = when {
        status != "completed" -> false
        conclusion == "success" -> true
        conclusion == "failure" -> false
        else -> null
    }

    /**
     * The installer to download, given every asset on the release.
     *
     * Takes the NEWEST match rather than the first: a rolling release can briefly hold a stale asset —
     * after a published filename changes, say — and taking the first would hand the user a downgrade.
     * `createdAt` is ISO-8601, so the lexical maximum is the most recent, no date parsing required.
     */
    fun <T> newestAsset(assets: List<T>, createdAt: (T) -> String, matches: (T) -> Boolean): T? =
        assets.filter(matches).maxByOrNull(createdAt)

    /** What to tell the user. */
    enum class Verdict {
        /** Nothing newer than what is installed. */
        CURRENT,

        /** Something newer exists but is still building, failed, or has no installer attached yet. */
        PENDING,

        /** A finished build is there to install. */
        AVAILABLE,

        /** The release did not say which build it is, so no claim can be made either way. */
        UNKNOWN,
    }

    /**
     * @param installedBuild the running build's number; 0 or less for a build that does not know its own
     *   provenance (a local developer build), which is reported as [Verdict.UNKNOWN] rather than being
     *   told it is out of date on every launch.
     * @param green the result of [runVerdict] for the published build.
     * @param hasInstaller whether an installer asset was actually found on the release.
     */
    fun verdict(
        installedBuild: Int,
        releaseBuild: Int?,
        green: Boolean?,
        hasInstaller: Boolean,
    ): Verdict = when {
        releaseBuild == null -> Verdict.UNKNOWN
        installedBuild <= 0 -> Verdict.UNKNOWN
        releaseBuild <= installedBuild -> Verdict.CURRENT
        green == false -> Verdict.PENDING
        !hasInstaller -> Verdict.PENDING
        else -> Verdict.AVAILABLE
    }

    /** The display name for a build number, matching what the installers are versioned as. */
    fun versionName(build: Int): String = "1.0.$build"
}
