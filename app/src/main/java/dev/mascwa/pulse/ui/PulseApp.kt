package dev.mascwa.pulse.ui

import androidx.compose.foundation.layout.WindowInsets
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
import dev.mascwa.pulse.feature.grid.GridHubScreen
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
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    fun navigateTopLevel(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val nw = dev.mascwa.pulse.ui.theme.Pulse.colors
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = nw.void,
        bottomBar = {
            NavigationBar(
                containerColor = nw.carbon,
                tonalElevation = 0.dp,
            ) {
                TOP_DESTINATIONS.forEach { dest ->
                    val selected = currentRoute == dest.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = { navigateTopLevel(dest.route) },
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
                    ),
                )
            }
            composable(Routes.NEWS) {
                val vm: NewsViewModel = viewModel(factory = factory)
                NewsScreen(vm)
            }
            composable(Routes.MARKETS) {
                val vm: MarketsViewModel = viewModel(factory = factory)
                MarketsScreen(vm)
            }
            composable(Routes.WEATHER) {
                val vm: WeatherViewModel = viewModel(factory = factory)
                WeatherScreen(vm)
            }
            composable(Routes.SETTINGS) {
                val vm: SettingsViewModel = viewModel(factory = factory)
                SettingsScreen(vm)
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

            // ---- Grid hub + Sky (Phase 1) ----
            val openRoute: (String) -> Unit = { route ->
                navController.navigate(route) { launchSingleTop = true }
            }
            composable(Routes.GRID) { GridHubScreen(onOpenRoute = openRoute) }
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
            composable(Routes.MAP) {
                val vm: dev.mascwa.pulse.feature.map.MapViewModel = viewModel(factory = factory)
                dev.mascwa.pulse.feature.map.MapScreen(vm, onBack = { navController.popBackStack() })
            }
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
