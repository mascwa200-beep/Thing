package dev.mascwa.pulse.jarvis.agent

import android.util.Base64
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.telemetry.DeviceContextProvider
import dev.mascwa.pulse.core.telemetry.Readability
import dev.mascwa.pulse.data.jarvis.JarvisMemory
import dev.mascwa.pulse.data.jarvis.KnowledgeStore
import dev.mascwa.pulse.data.jarvis.db.NoteSource
import dev.mascwa.pulse.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.net.URLEncoder

private const val MAX_OBS = 1500

private fun String.clip(n: Int = MAX_OBS): String =
    if (length <= n) this else take(n) + "…[truncated]"

// Compiled once — stripHtml runs on every page the agent fetches.
private val HTML_SCRIPT_STYLE = Regex("(?is)<(script|style)[^>]*>.*?</\\1>")
private val HTML_TAG = Regex("<[^>]+>")
private val HTML_WHITESPACE = Regex("\\s+")

/** Strip HTML to rough plain text. */
private fun stripHtml(html: String): String =
    html.replace(HTML_SCRIPT_STYLE, " ")
        .replace(HTML_TAG, " ")
        .replace(HTML_WHITESPACE, " ")
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

/**
 * Fetch a URL and return what was written on it.
 *
 * ⚠️ **THIS USED TO RETURN THE NAVIGATION AND CALL IT THE PAGE.** It stripped every tag and handed
 * back the first 1500 characters, which on a real page is the skip-link, the masthead, the menu and
 * the site's own promoted headlines. Measured over five real pages, the article did not begin within
 * that budget on ANY of them: BBC at character 1,609, MDN at 2,112, and the Associated Press at
 * **34,972** of a 47,010-character strip. On AP the tool did not merely return nothing useful — it
 * returned OTHER STORIES' headlines out of the nav rail ("The Moscow region is hit by a drone
 * blitz") inside what was supposed to be a musician's obituary, which the model would read as the
 * content of the page it asked for. That is a correctness defect, not a quality one.
 *
 * ⚠️ It also fetched with no size cap at all, so a heavy page was read whole into a string.
 *
 * Deliberately NOT routed through `ReaderRepository`, even though the two do similar work. A reader
 * wants an article and is right to refuse a PDF or a JSON feed; a `fetch` tool wants *whatever is
 * there*, because the model may well be asking for an API response or a plain-text file. So the
 * ladder here is its own: non-HTML comes back as-is, HTML is decimated, and anything the decimator
 * will not call an article falls back to the old crude strip — which keeps every page that works
 * today working, and is why this can only improve the tool's output.
 */
class WebFetchTool(private val http: HttpClient) : JarvisTool {
    override val name = "fetch"
    override val usage = "fetch <https url> — fetch a page and return its readable text (the article, not the site chrome)"

    override suspend fun run(arg: String): String {
        val url = arg.trim()
        if (!url.startsWith("http")) return "Provide a full http(s) URL."
        // Known redirect stubs: say so instead of fetching a shell page and reporting it as content.
        Readability.unreadableReason(url)?.let { return it }

        val res = runCatching { http.getTextCapped(url, MAX_FETCH_CHARS) }
            .getOrElse { return "Fetch failed: ${it.message}" }

        val type = res.contentType?.substringBefore(';')?.trim()?.lowercase()
        val isHtml = type == null ||
            type.startsWith("text/html") ||
            type.startsWith("application/xhtml")
        // A JSON feed, a CSV, a plain-text file: hand it over untouched. Running the tag stripper
        // over JSON mangles it, and there is no article in it to find.
        if (!isHtml) return res.body.trim().clip(PAGE_OBS)

        val e = withContext(Dispatchers.Default) { Readability.extract(res.body, res.finalUrl) }
        if (e.isArticle) {
            return buildString {
                e.meta.title?.let { appendLine(it) }
                e.meta.byline?.let { appendLine("By $it") }
                appendLine()
                append(Readability.plainText(e))
            }.trim().clip(PAGE_OBS)
        }
        // An index, a listing, a wall, a page built entirely by script. Say what was concluded, then
        // fall back to exactly what this tool did before, so nothing that worked stops working.
        return (e.note?.let { "($it)\n" } ?: "") + stripHtml(res.body).clip()
    }

    private companion object {
        /**
         * The ceiling on a page this tool will read into memory.
         *
         * Lower than the reader's, because a tool result is bounded to a few thousand characters
         * anyway — reading four times that off the wire only to discard it is waste, not safety.
         */
        const val MAX_FETCH_CHARS = 1_000_000

        /**
         * How much of a decimated page to hand back.
         *
         * Larger than [MAX_OBS], and the reason is the measurement above: 1500 characters of an
         * article is worth more than 1500 characters of menu, so the budget that was being spent on
         * chrome can now buy content. Kept below `AgentOrchestrator.MAX_OBS` (6000), which truncates
         * every observation anyway — so this is a real bound and not a way around that one.
         */
        const val PAGE_OBS = 4000
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
