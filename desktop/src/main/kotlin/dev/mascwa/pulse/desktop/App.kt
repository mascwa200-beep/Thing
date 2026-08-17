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
import dev.mascwa.pulse.desktop.feature.library.LibraryScreen
import dev.mascwa.pulse.desktop.feature.library.LibraryViewModel
import dev.mascwa.pulse.desktop.feature.news.NewsScreen
import dev.mascwa.pulse.desktop.feature.news.NewsViewModel
import dev.mascwa.pulse.desktop.feature.remote.RemoteScreen
import dev.mascwa.pulse.desktop.feature.remote.RemoteViewModel
import dev.mascwa.pulse.desktop.feature.search.SearchScreen
import dev.mascwa.pulse.desktop.feature.search.SearchViewModel
import dev.mascwa.pulse.desktop.feature.study.StudyScreen
import dev.mascwa.pulse.desktop.feature.study.StudyViewModel
import dev.mascwa.pulse.desktop.library.LibraryRepository
import dev.mascwa.pulse.desktop.network.HttpClient
import dev.mascwa.pulse.desktop.news.NewsRepository
import dev.mascwa.pulse.desktop.study.StudyStore
import dev.mascwa.pulse.desktop.settings.DesktopSettingsStore
import dev.mascwa.pulse.desktop.theme.ChakraPetch
import dev.mascwa.pulse.desktop.theme.JetBrainsMono
import dev.mascwa.pulse.desktop.theme.LcarsNavItem
import dev.mascwa.pulse.desktop.theme.Pulse
import dev.mascwa.pulse.desktop.theme.PulseDesktopTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The desktop's screens. Still not enough of them to warrant a navigation library. */
enum class Screen(val title: String) {
    REMOTE("Remote"),
    NEWS("News"),
    STUDY("Study"),
    LIBRARY("Library"),
    SEARCH("Search"),
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
    studyStore: StudyStore,
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
        val studyVm = remember { StudyViewModel(scope, studyStore) }
        val libraryVm = remember { LibraryViewModel(scope, library, settings) }
        val searchVm = remember { SearchViewModel(scope, library, studyStore) }

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
                    Modifier.width(168.dp).fillMaxHeight().background(c.carbon).padding(vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        "LCARS",
                        fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                        fontSize = 20.sp, letterSpacing = 4.sp, color = c.accent,
                        modifier = Modifier.padding(start = 14.dp, bottom = 18.dp),
                    )
                    Screen.entries.forEach { s ->
                        LcarsNavItem(s.title, selected = screen == s, onClick = { screen = s })
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
                        Screen.SEARCH -> SearchScreen(searchVm, onOpenGuide = openGuide, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}
