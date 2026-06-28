package dev.mascwa.pulse.feature.dial

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the Reactor Dial — the arc-reactor rotary app launcher. Loads the device's launchable apps (for the
 * picker), exposes the user's per-slot pins from settings, and launches an app on tap. All on-device; uses
 * QUERY_ALL_PACKAGES (already held) to enumerate launchers.
 */
class ReactorDialViewModel(
    private val appContext: Context,
    private val settings: SettingsRepository,
) : ViewModel() {

    /** A launchable app: stable [packageName] + display [label]. (Icons are loaded lazily in the UI.) */
    data class AppEntry(val packageName: String, val label: String)

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    /** The package pinned to each of [NUM_SLOTS] positions ("" = empty), normalized to a fixed length. */
    val slots: StateFlow<List<String>> = settings.settings
        .map { normalizeSlots(it.reactorDialSlots) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, normalizeSlots(emptyList()))

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _apps.value = loadApps()
            // Now that labels are known, arrange any existing pins alphabetically (one-time, only if needed).
            runCatching {
                val cur = normalizeSlots(settings.current().reactorDialSlots)
                val sorted = sortedSlots(cur)
                if (sorted != cur) {
                    settings.update { it.copy(reactorDialSlots = sorted) }
                    dev.mascwa.pulse.widget.DialWidgetProvider.refresh(appContext)
                }
            }
        }
    }

    /** The pins arranged alphabetically by app name, empties last, normalized to [NUM_SLOTS]. */
    private fun sortedSlots(list: List<String>): List<String> {
        val filled = list.filter { it.isNotEmpty() }.distinct().sortedBy { labelFor(it).lowercase() }
        return (0 until NUM_SLOTS).map { filled.getOrNull(it) ?: "" }
    }

    private fun loadApps(): List<AppEntry> = runCatching {
        val pm = appContext.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(main, 0)
            .mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                AppEntry(pkg, ri.loadLabel(pm).toString())
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }.getOrDefault(emptyList())

    fun assign(slot: Int, packageName: String) = viewModelScope.launch {
        settings.update { s ->
            val list = normalizeSlots(s.reactorDialSlots).toMutableList()
            if (slot in list.indices) list[slot] = packageName
            s.copy(reactorDialSlots = sortedSlots(list))
        }
        dev.mascwa.pulse.widget.DialWidgetProvider.refresh(appContext)
    }

    fun clear(slot: Int) = viewModelScope.launch {
        settings.update { s ->
            val list = normalizeSlots(s.reactorDialSlots).toMutableList()
            if (slot in list.indices) list[slot] = ""
            s.copy(reactorDialSlots = sortedSlots(list))
        }
        dev.mascwa.pulse.widget.DialWidgetProvider.refresh(appContext)
    }

    /** Launch the pinned app. Returns false if it has no launch intent (e.g. uninstalled since pinned). */
    fun launch(context: Context, packageName: String): Boolean = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return false
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    /** Best-effort display label for a pinned package (falls back to the package name). */
    fun labelFor(packageName: String): String =
        _apps.value.firstOrNull { it.packageName == packageName }?.label ?: packageName

    companion object {
        const val NUM_SLOTS = 8

        fun normalizeSlots(stored: List<String>): List<String> =
            (0 until NUM_SLOTS).map { stored.getOrNull(it) ?: "" }
    }
}
