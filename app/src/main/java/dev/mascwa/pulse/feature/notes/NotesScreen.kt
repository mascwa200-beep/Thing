package dev.mascwa.pulse.feature.notes

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.mascwa.pulse.feature.common.LcarsIcons
import dev.mascwa.pulse.feature.common.PulseScaffold

/** Standalone Notes screen — one tap from MENU (the old console sub-tab home is gone). */
@Composable
fun NotesScreen(vm: NotesViewModel, onBack: (() -> Unit)? = null) {
    PulseScaffold(
        title = "NOTES",
        onBack = onBack,
    ) { innerPadding ->
        NotesBody(vm, Modifier.padding(innerPadding))
    }
}
