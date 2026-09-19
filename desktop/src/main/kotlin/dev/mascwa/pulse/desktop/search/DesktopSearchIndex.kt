package dev.mascwa.pulse.desktop.search

import dev.mascwa.pulse.desktop.DESK_GROUPS
import dev.mascwa.pulse.desktop.Screen
import dev.mascwa.pulse.desktop.library.LibraryRepository
import dev.mascwa.pulse.desktop.notes.DiaryStore
import dev.mascwa.pulse.desktop.notes.NotesStore
import dev.mascwa.pulse.core.telemetry.GuideSearch
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
 * ⚠️ **The corpus is honestly smaller than the phone's, and this paragraph used to overstate how
 * much.** It said the Android version searches "notes, diary, tasks, profile, episodic memory,
 * findings and ingested documents; none of those exist on the desktop" — and **notes and diary do
 * exist here**, with their own stores, their own screens and their own entries in the directory.
 * They simply were not indexed, so the two kinds of thing the user WROTE THEMSELVES could not be
 * found, which is the case [DeviceSearch] opens by arguing is the most important one of all.
 *
 * What genuinely does not exist here is the phone's tasks, profile, episodic memory, findings and
 * knowledge base. What is here is the bundled library, this machine's study cards, and now its
 * notes and diary. The screen says which, rather than implying the phone's stores are reachable.
 */
object DesktopSearchIndex {

    /**
     * ⚠️ Every source is REQUIRED rather than defaulted to null, and that is deliberate. A defaulted
     * store is a default that quietly means "do not index this" — the shape that shipped twice in
     * this repository already, where a caller compiled, looked wired, and silently did nothing.
     * Making them required means adding a source forces every caller to decide about it, which is
     * how these two came to be noticed missing in the first place.
     */
    suspend fun records(
        library: LibraryRepository,
        study: StudyStore,
        notes: NotesStore,
        diary: DiaryStore,
    ): List<DeviceSearch.Record> {
        val out = ArrayList<DeviceSearch.Record>(700)

        // Guides: the resident index only. Title, category, summary and headings are exactly the fields
        // GuideSearch was tuned against, so they are passed through rather than flattened.
        runCatching { library.index() }.getOrNull()?.forEach { g ->
            out += DeviceSearch.Record(entry = g.toSearchEntry(), kind = RecordKind.GUIDE)
        }

        // Study cards: what you have actually been asked. Searching them is how "wasn't there a question
        // about bleach dilution?" gets answered without re-reading the guide it came from.
        //
        // ⚠️ `STUDY`, not `KNOWLEDGE`, and that is a user-visible correction rather than tidying. The
        // label is drawn twice — as the section heading and in the corpus line — so these rows were
        // headed DOCUMENT and this machine, which holds no documents at all, reported "14 documents on
        // this machine". It stayed invisible while the phone emitted `KNOWLEDGE` for nothing; now that
        // it emits real ingested documents, one member cannot be labelled truthfully for both.
        //
        // ⚠️ And `cards()`, which LOADS, not `items.value`, which reads a flow that is empty until
        // something else has caused a load. Measured before the change: a fresh store over a saved
        // deck returned five cards warm and ZERO cold, so opening SEARCH first thing after launch
        // found none of them — silently, and only on that path.
        runCatching { study.cards() }.getOrNull()?.forEach { item ->
            out += DeviceSearch.of(
                id = item.question.id,
                kind = RecordKind.STUDY,
                title = item.question.guideTitle + " ▸ " + item.question.heading,
                body = item.question.prompt + " " + item.question.answer,
            )
        }

        // What the user wrote themselves. `load()` rather than the published flow, for the reason
        // `StudyStore.cards` sets out at length: a flow is empty until something causes a load, and
        // the search index runs before anything has opened either screen.
        //
        // ⚠️ A store that cannot read its file yields nothing here, which is the same shape as an
        // empty one. That is the index's stated contract — a failing source costs its own kind and
        // nothing else — and both stores carry a `loadFailed` their own screens surface, so the
        // reader is not left with no way to find out.
        runCatching { notes.load() }.getOrNull()?.forEach { n ->
            out += DeviceSearch.of(
                n.id, RecordKind.NOTE, n.title, n.body.take(DeviceSearch.BODY_CHARS), n.createdMs,
            )
        }

        runCatching { diary.load() }.getOrNull()?.forEach { d ->
            out += DeviceSearch.of(
                d.id, RecordKind.DIARY, d.title, d.body.take(DeviceSearch.BODY_CHARS), d.createdMs,
            )
        }

        return out
    }

    /**
     * Every screen this machine has, as records — so the box can answer "take me there" as well as
     * "what should I read".
     *
     * The vocabulary is [DESK_GROUPS]' own: the label someone sees in the directory, the description
     * beside it, and the words that entry already lists for people who call it something else. None
     * of it is new, and none of it is written twice — the directory stays the one place a screen is
     * named, which is what makes adding a screen still one entry and one branch.
     *
     * ⚠️ **Searched SEPARATELY from [records], and that is a deliberate departure from the phone —
     * measured, not stylistic.** [DeviceSearch.search] caps each kind at four places and fills the
     * rest ONLY when everything that matched is a single kind. This machine's corpus is almost
     * entirely guides, so a guides-only query takes that fill today and comes back with up to twelve;
     * the moment one screen also matched, the same query would come back with four. Cutting the
     * library answer by two thirds to make room for one row is the wrong trade on the screen whose
     * own description is *"Find a page, or a study card, by what you need"*. Two searches over
     * disjoint corpora make "nothing is displaced" true by construction rather than by a measurement
     * somebody has to keep re-running.
     *
     * ⚠️ **The search terms go in `headings`, not `summary`, and the split is load-bearing.** A
     * result row renders the summary, so putting them there would print *"Internet radio — near you,
     * starred, and always on music stream station listen fm somafm audio"* under RADIO. `headings` is
     * matched (at a heavier weight than summary) and never drawn — exactly how a guide is indexed by
     * [dev.mascwa.pulse.core.telemetry.toSearchEntry], so one field means one thing on both.
     *
     * ⚠️ The phone filters a Settings record's body against its own title; that is **not** done here,
     * and copying it would be cargo-culting a fix whose reason does not hold. It exists because a
     * keyword list repeats its own title *by construction* and would otherwise outrank the screen it
     * describes. These descriptions are human prose that mostly does not repeat the label, and the
     * only thing a screen record competes with is another screen record — where a self-repeat, as in
     * RADIO's, ranks the right answer first.
     */
    fun screenRecords(): List<DeviceSearch.Record> =
        DESK_GROUPS.flatMap { group ->
            group.entries.map { e ->
                DeviceSearch.Record(
                    entry = GuideSearch.Entry(
                        // The enum name, so [screenOf] can turn a result back into somewhere to go.
                        id = e.screen.name,
                        title = e.label,
                        category = group.label,
                        summary = e.description,
                        headings = e.searchTerms,
                    ),
                    kind = RecordKind.FEATURE,
                )
            }
        }

    /**
     * The screen a [screenRecords] result points at, or null for anything else.
     *
     * Defensive rather than load-bearing — the ids are built from [Screen] a few lines up, so this
     * cannot miss — but a row that silently went nowhere would be worse than one that is not drawn.
     */
    fun screenOf(result: DeviceSearch.Result): Screen? =
        Screen.entries.firstOrNull { it.name == result.id }
}
