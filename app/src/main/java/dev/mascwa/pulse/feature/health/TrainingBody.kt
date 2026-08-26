package dev.mascwa.pulse.feature.health

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.telemetry.Training
import dev.mascwa.pulse.data.health.HealthDays
import dev.mascwa.pulse.data.health.TrainingStore
import dev.mascwa.pulse.feature.common.LcarsButton
import dev.mascwa.pulse.feature.common.LcarsDataRow
import dev.mascwa.pulse.feature.common.LcarsField
import dev.mascwa.pulse.feature.common.LcarsFrame
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

/**
 * What was lifted, what it was worth, and what to load next time.
 *
 * ⚠️ **No calorie figure appears anywhere on this page, and that is the design rather than an
 * omission.** [Expenditure] measures total expenditure from weight change and intake, so training is
 * already inside that number; a bonus on a training day would count the same work twice and have
 * somebody eat for it a second time. What lifting genuinely earns is the calorie CYCLING on COACH —
 * the same measured weekly budget moved onto the days the work is on — and the only thing that
 * crosses between the two halves of this tab is which days those were.
 */
@Composable
fun TrainingBody(vm: HealthViewModel) {
    val session by vm.session.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val bests by vm.bests.collectAsStateWithLifecycle()

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = Pad,
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        val open = session
        if (open != null) {
            item { OpenSessionPanel(vm, open) }
        } else {
            item { SessionListPanel(vm, sessions) }
            item { WeekVolumePanel(vm, sessions) }
        }
        if (bests.isNotEmpty()) {
            item { BestsPanel(bests) }
        }
    }
}

@Composable
private fun SessionListPanel(vm: HealthViewModel, sessions: List<Training.Session>) {
    val c = Pulse.colors
    LcarsFrame {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("TRAINING", fontFamily = ChakraPetch, fontSize = 15.sp, color = c.ink)
            Text(
                "Sets and effort. Nothing here adds calories — what you lift is already inside the " +
                    "measured expenditure, so counting it again would have you eat for it twice.",
                fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted, lineHeight = 13.sp,
            )
            if (sessions.isEmpty()) {
                Text(
                    "Nothing logged yet.",
                    fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                )
            } else {
                for (s in sessions.take(RECENT)) {
                    LcarsDataRow(relativeDay(s.atMs), Training.sentence(s))
                }
                if (sessions.size > RECENT) {
                    Text(
                        "and ${sessions.size - RECENT} more",
                        fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                LcarsButton("START A SESSION", { vm.startSession() })
                sessions.firstOrNull()?.let {
                    LcarsButton("CARRY ON", { vm.openSession(it) }, color = c.muted)
                }
            }
        }
    }
}

private const val RECENT = 5

@Composable
private fun OpenSessionPanel(vm: HealthViewModel, session: Training.Session) {
    val c = Pulse.colors
    val exercises by vm.exercises.collectAsStateWithLifecycle()

    LcarsFrame {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(
                Training.sentence(session).uppercase(),
                fontFamily = ChakraPetch, fontSize = 14.sp, color = c.ink,
            )

            session.movements.forEachIndexed { index, movement ->
                MovementBlock(vm, index, movement)
            }

            AddMovementRow(
                exercises,
                onPick = { vm.addMovement(it); vm.saveSession() },
                onAdd = { name, pattern -> vm.addExercise(name, pattern) },
                onRemove = { vm.removeExercise(it) },
                ownPrefix = Training.OWN_PREFIX,
            )

            // ⚠️ Commits on every keystroke, like the set cells above it: nothing recomputes a
            // note, and one that only saved on focus loss would be lost by whoever types it and
            // puts the phone down.
            LcarsField(
                session.note,
                { vm.setSessionNote(it); vm.saveSession() },
                placeholder = "Note (optional)",
            )

            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                LcarsButton("DONE", { vm.saveSession(); vm.closeSession() })
                // ⚠️ Deletes rather than merely closing, and only when the session holds nothing —
                // an empty record is not something anybody meant to keep, and leaving one would put
                // a blank row in the list and a heavy day on the plan.
                if (session.movements.isEmpty()) {
                    LcarsButton("CANCEL", {
                        vm.deleteSession(session.atMs)
                        vm.closeSession()
                    }, color = c.muted)
                } else {
                    LcarsButton("CLOSE", { vm.closeSession() }, color = c.muted)
                }
            }
        }
    }
}

@Composable
private fun MovementBlock(vm: HealthViewModel, index: Int, movement: Training.Movement) {
    val c = Pulse.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                movement.exercise.name.uppercase(),
                fontFamily = ChakraPetch, fontSize = 12.sp, color = c.accent,
            )
            LcarsButton("REMOVE", { vm.removeMovement(index); vm.saveSession() }, color = c.muted)
        }

        movement.sets.forEachIndexed { setIndex, set ->
            SetLine(movement, set, setIndex) { vm.updateSet(index, setIndex, it); vm.saveSession() }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            LcarsButton("ADD A SET", { vm.addSet(index); vm.saveSession() })
            if (movement.sets.isNotEmpty()) {
                LcarsButton(
                    "DROP THE LAST",
                    { vm.removeSet(index, movement.sets.lastIndex); vm.saveSession() },
                    color = c.muted,
                )
            }
        }

        // ⚠️ Read from the whole history rather than from this session, so a movement trained on
        // Monday still answers on Tuesday. See `HealthViewModel.nextLoad`.
        val advice = vm.nextLoad(movement.exercise.id)
        Text(
            when (advice) {
                is Training.Advice.Load -> advice.sentence
                is Training.Advice.Unknown -> advice.sentence
            },
            fontFamily = JetBrainsMono,
            fontSize = 9.sp,
            lineHeight = 13.sp,
            color = if (advice is Training.Advice.Load) c.accent else c.muted,
        )
    }
}

/**
 * One set, editable in place.
 *
 * ⚠️ The load cell is ABSENT on an unloaded movement rather than disabled. A press-up has no weight
 * to type, and an empty box somebody is invited to fill is an invitation to invent one.
 */
@Composable
private fun SetLine(
    movement: Training.Movement,
    set: Training.SetEntry,
    index: Int,
    onChange: (Training.SetEntry) -> Unit,
) {
    val c = Pulse.colors
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            "${index + 1}",
            fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
            modifier = Modifier.width(14.dp),
        )
        NumberCell(
            "REPS",
            set.reps.takeIf { it > 0 }?.toString().orEmpty(),
            { onChange(set.copy(reps = it.toIntOrNull() ?: 0)) },
            Modifier.weight(1f),
        )
        if (movement.exercise.loaded) {
            NumberCell(
                "KG",
                set.loadKg?.let { trimKg(it) }.orEmpty(),
                { onChange(set.copy(loadKg = it.toDoubleOrNull())) },
                Modifier.weight(1.2f),
            )
        }
        NumberCell(
            "RPE",
            set.rpe?.let { trimKg(it) }.orEmpty(),
            { onChange(set.copy(rpe = it.toDoubleOrNull())) },
            Modifier.weight(1f),
        )
    }
}

/**
 * Pick a movement.
 *
 * ⚠️ Inline rather than a dropdown menu, and the reason is the search field. A menu is a popup
 * window and a text field inside one takes focus unreliably, which would leave somebody unable to
 * type in the only control that reaches past the first twenty movements.
 */
@Composable
private fun AddMovementRow(
    exercises: List<Training.Exercise>,
    onPick: (Training.Exercise) -> Unit,
    onAdd: (String, Training.Pattern) -> Unit,
    onRemove: (String) -> Unit,
    /** ⚠️ Passed in rather than restated here, so it cannot drift from what the model prefixes. */
    ownPrefix: String,
) {
    val c = Pulse.colors
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    if (!open) {
        LcarsButton("ADD A MOVEMENT", { open = true })
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LcarsField(query, { query = it }, placeholder = "Search movements")
        val matching = exercises.filter { it.name.contains(query, ignoreCase = true) }
        for (e in matching.take(LIST_LIMIT)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                LcarsDataRow(
                    e.pattern.label.uppercase(),
                    e.name,
                    Modifier.weight(1f).clickable { onPick(e); open = false; query = "" },
                )
                // ⚠️ Only a movement somebody ADDED can be removed. The catalogue ships with the
                // build, and its ids would come back on the next update anyway — so a button there
                // would appear to do nothing.
                if (e.id.startsWith(ownPrefix)) {
                    LcarsButton("FORGET", { onRemove(e.id) }, color = c.muted)
                }
            }
        }
        if (matching.size > LIST_LIMIT) {
            Text(
                "and ${matching.size - LIST_LIMIT} more — keep typing to narrow it",
                fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
            )
        }

        // ⚠️ The catalogue is deliberately short, so adding is the ordinary path rather than an
        // edge — and the pattern is ASKED FOR rather than defaulted, because it decides the load
        // increment and which heading the movement appears under in the week's volume.
        val exact = matching.any { it.name.equals(query.trim(), ignoreCase = true) }
        if (query.isNotBlank() && !exact) {
            Text(
                "ADD \"${query.trim().uppercase()}\" AS",
                fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 0.8.sp, color = c.muted,
            )
            for (p in Training.Pattern.entries) {
                if (p == Training.Pattern.OTHER) continue
                LcarsButton(p.label.uppercase(), { onAdd(query.trim(), p); open = false; query = "" })
            }
        } else if (matching.isEmpty()) {
            Text(
                "Type a name to add a movement the list does not have.",
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
            )
        }
        LcarsButton("NEVER MIND", { open = false; query = "" }, color = c.muted)
    }
}

/**
 * ⚠️ A list has to be readable, and the catalogue plus everything somebody has added is longer than
 * a phone screen. Twenty is what fits; the search field above it is how you reach the rest.
 */
private const val LIST_LIMIT = 20

@Composable
private fun WeekVolumePanel(vm: HealthViewModel, sessions: List<Training.Session>) {
    val c = Pulse.colors
    if (sessions.isEmpty()) return
    val volume = remember(sessions) { vm.weekVolume() }
    LcarsFrame {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("THIS WEEK", fontFamily = ChakraPetch, fontSize = 13.sp, color = c.ink)
            Text(
                "Hard sets only — a warm-up is a set and is not work.",
                fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
            )
            if (volume.isEmpty()) {
                Text(
                    "Nothing in the last seven days.",
                    fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                )
            } else {
                for ((pattern, count) in volume.entries.sortedByDescending { it.value }) {
                    LcarsDataRow(pattern.label.uppercase(), "$count")
                }
            }
        }
    }
}

@Composable
private fun BestsPanel(bests: List<TrainingStore.Best>) {
    val c = Pulse.colors
    LcarsFrame {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("BEST ESTIMATES", fontFamily = ChakraPetch, fontSize = 13.sp, color = c.ink)
            // ⚠️ Named as an ESTIMATE everywhere it appears. It comes from a formula fitted on sets
            // taken near failure, not from a single anybody actually lifted, and a screen that
            // called it "your max" would have somebody load a bar from it.
            Text(
                "Worked out from a set you took near failure, not from a single you lifted.",
                fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted, lineHeight = 13.sp,
            )
            for (b in bests.take(BESTS_SHOWN)) {
                LcarsDataRow(
                    b.name.uppercase(),
                    "${trimKg(b.oneRepMaxKg)} KG  ·  ${b.reps} x ${trimKg(b.loadKg)}",
                )
            }
        }
    }
}

private const val BESTS_SHOWN = 8

/**
 * A weight or an effort, at the fewest places that lose nothing.
 *
 * ⚠️ Two places are needed because plates go in 1.25 kg steps, and one place would report that as
 * 1.3. Locale.US matches every other figure this tab prints.
 */
private fun trimKg(v: Double): String {
    val hundredths = Math.round(v * 100.0)
    return when {
        hundredths % 100L == 0L -> "${hundredths / 100L}"
        hundredths % 10L == 0L -> String.format(java.util.Locale.US, "%.1f", v)
        else -> String.format(java.util.Locale.US, "%.2f", v)
    }
}

/** ⚠️ Local calendar days, not elapsed milliseconds — see [HealthDays] for why that matters. */
private fun relativeDay(atMs: Long): String = when (val days = HealthDays.daysAgo(atMs)) {
    0 -> "TODAY"
    1 -> "YESTERDAY"
    else -> if (days < 0) "TODAY" else "$days DAYS AGO"
}
