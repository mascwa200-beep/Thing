package dev.mascwa.pulse.feature.redalert

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
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

        val hazard = intent.getStringExtra(EXTRA_HAZARD)?.takeIf { it.isNotBlank() } ?: "Emergency alert"
        val condition = intent.getStringExtra(EXTRA_CONDITION)?.takeIf { it.isNotBlank() } ?: "RED ALERT"

        klaxon = EmergencyKlaxon(this).also { it.start() }

        setContent {
            RedAlertScreen(
                condition = condition,
                hazard = hazard,
                area = intent.getStringExtra(EXTRA_AREA).orEmpty(),
                timing = CapAlerts.timing(
                    intent.getStringExtra(EXTRA_URGENCY),
                    intent.getStringExtra(EXTRA_CERTAINTY),
                ),
                remaining = intent.getStringExtra(EXTRA_REMAINING)?.takeIf { it.isNotBlank() },
                instruction = intent.getStringExtra(EXTRA_INSTRUCTION)?.takeIf { it.isNotBlank() },
                source = intent.getStringExtra(EXTRA_SOURCE)?.takeIf { it.isNotBlank() } ?: "the issuing agency",
                receivedAt = CLOCK.format(Date(System.currentTimeMillis())),
                onAcknowledge = {
                    klaxon?.stop()
                    finish()
                },
            )
        }
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
