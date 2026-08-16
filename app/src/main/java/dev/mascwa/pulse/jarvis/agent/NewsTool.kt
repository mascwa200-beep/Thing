package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.core.telemetry.EmergencyNews
import dev.mascwa.pulse.core.telemetry.NewsInsights
import dev.mascwa.pulse.core.telemetry.NewsMarketLink
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.data.news.Article
import dev.mascwa.pulse.data.news.NewsCategory
import dev.mascwa.pulse.data.news.NewsRepository

/**
 * The news the app already has, with what it already worked out about it.
 *
 * The console could search the web for headlines, which is both slower and worse: the app has fetched
 * this feed, and the same classifiers the NEWS screen renders — tone, topics, market links, emergency
 * severity — have already run over it. Reading it here means the answer matches what the user sees on
 * the screen instead of quietly disagreeing with it.
 *
 * Reads the warm cache. Read-only.
 */
class NewsTool(private val news: NewsRepository) : JarvisTool {
    override val name = "news"
    override val usage =
        "news [topic|world|business|tech|politics] — current headlines with their tone, subject and " +
            "likely market effect (blank = top stories)"

    private val maxItems = 6

    override suspend fun run(arg: String): String {
        val a = arg.trim()
        val category = a.takeIf { it.isNotBlank() }?.let { q ->
            NewsCategory.entries.firstOrNull { it.name.equals(q, true) || it.title.equals(q, true) }
        }

        val articles: List<Article> = when {
            // A recognised section name reads the section; anything else is treated as a search.
            category != null -> runCatching { news.fetchCategory(category, force = false).data }
                .getOrDefault(emptyList())
            a.isNotBlank() -> runCatching { news.search(a) }.getOrDefault(emptyList())
            else -> runCatching { news.fetchCategory(NewsCategory.TOP, force = false).data }
                .getOrDefault(emptyList())
        }

        if (articles.isEmpty()) {
            return if (a.isBlank()) "No headlines available — the feed didn't answer."
            else "Nothing in the current feed about \"$a\"."
        }

        val heading = category?.title ?: a.takeIf { it.isNotBlank() }?.let { "\"$it\"" } ?: "Top stories"
        return buildString {
            append(heading).append(":\n")
            articles.take(maxItems).forEach { append("\n").append(entry(it)) }
        }
    }

    private fun entry(x: Article): String = buildString {
        // Severity leads, because a disaster headline should not read like the rest of the list.
        if (EmergencyNews.isMajor(x.title, x.summary)) append("‼ ")
        else if (EmergencyNews.isEmergency(x.title, x.summary)) append("! ")

        append(x.title).append("\n   ").append(x.source)
        if (x.publishedEpochMs > 0) append(" · ").append(Formatters.relativeTime(x.publishedEpochMs))

        val (tone, _) = NewsInsights.tone(x.title, x.summary)
        append(" · ").append(tone.label)
        NewsInsights.topics(x.title, x.summary, max = 3).takeIf { it.isNotEmpty() }
            ?.let { append(" · ").append(it.joinToString(", ")) }

        // What the app already concluded it might move, so the console and the NEWS screen agree.
        val links = NewsMarketLink.linksFor(x.title, x.summary, x.category)
        if (links.isNotEmpty()) {
            append("\n   Markets: ").append(NewsMarketLink.summarize(links))
        }
    }
}
