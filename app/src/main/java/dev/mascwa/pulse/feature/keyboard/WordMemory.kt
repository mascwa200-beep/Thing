package dev.mascwa.pulse.feature.keyboard

/**
 * The words this phone has learned, as the keyboard sees them: a list to consult and two things to
 * report. Where they are kept is not the keyboard's business — `data/keyboard/LearnedWords` keeps them
 * encrypted on this phone and nowhere else — and the keyboard reaches them only through this, so the
 * rest of this package can still be held to storing nothing (`KeyboardRecordsNothingTest`).
 */
interface WordMemory {

    /** The learned words that count as words: typed [LearnedCounts.KNOWN_AFTER] times, or kept on purpose. */
    fun known(): Collection<String>

    /** [word] was typed and kept as typed, in a field that allows learning. */
    fun saw(word: String)

    /** [word] was kept on purpose — an autocorrection undone, or the typed word picked from the strip. */
    fun accept(word: String)

    /** Reads what was kept. Until it has, [known] is empty and nothing is saved over what is there. */
    suspend fun load()

    /** Saves what is unsaved now, rather than after the usual pause: the keyboard is going away. */
    suspend fun flushNow()
}

/**
 * The bookkeeping behind [WordMemory], with no storage in it, so every rule is tested.
 *
 * - A word counts once it has been typed [KNOWN_AFTER] times, or kept on purpose once.
 * - Only words: letters and apostrophes, [MIN_LENGTH] to [MAX_LENGTH] long. Anything else — a code, a
 *   number, something with a symbol in it — is not a word and is never kept.
 * - At most [CAP] words; past that the least used goes first, oldest first among equals.
 * - One entry per word whatever its capitals, keeping the capitals it was last typed with.
 */
object LearnedCounts {

    const val KNOWN_AFTER = 2
    const val CAP = 2000
    const val MIN_LENGTH = 2
    const val MAX_LENGTH = 32

    data class Entry(val word: String, val count: Int, val lastMs: Long)

    fun isLearnable(word: String): Boolean =
        word.length in MIN_LENGTH..MAX_LENGTH && word.any { it.isLetter() } && word.all { it.isLetter() || it == '\'' }

    fun saw(entries: Map<String, Entry>, word: String, nowMs: Long): Map<String, Entry> {
        if (!isLearnable(word)) return entries
        val k = Dictionary.key(word)
        val count = (entries[k]?.count ?: 0) + 1
        return capped(entries + (k to Entry(word, count, nowMs)))
    }

    fun accept(entries: Map<String, Entry>, word: String, nowMs: Long): Map<String, Entry> {
        if (!isLearnable(word)) return entries
        val k = Dictionary.key(word)
        val count = maxOf(entries[k]?.count ?: 0, KNOWN_AFTER)
        return capped(entries + (k to Entry(word, count, nowMs)))
    }

    fun known(entries: Map<String, Entry>): List<String> =
        entries.values.filter { it.count >= KNOWN_AFTER }.map { it.word }

    internal fun capped(entries: Map<String, Entry>): Map<String, Entry> {
        if (entries.size <= CAP) return entries
        val drop = entries.values.sortedWith(compareBy({ it.count }, { it.lastMs })).take(entries.size - CAP)
            .map { Dictionary.key(it.word) }.toSet()
        return entries.filterKeys { it !in drop }
    }

    /** One `word<TAB>count<TAB>lastMs` per line — the plaintext that is encrypted before it is kept. */
    fun encode(entries: Map<String, Entry>): String =
        entries.values.joinToString("") { "${it.word}\t${it.count}\t${it.lastMs}\n" }

    /** The reverse of [encode]; a line that does not read is dropped rather than trusted. */
    fun decode(text: String): Map<String, Entry> = text.lineSequence().mapNotNull { line ->
        val f = line.split('\t')
        if (f.size != 3 || !isLearnable(f[0])) return@mapNotNull null
        val count = f[1].toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        val at = f[2].toLongOrNull() ?: return@mapNotNull null
        Dictionary.key(f[0]) to Entry(f[0], count, at)
    }.toMap().let { capped(it) }
}
