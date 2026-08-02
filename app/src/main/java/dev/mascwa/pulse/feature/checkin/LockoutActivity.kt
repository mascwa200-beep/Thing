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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import dev.mascwa.pulse.core.telemetry.CareCheckin
import dev.mascwa.pulse.core.telemetry.CareSchedule
import dev.mascwa.pulse.core.telemetry.ClaimVerdict
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
 * Keyed on [CareCheckin] — the merged self-care scheduler's own vocabulary (brush/floss/water/eat/rest/wash),
 * so it's driven by the same single due-check engine as the ordinary check-in prompt, not a second parallel
 * habit model. [CareSchedule.verifyClaim] already knows which check-ins have no reliable sensor signal
 * (FLOSS, REST) and is deliberately forgiving there — trusts rather than holds you hostage to an unsensable task.
 *
 * Safe by construction — it can NEVER trap you:
 *  - **Guaranteed auto-release**: it unconditionally unlocks after [MAX_LOCK_MS] no matter what (the hard net).
 *  - **Sensor release**: unlocks + credits the moment [CareSchedule.verifyClaim] confirms the task from the
 *    evidence log (or, for an unsensable check-in, trusts immediately — see above).
 *  - **Emergency**: a prominent button opens the dialer (the dialer is kept on the lock-task allow-list).
 *  - **Override**: a deliberate 5-second hold releases it (a friction escape for a broken detection).
 *  - If Pulse isn't a Device Owner, there's no kiosk pin at all — it degrades to a full-screen nag.
 */
class LockoutActivity : ComponentActivity() {

    private lateinit var checkin: CareCheckin
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

        val c = runCatching { CareCheckin.valueOf(intent.getStringExtra(EXTRA_CHECKIN) ?: "") }.getOrNull()
        if (c == null) { finish(); return }
        checkin = c
        // A USER-ARMED commitment lock ("lock me out until I brush") is a deliberate self-request — it fires
        // regardless of any need level, genuinely locks the screen, and uses the owner's chosen backstop.
        val userArmed = intent.getBooleanExtra(EXTRA_USER_ARMED, false)
        // The no-brick auto-release net — the lock can NEVER hold longer than this, whatever happens. A
        // commitment lock uses the owner's chosen minutes (clamped to a safe range); the penalty path is fixed.
        val backstopMs = if (userArmed) {
            intent.getIntExtra(EXTRA_BACKSTOP_MIN, 30).coerceIn(MIN_BACKSTOP_MIN, MAX_BACKSTOP_MIN) * 60_000L
        } else {
            MAX_LOCK_MS
        }

        // Enter device-owner lock task (kiosk pin) — best-effort; keep the DIALER on the allow-list so the
        // emergency button can always launch it. No-op (degrades to a plain full-screen nag) if not DO. For a
        // user-armed lock, also genuinely lock the screen (this gate then shows over the lock screen).
        runCatching {
            val dpc = DevicePolicyController(this)
            if (dpc.isDeviceOwner()) {
                val dialer = runCatching { getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()
                dpc.setLockTaskPackages(listOfNotNull(packageName, dialer))
                startLockTask()
                if (userArmed) dpc.lockNow()
            }
        }

        val deadline = SystemClock.elapsedRealtime() + backstopMs
        setContent {
            LockoutScreen(
                checkin = checkin,
                userArmed = userArmed,
                deadlineElapsed = deadline,
                overrideCodeProvider = {
                    runCatching { (application as PulseApplication).container.settingsRepository.current().lockOverrideCode }
                        .getOrDefault("")
                },
                onEmergency = { runCatching { startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                onOverride = { release(sensedDone = false) },
            )
        }

        // Watchdog: release the instant the task is SENSED done, else unconditionally at the deadline. A
        // sliding window (not the check-in's whole cadence) — this is "are you doing it NOW", not a re-check
        // of the last several hours. verifyClaim is deliberately forgiving for check-ins with no reliable
        // sensor (FLOSS, REST) — it trusts rather than holding you hostage to an unsensable task.
        lifecycleScope.launch {
            val container = (application as PulseApplication).container
            while (isActive && !released) {
                val since = System.currentTimeMillis() - LOCK_SENSE_WINDOW_MS
                val evidence = runCatching { container.activityEvidenceStore.recent(since) }.getOrDefault(emptyList())
                val verdict = CareSchedule.verifyClaim(checkin, evidence, since, sensingActive = true)
                if (verdict != ClaimVerdict.NONE) {
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

    /** Unlock. [sensedDone] = the sensors confirmed it (or it's unsensable and trusted), so credit the
     *  need; the override/timeout doesn't. */
    private fun release(sensedDone: Boolean) {
        if (released) return
        released = true
        if (sensedDone) {
            val container = (application as PulseApplication).container
            lifecycleScope.launch { runCatching { container.careCheckinStore.complete(checkin) } }
        }
        runCatching { stopLockTask() }
        finish()
    }

    companion object {
        const val EXTRA_CHECKIN = "lockout_checkin"
        /** true = a user-armed commitment lock (fires regardless of need level; locks the screen). */
        const val EXTRA_USER_ARMED = "lockout_user_armed"
        /** The commitment lock's auto-release backstop in minutes (clamped to [MIN_BACKSTOP_MIN]..[MAX_BACKSTOP_MIN]). */
        const val EXTRA_BACKSTOP_MIN = "lockout_backstop_min"
        /** The penalty-path hard auto-release net — the lockout NEVER holds longer than this, whatever happens. */
        private const val MAX_LOCK_MS = 10 * 60_000L
        private const val MIN_BACKSTOP_MIN = 5
        /** A hard ceiling on the commitment lock — it can never hold longer than this (no-brick guarantee). */
        private const val MAX_BACKSTOP_MIN = 240
        /** How long the watchdog looks back for sensed corroboration — a recent, "right now" window. */
        private const val LOCK_SENSE_WINDOW_MS = 30 * 60_000L
        /** How long the override must be held — deliberate friction so it isn't a one-tap bail-out. */
        const val OVERRIDE_HOLD_MS = 5_000L
    }
}

@Composable
private fun LockoutScreen(
    checkin: CareCheckin,
    userArmed: Boolean,
    deadlineElapsed: Long,
    overrideCodeProvider: suspend () -> String,
    onEmergency: () -> Unit,
    onOverride: () -> Unit,
) {
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

    // The owner's personal override code (loaded once); the code field only shows when a code is set.
    var overrideCode by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { overrideCode = overrideCodeProvider() }
    var entry by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize().background(Color(0xFF04070A)).padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(
                if (userArmed) "🔒  COMMITMENT LOCK" else "🔒  PHONE LOCKED",
                color = red, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 2.sp,
            )
            Text(
                if (userArmed) "You locked yourself out until you ${checkin.label.lowercase()}. It unlocks the moment I sense you've done it."
                else "Go ${checkin.label.lowercase()}. Your phone unlocks the moment I sense you've done it.",
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

            // Code override: enter the personal override code to force-release immediately. Only shown when set.
            if (overrideCode.isNotBlank()) {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .border(1.dp, dim.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicTextField(
                        value = entry,
                        onValueChange = {
                            entry = it.filter(Char::isDigit).take(12)
                            if (entry == overrideCode) onOverride()
                        },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center),
                        cursorBrush = SolidColor(amber),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.Center) {
                                if (entry.isEmpty()) {
                                    Text("enter override code", color = dim, fontSize = 14.sp)
                                }
                                inner()
                            }
                        },
                    )
                }
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
