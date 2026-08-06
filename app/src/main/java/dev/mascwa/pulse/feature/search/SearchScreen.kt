package dev.mascwa.pulse.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import dev.mascwa.pulse.feature.common.LcarsIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.util.openUrl
import dev.mascwa.pulse.data.settings.SearchEngine
import dev.mascwa.pulse.feature.common.LcarsChip
import dev.mascwa.pulse.feature.common.LcarsFrame
import dev.mascwa.pulse.feature.common.LcarsHeaderBar
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

@Composable
fun SearchScreen(vm: SearchViewModel, onBack: (() -> Unit)? = null) {
    PulseScaffold(
        title = "Search",
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(LcarsIcons.ArrowBack, "Back") }
        },
    ) { innerPadding ->
        SearchBody(vm, Modifier.padding(innerPadding))
    }
}

/** The scaffold-free web-search feed (query box + engine picker) — hosted standalone in [SearchScreen]
 *  and as the SEARCH sub-tab inside the LCARS COMMS section. */
@Composable
fun SearchBody(vm: SearchViewModel, modifier: Modifier = Modifier) {
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

    Column(
        modifier.padding(horizontal = 16.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
    ) {
            LcarsHeaderBar("Query")
            LcarsFrame(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("⌕", fontFamily = JetBrainsMono, fontSize = 18.sp, color = c.accent)
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = c.ink, fontFamily = JetBrainsMono, fontSize = 15.sp),
                        cursorBrush = SolidColor(c.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { go() }),
                        modifier = Modifier.weight(1f).padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
                        decorationBox = { inner ->
                            if (query.isEmpty()) {
                                Text("Search the web…", fontFamily = JetBrainsMono, fontSize = 15.sp, color = c.muted)
                            }
                            inner()
                        },
                    )
                }
            }

            LcarsHeaderBar("Engine")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchEngine.entries.forEach { e ->
                    LcarsChip(e.label, selected = e == engine, onClick = { vm.setEngine(e) },
                        modifier = Modifier.padding(bottom = 8.dp))
                }
            }

            LcarsFrame(
                Modifier.fillMaxWidth().padding(top = 8.dp).clickable { go() },
                accent = c.accent,
            ) {
                Text(
                    "▸ SEARCH WITH ${engine.label.uppercase()}",
                    fontFamily = JetBrainsMono, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp, color = c.accent, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                "Opens your query in ${engine.label} in the browser. No tracking key required.",
                fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                modifier = Modifier.padding(top = 10.dp, bottom = 24.dp),
            )
        }
}
