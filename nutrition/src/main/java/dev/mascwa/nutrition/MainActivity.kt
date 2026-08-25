package dev.mascwa.nutrition

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.mascwa.nutrition.ui.NutritionTheme
import java.text.NumberFormat
import java.util.Locale

/**
 * The whole application, for now.
 *
 * ⚠️ **This is scaffolding and it says so, but it is not a stub.** The six screens — macros, intake,
 * body, coach, recipes, habits — are the expensive half of this work and land next. What is here is
 * the thing that has to be true before any of them are worth writing: that the bundled barcode
 * database reached the phone and opened, on a device this application was never built for. A
 * placeholder saying "coming soon" would have told nobody anything; this answers the one question
 * the packaging poses.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NutritionTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    FirstRunScreen()
                }
            }
        }
    }
}

// ⚠️ `TopAppBar` is still experimental in Material 3 1.3.1, and an experimental API used without
// this is a compile ERROR rather than a warning — found by the local type check against the real
// artifact, which is the only reason it did not cost a CI round.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FirstRunScreen() {
    val context = LocalContext.current
    var report by remember { mutableStateOf<BundleReport?>(null) }
    val device = remember { DeviceFacts.read() }

    // ⚠️ Opening the database unpacks a third of a gigabyte out of the assets on a first run, so it
    // is genuinely slow and genuinely must not be on the main thread. `BundleReport.read` suspends;
    // Room's own queries move themselves to its executor.
    LaunchedEffect(Unit) { report = BundleReport.read(context) }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Text("Nutrition", fontWeight = FontWeight.SemiBold)
            })
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Text(
                "A food and body log, and nothing else. The screens are on their way; this page " +
                    "exists to show that what they will read actually arrived.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val r = report
            if (r == null) {
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.height(20.dp))
                        Text(
                            "Opening the food database. On a first run this unpacks it, which " +
                                "takes a moment.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else {
                FoodDatabaseCard(r)
            }

            DeviceCard(device)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun FoodDatabaseCard(r: BundleReport) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (r.present) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                if (r.present) "Food database" else "No food database",
                style = MaterialTheme.typography.titleMedium,
            )
            if (r.present) {
                Fact("Products", count(r.products))
                Fact("With nutrition", count(r.withNutrition))
                // ⚠️ Both halves, because they answer different questions: how many figures the
                // side table actually holds, and how many kinds of figure this build understands.
                // A zero against a healthy nutrient count means the extraction did not run.
                Fact("Further nutrients", "${count(r.extraFigures)} figures, ${r.extraNutrients} kinds")
                r.builtAt?.let { Fact("Built", it) }
                r.attribution?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    r.failure ?: "Unknown.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun DeviceCard(d: DeviceFacts) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("This phone", style = MaterialTheme.typography.titleMedium)
            Fact("Model", d.model)
            Fact("Android", "${d.android} (API ${d.sdk})")
            Fact("Architecture", d.abis.joinToString(", ").ifBlank { "not reported" })
            Text(
                "There is no native code in this app, so none of the architectures above needs a " +
                    "build of its own.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/**
 * A count with thousands separators, or an honest dash.
 *
 * ⚠️ `Locale.getDefault()` and not `Locale.US`: this is a number being READ by a person, in their
 * own conventions, not one being written into a file another program has to parse. The rule this
 * project keeps relearning is the opposite one — a figure crossing a boundary is `Locale.US` — and
 * the two are genuinely different cases.
 */
private fun count(n: Int?): String =
    n?.let { NumberFormat.getIntegerInstance(Locale.getDefault()).format(it) } ?: "—"
