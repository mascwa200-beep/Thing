package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Every rule the Oracle declares can actually fire.
 *
 * ## Why this exists
 *
 * ⚠️ **A rule whose gate cannot be satisfied is a feature nobody ever sees, and nothing reports
 * it.** It compiles, it is listed in [Oracle.RULES], it is covered by a unit test that hands it a
 * hand-built snapshot — and in the shipped app it returns null every time. This repository has
 * found that shape repeatedly and always by hand, one instance at a time: `SceneStrategy.tempoNudge`
 * computed and consumed nowhere; `OracleSignals.windKmh` declared and never populated, so any rule
 * reading wind could never have fired; `VitalsAnalyzer`'s motion argument defaulted at its one
 * caller, so the heart-rate gate was false on every device while four green tests covered a guard
 * that did not exist in the shipped app.
 *
 * This asks the whole rule set the question at once, so the next one is a build failure.
 *
 * ## Method
 *
 * A **seeded** randomised sweep over plausible ranges for every field of [OracleSignals], invoking
 * each entry of [Oracle.RULES] directly. Seeded, so the result is deterministic: this is a gate,
 * not a fuzz run.
 *
 * ⚠️ **Directly, rather than through [Oracle.divine], and that is not a shortcut.** `divine` ranks,
 * truncates and de-duplicates, so a rule that fired could be absent from its output for three
 * different reasons and only one of them is the defect being hunted. Calling the rules themselves
 * separates "cannot fire" from "fired and was dropped" — and the dropping is worth its own
 * assertion, which is the third test below.
 *
 * ## ⚠️ If this fails on a rule you just added
 *
 * The fix is almost always to **widen [draw] so it produces the shape your rule needs** — not to
 * delete the assertion, and not to add your family to an exception list, because there is
 * deliberately no such list. A rule that genuinely needs a signal no sweep can synthesise is a
 * rule worth a comment here explaining why.
 *
 * ⚠️ **The first version of this sweep reported three rules as never firing and all three were
 * fine.** [OracleEvent.startMs] is an ABSOLUTE epoch timestamp and the draw built it as a small
 * relative offset against a `nowMs` of ~1.6e12, so `startMs > nowMs` was never true and
 * `minutesUntil` was always hugely negative. `leaveNow` and `meetingPrep` could not fire, and the
 * event branches of `chargeNow` and `weatherPrep` were silently never exercised either. A harness
 * that mis-shapes one field accuses the thing it is checking — so [draw] states the shape of every
 * field it is easy to get wrong.
 */
class OracleReachabilityTest {

    /**
     * One plausible snapshot.
     *
     * Ranges deliberately straddle each rule's thresholds on both sides, so a gate is exercised in
     * both directions rather than only the one that fires.
     */
    private fun draw(r: Random): OracleSignals {
        fun maybe(p: Double) = r.nextDouble() < p
        fun <T> some(v: T, p: Double = 0.7): T? = if (maybe(p)) v else null

        val hour = r.nextInt(0, 24)
        // ⚠️ `now` is drawn FIRST and every event start is `now + offset`, because
        // [OracleEvent.startMs] is an absolute epoch timestamp rather than an offset. See the class
        // KDoc: getting this wrong silently disabled four rules' worth of coverage.
        val now = r.nextLong(1_600_000_000_000, 1_800_000_000_000)
        val events = if (maybe(0.6)) {
            List(r.nextInt(1, 4)) { i ->
                val located = maybe(0.6)
                OracleEvent(
                    title = "Event $i",
                    // Into the next six hours and a little into the past, because a rule that only
                    // fires on an event already under way would otherwise never be tried.
                    startMs = now + r.nextLong(-30 * 60_000, 6L * 3_600_000),
                    hasLocation = located,
                    // Reaching BELOW the 120 m "you are already there" floor and above the 4 km
                    // driving threshold, so both sides of each gate are visited.
                    distanceM = if (located) some(r.nextDouble(20.0, 60_000.0), 0.85) else null,
                )
            }
        } else emptyList()

        return OracleSignals(
            nowMs = now,
            hourOfDay = hour,
            minuteOfDay = hour * 60 + r.nextInt(0, 60),
            dayOfWeek = r.nextInt(1, 8),
            lat = some(51.5, 0.85),
            lon = some(-0.12, 0.85),
            placeName = some("Home", 0.6),
            movement = if (maybe(0.4)) r.nextDouble(0.0, 0.4).toFloat() else 0f,
            speedMps = some(r.nextDouble(0.0, 35.0), 0.5),
            // ⚠️ Three-valued on purpose. `windDown` requires `awayFromHome == false` and
            // `weatherPrep` requires `== true`, so a Boolean here would make one of them
            // unreachable — null is "nobody could tell", which is a state the phone really has.
            awayFromHome = if (maybe(0.7)) maybe(0.5) else null,
            events = events,
            pendingTasks = if (maybe(0.6)) List(r.nextInt(1, 4)) { "task $it" } else emptyList(),
            // ⚠️ Realistic text on BOTH sides of the interest/mover match. The engine fills movers
            // from `Quote.label` ("Bitcoin", "Gold") and interests from free-form
            // `ProfileCategory.INTEREST` text, and `interestPulse` matches them in either
            // direction — so a real overlap looks like the interest phrase CONTAINING the label.
            // Inventing unrelated strings on the two sides guarantees no overlap ever, which reads
            // as a dead rule and is only a dead fixture.
            interests = if (maybe(0.6)) {
                listOf(
                    "astronomy and stargazing", "I follow Bitcoin closely", "cooking",
                    "cycling", "the price of Gold", "markets",
                ).shuffled(r).take(r.nextInt(1, 4))
            } else emptyList(),
            tempC = some(r.nextDouble(-25.0, 45.0), 0.85),
            precipChancePct = some(r.nextInt(0, 101), 0.8),
            uvIndex = some(r.nextDouble(0.0, 12.0), 0.8),
            windKmh = some(r.nextDouble(0.0, 90.0), 0.8),
            humidityPct = some(r.nextDouble(5.0, 100.0), 0.8),
            dewPointC = some(r.nextDouble(-25.0, 28.0), 0.8),
            gustKmh = some(r.nextDouble(0.0, 140.0), 0.8),
            overnightLowC = some(r.nextDouble(-25.0, 25.0), 0.8),
            movers = if (maybe(0.6)) {
                val labels = listOf("Bitcoin", "Gold", "S&P 500", "Oil", "Nasdaq").shuffled(r)
                List(r.nextInt(1, 4)) { i -> OracleMover(labels[i], r.nextDouble(-12.0, 12.0), maybe(0.4)) }
            } else emptyList(),
            emergencyHeadline = some("Something major happened", 0.15),
            kpIndex = some(r.nextDouble(0.0, 9.0), 0.7),
            batteryPct = some(r.nextInt(1, 101), 0.9),
            charging = maybe(0.3),
            storageFreePct = some(r.nextInt(0, 101), 0.8),
            onCellular = if (maybe(0.7)) maybe(0.5) else null,
            habitualRoute = some("markets", 0.5),
            habitualLabel = some("Markets", 0.5),
            envDescription = some("indoors, quiet, dim", 0.6),
            envAnomaly = some("unusually loud for 03:00 on a weekday", 0.2),
            pressureFallingFast = maybe(0.15),
            ledgerHeadline = some("Pressure is the lowest in two years", 0.2),
            ledgerBits = if (maybe(0.3)) r.nextDouble(0.0, 14.0) else 0.0,
            reviewsDue = if (maybe(0.5)) r.nextInt(1, 40) else 0,
            kcalLeftToday = some(r.nextInt(-800, 1600), 0.6),
            proteinLeftG = some(r.nextInt(-40, 160), 0.6),
            loggedAnythingToday = maybe(0.5),
            daysSinceWeighIn = some(r.nextInt(0, 30), 0.6),
            studyStreakDays = if (maybe(0.5)) r.nextInt(1, 60) else 0,
            studiedToday = maybe(0.5),
            shakyGuideTitle = some("Knots and Cordage", 0.4),
            shakyGuideDetail = some("two of the last five wrong", 0.4),
        )
    }

    /**
     * How many times each rule fired, **indexed by its position in [Oracle.RULES]**.
     *
     * ⚠️ Per RULE, not per family, and that distinction is load-bearing. An earlier version
     * asserted `RULES.size == families.size`, which is fragile in a way that would have wasted
     * somebody's afternoon: five rules already set `family` explicitly, so the moment two of them
     * shared one — a perfectly reasonable thing to do, since the field exists to pool a rule's
     * instances for [OracleMemory] — the count would drop and the gate would report a dead rule
     * that is firing happily. Calling each rule directly removes the question, and names the
     * offender by index instead of leaving a set difference to be eyeballed.
     */
    private fun firings(trials: Int, seed: Int = 20260919): IntArray {
        val r = Random(seed)
        val hits = IntArray(Oracle.RULES.size)
        repeat(trials) {
            val s = draw(r)
            Oracle.RULES.forEachIndexed { i, rule -> if (rule(s) != null) hits[i]++ }
        }
        return hits
    }

    @Test
    fun `every rule the Oracle declares can fire`() {
        val hits = firings(TRIALS)
        val dead = hits.indices.filter { hits[it] == 0 }
        assertTrue(
            "rule(s) at these positions in Oracle.RULES never fired in $TRIALS synthetic " +
                "snapshots — either the gate cannot be satisfied, or `draw` does not produce the " +
                "shape the rule needs (see the class KDoc: getting one field's shape wrong " +
                "silently disabled four rules' worth of coverage once already): $dead",
            dead.isEmpty(),
        )
    }

    /**
     * ⚠️ Every rule must fire often enough that the sweep is measuring the rule rather than luck.
     *
     * A rule seen once in a hundred thousand draws would satisfy the test above while telling
     * nobody whether the next seed still reaches it. This is the floor that makes the gate stable:
     * the rarest real rule (`windDown`, which needs the late hours AND a known-at-home) sits near
     * 1.5%, so a thousandth of the trials is a wide margin under a real rule and still catches one
     * whose gate has become nearly unsatisfiable.
     */
    @Test
    fun `no rule fires so rarely that the sweep is measuring luck`() {
        val hits = firings(TRIALS)
        val floor = TRIALS / 1000
        val rare = hits.indices.filter { hits[it] in 1 until floor }.associateWith { hits[it] }
        assertTrue(
            "these rules (by position in Oracle.RULES) fired fewer than $floor times in $TRIALS " +
                "draws, so the gate above is resting on chance rather than on the rule: $rare",
            rare.isEmpty(),
        )
    }

    /**
     * ⚠️ No two rules may produce the same id for one snapshot, because `divine` de-duplicates on
     * it and the loser vanishes **silently**.
     *
     * `divine` is `RULES.mapNotNull { … }.distinctBy { it.id }`, so a rule whose id collides with
     * another's on the same snapshot is dropped with nothing said — it fires, it is correct, and
     * the user never sees it. That is the same shape as a rule that cannot fire at all, arrived at
     * from the other direction, and neither of the two tests above can see it.
     */
    @Test
    fun `no two rules produce the same insight id for one snapshot`() {
        val r = Random(4242)
        val collisions = LinkedHashMap<String, MutableSet<Int>>()
        repeat(TRIALS / 4) {
            val s = draw(r)
            val byId = LinkedHashMap<String, Int>()
            Oracle.RULES.forEachIndexed { i, rule ->
                val id = rule(s)?.id ?: return@forEachIndexed
                val first = byId.putIfAbsent(id, i)
                if (first != null && first != i) collisions.getOrPut(id) { linkedSetOf(first) }.add(i)
            }
        }
        assertTrue(
            "two different rules produced the same Insight id for one snapshot, so `divine`'s " +
                "distinctBy drops one of them with nothing said: $collisions",
            collisions.isEmpty(),
        )
    }

    /**
     * ⚠️ The sweep must be able to FAIL, and an all-quiet draw would satisfy nothing while looking
     * calm. A snapshot with everything present should have plenty to say.
     */
    @Test
    fun `the sweep produces snapshots the Oracle has something to say about`() {
        val r = Random(7)
        var withSomething = 0
        repeat(2_000) { if (Oracle.divine(draw(r), limit = 100).isNotEmpty()) withSomething++ }
        assertTrue(
            "the draw produced almost nothing the Oracle reacts to ($withSomething/2000), so a " +
                "passing reachability result would be meaningless",
            withSomething > 1_800,
        )
    }

    private companion object {
        /**
         * Enough that the rarest rule clears the floor above with room to spare, and small enough
         * that the whole class runs in a couple of seconds. Measured: 300,000 draws took under two
         * seconds and reached all 26 families.
         */
        const val TRIALS = 120_000
    }
}
