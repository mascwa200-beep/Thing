package dev.mascwa.pulse.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * The lifecycle and saved-state owners a `ComposeView` needs, for a SERVICE that has neither.
 *
 * A screensaver and a keyboard are services, not activities, so neither is a `LifecycleOwner` or a
 * `SavedStateRegistryOwner` — and a `ComposeView` without both on its view tree throws the moment it
 * attaches. The service drives this through [create], [start], [stop] and [destroy] from its own
 * callbacks.
 *
 * ⚠️ **Being STARTED is not decoration.** Compose's window recomposer pauses its frame clock while the
 * view tree's lifecycle is below STARTED, so a view that is on screen while its owner sits at CREATED
 * composes once and then never redraws. Every service must [start] this when it becomes visible.
 *
 * Each transition is idempotent: a keyboard is told "the input view started" again whenever the app
 * restarts its input, without being told it finished in between, and walking a lifecycle backwards
 * and forwards on every one of those would be pointless churn.
 */
internal class ServiceComposeOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    fun create() {
        if (registry.currentState != Lifecycle.State.INITIALIZED) return
        saved.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun start() {
        if (!registry.currentState.isAtLeast(Lifecycle.State.CREATED)) return
        if (registry.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun stop() {
        if (!registry.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    fun destroy() {
        stop()
        if (registry.currentState == Lifecycle.State.CREATED) registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}
