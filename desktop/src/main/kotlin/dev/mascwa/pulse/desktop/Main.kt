package dev.mascwa.pulse.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.mascwa.pulse.desktop.library.LibraryRepository
import dev.mascwa.pulse.desktop.settings.DesktopSettingsStore
import dev.mascwa.pulse.desktop.study.StudyStore
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
private val libraryRepository = LibraryRepository()
private val studyStore = StudyStore(libraryRepository)

fun main() {
    // A local file read, not a network call — a one-time blocking read at startup to seed the initial
    // window size is negligible and keeps main() simple; every later access goes through the coroutine API.
    val initial = runBlocking { settingsStore.current() }

    application {
        val state = rememberWindowState(size = DpSize(initial.windowWidth.dp, initial.windowHeight.dp))
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
                    settingsStore.flushNow()
                    // The window being open was the sitting; bank the time before the process dies.
                    // An unbanked sitting is simply lost, which is the honest outcome — see the note
                    // on StudyStore's open-sitting fields.
                    studyStore.closeSession()
                    // The schedule is the whole point of the study feature; losing the last answer to
                    // a debounce window would make it quietly unreliable.
                    studyStore.flushNow()
                }
                exitApplication()
            },
            title = "LCARS",
            state = state,
        ) {
            PulseDesktopApp(
                settingsStore,
                libraryRepository,
                studyStore,
                // Handing over to the installer. Flushed the same way the close button does, because an
                // upgrade that lost the last answered study card would be a poor trade for being current.
                onQuitForInstall = {
                    runBlocking {
                        settingsStore.flushNow()
                        studyStore.closeSession()
                        studyStore.flushNow()
                    }
                    exitApplication()
                },
            )
        }
    }
}
