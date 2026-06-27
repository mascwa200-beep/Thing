package dev.mascwa.pulse.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mascwa.pulse.security.SecretCrypto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        prefs[key]?.let { decode(it) } ?: AppSettings()
    }

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
        val plaintext = if (SecretCrypto.isEncrypted(raw)) (SecretCrypto.decrypt(raw) ?: return null) else raw
        return runCatching { json.decodeFromString(AppSettings.serializer(), plaintext) }.getOrNull()
    }

    /** Serialise, then encrypt at rest when the user's "encrypt secrets" setting is on (Keystore-backed).
     *  If the Keystore is unavailable, falls back to plaintext rather than failing the write. */
    private fun persist(s: AppSettings): String {
        val plaintext = json.encodeToString(AppSettings.serializer(), s)
        return if (s.security.encryptSecretsAtRest) SecretCrypto.encrypt(plaintext) ?: plaintext else plaintext
    }

    suspend fun replace(settings: AppSettings) = update { settings }
}
