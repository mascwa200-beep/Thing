package dev.mascwa.pulse.core.telemetry

import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The **episodic memory stream** for J.A.R.V.I.S. — the deliberate, time-aware long-term memory layer
 * of the on-device cognitive stack (beside the structured profile, the procedural cerebellum and the
 * task board). Modelled on the *Generative Agents* memory stream (Park et al. 2023): every memory is
 * timestamped and rated for importance, retrieval scores candidates by **recency + importance +
 * relevance**, and a periodic **reflection** pass synthesises higher-level memories from recent ones.
 *
 * This file is the pure, CI-tested core — the scoring maths and selection logic. It operates on
 * memories that already carry an **embedding vector** (relevance is cosine similarity); generating
 * those embeddings on-device, persisting the stream, and running the reflection LLM call all live in
 * the app layer. Keeping the maths here means CI gates the part that's easy to get subtly wrong.
 *
 * Honest scope: this is engineered *time-aware retrieval*, not "temporal consciousness" — timestamps,
 * exponential recency decay, importance weighting and cosine relevance. Nothing here is sapient.
 */
enum class MemoryKind {
    /** A raw event/observation recorded as it happened. */
    OBSERVATION,

    /** A higher-level memory the reflection pass synthesised from observations. */
    REFLECTION,
}

/**
 * One memory. [importance] is a 1–10 "poignancy" rating (mundane → significant). [embedding] is the
 * semantic vector used for relevance; [lastAccessedMs] is bumped whenever the memory is retrieved, so
 * recency reflects *use*, not just creation (as in Generative Agents). [linkedEntityIds] are
 * cross-referenced IDs (profile, task, context) surfaced at retrieval time for real-time linking.
 */
data class Memory(
    val id: Long,
    val text: String,
    val kind: MemoryKind,
    val importance: Int,
    val createdMs: Long,
    val lastAccessedMs: Long,
    val embedding: List<Float>,
    val linkedEntityIds: Map<String, List<String>> = emptyMap(),
)

/** A memory plus its retrieval score and the normalized components that produced it (for transparency). */
data class ScoredMemory(
    val memory: Memory,
    val score: Double,
    val recency: Double,
    val importance: Double,
    val relevance: Double,
)

/** Relative weighting of the three retrieval components (all 1.0 = the Generative-Agents default). */
data class RetrievalWeights(
    val recency: Double = 1.0,
    val importance: Double = 1.0,
    val relevance: Double = 1.0,
)

/** Callback to resolve linked entities from stores during retrieval. */
interface LinkedEntityResolver {
    suspend fun resolveProjectsAndTasks(queryText: String): Map<String, List<String>>
}

object MemoryStream {
    const val MAX_IMPORTANCE = 10
    const val MIN_IMPORTANCE = 1
    const val DEFAULT_TOP_K = 5
    const val DEFAULT_HALF_LIFE_MS = 24L * 60L * 60L * 1000L // recency halves every 24h of disuse
    /** Reflection fires once the importance of un-reflected recent observations crosses this sum. */
    const val DEFAULT_REFLECTION_THRESHOLD = 50
    const val DEFAULT_REFLECTION_SEEDS = 8

    // ---- Relevance ---------------------------------------------------------

    /** Cosine similarity of two vectors, in [-1, 1]. 0 for empty/mismatched/zero vectors. */
    fun cosine(a: List<Float>, b: List<Float>): Double {
        if (a.isEmpty() || a.size != b.size) return 0.0
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            dot += x * y
            na += x * x
            nb += y * y
        }
        if (na == 0.0 || nb == 0.0) return 0.0
        return dot / (sqrt(na) * sqrt(nb))
    }

    // ---- Recency -----------------------------------------------------------

    /** Exponential recency: 1.0 at zero elapsed, halving every [halfLifeMs]. Clamps negative elapsed. */
    fun recencyDecay(elapsedMs: Long, halfLifeMs: Long = DEFAULT_HALF_LIFE_MS): Double {
        if (halfLifeMs <= 0L) return if (elapsedMs <= 0L) 1.0 else 0.0
        val e = if (elapsedMs < 0L) 0.0 else elapsedMs.toDouble()
        return 0.5.pow(e / halfLifeMs.toDouble())
    }

    // ---- Retrieval ---------------------------------------------------------

    /**
     * Retrieve the top-[topK] memories for [queryEmbedding] at time [nowMs]. Each of the three
     * components (recency, importance, relevance) is min-max normalized across the candidate set to
     * [0,1] and then combined with [weights] — the Generative-Agents retrieval function. Pass an empty
     * [queryEmbedding] to rank by recency + importance alone (relevance contributes nothing).
     * 
     * If [linkedEntityResolver] is provided, enrich each result with cross-referenced project/task
     * entities from ProfileStore and CerebellumStore, resolved in real time during retrieval.
     */
    suspend fun retrieve(
        memories: List<Memory>,
        queryEmbedding: List<Float>,
        nowMs: Long,
        topK: Int = DEFAULT_TOP_K,
        weights: RetrievalWeights = RetrievalWeights(),
        halfLifeMs: Long = DEFAULT_HALF_LIFE_MS,
        linkedEntityResolver: LinkedEntityResolver? = null,
        queryText: String = "",
    ): List<ScoredMemory> {
        if (memories.isEmpty() || topK <= 0) return emptyList()
        val rawRecency = memories.map { recencyDecay(nowMs - it.lastAccessedMs, halfLifeMs) }
        val rawImportance = memories.map { it.importance.toDouble() }
        val rawRelevance = memories.map {
            if (queryEmbedding.isEmpty()) 0.0 else cosine(queryEmbedding, it.embedding)
        }
        val recN = minMaxNormalize(rawRecency)
        val impN = minMaxNormalize(rawImportance)
        val relN = minMaxNormalize(rawRelevance)
        
        val linkedEntities = if (linkedEntityResolver != null && queryText.isNotBlank()) {
            linkedEntityResolver.resolveProjectsAndTasks(queryText)
        } else {
            emptyMap()
        }
        
        return memories.indices.map { i ->
            val score = weights.recency * recN[i] +
                weights.importance * impN[i] +
                weights.relevance * relN[i]
            val enrichedMemory = memories[i].copy(linkedEntityIds = linkedEntities)
            ScoredMemory(enrichedMemory, score, recN[i], impN[i], relN[i])
        }.sortedWith(
            compareByDescending<ScoredMemory> { it.score }.thenByDescending { it.memory.lastAccessedMs },
        ).take(topK)
    }

    /** Scale values to [0,1]. When every value is equal (no signal), map all to 1.0 (rank-neutral). */
    private fun minMaxNormalize(xs: List<Double>): List<Double> {
        val min = xs.minOrNull() ?: return xs
        val max = xs.maxOrNull() ?: return xs
        val range = max - min
        return if (range <= 0.0) xs.map { 1.0 } else xs.map { (it - min) / range }
    }

    /** Return [memory] with its access time bumped to [nowMs] (call on each retrieved memory). */
    fun touch(memory: Memory, nowMs: Long): Memory =
        if (nowMs > memory.lastAccessedMs) memory.copy(lastAccessedMs = nowMs) else memory

    // ---- Importance --------------------------------------------------------

    private val IMPORTANCE_CUES = listOf(
        "important", "urgent", "deadline", "decided", "decide", "realized", "realised", "learned",
        "learnt", "never", "always", "promise", "goal", "remember", "must", "failed", "succeeded",
        "love", "hate", "afraid", "worried", "excited", "milestone", "breakthrough", "birthday",
        "anniversary", "moving", "quit", "hired", "fired",
    )
    private val SELF_CUES = listOf("i ", "i'm", "im ", "i've", "ive ", "my ", "me ", "mine")

    /**
     * A cheap, deterministic on-device estimate of a memory's importance (1–10) for when no cloud
     * brain is available to rate poignancy. The app may override this with an LLM rating when cloud
     * chat is on; this heuristic just keeps the stream usable offline.
     */
    fun estimateImportance(text: String): Int {
        val t = text.trim()
        if (t.isEmpty()) return MIN_IMPORTANCE
        val lower = t.lowercase()
        var score = 2.0
        score += IMPORTANCE_CUES.count { lower.contains(it) } * 1.5
        if (SELF_CUES.any { lower.startsWith(it) || lower.contains(" $it") }) score += 1.0
        val words = lower.split(Regex("\\s+")).size
        if (words >= 12) score += 1.0
        if (words >= 30) score += 1.0
        if (lower.endsWith("?")) score -= 1.0
        return score.roundToInt().coerceIn(MIN_IMPORTANCE, MAX_IMPORTANCE)
    }

    // ---- Reflection --------------------------------------------------------

    /** Sum of importance of OBSERVATIONS created at/after [sinceMs] — the reflection trigger metric. */
    fun recentImportanceSum(memories: List<Memory>, sinceMs: Long): Int =
        memories.filter { it.kind == MemoryKind.OBSERVATION && it.createdMs >= sinceMs }
            .sumOf { it.importance }

    /** Whether enough significant experience has accumulated to warrant a reflection pass. */
    fun shouldReflect(importanceSum: Int, threshold: Int = DEFAULT_REFLECTION_THRESHOLD): Boolean =
        importanceSum >= threshold

    /**
     * The most salient recent observations to feed the reflection LLM — ranked by importance then
     * recency. Reflections themselves are excluded so reflection doesn't recursively chew its own
     * output. The synthesis (asking the model for higher-level insights) happens in the app layer.
     */
    fun reflectionSeeds(
        memories: List<Memory>,
        count: Int = DEFAULT_REFLECTION_SEEDS,
    ): List<Memory> {
        if (count <= 0) return emptyList()
        return memories.filter { it.kind == MemoryKind.OBSERVATION }
            .sortedWith(
                compareByDescending<Memory> { it.importance }.thenByDescending { it.createdMs },
            )
            .take(count)
    }

    // ---- Capacity ----------------------------------------------------------

    /**
     * Cap the stream to [cap] memories, evicting the least valuable first (lowest importance, then
     * stalest by last access). Preserves the input order of survivors. Reflections, being higher
     * importance, naturally outlast mundane observations.
     */
    fun capped(memories: List<Memory>, cap: Int): List<Memory> {
        if (cap <= 0) return emptyList()
        if (memories.size <= cap) return memories
        val keep = memories
            .sortedWith(
                compareByDescending<Memory> { it.importance }.thenByDescending { it.lastAccessedMs },
            )
            .take(cap)
            .map { it.id }
            .toSet()
        return memories.filter { it.id in keep }
    }
}