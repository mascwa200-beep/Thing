package dev.mascwa.pulse.feature.survive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.data.survival.Guide
import dev.mascwa.pulse.feature.common.PipFrame
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

@Composable
fun GuidesScreen(vm: GuidesViewModel, onBack: (() -> Unit)? = null) {
    val guides by vm.guides.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<Guide?>(null) }
    val c = Pulse.colors

    PulseScaffold(
        title = selected?.title ?: "Survival Guides",
        navigationIcon = {
            IconButton(onClick = { if (selected != null) selected = null else onBack?.invoke() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
        },
    ) { innerPadding ->
        val sel = selected
        if (sel == null) {
            LazyColumn(
                modifier = Modifier.padding(innerPadding),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text("Works offline — bundled in the app.",
                        fontFamily = JetBrainsMono, fontSize = 10.sp,
                        color = c.muted, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
                }
                items(guides, key = { it.id }) { g ->
                    PipFrame(Modifier.fillMaxWidth().clickable { selected = g }) {
                        Column {
                            Text(g.category.uppercase(), fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.accent)
                            Text(g.title, style = MaterialTheme.typography.titleMedium, color = c.ink,
                                modifier = Modifier.padding(top = 2.dp))
                            Text(g.summary, style = MaterialTheme.typography.bodyMedium, color = c.muted,
                                modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(sel.sections, key = { it.heading }) { section ->
                    Column {
                        Text(section.heading, style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold, color = c.accent)
                        Text(section.body, style = MaterialTheme.typography.bodyMedium, color = c.ink2,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}
