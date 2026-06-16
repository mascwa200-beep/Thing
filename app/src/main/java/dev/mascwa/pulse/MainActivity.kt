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
import dev.mascwa.pulse.ui.effects.ScanlineOverlay
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

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val factory = PulseViewModelFactory(app.container)
        val gateResult = DeviceGate.evaluate()
        val startRoute = intent?.getStringExtra(EXTRA_ROUTE)

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
                    runCatching { dev.mascwa.pulse.jarvis.matrix.ActiveMatrixService.start(this@MainActivity) }
                }
                if (settings.jarvis.vitalsTracking) {
                    runCatching { dev.mascwa.pulse.jarvis.vitals.VitalsTrackingService.start(this@MainActivity) }
                }
            }
        }

        setContent {
            val settings by app.container.settingsRepository.settings
                .collectAsStateWithLifecycle(initialValue = AppSettings())
            val online by app.container.connectivityObserver.isOnline.collectAsStateWithLifecycle()
            val hudVm: dev.mascwa.pulse.feature.common.HudViewModel =
                androidx.lifecycle.viewmodel.compose.viewModel(factory = factory)
            val kp by hudVm.kp.collectAsStateWithLifecycle()
            val auroraPct by hudVm.auroraPct.collectAsStateWithLifecycle()

            NightwireTheme(accent = settings.accentColor, amoledBlack = settings.amoledBlack) {
                var acknowledged by remember { mutableStateOf(false) }
                val gated = !gateResult.isMatch && !acknowledged && !settings.deviceGateAcknowledged

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
                    dev.mascwa.pulse.core.connectivity.LocalIsOnline provides online,
                    dev.mascwa.pulse.feature.common.LocalHud provides dev.mascwa.pulse.feature.common.HudState(
                        kp = kp,
                        hasLocation = app.container.locationProvider.hasPermission(),
                        enabled = settings.hudStrip,
                        auroraPct = auroraPct,
                    ),
                ) {
                Box(Modifier.fillMaxSize()) {
                    if (gated) {
                        DeviceGateScreen(
                            result = gateResult,
                            onContinue = {
                                acknowledged = true
                                lifecycleScope.launch {
                                    app.container.settingsRepository.update { it.copy(deviceGateAcknowledged = true) }
                                }
                            },
                            onExit = { finish() },
                        )
                    } else {
                        PulseApp(factory = factory, startRoute = startRoute, isOnline = online)
                        // Terminal boot sequence, once per launch.
                        if (settings.bootAnimation && !booted) {
                            BootScreen(onFinished = { booted = true })
                        }
                    }
                    // CRT scanline + sweep overlay above everything (never blocks touch).
                    ScanlineOverlay(scanlines = settings.scanlines)
                }
                }
            }
        }
    }

    companion object {
        const val EXTRA_ROUTE = "pulse.extra.route"
    }
}
