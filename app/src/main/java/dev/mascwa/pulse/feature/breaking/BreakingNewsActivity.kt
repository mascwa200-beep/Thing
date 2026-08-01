package dev.mascwa.pulse.feature.breaking

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.core.app.NotificationManagerCompat
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.core.util.openUrl

/**
 * The full-screen BREAKING NEWS takeover — the app force-opening a dedicated, ad-free, cinematic page on a
 * major detected event. Mirrors the survival check-in/lockout activities: shows over the lock screen
 * (`setShowWhenLocked`/`setTurnScreenOn`), reads its topic from the Intent, pulls aggregated coverage off
 * the [PulseApplication] container, and clears its own full-screen-intent notification on dismiss.
 */
class BreakingNewsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)   // show over the keyguard, instantly
            setTurnScreenOn(true)     // wake the screen
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { clearAndFinish() }
        })

        val headline = intent.getStringExtra(EXTRA_HEADLINE)?.takeIf { it.isNotBlank() } ?: "Breaking News"
        val query = intent.getStringExtra(EXTRA_QUERY)?.takeIf { it.isNotBlank() } ?: headline
        val repo = (application as PulseApplication).container.breakingCoverageRepository

        setContent {
            BreakingNewsScreen(
                headline = headline,
                coverage = { force -> repo.coverage(query, force) },
                onOpenUrl = { url -> runCatching { openUrl(this, url) } },
                onClose = { clearAndFinish() },
            )
        }
    }

    private fun clearAndFinish() {
        runCatching { NotificationManagerCompat.from(this).cancel(NOTIF_ID) }
        finish()
    }

    companion object {
        const val EXTRA_HEADLINE = "breaking_headline"
        const val EXTRA_QUERY = "breaking_query"
        const val NOTIF_ID = 1003 // distinct from breaking (1001) / emergency (1002)
    }
}
