package dev.mascwa.pulse.core.util

import dev.mascwa.pulse.core.network.HttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.net.UnknownHostException

/**
 * Runs [fetch] and folds the result into an [Async] state, preserving any
 * already-shown data while loading and on error.
 */
suspend fun <T> MutableStateFlow<Async<T>>.load(
    force: Boolean,
    fetch: suspend (Boolean) -> Fetched<T>,
) {
    update { it.copy(loading = true, error = null) }
    try {
        val f = fetch(force)
        update {
            it.copy(
                data = f.data,
                loading = false,
                error = null,
                stale = f.fromCache,
                lastUpdatedEpochMs = f.timestampEpochMs,
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        update {
            it.copy(
                loading = false,
                error = e.toUserMessage(),
                // Keeping the previous data on a failed refresh is deliberate, but it used to be
                // SILENT: screens gate their error on `isError`, which is false while data is present,
                // and nothing set `stale`, so a failed refresh left yesterday's numbers on screen
                // looking exactly like a live reading. Marking it here is what lets the UI say so.
                //
                // `lastUpdatedEpochMs` is deliberately untouched — it still correctly describes when
                // the data being shown was obtained, which is precisely what needs reporting.
                stale = it.data != null || it.stale,
            )
        }
    }
}

fun Throwable.toUserMessage(): String = when (this) {
    is UnknownHostException -> "No internet connection."
    is HttpException -> when (code) {
        429 -> "Rate limited by the data source. Try again shortly."
        in 500..599 -> "The data source is temporarily unavailable."
        else -> "Request failed (HTTP $code)."
    }
    is IOException -> "Network error. Check your connection."
    else -> message?.takeIf { it.isNotBlank() } ?: "Something went wrong."
}
