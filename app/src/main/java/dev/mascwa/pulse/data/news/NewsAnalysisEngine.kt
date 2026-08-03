package dev.mascwa.pulse.data.news

import dev.mascwa.pulse.core.telemetry.MarketLink
import dev.mascwa.pulse.core.telemetry.NewsInsights
import dev.mascwa.pulse.data.settings.SettingsRepository
import dev.mascwa.pulse.jarvis.inference.EngineState
import dev.mascwa.pulse.jarvis.inference.LocalInferenceEngine
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable

/**
 * A short, plain-English cross-domain read of one news article, synthesised by the cloud LLM: what it's
 * about, why (if at all) it moves money, how different audiences are likely reacting — everyday readers,
 * social media, left- and right-leaning commentary, the international angle — and where this likely heads
 * next. Cached forever per article by [NewsAnalysisStore]. Never partially filled: a malformed model
 * response yields no [NewsAnalysis] at all (see [NewsAnalysisEngine.analyze]), so every field here is a
 * real, validated line when this class exists.
 */
@Serializable
data class NewsAnalysis(
    val moodLine: String,
    val marketLine: String,
    val widerLine: String,
    val nextLine: String,
    val generatedAtMs: Long,
)

/**
 * Synthesises [NewsAnalysis] for one article via the cloud LLM. Mirrors
 * [dev.mascwa.pulse.jarvis.reflection.ReflectionEngine]'s shape: cloud-gated (a no-op unless a backend is
 * Ready), a tolerant free-text-prompt + line-parsed response, fully defensive — never throws.
 *
 * The MARKET line is grounded in real, already-computed [MarketLink] facts (so the model can't invent a
 * price or market that isn't actually implicated) — but the WIDER line is explicitly asked to REASON,
 * hedged, rather than assert specifics: this app has no live feed for internet/social sentiment, political
 * leaning, or conflict status, so that angle has to come from the model's own trained reasoning, prompted to
 * stay honest about what's inference versus fact.
 */
class NewsAnalysisEngine(
    private val engine: LocalInferenceEngine,
    private val settings: SettingsRepository,
) {

    /**
     * Analyze one article. Returns null when cloud isn't active/ready, the call errors, or the response
     * can't be parsed into all 4 lines — callers fall back to the existing heuristic copy in that case.
     * Never throws.
     */
    suspend fun analyze(
        title: String,
        summary: String,
        source: String,
        category: String,
        links: List<MarketLink>,
        livePulse: Map<String, Double>,
    ): NewsAnalysis? = runCatching {
        val s = settings.current()
        if (!s.jarvis.cloudActive) return@runCatching null
        engine.ensureReady()
        if (engine.state.value !is EngineState.Ready) return@runCatching null

        val raw = engine.generate(buildPrompt(title, summary, source, category, links, livePulse), emptyList(), SYSTEM_PROMPT)
            .toList().joinToString("").trim()
        parse(raw)
    }.getOrNull()

    private fun buildPrompt(
        title: String,
        summary: String,
        source: String,
        category: String,
        links: List<MarketLink>,
        livePulse: Map<String, Double>,
    ): String {
        val marketFacts = if (links.isEmpty()) {
            "No specific markets are clearly implicated by this story."
        } else {
            links.joinToString("; ") { l ->
                val live = livePulse[l.market]?.let {
                    val sign = if (it >= 0.0) "+" else ""
                    " (today: $sign${"%.1f".format(it)}%)"
                } ?: ""
                "${l.market} likely ${l.impact.name}$live — ${l.why}"
            }
        }
        val tags = NewsInsights.topics(title, summary)
        val tagLine = if (tags.isEmpty()) "none auto-detected" else tags.joinToString(", ")
        return buildString {
            appendLine("HEADLINE: $title")
            if (summary.isNotBlank()) appendLine("SUMMARY: $summary")
            appendLine("SOURCE: $source · CATEGORY: $category")
            appendLine("KNOWN MARKET FACTS: $marketFacts")
            appendLine("AUTO-TAGGED TOPICS/REGIONS: $tagLine")
        }
    }

    private fun parse(raw: String): NewsAnalysis? {
        val lines = raw.lines().map { it.trim() }
        fun grab(prefix: String): String? =
            lines.firstOrNull { it.startsWith(prefix, ignoreCase = true) }
                ?.substringAfter(":", "")?.trim()?.trim('"')
                ?.takeIf { it.length in 8..600 }

        val mood = grab("MOOD") ?: return null
        val market = grab("MARKET") ?: return null
        val wider = grab("WIDER") ?: return null
        val next = grab("NEXT") ?: return null
        return NewsAnalysis(mood, market, wider, next, System.currentTimeMillis())
    }

    private companion object {
        val SYSTEM_PROMPT = """
            You write short, plain-English "here's what's really going on" copy for a general-interest news
            app, aimed at readers who know nothing about markets or politics. Be direct, warm and confiding —
            like a sharp friend explaining it over coffee, never a dry report. Style reference, from this
            app's existing copy: "Here's the real story: the taps just got tighter, so every barrel left on
            the market is worth more." / "Here's who's actually moving: Oil, Gold catching a bid — Airlines
            paying for it."

            Given one news story (headline, summary, source, category, known market facts, auto-tagged
            topics/regions), write EXACTLY these 4 lines, each starting with its exact prefix, nothing else:

            MOOD: one confiding sentence on the story's overall vibe, in plain words.
            MARKET: one confiding sentence on what/why this moves money — use ONLY the given market facts,
            never invent a price, market or link that wasn't provided. If no market facts were given, say
            plainly that this story doesn't look like a market mover.
            WIDER: 2-3 hedged sentences ("likely"/"probably"/"expect") synthesizing how different groups are
            probably reacting — everyday readers, social media, people leaning left, people leaning right,
            the view from other countries, and any real economic or conflict backdrop that's genuinely
            relevant. Never invent a specific quote, poll number, or statistic you can't verify — reason in
            general, honest terms, and say so when you're speculating.
            NEXT: one sentence on where this likely heads next, given what caused it and what it is.

            No preamble, no markdown, no numbering, no extra lines — exactly those 4 lines, in that order.
        """.trimIndent()
    }
}
