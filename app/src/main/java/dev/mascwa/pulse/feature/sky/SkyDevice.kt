package dev.mascwa.pulse.feature.sky

import dev.mascwa.pulse.data.sensors.CompassController
import dev.mascwa.pulse.data.weather.LocationProvider
import dev.mascwa.pulse.sky.SkyAttitude
import dev.mascwa.pulse.sky.SkyDeps
import dev.mascwa.pulse.sky.SkySite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What the LCARS application hands the star map: this phone's own sense of place and aim.
 *
 * ⚠️ **An adapter rather than a move, and [dev.mascwa.pulse.sky.SkyDeps] says why**: the two
 * services behind it have twenty-four consumers between them across this application, so the star
 * map is one caller among many and has no business owning either. The standalone sky app writes its
 * own adapter over the same interface, so both draw from one view model rather than two that can
 * drift.
 */
class SkyDevice(
    private val locationProvider: LocationProvider,
    private val compass: CompassController,
) : SkyDeps {

    /**
     * ⚠️ **Its own, rather than one handed in.** The collector's life is already bounded by
     * [stopAttitude], which the view model calls from `onCleared`, so there is nothing an outer
     * scope would add — and a `viewModelScope` is not reachable from the factory that builds this.
     * `Main.immediate` because every value published here is read by a composable.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _attitude = MutableStateFlow<SkyAttitude?>(null)
    override val attitude: StateFlow<SkyAttitude?> = _attitude.asStateFlow()

    override suspend fun site(): SkySite? {
        if (!locationProvider.hasPermission()) return null
        return runCatching { locationProvider.current() }.getOrNull()
            ?.let { SkySite(it.latitude, it.longitude) }
    }

    /**
     * ⚠️ **`hasSensor` answers this and NOTHING ELSE, which is the one thing it is good for.** Its
     * own KDoc on [dev.mascwa.pulse.data.sensors.CompassController.Reading] warns that it is not a
     * statement about whether anything has been measured — that is `hasReading`, tested separately
     * below. Here the question genuinely is "does this handset own a rotation-vector sensor", which
     * is fixed at construction from `getDefaultSensor` and never changes.
     *
     * Read from `.value` rather than collected: a capability cannot arrive later.
     */
    override val hasAttitudeSensor: Boolean get() = compass.reading.value.hasSensor

    override fun startAttitude(samplingPeriodUs: Int) {
        compass.start(samplingPeriodUs)
        // ⚠️ Guarded, so a second `startAttitude` cannot leave two collectors on one flow. They
        // would publish the same values and only one would be cancelled by `stopAttitude`, so the
        // survivor would keep the sensor listener alive behind a control that says it is off.
        if (job == null) {
            job = scope.launch {
                compass.reading.collect { r ->
                    // ⚠️ THE ONE LINE THE WHOLE SEAM EXISTS FOR. `hasSensor` says the hardware is
                    // present, which is true from construction, so the flow's SEED carries it with
                    // every angle at zero — level and due north. `hasReading` is the fact that
                    // something was actually measured. Publishing the seed as an attitude is what
                    // made the map sweep in from north for a third of a second on every enable.
                    if (!r.hasSensor || !r.hasReading) return@collect
                    _attitude.value = SkyAttitude(
                        trueAzimuthDeg = r.trueAzimuth.toDouble(),
                        altitudeDeg = r.pitch.toDouble(),
                        rollDeg = r.roll.toDouble(),
                        accuracyLow = r.accuracyLow,
                    )
                }
            }
        }
    }

    override fun stopAttitude() {
        job?.cancel()
        job = null
        compass.stop()
        // ⚠️ Cleared, so a later enable starts from "nothing measured yet" rather than from
        // wherever the phone happened to be pointed last time. The stale value would be taken whole
        // as the first reading, which is the same defect this class was written to remove.
        _attitude.value = null
    }

    override fun declinationAt(latitude: Double, longitude: Double, altitudeMetres: Double) {
        compass.setLocation(latitude, longitude, altitudeMetres)
    }

    private var job: Job? = null
}
