package dev.mascwa.pulse.feature.diary

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.mascwa.pulse.feature.common.LcarsIcons
import dev.mascwa.pulse.feature.common.PulseScaffold

/** Standalone Diary screen — one tap from MENU (the old console sub-tab home is gone). */
@Composable
fun DiaryScreen(vm: DiaryViewModel, onBack: (() -> Unit)? = null) {
    PulseScaffold(
        title = "DIARY",
        onBack = onBack,
    ) { innerPadding ->
        DiaryBody(vm, Modifier.padding(innerPadding))
    }
}
