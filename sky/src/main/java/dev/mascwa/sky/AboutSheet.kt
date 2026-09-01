package dev.mascwa.sky

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.crash.CrashUploader
import dev.mascwa.pulse.data.update.SelfUpdate
import kotlinx.coroutines.launch

/**
 * What build this is, what is being done about it, and the one credential it needs.
 *
 * ⚠️ **Nothing here has to be touched for the app to stay current.** [MainActivity] checks on every
 * foreground over Wi-Fi and installs when the app is put down; this exists so the owner can see what
 * happened, force it, and paste the token that makes it possible at all.
 *
 * ⚠️ **A dialog rather than a screen, because this app has one screen and that is the point.** A
 * second destination would want a navigation graph, a back stack and a top bar — sixty-four
 * density-independent pixels of chrome on a map that deliberately has none — for a surface opened
 * about twice in the life of an install.
 *
 * ⚠️ **The content scrolls inside a bounded height, and it is NOT a `LazyColumn`.** Material 3's
 * `AlertDialog` grows to fit its text and would happily take its own buttons off the bottom of the
 * screen; and a lazy list inside a dialog is the trap this repository already recorded — a
 * `SubcomposeLayout` refuses intrinsic measurement outright. A dozen rows is a plain `Column`.
 */
@Composable
fun AboutSheet(
    updates: SelfUpdate,
    settings: SkySettings,
    uploader: CrashUploader,
    onDismiss: () -> Unit,
) {
    val state by updates.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var token by remember { mutableStateOf("") }
    var hasToken by remember { mutableStateOf<Boolean?>(null) }
    var saved by remember { mutableStateOf(false) }
    var autoSend by remember { mutableStateOf(true) }
    var reportNote by remember { mutableStateOf<String?>(null) }

    // ⚠️ Whether a token is STORED, never what it is. The field starts empty and a paste replaces
    // it; reading a credential back into an editable box would put it on screen to no purpose.
    LaunchedEffect(saved) { hasToken = updates.hasToken() }
    LaunchedEffect(Unit) { autoSend = settings.autoSendReports() }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("CLOSE") } },
        title = { Text("Star Map") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "Build ${updates.installedVersion} (#${updates.installedCode})",
                    style = MaterialTheme.typography.bodyMedium,
                )

                Text(
                    describe(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                (state as? SelfUpdate.State.Downloading)?.let { s ->
                    LinearProgressIndicator(
                        progress = { s.percent / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }

                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = { scope.launch { updates.check() } }) { Text("CHECK NOW") }
                    val available = state as? SelfUpdate.State.Available
                    if (available != null) {
                        TextButton(onClick = { scope.launch { updates.download(available.info) } }) {
                            Text("DOWNLOAD")
                        }
                    }
                    if (state is SelfUpdate.State.Ready) {
                        TextButton(onClick = { scope.launch { updates.install() } }) {
                            Text("INSTALL NOW")
                        }
                    }
                }

                // ------------------------------------------------------------------- the token

                Text(
                    when (hasToken) {
                        true -> "A GitHub token is stored."
                        false -> "No token yet, so update checks will be refused."
                        null -> "Checking whether a token is stored…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it; saved = false },
                    label = { Text("GitHub token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                Text(
                    // ⚠️ Both halves said, because the careful reader is the one this catches. A
                    // classic `repo` token carries write and works by accident; a fine-grained token
                    // with Contents:Read updates the app and then 403s every fault report, and the
                    // failure reads as a broken token rather than as a missing scope.
                    "It needs to READ this repository's contents for updates, and to WRITE them for " +
                        "fault reports. Stored in this app's plain preferences — there is no " +
                        "Keystore machinery here, and pretending otherwise would be theatre.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                TextButton(
                    onClick = {
                        scope.launch {
                            updates.saveToken(token)
                            token = ""
                            saved = true
                            updates.check()
                        }
                    },
                ) { Text(if (token.isBlank()) "CLEAR TOKEN" else "SAVE AND CHECK") }

                // -------------------------------------------------------------- fault reporting

                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Send fault reports", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "The only thing this app sends anywhere. Reports carry the build, the " +
                                "device and the fault — never the token, which is redacted by value.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = autoSend,
                        onCheckedChange = { on ->
                            autoSend = on
                            scope.launch { settings.setAutoSendReports(on) }
                        },
                    )
                }
                TextButton(
                    onClick = {
                        scope.launch {
                            reportNote = "Sending…"
                            reportNote = explain(uploader.sendNow("asked for from ABOUT"))
                        }
                    },
                ) { Text("SEND ONE NOW") }
                reportNote?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text(
                    "The map itself needs no network. Everything it draws — three million stars, " +
                        "the constellations, the deep sky, the Milky Way and every planet — is " +
                        "bundled or computed on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
    )
}

/**
 * ⚠️ Every state gets a sentence, including the ones that are not failures. "Nothing happened" and
 * "a newer build is halfway through CI" look identical from outside unless each says which it is.
 *
 * ⚠️ **A function taking the state as a parameter, rather than a `when` written inline above.** The
 * caller holds it as `by collectAsStateWithLifecycle()`, which is a DELEGATED property — and a
 * delegated property never smart-casts, so `is State.Current -> state.latest` would not compile
 * there however it is guarded. A parameter is an ordinary local and narrows normally. This
 * repository has paid for that distinction before, in a coordinate readout.
 */
private fun describe(state: SelfUpdate.State): String = when (state) {
    SelfUpdate.State.Idle -> "Checks by itself when the app opens on Wi-Fi."
    SelfUpdate.State.Checking -> "Asking GitHub…"
    is SelfUpdate.State.Current -> "This is the newest build (${state.latest})."
    is SelfUpdate.State.Pending ->
        "Build ${state.latest} exists but is not finished building yet."
    is SelfUpdate.State.Available -> "Build ${state.info.versionName} is ready to download."
    is SelfUpdate.State.Downloading -> "Downloading ${state.info.versionName} — ${state.percent}%."
    is SelfUpdate.State.Ready ->
        "Build ${state.info.versionName} will install when this app is next put down."
    is SelfUpdate.State.Failed -> state.reason
}

/** The uploader already decides what happened; this only turns it into a line. */
private fun explain(result: CrashUploader.Result): String = when (result) {
    is CrashUploader.Result.Ok -> "Sent as ${result.path}."
    is CrashUploader.Result.Skipped -> result.reason
    is CrashUploader.Result.Failed -> result.reason
}
