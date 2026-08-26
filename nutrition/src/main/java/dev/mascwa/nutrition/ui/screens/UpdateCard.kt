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
 * happened, force it, and — the one thing that genuinely cannot be automatic — paste the token that
 * lets a private repository be read at all.
 */
@Composable
fun UpdateCard(updates: NutritionUpdates) {
    val state by updates.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var token by remember { mutableStateOf("") }
    var hasToken by remember { mutableStateOf<Boolean?>(null) }
    var saved by remember { mutableStateOf(false) }

    // Whether a token is stored, not what it is: the field starts empty and a paste replaces it.
    // Reading the token back into an editable field would put a credential on screen to no end.
    LaunchedEffect(saved) { hasToken = updates.hasToken() }

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

        if (hasToken == false) {
            Note(
                "These builds live in a private repository, so updating needs a GitHub token with " +
                    "repo scope. It is kept on this phone in plain preferences — this app has no " +
                    "secure store of its own — so use a token that can do nothing but read releases.",
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
