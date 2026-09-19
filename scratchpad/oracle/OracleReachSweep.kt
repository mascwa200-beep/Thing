package dev.mascwa.pulse.core.telemetry

import org.junit.Test
import kotlin.random.Random

/**
 * Which of the Oracle's 26 rules can fire at all?
 *
 * ⚠️ Not a gate — a MEASUREMENT, run from the scratchpad. A rule whose gate cannot be satisfied is
 * a feature nobody ever sees and nothing reports: this repository has already found `tempoNudge`
 * computed and consumed nowhere, `windKmh` declared and never populated, and the vitals
 * heart-rate gate unable to fire because its one caller never passed the motion argument. Each was
 * found by hand, one at a time. This asks the whole rule set at once.
 *
 * Method: a seeded randomised sweep over plausible ranges for every field of [OracleSignals], with
 * `divine` called at a limit far above the rule count so ranking cannot hide a rule that did fire.
 *
 * ⚠️ Grouped by [Insight.family], which is the shipped grouping key, NOT by parsing the id — the
 * field's own KDoc says prefix-derivation would collide `wind_chill` with `wind_down`. Using the
 * real field means this measures the same partition the learning layer does.
 *
 * ⚠️ A family that never appears is NOT automatically a defect — it may need a signal this
 * platform never populates (the ledger pair is desktop-only by design). What it is, always, is a
 * question worth answering.
 */
class OracleReachSweep {

    private fun draw(r: Random): OracleSignals {
        fun maybe(p: Double) = r.nextDouble() < p
        fun <T> some(v: T, p: Double = 0.7): T? = if (maybe(p)) v else null

        val hour = r.nextInt(0, 24)
        // ⚠️ `now` is drawn FIRST and every event start is `now + offset`, because
        // [OracleEvent.startMs] is an ABSOLUTE epoch timestamp — not an offset. The first version of
        // this sweep drew it as a small relative number against a `nowMs` of ~1.6e12, so
        // `startMs > nowMs` was never true and `minutesUntil` was always hugely negative: TWO rules
        // (`leaveNow`, `meetingPrep`) reported as never firing when the code is perfectly fine, and
        // the event branches of `chargeNow` and `weatherPrep` were silently never exercised either.
        // A harness that mis-shapes one field accuses the thing it is checking.
        val now = r.nextLong(1_600_000_000_000, 1_800_000_000_000)
        val events = if (maybe(0.6)) {
            List(r.nextInt(1, 4)) { i ->
                val located = maybe(0.6)
                OracleEvent(
                    title = "Event $i",
                    // Across the next six hours AND a little into the past, because a rule that only
                    // fires on an event already under way would otherwise never be tried.
                    startMs = now + r.nextLong(-30 * 60_000, 6L * 3_600_000),
                    hasLocation = located,
                    // Deliberately reaching BELOW the 120 m "already there" floor and above the
                    // 4 km driving threshold, so both sides of each gate are visited.
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
            awayFromHome = if (maybe(0.7)) maybe(0.5) else null,
            events = events,
            pendingTasks = if (maybe(0.6)) List(r.nextInt(1, 4)) { "task $it" } else emptyList(),
            // ⚠️ Realistic text on BOTH sides, because `interestPulse` matches a mover's name
            // against an interest in either direction. The engine fills movers from `Quote.label`
            // (instrument names like "Bitcoin", "Gold") and interests from free-form
            // `ProfileCategory.INTEREST` text, so a real overlap looks like the interest phrase
            // CONTAINING the label. Inventing "SYM0"/"astronomy" on the two sides guarantees no
            // overlap ever, which reads as a dead rule and is only a dead fixture.
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
                List(r.nextInt(1, 4)) { i ->
                    OracleMover(labels[i], r.nextDouble(-12.0, 12.0), maybe(0.4))
                }
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

    @Test
    fun `which families can fire`() {
        val r = Random(20260919)
        val seen = LinkedHashMap<String, Int>()
        val urgencies = LinkedHashMap<String, MutableSet<String>>()
        val examples = LinkedHashMap<String, String>()
        var pushes = 0
        var quiet = 0
        val trials = 300_000
        repeat(trials) {
            val s = draw(r)
            val out = Oracle.divine(s, limit = 100)
            if (out.isEmpty()) quiet++
            if (Oracle.pushWorthy(out).isNotEmpty()) pushes++
            out.forEach { i ->
                seen[i.family] = (seen[i.family] ?: 0) + 1
                urgencies.getOrPut(i.family) { linkedSetOf() }.add(i.urgency.name)
                examples.putIfAbsent(i.family, i.title)
            }
        }

        println("trials: $trials")
        println("snapshots with NOTHING to say: $quiet")
        println("snapshots with something push-worthy (URGENT+): $pushes")
        println("\nRULES declared: 26   families that fired: ${seen.size}")
        seen.entries.sortedByDescending { it.value }.forEach { (f, n) ->
            println("  %-16s %8d  %7s%%  %-28s %s".format(
                f, n, "%.3f".format(100.0 * n / trials), urgencies[f].toString(),
                examples[f]?.take(46),
            ))
        }
    }
}
