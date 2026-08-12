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
    /** The prompt-spec generation this entry was produced under (see [NewsAnalysisEngine.CURRENT_VERSION]).
     *  Defaulted so entries cached before versioning existed deserialize as v1 — and get re-analyzed once. */
    val version: Int = 1,
)

/**
 * Synthesises [NewsAnalysis] for one article via the cloud LLM. Mirrors
 * [dev.mascwa.pulse.jarvis.reflection.ReflectionEngine]'s shape: cloud-gated (a no-op unless a backend is
 * Ready), a tolerant free-text-prompt + line-parsed response, fully defensive — never throws.
 *
 * The MARKET line is the owner-specified "MARKET REACTION + IMPACT" desk note — a 180-240-word clinical
 * brief in a senior multi-asset strategist's register (instruments moved → transmission mechanism →
 * positioning backdrop → one concrete catalyst). It is grounded in the measured WIRES-WINDOW TAPE (the
 * macro complex's real 5-minute-bar moves after the story's publish time — [MarketTape]) plus the
 * already-computed [MarketLink] facts and live basket quotes; those are the ONLY numerical prints the
 * model may cite, everything else must stay directional — an invented print is worse than none. The WIDER
 * line is
 * explicitly asked to REASON, hedged, rather than assert specifics: this app has no live feed for
 * internet/social sentiment, political leaning, or conflict status, so that angle has to come from the
 * model's own trained reasoning, prompted to stay honest about what's inference versus fact.
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
        // A PROVIDER, not a value: the wires tape costs ~9 gated Yahoo fetches, so it must only be
        // computed once the free gates below have passed — a cloud-off device must never pay it.
        wiresTape: suspend () -> String? = { null },
    ): NewsAnalysis? = runCatching {
        val s = settings.current()
        if (!s.jarvis.cloudActive) return@runCatching null
        engine.ensureReady()
        if (engine.state.value !is EngineState.Ready) return@runCatching null

        val tape = runCatching { wiresTape() }.getOrNull()
        val raw = engine.generate(
            buildPrompt(title, summary, source, category, links, livePulse, tape),
            emptyList(),
            SYSTEM_PROMPT,
        ).toList().joinToString("").trim()
        parse(raw)
    }.getOrNull()

    private fun buildPrompt(
        title: String,
        summary: String,
        source: String,
        category: String,
        links: List<MarketLink>,
        livePulse: Map<String, Double>,
        wiresTape: String?,
    ): String {
        val marketFacts = if (links.isEmpty()) {
            "No specific markets are clearly implicated by this story."
        } else {
            links.joinToString("; ") { l ->
                val live = livePulse[l.market]?.let {
                    // Locale.US: this string feeds a prompt whose numbers the model may quote verbatim —
                    // a comma-decimal device locale must not turn 1.3% into "1,3".
                    " (today: ${String.format(java.util.Locale.US, "%+.1f", it)}%)"
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
            if (wiresTape != null) {
                appendLine("WIRES-WINDOW TAPE — the macro complex's MEASURED moves around this story's")
                appendLine("publish time, computed from real 5-minute bars. Each line states its own span")
                appendLine("relative to the wire (e.g. wire-3m -> wire+88m):")
                appendLine(wiresTape)
            } else {
                appendLine("WIRES-WINDOW TAPE: unavailable for this story — no measured window moves exist.")
            }
            appendLine("AUTO-TAGGED TOPICS/REGIONS: $tagLine")
        }
    }

    companion object {
        /** The prompt-spec generation. Bump when the register/shape of any line changes materially — a
         *  cached [NewsAnalysis] with an older [NewsAnalysis.version] is treated as absent by the caller
         *  and re-analyzed ONCE into the new register (the "at most once ever" cost invariant becomes
         *  "at most once per spec generation", which is the intended spend). v2: the MARKET line became
         *  the owner-specified 180-240-word clinical desk note. v3: the note is grounded in the measured
         *  WIRES-WINDOW TAPE (real 5-minute-bar moves after the story's publish time). */
        const val CURRENT_VERSION = 3

        /** 240 words of desk note ≈ 1,700 chars; headroom for long words, still rejects runaway output. */
        private const val MARKET_MAX_CHARS = 2400

        /** Internal (not private) so a JVM test can feed it canned model output — the length bounds here
         *  are exactly what decides whether a desk note survives or the card falls back to heuristics. */
        internal fun parse(raw: String): NewsAnalysis? {
            val lines = raw.lines().map { it.trim() }
            fun grab(prefix: String, max: Int = 600): String? =
                lines.firstOrNull { it.startsWith(prefix, ignoreCase = true) }
                    ?.substringAfter(":", "")?.trim()?.trim('"')
                    ?.takeIf { it.length in 8..max }

            val mood = grab("MOOD") ?: return null
            val market = grab("MARKET", MARKET_MAX_CHARS) ?: return null
            val wider = grab("WIDER") ?: return null
            val next = grab("NEXT") ?: return null
            return NewsAnalysis(mood, market, wider, next, System.currentTimeMillis(), CURRENT_VERSION)
        }

        val SYSTEM_PROMPT = """
            You write the analysis strips for a general-interest news app. Given one story (headline,
            summary, source, category, known market facts, auto-tagged topics/regions), write EXACTLY these
            4 lines, each starting with its exact prefix, each on ONE single line, nothing else.

            Two registers, switched cleanly: MOOD/WIDER/NEXT are for readers who know nothing about markets
            or politics — direct, warm and confiding, like a sharp friend explaining it over coffee. MARKET
            is the opposite — a clinical desk note.

            MOOD: one confiding sentence on the story's overall vibe, in plain words.

            MARKET: a desk note of 180-240 words as ONE continuous paragraph on this single line, in the
            style a senior multi-asset or rates strategist uses to brief a portfolio manager. Dense,
            mechanistic, cause-and-effect — do not rehash the story, do not moralize, no generic
            commentary. Cover, in this exact order with zero deviation: (1) the specific instruments and
            asset classes that moved on this story — lead with the WIRES-WINDOW TAPE, the MEASURED move of
            each macro instrument around this story's publish time, computed from real 5-minute bars. Cite
            its figures directly, respecting each line's OWN stated span: a "wire-Xm -> wire+Ym" line is
            the live reaction window; a line saying the last print came LONG before the wire means the
            market was not printing when the story hit (closed, or simply not trading) — frame that figure
            as the subsequent reaction across the stated gap, never as a live response, and treat a span
            much wider than ~90 minutes with proportionate caution. A tape move is what happened DURING
            its span, not necessarily BECAUSE of this story — attribute causation only where the story is
            plausibly the driver, and say when the move likely belongs to the broader session instead. The
            tape plus KNOWN MARKET FACTS are the ONLY prints that exist; cite those exact figures and no
            others — NEVER invent a numerical print; describe anything they don't cover directionally and
            logically. If the tape is unavailable, every magnitude stays directional. If the story
            plausibly moved nothing, state that plainly and explain why it does not transmit. (2) The
            transmission
            mechanism in precise language: how the relevant desks (rates, equity risk, systematic/macro,
            credit, FX) interpret the news; which probability or expected cash-flow path the market is
            adjusting (policy odds, regulatory risk, earnings trajectory, supply/demand balance,
            geopolitical risk premium); and the second-order flows that follow (duration positioning,
            sector rotation, volatility buying or selling, relative-value trades, forced rebalancing).
            (3) The positioning backdrop amplifying or dampening the move — crowded longs or shorts,
            recent central-bank communication, upcoming data or event risk, leverage. (4) Close with one
            concrete near-term risk or catalyst traders now watch specifically because of this
            development. Banned in this line: bullets, headers, moral/political/narrative language about
            the people or parties involved, and filler such as "investors are watching", "markets are
            reacting to uncertainty", "could go either way", "time will tell".

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
