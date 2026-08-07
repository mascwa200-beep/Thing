package dev.mascwa.pulse.remote

import android.content.Context
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dev.mascwa.pulse.data.radio.DEFAULT_STATIONS
import dev.mascwa.pulse.di.AppContainer
import dev.mascwa.pulse.feature.tacnet.RadioController
import dev.mascwa.pulse.notifications.BriefEngine
import dev.mascwa.pulse.notifications.RefreshWorker

/**
 * The side effects behind the non-toggle commands, kept out of [RemoteCommandExecutor] so that class stays
 * a readable one-screen map of what a remote peer can do.
 *
 * Each is best-effort and never throws: a remote command failing should return "no" to the desktop, not
 * take down the listener.
 */
object RemoteActions {

    /**
     * Runs the periodic refresh once, right now. Enqueued as one-time work rather than called inline so it
     * runs under WorkManager with the same setup as the scheduled pass — and so a slow refresh cannot hold
     * the socket open while the desktop waits for a reply.
     */
    fun refreshNow(context: Context) {
        runCatching {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<RefreshWorker>().addTag(RefreshWorker.UNIQUE_NAME).build(),
            )
        }
    }

    /** Republishes the situation board immediately. */
    suspend fun publishBrief(context: Context, container: AppContainer) {
        runCatching {
            val settings = container.settingsRepository.current()
            BriefEngine.publish(context, container, settings)
        }
    }

    fun stopRadio(context: Context) {
        runCatching { RadioController.stop(context) }
    }

    /**
     * Plays a station by id. Only stations already known to this phone — a favourite or a curated one —
     * can be started, so the command carries an identifier to look up rather than a URL to fetch. That
     * keeps a remote peer from pointing playback at an arbitrary address.
     */
    suspend fun playRadio(context: Context, container: AppContainer, stationName: String): Boolean =
        runCatching {
            // Favourites first, then the curated defaults — otherwise this could never succeed on a phone
            // whose favourites list is still empty, which is the default state.
            val known = container.settingsRepository.current().favoriteRadio + DEFAULT_STATIONS
            val station = known.firstOrNull { it.name.equals(stationName, ignoreCase = true) }
                ?: return false
            RadioController.play(context, station)
            true
        }.getOrDefault(false)
}
