package dev.mascwa.pulse.feature.sky

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.telemetry.Explainer
import dev.mascwa.pulse.feature.common.ExplainerDialog
import dev.mascwa.pulse.feature.common.LoadingState
import dev.mascwa.pulse.feature.common.SectionBar
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

/**
 * The PIP-BOY **DATA** feed — orbital readouts and space weather mixed into one scroll. Reuses each
 * feed's `LazyListScope` sections (orbitalSections / spaceWeatherSections) so there's one source of
 * truth; the space-weather metrics still tap through to their plain-English explainers.
 */
@Composable
fun DataBody(
    orbitalVm: OrbitalViewModel,
    spaceWxVm: SpaceWeatherViewModel,
    modifier: Modifier = Modifier,
) {
    val orbital by orbitalVm.state.collectAsStateWithLifecycle()
    val space by spaceWxVm.state.collectAsStateWithLifecycle()
    val c = Pulse.colors
    var explainer by remember { mutableStateOf<Pair<String, List<Explainer>>?>(null) }

    PullToRefreshBox(
        isRefreshing = (orbital.loading && orbital.data != null) || (space.loading && space.data != null),
        onRefresh = { orbitalVm.refresh(); spaceWxVm.refresh() },
        modifier = modifier,
    ) {
        if (orbital.isInitialLoading && space.isInitialLoading) {
            LoadingState()
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                orbital.data?.let { orbitalSections(it, orbital.stale, c) }
                item {
                    SectionBar("Space weather")
                }
                if (space.data != null) {
                    spaceWeatherSections(space.data, space.stale, c) { title, items -> explainer = title to items }
                } else {
                    item {
                        Text("Space weather unavailable — pull to refresh.",
                            fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
                    }
                }
            }
        }
    }

    explainer?.let { (title, items) ->
        ExplainerDialog(title, items, onDismiss = { explainer = null })
    }
}
