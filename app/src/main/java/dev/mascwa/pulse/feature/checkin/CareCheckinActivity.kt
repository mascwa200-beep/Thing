package dev.mascwa.pulse.feature.checkin

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import dev.mascwa.pulse.core.telemetry.CareCheckin
import dev.mascwa.pulse.core.telemetry.CareSchedule
import kotlinx.coroutines.launch

/**
 * A smart self-care CHECK-IN as a full-screen takeover (over the lock screen). Unlike the penalty gate this is
 * a PROMPT, not a lock: it asks the one thing that needs doing most right now (brush / floss / water / …, from
 * [dev.mascwa.pulse.core.telemetry.CareSchedule]) and you answer **DONE** (tends the need + records it, so the
 * follow-up in the sequence can come due — you ate → brush → floss) or **NOT YET** (a nudge; it'll ask again
 * next tick). Always answerable — never a trap.
 *
 * [EXTRA_STRICT] carries the merged former "aggressive" mode (the owner's `aggressiveCheckin` switch): when
 * true and the device-owner lockout is armed, answering NOT YET escalates straight into [LockoutActivity]
 * instead of just re-asking later — the harder-to-dismiss behaviour the old standalone aggressive check-in
 * used to provide, now living on this one merged scheduler instead of a second parallel system.
 */
class CareCheckinActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* swallow — answer DONE or NOT YET */ }
        })

        val checkin = runCatching { CareCheckin.valueOf(intent.getStringExtra(EXTRA_CHECKIN) ?: "") }.getOrNull()
        if (checkin == null) { finish(); return }
        val strict = intent.getBooleanExtra(EXTRA_STRICT, false)
        val container = (application as PulseApplication).container
        val store = container.careCheckinStore

        fun clearAndFinish() {
            runCatching { NotificationManagerCompat.from(this).cancel(NOTIF_ID) }
            finish()
        }

        suspend fun maybeEscalate(): Boolean {
            if (!strict) return false
            val armed = runCatching { container.settingsRepository.current().notifications.lockoutEnabled }.getOrDefault(false)
            if (!armed) return false
            // Only escalate check-ins the sensors can actually corroborate — locking the phone over something
            // unsensable (FLOSS, REST) would just release itself on the very next watchdog tick, a pointless
            // lock/unlock flicker rather than a real hold.
            if (CareSchedule.verifyActivities(checkin).isEmpty()) return false
            runCatching {
                startActivity(
                    Intent(this, LockoutActivity::class.java).apply {
                        putExtra(LockoutActivity.EXTRA_CHECKIN, checkin.name)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            }
            return true
        }

        setContent {
            CareCheckinScreen(
                checkin = checkin,
                // Verify the claim against sensed evidence; return true (credited) or false (bounced → insist).
                onVerifyDone = { store.completeVerified(checkin).let { CareSchedule.credits(it) } },
                onCredited = { clearAndFinish() },
                onNotYet = {
                    lifecycleScope.launch {
                        runCatching { store.markAsked(checkin) }
                        maybeEscalate()
                        clearAndFinish()
                    }
                },
            )
        }
    }

    companion object {
        const val EXTRA_CHECKIN = "care_checkin"
        /** Strict mode (the merged former "aggressive" switch) — see class doc. */
        const val EXTRA_STRICT = "care_checkin_strict"
        /** Fixed id so a new check-in replaces the last and the activity can clear it on answer. */
        const val NOTIF_ID = 91_850
    }
}

@Composable
private fun CareCheckinScreen(
    checkin: CareCheckin,
    onVerifyDone: suspend () -> Boolean,
    onCredited: () -> Unit,
    onNotYet: () -> Unit,
) {
    val cyan = Color(0xFF5AD1FF)
    val amber = Color(0xFFE0B341)
    val dim = Color(0xFF7C8A93)
    val scope = rememberCoroutineScope()
    // "Sensors didn't catch that" — set when a claim isn't corroborated; the prompt insists (still escapable).
    var bounced by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val accent = if (bounced) amber else cyan
    val onDone: () -> Unit = {
        if (!busy) {
            busy = true
            scope.launch {
                val credited = runCatching { onVerifyDone() }.getOrDefault(true)
                if (credited) onCredited() else { bounced = true; busy = false }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF04070A)).padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Text(
                if (bounced) "SENSORS DIDN'T CATCH THAT" else "SELF-CARE CHECK-IN",
                color = accent, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 2.sp,
                textAlign = TextAlign.Center,
            )
            Text(
                checkin.label.uppercase(),
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 30.sp, textAlign = TextAlign.Center,
            )
            Text(
                if (bounced) "Do it now — properly — then confirm. It'll only count once it's actually seen."
                else checkin.prompt,
                color = dim, fontSize = 15.sp, textAlign = TextAlign.Center,
            )

            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(accent.copy(alpha = 0.16f))
                    .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    .clickable(enabled = !busy, onClick = onDone).padding(vertical = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (busy) "CHECKING…" else if (bounced) "✓ I DID IT — RE-CHECK" else "✓ DONE",
                    color = accent, fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = 1.sp,
                )
            }
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .border(1.dp, dim.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .clickable(onClick = onNotYet).padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("not yet", color = dim, fontSize = 14.sp)
            }
        }
    }
}
