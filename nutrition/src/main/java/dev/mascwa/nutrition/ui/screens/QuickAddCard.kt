package dev.mascwa.nutrition.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mascwa.nutrition.ui.SectionCard
import dev.mascwa.pulse.core.telemetry.FoodPortion
import dev.mascwa.pulse.core.telemetry.Micronutrients
import dev.mascwa.pulse.core.telemetry.NutrientSet
import dev.mascwa.pulse.core.telemetry.NutritionDay
import dev.mascwa.pulse.feature.health.HealthViewModel

/**
 * Figures taken straight off a packet.
 *
 * ⚠️ **This is the path that never stops working** — no signal, no barcode, a local bakery nobody
 * has photographed. Everything else on the Log tab depends on a record existing somewhere; this one
 * depends on the person being able to read.
 */
@Composable
fun QuickAddCard(vm: HealthViewModel, meal: NutritionDay.Meal) {
    // ⚠️ Read here rather than at the button, so the label and the call cannot disagree about the
    // mode — a button reading "add to the plate" that logged straight into the record would be the
    // worst failure this feature could have.
    val building by vm.buildingPlate.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var kcal by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }
    var carb by remember { mutableStateOf("") }
    var grams by remember { mutableStateOf("") }
    var keep by remember { mutableStateOf(false) }
    // ⚠️ Keyed by the picker's own prefixed key, so one map carries both enums. See [LabelNutrients].
    val typed = remember { mutableStateMapOf<String, String>() }

    val gramsValue = grams.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
    val kcalValue = kcal.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }

    SectionCard("Type it in", subtitle = "What is printed on the packet, for the portion you ate.") {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(60) },
            label = { Text("What was it") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Calories", kcal, { kcal = it }, Modifier.weight(1f))
            NumberField("Weight (g)", grams, { grams = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Protein (g)", protein, { protein = it }, Modifier.weight(1f))
            NumberField("Fat (g)", fat, { fat = it }, Modifier.weight(1f))
            NumberField("Carbs (g)", carb, { carb = it }, Modifier.weight(1f))
        }

        MoreFromTheLabel(typed)

        KeepThisFood(
            keep = keep,
            canKeep = gramsValue != null,
            onToggle = { keep = it },
            reason = if (gramsValue == null) {
                "A saved food is a figure per hundred grams, and there is no honest way to work one " +
                    "out from calories alone. Give it a weight and this switch comes alive."
            } else {
                null
            },
        )

        Button(
            onClick = {
                vm.quickAdd(
                    name = name,
                    kcal = kcalValue ?: 0.0,
                    proteinG = protein.toDoubleOrNull() ?: 0.0,
                    fatG = fat.toDoubleOrNull() ?: 0.0,
                    carbG = carb.toDoubleOrNull() ?: 0.0,
                    meal = meal,
                    grams = gramsValue ?: 0.0,
                    // ⚠️ Re-checked rather than trusted: the switch does not clear itself when the
                    // weight field is emptied underneath it. The shared code checks a third time,
                    // and that is not redundancy for its own sake — a saved food with an impossible
                    // density is sanitised into a food with no numbers at all, which reads as the
                    // app having lost it.
                    keepAsFood = keep && gramsValue != null,
                    micros = typedMicros(typed),
                    extras = typedExtras(typed),
                    toPlate = building,
                )
                name = ""; kcal = ""; protein = ""; fat = ""; carb = ""; grams = ""
                keep = false; typed.clear()
            },
            enabled = kcalValue != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (building) "Add to the plate" else "Add to ${meal.label.lowercase()}")
        }

        // The density check, shown before it is committed rather than after it is refused.
        val per100 = if (gramsValue != null && kcalValue != null) {
            FoodPortion.per100gFrom(
                NutritionDay.Nutrients(
                    kcal = kcalValue,
                    proteinG = protein.toDoubleOrNull() ?: 0.0,
                    fatG = fat.toDoubleOrNull() ?: 0.0,
                    carbG = carb.toDoubleOrNull() ?: 0.0,
                ),
                gramsValue,
            )
        } else {
            null
        }
        val wrong = per100?.let { FoodPortion.densityLooksWrong(it) }
        if (wrong != null) {
            Text(wrong, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { ch -> ch.isDigit() || ch == '.' }.take(7)) },
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        modifier = modifier,
    )
}

// --------------------------------------------------------------------------- every other nutrient

/**
 * One flat list of every nutrient a label can state and this app can hold.
 *
 * ⚠️ **It spans two enums, and that is deliberate rather than an oversight to converge.**
 * [Micronutrients.Micro] holds eight figures that have a published reference intake to compare
 * against — that comparison, and the refusals inside `Micronutrients.reference`, are the whole
 * reason that type exists. [NutrientSet.Nutrient] holds twenty-nine more that have no figure current
 * guidance states. Folding them into one enum would mean either inventing guidelines for the
 * twenty-nine or discarding the eight real ones.
 *
 * ⚠️ Sorted by label rather than by declaration. Somebody hunting for magnesium is reading, and the
 * declaration order encodes measured corpus coverage — useful to the database builder, meaningless
 * here.
 *
 * ⚠️ Keys carry a prefix for which enum they came from. The two are disjoint today; the prefix means
 * they can never collide even if a name were repeated, and it is what lets one typed map hold both.
 */
private class LabelNutrient(
    val key: String,
    val label: String,
    val unit: String,
    val micro: Micronutrients.Micro?,
    val extra: NutrientSet.Nutrient?,
)

private val LabelNutrients: List<LabelNutrient> = buildList {
    Micronutrients.Micro.entries.forEach { add(LabelNutrient("M:" + it.name, it.label, it.unit, it, null)) }
    NutrientSet.Nutrient.entries.forEach {
        add(LabelNutrient("E:" + it.name, it.label, it.unit.symbol, null, it))
    }
}.sortedBy { it.label.lowercase() }

/**
 * What was typed, as the vitamins and minerals.
 *
 * ⚠️ **A blank field yields no key**, which falls out of `toDoubleOrNull` rather than being enforced,
 * and is the whole discipline of the sparse layer: absent means nobody measured it. A typed **0** is
 * a different thing and is kept — "0 g trans fat" is printed on real labels.
 */
private fun typedMicros(typed: Map<String, String>): Micronutrients.Amounts =
    Micronutrients.Amounts(
        LabelNutrients.mapNotNull { n ->
            val m = n.micro ?: return@mapNotNull null
            typed[n.key]?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }?.let { m to it }
        }.toMap(),
    )

/** The twin of [typedMicros] for the twenty-nine further nutrients. */
private fun typedExtras(typed: Map<String, String>): NutrientSet.Amounts =
    NutrientSet.Amounts(
        LabelNutrients.mapNotNull { n ->
            val e = n.extra ?: return@mapNotNull null
            typed[n.key]?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }?.let { e to it }
        }.toMap(),
    )

/**
 * ⚠️ **Nothing is shown until it is asked for.** Thirty-seven number fields under a quick-add card
 * would bury the four that matter, and the point of that card is that it is the path which always
 * works. So the default is a single button, and each nutrient appears only once it has been picked.
 *
 * ⚠️ The figures here are what was **eaten**, exactly like the macro cells above them, and the
 * conversion to a density happens once, in the shared code, against the same weight. Asking for
 * per-hundred-gram figures here would put two unit conventions on one card.
 */
@Composable
private fun MoreFromTheLabel(typed: androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>) {
    var picking by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val chosen = LabelNutrients.filter { typed.containsKey(it.key) }

    chosen.forEach { n ->
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = typed[n.key].orEmpty(),
                onValueChange = { typed[n.key] = it.filter { ch -> ch.isDigit() || ch == '.' }.take(8) },
                label = { Text("${n.label} (${n.unit})", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { typed.remove(n.key) }) { Text("Remove") }
        }
    }

    OutlinedButton(
        onClick = { query = ""; picking = true },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (chosen.isEmpty()) "More from the label" else "Add another") }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Done") } },
            title = { Text("Add a nutrient") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val q = query.trim()
                    val available = LabelNutrients.filter {
                        !typed.containsKey(it.key) && (q.isBlank() || it.label.contains(q, ignoreCase = true))
                    }
                    if (available.isEmpty()) {
                        Text(
                            if (q.isBlank()) "Every one of them is already on the card."
                            else "Nothing left matching that.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        // ⚠️ **A bounded scrolling Column, and both halves are load-bearing.**
                        // Measured against the shipped material3 1.3.1 classes rather than assumed:
                        // `AlertDialogKt` references `verticalScroll` NOWHERE, so this dialog does
                        // not scroll its own body — thirty-seven rows would grow it past the screen
                        // and take its "Done" button with them. It has no intrinsic measurement
                        // either, so a `LazyColumn` would not throw here; it would simply be handed
                        // an unbounded height, which is the other way a scrolling child fails. A
                        // bounded eager Column avoids both, and thirty-seven rows is nothing to
                        // compose at once.
                        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                            available.forEach { n ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        // Added with an EMPTY value, not a zero: choosing to record a
                                        // nutrient is not the same as saying the food has none of it.
                                        .clickable { typed[n.key] = ""; picking = false }
                                        .padding(vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(n.label, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        n.unit,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
        )
    }
}

/**
 * ⚠️ Disabled with a reason rather than absent. A control that quietly vanishes when the weight
 * field is empty teaches nothing; one that says why teaches the rule.
 */
@Composable
private fun KeepThisFood(keep: Boolean, canKeep: Boolean, onToggle: (Boolean) -> Unit, reason: String?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "Keep this food for next time",
                style = MaterialTheme.typography.bodyMedium,
                color = if (canKeep) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (reason != null) {
                Text(
                    reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = keep && canKeep, onCheckedChange = onToggle, enabled = canKeep)
    }
}
