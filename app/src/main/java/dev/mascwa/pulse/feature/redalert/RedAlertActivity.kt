package dev.mascwa.pulse.feature.redalert

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.mascwa.pulse.core.telemetry.CapAlerts
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The screen a genuine emergency takes over the phone with.
 *
 * Mirrors the lock-screen behaviour of the app's other takeovers (`setShowWhenLocked`,
 * `setTurnScreenOn`) and adds `FLAG_KEEP_SCREEN_ON`, because a warning that dims out thirty seconds
 * in has failed at the one job it has.
 *
 * ⚠️ **Back does not dismiss it, and that is the single deliberate difference from every other
 * screen in this app.** Everything else here is escapable by design; this is not, until the
 * acknowledge button is pressed. A warning that can be swiped away without being read is a warning
 * that will be swiped away without being read. There is exactly one way out and it is a button that
 * says what it does.
 *
 * The alarm belongs to the Activity's lifetime rather than to a service, so it cannot outlive the
 * screen: whatever kills this — acknowledge, the system, a crash — stops the sound with it.
 */
class RedAlertActivity : ComponentActivity() {

    private var klaxon: EmergencyKlaxon? = null

    /**
     * The alert currently on screen.
     *
     * ⚠️ **State, not `intent`, because this Activity is `singleTask`.** A second alert arriving
     * while one is up does not create a new instance — it is delivered to this one through
     * [onNewIntent], `onCreate` does not run again, and `getIntent()` keeps returning the ORIGINAL.
     * With the screen read straight off `intent` at composition time, that meant a tornado warning
     * arriving behind a flood warning was silently swallowed: the alarm kept sounding, the screen
     * kept showing the older, possibly milder hazard, and nothing anywhere said a second one had
     * come in. Holding it as state and updating it is what makes the newer alert appear.
     */
    private var current by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = Unit // see the class KDoc
            },
        )

        current = intent
        klaxon = EmergencyKlaxon(this).also { it.start() }

        setContent {
            val shown = current ?: intent
            RedAlertScreen(
                condition = shown.getStringExtra(EXTRA_CONDITION)?.takeIf { it.isNotBlank() } ?: "RED ALERT",
                hazard = shown.getStringExtra(EXTRA_HAZARD)?.takeIf { it.isNotBlank() } ?: "Emergency alert",
                area = shown.getStringExtra(EXTRA_AREA).orEmpty(),
                timing = CapAlerts.timing(
                    shown.getStringExtra(EXTRA_URGENCY),
                    shown.getStringExtra(EXTRA_CERTAINTY),
                ),
                remaining = shown.getStringExtra(EXTRA_REMAINING)?.takeIf { it.isNotBlank() },
                instruction = shown.getStringExtra(EXTRA_INSTRUCTION)?.takeIf { it.isNotBlank() },
                source = shown.getStringExtra(EXTRA_SOURCE)?.takeIf { it.isNotBlank() } ?: "the issuing agency",
                // Keyed on the alert, so a newer one stamps its own arrival rather than inheriting
                // the time the first one came in.
                receivedAt = remember(shown) { CLOCK.format(Date(System.currentTimeMillis())) },
                onAcknowledge = {
                    klaxon?.stop()
                    finish()
                },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        current = intent
        // Already sounding in the ordinary case, and `start()` is a no-op then. It matters when the
        // alarm had been stopped: a second emergency has to be audible again, not merely visible.
        klaxon?.start()
    }

    override fun onDestroy() {
        // Belt and braces: acknowledge already stops it, but the sound must not survive this screen
        // by any route at all.
        klaxon?.stop()
        klaxon = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CONDITION = "condition"
        const val EXTRA_HAZARD = "hazard"
        const val EXTRA_AREA = "area"
        const val EXTRA_URGENCY = "urgency"
        const val EXTRA_CERTAINTY = "certainty"
        const val EXTRA_REMAINING = "remaining"
        const val EXTRA_INSTRUCTION = "instruction"
        const val EXTRA_SOURCE = "source"

        private val CLOCK = SimpleDateFormat("HH:mm", Locale.US)

        /**
         * Build the launch intent.
         *
         * `NEW_TASK` with no `CLEAR_TASK`: this must be able to start from a service, but it has no
         * business destroying the back stack of whatever the user was doing — that was the exact
         * defect in the old breaking-news takeover, and repeating it here would be worse, because
         * this screen appears at the least convenient moment by definition.
         */
        fun intent(
            context: Context,
            condition: String,
            hazard: String,
            area: String,
            urgency: String?,
            certainty: String?,
            remaining: String?,
            instruction: String?,
            source: String,
        ): Intent = Intent(context, RedAlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(EXTRA_CONDITION, condition)
            putExtra(EXTRA_HAZARD, hazard)
            putExtra(EXTRA_AREA, area)
            putExtra(EXTRA_URGENCY, urgency)
            putExtra(EXTRA_CERTAINTY, certainty)
            putExtra(EXTRA_REMAINING, remaining)
            putExtra(EXTRA_INSTRUCTION, instruction)
            putExtra(EXTRA_SOURCE, source)
        }
    }
}
