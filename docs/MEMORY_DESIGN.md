# MEMORY_DESIGN — episodic memory stream (Phase 2)

The deliberate, time-aware long-term memory layer of J.A.R.V.I.S.'s on-device cognitive stack, beside
the structured profile (who the user is), the cerebellum (which actions reliably work) and the task
board (what the user is working toward). Modelled on the **Generative Agents memory stream** (Park et
al. 2023): timestamped memories, retrieval scored by **recency + importance + relevance**, and a
periodic **reflection** pass that synthesises higher-level memories.

> Honest scope (per `docs/PHASE0_FINDINGS.md` and the brief's §0): this is *engineered time-aware
> retrieval*, not "temporal consciousness". Recency is exponential decay over timestamps; relevance is
> cosine similarity of embeddings; importance is a 1–10 rating. Nothing here is sapient.

## Layering — pure core vs app

| Concern | Where | CI-gated? |
|---|---|---|
| Scoring maths: cosine relevance, recency decay, min-max-normalized composite retrieval, importance heuristic, reflection trigger/seed selection, capacity eviction | **`core:telemetry/MemoryStream.kt`** (pure Kotlin) + `MemoryStreamTest.kt` | **Yes** — this is the part that's easy to get subtly wrong, so CI owns it. |
| On-device embedding generation (text → vector) | app layer, Phase 2b — small sentence-transformer (all-MiniLM / gte-small) via ONNX Runtime Mobile or MediaPipe Text Embedder | No (native; owner-verified) |
| Persistence (timestamped `events` store + vectors) | app layer — a `MemoryStreamStore` mirroring the established store pattern (in-memory cache + Mutex + debounced flush to its own DataStore; `flushNow()` on stop; `clear()` cancels flush first) | No (compile-gated) |
| Reflection synthesis (LLM call over the seeds) | app layer — uses the existing cloud opt-in; `MemoryStream.reflectionSeeds` picks what to feed it | No |
| Retrieval injection into the prompt + a `memory`/recall tool | app layer — threads top-k into `composePersona`/recall, like the profile/task digests | No |

This PR lands **only the pure core + tests + this doc** — the foundation, before any on-device wiring,
matching how TaskBoard / UserProfile / Cerebellum were built (core first, CI-green, then store + tool).

## Data model (`Memory`)

`id, text, kind (OBSERVATION | REFLECTION), importance (1–10), createdMs, lastAccessedMs, embedding`.

- **`lastAccessedMs`** is bumped on each retrieval (`MemoryStream.touch`), so recency tracks *use*, not
  just creation — a memory recalled often stays "fresh" (Generative Agents behaviour).
- **`kind`** separates raw observations from synthesised reflections; reflection excludes its own
  output so it doesn't recursively chew on itself.

## Retrieval scoring

`retrieve(memories, queryEmbedding, nowMs, topK, weights, halfLifeMs)`:

1. For each memory compute raw **recency** = `0.5^(elapsed / halfLife)` (default half-life 24h of
   disuse), raw **importance** (1–10), raw **relevance** = cosine(query, memory) (0 when the query has
   no embedding — then it ranks by recency + importance alone).
2. **Min-max normalize** each component across the candidate set to [0,1] (the paper's approach;
   when a component has no variation it's rank-neutral).
3. Composite = `wᵣ·recency + wᵢ·importance + wₛ·relevance` (default weights all 1.0), sorted
   descending, ties broken by most-recent access. Return the top-k with their component breakdown
   (`ScoredMemory`) for transparency/debugging.

## Importance

`estimateImportance(text)` is a cheap, deterministic on-device heuristic (1–10) over affect/self/
length/question cues — the offline fallback for when no cloud brain is available to rate "poignancy".
When cloud chat is on, the app may override it with an LLM rating (the paper's method). The heuristic
keeps the stream usable and CI-testable with zero network.

## Reflection

- **Trigger:** `recentImportanceSum(memories, sinceMs)` sums the importance of recent *observations*;
  `shouldReflect(sum, threshold)` fires once it crosses the threshold (default 50) — i.e. reflection
  is driven by accumulated *significance*, not a fixed clock (Generative Agents). Scheduled via
  WorkManager in the app, checked against this metric.
- **Seeds:** `reflectionSeeds(memories, count)` returns the most salient recent observations
  (importance then recency) to feed the synthesis LLM. The app makes the call and stores the result
  as a `REFLECTION` memory with its own timestamp, so reflections are themselves retrievable.

## Capacity

`capped(memories, cap)` evicts the least valuable first (lowest importance, then stalest by access),
preserving survivor order. Reflections, being higher importance, naturally outlast mundane
observations.

## Temporal reasoning (Phase 3 preview)

The same timestamped store feeds the Phase-3 temporal subsystem (elapsed-duration, ordering,
"what changed since…"). `TEMPORAL_DESIGN.md` lands with that phase. The decay + ordering primitives
here are the substrate.

## On-device-unverified caveats

CI gates the maths but can't run on-device embeddings, drive the store, or make LLM calls. The
embedding model's size/latency on the Pixel, retrieval quality on real memories, and the reflection
cadence are all owner-verified. The pure core is conservative and fully unit-tested.
