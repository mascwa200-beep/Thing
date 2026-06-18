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

class ProposeResearchTool(private val selfEdit: SelfEditStore) : JarvisTool {
    override val name = "propose_research"
    override val usage = "propose_research <topic> — suggest researching a topic and saving a summary (needs approval; never fetches on its own)"
    override suspend fun run(arg: String): String {
        val topic = arg.trim()
        if (topic.isBlank()) return "What should I research?"
        selfEdit.enqueue(
            PendingAction(
                id = UUID.randomUUID().toString(),
                type = ActionType.RESEARCH,
                title = "Research: $topic",
                preview = "On approval, search the web for \"$topic\" and save a summary to your knowledge library.",
                payload = mapOf("topic" to topic),
                createdAt = System.currentTimeMillis(),
            ),
        )
        return proposed("researching \"$topic\"")
    }
}

class ProposeToolTool(private val selfEdit: SelfEditStore) : JarvisTool {
    override val name = "propose_tool"
    override val usage = "propose_tool <name> | <caps csv: web,fetch,docs,recall> | <lua defining run(arg) returning text> — register a TINY sandboxed Lua text-helper (caps limited to web/fetch/docs/recall). It CANNOT add real app capabilities like file/image/camera access, permissions or UI — for ANY real app feature or capability use `selfcode` instead. Needs approval."
    override suspend fun run(arg: String): String {
        val parts = arg.split("|", limit = 3)
        if (parts.size < 3) return "Use: <name> | <caps csv> | <lua script>"
        val toolName = parts[0].trim().lowercase().replace(Regex("[^a-z0-9_]"), "")
        val script = parts[2].trim()
        if (toolName.isBlank() || script.isBlank()) return "A name and a script are required."
        val caps = parts[1].split(",").map { it.trim().lowercase() }.filter { it in ALLOWED_CAPS }
        selfEdit.enqueue(
            PendingAction(
                id = UUID.randomUUID().toString(),
                type = ActionType.TOOL_REGISTER,
                title = "New tool: $toolName",
                preview = "Capabilities: ${if (caps.isEmpty()) "none (pure compute)" else caps.joinToString()}\n\nScript:\n" + script.take(800),
                payload = mapOf("name" to toolName, "caps" to caps.joinToString(","), "script" to script),
                createdAt = System.currentTimeMillis(),
            ),
        )
        return proposed("a new tool \"$toolName\"")
    }

    private companion object {
        val ALLOWED_CAPS = setOf("web", "fetch", "docs", "recall")
    }
}

class SelfInspectTool(
    private val selfEdit: SelfEditStore,
    private val knowledge: KnowledgeStore,
    private val context: Context,
) : JarvisTool {
    override val name = "selfinspect"
    override val usage = "selfinspect <persona|knowledge|assets|tools> — read your own config (read-only)"

    override suspend fun run(arg: String): String {
        val what = arg.trim().lowercase()
        return when {
            what.startsWith("tool") -> {
                val authored = runCatching { selfEdit.current().authoredTools }.getOrDefault(emptyList())
                val builtins = "Built-in tools: web, fetch, repo, remember, recall, docs, device."
                if (authored.isEmpty()) "$builtins\nNo authored tools yet."
                else "$builtins\nAuthored tools:\n" + authored.joinToString("\n") {
                    "- ${it.name}${if (it.enabled) "" else " (off)"} [${it.caps.joinToString(",")}]"
                }
            }
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
            else -> "Inspect what? Try: persona, knowledge, assets, tools. (To read your own source code, use the `code` tool when self-coding is on.)"
        }
    }
}
