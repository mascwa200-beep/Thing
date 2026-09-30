package dev.mascwa.pulse.feature.launcher

/**
 * The home console's recent-apps strip, as arithmetic over the times each app came to the front.
 *
 * Android's recents screen belongs to the system and cannot be replaced; this is LCARS's own, built
 * from the same usage record Android keeps (`UsageStatsManager`, behind Usage Access).
 *
 * Pure, so the rules run under JUnit:
 *
 * - **Newest first, one entry per app.** Switching between two apps ten times is two recents, not
 *   twenty.
 * - **Only apps the home screen can open.** The usage record also holds services, the system UI and
 *   apps with no launcher entry; a recent that cannot be tapped open is not a recent.
 * - **Never LCARS itself, and never another home screen.** Coming home is not using an app, and a
 *   strip that led with the launcher you are looking at would be the strip showing itself.
 */
object RecentApps {

    data class Use(val pkg: String, val atMs: Long)

    fun order(uses: List<Use>, launchable: Set<String>, exclude: Set<String>, limit: Int): List<String> =
        uses.asSequence()
            .filter { it.pkg in launchable && it.pkg !in exclude }
            .sortedWith(compareByDescending<Use> { it.atMs }.thenBy { it.pkg })
            .map { it.pkg }
            .distinct()
            .take(limit)
            .toList()
}
