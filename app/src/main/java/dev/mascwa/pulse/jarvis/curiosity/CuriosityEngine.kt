package dev.mascwa.pulse.jarvis.curiosity

import dev.mascwa.pulse.data.jarvis.JarvisMemory
import dev.mascwa.pulse.data.jarvis.db.NoteSource
import dev.mascwa.pulse.data.settings.SettingsRepository
import dev.mascwa.pulse.jarvis.inference.ChatTurn
import dev.mascwa.pulse.jarvis.inference.EngineState
import dev.mascwa.pulse.jarvis.inference.LocalInferenceEngine
import kotlinx.coroutines.flow.toList

/**
 * The "Curious Learning" brain. Periodically (strictly rate-limited by the user's curiosity level) it
 * compares what recurs in the conversation against what's already in the durable memory store and asks
 * **one** genuine gap-filling question — or surfaces a gentle follow-up about an older learned fact.
 * It learns ONLY from the user's answers; it never writes to memory itself (the ViewModel does that,
 * after reflecting the fact back for confirmation). All scheduling state lives in the memory KV store,
 * so there are no new tables and the model is never modified.
 */
class CuriosityEngine(
    private val memory: JarvisMemory,
    private val engine: LocalInferenceEngine,
    private val settings: SettingsRepository,
) {

    enum class Confirm { AFFIRM, REJECT, OTHER, CORRECTION }

    /**
     * Decide whether to ask something at the end of a turn. Returns the question to ask, or null.
     * [recentTurns] is the recent context window (oldest → newest).
     */
    suspend fun nextPrompt(recentTurns: List<ChatTurn>, now: Long = System.currentTimeMillis()): String? {
        val level = runCatching { settings.current().jarvis.curiosityLevel }.getOrDefault(1)
        if (level <= 0) return null
        if (engine.state.value !is EngineState.Ready) return null

        val turns = (memory.getState(KEY_TURNS)?.toIntOrNull() ?: 0) + 1
        val lastAsk = memory.getState(KEY_LAST_ASK)?.toLongOrNull() ?: 0L

        // Decouple intensity from interruption (the JARVIS character): levels 1–3 share the SAME hard
        // time cap + daily cap, so the question rate stays low and courteous no matter the level. A
        // higher level only lowers the turn-gap (readiness) and deepens attention — better-timed SINGLE
        // questions, not more of them. MAX (4) deliberately relaxes the cap (breaks character; gated +
        // warned in the UI).
        val turnGap = when (level) { 4 -> 1; 3 -> 3; 2 -> 5; else -> 8 }
        val minIntervalMs = if (level >= 4) MAX_MIN_INTERVAL_MS else HARD_MIN_INTERVAL_MS
        val dailyCap = if (level >= 4) MAX_DAILY_CAP else HARD_DAILY_CAP

        val today = now / DAY_MS
        val sameDay = memory.getState(KEY_DAY)?.toLongOrNull() == today
        val count = if (sameDay) (memory.getState(KEY_COUNT)?.toIntOrNull() ?: 0) else 0

        if (count >= dailyCap || turns < turnGap || now - lastAsk < minIntervalMs) {
            memory.setState(KEY_TURNS, turns.toString())
            return null
        }

        // ~1 in 4 eligible asks, revisit an older learned fact instead of probing a new gap.
        val revisit = if (Math.random() <= 0.25) runCatching { revisitQuestion() }.getOrNull() else null
        val question = revisit ?: runCatching { gapQuestion(recentTurns) }.getOrNull()
        if (question.isNullOrBlank()) {
            memory.setState(KEY_TURNS, turns.toString()) // keep counting; try again next turn
            return null
        }
        // Commit the schedule (turn counter, time cap, daily cap) + remember we asked it.
        memory.setState(KEY_TURNS, "0")
        memory.setState(KEY_LAST_ASK, now.toString())
        memory.setState(KEY_DAY, today.toString())
        memory.setState(KEY_COUNT, (count + 1).toString())
        recordAsked(question)
        return question
    }

    /** Turn a curiosity answer into one concise standalone fact (LLM-distilled; falls back to the raw
     *  answer). Used by the ViewModel to reflect the fact back before saving it. */
    suspend fun distill(question: String, answer: String): String {
        val raw = answer.trim()
        if (raw.isBlank()) return raw
        val sys = "Rewrite the user's answer as ONE concise, standalone fact about the user, in the third " +
            "person (e.g. \"The user lives in Leeds.\"). Output only the fact, no preamble."
        val out = runCatching {
            engine.generate("Question: $question\nAnswer: $raw", emptyList(), sys).toList().joinToString("")
        }.getOrDefault("").trim().trim('"')
        // Guard against the model echoing junk / a marker: fall back to a contextualized raw answer.
        return if (out.length in 3..240 && !out.startsWith("//")) out else contextualize(question, raw)
    }

    /** Classify the user's reply to a curiosity question / "shall I remember this?" reflection.
     *  OTHER means "this isn't an answer" (a command or new question) — the caller should not capture it. */
    fun classify(text: String): Confirm {
        val t = text.lowercase().trim().trim('.', '!', ',', ' ')
        if (t.isBlank()) return Confirm.OTHER
        val words = t.split(Regex("\\s+"))
        val first = words.first()
        // A command or a fresh question is not an answer — check this FIRST so it's never mis-saved.
        if (t.endsWith("?") || first in QUESTION_STARTS) return Confirm.OTHER
        if (first in AFFIRM && words.size <= 4) return Confirm.AFFIRM
        if (first in REJECT) return if (words.size <= 2) Confirm.REJECT else Confirm.CORRECTION
        return Confirm.CORRECTION
    }

    // ---- internals ----

    private suspend fun gapQuestion(recentTurns: List<ChatTurn>): String? {
        if (recentTurns.isEmpty()) return null
        val convo = recentTurns.takeLast(10).joinToString("\n") {
            (if (it.role.equals("user", true)) "User: " else "J.A.R.V.I.S.: ") + it.text.trim()
        }
        val known = knownDigest(recentTurns)
        val sys = "You are J.A.R.V.I.S. — precise, courteous and understated, and you pay very close " +
            "attention. From the conversation and what you already know, infer ONE salient thing about the " +
            "user you are not yet certain of, and ask a SINGLE anticipatory question to CONFIRM that " +
            "inference — never a blank or generic prompt; it should feel like you were already paying " +
            "attention. Phrase it warmly and in character, e.g. \"If I may, sir — I gather you're using X " +
            "for Y. Is that right?\". One question only. If nothing is worth confirming, output exactly: NONE."
        val user = buildString {
            append("Recent conversation:\n").append(convo).append("\n\n")
            append("What you already know about the user:\n").append(known.ifBlank { "(nothing yet)" })
            append("\n\nYour one question (or NONE):")
        }
        val raw = engine.generate(user, emptyList(), sys).toList().joinToString("").trim().trim('"')
        if (raw.isBlank() || raw.equals("NONE", true) || raw.startsWith("//") || !raw.any { it.isLetter() }) return null
        val q = raw.lines().firstOrNull { it.isNotBlank() }?.trim()?.trim('"') ?: return null
        if (q.length > 200) return null
        if (isRedundant(q)) return null
        return q
    }

    /** A gentle follow-up about the oldest learned fact not yet revisited. */
    private suspend fun revisitQuestion(): String? {
        val revisited = (memory.getState(KEY_REVISITED) ?: "").split('\n').toSet()
        val note = memory.recentNotes(20)
            .filter { it.source == NoteSource.LEARNED && it.id.toString() !in revisited }
            .minByOrNull { it.timestamp } ?: return null // oldest not-yet-revisited learned fact
        memory.setState(KEY_REVISITED, (revisited + note.id.toString()).filter { it.isNotBlank() }.takeLast(50).joinToString("\n"))
        val sys = "You are J.A.R.V.I.S. — courteous and understated. Ask ONE brief, warm follow-up that " +
            "references this fact you remember about the user, e.g. \"If I recall, sir, you mentioned X — " +
            "how is that coming along?\". One question only; output only the question."
        val raw = engine.generate("Fact you remember: ${note.noteText}", emptyList(), sys).toList().joinToString("").trim().trim('"')
        return raw.lines().firstOrNull { it.isNotBlank() }?.takeIf { it.length in 3..200 && !it.startsWith("//") }
    }

    private suspend fun knownDigest(recentTurns: List<ChatTurn>): String {
        val recent = memory.recentNotes(8)
        val keywords = recentTurns.joinToString(" ") { it.text }
        val relevant = runCatching { memory.recall(keywords, limit = 5) }.getOrDefault(emptyList())
        return (recent + relevant).distinctBy { it.id }.take(10).joinToString("\n") { "- ${it.noteText}" }
    }

    private suspend fun isRedundant(question: String): Boolean {
        val asked = (memory.getState(KEY_ASKED) ?: "").split('\n').map { it.trim() }.filter { it.isNotBlank() }
        val norm = normalize(question)
        if (asked.any { normalize(it) == norm }) return true
        // If the memory already answers it, treat as redundant.
        return runCatching { memory.recall(question, limit = 1).isNotEmpty() }.getOrDefault(false)
    }

    private suspend fun recordAsked(question: String) {
        val asked = (memory.getState(KEY_ASKED) ?: "").split('\n').filter { it.isNotBlank() }
        memory.setState(KEY_ASKED, (asked + question).takeLast(40).joinToString("\n"))
    }

    private fun contextualize(question: String, answer: String): String {
        val topic = question.trim().trimEnd('?').removePrefix("What").removePrefix("what").trim().take(60)
        return if (answer.split(Regex("\\s+")).size >= 4 || topic.isBlank()) answer else "$answer (re: $topic)"
    }

    private fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() || it == ' ' }.trim()

    private companion object {
        const val KEY_TURNS = "curiosity_turns"
        const val KEY_LAST_ASK = "curiosity_last_ask"
        const val KEY_ASKED = "curiosity_asked"
        const val KEY_REVISITED = "curiosity_revisited"
        const val KEY_DAY = "curiosity_day"
        const val KEY_COUNT = "curiosity_count"
        const val DAY_MS = 24 * 60 * 60_000L
        // Hard cap shared by Low/Med/High so it can never become an interrogation (JARVIS character):
        // at most one question per 45 min and a handful per day, regardless of level.
        const val HARD_MIN_INTERVAL_MS = 45 * 60_000L
        const val HARD_DAILY_CAP = 6
        // MAX level only — deliberately frequent (breaks character); gated + warned in the UI.
        const val MAX_MIN_INTERVAL_MS = 4 * 60_000L
        const val MAX_DAILY_CAP = 200
        val AFFIRM = listOf("yes", "yeah", "yep", "yup", "sure", "ok", "okay", "correct", "right", "indeed", "exactly", "save", "keep")
        val REJECT = listOf("no", "nope", "nah", "discard", "forget", "skip", "cancel", "wrong", "incorrect", "don't")
        val QUESTION_STARTS = listOf(
            "what", "why", "how", "when", "where", "who", "which", "can", "could", "would", "do", "does",
            "tell", "show", "set", "open", "play", "call", "text", "email", "search", "remind", "navigate",
            "copy", "clip", "torch", "alarm", "timer", "status", "brief", "lockdown",
        )
    }
}
