package dev.mascwa.pulse.feature.analytics

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mascwa.pulse.feature.analytics.AnalyticsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(viewModel: AnalyticsViewModel = viewModel()) {
    val usageData by viewModel.usageData.collectAsState()
    val recommendations by viewModel.featureRecommendations.collectAsState()
    val diagnosticsStatus by viewModel.diagnosticsStatus.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.checkDiagnostics()
        viewModel.analyzeUserEngagement()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Analytics Dashboard") }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("User Engagement Analysis")
            Spacer(modifier = Modifier.height(16.dp))
            usageData?.let {
                Text("Engagement Data: $it")
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text("Feature Recommendations:")
            recommendations.forEach { recommendation ->
                Text("- $recommendation")
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text("Diagnostics Status: $diagnosticsStatus")
            Button(onClick = { viewModel.requestFeedback() }) {
                Text("Provide Feedback")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun AnalyticsScreenPreview() {
    AnalyticsScreen()
}