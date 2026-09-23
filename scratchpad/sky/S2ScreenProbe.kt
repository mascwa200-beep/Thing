package dev.mascwa.pulse.feature.sky

import kotlin.math.roundToInt
import kotlinx.coroutines.flow.StateFlow

/**
 * Typed probe for the S2 screen edits: the exact view-model members both screens now reach,
 * against the REAL types. Neither screen can be compiled here (each pulls in its application's own
 * kit), so this is the half of them that can be — and it is the half that was newly written.
 */
fun s2ScreenProbe(vm: SkyMapViewModel, atmosphere: Boolean, body: SkyMapViewModel.Body) {
    val flow: StateFlow<Boolean> = vm.atmosphere
    vm.setAtmosphere(!atmosphere)
    val drawn: Double = vm.apparentAltitudeDeg(body.altitudeDeg, atmosphere)
    val live: Double = vm.apparentAltitudeDeg(body.altitudeDeg)
    val label = "${drawn.roundToInt()}° up" + if (drawn < 0) " · below the horizon" else ""
    check(flow.value == atmosphere || live == drawn || label.isNotEmpty())
}
