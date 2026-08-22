package dev.mascwa.pulse.feature.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.telemetry.Body
import dev.mascwa.pulse.core.telemetry.BodyTrend
import dev.mascwa.pulse.core.telemetry.Expenditure
import dev.mascwa.pulse.core.telemetry.FoodPortion
import dev.mascwa.pulse.core.telemetry.MacroTargets
import dev.mascwa.pulse.core.telemetry.NutritionDay
import dev.mascwa.pulse.data.health.BodyStore
import dev.mascwa.pulse.data.food.Food
import dev.mascwa.pulse.data.settings.HealthSettings
import dev.mascwa.pulse.di.AppContainer
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
class HealthViewModel(private val c: AppContainer) : ViewModel() {

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
    fun todayStartMs(): Long = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()

    fun dayStartOf(epochMs: Long): Long =
        java.time.Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toInstant().toEpochMilli()

    /**
     * The day [days] before or after the one starting at [dayStartMs].
     *
     * ⚠️ Calendar arithmetic, not `± 86_400_000`. A local day is 23 hours the morning the clocks go
     * forward and 25 the morning they go back, so adding a fixed day either overshoots into the day
     * after next or lands back inside the same one — and the visible symptom is a log that skips a day,
     * or a "next day" button that will not move. `LocalDate.plusDays` has no such failure.
     */
    fun dayPlus(dayStartMs: Long, days: Long): Long =
        java.time.Instant.ofEpochMilli(dayStartMs).atZone(zone).toLocalDate()
            .plusDays(days).atStartOfDay(zone).toInstant().toEpochMilli()

    // -------------------------------------------------------------------------------- the record

    val profile: StateFlow<HealthSettings> = c.settingsRepository.settings
        .map { it.health }
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

    private suspend fun build(
        p: HealthSettings,
        w: List<BodyTrend.Weighin>,
        todayEntries: List<NutritionDay.Entry>,
    ): State {
        val trend = BodyTrend.estimate(w)
        val latestKg = (trend as? BodyTrend.Trend.Estimated)?.latest?.trendKg
        val person = person(p, latestKg)
        val now = System.currentTimeMillis()

        val window = p.expenditureWindowDays.coerceIn(14, 120)
        val from = todayStartMs() - window * DAY_MS
        val intake = c.foodLogStore.intakeDays(from, now)
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

        return State(
            profile = p,
            person = person,
            trend = trend,
            formula = formula,
            measured = measured,
            expenditure = expenditure,
            measuredShare = share,
            plan = plan,
            eatenToday = NutritionDay.total(todayEntries),
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
     */
    private fun person(p: HealthSettings, trendKg: Double?): Body.Person? {
        if (p.heightCm <= 0.0 || p.birthYear <= 0 || trendKg == null) return null
        val age = LocalDate.now(zone).year - p.birthYear
        val sex = runCatching { Body.Sex.valueOf(p.sex) }.getOrDefault(Body.Sex.UNSPECIFIED)
        val person = Body.Person(trendKg, p.heightCm, age, sex)
        return person.takeIf { Body.isPlausible(it) }
    }

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
    }

    fun recordWeighin(kg: Double) {
        if (!kg.isFinite() || kg <= 0.0) return
        val now = System.currentTimeMillis()
        c.bodyStore.record(now, kg, dayStartOf(now))
        _notice.value = "Weigh-in recorded."
    }

    fun removeWeighin(atMs: Long) = c.bodyStore.removeWeighin(atMs)

    fun recordMeasurement(kind: BodyStore.MeasureKind, cm: Double) {
        c.bodyStore.recordMeasurement(System.currentTimeMillis(), kind, cm)
        _notice.value = "${kind.label} recorded."
    }

    /**
     * Log something by its numbers alone.
     *
     * The whole food database arrives in a later slice; this is the path that never needs one and never
     * goes away — a label in your hand, or a meal somebody cooked you, is faster to type than to search.
     */
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
    )

    private val _search = MutableStateFlow(Search())
    val search: StateFlow<Search> = _search.asStateFlow()

    /** The food being portioned, if the reader has picked one. */
    private val _picked = MutableStateFlow<Food?>(null)
    val picked: StateFlow<Food?> = _picked.asStateFlow()

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
            val online = c.connectivityObserver.isOnline.value
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
                ),
            )
            _picked.value = null
            _search.value = Search()
            reloadEntries()
            recompute.value++
        }
    }

    fun quickAdd(
        name: String,
        kcal: Double,
        proteinG: Double,
        fatG: Double,
        carbG: Double,
        meal: NutritionDay.Meal,
        grams: Double = 0.0,
    ) {
        val label = name.trim().ifBlank { "Quick add" }
        if (!kcal.isFinite() || kcal < 0.0) return
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            c.foodLogStore.add(
                NutritionDay.Entry(
                    id = UUID.randomUUID().toString(),
                    dayStartMs = _today.value,
                    atMs = now,
                    name = label,
                    grams = grams,
                    nutrients = NutritionDay.Nutrients(
                        kcal = kcal, proteinG = proteinG, fatG = fatG, carbG = carbG,
                    ),
                    meal = meal,
                    source = NutritionDay.Source.CUSTOM,
                ),
            )
            reloadEntries()
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
            c.settingsRepository.update { it.copy(health = block(it.health)) }
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
        const val DAY_MS = 86_400_000L
    }

    private companion object {
        const val MIN_QUERY = 2
        /** Long enough that an ordinary typist fires one search per word, short enough to feel live. */
        const val DEBOUNCE_MS = 280L
    }
}
