package dev.mascwa.pulse.feature.health

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.telemetry.FoodPortion
import dev.mascwa.pulse.core.telemetry.NutritionDay
import dev.mascwa.pulse.core.telemetry.RecipeImport
import dev.mascwa.pulse.core.telemetry.Recipes
import dev.mascwa.pulse.data.food.Food
import dev.mascwa.pulse.feature.common.LcarsButton
import dev.mascwa.pulse.feature.common.LcarsChip
import dev.mascwa.pulse.feature.common.LcarsField
import dev.mascwa.pulse.feature.common.LcarsFrame
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse
import kotlin.math.roundToInt


/**
 * The dishes you make more than once, and the groups of foods you eat together.
 *
 * A bolognese is eleven ingredients, and logging it as eleven entries every Tuesday is what makes
 * people stop logging. Built once here, it is a single tap on INTAKE afterwards.
 *
 * ⚠️ **Two kinds, and the difference is what logging one means.** A RECIPE is a density and becomes
 * one entry, because a bolognese is one dish. A MEAL is several foods that arrive together and
 * becomes one entry each, so the day still breaks down by food. They share this page, the builder
 * and the food picker because they are the same data — see [Recipes.Kind].
 *
 * Every number on this page comes from the CI-tested [Recipes] core. Nothing is computed inline —
 * the whole point of that core is that the two ways to take a helping, weighed and counted, cannot
 * disagree, and a screen doing its own arithmetic would put that back.
 */
@Composable
fun RecipesBody(vm: HealthViewModel) {
    val draft by vm.draft.collectAsStateWithLifecycle()
    val saved by vm.recipes.collectAsStateWithLifecycle()

    val d = draft
    if (d != null) {
        RecipeBuilder(vm, d)
        return
    }

    // Split here rather than inside the list: `partition` on every recomposition of a lazy item
    // would run once per visible row for an answer that is the same for all of them.
    val (dishes, meals) = remember(saved) { saved.partition { !Recipes.isMeal(it) } }

    LazyColumn(Modifier.fillMaxWidth(), contentPadding = Pad, verticalArrangement = Arrangement.spacedBy(11.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LcarsButton(
                    text = "＋ RECIPE",
                    onClick = { vm.newRecipe(Recipes.Kind.RECIPE) },
                    modifier = Modifier.weight(1f),
                )
                LcarsButton(
                    text = "＋ MEAL",
                    onClick = { vm.newRecipe(Recipes.Kind.MEAL) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item { ImportFromLink(vm) }
        if (saved.isEmpty()) {
            item {
                NotYet(
                    "Nothing saved yet. A RECIPE is a dish you cook — weigh the pot when it comes " +
                        "off the heat, say how many it serves, and a helping is one entry rather " +
                        "than eleven. A MEAL is the foods you eat together — the same breakfast " +
                        "every morning goes in with one tap, and still shows up as each food.",
                )
            }
        }
        if (dishes.isNotEmpty()) {
            item { SectionLabel("RECIPES") }
            items(dishes, key = { it.id }) { r -> SavedRecipe(vm, r) }
        }
        if (meals.isNotEmpty()) {
            item { SectionLabel("SAVED MEALS") }
            items(meals, key = { it.id }) { r -> SavedRecipe(vm, r) }
        }
    }
}

// ------------------------------------------------------------------------------ from a web page

/**
 * Read a recipe page and start a draft from it.
 *
 * ⚠️ **Offered on the list and deliberately not inside the builder.** An import carries a name and a
 * portion count, and it always opens a fresh draft — folding one into an open builder would overwrite
 * both and throw away whatever was half-built.
 *
 * ⚠️ **A page supplies names and amounts. It supplies no nutrition, and nothing here invents any.**
 * Every figure in this tab comes from a real food record, so an imported line is a saved keystroke
 * and not an ingredient — it counts for nothing until it is matched against the database below.
 */
@Composable
private fun ImportFromLink(vm: HealthViewModel) {
    val c = Pulse.colors
    val state by vm.recipeImport.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf("") }

    LcarsFrame(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "FROM A LINK",
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c.accent,
            )
            LcarsField(
                url,
                { url = it.take(300) },
                placeholder = "Address of a recipe page",
                onImeAction = { vm.importRecipe(url) },
            )
            LcarsButton(
                text = if (state.busy) "READING…" else "READ IT",
                enabled = !state.busy && url.isNotBlank(),
                onClick = { vm.importRecipe(url) },
            )
            if (state.note.isNotBlank()) {
                Text(state.note, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.amber, lineHeight = 14.sp)
            }
            Text(
                "The page gives the names and the amounts. Every calorie still comes from a food " +
                    "record, so each line is matched against the database before it counts.",
                fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted, lineHeight = 13.sp,
            )
        }
    }
}

/** The lines an import found that nobody has dealt with yet. */
@Composable
private fun FromThePage(vm: HealthViewModel) {
    val c = Pulse.colors
    val state by vm.recipeImport.collectAsStateWithLifecycle()
    if (state.lines.isEmpty()) return

    LcarsFrame(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "FROM THE PAGE",
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c.accent,
            )
            if (state.summary.isNotBlank()) {
                Text(
                    state.summary,
                    fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted, lineHeight = 13.sp,
                )
            }
            state.lines.forEachIndexed { i, line ->
                val matching = state.matching == i
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            RecipeImport.describe(line),
                            fontFamily = ChakraPetch, fontSize = 13.sp,
                            color = if (matching) c.accent else c.ink,
                        )
                        Text(
                            lineWeight(line),
                            fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted, lineHeight = 13.sp,
                        )
                    }
                    Text(
                        if (matching) "STOP" else "FIND",
                        fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.accent,
                        modifier = Modifier
                            .clickable { if (matching) vm.cancelImportedMatch() else vm.matchImported(i) }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                    Text(
                        "✕",
                        fontFamily = JetBrainsMono, fontSize = 13.sp, color = c.muted,
                        modifier = Modifier
                            .clickable { vm.dropImported(i) }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * ⚠️ **Four situations, said as four different things, and collapsing them is the easy mistake.** A
 * volume, a count and a line the page gave no amount for every one come back with a null weight —
 * but "needs a weight" about a line that never had an amount sends somebody hunting a number that
 * was never there, and calling "2 onions" a volume is simply untrue.
 */
private fun lineWeight(line: RecipeImport.Ingredient): String {
    val grams = line.grams
    return when {
        grams != null -> "${grams.roundToInt()} g"
        line.quantity == null -> "the page gave no amount — weigh it yourself"
        line.measure == RecipeImport.Measure.PIECE -> "counted, not weighed — say what it comes to"
        else -> "a ${line.measure.label} is a volume, not a weight — say what it comes to"
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 0.8.sp, color = Pulse.colors.muted,
    )
}

/** One saved dish or meal: what it is, and how to put it in the day. */
@Composable
private fun SavedRecipe(vm: HealthViewModel, r: Recipes.Recipe) {
    val c = Pulse.colors
    val meal = Recipes.isMeal(r)
    var open by remember(r.id) { mutableStateOf(false) }
    LcarsFrame(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        r.name.ifBlank { if (meal) "Unnamed meal" else "Unnamed recipe" },
                        fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c.accent,
                    )
                    // The core's own one-line summary, so the list and the builder agree by
                    // construction rather than by two places formatting the same three numbers.
                    Recipes.summary(r)?.let {
                        Text(it, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted, lineHeight = 14.sp)
                    }
                }
                Text(if (open) "▾" else "▸", fontFamily = JetBrainsMono, fontSize = 13.sp, color = c.muted)
            }
            if (open) {
                r.components.forEach { comp ->
                    Text(
                        "· ${comp.name} — ${comp.grams.roundToInt()} g",
                        fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.ink, lineHeight = 14.sp,
                    )
                }
                // ⚠️ Only for a dish. A per-100-gram figure is what a recipe IS; for a group of foods
                // it is an average of things nobody eats by the 100 g, and printing it would invite
                // somebody to log a meal by weight — which is the one thing a meal cannot express.
                if (!meal) {
                    Recipes.per100g(r)?.let { per ->
                        Text(
                            "Per 100 g · ${per.kcal.roundToInt()} kcal · " +
                                "P ${per.proteinG.roundToInt()} F ${per.fatG.roundToInt()} " +
                                "C ${per.carbG.roundToInt()}",
                            fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted, lineHeight = 13.sp,
                        )
                    }
                }
                if (meal) LogTheMeal(vm, r) else LogAHelping(vm, r)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LcarsButton(text = "EDIT", onClick = { vm.editRecipe(r) }, modifier = Modifier.weight(1f))
                    LcarsButton(text = "DELETE", onClick = { vm.deleteRecipe(r.id) }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * How much of it went on the plate.
 *
 * ⚠️ Both routes are offered because a person genuinely uses both — you can count portions out of a
 * tray and you can weigh what is left. [Recipes] pins that they agree for the same amount of food,
 * so offering both costs nothing; a screen that offered only one would be guessing which situation
 * somebody is in.
 */
@Composable
private fun LogAHelping(vm: HealthViewModel, r: Recipes.Recipe) {
    val c = Pulse.colors
    var byServings by remember(r.id) { mutableStateOf(true) }
    var amount by remember(r.id) { mutableStateOf("1") }
    var meal by remember(r.id) { mutableStateOf(NutritionDay.Meal.DINNER) }
    val n = amount.trim().toDoubleOrNull()

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NutritionDay.Meal.entries.forEach { m -> LcarsChip(m.label, meal == m, { meal = m }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            LcarsChip("PORTIONS", byServings, { byServings = true })
            LcarsChip("GRAMS", !byServings, { byServings = false })
            LcarsField(amount, { amount = it }, modifier = Modifier.weight(1f))
        }
        // The figure it will actually log, worked out by the same call that will log it. A person
        // should be able to see the number before it lands in their day, not after.
        val preview = n?.let {
            if (byServings) Recipes.eatenServings(r, it) else Recipes.eatenGrams(r, it)
        }
        Text(
            preview?.let { "That is ${it.kcal.roundToInt()} kcal · P ${it.proteinG.roundToInt()}" }
                ?: "Type an amount.",
            fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
        )
        LcarsButton(
            text = "LOG A HELPING",
            enabled = preview != null,
            onClick = { n?.let { vm.logRecipe(r, it, byServings, meal) } },
        )
    }
}

/**
 * A whole meal, in one tap.
 *
 * ⚠️ **No amount field, and that is the feature rather than an omission.** The point of saving a meal
 * is that it is the same every time; asking for a number before logging the breakfast somebody has
 * eaten two hundred mornings running would put the work straight back. The one concession is HALF,
 * for the morning it was a smaller bowl.
 *
 * ⚠️ The preview counts the ENTRIES it is about to write, because that is the surprising part: a meal
 * is several rows in the day, not one, and somebody should see that before it happens rather than
 * discover it on INTAKE.
 */
@Composable
private fun LogTheMeal(vm: HealthViewModel, r: Recipes.Recipe) {
    val c = Pulse.colors
    var scale by remember(r.id) { mutableStateOf(1.0) }
    var meal by remember(r.id) { mutableStateOf(NutritionDay.Meal.BREAKFAST) }
    val parts = remember(r, scale) { Recipes.eatenComponents(r, scale) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NutritionDay.Meal.entries.forEach { m -> LcarsChip(m.label, meal == m, { meal = m }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            LcarsChip("ALL OF IT", scale == 1.0, { scale = 1.0 })
            LcarsChip("HALF", scale == 0.5, { scale = 0.5 })
        }
        Text(
            if (parts.isEmpty()) {
                "Nothing in this meal has a weight yet."
            } else {
                "${parts.size} ${if (parts.size == 1) "entry" else "entries"} · " +
                    "${parts.sumOf { it.nutrients.kcal }.roundToInt()} kcal · " +
                    "P ${parts.sumOf { it.nutrients.proteinG }.roundToInt()}"
            },
            fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
        )
        LcarsButton(
            text = "LOG THE WHOLE MEAL",
            enabled = parts.isNotEmpty(),
            onClick = { vm.logMeal(r, scale, meal) },
        )
    }
}

// ==================================================================================== builder

@Composable
private fun RecipeBuilder(vm: HealthViewModel, d: Recipes.Recipe) {
    val c = Pulse.colors
    val meal = Recipes.isMeal(d)
    val problems = remember(d) { Recipes.problems(d) }
    val total = remember(d) { Recipes.total(d) }

    LazyColumn(Modifier.fillMaxWidth(), contentPadding = Pad, verticalArrangement = Arrangement.spacedBy(11.dp)) {
        item {
            LcarsFrame(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (meal) "THE MEAL" else "THE DISH",
                        fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c.accent,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LcarsChip("A DISH", !meal, { vm.draftKind(Recipes.Kind.RECIPE) })
                        LcarsChip("FOODS TOGETHER", meal, { vm.draftKind(Recipes.Kind.MEAL) })
                    }
                    Text(
                        if (meal) {
                            "Logs as one entry per food, so the day still breaks down by what you ate."
                        } else {
                            "Cooked down into one thing, so it logs as one entry with its own numbers."
                        },
                        fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted, lineHeight = 13.sp,
                    )
                    LcarsField(
                        d.name,
                        vm::draftName,
                        placeholder = if (meal) {
                            "Usual breakfast, gym snack, Sunday fry-up…"
                        } else {
                            "Bolognese, overnight oats, the good curry…"
                        },
                    )
                    // ⚠️ Neither field is shown for a meal, and the core agrees rather than merely
                    // being unasked: `Recipes.yieldGrams` ignores a meal's stored yield outright, and
                    // `problems` does not ask either question of one. Nothing cooks a plate down, and
                    // nothing divides it into helpings.
                    if (!meal) {
                        // ⚠️ Blank clears the weighed yield rather than setting it to zero. "I did not
                        // weigh it" and "it weighed nothing" are different facts, and the core treats
                        // the first as "assume nothing was lost" and refuses the second.
                        YieldField(d, vm)
                        ServingsField(d, vm)
                    }
                }
            }
        }
        item { FromThePage(vm) }
        item { FindAnIngredient(vm, meal) }
        if (d.components.isNotEmpty()) {
            item {
                Text(
                    if (meal) {
                        "ON THE PLATE · ${Recipes.rawGrams(d).roundToInt()} g · " +
                            "${total.kcal.roundToInt()} kcal"
                    } else {
                        "IN THE POT · ${Recipes.rawGrams(d).roundToInt()} g raw · " +
                            "${total.kcal.roundToInt()} kcal"
                    },
                    fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 0.8.sp, color = c.muted,
                )
            }
            itemsIndexed(d.components) { i, comp ->
                LcarsFrame(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(comp.name, fontFamily = ChakraPetch, fontSize = 13.sp, color = c.ink)
                            Text(
                                "${comp.grams.roundToInt()} g · " +
                                    "${FoodPortion.eaten(comp.per100g, comp.grams).kcal.roundToInt()} kcal",
                                fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                            )
                        }
                        Text(
                            "✕",
                            fontFamily = JetBrainsMono, fontSize = 13.sp, color = c.muted,
                            modifier = Modifier
                                .clickable { vm.draftRemoveAt(i) }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
        // ⚠️ Warnings, and only warnings. The core deliberately still produces a number for every
        // one of these, because people cook strange things and a builder that refuses to add up a
        // reduction is one they stop using. A portion figure out by a factor of four is still worth
        // saying out loud, because this tab tells a real person how much to eat.
        if (problems.isNotEmpty()) {
            item {
                LcarsFrame(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            "WORTH A LOOK",
                            fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = c.amber,
                        )
                        problems.forEach {
                            Text(it, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.amber, lineHeight = 14.sp)
                        }
                    }
                }
            }
        }
        Recipes.summary(d)?.let { s ->
            item { Text(s, fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink, lineHeight = 15.sp) }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LcarsButton(
                    text = "SAVE",
                    // Nothing to divide by is the one case the core cannot answer, so it is the one
                    // case the button refuses rather than warns about.
                    enabled = d.components.isNotEmpty(),
                    onClick = vm::saveDraft,
                    modifier = Modifier.weight(1f),
                )
                LcarsButton(text = "CANCEL", onClick = vm::closeDraft, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * What the finished dish weighed.
 *
 * ⚠️ Held as text and committed on focus loss, like the other numeric fields in this tab. The draft
 * is a flow, so writing on every keystroke means a half-typed "12" is briefly the recipe's yield and
 * the warnings flash absurd values at the person mid-word.
 */
@Composable
private fun YieldField(d: Recipes.Recipe, vm: HealthViewModel) {
    val c = Pulse.colors
    var text by remember(d.id) { mutableStateOf(d.cookedYieldG?.let { it.roundToInt().toString() } ?: "") }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        LcarsField(
            text,
            {
                text = it
                vm.draftYield(it.trim().toDoubleOrNull())
            },
            placeholder = "Cooked weight in grams — leave blank if you did not weigh it",
        )
        Text(
            "Simmering drives off water: the same calories in less mass. Weighing the finished pot " +
                "is what makes a portion right.",
            fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted, lineHeight = 13.sp,
        )
    }
}

@Composable
private fun ServingsField(d: Recipes.Recipe, vm: HealthViewModel) {
    val c = Pulse.colors
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("SERVES", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
        listOf(1, 2, 3, 4, 6, 8).forEach { n ->
            LcarsChip("$n", d.servings == n, { vm.draftServings(n) })
        }
    }
}

/**
 * Search, pick, weigh, add.
 *
 * ⚠️ Reuses the tab's one search state, and tells the view model the pick is destined for the
 * recipe. Without that, picking here and switching to INTAKE would offer the same food in the log's
 * portion picker — one search box serving two destinations with nothing saying which.
 */
@Composable
private fun FindAnIngredient(vm: HealthViewModel, meal: Boolean) {
    val c = Pulse.colors
    val search by vm.search.collectAsStateWithLifecycle()
    val picked by vm.picked.collectAsStateWithLifecycle()
    val target by vm.pickFor.collectAsStateWithLifecycle()
    val imported by vm.recipeImport.collectAsStateWithLifecycle()

    LcarsFrame(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (meal) "ADD A FOOD" else "ADD AN INGREDIENT",
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c.accent,
            )
            LcarsField(
                search.query,
                vm::onSearchQuery,
                placeholder = if (meal) {
                    "Porridge oats, a banana, black coffee…"
                } else {
                    "Mince, tinned tomatoes, olive oil…"
                },
            )
            val chosen = picked.takeIf { target == HealthViewModel.PickFor.RECIPE }
            if (chosen == null) {
                search.results.take(8).forEach { food ->
                    Column(
                        Modifier.fillMaxWidth().clickable { vm.pick(food) }.padding(vertical = 4.dp),
                    ) {
                        Text(food.display, fontFamily = ChakraPetch, fontSize = 13.sp, color = c.ink)
                        Text(
                            "${food.per100g.kcal.roundToInt()} kcal / 100 g · ${food.source.label}",
                            fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                        )
                    }
                }
            } else {
                // ⚠️ The weight the page gave, when it gave one this app can use. Null for a cup, a
                // spoon or a count — `RecipeImport.Ingredient.grams` refuses rather than guessing a
                // density it has no way to know, and the field then asks.
                HowMuchWentIn(chosen, vm, meal, seedGrams = imported.current?.grams)
            }
        }
    }
}

/** The weight that went in, in whatever unit the food can actually express. */
@Composable
private fun HowMuchWentIn(food: Food, vm: HealthViewModel, meal: Boolean, seedGrams: Double? = null) {
    val c = Pulse.colors
    val units = remember(food.id) { FoodPortion.unitsFor(food.sizes) }
    // ⚠️ Keyed on the seed as well as the food, so picking the same ingredient again for a different
    // imported line re-seeds rather than keeping the weight the previous line put there. A seeded
    // weight is in grams by definition — it came off a mass unit on the page.
    var unit by remember(food.id, seedGrams) {
        mutableStateOf(if (seedGrams != null) FoodPortion.Unit.GRAM else units.first())
    }
    var amount by remember(food.id, seedGrams) {
        mutableStateOf(
            when {
                seedGrams != null -> seedGrams.roundToInt().toString()
                unit == FoodPortion.Unit.GRAM -> "100"
                else -> "1"
            },
        )
    }
    val n = amount.trim().toDoubleOrNull()
    val grams = n?.let { FoodPortion.gramsFor(FoodPortion.Portion(it, unit), food.sizes) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(food.display, fontFamily = ChakraPetch, fontSize = 14.sp, color = c.accent)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            units.forEach { u -> LcarsChip(u.label, unit == u, { unit = u }) }
            LcarsField(amount, { amount = it }, modifier = Modifier.weight(1f))
        }
        Text(
            grams?.let { "${it.roundToInt()} g ${if (meal) "on the plate" else "into the pot"}" }
                ?: "Type an amount.",
            fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LcarsButton(
                text = "ADD",
                enabled = grams != null,
                onClick = { n?.let { vm.draftAdd(food, it, unit) } },
                modifier = Modifier.weight(1f),
            )
            LcarsButton(text = "BACK", onClick = { vm.pick(null) }, modifier = Modifier.weight(1f))
        }
    }
}
