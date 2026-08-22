package dev.mascwa.pulse.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.core.cache.DiskCache
import dev.mascwa.pulse.desktop.feature.about.AboutScreen
import dev.mascwa.pulse.desktop.feature.about.AboutViewModel
import dev.mascwa.pulse.desktop.feature.library.LibraryScreen
import dev.mascwa.pulse.desktop.feature.library.LibraryViewModel
import dev.mascwa.pulse.desktop.feature.live.LiveScreen
import dev.mascwa.pulse.desktop.feature.live.LiveViewModel
import dev.mascwa.pulse.desktop.feature.radio.RadioScreen
import dev.mascwa.pulse.desktop.feature.radio.RadioViewModel
import dev.mascwa.pulse.desktop.feature.news.NewsScreen
import dev.mascwa.pulse.desktop.feature.news.NewsViewModel
import dev.mascwa.pulse.desktop.feature.notes.DiaryScreen
import dev.mascwa.pulse.desktop.feature.notes.DiaryViewModel
import dev.mascwa.pulse.desktop.feature.notes.NotesScreen
import dev.mascwa.pulse.desktop.feature.notes.NotesViewModel
import dev.mascwa.pulse.desktop.feature.packs.PacksScreen
import dev.mascwa.pulse.desktop.feature.packs.PacksViewModel
import dev.mascwa.pulse.desktop.feature.remote.RemoteScreen
import dev.mascwa.pulse.desktop.feature.remote.RemoteViewModel
import dev.mascwa.pulse.desktop.feature.search.SearchScreen
import dev.mascwa.pulse.desktop.feature.search.SearchViewModel
import dev.mascwa.pulse.desktop.feature.settings.SettingsScreen
import dev.mascwa.pulse.desktop.feature.home.HomeScreen
import dev.mascwa.pulse.desktop.feature.home.HomeViewModel
import dev.mascwa.pulse.desktop.feature.world.AdvisoriesScreen
import dev.mascwa.pulse.desktop.feature.world.AdvisoriesViewModel
import dev.mascwa.pulse.desktop.feature.world.EconomyScreen
import dev.mascwa.pulse.desktop.feature.world.EconomyViewModel
import dev.mascwa.pulse.desktop.feature.world.FuelScreen
import dev.mascwa.pulse.desktop.feature.world.FuelViewModel
import dev.mascwa.pulse.desktop.feature.world.MarketsScreen
import dev.mascwa.pulse.desktop.feature.world.MarketsViewModel
import dev.mascwa.pulse.desktop.feature.world.ObservatoryScreen
import dev.mascwa.pulse.desktop.feature.world.ObservatoryViewModel
import dev.mascwa.pulse.desktop.feature.world.PlacesScreen
import dev.mascwa.pulse.desktop.feature.diagnostics.CrashScreen
import dev.mascwa.pulse.desktop.feature.diagnostics.CrashViewModel
import dev.mascwa.pulse.desktop.feature.ledger.AnomaliesViewModel
import dev.mascwa.pulse.desktop.feature.world.MapScreen
import dev.mascwa.pulse.desktop.feature.world.MapViewModel
import dev.mascwa.pulse.desktop.feature.world.PlacesViewModel
import dev.mascwa.pulse.desktop.feature.world.RadarScreen
import dev.mascwa.pulse.desktop.feature.world.RadarViewModel
import dev.mascwa.pulse.desktop.feature.world.SafetyScreen
import dev.mascwa.pulse.desktop.feature.world.SafetyViewModel
import dev.mascwa.pulse.desktop.feature.world.SpaceWeatherScreen
import dev.mascwa.pulse.desktop.feature.world.SpaceWeatherViewModel
import dev.mascwa.pulse.desktop.feature.world.WildlifeScreen
import dev.mascwa.pulse.desktop.feature.world.WeatherScreen
import dev.mascwa.pulse.desktop.feature.world.WeatherViewModel
import dev.mascwa.pulse.desktop.feature.world.WildlifeViewModel
import dev.mascwa.pulse.desktop.feature.settings.SettingsViewModel
import dev.mascwa.pulse.desktop.feature.study.StudyScreen
import dev.mascwa.pulse.desktop.feature.study.StudyViewModel
import dev.mascwa.pulse.desktop.library.LibraryRepository
import dev.mascwa.pulse.desktop.library.PackRepository
import dev.mascwa.pulse.desktop.library.PackStore
import dev.mascwa.pulse.desktop.live.LiveCatalogRepository
import dev.mascwa.pulse.desktop.live.LivePlayer
import dev.mascwa.pulse.desktop.location.IpLocationService
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.desktop.news.NewsRepository
import dev.mascwa.pulse.desktop.reader.ReaderRepository
import dev.mascwa.pulse.data.economy.EconomyRepository
import dev.mascwa.pulse.data.fuel.FuelRepository
import dev.mascwa.pulse.data.economy.WorldBankClient
import dev.mascwa.pulse.data.markets.MarketsRepository
import dev.mascwa.pulse.data.orbital.LaunchRepository
import dev.mascwa.pulse.data.orbital.OrbitalRepository
import dev.mascwa.pulse.data.orbital.TleRepository
import dev.mascwa.pulse.data.places.OverpassRepository
import dev.mascwa.pulse.data.radio.RadioBrowserRepository
import dev.mascwa.pulse.data.radar.RadarRepository
import dev.mascwa.pulse.data.safety.SafetyRepository
import dev.mascwa.pulse.data.social.SocialRepository
import dev.mascwa.pulse.data.settings.FuelPreferences
import dev.mascwa.pulse.data.settings.MarketPreferences
import dev.mascwa.pulse.data.space.SpaceWeatherRepository
import dev.mascwa.pulse.data.weather.WeatherRepository
import dev.mascwa.pulse.desktop.settings.DesktopSettingsStore
import dev.mascwa.pulse.desktop.settings.DesktopUnits
import dev.mascwa.pulse.desktop.settings.LocalUnits
import dev.mascwa.pulse.desktop.settings.UnitPrefs
import dev.mascwa.pulse.desktop.study.StudyStore
import dev.mascwa.pulse.desktop.theme.ChakraPetch
import dev.mascwa.pulse.desktop.theme.JetBrainsMono
import dev.mascwa.pulse.desktop.theme.LcarsCorner
import dev.mascwa.pulse.desktop.theme.LcarsGhostButton
import dev.mascwa.pulse.desktop.theme.LcarsScreenFrame
import dev.mascwa.pulse.desktop.theme.LocalConsoleSection
import dev.mascwa.pulse.desktop.theme.Orbitron
import dev.mascwa.pulse.desktop.theme.ProvideStardate
import dev.mascwa.pulse.desktop.theme.Pulse
import dev.mascwa.pulse.desktop.theme.PulseDesktopTheme
import dev.mascwa.pulse.desktop.theme.lcarsBlockShape
import dev.mascwa.pulse.desktop.update.DesktopUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The desktop shell: a left LCARS rail and the selected screen.
 *
 * Dependencies are built once here rather than in a container object — the graph is four objects deep, so
 * a DI framework (or even the Android app's manual `AppContainer`) would be more ceremony than the module
 * currently earns. It grows into one if the graph does.
 */
@Composable
fun PulseDesktopApp(
    settings: DesktopSettingsStore,
    library: LibraryRepository,
    packStore: PackStore,
    studyStore: StudyStore,
    livePlayer: LivePlayer,
    radioPlayer: dev.mascwa.pulse.desktop.radio.RadioPlayer,
    notesStore: dev.mascwa.pulse.desktop.notes.NotesStore,
    diaryStore: dev.mascwa.pulse.desktop.notes.DiaryStore,
    crashReporter: dev.mascwa.pulse.desktop.diagnostics.CrashReporter,
    /**
     * The window's key handler, which the shell fills in — see [ConsoleKeys]. Defaulted so a caller
     * that has no window (a test, a preview) needs to know nothing about shortcuts.
     */
    keys: ConsoleKeys = remember { ConsoleKeys() },
    onQuitForInstall: () -> Unit = {},
    /**
     * Where to open, when something other than a person opened us — Windows asking the screensaver
     * to be configured lands on SETTINGS. Null means the ordinary launch.
     */
    startOn: Screen? = null,
) {
    PulseDesktopTheme {
        val c = Pulse.colors
        // Home, not the pairing page: the first thing this machine should show is what is going on,
        // not a setup screen you have already been through.
        var screen by remember { mutableStateOf(startOn ?: Screen.HOME) }

        val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
        val remoteVm = remember { RemoteViewModel(scope, settings) }
        val json = remember { HttpClient.defaultJson() }

        // ⚠️ ONE client and ONE cache for everything that fetches, which is what the phone's
        // `AppContainer` does and what the desktop was NOT doing — it built a fresh `HttpClient` per
        // feature, so the connection pool, the dispatcher's per-host limits and the HTTP cache were all
        // fragmented four ways. The disk cache is the sharper case: each instance carries its own Mutex,
        // so several of them over one directory serialise nothing against each other.
        val http = remember { HttpClient.create(json, AppPaths.dataDir.toFile()) }
        val cache = remember { DiskCache(AppPaths.dataDir.toFile(), json) }

        // ⚠️ Four repositories are hoisted out of their view models because ADVISORIES reads the same
        // feeds the screens below present. One instance each keeps their per-host rate gates and their
        // in-memory throttles honest — two `MarketsRepository`s would each think they were the only
        // caller and burst twice as hard at a venue that answers 429 to exactly that.
        val newsRepository = remember { NewsRepository(http, cache) }
        val spaceWeatherRepository = remember { SpaceWeatherRepository(http, cache) }
        val marketsRepository = remember {
            // ⚠️ The watch list is empty until the desktop grows a settings surface for one, which is
            // honest: this machine has never been told what to price. `DefaultData` on the phone comes
            // from `AppSettings`, which deliberately did not move.
            MarketsRepository(http, cache) { MarketPreferences() }
        }
        val weatherRepository = remember {
            // ⚠️ The reader's actual unit switches, not a bare `WeatherPreferences()`. Passing the
            // defaults asked Open-Meteo for Celsius and km/h however the Settings page was set, so
            // three switches were written to disk and changed nothing on screen.
            WeatherRepository(http, cache) { DesktopUnits.weatherPreferences(settings.current()) }
        }

        // The standby display — what the console shows when nobody is at it. Built HERE rather than
        // in Main.kt because it reads the same six repositories the screens do, and a second set
        // would mean a second OkHttp cache over one directory, which is the corruption the note
        // above exists to prevent.
        val standby = remember {
            dev.mascwa.pulse.desktop.standby.StandbySession(
                scope = scope,
                engine = dev.mascwa.pulse.desktop.standby.StandbyEngine(
                    settings = settings,
                    weather = weatherRepository,
                    markets = marketsRepository,
                    space = spaceWeatherRepository,
                    news = newsRepository,
                    study = studyStore,
                    tle = dev.mascwa.pulse.data.orbital.TleRepository(http, cache),
                    // What the radio is playing, if anything. A lambda so the standalone renders,
                    // which have no player at all, record it as "not asked" rather than as absent.
                    nowPlaying = {
                        // The player's own state, not a second read of the stream. Only while
                        // something is actually on air — a stopped player has nothing to say, which
                        // the engine records as empty rather than as a failure.
                        radioPlayer.state.value.station?.name
                    },
                ),
                settings = settings,
            )
        }
        LaunchedEffect(standby) { standby.start() }

        // One ledger for the whole window: the collector writes it, the ANOMALIES page reads it, and
        // the Oracle takes its strangest line. Sharing the instance is not about cost — it is a plain
        // file reader — but about there being one answer to "what is odd here" rather than three.
        val worldLedger = remember { dev.mascwa.pulse.desktop.ledger.WorldLedger() }

        // The long watch, while the window is open. Between this and the scheduled task the record
        // has no holes. ⚠️ Built HERE, over the shell's one HTTP client and one disk cache, for the
        // reason the note above the repositories gives — a second set inside the running process
        // would fragment the connection pool and the per-host rate gates it exists to keep honest.
        val longWatch = remember {
            dev.mascwa.pulse.desktop.ledger.LongWatch.Session(
                scope = scope,
                collector = dev.mascwa.pulse.desktop.ledger.LongWatch.inApp(
                    settings = settings,
                    http = http,
                    cache = cache,
                    weather = weatherRepository,
                    space = spaceWeatherRepository,
                    markets = marketsRepository,
                ),
            )
        }


        val newsVm = remember {
            NewsViewModel(
                scope = scope,
                repository = newsRepository,
                settings = settings,
                // The reader keeps no cache of its own on purpose: it goes through the same client, so
                // re-opening an article inside the HTTP cache window costs no request.
                reader = ReaderRepository(http),
                // The discussion feeds ride the same rail as the categories, so they ride the same
                // client and cache too.
                social = SocialRepository(
                    http,
                    cache,
                    lemmyInstance = { settings.current().lemmyInstance },
                    mastodonInstance = { settings.current().mastodonInstance },
                ),
            )
        }
        // The library and the deck are passed in, not built here — see the note in Main.kt on why they
        // have to outlive the composition. One instance each, so the resident index and the shard LRU
        // are paid for once across all three screens below.
        val packsVm = remember {
            PacksViewModel(scope, PackRepository(http, settings, packStore))
        }
        val studyVm = remember { StudyViewModel(scope, studyStore) }
        val liveVm = remember {
            LiveViewModel(
                scope = scope,
                player = livePlayer,
                settings = settings,
                catalogue = LiveCatalogRepository(http, cache),
            )
        }
        val libraryVm = remember { LibraryViewModel(scope, library, settings) }
        val searchVm = remember { SearchViewModel(scope, library, studyStore) }
        val aboutVm = remember {
            AboutViewModel(scope, DesktopUpdater(http, settings), settings)
        }
        // The world screens. Each takes the ONE client and ONE cache above, and each reads the
        // coordinate out of settings for itself — a tower PC has no GPS, so "we do not know where you
        // are" is an ordinary state on all of them rather than an error.
        val spaceWeatherVm = remember {
            SpaceWeatherViewModel(scope, spaceWeatherRepository, settings)
        }
        val observatoryVm = remember {
            ObservatoryViewModel(
                scope,
                // The NASA key: the desktop has no settings field for one yet, so it uses the shared
                // demo key the repository falls back to. Said here rather than left to be discovered
                // when the close-approach list starts returning nothing.
                OrbitalRepository(http, cache) { "DEMO_KEY" },
                LaunchRepository(http, cache),
                settings,
            )
        }
        val radarVm = remember {
            RadarViewModel(scope, RadarRepository(http, cache, TleRepository(http, cache)), settings)
        }
        val safetyVm = remember { SafetyViewModel(scope, SafetyRepository(http, cache), settings) }
        val placesVm = remember { PlacesViewModel(scope, OverpassRepository(http, cache), settings) }
        val mapVm = remember {
            MapViewModel(
                scope = scope,
                settings = settings,
                // ⚠️ The SAME repositories the Radar, Nearby-danger and Nearest-help screens read.
                // They cache to disk by coordinate, so a layer switched on here costs nothing beyond
                // what those screens already fetched, and the two never disagree about what is out there.
                radar = RadarRepository(http, cache, TleRepository(http, cache)),
                safety = SafetyRepository(http, cache),
                overpass = OverpassRepository(http, cache),
                cacheDir = AppPaths.dataDir.toFile(),
            )
        }
        val wildlifeVm = remember { WildlifeViewModel(scope, settings) }
        val marketsVm = remember { MarketsViewModel(scope, marketsRepository) }
        val weatherVm = remember { WeatherViewModel(scope, weatherRepository, settings) }
        val worldBank = remember { WorldBankClient(http) }
        val economyVm = remember {
            // ⚠️ The reader's own country, not a hardcoded "US". It was hardcoded because there was
            // nowhere to put the setting; there is now.
            EconomyViewModel(scope, EconomyRepository(worldBank, cache) { settings.current().countryCode })
        }
        val radioVm = remember {
            RadioViewModel(scope, settings, RadioBrowserRepository(http), radioPlayer)
        }
        val fuelVm = remember {
            FuelViewModel(
                scope,
                FuelRepository(http, marketsRepository, worldBank, cache) {
                    val s = settings.current()
                    FuelPreferences(countryCode = s.countryCode, eiaKey = s.eiaKey)
                },
            )
        }
        val homeVm = remember {
            HomeViewModel(
                scope = scope,
                settings = settings,
                weather = weatherRepository,
                markets = marketsRepository,
                news = newsRepository,
                study = studyStore,
            )
        }
        val advisoriesVm = remember {
            AdvisoriesViewModel(
                scope = scope,
                settings = settings,
                weather = weatherRepository,
                markets = marketsRepository,
                space = spaceWeatherRepository,
                news = newsRepository,
                study = studyStore,
                // The same ledger the ANOMALIES page reads, so the advisory and the wall can never
                // disagree about what the strangest reading on this machine is.
                ledger = worldLedger,
            )
        }

        val anomaliesVm = remember { AnomaliesViewModel(scope, settings, worldLedger) }

        val crashVm = remember { CrashViewModel(scope, crashReporter) }
        val notesVm = remember { NotesViewModel(scope, notesStore) }
        val diaryVm = remember { DiaryViewModel(scope, diaryStore) }
        val settingsVm = remember {
            SettingsViewModel(scope, settings, IpLocationService(http))
        }

        // Opening a guide from STUDY or SEARCH means switching screen AND telling the reader what to
        // open — the desktop's equivalent of the Android app's `openRoute`. Hoisted here because it is
        // the only place that can do both.
        val openGuide: (String) -> Unit = { id ->
            libraryVm.open(id)
            screen = Screen.LIBRARY
        }
        val studyGuide: (String) -> Unit = { id ->
            studyVm.teach(id)
            screen = Screen.STUDY
        }

        // ⚠️ Built with `remember` keyed on nothing, so it is one instance for the process. Every view
        // model in it is already a single instance; this only bundles them so a second host can be
        // handed all of them without twenty-six parameters.
        val vms = remember {
            DeskViewModels(
                library = library,
                about = aboutVm, advisories = advisoriesVm, anomalies = anomaliesVm, crash = crashVm, diary = diaryVm,
                economy = economyVm, fuel = fuelVm, home = homeVm, libraryVm = libraryVm,
                live = liveVm, map = mapVm, markets = marketsVm, news = newsVm, notes = notesVm,
                observatory = observatoryVm, packs = packsVm, places = placesVm, radar = radarVm,
                radio = radioVm, remote = remoteVm, safety = safetyVm, search = searchVm,
                settings = settingsVm, spaceWeather = spaceWeatherVm, study = studyVm,
                weather = weatherVm, wildlife = wildlifeVm,
            )
        }

        // Which screens are torn off into windows of their own, in the order they were torn off.
        // ⚠️ A list rather than a set: the order is what someone sees in their taskbar, and a set
        // reorders on every change, which would shuffle the windows' identities under `key`.
        var poppedOut by remember { mutableStateOf(listOf<Screen>()) }
        // Asking an existing window to come forward. The tick is what makes a second request for the
        // same window fire again — the screen alone would be an unchanged key and the effect would not
        // re-run, so clicking the directory twice would raise it once.
        var raise by remember { mutableStateOf<Screen?>(null) }
        var raiseTick by remember { mutableStateOf(0) }

        val openScreen: (Screen) -> Unit = { target ->
            if (target in poppedOut) {
                // It already has a window. Raise that rather than drawing a second live copy of one
                // screen — confusing on the ordinary screens, and genuinely wrong on any holding a
                // native surface.
                raise = target
                raiseTick++
            } else {
                screen = target
            }
        }
        val popOut: (Screen) -> Unit = { target ->
            if (canPopOut(target) && target !in poppedOut) {
                poppedOut = poppedOut + target
                // The main pane cannot keep showing what just left it. Home is where this console
                // starts, and it is the one screen that is always worth looking at.
                if (screen == target) screen = Screen.HOME
            }
        }

        // The ops wall. Open/closed is deliberately NOT persisted — a full-screen window that comes
        // back on next launch, covering a monitor, is a surprise rather than a convenience. WHAT is on
        // it is persisted, because that is a choice someone made.
        var wallOpen by remember { mutableStateOf(false) }
        var commandOpen by remember { mutableStateOf(false) }

        // ⚠️ Kept here rather than inside the key handler so the handler stays a lookup: what a key
        // MEANS is [consoleCommandFor]'s pure decision, and what to DO about it is this.
        val runCommand: (ConsoleCommand) -> Unit = { cmd ->
            when (cmd) {
                ConsoleCommand.OPEN_COMMAND_BAR -> commandOpen = true
                ConsoleCommand.TOGGLE_OPS_WALL -> wallOpen = !wallOpen
                ConsoleCommand.CLOSE_OVERLAY -> commandOpen = false
                ConsoleCommand.POP_OUT_CURRENT -> popOut(screen)
            }
        }
        // ⚠️ Published every composition rather than once, because both fields go stale: the callback
        // closes over `screen`, so a stale one would tear off whatever was showing when the shell first
        // composed, and `overlayOpen` decides whether Escape has anything to close.
        SideEffect {
            keys.overlayOpen = commandOpen
            keys.onCommand = runCommand
        }

        // Where you are, for the frame's header readout — one provider around everything, so no
        // screen has to know its own name. Same arrangement as the phone's.
        // The unit switches, read once for the whole app. Collected rather than fetched so flipping
        // one redraws every screen holding a distance or a clock time — see [LocalUnits].
        val prefs by settings.settingsFlow.collectAsState()
        val units = UnitPrefs(miles = prefs.miles, twelveHourClock = prefs.twelveHourClock)

        // Keyed on the switch, so turning the long watch off stops the timer now rather than at the
        // next launch. ⚠️ Placed here rather than beside the session it drives, because `prefs` is
        // collected at this point — a second `collectAsState` over the same flow just to read one
        // boolean would be a duplicate subscription for nothing.
        LaunchedEffect(prefs.longWatch) {
            if (prefs.longWatch) longWatch.start() else longWatch.stop()
        }
        CompositionLocalProvider(
            LocalConsoleSection provides (DESK_SECTION[screen] ?: ""),
            LocalUnits provides units,
        ) {
            ProvideStardate {
                // The torn-off windows. Declared inside the composition on purpose — see [PopOutWindows]
                // — so they read the same view models the main pane does rather than a second graph.
                PopOutWindows(
                    open = poppedOut,
                    vms = vms,
                    units = units,
                    raise = raise,
                    raiseTick = raiseTick,
                    onClose = { poppedOut = poppedOut - it },
                    onOpenGuide = openGuide,
                    onStudyGuide = studyGuide,
                )
                // Rung C of the standby display: its own always-on-top panel. Declared here for the
                // same reason the torn-off windows are — a Compose Desktop `Window` is a plain
                // composable, so this one reads the session the shell already built rather than
                // standing up a second set of repositories to feed a second copy of the same view.
                if (prefs.standbyHud) {
                    dev.mascwa.pulse.desktop.standby.StandbyHudWindow(standby) {
                        // Closing the panel switches it off rather than merely hiding it. A window
                        // that came back on the next launch after being deliberately dismissed would
                        // read as the app ignoring you — the same reasoning as the phone's overlay.
                        scope.launch { settings.update { it.copy(standbyHud = false) } }
                    }
                }
                if (wallOpen) {
                    OpsWallWindow(
                        // ⚠️ Resolved by NAME, and an unknown one is dropped rather than crashing.
                        // The stored list outlives the enum it names, so a screen that was removed
                        // must simply stop appearing.
                        wall = prefs.opsWall.mapNotNull { name ->
                            runCatching { Screen.valueOf(name) }.getOrNull()
                        }.filter { canPutOnWall(it) },
                        vms = vms,
                        units = units,
                        onClose = { wallOpen = false },
                        onSetWall = { next ->
                            scope.launch {
                                settings.update { it.copy(opsWall = next.map(Screen::name)) }
                            }
                        },
                        onOpenGuide = openGuide,
                        onStudyGuide = studyGuide,
                    )
                }
                Surface(color = c.void) {
                    val entry = DESK_ENTRIES[screen]
                    Box(Modifier.fillMaxSize()) {
                    LcarsScreenFrame(
                        title = entry?.label ?: "LCARS",
                        seed = screen.name,
                        // ⚠️ `rail = false` AND a wider `railWidth`: the frame skips its decorative
                        // block column so the directory can occupy the same slot, and the header's
                        // corner takes the same width so the console's L still closes.
                        rail = false,
                        railWidth = DIRECTORY_WIDTH,
                        actions = {
                            // ⚠️ The keyboard shortcut is on the label, because a shortcut nobody can
                            // see is a shortcut nobody uses. Same reason the ops wall says how to leave.
                            LcarsGhostButton("⌕ GO TO · CTRL+K", onClick = { commandOpen = true })
                            if (canPopOut(screen)) {
                                LcarsGhostButton("⧉ POP OUT", onClick = { popOut(screen) })
                            }
                            LcarsGhostButton("▦ OPS WALL", onClick = { wallOpen = true })
                        },
                    ) {
                        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Directory(
                                current = screen,
                                poppedOut = poppedOut,
                                onSelect = openScreen,
                                modifier = Modifier.width(DIRECTORY_WIDTH).fillMaxHeight(),
                            )
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                // ⚠️ ONE dispatch, shared with every torn-off window — see [ScreenHost].
                                // A second copy of a twenty-six-branch `when` drifts the moment a screen
                                // is added to one host and forgotten in the other.
                                ScreenHost(
                                    screen = screen,
                                    vms = vms,
                                    onOpenScreen = openScreen,
                                    onOpenGuide = openGuide,
                                    onStudyGuide = studyGuide,
                                    onQuitForInstall = onQuitForInstall,
                                )
                            }
                        }
                    }
                    if (commandOpen) {
                        // Over the whole page, so the answer to "where do I type" is never in doubt.
                        CommandScrim(onDismiss = { commandOpen = false })
                        Box(
                            Modifier.fillMaxSize().padding(top = 90.dp),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            CommandBar(
                                onPick = { target ->
                                    commandOpen = false
                                    openScreen(target)
                                },
                                onDismiss = { commandOpen = false },
                            )
                        }
                    }
                    }
                }
            }
        }
    }
}

/**
 * The directory column: every screen this machine has, grouped, always on view.
 *
 * ⚠️ **The same design as the phone's MENU, and that is the point of it being here rather than a
 * left rail of plain rows.** There, twenty-nine destinations in one vertical run meant scrolling to
 * find anything; here the answer is the same two-pane arrangement — group headers and their contents,
 * in fixed positions, no scrolling in the ordinary case. Someone who learns one console knows the
 * other.
 *
 * It occupies the frame's own rail slot, so it costs no width the console was not already spending:
 * on every other screen that column is decorative blocks, and here it is the same blocks doing work.
 */
@Composable
private fun Directory(
    current: Screen,
    /** Screens living in windows of their own — marked, because selecting one raises it instead. */
    poppedOut: List<Screen>,
    onSelect: (Screen) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Pulse.colors
    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            "LCARS",
            // The wordmark is the identity element, so it takes the console face first.
            fontFamily = Orbitron, fontWeight = FontWeight.Bold,
            fontSize = 18.sp, letterSpacing = 3.sp, color = c.accent,
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 14.dp),
        )
        DESK_GROUPS.forEach { group ->
            Text(
                group.label,
                fontFamily = JetBrainsMono, fontSize = 8.sp, letterSpacing = 1.6.sp, color = c.faint,
                modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 3.dp),
            )
            group.entries.forEach { e ->
                DirectoryBlock(
                    entry = e,
                    accent = group.accent(c),
                    selected = e.screen == current,
                    torn = e.screen in poppedOut,
                ) { onSelect(e.screen) }
            }
        }
        Box(Modifier.height(16.dp))
    }
}

@Composable
private fun DirectoryBlock(
    entry: DeskEntry,
    accent: Color,
    selected: Boolean,
    torn: Boolean,
    onClick: () -> Unit,
) {
    val c = Pulse.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(lcarsBlockShape(14.dp, LcarsCorner.TopStart))
            .background(if (selected) accent else c.raise)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Text(
            // ⚠️ The mark says where the screen IS, which is what makes clicking it raising a window
            // rather than a dead selection. Without it, a torn-off screen looks exactly like every
            // other entry and clicking it appears to do nothing — the window it raised is behind this
            // one, or on the other monitor.
            if (torn) "⧉ ${entry.label.uppercase()}" else entry.label.uppercase(),
            fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
            fontSize = 12.sp, letterSpacing = 1.sp,
            // Black on the lit block: LCARS letters a filled block in the ground colour, and the
            // group accents are all light.
            color = if (selected) c.void else c.ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            entry.description,
            fontFamily = JetBrainsMono, fontSize = 9.sp, lineHeight = 12.sp,
            color = if (selected) c.void.copy(alpha = 0.72f) else c.muted,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * ⚠️ Wider than a phone's rail because it holds a description as well as a name, and a desktop window
 * has the room to spend. It is one number so a screenshot can settle it.
 */
private val DIRECTORY_WIDTH = 232.dp
