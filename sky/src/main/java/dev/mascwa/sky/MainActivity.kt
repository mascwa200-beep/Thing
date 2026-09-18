package dev.mascwa.sky

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import dev.mascwa.pulse.crash.Breadcrumbs
import dev.mascwa.pulse.feature.sky.SkyMapViewModel
import kotlinx.coroutines.launch

/**
 * The whole application: one activity, one map, no gate and no navigation.
 *
 * ⚠️ **The view model is the shared one, verbatim** — the same `SkyMapViewModel` the LCARS
 * application's sky map drives, so where a star is drawn is decided once. What differs between the
 * two applications is the dependency bundle handed to it ([SkyHardware] here, `SkyDevice` there)
 * and the chrome around the chart, and nothing else.
 *
 * ⚠️ `by viewModels` rather than constructing it in `setContent`, so it survives a rotation. Built
 * in a composable it would be discarded and rebuilt on every configuration change — and with it the
 * bright catalogue's 8,404 parsed rows, the memory-mapped deep catalogue, the constellation shapes,
 * the deep-sky layer and the Milky Way raster, all re-read from disk for a turn of the wrist.
 */
class MainActivity : ComponentActivity() {

    private val container by lazy { (application as SkyApplication).container }

    private val vm: SkyMapViewModel by viewModels { container.viewModelFactory }

    /**
     * Keep the app current without anybody having to think about it.
     *
     * ⚠️ **The clear and the check share ONE coroutine, in that order.** As two launches the clear
     * could land after a fresh download had set the marker, wiping the guard at the exact moment it
     * was needed. The guard's whole job is to stop a build that will not install being fetched again
     * on every launch for ever.
     *
     * ⚠️ **Unmetered only.** This APK carries three million stars and is a large download; pulling
     * it over a mobile connection because somebody opened the app under a dark sky miles from a
     * router is not a cost to impose silently. When the network cannot be classified the answer is
     * "metered", which is the safe direction — it costs a delayed update, never an unexpected bill.
     *
     * ⚠️ **Throttled, because this runs on every foreground.** Somebody stepping outside, checking
     * the map, putting the phone away and looking again ten minutes later made a network round trip
     * to GitHub for each of those, for an answer that cannot have changed. In memory rather than
     * persisted on purpose: a fresh process SHOULD check, and it is the repeated open of a live one
     * that is wasteful. The interval matches the other two applications', so there is one rule.
     */
    private fun maybeAutoUpdate() {
        lifecycleScope.launch {
            if (!container.updates.clearPendingIfLanded()) return@launch
            if (container.updates.hasDownload) return@launch
            val now = System.currentTimeMillis()
            if (now - lastUpdateCheckMs < UPDATE_CHECK_MIN_INTERVAL_MS) return@launch
            if (!unmetered()) return@launch
            // ⚠️ Stamped only once the metered check has passed, so a run of opens on mobile data
            // does not consume the window and leave the first Wi-Fi open silently skipped.
            lastUpdateCheckMs = now
            val info = container.updates.check() ?: return@launch
            container.updates.download(info)
        }
    }

    private fun unmetered(): Boolean = runCatching {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps != null &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)

    override fun onStart() {
        super.onStart()
        Breadcrumbs.drop("app", "foregrounded")
        maybeAutoUpdate()
    }

    /**
     * ⚠️ **Installing happens on the way OUT, not on the way in, and that is correctness rather than
     * politeness.** Android tears this process down while its own package is replaced, so committing
     * while somebody is looking at the sky makes the app vanish mid-sentence — which reads as a
     * crash, and is worse than the tap it saves.
     *
     * ⚠️ **Nothing is flushed first, and that is a real difference from the other two applications.**
     * Both of those write a store from `onStop` before installing, because they hold a food log or a
     * study deck that cannot be refetched. This one persists exactly three preferences, each written
     * synchronously at the moment it is set, so there is nothing buffered to lose.
     */
    override fun onStop() {
        super.onStop()
        Breadcrumbs.drop("app", "backgrounded")
        if (container.updates.hasDownload) {
            lifecycleScope.launch {
                Breadcrumbs.drop("update", "installing on the way out")
                container.updates.install()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SkyTheme {
                Surface(Modifier.fillMaxSize()) {
                    StarMapScreen(vm, container)
                }
            }
        }
    }

    private var lastUpdateCheckMs = 0L

    private companion object {
        /** Matches the other two applications', so one rule rather than three that can drift. */
        const val UPDATE_CHECK_MIN_INTERVAL_MS = 15 * 60 * 1000L
    }
}
