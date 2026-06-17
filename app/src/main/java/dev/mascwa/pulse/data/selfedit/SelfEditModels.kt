package dev.mascwa.pulse.data.selfedit

import kotlinx.serialization.Serializable

/** A saved snapshot of an editable interpreted artifact, kept for rollback. */
@Serializable
data class ArtifactVersion(
    val type: String,        // "charter" now; "doc"/"tool" added by later modules
    val key: String = "",    // artifact id within the type (the charter uses "")
    val content: String,
    val timestamp: Long,
)

/**
 * The user-owned, on-device "interpreted layer" J.A.R.V.I.S. may edit (only via approved proposals).
 * Held as one serializable blob in its own DataStore file so adding fields never migrates or wipes the
 * main settings / Room data. Module 1 uses [charter] + [versions]; later modules add pending actions
 * and authored tools.
 */
@Serializable
data class SelfEditState(
    /** User-supplied persona "charter". Blank → fall back to the built-in JarvisPersona. */
    val charter: String = "",
    /** Bounded rollback history of edited artifacts (newest last). */
    val versions: List<ArtifactVersion> = emptyList(),
)
