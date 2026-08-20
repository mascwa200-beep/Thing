package dev.mascwa.pulse.data.search

import dev.mascwa.pulse.core.telemetry.DeviceSearch
import dev.mascwa.pulse.core.telemetry.DeviceSearch.RecordKind
import dev.mascwa.pulse.core.telemetry.toSearchEntry
import dev.mascwa.pulse.di.AppContainer

/**
 * Everything on this device that can be searched, gathered into one list.
 *
 * Shaped after `OracleEngine.snapshot`: each source is read defensively and independently, so a
 * store that has not loaded — or that throws — costs its own kind and nothing else. There is no
 * network here and no permission is consulted; every one of these is already on the disk.
 *
 * Each store is asked to **load**, not read off its published flow. A flow holds an empty list until
 * something else has caused a load, so reading it would have made findings and memories invisible on
 * a screen opened cold — quietly, and only on the paths where nothing else had touched them.
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

        // The app's own features, so typing "radar" OPENS the radar instead of only finding prose
        // about radar. Ids are the nav routes (the FEATURE convention); the body carries the menu
        // pitch plus each entry's declared synonyms, so "planes" matches the radar here exactly as
        // it does in the MENU's own search. The search screen itself is excluded — being offered
        // the screen you are standing on is noise.
        out += featureRecords()

        // Guides: the resident index only. Title, category, summary and headings are exactly the
        // fields GuideSearch was tuned against, so these are passed through rather than flattened.
        runCatching { c.survivalContentRepository.index() }.getOrNull()?.forEach { g ->
            out += DeviceSearch.Record(entry = g.toSearchEntry(), kind = RecordKind.GUIDE)
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

        runCatching { c.findingStore.load() }.getOrNull()?.forEach { f ->
            out += DeviceSearch.of(f.id, RecordKind.FINDING, f.headline, f.body.take(BODY_CHARS), f.createdMs)
        }

        runCatching { c.memoryStream.all() }.getOrNull()?.forEach { m ->
            // A memory is one line of text with no title of its own; `of` uses the opening as both.
            out += DeviceSearch.of("memory:${m.id}", RecordKind.MEMORY, "", m.text, m.createdMs)
        }

        return out
    }

    private fun featureRecords(): List<DeviceSearch.Record> {
        val terms = dev.mascwa.pulse.navigation.GROUPS
            .flatMap { it.entries }
            .associateBy({ it.route }, { it.searchTerms })
        val features = dev.mascwa.pulse.data.usage.FeatureCatalog.entries
            .filter { it.key != dev.mascwa.pulse.navigation.Routes.SEARCH }
            .map { f ->
                DeviceSearch.of(
                    id = f.key,
                    kind = RecordKind.FEATURE,
                    title = f.label,
                    body = (listOf(f.pitch) + terms[f.key].orEmpty()).joinToString(" "),
                )
            }
        // Every Settings CATEGORY is findable by name ("notifications", "storage") and opens
        // Settings AT that category — a FEATURE id IS a route by convention, and openApp carries
        // the argument through. This is the settings?cat= route argument's first producer: it
        // shipped with zero callers (this repo's recorded computed-and-never-used class).
        val settingsCats = dev.mascwa.pulse.feature.settings.SettingsCategory.entries.map { cat ->
            DeviceSearch.of(
                id = "${dev.mascwa.pulse.navigation.Routes.SETTINGS}?cat=${cat.name.lowercase()}",
                kind = RecordKind.FEATURE,
                title = "Settings · ${cat.title}",
                body = "${cat.blurb} ${cat.keywords}",
            )
        }
        return features + settingsCats
    }
}
