package dev.mascwa.pulse.core.telemetry

/**
 * The **temporal reasoning** layer over the [MemoryStream] (Phase 3 of the persistent-agent brief).
 * Every memory is timestamped, so J.A.R.V.I.S. can reason about *when* things happened: how long ago,
 * what falls in a window, the order of events, and the span of its own timeline. Pure, CI-tested core;
 * the app threads device time + time-zone in and exposes these via recall/a temporal tool.
 *
 * Honest scope (per `docs/PHASE0_FINDINGS.md`): this is engineered time-awareness — arithmetic over
 * timestamps and human-readable relative-time formatting — not "temporal consciousness". Natural-
 * language window parsing ("last Tuesday") is left to the LLM, which reasons over the data these
 * primitives select and label.
 */
object TemporalReasoner {

    /** Memories oldest → newest by creation time. */
    fun chronological(memories: List<Memory>): List<Memory> = memories.sortedBy { it.createdMs }

    /** Memories created at/after [sinceMs] (e.g. "what changed since yesterday"), oldest → newest. */
    fun since(memories: List<Memory>, sinceMs: Long): List<Memory> =
        memories.filter { it.createdMs >= sinceMs }.sortedBy { it.createdMs }

    /** Memories created within the inclusive window [startMs, endMs], oldest → newest. */
    fun inWindow(memories: List<Memory>, startMs: Long, endMs: Long): List<Memory> {
        if (startMs > endMs) return emptyList()
        return memories.filter { it.createdMs in startMs..endMs }.sortedBy { it.createdMs }
    }

    fun newest(memories: List<Memory>): Memory? = memories.maxByOrNull { it.createdMs }

    fun oldest(memories: List<Memory>): Memory? = memories.minByOrNull { it.createdMs }

    /** The span from oldest to newest memory (0 when there are fewer than two). */
    fun spanMs(memories: List<Memory>): Long {
        if (memories.size < 2) return 0L
        val min = memories.minOf { it.createdMs }
        val max = memories.maxOf { it.createdMs }
        return max - min
    }

    /** Time since a memory was created, clamped at 0 (a future timestamp reads as "just now"). */
    fun elapsedMs(memory: Memory, nowMs: Long): Long = (nowMs - memory.createdMs).coerceAtLeast(0L)

    /**
     * A human, calendar-free relative-time phrase for a duration (à la "3 days ago"). Sign-agnostic —
     * pass an absolute elapsed value. Deterministic and unit-tested.
     */
    fun describeElapsed(elapsedMs: Long): String = ElapsedPhrase.describe(elapsedMs)

    /** Relative phrase for a specific memory at [nowMs]. */
    fun describeWhen(memory: Memory, nowMs: Long): String = describeElapsed(elapsedMs(memory, nowMs))

    /**
     * A compact chronological digest of [memories] — each line stamped with its relative time — for a
     * "what happened / replay" prompt. Newest first, capped at [max]. Empty when there are none.
     */
    fun timeline(memories: List<Memory>, nowMs: Long, max: Int = DEFAULT_TIMELINE): String {
        if (memories.isEmpty() || max <= 0) return ""
        return memories.sortedByDescending { it.createdMs }
            .take(max)
            .joinToString("\n") { "- ${describeWhen(it, nowMs)}: ${it.text}" }
    }

    const val DEFAULT_TIMELINE = 8
}
