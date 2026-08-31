package dev.mascwa.nutrition.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import dev.mascwa.nutrition.data.NutritionContainer
import dev.mascwa.nutrition.ui.SectionCard
import dev.mascwa.nutrition.ui.round
import dev.mascwa.pulse.core.telemetry.Decimals
import dev.mascwa.pulse.core.telemetry.FoodPhrase
import dev.mascwa.pulse.core.telemetry.FoodPortion
import dev.mascwa.pulse.core.telemetry.MealDraft
import dev.mascwa.pulse.core.telemetry.NutritionDay
import dev.mascwa.pulse.data.food.Food
import dev.mascwa.pulse.feature.health.HealthViewModel

/**
 * Adding to the day: find a food, say how much, log it.
 *
 * ⚠️ **The meal this lands in is chosen once, at the top, and every path below it obeys that
 * choice.** Search, "again", quick add and a saved food all take the same value. The LCARS screen
 * asks separately in three places and it is worth not copying: a person adding four things to lunch
 * should say "lunch" once.
 */
@Composable
fun LogScreen(vm: HealthViewModel, container: NutritionContainer) {
    var meal by remember { mutableStateOf(NutritionDay.Meal.SNACK) }

    // ⚠️ **Only when there is no database, and here rather than only on the Plan tab.** This is the
    // screen a barcode is scanned from, so it is where "every scan will say unknown" has to be
    // visible; finding out on a settings tab is finding out after the scan that failed. With a
    // database present this is not drawn at all — the card on the Plan tab is where an update lives.
    if (remember { container.foodPack.installed() } == null) FoodPackCard(container)

    MealPicker(meal) { meal = it }
    PlateCard(vm)
    DescribeCard(vm, meal)
    FindAFood(vm, meal)
    Recents(vm, meal)
    QuickAddCard(vm, meal)
    MyFoods(vm)
    RepeatADay(vm)
    FastedDay(vm)
}

/**
 * Say that a day was a deliberate fast.
 *
 * ⚠️ **Without this control the distinction it records did nothing at all.** `Expenditure` separates
 * a day worth zero calories from a day nobody logged — a fast counts toward completeness and pulls
 * the intake mean down honestly, a gap does neither and is priced as missing — and the store has
 * carried a fasted set the whole time with nothing able to put a day in it. So every fast was read
 * as a lapse, which is the opposite of what it was.
 *
 * ⚠️ It sits at the bottom with "repeat a day" because both are statements about a whole day rather
 * than about a food, and it is deliberately not near the meal picker: this is the rarer thing, and
 * a switch that says "I ate nothing" wants to be somewhere nobody reaches by accident.
 */
@Composable
private fun FastedDay(vm: HealthViewModel) {
    val fasted by vm.fastedDay.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()

    SectionCard(
        "Or say you fasted",
        subtitle = "A deliberate fast is a record of what you ate. Left unmarked it reads as a day " +
            "you forgot, which is priced quite differently.",
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (fasted) "This day is marked as a fast." else "Nothing marked.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                // ⚠️ Said before it is tried, as well as after. The store refuses a day that has
                // entries and the view model reports that refusal — but a switch that looks live and
                // answers with a complaint is worse than one that explains itself first.
                if (entries.isNotEmpty() && !fasted) {
                    Text(
                        "There is food logged on this day, so it cannot be a fast.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(
                checked = fasted,
                onCheckedChange = { vm.setFasted(it) },
                enabled = fasted || entries.isEmpty(),
            )
        }
    }
}

/**
 * A meal being assembled, and what committing it would do to the day.
 *
 * ⚠️ **One control, not two.** With nothing being built this is a single button; once a plate is
 * standing it becomes the plate, and every "add" on the screen below puts food here instead of into
 * the record. Offering both everywhere would make the choice ambiguous on every single food.
 *
 * ⚠️ Nothing here refuses a commit or paints a meal as a failure. The lines state arithmetic — see
 * `MealDraft`, and `MacroTargets.Bound` for why a surface that treats an honest over-budget meal as
 * an error is working against the measurement this whole app rests on. This theme also takes the
 * device's dynamic colours, so a hue could not carry a meaning here even if there were one to carry.
 */
@Composable
private fun PlateCard(vm: HealthViewModel) {
    val building by vm.buildingPlate.collectAsStateWithLifecycle()
    val staged by vm.plate.collectAsStateWithLifecycle()
    val effect by vm.plateEffect.collectAsStateWithLifecycle()
    val day by vm.shownDay.collectAsStateWithLifecycle()

    if (!building) {
        SectionCard(
            "The plate",
            subtitle = "Put a few things together, see what they come to, then log them in one go.",
        ) {
            Button(
                onClick = { vm.setBuildingPlate(true) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Build a plate") }
        }
        return
    }

    SectionCard("The plate", subtitle = MealDraft.summary(effect)) {
        if (staged.isEmpty()) {
            Text(
                "Search for a food below, or type one in, and it comes here instead of going " +
                    "straight into the record.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        staged.forEach { e ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(e.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${round(e.nutrients.kcal)} kcal · ${e.meal.label}" +
                            // ⚠️ The day is named only where it differs from the one on screen.
                            // Printing it on every row would be noise; leaving it off the one row
                            // that needs it is how a meal lands somewhere nobody is looking.
                            if (e.dayStartMs != day) " · added on another day" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { vm.unstage(e.id) }) { Text("Remove") }
            }
        }
        effect.lines.forEach { l ->
            Text(
                l.sentence,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.commitPlate() }, enabled = staged.isNotEmpty()) {
                Text("Log the plate")
            }
            TextButton(onClick = { vm.clearPlate() }) {
                Text(if (staged.isEmpty()) "Stop" else "Clear")
            }
        }
    }
}

/**
 * Put a previous day's whole log onto this one — how a routine gets entered in one tap.
 *
 * ⚠️ **`copyFrom` was on the shared view model with no caller here**, so somebody who eats the same
 * breakfast every morning had to enter it every morning. It sits below the other paths deliberately:
 * it is the fastest route once a routine exists and the least useful on a first run, where there is
 * no earlier day to repeat.
 *
 * ⚠️ Not a date picker. The three offsets cover what people actually repeat — yesterday, the day
 * before, the same weekday last week — and each is a plain question a person can answer without
 * working out a date. The view model reports what it copied, including "nothing logged that day",
 * so an empty source is answered rather than silently doing nothing.
 */
@Composable
private fun RepeatADay(vm: HealthViewModel) {
    val day by vm.shownDay.collectAsStateWithLifecycle()
    SectionCard(
        "Or repeat a day",
        subtitle = "Copies everything logged that day onto this one. Nothing is removed.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1L to "Yesterday", 2L to "Two days back", 7L to "A week back").forEach { (back, label) ->
                // ⚠️ Through `dayPlus`, never `day - back * 86_400_000`. A local day is 23 hours on
                // one night of the year and 25 on another, and the arithmetic version silently
                // copies the wrong day — the defect this feature has already had four times.
                val source = vm.dayPlus(day, -back)
                OutlinedButton(onClick = { vm.copyFrom(source) }) { Text(label) }
            }
        }
    }
}

@Composable
private fun MealPicker(meal: NutritionDay.Meal, onPick: (NutritionDay.Meal) -> Unit) {
    SectionCard("Adding to", subtitle = "Everything below goes here until you change it.") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NutritionDay.Meal.entries.forEach { m ->
                FilterChip(
                    selected = meal == m,
                    onClick = { onPick(m) },
                    label = { Text(m.label) },
                )
            }
        }
    }
}

// ------------------------------------------------------------------------------ describing a meal

/**
 * A whole meal in one line of ordinary English.
 *
 * ⚠️ **Nothing is logged until the list has been read.** The parser is deterministic and good at the
 * shapes people write, and it is still reading somebody's words — so what it understood is shown
 * first, item by item, with the record each one matched. That readback is the feature. A field that
 * silently turned a sentence into six log entries would put figures nobody checked into the record
 * that every target in this app is measured from.
 *
 * ⚠️ **An unmatched line stays on screen with its name and a way to search for it.** Dropping it
 * quietly would leave the day looking complete and short by whatever it was.
 */
@Composable
private fun DescribeCard(vm: HealthViewModel, meal: NutritionDay.Meal) {
    val state by vm.describe.collectAsStateWithLifecycle()
    val building by vm.buildingPlate.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }

    SectionCard(
        "Describe it",
        subtitle = "Type the meal the way you would say it, and it comes apart into things to log.",
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("What did you eat") },
            placeholder = { Text("two eggs, a slice of toast and 200g of chicken") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.describeMeal(text) },
                enabled = text.isNotBlank() && !state.busy,
            ) { Text("Read it") }
            if (state.items.isNotEmpty()) {
                TextButton(onClick = { text = ""; vm.clearDescribed() }) { Text("Start again") }
            }
        }

        if (state.busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.padding(2.dp))
                Text("Looking each one up…", style = MaterialTheme.typography.bodySmall)
            }
        }

        state.items.forEachIndexed { i, row -> DescribedRow(vm, i, row) }

        if (state.note.isNotBlank()) {
            Text(
                state.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.ready > 0) {
            Button(
                onClick = { vm.logDescribed(meal, toPlate = building) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (building) "Add ${state.ready} to the plate"
                    else "Log ${state.ready} to ${meal.label.lowercase()}",
                )
            }
        }
    }
}

@Composable
private fun DescribedRow(vm: HealthViewModel, index: Int, row: HealthViewModel.Described) {
    // ⚠️ Hoisted to locals before the null test. `Described` is declared in another module, and
    // Kotlin will not smart-cast a public property across a module boundary — the trap this repo has
    // paid for three times, and one the local gates cannot see.
    val food = row.food
    val grams = row.grams

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            // What was UNDERSTOOD, in the core's own words — "a serving" rather than "1 serving"
            // where nothing was stated, because those are different claims.
            Text(FoodPhrase.describe(row.item), style = MaterialTheme.typography.bodyMedium)
            when {
                food != null && grams != null -> Text(
                    "${food.display} · ${round(grams)} g · " +
                        "${round(FoodPortion.eaten(food.per100g, grams).kcal)} kcal",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // Matched, but the record never said what one of them weighs, so nothing here can.
                food != null -> Text(
                    "${food.display} — but it does not say what one comes to. Say it in grams.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                else -> Text(
                    "Nothing matched “${row.item.name}”.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!row.ready) {
            TextButton(onClick = { vm.searchDescribed(index) }) { Text("Find") }
        }
        TextButton(onClick = { vm.dropDescribed(index) }) { Text("Drop") }
    }
}

// ---------------------------------------------------------------------------------- finding a food

/**
 * Search, then portion.
 *
 * ⚠️ **The picked food is held by the view model rather than by this composable**, and that is not
 * incidental: the same `picked` drives the recipe builder, and `PickFor` is what stops a food chosen
 * on one screen appearing in the other's portion box. Keeping it in a local `remember` here would
 * defeat a guard the shared code already has.
 */
@Composable
private fun FindAFood(vm: HealthViewModel, meal: NutritionDay.Meal) {
    val search by vm.search.collectAsStateWithLifecycle()
    val picked by vm.picked.collectAsStateWithLifecycle()
    val pickFor by vm.pickFor.collectAsStateWithLifecycle()

    // ⚠️ Held here rather than in the view model, unlike the picked food, because a viewfinder is
    // not state anything else needs to agree about — and one that survived leaving the tab would be
    // a camera left open behind a screen nobody is looking at.
    var scanning by remember { mutableStateOf(false) }
    if (scanning) {
        BarcodeScanner(
            onCode = { code ->
                scanning = false
                vm.searchFor(HealthViewModel.PickFor.LOG)
                // ⚠️ The digits go to `lookUpBarcode`, not into the search box. A barcode is an
                // exact key — the bundled database is indexed on it — and running it through the
                // name search would turn a certain answer into a ranked guess.
                vm.lookUpBarcode(code)
            },
            onCancel = { scanning = false },
        )
        return
    }

    SectionCard("Find a food") {
        OutlinedTextField(
            value = search.query,
            onValueChange = { vm.searchFor(HealthViewModel.PickFor.LOG); vm.onSearchQuery(it) },
            label = { Text("Search") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedButton(onClick = { scanning = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Scan a barcode")
        }

        if (search.busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.padding(2.dp))
                Text("Looking…", style = MaterialTheme.typography.bodySmall)
            }
        }

        // ⚠️ The note says WHY a list is short — no network, or a network that refused — and it
        // comes out of the shared search rather than being guessed at here. A phone in a basement
        // gets the bundled half, and rendering that identically to "this is everything there is"
        // tells somebody their food does not exist when nobody could ask.
        if (search.note.isNotBlank()) {
            Text(
                search.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ⚠️ Only ever the LOG pick. If the recipe builder holds the pick, this card must not draw a
        // portion box for it — that is the whole reason `PickFor` exists.
        val forLog = picked?.takeIf { pickFor == HealthViewModel.PickFor.LOG }
        if (forLog != null) {
            PortionBox(vm, forLog, meal)
            return@SectionCard
        }

        search.results.take(RESULT_CAP).forEach { food ->
            FoodRow(food) { vm.searchFor(HealthViewModel.PickFor.LOG); vm.pick(food) }
        }

        if (search.query.trim().length >= 2 && search.results.isEmpty() && !search.busy) {
            Text(
                "Nothing matched. Every word has to appear somewhere in the name — try fewer of them, " +
                    "or the dish rather than the shop.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (search.query.trim().length >= 2) {
            OutlinedButton(
                onClick = { vm.searchEveryProduct() },
                enabled = !search.searchingAll,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (search.searchingAll) "Searching every product…" else "Search every product")
            }
            if (search.allTruncated) {
                Text(
                    "The full scan stopped at its limit — these are the best of the first few " +
                        "thousand, not of everything.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FoodRow(food: Food, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Text(food.display, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            "${round(food.kcal)} kcal per 100 g · ${food.source.label}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * How much of it.
 *
 * ⚠️ **A unit is offered only when this food can actually be measured in it.** `gramsFor` returns
 * null for a serving the record never declared, and the shared logging call then silently does
 * nothing — so offering "serving" for a food with no serving weight would be a button that appears
 * to work and does not. The list is derived from the record rather than fixed.
 */
@Composable
private fun PortionBox(vm: HealthViewModel, food: Food, meal: NutritionDay.Meal) {
    var amount by remember(food.id) { mutableStateOf("100") }
    val usable = remember(food.id) {
        FoodPortion.Unit.entries.filter {
            FoodPortion.gramsFor(FoodPortion.Portion(1.0, it), food.sizes) != null
        }
    }
    var unit by remember(food.id) { mutableStateOf(usable.firstOrNull() ?: FoodPortion.Unit.GRAM) }

    Text(food.display, style = MaterialTheme.typography.titleSmall)

    OutlinedTextField(
        value = amount,
        onValueChange = { amount = Decimals.keep(it, 7) },
        label = { Text("How much") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        usable.forEach { u ->
            FilterChip(selected = unit == u, onClick = { unit = u }, label = { Text(u.label) })
        }
    }

    // What that portion actually comes to, before it is committed. The same arithmetic the log will
    // use, so the preview cannot disagree with the entry.
    val n = Decimals.parse(amount)
    val grams = n?.let { FoodPortion.gramsFor(FoodPortion.Portion(it, unit), food.sizes) }
    if (grams != null) {
        val eaten = FoodPortion.eaten(food.per100g, grams)
        Text(
            "${round(grams)} g · ${round(eaten.kcal)} kcal · ${round(eaten.proteinG)} g protein · " +
                "${round(eaten.fatG)} g fat · ${round(eaten.carbG)} g carbs",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // ⚠️ One button that changes what it says, rather than two side by side. The plate is a mode
    // the person deliberately turned on and can see standing at the top of this screen; offering
    // both would make the choice ambiguous on every single food and the mode meaningless.
    val building by vm.buildingPlate.collectAsStateWithLifecycle()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { if (n != null) vm.logPortion(food, n, unit, meal, toPlate = building) },
            enabled = grams != null,
        ) {
            Text(if (building) "Add to the plate" else "Add to ${meal.label.lowercase()}")
        }
        TextButton(onClick = { vm.pick(null) }) { Text("Cancel") }
    }
}

// -------------------------------------------------------------------------------------- again

/**
 * ⚠️ **The numbers are copied, never recomputed** — the shared `logAgain` says so and it is the
 * reason this is worth having rather than a shortcut into the search box. Re-deriving a portion from
 * a per-hundred-gram figure would be a second chance to get it wrong on a value already checked.
 */
@Composable
private fun Recents(vm: HealthViewModel, meal: NutritionDay.Meal) {
    val recents by vm.recents.collectAsStateWithLifecycle()
    if (recents.isEmpty()) return

    SectionCard("Again", subtitle = "Logging the same breakfast every morning, without searching for it.") {
        recents.take(RECENT_CAP).forEach { e ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.fillMaxWidth(0.68f)) {
                    Text(e.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${round(e.grams)} g · ${round(e.nutrients.kcal)} kcal",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { vm.logAgain(e, meal) }) { Text("Add") }
            }
        }
    }
}

// -------------------------------------------------------------------------------- your own foods

@Composable
private fun MyFoods(vm: HealthViewModel) {
    val mine by vm.myFoods.collectAsStateWithLifecycle()
    if (mine.isEmpty()) return

    SectionCard(
        "Your own foods",
        subtitle = "Searched ahead of both databases — a short list you named yourself beats one of millions.",
    ) {
        // ⚠️ **Capped rather than put in its own scrolling box, and the reason is the gesture.** The
        // whole tab is one vertical scroll; a second one nested inside it in the same direction
        // swallows the drag, so a finger on this card cannot move the page. Showing fewer costs
        // nothing here — a saved food is found by searching for it, not by scrolling a list of them.
        mine.take(MY_FOOD_CAP).forEach { f ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.fillMaxWidth(0.68f)) {
                    Text(f.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${round(f.kcal)} kcal per 100 g",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { vm.forgetFood(f.id) }) { Text("Forget") }
            }
            HorizontalDivider()
        }
        if (mine.size > MY_FOOD_CAP) {
            Text(
                "${mine.size - MY_FOOD_CAP} more — search for them by name.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * ⚠️ Capped because this whole tab is one scrolling column: an uncapped result list pushes "Again",
 * quick add and your own foods off the bottom, and the search box is the one thing that must never
 * become hard to get back to.
 */
private const val RESULT_CAP = 20
private const val RECENT_CAP = 8
private const val MY_FOOD_CAP = 12
