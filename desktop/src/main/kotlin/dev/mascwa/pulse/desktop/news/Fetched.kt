package dev.mascwa.pulse.desktop.news

/**
 * A payload plus where it came from and when.
 *
 * The Android app has carried this shape since it was written, and the desktop did not: its repository
 * read the cache's `savedAtMs`, took `.value.articles`, and dropped the rest at the boundary. That is
 * what made the fallback silent — a request could fail, the last stored headlines could be served in its
 * place, and nothing downstream had any way to know either fact.
 *
 * [refreshFailed] is deliberately distinct from [fromCache]. "This came from disk" and "the network was
 * tried and did not answer" are different things to tell someone, and collapsing them is precisely the
 * defect being fixed here and on the phone.
 */
data class Fetched<T>(
    val data: T,
    val fromCache: Boolean,
    val refreshFailed: Boolean = false,
    val timestampEpochMs: Long = System.currentTimeMillis(),
)
