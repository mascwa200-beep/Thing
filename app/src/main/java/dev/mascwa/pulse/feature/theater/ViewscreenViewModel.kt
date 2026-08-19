package dev.mascwa.pulse.feature.theater

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.telemetry.MediaItem
import dev.mascwa.pulse.core.telemetry.MediaResolution
import dev.mascwa.pulse.core.telemetry.SponsorSegments
import dev.mascwa.pulse.core.telemetry.TheaterModel
import dev.mascwa.pulse.data.media.MediaBrowser
import dev.mascwa.pulse.data.news.NewsCategory
import dev.mascwa.pulse.di.AppContainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * The Theater: browse, search and watch — no links needed.
 *
 * Discovery happens HERE, inside the app: shelves fill with zero typing (what you were watching,
 * videos about today's stories, videos your feeds are already linking to, curated subjects, and
 * what is saved on this device), a search box covers everything else, and a tap plays in place.
 * The pasted-address flow survives only as a demoted power-user affordance — it is no longer how
 * the feature is used.
 *
 * The heavy machinery all lives elsewhere and is deliberately not duplicated here — flat listing in
 * [MediaBrowser] (one cheap round trip per shelf, nothing resolved), extraction in
 * [dev.mascwa.pulse.data.media.MediaExtractor] (Python-side classification), the skip policy in the
 * CI-tested [SponsorSegments], the shelf/ledger/query rules in the CI-tested [TheaterModel],
 * playback in [OnDemandController]. This class sequences them.
 *
 * ⚠️ **The skip database is asked only when the user has switched skipping on** — the setting gates
 * the *network request*, not just the seeks. A privacy toggle that still phones out and merely
 * ignores the answer would not be a privacy toggle.
 *
 * ⚠️ **Shelves load SEQUENTIALLY, not in parallel.** Every shelf is one network round trip through
 * the one embedded interpreter; firing six at once contends a single-threaded resource for no
 * gain. They publish incrementally, so the first shelf is on screen while the last still loads,
 * and every shelf is optional — a refused shelf becomes a sentence, never a blank screen.
 */
class ViewscreenViewModel(private val c: AppContainer) : ViewModel() {

    /** What the resolve step is doing, distinct from the player's own state. */
    sealed interface Resolve {
        data object Idle : Resolve
        data object Working : Resolve
        data class Refused(val reason: MediaResolution.Reason, val detail: String) : Resolve
        data class Ready(
            val item: MediaItem,
            /** Usable (merged, policy-filtered) segments; empty when skipping is off or none exist. */
            val segments: List<SponsorSegments.Segment>,
            /** Whether the database was asked at all, so the screen can say "skipping off" honestly. */
            val skippingOn: Boolean,
        ) : Resolve
    }

    /** One horizontal shelf on the browse surface. */
    data class ShelfRow(val id: String, val label: String, val items: List<MediaItem>)

    /** The whole browse surface, published incrementally as each shelf loads. */
    data class Shelves(
        val continueWatching: List<TheaterModel.Viewing> = emptyList(),
        val rows: List<ShelfRow> = emptyList(),
        val harvested: List<File> = emptyList(),
        val loading: Boolean = true,
        /** The first network refusal's sentence — one honest line instead of N blank shelves. */
        val note: String? = null,
    )

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    private val _resolve = MutableStateFlow<Resolve>(Resolve.Idle)
    val resolve: StateFlow<Resolve> = _resolve.asStateFlow()

    private val _shelves = MutableStateFlow(Shelves())
    val shelves: StateFlow<Shelves> = _shelves.asStateFlow()

    private val _search = MutableStateFlow("")
    val search: StateFlow<String> = _search.asStateFlow()

    private val _searchResults = MutableStateFlow<List<MediaItem>>(emptyList())
    val searchResults: StateFlow<List<MediaItem>> = _searchResults.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    /** Non-null while a typed search has been answered with a refusal — the honest sentence. */
    private val _searchNote = MutableStateFlow<String?>(null)
    val searchNote: StateFlow<String?> = _searchNote.asStateFlow()

    // The player's own flows, re-exposed so the screen has one view model to watch.
    val playback = OnDemandController.state
    val progress = OnDemandController.progress
    val skipNote = OnDemandController.skipNote

    /** The harvester's readout — a download the held volume key (or the button) started. */
    val harvest = c.mediaHarvester.state

    private var typingJob: Job? = null
    private var shelvesStarted = false

    /** The armed resume seek, if any — cancelled by every newer play and by stop. */
    private var resumeJob: Job? = null

    /**
     * Monotonic tap counter. Every play entry point bumps it; a resolve coroutine that comes back
     * to find a newer generation was superseded by a later tap and must not publish or play —
     * without this, the SLOWER of two resolves wins over the user's last choice.
     */
    private var playGeneration = 0

    init {
        // Adopt a session already on the player (audio kept playing while the screen was away, per
        // its keep-alive design) so the transport renders instead of an Idle screen over live
        // sound. Segments/skip readout are not reconstructable here; the controller keeps applying
        // its own skip set regardless, so only the readout line is conservative.
        playback.value.item?.let { _resolve.value = Resolve.Ready(it, emptyList(), skippingOn = false) }
        loadShelves()
    }

    /** Invalidate every in-flight play: the armed resume seek and any unresolved tap. */
    private fun supersedePlays(): Int {
        resumeJob?.cancel()
        resumeJob = null
        return ++playGeneration
    }

    // ------------------------------------------------------------------ shelves

    /** Load (or reload) the browse surface. Local shelves land instantly; network ones stream in. */
    fun loadShelves(force: Boolean = false) {
        if (shelvesStarted && !force) return
        shelvesStarted = true
        viewModelScope.launch {
            // Local, instant: the resume ledger and what is saved on this device.
            c.viewingLedger.ensureLoaded()
            _shelves.value = Shelves(
                continueWatching = c.viewingLedger.ledger.value,
                harvested = c.mediaHarvester.harvested(),
                loading = true,
            )

            val rows = mutableListOf<ShelfRow>()
            var note: String? = null
            fun publish(loading: Boolean) {
                _shelves.value = _shelves.value.copy(rows = rows.toList(), loading = loading, note = note)
            }

            // ⚠️ ORDER IS TIME-TO-FIRST-CARD. The curated shelves go FIRST: they need no other
            // subsystem — a fixed query each — so on a fresh install (empty ledger, empty caches)
            // the first browsable card lands after ONE search round trip instead of after two
            // social fetches + a news fetch that mostly feed shelves the videoId filter then
            // empties. Stories and feeds follow; each still publishes as it lands.
            for (shelf in TheaterModel.CATEGORY_SHELVES) {
                when (val b = c.mediaBrowser.search(shelf.query, limit = SHELF_LIMIT)) {
                    is MediaBrowser.Browse.Page -> {
                        rows += ShelfRow(shelf.id, shelf.label, b.items)
                        publish(loading = true)
                    }
                    is MediaBrowser.Browse.Refused ->
                        if (note == null) note = MediaResolution.say(b.reason)
                }
            }
            publish(loading = true)

            // ON TODAY'S STORIES — the trending replacement: search for the app's own top headlines.
            val headlines = runCatching {
                c.newsRepository.fetchCategory(NewsCategory.TOP, force = false).data.map { it.title }
            }.getOrDefault(emptyList())
            val queries = TheaterModel.storyQueries(headlines, max = STORY_QUERIES)
            val storyItems = mutableListOf<MediaItem>()
            for (q in queries) {
                when (val b = c.mediaBrowser.search(q, limit = STORY_LIMIT)) {
                    is MediaBrowser.Browse.Page -> storyItems += b.items
                    is MediaBrowser.Browse.Refused ->
                        if (note == null) note = MediaResolution.say(b.reason)
                }
            }
            if (storyItems.isNotEmpty()) {
                // Above the curated rows: today's stories are the fresher read once they exist.
                rows.add(0, ShelfRow("stories", "ON TODAY'S STORIES", storyItems.distinctBy { it.pageUrl }))
            }
            publish(loading = true)

            // FROM YOUR FEEDS — links the social feeds already carry, no new fetch class. Cached
            // reads; a cold cache may fetch, which is why this loads LAST: on a fresh install the
            // videoId filter usually leaves it empty, so it must not gate anything visible.
            val feedItems = fromFeeds()
            if (feedItems.isNotEmpty()) rows.add(0, ShelfRow("feeds", "FROM YOUR FEEDS", feedItems))
            publish(loading = false)
        }
    }

    /**
     * Retry after a refused surface. [MediaBrowser] never memoises refusals precisely so that a
     * retry gets fresh answers — this is the affordance that invokes one (before it, the force
     * parameter had zero callers and the only retry was leaving and re-entering the screen).
     */
    fun retryShelves() = loadShelves(force = true)

    /** Video links sitting in the social feeds right now, as unresolved items. */
    private suspend fun fromFeeds(): List<MediaItem> {
        val social = buildList {
            runCatching { addAll(c.socialRepository.lemmy(force = false).data.items) }
            runCatching { addAll(c.socialRepository.hackerNews(force = false).data.items) }
        }
        return social.mapNotNull { item ->
            val id = TheaterModel.videoId(item.url)
            if (id.isBlank()) return@mapNotNull null
            MediaItem(
                id = id,
                title = item.title,
                streamUrl = "",
                audioUrl = "",
                uploader = item.source,
                thumbnailUrl = item.thumbnail.orEmpty(),
                pageUrl = item.url,
            )
        }.distinctBy { it.id }.take(FEED_LIMIT)
    }

    // ------------------------------------------------------------------ search

    /** Debounced search-as-you-type; blank clears. The SearchViewModel pattern. */
    fun setSearch(text: String) {
        _search.value = text
        typingJob?.cancel()
        val q = text.trim()
        if (q.isEmpty()) {
            _searchResults.value = emptyList()
            _searching.value = false
            _searchNote.value = null
            return
        }
        typingJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            _searching.value = true
            _searchNote.value = null
            when (val b = c.mediaBrowser.search(q, limit = SEARCH_LIMIT)) {
                is MediaBrowser.Browse.Page -> _searchResults.value = b.items
                is MediaBrowser.Browse.Refused -> {
                    _searchResults.value = emptyList()
                    _searchNote.value = MediaResolution.say(b.reason)
                }
            }
            _searching.value = false
        }
    }

    // ------------------------------------------------------------------ playback

    /**
     * Play a browsed item: already-resolved items (local files) play directly; everything else
     * resolves its page URL first — the expensive step, paid for exactly the item that was tapped.
     */
    fun playItem(context: Context, item: MediaItem, audioOnly: Boolean = false) {
        if (item.isResolved && item.isFresh(System.currentTimeMillis())) {
            supersedePlays() // an in-flight resolve from an earlier tap must not stomp this
            _resolve.value = Resolve.Ready(item, emptyList(), skippingOn = false)
            OnDemandController.play(context, item, emptyList(), audioOnly = audioOnly)
            return
        }
        playUrl(context, item.pageUrl, audioOnly)
    }

    /** Restart what is on the viewscreen as audio-only, which earns the background keep-alive. */
    fun listenCurrent(context: Context) {
        val ready = resolve.value as? Resolve.Ready ?: return
        val at = progress.value.positionMs // capture BEFORE the restart resets the player
        saveProgress()
        supersedePlays()
        OnDemandController.play(context, ready.item, ready.segments, audioOnly = true)
        if (at > RESUME_MIN_MS) resumeWhenPlaying(ready.item.id, at)
    }

    /** Play a harvested file from this device — works with no network at all. */
    fun playHarvested(context: Context, file: File) {
        supersedePlays()
        val item = MediaItem(
            id = "",
            title = file.nameWithoutExtension,
            streamUrl = file.toURI().toString(),
            expiresAtMs = Long.MAX_VALUE,
        )
        _resolve.value = Resolve.Ready(item, emptyList(), skippingOn = false)
        OnDemandController.play(context, item, emptyList())
    }

    /** Delete a harvested file and refresh the shelf. */
    fun deleteHarvested(file: File) {
        c.mediaHarvester.delete(file)
        _shelves.value = _shelves.value.copy(harvested = c.mediaHarvester.harvested())
    }

    /**
     * Resolve the typed/pasted address and start playback — the demoted direct-address path and
     * the `viewscreen?play=` deep-link both land here.
     */
    fun playFromInput(context: Context, audioOnly: Boolean = false) {
        val url = _input.value.trim()
        if (url.isEmpty()) return
        playUrl(context, url, audioOnly)
    }

    fun setInput(text: String) {
        _input.value = text
    }

    /** Open the Theater on a specific address (the `?play=` route argument). */
    fun playAddress(context: Context, url: String) {
        if (url.isBlank()) return
        _input.value = url
        playUrl(context, url, audioOnly = false)
    }

    private fun playUrl(context: Context, url: String, audioOnly: Boolean) {
        if (url.isBlank()) return
        val gen = supersedePlays()
        _resolve.value = Resolve.Working
        viewModelScope.launch {
            val r = c.mediaExtractor.resolve(url)
            // The user tapped something newer (or STOP) while this resolved: the slower resolve
            // must not win — it neither publishes state nor starts playback.
            if (gen != playGeneration) return@launch
            when (r) {
                is MediaResolution.Refused -> _resolve.value = Resolve.Refused(r.reason, r.detail)
                is MediaResolution.Ready -> {
                    val skippingOn = c.settingsRepository.current().sponsorSkip
                    val segments = if (skippingOn) fetchSegments(url, r.item) else emptyList()
                    if (gen != playGeneration) return@launch // the segments fetch is a second await
                    _resolve.value = Resolve.Ready(r.item, segments, skippingOn)
                    val resumeAt = r.item.id.takeIf { it.isNotBlank() }
                        ?.let { c.viewingLedger.positionOf(it) } ?: 0L
                    OnDemandController.play(context, r.item, segments, audioOnly = audioOnly)
                    rememberViewing(r.item, positionMs = resumeAt)
                    if (resumeAt > RESUME_MIN_MS) resumeWhenPlaying(r.item.id, resumeAt)
                }
            }
        }
    }

    /**
     * Seek to the saved position once THE SAME ITEM is actually playing.
     *
     * [OnDemandController.play] has no start-position parameter and its signature is not widened
     * for this — playback begins, the first frames may show 0:00 for a beat, then the seek lands.
     * Accepted and stated rather than hidden behind a race: seeking before READY is discarded by
     * the player, which is the bug this ordering avoids.
     *
     * ⚠️ The waiter matches on [itemId], not merely on PLAYING, and is cancelled by every newer
     * play ([supersedePlays]) — without both, a resume armed for video A fires into video B tapped
     * moments later, jumping it ten minutes in (or straight past its end). And the seek is
     * ABSOLUTE: a saved position is a place, and anything that moved playback first (a sponsor
     * skip at 0:00) would offset a relative one.
     */
    private fun resumeWhenPlaying(itemId: String, positionMs: Long) {
        resumeJob?.cancel()
        resumeJob = viewModelScope.launch {
            val playing = withTimeoutOrNull(RESUME_WAIT_MS) {
                playback.first { it.status == OnDemandController.Status.PLAYING && it.item?.id == itemId }
            }
            if (playing != null) OnDemandController.seekTo(positionMs)
        }
    }

    /**
     * Fold the current viewing into the resume ledger. Local files (blank id) never enter it.
     *
     * ⚠️ Rides the STORE's own scope, not [viewModelScope] — the last call comes from [onCleared],
     * which runs after the ViewModel's scope is already cancelled, so a launch there would silently
     * never execute and the "save where I was" write would be lost exactly when it matters. The
     * shelf refresh below is cosmetic and may ride the (possibly dead) VM scope.
     */
    private fun rememberViewing(item: MediaItem, positionMs: Long) {
        if (item.id.isBlank() || item.pageUrl.isBlank()) return
        c.viewingLedger.rememberAsync(
            TheaterModel.Viewing(
                id = item.id,
                title = item.title,
                pageUrl = item.pageUrl,
                thumbnailUrl = item.thumbnailUrl,
                positionMs = positionMs,
                durationMs = (item.durationS * 1000).toLong(),
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
        viewModelScope.launch {
            c.viewingLedger.ledger.first { list -> list.any { it.id == item.id } }
            _shelves.value = _shelves.value.copy(continueWatching = c.viewingLedger.ledger.value)
        }
    }

    /** Save where playback stands right now, so leaving mid-video is resumable. */
    private fun saveProgress() {
        val ready = resolve.value as? Resolve.Ready ?: return
        val pos = progress.value.positionMs
        if (pos > RESUME_MIN_MS) rememberViewing(ready.item, positionMs = pos)
    }

    /** Harvest what is on the viewscreen — the button form of the held-volume-key gesture. */
    fun harvestCurrent(): Boolean {
        val item = (resolve.value as? Resolve.Ready)?.item ?: return false
        return c.mediaHarvester.harvest(item)
    }

    /**
     * The usable skip set for this video, or empty.
     *
     * Keyed on the extractor's own id when it has one, else the URL-derived id — the database is
     * keyed on the source's id, and for anything that is not a recognised video URL there is no key
     * and honestly no answer. Best-effort throughout: no data and could-not-ask are the same outcome
     * for playback, which plays the whole video.
     */
    private suspend fun fetchSegments(url: String, item: MediaItem): List<SponsorSegments.Segment> {
        val id = item.id.ifBlank { c.mediaExtractor.videoId(url) }
        if (id.isBlank()) return emptyList()
        val raw = runCatching { c.sponsorBlockRepository.segments(id) }.getOrDefault(emptyList())
        return SponsorSegments.usable(raw, SponsorSegments.Policy())
    }

    fun pause() {
        OnDemandController.pause()
        saveProgress()
    }

    fun resume() = OnDemandController.resume()
    fun seekBy(deltaMs: Long) = OnDemandController.seekBy(deltaMs)

    fun stop(context: Context) {
        saveProgress()
        supersedePlays() // a resolve still in flight must not restart playback after STOP
        OnDemandController.stop(context)
        _resolve.value = Resolve.Idle
    }

    override fun onCleared() {
        // Leaving the screen mid-video is the commonest "I'll finish later" — save it. The write
        // rides the ledger store's OWN scope (see rememberViewing), because viewModelScope is
        // already cancelled by the time onCleared runs.
        saveProgress()
        // A VIDEO session ends with the screen: the player is process-wide, so without this the
        // soundtrack kept playing headless — no notification (only audio-only earns the keep-alive
        // service), no visible transport, and the fresh VM a re-entry builds started Idle so even
        // the STOP button was gone. Audio-only sessions deliberately keep playing; that is their
        // point, and the service's notification carries their Stop.
        val pb = playback.value
        if (pb.item != null && !pb.audioOnly) OnDemandController.stop(c.applicationContext)
        super.onCleared()
    }

    private companion object {
        const val DEBOUNCE_MS = 160L
        const val SEARCH_LIMIT = 24
        const val SHELF_LIMIT = 12
        const val STORY_LIMIT = 6
        const val STORY_QUERIES = 2
        const val FEED_LIMIT = 12

        /** Below this a "resume" is indistinguishable from starting over — don't bother seeking. */
        const val RESUME_MIN_MS = 15_000L

        /** How long to wait for PLAYING before giving up on the resume seek. */
        const val RESUME_WAIT_MS = 20_000L
    }
}
