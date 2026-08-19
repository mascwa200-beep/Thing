package dev.mascwa.pulse.feature.theater

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.telemetry.MediaItem
import dev.mascwa.pulse.core.telemetry.MediaResolution
import dev.mascwa.pulse.core.telemetry.SponsorSegments
import dev.mascwa.pulse.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The viewscreen: paste an address, the bundled extractor resolves it, the player plays it.
 *
 * The heavy machinery all lives elsewhere and is deliberately not duplicated here — extraction in
 * [dev.mascwa.pulse.data.media.MediaExtractor] (Python-side classification), the skip policy in the
 * CI-tested [SponsorSegments], playback in [OnDemandController]. This class sequences them:
 * resolve → (optionally) ask the skip database → hand both to the controller.
 *
 * ⚠️ **The skip database is asked only when the user has switched skipping on** — the setting gates
 * the *network request*, not just the seeks. A privacy toggle that still phones out and merely
 * ignores the answer would not be a privacy toggle.
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

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    private val _resolve = MutableStateFlow<Resolve>(Resolve.Idle)
    val resolve: StateFlow<Resolve> = _resolve.asStateFlow()

    // The player's own flows, re-exposed so the screen has one view model to watch.
    val playback = OnDemandController.state
    val progress = OnDemandController.progress
    val skipNote = OnDemandController.skipNote

    /** The harvester's readout — a download the held volume key (or the button) started. */
    val harvest = c.mediaHarvester.state

    fun setInput(text: String) {
        _input.value = text
    }

    /**
     * Resolve the pasted address and start playback.
     *
     * One action rather than resolve-then-play, because there is nothing to decide in between: the
     * resolved item either plays or the refusal is the thing on screen. The intermediate states are
     * still published so the screen can narrate the seconds extraction takes.
     */
    fun playFromInput(context: Context, audioOnly: Boolean = false) {
        val url = _input.value.trim()
        if (url.isEmpty()) return
        _resolve.value = Resolve.Working
        viewModelScope.launch {
            when (val r = c.mediaExtractor.resolve(url)) {
                is MediaResolution.Refused -> _resolve.value = Resolve.Refused(r.reason, r.detail)
                is MediaResolution.Ready -> {
                    val skippingOn = c.settingsRepository.current().sponsorSkip
                    val segments = if (skippingOn) fetchSegments(url, r.item) else emptyList()
                    _resolve.value = Resolve.Ready(r.item, segments, skippingOn)
                    OnDemandController.play(context, r.item, segments, audioOnly = audioOnly)
                }
            }
        }
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

    fun pause() = OnDemandController.pause()
    fun resume() = OnDemandController.resume()
    fun seekBy(deltaMs: Long) = OnDemandController.seekBy(deltaMs)

    fun stop(context: Context) {
        OnDemandController.stop(context)
        _resolve.value = Resolve.Idle
    }
}
