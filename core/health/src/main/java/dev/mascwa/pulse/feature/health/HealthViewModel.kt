package dev.mascwa.pulse.feature.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.telemetry.Body
import dev.mascwa.pulse.core.telemetry.BodyTrend
import dev.mascwa.pulse.core.telemetry.Expenditure
import dev.mascwa.pulse.core.telemetry.Habits
import dev.mascwa.pulse.core.telemetry.IntakeWeek
import dev.mascwa.pulse.core.telemetry.FoodPortion
import dev.mascwa.pulse.core.telemetry.MacroTargets
import dev.mascwa.pulse.core.telemetry.Micronutrients
import dev.mascwa.pulse.core.telemetry.NutrientSet
import dev.mascwa.pulse.core.telemetry.NutritionDay
import dev.mascwa.pulse.core.telemetry.Recipes
import dev.mascwa.pulse.data.health.HealthDeps
import dev.mascwa.pulse.data.health.HealthSettings
import dev.mascwa.pulse.data.health.MealPhotos
import dev.mascwa.pulse.data.health.BodyStore
import dev.mascwa.pulse.data.health.HealthConnectBridge
import dev.mascwa.pulse.data.health.HealthDays
import dev.mascwa.pulse.data.health.ProgressPhotoStore
import dev.mascwa.pulse.data.food.Food
import dev.mascwa.pulse.data.food.FoodLookup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * The HEALTH tab's one view model.
 *
 * ⚠️ **Nothing derived is stored, anywhere.** The trend, the rate, the measured expenditure and the
 * calorie targets are all recomputed here from the raw record by the pure cores, on every change. That
 * is deliberate and it is what makes the tab trustworthy: a cached target is a number that was true once,
 * and the one thing worse than a wrong calorie target is a *stale* one that nothing will ever correct.
 * The cores are cheap — a Kalman pass over a few hundred weigh-ins is microseconds — so there is nothing
 * to buy by caching them and a whole class of disagreement to avoid.
 */
class HealthViewModel(private val c: HealthDeps) : ViewModel() {

    /** Which sub-tab is showing. Hoisted here so it survives navigating away and back. */
    val tabIndex = MutableStateFlow(0)

    private val zone: ZoneId get() = ZoneId.systemDefault()

    /**
     * The start of today, in the reader's own zone.
     *
     * ⚠️ Every day boundary in this feature comes from here. The cores are all clock-free and
     * zone-free precisely so that this decision is made once, in the one place that knows where the
     * person actually is — a day taken in UTC is a day out for most of the world, which this repo has
     * already shipped twice.
     */
    fun todayStartMs(): Long = HealthDays.todayStart(zone)

    fun dayStartOf(epochMs: Long): Long = HealthDays.startOf(epochMs, zone)

    /**
     * The day [days] before or after the one starting at [dayStartMs].
     *
     * ⚠️ Calendar arithmetic, not `± 86_400_000`. A local day is 23 hours the morning the clocks go
     * forward and 25 the morning they go back, so adding a fixed day either overshoots into the day
     * after next or lands back inside the same one — and the visible symptom is a log that skips a day,
     * or a "next day" button that will not move. `LocalDate.plusDays` has no such failure.
     */
    fun dayPlus(dayStartMs: Long, days: Long): Long = HealthDays.plus(dayStartMs, days, zone)

    /**
     * The day before the one starting at [dayStartMs] — what "consecutive" means to a streak.
     *
     * A named member rather than a lambda at each call site so the four habit streaks cannot end up
     * asking four slightly different questions about the same word.
     */
    fun dayBefore(dayStartMs: Long): Long = dayPlus(dayStartMs, -1)

    /**
     * The last [days] day-starts ending today, oldest first — the row a windowed chart draws.
     *
     * The calendar lives here rather than in the core, which is deliberately zone-free, and handing
     * the whole list across means the days the chart draws and the days the core scores are one list
     * rather than two expressions that can drift apart.
     */
    fun dayGrid(days: Int): List<Long> = HealthDays.grid(todayStartMs(), days, zone)

    // -------------------------------------------------------------------------------- the record

    val profile: StateFlow<HealthSettings> = c.healthSettings
        .stateIn(viewModelScope, SharingStarted.Eagerly, HealthSettings())

    val weighins: StateFlow<List<BodyTrend.Weighin>> = c.bodyStore.weighins

    /**
     * The newest reading of each kind, which is all a measurements panel ever shows.
     *
     * Derived rather than stored: the store keeps every reading so a history is possible later, and
     * collapsing to the latest here means the panel and the record cannot disagree.
     */
    val measurements: StateFlow<Map<BodyStore.MeasureKind, BodyStore.Measurement>> =
        c.bodyStore.measurements
            .map { all -> all.groupBy { it.kind }.mapValues { (_, v) -> v.maxBy { m -> m.atMs } } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _today = MutableStateFlow(todayStartMs())

    /** Which day the log is showing. Today unless the reader stepped back through the record. */
    val shownDay: StateFlow<Long> = _today.asStateFlow()

    private val _entries = MutableStateFlow<List<NutritionDay.Entry>>(emptyList())
    val entries: StateFlow<List<NutritionDay.Entry>> = _entries.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)

    /** One transient line for something that just happened. Cleared by the surface once read. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    // ----------------------------------------------------------------------------- what it means

    /**
     * Everything the cores can say, recomputed from the raw record.
     *
     * ⚠️ The whole state is one object rather than several flows because its parts are only true
     * together: the plan is derived from the expenditure, which is derived from the trend and the log.
     * Emitting them separately would let a screen paint a target from one moment beside a rate from
     * another, and the two would quietly disagree.
     */
    data class State(
        val profile: HealthSettings = HealthSettings(),
        val person: Body.Person? = null,
        val trend: BodyTrend.Trend = BodyTrend.Trend.TooLittle(0, ""),
        val formula: Expenditure.Estimate.Known? = null,
        val measured: Expenditure.Estimate? = null,
        val expenditure: Expenditure.Estimate.Known? = null,
        /** How much of [expenditure] is measured rather than guessed, 0..1. Worth showing while it climbs. */
        val measuredShare: Double = 0.0,
        val plan: MacroTargets.Plan? = null,
        val eatenToday: NutritionDay.Nutrients = NutritionDay.Nutrients(),
        /**
         * Today's vitamins and minerals, and how many of today's foods each was drawn from.
         *
         * ⚠️ Separate from [eatenToday] because it answers a question the macros never have to. A
         * calorie total is complete by construction; a calcium total is only as complete as the
         * records that happened to state it, and roughly three product records in four do not.
         */
        val microsToday: Micronutrients.Day = Micronutrients.Day(),
        /**
         * The further nutrients today's foods recorded, and how much of the day each came from.
         *
         * ⚠️ Kept apart from [microsToday] rather than merged: those eight have reference intakes to
         * compare against and these twenty-nine have none this app can honestly state, so one list
         * would mean either inventing guidelines or dropping the real ones.
         */
        val extrasToday: NutrientSet.Day = NutrientSet.Day(),
        val loggedDaysInWindow: Int = 0,
    ) {
        val targets: MacroTargets.Targets? get() = (plan as? MacroTargets.Plan.Set)?.targets
        val remaining: NutritionDay.Remaining?
            get() = targets?.let { NutritionDay.remaining(eatenToday, it) }
        val latest: BodyTrend.Point? get() = (trend as? BodyTrend.Trend.Estimated)?.latest
        val unit: BodyTrend.MassUnit
            get() = runCatching { BodyTrend.MassUnit.valueOf(profile.massUnit) }
                .getOrDefault(BodyTrend.MassUnit.KG)
    }

    private val recompute = MutableStateFlow(0)

    val state: StateFlow<State> =
        combine(profile, weighins, _entries, recompute) { p, w, todayEntries, _ ->
            build(p, w, todayEntries)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, State())

    /**
     * How the last week went, or null while there is no plan to have gone against.
     *
     * ⚠️ Its own flow rather than a field on [State], and the reason is the assistant. `State` is
     * shared with the `health` tool through [composeHealthReading], and folding a week into it would
     * make every tool call assemble one for a caller that never asks. This is a screen's question.
     *
     * The source is `FoodLogStore.days`, a per-day nutrient map the store has published on every
     * index rebuild since it was written and which nothing has ever read. `refresh()` calls the
     * store's `load()`, which is what makes the flow non-empty — the store's own note warns that
     * every flow there starts empty and fills on first read, and that has silently hidden whole
     * categories of data elsewhere in this app.
     *
     * ⚠️ `todayStartMs()` is read on every emission rather than captured once. A phone left on the
     * counter overnight would otherwise keep scoring the week against yesterday, which is the same
     * midnight bug this view model has already had once.
     */
    val week: StateFlow<IntakeWeek.Week?> =
        combine(c.foodLogStore.days, state) { byDay, s ->
            IntakeWeek.score(byDay, s.targets?.kcal, dayGrid(IntakeWeek.DEFAULT_WINDOW_DAYS))
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * ⚠️ Delegates to [composeHealthReading], which is a TOP-LEVEL function rather than a method
     * here for one reason: the `health` assistant tool has to answer the same questions, and a
     * second copy of this arithmetic is how the Computer and the screen start quoting different
     * calorie targets for the same day. One composition, two readers.
     */
    private suspend fun build(
        p: HealthSettings,
        w: List<BodyTrend.Weighin>,
        todayEntries: List<NutritionDay.Entry>,
    ): State = composeHealthReading(p, w, todayEntries, c.foodLogStore, todayStartMs())



    // ------------------------------------------------------------------------------------ actions

    /**
     * True once the reader has deliberately stepped off today.
     *
     * ⚠️ It exists for midnight. A view model outlives a day — this one survives navigating away and
     * back, and a phone left on the counter overnight keeps it alive indefinitely — so `_today`, set
     * once at construction, would quietly go on filing breakfast under yesterday. [refresh] therefore
     * snaps forward, but only when nobody chose the day being shown: yanking somebody out of Tuesday
     * because they happened to reopen the tab after midnight would be a worse bug than the one being
     * fixed, and far more confusing, because the log would look like it had lost their entries.
     */
    private var pinnedDay = false

    fun refresh() {
        viewModelScope.launch {
            if (!pinnedDay) _today.value = todayStartMs()
            c.foodLogStore.load()
            c.bodyStore.all()
            // ⚠️ Read in explicitly, for [myFoods] rather than for the search: `search()` loads on
            // demand under its own lock, but the FLOW starts empty and fills on first read, so
            // without this the saved-foods list renders as "nothing yet" on a cold screen even
            // though the search behind it can see them.
            c.customFoodStore.load()
            c.progressPhotoStore.load()
            reloadEntries()
            recompute.value++
        }
    }

    fun showDay(dayStartMs: Long) {
        pinnedDay = dayStartMs != todayStartMs()
        _today.value = dayStartMs
        viewModelScope.launch { reloadEntries() }
    }

    private suspend fun reloadEntries() {
        _entries.value = c.foodLogStore.entriesFor(_today.value)
        // ⚠️ Refreshed here rather than once at construction: something logged a moment ago is the
        // most likely thing to be logged again, and a list that never moves is one nobody looks at.
        _recents.value = c.foodLogStore.recentFoods(System.currentTimeMillis())
    }

    fun recordWeighin(kg: Double) {
        if (!kg.isFinite() || kg <= 0.0) return
        val now = System.currentTimeMillis()
        val day = dayStartOf(now)
        c.bodyStore.record(now, kg, day)
        _notice.value = "Weigh-in recorded."
        publishWeighin(now, kg, day)
    }

    /**
     * Delete a reading here, and take it out of Health Connect too.
     *
     * ⚠️ The withdrawal is what stops "delete" meaning "delete here". Without it a reading typed by
     * mistake stays visible to every other app on the phone, and — since the import asks for
     * everything newer than the newest reading still held — deleting the newest one would let the
     * next import bring the mistake straight back.
     */
    fun removeWeighin(atMs: Long) {
        c.bodyStore.removeWeighin(atMs)
        viewModelScope.launch {
            if (c.healthConnect.canPublish()) c.healthConnect.withdrawWeightBetween(atMs, atMs + 1)
        }
    }

    /**
     * Send a typed-in reading out, so a scale app or a watch can see it.
     *
     * ⚠️ **Gated on the write permission actually being granted, and silent when it is not.** The
     * panel offers this connection; somebody who never set it up must not be told about Health
     * Connect every time they step off the scales.
     *
     * ⚠️ The day is withdrawn before the new reading is published, because `BodyStore.record`
     * REPLACES a day's reading rather than appending — weighing twice before breakfast is a
     * correction here. Publishing without withdrawing would leave the mistaken first number in
     * Health Connect for good, and the two records would disagree about the same morning.
     *
     * A failure sets the sync status rather than the general notice: the status line lives on the
     * Health Connect panel, which is where somebody can do something about it.
     *
     * ⚠️ The window ends at [dayPlus], never at `dayStartMs + 86_400_000`. A local day is 23 hours
     * the morning the clocks go forward, so a fixed day would reach an hour into tomorrow and
     * withdraw a reading that belongs to it — the trap this file already warns about at [dayPlus].
     */
    private fun publishWeighin(atMs: Long, kg: Double, dayStartMs: Long) {
        viewModelScope.launch {
            if (!c.healthConnect.canPublish()) return@launch
            c.healthConnect.withdrawWeightBetween(dayStartMs, dayPlus(dayStartMs, 1))
            if (!c.healthConnect.publishWeight(atMs, kg)) {
                _syncStatus.value = "That weigh-in could not be sent to Health Connect."
            }
        }
    }

    fun recordMeasurement(kind: BodyStore.MeasureKind, cm: Double) {
        c.bodyStore.recordMeasurement(System.currentTimeMillis(), kind, cm)
        _notice.value = "${kind.label} recorded."
    }

    /**
     * Take a measurement back.
     *
     * ⚠️ Keyed on the instant AND the kind, because `recordMeasurement` appends rather than
     * replacing — several kinds can share a moment if somebody works round themselves with a tape,
     * and removing by time alone would take the lot.
     */
    fun removeMeasurement(kind: BodyStore.MeasureKind, atMs: Long) {
        c.bodyStore.removeMeasurement(atMs, kind)
        _notice.value = "${kind.label} removed."
    }

    /**
     * Log something by its numbers alone.
     *
     * The whole food database arrives in a later slice; this is the path that never needs one and never
     * goes away — a label in your hand, or a meal somebody cooked you, is faster to type than to search.
     */
    // ------------------------------------------------------------------- eaten before

    private val _recents = MutableStateFlow<List<NutritionDay.Entry>>(emptyList())

    /**
     * Distinct foods eaten recently, most recent first.
     *
     * Logging the same breakfast every morning is the commonest thing anybody does with a food log,
     * and making them search for it again each time is the commonest reason they stop.
     */
    val recents: StateFlow<List<NutritionDay.Entry>> = _recents.asStateFlow()

    /**
     * Log a past entry again, on the day currently shown.
     *
     * ⚠️ The numbers are COPIED, never recomputed — see [NutritionDay.again]. Re-deriving them from a
     * per-100-gram figure would be a second chance to get the portion arithmetic wrong, on a value
     * the person has already checked and accepted once.
     */
    fun logAgain(entry: NutritionDay.Entry, meal: NutritionDay.Meal = entry.meal) {
        viewModelScope.launch {
            c.foodLogStore.add(
                NutritionDay.again(
                    entry,
                    id = UUID.randomUUID().toString(),
                    dayStartMs = _today.value,
                    nowMs = System.currentTimeMillis(),
                    meal = meal,
                ),
            )
            reloadEntries()
            recompute.value++
        }
    }

    // ------------------------------------------------------------------- finding a food

    /**
     * What a food search is currently showing.
     *
     * ⚠️ [note] exists so the screen can say *why* a list is short. A phone in a basement supermarket
     * gets the bundled half and nothing else, and rendering that identically to "we looked everywhere
     * and this is all there is" tells somebody their packaged food does not exist when in fact nobody
     * could ask.
     */
    data class Search(
        val query: String = "",
        val results: List<Food> = emptyList(),
        val busy: Boolean = false,
        val note: String = "",
        /**
         * True while the full 4.4-million-product scan is running.
         *
         * ⚠️ Separate from [busy] because the two mean different things to somebody looking at the
         * screen. [busy] is the as-you-type search and clears in a moment; this one takes a second or
         * more, was asked for by a button press, and needs to say so — a spinner that appears on a
         * keystroke and one that appears on a deliberate action cannot share a label.
         */
        val searchingAll: Boolean = false,
        /**
         * Whether the last full scan stopped at its cap.
         *
         * ⚠️ Kept because a truncated list and a complete one look identical, and "these are the best
         * matches" is a different claim from "these are the best of the first few thousand". A one-word
         * query against a supermarket-sized corpus reaches the cap easily.
         */
        val allTruncated: Boolean = false,
    )

    private val _search = MutableStateFlow(Search())
    val search: StateFlow<Search> = _search.asStateFlow()

    /** The food being portioned, if the reader has picked one. */
    private val _picked = MutableStateFlow<Food?>(null)
    val picked: StateFlow<Food?> = _picked.asStateFlow()

    /**
     * What the next pick is FOR.
     *
     * ⚠️ One view model serves every sub-tab, so the search box and the picked food are shared
     * between logging a meal and building a recipe. Without this, picking a food on RECIPES and then
     * switching to INTAKE would show that food in the log's portion picker, and the two screens would
     * disagree about what the person is doing. Making the destination explicit means the wrong screen
     * cannot render the picker at all, rather than relying on the two never being open together.
     */
    enum class PickFor { LOG, RECIPE }

    private val _pickFor = MutableStateFlow(PickFor.LOG)
    val pickFor: StateFlow<PickFor> = _pickFor.asStateFlow()

    fun searchFor(target: PickFor) {
        if (_pickFor.value == target) return
        _pickFor.value = target
        // A pick belongs to the destination that made it. Carrying it across would be the bug above.
        _picked.value = null
    }

    private var searchJob: Job? = null

    /**
     * Search as they type.
     *
     * ⚠️ Debounced, and the previous search is **cancelled** rather than left to finish. Without that
     * the answer to "chick" can land after the answer to "chicken" and overwrite it — the results
     * would visibly go backwards while somebody is still typing, which reads as the search being
     * broken rather than slow.
     */
    fun onSearchQuery(query: String) {
        _search.value = _search.value.copy(query = query)
        searchJob?.cancel()
        if (query.trim().length < MIN_QUERY) {
            _search.value = _search.value.copy(results = emptyList(), busy = false, note = "")
            return
        }
        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            _search.value = _search.value.copy(busy = true)
            val online = c.isOnline()
            val r = c.foodRepository.search(query, online)
            // ⚠️ Guard against a stale answer landing on a query that has moved on. The cancel above
            // handles the common case; this handles the one where the request had already returned.
            if (_search.value.query != query) return@launch
            _search.value = Search(
                query = query,
                results = r.foods,
                busy = false,
                note = when {
                    r.onlineFailure != null ->
                        "Offline foods only — the packaged-food database could not be reached."
                    !r.onlineConsulted ->
                        "Offline foods only — no network, so packaged goods are not in this list."
                    else -> ""
                },
            )
        }
    }

    /**
     * Search every bundled product, not only the ones whose name starts with what was typed.
     *
     * ⚠️ **A deliberate action rather than a keystroke, and the whole design follows from that.** The
     * as-you-type path can only afford an indexed prefix scan, which cannot find "Coca-Cola Zero
     * Sugar" from "coke zero"; this is a full table scan over 4.4 million rows, measured at roughly a
     * second on a desktop and slower on a phone reading it cold. It is worth a second when somebody
     * pressed a button and knows the product is in there; it would be intolerable per character.
     *
     * ⚠️ Results are APPENDED rather than replacing the list, and de-duplicated by id. Somebody who
     * pressed this has already seen the ranked local answers and is asking for more — throwing those
     * away to show a different set would lose the row they might have wanted.
     *
     * ⚠️ Uses its own [scanJob] rather than [searchJob]. A scan that takes a second must survive the
     * next keystroke's debounce cancelling the typed search, and equally must not be cancelled by it —
     * they are two different requests and sharing one handle would have each silently kill the other.
     */
    private var scanJob: Job? = null

    fun searchEveryProduct() {
        val query = _search.value.query.trim()
        if (query.length < MIN_QUERY) return
        scanJob?.cancel()
        _search.value = _search.value.copy(searchingAll = true, allTruncated = false)
        scanJob = viewModelScope.launch {
            val scan = c.foodRepository.searchAllBundled(query)
            // ⚠️ The query may have moved on during the scan — a second is long enough to type in.
            // Landing these results on a different query would be the "results go backwards" defect
            // the typed path already guards against, arriving from the other direction.
            if (_search.value.query.trim() != query) return@launch
            val seen = _search.value.results.mapTo(HashSet()) { it.id }
            _search.value = _search.value.copy(
                results = _search.value.results + scan.foods.filterNot { it.id in seen },
                searchingAll = false,
                allTruncated = scan.truncated,
                note = when {
                    scan.foods.isEmpty() ->
                        "No bundled product has all of those words in its name."
                    else -> _search.value.note
                },
            )
        }
    }

    /**
     * Look up a scanned barcode.
     *
     * ⚠️ Every outcome is a sentence, because Open Food Facts answers a barcode it has never heard of
     * with **HTTP 200 and `status: 0`** — probed, not assumed. "The request failed" and "nobody has
     * ever added this product" arrive as the same HTTP code and are completely different things to
     * tell somebody standing in a supermarket: one means try again, the other means type it in.
     */
    fun lookUpBarcode(code: String) {
        searchJob?.cancel()
        _search.value = Search(query = code, busy = true)
        searchJob = viewModelScope.launch {
            when (val r = c.foodRepository.byBarcode(code)) {
                is FoodLookup.Found -> {
                    _search.value = Search()
                    _picked.value = r.food
                }
                // ⚠️ **Neither of these names a card, and that is a rule now rather than a
                // wording choice.** This copy is written in a shared module and rendered by two
                // applications whose screens are called different things — the LCARS one has QUICK
                // ADD and the standalone one has "Type it in" — so naming either sends half the
                // readers looking for something that is not on their screen. Describe the action.
                is FoodLookup.NoNutrition -> _search.value = Search(
                    query = code,
                    note = "${r.food.display} is in the database, but nobody has filled in its " +
                        "nutrition. Typing the numbers off the label works.",
                )
                is FoodLookup.NotInDatabase -> _search.value = Search(
                    query = code,
                    note = "Barcode $code is not in the packaged-food database. Typing the numbers " +
                        "off the label works.",
                )
                is FoodLookup.Unreachable -> _search.value = Search(
                    query = code,
                    note = "Could not reach the packaged-food database — ${r.reason}. Worth another try.",
                )
            }
        }
    }

    fun pick(food: Food?) {
        _picked.value = food
    }

    /**
     * Log a portion of a found food.
     *
     * ⚠️ The conversion to what was actually eaten happens **here and nowhere else**, through
     * [FoodPortion.eaten]. Every source publishes per 100 grams; an entry stores what is on the plate.
     * Carrying a per-100-gram figure any further is how a 30-gram biscuit gets logged as a packet.
     */
    fun logPortion(food: Food, amount: Double, unit: FoodPortion.Unit, meal: NutritionDay.Meal) {
        val portion = FoodPortion.Portion(amount, unit)
        val grams = FoodPortion.gramsFor(portion, food.sizes) ?: return
        val eaten = FoodPortion.eaten(food.per100g, grams)
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            c.foodLogStore.add(
                NutritionDay.Entry(
                    id = UUID.randomUUID().toString(),
                    dayStartMs = _today.value,
                    atMs = now,
                    name = food.display,
                    grams = grams,
                    nutrients = eaten,
                    meal = meal,
                    brand = food.brand,
                    servingLabel = food.servingLabel,
                    source = food.source,
                    foodId = food.id,
                    // Through the same scaling rule as the macros above — see FoodPortion.
                    micros = FoodPortion.eatenMicros(food.microsPer100g, grams),
                    extras = FoodPortion.eatenExtras(food.extrasPer100g, grams),
                ),
            )
            _picked.value = null
            _search.value = Search()
            reloadEntries()
            recompute.value++
        }
    }

    /**
     * Log figures taken straight off a label, optionally remembering the food.
     *
     * ⚠️ [keepAsFood] is honoured only when [grams] gives a real weight, and the refusal is in
     * `FoodPortion.per100gFrom` rather than here: a saved food is a density, and there is no honest
     * way to derive one from "320 calories". The surface disables the switch and says so, and this
     * re-checks rather than trusting it, because the switch does not clear itself when the weight
     * field is emptied.
     */
    fun quickAdd(
        name: String,
        kcal: Double,
        proteinG: Double,
        fatG: Double,
        carbG: Double,
        meal: NutritionDay.Meal,
        grams: Double = 0.0,
        keepAsFood: Boolean = false,
        micros: Micronutrients.Amounts = Micronutrients.Amounts(),
        extras: NutrientSet.Amounts = NutrientSet.Amounts(),
    ) {
        val label = name.trim().ifBlank { "Quick add" }
        if (!kcal.isFinite() || kcal < 0.0) return
        val now = System.currentTimeMillis()
        val eaten = NutritionDay.Nutrients(kcal = kcal, proteinG = proteinG, fatG = fatG, carbG = carbG)
        viewModelScope.launch {
            // ⚠️ Saved BEFORE the entry, so the log can carry the new food's id. Otherwise the entry
            // says CUSTOM with no foodId and nothing later can tell that the two are the same food.
            val saved =
                if (keepAsFood && name.isNotBlank()) {
                    FoodPortion.per100gFrom(eaten, grams)
                        // ⚠️ Re-checked here as well as on screen, for the same reason the weight is:
                        // the switch does not clear itself when a figure changes underneath it. And
                        // an impossible density must not be saved — `Food.of` would sanitise it to a
                        // food with no numbers at all, which reads as the app having lost it.
                        ?.takeIf { FoodPortion.densityLooksWrong(it) == null }
                        ?.let { per100 ->
                            c.customFoodStore.save(
                                name = label,
                                per100g = per100,
                                servingGrams = grams,
                                // ⚠️ Converted, not passed through. Everything typed above is what was
                                // EATEN; a saved food is a density. Handing the eaten figures straight
                                // to the store would record a 30 g biscuit's magnesium as if it were a
                                // hundred grams of it — and the macros beside them, which do convert,
                                // would then describe a different portion of the same food.
                                micros = FoodPortion.per100gMicrosFrom(micros, grams),
                                extras = FoodPortion.per100gExtrasFrom(extras, grams),
                            )
                        }
                } else {
                    null
                }
            c.foodLogStore.add(
                NutritionDay.Entry(
                    id = UUID.randomUUID().toString(),
                    dayStartMs = _today.value,
                    atMs = now,
                    name = label,
                    grams = grams,
                    nutrients = eaten,
                    meal = meal,
                    micros = micros,
                    extras = extras,
                    source = NutritionDay.Source.CUSTOM,
                    foodId = saved?.id.orEmpty(),
                ),
            )
            reloadEntries()
            recompute.value++
        }
    }

    // ----------------------------------------------------------------------------- your own foods

    /** Foods typed in by hand, newest first. Searched ahead of both databases. */
    val myFoods: StateFlow<List<Food>> = c.customFoodStore.foods

    fun forgetFood(id: String) {
        viewModelScope.launch { c.customFoodStore.remove(id) }
    }

    // -------------------------------------------------------------------------- progress photos

    /** Newest first. App-private files, never the camera roll. */
    val photos: StateFlow<List<ProgressPhotoStore.Photo>> = c.progressPhotoStore.photos

    /**
     * How much disk the photographs are using.
     *
     * ⚠️ Derived from the list rather than polled, so it is recomputed exactly when a photograph is
     * taken or deleted and not once a second for a screen nobody is looking at. Each recomputation
     * stats every file, which is cheap for the handful this holds and is the reason it hangs off a
     * change rather than a timer.
     *
     * Starts at zero, which is also the honest answer for an empty list — the panel only prints the
     * figure when there is something to print, so "not measured yet" never renders as "0.0 MB".
     */
    val photoBytes: StateFlow<Long> = c.progressPhotoStore.photos
        .map { c.progressPhotoStore.bytesOnDisk() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    fun photoUri(id: String): android.net.Uri? = c.progressPhotoStore.uriFor(id)

    /**
     * Where the camera should write the next photograph, or null if the file cannot be made.
     *
     * ⚠️ Reserving does NOT record it. A cancelled capture would otherwise leave an index row
     * pointing at a zero-byte file, which the store's load-time sweep cannot catch because the file
     * genuinely exists. [photoTaken] is the half that records, and only on success.
     */
    fun reservePhoto(): Pair<String, android.net.Uri>? =
        c.progressPhotoStore.reserve(System.currentTimeMillis())

    fun photoTaken(id: String) {
        viewModelScope.launch {
            c.progressPhotoStore.confirm(id, System.currentTimeMillis())
        }
    }

    fun forgetPhoto(id: String) {
        viewModelScope.launch { c.progressPhotoStore.remove(id) }
    }

    // ------------------------------------------------------------------------ photograph a meal

    /**
     * What a photograph of a plate has turned into so far.
     *
     * ⚠️ **This is a review step, and the state machine is what makes it one.** A [Plate] is a list
     * of proposals sitting on screen waiting to be corrected; nothing reaches the log until somebody
     * presses the button. The portion especially is a guess — the model has weighed nothing — so
     * writing these straight into a day's total would put invented grams beside weighed ones with
     * no way to tell them apart afterwards.
     */
    sealed interface MealShot {
        data object Idle : MealShot
        data object Reading : MealShot
        data class Plate(
            val proposals: List<MealPhotos.Proposal>,
            val summary: String,
        ) : MealShot

        /** The model looked and says this is not food. Its own answer, not a failure. */
        data object NotFood : MealShot

        /** No vision-capable model is configured. The one state that is not an error. */
        data object NoVision : MealShot
        data class Failed(val reason: String) : MealShot
    }

    private val _mealShot = MutableStateFlow<MealShot>(MealShot.Idle)
    val mealShot: StateFlow<MealShot> = _mealShot.asStateFlow()

    /**
     * Read a captured photograph into proposals.
     *
     * ⚠️ Encoding happens off the main thread — a JPEG off a modern phone camera is several
     * megapixels and both the decode and the base64 pass are long enough to drop frames. The reader
     * itself suspends on a network call, so the whole thing belongs in a coroutine regardless.
     */
    fun readMealPhoto(context: android.content.Context, uri: android.net.Uri) {
        mealShotJob?.cancel()
        _mealShot.value = MealShot.Reading
        val reader = c.mealPhotoReader
        if (reader == null) {
            // ⚠️ Not a failure. This application simply has no vision model, and the surface says so
            // rather than showing a button that quietly does nothing.
            _mealShot.value = MealShot.NoVision
            return
        }
        mealShotJob = viewModelScope.launch {
            _mealShot.value = when (val r = reader.read(context, uri)) {
                is MealPhotos.Result.Plate -> MealShot.Plate(r.proposals, r.summary)
                is MealPhotos.Result.NotFood -> MealShot.NotFood
                is MealPhotos.Result.NoVision -> MealShot.NoVision
                is MealPhotos.Result.Failed -> MealShot.Failed(r.reason)
            }
        }
    }

    private var mealShotJob: Job? = null

    /** Correct a portion the model guessed at. Nutrition re-derives from the matched record. */
    fun editMealGrams(index: Int, grams: Double) {
        val plate = _mealShot.value as? MealShot.Plate ?: return
        val p = plate.proposals.getOrNull(index) ?: return
        if (!grams.isFinite() || grams <= 0.0) return
        _mealShot.value = plate.copy(
            proposals = plate.proposals.toMutableList().also {
                it[index] = p.copy(item = p.item.copy(grams = grams))
            },
        )
    }

    /** Drop something the model saw that was not there, or that nobody ate. */
    fun dropMealItem(index: Int) {
        val plate = _mealShot.value as? MealShot.Plate ?: return
        if (index !in plate.proposals.indices) return
        val left = plate.proposals.toMutableList().also { it.removeAt(index) }
        _mealShot.value = if (left.isEmpty()) MealShot.Idle else plate.copy(proposals = left)
    }

    fun clearMealShot() {
        mealShotJob?.cancel()
        _mealShot.value = MealShot.Idle
    }

    /**
     * Log everything on the plate that has real numbers behind it.
     *
     * ⚠️ Each proposal becomes its own entry rather than one combined "meal", and that is
     * deliberate: the log is what the coach measures expenditure from and what MACROS breaks down,
     * and a single row reading "photographed meal" is unusable for both. Separate rows are also the
     * only way to correct one item later without re-photographing the plate.
     *
     * ⚠️ Anything unmatched is skipped rather than logged with zeros. Its name is returned so the
     * surface can say which ones were left behind — silently dropping them is how somebody comes to
     * believe a day is fully logged when it is not.
     */
    fun logPlate(meal: NutritionDay.Meal) {
        val plate = _mealShot.value as? MealShot.Plate ?: return
        val loggable = plate.proposals.filter { it.loggable }
        if (loggable.isEmpty()) return
        val now = System.currentTimeMillis()
        val day = _today.value
        viewModelScope.launch {
            loggable.forEach { p ->
                val food = p.match ?: return@forEach
                val grams = p.item.grams
                c.foodLogStore.add(
                    NutritionDay.Entry(
                        id = UUID.randomUUID().toString(),
                        dayStartMs = day,
                        atMs = now,
                        // ⚠️ The model's words, not the matched record's. "scrambled eggs" is what
                        // was on the plate; the record it borrowed numbers from may well be called
                        // "Egg, whole, cooked, scrambled" and reading that back would be a small
                        // lie about what was photographed.
                        name = p.item.name,
                        grams = grams,
                        nutrients = FoodPortion.eaten(food.per100g, grams),
                        meal = meal,
                        servingLabel = food.servingLabel,
                        source = food.source,
                        foodId = food.id,
                        micros = FoodPortion.eatenMicros(food.microsPer100g, grams),
                    extras = FoodPortion.eatenExtras(food.extrasPer100g, grams),
                    ),
                )
            }
            _mealShot.value = MealShot.Idle
            reloadEntries()
            recompute.value++
        }
    }

    // -------------------------------------------------------------------------- Health Connect

    /** Whether a scale or watch can reach this tab at all, re-read rather than cached. */
    fun healthConnect(): HealthConnectBridge = c.healthConnect

    private val _syncStatus = MutableStateFlow("")

    /** What the last import did, in a sentence. Blank until one has been tried. */
    val syncStatus: StateFlow<String> = _syncStatus.asStateFlow()

    /**
     * Pull weigh-ins recorded by anything else into the record.
     *
     * ⚠️ The permission is checked FIRST and reported separately. `weighinsSince` returns an empty
     * list both for "nothing new" and for "could not ask", and telling somebody "no new readings"
     * when the truth is "you refused the permission" is the silent-failure shape this repo keeps
     * correcting.
     *
     * ⚠️ Imported readings go through `BodyStore.record` with the day computed here, so the
     * same-day replacement rule applies exactly as it does to a typed one — importing twice cannot
     * double a morning's weight in the trend.
     */
    fun importFromHealthConnect() {
        viewModelScope.launch {
            val hc = c.healthConnect
            if (hc.availability() !is HealthConnectBridge.Availability.Ready) {
                _syncStatus.value = "Health Connect is not available on this device."
                return@launch
            }
            if (!hc.hasAll()) {
                _syncStatus.value = "Permission has not been granted, so nothing could be read."
                return@launch
            }
            // ⚠️ Since the newest reading already held, not "since forever". A first import brings
            // the lot; every later one asks only for what it has not seen, which is both cheaper and
            // the reason re-importing cannot resurrect a reading deliberately deleted here.
            val newest = c.bodyStore.all().maxOfOrNull { it.atMs } ?: 0L
            val found = hc.weighinsSince(newest)
            found.forEach { c.bodyStore.record(it.atMs, it.kg, dayStartOf(it.atMs)) }
            _syncStatus.value = when (found.size) {
                0 -> "Nothing new to bring in."
                1 -> "Brought in 1 weigh-in."
                else -> "Brought in ${found.size} weigh-ins."
            }
            recompute.value++
        }
    }

    fun removeEntry(id: String) {
        viewModelScope.launch {
            c.foodLogStore.remove(id, _today.value)
            reloadEntries()
            recompute.value++
        }
    }

    // ---------------------------------------------------------------------------------- habits

    /**
     * Today's walking, straight off the pedometer.
     *
     * ⚠️ Null means the count is unknown, not zero. Without ACTIVITY_RECOGNITION granted the sensor
     * delivers nothing at all, and a screen showing "0 steps" to somebody who has walked all morning
     * is worse than one saying it cannot tell.
     */
    val steps: StateFlow<Habits.Steps?> = c.bodyStore.steps

    /**
     * Every habit and how long its run is.
     *
     * ⚠️ Derived from the record on every change, never stored. Each day set is a question the
     * existing stores can already answer, which is the whole point — a streak nobody can tap is a
     * statement about how far the measured expenditure can be trusted, and a stored one would be a
     * number that was true once.
     */
    val habits: StateFlow<Map<Habits.Habit, Habits.Streak>> =
        combine(c.foodLogStore.days, weighins, state) { byDay, w, s ->
            val today = _today.value
            val target = s.targets

            val logged = byDay.filterValues { it.kcal > 0.0 }.keys

            // ⚠️ A weigh-in is stamped with the moment it was taken, not a day start, so it has to be
            // folded down to the reader's own day before it can be compared with the log's keys.
            val weighed = w.map { dayStartOf(it.atMs) }.toSet()

            val protein = if (target == null) emptySet() else byDay
                .filterValues { it.proteinG >= target.proteinG }.keys

            val inBand = if (target == null || target.kcal <= 0) emptySet() else byDay
                .filterValues {
                    it.kcal > 0.0 && abs(it.kcal - target.kcal) <= target.kcal * IntakeWeek.ON_TARGET_BAND
                }.keys

            mapOf(
                Habits.Habit.LOG_EVERY_DAY to Habits.streak(logged, today, ::dayBefore),
                Habits.Habit.WEIGH_IN to Habits.streak(weighed, today, ::dayBefore),
                Habits.Habit.HIT_PROTEIN to Habits.streak(protein, today, ::dayBefore),
                Habits.Habit.STAY_IN_BAND to Habits.streak(inBand, today, ::dayBefore),
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /**
     * Fold a raw pedometer reading in.
     *
     * ⚠️ Called from the HABITS tab's sensor collector rather than app-wide. The counter is
     * cumulative and hardware-maintained, so nothing is lost by only reading it while somebody is
     * looking — the total is still right the moment they open the tab, and a collector running for
     * the life of the process would keep the sensor registered for a number nobody is reading.
     */
    fun onSteps(raw: Long) = c.bodyStore.onStepReading(raw, todayStartMs())

    // ---------------------------------------------------------------------------- your own copy

    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    private val _exportStatus = MutableStateFlow("")

    /** What the last export did, in a sentence. Blank until one has been tried. */
    val exportStatus: StateFlow<String> = _exportStatus.asStateFlow()

    /**
     * Write the whole record to a file the reader chose.
     *
     * ⚠️ [exporting] is set before the launch and cleared in a `finally`. This opens every shard of
     * the food log, so on a long record it is genuinely slow — a button with no busy state invites a
     * second tap, and a second export while the first is still walking the shards would read the same
     * files twice for no reason.
     */
    fun exportRecord(uri: android.net.Uri) {
        if (_exporting.value) return
        _exporting.value = true
        _exportStatus.value = ""
        viewModelScope.launch {
            try {
                _exportStatus.value = c.healthExporter.export(uri).message
            } finally {
                _exporting.value = false
            }
        }
    }

    /**
     * Read a record back in from a file the reader chose.
     *
     * ⚠️ Shares [exporting] and [exportStatus] rather than adding a second pair, and that is a
     * behavioural decision as much as a tidy one: the two buttons sit beside each other and touch the
     * same stores, so one busy flag means neither can run while the other is walking the log. Two
     * flags would let somebody export and import at once, over the same shards, for no gain.
     */
    fun importRecord(uri: android.net.Uri) {
        if (_exporting.value) return
        _exporting.value = true
        _exportStatus.value = ""
        viewModelScope.launch {
            try {
                _exportStatus.value = c.healthImporter.import(uri).message
                // The day on screen may have gained entries, and the coach's numbers are built on
                // days that just changed underneath it.
                reloadEntries()
                recompute.value++
            } finally {
                _exporting.value = false
            }
        }
    }

    // --------------------------------------------------------------------------------- recipes

    /** Every saved recipe, newest first. */
    val recipes: StateFlow<List<Recipes.Recipe>> = c.recipeStore.recipes

    /**
     * The recipe being built or edited, or null when the builder is closed.
     *
     * ⚠️ Held here rather than in the composable. A builder is several minutes of work across a
     * search, a scan and half a dozen weights, and `remember` dies the moment the reader navigates to
     * MACROS to check something — which is exactly when they would lose it.
     */
    private val _draft = MutableStateFlow<Recipes.Recipe?>(null)
    val draft: StateFlow<Recipes.Recipe?> = _draft.asStateFlow()

    fun newRecipe(kind: Recipes.Kind = Recipes.Kind.RECIPE) {
        _draft.value = Recipes.Recipe(id = UUID.randomUUID().toString(), name = "", kind = kind)
        searchFor(PickFor.RECIPE)
    }

    fun editRecipe(r: Recipes.Recipe) {
        _draft.value = r
        searchFor(PickFor.RECIPE)
    }

    /** Close the builder without saving. The saved copy, if there is one, is untouched. */
    fun closeDraft() {
        _draft.value = null
        _picked.value = null
        _search.value = Search()
        searchFor(PickFor.LOG)
    }

    /**
     * ⚠️ `editDraft`, not `edit`. This class already has a private `edit` taking
     * `(HealthSettings) -> HealthSettings`, and two single-lambda overloads make every `it.copy(...)`
     * at a call site ambiguous — the compiler cannot tell which receiver `it` is. Grep the class
     * before adding a private helper to it.
     */
    private fun editDraft(block: (Recipes.Recipe) -> Recipes.Recipe) {
        _draft.value = _draft.value?.let(block)
    }

    fun draftName(name: String) = editDraft { it.copy(name = name) }

    fun draftNote(note: String) = editDraft { it.copy(note = note) }

    /** Null clears the weighed yield, which is not the same as weighing it as zero. */
    fun draftYield(grams: Double?) = editDraft { it.copy(cookedYieldG = grams) }

    fun draftServings(n: Int) = editDraft { it.copy(servings = n.coerceAtLeast(1)) }

    /**
     * Switch a draft between a dish and a group of foods eaten together.
     *
     * ⚠️ The yield and the portion count are **left on the draft** rather than cleared. Nothing reads
     * them for a meal — `Recipes.yieldGrams` ignores a meal's yield outright, and `problems` does not
     * ask either question of one — so keeping them means somebody who flips the toggle to look, and
     * flips it straight back, still has the numbers they typed.
     */
    fun draftKind(kind: Recipes.Kind) = editDraft { it.copy(kind = kind) }

    /**
     * Add a found food to the draft at a weight.
     *
     * ⚠️ Goes through [FoodPortion.gramsFor] like every other portion in this feature, so a
     * "1 serving" of something is converted by the one function that knows how, rather than by a
     * second copy of that arithmetic living in the builder.
     */
    fun draftAdd(food: Food, amount: Double, unit: FoodPortion.Unit) {
        val grams = FoodPortion.gramsFor(FoodPortion.Portion(amount, unit), food.sizes) ?: return
        editDraft {
            it.copy(
                components = it.components + Recipes.Component(
                    foodId = food.id,
                    name = food.display,
                    per100g = food.per100g,
                    grams = grams,
                    micros = food.microsPer100g,
                    // ⚠️ Carried, not dropped. Without this a dish built entirely out of foods that
                    // record magnesium logs no magnesium — the figure is on the ingredient and would
                    // be discarded at the component, which is the same defect the micronutrient path
                    // had before it was fixed.
                    extras = food.extrasPer100g,
                ),
            )
        }
        _picked.value = null
        _search.value = Search()
    }

    /**
     * ⚠️ Removes by POSITION, not by food id. A recipe legitimately holds the same ingredient twice —
     * half the butter in the pastry and half in the filling — and removing by id would silently take
     * both.
     */
    fun draftRemoveAt(index: Int) = editDraft {
        if (index !in it.components.indices) it
        else it.copy(components = it.components.filterIndexed { i, _ -> i != index })
    }

    fun saveDraft() {
        val d = _draft.value ?: return
        viewModelScope.launch {
            c.recipeStore.save(d)
            closeDraft()
        }
    }

    fun deleteRecipe(id: String) {
        viewModelScope.launch {
            c.recipeStore.remove(id)
            if (_draft.value?.id == id) closeDraft()
        }
    }

    /**
     * Log a helping of a saved recipe.
     *
     * ⚠️ [Recipes.eatenGrams] and [Recipes.eatenServings] are the only two ways to get here, and the
     * core pins that they agree for the same amount of food. Weighing the pot is not always possible,
     * counting portions is not always accurate, and a person will use both — so the two must not be
     * able to disagree about what a helping came to.
     */
    fun logRecipe(
        recipe: Recipes.Recipe,
        amount: Double,
        byServings: Boolean,
        meal: NutritionDay.Meal,
    ) {
        val eaten = if (byServings) Recipes.eatenServings(recipe, amount)
        else Recipes.eatenGrams(recipe, amount)
        if (eaten == null) return
        // ⚠️ The SAME branch, so the two halves of one helping cannot describe different portions.
        // Deriving the micronutrients from the other route — say always by grams — would put a
        // calcium figure for 200 g beside a calorie figure for two portions on one row.
        val micros = if (byServings) Recipes.eatenServingsMicros(recipe, amount)
        else Recipes.eatenGramsMicros(recipe, amount)
        // ⚠️ The SAME branch again, for the reason above: a helping's magnesium must describe the
        // same portion its calories do.
        val extras = if (byServings) Recipes.eatenServingsExtras(recipe, amount)
        else Recipes.eatenGramsExtras(recipe, amount)
        val grams = if (byServings) (Recipes.servingGrams(recipe) ?: return) * amount else amount
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            c.foodLogStore.add(
                NutritionDay.Entry(
                    id = UUID.randomUUID().toString(),
                    dayStartMs = _today.value,
                    atMs = now,
                    name = recipe.name.ifBlank { "Recipe" },
                    grams = grams,
                    nutrients = eaten,
                    micros = micros ?: Micronutrients.Amounts(),
                    extras = extras ?: NutrientSet.Amounts(),
                    meal = meal,
                    // ⚠️ **RECIPE, and this used to say CUSTOM with a comment arguing against the very
                    // enum value that exists for this case.** `Source` answers "where did this record
                    // come from, so the surface can say how much to trust it", and a dish somebody
                    // assembled out of looked-up ingredients is neither a database record nor a figure
                    // typed off a label — which is exactly what `Source.RECIPE` was declared to mean.
                    // It had no producer at all until now.
                    //
                    // Safe in both directions: `FoodLogStore` persists source by enum NAME with a
                    // `getOrDefault(CUSTOM)` fallback, so an entry written by this build decodes as
                    // CUSTOM on any build that lacks the value rather than failing to decode.
                    source = NutritionDay.Source.RECIPE,
                    foodId = recipe.id,
                ),
            )
            reloadEntries()
            recompute.value++
        }
    }

    /**
     * Log a saved meal — every food in it, in one tap.
     *
     * ⚠️ **This is the whole difference between a meal and a recipe, and it is deliberately not one
     * entry.** A recipe is a density and becomes a single row, because a bolognese is one dish. A
     * meal is several foods that happen to arrive together, and logging it as one row would leave
     * INTAKE unable to say what was eaten and the macro panel unable to say which food the protein
     * came from — which is the entire reason somebody would break a day down by food at all.
     *
     * ⚠️ Each entry carries **its own food's id**, never the meal's. Stamping them all with the meal
     * would make porridge, a banana and a coffee look like the same food to recents, favourites and
     * "log this again".
     *
     * ⚠️ The arithmetic is [Recipes.eatenComponents], which is pure and tested and pins that the
     * portions sum to the meal. Looping over the components here would be a second definition of
     * "this much of that food", and the two would eventually disagree.
     */
    fun logMeal(recipe: Recipes.Recipe, scale: Double, meal: NutritionDay.Meal) {
        val parts = Recipes.eatenComponents(recipe, scale)
        if (parts.isEmpty()) return
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            parts.forEach { p ->
                c.foodLogStore.add(
                    NutritionDay.Entry(
                        id = UUID.randomUUID().toString(),
                        dayStartMs = _today.value,
                        atMs = now,
                        name = p.name,
                        grams = p.grams,
                        nutrients = p.nutrients,
                        micros = p.micros,
                        extras = p.extras,
                        meal = meal,
                        source = NutritionDay.Source.RECIPE,
                        foodId = p.foodId,
                    ),
                )
            }
            reloadEntries()
            recompute.value++
            _notice.value =
                "Logged ${parts.size} ${if (parts.size == 1) "food" else "foods"} " +
                    "from ${recipe.name.ifBlank { "your meal" }}."
        }
    }

    /** Repeat a whole day onto the one being shown — the fastest way to log a routine. */
    fun copyFrom(dayStartMs: Long) {
        viewModelScope.launch {
            val n = c.foodLogStore.copyDay(dayStartMs, _today.value, System.currentTimeMillis()) {
                UUID.randomUUID().toString()
            }
            reloadEntries()
            recompute.value++
            _notice.value = if (n > 0) "Copied $n entries." else "Nothing logged that day."
        }
    }

    fun clearNotice() {
        _notice.value = null
    }

    // ----------------------------------------------------------------------------------- the plan

    private fun edit(block: (HealthSettings) -> HealthSettings) {
        viewModelScope.launch {
            c.updateHealth(block)
            recompute.value++
        }
    }

    fun setHeightCm(v: Double) = edit { it.copy(heightCm = v.coerceIn(0.0, Body.MAX_HEIGHT_CM)) }
    fun setBirthYear(v: Int) = edit { it.copy(birthYear = v.coerceIn(0, LocalDate.now(zone).year)) }
    fun setSex(v: Body.Sex) = edit { it.copy(sex = v.name) }
    fun setGoalKg(v: Double) = edit { it.copy(goalKg = v.coerceIn(0.0, Body.MAX_KG)) }
    fun setRatePerWeekKg(v: Double) = edit { it.copy(ratePerWeekKg = v.coerceIn(-3.0, 3.0)) }
    fun setDietMode(v: MacroTargets.DietMode) = edit { it.copy(dietMode = v.name) }
    fun setActivity(v: Expenditure.Activity) = edit { it.copy(activity = v.name) }
    fun setProteinGPerKg(v: Double) = edit { it.copy(proteinGPerKg = v.coerceIn(0.0, 5.0)) }
    fun setMassUnit(v: BodyTrend.MassUnit) = edit { it.copy(massUnit = v.name) }
    fun setConfigured(v: Boolean) = edit { it.copy(configured = v) }

    init {
        refresh()
    }

    private companion object {
        const val MIN_QUERY = 2
        /** Long enough that an ordinary typist fires one search per word, short enough to feel live. */
        const val DEBOUNCE_MS = 280L
    }
}

/**
 * The whole health reading, composed from the raw record by the pure cores.
 *
 * ⚠️ **Top-level, and shared with the `health` assistant tool.** Nothing derived is stored anywhere,
 * so the only way the Computer and the HEALTH screen can disagree about a calorie target is if there
 * are two copies of this arithmetic. There is one.
 */
suspend fun composeHealthReading(
    p: HealthSettings,
    w: List<BodyTrend.Weighin>,
    todayEntries: List<NutritionDay.Entry>,
    foodLog: dev.mascwa.pulse.data.health.FoodLogStore,
    todayStartMs: Long,
): HealthViewModel.State {
    val trend = BodyTrend.estimate(w)
    val latestKg = (trend as? BodyTrend.Trend.Estimated)?.latest?.trendKg
    val person = person(p, latestKg)
    val now = System.currentTimeMillis()

    val window = p.expenditureWindowDays.coerceIn(14, 120)
    // The window's far edge is a day boundary, so it comes from a calendar. An hour of slop there
    // is an extra or a missing day of intake at the far end, which is a day the measurement then
    // weighs against a window length it was told separately.
    val from = HealthDays.plus(todayStartMs, -window.toLong())
    val intake = foodLog.intakeDays(from, now)
    val measured = Expenditure.measure(trend, intake, now, window)

    val bmr = person?.let { Body.bmr(it) }
    val activity = runCatching { Expenditure.Activity.valueOf(p.activity) }
        .getOrDefault(Expenditure.Activity.LIGHT)
    val formula = bmr?.takeIf { it.isFinite() }?.let { Expenditure.fromFormula(it, activity) }

    // ⚠️ The blend, not a switch. Inverse-variance weighting hands the answer over as the
    // measurement tightens, so there is no day on which the number jumps and no threshold to pick.
    val known = measured as? Expenditure.Estimate.Known
    val expenditure = when {
        formula != null && known != null -> Expenditure.blend(formula, known)
        known != null -> known
        else -> formula
    }
    val share = if (formula != null && known != null) Expenditure.measuredShare(formula, known) else 0.0

    val plan = if (person != null && expenditure != null) {
        MacroTargets.plan(
            MacroTargets.Request(
                person = person,
                expenditure = expenditure,
                ratePerWeekKg = p.ratePerWeekKg,
                mode = runCatching { MacroTargets.DietMode.valueOf(p.dietMode) }
                    .getOrDefault(MacroTargets.DietMode.BALANCED),
                proteinGPerKgOverride = p.proteinGPerKg.takeIf { it > 0.0 },
                goalKg = p.goalKg.takeIf { it > 0.0 },
            ),
        )
    } else {
        null
    }

    return HealthViewModel.State(
        profile = p,
        person = person,
        trend = trend,
        formula = formula,
        measured = measured,
        expenditure = expenditure,
        measuredShare = share,
        plan = plan,
        eatenToday = NutritionDay.total(todayEntries),
        microsToday = NutritionDay.microTotal(todayEntries),
        extrasToday = NutritionDay.extraTotal(todayEntries),
        loggedDaysInWindow = intake.size,
    )
}

/**
 * The person the cores need, or null when too little is known.
 *
 * ⚠️ The weight comes from the **trend**, not the newest reading. Everything downstream of this —
 * the resting-rate floor, the protein reference, the rate cap — would otherwise move by a kilogram
 * or two depending on which morning the scale was stepped on, and a calorie target that changes
 * because of yesterday's salt is exactly what the trend exists to prevent.
 *
 * ⚠️ The zone is read here rather than taken from the view model. Age off a UTC year is wrong for
 * anyone born in the closing hours of a year and living east of Greenwich — and it feeds the resting
 * rate, so the error would land on a calorie target rather than on a label.
 */
private fun person(p: HealthSettings, trendKg: Double?): Body.Person? {
    if (p.heightCm <= 0.0 || p.birthYear <= 0 || trendKg == null) return null
    val age = LocalDate.now(ZoneId.systemDefault()).year - p.birthYear
    val sex = runCatching { Body.Sex.valueOf(p.sex) }.getOrDefault(Body.Sex.UNSPECIFIED)
    val person = Body.Person(trendKg, p.heightCm, age, sex)
    return person.takeIf { Body.isPlausible(it) }
}
