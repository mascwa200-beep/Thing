package dev.mascwa.nutrition.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.nutrition.ui.SectionCard
import dev.mascwa.nutrition.ui.StatRow
import dev.mascwa.nutrition.ui.round
import dev.mascwa.pulse.core.telemetry.Training
import dev.mascwa.pulse.feature.health.HealthViewModel

/**
 * What was lifted, and what to load next time.
 *
 * ⚠️ **On Habits rather than in a tab of its own, and that is a measured constraint rather than a
 * judgement about importance.** The navigation bar is at six items and `NutritionApp` records why a
 * seventh does not fit at 411dp. Habits is where it belongs anyway: that tab's subject is what you
 * actually do, and a training log is the strongest signal of that this app can hold.
 *
 * ⚠️ **No calories appear anywhere in here.** Training is already inside the measured expenditure —
 * see [Training]'s own note — so a bonus on a training day would count the same work twice. What
 * lifting earns is the calorie CYCLING on Plan, and the only thing that crosses over is which days
 * the work was on.
 */
@Composable
fun TrainingCard(vm: HealthViewModel) {
    val session by vm.session.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()

    SectionCard(
        "Training",
        subtitle = "Sets, and what to load next time. No calories: what you lift is already inside " +
            "the measured expenditure, so counting it again would have you eat for it twice.",
    ) {
        val open = session
        if (open == null) {
            if (sessions.isEmpty()) {
                Text(
                    "Nothing logged yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                for (s in sessions.take(RECENT_SESSIONS)) {
                    StatRow(relativeDay(s.atMs), Training.sentence(s))
                }
                if (sessions.size > RECENT_SESSIONS) {
                    Text(
                        "and ${sessions.size - RECENT_SESSIONS} more",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ vm.startSession() }) { Text("Start a session") }
                val last = sessions.firstOrNull()
                if (last != null) {
                    OutlinedButton({ vm.openSession(last) }) { Text("Carry on the last one") }
                }
            }
        } else {
            OpenSession(vm, open)
        }
    }
}

private const val RECENT_SESSIONS = 4

@Composable
private fun OpenSession(vm: HealthViewModel, session: Training.Session) {
    val exercises by vm.exercises.collectAsStateWithLifecycle()

    Text(Training.sentence(session), style = MaterialTheme.typography.bodyMedium)

    session.movements.forEachIndexed { index, movement ->
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        MovementRows(vm, index, movement)
    }

    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    AddMovement(
        exercises,
        onPick = { vm.addMovement(it); vm.saveSession() },
        onAdd = { name, pattern -> vm.addExercise(name, pattern) },
        onRemove = { vm.removeExercise(it) },
        ownPrefix = Training.OWN_PREFIX,
    )

    // ⚠️ Commits on every keystroke, like the set fields beside it: nothing recomputes a note, and
    // one that only saved on focus loss would be lost by whoever types it and puts the phone down.
    OutlinedTextField(
        value = session.note,
        onValueChange = { vm.setSessionNote(it); vm.saveSession() },
        label = { Text("Note (optional)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ vm.saveSession(); vm.closeSession() }) { Text("Done") }
        // ⚠️ Deletes rather than merely closing, and only because the session has nothing in it —
        // an empty record is not something anybody meant to keep, and leaving it would put a blank
        // row in the list and a heavy day on the plan.
        if (session.movements.isEmpty()) {
            TextButton({ vm.deleteSession(session.atMs); vm.closeSession() }) { Text("Cancel") }
        } else {
            TextButton({ vm.closeSession() }) { Text("Close") }
        }
    }
}

@Composable
private fun MovementRows(vm: HealthViewModel, index: Int, movement: Training.Movement) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(movement.exercise.name, style = MaterialTheme.typography.titleSmall)
        TextButton({ vm.removeMovement(index); vm.saveSession() }) { Text("Remove") }
    }

    movement.sets.forEachIndexed { setIndex, set ->
        SetRow(movement, set, setIndex) { vm.updateSet(index, setIndex, it); vm.saveSession() }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ vm.addSet(index); vm.saveSession() }) { Text("Add a set") }
        if (movement.sets.isNotEmpty()) {
            TextButton({ vm.removeSet(index, movement.sets.lastIndex); vm.saveSession() }) {
                Text("Drop the last")
            }
        }
    }

    // ⚠️ Read from the whole history rather than from this session, so a movement trained on
    // Monday still answers on Tuesday. See `HealthViewModel.nextLoad`.
    when (val advice = vm.nextLoad(movement.exercise.id)) {
        is Training.Advice.Load -> Text(
            advice.sentence,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        is Training.Advice.Unknown -> Text(
            advice.sentence,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One set, editable in place.
 *
 * ⚠️ The load field is absent on an unloaded movement rather than disabled — a press-up has no
 * weight to type, and an empty box somebody is invited to fill is an invitation to invent one.
 */
@Composable
private fun SetRow(
    movement: Training.Movement,
    set: Training.SetEntry,
    index: Int,
    onChange: (Training.SetEntry) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "${index + 1}",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(16.dp),
        )
        NumberBox("Reps", set.reps.takeIf { it > 0 }?.toString().orEmpty(), Modifier.width(72.dp)) {
            onChange(set.copy(reps = it?.toInt() ?: 0))
        }
        if (movement.exercise.loaded) {
            NumberBox("kg", set.loadKg?.let { trim(it) }.orEmpty(), Modifier.width(84.dp)) {
                onChange(set.copy(loadKg = it))
            }
        }
        NumberBox("RPE", set.rpe?.let { trim(it) }.orEmpty(), Modifier.width(72.dp)) {
            onChange(set.copy(rpe = it))
        }
    }
}

/**
 * ⚠️ Commits on every keystroke rather than on focus loss, unlike the life-profile fields.
 *
 * The difference is what the value feeds. Those drive a decayed flow that ticks every second and
 * would clobber a half-typed number; nothing recomputes a set, so the simpler behaviour is also the
 * correct one here — and a set that only saved when you happened to tap elsewhere would be lost by
 * the person who types the last number and puts the phone down.
 */
@Composable
private fun NumberBox(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onChange: (Double?) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { text ->
            val cleaned = text.filter { it.isDigit() || it == '.' }
            onChange(cleaned.toDoubleOrNull())
        },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

/**
 * Pick a movement.
 *
 * ⚠️ **An inline panel rather than a DropdownMenu, and the reason is the search box.** A menu is a
 * popup window, and a text field inside one takes focus unreliably across launchers and keyboards —
 * which would leave somebody unable to type in the only control that reaches past the first twenty
 * movements. Inline, the field is an ordinary part of the page and behaves like every other field
 * in this app.
 */
@Composable
private fun AddMovement(
    exercises: List<Training.Exercise>,
    onPick: (Training.Exercise) -> Unit,
    onAdd: (String, Training.Pattern) -> Unit,
    onRemove: (String) -> Unit,
    /** ⚠️ Passed in rather than restated here, so it cannot drift from what the model prefixes. */
    ownPrefix: String,
) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    if (!open) {
        OutlinedButton({ open = true }) { Text("Add a movement") }
        return
    }

    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text("Search movements") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    val matching = exercises.filter { it.name.contains(query, ignoreCase = true) }
    for (e in matching.take(LIST_LIMIT)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton({ onPick(e); open = false; query = "" }) {
                Text("${e.name}  ·  ${e.pattern.label}")
            }
            // ⚠️ Only a movement somebody ADDED can be removed. The catalogue ships with the build
            // and deleting from it would be a per-install edit to shared content — and the id would
            // come back on the next update anyway, so the button would appear to do nothing.
            if (e.id.startsWith(ownPrefix)) {
                TextButton({ onRemove(e.id) }) { Text("Forget") }
            }
        }
    }
    if (matching.size > LIST_LIMIT) {
        Text(
            "and ${matching.size - LIST_LIMIT} more — keep typing to narrow it",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // ⚠️ **The card used to say anything missing could be added, with nothing that added it.** The
    // catalogue is deliberately short, so this is the ordinary path rather than an edge — and the
    // pattern is asked for rather than defaulted, because it decides the load increment and which
    // heading the movement appears under in the week's volume.
    val exact = matching.any { it.name.equals(query.trim(), ignoreCase = true) }
    if (query.isNotBlank() && !exact) {
        Text(
            "Add \"${query.trim()}\" as:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (p in Training.Pattern.entries) {
            if (p == Training.Pattern.OTHER) continue
            TextButton({ onAdd(query.trim(), p); open = false; query = "" }) { Text(p.label) }
        }
    } else if (matching.isEmpty()) {
        Text(
            "Type a name to add a movement the list does not have.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    TextButton({ open = false; query = "" }) { Text("Never mind") }
}


/**
 * ⚠️ A list has to be readable, and the catalogue plus everything somebody has added is longer than
 * a phone screen. Twenty is what fits; the search field above it is how you reach the rest.
 */
private const val LIST_LIMIT = 20

/** Hard sets by pattern over the last week, for somebody asking whether it was balanced. */
@Composable
fun WeekVolumeCard(vm: HealthViewModel) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val bests by vm.bests.collectAsStateWithLifecycle()
    if (sessions.isEmpty()) return

    val volume = remember(sessions) { vm.weekVolume() }
    SectionCard("This week's lifting", subtitle = "Hard sets only — a warm-up is a set and is not work.") {
        if (volume.isEmpty()) {
            Text(
                "Nothing in the last seven days.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            for ((pattern, count) in volume.entries.sortedByDescending { it.value }) {
                StatRow(pattern.label, "$count")
            }
        }

        if (bests.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("Best estimates", style = MaterialTheme.typography.titleSmall)
            // ⚠️ Named as an ESTIMATE everywhere it appears. It comes from a formula fitted on sets
            // taken near failure, not from a single anybody actually lifted, and a screen that
            // called it "your max" would have somebody load a bar from it.
            for (b in bests.take(BESTS_SHOWN)) {
                StatRow(b.name, "${trim(b.oneRepMaxKg)} kg  ·  from ${b.reps} x ${trim(b.loadKg)}")
            }
        }
    }
}

private const val BESTS_SHOWN = 6

/**
 * A weight or an effort, at the fewest places that do not lose anything.
 *
 * ⚠️ **Chooses the places FIRST and formats once, rather than formatting and trimming the result.**
 * `round` is this app's on-screen formatter and is locale-aware by design, so the decimal separator
 * is whatever the reader uses — and trimming a trailing '0' then a '.' off "1,25" removes nothing
 * and off "100,00" removes the wrong character. Two places are needed because plates go in 1.25 kg
 * steps, and one place would report that as 1.3.
 */
private fun trim(v: Double): String {
    val hundredths = Math.round(v * 100.0)
    return when {
        hundredths % 100L == 0L -> round(v, 0)
        hundredths % 10L == 0L -> round(v, 1)
        else -> round(v, 2)
    }
}

/** ⚠️ Local calendar days, not elapsed milliseconds — see `HealthDays` for why that matters. */
@Composable
private fun relativeDay(atMs: Long): String {
    val days = dev.mascwa.pulse.data.health.HealthDays.daysAgo(atMs)
    return when {
        days <= 0 -> "Today"
        days == 1 -> "Yesterday"
        else -> "$days days ago"
    }
}

/** A chip stating that the plan's heavy days can be taken from what was actually trained. */
@Composable
fun HeavyDaySuggestion(vm: HealthViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val suggested = remember(sessions) { vm.heavyDaysFromTraining() }
    // ⚠️ Silent when it agrees, and silent when there is nothing to say. A suggestion that repeats
    // what is already set teaches somebody to ignore the row it sits in.
    if (suggested.isEmpty() || suggested == state.profile.heavyDays.toSet()) return

    OutlinedButton({ vm.applyTrainingDays() }) {
        Text("Use the ${suggested.size} days you actually trained")
    }
}
