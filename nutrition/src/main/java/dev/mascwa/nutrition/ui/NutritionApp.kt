package dev.mascwa.nutrition.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.nutrition.ui.screens.BodyScreen
import dev.mascwa.nutrition.ui.screens.HabitsScreen
import dev.mascwa.nutrition.ui.screens.LogScreen
import dev.mascwa.nutrition.data.NutritionContainer
import dev.mascwa.pulse.crash.Breadcrumbs
import dev.mascwa.nutrition.ui.screens.PlanScreen
import dev.mascwa.nutrition.ui.screens.RecipesScreen
import dev.mascwa.nutrition.ui.screens.TodayScreen
import dev.mascwa.pulse.feature.health.HealthViewModel

/**
 * The six places this app has.
 *
 * ⚠️ **Plain words, not jargon.** The LCARS application calls these MACROS, INTAKE, BODY, COACH — a
 * vocabulary that reads fine inside a Star Trek console and means nothing on a phone somebody
 * downloaded to count calories. "Today" is what you ate today; "Log" is where you add to it.
 */
enum class Tab(val label: String) {
    TODAY("Today"),
    LOG("Log"),
    BODY("Body"),
    PLAN("Plan"),
    RECIPES("Recipes"),
    HABITS("Habits"),
}

/**
 * ⚠️ **Six items is the most a Material navigation bar should carry and this is deliberately at the
 * limit.** Measured: at 411dp each slot is about 68dp wide and the longest label, "Recipes", needs
 * roughly 45dp at the 12sp Material label size, so all six fit without truncating. A seventh would
 * not, and the answer then is a different navigation pattern rather than smaller text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutritionApp(vm: HealthViewModel, container: NutritionContainer) {
    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }

    // ⚠️ **The shared view model answers back and nothing here was listening.** Every action that
    // reports — a weigh-in recorded, a measurement removed, "Copied 3 entries", the count of foods
    // logged from a meal — sets `notice`, and until this was wired the standalone app performed all
    // of them in complete silence. A control that gives no sign it worked reads as a broken one, and
    // the user's only recourse is to do it again, which for "record a weigh-in" writes a second
    // reading.
    //
    // ⚠️ Hosted here rather than per-screen, because the notice belongs to the view model and the
    // view model outlives any one tab: recording a weigh-in on Body and switching to Today before
    // the message is read must not lose it.
    val snackbars = remember { SnackbarHostState() }
    val notice by vm.notice.collectAsStateWithLifecycle()
    LaunchedEffect(notice) {
        val message = notice ?: return@LaunchedEffect
        // ⚠️ **Show first, clear afterwards, and the obvious order is the broken one.** This effect
        // is keyed on the notice, and `LaunchedEffect` cancels its coroutine whenever the key
        // changes — so clearing before showing sets the key to null, cancels this very coroutine,
        // and `showSnackbar` never reaches the screen. It compiles, it looks wired, and no message
        // is ever seen. `showSnackbar` suspends until the message is dismissed or times out, so
        // clearing after it returns is what marks it read.
        snackbars.showSnackbar(message)
        vm.clearNotice()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(tab.label, fontWeight = FontWeight.SemiBold) }) },
        snackbarHost = { SnackbarHost(snackbars) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = {
                            // ⚠️ A route name, which is exactly what the content rule permits: it
                            // says where somebody was when something went wrong and nothing about
                            // what they had eaten.
                            Breadcrumbs.drop("nav", t.name)
                            tab = t
                        },
                        icon = {},
                        label = { Text(t.label) },
                        alwaysShowLabel = true,
                    )
                }
            }
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ⚠️ Exhaustive with no `else`, deliberately: a seventh tab is then a compile error
            // until it has something to draw, rather than an entry that renders a blank page.
            when (tab) {
                Tab.TODAY -> TodayScreen(vm)
                Tab.LOG -> LogScreen(vm)
                Tab.BODY -> BodyScreen(vm)
                Tab.PLAN -> PlanScreen(vm, container)
                Tab.RECIPES -> RecipesScreen(vm)
                Tab.HABITS -> HabitsScreen(vm)
            }
        }
    }
}
