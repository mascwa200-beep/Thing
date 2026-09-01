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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import dev.mascwa.pulse.feature.common.LcarsIcons
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.feature.common.LcarsButton
import dev.mascwa.pulse.feature.common.LcarsFrame
import dev.mascwa.pulse.ui.effects.HapticCue
import dev.mascwa.pulse.ui.effects.SoundCue
import dev.mascwa.pulse.ui.effects.rememberLcarsCue
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import dev.mascwa.pulse.remote.RemoteLinkService
import dev.mascwa.pulse.remote.RemotePeers
import dev.mascwa.pulse.feature.settings.SettingsViewModel.UpdateUi
import dev.mascwa.pulse.data.settings.CustomFeed
import dev.mascwa.pulse.data.settings.HomeSection
import dev.mascwa.pulse.data.settings.PrecipUnit
import dev.mascwa.pulse.data.settings.TemperatureUnit
import dev.mascwa.pulse.data.settings.ThemeMode
import dev.mascwa.pulse.data.settings.WatchItem
import dev.mascwa.pulse.data.settings.WatchType
import dev.mascwa.pulse.data.settings.WindUnit
import dev.mascwa.pulse.feature.common.LcarsDialog
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.ui.theme.Pulse
import dev.mascwa.pulse.feature.economy.CountryPicker

@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onOpenCrashLog: () -> Unit = {},
    onOpenSecurityAudit: () -> Unit = {},
    initialCategory: SettingsCategory? = null,
    onBack: (() -> Unit)? = null,
) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val cacheSize by vm.cacheSize.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    // Collapse state for the sections whose content is rendered as separate LazyColumn items (so the
    // PrefSection header alone can't gate them). Hoisted here so the header + its items toggle together.
    // All start collapsed, matching the rest of Settings.
    // Expanded by default now — the Steam-style category page does the hiding, not per-section collapse.
    var homeCollapsed by rememberSaveable { mutableStateOf(false) }
    var watchlistCollapsed by rememberSaveable { mutableStateOf(false) }
    var feedsCollapsed by rememberSaveable { mutableStateOf(false) }
    var mutedCollapsed by rememberSaveable { mutableStateOf(false) }

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

    // ⚠️ Read into state rather than called at each recomposition: `checkSelfPermission` is a
    // binder call, and the Security Audit screen froze outright doing exactly that per frame.
    var smsGranted by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_SMS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    val smsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> smsGranted = granted }

    // Lets the Appearance section sound a cue on demand. Gated by the Interface-sounds switch like
    // every other cue, which is what makes it a useful test of the switch.
    val soundTest = rememberLcarsCue()

    // Requests permission if needed, then fires the test notification.
    val testNotifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) vm.sendTestNotification()
        else dev.mascwa.pulse.core.util.openAppNotificationSettings(context)
    }

    // Steam-style master-detail state: which category is open (null = the compact master list), and the
    // cross-category search query. A section is visible when it belongs to the active category, or (while
    // searching) when its title/keywords match — so a search result is the real, live control.
    // VM-backed (see SettingsViewModel.selectedCategory) — a remember{} here died on tab-away.
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.seedCategory(initialCategory) }
    val selectedCat by vm.selectedCategory.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    // ⚠️ System back leaves the CATEGORY, then the screen — Settings previously exited wholesale
    // from three levels deep. Gated on the sub-state, per the app-wide BackHandler rule.
    androidx.activity.compose.BackHandler(enabled = selectedCat != null) { vm.selectedCategory.value = null }
    val activeCat = selectedCat ?: SettingsCategory.FIRST
    fun vis(cat: SettingsCategory, keywords: String): Boolean {
        val q = query.trim()
        return if (q.isBlank()) cat == activeCat
        else "${cat.title} ${cat.keywords} $keywords".contains(q, ignoreCase = true)
    }

    // The detail pane: the settings sections as a LazyColumn, each gated by category/search. Rendered by the
    // shell wherever the detail belongs (right pane on wide screens, pushed page on a phone).
    val detail: @Composable (Modifier) -> Unit = { detailModifier ->
        LazyColumn(modifier = detailModifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp)) {

            // ----- Software update (in-app updater) -----
            if (vis(SettingsCategory.SYSTEM, "update version build install download apk")) item {
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
                        // Says WHEN, because otherwise this reads as broken: the automatic install
                        // waits until the app is backgrounded (Android kills the process while its
                        // own package is replaced, so doing it mid-screen would look like a crash),
                        // and without that sentence somebody watching this line would see "installs
                        // itself" and then nothing happen for as long as they keep looking at it.
                        else -> "Auto-update is on — the newest green build installs itself, " +
                            "the next time you leave the app."
                    }
                    // Compact round HUD buttons (no full-width rows): check, then download / install
                    // as the state advances. Auto-update is always on, so this is just manual override.
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        RoundCyberButton(LcarsIcons.Refresh, "Check for updates") {
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

            // ----- The companion nutrition app -----
            if (vis(SettingsCategory.SYSTEM, "nutrition companion app download install food macros")) item {
                val n by vm.companionState.collectAsStateWithLifecycle()
                PrefSection("Nutrition app") {
                    val status = when (val st = n) {
                        is UpdateUi.Checking -> "Asking GitHub for the newest build…"
                        is UpdateUi.Available -> "Build #${st.info.versionCode} is ready to download."
                        is UpdateUi.Downloading -> "Downloading ${st.pct}% — it is a large file."
                        is UpdateUi.ReadyToInstall -> "Downloaded — tap install."
                        is UpdateUi.Pending ->
                            "The newest nutrition build is still going through CI. Nothing to install yet."
                        is UpdateUi.Error -> st.message
                        // ⚠️ Says what the thing IS, because nothing else in this app mentions it.
                        // Somebody reading a settings screen has no way to know a second application
                        // exists, and "check" with no explanation is a button that means nothing.
                        else -> "A separate app with just the food and body log — plain, no gate, and " +
                            "it runs on any phone. Same barcode database. Install it here; after that " +
                            "it keeps itself current."
                    }
                    // ⚠️ **A LABELLED button, not the round icon the rest of this screen uses, and
                    // that is the whole fix.** `RoundCyberButton` is a 44dp circle holding a 20dp
                    // icon; its string is a `contentDescription`, so it is read by a screen reader
                    // and by nobody else. Every other control here is something you already know
                    // you want — but this one is the *only* route by which the second application
                    // can get onto the phone at all, and somebody hunting for a download has no
                    // reason to read a small circular glyph as one. Reported as exactly that: no
                    // button anywhere.
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        when (val st = n) {
                            // Installing is the one step Android will not let an ordinary app do
                            // for you, so it stays a separate, differently-worded action.
                            is UpdateUi.ReadyToInstall -> LcarsButton(
                                "INSTALL THE NUTRITION APP",
                                {
                                    installApk(
                                        context,
                                        st.file,
                                        dev.mascwa.pulse.data.update.UpdateRepository.NUTRITION_PACKAGE,
                                    )
                                },
                                Modifier.fillMaxWidth(),
                            )
                            // ⚠️ Disabled rather than hidden while it works. A control that vanishes
                            // mid-operation leaves the screen looking as though the tap did nothing,
                            // which is the same class of confusion this section is being fixed for.
                            is UpdateUi.Checking, is UpdateUi.Downloading -> LcarsButton(
                                "WORKING…",
                                {},
                                Modifier.fillMaxWidth(),
                                enabled = false,
                            )
                            // ⚠️ The size is named because one tap now starts a ~180 MB download
                            // with no second confirmation — see `getCompanion`. An unnamed number
                            // that large is a nasty surprise on a metered connection.
                            else -> LcarsButton(
                                if (st is UpdateUi.Error) "TRY AGAIN — GET THE NUTRITION APP"
                                else "GET THE NUTRITION APP  ·  ~180 MB",
                                { vm.getCompanion() },
                                Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            // ----- Device, OS & special access -----
            if (vis(SettingsCategory.DEVICE, "hardware os graphene attestation sensors")) item {
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
                    // ⚠️ Read through the container, not a fresh reader. The probe keeps a
                    // high-water mark of the core count because `availableProcessors()` reports only
                    // cores that are currently online, so a throwaway instance can report an
                    // eight-core phone as a two-core one.
                    fun readDeviceClass(): String? = runCatching {
                        (context.applicationContext as dev.mascwa.pulse.PulseApplication)
                            .container.deviceProbe.describe()
                    }.getOrNull()
                    var deviceClass by remember { mutableStateOf(readDeviceClass()) }
                    PrefClickable(
                        "Device class",
                        value = deviceClass?.substringBefore('\n') ?: "unavailable",
                        subtitle = deviceClass
                            ?: "This phone would not say what it is made of.",
                        // Half of this reading is live — thermal state, heap use, power saver — so
                        // tapping re-reads it rather than doing nothing.
                        onClick = { deviceClass = readDeviceClass() },
                    )
                    var sensors by remember { mutableStateOf<String?>(null) }
                    PrefClickable(
                        "Sensors",
                        value = if (sensors == null) "tap to probe" else "see below",
                        subtitle = sensors
                            ?: "Probe which sensors this device actually exposes. The activity-sensing " +
                            "(shower / eating detection) is built on what's really here — Pixels have a " +
                            "barometer but usually no humidity or ambient-temperature sensor, so the honest " +
                            "signal is sound (running water) + scene, not a humidity spike.",
                        onClick = {
                            if (sensors == null) {
                                sensors = dev.mascwa.pulse.core.device.SensorCapabilities(context).summary()
                            }
                        },
                    )
                    PrefClickable(
                        "GrapheneOS per-app controls",
                        subtitle = "Network, Sensors & Storage Scopes for LCARS (GrapheneOS-exclusive) live in App info.",
                        onClick = { dev.mascwa.pulse.core.util.openAppInfo(context) },
                    )
                }
            }

            // ----- Device-owner security policies (effective only once provisioned) -----
            if (vis(SettingsCategory.DEVICE, "owner usb camera wipe")) item {
                val dpc = remember { dev.mascwa.pulse.security.DevicePolicyController(context) }
                val isOwner = remember { dpc.isDeviceOwner() }
                // ⚠️ The explanation comes from the controller, not from here. This screen used to
                // carry its own hand-written version, so the sentence could drift from the check that
                // produces it — and it did: it pointed at a "Device owner" row above rather than
                // saying what provisioning actually requires. One definition now, shared with the
                // first-launch device notice.
                val ownerReason = remember { dpc.unavailableReason() }
                val usbSupported = remember { dpc.usbDataControlSupported() }
                var usbDataOn by remember { mutableStateOf(dpc.isUsbDataEnabled()) }
                var camDisabled by remember { mutableStateOf(dpc.isCameraDisabled()) }
                var wipeN by remember { mutableStateOf(dpc.maxFailedForWipe()) }
                var pendingWipe by remember { mutableStateOf<Int?>(null) }
                var wipeError by remember { mutableStateOf(false) }
                PrefSection("Device-owner controls") {
                    ownerReason?.let { reason ->
                        Text(
                            reason,
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
                    LcarsDialog(
                        title = "Arm device wipe?",
                        onDismiss = { pendingWipe = null },
                        confirmText = "ARM WIPE",
                        onConfirm = {
                            if (dpc.setMaxFailedForWipe(n)) {
                                wipeN = n; wipeError = false
                                vm.recordDevicePolicy("wipe_after_fails", "$n")
                            } else wipeError = true
                            pendingWipe = null
                        },
                        dismissText = "CANCEL",
                    ) {
                        DialogBody(
                            "After $n wrong unlock attempts, this device will FACTORY-RESET — erasing " +
                                "everything on it. This is an anti-theft measure and can't be undone once it " +
                                "triggers. Set it to Off any time to disarm.",
                        )
                    }
                }
            }

            // ----- GrapheneOS hardening (surface the OS's own controls; they beat app-level versions) -----
            if (vis(SettingsCategory.DEVICE, "graphene hardening mte usb sandboxed play")) item {
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
                            "protection per app, using the Tensor chip. Toggle it for LCARS in App info → " +
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
                            "access). LCARS needs none of it — it's purely informational.",
                        onClick = { dev.mascwa.pulse.core.util.openAppInfo(context) },
                    )
                }
            }

            // ----- Special access & restricted settings -----
            if (vis(SettingsCategory.DEVICE, "special access restricted usage install app info")) item {
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
            if (vis(SettingsCategory.INTERFACE, "accessibility contrast haptics")) item {
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
                            "(LCARS honours reduce-motion).",
                        onClick = { dev.mascwa.pulse.core.util.openAccessibilitySettings(context) },
                    )
                }
            }

            // ----- Diagnostics & debug reporting -----
            if (vis(SettingsCategory.SYSTEM, "diagnostics debug crash console report")) item {
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

            // ----- Appearance -----
            // The accent swatches and the AMOLED switch used to live here. Both were dead: LCARS is
            // the app's one fixed palette, `NightwireTheme` discards both parameters, and picking a
            // colour changed nothing on screen. A control that does nothing is worse than no
            // control, so they are gone. The settings fields survive — they are serialization keys,
            // and dropping one silently discards the rest of the blob.
            if (vis(SettingsCategory.INTERFACE, "appearance haptics sounds audio boot theme")) item {
                PrefSection("Appearance") {
                    PrefSwitch("Haptics", "Subtle vibration on key actions", s.haptics) { v ->
                        vm.update { it.copy(haptics = v) }
                    }
                    PrefSwitch("Interface sounds", "Console chirps on taps and alerts", s.sounds) { v ->
                        vm.update { it.copy(sounds = v) }
                    }
                    // These ride the system/UI sound stream, which Android's own "Touch sounds"
                    // setting silences independently of this app. Without a way to hear one on
                    // demand there is no telling that apart from the app being broken.
                    PrefClickable(
                        "Test the console sounds",
                        subtitle = "Plays the alert cue. If this is silent with the switch on, " +
                            "it is Android's Sound & vibration ▸ Touch sounds, not LCARS.",
                        onClick = { soundTest(SoundCue.ALERT, HapticCue.IMPACT_HEAVY) },
                    )
                    PrefSwitch("Boot sequence", "Cinematic cold-open on launch (off saves startup RAM)", s.bootAnimation) { v ->
                        vm.update { it.copy(bootAnimation = v) }
                    }
                }
            }

            // ----- Region & units -----
            if (vis(SettingsCategory.REGION, "region country currency units temperature wind precipitation clock language")) item {
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

            // ----- Data & refresh -----
            if (vis(SettingsCategory.CONTENT, "refresh interval wifi articles data")) item {
                PrefSection("Data & refresh") {
                    SingleChoiceRow(
                        "Background refresh", s.refreshIntervalMinutes,
                        listOf(15 to "15 min", 30 to "30 min", 60 to "1 hour", 120 to "2 hours", 240 to "4 hours"),
                    ) { m -> vm.update { it.copy(refreshIntervalMinutes = m) } }
                    PrefSwitch("Refresh on Wi-Fi only", checked = s.refreshOnlyOnWifi,
                        onChange = { v -> vm.update { it.copy(refreshOnlyOnWifi = v) } })
                    // ⚠️ Nothing on the phone knows a carrier's billing cycle, so the widget's
                    // "data used" line is only as right as this. A day the month does not have —
                    // the 31st in April — resolves to that month's last rather than skipping the
                    // month; `BillingCycle` decides that once, so this is a plain day number.
                    SingleChoiceRow(
                        "Data allowance resets on", s.dataCycleDay,
                        (1..31).map { d -> d to ordinal(d) },
                    ) { d -> vm.update { it.copy(dataCycleDay = d) } }
                    SingleChoiceRow(
                        "Articles per category", s.newsItemsPerCategory,
                        listOf(15 to "15", 30 to "30", 50 to "50"),
                    ) { n -> vm.update { it.copy(newsItemsPerCategory = n) } }
                    PrefSwitch(
                        "Look up who else is carrying a story",
                        subtitle = "On each article, name the other outlets reporting the same story. Does one extra cached search per article you view.",
                        checked = s.showNewsCoverageStrip,
                        onChange = { v -> vm.update { it.copy(showNewsCoverageStrip = v) } },
                    )
                }
            }

            // ----- What is waiting for you: unread texts and unread mail -----
            if (vis(
                    SettingsCategory.CONTENT,
                    "unread texts sms email imap mail inbox accounts notification access gmail outlook",
                )
            ) item {
                // ⚠️ No `initiallyExpanded = false`. This was the ONE section of the twenty-two in
                // this file that rendered collapsed, so the whole of "link your mail" was a bare
                // header you had to know to tap — and the owner reported not being able to find it.
                PrefSection("Texts & mail") {
                    PrefInfo(
                        "Unread counts",
                        // ⚠️ `subtitle`, not the positional second parameter — that one is `value`,
                        // the short right-aligned accent text, capped at two lines. A paragraph
                        // there is truncated and right-aligned, which is not what it is for.
                        subtitle = "The widget can show how many texts and emails are waiting. " +
                            "Nothing is read but the counts — no addresses, no subjects, no message " +
                            "text — and nothing leaves this phone except the sign-in to your own " +
                            "mail server.",
                    )
                    // ⚠️ Asked here, at the point the feature is switched on, rather than at launch.
                    // Without it the count is simply absent; it is never shown as a zero, which
                    // would be a claim about the inbox rather than about the permission.
                    if (!smsGranted) {
                        PrefClickable(
                            "Count unread texts",
                            subtitle = "Needs permission to read the SMS inbox",
                            onClick = { smsLauncher.launch(android.Manifest.permission.READ_SMS) },
                        )
                    } else {
                        PrefInfo("Unread texts", "Counting")
                    }
                    // Mail with no password at all, from the notification shade. Its own rows,
                    // above the IMAP mailboxes, because it is the one most people want and the one
                    // that needs nothing typed in.
                    MailNotificationRows(
                        chosen = s.mailApps,
                        onChosenChange = { picked -> vm.update { st -> st.copy(mailApps = picked) } },
                    )
                    s.emailAccounts.forEachIndexed { i, acct ->
                        PrefClickable(
                            acct.display,
                            subtitle = when {
                                !acct.enabled -> "Switched off — tap to remove"
                                acct.password.isBlank() ->
                                    "Needs its password — a backup never carries one. Tap to remove and add it again."
                                else -> "${acct.host}:${acct.port} — tap to remove"
                            },
                            onClick = {
                                vm.update { st ->
                                    st.copy(emailAccounts = st.emailAccounts.filterIndexed { j, _ -> j != i })
                                }
                            },
                        )
                    }
                    AddMailboxRow { account ->
                        vm.update { st -> st.copy(emailAccounts = st.emailAccounts + account) }
                    }
                }
            }

            // ----- Notifications -----
            if (vis(SettingsCategory.NOTIFICATIONS, "notifications alerts push quiet hours")) item {
                PrefSection("Notifications") {
                    if (Build.VERSION.SDK_INT >= 33) {
                        PrefClickable(
                            "Grant notification permission",
                            subtitle = "Required on Android 13+ to receive alerts",
                            onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                        )
                    }
                    PrefSwitch(
                        "Enable notifications",
                        subtitle = "LCARS shows ONE notification — the Situation Board: news, markets, weather and your agenda, always current, updated in place.",
                        checked = s.notifications.masterEnabled,
                        onChange = { v -> vm.update { it.copy(notifications = it.notifications.copy(masterEnabled = v)) } })
                    val on = s.notifications.masterEnabled
                    PrefSwitch("Show news on the board", checked = s.notifications.showNewsRow, enabled = on,
                        onChange = { v -> vm.update { it.copy(notifications = it.notifications.copy(showNewsRow = v)) } })
                    PrefSwitch("Show markets on the board", checked = s.notifications.showMarketsRow, enabled = on,
                        onChange = { v -> vm.update { it.copy(notifications = it.notifications.copy(showMarketsRow = v)) } })
                    PrefSwitch("Show weather on the board", checked = s.notifications.showWeatherRow, enabled = on,
                        onChange = { v -> vm.update { it.copy(notifications = it.notifications.copy(showWeatherRow = v)) } })
                    PrefSwitch("Show your agenda on the board", checked = s.notifications.showAgendaRow, enabled = on,
                        onChange = { v -> vm.update { it.copy(notifications = it.notifications.copy(showAgendaRow = v)) } })
                    PrefSwitch("Show what you have eaten on the board", checked = s.notifications.showHealthRow, enabled = on,
                        onChange = { v -> vm.update { it.copy(notifications = it.notifications.copy(showHealthRow = v)) } })
                    SingleChoiceRow(
                        "Market row threshold", s.notifications.marketMovePercent,
                        listOf(1.0 to "±1%", 2.0 to "±2%", 3.0 to "±3%", 5.0 to "±5%", 10.0 to "±10%"),
                        enabled = on && s.notifications.showMarketsRow,
                    ) { p -> vm.update { it.copy(notifications = it.notifications.copy(marketMovePercent = p)) } }
                    PrefSwitch(
                        "Urgent alerts (sound & vibration)",
                        subtitle = "A due reminder, a major emergency, or a security or danger notice buzzes once. Off = the board still updates, just always silently.",
                        checked = s.notifications.urgentAlertsEnabled, enabled = on,
                        onChange = { v -> vm.update { it.copy(notifications = it.notifications.copy(urgentAlertsEnabled = v)) } })
                    PrefSwitch(
                        "Breaking news card",
                        subtitle = "On a major event, a card with that one story floats over whatever you're doing. The app behind stays usable — drag it aside or tap ✕.",
                        checked = s.notifications.breakingInterrupt, enabled = on,
                        onChange = { v -> vm.update { it.copy(notifications = it.notifications.copy(breakingInterrupt = v)) } })
                    // ⚠️ NOT gated on `enabled = on`. The notification master switch means "stop
                    // telling me about the news"; it has never meant "stop telling me the building
                    // is on fire", and a life-safety alert must not be switched off as a side effect
                    // of quietening everything else. This has its own switch and only its own.
                    PrefSwitch(
                        "Official emergency alerts (full screen + alarm)",
                        subtitle = "A government warning for your area takes over the screen and sounds a full-volume alarm, even on silent or Do Not Disturb. Your phone's own emergency alerts work separately and are unaffected. US coverage only — the feed is the National Weather Service.",
                        checked = s.notifications.emergencyTakeover,
                        onChange = { v ->
                            vm.update { it.copy(notifications = it.notifications.copy(emergencyTakeover = v)) }
                            // Start or stop the watch immediately rather than waiting up to fifteen
                            // minutes for the worker to notice. Done here rather than in the view
                            // model because the service needs a Context and the view model has none.
                            if (v) {
                                dev.mascwa.pulse.notifications.EmergencyWatchService.start(context)
                            } else {
                                dev.mascwa.pulse.notifications.EmergencyWatchService.stop(context)
                            }
                        })
                    PrefClickable(
                        "Allow the takeover over other apps",
                        subtitle = "Grant \"display over other apps\" so the breaking-news card can float over any app, and an emergency alert can take the screen with no tap. Without it both fall back to a full-screen-intent notification.",
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(
                                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        android.net.Uri.parse("package:${context.packageName}"),
                                    ),
                                )
                            }
                        },
                    )
                    PrefSwitch("Live news polling (~90s, needs the resident Computer)",
                        checked = s.notifications.liveBreakingNews, enabled = on,
                        onChange = { v -> vm.update { it.copy(notifications = it.notifications.copy(liveBreakingNews = v)) } })
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
                        subtitle = "Posts a sample Situation Board so you can see the LCARS layout (grants permission first if needed)",
                        onClick = {
                            if (notificationsAllowed()) vm.sendTestNotification()
                            else testNotifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        },
                    )
                }
            }

            // ----- Security & network (Trusted Network Mode + encryption) -----
            if (vis(SettingsCategory.SECURITY, "remote link desktop computer pair lan wifi control anydesk")) item {
                PrefSection("Remote link") {
                    PrefSwitch(
                        "Remote link",
                        "Let a paired computer on the same Wi-Fi switch app features on and off. Off by " +
                            "default; while it's on, an ongoing notification shows the phone is listening.",
                        checked = s.remote.enabled,
                        onChange = { v ->
                            vm.update { it.copy(remote = it.remote.copy(enabled = v)) }
                            if (v) RemoteLinkService.start(context) else RemoteLinkService.stop(context)
                        },
                    )
                    PrefClickable(
                        "Pair a computer",
                        subtitle = if (!s.remote.enabled) {
                            "Switch the link on first."
                        } else {
                            "Shows a six-digit code and this phone's address in the notification for two " +
                                "minutes. Enter both on the desktop app."
                        },
                        onClick = { if (s.remote.enabled) RemoteLinkService.requestPairing(context) },
                    )
                    PrefInfo(
                        "Paired computers",
                        value = if (s.remote.pairedKeys.isEmpty()) "None" else "${s.remote.pairedKeys.size}",
                        subtitle = buildString {
                            append(
                                "Only these can send commands. Nothing sensitive is exposed — no wipe, " +
                                    "no app locking, no keys, and every command is written to the audit ledger.",
                            )
                            // Show WHICH machines. The desktop displays the same four bytes for its own
                            // identity, so an unexpected entry here is visible rather than just a count.
                            val marks = s.remote.pairedKeys.mapNotNull(RemotePeers::fingerprintOf)
                            if (marks.isNotEmpty()) append("\nPaired: ${marks.joinToString("  ")}")
                        },
                    )
                    if (s.remote.pairedKeys.isNotEmpty()) {
                        PrefClickable(
                            "Unpair all computers",
                            subtitle = "They'll each have to pair again before anything can reach this phone.",
                            onClick = {
                                vm.unpairAllComputers()
                                // Also revoke on the RUNNING listener — it holds an in-memory snapshot, so
                                // clearing the setting alone would leave every paired machine with command
                                // access until the service next restarted.
                                RemoteLinkService.requestUnpairAll(context)
                            },
                        )
                    }
                }
            }

            if (vis(SettingsCategory.SECURITY, "live tv television channels community catalogue iptv news video stream")) item {
                PrefSection("Live television") {
                    PrefSwitch(
                        "Community channel catalogue",
                        "Adds hundreds of news channels from a volunteer-maintained public list, on top " +
                            "of the handful of broadcasters' own feeds the app ships with. That list is " +
                            "of mixed origin and includes unauthorised restreams of channels that are " +
                            "not free to watch — which is why this is a switch and not something the app " +
                            "decides for you. None of it is verified to work. Costs a small download, " +
                            "kept for a week.",
                        checked = s.communityChannels,
                        onChange = { v -> vm.update { it.copy(communityChannels = v) } },
                    )
                }
            }

            if (vis(SettingsCategory.SECURITY, "viewscreen sponsor skip sponsorblock segments on-demand video playback")) item {
                PrefSection("Viewscreen") {
                    PrefSwitch(
                        "Skip flagged segments",
                        "During on-demand playback, jump over segments the SponsorBlock community has " +
                            "flagged — sponsors, self-promotion, intros and the like. Asks a public " +
                            "database about each video privately: only the first four characters of a " +
                            "hash are sent, so the server is never told which video you are watching. " +
                            "Off = nothing is asked and nothing is skipped.",
                        checked = s.sponsorSkip,
                        onChange = { v -> vm.update { it.copy(sponsorSkip = v) } },
                    )
                }
            }

            if (vis(SettingsCategory.SECURITY, "ambient sensing sensorium camera mic microphone environment scanner light barometer")) item {
                PrefSection("Ambient sensing (Sensorium)") {
                    PrefSwitch(
                        "Environment sensing",
                        "The ship's senses: fuses motion, light, pressure, magnetics and radio density " +
                            "24/7, learns your normal, and flags what's unusual. Everything stays on this " +
                            "device — camera and mic produce text labels only, nothing raw is kept or sent.",
                        checked = s.sensing.enabled,
                        onChange = { v ->
                            vm.update { it.copy(sensing = it.sensing.copy(enabled = v)) }
                            if (!v) dev.mascwa.pulse.data.sensing.SensoriumService.stop(context)
                            else dev.mascwa.pulse.data.sensing.SensoriumService.start(context, foregroundLaunch = true)
                        },
                    )
                    PrefSwitch(
                        "Ambient hearing (mic sips)",
                        "Short mic samples classified on-device into soundscape labels — including safety " +
                            "sounds like a smoke alarm or breaking glass, which raise an urgent alert.",
                        checked = s.sensing.micSensing,
                        onChange = { v -> vm.update { it.copy(sensing = it.sensing.copy(micSensing = v)) } },
                    )
                    PrefSwitch(
                        "Ambient sight (camera sips)",
                        "Brief back-camera bursts classified on-device into scene labels. The status-bar " +
                            "camera indicator lighting up during a sip is the OS working as designed.",
                        checked = s.sensing.cameraSensing,
                        onChange = { v -> vm.update { it.copy(sensing = it.sensing.copy(cameraSensing = v)) } },
                    )
                    PrefSwitch(
                        "Acoustic interrogator",
                        "Records speech continuously, transcribes it on-device, and questions weak " +
                            "reasoning. The transcript is encrypted and kept for a day; the wake word " +
                            "stands down while this runs. Off unless you turn it on.",
                        checked = s.sensing.interrogator,
                        onChange = { v ->
                            vm.update { it.copy(sensing = it.sensing.copy(interrogator = v)) }
                            // ⚠️ Stop is honoured from here, but START is not: the microphone
                            // foreground-service type can only be armed from a visible activity that
                            // holds RECORD_AUDIO, and Settings has no way to ask for it. Turning it on
                            // here arms the setting; the Interrogator screen's LISTEN button is what
                            // actually opens the microphone, and it requests the permission first.
                            if (!v) dev.mascwa.pulse.data.interrogator.AcousticInterrogatorService.stop(context)
                        },
                    )
                    PrefSwitch(
                        "Radio density (crowd sense)",
                        "WiFi and Bluetooth scan bursts as a people-density signal. WiFi counts need " +
                            "Location on; nothing is ever connected to.",
                        checked = s.sensing.radioSensing,
                        onChange = { v -> vm.update { it.copy(sensing = it.sensing.copy(radioSensing = v)) } },
                    )
                    PrefSwitch(
                        "Remember sensed events",
                        "Notable moments (a doorbell, thunder, a storm-front pressure drop) become " +
                            "episodic memories the Computer can recall — a few per day at most.",
                        checked = s.sensing.rememberEvents,
                        onChange = { v -> vm.update { it.copy(sensing = it.sensing.copy(rememberEvents = v)) } },
                    )
                }
            }
            if (vis(SettingsCategory.SECURITY, "security network wifi ssid encryption https audit ledger")) item {
                PrefSection("Security & network") {
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
                            "LCARS can toggle Wi-Fi. Stays minimal: no wipe/lock/password powers."
                        else
                            "To let LCARS actually toggle Wi-Fi, provision it once over adb on a device with no " +
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
                            "No home networks set. Add the one you're on at home so LCARS knows when you've left it.",
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
                        "Show a notification when LCARS wants to toggle Wi-Fi but isn't a Device Owner yet.",
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

            // ----- Home dashboard -----
            if (vis(SettingsCategory.INTERFACE, "home dashboard layout sections reorder")) collapsibleHeader("Home dashboard", homeCollapsed) { homeCollapsed = !homeCollapsed }
            if (vis(SettingsCategory.INTERFACE, "home dashboard layout sections reorder") && !homeCollapsed) {
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

            // ----- Watchlist -----
            if (vis(SettingsCategory.CONTENT, "watchlist markets crypto symbols stooq coingecko")) collapsibleHeader("Markets watchlist", watchlistCollapsed) { watchlistCollapsed = !watchlistCollapsed }
            if (vis(SettingsCategory.CONTENT, "watchlist markets crypto symbols stooq coingecko") && !watchlistCollapsed) {
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

            // ----- Custom feeds -----
            if (vis(SettingsCategory.CONTENT, "custom rss feed news source")) collapsibleHeader("Custom RSS feeds", feedsCollapsed) { feedsCollapsed = !feedsCollapsed }
            if (vis(SettingsCategory.CONTENT, "custom rss feed news source") && !feedsCollapsed) {
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
                        }) { Icon(LcarsIcons.Delete, "Remove") }
                    }
                }
                item {
                    AddFeedRow { feed ->
                        vm.update { it.copy(customFeeds = (it.customFeeds + feed)) }
                    }
                }
            }

            // ----- Muted keywords -----
            if (vis(SettingsCategory.CONTENT, "muted keyword filter hide block")) collapsibleHeader("Muted keywords", mutedCollapsed) { mutedCollapsed = !mutedCollapsed }
            if (vis(SettingsCategory.CONTENT, "muted keyword filter hide block") && !mutedCollapsed) {
                itemsIndexed(s.mutedKeywords) { i, kw ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(kw, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        IconButton(onClick = {
                            vm.update { it.copy(mutedKeywords = it.mutedKeywords.filterIndexed { idx, _ -> idx != i }) }
                        }) { Icon(LcarsIcons.Delete, "Remove") }
                    }
                }
                item {
                    AddTextRow("Add keyword to mute") { kw ->
                        vm.update { it.copy(mutedKeywords = (it.mutedKeywords + kw).distinct()) }
                    }
                }
            }

            // The "Image search sites" section lived here until it was found to be a ZOMBIE: it
            // edited AppSettings.customImageSites, which fed the Images screen deleted long ago —
            // a settings surface inviting configuration of a feature that no longer exists. The
            // field itself stays (property names are a data contract; old blobs still carry it).

            // ----- Optional API keys -----
            if (vis(SettingsCategory.KEYS, "api key token openrouter github openai google brave search web")) item {
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
                    EditableValueRow("Brave Search (the open web)", masked(s.apiKeys.brave), "https://api-dashboard.search.brave.com/register") { v ->
                        vm.update { it.copy(apiKeys = it.apiKeys.copy(brave = v.trim())) }
                    }
                    Text(
                        "Without a Brave key the Computer still searches the offline library and " +
                            "Wikipedia — but it cannot answer anything about today, and it will say " +
                            "so rather than guessing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            // ----- Safety (SOS) -----
            if (vis(SettingsCategory.SAFETY, "safety sos medical emergency contact blood allergy sms")) item {
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
                            }) { Icon(LcarsIcons.Delete, "Remove") }
                        }
                    }
                    AddContactRow { contact ->
                        vm.update { it.copy(emergencyContacts = it.emergencyContacts + contact) }
                    }
                }
            }

            // ----- Backup & restore (local, offline) -----
            if (vis(SettingsCategory.SYSTEM, "backup restore export import settings")) item {
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

            // ----- Storage & about -----
            if (vis(SettingsCategory.STORAGE, "storage cache clear activity log memory profile tasks reflexes episodic reset about")) item {
                val ledgerStatus by vm.auditLedgerStatus.collectAsStateWithLifecycle()
                val selfTest by vm.ledgerSelfTestResult.collectAsStateWithLifecycle()
                val selfTestRunning by vm.ledgerSelfTestRunning.collectAsStateWithLifecycle()
                val pythonTest by vm.pythonTestResult.collectAsStateWithLifecycle()
                val pythonTestRunning by vm.pythonTestRunning.collectAsStateWithLifecycle()
                PrefSection("Storage & about") {
                    // ⚠️ `megabytes`, not `compact` — that produced "8.4M B", which reads as a unit
                    // nobody uses. Mebibytes, so the figure matches what a file manager says about
                    // the same app.
                    val freed by vm.cacheFreed.collectAsStateWithLifecycle()
                    PrefClickable(
                        "Cached data",
                        value = Formatters.megabytes(cacheSize),
                        subtitle = "Feeds, images, web responses and downloaded installers. All of " +
                            "it can be fetched again.",
                        onClick = { vm.refreshCacheSize() },
                    )
                    PrefClickable(
                        "Clear cache",
                        // Says what it gave back rather than implying the directory is now empty:
                        // a download in progress and whatever the platform keeps here are left alone.
                        subtitle = freed?.let { "Freed ${Formatters.megabytes(it)}." },
                        onClick = { vm.clearCache() },
                    )
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
                            "the Computer's tailored tips and self-awareness. Never leaves your device.",
                        onClick = { vm.clearUsageData() },
                    )
                    PrefClickable(
                        "Reset learned reflexes",
                        subtitle = "Wipe the virtual cerebellum — the practiced skills & reliability " +
                            "the Computer has learned from its own actions. It relearns from scratch.",
                        onClick = { vm.resetReflexes() },
                    )
                    PrefClickable(
                        "Clear remembered profile",
                        subtitle = "Forget the preferences, interests & projects the computer has " +
                            "remembered about you. On-device only.",
                        onClick = { vm.clearProfile() },
                    )
                    PrefClickable(
                        "Clear tracked tasks",
                        subtitle = "Forget the ongoing tasks & goals the computer is tracking for you. " +
                            "On-device only.",
                        onClick = { vm.clearTasks() },
                    )
                    PrefSwitch(
                        "Reflect on memories",
                        "Periodically distil recent moments into higher-level insights the computer keeps " +
                            "(Mnemosyne reflection). Uses the cloud brain, throttled; off = raw moments only.",
                        s.jarvis.reflectionEnabled,
                    ) { v -> vm.update { it.copy(jarvis = it.jarvis.copy(reflectionEnabled = v)) } }
                    PrefClickable(
                        "Clear mail counts",
                        subtitle = "Forget how much mail is waiting and which of your apps have been " +
                            "seen to notify. The count comes back on its own; the list of apps is " +
                            "the part worth forgetting. On-device only.",
                        onClick = { vm.clearMailNotices() },
                    )
                    PrefClickable(
                        "Clear what the Oracle learned",
                        subtitle = "Forget which advisories you act on. The Oracle keeps a tally per rule " +
                            "and ranks by it; clearing puts every rule back on equal footing. On-device only.",
                        onClick = { vm.clearOracleLearning() },
                    )
                    PrefClickable(
                        "Clear episodic memory",
                        subtitle = "Forget the timestamped moments the computer remembers from your " +
                            "conversations (recalled by recency, importance & relevance). On-device only.",
                        onClick = { vm.clearMemoryStream() },
                    )
                    PrefClickable(
                        "Clear study progress",
                        subtitle = "Forget the enrolled path, which guides have been taught and every " +
                            "review schedule. The library itself is untouched. On-device only.",
                        onClick = { vm.clearStudy() },
                    )
                    PrefClickable(
                        "Verify audit ledger",
                        value = ledgerStatus,
                        subtitle = "Re-check the tamper-evident blackbox log — chain integrity, hardware " +
                            "signature & last trusted-time anchor. View the full log in Computer → Memory.",
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
                            "manually from Computer → Memory.",
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
                    PrefClickable(
                        "Test the Python runtime",
                        value = if (pythonTestRunning) "Starting…" else null,
                        subtitle = "Start the embedded interpreter on this device and report what " +
                            "actually works. The build only proves it was packaged; whether it runs " +
                            "here is a separate question. Nothing leaves the device.",
                        onClick = { vm.runPythonTest() },
                    )
                    PrefClickable("Crash log", subtitle = "View & share recent faults (on-device)",
                        onClick = onOpenCrashLog)
                    PrefClickable("Reset all settings", subtitle = "Restore defaults",
                        onClick = { vm.resetToDefaults() })
                    Text(
                        "LCARS · built exclusively for Pixel 10 Pro XL · all data from free public sources.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                selfTest?.let { report ->
                    LcarsDialog(
                        title = if (report.allOk) "Self-test — all ${report.total} passed"
                        else "Self-test — ${report.passed}/${report.total} passed",
                        onDismiss = { vm.dismissLedgerSelfTest() },
                        dismissText = "DONE",
                    ) {
                        // Read the palette here rather than reaching for an outer `c` — this
                        // composable does not declare one, and nothing above it in this file does
                        // either. That is the whole reason the remaining inline Material styles in
                        // this screen are not being swept blind.
                        val selfTestColors = Pulse.colors
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            report.checks.forEach { ch ->
                                Column {
                                    Text(
                                        "${if (ch.ok) "✓" else "✗"}  ${ch.name}",
                                        fontFamily = ChakraPetch, fontSize = 13.sp,
                                        color = if (ch.ok) selfTestColors.positive else selfTestColors.negative,
                                    )
                                    DialogBody(ch.detail)
                                }
                            }
                        }
                    }
                }
                pythonTest?.let { report ->
                    LcarsDialog(
                        title = if (report.allOk) "Python — running" else "Python — incomplete",
                        onDismiss = { vm.dismissPythonTest() },
                        dismissText = "DONE",
                    ) {
                        val pyColors = Pulse.colors
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Each finding is reported on its own line, because "the interpreter did
                            // not start" and "it started with no standard library" are different
                            // builds to fix and a single pass/fail could not tell them apart.
                            listOf(
                                "Interpreter started" to (if (report.running) "yes" else null),
                                "Version" to report.interpreter,
                                "Argument round-trip" to report.roundTrip,
                                "Standard library" to report.stdlib,
                                // Shown as its own line because "the interpreter runs but the
                                // extractor did not import" is a specific, fixable state.
                                "Extractor" to report.extractor,
                            ).forEach { (name, detail) ->
                                Column {
                                    Text(
                                        "${if (detail != null) "✓" else "✗"}  $name",
                                        fontFamily = ChakraPetch, fontSize = 13.sp,
                                        color = if (detail != null) pyColors.positive else pyColors.negative,
                                    )
                                    DialogBody(detail ?: "did not run")
                                }
                            }
                            report.error?.let { DialogBody(it) }
                        }
                    }
                }
            }
        }
    }

    // The screen's FIRST frame-level back: the densest screen in the app had no back affordance
    // at all — its only back was an 18dp text row 1300 lines into the body that exited a category,
    // never the screen. The corner leaves the category first, then the screen, mirroring the
    // system-back gesture above.
    PulseScaffold(
        title = "Settings",
        onBack = { if (selectedCat != null) vm.selectedCategory.value = null else onBack?.invoke() },
    ) { innerPadding ->
        SettingsShell(
            modifier = Modifier.padding(innerPadding),
            selectedCat = selectedCat,
            query = query,
            onSelect = { vm.selectedCategory.value = it },
            onQuery = { query = it },
            detail = detail,
        )
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

/**
 * A day of the month as it is said: 1st, 2nd, 3rd, 21st, but 11th, 12th and 13th.
 *
 * ⚠️ The teens are the whole reason this is a function rather than a lookup on the last digit —
 * eleventh, twelfth and thirteenth break the pattern that 21st, 22nd and 23rd follow. English only,
 * like the rest of this screen: the app ships `resourceConfigurations += listOf("en")`.
 */
private fun ordinal(day: Int): String {
    val suffix = if (day % 100 in 11..13) "th" else when (day % 10) {
        1 -> "st"
        2 -> "nd"
        3 -> "rd"
        else -> "th"
    }
    return "$day$suffix"
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
    LcarsDialog(
        title = title,
        onDismiss = onDismiss,
        confirmText = "SAVE",
        onConfirm = { onConfirm(text) },
        dismissText = "CANCEL",
    ) {
        OutlinedTextField(
            value = text, onValueChange = { text = it }, singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
        )
    }
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
                dev.mascwa.pulse.feature.common.LcarsSwitch(checked = true, onCheckedChange = { onChange(enabledOrdered - section) })
            }
        }
        disabled.forEach { section ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(section.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                dev.mascwa.pulse.feature.common.LcarsSwitch(checked = false, onCheckedChange = { onChange(enabledOrdered + section) })
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
            IconButton(onClick = { onRemove(item) }) { Icon(LcarsIcons.Delete, "Remove") }
        }
    }
}

/**
 * Add a mailbox.
 *
 * ⚠️ The password field is a real credential going into `AppSettings`. Three things already know
 * that and had to before this row existed: the settings blob is encrypted at rest, `allSecretValues`
 * carries it so a debug report cannot, and `SettingsBackup` blanks it on export and restores the
 * device's own on import. `CredentialCoverageTest` fails the build if any of that is undone.
 */
@Composable
private fun AddMailboxRow(onAdd: (dev.mascwa.pulse.data.settings.EmailAccount) -> Unit) {
    var show by remember { mutableStateOf(false) }
    PrefClickable("Add a mailbox", subtitle = "IMAP over TLS, e.g. imap.gmail.com", onClick = { show = true })
    if (show) {
        var host by remember { mutableStateOf("") }
        var user by remember { mutableStateOf("") }
        var pass by remember { mutableStateOf("") }
        var label by remember { mutableStateOf("") }
        LcarsDialog(
            title = "Add a mailbox",
            onDismiss = { show = false },
            content = {
                Column {
                    OutlinedTextField(host, { host = it }, label = { Text("IMAP server") }, singleLine = true)
                    OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true)
                    OutlinedTextField(
                        pass, { pass = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                        ),
                    )
                    OutlinedTextField(label, { label = it }, label = { Text("What to call it") }, singleLine = true)
                    // ⚠️ Said before the attempt, not after it fails. Gmail, Outlook and Yahoo all
                    // answer "authentication failed" whether the password is wrong or merely the
                    // wrong KIND, so somebody pasting their website password has no way to tell.
                    DialogBody(
                        "Gmail, Outlook and Yahoo need an app-specific password rather than the one " +
                            "you sign into the website with.",
                    )
                }
            },
            confirmText = "ADD",
            confirmEnabled = host.isNotBlank() && user.isNotBlank() && pass.isNotBlank(),
            onConfirm = {
                onAdd(
                    dev.mascwa.pulse.data.settings.EmailAccount(
                        label = label.trim(),
                        host = host.trim(),
                        username = user.trim(),
                        password = pass,
                    ),
                )
                show = false
            },
            dismissText = "CANCEL",
        )
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
        LcarsDialog(
            title = "Add to watchlist",
            onDismiss = { show = false },
            content = {
                Column {
                    OutlinedTextField(id, { id = it }, label = { Text("Symbol / id") }, singleLine = true)
                    OutlinedTextField(label, { label = it }, label = { Text("Display name") }, singleLine = true)
                    PrefRadioGroup(
                        options = WatchType.entries.map { it to it.name.lowercase() },
                        selected = type, onSelect = { type = it },
                    )
                }
            },
            confirmText = "ADD",
            confirmEnabled = id.isNotBlank(),
            onConfirm = {
                onAdd(WatchItem(id.trim(), label.ifBlank { id }.trim(), type)); show = false
            },
            dismissText = "CANCEL",
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
        LcarsDialog(
            title = "Add RSS feed",
            onDismiss = { show = false },
            content = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(url, { url = it }, label = { Text("Feed URL") }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Uri))
                }
            },
            confirmText = "ADD",
            confirmEnabled = url.startsWith("http"),
            onConfirm = {
                onAdd(CustomFeed(name.ifBlank { url }, url.trim())); show = false
            },
            dismissText = "CANCEL",
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
        LcarsDialog(
            title = "Add contact",
            onDismiss = { show = false },
            content = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(phone, { phone = it }, label = { Text("Phone") }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Phone))
                }
            },
            confirmText = "ADD",
            confirmEnabled = phone.isNotBlank(),
            onConfirm = {
                onAdd(dev.mascwa.pulse.data.settings.EmergencyContact(name.ifBlank { phone }.trim(), phone.trim()))
                show = false
            },
            dismissText = "CANCEL",
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

/**
 * The Steam-style shell. Three width modes via BoxWithConstraints:
 *  - WIDE (≥ 720dp, landscape/foldable): a fixed 210dp category rail + the detail pane side by side.
 *  - COMPACT (phone portrait): master → detail push. selectedCat == null (and no search) shows the master
 *    category list; picking one (or typing a search) shows the detail pane with a back/▸ header.
 * [detail] is the settings LazyColumn (gated by category/search in the caller's closure) — rendered wherever
 * the detail belongs. [onSelect]/[onQuery] drive the shared state the detail gates on.
 */
@Composable
private fun SettingsShell(
    modifier: Modifier,
    selectedCat: SettingsCategory?,
    query: String,
    onSelect: (SettingsCategory?) -> Unit,
    onQuery: (String) -> Unit,
    detail: @Composable (Modifier) -> Unit,
) {
    val c = Pulse.colors
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.width(210.dp).fillMaxHeight()
                        .background(c.panel)
                        .verticalScroll(rememberScrollState()),
                ) {
                    SettingsSearchField(query, onQuery, Modifier.padding(10.dp))
                    SettingsCategory.entries.forEach { cat ->
                        SettingsRailRow(cat, selected = (selectedCat ?: SettingsCategory.FIRST) == cat, compact = false) {
                            onSelect(cat); onQuery("")
                        }
                    }
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(c.line))
                detail(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            val master = selectedCat == null && query.isBlank()
            if (master) {
                Column(Modifier.fillMaxSize()) {
                    SettingsSearchField(query, onQuery, Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp))
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        SettingsCategory.entries.forEach { cat ->
                            SettingsMasterRow(cat) { onSelect(cat) }
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    // Back header — ‹ SETTINGS ▸ CATEGORY  (or ‹ SETTINGS ▸ SEARCH while searching)
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(null); onQuery("") }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(LcarsIcons.ArrowBack, "Back", tint = c.accent, modifier = Modifier.size(18.dp))
                        Text(
                            "SETTINGS ▸ ${(selectedCat ?: SettingsCategory.FIRST).title.uppercase()}",
                            fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                            color = c.ink, modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    SettingsSearchField(query, onQuery, Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp))
                    detail(Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

/** The rail search field (Steam's "Search settings"). An LCARS framed monospace field. */
@Composable
private fun SettingsSearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Pulse.colors
    dev.mascwa.pulse.feature.common.LcarsFrame(modifier.fillMaxWidth()) {
        androidx.compose.foundation.text.BasicTextField(
            value = query, onValueChange = onQuery, singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = c.ink, fontFamily = JetBrainsMono, fontSize = 13.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text("🔍 Search settings", fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.muted)
                }
                inner()
            },
        )
    }
}

/** A rail row (WIDE mode): icon + title, selected = inverted accent block (the canonical pick-one look). */
@Composable
private fun SettingsRailRow(cat: SettingsCategory, selected: Boolean, compact: Boolean, onClick: () -> Unit) {
    val c = Pulse.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .background(if (selected) c.accent else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(cat.icon, null, tint = if (selected) c.void else c.muted, modifier = Modifier.size(18.dp))
        Text(
            cat.title, fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
            color = if (selected) c.void else c.ink, modifier = Modifier.padding(start = 10.dp),
        )
    }
}

/** A master-list row (COMPACT mode): icon + title + blurb + ›. */
@Composable
private fun SettingsMasterRow(cat: SettingsCategory, onClick: () -> Unit) {
    val c = Pulse.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(cat.icon, null, tint = c.accent, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(cat.title, fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.ink)
            Text(cat.blurb, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted, modifier = Modifier.padding(top = 2.dp))
        }
        Text("›", fontFamily = ChakraPetch, fontSize = 18.sp, color = c.muted)
    }
}

