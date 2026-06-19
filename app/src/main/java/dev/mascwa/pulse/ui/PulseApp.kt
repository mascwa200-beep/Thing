package dev.mascwa.pulse.ui

import androidx.compose.foundation.layout.WindowInsets
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
                    val isTools = dest.route == Routes.RADAR
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
        val feedCtx = if (currentRoute != null && currentRoute in dev.mascwa.pulse.navigation.FEED_ROUTES)
            dev.mascwa.pulse.navigation.FeedTabState(currentRoute, openTab) else null
        androidx.compose.runtime.CompositionLocalProvider(dev.mascwa.pulse.navigation.LocalFeedTabs provides feedCtx) {
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
                SettingsScreen(vm, onOpenCrashLog = { navController.navigate(Routes.CRASH_LOG) { launchSingleTop = true } })
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

            // ---- Survive (Phase 2) ----
            composable(Routes.SURVIVE) {
                SurviveHubScreen(onOpenRoute = openRoute, onBack = { navController.popBackStack() })
            }
            composable(Routes.PLACES) {
                val vm: PlacesViewModel = viewModel(factory = factory)
                PlacesScreen(vm, onBack = { navController.popBackStack() })
            }
            composable(Routes.SURVIVAL) {
                val vm: GuidesViewModel = viewModel(factory = factory)
                GuidesScreen(vm, onBack = { navController.popBackStack() })
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

            // ---- Social & search (Phase 3) ----
            composable(Routes.SOCIAL) {
                val vm: dev.mascwa.pulse.feature.social.SocialViewModel = viewModel(factory = factory)
                dev.mascwa.pulse.feature.social.SocialScreen(vm, onBack = { navController.popBackStack() })
            }
            composable(Routes.SEARCH) {
                val vm: dev.mascwa.pulse.feature.search.SearchViewModel = viewModel(factory = factory)
                dev.mascwa.pulse.feature.search.SearchScreen(vm, onBack = { navController.popBackStack() })
            }

            // ---- Tacnet (real-time radar + telemetry) ----
            composable(Routes.TACNET) {
                dev.mascwa.pulse.feature.tacnet.TacnetHubScreen(onOpenRoute = openRoute, onBack = { navController.popBackStack() })
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
                dev.mascwa.pulse.feature.jarvis.JarvisScreen(
                    vm,
                    onBack = { navController.popBackStack() },
                    onOpenSetup = { navController.navigate(Routes.JARVIS_SETUP) },
                )
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

            // ---- 3D cyberpunk navigation map ----
            composable(Routes.NAV) {
                val vm: dev.mascwa.pulse.feature.nav.NavViewModel = viewModel(factory = factory)
                dev.mascwa.pulse.feature.nav.NavScreen(vm, onBack = { navController.popBackStack() })
            }

            // ---- Objectives / waypoint tracker ----
            composable(Routes.OBJECTIVES) {
                val vm: dev.mascwa.pulse.feature.objectives.ObjectivesViewModel = viewModel(factory = factory)
                dev.mascwa.pulse.feature.objectives.ObjectivesScreen(vm, onBack = { navController.popBackStack() })
            }

            // ---- Diagnostics ----
            composable(Routes.CRASH_LOG) {
                val vm: dev.mascwa.pulse.feature.diagnostics.CrashLogViewModel = viewModel(factory = factory)
                dev.mascwa.pulse.feature.diagnostics.CrashLogScreen(vm, onBack = { navController.popBackStack() })
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

    // Deep-link from a notification tap.
    LaunchedEffect(startRoute) {
        if (!startRoute.isNullOrBlank() && startRoute != Routes.HOME &&
            TOP_DESTINATIONS.any { it.route == startRoute }
        ) {
            navigateTopLevel(startRoute)
        }
    }
}
