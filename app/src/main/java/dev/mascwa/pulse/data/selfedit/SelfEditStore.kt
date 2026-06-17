package dev.mascwa.pulse.data.selfedit

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

private val Context.selfEditDataStore: DataStore<Preferences> by preferencesDataStore(name = "jarvis_selfedit")

/**
 * On-device store for J.A.R.V.I.S.'s editable interpreted layer — the persona [SelfEditState.charter]
 * plus a rollback [SelfEditState.versions] history (later modules add pending approvals + authored
 * tools). Mirrors `SettingsRepository`'s atomic-JSON-blob pattern but in its own DataStore file, so
 * the existing settings and Room data are never migrated or wiped when this grows. Fully local.
 */
class SelfEditStore(private val context: Context, private val json: Json) {

    private val key = stringPreferencesKey("selfedit_json")

    val state: Flow<SelfEditState> = context.selfEditDataStore.data.map { prefs ->
        prefs[key]?.let { runCatching { json.decodeFromString(SelfEditState.serializer(), it) }.getOrNull() }
            ?: SelfEditState()
    }

    suspend fun current(): SelfEditState = state.first()

    /** Atomically read-modify-write the self-edit state. */
    suspend fun update(transform: (SelfEditState) -> SelfEditState) {
        context.selfEditDataStore.edit { prefs ->
            val existing = prefs[key]?.let {
                runCatching { json.decodeFromString(SelfEditState.serializer(), it) }.getOrNull()
            } ?: SelfEditState()
            prefs[key] = json.encodeToString(SelfEditState.serializer(), transform(existing))
        }
    }

    /** Replace the charter, snapshotting the previous one for rollback (bounded history). */
    suspend fun setCharter(text: String) = update { s ->
        val next = text.trim()
        if (next == s.charter) return@update s
        val versioned = if (s.charter.isNotBlank()) {
            (s.versions + ArtifactVersion("charter", "", s.charter, System.currentTimeMillis())).takeLast(MAX_VERSIONS)
        } else {
            s.versions
        }
        s.copy(charter = next, versions = versioned)
    }

    /** Queue a proposed self-change for the user to approve (de-duped by id, bounded). */
    suspend fun enqueue(action: PendingAction) = update { s ->
        if (s.pendingActions.any { it.id == action.id }) s
        else s.copy(pendingActions = (s.pendingActions + action).takeLast(MAX_PENDING))
    }

    suspend fun removePending(id: String) = update { s ->
        s.copy(pendingActions = s.pendingActions.filterNot { it.id == id })
    }

    /** Snapshot an artifact for rollback. */
    suspend fun pushVersion(type: String, key: String, content: String) = update { s ->
        s.copy(versions = (s.versions + ArtifactVersion(type, key, content, System.currentTimeMillis())).takeLast(MAX_VERSIONS))
    }

    /** Register (or replace) an approved authored tool. */
    suspend fun addAuthoredTool(tool: AuthoredTool) = update { s ->
        s.copy(authoredTools = s.authoredTools.filterNot { it.name == tool.name } + tool)
    }

    suspend fun removeAuthoredTool(name: String) = update { s ->
        s.copy(authoredTools = s.authoredTools.filterNot { it.name == name })
    }

    private companion object {
        const val MAX_VERSIONS = 30
        const val MAX_PENDING = 50
    }
}
