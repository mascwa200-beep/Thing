package dev.mascwa.pulse.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
        prefs[key]?.let { raw ->
            runCatching { json.decodeFromString(AppSettings.serializer(), raw) }.getOrNull()
        } ?: AppSettings()
    }

    suspend fun current(): AppSettings = settings.first()

    /** Atomically read-modify-write the settings object. */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val existing = prefs[key]?.let {
                runCatching { json.decodeFromString(AppSettings.serializer(), it) }.getOrNull()
            } ?: AppSettings()
            val updated = transform(existing)
            prefs[key] = json.encodeToString(AppSettings.serializer(), updated)
        }
    }

    suspend fun replace(settings: AppSettings) = update { settings }
}
