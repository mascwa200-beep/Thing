package dev.mascwa.pulse.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mascwa.pulse.security.SecretCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pulse_settings")

/**
 * Single source of truth for [AppSettings]. The whole object is persisted as
 * one JSON blob, which keeps reads/writes atomic and avoids a sprawl of
 * individual preference keys.
 */
class SettingsRepository(
    private val context: Context,
    private val json: Json,
) {
    private val key = stringPreferencesKey("settings_json")

    /**
     * The last blob decoded, and what it decoded to.
     *
     * ⚠️ **Seventeen collectors read [settings], and every one of them was decrypting and parsing the
     * same string independently.** One settings change wakes all of them, and each did its own
     * AndroidKeyStore AES-GCM round trip — a blocking binder call into another process — followed by a
     * multi-hundred-field `kotlinx.serialization` decode. Seventeen times the work for one answer, which
     * on the phone this was written for merely went unnoticed.
     *
     * Sharing one decoded instance is safe only because [AppSettings] is deeply immutable: every field
     * across its twenty data classes is a `val` and none holds a mutable collection. Checked rather than
     * assumed — the day that stops being true, this hands the same object to every screen and one of them
     * can change what another is reading.
     *
     * ⚠️ **A failed decode is never remembered.** Null means the blob is present and could not be read,
     * which is the transient-Keystore case [update] deliberately refuses to clobber on. Caching it would
     * make one bad moment stick for the life of the process — the shape of the audit-ledger `corrupt`
     * latch this repository has already had to correct once.
     */
    @Volatile
    private var lastDecoded: Pair<String, AppSettings>? = null

    /**
     * ⚠️ **`flowOn` moves the crypto and the parse off the collector's thread, and most collectors are on
     * the main one.** `MainActivity` reads this in composition; several view models collect it in
     * `viewModelScope`, which is `Main.immediate`. A flow operator runs in the *collector's* context
     * unless told otherwise, so each of those was doing a binder round trip and a large parse on the frame
     * thread. `IO` rather than `Default` because the Keystore call blocks on IPC, and blocking a `Default`
     * thread starves the small pool it shares with every other piece of computation in the app.
     */
    val settings: Flow<AppSettings> = context.dataStore.data
        .map { prefs -> prefs[key]?.let { decode(it) } ?: AppSettings() }
        .flowOn(Dispatchers.IO)

    suspend fun current(): AppSettings = settings.first()

    /** Atomically read-modify-write the settings object. */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val raw = prefs[key]
            val existing = raw?.let { decode(it) }
            // A blob EXISTS but couldn't be decoded/decrypted (e.g. a transient Keystore hiccup). Refuse to
            // overwrite it — writing transform(defaults) here would clobber the real settings, INCLUDING
            // every saved credential, with defaults. Skip this write; the next one (once the Keystore is
            // readable again) will apply cleanly.
            if (raw != null && existing == null) return@edit
            val updated = transform(existing ?: AppSettings())
            prefs[key] = persist(updated)
        }
    }

    /** Decode a stored blob, decrypting it when it's one of our at-rest ciphertexts. Legacy plaintext is
     *  read as-is. An encrypted blob that can't be decrypted returns null (NOT the raw ciphertext) so
     *  callers can tell "undecodable existing data" from "no data" and avoid clobbering it. */
    private fun decode(raw: String): AppSettings? {
        // A plain local rather than `?.let { (a, b) -> ... }`: the non-local return out of a `let` is
        // the subtler of the two readings, and this one says outright that a hit returns immediately.
        val cached = lastDecoded
        if (cached != null && cached.first == raw) return cached.second
        val plaintext = if (SecretCrypto.isEncrypted(raw)) (SecretCrypto.decrypt(raw) ?: return null) else raw
        return runCatching { json.decodeFromString(AppSettings.serializer(), plaintext) }.getOrNull()
            ?.also { lastDecoded = raw to it }
    }

    /** Serialise, then encrypt at rest when the user's "encrypt secrets" setting is on (Keystore-backed).
     *  If the Keystore is unavailable, falls back to plaintext rather than failing the write. */
    private fun persist(s: AppSettings): String {
        val plaintext = json.encodeToString(AppSettings.serializer(), s)
        val raw = if (s.security.encryptSecretsAtRest) SecretCrypto.encrypt(plaintext) ?: plaintext else plaintext
        // Both halves are in hand here, so the read that immediately follows this write costs nothing.
        // Worth doing rather than obvious: at-rest encryption uses a fresh IV per write, so the ciphertext
        // differs every time even when the settings do not — without this the cache would miss on every
        // save, which is exactly when all seventeen collectors wake at once.
        lastDecoded = raw to s
        return raw
    }

    suspend fun replace(settings: AppSettings) = update { settings }
}
