package dev.mascwa.nutrition.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.mascwa.nutrition.ui.screens.BodyScreen
import dev.mascwa.nutrition.ui.screens.PlanScreen
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
fun NutritionApp(vm: HealthViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(tab.label, fontWeight = FontWeight.SemiBold) }) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
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
            when (tab) {
                Tab.TODAY -> TodayScreen(vm)
                Tab.BODY -> BodyScreen(vm)
                Tab.PLAN -> PlanScreen(vm)
                Tab.LOG, Tab.RECIPES, Tab.HABITS -> NotYet(tab)
            }
        }
    }
}

/**
 * ⚠️ A screen that has not been written yet, saying so.
 *
 * The alternative — leaving the tab out until it works — was considered and rejected: the shape of
 * the app is part of what the owner is being asked to look at, and a bar that grows by two items
 * between builds is harder to judge than one that is complete and honest about which pages are
 * filled in. This composable is deleted, not emptied, as each screen lands.
 */
@Composable
private fun NotYet(tab: Tab) {
    SectionCard(tab.label, subtitle = "Not built yet.") {
        Text(
            when (tab) {
                Tab.LOG -> "Searching the bundled food database, scanning a barcode and adding what " +
                    "you ate. The data behind it is already on the phone — see Today."
                Tab.RECIPES -> "Building a recipe once and logging a helping of it afterwards."
                Tab.HABITS -> "How consistently you have been logging, and what that means for how " +
                    "far the numbers on Plan can be trusted."
                else -> ""
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
