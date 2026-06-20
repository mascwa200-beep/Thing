package dev.mascwa.pulse.feature.spotify

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.mascwa.pulse.MainActivity
import dev.mascwa.pulse.PulseApplication
import kotlinx.coroutines.launch

/**
 * Catches the Spotify OAuth redirect (`dev.mascwa.pulse://spotify-callback?code=…`), exchanges the
 * authorization code for tokens via [dev.mascwa.pulse.data.spotify.SpotifyRepository.completeAuth], then
 * returns to the app. Translucent + singleTask (manifest) so it just flickers through. Defensive — a
 * denied grant or a failed exchange simply returns to the app unlinked.
 */
class SpotifyAuthActivity : ComponentActivity() {

    private val container get() = (application as PulseApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val code = intent?.data?.getQueryParameter("code")
        if (code.isNullOrBlank()) { returnToApp(); return }
        // Keep the activity alive until the exchange completes (lifecycleScope is cancelled on finish).
        lifecycleScope.launch {
            runCatching { container.spotifyRepository.completeAuth(code) }
            returnToApp()
        }
    }

    private fun returnToApp() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
            )
        }
        finish()
    }
}
