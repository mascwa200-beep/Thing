package dev.mascwa.pulse.data.keyboard

import dev.mascwa.pulse.feature.keyboard.LearnedCounts
import dev.mascwa.pulse.feature.keyboard.LearnedCounts.Entry
import dev.mascwa.pulse.feature.keyboard.WordMemory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * The words the LCARS keyboard has learned, kept encrypted on this phone and nowhere else.
 *
 * This is the one place anything typed on the phone is kept, and the owner asked for it: a keyboard that
 * never learns keeps "correcting" the same names and terms. So it is held to the narrowest shape that
 * does the job, and `LearnedWordsTest` holds it there:
 *
 * - ⚠️ **Sealed, or not kept at all.** Everything written goes through [seal] — the Keystore cipher,
 *   `SecretCrypto.encrypt`, as `AppContainer` wires it. When the cipher cannot seal, NOTHING is written:
 *   the tempting reading is "keep it in the clear, it never leaves the phone", but a phone whose Keystore
 *   is not working is exactly where a plain file is most likely to be read by something else.
 * - **Words and counts only**: what [LearnedCounts] keeps — a word, how often, when last — never a
 *   sentence, never the app it was typed in, never what came before or after it.
 * - **Never learned in a password field**, or where a field asks for no learning. That is decided before
 *   anything reaches here, by `KeyboardPrivacy.mayLearn`.
 * - **Nowhere else.** No log, no activity record, no report, no network; and the file is in
 *   `noBackupFilesDir`, so a device backup does not carry it either — nor could it be read on another
 *   phone, whose Keystore does not hold this one's key.
 * - **[forget] empties it**, from Settings ▸ Android takeover ▸ LCARS keyboard.
 *
 * ⚠️ **A file that is there and cannot be opened is left alone**, not written over. A Keystore that
 * fails once — after an update, on a cold boot — would otherwise cost every learned word the next time
 * a word was saved. The store notes it ([Summary.unreadable]) and learns in memory for this process;
 * [forget] is how somebody whose key is genuinely gone starts again, and Settings says so.
 */
class LearnedWords(
    private val file: File,
    private val scope: CoroutineScope,
    private val seal: (String) -> String?,
    private val unseal: (String) -> String?,
    private val now: () -> Long = System::currentTimeMillis,
    private val saveAfterMs: Long = SAVE_AFTER_MS,
) : WordMemory {

    data class Summary(val words: Int, val known: Int, val unreadable: Boolean)

    private val lock = Any()
    private val io = Mutex()

    @Volatile private var entries: Map<String, Entry> = emptyMap()
    @Volatile private var known: List<String> = emptyList()
    @Volatile private var loaded = false
    @Volatile private var unreadable = false
    /** Something learned that is not yet on the disk. */
    @Volatile private var dirty = false
    private var pending: Job? = null

    override fun known(): Collection<String> = known

    override fun saw(word: String) = change { LearnedCounts.saw(it, word, now()) }

    override fun accept(word: String) = change { LearnedCounts.accept(it, word, now()) }

    /**
     * Reads what was kept, once. Words learned before it finishes are kept too: the counts are added,
     * because a word typed before the file was read was typed that many more times.
     */
    override suspend fun load() {
        if (loaded) return
        io.withLock {
            if (loaded) return
            val onDisk = read()
            synchronized(lock) {
                entries = merge(onDisk, entries)
                known = LearnedCounts.known(entries)
                loaded = true
                // Words learned while it was being read were held back from the disk; they go now.
                if (dirty) schedule()
            }
        }
    }

    fun summary(): Summary = Summary(entries.size, known.size, unreadable)

    /** Everything learned, gone: from memory, from the pending save, and from the phone. */
    suspend fun forget() {
        synchronized(lock) {
            pending?.cancel()
            pending = null
            entries = emptyMap()
            known = emptyList()
            dirty = false
        }
        io.withLock {
            file.delete()
            File(file.path + TMP).delete()
            unreadable = false
            loaded = true
        }
    }

    /** Writes what is unsaved now rather than after the pause; for the keyboard going away. */
    override suspend fun flushNow() {
        synchronized(lock) {
            pending?.cancel()
            pending = null
        }
        write()
    }

    private fun change(update: (Map<String, Entry>) -> Map<String, Entry>) {
        synchronized(lock) {
            val next = update(entries)
            if (next === entries) return
            entries = next
            known = LearnedCounts.known(next)
            dirty = true
            schedule()
        }
    }

    /** Called holding [lock]. A later change moves the save back, so a sentence is one write. */
    private fun schedule() {
        pending?.cancel()
        pending = scope.launch {
            delay(saveAfterMs)
            write()
        }
    }

    private suspend fun read(): Map<String, Entry> {
        if (!file.isFile) return emptyMap()
        val text = runCatching { file.readText() }.getOrNull()?.let(unseal)
        if (text == null) {
            unreadable = true
            return emptyMap()
        }
        return LearnedCounts.decode(text)
    }

    private suspend fun write() = io.withLock {
        // Not until the file has been read, or the first save would replace everything learned before.
        if (!loaded || unreadable || !dirty) return@withLock
        val snapshot = entries
        if (snapshot.isEmpty()) {
            file.delete()
            dirty = false
            return@withLock
        }
        // A failed seal leaves it dirty: the next change, or the next flush, tries again.
        val sealed = seal(LearnedCounts.encode(snapshot)) ?: return@withLock
        val tmp = File(file.path + TMP)
        val saved = runCatching {
            file.parentFile?.mkdirs()
            tmp.writeText(sealed)
            tmp.renameTo(file) || (file.delete() && tmp.renameTo(file))
        }.getOrDefault(false)
        tmp.delete()
        // Only what this save held is saved: a word learned while it was being written is still owed.
        // Checked and cleared together, under the lock every change takes.
        if (saved) synchronized(lock) { if (entries === snapshot) dirty = false }
    }

    companion object {
        /** The file's name inside `noBackupFilesDir`. */
        const val FILE_NAME = "keyboard_words.sealed"

        /** Saves wait this long after the last change, so a sentence is one write rather than twelve. */
        const val SAVE_AFTER_MS = 5_000L

        private const val TMP = ".tmp"

        /** Two tallies of the same words: counts added, the later time and its spelling kept. */
        internal fun merge(a: Map<String, Entry>, b: Map<String, Entry>): Map<String, Entry> {
            val out = a.toMutableMap()
            for ((k, e) in b) {
                val have = out[k]
                out[k] = if (have == null) e else Entry(
                    word = if (e.lastMs >= have.lastMs) e.word else have.word,
                    count = have.count + e.count,
                    lastMs = maxOf(have.lastMs, e.lastMs),
                )
            }
            return LearnedCounts.capped(out)
        }
    }
}
