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
import dev.mascwa.pulse.data.settings.AppSettings
import dev.mascwa.pulse.di.PulseViewModelFactory
import dev.mascwa.pulse.ui.DeviceGateScreen
import dev.mascwa.pulse.ui.PulseApp
import dev.mascwa.pulse.ui.theme.NightwireTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val app get() = application as PulseApplication

    /** Glasses HUD on a connected external display; lives only while the app is in the foreground. */
    private var hud: dev.mascwa.pulse.feature.hud.HudController? = null

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    /** Wall-clock of the last auto-update check, so a quick app-switch doesn't re-hit GitHub each resume. */
    private var lastAutoUpdateCheckMs = 0L

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
        val startRoute = intent?.getStringExtra(EXTRA_ROUTE)
        runCatching { app.container.usageRepository.log("lifecycle", "app opened") }

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
            }
        }
        // Auto-update runs from onStart (fires on cold launch + every foreground return).

        setContent {
            val settings by app.container.settingsRepository.settings
                .collectAsStateWithLifecycle(initialValue = AppSettings())
            val online by app.container.connectivityObserver.isOnline.collectAsStateWithLifecycle()
            val hudVm: dev.mascwa.pulse.feature.common.HudViewModel =
                androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
            val kp by hudVm.kp.collectAsStateWithLifecycle()
            val auroraPct by hudVm.auroraPct.collectAsStateWithLifecycle()
            val solarWind by hudVm.solarWindKmS.collectAsStateWithLifecycle()
            val bz by hudVm.bz.collectAsStateWithLifecycle()
            val stormLevel by hudVm.stormLevel.collectAsStateWithLifecycle()

            NightwireTheme(accent = settings.accentColor, amoledBlack = settings.amoledBlack) {
                var acknowledged by remember { mutableStateOf(false) }
                val gated = (!gateResult.isMatch || !graphene.isGraphene) &&
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

                androidx.compose.runtime.CompositionLocalProvider(
                    dev.mascwa.pulse.ui.effects.LocalGlitchEnabled provides settings.glitch,
                    dev.mascwa.pulse.ui.effects.LocalHaptics provides settings.haptics,
                    dev.mascwa.pulse.feature.common.LocalTickerSpeed provides settings.tickerSpeed,
                    dev.mascwa.pulse.feature.home.LocalJarvisFeedTopic provides settings.jarvisFeedTopic,
                    dev.mascwa.pulse.core.connectivity.LocalIsOnline provides online,
                    dev.mascwa.pulse.feature.common.LocalHud provides dev.mascwa.pulse.feature.common.HudState(
                        kp = kp,
                        hasLocation = app.container.locationProvider.hasPermission(),
                        enabled = settings.hudStrip,
                        auroraPct = auroraPct,
                        solarWindKmS = solarWind,
                        bz = bz,
                        stormLevel = stormLevel,
                        dataStream = settings.hudDataStream,
                    ),
                ) {
                Box(Modifier.fillMaxSize()) {
                    if (gated) {
                        DeviceGateScreen(
                            result = gateResult,
                            grapheneOk = graphene.isGraphene,
                            osDetail = graphene.summary,
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
                            startRoute = startRoute,
                            isOnline = online,
                            onRouteVisit = { route ->
                                app.container.usageRepository.record(route)
                                app.container.usageRepository.log("nav", route)
                            },
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

    override fun onStart() {
        super.onStart()
        // Self-gates on the glassesHud setting + a connected external display; defensive so it can't crash.
        hud = runCatching { dev.mascwa.pulse.feature.hud.HudController(this, app.container).also { it.start() } }.getOrNull()
        // Catch a build that turned green while the app was backgrounded — prompt the install on return.
        maybeAutoUpdate()
    }

    override fun onStop() {
        runCatching { hud?.stop() }
        hud = null
        // Best-effort: persist any buffered on-device learning before the process may be reclaimed.
        lifecycleScope.launch {
            runCatching { app.container.usageRepository.flushNow() }
            runCatching { app.container.cerebellumStore.flushNow() }
            runCatching { app.container.profileStore.flushNow() }
            runCatching { app.container.taskStore.flushNow() }
            runCatching { app.container.memoryStream.flushNow() }
            runCatching { app.container.diaryStore.flushNow() }
            runCatching { app.container.interestStore.flushNow() }
            runCatching { app.container.findingStore.flushNow() }
            runCatching { app.container.securityAuditStore.flushNow() }
        }
        super.onStop()
    }

    companion object {
        const val EXTRA_ROUTE = "pulse.extra.route"
        /** Don't re-check GitHub more than once per ~15 min of foregrounding (covers fast app-switches). */
        private const val AUTO_UPDATE_MIN_INTERVAL_MS = 15 * 60 * 1000L
    }
}
