package dev.mascwa.pulse.feature.assist

import android.app.Activity
import android.os.Bundle
import dev.mascwa.pulse.navigation.Routes
import dev.mascwa.pulse.widget.openRouteIntent

/**
 * What the phone's assistant gesture opens once LCARS is chosen as the digital assistant: the
 * Computer console.
 *
 * Android offers any app with an activity for `android.intent.action.ASSIST` in its Default apps ▸
 * Digital assistant list; an app cannot pick itself, so Settings takes the owner there. Holding the
 * power button reaches the assistant only when Android's gesture settings say so — that is said there
 * too, rather than left for somebody to discover.
 *
 * A trampoline with no window: it hands the request to the console and is gone, so the assistant opens
 * where the rest of LCARS lives, with its back stack, rather than as a second copy.
 */
class AssistActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { startActivity(openRouteIntent(this, Routes.JARVIS)) }
        finish()
    }
}
