package dev.mascwa.pulse.feature.breaking

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    /**
     * The story currently on screen.
     *
     * ⚠️ **State, not `intent`, because this Activity is `singleTask`.** A second story breaking
     * while this is up does not create a new instance — it arrives at this one through
     * [onNewIntent], `onCreate` does not run again, and `getIntent()` keeps returning the ORIGINAL.
     * Read straight off `intent` at composition time, the newer story was silently swallowed: the
     * takeover stayed on the first headline and nothing said another had come in. `MainActivity`
     * had this same defect fixed in an earlier pass; the two takeovers were left behind.
     */
    private var current by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)   // show over the keyguard, instantly
            setTurnScreenOn(true)     // wake the screen
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { clearAndFinish() }
        })

        current = intent
        val repo = (application as PulseApplication).container.breakingCoverageRepository

        setContent {
            val shown = current ?: intent
            val headline = shown.getStringExtra(EXTRA_HEADLINE)?.takeIf { it.isNotBlank() }
                ?: "Breaking News"
            val query = shown.getStringExtra(EXTRA_QUERY)?.takeIf { it.isNotBlank() } ?: headline
            BreakingNewsScreen(
                headline = headline,
                coverage = { force -> repo.coverage(query, force) },
                onOpenUrl = { url -> runCatching { openUrl(this, url) } },
                onClose = { clearAndFinish() },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        current = intent
    }

    private fun clearAndFinish() {
        runCatching { NotificationManagerCompat.from(this).cancel(NOTIF_ID) }
        finish()
    }

    companion object {
        const val EXTRA_HEADLINE = "breaking_headline"
        const val EXTRA_QUERY = "breaking_query"
        const val NOTIF_ID = dev.mascwa.pulse.notifications.NotifId.TAKEOVER
    }
}
