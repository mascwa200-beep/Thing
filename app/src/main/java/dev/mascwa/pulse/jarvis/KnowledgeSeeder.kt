package dev.mascwa.pulse.jarvis

import android.content.Context
import dev.mascwa.pulse.data.jarvis.JarvisMemory
import dev.mascwa.pulse.data.jarvis.KnowledgeStore

/**
 * Loads the reference docs bundled in the APK (`assets/knowledge/`) into the on-device knowledge
 * library on first launch, so J.A.R.V.I.S. can retrieve from them with nothing for the user to
 * import. Bundled docs are tagged `source = "bundled"` and re-seeded only when [SEED_VERSION] bumps,
 * so refreshing the bundled set never touches the user's own imported documents.
 */
class KnowledgeSeeder(
    private val context: Context,
    private val knowledge: KnowledgeStore,
    private val memory: JarvisMemory,
) {

    suspend fun seedIfNeeded() {
        if (runCatching { memory.getState(SEED_KEY) }.getOrNull() == SEED_VERSION) return
        runCatching {
            knowledge.deleteBySource(BUNDLED)
            val files = context.assets.list(DIR).orEmpty()
            for (name in files) {
                val text = context.assets.open("$DIR/$name").bufferedReader().use { it.readText() }
                if (text.isNotBlank()) {
                    knowledge.addDocument(name.substringBeforeLast('.'), text, source = BUNDLED)
                }
            }
            memory.setState(SEED_KEY, SEED_VERSION)
        }
    }

    private companion object {
        const val DIR = "knowledge"
        const val BUNDLED = "bundled"
        const val SEED_KEY = "knowledge_seed_version"
        // Bump when the bundled docs change to force a one-time re-seed on next launch.
        const val SEED_VERSION = "1"
    }
}
