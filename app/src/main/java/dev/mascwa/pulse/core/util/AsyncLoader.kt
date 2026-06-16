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
        update { it.copy(loading = false, error = e.toUserMessage()) }
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
