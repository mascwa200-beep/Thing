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

/** The kinds of self-changes J.A.R.V.I.S. can PROPOSE (each applied only on the user's tap). */
object ActionType {
    const val PERSONA_EDIT = "PERSONA_EDIT"
    const val DOC_ADD = "DOC_ADD"
    const val DOC_EDIT = "DOC_EDIT"
    const val DOC_DELETE = "DOC_DELETE"
    const val RESEARCH = "RESEARCH"       // M3
    const val TOOL_REGISTER = "TOOL_REGISTER" // M4
}

/**
 * A proposed self-change awaiting the user's explicit approval. The agent can only ENQUEUE these; the
 * only code that acts on one is `ApprovalGate.apply` (called from a UI tap). [payload] carries the
 * type-specific data; [preview] is the human-readable diff/summary shown before approving.
 */
@Serializable
data class PendingAction(
    val id: String,
    val type: String,
    val title: String,
    val preview: String,
    val payload: Map<String, String> = emptyMap(),
    val createdAt: Long,
)

/**
 * The user-owned, on-device "interpreted layer" J.A.R.V.I.S. may edit (only via approved proposals).
 * Held as one serializable blob in its own DataStore file so adding fields never migrates or wipes the
 * main settings / Room data.
 */
@Serializable
data class SelfEditState(
    /** User-supplied persona "charter". Blank → fall back to the built-in JarvisPersona. */
    val charter: String = "",
    /** Bounded rollback history of edited artifacts (newest last). */
    val versions: List<ArtifactVersion> = emptyList(),
    /** Proposed self-changes awaiting the user's approval. */
    val pendingActions: List<PendingAction> = emptyList(),
)
