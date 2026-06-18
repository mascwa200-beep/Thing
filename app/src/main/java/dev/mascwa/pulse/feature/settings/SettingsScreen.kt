package dev.mascwa.pulse.feature.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.setValue
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
import dev.mascwa.pulse.feature.economy.CountryPicker

@Composable
fun SettingsScreen(vm: SettingsViewModel, onOpenCrashLog: () -> Unit = {}) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val cacheSize by vm.cacheSize.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

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
                PrefSection("Software update") {
                    val status = when (val st = u) {
                        is UpdateUi.Checking -> "Checking for a newer build…"
                        is UpdateUi.UpToDate -> "You're on the latest build."
                        is UpdateUi.Available -> "Update available — build #${st.info.versionCode}."
                        is UpdateUi.Downloading -> "Downloading ${st.pct}%…"
                        is UpdateUi.ReadyToInstall -> "Downloaded — tap Install now."
                        is UpdateUi.Error -> st.message
                        else -> "Tap to check for a new version."
                    }
                    PrefClickable(
                        "Check for updates",
                        value = "v${vm.installedVersion}",
                        subtitle = status,
                        onClick = {
                            android.widget.Toast.makeText(context, "Checking for updates…", android.widget.Toast.LENGTH_SHORT).show()
                            vm.checkForUpdate()
                        },
                    )
                    when (val st = u) {
                        is UpdateUi.Available -> PrefClickable(
                            "Download & install",
                            subtitle = st.info.notes.take(140).ifBlank { "Get build #${st.info.versionCode}" },
                            onClick = { vm.downloadUpdate() },
                        )
                        is UpdateUi.ReadyToInstall -> PrefClickable(
                            "Install now",
                            subtitle = "Opens the system installer — you confirm the update.",
                            onClick = { installApk(context, st.file) },
                        )
                        is UpdateUi.Error -> PrefClickable("Retry", onClick = { vm.downloadUpdate() })
                        else -> {}
                    }
                }
            }
            item { HorizontalDivider() }

            // ----- Self-coding (experimental) -----
            item {
                val selfCodeStatus by vm.selfCodeStatus.collectAsStateWithLifecycle()
                var goal by remember { mutableStateOf("") }
                var path by remember { mutableStateOf("") }
                PrefSection("Self-coding (experimental)") {
                    PrefSwitch(
                        "Enable self-coding",
                        "Let J.A.R.V.I.S. draft changes to its own code and open GitHub PRs. Needs a " +
                            "write-scoped GitHub token in J.A.R.V.I.S. Setup.",
                        checked = s.jarvis.selfCodingEnabled,
                        onChange = { v -> vm.update { it.copy(jarvis = it.jarvis.copy(selfCodingEnabled = v)) } },
                    )
                    if (s.jarvis.selfCodingEnabled) {
                        PrefSwitch(
                            "Auto-merge on green CI",
                            "Merge its PRs automatically once the build passes — you still confirm the " +
                                "install. Off = you review and merge each PR yourself.",
                            checked = s.jarvis.selfCodeAutoMerge,
                            onChange = { v -> vm.update { it.copy(jarvis = it.jarvis.copy(selfCodeAutoMerge = v)) } },
                        )
                        OutlinedTextField(
                            goal, { goal = it }, label = { Text("Goal — what to change") },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        OutlinedTextField(
                            path, { path = it }, label = { Text("Target file (e.g. app/src/…/Foo.kt)") },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        PrefClickable(
                            "Propose change → open PR",
                            subtitle = selfCodeStatus.ifBlank { "Drafts the change with the cloud model and opens a PR." },
                            onClick = { vm.proposeSelfChange(goal, path) },
                        )
                        Text(
                            "⚠ Experimental. The AI writes app code; CI must pass before anything can ship, " +
                                "protected files (CI/signing/safety gates) are off-limits, and you confirm every " +
                                "install. Turn this off to halt it.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
            item { HorizontalDivider() }

            // ----- Appearance -----
            item {
                PrefSection("Appearance") {
                    AccentSwatchRow(selected = s.accentColor, onSelect = { a -> vm.update { it.copy(accentColor = a) } })
                    PrefSwitch("AMOLED black", "True-black surfaces, saves OLED power", s.amoledBlack) { v ->
                        vm.update { it.copy(amoledBlack = v) }
                    }
                    PrefSwitch("Scanlines & CRT", "Retro display overlay", s.scanlines) { v ->
                        vm.update { it.copy(scanlines = v) }
                    }
                    PrefSwitch("Glitch effects", "Animated chromatic distortion", s.glitch) { v ->
                        vm.update { it.copy(glitch = v) }
                    }
                    PrefSwitch("Boot sequence", "Terminal animation on launch", s.bootAnimation) { v ->
                        vm.update { it.copy(bootAnimation = v) }
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

            // ----- Home dashboard -----
            item {
                PrefSection("Home dashboard") {
                    Text(
                        "Toggle and reorder the sections shown on Home.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            item {
                HomeSectionEditor(
                    enabledOrdered = s.homeSections,
                    onChange = { list -> vm.update { it.copy(homeSections = list) } },
                )
            }
            item { HorizontalDivider() }

            // ----- Watchlist -----
            item { PrefSection("Markets watchlist") {} }
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
            item { HorizontalDivider() }

            // ----- Custom feeds -----
            item { PrefSection("Custom RSS feeds") {} }
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
            item { HorizontalDivider() }

            // ----- Muted keywords -----
            item { PrefSection("Muted keywords") {} }
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
            item { HorizontalDivider() }

            // ----- Image search sites -----
            item { PrefSection("Image search sites") {} }
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
                    EditableValueRow("NewsAPI.org", masked(s.apiKeys.newsApi)) { v ->
                        vm.update { it.copy(apiKeys = it.apiKeys.copy(newsApi = v.trim())) }
                    }
                    EditableValueRow("FRED (US economy)", masked(s.apiKeys.fred)) { v ->
                        vm.update { it.copy(apiKeys = it.apiKeys.copy(fred = v.trim())) }
                    }
                    EditableValueRow("EIA (US fuel)", masked(s.apiKeys.eia)) { v ->
                        vm.update { it.copy(apiKeys = it.apiKeys.copy(eia = v.trim())) }
                    }
                    EditableValueRow("Finnhub (markets)", masked(s.apiKeys.finnhub)) { v ->
                        vm.update { it.copy(apiKeys = it.apiKeys.copy(finnhub = v.trim())) }
                    }
                    EditableValueRow("OpenWeatherMap", masked(s.apiKeys.openWeatherMap)) { v ->
                        vm.update { it.copy(apiKeys = it.apiKeys.copy(openWeatherMap = v.trim())) }
                    }
                    EditableValueRow("NASA (asteroids)", masked(s.apiKeys.nasa)) { v ->
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

            // ----- Storage & about -----
            item {
                PrefSection("Storage & about") {
                    PrefClickable("Cached data", value = Formatters.compact(cacheSize.toDouble()) + " B",
                        onClick = { vm.refreshCacheSize() })
                    PrefClickable("Clear cache", onClick = { vm.clearCache() })
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
            }
        }
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
private fun EditableValueRow(title: String, value: String, onSet: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    PrefClickable(title, value = value, onClick = { show = true })
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
    Row(
        Modifier.fillMaxWidth()
            .then(if (enabled) Modifier.clickable { show = true } else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(current, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (show) {
        AlertDialog(
            onDismissRequest = { show = false },
            confirmButton = { TextButton(onClick = { show = false }) { Text("Close") } },
            title = { Text(title) },
            text = {
                PrefRadioGroup(options = options, selected = selected, onSelect = { onSelect(it); show = false })
            },
        )
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

@Composable
private fun AddTextRow(title: String, onAdd: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    PrefClickable(title, onClick = { show = true })
    if (show) {
        TextEditDialog(title, initial = "", onDismiss = { show = false },
            onConfirm = { if (it.isNotBlank()) onAdd(it.trim()); show = false })
    }
}
