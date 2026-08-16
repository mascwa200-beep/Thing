package dev.mascwa.pulse.data.search

import dev.mascwa.pulse.core.telemetry.DeviceSearch
import dev.mascwa.pulse.core.telemetry.DeviceSearch.RecordKind
import dev.mascwa.pulse.core.telemetry.GuideSearch
import dev.mascwa.pulse.di.AppContainer

/**
 * Everything on this device that can be searched, gathered into one list.
 *
 * Shaped after `OracleEngine.snapshot`: each source is read defensively and independently, so a
 * store that has not loaded — or that throws — costs its own kind and nothing else. There is no
 * network here and no permission is consulted; every one of these is already on the disk.
 *
 * **Bodies are not read.** The guide index is resident and carries a title, a category, a summary
 * and the section headings; the shards holding the prose stay closed. Opening 577 guides to answer
 * a keystroke is the thing the sharded loader exists to avoid.
 */
object DeviceSearchIndex {

    /**
     * How much of a long body to index.
     *
     * A diary entry can run to pages. Beyond a point the extra text stops helping the reader find
     * the entry and starts making every entry match everything, so the opening is indexed and the
     * rest is left to the screen that owns it.
     */
    const val BODY_CHARS = 1_200

    /** Gather. Safe to call on every keystroke — see [records] for why nothing here touches a shard. */
    suspend fun records(c: AppContainer): List<DeviceSearch.Record> {
        val out = ArrayList<DeviceSearch.Record>(700)

        // Guides: the resident index only. Title, category, summary and headings are exactly the
        // fields GuideSearch was tuned against, so these are passed through rather than flattened.
        runCatching { c.survivalContentRepository.index() }.getOrNull()?.forEach { g ->
            out += DeviceSearch.Record(
                entry = GuideSearch.Entry(
                    id = g.id, title = g.title, category = g.category,
                    summary = g.summary, headings = g.headings,
                ),
                kind = RecordKind.GUIDE,
            )
        }

        runCatching { c.notesStore.load() }.getOrNull()?.forEach { n ->
            out += DeviceSearch.of(n.id, RecordKind.NOTE, n.title, n.body.take(BODY_CHARS), n.createdMs)
        }

        runCatching { c.diaryStore.load() }.getOrNull()?.forEach { d ->
            out += DeviceSearch.of(d.id, RecordKind.DIARY, d.title, d.body.take(BODY_CHARS), d.createdMs)
        }

        runCatching { c.taskStore.all() }.getOrNull()?.forEach { t ->
            // A task has no id of its own — the board keys them by title, so that is the identity.
            out += DeviceSearch.of("task:${t.title}", RecordKind.TASK, t.title, t.note, t.updatedMs)
        }

        runCatching { c.profileStore.all() }.getOrNull()?.forEach { p ->
            // Nor does a profile entry; its text IS the fact, so it is both title and identity.
            out += DeviceSearch.of("profile:${p.text}", RecordKind.PROFILE, p.text, "", p.lastSeenMs)
        }

        runCatching { c.findingStore.findingsFlow.value }.getOrNull()?.forEach { f ->
            out += DeviceSearch.of(f.id, RecordKind.FINDING, f.headline, f.body.take(BODY_CHARS), f.createdMs)
        }

        runCatching { c.memoryStream.memoriesFlow.value }.getOrNull()?.forEach { m ->
            // A memory is one line of text with no title of its own; `of` uses the opening as both.
            out += DeviceSearch.of("memory:${m.id}", RecordKind.MEMORY, "", m.text, m.createdMs)
        }

        return out
    }
}
