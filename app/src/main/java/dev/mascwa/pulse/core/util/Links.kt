package dev.mascwa.pulse.core.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** Shares plain text (a link, coordinates, etc.) via the system share sheet. */
fun shareText(context: Context, text: String, title: String = "Share") {
    if (text.isBlank()) return
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, text)
    val chooser = Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(chooser) }
}

/** Opens this app's system notification settings (to enable a denied permission). */
fun openAppNotificationSettings(context: Context) {
    val intent = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        // Fall back to the app details page.
        runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

/** Opens a maps app at the given coordinates. */
fun openMaps(context: Context, lat: Double, lon: Double, label: String? = null) {
    val q = if (label != null) "$lat,$lon(${Uri.encode(label)})" else "$lat,$lon"
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$q"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        openUrl(context, "https://maps.google.com/?q=$lat,$lon")
    }
}

/** Opens the dialer pre-filled with a number (no CALL permission needed). */
fun dialNumber(context: Context, number: String) {
    if (number.isBlank()) return
    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/** Opens a URL in the user's browser (or any handler). Safe no-op on failure. */
fun openUrl(context: Context, url: String) {
    if (url.isBlank()) return
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
    }
}

// ---- Device actions: each OPENS the relevant app pre-filled (the user confirms the final
// send/call/save). No dangerous permissions, no autonomous sending. Returns true if an app handled it.

private fun launch(context: Context, intent: Intent): Boolean =
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)

/** Opens the SMS composer pre-filled with [number] and [body] (the user taps send). */
fun composeSms(context: Context, number: String, body: String): Boolean {
    val uri = Uri.parse("smsto:" + Uri.encode(number.trim()))
    return launch(context, Intent(Intent.ACTION_SENDTO, uri).putExtra("sms_body", body))
}

/** Opens the email composer pre-filled (the user taps send). */
fun composeEmail(context: Context, to: String, subject: String, body: String): Boolean {
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
        if (to.isNotBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(to.trim()))
        if (subject.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, subject)
        if (body.isNotBlank()) putExtra(Intent.EXTRA_TEXT, body)
    }
    return launch(context, intent)
}

/** Opens the calendar's new-event editor pre-filled. [beginMillis] <= 0 leaves the time blank. */
fun createCalendarEvent(context: Context, title: String, details: String, beginMillis: Long): Boolean {
    val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI).apply {
        putExtra(CalendarContract.Events.TITLE, title)
        if (details.isNotBlank()) putExtra(CalendarContract.Events.DESCRIPTION, details)
        if (beginMillis > 0) putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginMillis)
    }
    return launch(context, intent)
}

/** Sets an alarm at [hour]:[minute] (the clock app shows it). */
fun setAlarm(context: Context, hour: Int, minute: Int, message: String): Boolean {
    val intent = Intent(AlarmClock.ACTION_SET_ALARM)
        .putExtra(AlarmClock.EXTRA_HOUR, hour)
        .putExtra(AlarmClock.EXTRA_MINUTES, minute)
    if (message.isNotBlank()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, message)
    return launch(context, intent)
}

/** Starts a countdown timer of [seconds]. */
fun setTimer(context: Context, seconds: Int, message: String): Boolean {
    val intent = Intent(AlarmClock.ACTION_SET_TIMER)
        .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
        .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
    if (message.isNotBlank()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, message)
    return launch(context, intent)
}

/** Opens the camera app to take a photo. */
fun openCamera(context: Context): Boolean =
    launch(context, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))

/**
 * Where the camera writes a photograph this app asked for.
 *
 * One definition, because two things now depend on it: [createCameraImageUri] names a file inside it
 * and [pruneCameraCaptures] empties it. A second spelling of the same path is how a sweep quietly
 * stops sweeping the directory that is actually filling up.
 */
fun cameraCaptureDir(context: Context): File = File(context.cacheDir, "camera")

/** A FileProvider content URI the camera can write a captured photo into (cacheDir/camera/). Pass it to
 *  the `TakePicture` contract, then read it back as the captured image. Null if the file can't be made. */
fun createCameraImageUri(context: Context): Uri? = runCatching {
    val dir = cameraCaptureDir(context).apply { mkdirs() }
    val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}.getOrNull()

/**
 * Give back the disk that photographs already read borrowed.
 *
 * ⚠️ **Nothing deleted these, ever.** Both callers of [createCameraImageUri] — the meal photograph on
 * the health tab and the console's Take photo — hand the file to the camera, read it once, and
 * abandon it. A full-resolution JPEG is several megabytes, so somebody who photographs their meals
 * accumulates that indefinitely, on a phone whose whole design brief here is that it might be a cheap
 * one. `ProgressPhotoStore` says in writing that the cache is "correct for a photo that is read once
 * and discarded" — right about the intent, and the discarding was never written.
 *
 * ⚠️ A **cancelled** capture is the case a per-call-site delete could never cover: some camera apps
 * create the file and then abandon it, so it belongs to no code path at all. Only a sweep collects it.
 *
 * ⚠️ [STALE_CAPTURE_MS] is an hour, and it has to be well clear of one particular race rather than
 * merely small. `Application.onCreate` runs BEFORE an Activity is recreated and before a pending
 * `TakePicture` result is delivered, so if our process was killed while the camera app was in the
 * foreground this sweep runs first. The file's timestamp is when the camera wrote it, which is at
 * most minutes before the user comes back — an hour is enormous headroom, and the alternative
 * (deleting a photograph a fraction of a second before it is read) is silent and unreproducible.
 *
 * Shaped after `UpdateRepository.pruneCache`, deliberately: same problem, same launch-time answer.
 */
fun pruneCameraCaptures(context: Context, nowMs: Long = System.currentTimeMillis()) {
    runCatching {
        cameraCaptureDir(context).listFiles()?.forEach { f ->
            if (f.isFile && nowMs - f.lastModified() > STALE_CAPTURE_MS) runCatching { f.delete() }
        }
    }
}

/** How long a captured photograph may sit before it is assumed read. See [pruneCameraCaptures]. */
const val STALE_CAPTURE_MS = 60L * 60 * 1000

/** Opens the contacts app. */
fun openContacts(context: Context): Boolean =
    launch(context, Intent(Intent.ACTION_VIEW).setData(ContactsContract.Contacts.CONTENT_URI))

/** Opens Spotify (app or web) on a search for [query]. */
fun openSpotifySearch(context: Context, query: String): Boolean =
    launch(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/search/" + Uri.encode(query.trim()))))

/** Opens a maps app searching for [query] (place name / address). */
fun searchMaps(context: Context, query: String): Boolean =
    launch(context, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query.trim()))))

/** Whether this app may install APKs (the user has granted "install unknown apps"). */
fun canInstallApks(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

/** Send the user to grant "install unknown apps" for this app. */
fun requestInstallPermission(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * Install [file] (an APK). Returns false (and routes to the unknown-sources screen) when the install
 * cannot start for want of permission.
 *
 * ⚠️ This no longer hands the file to the system installer. It goes through
 * [dev.mascwa.pulse.data.update.ApkInstaller], which completes with **no confirmation at all** where
 * the platform permits it — this app is a device owner on the owner's phone — and falls back to
 * showing the old dialog where it does not. Both the automatic and the manual UPDATE control come
 * through here on purpose, so the two can never drift into behaving differently.
 *
 * ⚠️ [expectPackage] names the package the APK declares when it is NOT this app's own — the
 * companion nutrition app is installed through this same path. The platform checks it against the
 * APK and refuses a mismatch, so it is stated when the caller knows it rather than guessed.
 *
 * The permission is checked *after* an attempt rather than before it: a device owner does not need
 * `REQUEST_INSTALL_PACKAGES` to install through a session, and asking first would send such a device
 * to a settings screen it has no reason to visit.
 */
fun installApk(context: Context, file: File, expectPackage: String? = null): Boolean {
    if (dev.mascwa.pulse.data.update.ApkInstaller.install(context, file, expectPackage)) return true
    if (!canInstallApks(context)) requestInstallPermission(context)
    return false
}

/**
 * Opens this app's App-info page. For a sideloaded build this is also where the user taps the ⋮ menu →
 * "Allow restricted settings" (Android 13+) and where GrapheneOS's per-app Network / Sensors / Storage
 * Scopes controls live.
 */
fun openAppInfo(context: Context): Boolean = launch(
    context,
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
)

/** Opens the system Accessibility settings (font/display size, TalkBack, colour correction, services). */
fun openAccessibilitySettings(context: Context): Boolean =
    launch(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

/** Opens "Usage access" so the user can grant it (used by the on-device security audit). */
fun openUsageAccessSettings(context: Context): Boolean =
    launch(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))

/** Opens the system Security settings (where GrapheneOS's USB-C port control + auto-reboot live). Falls
 *  back to the main Settings screen if a device doesn't expose the security panel directly. */
fun openSecuritySettings(context: Context): Boolean =
    launch(context, Intent(Settings.ACTION_SECURITY_SETTINGS)) ||
        launch(context, Intent(Settings.ACTION_SETTINGS))

/** Opens a system settings panel by [which] (wifi/bluetooth/location/display/sound/battery/data/nfc/apps),
 *  or the main Settings screen when blank/unknown. */
fun openSettingsPanel(context: Context, which: String): Boolean {
    val action = when (which.trim().lowercase()) {
        "wifi", "wi-fi" -> Settings.ACTION_WIFI_SETTINGS
        "bluetooth", "bt" -> Settings.ACTION_BLUETOOTH_SETTINGS
        "location", "gps" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
        "display", "screen" -> Settings.ACTION_DISPLAY_SETTINGS
        "sound", "volume" -> Settings.ACTION_SOUND_SETTINGS
        "battery", "power" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
        "data", "network", "mobile" -> Settings.ACTION_WIRELESS_SETTINGS
        "nfc" -> Settings.ACTION_NFC_SETTINGS
        "apps", "applications" -> Settings.ACTION_APPLICATION_SETTINGS
        "airplane", "flight" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
        else -> Settings.ACTION_SETTINGS
    }
    return launch(context, Intent(action))
}
