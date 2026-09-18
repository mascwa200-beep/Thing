package dev.mascwa.nutrition.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.mascwa.nutrition.data.NutritionContainer
import dev.mascwa.nutrition.ui.SectionCard
import dev.mascwa.nutrition.ui.StatRow
import dev.mascwa.pulse.core.telemetry.FoodPack
import kotlinx.coroutines.launch

/**
 * Getting the barcode database onto the phone.
 *
 * ⚠️ **This app no longer carries the corpus inside its APK**, which was 189,972,281 bytes of which
 * almost all was one 425 MB asset — and the in-app updater downloads the WHOLE APK on every
 * published build, so adding barcodes meant re-downloading every product each time a line of
 * interface code changed. It is fetched once instead.
 *
 * ⚠️ **The cost of that is a first run which needs a network, and this card is where that is said out
 * loud rather than discovered.** Without a database every scan answers "unknown barcode", which is
 * indistinguishable from a product genuinely not being in it — the exact failure the self-check on
 * this screen already exists to catch. So the card states which of the two it is, and offers the
 * download rather than describing a problem the person cannot act on.
 */
@Composable
fun FoodPackCard(container: NutritionContainer) {
    val scope = rememberCoroutineScope()
    val pack = remember { container.foodPack }

    var installed by remember { mutableStateOf(pack.installed()) }
    var manifest by remember { mutableStateOf<FoodPack.Manifest?>(null) }
    var plan by remember { mutableStateOf<FoodPack.Plan?>(null) }
    var checking by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf("") }
    var percent by remember { mutableIntStateOf(0) }
    var problem by remember { mutableStateOf<String?>(null) }

    // ⚠️ A lambda over a local `suspend fun`, because a local suspend function declared inside a
    // composable and capturing its state writes is the one shape the Compose compiler plugin has
    // historically been awkward about. This is an ordinary value; nothing about it is special.
    val check: suspend () -> Unit = {
        checking = true
        problem = null
        runCatching { pack.check() }
            .onSuccess { (m, p) -> manifest = m; plan = p }
            .onFailure {
                // ⚠️ "Could not check" is NOT "up to date", and collapsing the two is the mistake
                // this whole feature exists to stop at a larger scale. A private repository with no
                // token answers 404, and telling somebody their database is current when it could
                // not be looked at is worse than saying nothing.
                problem = "Could not check for the food database — ${it.message ?: "no answer"}. " +
                    "A private repository needs the same token the app updater uses."
            }
        checking = false
    }

    // ⚠️ Only when there is nothing, so an ordinary launch does not spend a network request on a
    // corpus that changes a handful of times a year. With a database present the check is a button.
    LaunchedEffect(Unit) { if (installed == null) check() }

    SectionCard("Food database") {
        // Captured once. `manifest` and `plan` are `var` state, so neither smart-casts inside the
        // click handler below — and reaching for `!!` there would be an assertion about a value
        // another coroutine owns rather than about this frame.
        val local = installed
        val current = plan
        val offered = manifest

        if (local != null) {
            StatRow("Products", FoodPack.products(local.rows))
            StatRow("Built", local.builtAt.ifBlank { "—" })
        } else if (!working) {
            Text(
                "The barcode database is downloaded once, separately from the app — it is far larger " +
                    "than the app itself. Until it is here, a scanned barcode has to be looked up " +
                    "over the network and will not be found without one.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when {
            working -> {
                Text(
                    // ⚠️ The stage as well as the bar. `HttpClient.download` never reports a
                    // percentage when the server states no length, and a bar frozen at nothing with
                    // no words beside it reads as a hang rather than as a transfer.
                    if (percent > 0) "$stage… $percent%" else "$stage…",
                    style = MaterialTheme.typography.bodyMedium,
                )
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            checking -> Text(
                "Checking for the food database…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            current != null && offered != null -> {
                Text(
                    FoodPack.describe(current, offered),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (current is FoodPack.Plan.Full || current is FoodPack.Plan.Deltas) {
                    Button(
                        onClick = {
                            scope.launch {
                                working = true
                                percent = 0
                                stage = "Starting"
                                problem = pack.install(
                                    offered, current,
                                    onProgress = { percent = it },
                                    onStage = { stage = it },
                                )
                                working = false
                                installed = pack.installed()
                                if (problem == null) check()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (local == null) {
                                "Download it — ${FoodPack.describeBytes(FoodPack.bytesToFetch(current))}"
                            } else {
                                "Update it — ${FoodPack.describeBytes(FoodPack.bytesToFetch(current))}"
                            },
                        )
                    }
                }
            }
        }

        problem?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        if (!working && !checking) {
            OutlinedButton(
                onClick = { scope.launch { check() } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Check for a newer database") }
        }
    }
}
