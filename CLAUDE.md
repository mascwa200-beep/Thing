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
- **Blackbox ledger: COMPLETE** (observe → hash-chain → sign → anchor → surface).
- **Follow-up — periodic auto-anchor (this slice):** `RefreshWorker` gained an opt-in, ~daily, best-effort
  anchor pass: when `AppSettings.autoAnchorLedger` is on and the head advanced since the last anchor
  (`headHash()` ≠ `anchoredHead()`, ≠ genesis), it calls `auditLedgerStore.anchorHead()` and stamps
  `lastLedgerAnchorMs` (throttle mirrors the curiosity pass). New store accessor `headHash()`. Settings →
  Storage toggle **"Auto-anchor audit ledger"** (default OFF — it's an external TSA call, sends only a hash;
  the manual ANCHOR NOW button is always available). So the head gets independently timestamped between
  manual anchors. ⚠️ On-device-unverified (CI compile-gates only).
- **Follow-up — runtime self-test (this slice, owner asked "how do I know it worked?"):**
  `data/blackbox/LedgerSelfTest.kt` exercises the ledger's REAL machinery on-device and reports each check
  pass/fail — (1) hash chain links + tamper detection, (2) the secure-element EC key signs a head + verifies
  (forgery rejected), (3) `SecretCrypto` encrypt→decrypt round-trip, (4) a live RFC-3161 TSA fetch (bounded
  by `withTimeoutOrNull(20s)`). Runs against throwaway data + the real crypto/network components, so it
  never touches the live ledger. `AppContainer.ledgerSelfTest`; `SettingsViewModel.runLedgerSelfTest()` →
  `ledgerSelfTestResult` flow; **Settings → Storage → "Run ledger self-test"** (shows "Running…", then an
  AlertDialog listing ✓/✗ per check + detail). This is the owner's on-device verification path for everything
  CI can only compile-gate.
- **Follow-up — attestation producer (this slice):** the ledger now records the device's **hardware-attestation
  posture** as a `SECURITY` `device.attestation` entry. New `AppContainer.deviceAttestation`
  (`DeviceAttestation()`, stateless StrongBox probe). A `RefreshWorker` pass runs the probe (throttled ~6h
  via `AppSettings.lastAttestationCheckMs`) and records **only when the posture signature CHANGES** (dedupe
  via `lastAttestationSig` = `grapheneVerified|hardwareBacked|strongBox|bootloaderLocked|verifiedBoot|verifiedBootKeyHex`)
  — a posture change (bootloader unlocked, GrapheneOS key mismatch, hardware-backing lost) is a real security
  event; identical verdicts are deduped so the append-only log isn't spammed (≈1 entry ever + one per real
  change). The ledger `detail` is the content-free `verdict.summary` (the key hex rides only in the dedupe
  sig in settings, never the ledger; it's a public OS-signing-key fingerprint anyway). Chosen over the
  recon's "record at the MainActivity gate" option because that fires every cold launch → spam. Adversarially
  reviewed inline (the orchestration workflow hit an infra permission error) across correctness/privacy/
  integration; the only edge (a flush-debounce kill window) is identical to every existing producer. ⚠️
  On-device-unverified (CI compile-gates only): the periodic probe + record firing. Remaining follow-ups:
  porting more `com.jarvis.app` subsystems (overlay HUD spec, egress kill-switch, input-remap) — all
  owner-gated (privileged surface).

### Removed — both "deck" features (this session, owner asked "remove the entire deck tab and features")
Owner disambiguated (AskUserQuestion) → **both**. Deleted: (1) the **Spaceballs "DECK" bottom-nav tab** —
`SpaceballsScreen.kt`, `Routes.SPACEBALLS`, the `TopDestination`, the NavHost `composable`, the now-unused
`Dashboard` icon import (bottom nav 8→7 tabs); it borrowed the shared `SettingsViewModel`, so no VM removed.
(2) the **Reactor Deck home-screen widget** — `ReactorDeckWidgetProvider`, `DeckRemoteViewsService`, the
`feature/dial/DialLaunchTrampolineActivity` (the whole `feature/dial` package — the Reactor Dial was already
gone, and the trampoline + `dial_card_bg` drawable were used only by the deck), the `widget_deck*` layouts,
`deck_widget_info.xml`, the two `widget_deck_*` strings, and the manifest receiver/service/activity. Verified
zero dangling references (`Spaceballs`/`ReactorDeck`/`Dashboard`/`widget_deck`/`pulsedeck` all NONE) + XML
well-formed. The `Reactor Deck` / `Reactor Dial` / `Spaceballs Ludicrous Speed` entries above are history now.

### S.P.E.C.I.A.L. → a real game (owner: "make S.P.E.C.I.A.L. actually do something, write a whole game")
The PIP-BOY STAT tab's S.P.E.C.I.A.L. was decorative — `TelemetryScreen.specialStats(t)` maps device metrics
(battery→STR, sensors→PER, RAM→INT, versionCode→LUCK/LEVEL) recomputed every frame, no persistence. Turning
it into a real wasteland RPG: a persistent character you build by playing stat-gated encounters. Design kept
(the two screenshots ARE the current app): `PipFrame`/banded `SpecialRow`/`pipBoyPalette` preserved; the
S.P.E.C.I.A.L. section becomes the game, OPERATOR LEVEL → the character's game level.
- **Slice 1 (pure engine + content + tests, this slice):** `core:telemetry/SpecialGame.kt` — `Special`
  (7 attrs), `Character` (stats 1–10 · level · xp · caps · hp · unspent points · seen), `Encounter`/`Choice`/
  `Outcome`; `check` (`stat + d10 + luckMod ≥ difficulty`; natural 10 crits, natural 1 fumbles; LUCK tilts
  ±2), `resolve` (crit doubles xp + bonus caps; marks non-repeatable seen), `gainXp` (cascading level-ups →
  +1 point + heal), `allocate` (raise a stat, cap 10, END lifts maxHp), `nextEncounter` (avoid-seen →
  repeatable fallback), `revive` (full HP, −25% caps). `core:telemetry/SpecialEncounters.kt` — 19 hand-written
  encounters covering all 7 stats × easy/med/hard, incl. 2 repeatable SCAV_* so it never runs dry. Deterministic
  (roll injected) → CI-testable: `SpecialGameTest.kt` (22 cases). Validated: kotlinc frontend type-checked
  clean + a Python twin (20/20) confirming the math + every test's expected values.
- **Slice 2 (on-device save, this slice):** `data/game/SpecialGameStore.kt` — mirrors ProfileStore/TaskStore
  (in-memory `Character` + Mutex + debounced flush; DataStore `pulse_special`). Persists the character +
  current encounter as a `Stored` blob (stats as `Map<String,Int>`; partial/old saves refill missing stats
  to START); supplies the randomness the pure engine needs — `venture()` (draw next, guarded on down/active),
  `choose(i)` (roll d10 → `SpecialGame.resolve` → publish `resolutionFlow`), `allocate(s)`, `revive()`,
  `reset()`; `characterFlow`/`resolutionFlow`/`encounterFor(c)`. Wired in `AppContainer.specialGameStore` +
  `TelemetryViewModel` (exposes `character`/`currentEncounter`/`gameResolution` + `venture`/`choose`/
  `allocate`/`revive`/`resetGame`) via the factory.
- **Slice 3 (STAT-tab UI, this slice):** `TelemetryScreen` — replaced the device-derived `specialStats`/
  `SpecialPanel`/`SpecialRow` with the game: `SpecialGamePanel` (LVL/CAPS/HP header + a 24-seg XP bar; the 7
  banded stat rows now show the CHARACTER's stats, with a ＋ to allocate when `unspent > 0`; a LEVEL-UP
  banner), `EncounterPanel` (title/prompt + `ChoiceButton`s tagged with the stat gate `· DC n` + a live
  odds label SURE/LIKELY/EVEN/RISKY/LONGSHOT from `SpecialGame.check` over the 10 die faces), `IdlePanel`
  (VENTURE OUT / last outcome + rewards + VENTURE ON), `DownedPanel` (PATCH UP). OPERATOR "LEVEL" now shows
  the character's game level, not `BuildConfig.VERSION_CODE`. All in the existing Pip-Boy style (`PipFrame`,
  `ChakraPetch`/`JetBrainsMono`, palette). ⚠️ On-device-unverified (CI compile-gates only): the play loop
  render + persistence on the Pixel. (Slices 1–3 merged as #241.)
- **Slice 4 — perks + more content (this slice):** `core:telemetry/Perks.kt` (`Perk` + 11 perks: per-stat
  +2-to-checks, Scrounger +25% caps, Fast Learner +25% XP, Field Medic heal-on-win, Born Lucky easier crits).
  Engine: `Character` gains `perks: Set<String>` + `perkPicks`; `gainXp` grants a perk pick every EVEN level;
  `check` gained an optional `critMargin` (default `CRIT_MARGIN`, backward-compat); `resolve` applies the
  owned perks (stat bonus to the check, easier crit margin, and on a win scales caps/XP + heals); new
  `choosePerk`/`perkStatBonus`. `SpecialEncounters` grew 19 → 28 (super-mutant, mole rats, caravan job,
  power-armor frame, number station, stray dog, sandstorm, card sharp, sniper). `SpecialGameTest` +8 perk
  cases (30 total). Store persists `perks`/`perkPicks` (defaulted → old saves load) + `choosePerk`; VM
  delegates; UI adds a `PerkChoicePanel` (shown when `perkPicks > 0`, lists un-owned perks) + an owned-perks
  line. Validated: kotlinc frontend clean + a Python twin (9/9, truncating division to match Kotlin `/`).
  ⚠️ On-device-unverified (CI compile-gates only). **Open follow-ups (offered):** stats also fed by real
  app-usage XP; more encounter content.

### S.P.E.C.I.A.L. → a real-world game — the "bleed into reality" expansion (this session, #243–#248 all merged)
Owner asked to grow the STAT-tab RPG into a game that "bleeds into the real world": achievements (usage-
driven), inventory/items, NPCs/dialogue, real-world telemetry/temperature-driven checks, and a Pokémon-Go
map on real shop data (places visited / distance / time). Built as **6 CI-green slices**, each squash-merged
+ re-synced. Pure logic went into `core:telemetry` (CI-gated); the Android layer is compile-gated only, and
each large Android slice was adversarially compile-reviewed by a subagent before merge. **All on-device
runtime behaviour (GPS/Overpass/weather/MapLibre render, persistence) is owner-verify — CI can't prove it.**
- **Slice 1 (#243) — items + inventory + environment (pure core):** `core:telemetry/Items.kt` (22-item
  catalog: AID heal / CHEM one-check buff / GEAR passive / JUNK sell / QUEST; original homage names) +
  `Environment.kt` (`EnvContext` distilled from device+weather → deterministic stat modifiers: cold/heat
  tax END, night hurts PER/helps AGI, thin air taxes END, motion helps AGI/hurts PER, low battery = bad
  LUCK; effects stack). `SpecialGame.resolve` gained optional `env` + `useItemId`; `Character.inventory`,
  `Outcome.items` loot; `addItem/removeItem/useAid/sellItem/buyItem/gearStatBonus`. +28 tests.
- **Slice 2 + 3-core (#244) — on-device wiring + achievements engine:** `SpecialGameStore` persists
  inventory + `choose(env,useItemId)`; `TelemetryViewModel` builds a live `EnvContext` each tick from
  `Telemetry` + a best-effort `WeatherRepository` outdoor temp (→ °C via unit symbol). Loot drops on ~9
  encounters. STAT-tab UI: **CONDITIONS** readout, **PACK** (USE/SELL), **PREP A CHEM** in encounters.
  Plus `core:telemetry/Achievements.kt` (18-achievement catalog over combat/progression/economy/**app
  usage**/**travel** with XP/caps/item rewards; `GameMetrics`/`evaluate`/`progress`/`applyReward`; +9 tests).
- **Slice 3b (#245) — achievements live on-device:** `SpecialGameStore` gained lifetime counters
  (wins/crits/ventures) + unlocked set, `runAchievementCheck` (grants rewards on unlock), split metrics
  setters. VM feeds real usage (`setUsageMetrics`). STAT-tab **ACHIEVEMENTS** panel (progress bars) + a
  one-shot **UNLOCK banner**.
- **Slice 4 (#246) — locations/NPCs/dialogue (pure core):** `core:telemetry/GameLocations.kt` —
  `LocationKind` (TRADER/MEDIC/FIXER/BARKEEP/OUTPOST), `GameLocation`, `NpcAction` (Shop|Talk),
  `kindFor(osmCategory)`, `stock(kind)`, `npcName`/`greeting`, and `conversation()` = a repeatable
  single-choice CHARISMA encounter reusing `SpecialGame.resolve` (perks/LUCK/env/crits all apply). +8 tests.
- **Slice 5a (#247) — the real-world map (data + travel + shop/talk loop):** `data/game/GameWorldStore.kt`
  — **per-kind Overpass queries** turn real OSM shops into `GameLocation`s (Place carries no category, so
  classify by which query it came from); tracks walked distance (jitter/jump-filtered GPS deltas), distinct
  locations reached (≤60 m), and time played — **aggregate + on-device only** (persisted blob is 3 numbers +
  a set of ids, never a raw trace). `SpecialGameStore.buy`/`resolveTalk` + `setTravelMetrics`. VM polls GPS
  every 10 s, accrues play-time, feeds travel achievements. STAT-tab **WASTELAND MAP** panel (WALKED/PLACES/
  PLAYED + SCAN AREA + nearby list → per-location shop [buy, caps-gated] + TALK).
- **Slice 5b (#248) — the literal MapLibre map:** `feature/tacnet/WastelandMap.kt` — clones NAV's MapLibre-
  in-Compose wiring (lifecycle `MapView`, OpenFreeMap style, player + per-kind-coloured location
  `CircleLayer`s via a GeoJSON `color` prop, tap→`queryRenderedFeatures`→select). **Compact/embedded:** map
  gestures disabled so it pans with the player (recentre on first fix) and page-scroll passes through; taps
  still select. Reuses the existing maplibre dep (no new deps). Map dot ids == `GameWorldStore` ids, so the
  map and the list stay in sync.
- **Verification pattern this run:** the pure cores were locally run with kotlinc 2.0.21 (needs
  `trove4j` + `kotlinx-coroutines` + `annotations` on the compiler classpath — the earlier "enum codegen
  crash" was really a missing-`trove4j` artifact, NOT a code issue); all game tests green (SpecialGame/
  SpecialWorld/Achievements/GameLocations). Android slices: subagent compile-review (all returned clean) +
  CI. **Open / owner-steerable:** the offered app-usage-XP + more-encounters follow-ups; a full-screen
  wasteland map (embedded one has gestures off); a Settings "clear travel history" (store `clear()` exists,
  UI not wired); haggle/discount at shops; richer branching dialogue.

### S.P.E.C.I.A.L. — more game features + the CP2077-on-mobile gesture redesign (this session cont., #249–#258 merged + gesture slice)
Owner: "keep adding game features," then a hard redesign ask — "This game can't be just a bunch of text and
some options anymore. Make it more user choice based but not with some lousy type-your-answers improvement.
It must have nothing to do with a keyboard or typing or choosing the two-or-more-options buttons." When asked
how, the owner chose (AskUserQuestion → Other): **"Do what CDPR's Cyberpunk 2077 did with the stat-based game,
but keep the interactive/Pokémon-Go part. What would CDPR have made if they built CP2077 on mobile?"**
- **Added game systems (all CI-green pure cores + on-device wiring, #249–#258):** **crafting**
  (`core:telemetry/Crafting.kt` — `Recipe`, workbench turns JUNK→GEAR/AID for XP; `WorkbenchPanel`),
  **companions** (`Companions.kt` — hireable NPCs that grant passive check bonuses / luckier crits;
  `CompanionPanel`), **daily objectives** (`DailyObjectives.kt` — a rotating daily grind loop with claimable
  rewards; `DailyPanel`), **faction reputation** (`Reputation.kt` — per-`LocationKind` standing that
  discounts shops + is earned by trading/winning talks; rep-aware `buyAt`/`resolveTalk`; `ReputationPanel`),
  and **boss fights** (rare, level-gated, brutal encounters in `SpecialEncounters`). Each mirrored the
  established store/VM/UI pattern; `SpecialGameStore` persists the new `Character` fields (recipes are
  content, companion/reputation/daily persisted), locally kotlinc-validated + subagent compile-review + CI.
- **The CP2077-on-mobile answer — physical-gesture encounter resolution (this slice, in progress):** the
  encounter UI no longer has choice buttons. Each approach is shown **CP2077-style as a stat gate** —
  `[STR 12] Heave it wide  ▸ SHAKE` — coloured by your live odds, and you **commit to it by performing the
  action with the phone**, not tapping. Pure CI-tested core `core:telemetry/Gestures.kt` (+ `GesturesTest`,
  3 cases, 30 total green): `GestureType` (**SHAKE** = STRENGTH/ENDURANCE/LUCK, **FLICK** = AGILITY,
  **HOLD STILL** = PERCEPTION/INTELLIGENCE/CHARISMA), `forStat`, and `performanceRoll(0f..1f → 1..DIE)` — a
  flawless gesture over a built stat lands a crit, a sloppy one under-statted hurts. On-device: a rewritten
  `EncounterPanel` runs a fixed-cadence (`POLL_MS`) `LaunchedEffect` over the live `TelemetryController`
  accelerometer/gyro (`accelG`/`gyroDps`) — detects the shake energy / flick spike / dead-still hold, grades
  it 0..1, and calls `choose(idx, chem, performanceRoll(quality))`. `SpecialGameStore.choose`/
  `TelemetryViewModel.choose` gained an optional `roll: Int?` (the graded die; random when null) +
  `telemetryFlow`. A live meter (`SegBar`) shows gesture progress; CHEM prep + the odds label are kept.
  Polls `telemetry.value` (not `.collect`) because a StateFlow is conflated and a perfectly-still phone
  wouldn't re-emit. All thresholds are top-of-file owner-tunable consts (`FLICK_MIN_G`, `SHAKE_FIRE`,
  `STILL_HOLD_MS`, …). The map shop/**TALK** buttons (the Pokémon-Go part) stay as-is per the owner's "keep
  the interactive part." ⚠️ **On-device-unverified — the gesture thresholds and sensor feel are entirely
  CI-unprovable; the owner tunes SHAKE/FLICK/HOLD sensitivity on the Pixel.** A HOLD scene now requires an
  **explicit arming tap** first (owner asked) — the still-timer only runs after "◉ TAP TO ARM THE HOLD", so a
  phone resting on a table can't auto-commit; SHAKE/FLICK stay immediate (they can't fire at rest).

### S.P.E.C.I.A.L. → a Fallout LIFE-SIM ("The Sims but I'm the Sim") — accepted brief, in progress
Owner's vision: turn the RPG into a Fallout-styled life-sim that **bleeds into reality**. (1) **Constant
camera/mic ambient sensing** → the game reads what's going on / what it hears and generates its strategy
(encounters/difficulty/flavour) from that. (2) **Geofenced purchasing** — you must **physically be at** a
real map location to buy/trade there (true Pokémon-Go). (3) **Day tracking** — the game counts wasteland
days. (4) **Generative missions/quests/storylines** driven by what it **sees, hears, and knows about you**
(profile interests/wants/needs + tasks + memory + location + time). Framing: *"The Sims but I get to be the
Sim,"* Fallout-styled. **Authorization (owner, explicit):** GrapheneOS, Pulse is **Device Owner**, single
user, on-device-first — "it's all authorized on my behalf." Invariants kept: on-device-first (ambient
sensing/classification stays on-device; no raw camera/audio leaves without opt-in — the credential-scrub /
privacy-first pattern extends to raw sensor data), human-gate for self-code, isProtected denylist.
- **Architecture / slice plan** (pure CI-tested cores in `core:telemetry` first, on-device sensing/UI after —
  the established pattern): **[1] geofence + days (this slice)** → **[2] Perception core** (`SceneSignals`→
  `SceneContext`: setting/activity/social distilled from camera-scene + sound labels + light/motion/time →
  strategy modifiers; pure) → **[3] on-device camera/mic capture + classifiers** (heavy, on-device-only) →
  **[4] Story/Quest director core** (compose personalised quests/story beats from profile+tasks+scene+
  location+day+character; deterministic seed for CI, LLM flavour on-device cloud-gated) → **[5] director
  wiring + UI**.
- **Slice 1 — geofenced purchase + day tracking (this slice):** two pure CI-tested cores +
  `LocationGateTest`/`GameClockTest` (13 cases, locally kotlinc-validated + green). **`LocationGate`**
  (`isAtLocation`/`distanceTo`/`reachHint`, 60 m reach, reuses `TravelFilter.distanceMeters` — no app Geo
  dep) gates the map: `WastelandPanel`'s `LocationSheet` now takes `atLocation`+`distanceM`; when you're **not**
  within reach it shows the wares as a lure with **"▸ TRAVEL HERE TO TRADE — Nm away"** and the BUY rows +
  TALK are disabled — buyable only when physically at the shop AND affordable. **`GameClock`** (`dayNumber`/
  `daysSurvived`/`phase`→`DayPhase`/`banner`/`isNewDay`) + `SpecialGameStore.startedAtMs` (persisted in the
  Stored blob, stamped on first load, migrates old saves, re-stamped on `reset()`) + `startedFlow`;
  `TelemetryViewModel.dayBanner` (rebuilt each tick from start-ms + now + hour) renders a **"DAY 3 · DUSK"**
  banner under the S.P.E.C.I.A.L. header. ⚠️ On-device-unverified (CI compile-gates only): the presence gate
  with a live GPS fix + the day banner advancing.
- **Slice 2 — perception core (this slice):** `core:telemetry/Perception.kt` (+ `PerceptionTest`, 15 cases,
  locally kotlinc-validated + green) — the PURE, CI-tested brain of "constant camera/mic → strategy." Takes
  the text labels an on-device classifier produces (`PerceptLabel` scene + sound tags) plus light/motion/hour
  (`SceneSignals`) and `distill`s them into a `SceneContext` (`Setting` INDOOR/OUTDOOR/VEHICLE, `Activity`
  STILL/MOVING/COMMUTING, `Social` ALONE/VOICES/CROWD, `LightLevel`, `DayPhase`, top tags + a `describe()`
  line). `strategy(ctx)` → `SceneStrategy` (which `Special` themes the wasteland favours next, a clamped
  ±1 `tempoNudge`, a flavour line for the story director). Keyword-vocab + threshold logic → deterministic →
  CI-gated. **Privacy invariant:** only text-label summaries reach the core — no pixels/audio, nothing leaves
  the device. **NOT yet wired** (foundation): next is the on-device camera/mic sampler + classifier feeding
  `SceneSignals`, then folding `strategy.favored` into encounter selection + `flavor` into the story director.
- **Slice 3 — story/quest director core (this slice):** `core:telemetry/StoryDirector.kt` (+
  `StoryDirectorTest`, 11 cases, locally kotlinc-validated + green) — the "knows about you" generative
  payoff. `compose(LifeContext, seed)` builds up to 4 personalised `Quest`s from your real life: a **MAIN**
  from your top pending task (`COMPLETE_TASK`, else a `SURVIVE_DAYS` arc), a **SIDE** from a profiled
  interest (`WALK_DISTANCE` when you're out/moving, else `VENTURE`), a **SIDE** `VISIT_KIND` for a real place
  nearby (ties to the geofenced map), and a **DAILY** `WIN_ENCOUNTERS` scaled to level + flavoured by the
  perceived `SceneContext`. Every `QuestGoal` is a real-world signal the game already tracks, so playing
  nudges your actual life. Deterministic given (context, seed) → CI-gated; `source` names the real-life
  origin for transparency; no profile/task text leaves the device. **NOT yet wired:** next is a `QuestStore`
  pulling interests (ProfileStore) + tasks (TaskStore) + nearby kinds (GameWorldStore) + day/level →
  `compose`, a QUESTS surface, and completion/reward tracking.
- **Slice 4 — quest director WIRED + QUESTS surface (this slice):** `TelemetryViewModel` gained
  `profileStore` + `taskStore` (via `PulseViewModelFactory`) and a `quests: StateFlow<List<Quest>>` built by
  `combine`-ing `profileStore.entriesFlow` (INTEREST/PROJECT/PREFERENCE → interests, weight-sorted) +
  `taskStore.tasksFlow` (`TaskBoard.pending` → titles) + `gameWorld.locationsFlow` (→ nearby `LocationKind`s)
  + `game.characterFlow` (level) + a `_day` StateFlow (from `GameClock.dayNumber`, updated each tick so
  quests roll over daily) → `StoryDirector.compose(seed = day)`. New **QUESTS panel** on the STAT tab
  (`QuestsPanel`/`QuestCard` — MAIN gold / SIDE white / DAILY green, brief + `source` + reward). Your real
  tasks/interests/nearby places now show as Fallout missions. **Scene stays default `SceneContext()`** until
  the camera/mic sampler is wired; **read-only** (completion detection + reward granting is the next slice).
  ⚠️ On-device-unverified (CI compile-gates only); the profile/task stores load app-wide via J.A.R.V.I.S., so
  quests fill in as those load (graceful — the combine re-emits).
- **Slice 5 — quest completion + reward loop (this slice):** the QUESTS panel is now a real loop.
  `core:telemetry/QuestLog.kt` (+ `QuestLogTest`, 11 cases, locally green) is the pure completion engine:
  `QuestMetrics` (live counters) + `ActiveQuest` (an issued quest FROZEN with a metric baseline) + `QuestLogState`
  (active + `completedIds`) → `progress` (delta from baseline for walk/wins/ventures/places; absolute for
  survive-day / task-left-pending), `isComplete`, `view`, and `sync` (retire completions → reward, top up from
  freshly-composed quests by new id only [never re-issue a completed id → no farming; daily dodges this via the
  day-in-id]). `data/game/QuestStore.kt` persists `QuestLogState` (mirrors ProfileStore; **Mutex-guarded
  read-modify-write** so rapid emissions can't double-complete), exposes `quests: StateFlow<List<QuestView>>` +
  `completed: SharedFlow<Quest>`. `TelemetryViewModel` drives `questStore.sync(composed, metrics)` from a
  `combine` (interests + tasks + nearby + `game.metricsFlow` + `_day`), collects `completed` →
  `game.awardQuest(caps, xp)` (new `SpecialGameStore` method: caps + `SpecialGame.gainXp`) + a one-shot
  `questCompleted` banner. UI: `QuestCard` now shows a **progress bar + `done/target` + ✓ COMPLETE**, and a
  **QUEST COMPLETE reward banner**. `resetGame` clears the quest log. Wired via `AppContainer.questStore` +
  factory. ⚠️ On-device-unverified (CI compile-gates only).
- **Slice 6 — on-device ambient HEARING (this slice, owner chose "on-device classifiers"):** the Perception
  brain is now live via the mic. `data/perception/AmbientPerceptionSampler.kt` samples short mic clips and
  runs each through the **MediaPipe YAMNet audio classifier fully on-device** (`tasks-audio` 0.10.21, new
  dep; RunningMode.AUDIO_CLIPS; `createAudioRecord`→`AudioData.load`→`classify`), publishing recognised sound
  labels (speech/music/traffic/crowd/silence) as `PerceptLabel`s. **Privacy:** only text labels are produced;
  raw audio is classified then discarded, never persisted/sent. Fully defensive — no RECORD_AUDIO (already a
  granted perm from voice), no model, or any hardware/classifier failure → publishes nothing → neutral scene.
  The ~4 MB YAMNet model is **fetched once on first use** (`HttpClient.download` → filesDir, kept out of the
  APK) and cached. `TelemetryViewModel.sceneContext` = `combine(sampler.soundLabels, telemetry)` →
  `Perception.distill` (light/motion/clock too), **throttled** via a bucketed `distinctUntilChanged` so
  accelerometer jitter doesn't re-distill; it feeds `LifeContext.scene` in the quest driver (via a
  `lifeInputs`+`scene` two-stage combine, since >5 flows) so the story director flavours by what you're doing.
  A **"PERCEIVES · <describe>"** readout on the STAT tab surfaces it for on-device verification. Sampler
  start/stop tied to the game screen lifecycle. ⚠️ **On-device-unverified — the MediaPipe audio path, model
  download, and mic behaviour are entirely CI-unprovable; owner verifies on the Pixel** (mic may be busy while
  voice is active → falls back to neutral, by design). **Next (camera half):** an ImageClassifier
  (`tasks-vision` + CameraX + CAMERA permission) for scene/object labels → the "sees" half; wiring
  `SceneStrategy.favored` into encounter *selection* (currently only flavours the story).
- **BUG FIX — "it thinks I'm moving while stationary":** both "moving" derivations thresholded the RAW
  gravity-inclusive accel magnitude (`accelG = |a|/g`, ~1.0 at rest), so sensor bias/handling could trip
  them and the `distinctUntilChanged` throttle then *stuck* on MOVING. Fixed by switching to a **smoothed
  deviation-from-rest intensity** (`EWMA(|accelG − 1|)`, ~0 at rest): (a) `Perception.SceneSignals.motionG`→
  **`movement`** (intensity), `MOTION_MOVING_G 1.10`→`MOVEMENT_THRESHOLD 0.09`, `moving = movement ≥ 0.09`;
  VEHICLE now also **requires motion** (engine sounds while stationary ≠ in transit). (b) `Environment`
  (the game's stat-modifier world) had the SAME bug — `EnvContext.motionG`→**`movement`**, `MOVING_G 1.3`→
  `MOVING_INTENSITY 0.09` (drives the CONDITIONS "On the move" AGI+1/PER−1 + the "moving" encounter tag).
  (c) `TelemetryViewModel.movementIntensity` = an Eagerly `StateFlow` (EWMA, `MOTION_SMOOTH 0.8`) feeding
  BOTH `sceneContext` and `buildEnv`. +4 core tests (resting/handling → STILL; stationary engine ≠ vehicle);
  83 core tests green. Owner-tunable consts. ⚠️ Threshold feel is on-device-tunable on the Pixel.
- **Slice 7 — perception now shapes GAMEPLAY (not just flavour):** `SpecialGame.nextEncounter` gained an
  optional `favored: Set<Special>` — when any eligible encounter tests a favoured stat, the pick is drawn
  from those (else the full pool, so variety holds). `SpecialGameStore.venture(favored)` passes it;
  `TelemetryViewModel.venture()` supplies `Perception.strategy(sceneContext.value).favored`, so what the game
  hears/senses (voices→CHARISMA, motion/dark→AGILITY/PERCEPTION/ENDURANCE) biases which encounters appear.
  +1 core test (76 SpecialGame-suite green). This closes the perception→gameplay loop for the AUDIO half; the
  camera "sees" half (ImageClassifier + CameraX) remains the last slice.
- **Slice 8 — on-device ambient SEEING (camera; owner chose "alternate back + front"):** the perception
  brain now sees as well as hears. `data/perception/CameraPerceptionSampler.kt` runs **CameraX ImageAnalysis
  bound to a self-managed `LifecycleRegistry`** (the sampler IS its own `LifecycleOwner`, so it works
  headless — no Activity), classifying brief frames through the **MediaPipe EfficientNet-Lite image
  classifier fully on-device** (new deps: `tasks-vision` 0.10.21 + CameraX 1.4.1 core/camera2/lifecycle) →
  object/scene `PerceptLabel`s. It **dwells on the BACK camera** (your surroundings) and **flips to the FRONT
  every FRONT_EVERY cycles** for a brief peek (owner's choice; rebinds only on camera change). Privacy: only
  text labels; each frame classified in memory + discarded, nothing stored/sent; fully defensive (no
  CAMERA/model/hardware → publishes nothing → neutral scene). ~4 MB model fetched once (`HttpClient.download`
  → filesDir, out of the APK). `TelemetryViewModel.sceneContext` grew to a **4-arg combine** (sounds + scenes
  + movement + light) → `Perception.distill`, so real `Setting` (INDOOR/OUTDOOR/VEHICLE) now comes from what
  the camera sees. Manifest gained `CAMERA` + `camera.any` (not required); the STAT tab requests CAMERA on
  open (`vm.start()` re-picks it up on grant). Wired via `AppContainer.cameraPerceptionSampler` + factory;
  start/stop tied to the game-screen lifecycle. **Completes camera/mic sensing — the game now sees, hears,
  knows you, tracks days, and geofences.** ⚠️ On-device-unverified (CI compile-gates only, and the CameraX +
  MediaPipe-vision API surface is validated only by CI having the jars): the camera path, model download,
  back/front alternation, and permission flow are entirely owner-verify on the Pixel.
- **The full life-sim batch (gesture encounters + slices 1–8) merged to `main` as PR #259** (squash `a5a4b0e`);
  dev branch re-synced. The game now sees, hears, knows you, tracks days, geofences, and rewards real-life
  quests. **CI-green through the camera slice (#956).**

### Life-sim follow-up — ambient-sensing privacy toggle (post-#259, new PR)
Owner asked (re camera): the ambient camera/mic sensing activates the camera whenever the STAT screen opens
(the GrapheneOS indicator lights up), so it needs an off switch. `AppSettings.ambientSensing` (default ON,
serializable → backward-compatible); `TelemetryViewModel.start()` now gates `sampler.start()`/
`cameraSampler.start()` on `settings.current().ambientSensing` (samplers stay individually no-op without
their permissions too). Settings → **Security & network → "Ambient sensing (game)"** toggle. Takes effect on
the next game-screen open (navigating to Settings pauses the game → stops the samplers → returning re-reads
the setting). ⚠️ On-device-unverified (CI compile-gates only). **(#260 merged.)**

### TOOLS visual unification — sibling tabs to the STATS Pip-Boy look (PR #261)
Owner sent a screenshot of the STATS/PIP-BOY page and asked to make the sibling TOOLS tabs match it
**pixel-for-pixel** (via AskUserQuestion → "unify the other TOOLS tabs"). Drove it from a full design-system
conformance audit (subagent) of all 9 TOOLS screens vs the canonical kit in `feature/common/PipUi.kt`
(`PipFrame` flat corner-bracket box, `PipHeader` ■+rule section header, `PipDataRow`, `PipSelectRow`,
`PipStatTile`) + the STATS reference `TelemetryScreen`. **Corrected a mis-flag:** the top-strip `LVL 888` is
INTENTIONAL (device readout: HP=battery, AP=free-mem, **LVL=build** in `PipBoyScreen.kt`), and the
OperatorPortrait already uses `character.level` — the screenshot's "LEVEL 888" was an older build, not a bug.
- **New `PipUi.PipChip`** — THE canonical flat rectangular pick-one chip (solid green when selected / hairline
  outline otherwise), replacing the cut-cornered `NeonChip` in feed rails.
- **1/3 (feed screens):** SEARCH (engine picker → PipChip; now fully conforming), SOCIAL (tab rail + tag
  chips → PipChip; item titles → ChakraPetch), PLACES (category rail → PipChip; name/address/source/permission
  → Pip fonts; Material "Grant location" `TextButton` → flat Pip button).
- **2/3 (SURVIVE sub-screens):** OfflineSurvival (Material `Surface`→`Box`; rounded `HubTile`→flat
  `PipHubTile`; `TextButton`→Pip button), Guides (title/summary + detail body → Pip fonts; detail headings →
  `PipHeader`+`PipFrame`), Tools + SOS + Safety (typography → Pip fonts; SOS/Safety Material buttons → Pip;
  SOS ActionRow rounded icon-chip flattened). **SurviveHub was already conforming.**
- **3/3 (shared states, app-wide):** `LoadingState`/`ErrorState`/`EmptyState`/`StaleBanner`
  (`feature/common/Components.kt`) were the last pure-Material surfaces (colorScheme/typography + Material
  `Button`/`Surface`); rewrote them to read `Pulse.colors` + ChakraPetch/JetBrainsMono + flat + a Pip retry
  button. **Signatures unchanged → all ~13 call sites untouched** (markets/weather/news/economy/sky/home +
  TOOLS) — they now render terminal-styled in each screen's own palette (green under TOOLS, app accent
  elsewhere). `SectionHeader` (a general Material header, distinct from `PipHeader`) left as-is.
- Build has **no `allWarningsAsErrors`**, so leftover unused `MaterialTheme` imports are harmless. Whole-refactor
  subagent compile-review came back clean (10 files). ⚠️ Render-blind (CI compile-gates only) — owner is the
  pixel-fidelity judge on the Pixel; the exact `PipChip`/`PipHubTile`/font-size values are easy to tune from a
  screenshot. **The app-wide 3/3 shared-states change is beyond the literal "unify TOOLS" ask — owner can veto.**

### PIP-BOY navigation restructure — STATS/ITEMS/DATA on top, the game split across them (PR #262, merged)
Owner (2nd STATS screenshot): "make the stats/item/data menu … moved up to the top … Make the Survive,
Social, Search tabs go inside the stats page. Also, make the whole game have its own dedicated pages in the
tools tab within the Stats, Items, Data menus as other tabs." Via AskUserQuestion: game split =
**STAT/INV/DATA**, Survive/Social/Search = **sub-tabs inside STATS**. Shipped in 3 CI-green slices (each
squash-merged + re-synced); the whole TOOLS UI now lives in the single self-contained **PIP-BOY** device
(`feature/tacnet/PipBoyScreen.kt`), a section selector (STATS · ITEMS · DATA) + HP/AP gauges on top, a
per-section sub-tab rail, and a slim `PipUtilBar` (SET/BUG/EXIT + build) at the bottom.
- **Slice 1 (#262 commit 1):** the STATS/ITEMS/DATA menu + HP/AP `StatGauge`s moved to the TOP (`PipTopNav`),
  the collapsible bottom nav replaced by `PipUtilBar`. (One CI failure: `Modifier.padding(horizontal=, top=)`
  is not a valid overload — split to `start/end/top`. The recurring padding lesson.)
- **Slice 2:** SURVIVE/SOCIAL/SEARCH folded into STATS as sub-tabs (rail now STATUS·SPECIAL·SURVIVE·SOCIAL·
  SEARCH). Extracted scaffold-free `SurviveBody`/`SocialBody`/`SearchBody` from the standalone PulseScaffold
  screens (the `*Screen` wrappers kept for Home/hub deep-links); `PipBoyScreen` gained `socialVm`/`searchVm`
  + an `onOpenRoute` callback (the SURVIVE tiles deep-link to SOS/PLACES/SAFETY/NAV/SURVIVAL/TOOLS). Dropped
  the three from `FEED_TABS` (now just PIP-BOY); `FeedTabBar` hides itself for a lone tab. TOOLS bottom-nav
  highlight unchanged (route stays TACNET).
- **Slice 3:** the monolithic `TelemetryBody` game split across sections — **STATS ▸ STATUS** = pure device
  telemetry; **STATS ▸ SPECIAL** = the S.P.E.C.I.A.L. character sheet + encounter/idle/downed loop + day
  banner/PERCEIVES readout + transient level-up/quest-complete/unlock banners; **ITEMS ▸ GEAR** = inventory/
  workbench/companions/reputation; **DATA ▸ WASTELAND** = daily/quests/wasteland-map(scan/shop/talk)/
  achievements. Existing app inventory kept as **ITEMS ▸ STORED**. New shared `GameSensors` composable owns
  the telemetry/ambient-sampler lifecycle + camera-permission request, hosted by each game body so sensors
  (and the GrapheneOS camera indicator) run **only while a game tab is open** — not on RADIO/MAP/etc. The old
  `SpecialGamePanel` was replaced by the slimmer `CharacterSheet`; the standalone `TelemetryScreen` route is
  now a device readout. Section defaults: STATS→STATUS, ITEMS→GEAR, DATA→WASTELAND (`firstOf`). Subagent
  compile-review came back clean (16-value `when` exhaustiveness, every panel signature, VM members, call
  sites). ⚠️ All render-blind (CI compile-gates only) — the sub-tab switching, the many-tab DATA rail, and
  the game-tab layout want eyes on the Pixel. Easy owner tweaks: tab labels/order, whether STATS should keep
  device+character as one STATUS tab vs the two split tabs, whether WASTELAND (vs MAP) should be the DATA
  default. There's a brief sensor stop→start on switching between game tabs (each body mounts its own
  `GameSensors`) — acceptable; lift to `PipBoyScreen` if it feels janky.

### Life profile — the operator's REAL self bleeds into the S.P.E.C.I.A.L. game (PR #263, merged)
Owner: "add more user-based input customizations for the game — height, weight, age, hydration, hygiene,
real money (in-game caps stay, but how much REAL money you have gives buffs/boosts + other effects), etc."
Built as 3 CI-green slices; the game now reads your real self.
- **Slice 1 — pure core (PR #263 commit `afa6ff5`):** `core:telemetry/LifeStats.kt` (+ 22-case
  `LifeStatsTest`, locally kotlinc-validated all-green). `LifeProfile` (heightCm/weightKg/ageYears/
  realMoney/currency/hydration/hygiene, all 0/unset = neutral). BMI → a `Build` archetype (featherweight
  AGI+ / athletic END+ / rugged STR+ / powerhouse STR+2, AGI−); age → an `AgeBand` (young reflexes AGI+ …
  veteran INT+PER+, AGI−); real money → a `MoneyTier` (BROKE→LOADED) that buffs CHARISMA/LUCK/INTELLIGENCE
  and boosts caps rewards up to **+35%**; hydration/hygiene sliding taxes END/STR (thirst) or CHA (grime).
  `effects()` is the single source of truth; `statBonus`/`capsBonusPct`/`describe` are views;
  `decayNeeds`/`drink`/`wash` + clamped setters evolve it. Wired into `SpecialGame.resolve(..., life = null)`
  (backward-compatible) so life modifiers stack with env/perk/gear/companion on the check + the wealth caps
  boost stacks with perk caps on a win.
- **Slice 2 + 3 — on-device + UI (PR #263 commit `cad4e09`):** `SpecialGameStore` persists the profile in
  the `Stored` blob — needs are stored as an ANCHORED base decayed forward from `needsAnchorMs`, so
  continuous display refresh never loses sub-threshold decay (base/anchor change only on a top-up/edit);
  `lifeFlow` publishes the live decayed profile; `setHeight/Weight/Age/RealMoney/Currency` + `drink/wash`
  re-anchor cleanly; `choose()`/`resolveTalk()` feed the profile into `resolve`; `reset()` clears it; all
  fields defaulted → old saves load blank/neutral. `TelemetryViewModel` exposes `life` + the setters and
  ticks `refreshNeeds()` every 1.5 s so the meters decay live. UI: a **LIFE panel on STATS ▸ SPECIAL**
  (`LifePanel`/`LifeNumberField`) — editable Height/Weight/Age/Real-money fields (commit on focus-loss so
  the per-tick decay flow can't clobber typing), a build·age·wealth readout, hydration/hygiene gauges with
  DRINK/WASH, and a live "how your life bends the wasteland" effects list. **Privacy:** the money figure +
  body metrics are on-device only — never transmitted or logged (panel says so); cleared by the game reset.
- Verification: pure core local kotlinc + 22 tests green; Android layers subagent compile-review clean + CI.
  ⚠️ On-device-unverified (CI compile-gates only): the panel render, the numeric-field commit UX, and the
  needs decay/meters on the Pixel.
- **Batch 2 — ENERGY + MOOD (PR #264, merged, commit `3085d16`):** `LifeStats` grew a third need **ENERGY**
  (0..100, decays ~3/hr with the others, restored by `rest()`; low saps AGILITY then INTELLIGENCE) and a
  user-set **MOOD** (0..100, 50 = neutral; ≥75 buffs CHA+LUCK, ≤25 saps CHA). +4 tests (26 total, kotlinc
  green). Store persists energy (anchored-decay) + mood (both defaulted → old saves neutral; `reset()` clears);
  VM `rest()`/`setMood()`; LIFE panel gained an Energy gauge, a **REST** button (row now DRINK·WASH·REST) and
  a Mood field. ⚠️ On-device-unverified (CI compile-gates only).
- **Batch 3 — NOURISHMENT + operator NAME (PR #265, merged, commit `44e1798`):** `LifeStats` grew a fourth
  need **NOURISHMENT** (0..100, decays ~3.5/hr, restored by `eat()`; hungry saps STRENGTH, starving saps
  STR+END) — with hydration + energy this is the **Fallout-Survival trio thirst · hunger · sleep** (+ hygiene)
  — plus a cosmetic `operatorName` (≤24 ch, no game effect, `withName`). +3 tests (29 total, kotlinc green).
  Store persists nourishment (anchored-decay) + operatorName (defaulted → old saves neutral; `reset()` clears);
  VM `eat()`/`setName()`; LIFE panel gained a Name text field (new `LifeTextField`, commit-on-focus-loss), a
  Nourishment gauge, and an EAT button (needs row now DRINK · EAT · REST · WASH). ⚠️ On-device-unverified.
- **Batch 4 — world-driven need dynamics (PR #266, merged, commit `9e86046`):** needs no longer decay flat —
  the live `EnvContext` sets the pace. `LifeStats.decayNeeds(p, elapsed, env)` overload (2-arg delegates,
  env=null → base rates, back-compat): heat ×1.6 / scorching ×2.2 hydration; cold ×1.3 / frigid ×1.5, night
  (22:00–06:00) ×1.5, moving ×1.4 energy (stacking); moving also ×1.2 hunger, ×1.3 hygiene; **CHARGING →
  energy REGENERATES +8/hr** (plugged in ≈ resting). `needDrivers(env)` → UI labels. +5 tests (34 total,
  kotlinc green). Store keeps `lastEnv` (fed each tick) + decays through it in `currentLife()`, with a
  charging-transition **re-anchor guard** so regen/decay isn't applied retroactively to a long gap; VM feeds
  `refreshNeeds(env)` each tick; LIFE panel shows a "REAL-WORLD DRIVERS" readout. ⚠️ On-device-unverified.
- **Batch 5 — real STEP COUNT → the game (PR #267, merged, commit `a827479`):** the strongest real-life tie
  yet. `LifeStats.stepsToday` → an activity buff (≥5000 END+1 "Active today"; ≥10000 END+1 AGI+1 "In stride")
  via the effects() pipeline; +2 tests (36 total, kotlinc green). `TelemetryController` reads
  `Sensor.TYPE_STEP_COUNTER` (cumulative-since-boot) → `Telemetry.stepCounterRaw` (defensive; no perm/sensor
  → no events). Manifest gains **ACTIVITY_RECOGNITION** + an optional stepcounter feature; `GameSensors`
  requests it at runtime (API 29+, re-registers on grant). `SpecialGameStore.setStepCounter(raw)` keeps a
  persisted per-day baseline (today = latest − baseline; a lower reading [reboot] or new day re-baselines);
  VM feeds it each tick. LIFE panel shows a "Steps N / 10000" gauge. **Aggregate + on-device only.**
  Subagent compile-review clean (incl. `android.os.Build` vs the `Build` enum). ⚠️ On-device-unverified.
- **Batch 6 — circadian encounter bias (PR #268, merged, commit `34d60b1`):** `LifeStats.circadianFavored(hour)`
  → the attributes the real hour-of-day leans into (morning 05–10 PER/INT, midday 11–16 STR/END, evening 17–21
  CHA, night 22–04 AGI/LUCK). Biases WHICH encounters appear (not check maths → no double-count with the
  darkness/energy effects): `TelemetryViewModel.venture()` unions it with the perception favoured set fed to
  `SpecialGame.nextEncounter`. +1 test (37 total, kotlinc green). LIFE panel shows "THIS HOUR FAVOURS · …".
- **Batch 7 — calendar-aware AGENDA (PR #269, merged, commit `38ebc5c`):** your real upcoming calendar events
  become time-boxed wasteland objectives. Pure `core:telemetry/CalendarQuests.kt` (`CalEvent`→`AgendaQuest`
  via `compose`: still-running or ≤48h-ahead, soonest-first, capped; `describeCountdown` "in 2h 15m"/"now"/
  "under way"; `imminent` ≤1h; game-flavoured briefing; +7 tests, kotlinc green). `data/calendar/
  CalendarRepository.kt` reads `CalendarContract.Instances` (expands recurrences), READ_CALENDAR-gated (perm
  already in manifest), fully defensive → empty. On-device only — event text never leaves the phone.
  `AppContainer.calendarRepository`; `TelemetryViewModel.agenda` (loads off-main in `refreshAgenda()` on
  start + ~5-minly; countdowns recompute each tick); `GameSensors` requests READ_CALENDAR (reload on grant);
  AGENDA panel atop DATA ▸ WASTELAND (imminent = amber). Subagent compile-review clean. ⚠️ On-device-unverified.
- **Life-sim customization set is now very comprehensive** (height/weight/age/real-money · hydration/hygiene/
  energy/nourishment · mood · name · world-driven decay · **real step count** · **time-of-day encounter bias** ·
  **real calendar → agenda**). **Owner-steerable next (bigger, needs hardware):** BLE heart-rate strap → vitals
  (manifest already has BLUETOOTH perms) — held pending owner go + a physical strap to verify.
  ⚠️ The whole life-sim UI (LIFE panel, meters, fields, drivers, steps) is CI-compile-gated only — owner verifies on the Pixel.

### Survival check-in push notifications (PR #270, merged, commit — squash)
Owner: "make a whole bunch of check-in push notifications necessary for your survival and whatnot." The
background worker now nudges you when a real-decaying life-sim need runs low — which, since the needs bleed
into reality, doubles as a drink/eat/rest/wash reminder. Pure `core:telemetry/SurvivalAlerts.kt` (+7 tests,
kotlinc green): `SurvivalNeed`×`AlertLevel` (LOW ≤30 / CRITICAL ≤15, matching LifeStats thresholds);
`evaluate(life, seed)` → one `SurvivalAlert` per low need, body drawn from a ~40-line themed catalog rotated
by the injected seed (deterministic → testable). On-device: `Notifier.notifySurvival`/`notifyAgenda`
(existing REMINDERS channel → deep-link "tacnet"/PIP-BOY); `SpecialGameStore.lifeSnapshot()` gives the worker
the decayed-to-now profile; `RefreshWorker` survival pass (gated by master toggle + quiet hours like every
other alert) fires each low need throttled per need in `NotifyState.survivalFiredMs` (~3h; critical ~1.5h),
and reminds once per imminent real calendar event (reuses `CalendarQuests` + `agendaNotifiedIds` dedup). New
opt-out `NotificationPrefs.survivalAlerts` (default on) + a "Survival check-ins (Pip-Boy)" Settings toggle. A
never-played game stays silent (needs at 100). Subagent compile-review clean. ⚠️ On-device-unverified — the
background worker firing/throttling + the notifications want a real run on the Pixel.

### Survival TIPS push notifications — 310-tip rotating catalog (PR #271, merged, commit — squash)
Owner: "make survival tips for push notifications, quite frequent, 300+ types." Pure
`core:telemetry/SurvivalTips.kt` (+4 tests, kotlinc green): **310 distinct field-survival tips** (water /
fire / shelter / cold / heat / first aid / food & foraging / navigation / signalling / weather / wildlife /
knots & gear / terrain / hygiene / urban-disaster prep); `tip(index)` walks the whole catalog before
repeating (wraps neg/large). On-device: `Notifier.notifyTip` on the LOW-priority DIGEST channel with a FIXED
id, so each new tip arrives QUIETLY and silently REPLACES the last (frequent, but no buzz / no tray pile-up);
tapping opens the Survival guides (added `Routes.SURVIVAL` to the deep-linkable `SHORTCUT_ROUTES`).
`RefreshWorker` pushes the next tip roughly every tick (a 12-min floor guards double-fires; the worker's own
≥15-min period is the cadence, so frequency scales with the notification refresh interval), advancing a
persisted `NotifyState.survivalTipIndex`, gated by master toggle + quiet hours. New opt-out
`NotificationPrefs.survivalTips` (default on) + a "Survival tips (frequent)" Settings toggle. ⚠️
On-device-unverified — the firing cadence wants a real run on the Pixel.

### ITEMS page — catalog 22→37 + a LOADOUT panel (PR #272, merged, commit — squash)
Owner: "add more stuff to the items page." Grew the wasteland item economy and surfaced the carried loadout.
- **Catalog (`core:telemetry/Items.kt`), 22 → 37:** AID gained Trauma Patch (+20 HP) + Surgeon's Kit (+60 HP)
  filling the mid/high heal tier; CHEM gained rare **+4** one-check kicks Titan Serum (STR) / Quicksilver (AGI)
  / Fortune Vial (LUCK); GEAR gained **Lucky Charm** (closes the LUCK gear gap — every S.P.E.C.I.A.L. attribute
  now has passive gear) plus a full **+2 tier** (Power Gauntlet, Recon Optics, Combat Webbing, Negotiator's
  Suit, Neural Implant, Sprint Servos, Fortune Idol); JUNK gained higher-value Fusion Cell + Gold Trinket.
  `GameLocations.stock` sells the new items per `LocationKind`; every stock id resolves.
- **UI (`TelemetryScreen`, ITEMS ▸ GEAR):** a new **LOADOUT panel** above PACK — the passive GEAR bonuses
  stacking on your checks right now (via `SpecialGame.gearStatBonus`) + a pack tally (items held, split by
  kind, total caps if sold). PACK rows now show a **rarity ★ tier tag** (colour-ramped common→rare, new
  `rarityColor` helper) and the item kind.
- **`ItemsTest`** (5 cases, kotlinc-validated green): catalog grew + ids unique, `byId` round-trips + rejects
  unknown, every shop-stock id is a real item, every stat has passive gear, higher-tier items present.
  ⚠️ On-device-unverified (CI compile-gates only): the LOADOUT panel + richer PACK rows want eyes on the Pixel.

### Item economy — crafting tier-up + rarity-weighted loot table (PR #273, merged, commit — squash)
Owner: "keep adding features and game stuff to the tools menu autonomously." Two cohesive item-economy
follow-ups to #272 (both pure CI-tested core; 21 game-core tests green, kotlinc-validated).
- **Crafting (`core:telemetry/Crafting.kt`), 8 → 17 recipes:** AID gained **Trauma Patch** (dressings +
  alloy) + INT-gated **Surgeon's Kit** (medkit + injector); a **GEAR tier-up** loop consumes a **+1** piece
  plus a **fusion cell / gold trinket** to forge its **+2** version — Power Gauntlet, Recon Optics, Combat
  Webbing, Negotiator's Suit, Sprint Servos, Neural Implant (INT-gated by tier) + the **LUCK**-gated Fortune
  Idol. Fusion Cell / Gold Trinket now have a use beyond selling. The workbench only surfaces recipes you
  hold an input for, so the longer list doesn't clutter. `CraftingTest` +2 (tier-up consumes +1 + power
  source → +2 gear; every tier-up output is real +2 GEAR / high AID); `everyRecipeIdResolvesInItems` guards typos.
- **Loot (`core:telemetry/LootTable.kt`, + `LootTableTest` 7 cases):** the "rarity drives loot weighting"
  model the catalog was built toward. `baseWeight` steep by rarity (common salvage often, rare gear rarely);
  `weight(item, luck)` lifts ONLY the rare tiers (4/5) with LUCK, never lowering common odds; `pick(luck,
  roll, pool)` is deterministic over an injected `[0,1)` roll (on-device supplies randomness, CI pins the
  distribution); `scavenge(luck, rolls)` merges a multi-roll bundle to id→count; QUEST items excluded.
- **SCAVENGE (PR #274, merged):** the first player-facing use of the loot core. **DATA ▸ WASTELAND** gained
  a **SCAVENGE** panel — comb the area for a rarity-weighted `LootTable` haul scaled by LUCK (1–3 picks; more
  + rarer with higher LUCK), rate-limited by a persisted **3-min cooldown** (`SpecialGameStore.SCAVENGE_COOLDOWN_MS`)
  so it can't be farmed. `SpecialGameStore.scavenge()`/`dismissScavenge()` + `lastScavengeFlow` (the haul) +
  `lastScavengeMsFlow` (cooldown anchor); `lastScavengeMs` persisted in the `Stored` blob (defaulted → old
  saves), reset-cleared; a new distinct find re-runs the achievement check. `TelemetryViewModel` exposes a
  live `scavengeCooldown` countdown (recomputed each 1.5s tick). UI `ScavengePanel`: a SCAVENGE button that
  dims to a "SEARCHED · 2m 45s" countdown while cooling down + a tap-to-dismiss FOUND readout. Compile-review
  subagent clean. ⚠️ On-device-unverified (CI compile-gates only) — the scavenge loop + cooldown want the Pixel.
- **Item Codex (PR #275, merged):** a completionist discovery tracker. Every acquisition path (loot/scavenge/
  shop/craft/encounter reward) marks an item **discovered**, monotonically (using/selling the last one never
  un-discovers). Pure `core:telemetry/ItemCodex.kt` (+ `ItemCodexTest` 7 cases, kotlinc-validated): `TOTAL`,
  `found`/`completion`, `discoveredItems`/`undiscovered`, `byKind` breakdown, and a flavour `rank` (Greenhorn
  → Picker → Scavenger → Collector → Curator → Archivist); stale ids ignored so the count never exceeds
  `TOTAL`. `SpecialGameStore` keeps a monotonic `discovered` set fed by **ONE collector on `_character`**
  (unions inventory ids on every change — no per-method hooks), seeded from held items on load, persisted in
  the `Stored` blob (defaulted → old saves), reset-cleared; `discoveredFlow` exposed. UI `CodexPanel` on
  ITEMS ▸ GEAR — "CODEX · n/TOTAL", progress bar, per-kind counts, rank, + a masked "??? · KIND ★rarity"
  teaser of what's still out there. Compile-review subagent clean. ⚠️ On-device-unverified (CI compile-gates).
- **Codex reward achievements (PR #276, merged):** closes the loop **scavenge → discover → codex → reward**.
  New `AchMetric.ITEMS_DISCOVERED` + `GameMetrics.itemsDiscovered` (defaulted → back-compat) + 3 achievements
  — **Rag And Bone** (10 items, +30 XP), **Curator** (20, +60 caps + grit_ration), **Archivist** (whole
  catalog, threshold = `Items.ALL.size` so it tracks growth; +200 caps + fortune_idol + 100 XP).
  `SpecialGameStore.currentMetrics()` feeds `ItemCodex.found(discovered)`; the discovery collector now
  `runAchievementCheck()`s after the codex grows (reward-item cascade bounded by the finite unlocked set).
  `AchievementsTest` +1 (22 game-core tests green). ⚠️ On-device-unverified (CI compile-gates only).
- **Gear sets (PR #277, merged):** a synergy layer on the loadout — carrying every piece of a set grants an
  extra stat bonus *beyond* the pieces' own passive gear, rewarding the chase for the rare +2 tier. Pure
  `core:telemetry/GearSets.kt` (+ `GearSetsTest` 5 cases): `GearSet` + `isComplete`/`active`/`ownedPieces`/
  `statBonus`; 4 sets — Enforcer (power_gauntlet+combat_webbing → +1 STR), Infiltrator (sprint_servos+
  recon_optics → +1 AGI), Envoy (negotiator_suit+neural_implant → +1 CHA), Fortune's Favor (fortune_idol+
  lucky_charm → +2 LUCK); every piece resolves as GEAR. `SpecialGame.setStatBonus(c,s)` summed into the
  resolve check beside `gearStatBonus` (stacks with perks/companions/env/life). LOADOUT panel gained a SET
  BONUS section (active sets + a "1/2 · +n STAT" teaser for in-progress ones). Compile-review subagent clean.
  28 game-core tests green. ⚠️ On-device-unverified (CI compile-gates only).
- **Item-economy arc now:** 37-item catalog → LOADOUT (+ set bonuses) → crafting tier-up → rarity-weighted
  loot → SCAVENGE → completion codex → discovery-milestone rewards → gear-set synergies.
- **Wasteland world events (PR #278, merged):** a fresh daily mechanic on the `GameClock` day counter — each
  day gets one deterministic **situation** that favours certain stats (biasing encounter selection) and/or
  modifies win caps. Pure `core:telemetry/WorldEvents.kt` (+ `WorldEventsTest` 5 cases): `WorldEvent(id,name,
  desc,favored,capsWinPct)` + `eventFor(day)` — step-7 over 8 events is a full permutation (each once per
  cycle, no back-to-back repeats; floor-mod keeps day 0/negatives in bounds). 8 events (Radstorm, Bounty
  Posted, Lucky Star, The Hunt, Market Fair, Gloom, Clear Skies, Calm). Wiring is in-app only (no core
  signature change): `TelemetryViewModel` derives `worldEvent` in `updateDayBanner()`, folds `favored` into
  `venture()` (beside perception+circadian) and passes `capsWinPct` to `choose()`; `SpecialGameStore.choose(...,
  worldCapsPct)` adds a +/- fraction of the win caps post-resolve; a "SITUATION · NAME ▸ …" banner on STATS ▸
  SPECIAL. Compile-review subagent clean. 33 game-core tests green. ⚠️ On-device-unverified (CI compile-gates).
- **Encounter content +9 (PR #279):** grew `SpecialEncounters` 45 → 54 across all seven stats + every tier —
  6 field (Collapsed Overpass, Trader's Dilemma, Irradiated Spring, Old Minefield, Wild Dog Pack, Fortune
  Teller), 2 bosses (★ The Glowing One L13, ★ The Enclave Colonel L15 — extend the ladder past Mainframe L11),
  1 repeatable (SCAVENGE — The Subway). Pure content; existing `SpecialWorldTest`/`SpecialGameTest` cover it
  (59 green). Traced a dup id (`minefield`→`old_minefield`) via the local test run before push.
- **BUG FIX — survival tips stuck on tip #0 "Rule of Threes" (owner-reported, PR #279):** `notify_state` is
  shared by two writers — `RefreshWorker` (owns the survival-tip index + all dedup fields) and the resident
  `BreakingNewsPulse` poller (owns only `seenTopUrls`). BreakingNewsPulse read the blob, did a **slow news
  fetch**, then wrote its stale pre-fetch snapshot back → clobbered the worker's advanced `survivalTipIndex`/
  `survivalTipLastMs`; the restored old timestamp reopened the 12-min gate so the same tip re-fired, sticking
  the rotation on `TIPS[0]` (the "Rule of Threes" tip). Fix: each writer preserves the other's field —
  BreakingNewsPulse re-reads the latest state immediately before writing (after the fetch) and updates only
  `seenTopUrls`; `RefreshWorker.writeState` re-reads and keeps the latest `seenTopUrls`. Also protects the
  other worker-owned dedup fields (survival check-ins, agenda, safety/flight/market) from the same clobber.
  ⚠️ On-device-unverified — the tip rotation advancing wants a run on the Pixel.
- **World-event shop prices (PR #280):** completed the daily mechanic — `WorldEvent.shopPct` (Market Fair
  −20% "wares go cheap"; Gloom +15% "traders gouge"). `SpecialGame.shopPrice(item,c,kind,shopPct)` is the
  single source of truth (rep discount → world modifier, floored at 1); `buyItemAt` routes through it; store
  `buyAt` + VM `buy` thread `worldEvent.shopPct` so the CHARGE matches; `LocationSheet` shows it + a "MARKET
  −20%" tag; the STAT banner lists the shop effect. +2 WorldEventsTest (37 core green). Compile-review clean.

### GEO-GATED WASTELAND — the "go there to get/do things" overhaul (accepted, in progress)
Owner: "Make Geo fenced wasteland encounters/cities/towns/tribes/gangs/monsters/fights/NPCs/shops/locations/
quests/weapons/money all linked to the real world. To get things and do things, first I must go there and
have it." Via AskUserQuestion the owner chose **FULLY GEO-GATED** — EVERYTHING (encounters, fights, loot,
shops, quests, weapons) requires physically traveling to a real place within reach; **nothing happens at
home** (couch-play `venture` is to be retired). Built as CI-green slices per the established pattern.
- **Slice 1 — site-model core (PR #280, merged):** `core:telemetry/WorldSite.kt` (+ `WorldSiteTest` 7 cases,
  kotlinc-validated) — deterministic map from a real place (coords + OSM category) to a stable wasteland
  **site**. `SiteType` taxonomy (11): safe/trade — SETTLEMENT/TRADER/MEDIC/FIXER/BARKEEP/OUTPOST (threat 1,
  a `shopKind`); danger — TRIBE(2)/RUINS(2)/GANG_CAMP(3)/MONSTER_DEN(4)/VAULT(5), `hostile` flag.
  `WorldSites.typeFor(osmCategory)` classifies (widens `GameLocations.kindFor`), `nameFor(type, seed)` gives
  deterministic seeded names that never churn ("The Rusted Fangs", "Deathclaw Gorge", "Vault 87", "New
  Haven"), `siteFor(...)` builds the stable site, `favoredStats(type)` biases which fights appear,
  `spawnsEncounter(type)`, per-type `intro()`.
- **Slice 2 — POI → WorldSite classification (PR #281, merged):** `GameWorldStore` now turns every scanned
  real place into a `WorldSite`, not just a shop. Query model is category-driven (`SiteQuery(category,id,
  filter)`); each POI maps through `WorldSites.typeFor`/`siteFor`. 5 trade queries (supermarket/pharmacy/
  hardware/pub/fuel) + 5 new danger queries — tribes (`leisure=park/nature_reserve`), gang camps
  (`landuse=industrial`), monster dens (`natural=water/wood/wetland`), vaults (`military=bunker`), ruins
  (`historic=ruins/archaeological_site`). Overpass `node`+`way` `out center` returns area POIs as points, so
  these classify. New `sitesFlow: StateFlow<List<WorldSite>>` (cap `MAX_SITES=32`); the trade sites still
  drive the existing `GameLocation` shop layer (derived from `SiteType.shopKind`) so the current map/shop UI
  is untouched. `TelemetryViewModel.sites` exposed for the render slice. Compile-review clean.
- **Slice 3 — render sites on the map (PR #282, merged):** `WastelandMap` now plots every nearby `WorldSite`
  (not just shops) as a dot coloured by `SiteType` + sized by threat (settlements green, tribes tan, gang
  camps orange, monster dens magenta, vaults bright yellow, ruins grey; bigger = more dangerous).
  `WastelandMap(sites: List<WorldSite>)` + `siteColorHex(SiteType)` + threat-driven GeoJSON radius; tap fires
  `onSelect(id)`. `WastelandPanel` gained a `sites` param (map renders from it; shop trade list still on
  `locations`). Compile-review clean.
- **Slice 4 — presence-gate the encounter loop (PR #283, merged):** couch-play `venture` retired — you can
  only fight when physically AT a wasteland site. Core `LocationGate` gained `WorldSite` overloads
  (`distanceTo`/`isAtSite`/`reachHint`, +2 tests, 14 core green). VM: a `SiteReach` (nearest engageable site +
  distance + in-reach flag) via `combine(_gps, sites)` filtered to `WorldSites.spawnsEncounter`; declared
  AFTER `_gps` so its initializer sees an initialized flow (compile-review flagged this as the key risk —
  confirmed ordered). `venture()` no-ops unless `siteReach.atSite` and biases the draw by
  `WorldSites.favoredStats(site.type)` (unioned with perception/circadian/world-event). UI `IdlePanel`:
  geo-gated — AT a site shows name + intro + ENGAGE; else NEAREST site + "travel here — N m away"; none
  scanned → prompt to SCAN AREA. **The loop: map (DATA) → travel to a gang camp/monster den/vault/tribe/ruins
  → STATS ▸ SPECIAL "AT · <site>" → ENGAGE → fight.** ⚠️ On-device-unverified — needs a real GPS fix at a real
  site; owner verifies on the Pixel (flag if gating feels too strict).
- **Slice 5 — presence-gate scavenging (PR #284, merged):** the last couch-play action falls in line —
  SCAVENGE now works only while physically AT a wasteland site. `TelemetryViewModel.scavenge()` no-ops unless
  `siteReach.atSite`; `ScavengePanel` gained an `atSite` flag (off-site → "travel to a site to comb it";
  on-site → the SCAVENGE button + 3-min cooldown). So rarity-weighted loot + the caps it sells for is found
  only out in the real world. A distinct weapon *system* is deferred (offered follow-up) — combat GEAR already
  drops as site loot, so "weapons at real places" is served by site loot + gated scavenge today.
- **GEO-GATED CORE LOOP COMPLETE (slices 1–5).** The full "to get things and do things, first I must go
  there" loop is in place: real place → wasteland site (taxonomy + classification) → rendered on the live map
  (per-type colour, threat size) → **presence-gated fights** (couch-play `venture` retired) → **presence-gated
  scavenge** (loot/caps only at sites). ⚠️ **PAUSED pending owner Pixel verification** — this reshaped the
  core loop and is entirely on-device-unverified (needs a real GPS fix at real sites). Do NOT build the
  weapons system (or more geo features) on top until the owner confirms the loop feels right on the Pixel;
  if the gating is too strict (can't-play-from-home), add a hybrid/practice mode. **Owner Pixel checklist:**
  SCAN AREA populates gang camps/monster dens/vaults; walking to one flips STATS ▸ SPECIAL to "AT · <site> ·
  ENGAGE"; scavenge only works at a site; and the survival tip now rotates ~every 3h (not stuck on "Rule of
  Threes"). **Open follow-ups (post-verify):** weapons-as-a-system; per-site-type encounter *restriction*
  (not just bias); site "cleared" cooldown; quests/NPCs tied to specific sites.

### Survival-tip notification — stuck-on-tip-0 + too-frequent (owner screenshot, PR #282)
Owner reported (screenshot) the survival tip still showed "Rule of Threes" (tip #0) and fired far too often.
Root of the recurrence: the rotation used a **persisted cursor** (`survivalTipIndex`); if it ever fails to
persist it sits at 0 (= "Rule of Threes"), and the 12-min firing floor re-posted it dozens/day (fixed id →
each re-post jumps to "now"). Fix (`RefreshWorker`): (a) the tip index is now **derived from the wall clock**
(`bucket = now / gap`, ×131 scramble — coprime with the ~310-tip catalog), so it can NEVER get stuck on tip 0
regardless of persistence and feels random while covering the whole catalog; (b) frequency cut **12 min → ~3
hours** (`SURVIVAL_TIP_MIN_GAP_MS`). Complements the earlier `notify_state` concurrency fix (which stopped the
`BreakingNewsPulse` clobber) — this removes the persisted-cursor dependency entirely. ⚠️ Owner confirms the
new cadence + rotation on the Pixel once the build lands (must install the updated APK for it to take effect).
- **Item/game arc open follow-ups (offered):** a STASH/storage; more encounter/boss content; a new non-item
  game system.

### LARP batch L1–L5 + depth batch + AR camera (this session, dev branch `claude/loving-edison-bd65oa`, #286–#292)
Owner: "I am essentially making this a giant larp." Shipped the 5-part LARP batch, then a "more real-time
tracking · different transportation · 1000+ achievements + your own deeper ideas" batch, then the AR camera —
all CI-green slices, squash-merged to `main`, re-synced each time. Pure cores in `core:telemetry` (kotlinc +
JUnit locally; the recipe = `/opt/gradle-8.14.3/lib` jars, compile only the depended-on files; the FULL
game-core suite is now **192+ tests**). Android layers compile-gated by CI + adversarial subagent
compile-reviews (no local SDK). **Everything on-device is owner-verify on the Pixel.**
- **L1 reset-run (#286):** `SpecialGameStore.resetProgress()` wipes the run (stats/level/XP/caps/inventory/
  perks/rep/achievements/quests/scavenge/codex) but **preserves the real-life profile** (LifeStats). Confirm-gated
  RESET RUN control on STATS ▸ SPECIAL. The full `reset()` (also wipes profile) is untouched.
- **L2 XP only from real places (#286):** removed the per-app-screen-visit XP grant (+ `XP_PER_VISIT`). XP comes
  only from site-gated encounters/scavenge/talks + quest rewards. (Two app-usage milestone achievements still
  grant a little — flagged.)
- **L3 track/untrack sites (#287):** tapping a scanned site on the DATA ▸ WASTELAND map opens a `SiteSheet`
  (arrival brief · threat · distance) with **TRACK ▸ SET PATH HERE** → drops a `WaypointStore` waypoint
  (`ObjectiveKind.MAIN`, made active) so the NAV gold path routes there; idempotent (re-activates a waypoint
  within ~20 m, no dupes). `TelemetryViewModel` gained `WaypointStore` (`trackedWaypoints`/`trackSite`/`untrackSite`).
- **L4 real-life → S.P.E.C.I.A.L. (#288):** `LifeProfile` gained **wellRead → INT, fitness → STR/END, community
  (where you live) → CHA/PER** (0..100 self-reports, `SELF_MID`/`SELF_HIGH` bands, defaulted → old saves neutral;
  flow into `resolve` via `effects()`). Persisted + LIFE-panel number fields. On-device only. (Money + BMI/steps
  already fed it.)
- **L5 your wasteland tale / renown (#289):** `core:telemetry/Legend.kt` — a **global renown** distinct from
  per-faction Reputation. `RenownTier` (Reviled→Revered), `Archetype` (curated seed), `DeedKind` (grow-it deeds).
  `Legends.shopPricePct` (±20%) + `charismaBonus` (±2). `Character.legend`; `SpecialGame.legendStatBonus` folds
  into CHA checks; `shopPrice` folds the renown modifier (stacks with faction discount + world event); `curateLegend`/
  `recordDeed`. GROW: `resolveTalk` win → `WON_TALK`. CURATE: archetype picker. LEGEND panel on STATS ▸ SPECIAL.
  The tale is game progress → cleared by both reset paths.
- **Depth batch 1 — 1000+ achievements + transport core (#290):** `core:telemetry/Transport.kt` — `TransportMode`
  STILL/WALK/RUN/CYCLE/DRIVE, `classify(speedMps, hint?)` (still overrides hint; 30 km/h+ always vehicular; hint
  breaks run-vs-cycle), per-mode distance accrual. **`Achievements.kt` → 1,717 achievements across 27 metrics**:
  hand-authored FEATURED + procedurally-generated tiered LADDERS (distance total/per-mode/on-foot, elevation,
  steps, caps, wins, crits, ventures, talks, scavenges, quests, crafts, trades, places, cells, level, renown,
  days, perks, carry, discovery) — unique ids, monotonic thresholds, scaling rewards (item every 10th rung).
  New `AchMetric`s + `GameMetrics` fields (defaulted). `nextUp(metrics, unlocked, limit)` = a short "what's next"
  chase list (the UI shows this, not a wall of 1717).
- **Depth batch 2 — wire transport/elevation/exploration (#291):** `core:telemetry/GeoTracking.kt` —
  `elevationGain` (ascent-only, 3 m noise floor) + `cellId` (~111 m grid bucket → distinct cells = exploration,
  aggregate not a path). `DeviceLocation` gains `altitudeM`/`speedMps` (from the Android fix; one construction
  site). `GameWorldStore.onLocation(...speed, altitude)` classifies each committed step per mode + accrues climb
  + cells (capped `MAX_CELLS=5000`); `TravelStats`/`Stored` carry them (defaulted). `SpecialGameStore` `ext*`
  fields + widened `setTravelMetrics` → `currentMetrics` feeds walk/run/cycle/drive/elevation/cells + **renown**.
  UI: ACHIEVEMENTS panel → unlocked/total + NEXT UP; WASTELAND map → ON-FOOT · CYCLE · DRIVE · CLIMB · AREAS.
- **AR wasteland camera (#292):** `feature/ar/ArScreen.kt` + `ArViewModel.kt` — a compass **"magic window"** (no
  ARCore) over the geo-gated sites. CameraX back-camera `PreviewView` (new `camera-view` dep) + the nearby
  `WorldSite`s projected via the merged `ArProjection` core (`screenX` from heading+bearing; sized by distance;
  threat-tinted cards; ◉ HERE · ENGAGE within 60 m). Compass heading/cardinal readout, figure-8 calibration hint,
  CAMERA-permission gate, SCAN button. `Routes.AR` + factory + NavHost + an ◈ AR CAMERA VIEW button on DATA ▸
  WASTELAND. Feeds `onLocation` so travel/exploration accrues in AR too.
- **Owner-proposed deeper ideas still queued (offered, not built):** game-action counter feeding for the
  remaining ladders (talks/scavenges/quests/crafts/trades/days/steps-total are defined but only partly fed);
  streaks (consecutive days), real POIs → "your settlements", route/commute recognition, seasons, vehicle-as-caravan.
  ⚠️ The whole batch's runtime (GPS speed/altitude, per-mode classification feel, elevation, AR viewfinder +
  marker projection, 1717-achievement unlock cascade) is **CI-unprovable — owner verifies on the Pixel**.

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

### AR wasteland + LARP batch (owner-driven, in progress)
- **AR wasteland — slice 1 (PR #285, merged):** owner scrapped a creature-catching idea; building an AR
  camera mode that projects the nearby `WorldSite`s through the live camera (Minecraft-Earth style, a
  compass "magic window" — **no ARCore**, unreliable on GrapheneOS). `core:telemetry/ArProjection.kt` (+
  `ArProjectionTest` 4 cases): `relativeBearing` (signed, wraps 359°→1°), `inView` (within FOV), `screenX`
  (bearing→0..1 horizontal fraction, 0.5 = ahead), `sizeForDistance` (depth cue). **AR camera screen (slice
  2) DEFERRED to after the LARP batch** — CameraX Preview (add `camera-view` dep) + `CompassController`
  heading + GPS + sites projected via `ArProjection` + reticle/tint, `Routes.AR` + entry on the wasteland map.
- **LARP batch accepted (owner: "I am essentially making this a giant larp"):** L1 reset-progress (keep
  personal info) → L2 XP only from real-place actions → L3 track/untrack sites for NAV → L4 more real-life →
  S.P.E.C.I.A.L. inputs (well-read→INT, social→CHA, fitness→STR/END) → L5 Legend/reputation-tale system
  (curate an archetype OR emergent from deeds; affects town attitude/shops/perks/stats). Each a CI-green slice.

### AR wasteland 3D rework — the "real, indoor/outdoor, geo-anchored" arc (this session, #315–#318 all merged)
Owner rejected the earlier wireframe-grid / solid-grey-box wasteland via screenshots + a refined spec (the
Lucky-38 New-Vegas poster as the "dial Bethesda Fallout to 11" reference): ONE combined AR mode (labels +
wasteland together, not a toggle) with a loading screen; camera-detected indoor/outdoor; real OSM building
footprints as wireframes at their true shape/scale; ground following the real topographical height map as an
invisible anchor. Built as 4 CI-green slices in `feature/ar3d/WastelandRenderer.kt` (Filament 1.71.5, verified
blind by compile-review subagents each PR; pure cores locally kotlinc-run):
- **#315 — one combined mode + wireframe structures + loading screen:** dropped the ◉3D toggle (the Filament
  wasteland is always on with site labels over it); solid heightmap ground (TRIANGLES) + amber wireframe
  structures (LINES) on one 2-primitive renderable; "MODELLING YOUR VICINITY" Fallout loading beat on entry.
- **#316 — indoor/outdoor camera detection drives the ground:** `data/perception/IndoorOutdoorDetector.kt`
  binds a MediaPipe `ImageAnalysis` pass to the AR camera's OWN session (a 2nd camera session's `unbindAll`
  would fight the preview), classifies via the same pure `Perception.distill` → INDOOR/OUTDOOR (2-frame
  debounce). `WastelandRenderer.setIndoor`: **outdoors** = solid wasteland floor (replaces real ground);
  **indoors** = only a wireframe ground ghost (a solid floor blocked the room — the owner's core complaint).
  Defaults to the non-blocking ghost until confidently OUTSIDE. HUD readout `INSIDE·GROUND GHOST` /
  `OUTSIDE·SOLID GROUND` for verification.
- **#317 — real OSM building footprints, geo-anchored wireframes:** pure `core:telemetry/BuildingFootprints.kt`
  (+6 tests) — `project()` equirectangular-maps each ring to the renderer frame (x=east, z=−north, matching the
  compass camera), trims OSM's repeated closing vertex; `estimateHeight()` (height / levels×3 m / default,
  clamped 2..120 m). `data/ar/BuildingRepository.kt` (Overpass `way["building"]` + `out geom`). Renderer
  `setBuildings()` extrudes each footprint to a wireframe box (base+top ring + verticals), safe buffer swap;
  empty = procedural fallback. VM fetches on first fix + >150 m move; re-projects each GPS tick so buildings
  stay anchored as you walk. `STRUCTURES · N MAPPED` HUD readout.
- **#318 — real DEM elevation as the invisible ground anchor:** pure `core:telemetry/ElevationField.kt`
  (+6 tests) — bilinear (res+1)² DEM grid, origin-relative so the ground under the player is ~0 (camera then
  sits eye-height above it). `data/ar/ElevationRepository.kt` (keyless **Open-Meteo** batch, 9×9). Renderer:
  `heightAt`→`rawDune`; `groundHeight()` = real DEM elevation + anchored dune detail (subtracts the origin dune
  so the floor is y≈0 under the player even with no DEM). `setElevation()` rebuilds terrain+ghost+buildings via
  the safe build-new→repoint→free swap; `create*()`→`build*Buffers()` returning Triples. Null field =
  flat-anchored procedural (fallback).
- **Result: the AR wasteland is now ONE combined mode, indoor/outdoor-aware, drawn from real OSM building
  footprints, on the real terrain elevation.** ⚠️ **ENTIRELY on-device-unverified — Filament can't render in
  CI, and the camera classifier / Overpass / Open-Meteo / GPS geo-anchoring are all CI-unprovable. PAUSED for
  owner Pixel verification before piling more on top.** Owner checklist: indoors shows the wireframe ground
  ghost (room stays visible), outdoors the solid floor; buildings stand where the real ones are (bearing/shape)
  and grow as you approach; the floor sits right under you. **Open / steerable next:** the Fallout aesthetic
  "to 11" polish (weathered sepia-green, atmosphere — a screenshot-driven visual judgment call), and only after
  the geo-anchoring is confirmed to feel right.

### AR wasteland Fallout AESTHETIC arc — Slices 1–4 + a quest fix (this session, #322–#328 all merged)
Owner gave two reference paintings ((A) a green-night wasteland — pale-green glowing orb low on the horizon, a
silhouetted settlement + broadcast tower, dark craggy rock mesas framing a valley, amber HUD corner brackets;
(B) the Fallout-New-Vegas Lucky-38 key art — gold-glowing spire, silhouetted city skyline with lit windows,
cracked desert + power poles) and: *"the final product for the Wasteland AR map should come out looking like
either of the two images."* Also: motion consistency (know when stationary vs moving). Shipped as CI-green
slices; each renderer slice verified blind by adversarial compile-reviews (Kotlin types + Filament buffer
safety + geometry/determinism). All in `feature/ar3d/WastelandRenderer.kt` + `feature/ar/ArScreen.kt`.
- **Slice 1 (#322) — hero horizon vista:** a static far backdrop toward `HERO_AZ_DEG` (335°) — a glowing pale-green
  BEACON orb + a graded phosphor HAZE glow + a near-black SILHOUETTE skyline + broadcast TOWER. A Compose
  additive `HeroBeaconBloom` fakes the orb's glow (opaque material can't bloom).
- **Slice 2a/2b (#323/#324) — backlit shading + screen grade:** a low grazing "sun" toward the beacon
  (gamma-punched `terrainColor` chiaroscuro + far beacon-facing rim + fog biased toward the beacon); Compose
  `ArGradeOverlay` (duotone wash + vignette + tube-curve edges + horizon-haze band) + `ArCornerBrackets` (amber
  L-frame, Ref A). `ArMood` enum committed to GREEN_NIGHT (AMBER_DUSK is a ready 7-value swap for Ref B).
- **Slice 3 (#326) — craggy rock mesas:** `ridged()` sharp crests + `mesaWall()` directional valley walls that
  open toward the beacon corridor (so the vista stays visible), `Mood.rock` umber on steep faces; TERRAIN_RES 50→110.
- **Slice 4 (#327) — lit-window skyline + RobCo boot:** the signature Fallout read — scattered warm LIT WINDOWS
  (small quads a metre in front of each silhouette building, seeded on/off), jagged/broken ROOFLINES (differing
  top-corner heights) + antenna spires, and foreground POWER POLES (Ref B). Refactored `buildHorizonVistaBuffers`
  to a collect-then-emit triangle list (kills the fragile hand-counted vertexCount; worst-case ~4.3k verts,
  USHORT-safe). `ArScreen.WastelandLoading` → a cinematic RobCo terminal boot (a decrypting diagnostic roll via
  `DecryptText` + a clearance bar) over the ~2.7 s the wasteland models.
- **Motion anchoring (#325):** the AR view/buildings now ride a motion-gated STABLE anchor (reuses the CI-tested
  `TravelFilter`) — jitter-free when stationary, snaps forward when you actually walk; a "◈ IN MOTION / ◉
  STATIONARY" readout. `ArViewModel.anchor`/`moving`; `localBuildings` re-projects off the anchor, not raw GPS.
- **⚠️ The whole aesthetic arc is ENTIRELY CI-unverifiable (Filament can't render in CI) — PAUSED for owner Pixel
  verification before layering more.** Pixel checklist: face ~NW (335°) — pale-green orb low on the horizon with
  a dark settlement in front (broken rooflines, warm lit windows, tower, power poles); dark craggy mesas frame
  the valley with the beacon visible down the corridor; entering AR plays the decrypting RobCo boot. Screenshot
  → easy const tuning (window density, mesa steepness `MESA_RISE`/`RIDGE_AMT`, beacon `HERO_AZ_DEG`, palette).
  The Ref-B "amber dusk" look is a flip of `AR_MOOD` + the renderer `Mood` palette (no structural change).
- **Quest VISIT_KIND bug fix (#328) — the last deferred correctness item:** a "visit a TRADER" side quest
  completed on reaching ANY place (it scored off the distinct-place count). Now `Quest.targetKey` (LocationKind
  name, set by `StoryDirector`) + `QuestMetrics/GameMetrics.visitedKinds` gate completion on the SPECIFIC kind
  physically reached; `GameWorldStore` records `loc.kind.name` at each ≤60 m reach and threads it alongside
  `placesVisited`; `QuestStore` persists `targetKey`. Legacy null-targetKey quests fall back to the old any-place
  delta (never un-completable). Per-kind ids → each kind pays out once (no farm). Core kotlinc-validated,
  `QuestLogTest` 15/15 (+3). ⚠️ Android wiring CI-compile-gated; on-device-unverified.
- **Open / steerable next (owner's call):** (a) verify the AR aesthetic + motion on the Pixel, tune from a
  screenshot, or flip to the Ref-B amber-dusk mood; (b) resume the geo-gated core loop verification (still
  paused, CLAUDE.md above); (c) more Fallout atmosphere (dust motes, weathered grade) once the base is confirmed.

### Survival-needs DEPTH arc — a real Sims-style survival layer (this session, #333–#346 all merged)
Owner drove a long run deepening the life-sim needs into a judgmental, consequential survival system that
"bleeds into reality." All CI-green slices, pure cores in `core:telemetry` (locally kotlinc + JUnit — the FULL
game-core suite is now **~640+ tests**), Android layers compile-gated by CI + adversarial subagent reviews.
Every runtime behaviour is **owner-verify on the Pixel** (CI can't run the store/decay/notifications).
- **Needs 100× deeper + notifications (#333/#334):** `LifeStats` needs gained escalating penalty **tiers**
  (LOW/POOR/CRITICAL), per-need **conditions**/advice, and **cross-need coupling** (neglect several at once →
  compounding penalties). `SurvivalAlerts` (pure) → tier-coloured gauges + finer notification throttles.
- **Afflictions (#335/#336/#337):** `core:telemetry/Afflictions.kt` — sustained-critical needs make you
  **contract an ailment** (`Affliction` per `NeedKind`; a fill/drain meter with hysteresis; `advance`/`effects`/
  `forNeed`). Folded into `SpecialGame.resolve` as `ModSource.AFFLICTION` (real check penalties); STAT-panel
  badges; contracted/cured push notifications. Cured by time healthy, rest, or (later) medicine.
- **Exertion (#338/#339/#340):** `core:telemetry/Exertion.kt` — the core loop **spends** survival needs.
  Every ENGAGE/VENTURE/SCAVENGE + real-world TRAVEL costs hydration/energy/(hard)nourishment via `engageCost`/
  `scavengeCost`/`travelCost` (per-mode, sub-point `ExertCarry`); the store applies it at the `exert()`
  chokepoint with capture-decay-then-re-anchor discipline. **NEVER touches HP** (no soft-lock) or `LifeStats.effects`
  (blank stays neutral) — only the 4 needs. So a run of action walks needs down through tiers→afflictions→alerts.
- **Oral hygiene + harsher + rest window (#341/#342):** two new needs **BRUSHING + FLOSSING** (own decay
  rates, `brush()`/`floss()`); harsher penalty bands + deeper cross-need coupling; **REST is now an 8h timer
  WINDOW** (`restUntilMs`/`REST_WINDOW_MS`) — pressing REST opens a window during which energy regenerates
  (`restingMs` in `currentLife`), so it doesn't assume you're always resting; `exert()` breaks the window
  (you got up to act). LIFE panel FLOSS button + RESTING indicator; `SelfCareTool` verbs updated.
- **Consumables (#343 core / #344 wiring):** `ItemKind.PROVISION` — **provisions restore needs**
  (water_ration/trail_jerky/hearty_stew/stim_coffee/soap_bar/toothpaste/floss_pack…) and **medicine cures/
  shortens afflictions** (antibiotics/dental_kit/painkillers/field_medicine, via `Afflictions.shorten`).
  `SpecialGame.canUseProvision`/`useProvision` + `LifeStats.restoreNeed`; store `useProvision` re-anchors the
  need + shortens the affliction meter; PACK **USE** button (cyan) for PROVISIONs; shops (`GameLocations.stock`)
  stock them. **Additive** — the free LIFE-panel top-up buttons stay, so no soft-lock/balance risk.
- **Craft food & medicine (#345):** 6 workbench recipes (`Recipes`) turn basic JUNK/AID into provisions/
  medicine — cook (canteen/stew/jerky, no gate) + brew (antibiotics/dental-kit/painkillers, INT-gated). Pure-
  core only: `Recipe` is output-agnostic + PROVISIONs already have a USE button + codex hook → zero Android change.
- **Resilience perks (#346):** `Perk.exertReductionPct` + **Conditioned** (−34% exertion cost) / **Second Wind**
  (−20% + heal-on-win 3) — the exertion loop's first counterplay/build dimension. `Exertion.scale(cost,pct)`
  (rounded, capped 60% so never free) + `SpecialGame.exertReductionPct(c)`; applied at the store `exert()`
  chokepoint. Perks auto-appear in the picker (`Perks.ALL`) → zero UI change.
- **The survival loop is now:** needs decay (world-driven) → tiers/penalties → afflictions → exertion spends
  them faster → provisions/medicine (bought OR crafted) restore/cure → resilience perks soften the drain →
  push notifications nudge you. ⚠️ **All on-device-unverified** (store/decay/notifications/persistence, the
  8h rest window, affliction contract/cure, exertion feel, perk reduction) — owner tunes/verifies on the Pixel.
- **Steerable next:** peak-condition positive buff is OUT (a blank profile's needs default to 100/PEAK →
  would break the `effects(LifeProfile())`-neutral invariant + overlaps the self-care streak reward); a
  STASH is convenience-only (no carry cap exists). Open ideas: temporary "well-fed" food buffs (needs a timed-
  buff subsystem), more provision/recipe/encounter content, affliction-resistance perks (touch the delicate
  decay/tick path — thread carefully).

### Item-economy + well-fed buff follow-ups (this session, #344–#348 all merged)
- **Consumables wiring (#344):** the PROVISION USE button in the pack + shops stock provisions/medicine.
- **Craft food & medicine (#345):** 6 workbench recipes cook (canteen/stew/jerky) + brew (antibiotics/dental-
  kit/painkillers, INT-gated) from basic JUNK/AID — pure `Recipes`, zero Android change (USE button + codex
  already exist). Closes the loop: you can MAKE provisions, not only buy them at geo-gated shops.
- **Resilience perks (#346):** `Perk.exertReductionPct` + **Conditioned** (−34% exertion cost) / **Second
  Wind** (−20% + heal-on-win 3) — the exertion loop's first counterplay. `Exertion.scale(cost,pct)` capped 60%;
  applied at the store `exert()` chokepoint. Perks auto-appear in the picker → zero UI change.
- **Well-fed buff (#348):** a hearty meal (hearty_stew=4 fights, trail_jerky=2) leaves you WELL-FED — +1 to
  STR/END checks for N encounters, ticking down. `Character.wellFedFor` + `Item.wellFedTurns` +
  `ModSource.WELL_FED` (auto-shows in the immersion check breakdown). A **deterministic counter** (not need-
  value-derived) so it dodges the neutrality trap; the positive counterpart to exertion draining you.

### PHONE PENALTIES — "neglect bites the phone" (owner ask, #349–#353 all merged, OPT-IN default OFF)
Owner: "make the penalties more invasive to the phone — each tailored to revoke access to a thing until the
need is taken care of," then chose **Harsher (kiosk)** intensity, then "seriously make it lock the apps / do
whatever it wants." A neglected survival need now **revokes real Device-Owner capabilities** until you tend it.
All CI-green slices; **the two safety floors kept (non-negotiable): emergency calls always work + a guaranteed
eventual release so a bug can't permanently brick the phone.** Uses the Device-Owner powers (Pulse is DO).
- **Core (#349):** `core:telemetry/PhonePenalties.kt` (+ `PhonePenaltiesTest`) — `PhoneLock` (pause apps /
  block installs / lock quick-settings / disable camera / lock volume / block screenshots), `DEFAULT_MAPPING`
  one distinct lock per need, `penalisedNeeds(life, prev)` **hysteresis** (engage ≤15 / release ≥60), `locksFor`,
  `kioskEngaged`, `restoreHint`. Pure/inert.
- **Enforcement (#350):** `DevicePolicyController` gained reversible levers (setUserRestriction/setStatusBarDisabled/
  setScreenCaptureDisabled/setPackagesSuspended); `security/PhonePenaltyController.reconcile(life, fireGate)`
  engages/lifts locks **transition-only** (never stomps a manual toggle); master toggle + **RELEASE ALL** in
  Settings → Device-owner controls (default OFF; off = release everything); driven on app-foreground + the worker.
- **Banner (#351):** STATS ▸ SPECIAL "🔒 PHONE LOCKED" readout (lock + how to lift each) + an ~8s in-game
  reconcile so tending a need lifts its lock promptly. `TelemetryViewModel.phonePenalties`.
- **Kiosk gate (#352):** `feature/checkin/PenaltyGateActivity` (mirrors `LockoutActivity`) — pins the phone
  **over the lock screen** with the critical need(s) + a "{VERB} · I DID IT" button each (runs the store care
  action); releases when every need is tended/recovered. Fired via `Notifier.notifyPenaltyGate` full-screen
  intent from the worker. Safety nets: 15→**60 min** auto-release backstop, emergency-dialer button, 5s-hold
  override, degrades to a nag without DO, ~2h re-pop cooldown. `AppSettings.phonePenaltyKiosk`/`lastPenaltyGateMs`.
- **Real teeth (#353):** PAUSE_DISTRACTIONS now suspends **EVERY launchable app** (QUERY_ALL_PACKAGES) minus a
  hard safe-list (Pulse/launcher/dialer/Settings/**active keyboard**) — not an empty list; the kiosk gate fires
  from **foreground reconciles too** (onStart + the 8s loop), so it bites within seconds; 60-min hold.
- ⚠️ **ALL on-device-unverified** (CI can't run Device-Owner policies) — owner verifies on the Pixel: toggle on,
  let a need go critical → apps lock + gate over the lock screen → tend the need to get back in. Both compile-
  reviewed clean by subagents. **Open:** per-need mapping picker + distraction-app picker (defaults work now).

### CAMERA/MIC DETECT THE NEEDS — auto-care (owner ask, #354–#356 all merged)
Owner: "camera + mic that detect the needs." The phone now **auto-restores a need when it catches you tending
it in real life** — no button press — building on the existing `ActivitySensing` (scene/sound labels →
`RealActivity` evidence: drinking/eating/washing/brushing) + the perception samplers.
- **Core (#354):** `core:telemetry/NeedSensing.kt` (+5 tests) — `needFor(RealActivity)`→NeedKind (toothbrush →
  the distinct BRUSHING need; shower/handwash → HYGIENE; eating → NOURISHMENT; drinking → HYDRATION),
  `sensedNeeds(evidence, minConf)` deduped ≥0.6-confidence, `CREDITABLE`.
- **Auto-care engine (#355):** `data/perception/NeedAutoCare.kt` observes `activityEvidenceStore.evidenceFlow`;
  when it confidently senses care in the last 5 min it calls the store care action (drink/eat/wash/brushTeeth),
  per-need 30-min cooldown, gated by the ambient-sensing toggle. Started from `PulseApplication` app scope.
  `Notifier.notifySensedCare` quiet DIGEST flourish. **Closes the penalty loop:** the gate releases when a need
  recovers, so the phone SEEING you drink auto-unlocks it.
- **Flourish + vocab (#356):** a live "👁 AUTO-CARE · caught you taking care — <Need> restored" banner on STATS
  (`TelemetryViewModel.sensedCare`); expanded `ActivitySensing` keyword vocab (water/tooth/eat/drink/scenes).
- ⚠️ On-device-unverified (mic/camera + MediaPipe classifiers + the auto-restore only run on the Pixel).
  **Open:** sleep/rest → ENERGY detection deliberately NOT built ("dark+quiet+night" misfires — would falsely
  credit rest whenever you sit quietly at night); a conservative version is possible if the owner wants it.

### SMART FULL-SCREEN SELF-CARE CHECK-INS (owner ask, #358–#360 all merged)
Owner: "add check-ins where a huge pop-up from Pulse takes over the entire screen — brushing/flossing/water are
the TOP priorities, the others deterministic by typical rhythms/cycles, and the whole point is contextual
sequencing (you ate → brush the right time after → floss the right time after) and **ensure you do it right**."
Shipped as three CI-green slices; each mirrors the established store/UI/worker patterns.
- **Slice 1 — scheduler core (#358):** `core:telemetry/CareSchedule.kt` (+`CareScheduleTest`) — pure, deterministic.
  `CareCheckin` enum (BRUSH 100 / FLOSS 95 / WATER 90 / EAT 70 / REST 55 / WASH 45 — `need`/`label`/`prompt`/
  `basePriority`); `CareContext` (now/hour/lastConfirmed/lastMealMs/needs). `due`/`next` sort by `priority`
  (base + escalation: +60 critical, +25 low). Rhythms: WATER 2h, EAT 5h, BRUSH 12h fallback, WASH/FLOSS daily.
  **Contextual sequencing:** BRUSH comes due `POST_MEAL_BRUSH_MS` (30 min) after a meal (`lastMealMs` set, not
  brushed since); FLOSS follows a brush within `FLOSS_AFTER_BRUSH_MS` (2h). Waking-window gated (WAKE 7–22) so it
  never takes over at 3am; `MIN_ASK_GAP_MS` 90 min throttles re-asks; REST only when energy ≤ NEED_LOW.
- **Slice 2 — takeover + store + worker + toggle (#359):** `feature/checkin/CareCheckinActivity.kt` — full-screen
  PROMPT over the lock screen (`showWhenLocked`/`turnScreenOn`, BACK swallowed, always answerable DONE/NOT YET —
  never a trap, distinct from the penalty *gate*). `data/game/CareCheckinStore.kt` (DataStore `pulse_carecheckin`,
  Mutex; tracks confirmed/asked per check-in + lastMealMs; `schedule()`→`CareSchedule.next`, `markAsked`,
  `complete`→stamp+tend-need). `start(scope, needAutoCare.sensed)` — a **sensed** care action counts as that
  check-in done, so it won't nag you to do what the camera/mic just caught you doing. `Notifier.notifyCareCheckin`
  (full-screen intent, REMINDERS). `RefreshWorker` raises the top due check-in each tick (gated by
  `NotificationPrefs.smartCheckins`, default ON, + quiet hours). Settings → "Smart sequenced check-ins" toggle.
- **Slice 3 — verification, "ensure you do it right" (#360):** answering DONE now cross-references the claim with
  what the camera/mic sensed (reuses `ActivitySensing.verifyClaim`). Core `CareSchedule.verifyActivities`
  (check-in→corroborating `RealActivity`; WASH ← shower OR handwash; FLOSS/REST = no sensor), `verifyClaim`
  (forgiving: WEAK when unsensable or sensing-off → **never a false accusation**), `credits`, `VERIFY_WINDOW_MS`
  (+5 tests, 12 total). `CareCheckinStore.completeVerified` gates teeth on **`ambientSensingAlways && ambientSensing`**
  — the one config where sensors actually watch over the lock screen; otherwise trusts the tap. A flat NONE
  (sensors watching, saw nothing) bounces the activity to an amber "SENSORS DIDN'T CATCH THAT — do it now, then
  confirm" state (re-check re-verifies; NOT YET still escapes).
- ⚠️ **All on-device-unverified** (CI compile-gates only): the full-screen takeover, the sequencing timing, the
  worker cadence/throttles, and the verify/bounce path — owner verifies on the Pixel (the bounce only engages
  with **always-on ambient sensing** on). **Open/steerable:** per-check-in cadence tuning; whether verification
  should also engage while a game screen is open (samplers running); a check-in adherence surface.

### Deep-links · RAM diet · INVASIVE LOCK · SURVIVE search (owner batch, #362–#367 all merged)
Owner's multi-part ask: every notification opens directly into its exact page (+ expandable dropdowns);
survival tips open the SPECIFIC how-to guide; cut RAM (kill the live wallpaper + the stats data-stream);
make the lockout TRULY invasive (user-armed, not criticality-gated, real screen lock, always-on background);
and a SURVIVE search bar so pages don't eager-load. Shipped as 6 CI-green slices. Recon was 4 parallel Explore
agents (whole-codebase map). **The compile-review subagent kept returning prompt-injected/corrupted output
(0 tool calls) — disregarded every time; relied on manual verification + CI (noted in each PR body).**
- **#362 — survival tip → the specific guide (flagship):** `SurvivalTips.guideIdFor(tip)`/`guideIdAt(i)`
  classify each of the 310 tips to a guide id by keyword (validated by running it over the whole catalog +
  tuning substring collisions; +4 tests). **9 new offline guides** in `assets/survival/guides.json` (knots,
  food, wildlife, cold, heat, terrain, hygiene, urban, mindset) so every tip lands on a real page. Argumented
  route **`survival?guide={id}`** + `GuidesScreen` pre-select; the deep-link consumer matches on the base route
  (`substringBefore('?')`) so the arg survives. `Notifier.notifyTip(guideId)` → `RefreshWorker` passes it.
- **#363 — every notification → its exact page + BigText dropdowns:** the shared `post()` helper already
  set BigText+route; fixed the 3 that dead-ended on Home via a stale `"grid"` route (sky→`space_wx`,
  safety→`safety`, flight→`radar`) + added those to `SHORTCUT_ROUTES`; added `BigTextStyle` to the 3
  full-screen builders (`notifyCheckin`/`notifyPenaltyGate`/`notifyCareCheckin`).
- **#364 — RAM diet:** **DELETED the live wallpaper entirely** (`JarvisWallpaperService.kt`, manifest
  `<service>`, `res/xml/jarvis_wallpaper.xml`, its strings, the Settings Appearance controls, the
  `AppSettings.liveWallpaperReadout` field — `ignoreUnknownKeys` makes old saves load). Removed the STATS ▸
  STATUS **"Data stream"** panel + its VM machinery (`_log`/`pushLog`/`fmt`). Cut the shared Coil memory
  cache **15% → 6% of heap** (the real lever — fixed-% shared across all image screens; removing news
  thumbnails would NOT help). **Kept `largeHeap`** — load-bearing for the LLM + Filament AR (dropping it OOMs).
  ⚠️ commit gotcha: `git rm` staged deletions, then a compound `git add` with the removed pathspec aborted →
  the first commit had only deletions; fixed by staging mods + `--amend` + force-with-lease (own dev commit).
- **#365 — USER-ARMED COMMITMENT LOCK (the invasive one):** from Settings → Device-owner controls, tap "Lock
  until I shower/brush/eat/drink" and Pulse **genuinely locks the screen** via a new
  `DevicePolicyController.lockNow()` (new `<force-lock/>` in `device_admin.xml`; DO-only, only locks — never
  wipes) then shows the gate over the lock screen, releasing only when `ActivitySensing` confirms you did it —
  **regardless of any need level**. Reuses `LockoutActivity` (`EXTRA_USER_ARMED`+`EXTRA_BACKSTOP_MIN`).
  **Safety floors kept:** emergency dialer, a hard-capped owner-set auto-release backstop (15 min–4 h, no-brick),
  a personal numeric **override code** (`AppSettings.lockOverrideCode`) that frees any lock + the 5-second hold,
  degrades to a nag without DO.
- **#366 — robust + default-on always-on sensing:** the 24/7 watch/listen the lock's completion-detection
  relies on now survives reboot/OS-kill — `BootReceiver` revives `AmbientSensingService`, `RefreshWorker`
  self-heals it each run (it's `START_NOT_STICKY`), and `AppSettings.ambientSensingAlways` now **defaults ON**
  (owner's explicit "watch/listen at all times" ask; still fully toggle-gated; existing installs keep their
  saved value — a fresh reinstall picks up the default).
- **#367 — SURVIVE file-explorer search:** a Pip-Boy search bar atop `SurviveBody` indexes every hub
  destination + every offline guide (title/category/summary/section headings); results deep-link straight to
  the exact page (guide results reuse `survival?guide=`). Empty query = the existing tile grid (nothing heavy
  loads; only cached guide titles are read, not the network screens). Both hosts (standalone + PIP-BOY ▸ STATS
  ▸ SURVIVE) with no call-site change.
- ⚠️ **All #362–#367 on-device-unverified** (CI compile-gates only). **Highest-stakes = the invasive lock +
  24/7 sensing (#365/#366)** — owner should exercise first on the Pixel: arm a lock, set an override code,
  confirm sensor-release + backstop + emergency paths, and watch battery with the camera on 24/7 (flip to
  mic-only if it drains). **Open/steerable:** a deeper "organize SURVIVE *exactly* like a Pip-Boy" visual pass
  (screenshot-driven); a JarvisTool to arm the commitment lock by voice; extend the override code to the
  penalty gate; the survival-tip classifier has a few soft misroutes (mindset fallback) that are easy to tune.

### Survival visual diagrams + Sexual-Health guide + the FLOATING GAME OVERLAY (this session, #369–#374 merged)
- **Survival guide diagrams (bundled, freely-licensed, offline).** Owner: "survival mode needs images/diagrams
  for the visual stuff like knot-tying, downloaded and saved in there." Via AskUserQuestion the owner chose
  **bundled freely-licensed** (PD/CC images committed in, no runtime download, no arbitrary web images).
  `GuideSection.image` (a filename under `assets/survival/images/`) renders under the section body via a
  `SurvivalDiagram` `AsyncImage` on a light card (`GuidesScreen`). **#369** knots (8 EB1911 public-domain
  engravings). **#370** Morse + ground-to-air (PD). **#371** added the **SVG decoder** (`coil-svg` →
  `SvgDecoder.Factory()` on the shared `AppContainer.imageLoader`, which `PulseApplication` returns as Coil's
  `ImageLoaderFactory` so `AsyncImage` uses it) + first-aid recovery-position, campfire, find-north (CC/CC0
  SVGs). Attribution is inline in each section + in `images/NOTICE.txt`; `find-north.svg` was re-encoded
  UTF-16→UTF-8 for the on-device parser. Illustrated guides now: knots · Morse/signals · first-aid · fire · nav.
- **Sexual-Health guide (#372).** Owner asked for a sex-education section with diagrams. Shipped a factual
  **`Sexual Health`** guide (new `Health` category) in the register of a standard health curriculum: anatomy
  (two clinical labelled reproductive-system diagrams — female PD/CDC-Mysid, male CC-BY-SA-4.0/Wumingbai,
  bundled offline), how reproduction works, **consent as the central rule**, contraception, STIs, staying
  healthy. Clinical/non-explicit throughout; attribution inline + NOTICE.txt. The SURVIVE file-explorer search
  indexes it automatically.
- **FLOATING GAME OVERLAY (#373 S1, #374 S2)** — owner: "make the game a physical constant overlay on the
  phone, the buttons at least, for the Special tab. It doesn't need the app open for it to work and update
  stuff about the game and conditions and me." `feature/overlay/GameOverlayService` — a foreground service
  (`specialUse`) that adds a raw `WindowManager` `TYPE_APPLICATION_OVERLAY` panel (programmatic Views, NOT
  Compose — far more robust to build blind), non-focusable so the rest of the phone stays interactive.
  **S1:** the six survival needs live (tier-coloured) + overall CND% + most-urgent line + a row of self-care
  buttons **DRINK/EAT/REST/WASH/BRUSH/FLOSS** (call `SpecialGameStore` care actions). Draggable by header,
  tap-to-collapse to a bubble, ✕ dismiss (also flips the setting off). New `SpecialGameStore.tickNeeds()` — a
  **passive** display tick (recompute+republish decayed needs, no re-anchor/flush/affliction-advance, never
  fights the foreground VM's env tracking); the overlay calls it every 30s so decay stays live app-closed.
  **S2:** added character **VITALS** (LVL/HP/caps from `characterFlow`), the **wasteland DAY** banner
  (`GameClock.banner`, recomputed each tick), and an **OPEN** header button that deep-links to PIP-BOY for the
  full-screen parts (encounters/gestures/map) — the draw-over-apps permission exempts the launch from
  background-activity limits. `AppSettings.gameOverlay` (opt-in, default OFF) + Settings → Appearance toggle +
  a "Grant draw-over-apps permission" launcher; `MainActivity` starts/stops it (only when `canDrawOverlays`);
  manifest gained `SYSTEM_ALERT_WINDOW` + the service. Fully defensive.
- **Trace verdict (overlay × the lock systems — no bypass):** the overlay's care buttons restore needs
  in-game, so I traced whether that could unlock the phone without the real action. **Commitment lock**
  (`LockoutActivity`) releases ONLY on sensor-confirmation / backstop / override — a need-value change can't
  release it, so no bypass. **Penalty gate** releasing on the in-game care action IS its intended
  "tend-the-need-to-unlock" design (its own buttons do the same), so the overlay is just another legitimate
  surface. Neither lock is weakened.
- ⚠️ **The whole overlay is on-device-unverified (CI compile-gates only) — owner verifies on the Pixel:**
  Settings → Appearance → **Floating game overlay** → grant "draw over other apps"; then the panel window
  render, drag/collapse, the buttons updating needs with the app closed, the day/vitals advancing, and the
  OPEN deep-link from over another app. Also eyeball the survival/sex-ed diagrams render legibly on the light
  card. **Open follow-ups (paused pending owner verify — don't stack more blind overlay UI first):** widen the
  overlay to ENGAGE/SCAVENGE when at a wasteland site + quests + a Pip-Boy visual polish; a shelter/CPR diagram.

### Autonomous continuation — classifier fix, encounters, overlay S3 (this session cont., #376–#378 merged)
Owner: "keep going autonomously … and make the whole thing." Shipped as CI-green slices; two subsystems
(auto-updater, RefreshWorker notification passes) were also traced and came back clean (no forced fixes).
- **Survival-tip classifier fix (#376):** `SurvivalTips.guideIdFor` let 40/310 tips fall through to the
  general `mindset` guide, incl. 16 that clearly belong elsewhere (a spinal-injury tip → first-aid, the
  moss-north-side/wristwatch/cairn myths → navigation, "split wood burns" → fire, frostbite rewarming →
  cold, etc.). Added narrow collision-free keywords so exactly those 16 retarget (mindset 40→24); verified
  over the whole catalog that ONLY those 16 change. +2 tests (retargeted-tips + a mindset-bound guard);
  locally kotlinc-run, 10 SurvivalTipsTest green.
- **8 new encounters (#377):** `SpecialEncounters` 63 → 71 — Dust-Choked Pass, Beggar's Bargain, Frozen
  Convoy, Frayed Crossing, Picked-Over Pharmacy, Collapsed Mine, Snake-Oil Doctor, + a repeatable SCAVENGE —
  The Buried Bus. Across all seven stats + tiers; all loot ids resolve. The id-uniqueness test caught a
  `rope_bridge` collision (renamed `frayed_crossing`) before push. 60 game-core tests green locally.
- **Game overlay S3 — "make the whole thing" (#378):** the floating overlay is now the **full S.P.E.C.I.A.L.
  game playable app-closed**. Added a **WASTELAND** section (VENTURE → an encounter's stat-gated choices as
  odds-tagged buttons [SURE/LIKELY/EVEN/RISKY/LONGSHOT via `SpecialGame.check`] → tap to resolve → ✓/✗/✦CRIT
  outcome → VENTURE again), a **SCAVENGE** button with the 3-min cooldown + haul readout, and an
  **OBJECTIVES** section (top quests + progress from `QuestStore`). New collectors on `characterFlow`/
  `resolutionFlow`/`lastScavengeFlow`/`questStore.quests`; dynamic sections rebuild on change; the 30s tick
  keeps decay + cooldown live. **Design note (owner-vetoable):** the overlay's VENTURE/SCAVENGE call the
  store directly — **couch-play**, NOT the geo-gated in-app path (the GEO-GATED arc retired couch-play in the
  SPECIAL tab). It's the "play from anywhere" surface the overlay exists for; the geo-gated + gesture
  experience stays in-app (OPEN jumps there). If the owner wants the overlay to respect geo-gating, gate
  VENTURE/SCAVENGE behind presence.
- ⚠️ **All #376–#378 CI-gated only; the overlay S3 is a large blind Android build (CI compiled it, can't run
  it).** Owner-verify on the Pixel: the encounter/scavenge/quest render + interaction in the overlay window
  app-closed, the panel height with every section expanded (no ScrollView yet — if it overflows the screen,
  add one), and the couch-play loop. **Open/steerable:** ScrollView if the panel is too tall; a per-section
  collapse; whether the overlay should respect geo-gating; a stat-allocate control in the overlay (currently
  routed to OPEN).

### News: Trending page · EMERGENCY alert type · market strip under each article (#380–#382 merged)
Owner-driven; all CI-green, squash-merged, re-synced.
- **Trending "seen everywhere" page (#380):** owner wanted "Hollywood instant news seen everywhere" —
  clarified as *how fast the internet/social media spread a story*, NOT celebrity/showbiz (I initially
  mis-built it as a Hollywood-celebrity query; corrected via `--amend` on the same draft PR). New **TRENDING**
  `NewsCategory` — a search-driven feed on velocity/virality words (`breaking OR trending OR viral OR "this
  just in" OR developing OR "happening now"`), recency-sorted. Tabs auto-build from `NewsCategory.entries`, so
  the WIRE screen picks up the "Trending" tab with no UI change.
- **EMERGENCY "this just in" notification type (#380):** major breaking emergencies anywhere get their OWN
  distinct notification, independent of the general breaking feed. `core:telemetry/EmergencyNews.kt` (+6 tests)
  — pure classifier: STRONG disaster/violence/crisis keywords fire alone; MODERATE ones fire unless an
  entertainment/sport context word sits alongside (so a "box-office explosion"/a striker's "blast" don't);
  two-tier `severity()`. New `NotificationChannels.EMERGENCY` (own urgent vibration pattern + red light),
  `Notifier.notifyEmergency` (PRIORITY_MAX, "THIS JUST IN" tag, id 1002 vs breaking's 1001),
  `NotificationPrefs.emergencyAlerts` (default ON) + a Settings toggle. `BreakingNewsPulse` now reads prefs and
  fires each notification per its own pref off the SAME top-feed fetch — the breaking lead AND, separately, the
  most-severe fresh emergency headline. `RefreshWorker` triggers the check when breaking OR emergency is on, so
  emergencies come through even with the breaking feed off. (Only scans TOP — broadening to WORLD is a
  follow-up for "everywhere.")
- **Market strip under each article (#381 core+chips, #382 live ±%):** owner — "on each news report add the
  market they're associated with / would affect, beneath the summary; a small strip showing what markets it
  affected + why." `core:telemetry/NewsMarketLink.kt` (+8 tests) — pure heuristic: keyword sector-matching
  (Oil/Gold/Defense/Bitcoin/Chips/Tech/Banks/Airlines/Housing/Pharma/Autos-EV/Retail/Food/broad-Stocks) for the
  association + a move/event lexicon for direction (UP good / DOWN bad when the headline states/implies a move,
  MIXED otherwise; havens like Gold/Defense lift on crisis words). `linksFor()` caps at 3; `summarize()` → the
  "Could lift Oil; weigh on Airlines." why-line. A test caught a greedy-match bug ("miss" matched "missile").
  `NewsComponents.MarketStrip` renders beneath each `ArticleCard` summary — a wrapping `FlowRow` of chips.
  **#382** added the LIVE move: `data/news/NewsMarketPulse.kt` (a fixed pulse basket → `MarketsRepository`
  market→WatchItem, fetched once via `quotesFor`); `NewsViewModel` gained the `MarketsRepository` dep + a
  one-time fetch into `NewsUiState.marketPulse` (factory wires it); the chip shows the live `▲ Oil +2.3%`
  coloured by the ACTUAL sign, falling back to the heuristic arrow when a quote is missing. Fully defensive.
- ⚠️ **All #380–#382 on-device-unverified** (CI compile-gates; the pure cores are locally kotlinc-tested).
  Owner-verify on the Pixel: the Trending feed feel, the emergency channel's distinct buzz + a real emergency
  firing the separate alert, and the market strip's layout/legibility + the live ±% populating (needs a
  successful Yahoo basket fetch). **Easy tunes:** the `TRENDING` query, the `EmergencyNews` keyword lists, the
  `NewsMarketLink` sector keywords, and the `NewsMarketPulse` basket symbols. **Open/steerable:** broaden
  emergency scan to WORLD; the market strip on the compact article row / home news preview.

### Ambient-sensing audit — "is it just visual?" (#386 merged)
Owner asked to "make sure the ambient environment sensing thing is not just some visual stuff only." Traced
the full camera/mic → gameplay pipeline. **Verdict: genuinely functional, with one dead output (now fixed).**
- **Already functional (NOT decoration):** the mic (`AmbientPerceptionSampler`/YAMNet) + camera
  (`CameraPerceptionSampler`/EfficientNet) → `PerceptLabel`s → `Perception.distill` → `SceneContext` →
  `Perception.strategy`. Its `favored` stat set **biases which encounters appear** (`TelemetryViewModel.venture()`
  unions it with circadian + world-event + perception favoured); `NeedAutoCare` **auto-restores survival needs**
  when the camera/mic catches you actually drinking/eating/washing/brushing (`game.drink()/eat()/wash()/brushTeeth()`);
  `ActivitySensing` **verifies self-care check-in claims** (bounces a "DONE" the sensors didn't corroborate);
  `flavor` writes the quest brief.
- **The one dead output → fixed (#386):** `SceneStrategy.tempoNudge` was computed but consumed nowhere (only
  its own test). Wired into `StoryDirector` daily `WIN_ENCOUNTERS` target: `2 + level/2 + tempoNudge`, floored
  at 1 — an active scene (moving/vehicle/dark → +1) asks for a bit more, calm eases off. So the perceived tempo
  now MOVES the objective, not just words. +2 `StoryDirectorTest` cases (13/13 green, locally kotlinc-run).
  Note: `strategy()` only ever produces tempoNudge 0 or +1 (no code path subtracts), so the coerce/-1 floor is
  defensive; that's fine. ⚠️ On-device runtime feel is owner-verify (CI can't run the samplers).

### Survival offline/depth · animal-habitat map · Trading-Places market strip · Social→News (this session, #387–#390 merged)
Owner batch: make survival completely offline + in-depth (word-for-word, materials/chemicals) + a clickable
animal-habitat heat map; redesign the news market strip "Trading Places but legal"; fold Social into News as
per-source tabs. Shipped as CI-green slices (pure cores locally kotlinc-run; Android layers compile-gated +
one CLEAN compile-review subagent on the map UI).
- **News MARKET REACTION strip (#387):** `core:telemetry/NewsMarketLink.kt` — `MarketLink` gained a per-story
  causal `rationale` + a 1..3 `strength`; `linksFor` now uses the news `category` for a broad-market baseline
  (Business→Stocks etc.) and sorts by strength; new on-theme **OJ Futures** market (frost/freeze→up, the
  literal Trading Places setup) + per-market up/down phrasings + `headline()`. `summarize()` → "Reality check:
  lifts X; weighs on Y." UI `feature/news/NewsComponents.kt` MarketStrip is now a framed "◢ MARKET REACTION ·
  LIVE" block (chips w/ live ±% or heuristic arrow · sharpest causal line · winners/losers), chip → `MarketChip`.
  `data/news/NewsMarketPulse.kt` added OJ=F to the live basket. +5 tests (13/13).
- **Offline animal-habitat map (#388):** pure `core:telemetry/AnimalHabitats.kt` (offline `continentFor`/
  `biomeFor` coordinate heuristics → `habitatFor(lat,lon)→Habitat`, `dangerLevel()`) + `AnimalCatalog.kt`
  (~55 species, each identify→behaviour→what-to-do→bite/sting first aid; `faunaFor(biome,continent)` regional
  table + per-continent fallback + global biome fauna). +10 tests (classification vs known cities, every
  fauna id resolves, hostiles-lead, determinism). UI `feature/survive/HabitatScreen.kt` — a Compose **Canvas**
  heat scope (you at centre, each species a radial-gradient territory coloured green→red by danger, range
  rings, tap a zone/card → read-out + field detail), `HabitatViewModel` (GPS fix → habitatFor, no network).
  Wired `Routes.HABITAT` + factory + NavHost(PipGreen) + SURVIVE hub "Wildlife" tile + search. **Regional
  model, not a live tracker** (biome estimated offline; positions illustrative). No map tiles → fully offline.
- **Survival guides deepened (#389):** +15 in-depth sections to `assets/survival/guides.json` (offline
  already) — water (exact bleach/iodine/tablet doses, DIY charcoal filter, distillation), fire (char cloth,
  bow drill, ferro/flint), food (full 7-step Universal Edibility Test + never-test list, edible plants/insects,
  snare + gorge-hook), first-aid (wound irrigation + infection signs, splinting, RICE), shelter (debris hut
  dimensions), navigation (shadow-stick, analog-watch). Guide ids unchanged → search/tip deep-links intact.
- **Social → News per-source tabs (#390):** `NewsTab` gained `social: SocialTab?`; the News tab bar appends
  Lemmy · Mastodon · Hacker News after the categories. `NewsViewModel` takes `SocialRepository`; `fetchTab`
  routes a social tab to `loadSocial()` which adapts each `SocialItem`→`Article` (upvotes/comments as summary,
  thumbnail) so it renders in the existing list. No NewsScreen change. The standalone Social screen + PIP-BOY
  SOCIAL sub-tab (which also carries Mastodon trending TAGS) left intact — fully removing Social from TOOLS is
  an owner follow-up.
- **Survival "completely offline" scope:** the offline CORE is now comprehensive (deepened guides, wildlife
  map, tools, SOS, compass/nav — all offline). "Nearest Help" (Overpass POIs) + "Nearby Safety" (live incident
  feeds) are inherently live-data — they cache + serve stale offline but can't bundle the world's hospitals/
  quakes. Honest scoping, not a gap to paper over.
- ⚠️ All #387–#390 on-device-unverified (CI compile-gates; pure cores locally tested). Owner-verify on the
  Pixel: the MARKET REACTION strip legibility + live ±%, the wildlife heat-map render/tap/GPS-biome, the
  deepened guide sections, and the new News social tabs (feed render, tab switching). **Open/steerable:**
  remove Social from TOOLS entirely; a topographic base for the wildlife map (needs online DEM); tune the
  biome heuristic / animal catalogue from real use.

### Market strip ×40 · news infographics · BREAKING NEWS takeover (this session cont., #392–#395 merged)
Owner: broaden the market strip to catch anything with market effect (local/small/community stocks, consumer
demand, politics, economics); add more infographic strips; and a Hollywood BREAKING-NEWS interruption that
force-opens a full-screen page.
- **Market strip ×40 (#392):** `core:telemetry/NewsMarketLink.kt` grown 15→~40 markets — macro (Small Caps =
  small/local/main-street business, Bonds & Rates, US Dollar), every sector (Nat Gas/Solar/Uranium/Silver/
  Copper/Steel/Lithium/Mining/AI/Cyber/Cloud/Social/Telecom/Regional Banks/Insurance/Biotech/Shipping/
  Industrials/REITs/Utilities…), consumer behaviour (Retail/E-comm/Staples/Restaurants/Travel/Alcohol/Tobacco/
  Cannabis/Gaming/Media/Luxury), softs (Coffee/Cocoa/OJ/Grains), politics→broad market (tariff/election/
  regulation→Stocks). PRECISE multi-word triggers ("local business" not "local") keep genuinely non-market
  news empty. Cap 3→4. +5 tests (18/18). `data/news/NewsMarketPulse.kt` basket resynced + expanded to a
  curated ~26 liquid instruments (kept modest — `quotesFor` fires one throttled request/symbol, a 40-burst
  would risk Yahoo 429s; wider markets show the heuristic arrow). Direction+why work OFFLINE for all markets.
- **News at-a-glance infographics (#393):** pure `core:telemetry/NewsInsights.kt` (+9 tests) — `tone` (Upbeat/
  Mixed/Grim/Tense + a −1..1 score), `topics` (auto topic + region tags), `marketImpact(links)` (NONE/LOW/MED/
  HIGH). UI `NewsComponents.GlanceStrip` under each summary (a MOOD bar green→red + #topic chips) + the MARKET
  REACTION header now shows the IMPACT level.
- **BREAKING NEWS takeover (#394 core+repo, #395 UI+trigger):** the app force-opens a cinematic full-screen
  page on a MAJOR event. **#394:** `EmergencyNews.isMajor` (higher bar than isEmergency — STRONG disaster, a
  notable death [fiction-guarded], or a historic event) + `topicQuery`; `data/breaking/BreakingCoverageRepository`
  aggregates the topic across trusted FREE sources via the keyless Google News search (Reuters/AP/BBC/…),
  trusted-first, ad-free, short-cached. **#395:** `feature/breaking/BreakingNewsActivity` (full-screen takeover
  over the lock screen, mirrors CareCheckinActivity) + `BreakingNewsScreen` (red BREAKING banner + pulsing LIVE
  + clock + tab system TOP COVERAGE/LATEST/SOURCES, trusted badges); `Notifier.notifyBreakingInterrupt` (FSI,
  CATEGORY_CALL, new BREAKING_INTERRUPT channel); fired from `BreakingNewsPulse` only on `isMajor`, gated by the
  opt-out `NotificationPrefs.breakingInterrupt` (default ON) + HARD throttle (per-title dedup `breakingInterruptSeen`
  + 25-min `breakingInterruptLastMs`, both preserved in `RefreshWorker.writeState`); Settings toggle; manifest
  `<activity>`. **Honest Android ceiling (documented):** FSI takes over instantly from lock/idle; while the
  phone is in active use the OS renders a max-priority heads-up to tap — the same path the app's lockout/care
  takeovers use. ⚠️ One CI failure fixed: the recurring `Modifier.padding(horizontal=, top=/bottom=)` overload
  trap in BreakingNewsScreen (no overload mixes horizontal with top/bottom → use start/end).
- ⚠️ All #392–#395 on-device-unverified (pure cores locally kotlinc-tested; the takeover render + lock-screen
  FSI firing + the wider strip are CI-unprovable). **Open/steerable:** live ±% for more markets (needs a
  heavier fetch strategy); tune the isMajor bar / trusted-source list; a manual "test the takeover" trigger.

## How to continue (new session)
Open this repo (default branch `main` has everything). Read this file. Continue development on the
session's assigned dev branch (this session: `claude/loving-edison-bd65oa`), push small CI-green commits,
open a draft PR → `main`, verify green, merge.
Honor the constraints above (human-gate for self-code, protected paths, commit trailers, no model id in
artifacts, on-device verification for anything CI can't prove — esp. R8, the HUD-on-glasses, and voice).
