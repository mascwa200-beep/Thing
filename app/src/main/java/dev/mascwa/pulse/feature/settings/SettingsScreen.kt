package dev.mascwa.pulse.feature.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.core.util.installApk
import dev.mascwa.pulse.feature.settings.SettingsViewModel.UpdateUi
import dev.mascwa.pulse.data.settings.CustomFeed
import dev.mascwa.pulse.data.settings.HomeSection
import dev.mascwa.pulse.data.settings.PrecipUnit
import dev.mascwa.pulse.data.settings.TemperatureUnit
import dev.mascwa.pulse.data.settings.ThemeMode
import dev.mascwa.pulse.data.settings.WatchItem
import dev.mascwa.pulse.data.settings.WatchType
import dev.mascwa.pulse.data.settings.WindUnit
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.ui.theme.Pulse
import dev.mascwa.pulse.feature.economy.CountryPicker

@Composable
fun SettingsScreen(vm: SettingsViewModel, onOpenCrashLog: () -> Unit = {}, onOpenSecurityAudit: () -> Unit = {}) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val cacheSize by vm.cacheSize.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    // Collapse state for the sections whose content is rendered as separate LazyColumn items (so the
    // PrefSection header alone can't gate them). Hoisted here so the header + its items toggle together.
    // All start collapsed, matching the rest of Settings.
    var homeCollapsed by rememberSaveable { mutableStateOf(true) }
    var watchlistCollapsed by rememberSaveable { mutableStateOf(true) }
    var feedsCollapsed by rememberSaveable { mutableStateOf(true) }
    var mutedCollapsed by rememberSaveable { mutableStateOf(true) }
    var imageSitesCollapsed by rememberSaveable { mutableStateOf(true) }

    fun notificationsAllowed(): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) dev.mascwa.pulse.core.util.openAppNotificationSettings(context)
    }

    // Requests permission if needed, then fires the test notification.
    val testNotifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) vm.sendTestNotification()
        else dev.mascwa.pulse.core.util.openAppNotificationSettings(context)
    }

    PulseScaffold(title = "Settings") { innerPadding ->
        LazyColumn(modifier = Modifier.padding(innerPadding), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp)) {

            // ----- Software update (in-app updater) -----
            item {
                val u by vm.updateState.collectAsStateWithLifecycle()
                // Auto-check on open so arriving from the update notification lands on the action.
                LaunchedEffect(Unit) { vm.checkForUpdate() }
                PrefSection("Software update", initiallyExpanded = true) {
                    val status = when (val st = u) {
                        is UpdateUi.Checking -> "Checking for a newer build…"
                        is UpdateUi.UpToDate ->
                            "You're on the latest build" + (st.latest?.let { " (v$it)" } ?: "") + "."
                        is UpdateUi.Pending ->
                            "Newer build${st.latest?.let { " v$it" } ?: ""} is still being verified — " +
                                "you're on v${vm.installedVersion}. Offered once CI is green."
                        is UpdateUi.Available -> "Update available — build #${st.info.versionCode}."
                        is UpdateUi.Downloading -> "Downloading ${st.pct}%…"
                        is UpdateUi.ReadyToInstall -> "Downloaded — tap install."
                        is UpdateUi.Error -> st.message
                        else -> "Auto-update is on — newest green build installs itself."
                    }
                    // Compact round HUD buttons (no full-width rows): check, then download / install
                    // as the state advances. Auto-update is always on, so this is just manual override.
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        RoundCyberButton(Icons.Filled.Refresh, "Check for updates") {
                            android.widget.Toast.makeText(context, "Checking for updates…", android.widget.Toast.LENGTH_SHORT).show()
                            vm.checkForUpdate()
                        }
                        when (val st = u) {
                            is UpdateUi.Available -> RoundCyberButton(Icons.Filled.Download, "Download & install") { vm.downloadUpdate() }
                            is UpdateUi.ReadyToInstall -> RoundCyberButton(Icons.Filled.InstallMobile, "Install now") { installApk(context, st.file) }
                            is UpdateUi.Error -> RoundCyberButton(Icons.Filled.Download, "Retry") { vm.downloadUpdate() }
                            else -> {}
                        }
                        Column(Modifier.weight(1f)) {
                            Text("v${vm.installedVersion}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item { HorizontalDivider() }

            // ----- Device, OS & special access -----
            item {
                val graphene = remember { dev.mascwa.pulse.core.device.GrapheneOs.detect(context) }
                val gate = remember { dev.mascwa.pulse.core.device.DeviceGate.evaluate() }
                val deviceOwner = remember {
                    runCatching {
                        context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
                            ?.isDeviceOwnerApp(context.packageName) == true
                    }.getOrDefault(false)
                }
                val attestScope = rememberCoroutineScope()
                var attestRunning by remember { mutableStateOf(false) }
                var attestation by remember {
                    mutableStateOf<dev.mascwa.pulse.core.device.DeviceAttestation.Report?>(null)
                }
                PrefSection("Device & OS") {
                    PrefClickable(
                        "Hardware",
                        value = if (gate.isMatch) "Pixel 10 Pro XL ✓" else "Unsupported",
                        subtitle = "Detected: ${gate.detectedModel} (${gate.detectedDevice})",
                        onClick = {},
                    )
                    PrefClickable(
                        "Operating system",
                        value = if (graphene.isGraphene) "GrapheneOS ✓" else "Not detected",
                        subtitle = if (graphene.isGraphene) "Signals: ${graphene.signals.joinToString(", ")}"
                        else "This build is intended for GrapheneOS; detection is heuristic.",
                        onClick = {},
                    )
                    PrefClickable(
                        "Device owner",
                        value = if (deviceOwner) "Provisioned ✓" else "Not set",
                        // NOTE: this is NOT the "Allow restricted settings" toggle — Device Owner is a separate,
                        // higher-privilege state that can only be set over adb, and only on a device with no
                        // accounts. The class must be the absolute name (the applicationId carries a `.debug`
                        // suffix the manifest namespace doesn't, so a relative `.security.…` would resolve wrong).
                        subtitle = "Separate from restricted settings — provision once over adb on a device with " +
                            "NO accounts:\nadb shell dpm set-device-owner " +
                            "${context.packageName}/dev.mascwa.pulse.security.PulseDeviceAdminReceiver",
                        onClick = {},
                    )
                    PrefClickable(
                        "Hardware attestation",
                        value = when {
                            attestRunning -> "checking…"
                            attestation == null -> "tap to verify"
                            attestation?.verdict?.grapheneVerified == true -> "GrapheneOS ✓"
                            attestation?.available == true -> "see below"
                            else -> "unavailable"
                        },
                        subtitle = attestationSubtitle(attestation, attestRunning),
                        onClick = {
                            if (!attestRunning) {
                                attestRunning = true
                                attestScope.launch {
                                    val r = withContext(Dispatchers.IO) {
                                        dev.mascwa.pulse.core.device.DeviceAttestation().run()
                                    }
                                    attestation = r
                                    attestRunning = false
                                }
                            }
                        },
                    )
                    PrefClickable(
                        "GrapheneOS per-app controls",
                        subtitle = "Network, Sensors & Storage Scopes for Pulse (GrapheneOS-exclusive) live in App info.",
                        onClick = { dev.mascwa.pulse.core.util.openAppInfo(context) },
                    )
                }
            }

            // ----- Device-owner security policies (effective only once provisioned) -----
            item {
                val dpc = remember { dev.mascwa.pulse.security.DevicePolicyController(context) }
                val isOwner = remember { dpc.isDeviceOwner() }
                val usbSupported = remember { dpc.usbDataControlSupported() }
                var usbDataOn by remember { mutableStateOf(dpc.isUsbDataEnabled()) }
                var camDisabled by remember { mutableStateOf(dpc.isCameraDisabled()) }
                var wipeN by remember { mutableStateOf(dpc.maxFailedForWipe()) }
                var pendingWipe by remember { mutableStateOf<Int?>(null) }
                var wipeError by remember { mutableStateOf(false) }
                PrefSection("Device-owner controls") {
                    if (!isOwner) {
                        Text(
                            "These hardware-backed protections need Pulse provisioned as Device Owner (see " +
                                "“Device owner” above). Until then they're inert.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    PrefSwitch(
                        "Disable USB data (charging only)",
                        if (usbSupported) "Cut the USB data lines so a cable can only charge — anti-forensic. " +
                            "Turn back on here to use adb / file transfer again."
                        else "This device can't disable USB data signaling.",
                        checked = !usbDataOn,
                        enabled = isOwner && usbSupported,
                    ) { disable ->
                        if (dpc.setUsbDataEnabled(!disable)) {
                            usbDataOn = !disable
                            vm.recordDevicePolicy("usb_data", "enabled=${!disable}")
                        }
                    }
                    PrefSwitch(
                        "Disable all cameras",
                        "Hardware-disable every camera, device-wide, until turned back off here.",
                        checked = camDisabled,
                        enabled = isOwner,
                    ) { disable ->
                        if (dpc.setCameraDisabled(disable)) {
                            camDisabled = disable
                            vm.recordDevicePolicy("camera_disabled", "$disable")
                        }
                    }
                    SingleChoiceRow(
                        "Wipe after failed unlocks",
                        wipeN,
                        listOf(0 to "Off", 10 to "10 tries", 20 to "20 tries", 30 to "30 tries"),
                        enabled = isOwner,
                    ) { n ->
                        wipeError = false
                        if (n <= 0) {
                            // Disarming is safe — apply immediately.
                            if (dpc.setMaxFailedForWipe(0)) {
                                wipeN = 0
                                vm.recordDevicePolicy("wipe_after_fails", "0")
                            } else wipeError = true
                        } else {
                            // Arming a factory-reset is irreversible-on-trigger — confirm first.
                            pendingWipe = n
                        }
                    }
                    if (wipeN > 0) {
                        Text(
                            "⚠ After $wipeN wrong unlock attempts the device factory-resets. Anti-theft — set to Off to disable.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    if (wipeError) {
                        Text(
                            "Couldn't apply that — the system rejected the value (it enforces a minimum). Try a higher count.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
                pendingWipe?.let { n ->
                    AlertDialog(
                        onDismissRequest = { pendingWipe = null },
                        title = { Text("Arm device wipe?") },
                        text = {
                            Text(
                                "After $n wrong unlock attempts, this device will FACTORY-RESET — erasing " +
                                    "everything on it. This is an anti-theft measure and can't be undone once it " +
                                    "triggers. Set it to Off any time to disarm.",
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                if (dpc.setMaxFailedForWipe(n)) {
                                    wipeN = n; wipeError = false
                                    vm.recordDevicePolicy("wipe_after_fails", "$n")
                                } else wipeError = true
                                pendingWipe = null
                            }) { Text("Arm wipe") }
                        },
                        dismissButton = {
                            TextButton(onClick = { pendingWipe = null }) { Text("Cancel") }
                        },
                    )
                }
            }

            // ----- GrapheneOS hardening (surface the OS's own controls; they beat app-level versions) -----
            item {
                val isGraphene = remember { dev.mascwa.pulse.core.device.GrapheneOs.detect(context).isGraphene }
                val sandboxedPlay = remember { dev.mascwa.pulse.core.device.GrapheneOs.hasSandboxedPlay(context) }
                PrefSection("GrapheneOS hardening") {
                    if (!isGraphene) {
                        Text(
                            "GrapheneOS not detected — these are pointers to GrapheneOS's own hardening controls.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    PrefClickable(
                        "Exploit protection (Memory Tagging)",
                        value = "App info",
                        subtitle = "GrapheneOS can enable hardware Memory Tagging (MTE) + stricter exploit " +
                            "protection per app, using the Tensor chip. Toggle it for Pulse in App info → " +
                            "GrapheneOS settings. (Better than forcing it in the build — you stay in control.)",
                        onClick = { dev.mascwa.pulse.core.util.openAppInfo(context) },
                    )
                    PrefClickable(
                        "USB-C port control",
                        subtitle = "For the strongest anti-forensic USB lockdown, prefer GrapheneOS's native " +
                            "Settings → Security → “USB-C port” → charging-only when locked. It's OS-level and " +
                            "more robust than the app's Device-Owner USB toggle above.",
                        onClick = { dev.mascwa.pulse.core.util.openSecuritySettings(context) },
                    )
                    PrefClickable(
                        "Sandboxed Google Play",
                        value = if (sandboxedPlay) "Installed ✓" else "Not installed",
                        subtitle = "GrapheneOS runs Play Services as a normal sandboxed app (no privileged " +
                            "access). Pulse needs none of it — it's purely informational.",
                        onClick = { dev.mascwa.pulse.core.util.openAppInfo(context) },
                    )
                }
            }

            // ----- Special access & restricted settings -----
            item {
                PrefSection("Special access & restricted settings") {
                    PrefClickable(
                        "Allow restricted settings",
                        subtitle = "Sideloaded apps are blocked from sensitive toggles on Android 13+. Open App info, " +
                            "tap ⋮ (top-right) → “Allow restricted settings”, then grant what you need.",
                        onClick = { dev.mascwa.pulse.core.util.openAppInfo(context) },
                    )
                    PrefClickable(
                        "Usage access",
                        subtitle = "Lets the on-device security audit read per-app data/battery use.",
                        onClick = { dev.mascwa.pulse.core.util.openUsageAccessSettings(context) },
                    )
                    PrefClickable(
                        "Install unknown apps",
                        subtitle = "Required so the in-app updater can install the new APK.",
                        onClick = { dev.mascwa.pulse.core.util.requestInstallPermission(context) },
                    )
                    PrefClickable(
                        "App info",
                        subtitle = "Per-app permissions, restricted-settings toggle, and (on GrapheneOS) Network/Sensors.",
                        onClick = { dev.mascwa.pulse.core.util.openAppInfo(context) },
                    )
                }
            }

            // ----- Accessibility -----
            item {
                PrefSection("Accessibility") {
                    PrefSwitch("High contrast", "Stronger contrast across the UI", s.highContrast) { v ->
                        vm.update { it.copy(highContrast = v) }
                    }
                    PrefSwitch("Haptics", "Vibration feedback on key actions", s.haptics) { v ->
                        vm.update { it.copy(haptics = v) }
                    }
                    PrefClickable(
                        "System accessibility",
                        subtitle = "Font & display size, TalkBack, colour correction, and “remove animations” " +
                            "(Pulse honours reduce-motion).",
                        onClick = { dev.mascwa.pulse.core.util.openAccessibilitySettings(context) },
                    )
                }
            }

            // ----- Diagnostics & debug reporting -----
            item {
                PrefSection("Diagnostics") {
                    PrefSwitch(
                        "Auto-send debug reports",
                        "On a crash, upload a secret-scrubbed report to the repo's debug-reports branch on " +
                            "next launch (needs a GitHub token) so issues can be read remotely.",
                        s.jarvis.debugReports,
                    ) { v -> vm.update { it.copy(jarvis = it.jarvis.copy(debugReports = v)) } }
                    PrefClickable(
                        "Crash console",
                        subtitle = "View recorded faults and send a debug report now.",
                        onClick = onOpenCrashLog,
                    )
                }
            }
            item { HorizontalDivider() }

            // ----- Appearance -----
            item {
                PrefSection("Appearance") {
                    AccentSwatchRow(selected = s.accentColor, onSelect = { a -> vm.update { it.copy(accentColor = a) } })
                    PrefClickable(
                        "J.A.R.V.I.S. live wallpaper",
                        subtitle = "Arc-reactor home screen with a live readout (clock · objective · weather · markets). " +
                            "Uses your accent + AMOLED choice above.",
                        onClick = {
                            // Open the system live-wallpaper preview pre-pointed at our service; fall back to the
                            // chooser if the direct preview action isn't supported. Class ref = correct package
                            // even with the .debug applicationId suffix.
                            val component = android.content.ComponentName(
                                context, dev.mascwa.pulse.wallpaper.JarvisWallpaperService::class.java,
                            )
                            val direct = android.content.Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                                .putExtra(android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
                            runCatching { context.startActivity(direct) }.onFailure {
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(android.app.WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER),
                                    )
                                }
                            }
                        },
                    )
                    PrefSwitch(
                        "Live wallpaper readout",
                        "Show the clock-line data (objective · weather · markets) on the J.A.R.V.I.S. wallpaper",
                        s.liveWallpaperReadout,
                    ) { v -> vm.update { it.copy(liveWallpaperReadout = v) } }
                    PrefSwitch("AMOLED black", "True-black surfaces, saves OLED power", s.amoledBlack) { v ->
                        vm.update { it.copy(amoledBlack = v) }
                    }
                    PrefSwitch("HUD strip", "Live clock · GPS · link · battery · Kp", s.hudStrip) { v ->
                        vm.update { it.copy(hudStrip = v) }
                    }
                    PrefSwitch("HUD data-stream", "Second-row live telemetry marquee + tap-to-scan", s.hudDataStream) { v ->
                        vm.update { it.copy(hudDataStream = v) }
                    }
                    PrefSwitch("Haptics", "Subtle vibration on key actions", s.haptics) { v ->
                        vm.update { it.copy(haptics = v) }
                    }
                    PrefSwitch("Boot sequence", "Cinematic cold-open on launch (off saves startup RAM)", s.bootAnimation) { v ->
                        vm.update { it.copy(bootAnimation = v) }
                    }
                    PrefSlider(
                        "Ticker speed",
                        value = s.tickerSpeed,
                        valueRange = 0.25f..3f,
                        subtitle = "Auto-scroll rate of the home markets ticker",
                        valueLabel = { "%.2f×".format(it) },
                    ) { v -> vm.update { it.copy(tickerSpeed = v) } }
                }
            }
            item { HorizontalDivider() }

            // ----- Region & units -----
            item {
                PrefSection("Region & units") {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Economy / fuel country", style = MaterialTheme.typography.bodyLarge)
                        CountryPicker(current = s.countryCode, onSelect = { c -> vm.update { it.copy(countryCode = c) } })
                    }
                    EditableValueRow("News region code (gl)", s.newsCountry) { v ->
                        vm.update { it.copy(newsCountry = v.uppercase().take(2)) }
                    }
                    EditableValueRow("News language (hl)", s.newsLanguage) { v ->
                        vm.update { it.copy(newsLanguage = v.lowercase().take(2)) }
                    }
                    EditableValueRow("Currency code", s.currencyCode) { v ->
                        vm.update { it.copy(currencyCode = v.uppercase().take(3)) }
                    }
                    SingleChoiceRow(
                        "Temperature", s.temperatureUnit, TemperatureUnit.entries.map { it to it.symbol },
                    ) { u -> vm.update { it.copy(temperatureUnit = u) } }
                    SingleChoiceRow(
                        "Wind speed", s.windUnit, WindUnit.entries.map { it to it.symbol },
                    ) { u -> vm.update { it.copy(windUnit = u) } }
                    SingleChoiceRow(
                        "Precipitation", s.precipUnit, PrecipUnit.entries.map { it to it.symbol },
                    ) { u -> vm.update { it.copy(precipUnit = u) } }
                    PrefSwitch("24-hour clock", checked = s.use24HourClock,
                        onChange = { v -> vm.update { it.copy(use24HourClock = v) } })
                }
            }
            item { HorizontalDivider() }

            // ----- Data & refresh -----
            item {
                PrefSection("Data & refresh") {
                    SingleChoiceRow(
                        "Background refresh", s.refreshIntervalMinutes,
                        listOf(15 to "15 min", 30 to "30 min", 60 to "1 hour", 120 to "2 hours", 240 to "4 hours"),
                    ) { m -> vm.update { it.copy(refreshIntervalMinutes = m) } }
                    PrefSwitch("Refresh on Wi-Fi only", checked = s.refreshOnlyOnWifi,
                        onChange = { v -> vm.update { it.copy(refreshOnlyOnWifi = v) } })
                    SingleChoiceRow(
                        "Articles per category", s.newsItemsPerCategory,
                        listOf(15 to "15", 30 to "30", 50 to "50"),
                    ) { n -> vm.update { it.copy(newsItemsPerCategory = n) } }
                }
            }
            item { HorizontalDivider() }

            // ----- Notifications -----
            item {
                PrefSection("Notifications") {
                    if (Build.VERSION.SDK_INT >= 33) {
                        PrefClickable(
                            "Grant notification permission",
                            subtitle = "Required on Android 13+ to receive alerts",
                            onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                        )
                    }
                    PrefSwitch("Enable notifications", checked = s.notifications.masterEnabled,
                        onChange = { v -> vm.update { it.copy(notifications = it.notifications.copy(masterEnabled = v)) } })
                    val on = s.notifications.masterEnabled
                    PrefSwitch("Breaking news", checked = s.notifications.breakingNews, enabled = on,
                        onChange = { v -> vm.update { it.copy(notifications = it.notifications.copy(breakingNews = v)) } })
                    PrefSwitch("Live breaking news (~90s, needs resident J.A.R.V.I.S.)",
                        checked = s.notifications.liveBreakingNews, enabled = on && s.notifications.breakingNews,
                        onChange = { v -> vm.update { it.copy(notifications = it.notifications.copy(liveBreakingNews = v)) } })
                    PrefSwitch("Market & price alerts", checked = s.notifications.marketAlerts, enabled = on,
                        onChange = { v -> vm.update { it.copy(notifications = it.notifications.copy(marketAlerts = v)) } })
                    PrefSwitch("Weather alerts", checked = s.notifications.weatherAlerts, enabled = on,
                        onChange = { v -> vm.update { it.copy(notifications = it.notifications.copy(weatherAlerts = v)) } })
                    PrefSwitch("Space & sky alerts", checked = s.notifications.spaceAlerts, enabled = on,
                        onChange = { v -> vm.update { it.copy(notifications = it.notifications.copy(spaceAlerts = v)) } })
                    PrefSwitch("Aurora likely (your location)", checked = s.notifications.auroraAlerts, enabled = on,
                        onChange = { v -> vm.update { it.copy(notifications = it.notifications.copy(auroraAlerts = v)) } })
                    PrefSwitch("Safety / nearby incidents", checked = s.notifications.safetyAlerts, enabled = on,
                        onChange = { v -> vm.update { it.copy(notifications = it.notifications.copy(safetyAlerts = v)) } })
                    PrefSwitch("Overhead flights (Tacnet)", checked = s.notifications.flightAlerts, enabled = on,
                        onChange = { v -> vm.update { it.copy(notifications = it.notifications.copy(flightAlerts = v)) } })
                    PrefSwitch("Survival check-ins (Pip-Boy)", checked = s.notifications.survivalAlerts, enabled = on,
                        onChange = { v -> vm.update { it.copy(notifications = it.notifications.copy(survivalAlerts = v)) } })
                    PrefSwitch("Survival tips (frequent)", checked = s.notifications.survivalTips, enabled = on,
                        onChange = { v -> vm.update { it.copy(notifications = it.notifications.copy(survivalTips = v)) } })
                    PrefSwitch("Daily digest", checked = s.notifications.dailyDigest, enabled = on,
                        onChange = { v -> vm.update { it.copy(notifications = it.notifications.copy(dailyDigest = v)) } })
                    PrefSwitch("App update alerts", checked = s.notifications.updateChecks, enabled = on,
                        onChange = { v -> vm.update { it.copy(notifications = it.notifications.copy(updateChecks = v)) } })
                    SingleChoiceRow(
                        "Digest time", s.notifications.digestHour,
                        (0..23).map { it to "%02d:00".format(it) }, enabled = on && s.notifications.dailyDigest,
                    ) { h -> vm.update { it.copy(notifications = it.notifications.copy(digestHour = h)) } }
                    SingleChoiceRow(
                        "Market alert threshold", s.notifications.marketMovePercent,
                        listOf(1.0 to "±1%", 2.0 to "±2%", 3.0 to "±3%", 5.0 to "±5%", 10.0 to "±10%"),
                        enabled = on && s.notifications.marketAlerts,
                    ) { p -> vm.update { it.copy(notifications = it.notifications.copy(marketMovePercent = p)) } }
                    PrefSwitch("Quiet hours", checked = s.notifications.quietHoursEnabled, enabled = on,
                        onChange = { v -> vm.update { it.copy(notifications = it.notifications.copy(quietHoursEnabled = v)) } })
                    if (s.notifications.quietHoursEnabled) {
                        SingleChoiceRow("Quiet start", s.notifications.quietStartHour,
                            (0..23).map { it to "%02d:00".format(it) }, enabled = on) { h ->
                            vm.update { it.copy(notifications = it.notifications.copy(quietStartHour = h)) }
                        }
                        SingleChoiceRow("Quiet end", s.notifications.quietEndHour,
                            (0..23).map { it to "%02d:00".format(it) }, enabled = on) { h ->
                            vm.update { it.copy(notifications = it.notifications.copy(quietEndHour = h)) }
                        }
                    }
                    PrefClickable(
                        "Send test notification",
                        subtitle = "Posts a sample alert (grants permission first if needed)",
                        onClick = {
                            if (notificationsAllowed()) vm.sendTestNotification()
                            else testNotifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        },
                    )
                }
            }
            item { HorizontalDivider() }

            // ----- Security & network (Trusted Network Mode + encryption) -----
            item {
                PrefSection("Security & network") {
                    PrefSwitch(
                        "Ambient sensing (game)",
                        "Let the S.P.E.C.I.A.L. game sense your surroundings via the camera + mic while its " +
                            "screen is open — classified on-device to text labels only, nothing stored or sent. " +
                            "Off = the game uses a neutral scene and the camera/mic are never touched.",
                        checked = s.ambientSensing,
                        onChange = { v -> vm.update { it.copy(ambientSensing = v) } },
                    )
                    PrefClickable(
                        "Security auditor",
                        subtitle = "Read-only, local-only audit: app permissions, trust store, encryption & " +
                            "data drain. Findings stay on-device.",
                        onClick = onOpenSecurityAudit,
                    )
                    PrefSwitch(
                        "Trusted Network Mode",
                        "Disable Wi-Fi when you leave the home network and cellular takes over (dual-verified: " +
                            "home lost AND cellular up). Re-enables on return. Needs Device-Owner provisioning to " +
                            "toggle the radio — otherwise it just notifies.",
                        checked = s.security.trustedNetworkMode,
                        onChange = { v -> vm.update { it.copy(security = it.security.copy(trustedNetworkMode = v)) } },
                    )
                    PrefClickable(
                        "Wi-Fi control",
                        value = if (vm.isDeviceOwner) "Device Owner ✓" else "Not provisioned",
                        subtitle = if (vm.isDeviceOwner)
                            "Pulse can toggle Wi-Fi. Stays minimal: no wipe/lock/password powers."
                        else
                            "To let Pulse actually toggle Wi-Fi, provision it once over adb on a device with no " +
                                "other accounts:\nadb shell dpm set-device-owner " +
                                "dev.mascwa.pulse.debug/dev.mascwa.pulse.security.PulseDeviceAdminReceiver\n" +
                                "Until then, the mode just notifies you to toggle Wi-Fi yourself.",
                        onClick = { },
                    )
                    // Home networks
                    val currentNet = vm.currentNetworkName()
                    PrefClickable(
                        "Add current Wi-Fi as home",
                        value = currentNet ?: "not on Wi-Fi",
                        subtitle = "Designate the network you're on now as a home network.",
                        onClick = { currentNet?.let { vm.addHomeSsid(it) } },
                    )
                    if (s.security.homeSsids.isEmpty()) {
                        Text(
                            "No home networks set. Add the one you're on at home so Pulse knows when you've left it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    s.security.homeSsids.forEach { ssid ->
                        PrefClickable(ssid, value = "remove", subtitle = "Home network", onClick = { vm.removeHomeSsid(ssid) })
                    }
                    SingleChoiceRow(
                        "Re-probe after",
                        s.security.reprobeMinutes,
                        listOf(5, 10, 15, 30, 60).map { it to "$it min" },
                    ) { m -> vm.update { it.copy(security = it.security.copy(reprobeMinutes = m)) } }
                    PrefSwitch(
                        "Notify if not provisioned",
                        "Show a notification when Pulse wants to toggle Wi-Fi but isn't a Device Owner yet.",
                        checked = s.security.notifyWhenUnprovisioned,
                        onChange = { v -> vm.update { it.copy(security = it.security.copy(notifyWhenUnprovisioned = v)) } },
                    )
                    PrefSwitch(
                        "Encrypt secrets at rest",
                        "Encrypt your cloud & GitHub tokens on disk with the Android Keystore.",
                        checked = s.security.encryptSecretsAtRest,
                        onChange = { v -> vm.update { it.copy(security = it.security.copy(encryptSecretsAtRest = v)) } },
                    )
                    PrefSwitch(
                        "HTTPS-only egress",
                        "Block cleartext (non-HTTPS) outbound app/API requests except whitelisted hosts, and " +
                            "log any that are blocked. (Doesn't affect the radio player, which streams audio " +
                            "on a separate path.)",
                        checked = s.security.httpsOnly,
                        onChange = { v -> vm.update { it.copy(security = it.security.copy(httpsOnly = v)) } },
                    )
                }
            }
            item { HorizontalDivider() }

            // ----- Home dashboard -----
            collapsibleHeader("Home dashboard", homeCollapsed) { homeCollapsed = !homeCollapsed }
            if (!homeCollapsed) {
                item {
                    Text(
                        "Toggle and reorder the sections shown on Home.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                item {
                    HomeSectionEditor(
                        enabledOrdered = s.homeSections,
                        onChange = { list -> vm.update { it.copy(homeSections = list) } },
                    )
                }
            }
            item { HorizontalDivider() }

            // ----- Watchlist -----
            collapsibleHeader("Markets watchlist", watchlistCollapsed) { watchlistCollapsed = !watchlistCollapsed }
            if (!watchlistCollapsed) {
                watchlistEditor(
                    title = "Symbols (Stooq) & crypto (CoinGecko)",
                    items = s.watchlist + s.cryptoList,
                    onRemove = { item ->
                        vm.update {
                            it.copy(
                                watchlist = it.watchlist.filterNot { w -> w.id == item.id },
                                cryptoList = it.cryptoList.filterNot { w -> w.id == item.id },
                            )
                        }
                    },
                )
                item {
                    AddWatchRow { item ->
                        vm.update {
                            if (item.type == WatchType.CRYPTO) it.copy(cryptoList = (it.cryptoList + item).distinctBy { w -> w.id })
                            else it.copy(watchlist = (it.watchlist + item).distinctBy { w -> w.id })
                        }
                    }
                }
            }
            item { HorizontalDivider() }

            // ----- Custom feeds -----
            collapsibleHeader("Custom RSS feeds", feedsCollapsed) { feedsCollapsed = !feedsCollapsed }
            if (!feedsCollapsed) {
                itemsIndexed(s.customFeeds) { i, feed ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(feed.name, style = MaterialTheme.typography.bodyLarge)
                            Text(feed.url, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        IconButton(onClick = {
                            vm.update { it.copy(customFeeds = it.customFeeds.filterIndexed { idx, _ -> idx != i }) }
                        }) { Icon(Icons.Filled.Delete, "Remove") }
                    }
                }
                item {
                    AddFeedRow { feed ->
                        vm.update { it.copy(customFeeds = (it.customFeeds + feed)) }
                    }
                }
            }
            item { HorizontalDivider() }

            // ----- Muted keywords -----
            collapsibleHeader("Muted keywords", mutedCollapsed) { mutedCollapsed = !mutedCollapsed }
            if (!mutedCollapsed) {
                itemsIndexed(s.mutedKeywords) { i, kw ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(kw, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        IconButton(onClick = {
                            vm.update { it.copy(mutedKeywords = it.mutedKeywords.filterIndexed { idx, _ -> idx != i }) }
                        }) { Icon(Icons.Filled.Delete, "Remove") }
                    }
                }
                item {
                    AddTextRow("Add keyword to mute") { kw ->
                        vm.update { it.copy(mutedKeywords = (it.mutedKeywords + kw).distinct()) }
                    }
                }
            }
            item { HorizontalDivider() }

            // ----- Image search sites -----
            collapsibleHeader("Image search sites", imageSitesCollapsed) { imageSitesCollapsed = !imageSitesCollapsed }
            if (!imageSitesCollapsed) {
                item {
                    Text(
                        "Sites you've added on the Images screen (a %s is replaced with your keyword).",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                itemsIndexed(s.customImageSites) { i, site ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(site, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        IconButton(onClick = {
                            vm.update { it.copy(customImageSites = it.customImageSites.filterIndexed { idx, _ -> idx != i }) }
                        }) { Icon(Icons.Filled.Delete, "Remove") }
                    }
                }
                item {
                    AddTextRow("Add image site URL") { url ->
                        if (url.startsWith("http")) {
                            vm.update { it.copy(customImageSites = (it.customImageSites + url.trim()).distinct()) }
                        }
                    }
                }
            }
            item { HorizontalDivider() }

            // ----- Optional API keys -----
            item {
                PrefSection("Optional API keys") {
                    Text(
                        "All sections work without keys. Add free keys to unlock richer sources.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    EditableValueRow("NewsAPI.org", masked(s.apiKeys.newsApi), "https://newsapi.org/register") { v ->
                        vm.update { it.copy(apiKeys = it.apiKeys.copy(newsApi = v.trim())) }
                    }
                    EditableValueRow("FRED (US economy)", masked(s.apiKeys.fred), "https://fredaccount.stlouisfed.org/apikeys") { v ->
                        vm.update { it.copy(apiKeys = it.apiKeys.copy(fred = v.trim())) }
                    }
                    EditableValueRow("EIA (US fuel)", masked(s.apiKeys.eia), "https://www.eia.gov/opendata/register.php") { v ->
                        vm.update { it.copy(apiKeys = it.apiKeys.copy(eia = v.trim())) }
                    }
                    EditableValueRow("Finnhub (markets)", masked(s.apiKeys.finnhub), "https://finnhub.io/register") { v ->
                        vm.update { it.copy(apiKeys = it.apiKeys.copy(finnhub = v.trim())) }
                    }
                    EditableValueRow("OpenWeatherMap", masked(s.apiKeys.openWeatherMap), "https://home.openweathermap.org/api_keys") { v ->
                        vm.update { it.copy(apiKeys = it.apiKeys.copy(openWeatherMap = v.trim())) }
                    }
                    EditableValueRow("NASA (asteroids)", masked(s.apiKeys.nasa), "https://api.nasa.gov/") { v ->
                        vm.update { it.copy(apiKeys = it.apiKeys.copy(nasa = v.trim())) }
                    }
                }
            }
            item { HorizontalDivider() }

            // ----- Safety (SOS) -----
            item {
                PrefSection("Safety (SOS)") {
                    Text(
                        "Medical info & contacts for the SOS screen. Stays on this device.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    val card = s.emergencyCard
                    EditableValueRow("Full name", card.fullName.ifBlank { "Not set" }) { v ->
                        vm.update { it.copy(emergencyCard = it.emergencyCard.copy(fullName = v.trim())) }
                    }
                    EditableValueRow("Blood type", card.bloodType.ifBlank { "Not set" }) { v ->
                        vm.update { it.copy(emergencyCard = it.emergencyCard.copy(bloodType = v.trim())) }
                    }
                    EditableValueRow("Allergies", card.allergies.ifBlank { "Not set" }) { v ->
                        vm.update { it.copy(emergencyCard = it.emergencyCard.copy(allergies = v.trim())) }
                    }
                    EditableValueRow("Medications", card.medications.ifBlank { "Not set" }) { v ->
                        vm.update { it.copy(emergencyCard = it.emergencyCard.copy(medications = v.trim())) }
                    }
                    EditableValueRow("Conditions", card.conditions.ifBlank { "Not set" }) { v ->
                        vm.update { it.copy(emergencyCard = it.emergencyCard.copy(conditions = v.trim())) }
                    }
                    EditableValueRow("Notes", card.notes.ifBlank { "Not set" }) { v ->
                        vm.update { it.copy(emergencyCard = it.emergencyCard.copy(notes = v.trim())) }
                    }
                    PrefSwitch(
                        "Auto-send SOS SMS",
                        "One tap sends your coordinates to contacts (requests SMS permission)",
                        s.autoSendSos,
                    ) { v -> vm.update { it.copy(autoSendSos = v) } }
                    Text("Emergency contacts", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    s.emergencyContacts.forEachIndexed { i, contact ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(contact.name, style = MaterialTheme.typography.bodyLarge)
                                Text(contact.phone, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = {
                                vm.update { it.copy(emergencyContacts = it.emergencyContacts.filterIndexed { idx, _ -> idx != i }) }
                            }) { Icon(Icons.Filled.Delete, "Remove") }
                        }
                    }
                    AddContactRow { contact ->
                        vm.update { it.copy(emergencyContacts = it.emergencyContacts + contact) }
                    }
                }
            }
            item { HorizontalDivider() }

            // ----- Backup & restore (local, offline) -----
            item {
                val backupStatus by vm.backupStatus.collectAsStateWithLifecycle()
                val exportLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/json"),
                ) { uri -> if (uri != null) vm.exportSettings(context, uri) }
                val importLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri -> if (uri != null) vm.importSettings(context, uri) }
                PrefSection("Backup & restore") {
                    PrefClickable(
                        "Back up settings",
                        subtitle = "Save your watchlist, locations, emergency card, waypoints & preferences " +
                            "to a file. API keys & tokens are left out for safety.",
                        onClick = { exportLauncher.launch("pulse-backup.json") },
                    )
                    PrefClickable(
                        "Restore from backup",
                        subtitle = "Load settings from a backup file. Your current API keys & tokens are kept. " +
                            "Handy after the one-time reinstall.",
                        onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    )
                    if (backupStatus.isNotBlank()) {
                        Text(
                            backupStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            item { HorizontalDivider() }

            // ----- Storage & about -----
            item {
                val ledgerStatus by vm.auditLedgerStatus.collectAsStateWithLifecycle()
                val selfTest by vm.ledgerSelfTestResult.collectAsStateWithLifecycle()
                val selfTestRunning by vm.ledgerSelfTestRunning.collectAsStateWithLifecycle()
                PrefSection("Storage & about") {
                    PrefClickable("Cached data", value = Formatters.compact(cacheSize.toDouble()) + " B",
                        onClick = { vm.refreshCacheSize() })
                    PrefClickable("Clear cache", onClick = { vm.clearCache() })
                    PrefSwitch(
                        "Detailed activity log",
                        "Log full content — your messages and the assistant's tool-call inputs — to the " +
                            "on-device activity log, which your cloud brain can read when cloud chat is on. " +
                            "Raw API keys & tokens are always scrubbed. Off = operational events only (no content).",
                        checked = s.jarvis.verboseActivityLog,
                        onChange = { v -> vm.update { it.copy(jarvis = it.jarvis.copy(verboseActivityLog = v)) } },
                    )
                    PrefClickable(
                        "Clear usage data",
                        subtitle = "Forget the on-device usage history & real-time activity log that power " +
                            "J.A.R.V.I.S.'s tailored tips and self-awareness. Never leaves your device.",
                        onClick = { vm.clearUsageData() },
                    )
                    PrefClickable(
                        "Reset learned reflexes",
                        subtitle = "Wipe the virtual cerebellum — the practiced skills & reliability " +
                            "J.A.R.V.I.S. has learned from its own actions. It relearns from scratch.",
                        onClick = { vm.resetReflexes() },
                    )
                    PrefClickable(
                        "Clear remembered profile",
                        subtitle = "Forget the preferences, interests & projects J.A.R.V.I.S. has " +
                            "remembered about you. On-device only.",
                        onClick = { vm.clearProfile() },
                    )
                    PrefClickable(
                        "Clear tracked tasks",
                        subtitle = "Forget the ongoing tasks & goals J.A.R.V.I.S. is tracking for you. " +
                            "On-device only.",
                        onClick = { vm.clearTasks() },
                    )
                    PrefSwitch(
                        "Reflect on memories",
                        "Periodically distil recent moments into higher-level insights J.A.R.V.I.S. keeps " +
                            "(Mnemosyne reflection). Uses the cloud brain, throttled; off = raw moments only.",
                        s.jarvis.reflectionEnabled,
                    ) { v -> vm.update { it.copy(jarvis = it.jarvis.copy(reflectionEnabled = v)) } }
                    PrefClickable(
                        "Clear episodic memory",
                        subtitle = "Forget the timestamped moments J.A.R.V.I.S. remembers from your " +
                            "conversations (recalled by recency, importance & relevance). On-device only.",
                        onClick = { vm.clearMemoryStream() },
                    )
                    PrefClickable(
                        "Verify audit ledger",
                        value = ledgerStatus,
                        subtitle = "Re-check the tamper-evident blackbox log — chain integrity, hardware " +
                            "signature & last trusted-time anchor. View the full log in J.A.R.V.I.S. → Memory.",
                        onClick = { vm.verifyAuditLedger() },
                    )
                    PrefClickable(
                        "Clear audit ledger",
                        subtitle = "Wipe the on-device tamper-evident audit log (diagnostic uploads, " +
                            "self-code & device-policy events). On-device only.",
                        onClick = { vm.clearAuditLedger() },
                    )
                    PrefSwitch(
                        "Auto-anchor audit ledger",
                        "Periodically timestamp the tamper-evident log to a public RFC-3161 authority " +
                            "(independent proof-of-time). Sends only a hash, ~once a day. Off = anchor " +
                            "manually from J.A.R.V.I.S. → Memory.",
                        s.autoAnchorLedger,
                    ) { v -> vm.update { it.copy(autoAnchorLedger = v) } }
                    PrefClickable(
                        "Run ledger self-test",
                        value = if (selfTestRunning) "Running…" else null,
                        subtitle = "Exercise the blackbox ledger on this device — the hash chain, the " +
                            "secure-element signature, at-rest encryption and a live trusted-timestamp " +
                            "fetch — and report what actually works. Uses throwaway data; doesn't touch " +
                            "your real log.",
                        onClick = { vm.runLedgerSelfTest() },
                    )
                    PrefClickable("Crash log", subtitle = "View & share recent faults (on-device)",
                        onClick = onOpenCrashLog)
                    PrefClickable("Reset all settings", subtitle = "Restore defaults",
                        onClick = { vm.resetToDefaults() })
                    Text(
                        "Pulse · built exclusively for Pixel 10 Pro XL · all data from free public sources.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                selfTest?.let { report ->
                    AlertDialog(
                        onDismissRequest = { vm.dismissLedgerSelfTest() },
                        title = {
                            Text(
                                if (report.allOk) "Ledger self-test — all ${report.total} passed"
                                else "Ledger self-test — ${report.passed}/${report.total} passed",
                            )
                        },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                report.checks.forEach { ch ->
                                    Column {
                                        Text(
                                            "${if (ch.ok) "✓" else "✗"}  ${ch.name}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (ch.ok) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.error,
                                        )
                                        Text(
                                            ch.detail,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { vm.dismissLedgerSelfTest() }) { Text("Done") }
                        },
                    )
                }
            }
        }
    }
}

/**
 * A standalone collapsible section header for sections whose body is rendered as separate LazyColumn
 * items (Home dashboard, watchlist, feeds, muted keywords, image sites). The caller gates the body items
 * with `if (!collapsed)`, so the chevron actually hides the content — same look as [PrefSection]'s header.
 */
private fun LazyListScope.collapsibleHeader(title: String, collapsed: Boolean, onToggle: () -> Unit) {
    item {
        dev.mascwa.pulse.feature.common.CyberHeader(title, collapsed = collapsed, onToggle = onToggle)
    }
}

/** A compact circular CP2077 HUD action button — accent-ringed icon, no full-width row. */
@Composable
private fun RoundCyberButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    val c = Pulse.colors
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(c.accent.copy(alpha = 0.10f))
            .border(BorderStroke(1.5.dp, c.accent), CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = c.accent, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun AccentSwatchRow(selected: dev.mascwa.pulse.data.settings.AccentColor, onSelect: (dev.mascwa.pulse.data.settings.AccentColor) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        dev.mascwa.pulse.data.settings.AccentColor.entries.forEach { a ->
            val color = androidx.compose.ui.graphics.Color(a.argb)
            Box(
                Modifier
                    .size(30.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(9.dp))
                    .background(color)
                    .then(
                        if (a == selected)
                            Modifier.border(2.dp, androidx.compose.ui.graphics.Color.White,
                                androidx.compose.foundation.shape.RoundedCornerShape(9.dp))
                        else Modifier,
                    )
                    .clickable { onSelect(a) },
            )
        }
    }
}

private fun masked(key: String): String =
    if (key.isBlank()) "Not set" else "••••" + key.takeLast(4)

// ---------- small reusable rows / dialogs ----------

@Composable
private fun EditableValueRow(title: String, value: String, helpUrl: String? = null, onSet: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    PrefClickable(title, value = value, onClick = { show = true })
    if (helpUrl != null) {
        val context = androidx.compose.ui.platform.LocalContext.current
        Text(
            "Get a free key →",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth()
                .clickable { dev.mascwa.pulse.core.util.openUrl(context, helpUrl) }
                .padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
        )
    }
    if (show) {
        TextEditDialog(title, initial = if (value == "Not set" || value.startsWith("••••")) "" else value,
            onDismiss = { show = false }, onConfirm = { onSet(it); show = false })
    }
}

@Composable
private fun <T> SingleChoiceRow(
    title: String,
    selected: T,
    options: List<Pair<T, String>>,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    var show by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selected }?.second ?: selected.toString()
    // Anchored dropdown: it opens from the row you tapped (not a centred dialog).
    Box {
        Row(
            Modifier.fillMaxWidth()
                .then(if (enabled) Modifier.clickable { show = true } else Modifier)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text("$current  ▾", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = show, onDismissRequest = { show = false }) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { onSelect(value); show = false },
                    trailingIcon = if (value == selected) {
                        { Icon(Icons.Filled.Check, contentDescription = "Selected") }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun TextEditDialog(
    title: String,
    initial: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun HomeSectionEditor(enabledOrdered: List<HomeSection>, onChange: (List<HomeSection>) -> Unit) {
    // Show enabled (in order) then disabled. Toggle adds/removes; arrows reorder.
    val disabled = HomeSection.entries.filter { it !in enabledOrdered }
    Column {
        enabledOrdered.forEachIndexed { i, section ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(section.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                IconButton(enabled = i > 0, onClick = {
                    val l = enabledOrdered.toMutableList(); l.add(i - 1, l.removeAt(i)); onChange(l)
                }) { Icon(Icons.Filled.ArrowUpward, "Up") }
                IconButton(enabled = i < enabledOrdered.lastIndex, onClick = {
                    val l = enabledOrdered.toMutableList(); l.add(i + 1, l.removeAt(i)); onChange(l)
                }) { Icon(Icons.Filled.ArrowDownward, "Down") }
                Switch(checked = true, onCheckedChange = { onChange(enabledOrdered - section) })
            }
        }
        disabled.forEach { section ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(section.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Switch(checked = false, onCheckedChange = { onChange(enabledOrdered + section) })
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.watchlistEditor(
    title: String,
    items: List<WatchItem>,
    onRemove: (WatchItem) -> Unit,
) {
    item {
        Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp))
    }
    itemsIndexed(items) { _, item ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(item.label, style = MaterialTheme.typography.bodyLarge)
                Text("${item.id} · ${item.type.name.lowercase()}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { onRemove(item) }) { Icon(Icons.Filled.Delete, "Remove") }
        }
    }
}

@Composable
private fun AddWatchRow(onAdd: (WatchItem) -> Unit) {
    var show by remember { mutableStateOf(false) }
    PrefClickable("Add symbol", subtitle = "e.g. tsla.us, ^spx, eurusd, gc.f, or crypto id", onClick = { show = true })
    if (show) {
        var id by remember { mutableStateOf("") }
        var label by remember { mutableStateOf("") }
        var type by remember { mutableStateOf(WatchType.STOCK) }
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text("Add to watchlist") },
            text = {
                Column {
                    OutlinedTextField(id, { id = it }, label = { Text("Symbol / id") }, singleLine = true)
                    OutlinedTextField(label, { label = it }, label = { Text("Display name") }, singleLine = true)
                    PrefRadioGroup(
                        options = WatchType.entries.map { it to it.name.lowercase() },
                        selected = type, onSelect = { type = it },
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = id.isNotBlank(), onClick = {
                    onAdd(WatchItem(id.trim(), label.ifBlank { id }.trim(), type)); show = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AddFeedRow(onAdd: (CustomFeed) -> Unit) {
    var show by remember { mutableStateOf(false) }
    PrefClickable("Add RSS feed", onClick = { show = true })
    if (show) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text("Add RSS feed") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(url, { url = it }, label = { Text("Feed URL") }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Uri))
                }
            },
            confirmButton = {
                TextButton(enabled = url.startsWith("http"), onClick = {
                    onAdd(CustomFeed(name.ifBlank { url }, url.trim())); show = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AddContactRow(onAdd: (dev.mascwa.pulse.data.settings.EmergencyContact) -> Unit) {
    var show by remember { mutableStateOf(false) }
    PrefClickable("Add emergency contact", onClick = { show = true })
    if (show) {
        var name by remember { mutableStateOf("") }
        var phone by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text("Add contact") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(phone, { phone = it }, label = { Text("Phone") }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Phone))
                }
            },
            confirmButton = {
                TextButton(enabled = phone.isNotBlank(), onClick = {
                    onAdd(dev.mascwa.pulse.data.settings.EmergencyContact(name.ifBlank { phone }.trim(), phone.trim()))
                    show = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("Cancel") } },
        )
    }
}

/** Subtitle for the "Hardware attestation" row — shows the verdict and surfaces the verified-boot key so
 *  it can be recorded (the GrapheneOS key isn't on file yet, so this is how we capture the real value). */
private fun attestationSubtitle(
    report: dev.mascwa.pulse.core.device.DeviceAttestation.Report?,
    running: Boolean,
): String {
    if (running) return "Probing the secure element…"
    if (report == null) {
        return "Cryptographically prove verified boot + genuine GrapheneOS via the Titan M2 (key attestation) — " +
            "stronger than the app-presence heuristic above."
    }
    if (!report.available || report.info == null) {
        return "Unavailable: ${report.error ?: "no hardware attestation on this device"}"
    }
    val summary = report.verdict?.summary ?: "Attestation record parsed."
    val key = report.info?.verifiedBootKeyHex
    return if (key != null) "$summary\nVerified-boot key: $key" else summary
}

@Composable
private fun AddTextRow(title: String, onAdd: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    PrefClickable(title, onClick = { show = true })
    if (show) {
        TextEditDialog(title, initial = "", onDismiss = { show = false },
            onConfirm = { if (it.isNotBlank()) onAdd(it.trim()); show = false })
    }
}
