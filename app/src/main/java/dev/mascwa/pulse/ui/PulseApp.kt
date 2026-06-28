package dev.mascwa.pulse.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.mascwa.pulse.feature.compass.CompassScreen
import dev.mascwa.pulse.feature.compass.CompassViewModel
import dev.mascwa.pulse.feature.economy.EconomyScreen
import dev.mascwa.pulse.feature.economy.EconomyViewModel
import dev.mascwa.pulse.feature.economy.InflationScreen
import dev.mascwa.pulse.feature.fuel.FuelScreen
import dev.mascwa.pulse.feature.fuel.FuelViewModel
import dev.mascwa.pulse.feature.sky.OrbitalScreen
import dev.mascwa.pulse.feature.sky.OrbitalViewModel
import dev.mascwa.pulse.feature.sky.SkyHubScreen
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

    // Pip-Boy feed tabs: tapping a tab replaces the current feed (shallow back stack).
    val openTab: (String) -> Unit = { route ->
        if (route != currentRoute) navController.navigate(route) {
            launchSingleTop = true
            currentRoute?.let { popUpTo(it) { inclusive = true } }
        }
    }
    // The TOOLS bottom-nav returns to the last feed viewed.
    var lastFeed by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(dev.mascwa.pulse.navigation.FEED_HOME) }
    androidx.compose.runtime.LaunchedEffect(currentRoute) {
        if (currentRoute != null && currentRoute in dev.mascwa.pulse.navigation.FEED_ROUTES) lastFeed = currentRoute
    }

    val nw = dev.mascwa.pulse.ui.theme.Pulse.colors
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = nw.void,
        bottomBar = {
            NavigationBar(
                containerColor = nw.carbon,
                tonalElevation = 0.dp,
            ) {
                TOP_DESTINATIONS.forEach { dest ->
                    // TOOLS now opens the Pip-Boy feed tabs; it highlights on any feed route.
                    val isTools = dest.route == Routes.TACNET
                    val selected = if (isTools) currentRoute != null && currentRoute in dev.mascwa.pulse.navigation.FEED_ROUTES
                        else currentRoute == dest.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = { navigateTopLevel(if (isTools) lastFeed else dest.route) },
                        icon = {
                            Icon(
                                if (selected) dest.selectedIcon else dest.unselectedIcon,
                                contentDescription = dest.label,
                            )
                        },
                        label = {
                            Text(
                                dest.label,
                                fontFamily = dev.mascwa.pulse.ui.theme.JetBrainsMono,
                                fontSize = 9.sp,
                                letterSpacing = 0.6.sp,
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = nw.accent,
                            selectedTextColor = nw.accent,
                            indicatorColor = nw.accent.copy(alpha = 0.14f),
                            unselectedIconColor = nw.muted,
                            unselectedTextColor = nw.muted,
                        ),
                    )
                }
            }
        },
    ) { innerPadding ->
        val isFeed = currentRoute != null && currentRoute in dev.mascwa.pulse.navigation.FEED_ROUTES
        val feedCtx = if (isFeed) dev.mascwa.pulse.navigation.FeedTabState(currentRoute, openTab) else null
        // The whole TOOLS section wears the Fallout Pip-Boy phosphor-green palette; everything else
        // keeps the NIGHTWIRE theme. Provided once around the NavHost so feed screens re-theme with
        // no per-screen edits (the bottom nav stays base — it reads `nw` captured above the provider).
        val feedPalette = if (isFeed) dev.mascwa.pulse.ui.theme.pipBoyPalette else nw
        androidx.compose.runtime.CompositionLocalProvider(
            dev.mascwa.pulse.navigation.LocalFeedTabs provides feedCtx,
            dev.mascwa.pulse.ui.theme.LocalNightwire provides feedPalette,
        ) {
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
                        openInflation = { navController.navigate(Routes.INFLATION) { launchSingleTop = true } },
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
            composable(Routes.SPACEBALLS) {
                val vm: SettingsViewModel = viewModel(factory = factory)
                dev.mascwa.pulse.feature.spaceballs.SpaceballsScreen(vm)
            }
            composable(Routes.ECONOMY) {
                val vm: EconomyViewModel = viewModel(factory = factory)
                EconomyScreen(vm, onBack = { navController.popBackStack() })
            }
            composable(Routes.INFLATION) {
                val vm: EconomyViewModel = viewModel(factory = factory)
                InflationScreen(vm, onBack = { navController.popBackStack() })
            }
            composable(Routes.FUEL) {
                val vm: FuelViewModel = viewModel(factory = factory)
                FuelScreen(vm, onBack = { navController.popBackStack() })
            }

            // ---- Sky + hubs (Phase 1) ----
            val openRoute: (String) -> Unit = { route ->
                navController.navigate(route) { launchSingleTop = true }
            }
            composable(Routes.SKY) {
                SkyHubScreen(onOpenRoute = openRoute, onBack = { navController.popBackStack() })
            }
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

            // ---- Survive (Phase 2) — all in the Pip-Boy green palette (matches PIP-BOY/QUESTS) ----
            composable(Routes.SURVIVE) {
                PipGreen { SurviveHubScreen(onOpenRoute = openRoute, onBack = { navController.popBackStack() }) }
            }
            composable(Routes.PLACES) {
                val vm: PlacesViewModel = viewModel(factory = factory)
                PipGreen { PlacesScreen(vm, onBack = { navController.popBackStack() }) }
            }
            composable(Routes.SURVIVAL) {
                val vm: GuidesViewModel = viewModel(factory = factory)
                PipGreen { GuidesScreen(vm, onBack = { navController.popBackStack() }) }
            }
            composable(Routes.TOOLS) {
                val vm: ToolsViewModel = viewModel(factory = factory)
                PipGreen { ToolsScreen(vm, onBack = { navController.popBackStack() }) }
            }
            composable(Routes.SOS) {
                val vm: SosViewModel = viewModel(factory = factory)
                PipGreen { SosScreen(vm, onBack = { navController.popBackStack() }) }
            }
            composable(Routes.SAFETY) {
                val vm: dev.mascwa.pulse.feature.safety.SafetyViewModel = viewModel(factory = factory)
                PipGreen { dev.mascwa.pulse.feature.safety.SafetyScreen(vm, onBack = { navController.popBackStack() }) }
            }

            // ---- Social & search (Phase 3) — Pip-Boy green ----
            composable(Routes.SOCIAL) {
                val vm: dev.mascwa.pulse.feature.social.SocialViewModel = viewModel(factory = factory)
                PipGreen { dev.mascwa.pulse.feature.social.SocialScreen(vm, onBack = { navController.popBackStack() }) }
            }
            composable(Routes.SEARCH) {
                val vm: dev.mascwa.pulse.feature.search.SearchViewModel = viewModel(factory = factory)
                PipGreen { dev.mascwa.pulse.feature.search.SearchScreen(vm, onBack = { navController.popBackStack() }) }
            }

            // ---- Tacnet (real-time radar + telemetry) ----
            composable(Routes.TACNET) {
                val radarVm: dev.mascwa.pulse.feature.tacnet.RadarViewModel = viewModel(factory = factory)
                val telemetryVm: dev.mascwa.pulse.feature.tacnet.TelemetryViewModel = viewModel(factory = factory)
                val orbitalVm: dev.mascwa.pulse.feature.sky.OrbitalViewModel = viewModel(factory = factory)
                val spaceWxVm: dev.mascwa.pulse.feature.sky.SpaceWeatherViewModel = viewModel(factory = factory)
                val radioVm: dev.mascwa.pulse.feature.tacnet.RadioViewModel = viewModel(factory = factory)
                val notesVm: dev.mascwa.pulse.feature.notes.NotesViewModel = viewModel(factory = factory)
                val diaryVm: dev.mascwa.pulse.feature.diary.DiaryViewModel = viewModel(factory = factory)
                val tasksVm: dev.mascwa.pulse.feature.tasks.TasksViewModel = viewModel(factory = factory)
                val objectivesVm: dev.mascwa.pulse.feature.objectives.ObjectivesViewModel = viewModel(factory = factory)
                val navVm: dev.mascwa.pulse.feature.nav.NavViewModel = viewModel(factory = factory)
                val spotifyVm: dev.mascwa.pulse.feature.spotify.SpotifyViewModel = viewModel(factory = factory)
                dev.mascwa.pulse.feature.tacnet.PipBoyScreen(
                    radarVm, telemetryVm, orbitalVm, spaceWxVm, radioVm, notesVm, diaryVm, tasksVm, objectivesVm, navVm, spotifyVm,
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                )
            }
            composable(Routes.RADAR) {
                val vm: dev.mascwa.pulse.feature.tacnet.RadarViewModel = viewModel(factory = factory)
                dev.mascwa.pulse.feature.tacnet.RadarScreen(vm, onBack = { navController.popBackStack() })
            }
            composable(Routes.TELEMETRY) {
                val vm: dev.mascwa.pulse.feature.tacnet.TelemetryViewModel = viewModel(factory = factory)
                dev.mascwa.pulse.feature.tacnet.TelemetryScreen(vm, onBack = { navController.popBackStack() })
            }

            // ---- J.A.R.V.I.S. Matrix (on-device assistant) ----
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
                        onOpenDial = { navController.navigate(Routes.DIAL) { launchSingleTop = true } },
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

            // ---- Reactor Dial — arc-reactor rotary app launcher ----
            composable(Routes.DIAL) {
                val vm: dev.mascwa.pulse.feature.dial.ReactorDialViewModel = viewModel(factory = factory)
                dev.mascwa.pulse.feature.dial.ReactorDialScreen(vm, onClose = { navController.popBackStack() })
            }

            // ---- 3D cyberpunk navigation map (OBJECTIVES manager folded in as a sub-tab) ----
            composable(Routes.NAV) {
                val vm: dev.mascwa.pulse.feature.nav.NavViewModel = viewModel(factory = factory)
                val objVm: dev.mascwa.pulse.feature.objectives.ObjectivesViewModel = viewModel(factory = factory)
                dev.mascwa.pulse.feature.nav.NavScreen(vm, objVm, onBack = { navController.popBackStack() })
            }

            // ---- Objectives / waypoint tracker ----
            composable(Routes.OBJECTIVES) {
                val vm: dev.mascwa.pulse.feature.objectives.ObjectivesViewModel = viewModel(factory = factory)
                dev.mascwa.pulse.feature.objectives.ObjectivesScreen(vm, onBack = { navController.popBackStack() })
            }

            // ---- Quests — the objectives/quest log as its own Pip-Boy feed tab ----
            composable(Routes.QUESTS) {
                val vm: dev.mascwa.pulse.feature.objectives.ObjectivesViewModel = viewModel(factory = factory)
                dev.mascwa.pulse.feature.objectives.QuestsScreen(vm, onBack = { navController.popBackStack() })
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
                    TOP_DESTINATIONS.any { it.route == startRoute } -> navigateTopLevel(startRoute)
                    // Launcher shortcuts can target non-top routes (e.g. NAV, SOS, QUESTS).
                    startRoute in SHORTCUT_ROUTES ->
                        runCatching { navController.navigate(startRoute) { launchSingleTop = true } }
                }
            }
            // Consume it so re-tapping the SAME shortcut after navigating away fires again.
            onStartRouteConsumed()
        }
    }
}

/** Non-top routes reachable directly from a launcher shortcut (see AppShortcuts) or a deep link (e.g. the
 *  wallpaper double-tap → the Reactor Dial). */
private val SHORTCUT_ROUTES = setOf(Routes.NAV, Routes.SOS, Routes.QUESTS, Routes.DIAL)

/** Renders [content] in the Pip-Boy phosphor-green palette — used to put the SURVIVE/SOCIAL/SEARCH
 *  feeds (and their sub-screens) in the same Fallout look as the PIP-BOY and QUESTS tabs. */
@Composable
private fun PipGreen(content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        dev.mascwa.pulse.ui.theme.LocalNightwire provides dev.mascwa.pulse.ui.theme.pipBoyPalette,
        content = content,
    )
}
