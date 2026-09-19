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

        // The documents the user loaded into the knowledge base.
        //
        // ⚠️ **`RecordKind.KNOWLEDGE` had no producer on this platform at all** — it is labelled
        // "Document", it is routed at the Memory screen, and the only thing anywhere that emitted
        // one was the DESKTOP, for study cards. So a document somebody imported so the Computer
        // could answer from it was unfindable by the person who imported it, while the sentence at
        // the top of this file said "everything on this device that can be searched".
        //
        // ⚠️ **The title only, and that is the same rule the guides above follow for the same
        // reason.** `titles()` is one DISTINCT query; `fullText` joins every chunk of a document
        // and a loaded reference doc runs to pages, so indexing bodies here would cost every
        // refresh what the sharded guide loader exists to avoid. The honest consequence, worth
        // knowing: a document is found by its NAME here. The Computer's own retrieval
        // (`KnowledgeStore.search`, FTS4 over the chunks) is what reaches the text, and that is a
        // question you ask it rather than the search box.
        // ⚠️ No timestamp, and what that costs was checked rather than shrugged at: `atMs` is a
        // TIEBREAK on equal score (`thenByDescending`) and nothing renders it, so a document shows
        // no false date — it only loses a tie against a dated record that scored the same.
        // `titles()` returns strings and the store exposes no per-document time, so supplying one
        // would mean inventing it.
        runCatching { c.knowledgeStore.titles() }.getOrNull()?.forEach { title ->
            out += DeviceSearch.of("doc:$title", RecordKind.KNOWLEDGE, title, "")
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
        // Every place inside Settings — each CATEGORY by name ("notifications", "storage"), and
        // each SECTION within one, which is where the vocabulary actually lives. A section's
        // keywords used to be a literal passed to a LOCAL function inside the Settings composable,
        // so nothing outside that screen could read them: 52 words a person would plausibly type —
        // "sponsorblock", "anydesk", "iptv", "gmail", "stooq" — reached no Settings destination
        // from here at all. The row's title names the section so the page it opens can be scanned
        // for it; several sections share one destination, deliberately.
        //
        // ⚠️ RecordKind.SETTING, not FEATURE, and measured rather than reasoned about: a Settings
        // row's body is a keyword list, so it repeats its own title and scores a title hit AND a
        // body hit where a real screen scores only the title hit — fields sum, with no length
        // normalisation anywhere in the scorer. As FEATUREs these rows outranked the screens they
        // describe and pushed two off the slate entirely for "device". The separate kind gives
        // them their own budget; SettingsSections.row() removes the double count. Both were needed.
        //
        // ⚠️ The rows are built THERE, not here, so the body rule has one home. This used to
        // assemble the category rows inline, which is how they missed the filter the sections got.
        val settings = dev.mascwa.pulse.feature.settings.SettingsSections.searchRecords()
            .map { (route, title, body) ->
                DeviceSearch.of(id = route, kind = RecordKind.SETTING, title = title, body = body)
            }
        return features + settings
    }
}
