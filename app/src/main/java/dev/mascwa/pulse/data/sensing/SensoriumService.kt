package dev.mascwa.pulse.data.sensing

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.mascwa.pulse.MainActivity
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.R
import dev.mascwa.pulse.core.telemetry.ActionTier
import dev.mascwa.pulse.core.telemetry.AmbientAction
import dev.mascwa.pulse.core.telemetry.AmbientRules
import dev.mascwa.pulse.core.telemetry.AmbientSignals
import dev.mascwa.pulse.core.telemetry.DeviceClass
import dev.mascwa.pulse.core.telemetry.Sensorium
import dev.mascwa.pulse.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The Sensorium's adaptive-24/7 foreground service — an UPGRADEABLE service, the design the Android
 * while-in-use law forces and the honest way to run ambient camera/mic sensing:
 *
 *  - **Background starts** (boot, worker self-heal, a sticky restart) run with the special-use type
 *    only and NEVER attempt the mic/camera types — Android 14+ throws for those from background, so
 *    we don't try (ActiveMatrix's boot path catches this exact exception; we avoid it instead). The
 *    type-free core still runs at full power: motion/light/barometer/magnetics fusion, radio
 *    density, baseline learning, anomaly detection.
 *  - **Foreground starts** (MainActivity onStart re-calls [start] with [EXTRA_FOREGROUND] true)
 *    re-invoke startForeground ADDING microphone|camera — legal with a visible activity, and the
 *    while-in-use access then persists in background for the service's life. That is the whole game.
 *  - Degradation is stepwise and honest: full → no camera → no mic → special-use only; the armed
 *    state is surfaced in the engine's flows and the ongoing notification, never faked.
 *
 * START_STICKY — a deliberate divergence from the deleted sensing service's NOT_STICKY: that one WAS
 * its mic/camera types, so a restart without them was pointless; this one has a valuable type-free
 * core worth resurrecting (a sticky null-intent restart simply takes the background path).
 */
class SensoriumService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var looping = false
    @Volatile private var micArmed = false
    @Volatile private var camArmed = false
    private var lastInteractiveMs = System.currentTimeMillis()

    /**
     * The last device reading, kept so [statusText] can say WHY the service is throttled.
     *
     * ⚠️ Held as fields rather than re-probed in [statusText], because that function runs from the
     * notification refresh and from `tryStartForeground` — re-reading there would take a second set
     * of binder calls to answer a question the heartbeat has just answered, and the two could
     * disagree, which is how a notification comes to contradict the behaviour it describes.
     */
    @Volatile private var deviceTier = DeviceClass.Tier.FULL
    @Volatile private var devicePressure = DeviceClass.Pressure.NONE
    @Volatile private var lastBatteryPct = 100
    @Volatile private var lastCharging = false
    @Volatile private var lastPowerSave = false

    private val container get() = (application as? PulseApplication)?.container

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // ⚠️ **Turn the feature off, not just this instance.** `stopSelf()` alone was a Stop
            // button that undid itself: `RefreshWorker` restarts the service on every run while
            // `sensing.enabled` is true, and `BootReceiver` does the same after a reboot, so the
            // scanner would quietly reappear within a worker period with nothing to explain why.
            // Flipping the setting is what the Settings switch does, so afterwards the switch shows
            // the truth, and it is the same shape as the game overlay's dismiss. The loop also
            // reads this and stands itself down, so the two agree even if the write lands first.
            // On the application's scope, not this service's: `onDestroy` cancels `scope` as soon
            // as its own teardown finishes, which would race this write away.
            val app = application as? PulseApplication
            val repo = app?.container?.settingsRepository
            if (app != null && repo != null) {
                app.appScope.launch {
                    runCatching { repo.update { it.copy(sensing = it.sensing.copy(enabled = false)) } }
                }
            }
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RELEASE_ALL) {
            // ⚠️ Releases and keeps sensing, which is the whole difference from Stop above. Somebody
            // pressing this is saying "not that", not "stop watching" — and `releaseAllByHand` bars
            // each released action from being re-asserted until its rule stops asking at least once,
            // so the next heartbeat cannot simply put it all back and make the button look broken.
            //
            // On the application's scope for the same reason Stop is: this service's own scope dies
            // with it, and a release that lost its race would leave the phone holding something
            // nobody can see a reason for.
            val app = application as? PulseApplication
            val acting = app?.container?.ambientActuator
            if (app != null && acting != null) {
                app.appScope.launch {
                    runCatching { acting.releaseAllByHand(System.currentTimeMillis()) }
                    // Redraw at once rather than waiting out the refresh cadence: a button that
                    // works and appears not to is indistinguishable from one that does not.
                    runCatching { updateOngoing(statusText()) }
                }
            }
            return START_STICKY
        }
        val c = container ?: run { stopSelf(); return START_NOT_STICKY }
        // The enabled-toggle is enforced by every caller AND by the loop's first iteration (settings
        // reads are suspend, so a disabled sticky-restart runs one instant heartbeat and stops).

        val foregroundLaunch = intent?.getBooleanExtra(EXTRA_FOREGROUND, false) == true
        val wantMic = foregroundLaunch && hasPermission(Manifest.permission.RECORD_AUDIO)
        val wantCam = foregroundLaunch && hasPermission(Manifest.permission.CAMERA)

        // Stepwise arming: full → no camera → no mic → special-use only → give up. A background
        // start goes straight to the last legal rung by construction (wantMic/wantCam false).
        var mic = wantMic
        var cam = wantCam
        val started = tryStartForeground(mic, cam) ||
            run { cam = false; tryStartForeground(mic, cam) } ||
            run { mic = false; cam = wantCam; tryStartForeground(mic, cam) } ||
            run { mic = false; cam = false; tryStartForeground(false, false) }
        if (!started) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Arming can only ever be upgraded by a foreground relaunch — a background restart while
        // already armed must not silently disarm a running engine's sips (the service keeps its
        // while-in-use grants for its lifetime once armed).
        if (mic) micArmed = true
        if (cam) camArmed = true
        c.sensoriumEngine.micArmed.value = micArmed
        c.sensoriumEngine.camArmed.value = camArmed

        if (!looping) {
            looping = true
            c.sensorFusion.start()
            // ⚠️ Registered here and nowhere else: ACTION_USER_PRESENT is only ever delivered to a
            // receiver registered at RUNTIME, so a manifest entry would install, compile and never
            // fire — see SenseContextReader.start.
            c.senseContextReader.start()
            scope.launch { loop() }
        } else {
            updateOngoing(statusText())
        }
        return START_STICKY
    }

    private suspend fun loop() {
        val c = container ?: return
        val engine = c.sensoriumEngine
        val acting = c.ambientActuator
        var level = Sensorium.SenseLevel.NOMINAL
        var lastNotifMs = 0L
        // What the notification currently SHOWS as held. Loop-local like the rest of this state, so
        // a restarted loop redraws once on its first heartbeat rather than trusting a stale field.
        var lastHeld: Set<AmbientAction> = emptySet()

        // ⚠️ **Safety rule 3, and it runs before the first heartbeat rather than beside it.** A hold
        // asserted by a process that then died — killed by the system, crashed, force-stopped — has
        // nobody left alive to release it, and the only record that it existed is on disk. Every
        // APP-tier hold happens to survive that by itself; the phone and device-owner tiers will
        // not. Doing it here, while there is nothing for it to find, is what makes it already
        // load-bearing on the day there is.
        runCatching { acting.reconcileFromDisk(System.currentTimeMillis()) }

        while (scope.isActive) {
            runCatching {
                val settings = c.settingsRepository.current()
                if (!settings.sensing.enabled) {
                    // ⚠️ Safety rule 4. A layer that cannot sense cannot justify holding anything,
                    // so everything goes before the service does — and this is awaited rather than
                    // fired off, because `stopSelf` is the last thing that happens to this process.
                    runCatching { acting.standDown(System.currentTimeMillis(), "sensing was switched off") }
                    stopSelf()
                    return
                }
                val device = c.deviceContextProvider.snapshot()
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (pm.isInteractive) lastInteractiveMs = System.currentTimeMillis()
                val screenOffMin = ((System.currentTimeMillis() - lastInteractiveMs) / 60_000L).toInt()

                // ⚠️ The device reading is taken here, per heartbeat, and not cached. The static
                // half of it IS cached inside the reader; the half that matters at this cadence —
                // thermal state, heap use, the battery saver — is exactly the half that moves.
                val probe = runCatching { c.deviceProbe.probe() }.getOrNull()
                deviceTier = probe?.let { DeviceClass.tierOf(it) } ?: deviceTier
                devicePressure = probe?.let { DeviceClass.pressureOf(it) } ?: DeviceClass.Pressure.NONE

                level = Sensorium.level(
                    previous = level,
                    batteryPct = device.batteryPct,
                    charging = device.isCharging,
                    powerSave = pm.isPowerSaveMode,
                    screenOffMinutes = screenOffMin,
                    movement = c.sensorFusion.snapshot.value.movement,
                    tier = deviceTier,
                    pressure = devicePressure,
                    // The user's own floor, which had been stored and read by nothing at all.
                    standDownPct = settings.sensing.standDownBatteryPct,
                )
                lastBatteryPct = device.batteryPct
                lastCharging = device.isCharging
                lastPowerSave = pm.isPowerSaveMode
                // ⚠️ The acting layer can slow the ladder, and `slowed` is what stops that ever
                // reaching STANDDOWN — where `micIntervalSec` is 0, which is OFF. Applied to the
                // level rather than to the cadence so the readout and the sampling agree about
                // which rung the watch is on.
                if (AmbientHolds.isHeld(AmbientAction.LOWER_SENSE_RATE)) level = Sensorium.slowed(level)
                engine.level.value = level
                val cadence = Sensorium.cadenceFor(level)

                engine.step(
                    cadence = cadence,
                    micAllowed = micArmed && settings.sensing.micSensing,
                    // ⚠️ The camera can be held off and the microphone deliberately cannot. Stopping
                    // the eyes costs a scene label; stopping the ears costs the smoke alarm, which
                    // is the one thing in this subsystem that has to work at three in the morning.
                    camAllowed = camArmed && settings.sensing.cameraSensing &&
                        !AmbientHolds.isHeld(AmbientAction.STOP_CAMERA_SIPS),
                    radioAllowed = settings.sensing.radioSensing &&
                        level != Sensorium.SenseLevel.CONSERVE && level != Sensorium.SenseLevel.STANDDOWN,
                )

                val now = System.currentTimeMillis()

                // ---- and then act on what all that sensing concluded ----
                runCatching {
                    val signals = AmbientSignals(
                        situation = engine.situation.value,
                        env = engine.reading.value,
                        phone = engine.phone.value,
                        events = engine.liveEvents.value,
                        nowMs = now,
                    )
                    acting.apply(
                        AmbientRules.permit(AmbientRules.decide(signals), tierFor(settings))
                            // ⚠️ Every tier that has a capability check, not only the ones switched
                            // on. A tier that is off has already been refused above and cannot
                            // reach here — but an owner-tier hold that IS permitted and cannot be
                            // carried out (this app is not the device owner, which is almost every
                            // install) would otherwise be recorded as held while nothing happened.
                            .minusWhatThisHandsetCannotDo(c.phoneHolds, c.ownerHolds),
                        now,
                        // ⚠️ Computed here, where the signals already are. Quiet is the common and
                        // correct state for this layer; what it must never be is unexplained.
                        quietBecause = AmbientRules.whyQuiet(signals),
                    )
                }
                // ⚠️ A hold changing redraws AT ONCE, ahead of the cadence. The refresh interval is
                // sized for a status line that drifts slowly; what the acting layer does is news,
                // and news that arrives up to a refresh period late is how somebody comes to believe
                // the phone did something for no reason. Comparing the rendered set rather than
                // asking the actuator keeps this a read — `apply` reports nothing, and widening it
                // to would put a notification concern into the reconciler.
                val heldNow = AmbientHolds.held.value
                if (heldNow != lastHeld || now - lastNotifMs >= NOTIF_REFRESH_MS) {
                    lastHeld = heldNow
                    lastNotifMs = now
                    updateOngoing(statusText())
                }
                delay(cadence.fusionHeartbeatSec * 1000L)
            }.onFailure {
                // One bad heartbeat must never kill the watch — back off briefly and continue.
                delay(30_000L)
            }
        }
    }

    /**
     * ⚠️ This used to say "Conserving battery" whatever the cause, and "Standing down (battery)"
     * even when the phone was standing down because it was too hot or because it is a cheap phone
     * with nothing to spare. A degradation the user can see but cannot account for is barely better
     * than a silent one, and now that the tier can throttle this service the old text would often
     * have been simply false. The sentence comes from [Sensorium.reasonFor], so the notification and
     * the ladder cannot disagree about why.
     */
    private fun statusText(): String {
        val engine = container?.sensoriumEngine
        val readingLine = engine?.reading?.value?.describe() ?: "warming up"
        val armed = buildString {
            append(if (micArmed) "ears armed" else "ears on standby — open the app to arm")
            append(" · ")
            append(if (camArmed) "eyes armed" else "eyes on standby")
        }
        val current = engine?.level?.value
        val why = current?.let {
            Sensorium.reasonFor(
                level = it,
                tier = deviceTier,
                pressure = devicePressure,
                batteryPct = lastBatteryPct,
                charging = lastCharging,
                powerSave = lastPowerSave,
            )
        }
        return when (current) {
            Sensorium.SenseLevel.CONSERVE ->
                "Sampling less — ${why ?: "conserving"} · $readingLine"
            Sensorium.SenseLevel.STANDDOWN ->
                "Standing down — ${why ?: "conserving"} · heartbeat only"
            else -> "$readingLine · $armed"
        }
    }

    private fun tryStartForeground(withMic: Boolean, withCam: Boolean): Boolean = runCatching {
        var type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        if (withMic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (withCam) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        ServiceCompat.startForeground(this, NOTIF_ID, ongoing(statusText()), type)
    }.isSuccess

    private fun hasPermission(perm: String): Boolean =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    /**
     * What the acting layer is holding right now, in one line, or null when it is holding nothing.
     *
     * ⚠️ Read from [AmbientHolds] rather than from the actuator's history: this answers "what is in
     * force", which is a different question from "what has it done", and the history's newest line
     * can be a RELEASE. A notification built from the history would announce a hold at the moment it
     * was let go of.
     */
    private fun heldLine(): String? {
        val held = AmbientHolds.held.value
        if (held.isEmpty()) return null
        return held.joinToString(" · ") { it.label }.replaceFirstChar { it.uppercase() }
    }

    private fun ongoing(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SensoriumService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val held = heldLine()
        val body = if (held == null) text else "$held\n$text"
        val b = NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setColor(ContextCompat.getColor(this, R.color.lcars_condition_routine))
            .setSubText("SENSORIUM")
            // ⚠️ The title says what is happening TO the phone when something is happening to it.
            // "Environment scanner" over a phone whose ringer this just changed buries the one fact
            // somebody needs, under the name of the thing that did it.
            .setContentTitle(if (held == null) "Environment scanner" else "Environment scanner · acting")
            .setContentText(body)
            // ⚠️ BigText because the collapsed line truncates and several holds run long. The
            // collapsed row is a summary somebody glances at; the expanded one has to be complete,
            // or the undo sits under a sentence that stops mid-word.
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
        // ⚠️ Only offered when there is something to undo. An always-present button that does
        // nothing on most presses is the dead-control shape this repository keeps correcting — and
        // it is exactly what "▸ LOOK NOW" was on this same feature before it was made to say why.
        if (held != null) {
            val release = PendingIntent.getService(
                this, 2, Intent(this, SensoriumService::class.java).setAction(ACTION_RELEASE_ALL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(0, "Let it all go", release)
        }
        return b
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateOngoing(text: String) {
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, ongoing(text))
        }
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "Environment scanner", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) },
        )
    }

    override fun onDestroy() {
        runCatching { container?.sensorFusion?.stop() }
        runCatching { container?.senseContextReader?.stop() }
        val c = container
        scope.launch {
            // ⚠️ Safety rule 4 again, on the other way out. This service is START_STICKY and can be
            // killed and restarted by the system, so a teardown that left holds in force would have
            // them survive on a phone with nothing sensing — exactly the state `reconcileFromDisk`
            // exists to clean up after, reached the slow way instead of the fast one.
            runCatching { c?.ambientActuator?.standDown(System.currentTimeMillis(), "the watch stopped") }
            runCatching { c?.ambientAudioSampler?.close() }
            runCatching { c?.ambientCameraSampler?.close() }
            runCatching { c?.sensoriumStore?.flushNow() }
        }.invokeOnCompletion { scope.cancel() }
        c?.sensoriumEngine?.micArmed?.value = false
        c?.sensoriumEngine?.camArmed?.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {

        /**
         * The deepest tier the rules may act at, from the user's own switch.
         *
         * ⚠️ **[ActionTier.APP] is the floor and has no switch**, because an app declining to
         * interrupt you is not a power that needs permission — and making it refusable would allow a
         * state where the layer senses everything and may do nothing at all.
         *
         * ⚠️ **The ladder itself is [AmbientRules.tierFor], not this function.** What is left here is
         * reading two booleans off the settings object — the half that needs an Android type — while
         * the rule that the owner tier needs BOTH switches lives in a module CI can run. It is the
         * same split, for the same reason, as `RingerPolicy` against `PhoneHolds`: a rule written
         * inside a class that needs a `Context` is a rule nothing can test.
         *
         * ⚠️ There is deliberately ONE owner switch rather than one per action, because after the
         * rule audit there is exactly one owner action left: the other four were removed for having
         * no situation that wanted them. A switch per action would be four controls, three of which
         * govern nothing. If a second owner action is ever added, this is where it stops being
         * honest.
         */
        private fun tierFor(settings: AppSettings): ActionTier =
            AmbientRules.tierFor(
                actOnPhone = settings.sensing.actOnPhone,
                actOnApps = settings.sensing.actOnApps,
            )

        private const val CHANNEL_ONGOING = "sensorium_ongoing"
        private const val NOTIF_ID = dev.mascwa.pulse.notifications.NotifId.FGS_SENSORIUM
        private const val ACTION_STOP = "dev.mascwa.pulse.data.sensing.STOP"

        /**
         * The undo, on the notification that reports the holds.
         *
         * ⚠️ Deliberately a SEPARATE action string from [ACTION_STOP] rather than an extra on it.
         * `Intent.filterEquals` — which is what decides whether two `PendingIntent`s are the same
         * one — compares the action and ignores extras, so two intents differing only by an extra
         * are one PendingIntent and the last one built silently wins the payload. This repository
         * has already shipped that defect once, on the radio notification.
         */
        private const val ACTION_RELEASE_ALL = "dev.mascwa.pulse.data.sensing.RELEASE_ALL"
        private const val EXTRA_FOREGROUND = "foreground_launch"
        private const val NOTIF_REFRESH_MS = 3 * 60_000L

        /** [foregroundLaunch] true ONLY from a visible activity — it arms the mic/camera types. */
        fun start(context: Context, foregroundLaunch: Boolean) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SensoriumService::class.java).putExtra(EXTRA_FOREGROUND, foregroundLaunch),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SensoriumService::class.java))
        }
    }
}
