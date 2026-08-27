package dev.mascwa.nutrition.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.nutrition.ui.SectionCard
import dev.mascwa.nutrition.ui.StatRow
import dev.mascwa.nutrition.ui.rememberTypedNumber
import dev.mascwa.nutrition.ui.round
import dev.mascwa.pulse.core.telemetry.Decimals
import dev.mascwa.pulse.core.telemetry.FoodPortion
import dev.mascwa.pulse.core.telemetry.NutritionDay
import dev.mascwa.pulse.core.telemetry.RecipeImport
import dev.mascwa.pulse.core.telemetry.Recipes
import dev.mascwa.pulse.data.food.Food
import dev.mascwa.pulse.feature.health.HealthViewModel

/**
 * Things you make, and things you eat together.
 *
 * ⚠️ **A recipe and a meal are not the same object with a different label, and the difference shows
 * up at the moment of logging.** A recipe is a *density* — a bolognese is one dish, so a helping of
 * it is one row. A meal is several foods that happen to arrive together, and it logs as one row per
 * food, because "which food did the protein come from" is the whole reason anybody breaks a day down
 * at all. The shared core keeps both straight; this screen only has to say which is which.
 */
@Composable
fun RecipesScreen(vm: HealthViewModel) {
    val draft by vm.draft.collectAsStateWithLifecycle()

    if (draft != null) {
        Builder(vm, draft!!)
        return
    }

    SectionCard("New") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.newRecipe(Recipes.Kind.RECIPE) }, modifier = Modifier.weight(1f)) {
                Text("A recipe")
            }
            OutlinedButton(onClick = { vm.newRecipe(Recipes.Kind.MEAL) }, modifier = Modifier.weight(1f)) {
                Text("A meal")
            }
        }
        Text(
            "A recipe is one dish, and a helping of it is one entry. A meal is a group of foods and " +
                "each one is logged separately.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    ImportFromLink(vm)
    Saved(vm)
}

// ------------------------------------------------------------------------------ from a web page

/**
 * Read a recipe page and start from it.
 *
 * ⚠️ **Offered here and deliberately not inside the builder.** An import carries a name and a portion
 * count, so folding one into an open draft would overwrite both — and it always opens a fresh draft,
 * which would throw away whatever was half-built.
 */
@Composable
private fun ImportFromLink(vm: HealthViewModel) {
    val state by vm.recipeImport.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf("") }

    SectionCard("From a link") {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it.take(300) },
            label = { Text("Address of a recipe page") },
            singleLine = true,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { vm.importRecipe(url) }, enabled = !state.busy && url.isNotBlank()) {
            Text(if (state.busy) "Reading…" else "Read it")
        }
        if (state.note.isNotBlank()) {
            Text(state.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Text(
            "A page gives the names and the amounts. Every calorie still comes from a food record, " +
                "so each line is matched against the database before it counts for anything.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The lines an import found and nobody has dealt with yet. */
@Composable
private fun FromThePage(vm: HealthViewModel) {
    val state by vm.recipeImport.collectAsStateWithLifecycle()
    if (state.lines.isEmpty()) return

    SectionCard("From the page", subtitle = state.summary.takeIf { it.isNotBlank() }) {
        state.lines.forEachIndexed { i, line ->
            val matching = state.matching == i
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.fillMaxWidth(0.6f)) {
                    Text(
                        RecipeImport.describe(line),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        lineWeight(line),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row {
                    TextButton(onClick = { if (matching) vm.cancelImportedMatch() else vm.matchImported(i) }) {
                        Text(if (matching) "Stop" else "Find")
                    }
                    TextButton(onClick = { vm.dropImported(i) }) { Text("Skip") }
                }
            }
            HorizontalDivider()
        }
    }
}

/**
 * ⚠️ **Four situations, said as four different things, and collapsing them is the easy mistake.** A
 * volume, a count and a line with no amount at all every one come back with a null weight — but
 * "needs a weight" about a line the page never gave an amount for sends somebody hunting a number
 * that was never there, and calling "2 onions" a volume is simply untrue.
 */
private fun lineWeight(line: RecipeImport.Ingredient): String {
    val grams = line.grams
    return when {
        grams != null -> "${round(grams)} g"
        line.quantity == null -> "the page gave no amount — weigh it yourself"
        line.measure == RecipeImport.Measure.PIECE -> "counted, not weighed — say what it comes to"
        else -> "a ${line.measure.label} is a volume, not a weight — say what it comes to"
    }
}

// ------------------------------------------------------------------------------------- the list

@Composable
private fun Saved(vm: HealthViewModel) {
    val recipes by vm.recipes.collectAsStateWithLifecycle()
    if (recipes.isEmpty()) {
        SectionCard("Nothing saved yet") {
            Text(
                "Build something once and logging it afterwards is a single tap.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    recipes.forEach { r -> SavedCard(vm, r) }
}

@Composable
private fun SavedCard(vm: HealthViewModel, r: Recipes.Recipe) {
    var meal by remember(r.id) { mutableStateOf(NutritionDay.Meal.DINNER) }
    val servingG = Recipes.servingGrams(r)
    val total = Recipes.yieldGrams(r)

    // ⚠️ Meals have no serving arithmetic — `servingGrams` is about a dish divided into portions —
    // so a meal is scaled by a MULTIPLIER and a recipe by an AMOUNT. Two different questions asked
    // of one field, which is why the label below changes with the kind.
    //
    // ⚠️ And the seed is not a flat "1", because a recipe with no declared portion size falls back
    // to grams, where "1" means one gram of bolognese — a default nobody wants and one that reads
    // as the feature being broken rather than as a field waiting to be filled in.
    var amount by remember(r.id) {
        mutableStateOf(if (Recipes.isMeal(r) || servingG != null) "1" else "100")
    }
    var byServings by remember(r.id) { mutableStateOf(true) }

    SectionCard(r.name.ifBlank { "Untitled" }, subtitle = if (Recipes.isMeal(r)) "A meal" else "A recipe") {
        val per100 = Recipes.per100g(r)
        if (per100 != null && !Recipes.isMeal(r)) {
            StatRow("Per 100 g", "${round(per100.kcal)} kcal")
        }
        StatRow(if (Recipes.isMeal(r)) "Foods" else "Ingredients", "${r.components.size}")
        if (total > 0.0) StatRow("Weight", "${round(total)} g")
        if (servingG != null && !Recipes.isMeal(r)) {
            StatRow("A portion", "${round(servingG)} g")
        }

        // ⚠️ The core's own list of what is wrong with the draft, not a second one written here.
        Recipes.problems(r).forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NutritionDay.Meal.entries.forEach { m ->
                FilterChip(selected = meal == m, onClick = { meal = m }, label = { Text(m.label) })
            }
        }

        if (Recipes.isMeal(r)) {
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = Decimals.keep(it, 5) },
                label = { Text("How many times over") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { Decimals.parse(amount)?.let { vm.logMeal(r, it, meal) } },
                enabled = Decimals.parse(amount)?.let { it > 0.0 } == true && r.components.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Log every food in it") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // ⚠️ "By portion" is offered only when the recipe declares how many it makes AND has
                // a weight to divide — `servingGrams` returns null otherwise, and the shared log call
                // then returns without doing anything. A control that appears to work and does not is
                // worse than one that is not there.
                FilterChip(
                    selected = byServings && servingG != null,
                    onClick = { if (servingG != null) byServings = true },
                    enabled = servingG != null,
                    label = { Text("Portions") },
                )
                FilterChip(
                    selected = !byServings || servingG == null,
                    onClick = { byServings = false },
                    label = { Text("Grams") },
                )
            }
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = Decimals.keep(it, 6) },
                label = { Text(if (byServings && servingG != null) "How many portions" else "How many grams") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val n = Decimals.parse(amount)
            val eaten = n?.let {
                if (byServings && servingG != null) Recipes.eatenServings(r, it) else Recipes.eatenGrams(r, it)
            }
            if (eaten != null) {
                Text(
                    "${round(eaten.kcal)} kcal · ${round(eaten.proteinG)} g protein · " +
                        "${round(eaten.fatG)} g fat · ${round(eaten.carbG)} g carbs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = { if (n != null) vm.logRecipe(r, n, byServings && servingG != null, meal) },
                enabled = eaten != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Log a helping") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { vm.editRecipe(r) }) { Text("Edit") }
            TextButton(onClick = { vm.deleteRecipe(r.id) }) { Text("Delete") }
        }
    }
}

// ----------------------------------------------------------------------------------- the builder

/**
 * ⚠️ **The draft lives in the shared view model, not in a `remember` here**, and that is load-bearing:
 * building something is several minutes across a search and half a dozen weights, and a `remember`
 * dies the moment somebody switches tab to check a figure — which is exactly when they would lose it.
 */
@Composable
private fun Builder(vm: HealthViewModel, r: Recipes.Recipe) {
    val meal = Recipes.isMeal(r)

    SectionCard(if (meal) "Building a meal" else "Building a recipe") {
        OutlinedTextField(
            value = r.name,
            onValueChange = { vm.draftName(it.take(60)) },
            label = { Text("What is it called") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !meal, onClick = { vm.draftKind(Recipes.Kind.RECIPE) }, label = { Text("Recipe") })
            FilterChip(selected = meal, onClick = { vm.draftKind(Recipes.Kind.MEAL) }, label = { Text("Meal") })
        }

        if (!meal) {
            // ⚠️ A cooked dish loses water, so the same calories sit in LESS mass and the density
            // goes UP. The yield moves the density and never the totals — dividing the totals by the
            // raw weight would under-report every cooked recipe silently. A yield LARGER than the raw
            // weight is legitimate: rice absorbs water.
            // ⚠️ Its own text buffer, like every number field here — see `rememberTypedNumber`.
            // Fully controlled, "250.5" became "2505": `round` renders whole grams, so the point was
            // destroyed on the next frame and the 5 appended to the hundreds.
            val yieldText = rememberTypedNumber(r.cookedYieldG?.let { round(it) } ?: "")
            OutlinedTextField(
                value = yieldText.value,
                onValueChange = { s ->
                    val v = Decimals.keep(s, 6)
                    yieldText.value = v
                    vm.draftYield(Decimals.parse(v))
                },
                label = { Text("Weight after cooking (g), if you weighed it") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            // ⚠️ **This field could not be cleared at all.** `s.toIntOrNull()?.let { … }` does
            // nothing for a blank string, and `servings` is a non-null `Int`, so pressing backspace
            // left the model saying 4 and the field re-rendered "4" on the next frame — the only way
            // to change it was to append digits, giving 46 or 64. The buffer is what lets the empty
            // intermediate exist; the model is still only told about a real number.
            val servingsText = rememberTypedNumber(r.servings.toString())
            OutlinedTextField(
                value = servingsText.value,
                onValueChange = { s ->
                    servingsText.value = s
                    s.toIntOrNull()?.let { vm.draftServings(it) }
                },
                label = { Text("How many portions it makes") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        OutlinedTextField(
            value = r.note,
            onValueChange = { vm.draftNote(it.take(200)) },
            label = { Text("Note") },
            modifier = Modifier.fillMaxWidth(),
        )

        Recipes.problems(r).forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.saveDraft() }, enabled = r.components.isNotEmpty()) { Text("Save") }
            TextButton(onClick = { vm.closeDraft() }) { Text("Cancel") }
        }
    }

    FromThePage(vm)
    Components(vm, r)
    AddIngredient(vm)
}

@Composable
private fun Components(vm: HealthViewModel, r: Recipes.Recipe) {
    if (r.components.isEmpty()) return
    SectionCard("In it") {
        // ⚠️ Indexed, because removal is by POSITION. A recipe legitimately holds the same
        // ingredient twice — half the butter in the pastry, half in the filling — and removing by
        // food id would silently take both.
        r.components.forEachIndexed { i, comp ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.fillMaxWidth(0.68f)) {
                    Text(comp.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${round(comp.grams)} g",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { vm.draftRemoveAt(i) }) { Text("Remove") }
            }
            HorizontalDivider()
        }
    }
}

/**
 * ⚠️ The same search the Log tab uses, told it is picking FOR A RECIPE. Without that the food
 * chosen here would appear in the log's portion box, and the two screens would disagree about what
 * the person is doing.
 */
@Composable
private fun AddIngredient(vm: HealthViewModel) {
    val search by vm.search.collectAsStateWithLifecycle()
    val picked by vm.picked.collectAsStateWithLifecycle()
    val pickFor by vm.pickFor.collectAsStateWithLifecycle()
    val imported by vm.recipeImport.collectAsStateWithLifecycle()

    SectionCard("Add something") {
        OutlinedTextField(
            value = search.query,
            onValueChange = { vm.searchFor(HealthViewModel.PickFor.RECIPE); vm.onSearchQuery(it) },
            label = { Text("Search") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (search.note.isNotBlank()) {
            Text(
                search.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val forRecipe = picked?.takeIf { pickFor == HealthViewModel.PickFor.RECIPE }
        if (forRecipe != null) {
            // ⚠️ The imported weight, when the page gave one this app can use. Null for a cup, a
            // spoon or a count — see `RecipeImport.Ingredient.grams`, which refuses rather than
            // guessing a density it has no way to know.
            IngredientWeight(vm, forRecipe, seedGrams = imported.current?.grams)
            return@SectionCard
        }

        search.results.take(15).forEach { food ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { vm.searchFor(HealthViewModel.PickFor.RECIPE); vm.pick(food) }
                    .padding(vertical = 6.dp),
            ) {
                Text(food.display, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${round(food.kcal)} kcal per 100 g",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun IngredientWeight(vm: HealthViewModel, food: Food, seedGrams: Double? = null) {
    // ⚠️ Keyed on the seed as well as the food, so picking the same ingredient again for a different
    // imported line re-seeds rather than keeping the previous line's weight.
    var amount by remember(food.id, seedGrams) {
        mutableStateOf(seedGrams?.let { round(it) } ?: "100")
    }
    val usable = remember(food.id) {
        FoodPortion.Unit.entries.filter {
            FoodPortion.gramsFor(FoodPortion.Portion(1.0, it), food.sizes) != null
        }
    }
    // A seeded weight is in grams by definition — it came out of a mass unit on the page.
    var unit by remember(food.id, seedGrams) {
        mutableStateOf(
            if (seedGrams != null) FoodPortion.Unit.GRAM else usable.firstOrNull() ?: FoodPortion.Unit.GRAM,
        )
    }

    Text(food.display, style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(
        value = amount,
        onValueChange = { amount = Decimals.keep(it, 7) },
        label = { Text("How much of it") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        usable.forEach { u ->
            FilterChip(selected = unit == u, onClick = { unit = u }, label = { Text(u.label) })
        }
    }
    val n = Decimals.parse(amount)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { if (n != null) vm.draftAdd(food, n, unit) },
            enabled = n != null && FoodPortion.gramsFor(FoodPortion.Portion(n, unit), food.sizes) != null,
        ) { Text("Add") }
        TextButton(onClick = { vm.pick(null) }) { Text("Cancel") }
    }
}
