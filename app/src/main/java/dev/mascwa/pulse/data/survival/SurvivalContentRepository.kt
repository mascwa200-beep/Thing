package dev.mascwa.pulse.data.survival

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Loads the bundled, always-offline survival guides from assets. */
class SurvivalContentRepository(
    private val context: Context,
    private val json: Json,
) {
    @Volatile private var cached: List<Guide>? = null

    suspend fun guides(): List<Guide> {
        cached?.let { return it }
        return withContext(Dispatchers.IO) {
            // Every guides*.json under assets/survival/ is loaded and flattened — new content areas (the
            // textbook/survival/cooking expansions) each get their own file for PR-diff isolation; the base
            // guides.json keeps the original catalog. Sorted so load order is deterministic.
            val files = context.assets.list("survival")
                ?.filter { it.startsWith("guides") && it.endsWith(".json") }
                ?.sorted().orEmpty()
            files.flatMap { name ->
                val text = context.assets.open("survival/$name").bufferedReader().use { it.readText() }
                json.decodeFromString(GuideBook.serializer(), text).guides
            }.also { cached = it }
        }
    }

    suspend fun guide(id: String): Guide? = guides().firstOrNull { it.id == id }
}
