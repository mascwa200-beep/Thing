package dev.mascwa.pulse.core.util

/**
 * A small UI state holder that can simultaneously express "I have data",
 * "I'm (re)loading", "the data I'm showing is stale/cached", and "the last
 * load failed". This lets every screen show cached content instantly while a
 * refresh runs underneath — important for an offline-capable aggregator.
 */
data class Async<T>(
    val data: T? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val stale: Boolean = false,
    val lastUpdatedEpochMs: Long = 0L,
) {
    val hasData: Boolean get() = data != null
    val isInitialLoading: Boolean get() = loading && data == null
    val isError: Boolean get() = error != null && data == null
}

/** Result returned by repositories: the payload plus whether it came from cache. */
data class Fetched<T>(
    val data: T,
    val fromCache: Boolean,
    val timestampEpochMs: Long = System.currentTimeMillis(),
)
