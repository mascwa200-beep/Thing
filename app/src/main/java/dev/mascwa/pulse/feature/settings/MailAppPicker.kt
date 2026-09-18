package dev.mascwa.pulse.feature.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.core.telemetry.MailGlance
import dev.mascwa.pulse.data.comms.MailNoticeStore
import dev.mascwa.pulse.data.comms.MailNotificationListener
import dev.mascwa.pulse.data.comms.NotificationAccess
import dev.mascwa.pulse.feature.common.LcarsDialog

/**
 * Linking email with no password: switch on notification access, then tick the apps that carry mail.
 *
 * ⚠️ **Three states, not two.** "Never switched on" and "switched on but the service is not running"
 * look identical from the outside and need entirely different things done about them — a settings
 * page in one case, a rebind in the other — and this repository has repeatedly found that rendering
 * an off state and a broken state the same way is how a feature comes to look like a bug.
 *
 * ⚠️ **Whether it is running is inferred from evidence, not from a flag.** The obvious
 * implementation is a `@Volatile` companion set in `onListenerConnected`, and it is wrong in the
 * commonest case: that flag is false on every freshly-started process, which is exactly when
 * somebody is most likely to be reading this screen. Whether the listener has ever read the shade
 * is a fact that survives the process, so that is what is asked.
 */
@Composable
internal fun MailNotificationRows(
    chosen: List<String>,
    onChosenChange: (List<String>) -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) {
        runCatching { (context.applicationContext as? PulseApplication)?.container?.mailNoticeStore }.getOrNull()
    }

    // ⚠️ Re-read when the screen comes back, not only when something here is tapped. Granting
    // access happens in a SYSTEM page, so the moment that matters is the return from it — and a
    // row that still said "switch it on" after the user had just switched it on would send them
    // straight back to the page they had finished with. `refresh` covers the taps; ON_START covers
    // the journey, which is the case this feature is actually about.
    var granted by remember { mutableStateOf(false) }
    var lastReadMs by remember { mutableStateOf(0L) }
    var seen by remember { mutableStateOf<List<MailNoticeStore.SeenApp>>(emptyList()) }
    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var refresh by remember { mutableStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { refresh++ }
    LaunchedEffect(refresh) {
        granted = NotificationAccess.isGranted(context)
        // ⚠️ Nothing is read from the store until access is granted, and that is not merely
        // pointless work avoided: the first read CREATES the DataStore file, and `AppContainer`
        // says in writing that a phone which never grants access never has this file on disk.
        // Opening Settings would otherwise make that claim false for everybody.
        if (!granted) return@LaunchedEffect
        lastReadMs = store?.lastRecomputedMs() ?: 0L
        seen = store?.seen().orEmpty()
        counts = store?.read()?.apps.orEmpty().associate { it.pkg to it.waiting }
    }

    PrefInfo(
        "Mail, without a password",
        subtitle = "Counts what your mail apps have already told you about — no server, no " +
            "sign-in, nothing sent anywhere. It reports what is on the notification shade, so it " +
            "says \"new\" rather than \"unread\": mail you have read elsewhere can still be showing, " +
            "and a notification you swiped away is gone even if the mail is not.",
    )

    when {
        !granted -> PrefClickable(
            "Switch on notification access",
            subtitle = "Opens the system list. There is no permission pop-up for this one — it can " +
                "only be granted from that page.",
            onClick = { context.openListenerSettings(); refresh++ },
        )

        lastReadMs <= 0L -> PrefClickable(
            "Switched on, but it has not read the shade yet",
            subtitle = "Usually a moment after switching it on. If it stays this way, tap to ask " +
                "the system to reconnect.",
            onClick = { context.requestRebind(); refresh++ },
        )

        else -> PrefInfo("Notification access", "Counting")
    }

    if (granted) {
        MailAppPickerRow(
            seen = seen,
            counts = counts,
            chosen = chosen,
            onChosenChange = onChosenChange,
            onOpened = { refresh++ },
        )
    }
}

/**
 * The picker.
 *
 * ⚠️ **Only apps that have actually notified are offered**, which is stricter than "every mail app
 * we can think of" and better for a reason worth stating: ticking an app that never notifies does
 * nothing at all, so offering it is offering a control that cannot work. [MailGlance.LIKELY_MAIL]
 * therefore decides ORDER — which of the apps in front of you is probably the one you want — and
 * never membership, exactly as its own KDoc says.
 *
 * ⚠️ Nothing is ticked for you. Neither signal the platform offers is good enough to decide it:
 * `CATEGORY_EMAIL` is optional metadata plenty of mail apps never set, and a guess that counted a
 * messaging app would double every text against a count that is already exact.
 */
@Composable
private fun MailAppPickerRow(
    seen: List<MailNoticeStore.SeenApp>,
    counts: Map<String, Int>,
    chosen: List<String>,
    onChosenChange: (List<String>) -> Unit,
    onOpened: () -> Unit,
) {
    val context = LocalContext.current
    var show by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }

    PrefClickable(
        "Which apps carry your mail",
        subtitle = when {
            chosen.isEmpty() -> "None chosen — nothing is counted"
            else -> chosen.joinToString(", ") { context.appLabel(it) }
        },
        onClick = { onOpened(); show = true },
    )

    if (!show) return
    val likely = seen.filter { it.everEmail || it.pkg in MailGlance.LIKELY_MAIL || it.pkg in chosen }
    val offered = (if (showAll) seen else likely)
        .sortedWith(
            // Ticked first so they can be found and unticked; then the ones that called themselves
            // mail; then the ones we recognise by name; then everything else, alphabetically by the
            // name a person would recognise rather than by package id.
            compareByDescending<MailNoticeStore.SeenApp> { it.pkg in chosen }
                .thenByDescending { it.everEmail }
                .thenByDescending { it.pkg in MailGlance.LIKELY_MAIL }
                .thenBy { context.appLabel(it.pkg).lowercase() },
        )

    LcarsDialog(
        title = "Which apps carry your mail",
        onDismiss = { show = false },
        content = {
            // ⚠️ A scrolling Column, never a LazyColumn. A dialog measures its content with an
            // intrinsic pass, and a lazy list is a SubcomposeLayout, which refuses intrinsic
            // measurement outright — it compiles perfectly and throws the moment the dialog opens.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (offered.isEmpty()) {
                    DialogBody(
                        "Nothing has notified yet. Come back once your mail app has shown you " +
                            "something — that is how it learns which apps to offer.",
                    )
                }
                offered.forEach { app ->
                    PrefSwitch(
                        context.appLabel(app.pkg),
                        // ⚠️ The count is the readback: it is what this app is contributing to the
                        // widget right now, so a wrong tick is visible here rather than only as a
                        // number that looks a bit high on the home screen.
                        subtitle = when {
                            counts[app.pkg] != null -> MailGlance.line(counts[app.pkg])
                                ?.let { "$it — from this app" } ?: app.pkg
                            app.everEmail -> "Says its notifications are email"
                            else -> app.pkg
                        },
                        checked = app.pkg in chosen,
                        onChange = { on ->
                            onChosenChange(if (on) chosen + app.pkg else chosen - app.pkg)
                        },
                    )
                }
                if (!showAll) {
                    PrefClickable(
                        "Show every app that notifies",
                        subtitle = "In case yours is not listed — an app is only offered once it " +
                            "has notified at least once.",
                        onClick = { showAll = true },
                    )
                }
            }
        },
        confirmText = "DONE",
        onConfirm = { show = false },
    )
}

/** The name a person would recognise, falling back to the package id when it cannot be resolved. */
private fun Context.appLabel(pkg: String): String = runCatching {
    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
}.getOrNull()?.takeIf { it.isNotBlank() } ?: pkg

/**
 * Open the system's notification-access page.
 *
 * ⚠️ Tries the per-app detail page first, which lands on the switch itself rather than on a list of
 * every app on the phone — but it is API 30+ and some builds do not implement it, so a failure falls
 * back to the list. Both are system pages; neither can be replaced by anything this app draws.
 */
private fun Context.openListenerSettings() {
    val component = ComponentName(this, MailNotificationListener::class.java)
    val detail = Intent(ACTION_LISTENER_DETAIL)
        .putExtra(EXTRA_LISTENER_COMPONENT, component.flattenToString())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { startActivity(detail) }.isSuccess) return
    runCatching {
        startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** Ask the system to bind the listener again after it has been killed. */
private fun Context.requestRebind() {
    runCatching {
        NotificationListenerService.requestRebind(ComponentName(this, MailNotificationListener::class.java))
    }
}

/**
 * ⚠️ Spelled out rather than taken from the constants, which are API 30+ while this app's floor is
 * lower. Referencing them directly would be a lint failure on the minimum, and both strings are
 * fixed by the platform's own settings intent contract.
 */
private const val ACTION_LISTENER_DETAIL = "android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS"
private const val EXTRA_LISTENER_COMPONENT = "android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME"
