package dev.mascwa.pulse.jarvis.agent

import android.content.Context
import dev.mascwa.pulse.data.jarvis.KnowledgeStore
import dev.mascwa.pulse.data.selfedit.ActionType
import dev.mascwa.pulse.data.selfedit.PendingAction
import dev.mascwa.pulse.data.selfedit.SelfEditStore
import java.util.UUID

/**
 * Self-edit tools. These are ENQUEUE-ONLY: they create a [PendingAction] for the user to approve and
 * never perform the change themselves (only `ApprovalGate.apply`, from a UI tap, does). They are only
 * offered to the model when the user has turned self-edit on. [SelfInspectTool] is read-only.
 */
private fun proposed(label: String) =
    "Proposed: $label. It's queued in Approvals and will only take effect when you tap APPROVE, sir."

class ProposePersonaTool(private val selfEdit: SelfEditStore) : JarvisTool {
    override val name = "propose_persona"
    override val usage = "propose_persona <new persona text> — propose changing your own persona (needs the user's approval)"
    override suspend fun run(arg: String): String {
        val text = arg.trim()
        if (text.isBlank()) return "Nothing to propose."
        selfEdit.enqueue(
            PendingAction(
                id = UUID.randomUUID().toString(),
                type = ActionType.PERSONA_EDIT,
                title = "Change persona",
                preview = "New persona:\n" + text.take(400),
                payload = mapOf("text" to text),
                createdAt = System.currentTimeMillis(),
            ),
        )
        return proposed("a new persona")
    }
}

/** [mode] = "add" | "edit" | "delete". */
class ProposeDocTool(private val selfEdit: SelfEditStore, private val mode: String) : JarvisTool {
    override val name = "propose_doc_$mode"
    override val usage = when (mode) {
        "add" -> "propose_doc_add <title> :: <text> — propose adding a knowledge doc (needs approval)"
        "edit" -> "propose_doc_edit <title> :: <new text> — propose replacing a knowledge doc (needs approval)"
        else -> "propose_doc_delete <title> — propose deleting a knowledge doc (needs approval)"
    }

    override suspend fun run(arg: String): String {
        if (mode == "delete") {
            val title = arg.trim()
            if (title.isBlank()) return "Give a document title to delete."
            enqueue(ActionType.DOC_DELETE, "Delete doc \"$title\"", "Delete \"$title\" from the knowledge library.", mapOf("title" to title))
            return proposed("deleting \"$title\"")
        }
        val parts = arg.split("::", limit = 2)
        if (parts.size < 2) return "Use: <title> :: <text>"
        val title = parts[0].trim()
        val text = parts[1].trim()
        if (title.isBlank() || text.isBlank()) return "Both a title and text are required."
        val type = if (mode == "add") ActionType.DOC_ADD else ActionType.DOC_EDIT
        val verb = if (mode == "add") "Add" else "Replace"
        enqueue(type, "$verb doc \"$title\"", "$verb \"$title\":\n" + text.take(400), mapOf("title" to title, "text" to text))
        return proposed("${verb.lowercase()} \"$title\"")
    }

    private suspend fun enqueue(type: String, title: String, preview: String, payload: Map<String, String>) {
        selfEdit.enqueue(
            PendingAction(UUID.randomUUID().toString(), type, title, preview, payload, System.currentTimeMillis()),
        )
    }
}

class SelfInspectTool(
    private val selfEdit: SelfEditStore,
    private val knowledge: KnowledgeStore,
    private val context: Context,
) : JarvisTool {
    override val name = "selfinspect"
    override val usage = "selfinspect <persona|knowledge|assets> — read your own config (read-only)"

    override suspend fun run(arg: String): String {
        val what = arg.trim().lowercase()
        return when {
            what.startsWith("persona") || what.startsWith("charter") -> {
                val charter = runCatching { selfEdit.current().charter }.getOrDefault("")
                if (charter.isBlank()) "Persona: (built-in default — no custom charter set)." else "Persona charter:\n$charter"
            }
            what.startsWith("knowledge") || what.startsWith("doc") -> {
                val titles = runCatching { knowledge.titles() }.getOrDefault(emptyList())
                if (titles.isEmpty()) "Knowledge library is empty." else "Knowledge docs:\n" + titles.joinToString("\n") { "- $it" }
            }
            what.startsWith("asset") -> {
                val files = runCatching { context.assets.list("knowledge")?.toList() }.getOrNull().orEmpty()
                if (files.isEmpty()) "No bundled assets found." else "Bundled assets:\n" + files.joinToString("\n") { "- $it" }
            }
            else -> "Inspect what? Try: persona, knowledge, assets. (Source code isn't on the device, so it can't be read.)"
        }
    }
}
