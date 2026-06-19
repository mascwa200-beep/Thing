# PHASE 0 — Verify reality & prior art

Status: **complete** — this satisfies the brief's Phase-0 gate (verified model id, streaming/
tool-calling answer, embeddings decision, prior-art verdict). Verified 2026-06-19.

Decisions taken with the owner before this doc (via AskUserQuestion):
- **Approach:** *evolve the existing Pulse / NIGHTWIRE app* — the "Mnemosyne" capabilities are built
  into J.A.R.V.I.S.'s on-device cognitive stack, not a greenfield rewrite. Pulse already implements
  the bulk of Phases 1–2 and all of Phase 4's human-gate.
- **Self-mod execution model:** *GitHub-PR gate* — reuse Pulse's proven pattern (the agent proposes a
  change → CI runs the tests → a human approves & merges), rather than provisioning a remote VM/E2B
  sandbox. Same human-approval + git-rollback guarantees the brief asks for, no infra to stand up.

> Honesty note (carried from the brief's §0): this is a persistent, time-aware, self-extending
> **agent** — not "sapient", "conscious", or "AGI", and nothing here is named or built as if it were.
> "Temporal consciousness" is realized as an **engineered time-reasoning subsystem** (timestamps +
> recency/duration/ordering over a memory stream); the neuroscience framing is inspiration only.
> "Self-modification" is **bounded**: the agent edits its own tools/skills/prompt-programs, gated
> behind tests + human approval, and never touches the model weights, the approval/CI/signing
> machinery, or secret handling.

---

## 1a. Backend verification — Claude Haiku 4.5 via OpenRouter

Sources: the Anthropic Claude API reference (model table, cached 2026-06-04) and the live OpenRouter
model page. Cross-checked against Pulse's existing `CloudInferenceEngine`, which already calls this
model in production.

| Fact | Value | Source |
|---|---|---|
| Anthropic model id | `claude-haiku-4-5` (dated: `claude-haiku-4-5-20251001`) | Claude API reference |
| **OpenRouter slug** | **`anthropic/claude-haiku-4.5`** (note the dot, not a dash) | openrouter.ai model page |
| Context window | **200K** tokens | both |
| Max output tokens | **64K** (streaming required for large outputs) | Claude API reference |
| Pricing | **$1.00 / 1M input · $5.00 / 1M output** | both |
| Streaming (SSE) | **Yes** — OpenAI-compatible `/v1/chat/completions`, `stream: true` | OpenRouter |
| Tool / function calling | **Yes** — native function-calling passthrough | OpenRouter |
| Prompt caching | **Yes** (Anthropic; min cacheable prefix 4096 tokens for Haiku 4.5). Through OpenRouter, caching is provider-pass-through and must be exercised to confirm hit-rates. | Claude API reference |

Chat endpoint: `https://openrouter.ai/api/v1/chat/completions` (OpenAI-compatible). Pulse's
`CloudInferenceEngine` already targets this with streaming + native tools and already defaults
OpenRouter to `gpt-4o-mini`; switching the default/representative model to `anthropic/claude-haiku-4.5`
is a config change, not new plumbing.

**Carry-over from Pulse's history (the 402 saga, already fixed):** OpenRouter is credit-metered and
pre-authorizes the model's *full* output budget. Always send `max_tokens` (Pulse threads
`JarvisSettings.maxTokens`, default 2048, floored 256) so a credit-metered key doesn't 402 on a modest
balance. A 402 is billing, not auth.

> ⚠️ Thinking note: Haiku 4.5 predates the adaptive-thinking (4.6+) family. The `effort` parameter and
> `thinking: {type:"adaptive"}` are **not** for this model (`effort:"max"` errors on Haiku 4.5). Not
> relevant for the OpenRouter chat path we use, but don't copy adaptive-thinking params onto it.

---

## 1a (cont.). Embeddings decision — **CORRECTION to the brief**

The brief states "OpenRouter is chat-completions only — it does not provide embeddings." **This is now
out of date.** OpenRouter has shipped an **embeddings endpoint**
(`https://openrouter.ai/docs/api/reference/embeddings`, OpenAI-compatible, non-streaming). So cloud
embeddings are now *available* — the decision is a genuine choice, not forced.

**Decision: on-device embeddings (default), with cloud embeddings as an explicit fallback only.**

Rationale, aligned to Pulse's standing constraints (privacy-first, offline, free, on-device-first):
- **Privacy** — memory text is the most sensitive data in the app; embedding it on-device means the
  raw memory stream never leaves the phone for the *indexing* path. (Chat itself is already an opt-in
  cloud path the user controls.)
- **Cost** — zero per-embedding cost; the memory store can be embedded freely and re-embedded on
  schema changes without metering.
- **Offline** — retrieval works with no network, matching the rest of the on-device stack.
- **Latency** — no round-trip per memory write.

Implementation note for Phase 2: a small sentence-transformer (e.g. all-MiniLM-L6-v2 / gte-small)
via ONNX Runtime Mobile or MediaPipe Text Embedder, shipped in the APK or fetched once. This adds
APK size / a model download — an **on-device-unverified** tradeoff the owner confirms on the Pixel.
If on-device proves infeasible (size, perf), the documented fallback is OpenRouter embeddings behind
the same cloud opt-in the chat path uses. For a *small* memory store, a plain cosine-similarity scan
over stored vectors is sufficient and avoids a native vector-index dependency entirely (start here;
add ObjectBox vector index / `sqlite-vec` only if the store grows enough to need it).

---

## 1b. Prior-art verdict — "find it before you build it"

**No single open-source project matches the full description** (Android / on-device-first client +
OpenRouter backend + persistent episodic memory + temporal/event reasoning + sandboxed
self-modifying task code under human approval). The closest *full* match is **Pulse itself** — which
is why we are evolving it rather than forking a stranger's repo. Closest partial matches per layer,
to borrow design from (not adopt wholesale):

| Layer | Closest prior art | Has | Lacks (vs this brief) | Use |
|---|---|---|---|---|
| Whole-system, self-improving agent | **Hermes Agent** (Nous Research, `nousresearch/hermes-agent`) | OpenRouter; learning loop that creates/improves skills from experience; cross-session recall (FTS5 + LLM summarization); agent-curated memory | Self-hosted **server**, not on-device Android; no Android client; no on-device privacy model | Design reference for the **skill library + skill self-improvement** loop (Voyager-style). |
| Episodic memory framework | Mem0, **Letta/MemGPT**, Zep/Graphiti; `rohitg00/agentmemory`; `CaviraOSS/OpenMemory` | Tiered/self-editing memory; recency+importance+relevance scoring; bi-temporal graphs (Zep) | Server/Python; not Kotlin/on-device | **Design reference** for the memory-stream scoring + reflection (Generative Agents, Park et al. 2023, is the canonical model). |
| Android + OpenRouter client | `oxproxion` (F-Droid), `awesome-openrouter` apps | OpenRouter chat on Android, system-prompt config, credit view | No memory, no temporal reasoning, no self-mod | Confirms the Android+OpenRouter client shape is well-trodden — Pulse already has a more capable one. |
| On-device vector search | ObjectBox vector index, `sqlite-vec` | On-device ANN / vector SQL | — | Adopt **only if** the cosine-scan baseline outgrows itself. |

**Conclusion:** build (by evolving Pulse), reusing forkable *patterns* — the Generative-Agents memory
stream (recency·importance·relevance + scheduled reflection), Voyager/Hermes skill-library self-
improvement, and Zep-style bi-temporal modelling *only if it earns its complexity*. No license-bearing
code is being copied; these are design references.

---

## 2. What this means for the build (mapping the brief onto Pulse)

The brief's five phases map onto Pulse's existing cognitive stack. Much of Phases 1, 2, and 4 already
exists; the genuinely-new work is an embedding-scored **episodic-memory stream**, a **temporal-
reasoning** subsystem, and a **self-extending skill library** (gated by the GitHub-PR model).

| Brief phase | Status in Pulse today | New work |
|---|---|---|
| **P1 — Android shell + secure OpenRouter chat** | **Largely done.** Compose single-Activity app; `CloudInferenceEngine` (OpenRouter, streaming, native tool-calling); keys in settings store; foreground service for voice; in-app cost lever (`maxTokens`). | Switch the representative cloud model to `anthropic/claude-haiku-4.5`; confirm key storage hardening; surface a token/cost meter if not already glanceable. |
| **P2 — Persistent episodic memory** | **Partial.** Durable note store (`JarvisMemory`), structured profile, activity log, cerebellum, task board — all on-device with debounced flush. But no **embedding-scored memory stream** with recency·importance·relevance retrieval + reflection. | The core new subsystem: timestamped `events` store + on-device embeddings + composite-score retrieval + scheduled reflection. Pure scoring/decay/reflection logic goes in `core:telemetry` so **CI gates it**. |
| **P3 — Temporal/event reasoning** | Timestamps exist on notes/activity; no first-class recency/duration/ordering queries or decay. | Pure, CI-tested temporal core (recency decay, elapsed-duration, ordering, "what changed since…") over the P2 store; reflection pass via WorkManager. |
| **P4 — Self-extending skills + sandboxed self-mod** | **Done, in the chosen model.** `SelfCoder` stages multi-file changes; **only `ApprovalGate.apply` (a human tap, or opt-in autonomous mode) commits → opens a PR**; `GitHubRepo.isProtected` denylist (CI/signing/manifest/gate/self-coder) is never editable; CI compiles; auto-merge-on-green. This **is** the brief's "propose → test → human-approve → commit, with git rollback", with **CI-as-the-sandbox** per the owner's decision. | A first-class **skill registry** (reusable, retrievable, described, tested) layered on the existing self-code PR flow — the agent authors a skill, it lands as a tested PR, and is reusable thereafter. |
| **P5 — Orchestration, evals, hardening** | `AgentOrchestrator` (native tool-calling cloud loop + text-ReAct fallback); CI runs `core:telemetry` + `core:model-inference` unit tests. | Add memory-recall / temporal-query / retrieval-scoring evals as `core:telemetry` JUnit tests (the CI-gated module); cost/latency + offline handling already partly present. |

**Hard invariants preserved (unchanged):** the self-coding human-gate, the `isProtected` denylist, and
the central credential scrub. The brief's "self-mod must never read secrets, modify its own gates, or
auto-deploy without approval" is *already* how Pulse is built — we are extending capability inside that
boundary, not loosening it.

**Declined-and-standing:** an earlier request to integrate a model "without strict safety, ethical, or
legal protocols" was refused and remains refused. Nothing in this brief asks for that — its self-mod is
explicitly bounded and gated, which is compatible.

---

## Next (Phase 1 → Phase 2)

With the gate satisfied, the first build slices are:
1. Make `anthropic/claude-haiku-4.5` the representative OpenRouter model + confirm the cost meter
   (small, CI-green).
2. Begin Phase 2's pure core in `core:telemetry`: the memory-stream **scoring/decay/reflection** logic
   with JUnit tests (CI-gated), before any on-device wiring — same pattern as TaskBoard/UserProfile/
   Cerebellum. `MEMORY_DESIGN.md` lands with that slice; `TEMPORAL_DESIGN.md` and `SELFMOD_SAFETY.md`
   land with Phases 3 and 4 respectively.
