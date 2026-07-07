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
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.core.telemetry.NeedKind
import dev.mascwa.pulse.core.telemetry.PhonePenalties
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The MAXIMUM phone penalty — a lock-screen gate. When a survival need is critically neglected and the kiosk
 * tier is on, this pins the phone **over the lock screen** with the need(s) you must tend; it releases — and
 * only then lets you back into the phone — once you've completed each one (DRINK / EAT / REST / WASH / BRUSH /
 * FLOSS restores that need in the game). Mirrors [LockoutActivity]; opt-in, off by default.
 *
 * Safe by construction — it can NEVER trap you:
 *  - **Guaranteed auto-release**: unconditionally unlocks after [MAX_LOCK_MS], whatever happens (the hard net).
 *  - **Completion release**: unlocks the moment every shown need is tended (its care action pressed) OR the
 *    needs have recovered (e.g. you tended them elsewhere first).
 *  - **Emergency**: a prominent button opens the dialer (kept on the lock-task allow-list).
 *  - **Override**: a deliberate 5-second hold releases it (a friction escape for a broken state).
 *  - If Pulse isn't a Device Owner, there's no kiosk pin at all — it degrades to a full-screen nag.
 */
class PenaltyGateActivity : ComponentActivity() {

    private var released = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* swallow — tend the need or wait it out */ }
        })

        val needs = (intent.getStringArrayExtra(EXTRA_NEEDS) ?: emptyArray())
            .mapNotNull { runCatching { NeedKind.valueOf(it) }.getOrNull() }
        if (needs.isEmpty()) { finish(); return }

        val container = (application as PulseApplication).container

        // Enter device-owner lock task (kiosk pin) — best-effort; keep the DIALER on the allow-list so the
        // emergency button can always launch it. No-op (degrades to a plain full-screen nag) if not DO.
        runCatching {
            val dpc = dev.mascwa.pulse.security.DevicePolicyController(this)
            if (dpc.isDeviceOwner()) {
                val dialer = runCatching { getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()
                dpc.setLockTaskPackages(listOfNotNull(packageName, dialer))
                startLockTask()
            }
        }

        val deadline = SystemClock.elapsedRealtime() + MAX_LOCK_MS

        setContent {
            val tended = remember { mutableStateOf(setOf<NeedKind>()) }
            PenaltyGateScreen(
                needs = needs,
                tended = tended.value,
                deadlineElapsed = deadline,
                onTend = { need ->
                    // Perform the care action in the game (restores the need) and mark it done for this gate.
                    when (need) {
                        NeedKind.HYDRATION -> container.specialGameStore.drink()
                        NeedKind.NOURISHMENT -> container.specialGameStore.eat()
                        NeedKind.ENERGY -> container.specialGameStore.rest()
                        NeedKind.HYGIENE -> container.specialGameStore.wash()
                        NeedKind.BRUSHING -> container.specialGameStore.brushTeeth()
                        NeedKind.FLOSSING -> container.specialGameStore.floss()
                    }
                    tended.value = tended.value + need
                },
                onEmergency = { runCatching { startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                onOverride = { release() },
            )
        }

        // Watchdog: release the instant every need is tended (or has recovered), else unconditionally at the deadline.
        lifecycleScope.launch {
            while (isActive && !released) {
                val life = runCatching { container.specialGameStore.lifeSnapshot() }.getOrNull()
                val allRecovered = life != null && needs.all { it.value(life) > PhonePenalties.ENGAGE_AT }
                if (allRecovered) { release(); break }
                if (SystemClock.elapsedRealtime() >= deadline) { release(); break }
                delay(2_000)
            }
        }
    }

    private fun release() {
        if (released) return
        released = true
        val container = (application as PulseApplication).container
        // Lift the capability locks too, now that the needs are tended.
        lifecycleScope.launch {
            runCatching {
                val life = container.specialGameStore.lifeSnapshot()
                container.phonePenaltyController.reconcile(life)
            }
        }
        runCatching { NotificationManagerCompat.from(this).cancel(NOTIF_ID) }
        runCatching { stopLockTask() }
        finish()
    }

    companion object {
        const val EXTRA_NEEDS = "penalty_gate_needs"
        /** Fixed id so a new gate replaces the last and the activity can clear it on release. */
        const val NOTIF_ID = 91_842
        /** The hard auto-release net — the gate NEVER holds longer than this, whatever happens. */
        private const val MAX_LOCK_MS = 15 * 60_000L
        /** How long the override must be held — deliberate friction so it isn't a one-tap bail-out. */
        const val OVERRIDE_HOLD_MS = 5_000L
    }
}

@Composable
private fun PenaltyGateScreen(
    needs: List<NeedKind>,
    tended: Set<NeedKind>,
    deadlineElapsed: Long,
    onTend: (NeedKind) -> Unit,
    onEmergency: () -> Unit,
    onOverride: () -> Unit,
) {
    val red = Color(0xFFFF5A6E)
    val amber = Color(0xFFE0B341)
    val green = Color(0xFF5BE3A7)
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

    Box(Modifier.fillMaxSize().background(Color(0xFF04070A)).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("🔒  PHONE LOCKED", color = red, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 2.sp)
            Text(
                "You've let yourself go too long. Take care of it to get back in.",
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp, textAlign = TextAlign.Center,
            )
            needs.forEach { need ->
                val done = need in tended
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background((if (done) green else red).copy(alpha = 0.16f))
                        .border(1.dp, (if (done) green else red).copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                        .pointerInput(done) { if (!done) detectTapGestures(onTap = { onTend(need) }) }
                        .padding(vertical = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (done) "✓ ${need.restoreLabel.uppercase()}" else "${need.verb}  ·  I DID IT",
                        color = if (done) green else red, fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = 1.sp,
                    )
                }
            }
            Text("Auto-unlocks in %d:%02d".format(mm, ss), color = dim, fontSize = 12.sp)

            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(amber.copy(alpha = 0.16f))
                    .pointerInput(Unit) { detectTapGestures(onTap = { onEmergency() }) }.padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("EMERGENCY CALL", color = amber, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 1.sp)
            }
            // Deliberate friction: hold 5s to give up (for a broken state) — not a one-tap escape.
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .border(1.dp, dim.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            val liftedEarly = withTimeoutOrNull(PenaltyGateActivity.OVERRIDE_HOLD_MS) { tryAwaitRelease() }
                            if (liftedEarly == null) onOverride()
                        })
                    }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("hold 5s to release", color = dim, fontSize = 12.sp)
            }
        }
    }
}
