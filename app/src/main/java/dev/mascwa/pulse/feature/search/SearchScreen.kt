package dev.mascwa.pulse.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.util.openUrl
import dev.mascwa.pulse.data.settings.SearchEngine
import dev.mascwa.pulse.feature.common.NeonChip
import dev.mascwa.pulse.ui.theme.Pulse

@Composable
fun SearchScreen(vm: SearchViewModel, onBack: (() -> Unit)? = null) {
    val engine by vm.engine.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    var query by remember { mutableStateOf("") }
    val c = Pulse.colors

    fun go() {
        if (query.isBlank()) return
        keyboard?.hide()
        openUrl(context, vm.urlFor(query))
    }

    dev.mascwa.pulse.feature.common.PulseScaffold(
        title = "Search",
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        },
    ) { innerPadding ->
        Column(
            Modifier.padding(innerPadding).padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search the web") },
                singleLine = true,
                trailingIcon = { IconButton(onClick = { go() }) { Icon(Icons.Filled.Search, "Search") } },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { go() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Text("ENGINE", style = MaterialTheme.typography.labelMedium, color = c.muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchEngine.entries.forEach { e ->
                    NeonChip(e.label, selected = e == engine, onClick = { vm.setEngine(e) },
                        modifier = Modifier.padding(bottom = 8.dp))
                }
            }
            Button(onClick = { go() }, modifier = Modifier.fillMaxWidth()) {
                Text("Search with ${engine.label}")
            }
            Text(
                "Opens your query in ${engine.label} in the browser. No tracking key required.",
                style = MaterialTheme.typography.labelSmall, color = c.muted,
            )
        }
    }
}
