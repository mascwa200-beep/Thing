package dev.mascwa.pulse.desktop

import java.nio.file.Path
import java.nio.file.Paths

/**
 * The OS-conventional per-user application-data directory for Pulse — `%APPDATA%\Pulse` on Windows (the
 * shipping target), the XDG data dir on Linux, `~/Library/Application Support/Pulse` on macOS (the other
 * two matter for local development/verification, since this repo's own dev environment is Linux). Every
 * desktop store (settings, disk cache, …) resolves its own path under this same root so they all agree on
 * where "Pulse's data" lives.
 */
object AppPaths {
    val dataDir: Path by lazy {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val home = System.getProperty("user.home").orEmpty()
        val base = when {
            os.contains("win") -> System.getenv("APPDATA")?.takeIf { it.isNotBlank() } ?: "$home/AppData/Roaming"
            os.contains("mac") -> "$home/Library/Application Support"
            else -> System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share"
        }
        Paths.get(base, "Pulse")
    }
}
