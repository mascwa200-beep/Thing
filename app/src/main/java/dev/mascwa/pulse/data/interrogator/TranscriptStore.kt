package dev.mascwa.pulse.data.interrogator

import android.content.Context
import dev.mascwa.pulse.core.telemetry.TranscriptPolicy
import dev.mascwa.pulse.data.interrogator.db.TranscriptDatabase
import dev.mascwa.pulse.data.interrogator.db.UtteranceEntity
import dev.mascwa.pulse.security.SecretCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The acoustic interrogator's transcript, on disk.
 *
 * Thin by design: every decision about *what may be kept* lives in the CI-tested
 * [TranscriptPolicy], and every decision about *where it lives* in [TranscriptDatabase]. What is
 * left here is the one thing neither of those can do — applying the Keystore-bound cipher, which is
 * an app-module concern because [SecretCrypto] is.
 *
 * ⚠️ **The rule that matters: a row that cannot be encrypted is not stored.** [SecretCrypto.encrypt]
 * returns null when the secure element is unavailable or the key has been invalidated, and the
 * tempting reading of that is "store it anyway, it is only on-device". It is not only on-device —
 * it is the one store in this app holding verbatim speech from people who never agreed to be
 * recorded, and a device with no working keystore is exactly the device where a plaintext row is
 * most likely to be read by something else. Losing the utterance is the correct outcome.
 *
 * ⚠️ **A row that will not decrypt is dropped from the read, not surfaced.** A key rotation or a
 * factory-reset restore leaves undecryptable rows behind; showing them as blanks or as base64 would
 * be worse than showing nothing, and the next [prune] clears them out by age regardless.
 */
class TranscriptStore(
    context: Context,
    private val encrypt: (String) -> String? = SecretCrypto::encrypt,
    private val decrypt: (String) -> String? = SecretCrypto::decrypt,
) {
    private val appContext = context.applicationContext

    /**
     * ⚠️ NOT a `by lazy`. [purge] closes the database and deletes the file, and a lazy would keep
     * handing out the closed instance forever after — every subsequent `record` throwing on a
     * handle whose file no longer exists. The handle is nulled on purge and rebuilt on next use,
     * so purging is something the store recovers from rather than something it dies of.
     */
    @Volatile
    private var handle: TranscriptDatabase? = null
    private val lock = Any()

    private fun db(): TranscriptDatabase =
        handle ?: synchronized(lock) {
            handle ?: TranscriptDatabase.build(appContext).also { handle = it }
        }

    private fun dao() = db().utteranceDao()

    /** One line of transcript, decrypted, as the surface wants it. */
    data class Line(val text: String, val atMs: Long)

    /**
     * Offer an utterance. Returns true when it was stored.
     *
     * False covers three genuinely different refusals — the policy would not admit it, the text was
     * empty, or it could not be sealed — and the caller does not need to tell them apart, because
     * the correct response to all three is the same: carry on and do not retry.
     */
    suspend fun record(text: String, atMs: Long): Boolean = withContext(Dispatchers.IO) {
        val sealed = TranscriptSeal.seal(text, atMs, encrypt) ?: return@withContext false
        dao().insert(UtteranceEntity(cipher = sealed.cipher, atMs = sealed.atMs))
        true
    }

    /** Newest first. Rows that will not decrypt are omitted rather than shown as noise. */
    suspend fun recent(limit: Int = DEFAULT_PAGE): List<Line> = withContext(Dispatchers.IO) {
        dao().recent(limit).mapNotNull { row ->
            decrypt(row.cipher)?.let { Line(it, row.atMs) }
        }
    }

    /**
     * Enforce the retention bound. Both halves, because [TranscriptPolicy] enforces both and for
     * different reasons — see its KDoc.
     */
    suspend fun prune(nowMs: Long = System.currentTimeMillis()): Int = withContext(Dispatchers.IO) {
        val byAge = dao().deleteOlderThan(nowMs - TranscriptPolicy.WINDOW_MS)
        val byCount = dao().trimToNewest(TranscriptPolicy.MAX_ENTRIES)
        byAge + byCount
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) { dao().count() }

    /**
     * The purge control.
     *
     * ⚠️ Clears the table AND deletes the file. The table clear is what makes the open handle
     * consistent; the file delete is what actually removes the bytes. Doing only the first would
     * leave the content in freed pages, which is the difference this store's whole shape is built
     * around — `PRAGMA secure_delete` narrows that window but does not close it.
     */
    suspend fun purge(): Boolean = withContext(Dispatchers.IO) {
        // Clearing first is defence for the case where the file delete is refused: it is wasted work
        // when the delete succeeds, and the only protection left when it does not.
        runCatching { dao().clear() }
        synchronized(lock) {
            runCatching { handle?.close() }
            handle = null
        }
        runCatching { TranscriptDatabase.destroy(appContext) }.getOrDefault(false)
    }

    companion object {
        const val DEFAULT_PAGE = 200
    }
}
