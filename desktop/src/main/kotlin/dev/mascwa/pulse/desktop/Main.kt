package dev.mascwa.pulse.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.mascwa.pulse.desktop.settings.DesktopSettingsStore
import kotlinx.coroutines.runBlocking

private val settingsStore = DesktopSettingsStore()

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
                }
                exitApplication()
            },
            title = "LCARS",
            state = state,
        ) {
            PulseDesktopApp()
        }
    }
}
