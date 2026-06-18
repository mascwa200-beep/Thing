package dev.mascwa.pulse.feature.jarvis

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.mascwa.pulse.navigation.Destinations

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JarvisFeatureScreen(navController: NavController) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Jarvis Features") },
                actions = {
                    IconButton(onClick = { navController.navigate(Destinations.Settings) }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Text(text = "Welcome to the Jarvis Feature Screen!", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(20.dp))
            Button(onClick = { /* TODO: Implement functionality */ }) {
                Text("Start Task")
            }
            Spacer(modifier = Modifier.height(10.dp))
            Button(onClick = { /* TODO: Implement functionality */ }) {
                Text("View History")
            }
        }
    }
}