package dev.mascwa.pulse.feature.economy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import dev.mascwa.pulse.feature.common.LcarsIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.telemetry.EconomyExplainers
import dev.mascwa.pulse.core.telemetry.EconomyVintage
import dev.mascwa.pulse.core.telemetry.Explainer
import dev.mascwa.pulse.data.economy.EconomyGroup
import dev.mascwa.pulse.data.economy.EconomyIndicator
import dev.mascwa.pulse.data.economy.IndicatorSeries
import dev.mascwa.pulse.feature.common.CyberHeader
import dev.mascwa.pulse.feature.common.ErrorState
import dev.mascwa.pulse.feature.common.ExplainerDialog
import dev.mascwa.pulse.feature.common.LoadingState
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.StaleBanner

@Composable
fun EconomyScreen(vm: EconomyViewModel, onBack: (() -> Unit)? = null) {
    PulseScaffold(
        title = "Economy",
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(LcarsIcons.ArrowBack, "Back") }
            }
        },
    ) { innerPadding ->
        EconomyBody(vm, Modifier.padding(innerPadding))
    }
}

/** The Economy feed body, scaffold-free so it can be hosted as a Markets sub-tab. */
@Composable
fun EconomyBody(vm: EconomyViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val dash = state.dashboard
    var explain by remember { mutableStateOf<IndicatorSeries?>(null) }

    PullToRefreshBox(
        isRefreshing = dash.loading && dash.data != null,
        onRefresh = { vm.refresh() },
        modifier = modifier,
    ) {
        when {
            dash.isInitialLoading -> LoadingState()
            dash.isError -> ErrorState(dash.error ?: "Error", onRetry = { vm.refresh() })
            else -> {
                val data = dash.data
                LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item { StaleBanner(dash) }
                    item {
                        CountryPicker(
                            current = state.country,
                            onSelect = { vm.setCountry(it) },
                            modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                        )
                    }
                    item { CyberHeader(data?.countryName ?: state.country) }
                    // Grouped, because nineteen indicators in one flat column is a wall rather than
                    // a picture. Series are matched back to their indicator by id, and anything the
                    // enum no longer knows about still renders — under the last group rather than
                    // vanishing, since a cached dashboard can outlive a change to the list.
                    val withData = data?.series.orEmpty().filter { it.points.isNotEmpty() }
                    val byId = EconomyIndicator.entries.associateBy { it.id }
                    EconomyGroup.entries.forEach { group ->
                        val rows = withData.filter {
                            (byId[it.indicatorId]?.group ?: EconomyGroup.entries.last()) == group
                        }
                        if (rows.isEmpty()) return@forEach
                        item(key = "grp_${group.name}") { CyberHeader(group.label) }
                        items(rows, key = { it.indicatorId }) {
                            IndicatorCard(it, Modifier.clickable { explain = it })
                        }
                    }
                    item {
                        Text(
                            "Source: World Bank Open Data (annual). Each figure shows the year it " +
                                "describes; some series lag by a year or more.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
            }
        }
    }

    explain?.let { s ->
        ExplainerDialog(
            s.indicatorTitle,
            buildList {
                add(EconomyExplainers.forIndicator(s.indicatorId, s.latest?.value))
                // How much weight the figure can carry. Null unless it is genuinely behind — a
                // warning on every card is a warning on none, and an annual series being a year
                // late is how annual series work.
                val year = s.latest?.year
                if (year != null) {
                    EconomyVintage.caution(year, System.currentTimeMillis())?.let { why ->
                        add(Explainer(EconomyVintage.describe(year, System.currentTimeMillis()), why))
                    }
                }
                // The source's own revision date — the third date, distinct from the year the figure
                // describes and from when this app fetched it.
                s.lastUpdatedMs?.let { updated ->
                    add(
                        Explainer(
                            "Source last revised",
                            // Spelled out with the year rather than run through the app's relative
                            // formatter, which drops to "Jul 13" past a week. On a card whose whole
                            // purpose is to stop three different dates being confused for each
                            // other, a date with no year would be a poor place to economise.
                            "World Bank revision date: ${revisionDate(updated)}. That is when the " +
                                "figures were revised — not the period they cover, and not when " +
                                "this app fetched them.",
                        ),
                    )
                }
            },
            onDismiss = { explain = null },
        )
    }
}

/**
 * A revision date a reader can place without guessing the year.
 *
 * Device locale on purpose: this is a date rendered for a person to read, not a value anything
 * parses back, so the reader's own conventions are the correct ones.
 */
private fun revisionDate(epochMs: Long): String =
    java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
        .format(java.util.Date(epochMs))
