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
fun SettingsScreen(vm: SettingsViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val cacheSize by vm.cacheSize.collectAsStateWithLifecycle()

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* state reflected by system; nothing else needed */ }

    PulseScaffold(title = "Settings") { innerPadding ->
        LazyColumn(modifier = Modifier.padding(innerPadding), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp)) {

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
                    PrefSwitch("Market & price alerts", checked = s.notifications.marketAlerts, enabled = on,
                        onChange = { v -> vm.update { it.copy(notifications = it.notifications.copy(marketAlerts = v)) } })
                    PrefSwitch("Weather alerts", checked = s.notifications.weatherAlerts, enabled = on,
                        onChange = { v -> vm.update { it.copy(notifications = it.notifications.copy(weatherAlerts = v)) } })
                    PrefSwitch("Daily digest", checked = s.notifications.dailyDigest, enabled = on,
                        onChange = { v -> vm.update { it.copy(notifications = it.notifications.copy(dailyDigest = v)) } })
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
                    PrefClickable("Send test notification", onClick = { vm.sendTestNotification() })
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
                }
            }
            item { HorizontalDivider() }

            // ----- Storage & about -----
            item {
                PrefSection("Storage & about") {
                    PrefClickable("Cached data", value = Formatters.compact(cacheSize.toDouble()) + " B",
                        onClick = { vm.refreshCacheSize() })
                    PrefClickable("Clear cache", onClick = { vm.clearCache() })
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
private fun AddTextRow(title: String, onAdd: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    PrefClickable(title, onClick = { show = true })
    if (show) {
        TextEditDialog(title, initial = "", onDismiss = { show = false },
            onConfirm = { if (it.isNotBlank()) onAdd(it.trim()); show = false })
    }
}
