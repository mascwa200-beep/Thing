package dev.mascwa.pulse.feature.jarvis

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mascwa.pulse.data.jarvis.UserPreferencesStore
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserPreferencesScreen(userPreferencesStore: UserPreferencesStore) {
    val scope = rememberCoroutineScope()
    var userInterests by remember { mutableStateOf("") }
    var ongoingProjects by remember { mutableStateOf("") }
    
    LaunchedEffect(Unit) {
        userInterests = userPreferencesStore.getUserInterests()
        ongoingProjects = userPreferencesStore.getOngoingProjects()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text("User Preferences", style = MaterialTheme.typography.headlineMedium)

        Spacer(modifier = Modifier.height(16.dp))

        TextField(
            value = userInterests,
            onValueChange = { userInterests = it },
            label = { Text("Interests") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        TextField(
            value = ongoingProjects,
            onValueChange = { ongoingProjects = it },
            label = { Text("Ongoing Projects") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = {
            scope.launch {
                userPreferencesStore.saveUserInterests(userInterests)
                userPreferencesStore.saveOngoingProjects(ongoingProjects)
            }
        }) {
            Text("Save Preferences")
        }
    }
}