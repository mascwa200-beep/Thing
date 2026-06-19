package dev.mascwa.pulse.core.telemetry

import kotlin.math.sqrt

/**
 * A dependency-free, on-device **text embedder** for the [MemoryStream]'s relevance signal. It maps
 * text to a fixed-dimension, L2-normalized vector via the **feature-hashing trick** (signed hashing of
 * unigrams + bigrams), so cosine similarity ≈ weighted lexical overlap. It is deterministic, needs no
 * model blob and no native runtime, works fully offline, costs nothing, and is pure Kotlin — so CI
 * gates it like the rest of the memory maths.
 *
 * It is honestly a **lexical** embedder, not a transformer: it captures word/phrase overlap, not deep
 * semantic paraphrase. That's a serviceable recall signal for a personal memory stream (and combined
 * with recency + importance it works well), and it ships the whole feature today with zero APK-size or
 * dependency cost. A learned sentence-transformer (ONNX Runtime / MediaPipe) is a clean drop-in upgrade
 * later — anything producing a vector plugs into the same [MemoryStream.cosine] path. See
 * `docs/MEMORY_DESIGN.md`.
 */
object LexicalEmbedder {
    const val DIM = 256
    private const val BIGRAM_WEIGHT = 0.5

    /** Embed [text] into a [dim]-length unit vector. Blank/token-less text yields the zero vector. */
    fun embed(text: String, dim: Int = DIM): List<Float> {
        if (dim <= 0) return emptyList()
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return List(dim) { 0f }
        val v = DoubleArray(dim)
        for (token in tokens) accumulate(v, token, 1.0)
        for (i in 0 until tokens.size - 1) accumulate(v, tokens[i] + "" + tokens[i + 1], BIGRAM_WEIGHT)
        var sumSq = 0.0
        for (x in v) sumSq += x * x
        if (sumSq == 0.0) return List(dim) { 0f }
        val norm = sqrt(sumSq)
        return List(dim) { (v[it] / norm).toFloat() }
    }

    /** Add a hashed feature into the vector with a sign bit (signed hashing reduces collision bias). */
    private fun accumulate(v: DoubleArray, feature: String, weight: Double) {
        val h = fnv1a(feature)
        val index = Math.floorMod(h, v.size)
        val sign = if (h and Int.MIN_VALUE == 0) 1.0 else -1.0
        v[index] += weight * sign
    }

    /** Lowercase, split on non-alphanumerics, drop blanks. */
    private fun tokenize(text: String): List<String> =
        text.lowercase().split(NON_ALNUM).filter { it.isNotEmpty() }

    /** FNV-1a 32-bit — an explicit, platform-stable hash (so vectors are reproducible across devices). */
    private fun fnv1a(s: String): Int {
        var h = -0x7ee3623b // 0x811c9dc5
        for (c in s) {
            h = h xor c.code
            h *= 0x01000193
        }
        return h
    }

    private val NON_ALNUM = Regex("[^a-z0-9]+")
}
