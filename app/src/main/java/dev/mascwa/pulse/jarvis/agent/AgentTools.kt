package dev.mascwa.pulse.jarvis.agent

import android.util.Base64
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.telemetry.DeviceContextProvider
import dev.mascwa.pulse.data.jarvis.JarvisMemory
import dev.mascwa.pulse.data.jarvis.KnowledgeStore
import dev.mascwa.pulse.data.jarvis.db.NoteSource
import dev.mascwa.pulse.data.settings.SettingsRepository
import kotlinx.serialization.Serializable
import java.net.URLEncoder

private const val MAX_OBS = 1500

private fun String.clip(n: Int = MAX_OBS): String =
    if (length <= n) this else take(n) + "…[truncated]"

/** Strip HTML to rough plain text. */
private fun stripHtml(html: String): String =
    html.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

/** Keyless web search via DuckDuckGo's Instant-Answer API (limited but no key/account). */
class WebSearchTool(private val http: HttpClient) : JarvisTool {
    override val name = "web"
    override val usage = "web <query> — search the web (DuckDuckGo, brief answer)"

    @Serializable
    private data class Ddg(
        val AbstractText: String = "",
        val Heading: String = "",
        val RelatedTopics: List<Topic> = emptyList(),
    )

    @Serializable
    private data class Topic(val Text: String = "")

    override suspend fun run(arg: String): String = runCatching {
        val q = URLEncoder.encode(arg.trim(), "UTF-8")
        val url = "https://api.duckduckgo.com/?q=$q&format=json&no_html=1&skip_disambig=1"
        val r = http.getJson(url, Ddg.serializer())
        val abstract = r.AbstractText.trim()
        if (abstract.isNotBlank()) return@runCatching abstract.clip()
        val topics = r.RelatedTopics.map { it.Text.trim() }.filter { it.isNotBlank() }.take(4)
        if (topics.isEmpty()) "No instant answer found for \"$arg\"." else topics.joinToString("\n").clip()
    }.getOrElse { "Web search failed: ${it.message}" }
}

/** Fetch a URL and return its readable text. */
class WebFetchTool(private val http: HttpClient) : JarvisTool {
    override val name = "fetch"
    override val usage = "fetch <https url> — fetch a page and return its text"

    override suspend fun run(arg: String): String {
        val url = arg.trim()
        if (!url.startsWith("http")) return "Provide a full http(s) URL."
        return runCatching { stripHtml(http.getString(url)).clip() }
            .getOrElse { "Fetch failed: ${it.message}" }
    }
}

/**
 * Read-only GitHub access. Arg: `owner/repo` or `owner/repo:path`. Lists a directory or returns a
 * file's text. Public repos are keyless; private repos use the GitHub token from settings.
 */
class RepoReadTool(
    private val http: HttpClient,
    private val settings: SettingsRepository,
) : JarvisTool {
    override val name = "repo"
    override val usage = "repo <owner/repo[:path]> — list a folder or read a file from a GitHub repo (read-only)"

    @Serializable
    private data class Content(
        val type: String = "",
        val name: String = "",
        val path: String = "",
        val content: String = "",
        val encoding: String = "",
    )

    override suspend fun run(arg: String): String = runCatching {
        val spec = arg.trim()
        val repo = spec.substringBefore(':').trim()
        val path = spec.substringAfter(':', "").trim()
        if (!repo.contains('/')) return@runCatching "Use owner/repo or owner/repo:path."
        val url = "https://api.github.com/repos/$repo/contents/$path"
        val token = runCatching { settings.current().jarvis.githubToken }.getOrNull()
        val headers = buildMap {
            put("Accept", "application/vnd.github+json")
            put("X-GitHub-Api-Version", "2022-11-28")
            if (!token.isNullOrBlank()) put("Authorization", "Bearer $token")
        }
        val body = http.getString(url, headers).trim()
        if (body.startsWith("[")) {
            // Directory listing.
            val items = http.json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Content.serializer()), body)
            "Directory $repo/$path:\n" + items.joinToString("\n") { "- ${it.name} (${it.type})" }.clip()
        } else {
            val item = http.json.decodeFromString(Content.serializer(), body)
            if (item.encoding == "base64" && item.content.isNotBlank()) {
                val decoded = String(Base64.decode(item.content.replace("\n", ""), Base64.DEFAULT))
                "File ${item.path}:\n${decoded.clip()}"
            } else {
                "No readable content at $repo/$path."
            }
        }
    }.getOrElse { "Repo read failed: ${it.message}" }
}

/** Save a durable note/fact. */
class RememberTool(private val memory: JarvisMemory) : JarvisTool {
    override val name = "remember"
    override val usage = "remember <fact> — save a fact to durable memory"

    override suspend fun run(arg: String): String {
        val text = arg.trim()
        if (text.isBlank()) return "Nothing to remember."
        memory.remember(text, NoteSource.INFERENCE)
        return "Saved: \"$text\""
    }
}

/** Retrieve relevant saved notes. */
class RecallTool(private val memory: JarvisMemory) : JarvisTool {
    override val name = "recall"
    override val usage = "recall <topic> — search durable memory for relevant facts"

    override suspend fun run(arg: String): String {
        val notes = memory.recall(arg.trim(), limit = 6)
        return if (notes.isEmpty()) "No saved notes match \"$arg\"."
        else notes.joinToString("\n") { "- ${it.noteText}" }.clip()
    }
}

/** Search the on-device knowledge library (the docs the user has loaded) — the docs RAG. */
class KnowledgeTool(private val knowledge: KnowledgeStore) : JarvisTool {
    override val name = "docs"
    override val usage = "docs <query> — search your loaded knowledge library (programming docs, notes, etc.)"

    override suspend fun run(arg: String): String {
        val hits = knowledge.search(arg.trim(), limit = 5)
        return if (hits.isEmpty()) "No docs match \"$arg\"."
        else hits.joinToString("\n\n") { "[${it.title}] ${it.text}" }.clip()
    }
}

/** Live device state, straight from the hardware/OS — your own substrate. */
class DeviceTool(private val device: DeviceContextProvider) : JarvisTool {
    override val name = "device"
    override val usage =
        "device [audit] — report this phone (model, OS, build, memory, storage, power, network). " +
            "`device audit` runs a deeper read-only self-audit of your substrate: sensors, hardware features, display, permissions"

    override suspend fun run(arg: String): String = runCatching {
        if (arg.trim().lowercase().startsWith("audit")) device.deviceAudit()
        else device.deviceReport()
    }.getOrElse { "Device read failed: ${it.message}" }
}
