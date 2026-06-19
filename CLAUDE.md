# CLAUDE.md — project context & session handoff

This file is auto-loaded by Claude Code at the start of every session. It carries the working context so
a fresh session continues with no loss. **The code, history and PRs live in git (already pushed); this is
the reasoning/state/decisions layer.**

## What this is
**Pulse / NIGHTWIRE** — a sideloaded, on-device-first Android app (Kotlin + Jetpack Compose, single
Activity + NavHost, manual DI via `di/AppContainer.kt` + `PulseViewModelFactory`) with a **J.A.R.V.I.S.**
assistant (Tony-Stark-style: free, private, on-device-first). Bottom nav: PULSE (home), WIRE (news),
MARKETS, WX (weather), GRID, SYS (settings). Device-gated to a **Pixel 10 Pro XL** (`DeviceGate`).
The user verifies on-device; there is **no Android SDK locally — CI is the compile gate.**

## Build / CI / release model
- CI: `.github/workflows/android-build.yml` — runs core unit tests, builds **`assembleRelease`**, publishes
  `app-release.apk` to the rolling **`latest`** GitHub release, and deletes the stale `app-debug.apk`.
  `concurrency: cancel-in-progress` (a new push supersedes the in-flight run).
- The **shipped build is `release`** but configured to keep the **debug identity**: `applicationIdSuffix=".debug"`
  (package `dev.mascwa.pulse.debug`) signed with the **committed `app/debug.keystore`**, `isMinifyEnabled=false`
  (R8 OFF — deliberate; PGO ≠ R8, R8 is a risky opt-in needing verified keep-rules). Non-debuggable so the
  **baseline profile** (`app/src/main/baseline-prof.txt` + `androidx.profileinstaller`) gives PGO-style AOT.
- `versionCode = github.run_number` (each build out-versions the last). In-app updater
  (`data/update/UpdateRepository.kt`) reads the private repo with the GitHub token, picks the **newest** `.apk`
  asset, installs via FileProvider (user confirms). "App not installed" = signature mismatch → one-time
  uninstall (the committed key makes all later updates seamless).
- Repo is **PRIVATE** (`mascwa200-beep/Thing`). Branch trailers required on commits:
  `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>` + the `Claude-Session:` line. PR bodies end with
  the 🤖 Generated-with-Claude-Code line + session link. **Never** put the model id in commits/PRs/code.

## Branch strategy (IMPORTANT)
- **`main` is the source of truth + shipping branch.** Self-code PRs target `main`, so it must stay current.
- Develop on **`claude/modest-ramanujan-0r3iz8`**; after each CI-green batch, open a PR → `main` and merge
  (keeps `main` current so a self-code merge can't regress features; also `latest` ships from `main`).
- PRs are created as **draft**; flip ready only to merge. Do NOT fast-forward the local dev branch onto the
  GitHub merge commit (its committer is `noreply@github.com` → the stop-hook flags it); keep committing on the
  dev branch tip.

## Key subsystems (where things live)
- **Inference** (`core/model-inference/`): `RoutingInferenceEngine` → cloud (`CloudInferenceEngine`,
  OpenAI-compatible, default **OpenRouter** `gpt-4o-mini`) when a key is set, else `IsolatedInferenceEngine`
  (MediaPipe in a `:inference` process) → `EchoInferenceEngine`. `ChatFormat.renderPrompt` budgets input
  tokens (fixed a long-chat native crash). Capabilities: **`ToolCallingEngine`** (native function-calling,
  cloud), **`VisionEngine`** (image/PDF analysis, cloud). `maxTokens` is the TOTAL budget; it's also sent
  as the cloud `max_tokens` (in `CloudConfig`) so credit-metered providers (OpenRouter) don't reserve the
  model's full output and **402** on a small balance. Editable in J.A.R.V.I.S. Setup ("Model max tokens")
  alongside a per-provider **"Check your balance"** link (`CloudProvider.creditsUrl`).
- **Agent loop** (`jarvis/agent/AgentOrchestrator.kt`): native tool-calling on the cloud path (reliable),
  text-ReAct fallback on-device. Threads recent conversation (short-term memory). Tools = `JarvisTool`
  registry in `AppContainer.agentTools` (+ self-edit tools when `selfEditEnabled`, + `code`/`selfcode` when
  `selfCodingEnabled`). Per-observation cap 6000. Routing rule in the system prompt: real features → `selfcode`,
  not `propose_tool`.
- **Self-coding** (`data/selfcode/`): `SelfCoder.stage()` plans files (can EDIT existing + CREATE new, multi-file
  in one PR), drafts, enqueues a `CODE_PR` `PendingAction`; **only `ApprovalGate.apply` (a user tap) calls
  `SelfCoder.commit`** → opens the PR (human-gate invariant). `code` tool reads own source; diff shown in the
  approval card (`feature/common/DiffText.kt`, `LineDiff`). `GitHubRepo.isProtected` denylist (CI/signing/
  manifest/ApprovalGate/self-coder) is never editable by the loop. Auto-merge-on-green via `RefreshWorker`
  (notifies on ship). Needs a **write-scoped (`repo`) GitHub token** in J.A.R.V.I.S. Setup.
- **Voice** (`jarvis/matrix/ActiveMatrixService.kt`): wake word → on-device Google `DeviceSpeechRecognizer`
  (Vosk fallback); optional cloud transcript cleanup (`voiceCloudInterpret`); follow-up/conversation modes;
  spoken commands run the **agent loop** (tools) when enabled; opt-in spoken proactive remarks.
- **Memory**: durable facts (`JarvisMemory.remember/recall`) + short-term conversation (recentContext threaded
  into chat AND the agent loop). Curiosity engine (rate-capped) + Approvals/Memory screens.
- **TTS** (`jarvis/voice/TextToSpeechEngine.kt`): strips Markdown before speaking.
- **Image/file interpretation**: console attach buttons → `JarvisViewModel.sendImage`/`sendFile` →
  vision (images, PDFs via `PdfRenderer`→pages) or text read; cloud-only for vision.
- **Glasses HUD** (`feature/hud/`): `HudController` + `HudPresentation` render a glanceable HUD (clock +
  **active-waypoint nav card** + brief + latest reply) on a connected external/wireless display via the
  `Presentation` API; opt-in (`glassesHud`), Activity-foreground-scoped, fully defensive. The nav card
  shows a relative turn arrow + distance + "40° right"/cardinal to the tracked `WaypointStore.active`,
  combining a polled GPS fix + the compass heading; relative-turn maths is `core:telemetry/NavGuidance`
  (CI-unit-tested), great-circle maths is `core/util/Geo`. Follow-up: background HUD (foreground service).

## On-device setup the user must do
- Add a **cloud key** (OpenRouter) in J.A.R.V.I.S. Setup → enables smartest chat, native tools, **vision**.
- Add a **`repo`-scoped GitHub token** → updater (private repo) + self-coding PRs.
- First install after a signing change: **uninstall once**, then install `app-release.apk` from the `latest`
  release; subsequent updates are seamless.

## Status (as of this handoff)
All of the above is implemented and **CI-green on `main`** (latest build published from `main`). The
smart-glasses spec's feasible core is done: **image interpretation, file (PDF/text) interpretation, glasses
HUD**. Standing user intent (now **explicit full autonomy** — no confirmations; add/streamline/evolve
freely, only surface genuinely-irreversible/owner decisions): "keep improving / keep adding features,
ship small CI-green commits, trace for bugs ('simulate breakpoints')."

Note on shipping: CI publishes the APK to `latest` on **every push to any branch** (not just `main`), so a
branch push ships immediately; merging to `main` is for keeping the source-of-truth current.

### Shipped this session (dev branch `claude/loving-edison-bd65oa`)
- **J.A.R.V.I.S. task board** (PR #42, merged) — the *deliberate-goal* layer of the on-device cognitive
  stack, beside the descriptive **profile** and procedural **cerebellum**. Pure CI-tested core in
  `core:telemetry/TaskBoard.kt` (`Task`/`TaskStatus` OPEN/ACTIVE/BLOCKED/DONE; `add` [dedupe by title,
  re-add reopens, cap evicts DONE-first then stalest], `setStatus`/`remove` [match exact then substring,
  pending preferred], `pending` ordering, `digest` [pending block for the prompt], `focus` [one-line
  nudge], `summary`, and a conservative `detect` for self-assigned tasks). On-device
  `data/tasks/TaskStore.kt` mirrors `ProfileStore` (in-memory + Mutex + debounced flush; flush-on-stop;
  clear-cancels-flush; `tasksFlow` for a future UI). The **`task` JarvisTool** (`task <title>` /
  `list` / `start`/`block`/`done`/`open`/`drop <title>`, `|`-note) keeps it current; pending tasks are
  injected into `composePersona` **every turn** (chat + agent) so J.A.R.V.I.S. stays oriented and follows
  up proactively. Always-on capture in `send()` ("I need to …", "todo: …"), mirroring profile capture.
  Persona directive added; **Settings → Storage → "Clear tracked tasks"** (`TaskStore.clear`).
- **Home "For you" task nudge** (PR #43) — `TaskBoard.focus()` surfaces the top pending task as a tappable
  line in the existing home `ForYouCard` (taps open the J.A.R.V.I.S. console); the card now shows when a
  recommendation, profile highlight **or** a pending task exists.
- **Tasks viewable + curatable in the Memory screen** (PR #44) — a `TASKS` section (parity with `PROFILE`),
  ordered pending-first via `TaskBoard.pending`+`completed`, each card status-coloured with a FORGET
  button + a CLEAR TASKS button (`TaskStore.tasksFlow` → `JarvisMemoryViewModel.tasks` / `forgetTask` /
  `clearTasks`). Completes the task board's lifecycle: capture → use → see/curate. On-device only.
- ⚠️ On-device-unverified (CI can't render/run inference): the "For you" task line, the Settings clear
  control, and the live prompt-injection/auto-capture behaviour — conservative, mirrors established patterns.
- **NEW PROJECT DIRECTION — "Mnemosyne" brief accepted (Phase 0 done, `docs/PHASE0_FINDINGS.md`).** Owner
  pasted a multi-phase brief: a persistent, time-aware, self-extending agent. Via AskUserQuestion the owner
  chose **(1) evolve Pulse** (build it into J.A.R.V.I.S.'s cognitive stack, not greenfield) and **(2) the
  GitHub-PR gate** as the self-mod execution model (reuse `SelfCoder`/`ApprovalGate`/CI; no remote VM).
  Honest scoping (in the doc): NOT consciousness/sapience — it's an *engineered* time-reasoning subsystem +
  episodic memory + bounded self-mod. **Phase-0 verified facts:** OpenRouter slug **`anthropic/claude-haiku-4.5`**
  (dot, not dash), 200K ctx, 64K max out, $1/$5, streaming + native tools ✓. **Correction to the brief:**
  OpenRouter now *does* offer an embeddings endpoint — but we still choose **on-device embeddings** (privacy/
  offline/free), cloud as documented fallback. Prior-art verdict: nothing matches the full spec; closest is
  Nous Research **Hermes Agent** (a server, not on-device) + memory engines as design refs; **Pulse is the
  base.** Mapping: P1 (OpenRouter chat) + P4 (human-gated self-code) **already exist**; the new work is an
  **embedding-scored episodic-memory stream** (recency·importance·relevance + reflection) and a **temporal
  subsystem** — pure logic in `core:telemetry` so CI gates it, mirroring TaskBoard/UserProfile/Cerebellum.
  Invariants unchanged (human-gate, `isProtected` denylist, credential scrub). DECLINED-and-standing
  "no-safety-protocols" framing is NOT what this brief asks — its self-mod is bounded + gated, so compatible.
  - **Phase 1 (PR #46, merged):** OpenRouter provider default model → `anthropic/claude-haiku-4.5`
    (`CloudInferenceEngine`); still overridable in Setup; `max_tokens` cap mitigates 402. Cost surface
    (balance link + max-tokens + 402 explainer) already exists; a per-response token meter is a Phase-5 item.
  - **Phase 2 core (PR #47):** `core:telemetry/MemoryStream.kt` (+ 18-case test, CI-gated) — the
    Generative-Agents episodic stream as PURE logic: `Memory`/`MemoryKind`; `cosine` relevance,
    `recencyDecay` (24h half-life on *last access*), `retrieve` (min-max-normalized recency+importance+
    relevance composite, top-k, weights), `estimateImportance` (offline poignancy heuristic 1–10),
    reflection (`recentImportanceSum`/`shouldReflect`/`reflectionSeeds`), `capped` eviction. Design in
    `docs/MEMORY_DESIGN.md`.
  - **Phase 2 embedder (PR #48):** `core:telemetry/LexicalEmbedder.kt` (+ tests, CI-gated) — a
    dependency-free feature-hashing embedder (unigram+bigram → L2-normalized vector; cosine ≈ lexical
    overlap). Ships on-device embeddings now with **zero new deps / APK-size cost**; honestly lexical,
    not a transformer. **Owner decision deferred:** upgrading to a sentence-transformer (ONNX/MediaPipe,
    +native dep +model blob) for semantic paraphrase — a clean drop-in (anything → vector → `cosine`).
  - **Phase 2b store + capture + recall (PR #49):** `data/memory/MemoryStreamStore.kt` (mirrors
    ProfileStore: in-memory + Mutex + debounced flush; flush-on-stop; clear-cancels-flush). Embeddings are
    DERIVED not stored — persist text+metadata, re-embed on load via the deterministic `LexicalEmbedder`
    (compact, no vectors on disk). **Always-on capture** in `send()` (`captureObservation`, gated by a
    significance floor so chatter is dropped) → records user turns as OBSERVATIONS. **Episodic recall**
    injected in `withMemory(query)` alongside the flat-note recall — top-k by recency·importance·relevance,
    touched-on-recall so recall keeps a memory fresh. Settings → Storage **"Clear episodic memory"**. ⚠️
    On-device-unverified (CI compile-gates only): capture/recall/injection behaviour + the Settings clear.
  - **Phase 3 temporal core (PR #50):** `core:telemetry/TemporalReasoner.kt` (+ tests, CI-gated) — pure
    time-aware reasoning over the stream: `chronological`/`since`/`inWindow`/`newest`/`oldest`/`spanMs`,
    `elapsedMs` (clamped), `describeElapsed` (calendar-free "3 hours ago"/"yesterday"/"2 years ago"),
    `timeline` (newest-first, relative-stamped). NL window parsing ("last Tuesday") stays in the LLM; the
    core does deterministic filtering + labelling. Doc: `docs/TEMPORAL_DESIGN.md`.
  - **Next (Phase 2c / 3b):** wire it — stamp `MemoryStreamStore.digest` recall lines with `describeWhen`
    ("yesterday: …"); a temporal recall path/tool; a WorkManager **reflection** pass
    (`shouldReflect`→`reflectionSeeds`→LLM synthesis → REFLECTION memories); a Memory-screen surface. The
    reflection pass needs a working cloud backend — **pending owner on-device verification of Haiku 4.5**.

### Owner-reported this session (updater fix + J.A.R.V.I.S. redesign — in progress)
- **Updater fix (PR #51, merged):** the in-app update check went through the shared OkHttp disk cache;
  GitHub serves authenticated API responses with `max-age=60`, so a fresh build was missed and it wrongly
  said "you're on the latest build." Fix: `UpdateRepository.check()` sends `Cache-Control: no-cache` (always
  live) and returns `UpdateCheck{latestVersionName, available}` so the UI shows the latest build number even
  when current. Pipeline confirmed healthy (run_number advances; CI publishes to `latest` on every push).
- **J.A.R.V.I.S. redesign (owner asked, multi-slice, mostly on-device-unverified):** consolidate self-coding
  to ONE switch + move J.A.R.V.I.S. settings/memory into the J.A.R.V.I.S. area + restyle the console to the
  Tony-Stark-J.A.R.V.I.S. HUD look.
  - **Slice 1 (PR #52):** the three self-coding toggles (enable / auto-merge / autonomous) → ONE
    **"AUTONOMOUS SELF-CODING"** switch in **J.A.R.V.I.S. Setup** (moved out of SYS Settings). The 3 underlying
    `JarvisSettings` flags are kept (so RefreshWorker/AppContainer/JarvisViewModel/ActiveMatrixService gating
    is untouched); `JarvisSetupViewModel.setSelfCoding(v)` flips all three together. **Human-gate invariant
    intact** — the one switch IS the opt-in; protected paths + CI gate unchanged; default OFF.
  - **Next slices:** move the memory/data controls (clears + detailed-log) into the J.A.R.V.I.S. area;
    declutter SYS; restyle `JarvisScreen` console (HUD header/status/bubbles/input). Owner verifies on Pixel.

### Shipped this session — visual overhaul (dev branch `claude/loving-edison-bd65oa`, #53–#60 all merged)
Owner drove a run of look-and-feel work via screenshots; all CI-green, squash-merged to `main`, on-device-
verified by the owner as they went. **Workflow note:** the dev branch kept diverging from `main`'s squash
commits (the "don't fast-forward onto the github merge commit" rule) — surfaced as a `JarvisScreen` merge
conflict on #57. Fix pattern now standard: after each squash merge, `git merge origin/main` back into the
dev branch (my authorship, not a FF) to stay conflict-free; resolve any overlap by taking the dev version
(it's the superset). One CI failure all session (#57: `Modifier.padding(vertical=, end=)` — no overload
mixes `vertical` with `end`; use `top/bottom/end`).
- **Pip-Boy radar** (#53 restyle, #54 sky readout, #59 tabs): `feature/tacnet/RadarScreen.kt` → a single
  monochrome **phosphor-green CRT** (`Pip` palette object) where every contact (aircraft/ISS/quakes/mil/
  emergency) is told apart by **glyph + brightness**, not hue; CRT scanlines + tube edge-glow; title
  "RADSCOPE". #54 added a **SKY · SPACE WX** panel (offline Moon phase + `PlanetCalc` naked-eye planets
  above-horizon w/ az/el + NOAA Kp/storm/aurora/wind) — `RadarViewModel` gained `spaceWeatherRepository`
  (factory) + a `SkyState` flow (moon/planets offline, space-wx best-effort). #59 added a **Fallout top tab
  bar** (`TacnetTabBar`: RADAR·SKY·ORBITAL·SPACE WX·TELEM, `Routes`-mapped, pinned above the scope,
  tab-replace nav via `popUpTo(RADAR){inclusive}`) — tabs live ON the radar; siblings not yet themed.
- **J.A.R.V.I.S. Stark-HUD console** (#55, #57, #58): #55 added `feature/jarvis/HudReactor.kt` — an animated
  arc-reactor (counter-rotating arc rings + tick ring + pulsing tri-coil core); **animation state = 3 Floats**,
  procedural Canvas, zero retained buffers (owner's ≤1 kB rule). Cyan `c.sky` (#5AD1FF) is the HUD primary
  (doesn't fight the theme accent). #57 **decluttered the idle state** (dropped the hint list → reactor +
  wordmark) and rebuilt the input as **one wide rounded HUD chat box** (`BasicTextField`) with attach-file +
  attach-image **combined into a ⊕ plus-in-circle pop-out** + inline mic/send. #58 **decluttered the top bar**
  to **Sound · Briefer · Settings**; Lockdown/Approvals/Memory/Clear-chat moved into a **CONSOLE section in
  J.A.R.V.I.S. Setup** — `JarvisSetupViewModel` gained the shared `JarvisMemory` + `ActionOrchestrator` and
  exposes `clearChat()`/`runLockdown()` (the console's `messages` is a reactive projection of `memory.history`,
  so clears reflect live; same singletons = identical behaviour). ⚠️ The wide chat box + ⊕ pop-out + top-bar
  declutter are the most interaction-changing — owner should eyeball on the Pixel.
- **Cinematic cold-open boot** (#56, then #60): replaced the friendly `NIGHTWIRE [OK]` boot with an ominous
  "you weren't meant to see this / you were chosen" sequence — a swirling **~800 analytic motes** (params-only,
  positions computed in the draw pass = no per-frame alloc) drawn into a waking eye/seal + escalating
  "clearance" arc + decrypting log. #60 (owner asked) made it **~half speed** (`BOOT_MS` 5200→8800, decrypt
  360→640) for readability, **rewrote the lines** to be consequential/villainous ("evil-lair" secure terminal),
  made it **PERMANENT** (removed the Settings "Boot sequence" toggle), and **always topmost** in MainActivity
  (draws first, masks app/gate/overlays until it fades). `bootAnimation` kept in `AppSettings` (unused) to
  avoid a settings migration.
- **Glitch → working chromatic aberration** (#60): `ui/effects/Effects.kt` — `GlitchText` is now a **constant
  RGB colour-split** (warm right / cool left, breathing offset + occasional burst) instead of an occasional
  flicker; new full-screen **`ChromaticAberrationOverlay`** (red/cyan edge fringe, gradient draw, no buffers)
  wired in MainActivity, gated by the renamed **"Chromatic aberration"** Settings toggle. Deliberately a
  gradient edge-fringe, **not** an AGSL per-pixel `RuntimeShader` — a bad shader crashes at runtime (ships
  past CI) and there's no local device to verify; the per-pixel version is a flagged, API-gated follow-up.
- **Mnemosyne (accepted brief) is paused** under the visual run — Phase 3b stash (`stash@{0}`: temporal
  recall stamp + `HistoryTool`) still un-shipped; reflection WorkManager + Memory-screen stream surface +
  Phase 4 skills still pending (reflection needs the owner's on-device Haiku 4.5 verification).
- **Open / next:** owner's "more HUD design" (≤590 kB) polish on the console (in tension with the declutter —
  read as *premium framing*, not more content); mirror the tab bar onto Sky/Orbital/Space WX/Telemetry +
  Pip-Boy-theme them; restore the Phase 3b stash.

### Shipped (prior session, dev branch `claude/nice-cori-0zkrjm`)
- **HUD active-waypoint nav card** (relative turn arrow + distance + bearing; `core:telemetry/NavGuidance`).
- **Markets reliability**: home ticker only showed ~3 instruments — root cause was Yahoo 429-throttling a
  12-wide burst (OkHttp `maxRequestsPerHost`). Fixed with a `Semaphore(5)` Yahoo cap + backoff retries +
  `mergeWithCache` (a partial fetch never SHRINKS the set). Ticker also made a true seamless fill
  (`BoxWithConstraints`) + denser items (NAME · price · ±%) + 24 instruments/6 crypto + edge fades.
- **Explainers** (`core:telemetry/Explainers` + `WeatherExplainers` + `EconomyExplainers`, CI-tested): tap
  space-weather (WX), market rows, weather metrics (feels-like/humidity/pressure/AQI), **Economy
  indicator cards** (World Bank codes → plain-English meaning + value bands), or **Fuel energy benchmarks**
  (reuse `MarketExplainers`) for plain-English meaning; shared `feature/common/ExplainerDialog`. **Market mood** breadth banner (`core:telemetry/MarketMood`).
  Home "Today in the sky" card taps through to Space Weather.
- **J.A.R.V.I.S. = tutor + translator**: persona now teaches at university level across any subject
  (first-principles, checks understanding) and translates fluently. (Best on the cloud brain.)
- **Self-modification expanded** (user wants this; chose "wide scope, keep tiny safety core"):
  `download` tool (web→private storage, untrusted-data framing, `HttpClient.download`); **opt-in
  `autonomousSelfCoding`** (Settings → Self-coding, DEFAULT OFF) — when on, `ProposeCodeChangeTool`
  auto-applies its staged change via `ApprovalGate.apply` so **protected paths (gate/CI/signing) are
  still refused, CI still compiles, user still installs**. The human-gate invariant now reads: a tap
  OR (opt-in) autonomous mode — never the loop touching its own gate/CI/signing.
- **UI: Cyberpunk-2077-HUD clarity pass** (user direction: keep identity, intuitive for everyone, no
  RAM-heavy FX): retired the flaky home auto-ticker → static swipeable **`MarketsStrip`**; defaulted
  `scanlines`+`glitch` OFF; Settings enum pickers are now **anchored dropdowns** (not centre dialogs);
  nav labels WIRE→NEWS, GRID→TOOLS.
- **Settings backup & restore** (local, offline; Settings → "Backup & restore"): export/import the whole
  `AppSettings` to a user-chosen JSON file via SAF. Fixes the real sideload pain — the one-time uninstall
  after a signing change wipes config. Logic in `data/settings/SettingsBackup.kt` (`Envelope` + `redactSecrets`
  + `merge`): export **blanks all credentials** (apiKeys + jarvis model/github/cloud tokens) so a backup file
  never carries secrets; restore **keeps the device's current credentials** and lays the rest over. No network.
- **Usage insights → tailored recommendations** (the "AI integration" the user asked for, built privacy-first):
  an **on-device, aggregated** usage store (`data/usage/UsageRepository.kt`, separate `pulse_usage` DataStore) —
  per-feature visit **counts + a 24-slot hour-of-day histogram only; no content, no locations, no PII**.
  Recorded at ONE point: a `LaunchedEffect(currentRoute)` in `PulseApp` → `onRouteVisit` → `usageRepository.record`
  (wired from `MainActivity`). Performance: in-memory cache + **debounced** flush (2s) so nav bursts = one write.
  Pure, CI-tested heuristics in `core:telemetry/UsageInsights.kt` (`recommend()`: go-to feature, what you open
  around now, one untried feature) using `data/usage/FeatureCatalog.kt` (route→label+pitch). Surfaced to the
  assistant via the **`usage` `JarvisTool`** (`UsageInsightsTool`) — so it rides J.A.R.V.I.S.'s existing cloud
  opt-in, no new data path. User control: Settings → Storage → **"Clear usage data"** (`UsageRepository.clear`).
- **Self-model + critical thinking + real-time activity log** (user asked J.A.R.V.I.S. to "log everything…",
  have internet + download its own files, "critical think", and hold a **self-model**):
  - **Persona** (`JarvisPersona.SYSTEM_PROMPT`): added a *critical-thinking* directive (first-principles,
    surface assumptions, weigh evidence, state confidence, self-correct) and an explicit *self-model*
    (purpose/character/capabilities/edges; code+memory+knowledge+**gates** are part of self, user/world/called
    systems are not; keeps it current via approved self-changes; can articulate it). The **safety gates are
    framed as load-bearing parts of self, kept by choice** — pro-safety, not a bypass. `SAFETY_ADDENDUM` intact.
  - **"Record what changed"** is real: `ApprovalGate.apply` now folds approved PERSONA_EDIT/TOOL_REGISTER into
    durable memory via a `recordSelfChange` callback (CODE_PR already did). Human-gate unchanged.
  - **Real-time activity log**: `UsageRepository` gained a capped (300) ring buffer of content-free events
    (`log(category,label)` / `recentActivity()`), persisted in the same debounced flush. Captured at: nav +
    app-lifecycle (`MainActivity`) and **every agent tool call** via a `LoggingTool` decorator wrapping the
    per-run tool list (orchestrator untouched). Read by the **`activity` `JarvisTool`** (`ActivityLogTool`).
    "Clear usage data" wipes it too. Internet + `download` tools already existed; confirmed, not rebuilt.
  - **Full-content logging** (user explicitly chose "full content incl. cloud" via AskUserQuestion, for their
    own single-user app): `JarvisSettings.verboseActivityLog` (**default ON**) makes the log capture chat
    messages (`JarvisViewModel.logChat` on in/out) + tool-call args (`LoggingTool` verbose); the `activity`
    tool can surface it to the cloud brain when cloud chat is on. **Tiny safety core kept**: `UsageRepository`
    centrally **scrubs raw credentials** (OpenAI/GitHub/Google/Slack key shapes + Bearer) from every logged
    label — a leaked key is transferable harm. Toggle: Settings → Storage → "Detailed activity log" (off =
    operational-only). This is the one place the app logs user content; everything else stays aggregated.
- **Virtual cerebellum** (user: "give J.A.R.V.I.S. a virtual cerebellum") — a subconscious procedural-skill +
  forward-model layer beneath the deliberate agent loop. Pure, CI-tested core in `core:telemetry/Cerebellum.kt`
  (`Skill`/`CerebellumState`/`Prediction`; EWMA reinforcement `learn`, reflex `recall` [practiced + reliable →
  automatic], forward-model `predict` with a **deviation/error signal**, `signature()` for request cues; capped
  + LRU-evicted). On-device `data/cerebellum/CerebellumStore.kt` (debounced flush like UsageRepository) is fed
  by the **`LoggingTool` decorator** — every tool call trains it as a *motor action* (`observeAction`: per-tool
  reliability + `after:<prev>` sequence coordination) — and by `JarvisViewModel` at request level (`observe`:
  agent-vs-direct path success per request signature). Consultative only: J.A.R.V.I.S. reads it via the
  **`reflex` `JarvisTool`** (`reflex` / `reflex after <tool>` / `reflex predict <tool>`); persona says it
  *informs but never replaces* deliberate choice (control-flow invariant intact). Cues/actions are tool names /
  transitions / normalized signatures — no raw content. Control: Settings → Storage → **"Reset learned reflexes"**.
- **Expanded contextual awareness — structured user profile** (user asked J.A.R.V.I.S. to better remember
  preferences/interests/projects for tailored help): durable, *categorized* profile memory, separate from the
  flat note store, woven into context **every turn** (proactive, not search-gated). Pure CI-tested core in
  `core:telemetry/UserProfile.kt` (`ProfileCategory`/`ProfileEntry`; `classify`, `detect` [confident
  first-person self-declaration only], `merge` [dedupe + weight reinforcement + cap/evict], `digest`
  [compact per-category block]). On-device `data/profile/ProfileStore.kt` (DataStore + debounced flush).
  Injected into the system prompt via `JarvisViewModel.composePersona` (both chat + agent paths) under "The
  user's profile…". Capture: always-on `profile.detectAndAdd(text)` in `send()` (works even on the non-agent
  path) **+** the **`profile` `JarvisTool`** (`profile <fact>` / `profile list` / `profile forget <fact>`).
  Persona directs J.A.R.V.I.S. to tailor to it + keep it current. Control: Settings → Storage → **"Clear
  remembered profile"** (`ProfileStore.clear`); **viewable + per-item curatable in the Memory screen**
  (`ProfileStore.entriesFlow` → `JarvisMemoryViewModel.profile`, FORGET / CLEAR PROFILE). On-device only.
- ⚠️ On-device-unverified (CI can't render): the markets strip, the no-glitch look, dropdowns, nav labels.
  ⚠️ User decision pending: whether to flip **Autonomous self-coding ON**.

### Known follow-ups / not done
- UI clarity pass continues: organize the **TOOLS hub** (`feature/.../GridHubScreen`) + any remaining
  cryptic screens so they're clear without taking more space (per the 2077-HUD direction).
- Background glasses HUD via a foreground service (HUD bearing/distance nav card: **done**).
- R8/minify on the shipped build (needs verified keep-rules for serialization/MediaPipe/Vosk/MapLibre).
- Optional emulator-generated baseline profile (vs the hand-authored starter).
- Text/PDF are handled; other binary file types decline honestly.

## How to continue (new session)
Open this repo (default branch `main` has everything). Read this file. Continue development on the
session's assigned dev branch (this session: `claude/loving-edison-bd65oa`), push small CI-green commits,
open a draft PR → `main`, verify green, merge.
Honor the constraints above (human-gate for self-code, protected paths, commit trailers, no model id in
artifacts, on-device verification for anything CI can't prove — esp. R8, the HUD-on-glasses, and voice).
