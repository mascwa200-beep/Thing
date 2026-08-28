package dev.mascwa.nutrition.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.nutrition.data.NutritionUpdates
import dev.mascwa.nutrition.ui.SectionCard
import dev.mascwa.nutrition.ui.StatRow
import kotlinx.coroutines.launch

/**
 * What version this is, and what is being done about it.
 *
 * ⚠️ **Nothing here has to be touched for the app to stay current.** The activity checks on every
 * foreground and installs when the app is put down; this card exists so the owner can see what
 * happened and force it.
 *
 * ⚠️ **The token is not "the one thing that cannot be automatic" — that claim used to be here and
 * is false on the phone this app was written for.** When the LCARS application installed this one it
 * also maintains it: it is a device owner, it already holds a token, and its background pass
 * reinstalls a newer build with no dialog and nothing asked for. So this card reads
 * [NutritionUpdates.maintainedByCompanion] first and says which of the two worlds it is in, rather
 * than demanding a credential that is already being supplied by something else. The field stays
 * either way — LCARS can be uninstalled, and this app's own updater is then the only route left.
 */
@Composable
fun UpdateCard(updates: NutritionUpdates) {
    val state by updates.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var token by remember { mutableStateOf("") }
    var hasToken by remember { mutableStateOf<Boolean?>(null) }
    var maintained by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    // Whether a token is stored, not what it is: the field starts empty and a paste replaces it.
    // Reading the token back into an editable field would put a credential on screen to no end.
    LaunchedEffect(saved) { hasToken = updates.hasToken() }

    // ⚠️ Keyed on Unit rather than on `saved`: which application installed this one is a fact about
    // this copy that cannot change while it is running — replacing the package restarts the process.
    // Re-asking the package manager whenever a token is typed would be a binder call for nothing.
    LaunchedEffect(Unit) { maintained = updates.maintainedByCompanion() }

    SectionCard("Updates") {
        StatRow("Installed", "${updates.installedVersion} (build ${updates.installedCode})")

        when (val s = state) {
            NutritionUpdates.State.Idle -> Note("Checks when the app opens.")
            NutritionUpdates.State.Checking -> Note("Asking GitHub…")
            is NutritionUpdates.State.Current -> Note("This is the newest build (${s.latest}).")
            is NutritionUpdates.State.Pending ->
                Note("Build ${s.latest} is published but still being built or did not pass. Nothing to install yet.")
            is NutritionUpdates.State.Available -> Note("Build ${s.info.versionName} is ready to download.")
            is NutritionUpdates.State.Downloading -> {
                Note("Downloading ${s.info.versionName} — ${s.percent}%")
                LinearProgressIndicator(
                    progress = { s.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is NutritionUpdates.State.Ready ->
                Note("Build ${s.info.versionName} is downloaded. It installs when you leave the app.")
            is NutritionUpdates.State.Failed -> Text(
                s.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = { scope.launch { updates.check() } }) { Text("Check now") }
            val ready = state as? NutritionUpdates.State.Available
            if (ready != null) {
                Button(onClick = { scope.launch { updates.download(ready.info) } }) { Text("Download") }
            }
            if (state is NutritionUpdates.State.Ready) {
                Button(onClick = { scope.launch { updates.install() } }) { Text("Install now") }
            }
        }

        if (maintained) {
            Note(
                "The LCARS app installed this one and keeps it up to date on its own — it checks " +
                    "on Wi-Fi and reinstalls a newer build with nothing to tap. You do not need a " +
                    "token for that. A token here is only worth setting if you want this app to be " +
                    "able to update itself should LCARS ever be removed, or to send fault reports, " +
                    "which need write access to contents.",
            )
        } else if (hasToken == false) {
            Note(
                "These builds live in a private repository, so updating needs a GitHub token that " +
                    "can read this repository's contents. The same token sends fault reports, and " +
                    "that half needs WRITE access to contents — a read-only token keeps updates " +
                    "working while every report is refused, which is a bad way to find out. Pick " +
                    "whichever half you want and the other one will say plainly when it cannot. " +
                    "The token is kept on this phone in plain preferences, because this app has no " +
                    "secure store of its own.",
            )
        } else if (hasToken == true) {
            Note("A token is saved. Pasting a new one replaces it; saving a blank field clears it.")
        }

        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("GitHub token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = {
            scope.launch {
                updates.saveToken(token)
                token = ""
                saved = !saved
                updates.check()
            }
        }) { Text("Save token") }
    }
}

@Composable
private fun Note(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)
