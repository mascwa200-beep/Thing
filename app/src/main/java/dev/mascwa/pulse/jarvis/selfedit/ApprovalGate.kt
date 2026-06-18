package dev.mascwa.pulse.jarvis.selfedit

import dev.mascwa.pulse.data.jarvis.KnowledgeStore
import dev.mascwa.pulse.data.selfedit.ActionType
import dev.mascwa.pulse.data.selfedit.ArtifactVersion
import dev.mascwa.pulse.data.selfedit.AuthoredTool
import dev.mascwa.pulse.data.selfedit.PendingAction
import dev.mascwa.pulse.data.selfedit.SelfEditStore

/**
 * THE safety boundary. This is the ONLY code that performs a self-change (edit persona/knowledge,
 * later: research/tool registration). It must be invoked exclusively from a user-driven UI tap
 * (`JarvisApprovalsViewModel`), never from the agent loop or a tool. The agent can only ENQUEUE a
 * [PendingAction]; nothing happens until the user approves it here. That makes the human gate a
 * control-flow invariant: even a successful prompt-injection can at most create a proposal the user
 * sees and rejects — it can never write, fetch, or execute on its own.
 */
class ApprovalGate(
    private val selfEdit: SelfEditStore,
    private val knowledge: KnowledgeStore,
    /** Runs a web search for a topic (wired to the vetted WebSearchTool). Only invoked here, after
     *  the user approves a RESEARCH proposal — never by the agent itself. */
    private val research: suspend (String) -> String = { "" },
    /** Opens the GitHub PR for an approved CODE_PR proposal (wired to SelfCoder.commit). Only invoked
     *  here, after the user approves — so no self-code PR is ever opened without the user's tap. */
    private val commitCode: suspend (PendingAction) -> String = { "Self-coding isn't available." },
) {
    /** Apply an approved action, then remove it from the queue. Returns a short result message. */
    suspend fun apply(action: PendingAction): String {
        val result = when (action.type) {
            ActionType.PERSONA_EDIT -> {
                selfEdit.setCharter(action.payload["text"].orEmpty()) // setCharter snapshots the old one
                "Persona updated."
            }
            ActionType.DOC_ADD -> {
                val title = action.payload["title"].orEmpty()
                val n = knowledge.addDocument(title, action.payload["text"].orEmpty(), source = SELF_SOURCE)
                "Added \"$title\" ($n chunk(s))."
            }
            ActionType.DOC_EDIT -> {
                val title = action.payload["title"].orEmpty()
                snapshotDoc(title)
                knowledge.deleteDocument(title)
                val n = knowledge.addDocument(title, action.payload["text"].orEmpty(), source = SELF_SOURCE)
                "Updated \"$title\" ($n chunk(s))."
            }
            ActionType.DOC_DELETE -> {
                val title = action.payload["title"].orEmpty()
                snapshotDoc(title)
                knowledge.deleteDocument(title)
                "Deleted \"$title\"."
            }
            ActionType.RESEARCH -> {
                // Only NOW (post-approval) do we touch the network, and only via the vetted search tool.
                val topic = action.payload["topic"].orEmpty()
                val summary = runCatching { research(topic) }.getOrElse { "Research failed: ${it.message}" }
                val n = knowledge.addDocument("Research: $topic", summary, source = "research")
                "Researched \"$topic\" — saved $n chunk(s)."
            }
            ActionType.TOOL_REGISTER -> {
                val toolName = action.payload["name"].orEmpty()
                val script = action.payload["script"].orEmpty()
                if (toolName.isBlank() || script.isBlank()) {
                    "Invalid tool proposal."
                } else {
                    val caps = action.payload["caps"].orEmpty().split(",").map { it.trim() }.filter { it.isNotBlank() }
                    val usage = "$toolName <arg> — authored tool" + (if (caps.isEmpty()) "" else " (caps: ${caps.joinToString()})")
                    selfEdit.addAuthoredTool(AuthoredTool(toolName, usage, script, caps, enabled = true))
                    "Registered tool \"$toolName\"."
                }
            }
            ActionType.CODE_PR -> {
                // Only NOW (post-approval) do we write to GitHub — and only via SelfCoder.commit.
                commitCode(action)
            }
            else -> "Unsupported action type: ${action.type}."
        }
        selfEdit.removePending(action.id)
        return result
    }

    suspend fun reject(id: String) = selfEdit.removePending(id)

    /** Re-apply a stored version. Charter rolls back in place; a doc snapshot is re-added. */
    suspend fun rollback(version: ArtifactVersion): String = when (version.type) {
        "charter" -> { selfEdit.setCharter(version.content); "Persona rolled back." }
        "doc" -> {
            knowledge.deleteDocument(version.key)
            knowledge.addDocument(version.key, version.content, source = SELF_SOURCE)
            "Restored \"${version.key}\"."
        }
        else -> "Nothing to roll back."
    }

    private suspend fun snapshotDoc(title: String) {
        val old = runCatching { knowledge.fullText(title) }.getOrDefault("")
        if (old.isNotBlank()) selfEdit.pushVersion("doc", title, old)
    }

    private companion object {
        const val SELF_SOURCE = "self-edit"
    }
}
