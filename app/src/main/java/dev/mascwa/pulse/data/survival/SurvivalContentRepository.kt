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
            val text = context.assets.open("survival/guides.json")
                .bufferedReader().use { it.readText() }
            val book = json.decodeFromString(GuideBook.serializer(), text)
            book.guides.also { cached = it }
        }
    }

    suspend fun guide(id: String): Guide? = guides().firstOrNull { it.id == id }
}
