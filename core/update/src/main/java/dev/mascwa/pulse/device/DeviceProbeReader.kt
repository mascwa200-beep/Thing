package dev.mascwa.pulse.device

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import dev.mascwa.pulse.core.telemetry.DeviceClass

/**
 * Reads what this phone will admit about itself, and hands it to the pure [DeviceClass] core.
 *
 * ## Why this lives in `:core:update`
 *
 * Both applications need it. `:app`'s own `core/device/` package is unreachable from `:nutrition`,
 * and a second copy is the duplicated-definition drift this repository has corrected six times. A
 * new module would cost a `settings.gradle.kts` entry, a `module_dep_check.py` pass and two CI
 * wirings for about a hundred lines.
 *
 * ⚠️ `:core:update` is already the shared Android-infrastructure module in everything but name — it
 * carries `dev.mascwa.pulse.crash` (the reporter, the breadcrumb ring, the logcat filter) alongside
 * the updater, and both apps already depend on it directly. Adding `dev.mascwa.pulse.device` beside
 * `dev.mascwa.pulse.crash` follows that precedent rather than setting a new one. Renaming the module
 * to match what it actually is would be a bigger diff than this whole slice and belongs on its own.
 *
 * ## The rule every read here obeys
 *
 * ⚠️ **A read that fails returns null. There is no `?: 0` and no `?: false` in this file.** The core
 * treats null as *unknown* and unknown never demotes, so a swallowed exception that produced a zero
 * would classify a working phone as the weakest thing the app can imagine. Every read is in its own
 * `runCatching` so one unavailable service cannot take the others down with it.
 */
class DeviceProbeReader(context: Context) {

    private val app = context.applicationContext

    private val activityManager: ActivityManager?
        get() = runCatching { app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager }.getOrNull()

    private val powerManager: PowerManager?
        get() = runCatching { app.getSystemService(Context.POWER_SERVICE) as? PowerManager }.getOrNull()

    /**
     * The half that cannot change while the process lives, read once.
     *
     * ⚠️ Not `by lazy`: [coresSeen] below has to keep moving, and folding the two together would
     * freeze the core count at whatever the governor happened to have online at first read.
     */
    @Volatile
    private var staticProbe: DeviceClass.Probe? = null

    /**
     * ⚠️ **The highest core count seen this process, not the current one.**
     * `Runtime.availableProcessors()` reports cores that are ONLINE, and big.LITTLE governors park
     * cores aggressively when idle — which is exactly the moment an app starts up and takes a
     * reading. A single read can report an eight-core flagship as a two-core phone, and the tier
     * would then be wrong for the life of the process. A high-water mark can only be too generous,
     * and being too generous is the safe direction here.
     */
    @Volatile
    private var coresSeen: Int = 0

    /**
     * The heap ceiling this app actually gets, in MB.
     *
     * ⚠️ **`getMemoryClass()` is the WRONG number for an app that declares `android:largeHeap`, and
     * the LCARS application does.** The standard class is what an ordinary app is given; a large-heap
     * app is given `getLargeMemoryClass()`, which on the same phone is routinely two to three times
     * larger. Reading the standard one meant the tier was computed from a ceiling this process never
     * has — and in the direction that hurts, because a device reporting a 192 MB standard class votes
     * MODEST against `HEAP_MODEST_MB` while genuinely having 512 MB to spend.
     *
     * The flag is read from this app's own `ApplicationInfo` rather than assumed, so the standalone
     * nutrition app — which does not declare it — keeps getting the number that is true for it.
     */
    private fun heapCeilingMb(am: ActivityManager?): Int? {
        am ?: return null
        val large = runCatching {
            (app.applicationInfo.flags and ApplicationInfo.FLAG_LARGE_HEAP) != 0
        }.getOrDefault(false)
        return if (large) am.largeMemoryClass else am.memoryClass
    }

    private fun readStatic(): DeviceClass.Probe {
        val am = activityManager
        val info = runCatching {
            ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        }.getOrNull()

        return DeviceClass.Probe(
            totalRamBytes = info?.totalMem,
            lowRamFlagged = runCatching { am?.isLowRamDevice }.getOrNull(),
            memoryClassMb = runCatching { heapCeilingMb(am) }.getOrNull(),
            apiLevel = Build.VERSION.SDK_INT,
        )
    }

    /** Everything, static half cached and live half re-read. Cheap, but a handful of binder calls —
     *  hoist it out of anything that runs per frame. */
    fun probe(): DeviceClass.Probe {
        val base = staticProbe ?: readStatic().also { staticProbe = it }

        val cores = runCatching { Runtime.getRuntime().availableProcessors() }.getOrNull()
        if (cores != null && cores > coresSeen) coresSeen = cores

        val am = activityManager
        val pm = powerManager
        val live = runCatching {
            ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        }.getOrNull()

        return base.copy(
            availRamBytes = live?.availMem,
            systemLowMemory = live?.lowMemory,
            cores = coresSeen.takeIf { it > 0 },
            // API 29. Below that the phone genuinely cannot say, which is a null and not a zero:
            // reporting THERMAL_STATUS_NONE here would be inventing a measurement.
            thermalStatus = if (Build.VERSION.SDK_INT >= 29) {
                runCatching { pm?.currentThermalStatus }.getOrNull()
            } else null,
            heapUsedFraction = runCatching {
                val r = Runtime.getRuntime()
                val max = r.maxMemory()
                if (max <= 0L) null else (r.totalMemory() - r.freeMemory()).toFloat() / max.toFloat()
            }.getOrNull(),
            animatorScale = runCatching {
                Settings.Global.getFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE)
            }.getOrNull(),
            powerSave = runCatching { pm?.isPowerSaveMode }.getOrNull(),
            deviceIdle = runCatching { pm?.isDeviceIdleMode }.getOrNull(),
            // API 28.
            backgroundRestricted = if (Build.VERSION.SDK_INT >= 28) {
                runCatching { am?.isBackgroundRestricted }.getOrNull()
            } else null,
        )
    }

    fun tier(): DeviceClass.Tier = DeviceClass.tierOf(probe())

    fun pressure(): DeviceClass.Pressure = DeviceClass.pressureOf(probe())

    /** What the rest of the app may spend. The single entry point — see [DeviceClass.budgetFor]. */
    fun budget(): DeviceClass.Budget = DeviceClass.budgetFor(probe())

    /** One block of text for a diagnostic screen, naming what could not be measured as well as what could. */
    fun describe(): String = DeviceClass.describe(probe())
}
