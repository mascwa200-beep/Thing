package dev.mascwa.pulse.desktop.feature.world

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.core.telemetry.Animal
import dev.mascwa.pulse.core.telemetry.AnimalHabitats
import dev.mascwa.pulse.core.telemetry.DangerLevel
import dev.mascwa.pulse.core.telemetry.Habitat
import dev.mascwa.pulse.desktop.settings.DesktopSettingsStore
import dev.mascwa.pulse.desktop.theme.ChakraPetch
import dev.mascwa.pulse.desktop.theme.JetBrainsMono
import dev.mascwa.pulse.desktop.theme.LcarsFrame
import dev.mascwa.pulse.desktop.theme.LcarsGhostButton
import dev.mascwa.pulse.desktop.theme.LcarsHeaderBar
import dev.mascwa.pulse.desktop.theme.LcarsStatBlock
import dev.mascwa.pulse.desktop.theme.Pulse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ⚠️ The one screen in this package with no repository and no network at all.
 *
 * The whole thing is a pure core: a coordinate resolves offline to a continent and a biome, and the
 * biome resolves to a catalogue of species. That makes it the single most useful page here when the
 * connection is gone, which is precisely when you are most likely to need it.
 */
class WildlifeViewModel(
    private val scope: CoroutineScope,
    private val settings: DesktopSettingsStore,
) {
    private val _habitat = MutableStateFlow<Habitat?>(null)
    val habitat: StateFlow<Habitat?> = _habitat.asStateFlow()

    private val _located = MutableStateFlow(true)
    val located: StateFlow<Boolean> = _located.asStateFlow()

    private val _selected = MutableStateFlow<Animal?>(null)
    val selected: StateFlow<Animal?> = _selected.asStateFlow()

    fun load() {
        scope.launch {
            val here = settings.here()
            _located.value = here != null
            _habitat.value = here?.let { AnimalHabitats.habitatFor(it.first, it.second) }
        }
    }

    fun select(animal: Animal?) {
        _selected.value = animal
    }
}

@Composable
fun WildlifeScreen(vm: WildlifeViewModel, modifier: Modifier = Modifier) {
    val habitat by vm.habitat.collectAsState()
    val located by vm.located.collectAsState()
    val selected by vm.selected.collectAsState()
    val c = Pulse.colors

    LaunchedEffect(Unit) { vm.load() }

    Column(modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        LcarsHeaderBar(
            "Wildlife",
            trailing = habitat?.let { "${it.animals.size} SPECIES" },
        )

        if (!located) {
            LcarsFrame(Modifier.fillMaxWidth(), accent = c.amber) {
                Text(
                    "This machine does not know where it is. Open SETTINGS and either let it guess " +
                        "from your connection or type a latitude and longitude.",
                    fontFamily = JetBrainsMono, fontSize = 12.sp, lineHeight = 17.sp, color = c.ink,
                )
            }
            return@Column
        }

        val h = habitat ?: return@Column

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            LcarsStatBlock("REGION", h.continent.label, Modifier.weight(1f))
            LcarsStatBlock("BIOME", h.biome.label, Modifier.weight(1f))
            LcarsStatBlock(
                "THREAT",
                h.dangerLevel().label,
                Modifier.weight(1f),
                valueColor = dangerColor(h.dangerLevel()),
            )
        }

        LcarsFrame(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Column {
                Text(
                    h.summary(),
                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = c.ink,
                )
                Text(
                    h.biome.blurb,
                    fontFamily = JetBrainsMono, fontSize = 11.sp, lineHeight = 16.sp, color = c.muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
                // ⚠️ Said plainly, because the biome is ESTIMATED from a coordinate by a coarse
                // offline rule rather than looked up in a range map. It is right about most of the
                // planet and wrong about edges, and presenting it as a survey would be dishonest.
                Text(
                    "The region is estimated from your coordinate offline. This is what typically " +
                        "lives in this kind of country, not a survey of what is outside your door.",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, lineHeight = 15.sp, color = c.faint,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        // The detail sits above the list rather than replacing it, so picking through several species
        // does not mean going back and forth.
        selected?.let { animal ->
            LcarsFrame(Modifier.fillMaxWidth().padding(top = 10.dp), accent = dangerColor(levelOf(animal.danger))) {
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            animal.name,
                            fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                            color = c.ink, modifier = Modifier.weight(1f),
                        )
                        LcarsGhostButton("CLOSE", { vm.select(null) })
                    }
                    Field("Recognising it", animal.identify)
                    Field("How it behaves", animal.behavior)
                    Field("If you meet one", animal.ifEncountered)
                    animal.firstAid?.let { Field("Bite or sting", it, accent = true) }
                }
            }
        }

        LcarsHeaderBar("What lives here", Modifier.padding(top = 12.dp))
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            items(h.animals, key = { it.id }) { animal ->
                LcarsFrame(
                    // ⚠️ `clickable` on the modifier rather than an `onClick` parameter: the frame has
                    // none, and adding one to a primitive thirty screens draw would be a change to the
                    // kit made for one caller.
                    Modifier.fillMaxWidth().clickable { vm.select(animal) },
                    accent = dangerColor(levelOf(animal.danger)),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                animal.name,
                                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                                fontSize = 14.sp, color = c.ink,
                            )
                            Text(
                                animal.identify,
                                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Text(
                            // "Hostile" and "dangerous" are different claims: one will come at you,
                            // the other only hurts you if you handle it. The core keeps them apart.
                            if (animal.hostile) "WILL THREATEN" else AnimalHabitats.dangerLabel(animal.danger),
                            fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 0.8.sp,
                            color = dangerColor(levelOf(animal.danger)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, body: String, accent: Boolean = false) {
    val c = Pulse.colors
    Column(Modifier.padding(top = 8.dp)) {
        Text(
            label.uppercase(),
            fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp,
            color = if (accent) c.negative else c.accent,
        )
        Text(
            body,
            fontFamily = JetBrainsMono, fontSize = 12.sp, lineHeight = 17.sp, color = c.ink,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

private fun levelOf(danger: Int): DangerLevel = when (danger.coerceIn(0, 4)) {
    0 -> DangerLevel.CALM
    1 -> DangerLevel.LOW
    2 -> DangerLevel.MODERATE
    3 -> DangerLevel.HIGH
    else -> DangerLevel.SEVERE
}

@Composable
private fun dangerColor(level: DangerLevel) = with(Pulse.colors) {
    when (level) {
        DangerLevel.CALM -> muted
        DangerLevel.LOW -> positive
        DangerLevel.MODERATE -> amber
        DangerLevel.HIGH -> negative
        DangerLevel.SEVERE -> negative
    }
}
