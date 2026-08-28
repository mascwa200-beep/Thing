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

    /** See [budgetCached]. Null until something asks. */
    @Volatile
    private var cachedBudget: DeviceClass.Budget? = null

    @Volatile
    private var cachedAtMs: Long = 0L

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

    /** Which class of phone this is. See [durableBudget], its only caller — and [budget] otherwise. */
    fun tier(): DeviceClass.Tier = DeviceClass.tierOf(probe())

    /** What the rest of the app may spend. The single entry point — see [DeviceClass.budgetFor]. */
    fun budget(): DeviceClass.Budget = DeviceClass.budgetFor(probe())

    /**
     * The same budget, re-read at most every [BUDGET_TTL_MS].
     *
     * ⚠️ **[probe] is not free and [budget] must not be called per item.** It makes five binder
     * calls and one content-provider query, which is nothing once and ruinous inside a scrolling
     * list of thumbnails — [probe]'s own note says to hoist it out of anything running per frame,
     * and a per-image decode cap is exactly that. Anything on a hot path reads this instead.
     *
     * ⚠️ Deliberately unlocked. Two threads racing recompute the same thing and one wins; the cost
     * is one extra probe, where a lock on a path that exists to be cheap would be the worse trade.
     *
     * Thirty seconds because thermal state moves over minutes: a shorter window would pay the probe
     * repeatedly to watch a number that has not changed, and a longer one would keep spending a hot
     * phone's budget after it cooled.
     */
    fun budgetCached(nowMs: Long = System.currentTimeMillis()): DeviceClass.Budget {
        val held = cachedBudget
        if (held != null && nowMs - cachedAtMs in 0 until BUDGET_TTL_MS) return held
        val fresh = budget()
        cachedBudget = fresh
        cachedAtMs = nowMs
        return fresh
    }

    /**
     * The budget from the HARDWARE ALONE — thermal state and doze deliberately excluded.
     *
     * ⚠️ **For structures that are sized once and then kept for the life of the process**, of which
     * the image memory cache is the one that matters. Folding a momentary reading into a durable
     * structure gets the asymmetry backwards: a phone that happened to be warm at launch would hold
     * a small cache all day, long after it cooled, while a phone that goes hot later is already
     * covered — both applications clear the cache from `onTrimMemory`, which is the mechanism for
     * pressure that arrives after construction.
     *
     * A per-request decision — a decode size, a poll interval — should read [budgetCached] instead,
     * because there pressure genuinely is the right input.
     */
    fun durableBudget(): DeviceClass.Budget =
        DeviceClass.budgetFor(tier(), DeviceClass.Pressure.NONE)

    /** One block of text for a diagnostic screen, naming what could not be measured as well as what could. */
    fun describe(): String = DeviceClass.describe(probe())

    companion object {
        /** See [budgetCached]. */
        const val BUDGET_TTL_MS = 30_000L
    }
}
