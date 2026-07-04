package dev.mascwa.pulse.feature.checkin

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.core.telemetry.Habit
import dev.mascwa.pulse.core.telemetry.HabitCheckin
import kotlinx.coroutines.launch

/**
 * The aggressive self-care check-in: a full-screen alert that shows **over the lock screen** (alarm-style —
 * `showWhenLocked` + `turnScreenOn`) and **cannot be dismissed** — BACK is swallowed, there's no other
 * affordance — until you ANSWER it (YES / NOT YET). Answering is always available, so it's a firm prompt,
 * never a trap. The answer is cross-referenced with the sensed evidence by [dev.mascwa.pulse.data.game.HabitStore].
 *
 * (The subsequent phone-lockout — locking until sensors confirm you actually did it — is a separate,
 * safeguarded step, not this screen.)
 */
class CheckinActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        // BACK does nothing — the only way out is to answer.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* swallow — answer to continue */ }
        })

        val habit = HabitCheckin.DEFAULTS.firstOrNull { it.activity.name == intent.getStringExtra(EXTRA_ACTIVITY) }
        if (habit == null) { finish(); return }
        val habitStore = (application as PulseApplication).container.habitStore

        setContent {
            CheckinFullScreen(habit) { claimedDone ->
                lifecycleScope.launch {
                    runCatching { habitStore.answer(habit, claimedDone) }
                    runCatching { NotificationManagerCompat.from(this@CheckinActivity).cancel(NOTIF_ID) }
                    finish()
                }
            }
        }
    }

    companion object {
        const val EXTRA_ACTIVITY = "checkin_activity"
        /** Fixed id so a new check-in replaces the last, and the activity can clear it on answer. */
        const val NOTIF_ID = 91_837
    }
}

@Composable
private fun CheckinFullScreen(habit: Habit, onAnswer: (Boolean) -> Unit) {
    val amber = Color(0xFFE0B341)
    val green = Color(0xFF5BFF9B)
    val red = Color(0xFFFF5A6E)
    Box(Modifier.fillMaxSize().background(Color(0xFF04070A)).padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Text("CHECK-IN REQUIRED", color = amber, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 4.sp)
            Text(habit.question, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 30.sp,
                textAlign = TextAlign.Center)
            Text("This won't clear until you answer.", color = Color(0xFF7C8A93), fontSize = 12.sp, textAlign = TextAlign.Center)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BigAnswer("YES", green, Modifier.weight(1f)) { onAnswer(true) }
                BigAnswer("NOT YET", red, Modifier.weight(1f)) { onAnswer(false) }
            }
        }
    }
}

@Composable
private fun BigAnswer(label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.14f))
            .clickable(onClick = onClick).padding(vertical = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 2.sp)
    }
}
