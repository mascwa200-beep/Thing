package dev.mascwa.pulse.feature.lockboard

import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.security.DevicePolicyController
import dev.mascwa.pulse.widget.LockBoard
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Puts the lock-screen board in place as the screen goes off, and again as it comes on if it is not
 * already there. Every decision is [LockBoardPolicy]'s; this only gathers the facts and obeys.
 *
 * ⚠️ **The screen broadcasts can only be received by a receiver registered at runtime**, so this
 * lives as long as the app's process does — the one the ambient watch and the emergency watch keep
 * running. A phone where every one of those is switched off can have its process reclaimed, and
 * then there is nothing listening until LCARS is next opened. That is the platform's rule, not a
 * choice; the Settings row says what the board needs rather than letting it quietly not appear.
 *
 * ⚠️ **Main process only.** `PulseApplication.onCreate` runs in every process this app has, and a
 * second process registering would start a second board on every screen-off.
 */
object LockBoardTrigger {

    @Volatile
    private var enabled = false

    @Volatile
    private var installed = false

    fun install(app: PulseApplication) {
        if (installed) return
        if (Application.getProcessName() != app.packageName) return
        installed = true
        // Starts false and becomes the setting within moments of launch. A screen-off in that window
        // is simply not acted on, which is the safe way round for something that covers the screen.
        app.appScope.launch {
            app.container.settingsRepository.settings
                .map { it.lockScreenBoard }
                .distinctUntilChanged()
                .collect { enabled = it }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val trigger = when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> LockBoardPolicy.Trigger.SCREEN_OFF
                    Intent.ACTION_SCREEN_ON -> LockBoardPolicy.Trigger.SCREEN_ON
                    else -> return
                }
                onScreen(app, trigger)
            }
        }
        // No export flag: both are protected system broadcasts, which Android 14's registration rule
        // exempts — the same reasoning `SenseContextReader` records for USER_PRESENT.
        runCatching {
            app.registerReceiver(
                receiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                },
            )
        }.onFailure { installed = false }
    }

    private fun onScreen(app: PulseApplication, trigger: LockBoardPolicy.Trigger) {
        val facts = LockBoardPolicy.Facts(
            enabled = enabled,
            canLaunch = canLaunch(app),
            inCall = inCall(app),
            alertSounding = alertSounding(app),
            takeoverAlive = LockScreenGuests.any,
            boardAlive = LockBoardActivity.alive,
            keyguardLocked = app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true,
        )
        if (LockBoardPolicy.decide(trigger, facts) != null) return
        // Read the board while the screen is dark, so it is current when the screen comes back.
        LockBoard.refreshIfStale(app, app.appScope, System.currentTimeMillis())
        runCatching {
            app.startActivity(
                Intent(app, LockBoardActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        }.onFailure { err ->
            dev.mascwa.pulse.crash.Breadcrumbs.drop("lockboard", "could not start: ${err.javaClass.simpleName}")
        }
    }

    /**
     * Whether Android will let this app open the board while it has no screen of its own. Public for
     * Settings, which says what is missing when it cannot.
     */
    fun canLaunch(context: Context): Boolean = LockBoardPolicy.canLaunch(
        deviceOwner = DevicePolicyController.isDeviceOwner(context),
        overlayGranted = Settings.canDrawOverlays(context),
        sdkInt = Build.VERSION.SDK_INT,
        targetSdk = context.applicationInfo.targetSdkVersion,
    )

    /**
     * A call is ringing or under way. The audio mode is the one reading that needs no phone
     * permission, and it covers VoIP calls too, which a telephony call-state read would not.
     */
    private fun inCall(context: Context): Boolean =
        runCatching {
            context.getSystemService(AudioManager::class.java)?.mode?.let { it != AudioManager.MODE_NORMAL } == true
        }.getOrDefault(false)

    /**
     * An alarm clock or a ringtone is sounding: something else woke the phone, and it is on screen.
     * Read from the active playback list, which any app may see, rather than from the alarm app.
     */
    private fun alertSounding(context: Context): Boolean =
        runCatching {
            context.getSystemService(AudioManager::class.java)?.activePlaybackConfigurations.orEmpty().any {
                val usage = it.audioAttributes.usage
                usage == AudioAttributes.USAGE_ALARM || usage == AudioAttributes.USAGE_NOTIFICATION_RINGTONE
            }
        }.getOrDefault(false)
}
