package dev.mascwa.pulse

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.mascwa.pulse.core.device.DeviceGate
import dev.mascwa.pulse.data.settings.AppSettings
import dev.mascwa.pulse.di.PulseViewModelFactory
import dev.mascwa.pulse.ui.DeviceGateScreen
import dev.mascwa.pulse.ui.PulseApp
import dev.mascwa.pulse.ui.theme.PulseTheme
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

        // Schedule the background refresh worker per the user's settings.
        lifecycleScope.launch {
            val settings = app.container.settingsRepository.current()
            if (settings.notifications.masterEnabled) {
                app.container.notificationScheduler.schedule(
                    settings.refreshIntervalMinutes, settings.refreshOnlyOnWifi,
                )
            }
        }

        setContent {
            val settings by app.container.settingsRepository.settings
                .collectAsStateWithLifecycle(initialValue = AppSettings())

            PulseTheme(themeMode = settings.theme, dynamicColor = settings.dynamicColor) {
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
                    PulseApp(factory = factory, startRoute = startRoute)
                }
            }
        }
    }

    companion object {
        const val EXTRA_ROUTE = "pulse.extra.route"
    }
}
