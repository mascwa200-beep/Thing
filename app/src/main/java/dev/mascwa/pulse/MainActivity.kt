package dev.mascwa.pulse

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import dev.mascwa.pulse.ui.effects.BootScreen
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.mascwa.pulse.core.device.DeviceGate
import dev.mascwa.pulse.core.telemetry.HardwareAttestation
import dev.mascwa.pulse.data.settings.AppSettings
import dev.mascwa.pulse.di.PulseViewModelFactory
import dev.mascwa.pulse.ui.DeviceGateScreen
import dev.mascwa.pulse.ui.PulseApp
import dev.mascwa.pulse.ui.theme.NightwireTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val app get() = application as PulseApplication

    /** Glasses HUD on a connected external display; lives only while the app is in the foreground. */
    private var hud: dev.mascwa.pulse.feature.hud.HudController? = null

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    /** Wall-clock of the last auto-update check, so a quick app-switch doesn't re-hit GitHub each resume. */
    private var lastAutoUpdateCheckMs = 0L

    /** The pending deep-link route (notification tap or launcher shortcut). Held as Compose state and
     *  updated on every new intent, so a WARM launch (app already running) re-navigates — not just a cold
     *  start. Paired with launchMode=singleTop so the running instance receives [onNewIntent]. */
    private val pendingRouteState = mutableStateOf<String?>(null)

    /**
     * If auto-update is on, check for a GREEN (CI-passed) build newer than what we last prompted to
     * install — and if found, download it and launch the system installer (the user taps "Update" once).
     * Fully defensive (never crashes the launch/resume) and throttled so foreground churn isn't chatty.
     * Runs on cold launch and on every foreground return so an update lands "as soon as it's green."
     */
    private fun maybeAutoUpdate() {
        val now = System.currentTimeMillis()
        if (now - lastAutoUpdateCheckMs < AUTO_UPDATE_MIN_INTERVAL_MS) return
        lastAutoUpdateCheckMs = now
        lifecycleScope.launch {
            runCatching {
                val settings = app.container.settingsRepository.current()
                // Auto-update is permanently on (no opt-out) — always check for a newer green build.
                val info = app.container.updateRepository.check().available
                if (info != null && info.versionCode > settings.lastAutoUpdateCode) {
                    val file = app.container.updateRepository.download(info) { }
                    app.container.settingsRepository.update { it.copy(lastAutoUpdateCode = info.versionCode) }
                    dev.mascwa.pulse.core.util.installApk(this@MainActivity, file)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val factory = PulseViewModelFactory(app.container)
        val gateResult = DeviceGate.evaluate()
        val graphene = dev.mascwa.pulse.core.device.GrapheneOs.detect(this)
        pendingRouteState.value = intent?.getStringExtra(EXTRA_ROUTE)
        runCatching { app.container.usageRepository.log("lifecycle", "app opened") }
        // Launcher integration: long-press app shortcuts (deep-link into J.A.R.V.I.S./Markets/NAV/SOS).
        runCatching { dev.mascwa.pulse.shortcuts.AppShortcuts.install(this) }

        // Schedule the background refresh worker per the user's settings. Guarded so a startup
        // hiccup (settings read, scheduling, or a service start) can never crash the launch.
        lifecycleScope.launch {
            runCatching {
                val settings = app.container.settingsRepository.current()
                if (settings.notifications.masterEnabled) {
                    app.container.notificationScheduler.schedule(
                        settings.refreshIntervalMinutes, settings.refreshOnlyOnWifi,
                    )
                }
                // Bring J.A.R.V.I.S. back online if the user left it resident.
                if (settings.jarvis.residentService) {
                    runCatching {
                        dev.mascwa.pulse.jarvis.matrix.ActiveMatrixService.start(
                            this@MainActivity, wakeWord = settings.jarvis.wakeWord,
                        )
                    }
                }
                if (settings.jarvis.vitalsTracking) {
                    runCatching { dev.mascwa.pulse.jarvis.vitals.VitalsTrackingService.start(this@MainActivity) }
                }
                // Sensorium: a FOREGROUND launch is the one context that can arm the mic/camera FGS
                // types (Android's while-in-use law) — re-call on every activity start so a service
                // that booted on the standby path upgrades to fully armed the moment the app opens.
                if (settings.sensing.enabled) {
                    runCatching {
                        dev.mascwa.pulse.data.sensing.SensoriumService.start(this@MainActivity, foregroundLaunch = true)
                    }
                }
                // Same treatment for the remote link, so the desk computer can reach the phone again after
                // a cold launch without anyone having to re-flip the switch.
                if (settings.remote.enabled) {
                    runCatching { dev.mascwa.pulse.remote.RemoteLinkService.start(this@MainActivity) }
                }
            }
        }
        // Upload any crash reports recorded since last launch (scrubbed → debug-reports branch). Done off
        // the crash path (the JVM is unstable mid-crash); opt-in + no-op without a token; never crashes launch.
        lifecycleScope.launch {
            runCatching { app.container.debugUploader.uploadPendingCrashes() }
        }
        // Auto-update runs from onStart (fires on cold launch + every foreground return).

        setContent {
            val settings by app.container.settingsRepository.settings
                .collectAsStateWithLifecycle(initialValue = AppSettings())
            val online by app.container.connectivityObserver.isOnline.collectAsStateWithLifecycle()
            val unmetered by app.container.connectivityObserver.isUnmetered.collectAsStateWithLifecycle()

            NightwireTheme(accent = settings.accentColor, amoledBlack = settings.amoledBlack) {
                var acknowledged by remember { mutableStateOf(false) }
                // Hardware attestation runs once, async, and STRENGTHENS the gate: a genuine result is
                // cryptographic proof (locked bootloader + the GrapheneOS verified-boot key), and a device
                // that fails hardware integrity (unlocked bootloader / unverified boot / not hardware-backed)
                // is distrusted even if the spoofable heuristic passes. It can never lock the owner out —
                // when attestation is unavailable or still pending it defers to the heuristic, and the
                // "Continue anyway" acknowledgement persists.
                val attestation by produceState<dev.mascwa.pulse.core.device.DeviceAttestation.Report?>(null) {
                    value = withContext(Dispatchers.IO) {
                        runCatching { dev.mascwa.pulse.core.device.DeviceAttestation().run() }.getOrNull()
                    }
                }
                val heuristicOk = gateResult.isMatch && graphene.isGraphene
                val attestationTrusted: Boolean? = attestation?.let { r ->
                    val v = r.verdict
                    if (r.available && v != null) {
                        val st = r.info?.verifiedBootState
                        val integrityOk = v.hardwareBacked && v.bootloaderLocked &&
                            st != HardwareAttestation.BootState.UNVERIFIED &&
                            st != HardwareAttestation.BootState.FAILED
                        v.grapheneVerified || (integrityOk && heuristicOk)
                    } else {
                        null // attestation unavailable on this device → defer to the heuristic
                    }
                }
                val gated = !(attestationTrusted ?: heuristicOk) &&
                    !acknowledged && !settings.deviceGateAcknowledged

                // Ask for notification permission once on Android 13+.
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED
                    ) {
                        requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                var booted by rememberSaveable { mutableStateOf(false) }

                // One haptics engine for the whole app. Only the on/off flag was provided before, so
                // every call site fell through to `rememberHapticCue`'s fallback and built its own
                // PulseHaptics per composition context — the engine resolves the vibrator and probes
                // each primitive's support on construction, and there is no reason to do that more
                // than once.
                val haptics = androidx.compose.runtime.remember {
                    dev.mascwa.pulse.ui.effects.PulseHaptics(applicationContext)
                }
                // The audio engine holds a native AudioTrack, so it is released when this leaves the
                // composition rather than left to the finaliser.
                val lcarsAudio = androidx.compose.runtime.remember { dev.mascwa.pulse.ui.effects.LcarsAudio() }
                androidx.compose.runtime.DisposableEffect(lcarsAudio) {
                    onDispose { lcarsAudio.release() }
                }
                androidx.compose.runtime.CompositionLocalProvider(
                    dev.mascwa.pulse.ui.effects.LocalGlitchEnabled provides settings.glitch,
                    dev.mascwa.pulse.ui.effects.LocalHaptics provides settings.haptics,
                    dev.mascwa.pulse.ui.effects.LocalPulseHaptics provides haptics,
                    dev.mascwa.pulse.ui.effects.LocalSounds provides settings.sounds,
                    dev.mascwa.pulse.ui.effects.LocalLcarsAudio provides lcarsAudio,
                    dev.mascwa.pulse.feature.home.LocalJarvisFeedTopic provides settings.jarvisFeedTopic,
                    dev.mascwa.pulse.core.connectivity.LocalIsOnline provides online,
                    // Metered only when we positively know it — offline is "no idea", not "cellular".
                    dev.mascwa.pulse.core.connectivity.LocalIsMetered provides (online && !unmetered),
                ) {
                // The klaxon. Fires on the way INTO red and nowhere else — once per condition
                // change, here rather than in the frame, because the frame is per-screen and would
                // sound it again on every navigation for as long as the alert lasted.
                val condition by dev.mascwa.pulse.notifications.AlertStatus.condition
                    .collectAsStateWithLifecycle()
                val cue = dev.mascwa.pulse.ui.effects.rememberLcarsCue()
                LaunchedEffect(condition) {
                    if (condition == dev.mascwa.pulse.notifications.AlertCondition.RED) {
                        cue(
                            dev.mascwa.pulse.ui.effects.SoundCue.ALERT,
                            dev.mascwa.pulse.ui.effects.HapticCue.IMPACT_HEAVY,
                        )
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    if (gated) {
                        DeviceGateScreen(
                            result = gateResult,
                            grapheneOk = graphene.isGraphene,
                            osDetail = graphene.summary,
                            attestationOk = attestation?.verdict?.grapheneVerified,
                            attestationDetail = attestation?.verdict?.summary
                                ?: attestation?.error,
                            onContinue = {
                                acknowledged = true
                                lifecycleScope.launch {
                                    app.container.settingsRepository.update { it.copy(deviceGateAcknowledged = true) }
                                }
                            },
                            onExit = { finish() },
                        )
                    } else {
                        PulseApp(
                            factory = factory,
                            startRoute = pendingRouteState.value,
                            isOnline = online,
                            navigationRequests = app.container.navigationBus,
                            onRouteVisit = { route ->
                                // ⚠️ The BASE route, not the pattern. currentRoute hands over the
                                // route PATTERN — "survival?guide={guide}" — and recording that
                                // verbatim mints junk usage keys that never match FeatureCatalog,
                                // so those features could never be counted or recommended.
                                val base = route.substringBefore('?')
                                app.container.usageRepository.record(base)
                                app.container.usageRepository.log("nav", base)
                            },
                            onStartRouteConsumed = { pendingRouteState.value = null },
                        )
                    }
                    // (The visual "always watching" overlay was removed — J.A.R.V.I.S.'s awareness is
                    // non-visual now; the presence surfaces as a professional status feed instead.)
                    // Cold-open: opt-in (off by default to save startup RAM — the ~800-mote animation).
                    // When on, it draws topmost and masks everything (app, gate, overlays) until it fades.
                    // Toggle in Settings → Appearance ("Boot sequence").
                    if (settings.bootAnimation && !booted) {
                        BootScreen(onFinished = { booted = true })
                    }
                }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Warm-launch deep link (e.g. a launcher shortcut tapped while Pulse is already running): publish
        // the new route so the composition re-navigates. Ignore intents without a route (a plain re-open).
        intent.getStringExtra(EXTRA_ROUTE)?.let { pendingRouteState.value = it }
    }

    /** One-shot latch so a single held press fires one harvest, however long it is held. */
    private var harvestFired = false

    /**
     * The hardware data harvester's trigger: a CONTINUOUS physical volume key press while inside the
     * app, while something is on the viewscreen, downloads that media into sandboxed storage.
     *
     * ⚠️ Conditional on the player actually holding an item — with nothing on the viewscreen both
     * volume keys behave completely normally everywhere in the app, and a plain single press always
     * steps the volume one notch as usual (`repeatCount == 0` passes through).
     *
     * ⚠️ **Once a hold begins (`repeatCount >= 1`) the events are consumed, so the volume freezes
     * after that first notch rather than ramping to the rails on the way to the trigger.** Holding a
     * volume key to ramp the volume continuously is the one ordinary gesture this sacrifices while
     * media is on the viewscreen — the documented cost of the owner's chosen "continuous press"
     * trigger. A 15-notch blast before the harvest fired would be far worse. The harvest itself
     * fires once at [HARVEST_HOLD_REPEATS], latched until the key is released.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN
        ) {
            val item = dev.mascwa.pulse.feature.theater.OnDemandController.state.value.item
            if (item != null) {
                when (event.action) {
                    android.view.KeyEvent.ACTION_DOWN ->
                        if (event.repeatCount >= 1) {
                            if (event.repeatCount >= HARVEST_HOLD_REPEATS && !harvestFired) {
                                harvestFired = true
                                app.container.mediaHarvester.harvest(item)
                            }
                            return true // consume the hold — freeze the volume, arm the gesture
                        }
                    android.view.KeyEvent.ACTION_UP -> {
                        val wasFired = harvestFired
                        harvestFired = false
                        if (wasFired) return true // swallow the release of a consumed hold
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        app.container.appForeground.value = true
        // Self-gates on the glassesHud setting + a connected external display; defensive so it can't crash.
        hud = runCatching { dev.mascwa.pulse.feature.hud.HudController(this, app.container).also { it.start() } }.getOrNull()
        // Catch a build that turned green while the app was backgrounded — prompt the install on return.
        maybeAutoUpdate()
        // Sensorium arming: every return to foreground is a legal moment to (re)arm the mic/camera
        // FGS types — a service revived on the standby path since the last open upgrades right here.
        lifecycleScope.launch {
            runCatching {
                if (app.container.settingsRepository.current().sensing.enabled) {
                    dev.mascwa.pulse.data.sensing.SensoriumService.start(this@MainActivity, foregroundLaunch = true)
                }
            }
        }
    }

    override fun onStop() {
        app.container.appForeground.value = false
        runCatching { hud?.stop() }
        hud = null
        // Best-effort: persist any buffered on-device learning before the process may be reclaimed.
        lifecycleScope.launch {
            runCatching { app.container.usageRepository.flushNow() }
            runCatching { app.container.cerebellumStore.flushNow() }
            runCatching { app.container.procedureStore.flushNow() }
            runCatching { app.container.profileStore.flushNow() }
            runCatching { app.container.taskStore.flushNow() }
            runCatching { app.container.memoryStream.flushNow() }
            runCatching { app.container.diaryStore.flushNow() }
            runCatching { app.container.interestStore.flushNow() }
            runCatching { app.container.findingStore.flushNow() }
            runCatching { app.container.securityAuditStore.flushNow() }
            runCatching { app.container.oracleLearningStore.flushNow() }
            runCatching { app.container.sensoriumStore.flushNow() }
            runCatching { app.container.studyStore.flushNow() }
            // Refresh the Nova/TeslaUnread badge with the current unread-findings count.
            runCatching {
                dev.mascwa.pulse.shortcuts.UnreadBadge.publish(
                    this@MainActivity, app.container.findingStore.unseenCount(),
                )
            }
        }
        super.onStop()
    }

    companion object {
        const val EXTRA_ROUTE = "pulse.extra.route"

        /**
         * How many auto-repeats a volume key must deliver before a hold counts as the harvest
         * gesture. The platform repeats roughly every 50 ms after an initial ~400 ms delay, so 15
         * repeats is about 1.2 s of continuous hold — long enough that nobody adjusting volume
         * trips it, short enough to feel like a gesture. One constant to tune from real use.
         */
        const val HARVEST_HOLD_REPEATS = 15
        /** Don't re-check GitHub more than once per ~15 min of foregrounding (covers fast app-switches). */
        private const val AUTO_UPDATE_MIN_INTERVAL_MS = 15 * 60 * 1000L
    }
}
