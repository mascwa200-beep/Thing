package dev.mascwa.pulse.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.mascwa.pulse.feature.compass.CompassScreen
import dev.mascwa.pulse.feature.compass.CompassViewModel
import dev.mascwa.pulse.feature.economy.EconomyScreen
import dev.mascwa.pulse.feature.economy.EconomyViewModel
import dev.mascwa.pulse.feature.fuel.FuelScreen
import dev.mascwa.pulse.feature.fuel.FuelViewModel
import dev.mascwa.pulse.feature.sky.OrbitalScreen
import dev.mascwa.pulse.feature.sky.OrbitalViewModel
import dev.mascwa.pulse.feature.sky.SpaceWeatherScreen
import dev.mascwa.pulse.feature.sky.SpaceWeatherViewModel
import dev.mascwa.pulse.feature.sos.SosScreen
import dev.mascwa.pulse.feature.sos.SosViewModel
import dev.mascwa.pulse.feature.survive.GuidesScreen
import dev.mascwa.pulse.feature.survive.GuidesViewModel
import dev.mascwa.pulse.feature.survive.PlacesScreen
import dev.mascwa.pulse.feature.survive.PlacesViewModel
import dev.mascwa.pulse.feature.survive.SurviveHubScreen
import dev.mascwa.pulse.feature.survive.ToolsScreen
import dev.mascwa.pulse.feature.survive.ToolsViewModel
import dev.mascwa.pulse.feature.home.HomeNav
import dev.mascwa.pulse.feature.home.HomeScreen
import dev.mascwa.pulse.feature.home.HomeViewModel
import dev.mascwa.pulse.feature.markets.MarketsScreen
import dev.mascwa.pulse.feature.markets.MarketsViewModel
import dev.mascwa.pulse.feature.news.NewsScreen
import dev.mascwa.pulse.feature.news.NewsViewModel
import dev.mascwa.pulse.feature.settings.SettingsScreen
import dev.mascwa.pulse.feature.settings.SettingsViewModel
import dev.mascwa.pulse.feature.weather.WeatherScreen
import dev.mascwa.pulse.feature.weather.WeatherViewModel
import dev.mascwa.pulse.navigation.Routes
import dev.mascwa.pulse.navigation.TOP_DESTINATIONS

@Composable
fun PulseApp(
    factory: ViewModelProvider.Factory,
    startRoute: String?,
    isOnline: Boolean = true,
    onRouteVisit: (String) -> Unit = {},
    onStartRouteConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    var offlineDismissed by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(isOnline) { if (isOnline) offlineDismissed = false }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Single, privacy-safe point to record which feature the user opened (aggregated counts only).
    LaunchedEffect(currentRoute) { currentRoute?.let(onRouteVisit) }

    fun navigateTopLevel(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // The ambient palette, which NightwireTheme provides and which now swings to the alert range when
    // the ship goes to red. This used to read `lcarsPalette` directly, to escape a stale pre-LCARS
    // default — but the redundant re-provider that made that necessary is gone, and a hardcoded read
    // would have left the bottom bar sitting in calm orange under a red alert.
    val nw = dev.mascwa.pulse.ui.theme.Pulse.colors
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = nw.void,
        bottomBar = {
            dev.mascwa.pulse.feature.common.LcarsNavBar(
                TOP_DESTINATIONS.map { dest ->
                    dev.mascwa.pulse.feature.common.LcarsNavItem(
                        key = dest.route,
                        label = dest.label,
                        icon = if (currentRoute == dest.route) dest.selectedIcon else dest.unselectedIcon,
                        selected = currentRoute == dest.route,
                        onClick = { navigateTopLevel(dest.route) },
                    )
                },
                // The bar draws its own ground and sits flush against the system bar, which is
                // black too — so it is padded for the gesture inset rather than letting the
                // navigation blocks run underneath it.
                Modifier.windowInsetsPadding(WindowInsets.navigationBars),
            )
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.HOME) {
                val vm: HomeViewModel = viewModel(factory = factory)
                HomeScreen(
                    vm,
                    HomeNav(
                        openNews = { navigateTopLevel(Routes.NEWS) },
                        openMarkets = { navigateTopLevel(Routes.MARKETS) },
                        openWeather = { navigateTopLevel(Routes.WEATHER) },
                        openEconomy = { navController.navigate(Routes.ECONOMY) { launchSingleTop = true } },
                        openFuel = { navController.navigate(Routes.FUEL) { launchSingleTop = true } },
                        openSettings = { navigateTopLevel(Routes.SETTINGS) },
                        openAssistant = { navController.navigate(Routes.JARVIS) { launchSingleTop = true } },
                        openRadar = { navController.navigate(Routes.RADAR) { launchSingleTop = true } },
                        openSpaceWeather = { navController.navigate(Routes.SPACE_WX) { launchSingleTop = true } },
                        openRoute = { route ->
                            if (TOP_DESTINATIONS.any { it.route == route }) navigateTopLevel(route)
                            else navController.navigate(route) { launchSingleTop = true }
                        },
                    ),
                )
            }
            composable(Routes.NEWS) {
                val vm: NewsViewModel = viewModel(factory = factory)
                NewsScreen(vm)
            }
            composable(Routes.MARKETS) {
                val marketsVm: MarketsViewModel = viewModel(factory = factory)
                val economyVm: EconomyViewModel = viewModel(factory = factory)
                val fuelVm: FuelViewModel = viewModel(factory = factory)
                MarketsScreen(marketsVm, economyVm, fuelVm)
            }
            composable(Routes.WEATHER) {
                val vm: WeatherViewModel = viewModel(factory = factory)
                WeatherScreen(vm)
            }
            composable(Routes.SETTINGS) {
                val vm: SettingsViewModel = viewModel(factory = factory)
                SettingsScreen(
                    vm,
                    onOpenCrashLog = { navController.navigate(Routes.CRASH_LOG) { launchSingleTop = true } },
                    onOpenSecurityAudit = { navController.navigate(Routes.SECURITY_AUDIT) { launchSingleTop = true } },
                )
            }
            composable(Routes.ECONOMY) {
                val vm: EconomyViewModel = viewModel(factory = factory)
                EconomyScreen(vm, onBack = { navController.popBackStack() })
            }
            composable(Routes.FUEL) {
                val vm: FuelViewModel = viewModel(factory = factory)
                FuelScreen(vm, onBack = { navController.popBackStack() })
            }

            val openRoute: (String) -> Unit = { route ->
                navController.navigate(route) { launchSingleTop = true }
            }

            // ---- THE MENU — the flat directory: every feature, one tap, plain English ----
            composable(Routes.MENU) {
                dev.mascwa.pulse.feature.menu.MenuScreen(
                    onOpen = { route ->
                        if (TOP_DESTINATIONS.any { it.route == route }) navigateTopLevel(route)
                        else navController.navigate(route) { launchSingleTop = true }
                    },
                )
            }

            // ---- Sky ----
            composable(Routes.COMPASS) {
                val vm: CompassViewModel = viewModel(factory = factory)
                CompassScreen(vm, onBack = { navController.popBackStack() })
            }
            composable(Routes.SPACE_WX) {
                val vm: SpaceWeatherViewModel = viewModel(factory = factory)
                SpaceWeatherScreen(vm, onBack = { navController.popBackStack() })
            }
            composable(Routes.ORBITAL) {
                val vm: OrbitalViewModel = viewModel(factory = factory)
                OrbitalScreen(vm, onBack = { navController.popBackStack() })
            }

            // ---- Survive (Phase 2) — all in the LCARS palette (matches TOOLS/QUESTS) ----
            composable(Routes.SURVIVE) {
                SurviveHubScreen(onOpenRoute = openRoute, onBack = { navController.popBackStack() })
            }
            composable(Routes.PLACES) {
                val vm: PlacesViewModel = viewModel(factory = factory)
                PlacesScreen(vm, onBack = { navController.popBackStack() })
            }
            // Optional ?guide= arg lets a notification (e.g. a survival tip about knots) open straight to a
            // specific guide; a plain "survival" navigation matches with guide=null and shows the list.
            composable(
                "${Routes.SURVIVAL}?guide={guide}",
                arguments = listOf(navArgument("guide") { nullable = true; defaultValue = null }),
            ) { backStackEntry ->
                val vm: GuidesViewModel = viewModel(factory = factory)
                val guideId = backStackEntry.arguments?.getString("guide")
                GuidesScreen(vm, onBack = { navController.popBackStack() }, initialGuideId = guideId)
            }
            composable(Routes.TOOLS) {
                val vm: ToolsViewModel = viewModel(factory = factory)
                ToolsScreen(vm, onBack = { navController.popBackStack() })
            }
            composable(Routes.SOS) {
                val vm: SosViewModel = viewModel(factory = factory)
                SosScreen(vm, onBack = { navController.popBackStack() })
            }
            composable(Routes.SAFETY) {
                val vm: dev.mascwa.pulse.feature.safety.SafetyViewModel = viewModel(factory = factory)
                dev.mascwa.pulse.feature.safety.SafetyScreen(vm, onBack = { navController.popBackStack() })
            }
            composable(Routes.HABITAT) {
                val vm: dev.mascwa.pulse.feature.survive.HabitatViewModel = viewModel(factory = factory)
                dev.mascwa.pulse.feature.survive.HabitatScreen(vm, onBack = { navController.popBackStack() })
            }

            // ---- Social & search (Phase 3) — LCARS palette ----
            composable(Routes.SOCIAL) {
                val vm: dev.mascwa.pulse.feature.social.SocialViewModel = viewModel(factory = factory)
                dev.mascwa.pulse.feature.social.SocialScreen(vm, onBack = { navController.popBackStack() })
            }
            composable(Routes.SEARCH) {
                val vm: dev.mascwa.pulse.feature.search.SearchViewModel = viewModel(factory = factory)
                dev.mascwa.pulse.feature.search.SearchScreen(vm, onBack = { navController.popBackStack() })
            }

            // ---- Live radar + device telemetry (standalone, one tap from MENU) ----
            composable(Routes.RADAR) {
                val vm: dev.mascwa.pulse.feature.tacnet.RadarViewModel = viewModel(factory = factory)
                dev.mascwa.pulse.feature.tacnet.RadarScreen(vm, onBack = { navController.popBackStack() })
            }
            composable(Routes.TELEMETRY) {
                val vm: dev.mascwa.pulse.feature.tacnet.TelemetryViewModel = viewModel(factory = factory)
                dev.mascwa.pulse.feature.tacnet.TelemetryScreen(vm, onBack = { navController.popBackStack() })
            }

            // ---- Sound (standalone, one tap from MENU) ----
            composable(Routes.RADIO) {
                val vm: dev.mascwa.pulse.feature.tacnet.RadioViewModel = viewModel(factory = factory)
                dev.mascwa.pulse.feature.tacnet.RadioScreen(vm, onBack = { navController.popBackStack() })
            }
            composable(Routes.MUSIC) {
                val vm: dev.mascwa.pulse.feature.spotify.SpotifyViewModel = viewModel(factory = factory)
                dev.mascwa.pulse.feature.spotify.MusicScreen(vm, onBack = { navController.popBackStack() })
            }

            // ---- Personal logs (standalone, one tap from MENU) ----
            composable(Routes.NOTES) {
                val vm: dev.mascwa.pulse.feature.notes.NotesViewModel = viewModel(factory = factory)
                dev.mascwa.pulse.feature.notes.NotesScreen(vm, onBack = { navController.popBackStack() })
            }
            composable(Routes.DIARY) {
                val vm: dev.mascwa.pulse.feature.diary.DiaryViewModel = viewModel(factory = factory)
                dev.mascwa.pulse.feature.diary.DiaryScreen(vm, onBack = { navController.popBackStack() })
            }

            composable(Routes.SENSORIUM) {
                val vm: dev.mascwa.pulse.feature.sensorium.SensoriumViewModel = viewModel(factory = factory)
                dev.mascwa.pulse.feature.sensorium.SensoriumScreen(vm, onBack = { navController.popBackStack() })
            }

            // ---- J.A.R.V.I.S. Matrix (on-device assistant) ----
            composable(Routes.ORACLE) {
                val vm: dev.mascwa.pulse.feature.oracle.OracleViewModel = viewModel(factory = factory)
                dev.mascwa.pulse.feature.oracle.OracleScreen(
                    vm, onOpenRoute = openRoute, onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.JARVIS) {
                val vm: dev.mascwa.pulse.feature.jarvis.JarvisViewModel = viewModel(factory = factory)
                // The console chat bar must dock FLUSH on the soft keyboard (Claude-app style). The outer
                // Scaffold pads the NavHost above the bottom nav bar with a *raw* padding that imePadding()
                // can't see, so imePadding() alone over-lifts the bar by one nav-bar height (the reported gap).
                // Consuming that padding as an inset here makes JarvisScreen's imePadding() lift by
                // (keyboard − nav-bar), seating the bar exactly on top of the keyboard. Scoped to JARVIS only.
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxSize().consumeWindowInsets(innerPadding),
                ) {
                    dev.mascwa.pulse.feature.jarvis.JarvisScreen(
                        vm,
                        onBack = { navController.popBackStack() },
                        onOpenSetup = { navController.navigate(Routes.JARVIS_SETUP) },
                    )
                }
            }
            composable(Routes.JARVIS_SETUP) {
                val vm: dev.mascwa.pulse.feature.jarvis.JarvisSetupViewModel = viewModel(factory = factory)
                dev.mascwa.pulse.feature.jarvis.JarvisSetupScreen(
                    vm,
                    onBack = { navController.popBackStack() },
                    onOpenApprovals = { navController.navigate(Routes.JARVIS_APPROVALS) },
                    onOpenMemory = { navController.navigate(Routes.JARVIS_MEMORY) },
                    onOpenDossier = { navController.navigate(Routes.JARVIS_DOSSIER) },
                )
            }
            composable(Routes.JARVIS_APPROVALS) {
                val vm: dev.mascwa.pulse.feature.jarvis.JarvisApprovalsViewModel = viewModel(factory = factory)
                dev.mascwa.pulse.feature.jarvis.JarvisApprovalsScreen(vm, onBack = { navController.popBackStack() })
            }
            composable(Routes.JARVIS_MEMORY) {
                val vm: dev.mascwa.pulse.feature.jarvis.JarvisMemoryViewModel = viewModel(factory = factory)
                dev.mascwa.pulse.feature.jarvis.JarvisMemoryScreen(vm, onBack = { navController.popBackStack() })
            }
            composable(Routes.JARVIS_DOSSIER) {
                val vm: dev.mascwa.pulse.feature.jarvis.JarvisDossierViewModel = viewModel(factory = factory)
                dev.mascwa.pulse.feature.jarvis.JarvisDossierScreen(vm, onBack = { navController.popBackStack() })
            }

            // ---- 3D cyberpunk navigation map ----
            composable(Routes.NAV) {
                val vm: dev.mascwa.pulse.feature.nav.NavViewModel = viewModel(factory = factory)
                dev.mascwa.pulse.feature.nav.NavScreen(vm, onBack = { navController.popBackStack() })
            }

            // ---- Saved places / waypoint tracker ----
            composable(Routes.OBJECTIVES) {
                val vm: dev.mascwa.pulse.feature.objectives.ObjectivesViewModel = viewModel(factory = factory)
                dev.mascwa.pulse.feature.objectives.ObjectivesScreen(vm, onBack = { navController.popBackStack() })
            }

            // ---- Diagnostics ----
            composable(Routes.CRASH_LOG) {
                val vm: dev.mascwa.pulse.feature.diagnostics.CrashLogViewModel = viewModel(factory = factory)
                dev.mascwa.pulse.feature.diagnostics.CrashLogScreen(vm, onBack = { navController.popBackStack() })
            }
            composable(Routes.SECURITY_AUDIT) {
                val vm: dev.mascwa.pulse.feature.security.SecurityAuditViewModel = viewModel(factory = factory)
                dev.mascwa.pulse.feature.security.SecurityAuditScreen(vm, onBack = { navController.popBackStack() })
            }
        }
    }

        // Auto Offline Survival Mode when there's no connection.
        if (!isOnline && !offlineDismissed) {
            dev.mascwa.pulse.feature.survive.OfflineSurvivalScreen(
                onOpenRoute = { r ->
                    offlineDismissed = true
                    navController.navigate(r) { launchSingleTop = true }
                },
                onDismiss = { offlineDismissed = true },
            )
        }
    }

    // Deep-link from a notification tap or a launcher shortcut.
    LaunchedEffect(startRoute) {
        if (!startRoute.isNullOrBlank()) {
            if (startRoute != Routes.HOME) {
                when {
                    // Legacy "tacnet" deep-links (old shortcuts/notifications) land on the MENU directory.
                    startRoute.substringBefore('?') == "tacnet" -> navigateTopLevel(Routes.MENU)
                    TOP_DESTINATIONS.any { it.route == startRoute } -> navigateTopLevel(startRoute)
                    // Launcher shortcuts + notification deep-links can target non-top routes (NAV, SOS,
                    // or an argumented one like "survival?guide=fire" — match on the base route).
                    startRoute.substringBefore('?') in SHORTCUT_ROUTES ->
                        runCatching { navController.navigate(startRoute) { launchSingleTop = true } }
                }
            }
            // Consume it so re-tapping the SAME shortcut after navigating away fires again.
            onStartRouteConsumed()
        }
    }
}

/** Non-top routes reachable directly from a launcher shortcut or a notification deep-link — each opens
 *  straight to the page it's about (see AppShortcuts + Notifier). Everything the MENU lists is here, so
 *  any surface can deep-link any feature. */
private val SHORTCUT_ROUTES = setOf(
    Routes.NAV, Routes.SOS, Routes.SURVIVAL,
    Routes.SPACE_WX, Routes.SAFETY, Routes.RADAR, Routes.ORACLE, Routes.SENSORIUM,
    Routes.PLACES, Routes.TOOLS, Routes.HABITAT,
    Routes.SURVIVE, Routes.COMPASS, Routes.ORBITAL, Routes.TELEMETRY,
    Routes.RADIO, Routes.MUSIC, Routes.NOTES, Routes.DIARY,
    Routes.OBJECTIVES, Routes.SOCIAL, Routes.SEARCH,
    Routes.ECONOMY, Routes.FUEL, Routes.CRASH_LOG, Routes.SECURITY_AUDIT,
    Routes.SETTINGS,
)
