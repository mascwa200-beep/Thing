package dev.mascwa.pulse.feature.launcher

import android.app.ActivityOptions
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.data.comms.NotificationAccess
import dev.mascwa.pulse.data.settings.AppSettings
import dev.mascwa.pulse.feature.lcarsboard.BoardTap
import dev.mascwa.pulse.feature.lcarsboard.ConsoleSequence
import dev.mascwa.pulse.feature.lcarsboard.hideStatusBarForConsole
import dev.mascwa.pulse.feature.shade.NoticeActions
import dev.mascwa.pulse.feature.shade.ShadeDigest
import dev.mascwa.pulse.feature.shade.ShadeStore
import dev.mascwa.pulse.ui.ProvideStardate
import dev.mascwa.pulse.ui.theme.NightwireTheme
import dev.mascwa.pulse.widget.LockBoard
import dev.mascwa.pulse.widget.openRouteIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The LCARS home screen — what the Home button opens once it is switched on.
 *
 * ## How it becomes Home
 *
 * ⚠️ **This activity is not a Home by itself.** The HOME intent filter lives on an `activity-alias`
 * declared DISABLED in the manifest, so a phone that has not switched the home screen on never even
 * sees LCARS offered as a launcher, and switching it off removes it completely rather than leaving a
 * second Home to be chosen by accident. As device owner the alias is switched on ONCE by default
 * ([HomeSwitch.applyDefaultOnce], on the first start of a build carrying it); after that the Settings
 * switch is the only thing that changes it.
 *
 * ## What it draws
 *
 * [HomeConsole] — the lock screen's console, with the dock, a search box, the board and every app.
 * The board is the widget's, from [LockBoard], so the widget, the lock screen and the home screen
 * show one reading of the world.
 *
 * ## If it crashes as it starts
 *
 * A default Home that dies on start is a phone whose Home button leads nowhere, reopened by Android
 * the moment it dies. So every start is counted ([HomeGuardPolicy]) and, after three that never lived
 * five seconds within two minutes, this hands Home back to the previous launcher and switches itself
 * off. Settings says when that happened.
 *
 * ## The Home button
 *
 * Pressing Home while already home clears the search and goes back to the top, which is what every
 * launcher does and what the button is for. Back only ever clears the search: a home screen has
 * nothing behind it, and finishing it would hand the phone to whatever launcher Android picks next.
 */
class LauncherActivity : ComponentActivity() {

    private val apps by lazy { HomeApps(applicationContext) }
    private val store by lazy { HomeStore(applicationContext) }

    private val appList = mutableStateOf<List<HomeApp>?>(null)
    private val pins = mutableStateOf<List<String>>(emptyList())
    private val query = mutableStateOf("")
    private val notice = mutableStateOf<String?>(null)
    private val progress = mutableFloatStateOf(0f)

    /**
     * Whether notification access is switched on, read again every time the home screen comes back —
     * the switch is flipped in a system page, so the return is exactly when it may have changed.
     */
    private val noticeGranted = mutableStateOf(false)

    /** Counts Home presses, so the screen can go back to the top on each without keeping a flag. */
    private val homePresses = mutableIntStateOf(0)

    private var unwatch: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (standDownIfLooping()) return
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        // The console owns the top of the screen: Android's status bar is hidden, and the header
        // carries the time and the battery instead. The gesture bar stays.
        hideStatusBarForConsole()

        val defaults = AppSettings()
        val sweepMs = ConsoleSequence.durationFor(animatorScale())
        val iconPx = (DOCK_ICON.value * resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
        val loadIcon: suspend (HomeApp) -> ImageBitmap? = { app ->
            withContext(Dispatchers.IO) { apps.icon(app, iconPx)?.asImageBitmap() }
        }
        val actions = HomeActions(
            launch = ::launch,
            togglePin = ::togglePin,
            info = { app -> if (!apps.openInfo(app)) notice.value = "Android would not open the page for ${app.label}." },
            tap = ::open,
            openLcars = ::openLcars,
            systemSettings = { start(Intent(Settings.ACTION_SETTINGS)) },
            notices = NoticeActions(
                open = ::openNotice,
                dismiss = { key ->
                    if (!ShadeStore.dismiss(key)) notice.value = "Android would not clear that notification."
                },
                clear = { keys ->
                    if (!ShadeStore.clear(keys)) notice.value = "Android would not clear the notifications."
                },
                grant = {
                    if (!NotificationAccess.openSettings(this)) {
                        notice.value = "Android would not open the notification-access page."
                    }
                },
            ),
        )

        // Registered BEFORE the content, so the screen's own BackHandler — added once it composes —
        // takes precedence while there is a search to clear, and this answers otherwise. A home
        // screen that finished on Back would hand the phone to whichever launcher Android chose next.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        setContent {
            NightwireTheme(accent = defaults.accentColor, amoledBlack = defaults.amoledBlack) {
                ProvideStardate {
                    val snapshot by LockBoard.latest.collectAsStateWithLifecycle()
                    val shade by ShadeStore.view.collectAsStateWithLifecycle()
                    val granted by noticeGranted
                    val list = rememberLazyListState()
                    val presses by homePresses
                    val typed by query

                    // The console assembles itself once, when the home screen first comes up.
                    LaunchedEffect(Unit) {
                        val start = withFrameMillis { it }
                        do {
                            val now = withFrameMillis { it }
                            progress.floatValue = ConsoleSequence.progressAt(now - start, true, 0f, sweepMs)
                        } while (progress.floatValue != 1f)
                    }
                    LaunchedEffect(presses) {
                        if (presses > 0) list.animateScrollToItem(0)
                    }
                    BackHandler(enabled = typed.isNotEmpty()) { query.value = "" }

                    val readProgress = remember { { progress.floatValue } }
                    HomeConsole(
                        board = snapshot?.board,
                        apps = appList.value,
                        pins = pins.value,
                        query = typed,
                        onQuery = { query.value = it; notice.value = null },
                        notice = notice.value,
                        noticeStatus = ShadeDigest.status(granted, shade.connected),
                        notices = shade.groups,
                        progress = readProgress,
                        list = list,
                        icon = loadIcon,
                        actions = actions,
                    )
                }
            }
        }

        reload()
        unwatch = apps.watch { reload() }
    }

    /**
     * The crash-loop guard ([HomeGuardPolicy]). True when this start is the one that stands the home
     * screen down, in which case it has already handed Home back and is finishing.
     *
     * ⚠️ Runs before anything that could crash, and its record is written synchronously, for the
     * reason `HomeStore.guardState` gives. A start that then lives [HomeGuardPolicy.HEALTHY_AFTER_MS]
     * clears the record, so only starts that die young count against it.
     */
    private fun standDownIfLooping(): Boolean {
        val now = System.currentTimeMillis()
        val verdict = runCatching { HomeGuardPolicy.onStart(store.guardState(), now) }.getOrNull() ?: return false
        runCatching { store.saveGuard(verdict.state) }
        if (verdict.standDown) {
            HomeSwitch.standDown(this, now)
            // Hand the phone to whichever Home Android now resolves — the old launcher, or the
            // picker if there is more than one. Never this one: its alias is off.
            runCatching {
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            finish()
            return true
        }
        lifecycleScope.launch {
            delay(HomeGuardPolicy.HEALTHY_AFTER_MS)
            withContext(Dispatchers.IO) { runCatching { store.saveGuard(HomeGuardPolicy.onHealthy()) } }
        }
        return false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // A bar swiped into view, or a window that was over this one, can leave it showing.
        if (hasFocus) hideStatusBarForConsole()
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            noticeGranted.value = withContext(Dispatchers.IO) { NotificationAccess.isGranted(applicationContext) }
        }
        (application as? PulseApplication)?.let { app ->
            LockBoard.refreshIfStale(app, app.appScope, System.currentTimeMillis())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed. Clear the search whenever it happens; go back to the top only when the home
        // screen was already the thing being looked at. "Already in front" is window focus and not
        // being brought forward — the test Android's own launcher makes, because an activity at the
        // top may be paused around a new intent, so the lifecycle state cannot answer it.
        val alreadyHome = hasWindowFocus() &&
            (intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) == 0
        query.value = ""
        notice.value = null
        if (alreadyHome) homePresses.intValue++
    }

    override fun onDestroy() {
        unwatch?.invoke()
        unwatch = null
        super.onDestroy()
    }

    private fun reload() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { apps.load() }
            appList.value = list
            pins.value = store.pins()
        }
    }

    private fun launch(app: HomeApp) {
        if (apps.launch(app)) {
            notice.value = null
        } else {
            notice.value = "Android would not open ${app.label}."
        }
    }

    private fun togglePin(app: HomeApp) {
        lifecycleScope.launch {
            val next = store.togglePin(app.key)
            if (next == null) {
                notice.value = "The dock holds ${HomeDirectory.MAX_PINS} apps. Unpin one to make room for ${app.label}."
            } else {
                pins.value = next
                notice.value = null
            }
        }
    }

    /** A tap on the board: a route opens LCARS on that screen, anything else is sent as it was given. */
    private fun open(tap: BoardTap) {
        runCatching {
            when (tap) {
                is BoardTap.Route -> startActivity(openRouteIntent(this, tap.route))
                is BoardTap.Sender -> startIntentSender(tap.sender, tap.fillIn, 0, 0, 0)
            }
        }.onFailure { notice.value = "That could not be opened." }
    }

    /**
     * Open what a notification opens, as tapping it on Android's shade would — and clear it afterwards
     * when the app asked Android to (`FLAG_AUTO_CANCEL`), because sending the intent ourselves does
     * not do that part.
     *
     * ⚠️ **The home screen lends its own right to start an activity.** A notification's intent was
     * made by another app, which is usually in the background, and since Android 14 a sender must opt
     * in to lending the foreground's permission to start one. Without it most taps would silently
     * open nothing.
     */
    private fun openNotice(key: String) {
        val handle = ShadeStore.handle(key)
        val sender = handle?.contentIntent
        if (handle == null || sender == null) {
            notice.value = "That notification has nothing to open."
            return
        }
        val options = ActivityOptions.makeBasic().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }
        }.toBundle()
        runCatching { startIntentSender(sender.intentSender, null, 0, 0, 0, options) }
            .onSuccess { if (handle.autoCancel) ShadeStore.dismiss(key) }
            .onFailure { notice.value = "That notification could not be opened." }
    }

    private fun openLcars() {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        start(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun start(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure { notice.value = "That could not be opened." }
    }

    /** The system's animator scale; 0 means "remove animations", which the power-up honours. */
    private fun animatorScale(): Float =
        runCatching { Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }
            .getOrDefault(1f)
}
