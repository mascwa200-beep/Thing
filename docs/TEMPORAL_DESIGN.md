# TEMPORAL_DESIGN — time-aware reasoning (Phase 3)

The temporal subsystem sits on top of the episodic [memory stream](MEMORY_DESIGN.md): every memory
carries `createdMs` / `lastAccessedMs`, so J.A.R.V.I.S. can reason about *when* things happened —
how long ago, what falls in a window, the order of events, the span of its own timeline.

> Honest scope (per `docs/PHASE0_FINDINGS.md` and the brief's §0): this is **engineered time-awareness**
> — arithmetic over timestamps plus human-readable relative-time formatting — **not** "temporal
> consciousness". Any neuroscience framing in the brief is design inspiration only; there is no
> portable algorithm to implement and none is faked here.

## Layering — pure core vs app

| Concern | Where | CI-gated? |
|---|---|---|
| Window/ordering/elapsed maths + relative-time phrasing + a timeline digest | **`core:telemetry/TemporalReasoner.kt`** (+ `TemporalReasonerTest.kt`) | **Yes** |
| Device time + time-zone, NL window parsing ("last Tuesday") | app layer / the LLM — the model reasons over the data these primitives select & label | No |
| Stamping episodic recall with relative time; a temporal recall path / tool | app layer (Phase 3b) | Compile-gated |

This PR lands **only the pure core + tests + this doc** — the substrate — matching the "core first,
CI-green, then wiring" cadence used for `MemoryStream` / `LexicalEmbedder` / `TaskBoard`.

## Primitives (`TemporalReasoner`)

- **Selection:** `chronological`, `since(sinceMs)` ("what changed since yesterday"), `inWindow(start,
  end)` ("what did we do last Tuesday" once the LLM resolves the window), `newest` / `oldest`.
- **Duration:** `elapsedMs(memory, nowMs)` (clamped at 0 so a skewed future timestamp reads as "just
  now"); `spanMs(memories)` (oldest→newest reach of the timeline).
- **Phrasing:** `describeElapsed(ms)` → calendar-free relative time ("just now", "3 hours ago",
  "yesterday", "4 weeks ago", "2 years ago"), à la `fromNow`. Deterministic and unit-tested.
- **Replay:** `timeline(memories, nowMs, max)` — a newest-first digest with each line stamped by its
  relative time, for "what happened / replay" prompts.

## Why NL parsing stays in the model

Resolving "last Tuesday", "this morning", "before the trip" into epoch windows is exactly what the
cloud brain is good at, and it's brittle to hardcode (locale, time-zone, ambiguity). The core provides
the deterministic *filtering and labelling*; the LLM resolves the phrase to a window (or reads the
stamped timeline) and answers. This keeps the CI-gated surface small and correct.

## Next (Phase 3b)

- Stamp episodic recall (`MemoryStreamStore.digest`) with `describeWhen`, so recalled memories arrive
  as "yesterday: …" — immediate temporal awareness in every reply.
- A temporal recall path / tool (`since` / `timeline`) the agent can call for explicit "how long ago"
  / "what changed since…" questions.

Owner-verified on the Pixel for anything that renders or makes inference calls.
