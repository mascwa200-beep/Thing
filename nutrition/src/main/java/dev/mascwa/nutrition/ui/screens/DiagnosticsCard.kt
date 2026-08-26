package dev.mascwa.nutrition.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.nutrition.data.NutritionContainer
import dev.mascwa.nutrition.ui.SectionCard
import dev.mascwa.pulse.crash.CrashUploader
import dev.mascwa.pulse.crash.FaultKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What has gone wrong on this phone, and whether it has been sent on.
 *
 * ⚠️ **The half of "why is it not working" that a crash console alone never covers is the handled
 * failure.** A bundled database that would not open, a lookup that threw and was caught, a
 * permission refused — none of those crash anything and every one of them makes the app feel broken
 * with nothing on any screen to say so. Both kinds are listed here, tagged apart, because a handled
 * failure shown as a crash would be a claim about the app that is not true.
 *
 * ⚠️ **The switch is visible because this is the one thing in the app that leaves the device.**
 * Everything else — the food log, the weigh-ins, the photographs — stays in this app's sandbox and
 * goes nowhere. A report carries the fault, the recent breadcrumbs and this process's own logcat,
 * with every credential scrubbed out by exact value on the way. Default on, since a report nobody
 * sends is a report nobody reads, but stated rather than assumed.
 */
@Composable
fun DiagnosticsCard(container: NutritionContainer) {
    val scope = rememberCoroutineScope()
    val autoSend by container.settings.autoSendReports.collectAsStateWithLifecycle(initialValue = true)

    // Read once when the card first appears, and again after anything that changes the list.
    var entries by remember { mutableStateOf<List<Pair<String, FaultKind>>?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    suspend fun reload() {
        // ⚠️ On IO, not the caller's dispatcher. `entries()` lists a directory and opens each file to
        // read one line out of it — cheap, and still file work, which does not belong on the thread
        // drawing a scrolling page.
        entries = withContext(Dispatchers.IO) {
            container.crashReporter.entries().take(MAX_SHOWN).map { entry ->
                "${STAMP.format(Date(entry.timeMs))}  ${entry.summary}" to entry.kind
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    SectionCard(
        "Diagnostics",
        subtitle = "Faults recorded on this phone. Sending one includes the fault, what the app was " +
            "doing just before it, and this app's own log — never your food or body data.",
    ) {
        val list = entries
        when {
            list == null -> Text(
                "Reading…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            list.isEmpty() -> Text(
                "Nothing has gone wrong on this phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                list.forEach { (line, kind) ->
                    Text(
                        (if (kind == FaultKind.FATAL) "CRASH · " else "HANDLED · ") + line,
                        style = MaterialTheme.typography.bodySmall,
                        // ⚠️ A handled failure is not an error state — it is information. Painting it
                        // in the error colour beside a real crash would make an app that recovered
                        // correctly look as though it had fallen over.
                        color = if (kind == FaultKind.FATAL) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Send reports automatically", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = autoSend,
                onCheckedChange = { value -> scope.launch { container.settings.setAutoSendReports(value) } },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        // ⚠️ `finally`, so a button that fails is a button that can be pressed again.
                        // `sendNow` is documented never to throw, but the guard costs nothing and the
                        // alternative is a control permanently stuck reading "Sending…".
                        try {
                            status = describe(container.crashUploader.sendNow())
                            reload()
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "Sending…" else "Send a report now") }

            if (list?.isNotEmpty() == true) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            container.crashReporter.clear()
                            status = "Cleared."
                            reload()
                        }
                    },
                ) { Text("Clear") }
            }
        }

        status?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * ⚠️ **Every outcome says which one it was.** "No token", "auto-send is off" and "nothing new" are
 * three different facts, and one message for all of them is how a reader learns to ignore the line
 * entirely — which is the failure this whole card exists to avoid.
 */
private fun describe(result: CrashUploader.Result): String = when (result) {
    is CrashUploader.Result.Ok -> "Sent — ${result.path}"
    is CrashUploader.Result.Skipped -> "Not sent: ${result.reason}."
    is CrashUploader.Result.Failed -> "Could not send: ${result.reason}"
}

/** Local rather than absolute-with-a-zone: this is a line being read on the device that wrote it. */
private val STAMP = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())

private const val MAX_SHOWN = 6
