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
        // The harsher kiosk lock-screen gate — fire (background worker only) whenever a need is genuinely
        // critical right now. No re-trigger cooldown (owner's explicit choice: max notification frequency,
        // including this mechanism) — the RELEASE side is untouched and remains the real safety net: the
        // gate's own backstop auto-release timer, override code, and emergency-dialer button (all in
        // PenaltyGateActivity) still guarantee you're never stuck, regardless of how often it re-engages.
        if (fireGate && s.phonePenaltyKiosk) {
            val critical = now.filter { it.value(life) <= PhonePenalties.ENGAGE_AT }
            if (critical.isNotEmpty()) {
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
                // Lock the apps for real: if the owner picked specific apps, lock those; otherwise lock EVERY
                // non-essential app (the default — all launchable apps minus the safe-list). Symmetric on
                // release (unsuspending a non-suspended app is a harmless no-op).
                PhoneLock.PAUSE_DISTRACTIONS ->
                    devicePolicy.setPackagesSuspended(safeApps(apps.ifEmpty { allLockableApps() }), on)
            }
        }
    }

    /** Every launchable app on the device — the candidate set for the broad "lock all apps" default. */
    private fun allLockableApps(): List<String> = runCatching {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        appContext.packageManager.queryIntentActivities(launcher, 0).map { it.activityInfo.packageName }.distinct()
    }.getOrDefault(emptyList())

    private fun decode(names: List<String>): Set<NeedKind> =
        names.mapNotNullTo(mutableSetOf()) { runCatching { NeedKind.valueOf(it) }.getOrNull() }

    /** Never suspend the essentials — suspending any of these could strand the owner or block emergencies:
     *  Pulse itself, the launcher (or you can't navigate), the dialer (emergencies), Settings, and the active
     *  keyboard (or you can't type to get out). Everything else is fair game. */
    private fun safeApps(apps: List<String>): List<String> {
        val protectedPkgs = buildSet {
            add(appContext.packageName)
            runCatching { appContext.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()?.let { add(it) }
            launcherPackage()?.let { add(it) }
            add("com.android.settings")
            // The active input method — suspending the keyboard would trap you unable to type.
            runCatching {
                android.provider.Settings.Secure.getString(appContext.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
                    ?.substringBefore('/')
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        return apps.filter { it.isNotBlank() && it !in protectedPkgs }
    }

    private fun launcherPackage(): String? = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        appContext.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
    }.getOrNull()

}
