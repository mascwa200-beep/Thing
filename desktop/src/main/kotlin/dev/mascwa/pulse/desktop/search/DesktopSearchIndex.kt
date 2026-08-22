package dev.mascwa.pulse.desktop.search

import dev.mascwa.pulse.desktop.library.LibraryRepository
import dev.mascwa.pulse.core.telemetry.toSearchEntry
import dev.mascwa.pulse.desktop.study.StudyStore
import dev.mascwa.pulse.core.telemetry.DeviceSearch
import dev.mascwa.pulse.core.telemetry.DeviceSearch.RecordKind

/**
 * Everything this desktop can search, gathered into one list.
 *
 * Shaped after the Android `DeviceSearchIndex`: each source is read defensively and independently, so a
 * source that fails costs its own kind and nothing else. Nothing here touches the network, and the
 * guide side reads only the resident index — no shard is opened to answer a keystroke.
 *
 * ⚠️ **The corpus is honestly smaller than the phone's.** The Android version searches notes, diary,
 * tasks, profile, episodic memory, findings and ingested documents; none of those exist on the desktop.
 * What is here is the bundled library and this machine's own study cards. The screen says which, rather
 * than implying the phone's stores are being searched from here.
 */
object DesktopSearchIndex {

    suspend fun records(library: LibraryRepository, study: StudyStore): List<DeviceSearch.Record> {
        val out = ArrayList<DeviceSearch.Record>(700)

        // Guides: the resident index only. Title, category, summary and headings are exactly the fields
        // GuideSearch was tuned against, so they are passed through rather than flattened.
        runCatching { library.index() }.getOrNull()?.forEach { g ->
            out += DeviceSearch.Record(entry = g.toSearchEntry(), kind = RecordKind.GUIDE)
        }

        // Study cards: what you have actually been asked. Searching them is how "wasn't there a question
        // about bleach dilution?" gets answered without re-reading the guide it came from.
        runCatching { study.items.value }.getOrNull()?.forEach { item ->
            out += DeviceSearch.of(
                id = item.question.id,
                kind = RecordKind.KNOWLEDGE,
                title = item.question.guideTitle + " ▸ " + item.question.heading,
                body = item.question.prompt + " " + item.question.answer,
            )
        }

        return out
    }
}
