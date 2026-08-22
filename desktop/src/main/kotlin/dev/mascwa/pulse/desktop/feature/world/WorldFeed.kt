package dev.mascwa.pulse.desktop.feature.world

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.core.telemetry.Freshness
import dev.mascwa.pulse.core.util.Async
import dev.mascwa.pulse.core.util.Fetched
import dev.mascwa.pulse.core.util.load
import dev.mascwa.pulse.desktop.settings.DesktopSettingsStore
import dev.mascwa.pulse.desktop.theme.JetBrainsMono
import dev.mascwa.pulse.desktop.theme.LcarsBusyBar
import dev.mascwa.pulse.desktop.theme.LcarsFrame
import dev.mascwa.pulse.desktop.theme.LcarsGhostButton
import dev.mascwa.pulse.desktop.theme.LcarsHeaderBar
import dev.mascwa.pulse.desktop.theme.Pulse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Where this machine thinks it is, or null.
 *
 * ⚠️ Every screen in this package needs a coordinate and NONE of them can obtain one — a tower PC has
 * no GPS. It comes from settings, which is either an IP guess or a place the user typed. So "we do not
 * know where you are" is an ordinary state here rather than a failure, and each screen says so and
 * points at the settings page instead of showing an empty list.
 */
suspend fun DesktopSettingsStore.here(): Pair<Double, Double>? {
    val s = current()
    val lat = s.latitude ?: return null
    val lon = s.longitude ?: return null
    return lat to lon
}

/**
 * The shape every screen in this package shares: fetch one thing for one coordinate, keep what was
 * last fetched while refreshing, and never wipe a readable page because a refresh failed.
 *
 * Built on the SHARED [Async]/[load] pair rather than a desktop copy — the same folding the phone's
 * view models do, including the part that makes a failed refresh visible instead of silent.
 */
class WorldFeed<T>(
    private val scope: CoroutineScope,
    private val settings: DesktopSettingsStore,
    private val fetch: suspend (lat: Double, lon: Double, force: Boolean) -> Fetched<T>,
) {
    private val _state = MutableStateFlow(Async<T>())
    val state: StateFlow<Async<T>> = _state.asStateFlow()

    /**
     * ⚠️ **Null means "nobody has looked yet", and that is the whole point of the type.**
     *
     * This used to be a `Boolean` initialised to `true`, written only from inside the launched
     * coroutine. So "we have a coordinate" and "nothing has run" were the same value, and a feed that
     * never started was indistinguishable on screen from one that had checked and was fine. That
     * ambiguity is exactly what made a real fault read as an ordinary empty page for as long as it did.
     */
    private val _located = MutableStateFlow<Boolean?>(null)

    /** True with a coordinate, false without one, **null when the question has not been asked yet**. */
    val located: StateFlow<Boolean?> = _located.asStateFlow()

    private var job: Job? = null

    /** Load once, unless something is already there. Safe to call on every visit to the screen. */
    fun ensureLoaded() {
        if (_state.value.hasData || job?.isActive == true) return
        refresh(force = false)
    }

    fun refresh(force: Boolean = true) {
        job?.cancel()
        job = scope.launch {
            // ⚠️ Marked busy BEFORE the first suspension point. Reading the settings file can block on
            // a lock held across disk IO, and until this line existed that whole window rendered as
            // "nothing loaded yet" with the busy bar off — a page that looks finished while it is in
            // fact still starting.
            _state.update { it.copy(loading = true) }
            val here = settings.here()
            _located.value = here != null
            if (here == null) {
                // ⚠️ Explicitly cleared rather than left spinning. A screen that says "loading" forever
                // because nobody ever entered a location is the least helpful thing it could do.
                //
                // `update` rather than `value = value.copy(...)`: that form is a read-modify-write, and a
                // superseded job taking this branch could otherwise clobber a newer one's state.
                _state.update { it.copy(loading = false, error = null) }
                return@launch
            }
            _state.load(force) { f -> fetch(here.first, here.second, f) }
        }
    }
}

/**
 * The chrome every screen in this package draws: a title, whether it is working, how old what is on
 * screen is, and what to do when there is nothing.
 *
 * Written once because six screens want exactly this and a sixth copy of it would drift from the first
 * within a session.
 */
@Composable
fun <T> WorldPanel(
    title: String,
    feed: WorldFeed<T>,
    state: Async<T>,
    /** True with a coordinate, false without one, null when the feed has not looked yet. */
    located: Boolean?,
    trailing: String? = null,
    /** Shown when there is a coordinate and a payload but the payload holds nothing. */
    emptyMessage: String = "Nothing to report.",
    isEmpty: (T) -> Boolean = { false },
    content: @Composable (T) -> Unit,
) {
    val c = Pulse.colors
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LcarsHeaderBar(title, Modifier.weight(1f), trailing = trailing)
            LcarsGhostButton("REFRESH", { feed.refresh() })
        }
        LcarsBusyBar(active = state.loading, modifier = Modifier.fillMaxWidth())

        if (located == false) {
            LcarsFrame(Modifier.fillMaxWidth().padding(top = 10.dp), accent = c.amber) {
                Text(
                    "This machine does not know where it is. Open SETTINGS and either let it guess " +
                        "from your connection or type a latitude and longitude.",
                    fontFamily = JetBrainsMono, fontSize = 12.sp, lineHeight = 17.sp, color = c.ink,
                )
            }
            return@Column
        }

        state.error?.let { err ->
            LcarsFrame(Modifier.fillMaxWidth().padding(top = 10.dp), accent = c.negative) {
                Column {
                    Text(err, fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink)
                    LcarsGhostButton("TRY AGAIN", { feed.refresh() }, Modifier.padding(top = 8.dp))
                }
            }
        }

        // How old what is on screen actually is. `stale` is set both when the answer came from disk and
        // when a refresh was attempted and failed — the second is the case worth saying out loud.
        val freshness = Freshness.assess(
            lastUpdatedMs = state.lastUpdatedEpochMs,
            nowMs = System.currentTimeMillis(),
            online = true,
            servingStored = state.stale,
            refreshFailed = state.stale && state.error != null,
        )
        if (freshness.worthShowing) {
            Text(
                freshness.label,
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.amber,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        val data = state.data
        if (data == null) {
            if (!state.loading && state.error == null) {
                // ⚠️ Three different situations, said differently. "Nothing loaded yet." was printed for
                // all of them, including the one where the feed had never run at all — so a screen that
                // was quietly broken read exactly like a screen that was merely empty.
                val (line, tone) = when (located) {
                    null -> "Waiting to start." to c.muted
                    else -> "Nothing came back, and nothing reported a reason. " +
                        "Press REFRESH; if this persists, MENU \u2192 CRASH CONSOLE will say why." to c.amber
                }
                LcarsFrame(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Text(line, fontFamily = JetBrainsMono, fontSize = 12.sp, lineHeight = 17.sp, color = tone)
                }
            }
        } else if (isEmpty(data)) {
            LcarsFrame(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text(emptyMessage, fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.muted)
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) { content(data) }
        }
    }
}
