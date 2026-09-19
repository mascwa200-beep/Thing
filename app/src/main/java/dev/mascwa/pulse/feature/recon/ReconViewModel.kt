package dev.mascwa.pulse.feature.recon

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.telemetry.Recon
import dev.mascwa.pulse.core.telemetry.Recon.ReconCollection
import dev.mascwa.pulse.data.recon.ReconEngine
import dev.mascwa.pulse.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Drives TROVE — the reconnaissance dossier.
 *
 * The six collections are gathered as six independent child coroutines, not the engine's sequential
 * [ReconEngine.full], so the fast sections (the device, the file on you, the environment) fill in at
 * once while the slow ones (the subnet sweep, the BLE burst, the around-you feeds) resolve over the
 * next several seconds. The screen shows a scanning placeholder for a key that has not returned yet.
 *
 * The reading order is fixed ([ORDER]); completion order is not, so the map is keyed and rendered in
 * order regardless of which section finishes first.
 */
class ReconViewModel(container: AppContainer) : ViewModel() {

    private val engine = ReconEngine(container)

    data class UiState(
        /** Completed collections, keyed by their [ReconCollection.key]. */
        val done: Map<String, ReconCollection> = emptyMap(),
        val scanning: Boolean = false,
        val startedMs: Long = 0L,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init { scan() }

    fun scan() {
        _state.update { UiState(scanning = true, startedMs = System.currentTimeMillis()) }
        viewModelScope.launch {
            var remaining = ORDER.size
            for (key in ORDER) {
                launch {
                    val col = runCatching { gather(key) }.getOrNull()
                    _state.update { s ->
                        val done = if (col != null) s.done + (key to col) else s.done
                        remaining--
                        s.copy(done = done, scanning = remaining > 0)
                    }
                }
            }
        }
    }

    private suspend fun gather(key: String): ReconCollection = when (key) {
        "network" -> engine.network()
        "radios" -> engine.radios()
        "device" -> engine.device()
        "you" -> engine.aboutYou()
        "environment" -> engine.environment()
        "around" -> engine.aroundYou()
        else -> error("unknown collection $key")
    }

    /** The whole dossier as it stands, in reading order — for the exported report ([Recon.reportText]). */
    fun collectionsInOrder(): List<ReconCollection> =
        ORDER.mapNotNull { _state.value.done[it] }

    /**
     * Write the dossier to a file the user picked (SAF). Offline — nothing crosses the network; the
     * report is only ever the sections already gathered on screen. Off the main thread, and defensive:
     * a write that fails costs nothing but the file.
     *
     * The header timestamp is device-locale (it is a heading a person reads, not a parsed field), while
     * the body's numbers were formatted Locale.US by the engine.
     */
    fun export(context: Context, uri: Uri) {
        val cols = collectionsInOrder()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
                    val text = Recon.reportText(cols, "TROVE — this device's dossier · $stamp")
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
                }
            }
        }
    }

    companion object {
        /** The dossier's reading order: the two invasive collections first, then the device, you, the world. */
        val ORDER = listOf("network", "radios", "device", "you", "environment", "around")

        /** Human-facing section titles for a key that has not returned yet (the scanning placeholder). */
        val PENDING_TITLE = mapOf(
            "network" to "The network, and who is on it",
            "radios" to "Radios in range",
            "device" to "This device, laid bare",
            "you" to "The file the app has on you",
            "environment" to "The environment right now",
            "around" to "Where you are, and what is around",
        )
    }
}
