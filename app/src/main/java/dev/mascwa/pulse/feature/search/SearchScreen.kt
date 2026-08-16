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
import dev.mascwa.pulse.core.telemetry.DeviceSearch
import dev.mascwa.pulse.core.util.openUrl
import dev.mascwa.pulse.data.settings.SearchEngine
import dev.mascwa.pulse.feature.common.LcarsChip
import dev.mascwa.pulse.feature.common.LcarsFrame
import dev.mascwa.pulse.feature.common.LcarsHeaderBar
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

@Composable
fun SearchScreen(
    vm: SearchViewModel,
    onBack: (() -> Unit)? = null,
    onOpen: ((DeviceSearch.Result) -> Unit)? = null,
) {
    PulseScaffold(
        title = "Search",
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(LcarsIcons.ArrowBack, "Back") }
        },
    ) { innerPadding ->
        SearchBody(vm, Modifier.padding(innerPadding), onOpen)
    }
}

/**
 * The search feed: what this device already knows, then the web.
 *
 * That order is the point. The app carries hundreds of written guides, the user's notes, their
 * diary, their tasks and the assistant's memory, and until now the only thing this box could do was
 * hand the question to a search engine. Typing now answers from the device first — offline, with no
 * key and no request — and the engine picker sits below for when it genuinely is a web question.
 */
@Composable
fun SearchBody(
    vm: SearchViewModel,
    modifier: Modifier = Modifier,
    onOpen: ((DeviceSearch.Result) -> Unit)? = null,
) {
    val engine by vm.engine.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val corpus by vm.corpus.collectAsStateWithLifecycle()
    val searched by vm.searched.collectAsStateWithLifecycle()
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
                        onValueChange = { query = it; vm.onQueryChanged(it) },
                        singleLine = true,
                        textStyle = TextStyle(color = c.ink, fontFamily = JetBrainsMono, fontSize = 15.sp),
                        cursorBrush = SolidColor(c.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { go() }),
                        modifier = Modifier.weight(1f).padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
                        decorationBox = { inner ->
                            if (query.isEmpty()) {
                                Text("Search this device, or the web…", fontFamily = JetBrainsMono,
                                    fontSize = 15.sp, color = c.muted)
                            }
                            inner()
                        },
                    )
                }
            }

            // --- what the device itself can answer -------------------------------------------
            if (corpus.isNotEmpty()) {
                Text(
                    "ON THIS DEVICE · " + corpus.joinToString(" · ") { (k, n) ->
                        "$n ${k.label.lowercase()}${if (n == 1) "" else "s"}"
                    },
                    fontFamily = JetBrainsMono, fontSize = 8.sp, letterSpacing = 0.6.sp,
                    color = c.muted, modifier = Modifier.padding(top = 10.dp),
                )
            }
            if (results.isNotEmpty()) {
                LcarsHeaderBar("On this device")
                results.forEach { r -> DeviceResultRow(r, onOpen) }
            } else if (searched) {
                LcarsHeaderBar("On this device")
                Text(
                    "Nothing here matches that. The web is below.",
                    fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
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
                "Opens your query in ${engine.label} in the browser. No tracking key required. " +
                    "Everything under \"On this device\" was answered without leaving the phone.",
                fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                modifier = Modifier.padding(top = 10.dp, bottom = 24.dp),
            )
        }
}

/**
 * One thing the device knows, and where it lives.
 *
 * The kind is shown rather than inferred from the styling: a result that came from your own diary
 * and one that came from a written guide deserve to be told apart at a glance, and the reader should
 * not have to learn a colour code to do it.
 */
@Composable
private fun DeviceResultRow(result: DeviceSearch.Result, onOpen: ((DeviceSearch.Result) -> Unit)?) {
    val c = Pulse.colors
    val open = onOpen
    LcarsFrame(
        Modifier.fillMaxWidth().padding(top = 6.dp)
            .let { m -> if (open != null) m.clickable { open(result) } else m },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                result.kind.label.uppercase(), fontFamily = JetBrainsMono, fontSize = 8.sp,
                letterSpacing = 0.8.sp, color = c.accent,
            )
        }
        Text(
            result.title, fontFamily = JetBrainsMono, fontSize = 13.sp,
            fontWeight = FontWeight.Bold, color = c.ink, modifier = Modifier.padding(top = 2.dp),
        )
        result.record.entry.summary.trim().takeIf { it.isNotBlank() }?.let { body ->
            Text(
                body.replace(Regex("\\s+"), " ").take(SUMMARY_CHARS),
                fontFamily = JetBrainsMono, fontSize = 10.sp, lineHeight = 14.sp,
                color = c.ink2, modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** One or two lines of context under a result — enough to recognise it, not enough to read it here. */
private const val SUMMARY_CHARS = 160
