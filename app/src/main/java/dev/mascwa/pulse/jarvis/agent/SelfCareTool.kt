package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.core.telemetry.Habit
import dev.mascwa.pulse.core.telemetry.HabitCheckin
import dev.mascwa.pulse.core.telemetry.Special
import dev.mascwa.pulse.data.game.HabitStore
import dev.mascwa.pulse.data.game.SpecialGameStore
import dev.mascwa.pulse.data.perception.ActivityEvidenceStore

/**
 * J.A.R.V.I.S.'s hands on the S.P.E.C.I.A.L. life-sim and the real-life self-care system. It can READ the
 * operator's character + needs + what the sensors have actually seen + which check-in is due, and ACT — tend
 * a need or answer a check-in. This is how the assistant gets access to the game and its features so it can
 * make smart calls about when to nudge or enforce (informed by the owner's typed directive + the evidence).
 *
 * Every branch is defensive (never throws — returns a short observation the orchestrator can read back).
 */
class SelfCareTool(
    private val game: SpecialGameStore,
    private val habits: HabitStore,
    private val evidence: ActivityEvidenceStore,
    /** Fire an actual check-in for a habit (aggressive full-screen or soft reminder, per the owner's switches).
     *  Wired by AppContainer; this is how J.A.R.V.I.S. proactively decides to ASK rather than a fixed clock. */
    private val fireCheckin: suspend (Habit) -> Unit,
) : JarvisTool {
    override val name = "selfcare"
    override val usage =
        "selfcare [status] | selfcare needs | selfcare drink|eat|rest|wash|brush | selfcare due | " +
            "selfcare evidence | selfcare confirm <shower|teeth|meal|water> | selfcare ask [shower|teeth|meal|" +
            "water] — read + tend the operator's real-life needs, the S.P.E.C.I.A.L. character, sensed " +
            "self-care, and habit check-ins; `ask` fires a check-in NOW when you judge the moment right"

    override suspend fun run(arg: String): String = runCatching {
        val a = arg.trim()
        val verb = a.substringBefore(' ').lowercase()
        val rest = a.substringAfter(' ', "").trim()
        when (verb) {
            "", "status", "state" -> status()
            "needs" -> needsLine()
            "drink" -> { game.drink(); "Hydration topped up." }
            "eat" -> { game.eat(); "Nourishment topped up." }
            "rest" -> { game.rest(); "Energy restored." }
            "wash" -> { game.wash(); "Hygiene restored (a wash)." }
            "brush" -> { game.brushTeeth(); "Teeth brushed (a partial hygiene lift)." }
            "due" -> habits.nextDue()?.let { "Check-in due: ${it.label} — \"${it.question}\"" } ?: "No check-in is due."
            "evidence" -> evidenceLine()
            "confirm", "answer", "done" -> confirm(rest.ifBlank { verb })
            "ask", "checkin", "nudge", "prompt" -> ask(rest)
            else -> status()
        }
    }.getOrElse { "selfcare failed: ${it.message}" }

    /** Proactively fire a check-in for the named habit (or the most-overdue one). */
    private suspend fun ask(rest: String): String {
        val key = rest.lowercase()
        val habit = HabitCheckin.DEFAULTS.firstOrNull { h ->
            key.isNotBlank() && (
                h.label.lowercase().contains(key) ||
                    h.activity.name.lowercase().contains(key) ||
                    h.activity.label.lowercase().contains(key)
                )
        } ?: habits.nextDue()
            ?: return "Nothing is due to ask about, so I held off."
        fireCheckin(habit)
        habits.markAsked(habit)
        return "Asked the operator: \"${habit.question}\""
    }

    private suspend fun status(): String {
        val c = game.characterFlow.value
        val stats = Special.entries.joinToString(" ") { "${it.letter}${c.stat(it)}" }
        return buildString {
            append("OPERATOR · LVL ${c.level} · ${c.caps} caps · HP ${c.hp}/${c.maxHp}\n")
            append("S.P.E.C.I.A.L. $stats\n")
            append(needsLine()).append('\n')
            append(habits.nextDue()?.let { "Check-in due: ${it.label}." } ?: "No check-in due.")
        }
    }

    private suspend fun needsLine(): String {
        val l = game.lifeSnapshot()
        return "NEEDS · hydration ${l.hydration}% · nourishment ${l.nourishment}% · " +
            "energy ${l.energy}% · hygiene ${l.hygiene}%"
    }

    private suspend fun evidenceLine(): String {
        val recent = evidence.recent(System.currentTimeMillis() - 24 * 3_600_000L)
        if (recent.isEmpty()) return "No self-care sensed in the last 24h."
        return "Sensed (24h): " + recent.takeLast(8).joinToString("; ") { e ->
            "${e.activity.label} (${(e.confidence * 100).toInt()}%)"
        }
    }

    private suspend fun confirm(rest: String): String {
        val key = rest.lowercase()
        val habit = HabitCheckin.DEFAULTS.firstOrNull { h ->
            key.isNotBlank() && (
                h.label.lowercase().contains(key) ||
                    h.activity.name.lowercase().contains(key) ||
                    h.activity.label.lowercase().contains(key)
                )
        } ?: habits.nextDue()
            ?: return "Which check-in should I confirm, sir? (shower / teeth / meal / water)"
        val outcome = habits.answer(habit, claimedDone = true)
        return "Confirmed ${habit.label} → $outcome."
    }
}
