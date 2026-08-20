package dev.mascwa.pulse.feature.tacnet

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.mascwa.pulse.feature.common.LcarsIcons
import dev.mascwa.pulse.feature.common.PulseScaffold

/** Standalone Radio screen — one tap from MENU (the old console sub-tab home is gone). */
@Composable
fun RadioScreen(vm: RadioViewModel, onBack: (() -> Unit)? = null) {
    PulseScaffold(
        title = "RADIO",
        onBack = onBack,
    ) { innerPadding ->
        RadioBody(vm, Modifier.padding(innerPadding))
    }
}
