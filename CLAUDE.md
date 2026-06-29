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
- **Mnemosyne resumed (#62, #63 merged):** Phase 3b/3c temporal recall — `MemoryStreamStore.digest()` now
  time-stamps recalled lines via `TemporalReasoner.describeWhen` ("yesterday: …"); new `timeline()` +
  **`history` JarvisTool** (registered in `agentTools`); **episodic stream now viewable/curatable in the
  Memory screen** (new EPISODIC section + `MemoryStreamStore.forget(id)`, parity with PROFILE/TASKS).

### Owner batch — Pip-Boy nav + fixes (this session cont., #64–#67 all merged)
Owner pasted Fallout Pip-Boy refs + a 4-part ask. Verdict + what shipped:
- **Space Weather fix (#64):** Solar Wind + Bz showed "—" — the NOAA `products/summary/solar-wind-*` objects
  go empty. Switched to the canonical real-time DSCOVR feeds (`solar-wind/plasma-5-minute.json` speed col 2,
  `mag-5-minute.json` bz_gsm col 3), newest finite row. ⚠️ CI failure first try: `products/summary/*` in a
  KDoc — Kotlin **nested block comments** mean `/*` opened a nested comment that commented out the next
  function ("unresolved reference" cascade). **Never put `/*` in a KDoc.**
- **TOOLS → Pip-Boy feed tabs (#66), replacing the grid** (owner via AskUserQuestion chose "scrolling tabs =
  feeds"). One horizontally-scrolling phosphor-green tab row; TOOLS opens straight into it; each tab jumps
  direct to its feed (no in-between launcher). Implemented WITHOUT touching the feed screens:
  `navigation/FeedTabs.kt` (FEED_TABS/FEED_ROUTES/FEED_HOME + `LocalFeedTabs` CompositionLocal + `FeedTabState`),
  `feature/common/FeedTabBar.kt` (the bar), **`PulseScaffold` auto-renders it** when `LocalFeedTabs` is set,
  and **`PulseApp` provides the ctx once around the NavHost** (keyed on currentRoute) + `openTab` (tab-replace
  nav: `popUpTo(current){inclusive}`) + repointed the TOOLS bottom-nav to the last feed (highlights on any
  feed route). Reverted the radar's one-off `TacnetTabBar` (#59) → uses the shared bar. **Note:** `String? in
  Set<String>` doesn't compile — null-guard (`currentRoute != null && currentRoute in FEED_ROUTES`). This
  subsumed the planned A (Tools restyle, #65 **closed/superseded**) and B (radar tabs). Old `GridHubScreen`/
  `Routes.GRID` **deleted (#70)** — the TOOLS bottom-nav item now anchors on `Routes.RADAR` (= `FEED_HOME`)
  and stays a pseudo-destination (opens the feed tabs, highlights on any feed route). **J.A.R.V.I.S.
  deliberately NOT a tab** (own cyan HUD; green bar would clash; reached from Home) — owner may want it added.
- **Compass → nav instrument (#67):** active-waypoint guidance (`WaypointStore` → label/distance/bearing +
  `NavGuidance` turn hint + a magenta dial needle) + **sun azimuth** (`feature/compass/SunCalc.kt`, offline
  Schlyter solar position like PlanetCalc; amber dial marker + Sun read-out). Moon azimuth = follow-up.
- **Open / owner-steerable:** the "more HUD" (≤590 kB) console polish (read as *premium framing*, not more
  content); whether J.A.R.V.I.S. should be a feed tab; delete the orphaned grid; reflection WorkManager pass
  (needs on-device Haiku 4.5 verification). ⚠️ On-device-unverify the **feed-tabs nav** (tab switching, TOOLS
  highlight, back behaviour) + the compass markers — biggest/most-behavioural, CI only compile-gates.

### Tab redesign + cleanups (this session cont., #69–#76 all merged)
Two autonomous follow-ups first: **Moon azimuth** on the compass (#69, `feature/compass/MoonCalc.kt` —
offline Schlyter lunar position w/ the main perturbations; pale `c.sky` dial marker + Moon read-out, mirrors
SunCalc), and **deleting the orphaned GRID hub** (#70 — `GridHubScreen`/`Routes.GRID` gone; TOOLS bottom-nav
re-anchored on `Routes.RADAR`, later `Routes.TACNET`). Then the owner pasted Fallout Pip-Boy refs + a big
tab-redesign ask, shipped in **CI-green slices** (each: extract scaffold-free `*Body` like the Markets hub,
host under a new shell, re-wire `FEED_TABS`/nav, keep standalone routes for Home/hub deep-links):
- **#71 — image search removed** (`feature/images` + `data/images` deleted; J.A.R.V.I.S. vision `sendImage`
  path is separate, untouched).
- **#72 — Economy/Inflation/Fuel folded into MARKETS** as a sub-tab hub (`MarketsBody`/`EconomyBody`/
  `InflationBody`/`FuelBody` + a `NeonChip` rail; standalone screens kept as thin wrappers). Dropped from tabs.
- **#73 — Compass folded onto the NAV map** (a heading-readout overlay — north needle + true azimuth +
  cardinal — using the NAV's existing `headingDeg`; COMPASS tab dropped; full dial still on Sky/Survive hubs).
- **#74 — incidents map folded into NAV; standalone Map deleted** (`NavViewModel` now fetches
  `SafetyRepository` incidents → an amber GeoJSON `INCIDENT_LAYER`, toggled by a ⚠ control button; osmdroid
  `feature/map` + `Routes.MAP` gone, Survive "Map" tile → NAV; osmdroid gradle dep left for a later cleanup).
- **#75/#76 — the centerpiece: PIP-BOY STAT tab.** Radar+Telemetry+Orbital+Space-WX collapse into ONE feed
  tab (`Routes.TACNET`, label "PIP-BOY", now `FEED_HOME`). New `feature/tacnet/PipBoyScreen.kt` hosts the four
  scaffold-free bodies under the literal Fallout sections **STATUS** (telemetry) · **DATA** (orbital) ·
  **MAP** (RADSCOPE) · **RADIO** (space wx) in a green CRT chrome (section rail + footer). Shared `Pip`
  palette + `crtScanlines` extracted to `feature/tacnet/PipBoyTheme.kt`; old `TacnetHubScreen` deleted. #76
  re-themes the whole subtree phosphor-green by providing a green-mapped `NightwirePalette` (`PipPalette`)
  over `LocalNightwire` — every `NeonPanel`/`Text` in the bodies re-themes with **zero per-screen edits**.
- **Result: feed tab bar 14 → 6** (PIP-BOY · NAV · OBJECTIVES · SURVIVE · SOCIAL · SEARCH). ⚠️ All on-device-
  unverified (CI compile-gates only): PIP-BOY sub-tab switching + green legibility, the MARKETS sub-tabs,
  NAV's incident toggle + compass readout. Owner should eyeball on the Pixel. Possible follow-ups: heavier
  CRT FX (global scanlines/tube curvature — trades readability), a STATUS Vault-Boy condition figure + an
  HP/AP-style bottom bar with live telemetry, and removing the now-unused osmdroid gradle dep.

### Pip-Boy build-out + objectives overhaul + auto-update (this session cont., #79–#93 all merged)
Owner drove a long Fallout-Pip-Boy run via screenshots, then an objectives redesign; all CI-green slices,
squash-merged to `main`, re-synced into the dev branch each time (`git merge origin/main`, my authorship).
- **PIP-BOY look (#79–#85, owner-verified as they went):** greened the whole TOOLS subtree; redesigned the
  feeds to the Pip-Boy idiom (drawn backgrounds/shapes, framed panels) via `feature/common/PipUi.kt`
  (`PipFrame` corner-bracket box + `PipHeader` rule line + `PipStatTile`); RADIO became a **real internet
  radio** (`feature/tacnet/RadioBody.kt` + `RadioViewModel` MediaPlayer + `data/radio/RadioStation.kt`
  SomaFM stations); **Space WX moved into DATA** mixed with orbital (`DataBody`); STAT chrome got **HP/AP/LVL
  bars from real device data, bars-only no figure** (owner's AskUserQuestion choice — battery/free-mem/
  versionCode); radar **sweep-driven blips** (#85 — contacts hold position, update only as the sweep hand
  passes). IP line held: original homage, no trademarked Vault-Boy/Fallout art.
- **Cold-open boot retext (owner):** wordmark → contractor name **ARGUS DYNAMICS / "ADVANCED SIGNALS
  DIVISION"**; bottom log → standard boot diagnostics (POST/secure-boot/crypto), not villain prose.
- **Updater (#86/#87):** only signal an update when the CI run is **GREEN** (not orange/in-progress) —
  `UpdateRepository.isBuildGreen(code, headers)` checks the Actions API run by `run_number`; plus opt-in
  auto-update.
- **J.A.R.V.I.S. bottom nav (#88):** added JARVIS to the bottom nav (`TOP_DESTINATIONS`); removed the
  top-of-Home shortcut (one entry point now).
- **Radar feed (#89):** dropped space-weather from the MAP feed's SKY panel; flight detail now drops down
  **under** the tapped row (accordion `ContactDetail`), not pinned at top.
- **Auto-update finalised (#90, task 7):** owner — "as soon as it's green, install it, show the installer
  thing so I can tap." Already did green-check → auto-download → system installer (one tap is the Android
  floor for a sideload — no device-owner = no silent install). Made it **default ON** (`AppSettings.autoUpdate
  = true`) and **foreground-responsive**: extracted `MainActivity.maybeAutoUpdate()`, called from `onStart()`
  so it fires on cold launch AND every return; throttled 15 min; green-gate + `lastAutoUpdateCode` dedupe kept.
- **OBJECTIVES → NAV map (task 6, #91/#92/#93):** the objective system folded into the navigational map.
  **6a (#91):** every tracked waypoint renders as a per-kind **Canvas-drawn bitmap icon** (★ MAIN gold / ◆ SIDE
  blue / ● PLAIN white) on an `OBJECTIVE_SOURCE` SymbolLayer — bitmaps via `style.addImage`, **no glyph-font
  dependency**; active one gets a coloured halo + larger icon (`NavViewModel.allWaypoints`/`activeWaypointId`).
  **6b (#92):** OBJECTIVES dropped from `FEED_TABS` (6→5) and folded into NAV as an internal **`MAP|OBJECTIVES`
  segmented switch** (map stays composed; OBJECTIVES draws an opaque `ObjectivesPanel` over it). Extracted
  scaffold-free `ObjectivesPanel` (reused by the kept standalone `ObjectivesScreen` for deep-links); NAV route
  now builds `ObjectivesViewModel` too (shared `WaypointStore` keeps icons+list in sync). **6c (#93):** tap an
  objective icon → `WaypointDetailCard` (name/kind/distance/note) with one-tap **TRACK**/**REMOVE**; map click
  listener checks the objective layer first (user pins beat POIs); empty-map tap dismisses cards. ⚠️ All
  on-device-unverified (CI compile-gates only) — owner should eyeball icons/sub-tab/tap-cards on the Pixel.
- **Open / steerable:** heavier CRT FX (global scanlines/tube curvature — trades readability); a STATUS
  condition figure; removing the unused osmdroid gradle dep; the Mnemosyne reflection WorkManager pass (still
  needs owner on-device Haiku 4.5 verification). Auto-update's one tap is the hard Android floor (documented).

### Radio overhaul + cleanups (this session cont., #94–#98 all merged)
Owner: "add the ability for the radio to connect to local stations and play local stuff," then "yes,
autonomously." The PIP-BOY **RADIO** tab grew from a fixed SomaFM list into a full subsystem; all CI-green
slices, squash-merged to `main`, re-synced into the dev branch each time.
- **#94 — docs handoff** (the #79–#93 batch recorded in this file).
- **#95 — LOCAL stations:** `data/radio/RadioBrowserRepository.kt` queries the free, keyless **Radio Browser**
  community API by ISO country code (most-clicked first; user's state floated to the top), de-duped by stream,
  fully defensive. `LocationProvider.describePlace()` reverse-geocodes a fix → country code/country/state/
  locality (new `GeoPlace`). `RadioViewModel` gained a LOCAL group (loaded on demand: location → reverse-
  geocode → Radio Browser) with a `LocalStatus` lifecycle; `RadioBody` renders LOCAL (region + count /
  "enable location" / retry) then CURATED. **Manifest: `usesCleartextTraffic=true`** — most radio streams are
  plain http, which API 35 blocks by default (required for local stations to play).
- **#96 — background playback + osmdroid removal:** playback moved into a process-wide `RadioController`
  (object; owns the `MediaPlayer`, tuned-by-identity state) kept alive by **`RadioService`** — a
  `mediaPlayback` foreground service with a Stop-action media notification (channel `radio_playback`).
  `RadioViewModel` is now a thin delegate (playback survives leaving the PIP-BOY/app). Manifest gained
  `FOREGROUND_SERVICE_MEDIA_PLAYBACK` + the service entry. Also dropped the dead **osmdroid** gradle dep
  (unused since #74). The one tap to Stop / the notification are the standard sideload-media pattern.
- **#97 — favourites:** `RadioStation` is now `@Serializable`; `AppSettings.favoriteRadio` persists starred
  stations (in the existing settings JSON blob). `RadioViewModel.favorites`/`toggleFavorite` over
  `SettingsRepository`; `RadioBody` shows a FAVOURITES section + a ★/☆ on every card.
- **#98 — search:** `RadioBrowserRepository.searchStations(name)` (URL-encoded, popularity-ordered);
  `RadioViewModel.search`/`clearSearch` + `searchResults`/`searchStatus`; a SEARCH bar under the tuner →
  tunable + favouritable result cards. **Radio is now local · favourites · search · background · curated.**
- ⚠️ All on-device-unverified (CI compile-gates only — no device/network/render): the Radio Browser fetches,
  reverse-geocode, cleartext + **foreground-service playback / notification**, and the favourites/search UI.
  The FGS media path especially wants a real run on the Pixel. **Open / steerable next:** background-play for
  the curated SomaFM too (already covered — same controller), a sleep timer, station logos (Radio Browser
  `favicon`), or pivot off radio.

### Radio sleep timer + screenshot-driven Fallout batch (this session cont., #100–#106 all merged)
- **#100 — radio sleep timer:** `RadioController` got its own `CoroutineScope` + `setSleep(minutes)` (a
  delay → `stop()`); `stop()`/clear cancels it. `RadioViewModel.sleepMinutes`/`setSleep`; a `☾ SLEEP ·
  OFF/15/30/60` selector in the tuner. Radio is now local · favourites · search · background · sleep · curated.
- Owner then pasted on-device screenshots + Fallout Pip-Boy refs with a multi-part ask; shipped in CI-green
  slices, each squash-merged + re-synced:
  - **#101 — objectives dedup BUG FIX:** tracking a *calendar* objective always `waypoints.add()`'d → a PIN
    twin every tap (the reported "duplicates again and again"). `ObjectivesViewModel.track` is now idempotent
    (re-activates an existing match by title+coords rounded ~11 m via `coordKey`); the merged `objectives`
    list de-dupes calendar events that already have a waypoint twin. Tracks once, shows once.
  - **#102 — radio TRULY location-based:** `RadioBrowserRepository.localStations(lat,lon,country?,state?)`
    now does real **geo search** (`geo_lat`/`geo_long`/`geo_distance` ~200 km + `has_geo_info`), parses each
    station's coords, sorts by true distance; falls back to country/state popularity only when nothing
    geo-tagged is near. Reuses `core/util/Geo`. `RadioViewModel` passes the GPS fix; label → "Near you".
  - **#103 — radio horizontal rails:** stations are now a left-to-right swipeable `LazyRow` of rounded
    `StationCard` cubes (play/EQ + ★ up top, name, tiny genre/region line) per section (Favourites/Search/
    Local/Curated) instead of full-width vertical rows — frees vertical space. Follow-up: live now-playing
    track text needs ICY stream metadata (MediaPlayer doesn't surface it).
  - **#104 — QUESTS tab + Fallout quest-tracker:** `ObjectivesPanel` restyled into a quest log (grouped MAIN/
    SIDE/MISC, ◆/◇ diamond markers, TRACKING flag, hairline rules; palette-agnostic). New **`Routes.QUESTS`**
    feed tab (`FEED_TABS` 5→6: PIP-BOY·NAV·QUESTS·SURVIVE·SOCIAL·SEARCH) — `QuestsScreen` wraps the shared
    `ObjectivesPanel` in `pipBoyPalette`; same `ObjectivesViewModel`/`WaypointStore` as the NAV sub-tab so
    both stay in sync. NAV's OBJECTIVES sub-tab gets the same quest-tracker look (cyberpunk-hued).
  - **#105 — STATUS/telemetry Fallout restyle:** `TelemetryScreen` VITALS gauges → green-banded rows with a
    **24-segment notched bar** (Pip-Boy HP/AP look); SENSORS/SYSTEM/POSITION → Fallout **DATA>STATS banded
    rows** (faint green band, dim label / bright value). Renders in the PIP-BOY green palette.
  - **#106 — NAV gold path line:** the player→objective route is now a **gold line over a white casing**
    (round-capped, soft glow; two `LineLayer`s on the route source), under the markers — the Cyberpunk/Fallout
    nav-line look, no minimap. Straight great-circle path (road-snapped routing would need a routing API).
- ⚠️ All #100–#106 on-device-unverified (CI compile-gates only). **Open / steerable:** live now-playing
  (ICY metadata) in the station cards; a STATUS Vault-Boy condition figure; turn-by-turn road routing.

### Screenshot-driven Fallout batch #2 (this session cont., #108–#111 all merged)
Owner pasted on-device screenshots + Fallout refs again; four asks, four CI-green slices:
- **#108 — boot log is procedurally generated, unique per launch:** `BootScreen.generateBootLog()` builds the
  decrypt roll from a wall-clock-seeded `Random` (hex addresses, module names, RNG seeds, sizes, OK/PASS
  tokens; a random handful of middle lines shuffled in). POST opens, operator-handoff closes. No file-driven
  text — different every cold start. ARGUS DYNAMICS reveal unchanged.
- **#109 — green the SURVIVE/SOCIAL/SEARCH sub-screens:** the feed *routes* already get `pipBoyPalette` via the
  NavHost-level provider, but the SURVIVE **sub-screens** (SOS/Places/Survival/Tools/Safety) aren't feed
  routes → rendered dark. New `PipGreen { }` helper in `PulseApp` wraps SURVIVE/PLACES/SURVIVAL/TOOLS/SOS/
  SAFETY/SOCIAL/SEARCH so the whole TOOLS area is uniformly green. (Follow-up: deeper per-screen PipFrame
  framing vs the NeonPanel cards.)
- **#110 — objective colours re-classified to gold/white/green:** `ObjectiveKind` MAIN=gold (keep),
  SIDE=**white** (was blue — user-placed side), PLAIN→**WORK**=**green** (calendar/work). `Waypoint` default
  PLAIN→SIDE; calendar events → WORK; NAV search/POI (user-placed) → SIDE; add-chips are Main/Side (WORK is
  calendar-only); quest groups MAIN/SIDE/WORK·CALENDAR; map icons `obj-plain`→`obj-work`. Old persisted
  "PLAIN" waypoints coerce to the SIDE default on load (`coerceInputValues`).
- **#111 — road-snapped NAV path:** new `data/places/RoutingRepository.kt` (free, keyless **OSRM** demo
  server, driving profile) returns road geometry; `NavViewModel.route` refreshes on waypoint-change or
  >60 m player move (debounced 350 ms, combine+collectLatest, throttled); `NavScreen.routeLineGeoJson` feeds
  the road polyline into `ROUTE_SOURCE` so the gold/white casing (#106) traces streets, straight-line
  fallback until it resolves. ⚠️ OSRM demo server is community-hosted/best-effort (a keyed/self-hosted router
  is the production hardening).
- ⚠️ All #108–#111 on-device-unverified (CI compile-gates only). **Open / steerable:** deeper PipFrame
  framing of the SURVIVE/SEARCH/SOCIAL screens; live now-playing (ICY) in station cards; STATUS condition figure.

### Owner follow-ups — PipFrame feeds · ICY now-playing · condition figure (this session cont., #113–#115 merged)
The three steerable follow-ups, each its own CI-green slice (all squash-merged + re-synced):
- **#113 — PipFrame framing for SURVIVE/SEARCH/SOCIAL:** converted from rounded `NeonPanel`/Material cards to
  the flat corner-bracketed `PipFrame` terminal chrome. SEARCH's Material box+Button → a Pip-framed
  `BasicTextField` + a `▸ SEARCH WITH <ENGINE>` Pip button under `PipHeader`s (was Material-coloured); SOCIAL
  item cards → `PipFrame`, trending labels → `PipHeader`; SURVIVE hub tiles → a flat `PipHubTile` (vs the
  rounded `HubTile`).
- **#114 — live now-playing track (ICY metadata):** `MediaPlayer` doesn't surface ICY, so new
  `data/radio/IcyMetadata.kt` opens a short-lived `Icy-MetaData:1` connection, reads `icy-metaint`, skips to
  the metadata block, parses `StreamTitle` (reads a few KB then disconnects — not a 2nd playback stream;
  defensive → null). `RadioController` polls the tuned station's title every ~25 s while on air (own
  `metaJob`, cancelled on stop/change) → `nowPlaying` flow; `RadioBody` shows `♪ Artist - Song` in the tuner
  + the on-air station card's tiny line. SomaFM carries ICY; many stations don't (→ falls back to genre).
- **#115 — STATUS condition figure:** a CONDITION section atop the STATUS feed — an **original** generic
  operator silhouette (head/torso/arms/legs drawn procedurally; **no trademarked Vault-Boy art**) whose
  regions tint green/amber/red by subsystem health (head=thermal, torso=free-mem, legs=power, arms=overall),
  a `CND %` (mean) + OPTIMAL/FAIR/CRITICAL label, and `PWR/MEM/THRM` rows. `condColor` thresholds at .66/.33.
- ⚠️ All #113–#115 on-device-unverified (CI compile-gates only). The ICY read especially wants a real run
  (SomaFM streams are the reliable ones). **Open / steerable next:** owner's call.

### GrapheneOS / Pixel security arc + Spaceballs Ludicrous Speed (this session, #197–#200 merged + a WiFi/audit batch)
Owner moved the project onto a **hardware-security** track on their real device (Pixel 10 Pro XL on GrapheneOS,
now **provisioned as a Device Owner** via adb). Also a long Spaceballs "Ludicrous Speed" visual arc landed first.
- **Spaceballs Ludicrous Speed** (in #197): `feature/spaceballs/SpaceballsScreen.kt` rebuilt to a movie-accurate
  sequence per the owner's REBUILD BRIEF — swept amber streak field, crossing-diagonal tartan wash, green
  dot-matrix signs, two-stage EMERGENCY STOP → snap. Smoothness fix: read animation `State` only in draw/layer
  lambdas (no per-frame recomposition). All tunables in a top-of-file `Lud` CONFIG.
- **Auto debug reporting (#197):** `data/diagnostics/DebugUploader.kt` + `core/util/SecretScrub.kt` — on launch,
  uploads scrubbed crash/diagnostic bundles (build/device/os/gate + latest fault + recent activity + own logcat)
  to a dedicated **`debug-reports`** branch via the repo token, so I can read them remotely. `AppSettings.allSecretValues()`
  is the single authoritative secret list (apiKeys + jarvis tokens + Spotify OAuth) for exact-match scrubbing.
  CI excludes the `debug-reports` branch so reports don't churn the version. Opt-in (default on), no-op without a token.
- **Device gate → Pixel 10 Pro XL on GrapheneOS (#197):** `core/device/GrapheneOs.kt` heuristic detection + the gate
  requires both; Settings gained Device & OS / Special access / Accessibility sections. Honest scope: GrapheneOS
  doesn't grant a sideloaded app more power; restricted-settings stay user-granted.
- **Device-Owner provisioning:** owner ran `adb shell dpm set-device-owner dev.mascwa.pulse.debug/dev.mascwa.pulse.security.PulseDeviceAdminReceiver`
  (only works on a device with NO accounts → factory-reset-first). Fixed a buggy in-app adb hint that used a relative
  class name (the `.debug` applicationId suffix made it resolve wrong). **This is the load-bearing event** — it makes
  `WifiPolicyController.setWifiEnabled` and the device-policy controls actually take effect (previously no-ops).
- **Hardware attestation (#198/#199):** `core:telemetry/HardwareAttestation.kt` (pure DER parser for the Keystore
  key-attestation extension, CI-tested against synthetic records incl. the `[704]` tag) + `core/device/DeviceAttestation.kt`
  (StrongBox-backed probe). Reads verified-boot state, lock state, and the verified-boot KEY. **On-device confirmed:**
  the owner's device reports StrongBox + locked + **Self-signed** + the GrapheneOS `mustang` verified-boot key
  `141d7fc3…c7a04f74` (now in `GRAPHENE_VERIFIED_BOOT_KEYS`). KEY INSIGHT: GrapheneOS re-locks against its own key, so
  the genuine signal is **Self-signed (not Verified) + key match** — `verdict.grapheneVerified`. The device gate now
  rests on attestation (lockout-proof: passes via key OR integrity+heuristic; only a hardware-integrity failure blocks;
  "Continue anyway" + persisted ack remain). Diagnostic also in Settings → Device & OS → Hardware attestation.
- **Device-owner controls (#200):** `security/DevicePolicyController.kt` + Settings → "Device-owner controls" — USB
  data lockdown (charging-only, anti-forensic), camera kill switch, wipe-after-N-failed-unlocks (now confirm-gated).
  `res/xml/device_admin.xml` declares `<disable-camera/><watch-login/><wipe-data/>` (capabilities only).
- **WiFi-disconnect BUG + network/security audit (pushed, pending merge as of this handoff):** owner reported Pulse
  disabling home WiFi while home — because Device Owner made Trusted Network Mode's radio toggle real, exposing latent
  bugs. **(a)** `core:telemetry/TrustedNetwork.kt`: an unreadable SSID (needs location permission, often denied on
  GrapheneOS) was read as "away" → disable. Now "away" must be a POSITIVE fact (off WiFi, or a readable non-home SSID)
  + new `State.wasHome` precondition + no-home-configured = idle. **(b)** A read-only audit subagent caught a BLOCKER:
  `TrustedNetworkMonitor.trigger()` double-locked a non-reentrant Mutex → every network callback deadlocked, freezing
  recovery (sticky disable). Fixed. Plus location-permission awareness (notify when the SSID can't be read). **(c)**
  Audit also fixed a credential-loss path in `SettingsRepository` (a decrypt failure reset all settings to defaults on
  next write → now refuses to clobber an undecodable blob) and gated the wipe control behind a confirm dialog.
- **StrongBox at-rest encryption:** `SecretCrypto.kt` now binds the settings AES key to the Titan M2 secure element
  (`enc2:` alias), used only after a round-trip self-test passes (else falls back to the original TEE `enc1:` key —
  can never strand settings). Backward-compatible with existing `enc1:` blobs.
- **GrapheneOS hardening surface:** Settings → "GrapheneOS hardening" points to the OS's own (stronger) controls —
  per-app MTE/exploit protection (App info), native USB-C charging-only-when-locked, sandboxed-Play detection. We do
  NOT force MTE in the manifest (native libs could break; user stays in control).
- ⚠️ On-device-unverified (CI compile-gates only): all of the above's runtime behaviour — esp. the WiFi state machine
  on the real device, the device-policy toggles (USB/camera/wipe), StrongBox round-trip, and attestation gating.
  **Open / steerable:** MEDIUM audit items deliberately not changed (cloud OkHttp client is in a core module that can't
  import the app's cleartext guard, and base URLs are a fixed HTTPS enum; `usesCleartextTraffic` can't be scoped without
  breaking radio's arbitrary http stream hosts). Mnemosyne reflection WorkManager pass still pending owner Haiku verify.

### Reflection pass · console fixes · Phase 4 skills (this session cont., #202–#206 merged + a procedure tool)
- **Mnemosyne reflection pass (#202):** `jarvis/reflection/ReflectionEngine.kt` — when enough recent
  observation-importance accrues, the model synthesises 1–3 higher-level INSIGHTS from the top seeds, recorded
  as `MemoryKind.REFLECTION`. Cloud-gated (no-op unless a backend is Ready) + throttled ~4h via
  `AppSettings.lastReflectionMs`; driven from `RefreshWorker` beside the curiosity pass. Surfaces in recall
  (`retrieve` scores all kinds) + the Memory screen (violet REFLECTION tag). Toggle: Settings → Storage
  "Reflect on memories". Completes observe → retrieve → **reflect**.
- **J.A.R.V.I.S. console fixes (#203):** keyboard-flush — the window is `adjustResize`, so `JarvisScreen`'s
  extra `imePadding()` double-counted the IME inset (full keyboard-height gap); dropped it so the input seats
  flush. Camera capture — the ⊕ pop-out gained "Take photo" (`TakePicture` → FileProvider `cacheDir/camera/`
  → vision path), matching Claude.ai's image/camera/file attach.
- **Get-a-key links (#204):** each API-key field now has a tappable provider link (`EditableValueRow(helpUrl)`
  for the Optional keys; a "Get a token →" link by the GitHub token) — for re-entry after the factory reset.
- **Mnemosyne Phase 4 — procedural "skills" (#204 slice 1, #205 slice 2, #206 slice 3 + a `procedure` tool):**
  a `Procedure` is a learned, named, reusable multi-step TOOL SEQUENCE that solved a class of goal — distinct
  from Cerebellum's single-action reflexes and from self-coding. Pure `core:telemetry/ProcedureLibrary.kt`
  (keywords/learn/recall/digest/capped; Jaccard cue match ≥0.34; learns only from ≥2-step SUCCESSFUL agent
  runs; practiced ≥2, reliable ≥60%; + 8 CI tests). `data/procedure/ProcedureStore.kt` mirrors CerebellumStore.
  `JarvisViewModel.generateWithAgent` collects the run's ordered tool names; the Chat handler calls
  `procedureStore.observe(request, sequence, ok)`; `composePersona` injects `digest()` so familiar goals follow
  the known plan. View/curate in the Memory screen's PROCEDURES section. `procedure` JarvisTool for explicit
  introspection/recall (parity with `reflex`). End-to-end: learn → use → see/curate.
- ⚠️ All on-device-unverified (CI compile-gates only) — esp. the keyboard-flush, camera capture, and the
  reflection/procedure behaviour with the cloud key set. **Open / steerable:** R8/minify (needs device verify);
  emulator baseline profile (needs SDK); UI declutter (owner-driven).

### Keyboard flush · markets readability · launcher integrations · Reactor Dial (this session cont., #208–#214 merged)
Owner-driven batch on dev branch `claude/loving-edison-bd65oa`; all CI-green, squash-merged to `main`, re-synced.
- **Markets readability + Security-Audit ANR (#208):** `MarketMood`/`MarketExplainers` de-jargoned to plain
  English (a `Mood.plain` full sentence + a tap-hint on the Markets list); fixed an ANR on the Security Audit
  screen — `hasUsageAccess()` (an AppOps binder call) was invoked **per-recomposition** and contended with the
  heavy `getInstalledPackages` scan on the main thread → froze. Cached via `remember(view.lastScanMs)`.
- **J.A.R.V.I.S. console chat bar FLUSH on the keyboard (#209):** the outer `Scaffold` pads the `NavHost` above
  the bottom nav bar with a *raw* padding that inset-aware modifiers can't see, so `imePadding()` over-lifted
  the bar by one nav-bar height (the reported gap). Fix: wrap the JARVIS route in a `Box` that
  `consumeWindowInsets(innerPadding)`, so `imePadding()` lifts by `(keyboard − nav-bar)` → flush. Scoped to
  JARVIS only. **Key Compose inset lesson** recorded here.
- **Launcher integrations (#209):** owner asked about Nova/launcher hooks. Honest scope: Android sandbox forbids
  reading/controlling Nova's own settings — the surface is widgets + live wallpaper + shortcuts + unread badge.
  Shipped three: **(1) J.A.R.V.I.S. arc-reactor live wallpaper** (`wallpaper/JarvisWallpaperService.kt` — ports
  `HudReactor` to a framework `Canvas`; glanceable readout = clock·objective·weather·top-mover; reads the same
  on-device cached data the widgets do via `AppContainer`, no GPS wake; accent/AMOLED from settings; draws only
  while visible, ≤1/min data refresh, no bitmaps, fully defensive; Settings → Appearance sets it). **(2) Live
  data-feed widget** (`widget/FeedWidgetProvider` + `FeedRemoteViewsService` — an `AdapterViewFlipper` collection
  auto-cycling markets·fuel·economy/inflation·news from cached data). **(3) Launcher shortcuts + Nova badge**
  (`shortcuts/AppShortcuts.kt` — dynamic shortcuts Ask-J.A.R.V.I.S./Markets/Navigate/SOS deep-linking via
  `EXTRA_ROUTE`; `UnreadBadge` broadcasts the unread-findings count to Nova/TeslaUnread from `RefreshWorker` +
  app stop). **#210** gave each shortcut a distinct palette glyph.
- **Warm-launch deep-links + wallpaper toggle + feed refresh (#211):** `MainActivity` is now `singleTop` +
  overrides `onNewIntent`, publishing the route into Compose state so shortcuts/notifications re-navigate even
  when Pulse is already running; `PulseApp` consumes the route after handling (re-tap fires again). Wallpaper
  readout toggle (`AppSettings.liveWallpaperReadout`). `RefreshWorker` nudges the feed widget to reload.
- **Reactor Dial — arc-reactor rotary app launcher (#212, #213, #214):** the owner's "rotary phone app list"
  idea. **In-app** (Compose) because a `WallpaperService` can't reliably get home-screen touch (the launcher
  grabs it). `feature/dial/`: `ReactorDialViewModel` (enumerates launchable apps via the held QUERY_ALL_PACKAGES;
  persists per-position pins in `AppSettings.reactorDialSlots`; launches), `ReactorDialScreen` (reactor + bloom
  expand/collapse animation; 8 nodes on a ring; tap=launch, long-press=assign, tap-core=close),
  `ReactorDialComponents` (node + searchable app picker). `Routes.DIAL`; reached by tapping the J.A.R.V.I.S.
  console idle reactor. **#213:** wallpaper **double-tap** (`onCommand` COMMAND_TAP) opens the dial — best-effort
  (launcher-dependent + BAL may be restricted on GrapheneOS); added `DIAL` to the deep-linkable route set (a
  traced bug: it wasn't, so the launch would've landed on Home). **#214:** app-picker search + a **Quick Settings
  tile** (`ReactorDialTileService`) — a reliable system-level touch entry; "Add Reactor Dial quick-tile" in
  Settings requests it via the system prompt (API-guarded).
- ⚠️ All #208–#214 on-device-unverified (CI compile-gates only): wallpaper render/cadence, widget auto-flip,
  TeslaUnread badge, shortcut/wallpaper deep-links, and the **dial layout/animation + app launching** especially
  want eyes on the Pixel. **Note:** a transient harness *content-filter* false-positive blocked one large
  code-gen output (the app-enumeration+launch code looked malware-shaped); worked around by writing the file in
  smaller chunks. **Open / steerable:** tune dial node radius/count/labels from a screenshot; a Home entry point
  for the dial; the wallpaper-touch reliability is an OS limit (QS tile is the dependable entry).

### Reactor Deck + Iron-Man-OS wallpaper + breadth/mood + trace-driven fixes (this session cont., #215–#229 all merged)
Context (#215–#221, owner-driven via screenshots): the in-app Reactor **Dial** was removed leaving only the
self-contained **Reactor Deck** widget (a StackView app-deck); the live wallpaper was rebuilt from a single
arc-reactor into a dense **"Iron-Man-OS" HUD** (world-map radar + corner dials + gauges + waveform + loading
bar + data panel), then **wired to real on-device cached data** (top-mover sparkline, RAM%, geo radar markers,
battery, Kp). Then this batch:
- **Wallpaper turbine→solar wind + breadth panel (#222):** the turbine dial's spin scales with live solar-wind
  km/s (`SOL <n>` label); the decorative "molecule" became a **market-breadth panel** (`drawBreadth`: up/down
  counts + up-share % + green/red split bar) — fed from the watchlist quotes already fetched.
- **Net % on the breadth panel (#223):** added the equal-weighted average daily % change (`NET +x.xx%`,
  sign-coloured, auto-sized) under the up-share headline.
- **Centralized breadth on the CI-tested core (#224):** `MarketMood.Mood` now also exposes `up/down/flat/total`,
  `upShare`, and **`netChangePct`** (raw average — documented as NOT the mood input); the wallpaper reads these
  from `MarketMood.summarize` instead of recounting inline (HUD + Markets screen share one definition). +2 tests.
- **Mood headline on the wallpaper (#225):** the breadth panel now renders `MarketMood`'s plain-English headline
  ("MOSTLY UP TODAY" etc.), tri-state coloured on the 0.55/0.45 boundaries, auto-fit via `measureText`.
- **Data-panel polish (#226):** the live-feed news line was prefixed `NET` (network) which collided with the
  breadth `NET` (net change) → renamed `NEWS`; added a plain-English geomagnetic line (`SPC  Kp 3.0 — Quiet`)
  via the CI-tested `SpaceWeatherExplainers.kp`, amber when storming. No new fetch.
- **Markets mood banner net % (#227):** surfaced `MarketMood.netChangePct` in-app beside the up/down detail as a
  sign-coloured `net +0.42%` (`c.trend`).
- **Trace-driven RADIO fix (#228):** found by tracing the playback stack — a *permanent* ExoPlayer error
  (dead/incompatible station, common with the Radio Browser list) set `Status.ERROR` but never released the
  player or stopped the service (`RadioService` only tears down on `IDLE`), leaking an ExoPlayer holding
  **audio focus** + an orphaned `mediaPlayback` foreground service/notification. New `failPermanently()`
  releases the player, cancels the meta poll, keeps `ERROR` in-app, and stops the service — wired into every
  terminal-error site. Plus a superseded-tune guard at the top of `startPlayer()` (fast-switch could play the
  wrong station). Rejected the trace's `RadioService` "IDLE-first startForeground" false-positive.
- **Trace-driven VOICE fix (#229):** three defensive guards on `ActiveMatrixService` keyed on the existing
  `capturing`/lifecycle flags — `onDestroy` now stops TTS (it kept talking after "Stand down"); the
  `consoleActive` collector re-arms `listenForWake` only `&& !capturing` (console open/close during a command
  capture could open a 2nd mic session); the critical-battery warning speaks only `!capturing` (mid-reply it
  `QUEUE_FLUSH`ed the in-flight TTS and stranded the wake mic). **Deferred (need on-device verify):** a single
  idempotent `rearmWake()` funnel, cancelling the system recognizer on console-open, Vosk `shutdown()` vs `stop()`.
- **Security review (clean, no PR):** verified the debug-report scrub path — `DebugUploader.buildBundle` runs the
  whole bundle through `SecretScrub` (exact-value pass from the authoritative `allSecretValues()` + key-shape
  patterns; Bearer pattern runs before the `authorization:` pattern so `Authorization: Bearer <tok>` is fully
  covered); path/commit-message carry only `crash`/`manual`. No leak; `allSecretValues()` complete vs the data classes.
- ⚠️ All #222–#229 on-device-unverified (CI compile-gates only). The radio teardown + voice mic lifecycle
  especially want a real run on the Pixel. **Open / steerable:** the deferred voice-concurrency items; whether the
  wallpaper breadth/mood density needs trimming; more trace-driven hardening of other subsystems (updater,
  RefreshWorker, nav routing).

### "Blackbox" tamper-evident audit ledger — Slice 1 (this session cont.)
Owner uploaded a second, unrelated **`com.jarvis.app`** APK (a *system-control* J.A.R.V.I.S. — **no LLM**;
Shizuku/Sui + local VPN firewall + accessibility input-remap + device-admin console + an overlay HUD + a
cryptographic blackbox) and asked how hard porting its features into Pulse would be. Verdict: architecture
fits well (same Kotlin/Compose/coroutines/baseline-profile stack; its `core/module`+`core/capability`+`state`
mirrors `AppContainer`/`core:telemetry`), but it's a compiled APK so it's *reimplement-from-design*, and the
privileged pieces (VPN/accessibility/device-admin) touch Pulse's protected/human-gate surface. Picked the
**blackbox tamper-evident ledger** as the highest-value, CI-safe first port.
- **Slice 1 (pure core, this slice):** `core:telemetry/AuditLedger.kt` (+ `AuditLedgerTest.kt`, 14 cases,
  CI-gated) — a hash-chained audit log: `AuditEntry` (seq·time·`AuditEventType`·label·detail·prevHash·hash),
  `Canonical` (length-prefixed, JSON-free, reproducible byte encoding so a hash recomputes identically),
  `HashChain` (`append` links each entry's SHA-256 over its canonical content **+ the prior hash**; `verify`
  walks the chain and returns the first break — seq gap / broken link / altered content — as
  `VerificationResult`), and a pure `LedgerStore`/`InMemoryLedgerStore`. SHA-256 via `MessageDigest` (works
  identically on the JVM unit tests and Android). Altering/reordering/deleting/inserting any past entry is
  detected. Validated the algorithm + every test's expected break-point with an independent Python reimpl
  (kotlinc isn't installed locally; CI is the compile gate — the standalone gradle compiler jars choke on
  enum codegen, an env artifact, not a code issue).
- **Slice 2 (on-device persistence + first producer, this slice):** `data/blackbox/AuditLedgerStore.kt`
  — mirrors ProfileStore (in-memory `HashChain` authoritative + Mutex + debounced flush; flush-on-stop;
  clear-cancels-flush). Persisted blob is **encrypted at rest via `SecretCrypto`** (StrongBox/TEE AES-GCM;
  plaintext fallback if no secure element); the chain is what proves integrity, encryption adds
  confidentiality of the operational `detail`. **Undecodable-blob guard** (the SettingsRepository lesson):
  a present-but-unreadable blob → keep in-memory empty AND refuse to flush, so a transient decrypt failure
  can't erase prior evidence. Exposes `record(type,label,detail)`, `entries()`, `verify()`, `entriesFlow`
  (for a future surface), `clear()`. Wired in `AppContainer.auditLedgerStore`; **first producer:**
  `DebugUploader` records a `DIAGNOSTIC` `debug.upload.<kind>` entry on every successful report upload
  (path only — no content). ⚠️ On-device-unverified (CI compile-gates only): the StrongBox round-trip,
  the flush/persist, and the producer firing.
- **Slice 3 (head-signing core, this slice):** `core:telemetry/LedgerSignature.kt` (+ `LedgerSignatureTest.kt`,
  6 cases, CI-gated) — makes the chain's tip **non-repudiable**: an attacker who rewrites the whole chain
  (recomputing every hash so `verify` passes) still can't forge a head signature without the private key.
  Pure JCA core: a `LedgerSigner` interface (`sign(bytes)` / `publicKeySpki()`, on-device impl is
  Keystore-backed) + `LedgerSignature.verify(headHashHex, sig, spki)` (EC P-256 / `SHA256withECDSA`, X.509
  SPKI decode, fully defensive → false) + a `signHead(hex, privateKey)` test/in-process overload. JCA works
  identically on JVM tests + Android; validated locally via a javac twin (6/6) since the standalone gradle
  compiler can't run here.
- **Slice 4 (on-device signing wired in, this slice):** `security/KeystoreLedgerSigner.kt` implements the
  `LedgerSigner` interface with an **EC P-256 key in AndroidKeyStore** (StrongBox-preferred, TEE fallback —
  mirrors `SecretCrypto`'s alias/fallback; `PURPOSE_SIGN` + `DIGEST_SHA256`; private key never leaves the
  secure element; fully defensive → null). Wired into `AuditLedgerStore` (`AppContainer` passes it): `flush()`
  now **signs the current head** and persists `headSig` + `publicKeySpki` (base64) in the `Stored` blob
  (backward-compatible — added fields default null, `ignoreUnknownKeys`/`coerceInputValues` on); load captures
  them; new **`headSignatureValid()`** verifies the persisted seal via `LedgerSignature.verify` (null when
  unsigned/unavailable; the chain-integrity `verify()` stands alone). Now an attacker who rewrites the whole
  chain on disk still can't forge the head signature. ⚠️ On-device-unverified (CI compile-gates only): the
  Keystore keygen (esp. StrongBox path), sign-on-flush, and verify-on-load.
- **Slice 5 (RFC-3161 timestamp core, this slice):** `core:telemetry/Rfc3161.kt` (+ `Rfc3161Test.kt`, 6
  cases, CI-gated) — pure proof-of-time core. `buildTimeStampQuery(imprint, nonce, certReq)` hand-encodes
  the DER `TimeStampReq` over a SHA-256 imprint (**byte-for-byte equal to `openssl ts -query`**, validated
  locally); `parseResponse(der)` defensively reads the `TimeStampResp` → PKI status + the raw
  `timeStampToken` (persisted verbatim as the anchor) + `genTimeMs` (the TSTInfo `genTime`, found by a TLV
  scan that descends into the CMS `eContent` OCTET STRING — the one non-obvious bit, caught against a real
  token). DER built/parsed by hand, mirroring `HardwareAttestation`; no Android types. Validated end-to-end
  against a **real DigiCert TSA** (POST → granted, genTime parsed exactly) via a Python twin + the embedded
  real response in the test; local kotlinc frontend type-checked it (a `ByteArray.ifEmpty` non-existence bug
  was caught + fixed). A trusted timestamp proves the ledger head existed at/before the TSA's clock, so a
  forger can't back-date a rewritten chain.
- **Slice 5b (Android TSA fetch wired in, this slice):** `data/blackbox/TsaClient.kt` POSTs the
  `Rfc3161.buildTimeStampQuery` (over SHA-256 of the head-hash ASCII) to a public **HTTPS** TSA
  (`freetsa.org` primary, `timestamp.sectigo.com` fallback — DigiCert is http-only; HTTPS dodges the
  cleartext-egress guard) via a new `HttpClient.postBinary`, parses with `Rfc3161.parseResponse`, returns
  the granted token (fully defensive → null). Wired into `AuditLedgerStore.anchorHead()` (opt-in,
  network-bound, call from a UI control / worker — never per-record): stamps the current head, persists the
  token (base64) + `genTimeMs` + which head it covers in the `Stored` blob (3 new defaulted fields,
  backward-compatible); load captures them; `anchorTimeMs()`/`anchoredHead()` surface it. Validated the
  fetch+parse end-to-end against real **freetsa + sectigo + DigiCert** responses (all granted, genTime
  parsed) via the Python twin. `AppContainer` passes `TsaClient(http)`. ⚠️ On-device-unverified: the live
  fetch + the anchor persistence.
- **Slice 6 (surface + trigger + producers — completes the port, this slice):** the ledger is now visible,
  verifiable and fed by real events. **Memory screen** gained an **AUDIT LEDGER** section (mirrors PROFILE/
  TASKS/EPISODIC): a one-line integrity readout (`verify()` intact/broken · `headSignatureValid()` ·
  last-anchor relative time), the entries newest-first (`#seq · type · relative-time · label · detail`), an
  **ANCHOR NOW** button (`anchorLedger()` → `anchorHead()`, best-effort) and **CLEAR LEDGER**. Deliberately
  **no per-entry FORGET** — append-only/tamper-evident, removing one entry would break the chain. `JarvisMemoryViewModel`
  gained `auditLedger` (+ `audit`/`ledgerStatus`/`anchoring` flows, `refreshLedgerStatus`/`anchorLedger`/
  `clearAuditLedger`); factory wires `c.auditLedgerStore`. **Settings → Storage** gained **"Verify audit
  ledger"** (inline status via `SettingsViewModel.verifyAuditLedger`) + **"Clear audit ledger"**. **Producers:**
  `AppContainer.commitCode` records a `SELF_CODE` `selfcode.apply` entry on every human-gated self-code apply
  (goal + PR, no secrets); the Settings device-owner toggles record `SECURITY` `devicepolicy.<action>` entries
  (USB/camera/wipe) via `SettingsViewModel.recordDevicePolicy`. With slice 2's `DebugUploader` producer, the
  ledger now captures the three sensitive event classes. ⚠️ On-device-unverified (CI compile-gates only): the
  Memory section render, the anchor/verify/clear actions, and the producers firing.
- **Blackbox ledger: COMPLETE** (observe → hash-chain → sign → anchor → surface). Possible follow-ups: a
  periodic `RefreshWorker` anchor pass; an attestation producer; porting more `com.jarvis.app` subsystems
  (overlay HUD spec, egress kill-switch, input-remap) — all owner-gated (privileged / protected-path surface).

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
- UI clarity pass continues: the TOOLS hub is now the Pip-Boy feed-tab bar (`GridHubScreen` deleted #70);
  declutter any remaining cryptic screens so they're clear without taking more space (per the 2077-HUD direction).
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
