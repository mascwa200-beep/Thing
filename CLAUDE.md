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
  cloud), **`VisionEngine`** (image/PDF analysis, cloud). `maxTokens` is the TOTAL budget.
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
HUD**. Standing user intent: "keep improving / keep adding features autonomously, ship small CI-green commits,
trace for bugs ('simulate breakpoints'), don't wait for go-aheads."

### Known follow-ups / not done
- Background glasses HUD via a foreground service (HUD bearing/distance nav card: **done**).
- R8/minify on the shipped build (needs verified keep-rules for serialization/MediaPipe/Vosk/MapLibre).
- Optional emulator-generated baseline profile (vs the hand-authored starter).
- Text/PDF are handled; other binary file types decline honestly.

## How to continue (new session)
Open this repo (default branch `main` has everything). Read this file. Continue development on
`claude/modest-ramanujan-0r3iz8`, push small CI-green commits, open a draft PR → `main`, verify green, merge.
Honor the constraints above (human-gate for self-code, protected paths, commit trailers, no model id in
artifacts, on-device verification for anything CI can't prove — esp. R8, the HUD-on-glasses, and voice).
