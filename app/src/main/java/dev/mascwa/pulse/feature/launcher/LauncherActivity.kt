package dev.mascwa.pulse.feature.launcher

import android.Manifest
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
import androidx.activity.result.contract.ActivityResultContracts
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
import dev.mascwa.pulse.core.device.UsageAccess
import dev.mascwa.pulse.core.util.openUsageAccessSettings
import dev.mascwa.pulse.data.comms.NotificationAccess
import dev.mascwa.pulse.data.settings.AppSettings
import dev.mascwa.pulse.feature.lcarsboard.BoardTap
import dev.mascwa.pulse.feature.lcarsboard.ConsoleSequence
import dev.mascwa.pulse.feature.controls.ControlActions
import dev.mascwa.pulse.feature.controls.ControlsState
import dev.mascwa.pulse.feature.controls.PhoneControls
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

    /**
     * The recent-apps strip and whether Usage access allows it. Null until first read, so the strip
     * does not flash a "needs access" line on a phone that has it.
     */
    private val recent = mutableStateOf<List<HomeApp>>(emptyList())
    private val usageGranted = mutableStateOf<Boolean?>(null)

    private val phoneControls by lazy { PhoneControls(applicationContext) }
    private val controlReading = mutableStateOf<ControlsState.Reading?>(null)
    private val volumes = mutableStateOf<List<PhoneControls.Volume>>(emptyList())
    private val askBluetooth = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshControls()
    }

    /**
     * Other apps' widgets on the console — made once the crash-loop guard has let this start go ahead,
     * so a start that stands down never touches Android's widget host.
     */
    private var widgetHost: HomeWidgetHost? = null

    /** Android's own "allow LCARS to place widgets" prompt — see [HomeWidgetHost]. */
    private val bindWidget = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val host = widgetHost ?: return@registerForActivityResult
        lifecycleScope.launch { afterWidgetStep(host.onBindResult(result.resultCode == RESULT_OK)) }
    }

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
        val host = HomeWidgetHost(this, store).also { widgetHost = it }
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
                grant = ::grantNotices,
            ),
            controls = ControlActions(tap = ::tapControl, volume = ::adjustVolume),
            grantUsage = {
                if (!openUsageAccessSettings(this)) notice.value = "Android would not open the Usage access page."
            },
            widgets = WidgetActions(
                view = host::view,
                sized = host::sized,
                offers = { host.offers() },
                add = { offer -> lifecycleScope.launch { afterWidgetStep(host.begin(offer)) } },
                remove = { id -> lifecycleScope.launch { host.remove(id) } },
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
                    val torch by phoneControls.torch.collectAsStateWithLifecycle()
                    val reading by controlReading
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
                        recent = recent.value,
                        usageGranted = usageGranted.value,
                        // The torch is reported live and the reader's connection as it stands, so
                        // neither waits for the next full read of the settings.
                        controls = reading?.copy(torch = torch, listener = shade.connected),
                        volumes = volumes.value,
                        widgets = host.placed.value,
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
        if (hasFocus) {
            hideStatusBarForConsole()
            // Coming back from Android's own shade or a settings page is exactly when a control may
            // have been changed somewhere else.
            refreshControls()
        }
    }

    override fun onStart() {
        super.onStart()
        widgetHost?.let { host ->
            host.start()
            // A widget whose app was removed, or one left half-added, is sorted out here.
            lifecycleScope.launch { host.reconcile() }
        }
        phoneControls.start()
        refreshControls()
        // Coming back home after using an app is exactly when the recent list has changed.
        loadRecents()
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

    override fun onStop() {
        phoneControls.stop()
        widgetHost?.stop()
        super.onStop()
    }

    /**
     * A widget's own configuration screen answering. Opened by `AppWidgetHost`, which starts it with
     * the legacy `startIntentSenderForResult` — there is no result contract for it — so its answer
     * arrives here. `super` runs first: it hands the result registry every request it recognises, and
     * the registry numbers its own from 0x10000 up, so [HomeWidgetHost.REQUEST_CONFIGURE] is never one.
     */
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != HomeWidgetHost.REQUEST_CONFIGURE) return
        val host = widgetHost ?: return
        lifecycleScope.launch { afterWidgetStep(host.onConfigureResult(resultCode == RESULT_OK)) }
    }

    override fun onDestroy() {
        widgetHost?.clear()
        unwatch?.invoke()
        unwatch = null
        super.onDestroy()
    }

    private fun reload() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { apps.load() }
            appList.value = list
            pins.value = store.pins()
            loadRecents()
        }
    }

    /**
     * The apps used most recently, from Android's usage record, as home-screen apps.
     *
     * ⚠️ Only apps in THIS profile: the usage record names a package, not a profile, and a work app's
     * package matches its personal twin — opening the wrong one is worse than leaving it out.
     */
    private fun loadRecents() {
        val all = appList.value ?: return
        lifecycleScope.launch {
            val (granted, list) = withContext(Dispatchers.IO) {
                if (!UsageAccess.isGranted(applicationContext)) return@withContext false to emptyList<HomeApp>()
                val byPkg = all.filter { !it.work }.groupBy { it.component.packageName }
                val now = System.currentTimeMillis()
                val order = RecentApps.order(
                    uses = apps.recentUses(now, RECENT_WINDOW_MS),
                    launchable = byPkg.keys,
                    exclude = apps.homePackages(),
                    limit = RECENT_LIMIT,
                )
                true to order.mapNotNull { byPkg[it]?.firstOrNull() }
            }
            usageGranted.value = granted
            recent.value = list
        }
    }

    /** Carry out what adding a widget asked for next. */
    private fun afterWidgetStep(step: HomeWidgetHost.Step) {
        when (step) {
            HomeWidgetHost.Step.Done -> Unit
            is HomeWidgetHost.Step.Refused -> notice.value = step.why
            is HomeWidgetHost.Step.Ask -> runCatching { bindWidget.launch(step.intent) }.onFailure {
                val host = widgetHost ?: return
                lifecycleScope.launch { afterWidgetStep(host.abandonPending("Android would not ask to place that widget.")) }
            }
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

    private fun grantNotices() {
        if (!NotificationAccess.openSettings(this)) {
            notice.value = "Android would not open the notification-access page."
        }
    }

    /** Read every control again, [afterMs] from now — a radio takes a moment to report its new state. */
    private fun refreshControls(afterMs: Long = 0L) {
        lifecycleScope.launch {
            if (afterMs > 0) delay(afterMs)
            val connected = ShadeStore.view.value.connected
            controlReading.value = withContext(Dispatchers.IO) { phoneControls.read(connected) }
            volumes.value = withContext(Dispatchers.IO) { phoneControls.volumes() }
        }
    }

    private fun tapControl(control: ControlsState.Control) {
        val r = controlReading.value ?: return
        lifecycleScope.launch {
            val now = r.copy(torch = phoneControls.torch.value, listener = ShadeStore.view.value.connected)
            when (val out = withContext(Dispatchers.IO) { phoneControls.act(control, now) }) {
                PhoneControls.Outcome.Done -> {
                    notice.value = null
                    refreshControls()
                    refreshControls(afterMs = RADIO_SETTLE_MS)
                }
                is PhoneControls.Outcome.Panel -> {
                    out.note?.let { notice.value = it }
                    start(out.intent)
                }
                PhoneControls.Outcome.AskBluetooth -> askBluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT)
                PhoneControls.Outcome.AskNotices -> grantNotices()
            }
        }
    }

    private fun adjustVolume(stream: PhoneControls.Stream, up: Boolean) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { phoneControls.adjust(stream, up) }
            if (!ok) notice.value = "Android would not change the ${stream.label.lowercase()} volume."
            volumes.value = withContext(Dispatchers.IO) { phoneControls.volumes() }
        }
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

/** How long a radio (Wi-Fi, Bluetooth) takes to report the state it was just switched to. */
private const val RADIO_SETTLE_MS = 1_200L

/** How far back the recent-apps strip looks, and how many it asks for before pinned apps are dropped. */
private const val RECENT_WINDOW_MS = 48L * 60 * 60 * 1000
private const val RECENT_LIMIT = 8
