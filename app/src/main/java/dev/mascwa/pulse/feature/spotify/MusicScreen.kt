package dev.mascwa.pulse.feature.spotify

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.mascwa.pulse.feature.common.LcarsIcons
import dev.mascwa.pulse.feature.common.PulseScaffold

/** Standalone Music (Spotify) screen — one tap from MENU (the old console sub-tab home is gone). */
@Composable
fun MusicScreen(vm: SpotifyViewModel, onBack: (() -> Unit)? = null) {
    PulseScaffold(
        title = "MUSIC",
        onBack = onBack,
    ) { innerPadding ->
        SpotifyBody(vm, Modifier.padding(innerPadding))
    }
}
