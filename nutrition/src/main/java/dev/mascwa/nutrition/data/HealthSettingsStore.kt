package dev.mascwa.nutrition.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mascwa.pulse.data.health.HealthSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore("nutrition_profile")

/**
 * Height, age, goal and rate — the handful of facts every calorie target is derived from.
 *
 * ⚠️ **This is where the two applications genuinely differ, which is why the shared code takes a flow
 * and a lambda rather than a settings type.** The LCARS application keeps this as one section of a
 * forty-field blob it already had; here it is the whole of what there is to remember, so it gets its
 * own small store rather than a section of something larger that does not exist.
 *
 * ⚠️ **Read-modify-write under a Mutex, not a plain write.** Every setter is `current().copy(...)`,
 * so two edits arriving together — a height typed while a goal is being saved — would otherwise race
 * and the later write would carry a stale copy of the earlier field. The lock makes the pair atomic;
 * DataStore's own `edit` is atomic per write, which is not the same thing.
 *
 * ⚠️ An unreadable blob yields defaults rather than throwing, and the defaults are all "not told" —
 * 0 height, 0 birth year — which every consumer already treats as "cannot compute" rather than zero.
 * That is the same discipline the shared stores use and the reason a corrupt file cannot brick the
 * app; what it costs is that a corrupt file is silently replaced on the next write.
 */
class HealthSettingsStore(
    private val context: Context,
    private val json: Json,
) {
    private val key = stringPreferencesKey("health")
    private val lock = Mutex()

    val settings: Flow<HealthSettings> =
        context.settingsStore.data.map { prefs -> decode(prefs[key]) }

    suspend fun current(): HealthSettings = decode(
        runCatching { context.settingsStore.data.first() }.getOrNull()?.get(key)
    )

    suspend fun update(block: (HealthSettings) -> HealthSettings) = lock.withLock {
        context.settingsStore.edit { prefs ->
            prefs[key] = json.encodeToString(HealthSettings.serializer(), block(decode(prefs[key])))
        }
        Unit
    }

    private fun decode(raw: String?): HealthSettings =
        raw?.let { runCatching { json.decodeFromString(HealthSettings.serializer(), it) }.getOrNull() }
            ?: HealthSettings()
}
