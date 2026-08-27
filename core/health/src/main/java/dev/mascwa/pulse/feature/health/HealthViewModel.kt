package dev.mascwa.pulse.feature.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.telemetry.BmrEquations
import dev.mascwa.pulse.core.telemetry.Body
import dev.mascwa.pulse.core.telemetry.BodyTrend
import dev.mascwa.pulse.core.telemetry.CheckIn
import dev.mascwa.pulse.core.telemetry.DashboardLayout
import dev.mascwa.pulse.core.telemetry.EnergyBalance
import dev.mascwa.pulse.core.telemetry.Expenditure
import dev.mascwa.pulse.core.telemetry.GoalProjection
import dev.mascwa.pulse.core.telemetry.Habits
import dev.mascwa.pulse.core.telemetry.IntakeWeek
import dev.mascwa.pulse.core.telemetry.FoodPhrase
import dev.mascwa.pulse.core.telemetry.FoodPortion
import dev.mascwa.pulse.core.telemetry.MacroTargets
import dev.mascwa.pulse.core.telemetry.PeriodCompare
import dev.mascwa.pulse.core.telemetry.MealDraft
import dev.mascwa.pulse.core.telemetry.Maintenance
import dev.mascwa.pulse.core.telemetry.Micronutrients
import dev.mascwa.pulse.core.telemetry.NutrientSet
import dev.mascwa.pulse.core.telemetry.NutritionDay
import dev.mascwa.pulse.core.telemetry.Readability
import dev.mascwa.pulse.core.telemetry.RecipeImport
import dev.mascwa.pulse.core.telemetry.Recipes
import dev.mascwa.pulse.core.telemetry.Training
import dev.mascwa.pulse.core.telemetry.WeeklyPlan
import dev.mascwa.pulse.data.health.TrainingStore
import dev.mascwa.pulse.data.health.HealthDeps
import dev.mascwa.pulse.data.health.HealthSettings
import dev.mascwa.pulse.data.health.MealPhotos
import dev.mascwa.pulse.data.health.BodyStore
import dev.mascwa.pulse.data.health.HealthConnectBridge
import dev.mascwa.pulse.data.health.HealthDays
import dev.mascwa.pulse.data.health.ProgressPhotoStore
import dev.mascwa.pulse.data.food.Food
import dev.mascwa.pulse.data.food.FoodLookup
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        /**
         * The resting rate the equations produced, WHOLE — the number, which equation gave it, and
         * any dieting discount applied.
         *
         * ⚠️ **Only `kcal` used to survive the composition**, and everything [BmrEquations] kept so
         * a surface could explain the figure was discarded one line after it was made: the equation
         * label ("so a surface can say, not imply"), [BmrEquations.describe], and the adaptation
         * factor — which takes up to 8% OFF the resting rate and therefore off the calories somebody
         * eats to. A number lowered by an invisible factor is exactly what this app documents as
         * needing to be said out loud.
         */
        val resting: BmrEquations.Estimate? = null,
        val measured: Expenditure.Estimate? = null,
        val expenditure: Expenditure.Estimate.Known? = null,
        /** How much of [expenditure] is measured rather than guessed, 0..1. Worth showing while it climbs. */
        val measuredShare: Double = 0.0,
        /**
         * The targets to eat to — **the PUBLISHED set between check-ins, not the planner's live
         * answer**.
         *
         * ⚠️ This used to be recomputed on every state build, so it moved with every weigh-in and
         * every logged meal. See [CheckIn] for why that is the opposite of a plan. It is still a
         * [MacroTargets.Plan] and still a `Set` when there is one, deliberately: a held plan renders
         * exactly as a fresh one and no surface has to learn what a check-in is.
         */
        val plan: MacroTargets.Plan? = null,
        /**
         * What the planner says TODAY, whether or not it has been published.
         *
         * ⚠️ Kept beside [plan] rather than replacing it because the two answer different questions,
         * and the check-in surface is the one place that needs both: [plan] is what to eat, this is
         * what the next check-in will hand down. Everywhere else should read [plan].
         */
        val livePlan: MacroTargets.Plan? = null,
        /** Whether a new set is due right now, and why — or how long the current one stands. */
        val checkIn: CheckIn.Verdict? = null,
        /** What was last handed down, or null before the first check-in. */
        val published: CheckIn.Published? = null,
        /**
         * What the last check-in said, in the words it said them.
         *
         * ⚠️ Read from the stored report rather than recomputed, because the set it was compared
         * against is gone the moment the new one replaces it.
         */
        val checkInReport: List<String> = emptyList(),
        /**
         * The same plan spread across seven days.
         *
         * ⚠️ Null whenever [plan] is not a [MacroTargets.Plan.Set] — a refusal has no week, and a
         * flat week of a number that was refused would be seven wrong answers instead of one.
         */
        val week: WeeklyPlan.Week? = null,
        /**
         * Whether daily walking has moved enough that the measurement window spans two ways of
         * living. Never null — the "not enough days to say" case is a value, not an absence, and
         * collapsing the two would make a surface unable to tell them apart.
         */
        val stepShift: Expenditure.StepShift =
            Expenditure.StepShift(0, 0, 0, 0, false, "No step counts yet."),
        /**
         * An activity band the measured walking supports and the current setting does not, or null.
         *
         * ⚠️ A suggestion the person confirms, never applied on its own. See
         * [Expenditure.suggestedActivity] for why it only ever points upward.
         */
        val stepSuggestion: Expenditure.Activity? = null,
        /**
         * Whether how much you eat has moved enough that the measurement window spans two ways of
         * eating. Never null, for the reason [stepShift] gives: "not enough days to say" is a value
         * rather than an absence, and collapsing the two leaves a surface unable to tell them apart.
         */
        val intakeShift: Expenditure.IntakeShift =
            Expenditure.IntakeShift(0.0, 0.0, 0, 0, false, "Nothing logged yet."),
        /**
         * Whether measured expenditure has moved since a genuinely independent earlier reading —
         * which is how the app can say a deficit's suppression is lifting rather than promise it.
         */
        val recovery: Maintenance.Recovery =
            Maintenance.Recovery.TooSoon(0.0, 0.0, "No expenditure readings yet."),
        /** When the trend should be able to show whether the current rate is happening. */
        val confirmation: Maintenance.Confirmation =
            Maintenance.Confirmation.Never("Not enough weigh-ins yet."),
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

        /** Which mode is in charge. Falls back rather than throwing on a value written by a newer build. */
        val programMode: WeeklyPlan.Mode
            get() = runCatching { WeeklyPlan.Mode.valueOf(profile.programMode) }
                .getOrDefault(WeeklyPlan.Mode.COACHED)
        val remaining: NutritionDay.Remaining?
            get() = targets?.let { NutritionDay.remaining(eatenToday, it) }
        val latest: BodyTrend.Point? get() = (trend as? BodyTrend.Trend.Estimated)?.latest
        val unit: BodyTrend.MassUnit
            get() = runCatching { BodyTrend.MassUnit.valueOf(profile.massUnit) }
                .getOrDefault(BodyTrend.MassUnit.KG)

        /**
         * When the goal weight arrives, if the last few weeks carry on.
         *
         * ⚠️ **A getter over this state's own fields rather than a stored one computed in [build],
         * and that is the point rather than an economy.** It reads [trend] and [profile] off the same
         * instance, so it is structurally incapable of quoting a projection from one moment beside a
         * rate from another — which is the exact hazard this class's own opening note exists to
         * prevent. Storing it would mean a second place the unit is resolved and a second chance for
         * the two to drift. The arithmetic is a handful of divisions, unlike [week], which is stored
         * because building one is real work.
         */
        val goalProjection: GoalProjection.Projection
            get() {
                val est = trend as? BodyTrend.Trend.Estimated
                    ?: return GoalProjection.Projection.NotYet(
                        "No weigh-ins yet — the count-down starts with the trend.",
                    )
                return GoalProjection.project(est.latest, est.hasRate, profile.goalKg, unit)
            }
    }

    private val recompute = MutableStateFlow(0)

    val state: StateFlow<State> =
        combine(profile, weighins, _entries, c.bodyStore.stepHistory, recompute) { p, w, todayEntries, walked, _ ->
            build(p, w, todayEntries, walked)
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
        walked: List<Expenditure.StepDay> = emptyList(),
    ): State = composeHealthReading(p, w, todayEntries, c.foodLogStore, todayStartMs(), walked)



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
     *
     * ⚠️ **[refresh] only snaps forward when something calls it, and for a long time nothing did.**
     * The standalone application builds this view model with `by viewModels`, so `init` ran once per
     * process and the day it decided on was the day every meal was filed under until the process
     * died — which on a phone left on a counter is days. Both applications now call [refresh] on
     * every foreground, and [watchForMidnight] covers the screen somebody is actually looking at.
     * Anything that hosts this view model owes it that call.
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

    /**
     * Roll onto the new day the moment the calendar does.
     *
     * ⚠️ **This is the second of two mechanisms, and the weaker one.** Whether a suspended [delay]
     * fires promptly after a phone has spent the night asleep is a property of the monotonic clock
     * that no build machine can settle, so the load-bearing half is each application calling
     * [refresh] when it comes back to the foreground — which asks the calendar outright and cannot
     * be wrong about it. This timer is what makes the header honest for somebody who is *looking at*
     * the screen as midnight passes, which the foreground signal by definition cannot cover.
     *
     * ⚠️ It rolls only when the calendar day genuinely differs from the one on show, so an early or
     * spurious wake costs nothing at all, and somebody who deliberately stepped onto another day
     * ([pinnedDay]) is left where they put themselves.
     */
    private fun watchForMidnight() {
        viewModelScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                // ⚠️ Asked of the calendar rather than added as 86_400_000, because a local day is
                // 23 hours the morning the clocks go forward and 25 the morning they go back, and
                // a fixed stride would put the roll an hour out for the rest of that week.
                val next = HealthDays.plus(HealthDays.startOf(now, zone), 1, zone)
                delay((next - now).coerceAtLeast(1_000L))
                val today = todayStartMs()
                if (!pinnedDay && today != _today.value) {
                    _today.value = today
                    reloadEntries()
                    recompute.value++
                }
            }
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
        // ⚠️ Read HERE and nowhere else, because this is the one place every day change and every
        // add or removal passes through. `FoodLogStore.add` CLEARS the fast on the day it lands on,
        // so a flag refreshed only when the day changes would keep saying "fasted" over a day with
        // food in it — the screen and the record disagreeing about the same day.
        _fasted.value = c.foodLogStore.isFasted(_today.value)
    }

    /**
     * Whether the day on screen is marked as a deliberate fast.
     *
     * ⚠️ **This flag is the whole reason [Expenditure.IntakeDay.fasted] means anything.** The core
     * distinguishes a day worth zero calories from a day nobody recorded — a fast counts toward
     * completeness and pulls the intake mean down honestly, a gap does neither — and until something
     * could SET it, every day was a gap and the distinction did nothing at all.
     */
    private val _fasted = MutableStateFlow(false)
    val fastedDay: StateFlow<Boolean> = _fasted.asStateFlow()

    /**
     * Mark the day on screen as a fast, or take the mark off.
     *
     * ⚠️ The store REFUSES to mark a day that has entries, and that refusal is passed on as a
     * sentence rather than swallowed. Marking a fast on a day with food logged asserts two
     * contradictory things about it; the honest answer is to say so and let somebody delete the
     * entries if the day really was a fast.
     */
    fun setFasted(fasted: Boolean) {
        c.crumb("log", "fasted")
        val day = _today.value
        viewModelScope.launch {
            val took = c.foodLogStore.setFasted(day, fasted)
            if (!took) {
                _notice.value = "There is food logged on that day, so it cannot be a fast. " +
                    "Remove the entries first."
                return@launch
            }
            _fasted.value = c.foodLogStore.isFasted(day)
            // A fast is a real intake day, so the measurement moves the moment it is marked.
            recompute.value++
        }
    }

    fun recordWeighin(kg: Double) {
        c.crumb("body", "weighin")
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
        c.crumb("body", "measure")
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
        c.crumb("search", "everyproduct")
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
        c.crumb("search", "barcode")
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
     * The entry a portion of [food] would become, or null when the amount cannot be worked out.
     *
     * ⚠️ **The conversion to what was actually eaten happens here and nowhere else**, through
     * [FoodPortion.eaten]. Every source publishes per 100 grams; an entry stores what is on the
     * plate. Carrying a per-100-gram figure any further is how a 30-gram biscuit gets logged as a
     * packet.
     *
     * ⚠️ And it is ONE construction of [NutritionDay.Entry] for every path that logs a found food —
     * the portion box, the plate, and a described meal. Two constructions meant to agree is exactly
     * how one of them quietly gains a field the other does not, and the loss is invisible because
     * the entry still looks complete.
     *
     * ⚠️ Null when [FoodPortion.gramsFor] cannot say what the portion weighs — a serving of a record
     * that never declared one. The caller has to say so rather than invent a weight.
     */
    private fun entryFor(
        food: Food,
        amount: Double,
        unit: FoodPortion.Unit,
        meal: NutritionDay.Meal,
    ): NutritionDay.Entry? {
        val grams = FoodPortion.gramsFor(FoodPortion.Portion(amount, unit), food.sizes) ?: return null
        val eaten = FoodPortion.eaten(food.per100g, grams)
        return NutritionDay.Entry(
            id = UUID.randomUUID().toString(),
            dayStartMs = _today.value,
            atMs = System.currentTimeMillis(),
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
        )
    }

    /** Log a portion of a found food. The arithmetic is [entryFor]'s. */
    fun logPortion(
        food: Food,
        amount: Double,
        unit: FoodPortion.Unit,
        meal: NutritionDay.Meal,
        /**
         * Put it on the plate instead of straight into the record.
         *
         * ⚠️ **A parameter on this function rather than a second function**, so both routes build
         * the entry from the same lines. `PlateStore` promises that committing produces the entry
         * the direct path would have produced, and two constructions of `NutritionDay.Entry` that
         * are meant to agree is exactly how that promise quietly stops being true — one of them
         * gains a field and the other does not, and the loss is invisible because the entry still
         * looks complete.
         */
        toPlate: Boolean = false,
    ) {
        c.crumb("log", "portion")
        val entry = entryFor(food, amount, unit, meal) ?: return
        viewModelScope.launch {
            if (toPlate) c.plateStore.stage(entry) else c.foodLogStore.add(entry)
            _picked.value = null
            _search.value = Search()
            // ⚠️ Neither happens for a staged item. Nothing has been eaten yet as far as the record
            // is concerned, so reloading the day would show no change and recomputing the plan would
            // move a target on the strength of food nobody has decided on.
            if (!toPlate) {
                reloadEntries()
                recompute.value++
            }
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
        /**
         * The four figures every panel states beside the macros, and which this door used to drop.
         *
         * ⚠️ [NutritionDay.Nutrients] has carried fibre, sugars, saturates and sodium all along;
         * quickAdd simply never took them, so a food typed or read off a label recorded none of
         * them however plainly the packet said so. Saturates and sugars are mandatory on a UK or EU
         * panel and sodium on a United States one, so this was not an edge case — it was most of
         * what a label says. They are NOT in either nutrient picker either: those cover the other
         * twenty-nine, and these four live directly on Nutrients, which is exactly why nothing
         * noticed. Defaulted, so no existing call site changes.
         */
        fibreG: Double = 0.0,
        sugarG: Double = 0.0,
        satFatG: Double = 0.0,
        sodiumMg: Double = 0.0,
        micros: Micronutrients.Amounts = Micronutrients.Amounts(),
        extras: NutrientSet.Amounts = NutrientSet.Amounts(),
        /** Put it on the plate instead of straight into the record — see [logPortion]. */
        toPlate: Boolean = false,
    ) {
        c.crumb("log", "quickadd")
        val label = name.trim().ifBlank { "Quick add" }
        if (!kcal.isFinite() || kcal < 0.0) return
        val now = System.currentTimeMillis()
        val eaten = NutritionDay.Nutrients(
            kcal = kcal,
            proteinG = proteinG,
            fatG = fatG,
            carbG = carbG,
            fibreG = fibreG,
            sugarG = sugarG,
            satFatG = satFatG,
            sodiumMg = sodiumMg,
        )
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
            val entry = NutritionDay.Entry(
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
            )
            // ⚠️ The custom food above is saved either way, and deliberately. Keeping it is a
            // separate decision from eating it — somebody who typed a label out and then thought
            // better of the portion still wants the food remembered.
            if (toPlate) c.plateStore.stage(entry) else c.foodLogStore.add(entry)
            if (!toPlate) {
                reloadEntries()
                recompute.value++
            }
        }
    }

    // ------------------------------------------------------------------ a meal described in words

    /**
     * One thing somebody named, and the record it was matched to.
     *
     * ⚠️ [food] null means **unmatched, and it is reported rather than dropped**. A described meal
     * that quietly logged four of its five things would be worse than one that logged nothing: the
     * day would look complete and be short by a meal's worth of calories, with nothing on screen to
     * say which one went missing.
     */
    data class Described(
        val item: FoodPhrase.Item,
        val food: Food? = null,
        /** What the stated portion weighs, or null when the record cannot say. */
        val grams: Double? = null,
    ) {
        /** Enough to log: a record, and a weight the record could actually give. */
        val ready: Boolean get() = food != null && grams != null
    }

    /**
     * What a description came to, before anything is logged.
     *
     * ⚠️ One flow rather than several, shaped like [Search] and [RecipeImportState] above, for the
     * same reason: a busy flag that can disagree with the list it belongs to is how a spinner ends
     * up spinning over rows that already arrived.
     */
    data class DescribeState(
        val busy: Boolean = false,
        val items: List<Described> = emptyList(),
        /** Why a row is short, when one is. Empty otherwise. */
        val note: String = "",
    ) {
        /** How many could be logged as they stand — the number the button offers. */
        val ready: Int get() = items.count { it.ready }
    }

    private val _describe = MutableStateFlow(DescribeState())
    val describe: StateFlow<DescribeState> = _describe.asStateFlow()

    private var describeJob: Job? = null

    /**
     * Read a described meal and match each thing in it to a record.
     *
     * ⚠️ **THE INVARIANT, which is [FoodPhrase]'s and the photograph path's: the words name foods,
     * and every NUMBER comes from a real record.** Nothing here knows what an egg contains. The name
     * goes to the food search, the amount goes to [FoodPortion], and a name that matches nothing is
     * reported unmatched.
     *
     * ⚠️ **Searched LOCALLY — the network is deliberately not consulted, one item at a time.** A
     * described meal names generic foods ("two eggs, a slice of toast"), which is exactly what the
     * bundled seed holds; a packaged good is named by scanning it or searching for it, where the
     * result can be seen before it is committed. The alternative is up to [FoodPhrase.MAX_ITEMS]
     * sequential requests to a community server behind one spinner, or a concurrent burst at it, and
     * neither is worth it for the half of the corpus this path is least likely to need. An unmatched
     * row keeps its name and hands it to the ordinary search box, so nothing is lost — it is routed
     * to the path that can find it.
     */
    fun describeMeal(text: String) {
        describeJob?.cancel()
        val parsed = FoodPhrase.parse(text)
        if (parsed.isEmpty()) {
            _describe.value = DescribeState()
            return
        }
        _describe.value = DescribeState(busy = true)
        describeJob = viewModelScope.launch {
            val rows = parsed.map { item ->
                val portion = item.portion ?: return@map Described(item)
                val food = c.foodRepository.search(item.name, online = false).foods.firstOrNull()
                Described(
                    item = item,
                    food = food,
                    grams = food?.let { FoodPortion.gramsFor(portion, it.sizes) },
                )
            }
            _describe.value = DescribeState(
                items = rows,
                note = when {
                    rows.none { it.ready } ->
                        "Nothing here matched a record. Search for them one at a time below — a " +
                            "packaged food is found by name or by its barcode, not by describing it."
                    rows.any { it.food != null && it.grams == null } ->
                        "A row saying it cannot work the amount out has a record that never " +
                            "declared what one of them weighs. Say that one in grams instead."
                    else -> ""
                },
            )
        }
    }

    /** Put one described row aside. Nothing else about the description changes. */
    fun dropDescribed(index: Int) {
        val s = _describe.value
        if (index !in s.items.indices) return
        _describe.value = s.copy(items = s.items.filterIndexed { i, _ -> i != index })
    }

    /** Hand an unmatched name to the ordinary search box, which can reach the network. */
    fun searchDescribed(index: Int) {
        val row = _describe.value.items.getOrNull(index) ?: return
        searchFor(PickFor.LOG)
        onSearchQuery(row.item.name)
    }

    fun clearDescribed() {
        describeJob?.cancel()
        _describe.value = DescribeState()
    }

    /**
     * Log everything that matched, in one go.
     *
     * ⚠️ **The rows that did NOT match are left standing**, and the notice says how many. Clearing
     * the whole description would take the only record of what was missed off the screen at the
     * moment it becomes actionable.
     *
     * ⚠️ One store write and one recompute for the lot, rather than [logPortion] per row: a
     * described meal is several things, and reloading the day and moving the plan six times over is
     * six chances for a target to visibly jump while somebody is still reading the list.
     */
    fun logDescribed(meal: NutritionDay.Meal, toPlate: Boolean = false) {
        val rows = _describe.value.items
        val entries = rows.mapNotNull { r ->
            val food = r.food ?: return@mapNotNull null
            val p = r.item.portion ?: return@mapNotNull null
            entryFor(food, p.amount, p.unit, meal)
        }
        if (entries.isEmpty()) return
        val left = rows.filterNot { it.ready }
        viewModelScope.launch {
            entries.forEach { if (toPlate) c.plateStore.stage(it) else c.foodLogStore.add(it) }
            _describe.value = DescribeState(items = left)
            if (!toPlate) {
                reloadEntries()
                recompute.value++
            }
            val where = if (toPlate) "the plate" else meal.label.lowercase()
            _notice.value = when {
                left.isEmpty() -> "${entries.size} logged to $where."
                else -> "${entries.size} logged to $where · ${left.size} still to find."
            }
        }
    }

    // ----------------------------------------------------------------------------------- the plate

    /**
     * Whether taps are building a plate rather than logging straight away.
     *
     * ⚠️ **A standing plate forces it on, whatever the toggle says**, and that is not a nicety. The
     * plate is persisted precisely so it survives a process death, and the toggle is not — so
     * without this, reopening the app after being killed would show a plate with three things on it
     * while the next thing tapped went silently past it into the log.
     */
    private val _buildingPlate = MutableStateFlow(false)
    val buildingPlate: StateFlow<Boolean> =
        combine(_buildingPlate, c.plateStore.items) { building, items ->
            building || items.isNotEmpty()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setBuildingPlate(v: Boolean) {
        _buildingPlate.value = v
    }

    /** What is on the plate, in the order it was put there. */
    val plate: StateFlow<List<NutritionDay.Entry>> = c.plateStore.items

    /**
     * What is on the plate and what committing it would do to the day.
     *
     * ⚠️ Read against the SHOWN day's entries and the live plan, so it answers the question actually
     * being asked — "what does this do to the day I am looking at" — rather than to today whichever
     * day is on screen.
     */
    val plateEffect: StateFlow<MealDraft.Effect> =
        combine(c.plateStore.items, _entries, state) { staged, logged, s ->
            MealDraft.effect(staged, logged, (s.plan as? MacroTargets.Plan.Set)?.targets)
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            MealDraft.effect(emptyList(), emptyList(), null),
        )

    fun unstage(id: String) {
        viewModelScope.launch { c.plateStore.unstage(id) }
    }

    /**
     * Write everything on the plate into the record.
     *
     * ⚠️ **Drained under the store's own lock rather than read-then-cleared**, because a commit
     * button is exactly the control people double-tap and the read-then-clear version double-logs
     * the lot. See `PlateStore.drain`.
     *
     * ⚠️ Each item goes to the day it was STAGED for, which is what the person meant, and the notice
     * says so when that is not the day on screen — otherwise a plate assembled yesterday and
     * committed today would land somewhere nobody is looking with nothing to say it had.
     */
    fun commitPlate() {
        c.crumb("log", "plate")
        viewModelScope.launch {
            val taken = c.plateStore.drain()
            if (taken.isEmpty()) return@launch
            for (e in taken) c.foodLogStore.add(e)
            _buildingPlate.value = false
            reloadEntries()
            recompute.value++
            val elsewhere = taken.count { it.dayStartMs != _today.value }
            val what = if (taken.size == 1) "1 item" else "${taken.size} items"
            _notice.value = when (elsewhere) {
                0 -> "Logged $what."
                taken.size -> "Logged $what to the day they were added on."
                else -> "Logged $what — $elsewhere on an earlier day."
            }
        }
    }

    /** Throw the plate away. */
    fun clearPlate() {
        viewModelScope.launch {
            c.plateStore.clear()
            _buildingPlate.value = false
            _notice.value = "Plate cleared."
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
    /**
     * Read a photograph of a plate into proposals.
     *
     * ⚠️ [addTo] carries the proposals already on screen, so a meal spread over several dishes can be
     * photographed one dish at a time and reviewed as one list. **They are APPENDED, never merged or
     * de-duplicated**, and that is deliberate rather than lazy: there is no honest way to tell "two
     * chicken breasts on the table" from "the same chicken breast photographed twice", and a merge
     * that guessed would either invent food or lose it. Because of that the surface offers this as a
     * separate, deliberately-named control — appending has to be chosen, not implied by taking
     * another photograph.
     */
    fun readMealPhoto(
        context: android.content.Context,
        uri: android.net.Uri,
        addTo: List<MealPhotos.Proposal> = emptyList(),
    ) {
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
                is MealPhotos.Result.Plate ->
                    MealShot.Plate(addTo + r.proposals, r.summary)
                // ⚠️ A failed second photograph must not throw away a good first one. The dish
                // already reviewed goes back on screen with the reason beside it, rather than the
                // whole plate being lost to a picture of a tablecloth.
                is MealPhotos.Result.NotFood ->
                    if (addTo.isEmpty()) MealShot.NotFood
                    else MealShot.Plate(addTo, "That last one did not look like food, so nothing was added.")
                is MealPhotos.Result.NoVision -> MealShot.NoVision
                is MealPhotos.Result.Failed ->
                    if (addTo.isEmpty()) MealShot.Failed(r.reason)
                    else MealShot.Plate(addTo, "That last one could not be read — ${r.reason}")
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
        c.crumb("healthconnect", "import")
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
        c.crumb("log", "remove")
        viewModelScope.launch {
            c.foodLogStore.remove(id, _today.value)
            reloadEntries()
            recompute.value++
        }
    }

    // -------------------------------------------------------------------------- the energy balance

    private val _balanceSpan = MutableStateFlow(EnergyBalance.Span.MONTH)

    /** Which interval the balance chart is showing. */
    val balanceSpan: StateFlow<EnergyBalance.Span> = _balanceSpan.asStateFlow()

    private val _balance = MutableStateFlow<EnergyBalance.Reading?>(null)

    /** The interval, or null before anything has been asked for. */
    val balance: StateFlow<EnergyBalance.Reading?> = _balance.asStateFlow()

    private val _balanceLoading = MutableStateFlow(false)
    val balanceLoading: StateFlow<Boolean> = _balanceLoading.asStateFlow()

    /**
     * Work out the balance over the chosen interval.
     *
     * ⚠️ **Asked for, never derived on every state build**, and the reason is the DISK rather than the
     * arithmetic. A first version of this comment claimed the computation was expensive; measured, it
     * is 2.4–3.4 ms on a JVM against eighteen months of daily weighing, and a third of a millisecond
     * against three months of it — nothing. What is worth avoiding is [FoodLogStore.intakeDays], a
     * read across a hundred and twenty days of the log, repeated on every logged meal and every
     * weigh-in, for a chart nobody may be looking at.
     *
     * ⚠️ The intake list reaches back a window BEFORE the interval on purpose. A causal reading at the
     * start of the interval needs the window that precedes it, and fetching only the interval's own
     * days leaves the first four weeks of the line empty for no reason — a documented consequence
     * with a test of its own.
     */
    fun loadBalance() {
        if (_balanceLoading.value) return
        _balanceLoading.value = true
        viewModelScope.launch {
            try {
                val span = _balanceSpan.value
                val today = todayStartMs()
                val window = state.value.profile.expenditureWindowDays.coerceIn(14, 120)
                val first = HealthDays.plus(today, -(span.days - 1).toLong())
                val grid = (0 until span.days).map { HealthDays.plus(first, it.toLong()) }
                // Reach back a window further for the food, so the readings at the start of the
                // interval have something behind them.
                val from = HealthDays.plus(first, -window.toLong())
                val intake = withContext(Dispatchers.IO) {
                    c.foodLogStore.intakeDays(from, HealthDays.plus(today, 1))
                }
                val w = weighins.value
                _balance.value = withContext(Dispatchers.Default) {
                    EnergyBalance.build(grid, w, intake, windowDays = window)
                }
            } catch (t: Throwable) {
                // A chart that could not be worked out is not a reason to take the tab down. The
                // surface shows its own empty state; leaving the previous interval up would be worse,
                // because it would be labelled with the span nobody is now looking at.
                _balance.value = null
            } finally {
                _balanceLoading.value = false
            }
        }
    }

    fun setBalanceSpan(span: EnergyBalance.Span) {
        if (_balanceSpan.value == span) return
        _balanceSpan.value = span
        _balance.value = null
        loadBalance()
    }

    // -------------------------------------------------------------------------- looking back

    /** How far back the body comparison reaches. */
    enum class Look(val days: Int, val label: String) {
        MONTH(30, "1 MONTH"),
        QUARTER(90, "3 MONTHS"),
        HALF(182, "6 MONTHS"),
        YEAR(365, "1 YEAR"),
    }

    private val _look = MutableStateFlow(Look.QUARTER)
    val look: StateFlow<Look> = _look.asStateFlow()

    fun setLook(v: Look) {
        _look.value = v
    }

    /**
     * What weight and every recorded tape measurement have done over [look].
     *
     * ⚠️ **Weight is compared on the SMOOTHED TREND, never on two raw weigh-ins**, and the difference
     * is not academic: a single reading carries about [BodyTrend.SCALE_NOISE_KG] of noise, so picking
     * the one nearest each end can report a gain in the middle of a real loss. A tape measurement has
     * no smoother behind it, so there the raw readings are all there is and the comparison says so by
     * being what it is.
     *
     * ⚠️ Derived rather than fetched: every input is already a flow this view model holds, so there is
     * no disk read here and nothing to ask for. That is why this one is a `stateIn` where the energy
     * balance is a `loadBalance()`.
     */
    val lookBack: StateFlow<List<PeriodCompare.Change>> =
        combine(weighins, c.bodyStore.measurements, _look, profile) { w, m, look, p ->
            val today = todayStartMs()
            val from = HealthDays.plus(today, -look.days.toLong())
            val unit = runCatching { BodyTrend.MassUnit.valueOf(p.massUnit) }
                .getOrDefault(BodyTrend.MassUnit.KG)

            val trend = BodyTrend.estimate(w) as? BodyTrend.Trend.Estimated
            val weight = trend?.let {
                PeriodCompare.compare(
                    label = "Weight",
                    unit = unit.label,
                    // Readings are stored in kilograms, so the conversion happens here rather than in
                    // the sentence — otherwise the two numbers and the difference between them would
                    // be in different units.
                    points = it.points.map { pt -> PeriodCompare.Point(pt.atMs, pt.trendKg * unit.perKg) },
                    fromMs = from,
                    toMs = today,
                )
            }

            val tape = BodyStore.MeasureKind.entries.mapNotNull { kind ->
                val pts = m.filter { it.kind == kind }.map { PeriodCompare.Point(it.atMs, it.cm) }
                // ⚠️ A kind nobody has ever recorded is left out entirely rather than listed with a
                // refusal beside it. Six "nothing recorded yet" rows would bury the one or two
                // somebody actually keeps.
                if (pts.isEmpty()) null else PeriodCompare.compare(kind.label, "cm", pts, from, today)
            }

            listOfNotNull(weight) + tape
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ------------------------------------------------------------------------ reading it back

    private val _grain = MutableStateFlow(PeriodCompare.Grain.WEEK)
    val grain: StateFlow<PeriodCompare.Grain> = _grain.asStateFlow()

    fun setGrain(v: PeriodCompare.Grain) {
        _grain.value = v
    }

    /**
     * The last stretch of the food log, added up a day, a week or a month at a time.
     *
     * ⚠️ **A different question from the energy balance chart, which is why both exist.** That one
     * asks what the balance was and needs weigh-ins to answer; this one asks whether you are eating
     * more than you were, and works for somebody who has never owned a scale.
     *
     * ⚠️ The bucket key comes from [HealthDays], never from arithmetic. A week is 7 × 24 h only until
     * a clock change — see `HealthDays.weekStart` for the transition that proves it.
     */
    val rollUp: StateFlow<List<PeriodCompare.Bucket>> =
        combine(c.foodLogStore.days, _grain, _today) { byDay, grain, today ->
            val span = when (grain) {
                PeriodCompare.Grain.DAY -> 14
                PeriodCompare.Grain.WEEK -> 84
                PeriodCompare.Grain.MONTH -> 365
            }
            val grid = HealthDays.grid(today, span)
            val values = byDay
                .filterValues { it.kcal > 0.0 }
                .mapValues { (_, v) -> v.kcal }
            PeriodCompare.bucket(grid, values) { d ->
                when (grain) {
                    PeriodCompare.Grain.DAY -> d
                    PeriodCompare.Grain.WEEK -> HealthDays.weekStart(d)
                    PeriodCompare.Grain.MONTH -> HealthDays.monthStart(d)
                }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
        c.crumb("record", "export")
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
        c.crumb("record", "import")
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

    // -------------------------------------------------------------------------------- training

    /** Every retained session, most recent first. */
    val sessions: StateFlow<List<Training.Session>> = c.trainingStore.sessions

    /** The catalogue plus anything added, additions first. */
    val exercises: StateFlow<List<Training.Exercise>> = c.trainingStore.exercises

    /** Personal bests, heaviest first. These outlive the sessions they were set in. */
    val bests: StateFlow<List<TrainingStore.Best>> = c.trainingStore.bests

    /**
     * The session being logged, or null when nothing is open.
     *
     * ⚠️ Held here rather than in the composable, for the reason the recipe draft above states: a
     * session is an hour of work across a dozen movements, and `remember` dies the moment somebody
     * navigates away to log a drink.
     */
    private val _session = MutableStateFlow<Training.Session?>(null)
    val session: StateFlow<Training.Session?> = _session.asStateFlow()

    /** Open a session for the given instant, or reopen one already saved at it. */
    fun startSession(atMs: Long = System.currentTimeMillis()) {
        _session.value = sessions.value.firstOrNull { it.atMs == atMs }
            ?: Training.Session(atMs = atMs, movements = emptyList())
    }

    /**
     * Reopen a session already saved.
     *
     * ⚠️ Named apart from the private lambda-taking `editSession` deliberately. Kotlin permits two
     * overloads of one name where the second takes a function, and then cannot infer which receiver
     * a trailing lambda belongs to — a shape this repository has already paid a CI round for.
     */
    fun openSession(session: Training.Session) {
        _session.value = session
    }

    fun closeSession() {
        _session.value = null
    }

    private fun editSession(block: (Training.Session) -> Training.Session) {
        _session.value = _session.value?.let(block)
    }

    fun addMovement(exercise: Training.Exercise) = editSession { s ->
        if (s.movements.any { it.exercise.id == exercise.id }) s
        else s.copy(movements = s.movements + Training.Movement(exercise, emptyList()))
    }

    fun removeMovement(index: Int) = editSession { s ->
        if (index !in s.movements.indices) s
        else s.copy(movements = s.movements.filterIndexed { i, _ -> i != index })
    }

    /**
     * Add a set to a movement.
     *
     * ⚠️ Defaults are taken from the LAST set on that movement rather than left blank, because a
     * working set is nearly always the same weight and reps as the one before it — and retyping
     * three numbers per set is how somebody stops logging halfway through a session.
     */
    fun addSet(movementIndex: Int, reps: Int? = null, loadKg: Double? = null, rpe: Double? = null) =
        editSession { s ->
            val m = s.movements.getOrNull(movementIndex) ?: return@editSession s
            val previous = m.sets.lastOrNull()
            val set = Training.SetEntry(
                reps = reps ?: previous?.reps ?: 5,
                loadKg = loadKg ?: previous?.loadKg,
                rpe = rpe,
            )
            s.copy(
                movements = s.movements.mapIndexed { i, mv ->
                    if (i == movementIndex) mv.copy(sets = mv.sets + set) else mv
                },
            )
        }

    fun updateSet(movementIndex: Int, setIndex: Int, set: Training.SetEntry) = editSession { s ->
        val m = s.movements.getOrNull(movementIndex) ?: return@editSession s
        if (setIndex !in m.sets.indices) return@editSession s
        s.copy(
            movements = s.movements.mapIndexed { i, mv ->
                if (i != movementIndex) mv
                else mv.copy(sets = mv.sets.mapIndexed { j, old -> if (j == setIndex) set else old })
            },
        )
    }

    fun removeSet(movementIndex: Int, setIndex: Int) = editSession { s ->
        val m = s.movements.getOrNull(movementIndex) ?: return@editSession s
        if (setIndex !in m.sets.indices) return@editSession s
        s.copy(
            movements = s.movements.mapIndexed { i, mv ->
                if (i != movementIndex) mv
                else mv.copy(sets = mv.sets.filterIndexed { j, _ -> j != setIndex })
            },
        )
    }

    fun setSessionNote(note: String) = editSession { it.copy(note = note) }

    /**
     * Save the open session.
     *
     * ⚠️ Called after every change rather than only at the end, and the store upserts on the
     * session's instant so that leaves one record rather than twenty. A phone killed mid-workout
     * should cost the last set, not the whole hour.
     */
    fun saveSession() {
        val s = _session.value ?: return
        viewModelScope.launch { c.trainingStore.save(s) }
    }

    fun deleteSession(atMs: Long) {
        viewModelScope.launch {
            c.trainingStore.remove(atMs)
            if (_session.value?.atMs == atMs) closeSession()
        }
    }

    /**
     * Add a movement the catalogue does not have, and put it straight into the open session.
     *
     * ⚠️ **Both, in one call, and that is not a convenience.** Somebody types a name because they
     * are about to log sets of it; a version that only filed it in the catalogue would look like it
     * had done nothing, because the store write is asynchronous and the session would still be
     * empty when they looked. The session gets the object directly rather than waiting for the
     * flow to come back around.
     */
    fun addExercise(name: String, pattern: Training.Pattern, loaded: Boolean = true) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val exercise = Training.Exercise(
            // ⚠️ Prefixed, so an added movement can never collide with a catalogue id and silently
            // shadow it — the same rule custom foods follow with `own:`.
            id = Training.OWN_PREFIX + UUID.randomUUID().toString(),
            name = trimmed,
            pattern = pattern,
            loaded = loaded,
        )
        if (_session.value != null) {
            addMovement(exercise)
        }
        viewModelScope.launch {
            c.trainingStore.addExercise(exercise)
            if (_session.value != null) saveSession()
        }
    }

    fun removeExercise(id: String) {
        viewModelScope.launch { c.trainingStore.removeExercise(id) }
    }

    /**
     * What to load next time on a movement, from the last session that actually recorded an effort.
     *
     * ⚠️ Searches back through sessions rather than reading only the most recent one. Somebody who
     * squats on Monday and benches on Tuesday would otherwise get "nothing to judge" on the squat
     * every Tuesday, which is the answer being useless exactly where it should be useful.
     */
    fun nextLoad(exerciseId: String, targetRpe: Double = 8.0): Training.Advice {
        for (s in sessions.value) {
            val m = s.movements.firstOrNull { it.exercise.id == exerciseId } ?: continue
            // ⚠️ An Unknown from an UNLOADED movement is final — searching further back would keep
            // asking a question a press-up has no answer to. An Unknown from a session that simply
            // recorded no effort is worth looking past, which is why the two are not treated alike.
            if (!m.exercise.loaded) return Training.nextLoad(m, targetRpe)
            val advice = Training.nextLoad(m, targetRpe)
            if (advice is Training.Advice.Load) return advice
        }
        return Training.Advice.Unknown(
            "Nothing logged on this movement yet, so there is no last set to judge the next one against.",
        )
    }

    /**
     * Hard sets by pattern over the last seven days, for somebody asking whether a week was balanced.
     */
    fun weekVolume(nowMs: Long = System.currentTimeMillis()): Map<Training.Pattern, Int> {
        val since = nowMs - 7L * 86_400_000L
        return Training.setsByPattern(sessions.value.filter { it.atMs >= since })
    }

    /**
     * Which days of this week the training log says were heavy.
     *
     * ⚠️ **A suggestion, never applied on its own** — the same rule the step-count activity
     * suggestion follows. `heavyDays` is a setting somebody may have chosen deliberately, and a
     * training log quietly overwriting it would move calories between days without being asked.
     * [applyTrainingDays] is the confirmation.
     */
    fun heavyDaysFromTraining(nowMs: Long = System.currentTimeMillis()): Set<Int> {
        val since = nowMs - 7L * 86_400_000L
        val recent = sessions.value.filter { it.atMs >= since }
        // ⚠️ A CALENDAR weekday, Monday = 0 — the convention `WeeklyPlan.Day.index` carries and the
        // MON…SUN toggles are indexed by. An earlier version of this used days-back-from-today,
        // which is a different number every day and would have moved the heavy days one place every
        // twenty-four hours without ever looking wrong.
        return Training.heavyDays(recent) { HealthDays.weekdayIndex(it) }
    }

    /** Take the training log's answer as the week's heavy days. */
    fun applyTrainingDays(nowMs: Long = System.currentTimeMillis()) {
        val days = heavyDaysFromTraining(nowMs).sorted()
        edit { it.copy(heavyDays = days) }
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
        // An import belongs to the draft it opened. Leaving the lines behind would offer them against
        // whatever draft is opened next, which is somebody else's recipe.
        _recipeImport.value = RecipeImportState()
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
        // If this add answered an imported line, that line is dealt with. Read AFTER the add rather
        // than before it, because `gramsFor` above can refuse and return early — retiring the line
        // first would lose it on exactly the portion the app could not convert.
        _recipeImport.value.matching?.let { dropImported(it) }
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
        c.crumb("recipe", "save")
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
        c.crumb("log", "recipe")
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
        c.crumb("log", "meal")
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

    // ------------------------------------------------------------------ a recipe off a web page

    /**
     * What an import has produced so far.
     *
     * ⚠️ **One flow rather than four, shaped like [Search] above.** The screens read it whole, and a
     * busy flag that can disagree with the line list it belongs to is how a spinner ends up spinning
     * over results that already arrived.
     */
    data class RecipeImportState(
        val busy: Boolean = false,
        /** Why nothing came back, when nothing did. Empty otherwise. */
        val note: String = "",
        /** [RecipeImport.sentence] — what the page yielded, said before anything is saved. */
        val summary: String = "",
        /** The lines still waiting to be matched to a food record. */
        val lines: List<RecipeImport.Ingredient> = emptyList(),
        /** Which of [lines] the search box is currently working on, if any. */
        val matching: Int? = null,
    ) {
        val current: RecipeImport.Ingredient? get() = matching?.let { lines.getOrNull(it) }
    }

    private val _recipeImport = MutableStateFlow(RecipeImportState())
    val recipeImport: StateFlow<RecipeImportState> = _recipeImport.asStateFlow()

    /**
     * Read a recipe page and open a draft from it.
     *
     * ⚠️ **The page's own ingredient list is what matters, NOT whether the decimator called it an
     * article.** A recipe page is exactly the shape [Readability.Outcome.THIN] describes — a bulleted
     * list and a numbered method with barely a paragraph between them — so gating on `isArticle`
     * would reject the very pages this exists for. The outcome is read only to EXPLAIN a failure.
     *
     * ⚠️ And it always starts a NEW draft. An import carries a name and a portion count, so folding
     * one into an open builder would overwrite both — which is why the control that calls this is
     * offered on the recipe list and not inside the builder.
     */
    fun importRecipe(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        // Somebody pasting an address off a phone often loses the scheme; assuming https rather than
        // refusing costs nothing, and http would be refused by the cleartext guard anyway.
        val address = if (trimmed.startsWith("http", ignoreCase = true)) trimmed else "https://$trimmed"
        _recipeImport.value = RecipeImportState(busy = true)
        viewModelScope.launch {
            val page = c.readerRepository.read(address)
            val found = RecipeImport.fromBlocks(
                blocks = page.blocks,
                title = page.meta.title?.takeIf { it.isNotBlank() } ?: "Imported recipe",
                sourceUrl = address,
            )
            if (found == null) {
                _recipeImport.value = RecipeImportState(
                    // The reader's own sentence when it has one — it knows about paywalls, dead links
                    // and things that are not pages at all, and none of that is guessable from here.
                    note = page.note
                        ?: "That page opened, but there is no ingredient list on it this can read.",
                )
                return@launch
            }
            newRecipe()
            draftName(found.title)
            found.servings?.let { draftServings(it) }
            _recipeImport.value = RecipeImportState(
                summary = RecipeImport.sentence(found),
                lines = found.ingredients,
            )
        }
    }

    /**
     * Start matching one imported line against the food database.
     *
     * ⚠️ Searches the parsed NAME, never the raw line. "200 g plain flour, sifted" finds nothing;
     * "plain flour" finds flour. That split is the whole reason [RecipeImport.Ingredient] keeps the
     * quantity, the measure and the note apart from the name rather than handing back one string.
     */
    fun matchImported(index: Int) {
        val line = _recipeImport.value.lines.getOrNull(index) ?: return
        _recipeImport.value = _recipeImport.value.copy(matching = index)
        searchFor(PickFor.RECIPE)
        onSearchQuery(line.name)
    }

    /** Stop matching the current line without dropping it — the list is unchanged. */
    fun cancelImportedMatch() {
        _recipeImport.value = _recipeImport.value.copy(matching = null)
        _picked.value = null
        _search.value = Search()
    }

    /**
     * Put an imported line aside.
     *
     * ⚠️ Called both when somebody skips a line deliberately and when [draftAdd] has just answered
     * one. Nothing about the draft changes here: an ingredient the app has no record for is still in
     * the pot, and the honest thing is to leave the recipe short and say so rather than invent it.
     */
    fun dropImported(index: Int) {
        val s = _recipeImport.value
        if (index !in s.lines.indices) return
        _recipeImport.value = s.copy(
            lines = s.lines.filterIndexed { i, _ -> i != index },
            matching = null,
        )
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

    /** Who is in charge of the calories. See [WeeklyPlan.Mode]. */
    fun setProgramMode(v: WeeklyPlan.Mode) = edit { it.copy(programMode = v.name) }

    /**
     * Mark a day of the week heavy, or stop marking it.
     *
     * ⚠️ Stored sorted and de-duplicated. The set is what the core reads, so order does not change
     * the plan — but it changes the JSON, and a settings blob that rewrites itself on every toggle
     * makes every diff of it unreadable and every flush look like a real change.
     */
    fun toggleHeavyDay(index: Int) = edit { current ->
        if (index !in 0 until WeeklyPlan.DAYS) return@edit current
        val next = current.heavyDays.toMutableSet()
        if (!next.add(index)) next.remove(index)
        current.copy(heavyDays = next.sorted())
    }
    fun setActivity(v: Expenditure.Activity) = edit { it.copy(activity = v.name) }
    fun setProteinGPerKg(v: Double) = edit { it.copy(proteinGPerKg = v.coerceIn(0.0, 5.0)) }
    fun setMassUnit(v: BodyTrend.MassUnit) = edit { it.copy(massUnit = v.name) }
    fun setConfigured(v: Boolean) = edit { it.copy(configured = v) }

    // ---------------------------------------------------------------- arranging the page

    /**
     * Move a card one place, or several.
     *
     * ⚠️ [available] comes from the surface because the two applications draw different cards, and a
     * shared view model that knew either list would have to be edited every time one of them gained
     * a panel.
     *
     * ⚠️ The move happens on the EDITABLE order — everything the page can draw, including what is
     * put away — rather than on what is currently visible. Moving across a hidden card on the
     * visible list would work and would quietly lose that card's own position, so bringing it back
     * later would find it at the bottom for no reason anybody could see.
     */
    fun moveCard(available: List<String>, id: String, delta: Int) = edit { s ->
        val order = DashboardLayout.editable(available, s.dashboardOrder)
        val moved = DashboardLayout.move(order, id, delta)
        s.copy(dashboardOrder = DashboardLayout.remember(moved, s.dashboardOrder))
    }

    fun hideCard(id: String) = edit {
        it.copy(dashboardHidden = DashboardLayout.hide(it.dashboardHidden.toSet(), id).toList())
    }

    fun showCard(id: String) = edit {
        it.copy(dashboardHidden = DashboardLayout.show(it.dashboardHidden.toSet(), id).toList())
    }

    /** Back to the order the page ships with, and everything showing. */
    fun resetDashboard() = edit { it.copy(dashboardOrder = emptyList(), dashboardHidden = emptyList()) }

    // ------------------------------------------------------------------------------- the check-in

    /**
     * True while a publish is in flight, so two emissions cannot both write one.
     *
     * ⚠️ Plain and not atomic on purpose: every read and write of it happens on the main dispatcher,
     * inside the state collector or the coroutine it starts. Making it atomic would imply a
     * concurrency that is not there and hide that this depends on the dispatcher.
     */
    private var publishing = false

    /**
     * Set when a publish threw, and it suppresses only the AUTOMATIC path.
     *
     * ⚠️ Without it a failing write spins: the verdict still says due on the next emission, so the
     * collector tries again, forever, on every state change. Suppressing the manual button too would
     * be worse in the other direction — a transient failure would leave somebody permanently unable
     * to get new targets, with a button that does nothing and says nothing.
     */
    private var autoPublishFailed = false

    /**
     * Hand down the targets the planner currently says, and record what changed.
     *
     * ⚠️ **Only ever called when [CheckIn.verdict] says so**, and never from a screen's own logic.
     * A surface that could publish would let somebody refresh their way to a new number whenever
     * they did not like the one they had, which is exactly the behaviour the cadence removes.
     * [recalculateNow] is the deliberate exception and it says so.
     *
     * ⚠️ It reads [State.livePlan], not [State.plan]. Between check-ins those differ — that IS the
     * feature — and publishing the held plan would republish last week's numbers forever.
     */
    private fun publish(s: State, why: CheckIn.Reason) {
        val fresh = s.livePlan as? MacroTargets.Plan.Set ?: return
        if (publishing) return
        publishing = true
        viewModelScope.launch {
            try {
                val nowMs = System.currentTimeMillis()
                val trendKg = (s.trend as? BodyTrend.Trend.Estimated)?.latest?.trendKg ?: 0.0
                val unit = runCatching { BodyTrend.MassUnit.valueOf(s.profile.massUnit) }
                    .getOrDefault(BodyTrend.MassUnit.KG)
                val before = s.published
                // The report is composed HERE, once, because the set it compares against is gone the
                // moment this one replaces it.
                val report = buildList {
                    add(why.sentence)
                    if (before != null) {
                        addAll(CheckIn.changes(before.targets, fresh.targets))
                        CheckIn.whyCaloriesMoved(before, fresh.expenditureKcal)?.let { add(it) }
                        CheckIn.weightMoved(before, trendKg, unit)?.let { add(it) }
                    }
                }
                c.updateHealth { p ->
                    dev.mascwa.pulse.data.health.PublishedPlan.store(
                        p = p,
                        atMs = nowMs,
                        plan = fresh,
                        stated = dev.mascwa.pulse.data.health.PublishedPlan.statedOf(p),
                        weightKg = trendKg,
                        report = report,
                    )
                }
                recompute.value++
                autoPublishFailed = false
            } catch (t: Throwable) {
                // ⚠️ Recorded rather than rethrown, and the flag is what stops the retry loop. A
                // throw here would take the view model's scope with it, which turns a settings
                // write that failed into a tab that stops updating at all.
                autoPublishFailed = true
            } finally {
                publishing = false
            }
        }
    }

    /**
     * Take the planner's current answer now, without waiting for the week.
     *
     * ⚠️ The one deliberate way past the cadence, and it is a button rather than something the app
     * does on its own. Somebody who has just come back from a fortnight away, or who has changed
     * something the fingerprint cannot see, should not have to wait — but the app choosing this for
     * them would be the drifting target again with an extra step.
     */
    fun recalculateNow() {
        val s = state.value
        if (s.livePlan !is MacroTargets.Plan.Set) return
        autoPublishFailed = false
        publish(s, CheckIn.Reason.DUE)
    }

    init {
        refresh()
        watchForMidnight()
        // ⚠️ The publish happens HERE and not inside `composeHealthReading`, because that function is
        // a read path shared with the `health` assistant tool — a write there would hand down a new
        // set of targets every time the Computer was asked a question.
        viewModelScope.launch {
            state.collect { s ->
                val v = s.checkIn
                if (v is CheckIn.Verdict.Publish && !autoPublishFailed) publish(s, v.why)
            }
        }
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
    /**
     * Finished days of walking, oldest first. Defaulted empty so the `health` tool and any other
     * caller that has no pedometer reading gets exactly today's behaviour.
     */
    walked: List<Expenditure.StepDay> = emptyList(),
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

    // ⚠️ The allometric equations, NOT [Body.bmr], and the two are deliberately not the same call.
    // This number is an *estimate* — it is what the blend below leans on hardest in the first weeks,
    // before enough weigh-ins and logged days exist to measure anything — so accuracy is what matters.
    // [Body.bmr] stays where it is, under the calorie floor in MacroTargets, because a floor wants the
    // conservative side rather than the accurate one: for an ordinary adult these equations land some
    // tens of calories BELOW Mifflin–St Jeor, and lowering a safety floor as a side effect of improving
    // an estimate is not a trade anybody asked for.
    //
    // ⚠️ Peak weight is the peak of the RECORDED weigh-ins, which is not the same as a lifetime peak —
    // somebody who started tracking after already losing twenty kilograms has no record of theirs, and
    // will not get the reduction. That is the safe direction: no discount means a higher resting rate,
    // a higher floor, and a more conservative plan.
    val peakKg = w.maxOfOrNull { it.kg }
    val resting = person?.let {
        BmrEquations.estimate(
            p = it,
            bodyFatPct = p.bodyFatPct,
            athlete = p.athlete,
            adaptation = BmrEquations.Adaptation(
                inDeficit = p.ratePerWeekKg < 0.0,
                belowPeak = peakKg != null && BmrEquations.isBelowPeak(it.kg, peakKg),
            ),
        )
    }
    val bmr = resting?.kcal
    val activity = runCatching { Expenditure.Activity.valueOf(p.activity) }
        .getOrDefault(Expenditure.Activity.LIGHT)
    val formula = bmr?.takeIf { it.isFinite() }?.let { Expenditure.fromFormula(it, activity) }

    // ⚠️ **Steps reach the answer by widening the measured interval, and nowhere else.** A shift in
    // daily walking means the older half of the window describes a different way of living from the
    // recent half — so the measurement is less certain than its own arithmetic thinks. Widening says
    // that honestly, and the inverse-variance blend below then hands weight to the formula on its
    // own. Nothing is discarded and no threshold is crossed. See `Expenditure.widenForShift` for why
    // shortening the window instead would have been worse.
    val shift = Expenditure.stepShift(walked, now, windowDays = window)
    // ⚠️ The same treatment for the OTHER way a window stops describing one steady life. A change in
    // how much you eat does not make the arithmetic wrong — an average over a window is an average
    // whether or not intake was constant — but it does break the assumption a reader makes, that the
    // answer describes them now. The trend responds to an intake change with a lag, and a body eating
    // differently for a fortnight is probably spending differently too.
    val intakeMove = Expenditure.intakeShift(intake, now, windowDays = window)
    val suggestion = walked
        .takeIf { it.size >= Expenditure.MIN_STEP_DAYS_EACH_SIDE }
        ?.let { days -> days.sumOf { it.steps.toLong() } / days.size }
        ?.let { Expenditure.suggestedActivity(Expenditure.stepBand(it.toInt()), activity) }

    // ⚠️ The blend, not a switch. Inverse-variance weighting hands the answer over as the
    // measurement tightens, so there is no day on which the number jumps and no threshold to pick.
    val known = (measured as? Expenditure.Estimate.Known)
        ?.let { Expenditure.widenForShifts(it, shift, intakeMove) }
    val expenditure = when {
        formula != null && known != null -> Expenditure.blend(formula, known)
        known != null -> known
        else -> formula
    }
    val share = if (formula != null && known != null) Expenditure.measuredShare(formula, known) else 0.0

    // ⚠️ **The earlier reading is RECOMPUTED, not banked, and that is better in two ways.** The
    // measurement is a pure function of the weigh-ins and the food log, both of which are stored in
    // full — so asking what it would have said a window ago is exact, where a recorded reading would
    // depend on whether the app happened to be opened that day.
    //
    // ⚠️ It does use today's smoothing for both endpoints, so it is not "what we told you a month
    // ago". It is not meant to be: the question is what the data says expenditure was over two
    // disjoint windows, and the best available smoothing is the right tool for both halves of it.
    val earlierAt = now - window.toLong() * 86_400_000L
    val earlierKnown = Expenditure.measure(trend, intake, earlierAt, window) as? Expenditure.Estimate.Known
    val recovery = Maintenance.recovery(
        listOfNotNull(
            earlierKnown?.let { Maintenance.Reading(earlierAt, it.kcal, window.toDouble()) },
            known?.let { Maintenance.Reading(now, it.kcal, window.toDouble()) },
        ),
        now,
    )

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

    // ------------------------------------------------------------------------------- the check-in
    //
    // ⚠️ **The plan the screens read is the PUBLISHED one, not the one just computed.** `plan` above
    // is a pure function of the live expenditure, which moves with every weigh-in and every meal, so
    // reading it directly is what made the target drift underneath people. See `CheckIn`.
    //
    // ⚠️ This is in `composeHealthReading` rather than in the view model because the `health`
    // assistant tool composes through here too. Holding the targets in one place and not the other
    // would have the Computer and the screen quoting different numbers for the same day, which is
    // the exact defect this function's own KDoc exists to prevent.
    //
    // ⚠️ Nothing is WRITTEN here. This is a read path, and a read path that persists would publish a
    // check-in every time the assistant was asked a question. The verdict is carried out in `State`
    // and the view model acts on it.
    val stated = dev.mascwa.pulse.data.health.PublishedPlan.statedOf(p)
    val published = dev.mascwa.pulse.data.health.PublishedPlan.from(p)
    val verdict = CheckIn.verdict(published, stated, now)
    val effectivePlan = if (verdict is CheckIn.Verdict.Hold && published != null) {
        dev.mascwa.pulse.data.health.PublishedPlan.asPlan(published)
    } else {
        plan
    }

    // ⚠️ Built from the plan rather than beside it, so the week can never describe a different
    // daily figure from the one the rest of the screen shows. The floor handed down is the HIGHER of
    // the absolute one and this person's resting rate: MacroTargets already refuses to set a daily
    // target below the resting rate, and a light day that dipped under it would quietly undo a
    // decision taken one layer up.
    val week = (effectivePlan as? MacroTargets.Plan.Set)?.let { set ->
        WeeklyPlan.build(
            base = set.targets,
            mode = runCatching { WeeklyPlan.Mode.valueOf(p.programMode) }
                .getOrDefault(WeeklyPlan.Mode.COACHED),
            heavy = p.heavyDays.toSet(),
            floorKcal = maxOf(MacroTargets.ABSOLUTE_FLOOR_KCAL, bmr ?: 0.0),
        )
    }

    return HealthViewModel.State(
        profile = p,
        person = person,
        trend = trend,
        formula = formula,
        resting = resting,
        // ⚠️ The WIDENED measurement, not the raw one, so the interval on screen is the same
        // interval the blend weighted by. Showing a tighter figure than the arithmetic used would
        // be two statements of one number that disagree — the defect class this app keeps finding.
        measured = known ?: measured,
        expenditure = expenditure,
        measuredShare = share,
        plan = effectivePlan,
        livePlan = plan,
        checkIn = verdict,
        published = published,
        checkInReport = p.publishedReport,
        week = week,
        stepShift = shift,
        intakeShift = intakeMove,
        stepSuggestion = suggestion,
        recovery = recovery,
        confirmation = Maintenance.confirmIn(
            p.ratePerWeekKg,
            (trend as? BodyTrend.Trend.Estimated)?.latest?.trendSdKg ?: Double.NaN,
        ),
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
