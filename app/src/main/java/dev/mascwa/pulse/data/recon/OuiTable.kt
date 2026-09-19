package dev.mascwa.pulse.data.recon

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The bundled OUI (MAC → vendor) table, loaded once and shared by both TROVE scanners.
 *
 * `assets/recon/oui.tsv` is 40k+ rows built from the IEEE MA-L registry (see `tools/recon/build_oui.py`).
 * It is read once, on IO, and kept — the file is a megabyte and both the network sweep and the radio
 * scan resolve vendors against it, so parsing it per scan would be wasteful and reading it on the caller's
 * thread would be a main-thread file read. The map is immutable once built, so every later reader is free.
 *
 * ⚠️ Fully defensive: a missing or unreadable asset yields an empty map, and [dev.mascwa.pulse.core
 * .telemetry.Recon.resolveMac] then reports "unknown vendor" rather than failing — the honest answer, and
 * the same one it gives for a hardware address the registry does not list.
 */
class OuiTable(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Mutex()
    @Volatile private var cached: Map<String, String>? = null

    suspend fun map(): Map<String, String> {
        cached?.let { return it }
        return lock.withLock {
            cached ?: withContext(Dispatchers.IO) {
                runCatching {
                    val out = HashMap<String, String>(45_000)
                    appContext.assets.open(ASSET).bufferedReader().useLines { lines ->
                        for (line in lines) {
                            val tab = line.indexOf('\t')
                            if (tab == 6) out[line.substring(0, 6)] = line.substring(tab + 1)
                        }
                    }
                    out as Map<String, String>
                }.getOrDefault(emptyMap()).also { cached = it }
            }
        }
    }

    private companion object {
        const val ASSET = "recon/oui.tsv"
    }
}
