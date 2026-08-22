package dev.mascwa.pulse.desktop

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.mascwa.pulse.desktop.feature.about.AboutScreen
import dev.mascwa.pulse.desktop.feature.about.AboutViewModel
import dev.mascwa.pulse.desktop.feature.diagnostics.CrashScreen
import dev.mascwa.pulse.desktop.feature.diagnostics.CrashViewModel
import dev.mascwa.pulse.desktop.feature.ledger.AnomaliesScreen
import dev.mascwa.pulse.desktop.feature.ledger.AnomaliesViewModel
import dev.mascwa.pulse.desktop.feature.ledger.SinceYouLeftViewModel
import dev.mascwa.pulse.desktop.feature.home.HomeScreen
import dev.mascwa.pulse.desktop.feature.home.HomeViewModel
import dev.mascwa.pulse.desktop.feature.library.LibraryScreen
import dev.mascwa.pulse.desktop.feature.library.LibraryViewModel
import dev.mascwa.pulse.desktop.feature.live.LiveScreen
import dev.mascwa.pulse.desktop.feature.live.LiveViewModel
import dev.mascwa.pulse.desktop.feature.news.NewsScreen
import dev.mascwa.pulse.desktop.feature.news.NewsViewModel
import dev.mascwa.pulse.desktop.feature.notes.DiaryScreen
import dev.mascwa.pulse.desktop.feature.notes.DiaryViewModel
import dev.mascwa.pulse.desktop.feature.notes.NotesScreen
import dev.mascwa.pulse.desktop.feature.notes.NotesViewModel
import dev.mascwa.pulse.desktop.feature.packs.PacksScreen
import dev.mascwa.pulse.desktop.feature.packs.PacksViewModel
import dev.mascwa.pulse.desktop.feature.radio.RadioScreen
import dev.mascwa.pulse.desktop.feature.radio.RadioViewModel
import dev.mascwa.pulse.desktop.feature.remote.RemoteScreen
import dev.mascwa.pulse.desktop.feature.remote.RemoteViewModel
import dev.mascwa.pulse.desktop.feature.search.SearchScreen
import dev.mascwa.pulse.desktop.feature.search.SearchViewModel
import dev.mascwa.pulse.desktop.feature.settings.SettingsScreen
import dev.mascwa.pulse.desktop.feature.settings.SettingsViewModel
import dev.mascwa.pulse.desktop.feature.study.StudyScreen
import dev.mascwa.pulse.desktop.feature.study.StudyViewModel
import dev.mascwa.pulse.desktop.feature.world.AdvisoriesScreen
import dev.mascwa.pulse.desktop.feature.world.AdvisoriesViewModel
import dev.mascwa.pulse.desktop.feature.world.EconomyScreen
import dev.mascwa.pulse.desktop.feature.world.EconomyViewModel
import dev.mascwa.pulse.desktop.feature.world.FuelScreen
import dev.mascwa.pulse.desktop.feature.world.FuelViewModel
import dev.mascwa.pulse.desktop.feature.world.MapScreen
import dev.mascwa.pulse.desktop.feature.world.MapViewModel
import dev.mascwa.pulse.desktop.feature.world.MarketsScreen
import dev.mascwa.pulse.desktop.feature.world.MarketsViewModel
import dev.mascwa.pulse.desktop.feature.world.ObservatoryScreen
import dev.mascwa.pulse.desktop.feature.world.ObservatoryViewModel
import dev.mascwa.pulse.desktop.feature.world.PlacesScreen
import dev.mascwa.pulse.desktop.feature.world.PlacesViewModel
import dev.mascwa.pulse.desktop.feature.world.RadarScreen
import dev.mascwa.pulse.desktop.feature.world.RadarViewModel
import dev.mascwa.pulse.desktop.feature.world.SafetyScreen
import dev.mascwa.pulse.desktop.feature.world.SafetyViewModel
import dev.mascwa.pulse.desktop.feature.world.SpaceWeatherScreen
import dev.mascwa.pulse.desktop.feature.world.SpaceWeatherViewModel
import dev.mascwa.pulse.desktop.feature.world.WeatherScreen
import dev.mascwa.pulse.desktop.feature.world.WeatherViewModel
import dev.mascwa.pulse.desktop.feature.world.WildlifeScreen
import dev.mascwa.pulse.desktop.feature.world.WildlifeViewModel
import dev.mascwa.pulse.desktop.library.LibraryRepository

/**
 * Every view model this console has, in one value.
 *
 * ⚠️ **The reason this exists is that a screen now has more than one host.** Before pop-out windows
 * the `when (screen)` lived inline in the shell, which was the right shape while there was exactly
 * one place a screen could be drawn. A second host — a torn-off window — would have meant a second
 * copy of that `when`, and two copies of a twenty-six-branch dispatch drift the moment a screen is
 * added to one and forgotten in the other.
 *
 * ⚠️ It is deliberately a bag of already-built view models rather than a container that builds them.
 * The graph is assembled once in the shell, where the repositories are wired, and the point of this
 * type is only that a host can be handed all of it without twenty-six parameters. Making it build
 * things would put construction somewhere a second window could accidentally trigger it, and one
 * `MarketsViewModel` per window would burst a rate-limited venue once per window.
 */
class DeskViewModels(
    val library: LibraryRepository,
    val about: AboutViewModel,
    val advisories: AdvisoriesViewModel,
    val anomalies: AnomaliesViewModel,
    val crash: CrashViewModel,
    val diary: DiaryViewModel,
    val economy: EconomyViewModel,
    val fuel: FuelViewModel,
    val home: HomeViewModel,
    val libraryVm: LibraryViewModel,
    val live: LiveViewModel,
    val map: MapViewModel,
    val markets: MarketsViewModel,
    val news: NewsViewModel,
    val notes: NotesViewModel,
    val observatory: ObservatoryViewModel,
    val packs: PacksViewModel,
    val places: PlacesViewModel,
    val radar: RadarViewModel,
    val radio: RadioViewModel,
    val remote: RemoteViewModel,
    val safety: SafetyViewModel,
    val search: SearchViewModel,
    val settings: SettingsViewModel,
    val since: SinceYouLeftViewModel,
    val spaceWeather: SpaceWeatherViewModel,
    val study: StudyViewModel,
    val weather: WeatherViewModel,
    val wildlife: WildlifeViewModel,
)

/**
 * Draw one screen.
 *
 * The single dispatch from [Screen] to a composable, so the main pane, a torn-off window and the ops
 * wall all show the same thing built the same way — and adding a screen stays one entry in
 * [DESK_GROUPS], one branch here, and nothing else.
 *
 * [onOpenScreen] is how a screen asks to go somewhere else. In the main pane that changes the pane;
 * in a torn-off window it changes what that window shows, which is what a window someone deliberately
 * separated should do rather than reaching over and rearranging the main one.
 */
@Composable
fun ScreenHost(
    screen: Screen,
    vms: DeskViewModels,
    onOpenScreen: (Screen) -> Unit,
    onOpenGuide: (String) -> Unit,
    onStudyGuide: (String) -> Unit,
    onQuitForInstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val m = modifier.fillMaxWidth()
    when (screen) {
        Screen.REMOTE -> RemoteScreen(vms.remote, m)
        Screen.NEWS -> NewsScreen(vms.news, m)
        Screen.LIVE -> LiveScreen(vms.live, m)
        Screen.SETTINGS -> SettingsScreen(vms.settings, m)
        Screen.SPACE_WEATHER -> SpaceWeatherScreen(vms.spaceWeather, m)
        Screen.OBSERVATORY -> ObservatoryScreen(vms.observatory, m)
        Screen.CRASH -> CrashScreen(vms.crash, m)
        Screen.MAP -> MapScreen(vms.map, m)
        Screen.RADAR -> RadarScreen(vms.radar, m)
        Screen.SAFETY -> SafetyScreen(vms.safety, m)
        Screen.PLACES -> PlacesScreen(vms.places, m)
        Screen.WILDLIFE -> WildlifeScreen(vms.wildlife, m)
        Screen.MARKETS -> MarketsScreen(vms.markets, m)
        Screen.WEATHER -> WeatherScreen(vms.weather, m)
        Screen.ECONOMY -> EconomyScreen(vms.economy, m)
        Screen.FUEL -> FuelScreen(vms.fuel, m)
        Screen.RADIO -> RadioScreen(vms.radio, m)
        Screen.NOTES -> NotesScreen(vms.notes, m)
        Screen.DIARY -> DiaryScreen(vms.diary, m)
        Screen.PACKS -> PacksScreen(vms.packs, m)
        Screen.HOME -> HomeScreen(
            vm = vms.home,
            // The SAME advisories view model the ADVISORIES screen reads, so the two pages can never
            // rank one machine's signals differently.
            advisories = vms.advisories,
            since = vms.since,
            onOpenScreen = onOpenScreen,
            modifier = m,
        )
        Screen.ANOMALIES -> AnomaliesScreen(
            vm = vms.anomalies,
            // Every row points at the screen that owns the reading, so "unusual" and "what is it"
            // are one tap apart.
            onOpenScreen = onOpenScreen,
            modifier = m,
        )
        Screen.ADVISORIES -> AdvisoriesScreen(
            vm = vms.advisories,
            // Acting on an advisory means going where it points, which is the one thing only a host
            // can do.
            onOpenScreen = onOpenScreen,
            modifier = m,
        )
        Screen.STUDY -> StudyScreen(vms.study, onOpenGuide = onOpenGuide, modifier = m)
        Screen.SEARCH -> SearchScreen(vms.search, onOpenGuide = onOpenGuide, modifier = m)
        Screen.LIBRARY -> LibraryScreen(
            vm = vms.libraryVm,
            repository = vms.library,
            // "STUDY THIS" from the reader: turn the guide into questions, then go answer them.
            // Marking it read instead would record the visit and teach nothing, which is not what the
            // button says.
            onStudy = onStudyGuide,
            modifier = m,
        )
        Screen.ABOUT -> AboutScreen(
            vm = vms.about,
            // The installer cannot replace files this process holds open, so handing over means
            // actually letting go.
            onQuitForInstall = onQuitForInstall,
            modifier = m,
        )
    }
}

/**
 * Whether [screen] can be torn off into a window of its own.
 *
 * ⚠️ **LIVE is the one exclusion, and it is not caution for its own sake.** It already opens a
 * detached window — a `JFrame` holding a `JFXPanel`, which is the plainest thing either toolkit does
 * and better than anything this mechanism could give it. Tearing the whole screen off as well would
 * put a second `SwingPanel` over the one player, and `LiveWindow`'s own note says exactly why that is
 * a bad trade: two hosts over one stream raise the question of which one closing should stop it.
 *
 * Everything else is fair game. A second copy of a canvas or a list costs a redraw, and the feeds
 * behind them are shared repositories with their own caches and rate gates, so a torn-off screen
 * fetches nothing its twin has not already paid for.
 */
fun canPopOut(screen: Screen): Boolean = screen != Screen.LIVE
