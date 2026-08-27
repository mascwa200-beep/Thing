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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.nutrition.ui.SectionCard
import dev.mascwa.pulse.core.telemetry.Decimals
import dev.mascwa.pulse.core.telemetry.FoodPortion
import dev.mascwa.pulse.core.telemetry.Micronutrients
import dev.mascwa.pulse.core.telemetry.NutrientSet
import dev.mascwa.pulse.core.telemetry.NutritionDay
import dev.mascwa.pulse.core.telemetry.NutritionLabel
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
    // ⚠️ Real fields rather than hidden state carried over from a label read. If a label fills them
    // and the weight is then changed, an invisible figure would silently stop matching the visible
    // ones; on screen it can at least be corrected. They are also the only way to type saturates,
    // sugars, fibre or sodium at all — neither nutrient picker covers them, because they live
    // directly on NutritionDay.Nutrients rather than in either enum.
    var fibre by remember { mutableStateOf("") }
    var sugar by remember { mutableStateOf("") }
    var satFat by remember { mutableStateOf("") }
    var sodium by remember { mutableStateOf("") }
    var keep by remember { mutableStateOf(false) }
    // ⚠️ Keyed by the picker's own prefixed key, so one map carries both enums. See [LabelNutrients].
    val typed = remember { mutableStateMapOf<String, String>() }

    val gramsValue = Decimals.parse(grams)?.takeIf { it.isFinite() && it > 0.0 }
    val kcalValue = Decimals.parse(kcal)?.takeIf { it.isFinite() && it >= 0.0 }

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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Fibre (g)", fibre, { fibre = it }, Modifier.weight(1f))
            NumberField("Sugars (g)", sugar, { sugar = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Saturates (g)", satFat, { satFat = it }, Modifier.weight(1f))
            NumberField("Sodium (mg)", sodium, { sodium = it }, Modifier.weight(1f))
        }

        ReadTheLabel { eaten, ate ->
            kcal = trimmed(eaten.kcal)
            protein = trimmed(eaten.proteinG)
            fat = trimmed(eaten.fatG)
            carb = trimmed(eaten.carbG)
            fibre = trimmed(eaten.fibreG)
            sugar = trimmed(eaten.sugarG)
            satFat = trimmed(eaten.satFatG)
            sodium = trimmed(eaten.sodiumMg)
            grams = trimmed(ate)
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
                    proteinG = Decimals.parse(protein) ?: 0.0,
                    fatG = Decimals.parse(fat) ?: 0.0,
                    carbG = Decimals.parse(carb) ?: 0.0,
                    meal = meal,
                    grams = gramsValue ?: 0.0,
                    // ⚠️ Re-checked rather than trusted: the switch does not clear itself when the
                    // weight field is emptied underneath it. The shared code checks a third time,
                    // and that is not redundancy for its own sake — a saved food with an impossible
                    // density is sanitised into a food with no numbers at all, which reads as the
                    // app having lost it.
                    keepAsFood = keep && gramsValue != null,
                    fibreG = Decimals.parse(fibre) ?: 0.0,
                    sugarG = Decimals.parse(sugar) ?: 0.0,
                    satFatG = Decimals.parse(satFat) ?: 0.0,
                    sodiumMg = Decimals.parse(sodium) ?: 0.0,
                    micros = typedMicros(typed),
                    extras = typedExtras(typed),
                    toPlate = building,
                )
                name = ""; kcal = ""; protein = ""; fat = ""; carb = ""; grams = ""
                fibre = ""; sugar = ""; satFat = ""; sodium = ""
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
                    proteinG = Decimals.parse(protein) ?: 0.0,
                    fatG = Decimals.parse(fat) ?: 0.0,
                    carbG = Decimals.parse(carb) ?: 0.0,
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
        onValueChange = { onChange(Decimals.keep(it, 7)) },
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
            Decimals.parse(typed[n.key])?.takeIf { it.isFinite() && it >= 0.0 }?.let { m to it }
        }.toMap(),
    )

/** The twin of [typedMicros] for the twenty-nine further nutrients. */
private fun typedExtras(typed: Map<String, String>): NutrientSet.Amounts =
    NutrientSet.Amounts(
        LabelNutrients.mapNotNull { n ->
            val e = n.extra ?: return@mapNotNull null
            Decimals.parse(typed[n.key])?.takeIf { it.isFinite() && it >= 0.0 }?.let { e to it }
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
                onValueChange = { typed[n.key] = Decimals.keep(it, 8) },
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


/**
 * Read the panel off a packet, rather than picking eight numbers out of it by hand.
 *
 * ⚠️ **The value here is the parser, not the typing.** [NutritionLabel] is where the traps live: a
 * comma that is a decimal point in most of the world, energy stated twice with kilojoules first,
 * saturates that read as the total fat, salt that is not sodium, and a per-serving panel that does
 * not say what a serving weighs and therefore cannot honestly become a density. Every one of those
 * is a wrong number a person would not question. The text can come from anywhere — typed, pasted
 * from a shop's website, or one day from a photograph — and the arithmetic is the same.
 *
 * ⚠️ **On-device text recognition is not available here and that is a measured finding, not an
 * omission.** Google's ML Kit text recognition depends on play-services-base even in its bundled
 * form, and the device this is built for runs GrapheneOS while the standalone app claims to work on
 * any phone at all — the same reason barcode scanning here uses ZXing. What is left is Tesseract,
 * which means a trained-data blob of roughly twenty megabytes per application on top of native code
 * for four architectures. That is a decision about size, not a detail, so it is not made quietly.
 *
 * The figures handed back are what was EATEN, scaled from the density by [FoodPortion.eaten], so
 * they and the weight beside them always describe the same portion.
 */
@Composable
private fun ReadTheLabel(onUse: (NutritionDay.Nutrients, Double) -> Unit) {
    var open by remember { mutableStateOf(false) }

    TextButton(onClick = { open = true }) { Text("Read a label instead") }
    if (!open) return

    var text by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }
    var ate by remember { mutableStateOf("100") }

    val reading = NutritionLabel.read(text)
    val override = Decimals.parse(weight)?.takeIf { it.isFinite() && it > 0.0 }
    val per100 = reading?.let { NutritionLabel.per100g(it, override) }
    val ateG = Decimals.parse(ate)?.takeIf { it.isFinite() && it > 0.0 }

    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Read a label") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "Type or paste the nutrition panel — one line per figure. Kilojoules, commas " +
                        "for decimals and salt instead of sodium are all handled.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(2000) },
                    label = { Text("The panel") },
                    minLines = 5,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (reading != null) {
                    Text(NutritionLabel.summary(reading), style = MaterialTheme.typography.bodyMedium)

                    // ⚠️ Only when the panel is per serving and never says what one weighs. The core
                    // refuses rather than assuming a hundred grams, so this is the one thing that
                    // can unblock it — and it is asked for here rather than guessed at.
                    if (per100 == null) {
                        NumberField(
                            "What a serving weighs (g)",
                            weight,
                            { weight = it },
                            Modifier.fillMaxWidth(),
                        )
                    }
                }

                NumberField("How much did you eat (g)", ate, { ate = it }, Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                enabled = per100 != null && ateG != null,
                onClick = {
                    val d = per100 ?: return@TextButton
                    val g = ateG ?: return@TextButton
                    // Bounded by physics on the way through, exactly as a scanned food is.
                    onUse(FoodPortion.eaten(FoodPortion.sane(d), g), g)
                    open = false
                },
            ) { Text("Use these figures") }
        },
        dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
    )
}

/**
 * A figure as a field would hold it: no trailing zero, and blank when there is nothing to say.
 *
 * ⚠️ Not [FoodPortion.trim], which is internal to its own module — formatting the initial value of
 * a text field does not justify widening a core's API. And ⚠️ `toString`, never a format string:
 * NumberField keeps only digits and a point, so on a comma-decimal device a formatted figure would
 * arrive stripped of its separator and mean ten times what it said. Half-up rather than
 * kotlin.math.round, which is banker's rounding and changes direction with the preceding digit.
 */
private fun trimmed(v: Double): String {
    if (!v.isFinite() || v <= 0.0) return ""
    val r = kotlin.math.floor(v * 10.0 + 0.5) / 10.0
    return if (r % 1.0 == 0.0) r.toLong().toString() else r.toString()
}
