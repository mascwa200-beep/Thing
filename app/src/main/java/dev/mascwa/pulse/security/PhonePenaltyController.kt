package dev.mascwa.pulse.security

import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.telecom.TelecomManager
import dev.mascwa.pulse.core.telemetry.LifeProfile
import dev.mascwa.pulse.core.telemetry.NeedKind
import dev.mascwa.pulse.core.telemetry.PhonePenalties
import dev.mascwa.pulse.core.telemetry.PhonePenalties.PhoneLock
import dev.mascwa.pulse.data.settings.SettingsRepository
import dev.mascwa.pulse.notifications.Notifier
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Turns the pure [PhonePenalties] decision into real, reversible Device-Owner locks: given the current
 * survival needs, it engages a capability lock for each critically-neglected need and lifts it once the need
 * recovers — the "neglect bites the phone" penalty. Safe by construction:
 *  - **Opt-in**: a no-op unless [dev.mascwa.pulse.data.settings.AppSettings.phonePenalties] is on AND Pulse is
 *    a Device Owner.
 *  - **Reversible + transition-only**: it only flips a capability flag when its need crosses the engage/release
 *    line, so it never stomps an unrelated manual control; turning the master switch off (or [releaseAll])
 *    lifts everything.
 *  - **Never strands you**: distraction-app suspension always excludes Pulse, the launcher, the dialer and
 *    Settings; no lock here can prevent tending a need or placing an emergency call. (The kiosk tier is separate.)
 */
class PhonePenaltyController(
    context: Context,
    private val settings: SettingsRepository,
    private val devicePolicy: DevicePolicyController,
    private val notifier: Notifier,
) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()

    /**
     * Reconcile the locks against the [life] needs snapshot — engages newly-critical needs' locks and lifts
     * recovered ones. Call on app foreground and from the background worker. Serialised so a foreground and a
     * worker pass can't race on the persisted state. When [fireGate] is true (the background worker only) and
     * the harsher kiosk tier is on, it also fires the lock-screen gate for genuinely-critical needs, throttled.
     */
    suspend fun reconcile(life: LifeProfile, fireGate: Boolean = false) = mutex.withLock {
        val s = settings.current()
        val prev = decode(s.phonePenalisedNeeds)
        if (!s.phonePenalties || !devicePolicy.isDeviceOwner()) {
            // Feature off (or not a Device Owner) → make sure nothing is left engaged.
            if (prev.isNotEmpty()) {
                setLocks(PhonePenalties.locksFor(prev), on = false, apps = s.phonePenaltyDistractionApps)
                settings.update { it.copy(phonePenalisedNeeds = emptyList()) }
            }
            return@withLock
        }
        val now = PhonePenalties.penalisedNeeds(life, prev)
        if (now != prev) {
            val prevLocks = PhonePenalties.locksFor(prev)
            val nowLocks = PhonePenalties.locksFor(now)
            setLocks(nowLocks - prevLocks, on = true, apps = s.phonePenaltyDistractionApps)   // engage newly-critical
            setLocks(prevLocks - nowLocks, on = false, apps = s.phonePenaltyDistractionApps)  // lift recovered
            settings.update { it.copy(phonePenalisedNeeds = now.map { n -> n.name }) }
        }
        // The harsher kiosk lock-screen gate — fire (background worker only) when a need is genuinely critical
        // right now and the cooldown has elapsed, so a just-completed gate can't immediately re-pop.
        if (fireGate && s.phonePenaltyKiosk) {
            val critical = now.filter { it.value(life) <= PhonePenalties.ENGAGE_AT }
            if (critical.isNotEmpty() && System.currentTimeMillis() - s.lastPenaltyGateMs >= GATE_COOLDOWN_MS) {
                val nowMs = System.currentTimeMillis()
                notifier.notifyPenaltyGate(critical.map { it.name })
                settings.update { it.copy(lastPenaltyGateMs = nowMs) }
            }
        }
    }

    /** Unconditionally lift every penalty lock and clear the state — the guaranteed escape hatch. */
    suspend fun releaseAll() = mutex.withLock {
        setLocks(PhoneLock.entries.toSet(), on = false, apps = settings.current().phonePenaltyDistractionApps)
        settings.update { it.copy(phonePenalisedNeeds = emptyList()) }
    }

    private fun setLocks(locks: Set<PhoneLock>, on: Boolean, apps: List<String>) {
        locks.forEach { lock ->
            when (lock) {
                PhoneLock.DISABLE_CAMERA -> devicePolicy.setCameraDisabled(on)
                PhoneLock.BLOCK_INSTALLS -> devicePolicy.setUserRestriction(UserManager.DISALLOW_INSTALL_APPS, on)
                PhoneLock.LOCK_QUICK_SETTINGS -> devicePolicy.setStatusBarDisabled(on)
                PhoneLock.LOCK_VOLUME -> devicePolicy.setUserRestriction(UserManager.DISALLOW_ADJUST_VOLUME, on)
                PhoneLock.BLOCK_SCREENSHOTS -> devicePolicy.setScreenCaptureDisabled(on)
                PhoneLock.PAUSE_DISTRACTIONS -> devicePolicy.setPackagesSuspended(safeApps(apps), on)
            }
        }
    }

    private fun decode(names: List<String>): Set<NeedKind> =
        names.mapNotNullTo(mutableSetOf()) { runCatching { NeedKind.valueOf(it) }.getOrNull() }

    /** Never suspend Pulse, the launcher, the dialer or Settings — suspending any could strand the owner. */
    private fun safeApps(apps: List<String>): List<String> {
        val protectedPkgs = buildSet {
            add(appContext.packageName)
            runCatching { appContext.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()?.let { add(it) }
            launcherPackage()?.let { add(it) }
            add("com.android.settings")
        }
        return apps.filter { it.isNotBlank() && it !in protectedPkgs }
    }

    private fun launcherPackage(): String? = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        appContext.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
    }.getOrNull()

    companion object {
        /** Min gap between kiosk lock-screen gates — a just-completed gate can't re-pop within this window
         *  (gives ENERGY's rest window time to recover so REST doesn't immediately re-lock you). */
        private const val GATE_COOLDOWN_MS = 2L * 60 * 60 * 1000 // ~2 hours
    }
}
