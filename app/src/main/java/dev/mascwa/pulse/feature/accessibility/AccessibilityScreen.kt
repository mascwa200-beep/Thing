package dev.mascwa.pulse.feature.accessibility

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mascwa.pulse.navigation.NavController

@Composable
fun AccessibilityScreen(navController: NavController) {
    val viewModel: AccessibilityViewModel = viewModel()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Accessibility Features") }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "Enhanced Accessibility Features", style = MaterialTheme.typography.h6)

            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = { viewModel.collectUsageData() }) {
                Text("Access Usage Data")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = { viewModel.runDiagnostics() }) {
                Text("Run Self-Diagnostics")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = { viewModel.fetchFeatureRecommendations() }) {
                Text("Get Feature Recommendations")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = { viewModel.enableLearningCapabilities() }) {
                Text("Enable Learning Capabilities")
            }
        }
    }
}