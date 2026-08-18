package dev.mascwa.pulse.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.desktop.cache.DiskCache
import dev.mascwa.pulse.desktop.feature.about.AboutScreen
import dev.mascwa.pulse.desktop.feature.about.AboutViewModel
import dev.mascwa.pulse.desktop.feature.library.LibraryScreen
import dev.mascwa.pulse.desktop.feature.library.LibraryViewModel
import dev.mascwa.pulse.desktop.feature.live.LiveScreen
import dev.mascwa.pulse.desktop.feature.live.LiveViewModel
import dev.mascwa.pulse.desktop.feature.news.NewsScreen
import dev.mascwa.pulse.desktop.feature.news.NewsViewModel
import dev.mascwa.pulse.desktop.feature.packs.PacksScreen
import dev.mascwa.pulse.desktop.feature.packs.PacksViewModel
import dev.mascwa.pulse.desktop.feature.remote.RemoteScreen
import dev.mascwa.pulse.desktop.feature.remote.RemoteViewModel
import dev.mascwa.pulse.desktop.feature.search.SearchScreen
import dev.mascwa.pulse.desktop.feature.search.SearchViewModel
import dev.mascwa.pulse.desktop.feature.study.StudyScreen
import dev.mascwa.pulse.desktop.feature.study.StudyViewModel
import dev.mascwa.pulse.desktop.library.LibraryRepository
import dev.mascwa.pulse.desktop.live.LivePlayer
import dev.mascwa.pulse.desktop.library.PackRepository
import dev.mascwa.pulse.desktop.library.PackStore
import dev.mascwa.pulse.desktop.network.HttpClient
import dev.mascwa.pulse.desktop.news.NewsRepository
import dev.mascwa.pulse.desktop.study.StudyStore
import dev.mascwa.pulse.desktop.update.DesktopUpdater
import dev.mascwa.pulse.desktop.settings.DesktopSettingsStore
import dev.mascwa.pulse.desktop.theme.ChakraPetch
import dev.mascwa.pulse.desktop.theme.JetBrainsMono
import dev.mascwa.pulse.desktop.theme.Orbitron
import dev.mascwa.pulse.desktop.theme.LcarsNavItem
import dev.mascwa.pulse.desktop.theme.Pulse
import dev.mascwa.pulse.desktop.theme.PulseDesktopTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The desktop's screens, each carrying what it is for.
 *
 * ⚠️ The rail is the directory here. On the phone that job belongs to a separate flat MENU page,
 * because a phone cannot keep a directory on screen alongside the content; a desktop can, so making
 * someone open a page to read the same seven words would be worse, not better. The [description] is
 * what turns a list of nouns into something navigable by a person who has not memorised it.
 *
 * [section] groups the rail, so the shape of the app is legible at a glance rather than being a flat
 * run of equals.
 */
enum class Screen(val title: String, val section: String, val description: String) {
    REMOTE("Remote", "THIS MACHINE", "Pair with your phone and control it over the local network"),
    ABOUT("About", "THIS MACHINE", "Which build you are on, and install a newer one"),
    LIBRARY("Library", "KNOWLEDGE", "Every bundled page, by subject — works offline"),
    SEARCH("Search", "KNOWLEDGE", "Find a page, or a study card, by what you need"),
    STUDY("Study", "KNOWLEDGE", "Learn the library a piece a day, and be asked again"),
    PACKS("Packs", "KNOWLEDGE", "Add more subjects — downloads once, then offline"),
    NEWS("News", "THE WORLD", "Headlines, refreshed while you watch"),
    LIVE("Live", "THE WORLD", "Television news, in a window of its own"),
}

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
    onQuitForInstall: () -> Unit = {},
) {
    PulseDesktopTheme {
        val c = Pulse.colors
        var screen by remember { mutableStateOf(Screen.REMOTE) }

        val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
        val remoteVm = remember { RemoteViewModel(scope, settings) }
        val json = remember { HttpClient.defaultJson() }
        val newsVm = remember {
            NewsViewModel(
                scope = scope,
                repository = NewsRepository(
                    http = HttpClient.create(json, AppPaths.dataDir.toFile()),
                    cache = DiskCache(json, subdirectory = "news"),
                ),
                settings = settings,
            )
        }
        // The library and the deck are passed in, not built here — see the note in Main.kt on why they
        // have to outlive the composition. One instance each, so the resident index and the shard LRU
        // are paid for once across all three screens below.
        val packsVm = remember {
            PacksViewModel(scope, PackRepository(HttpClient.create(json), settings, packStore))
        }
        val studyVm = remember { StudyViewModel(scope, studyStore) }
        val liveVm = remember { LiveViewModel(livePlayer) }
        val libraryVm = remember { LibraryViewModel(scope, library, settings) }
        val searchVm = remember { SearchViewModel(scope, library, studyStore) }
        val aboutVm = remember {
            AboutViewModel(scope, DesktopUpdater(HttpClient.create(json), settings), settings)
        }

        // Opening a guide from STUDY or SEARCH means switching screen AND telling the reader what to
        // open — the desktop's equivalent of the Android app's `openRoute`. Hoisted here because it is
        // the only place that can do both.
        val openGuide: (String) -> Unit = { id ->
            libraryVm.open(id)
            screen = Screen.LIBRARY
        }

        Surface(color = c.void) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.width(216.dp).fillMaxHeight().background(c.carbon).padding(vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        "LCARS",
                        // The wordmark is the identity element, so it takes the console face first.
                        fontFamily = Orbitron, fontWeight = FontWeight.Bold,
                        fontSize = 20.sp, letterSpacing = 3.sp, color = c.accent,
                        modifier = Modifier.padding(start = 14.dp, bottom = 18.dp),
                    )
                    // Grouped, so the rail reads as the app's shape rather than a flat run of
                    // equals. `entries` is declaration-ordered and the enum is declared grouped, so
                    // the headers fall out of one pass with no second list to keep in step.
                    var lastSection: String? = null
                    Screen.entries.forEach { s ->
                        if (s.section != lastSection) {
                            lastSection = s.section
                            Text(
                                s.section,
                                fontFamily = JetBrainsMono, fontSize = 8.sp, letterSpacing = 1.6.sp,
                                color = c.faint,
                                modifier = Modifier.padding(start = 14.dp, top = 14.dp, bottom = 4.dp),
                            )
                        }
                        LcarsNavItem(
                            s.title,
                            selected = screen == s,
                            onClick = { screen = s },
                            description = s.description,
                        )
                    }
                    Box(Modifier.weight(1f))
                    Text(
                        "DESKTOP",
                        fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.5.sp, color = c.faint,
                        modifier = Modifier.padding(start = 14.dp),
                    )
                }

                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (screen) {
                        Screen.REMOTE -> RemoteScreen(remoteVm, Modifier.fillMaxWidth())
                        Screen.NEWS -> NewsScreen(newsVm, Modifier.fillMaxWidth())
                        Screen.LIVE -> LiveScreen(liveVm, Modifier.fillMaxWidth())
                        Screen.STUDY -> StudyScreen(studyVm, onOpenGuide = openGuide, modifier = Modifier.fillMaxWidth())
                        Screen.LIBRARY -> LibraryScreen(
                            vm = libraryVm,
                            repository = library,
                            // "STUDY THIS" from the reader: turn the guide into questions, then go
                            // answer them. Marking it read instead would record the visit and teach
                            // nothing, which is not what the button says.
                            onStudy = { id ->
                                studyVm.teach(id)
                                screen = Screen.STUDY
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Screen.PACKS -> PacksScreen(packsVm, Modifier.fillMaxWidth())
                        Screen.SEARCH -> SearchScreen(searchVm, onOpenGuide = openGuide, modifier = Modifier.fillMaxWidth())
                        Screen.ABOUT -> AboutScreen(
                            vm = aboutVm,
                            // The installer cannot replace files this process holds open, so handing over
                            // means actually letting go.
                            onQuitForInstall = onQuitForInstall,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
