package dev.mascwa.pulse.sky

import kotlinx.coroutines.flow.StateFlow

/**
 * The two things the star map needs from the handset that the handset alone can answer.
 *
 * Everything else the map draws is bundled or computed — the catalogues live in this module, the
 * astronomy in `:core:telemetry`, and neither needs a network or a permission. What is left is
 * **where you are** and **where the phone is pointed**, and both come from platform services that
 * an application owns rather than a library.
 *
 * ⚠️ **An interface rather than moving the two classes in, and the reason is which way the
 * dependency would run.** `LocationProvider` has twenty consumers across the LCARS application —
 * weather, navigation, the radar, safety, nearest help — so putting it here would make the weather
 * screen depend on a star renderer to find out where it is. `CompassController` has four. Neither
 * belongs to the sky; the sky merely asks them a question, which is exactly what an interface is
 * for. Same shape as `HealthDeps`, and for the same reason.
 *
 * ⚠️ **Both answers are NULLABLE and that is the whole design.** A location can be unknown because
 * the permission was refused, because no fix has arrived, or because the machine has no idea where
 * it is; an attitude can be unknown because the phone has no rotation-vector sensor, or because it
 * has one and it has not reported yet. Every one of those is a FACT the map can state, and none of
 * them is the same as a reading of zero — see [SkyAttitude] for what conflating the two cost.
 */
interface SkyDeps {

    /**
     * Where the observer is, or null when nothing can say.
     *
     * ⚠️ Suspending because answering can mean asking the platform for a fix. It must never throw:
     * a refused permission and a failed lookup both answer null, and the map falls back to whatever
     * site it was last given rather than to a crash on opening a page.
     */
    suspend fun site(): SkySite?

    /**
     * Where the handset is aimed, or null until something has actually measured it.
     *
     * ⚠️ **Null while nothing has reported, and it stays null on a device with no sensor.** Not an
     * `SkyAttitude()` of zeros — see the type's own note.
     */
    val attitude: StateFlow<SkyAttitude?>

    /**
     * Whether this device can answer where it is aimed AT ALL.
     *
     * ⚠️ **This is a different question from [attitude] being null, and conflating them is what
     * makes a map freeze.** A null attitude means "nothing has measured this yet", which is the
     * ordinary state for the first few tens of milliseconds after [startAttitude]. This means "there
     * is no rotation-vector sensor in this handset", which never becomes false and never resolves.
     * Turning following on without consulting it registers a listener that can never fire, leaves
     * `pointing` reading true, and — because dragging is refused while following — hands somebody a
     * sky they cannot turn by any means. Read before enabling, not after.
     *
     * Cheap and constant: both implementations answer from a `getDefaultSensor` performed once.
     */
    val hasAttitudeSensor: Boolean

    /** Begin reporting into [attitude]. A device with no sensor may do nothing at all. */
    fun startAttitude()

    /** Stop reporting. Calling this without a matching [startAttitude] must be harmless. */
    fun stopAttitude()

    /**
     * Tell the attitude source where it is, so it can turn a magnetic bearing into a true one.
     *
     * ⚠️ Offline: the declination comes from the world magnetic model built into the platform, not
     * from a lookup. Passing a site is what makes a compass point at true north with the radio off,
     * which is the condition this whole feature is designed for.
     */
    fun declinationAt(latitude: Double, longitude: Double, altitudeMetres: Double)
}

/** Where the observer is, in degrees. */
data class SkySite(val latitude: Double, val longitude: Double)

/**
 * Where the handset is aimed, as measured.
 *
 * ⚠️ **THE EXISTENCE OF THIS TYPE FIXED A REAL DEFECT, and the defect is why it is only ever
 * handed over once something has been measured.** The sensor wrapper it replaces published a
 * `StateFlow` seeded with an all-zero reading carrying `hasSensor = true` — meaning "this phone has
 * a rotation-vector sensor", which is true, and reading as "the phone is level and pointed due
 * north", which is not. A `StateFlow` hands a new collector its current value immediately, so
 * turning pointing mode on collected that seed first, the map's own "take the FIRST reading whole
 * rather than blending it" branch spent itself on a non-reading, and the first real sample then
 * arrived weighted at a quarter. The measured cost: about sixteen samples at roughly twenty
 * milliseconds each, so **a third of a second of the sky sweeping in from due north and the
 * horizon, on every enable** — precisely what that branch was written to prevent.
 *
 * Null-until-measured makes the seed unrepresentable. There is no value of this class that means
 * "nothing has been measured", because that is the absence of one.
 */
data class SkyAttitude(
    /** Degrees clockwise from TRUE north, 0..360. */
    val trueAzimuthDeg: Double,
    /** Degrees above the horizon the phone is aimed, negative below. */
    val altitudeDeg: Double,
    /** Degrees the handset is tipped in its own plane, positive when its top goes right. */
    val rollDeg: Double,
    /**
     * Whether the platform reports the magnetometer as needing a figure-of-eight.
     *
     * ⚠️ Advisory, not a gate. A low-accuracy reading is still the best available answer and the
     * map keeps following it; the flag exists so the screen can say why the sky is a few degrees
     * out rather than leaving somebody to conclude the maths is wrong.
     */
    val accuracyLow: Boolean,
)
