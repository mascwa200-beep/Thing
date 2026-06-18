package dev.mascwa.pulse.feature.jarvis

import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mascwa.pulse.viewmodel.ContextualAwarenessViewModel
import kotlinx.coroutines.launch

@Composable
fun ContextualAwarenessScreen(viewModel: ContextualAwarenessViewModel) {
    val state = viewModel.state.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "User Preferences and Interests")

        state.value.preferences.forEach { preference ->
            Text(text = preference)
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = {
            coroutineScope.launch {
                viewModel.loadPreferences()
            }
        }) {
            Text(text = "Refresh Preferences")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = {
            coroutineScope.launch {
                viewModel.saveCurrentProject()
            }
        }) {
            Text(text = "Save Current Project")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(text = "Ongoing Projects:")
        state.value.ongoingProjects.forEach { project ->
            Text(text = project)
        }
    }
}