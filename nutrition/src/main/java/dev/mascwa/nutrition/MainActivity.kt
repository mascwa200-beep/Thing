package dev.mascwa.nutrition

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import dev.mascwa.nutrition.ui.NutritionApp
import dev.mascwa.nutrition.ui.NutritionTheme
import dev.mascwa.pulse.crash.Breadcrumbs
import dev.mascwa.pulse.feature.health.HealthViewModel
import kotlinx.coroutines.launch

/**
 * The whole application: one activity, six tabs, no gate.
 *
 * ⚠️ **The view model is the shared one, verbatim.** Every figure this app shows — a calorie target,
 * a weight trend, what a portion contributes — comes out of the same `HealthViewModel` the LCARS
 * application's HEALTH tab uses, because these are numbers somebody eats to and two copies of that
 * arithmetic would eventually disagree. What differs between the applications is the dependency
 * bundle handed to it, and nothing else.
 */
class MainActivity : ComponentActivity() {

    /**
     * ⚠️ **The application's container, not one of its own.** Two containers in one process means two
     * of every store, and several of those hold a DataStore over a fixed file — which throws on a
     * second instance rather than quietly working. This activity used to build its own, which was
     * safe only while nothing else did.
     */
    private val container by lazy { (application as NutritionApplication).container }

    /**
     * ⚠️ `by viewModels` rather than constructing it in `setContent`, so it survives a rotation.
     * Built in a composable it would be discarded and rebuilt on every configuration change, and
     * with it the day being viewed, a half-typed weight and every in-flight lookup.
     */
    private val vm: HealthViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HealthViewModel(container.healthDeps) as T
        }
    }

    /**
     * Keep the app current without anybody having to think about it.
     *
     * ⚠️ **The clear and the check share ONE coroutine, in that order.** As two launches the clear
     * could land after a fresh download had set the marker, wiping the guard at the exact moment it
     * was needed. The guard's whole job is to stop a build that will not install being fetched
     * again on every launch for ever.
     *
     * ⚠️ **Unmetered only.** This APK carries the barcode database and is a very large download;
     * pulling it over a mobile connection because somebody opened the app on a train is not a cost
     * to impose silently. When the network cannot be classified the answer is "metered", which is
     * the safe direction — it costs a delayed update, never an unexpected bill.
     *
     * ⚠️ **Throttled, because this runs on every foreground and logging a meal is a foreground.**
     * Somebody who opens this app at breakfast, lunch, a snack and dinner, and again to check a
     * label in a shop, made a network round trip to GitHub for each of them — for an answer that
     * cannot have changed in the meantime. The interval matches the LCARS app's, which has had one
     * since auto-update was written; this is the same rule arriving late rather than a new one.
     * In memory rather than persisted on purpose: a fresh process SHOULD check, and it is the
     * repeated open of a live one that is wasteful.
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

    /**
     * ⚠️ **Installing happens on the way OUT, not on the way in, and that is correctness rather
     * than politeness.** Android tears this process down while its own package is replaced, so
     * committing while somebody is reading a page makes the app vanish mid-sentence — which reads
     * as a crash, and is worse than the tap it saves.
     */
    override fun onStop() {
        super.onStop()
        Breadcrumbs.drop("app", "backgrounded")
        // ⚠️ The flush and the install are ONE coroutine, in this order, and that is the whole
        // point. Every store debounces its write by a couple of seconds, so committing an install
        // first hands the process to Android while somebody's last few entries are still in memory.
        // As two launches there would be nothing sequencing them.
        lifecycleScope.launch {
            container.flushAll()
            if (container.updates.hasDownload) {
                Breadcrumbs.drop("update", "installing on the way out")
                container.updates.install()
            }
        }
    }

    /**
     * ⚠️ **[HealthViewModel.refresh] on every foreground, and the day is why.** The view model is
     * held `by viewModels`, so its `init` runs once per PROCESS — and a phone left on a kitchen
     * counter keeps this process alive for days. Without this call the day it settled on at
     * construction stays the day every meal is filed under, so breakfast lands on yesterday under a
     * header that says "Today", and the expenditure window then reads a day with two breakfasts
     * beside a day with none. The view model refuses to move somebody who deliberately stepped onto
     * another day, so this cannot yank a reader out of Tuesday.
     */
    override fun onStart() {
        super.onStart()
        Breadcrumbs.drop("app", "foregrounded")
        vm.refresh()
        maybeAutoUpdate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NutritionTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    NutritionApp(vm, container)
                }
            }
        }
    }

    private var lastUpdateCheckMs = 0L

    private companion object {
        /** Matches the LCARS app's, so one rule rather than two that can drift. */
        const val UPDATE_CHECK_MIN_INTERVAL_MS = 15 * 60 * 1000L
    }
}
