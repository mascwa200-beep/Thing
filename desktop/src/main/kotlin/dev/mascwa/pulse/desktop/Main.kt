package dev.mascwa.pulse.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.desktop.diagnostics.CrashReporter
import dev.mascwa.pulse.desktop.library.LibraryRepository
import dev.mascwa.pulse.desktop.library.PackStore
import dev.mascwa.pulse.desktop.live.LivePlayer
import dev.mascwa.pulse.desktop.settings.DesktopSettingsStore
import dev.mascwa.pulse.desktop.notes.DiaryStore
import dev.mascwa.pulse.desktop.notes.NotesStore
import dev.mascwa.pulse.desktop.standby.LockScreenImage
import dev.mascwa.pulse.desktop.standby.ScreenSaver
import dev.mascwa.pulse.desktop.standby.StandbyScreenSaverWindow
import dev.mascwa.pulse.desktop.study.StudyStore
import dev.mascwa.pulse.desktop.update.BuildInfo
import dev.mascwa.pulse.desktop.update.DesktopAutoUpdater
import dev.mascwa.pulse.desktop.update.DesktopUpdater
import dev.mascwa.pulse.desktop.update.ScheduledUpdate
import dev.mascwa.pulse.desktop.update.SingleInstance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val settingsStore = DesktopSettingsStore()

/**
 * The library and the study deck are owned here rather than inside the composition.
 *
 * ⚠️ Not a style choice. `exitApplication()` calls `System.exit(0)` the moment `onCloseRequest`
 * returns, and the study store's writes are debounced by two seconds — so a deck built inside
 * `remember` would be unreachable at exactly the moment it needs flushing, and answering a question
 * and closing the window within two seconds would silently lose the answer. Same reason the settings
 * store already lives out here.
 */
private val packStore = PackStore()
private val libraryRepository = LibraryRepository(packs = packStore)
private val studyStore = StudyStore(libraryRepository)

/**
 * The live-television player, hoisted for the same reason the stores are — and a sharper one.
 *
 * `exitApplication()` calls `System.exit(0)` the moment `onCloseRequest` returns. A player built
 * inside the composition would still be holding a native decoder and an open socket at that point,
 * with nothing able to reach it to let go.
 */
private val livePlayer = LivePlayer()

/**
 * ⚠️ Hoisted above the composition for the same reason as the stores and the live player: a station
 * has to keep playing while you move between screens, and `onCloseRequest` has to be able to reach it
 * to let go of the native decoder before the process dies.
 */
private val radioPlayer = dev.mascwa.pulse.desktop.radio.RadioPlayer()

// ⚠️ Owned here for the same reason every other store is: `exitApplication()` calls `System.exit(0)`
// immediately, so a write still sitting in the debounce window when the window closes is simply lost.
// These hold what a person actually typed, which makes losing one worse than losing a cached feed.
private val notesStore = NotesStore()
private val diaryStore = DiaryStore()

/**
 * ⚠️ Owned here and installed FIRST, before anything else in [main] runs.
 *
 * A fault during startup is exactly the one nobody can diagnose otherwise — the window never
 * appears, so there is no screen to read it back from — and a handler installed after the thing it
 * was meant to catch is decoration.
 */
private val crashReporter = CrashReporter(AppPaths.dataDir.toFile())

/**
 * The window's keyboard shortcuts.
 *
 * ⚠️ Owned here because `onKeyEvent` is a parameter of `Window`, which is built before any composition
 * exists — so the handler cannot be a lambda closing over shell state. The shell publishes into this
 * instead; see [ConsoleKeys].
 */
private val consoleKeys = ConsoleKeys()

/**
 * Set when Windows launched us as `LCARS.scr /c` — its screensaver settings dialog asking to be
 * configured. The console is that dialog, so it opens on its own settings page instead.
 */
private var startOnSettings = false

/** How often the console looks for an upgrade asking it to stand down. */
private const val QUIT_POLL_MS = 2_000L

/**
 * Bank everything a person typed or answered, before the process is allowed to die.
 *
 * ⚠️ **Blocking, and one definition for all three ways out.** Compose Desktop's `exitApplication()`
 * calls `System.exit(0)` the moment the close handler returns, with no shutdown hook to wait on, so
 * a fire-and-forget write races the process dying — every store here debounces by two seconds. There
 * are now three exits (the close button, ABOUT's install-and-quit, and standing down for the hourly
 * upgrade); three copies of this list would mean the third one quietly losing the last study answer.
 */
private fun flushEverything() {
    runBlocking {
        settingsStore.flushNow()
        // The window being open was the sitting; bank the time before the process dies. An unbanked
        // sitting is simply lost, which is the honest outcome — see StudyStore's open-sitting fields.
        studyStore.closeSession()
        studyStore.flushNow()
        notesStore.flushNow()
        diaryStore.flushNow()
    }
}

/**
 * Keeps this machine on the newest green build with nothing to click.
 *
 * ⚠️ Owned here for the usual reason and one more. The usual: anything that must outlive a screen
 * cannot live in a `remember`, and until now the update system was driven only by the ABOUT screen,
 * so a machine left on the news page never learned an update existed. The extra reason: the install
 * itself has to happen in `onCloseRequest`, which is a `Window` parameter and can reach nothing
 * inside the composition. See [DesktopAutoUpdater] for why closing is the only moment Windows
 * Installer can actually replace this program's own files.
 *
 * ⚠️ Its HTTP client is its own and deliberately has **no disk cache**. Two OkHttp caches over one
 * directory corrupt each other, the shell builds its own client for the feeds, and a release check
 * plus a one-off installer download are exactly the two requests that gain nothing from caching.
 */
private val autoUpdaterScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private val autoUpdater = DesktopAutoUpdater(
    scope = autoUpdaterScope,
    updater = DesktopUpdater(
        HttpClient.create(HttpClient.defaultJson(), cacheDir = null),
        settingsStore,
    ),
    autoCheckEnabled = { runCatching { settingsStore.current().autoCheckUpdates }.getOrDefault(true) },
)

/**
 * ⚠️ Takes arguments now, and three different things can be asking.
 *
 * Windows starts this same executable as a **screensaver** (with `/s`, `/c` or `/p <hwnd>`, because
 * a copy of the launcher named `LCARS.scr` is a working screensaver — see [ScreenSaver]) and as an
 * **hourly update pass** (with `--update-only`, see [ScheduledUpdate]). Neither of those wants a
 * console window, and both must return quickly, so they are answered before anything else is built.
 */
fun main(args: Array<String>) {
    crashReporter.install(BuildInfo.display)

    // How the shared HTTP client introduces itself. Several feeds answer differently depending on what
    // they think they are talking to, and this is a Windows program rather than a phone — so it is set
    // once here, before anything fetches, rather than left reading the Android default.
    HttpClient.USER_AGENT = "PulseDesktop/1.0 (Windows; +https://localhost) okhttp"

    // The hourly task, first: it is the most explicit request and it wants no window at all.
    if (args.any { it.equals(ScheduledUpdate.UPDATE_ONLY_FLAG, ignoreCase = true) }) {
        // Nothing is shown and nothing is written to a screen, because there is no screen. The pass
        // records its own outcome in one line for the ABOUT page to report, so "why am I not on the
        // newest build" has an answer — see ScheduledUpdate.lastPass.
        runBlocking {
            ScheduledUpdate.runPass(
                DesktopUpdater(HttpClient.create(HttpClient.defaultJson(), cacheDir = null), settingsStore),
            )
        }
        return
    }

    when (ScreenSaver.modeOf(args)) {
        // ⚠️ Deliberately nothing. Honouring the preview means parenting a window into the Settings
        // dialog's HWND, which needs a native handle Compose does not expose — so the little monitor
        // in Windows' own screensaver settings stays blank while the screensaver itself works. Said
        // out loud in [ScreenSaver]'s notes rather than left to be discovered.
        ScreenSaver.Mode.PREVIEW -> return

        ScreenSaver.Mode.FULL_SCREEN -> {
            // The picture the console last drew, full screen, gone at a keystroke. No repositories,
            // no HTTP client, no stores — a screensaver has to be on screen the moment the machine
            // goes idle, and one that started six feeds first would be visibly late.
            application {
                StandbyScreenSaverWindow(LockScreenImage.imageFile()) { exitApplication() }
            }
            return
        }

        // Windows asking for the configuration dialog. The console *is* the configuration dialog, so
        // it opens normally — on the settings page, since that is what was asked for.
        ScreenSaver.Mode.CONFIGURE -> startOnSettings = true
        ScreenSaver.Mode.NONE -> Unit
    }

    // ⚠️ Claimed, but never enforced. This tells the hourly update pass whether to wait for a window
    // to close before it upgrades; it is not a rule about how many consoles a person may open, and
    // refusing to launch because a lock file could not be written would turn a convenience into an
    // outage. See [SingleInstance].
    SingleInstance.claim()
    // Any request left over from a pass that was killed. Consumed here so it cannot quit the console
    // the instant it finishes starting.
    SingleInstance.clearQuitRequest()

    // A local file read, not a network call — a one-time blocking read at startup to seed the initial
    // window size is negligible and keeps main() simple; every later access goes through the coroutine API.
    val initial = runBlocking { settingsStore.current() }

    // Starts a slow background poll; it downloads quietly and installs on close. Nothing it does can
    // reach the screen, so a failure here costs a build's delay rather than a working launch.
    autoUpdater.start()

    // ⚠️ Registered on every launch, not once. `schtasks /f` overwrites, and the task's command line
    // holds the launcher's path — which changes when the install location does. A task registered
    // once at first run and never refreshed would keep pointing at wherever the app used to be, and
    // an hourly task launching a file that is no longer there fails silently forever.
    autoUpdaterScope.launch { runCatching { ScheduledUpdate.install() } }

    application {
        val state = rememberWindowState(size = DpSize(initial.windowWidth.dp, initial.windowHeight.dp))

        // ⚠️ The console closing itself so an upgrade can proceed — the half of the hourly update
        // pass that lives inside the app. Windows Installer cannot replace a file this process holds
        // open, so a machine left open all week could never be upgraded without this.
        //
        // Deliberately does NOT call `autoUpdater.installOnExit()`: the scheduled task owns this
        // install and is already waiting for us to go, and two msiexec runs over one MSI is a race
        // with nothing to gain.
        LaunchedEffect(Unit) {
            while (true) {
                delay(QUIT_POLL_MS)
                if (SingleInstance.quitRequested()) {
                    runCatching { flushEverything() }
                    livePlayer.dispose()
                    radioPlayer.dispose()
                    SingleInstance.release()
                    exitApplication()
                    return@LaunchedEffect
                }
            }
        }

        Window(
            onCloseRequest = {
                // Blocking, not fire-and-forget: Compose Desktop's exitApplication() calls
                // System.exit(0) right after this returns, with no shutdown hook to wait for a
                // background save — a fire-and-forget write would race the process dying. The
                // write is a few bytes of local JSON, so blocking the AWT event thread for it
                // here is negligible.
                runBlocking {
                    settingsStore.update {
                        it.copy(
                            windowWidth = state.size.width.value.toInt(),
                            windowHeight = state.size.height.value.toInt(),
                        )
                    }
                }
                flushEverything()
                // Nothing to save here — this is releasing a native decoder and a live socket before
                // the process is killed out from under them.
                livePlayer.dispose()
                radioPlayer.dispose()
                SingleInstance.release()
                // ⚠️ Last, and after every save: this hands a staged upgrade to Windows and returns
                // immediately, because msiexec was launched detached so it outlives this process.
                // It has to be here rather than anywhere else — Windows Installer cannot replace
                // files a running program holds open, so closing is the only moment the upgrade can
                // actually happen. Nothing is shown and nothing is asked; the next launch is simply
                // the newer build.
                autoUpdater.installOnExit()
                exitApplication()
            },
            title = "LCARS",
            state = state,
            // ⚠️ `onKeyEvent`, NOT `onPreviewKeyEvent`. Preview runs from the root DOWN to whatever has
            // focus, so a handler here would see every keystroke before a text field could — which is
            // the exact bug this project already shipped once, where typing a digit into a filter box
            // changed the television channel instead. Bubbling means a focused field keeps what it
            // handles and only the combinations nothing wanted reach the console.
            onKeyEvent = { consoleKeys.handle(it) },
        ) {
            PulseDesktopApp(
                settingsStore,
                libraryRepository,
                packStore,
                studyStore,
                livePlayer,
                radioPlayer,
                notesStore,
                diaryStore,
                crashReporter,
                consoleKeys,
                // Handing over to the installer. Flushed the same way the close button does, because an
                // upgrade that lost the last answered study card would be a poor trade for being current.
                onQuitForInstall = {
                    flushEverything()
                    livePlayer.dispose()
                    radioPlayer.dispose()
                    SingleInstance.release()
                    exitApplication()
                },
                startOn = if (startOnSettings) Screen.SETTINGS else null,
            )
        }
    }
}
