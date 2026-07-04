package dev.mascwa.pulse.feature.checkin

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.core.telemetry.ActivitySensing
import dev.mascwa.pulse.core.telemetry.ClaimVerdict
import dev.mascwa.pulse.core.telemetry.Habit
import dev.mascwa.pulse.core.telemetry.HabitCheckin
import dev.mascwa.pulse.security.DevicePolicyController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The self-care **lockout** — after you dodge a check-in (said "not yet", or claimed done but the sensors
 * caught the lie), this pins the phone (device-owner kiosk lock task) with a "go do it" screen, and releases
 * the moment the sensors confirm you actually did it. Opt-in, off by default.
 *
 * Safe by construction — it can NEVER trap you:
 *  - **Guaranteed auto-release**: it unconditionally unlocks after [MAX_LOCK_MS] no matter what (the hard net).
 *  - **Sensor release**: unlocks + credits the moment [ActivitySensing] confirms the task from the evidence log.
 *  - **Emergency**: a prominent button opens the dialer (the dialer is kept on the lock-task allow-list).
 *  - **Override**: a deliberate 5-second hold releases it (a friction escape for a broken detection).
 *  - If Pulse isn't a Device Owner, there's no kiosk pin at all — it degrades to a full-screen nag.
 */
class LockoutActivity : ComponentActivity() {

    private lateinit var habit: Habit
    private var released = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* swallow — do the task or wait it out */ }
        })

        val h = HabitCheckin.DEFAULTS.firstOrNull { it.activity.name == intent.getStringExtra(EXTRA_ACTIVITY) }
        if (h == null) { finish(); return }
        habit = h

        // Enter device-owner lock task (kiosk pin) — best-effort; keep the DIALER on the allow-list so the
        // emergency button can always launch it. No-op (degrades to a plain full-screen nag) if not DO.
        runCatching {
            val dpc = DevicePolicyController(this)
            if (dpc.isDeviceOwner()) {
                val dialer = runCatching { getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()
                dpc.setLockTaskPackages(listOfNotNull(packageName, dialer))
                startLockTask()
            }
        }

        val deadline = SystemClock.elapsedRealtime() + MAX_LOCK_MS
        setContent {
            LockoutScreen(
                habit = habit,
                deadlineElapsed = deadline,
                onEmergency = { runCatching { startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                onOverride = { release(sensedDone = false) },
            )
        }

        // Watchdog: release the instant the task is SENSED done, else unconditionally at the deadline.
        lifecycleScope.launch {
            val container = (application as PulseApplication).container
            while (isActive && !released) {
                val since = System.currentTimeMillis() - habit.everyMs
                val evidence = runCatching { container.activityEvidenceStore.recent(since) }.getOrDefault(emptyList())
                if (ActivitySensing.verifyClaim(habit.activity, evidence, since) != ClaimVerdict.NONE) {
                    release(sensedDone = true)
                    break
                }
                if (SystemClock.elapsedRealtime() >= deadline) {
                    release(sensedDone = false)
                    break
                }
                delay(4_000)
            }
        }
    }

    /** Unlock. [sensedDone] = the sensors confirmed it, so credit the need; the override/timeout doesn't. */
    private fun release(sensedDone: Boolean) {
        if (released) return
        released = true
        if (sensedDone) {
            val container = (application as PulseApplication).container
            lifecycleScope.launch { runCatching { container.habitStore.answer(habit, true) } }
        }
        runCatching { stopLockTask() }
        finish()
    }

    companion object {
        const val EXTRA_ACTIVITY = "lockout_activity"
        /** The hard auto-release net — the lockout NEVER holds longer than this, whatever happens. */
        private const val MAX_LOCK_MS = 10 * 60_000L
        /** How long the override must be held — deliberate friction so it isn't a one-tap bail-out. */
        const val OVERRIDE_HOLD_MS = 5_000L
    }
}

@Composable
private fun LockoutScreen(habit: Habit, deadlineElapsed: Long, onEmergency: () -> Unit, onOverride: () -> Unit) {
    val red = Color(0xFFFF5A6E)
    val amber = Color(0xFFE0B341)
    val dim = Color(0xFF7C8A93)

    var remainingMs by remember { mutableStateOf((deadlineElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0)) }
    LaunchedEffect(Unit) {
        while (remainingMs > 0) {
            delay(1_000)
            remainingMs = (deadlineElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        }
    }
    val mm = remainingMs / 60_000
    val ss = (remainingMs % 60_000) / 1_000

    Box(Modifier.fillMaxSize().background(Color(0xFF04070A)).padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("🔒  PHONE LOCKED", color = red, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 2.sp)
            Text(
                "Go ${habit.label.lowercase()}. Your phone unlocks the moment I sense you've done it.",
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 24.sp, textAlign = TextAlign.Center,
            )
            Text("Auto-unlocks in %d:%02d".format(mm, ss), color = dim, fontSize = 13.sp)

            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(amber.copy(alpha = 0.16f))
                    .pointerInput(Unit) { detectTapGestures(onTap = { onEmergency() }) }.padding(vertical = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("EMERGENCY CALL", color = amber, fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 1.sp)
            }

            // Deliberate friction: hold 5s to give up (for a broken detection) — not a one-tap escape.
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .border(1.dp, dim.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            // If the press lasts the full hold (timeout fires), release; an early lift cancels.
                            val liftedEarly = withTimeoutOrNull(LockoutActivity.OVERRIDE_HOLD_MS) { tryAwaitRelease() }
                            if (liftedEarly == null) onOverride()
                        })
                    }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("hold 5s to release", color = dim, fontSize = 12.sp)
            }
        }
    }
}
