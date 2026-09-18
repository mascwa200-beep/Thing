package dev.mascwa.pulse.core.telemetry

/**
 * Which way up the phone is lying, when the accelerometer can say.
 *
 * ⚠️ [FACE_DOWN] is the one that carries information a person would recognise: a phone placed
 * deliberately face-down on a table is somebody saying "leave me alone", and it is also the one
 * posture in which the screen, the front sensors and the notification light are all useless.
 * [UPRIGHT] covers held-in-hand and stood in a dock, which the accelerometer alone cannot separate.
 */
enum class Posture {
    FACE_UP, FACE_DOWN, UPRIGHT, UNKNOWN;

    companion object {
        /**
         * Which way up, from the accelerometer's z axis — the one that points out of the screen.
         *
         * Both arguments are in units of g, so this stays free of `SensorManager.GRAVITY_EARTH` and
         * of any platform type; the caller divides, exactly as
         * `SensorFusionController` already does for the movement figure it keeps.
         *
         * ⚠️ **[restDeviationG] is the load-bearing half, not the sign of z.** An accelerometer
         * reports gravity PLUS whatever else is pushing the phone about, and it has no way to say
         * which part is which. At rest the total is 1 g and the direction of that vector really is
         * down; in a hand on a walk it swings between roughly 0.7 and 1.4 g and the z component then
         * says nothing about orientation at all. Claiming a posture from a moving phone is how a
         * rule comes to believe a handset was set face-down on a table while it was in fact swinging
         * in a coat pocket — so anything above a small deviation is [UNKNOWN], a refusal rather than
         * a guess.
         *
         * ⚠️ **It is a DEVIATION from rest and not a magnitude, and that distinction is what makes a
         * smoothed input usable.** A phone's raw |a| swings symmetrically about 1 g while it is
         * carried, so the obvious thing — smooth the magnitude and compare it against 1 — averages
         * straight back to 1 and the gate never fires on the one case it exists for. `|a|/g − 1` is
         * always positive, so smoothing it preserves the movement instead of cancelling it. The
         * Sensorium passes the EWMA `SensorFusionController` already keeps and has already tuned; an
         * instantaneous `abs(g − 1)` is equally valid for a caller that wants a single sample.
         *
         * [UPRIGHT] is deliberately one value covering held-in-hand, stood in a dock and propped
         * against a mug. The accelerometer cannot separate those and inventing names for them would
         * be a claim the hardware does not support.
         */
        fun from(zG: Float, restDeviationG: Float): Posture = when {
            !zG.isFinite() || !restDeviationG.isFinite() -> UNKNOWN
            restDeviationG > RESTING_TOLERANCE_G -> UNKNOWN
            zG >= FLAT_G -> FACE_UP
            zG <= -FLAT_G -> FACE_DOWN
            else -> UPRIGHT
        }

        /**
         * How far from rest the acceleration may sit and still be read as gravity alone.
         *
         * A phone at rest on a table deviates by about 0.00; one being carried sits well above this.
         * Widening it buys postures that are wrong, narrowing it refuses postures that are right —
         * and the cost is asymmetric, because the refusal is visible as [UNKNOWN] while a wrong
         * posture is indistinguishable from a real one.
         */
        const val RESTING_TOLERANCE_G = 0.15f

        /**
         * How flat counts as flat: cos(37°) ≈ 0.80, so a phone within about 37° of horizontal is
         * lying down. A shallower bar would call a phone propped at a steep angle "face-up".
         */
        const val FLAT_G = 0.80f
    }
}

/**
 * Where this phone's sound is going right now.
 *
 * ⚠️ [BLUETOOTH] is the interesting one and it is deliberately not split further. A car stereo, a
 * pair of earbuds and a kitchen speaker are all `TYPE_BLUETOOTH_A2DP` and the platform will not say
 * which — so a rule that wants "you are driving" must corroborate with speed and motion rather than
 * reading a car into the route on its own.
 */
enum class AudioRoute { EARPIECE, SPEAKER, WIRED, BLUETOOTH, UNKNOWN }

/** The ringer switch, as the person left it. */
enum class RingerState { SILENT, VIBRATE, NORMAL }

/**
 * The phone's own Do Not Disturb, read as a SENSING input.
 *
 * ⚠️ **Named for what a person would call it, not for the platform's constants**, which are
 * genuinely confusing: `INTERRUPTION_FILTER_ALL` means DND is OFF (everything gets through) and
 * `INTERRUPTION_FILTER_NONE` means total silence. Reading those two the wrong way round inverts
 * every rule that consults this, so they are renamed at the boundary and the mapping is asserted in
 * one place.
 *
 * ⚠️ [UNKNOWN] is what the platform itself answers (`INTERRUPTION_FILTER_UNKNOWN`) when it will not
 * say, and it must not be folded into [OFF]: "DND is off" and "we are not allowed to know" are
 * different facts, and the second one must never be used to justify overriding a setting.
 */
enum class DndFilter { OFF, PRIORITY, ALARMS_ONLY, TOTAL_SILENCE, UNKNOWN }

/**
 * Whether a person is with the phone, and how much of their attention it has.
 *
 * ⚠️ This is a judgement about a PERSON from evidence about a DEVICE, so the confident end of it is
 * narrow on purpose: a lit, unlocked screen is the only thing that really means somebody is looking.
 */
enum class Attention { IN_USE, PRESENT, AWAY, UNKNOWN }

/**
 * What the phone knows about ITSELF at one moment — as distinct from [SenseFrame], which is what the
 * instruments measured about the world around it.
 *
 * ⚠️ **The split is [SenseFrame]'s own rule, applied rather than invented here.** That class states
 * it outright: *a frame carries what an instrument measured, and anything the caller simply knows is
 * passed alongside* — which is why hour-of-day and weekend were taken off it. Screen state, the
 * ringer switch, what audio is routed where and whether a charger is plugged in are all things the
 * caller simply knows, from system services, with no sensor involved. Putting them on the frame
 * would have made [Sensorium.distill] the thing that reads them, and it has no business deciding
 * whether somebody is at their desk.
 *
 * Every field is nullable and null means **nothing measured it**, never a default. That is the rule
 * the whole subsystem turns on: a phone with no ambient-light sensor reported a dim room for months
 * because a null was read as a value, and every reader here is written so an absent signal removes
 * evidence rather than inventing it.
 *
 * Nothing here costs a permission. Screen, lock, power, thermal, audio and Do Not Disturb are all
 * free reads; [awayFromHome] is already derived elsewhere in the app for the Oracle and is passed
 * in rather than read a second time.
 */
data class SenseContext(
    /** `PowerManager.isInteractive` — the screen is on, whatever is showing on it. */
    val screenOn: Boolean? = null,
    /** `KeyguardManager.isKeyguardLocked` — the lock screen is up. */
    val locked: Boolean? = null,
    /**
     * How long since the phone was last unlocked, on the elapsed-realtime clock.
     *
     * ⚠️ **Null means "no unlock has been seen since this process started", which is NOT the same as
     * "not unlocked recently".** The sensing service can start while the phone is already in
     * somebody's hand, and it will then carry a null here for as long as they keep using it without
     * locking. [attention] is written around that: the screen state decides, and this only refines
     * the screen-off case.
     */
    val msSinceUnlock: Long? = null,
    /** `PowerManager.isDeviceIdleMode` — the OS has concluded the phone is unattended. */
    val idle: Boolean? = null,
    val posture: Posture? = null,
    val charging: Boolean? = null,
    /** Which kind of charger, when charging — wireless on a nightstand reads differently from USB in a car. */
    val power: PowerSource? = null,
    /** Thermal headroom, via the pressure vocabulary [DeviceClass] already defines. */
    val thermal: DeviceClass.Pressure? = null,
    /** `AudioManager.getMode()` is in a call or a voice/VoIP call. */
    val onCall: Boolean? = null,
    val musicPlaying: Boolean? = null,
    val ringer: RingerState? = null,
    val route: AudioRoute? = null,
    val dnd: DndFilter? = null,
    /**
     * From the app's trusted-network read: the phone is on a Wi-Fi network that is not one of the
     * home ones.
     *
     * ⚠️ **A positive fact or nothing, never an inference from silence.** Null when no home network
     * has been configured and null when the SSID cannot be read — which on GrapheneOS is the
     * ordinary case, because reading it needs location permission. The Trusted-Network subsystem
     * learned this the hard way: an unreadable SSID read as "away" once switched the Wi-Fi radio off
     * in somebody's own house.
     *
     * ⚠️ It is deliberately NOT the INDOOR/OUTDOOR scene guess. That says something about a room;
     * this says something about a place, and they are not the same question.
     */
    val awayFromHome: Boolean? = null,
) {

    /**
     * Whether somebody is with the phone.
     *
     * The rules, in order, and each one is a claim that can be defended:
     *  - nothing read the screen → [Attention.UNKNOWN]. There is no second-best evidence for this.
     *  - lit and unlocked → [Attention.IN_USE]. The only state that really means someone is looking.
     *  - lit → [Attention.PRESENT]. A glance at a notification, or a lock screen somebody woke.
     *  - the OS is dozing → [Attention.AWAY]. Doze is Android's own conclusion that nobody is here,
     *    reached from far more evidence than this class has, so it is taken at its word.
     *  - unlocked within the last few minutes → [Attention.PRESENT]. Somebody who put their phone
     *    down a minute ago is still sitting next to it.
     *  - otherwise → [Attention.AWAY].
     *
     * ⚠️ **A screen-off phone is not AWAY on the strength of the screen alone** — that would call
     * every pocket, every table and every meeting "away" within a second of the display timing out.
     * The recency of the last unlock is what separates "just put it down" from "left the room", and
     * when that is unknown the answer falls to AWAY, which is the conservative direction: a rule
     * that holds interruptions back because it believes nobody is looking is a smaller mistake than
     * one that interrupts a person who has left.
     */
    fun attention(): Attention = when {
        screenOn == null -> Attention.UNKNOWN
        screenOn && locked == false -> Attention.IN_USE
        screenOn -> Attention.PRESENT
        idle == true -> Attention.AWAY
        msSinceUnlock != null && msSinceUnlock <= RECENTLY_PUT_DOWN_MS -> Attention.PRESENT
        else -> Attention.AWAY
    }

    /**
     * One line for the scanner and for anything that wants to say what the phone is doing.
     *
     * ⚠️ **Deliberately NOT folded into [EnvReading.describe]**, which is the environment line the
     * Computer is handed every single turn. That line is four to six words about the room; this is
     * about the handset, and stapling them together would double the length of the thing a person
     * reads most often to carry facts most turns do not need.
     *
     * Silent when there is nothing to say: an idle, uncharging, unrouted phone in normal ringer mode
     * contributes no clause at all, so what survives is what is actually notable.
     */
    fun describe(): String {
        val parts = mutableListOf<String>()
        parts += when (attention()) {
            Attention.IN_USE -> "in use"
            Attention.PRESENT -> "to hand"
            Attention.AWAY -> "put down"
            Attention.UNKNOWN -> "attention unknown"
        }
        if (posture == Posture.FACE_DOWN) parts += "face-down"
        if (charging == true) {
            parts += when (power) {
                PowerSource.WIRELESS -> "charging on a pad"
                PowerSource.USB -> "charging over USB"
                PowerSource.AC -> "on mains"
                else -> "charging"
            }
        }
        when (thermal) {
            DeviceClass.Pressure.WARM -> parts += "warm"
            DeviceClass.Pressure.HOT -> parts += "hot"
            DeviceClass.Pressure.CRITICAL -> parts += "overheating"
            else -> {}
        }
        if (onCall == true) parts += "on a call"
        if (musicPlaying == true) parts += "playing audio"
        when (route) {
            AudioRoute.BLUETOOTH -> parts += "over Bluetooth"
            AudioRoute.WIRED -> parts += "on headphones"
            else -> {}
        }
        when (ringer) {
            RingerState.SILENT -> parts += "silenced"
            RingerState.VIBRATE -> parts += "on vibrate"
            else -> {}
        }
        when (dnd) {
            DndFilter.PRIORITY -> parts += "priority only"
            DndFilter.ALARMS_ONLY -> parts += "alarms only"
            DndFilter.TOTAL_SILENCE -> parts += "do not disturb"
            else -> {}
        }
        if (awayFromHome == true) parts += "away from home"
        if (idle == true) parts += "dozing"
        return parts.joinToString(" · ")
    }

    /**
     * True when the person has asked, in one way or another, not to be interrupted.
     *
     * ⚠️ **The whole point is that it is UNSET when unknown.** Every one of the three signals can be
     * absent, and an absent signal is not a quiet phone: a rule that reads "not silenced" out of a
     * null would decide it is free to make a noise on a device that never told it anything. Null
     * here means the question could not be answered, and the caller has to handle that rather than
     * being handed a comfortable `false`.
     */
    fun wantsQuiet(): Boolean? {
        val known = listOfNotNull(
            ringer?.let { it != RingerState.NORMAL },
            dnd?.takeIf { it != DndFilter.UNKNOWN }?.let { it != DndFilter.OFF },
            posture?.let { it == Posture.FACE_DOWN },
        )
        return if (known.isEmpty()) null else known.any { it }
    }

    companion object {
        /**
         * How long after a lock somebody is still considered to be sitting there.
         *
         * Two minutes because it has to cover the ordinary gap between putting a phone face-down and
         * picking it up again — and because the cost of being wrong is asymmetric. Too long and a
         * rule waits a couple of minutes before deciding you have gone; too short and it decides you
         * have left the room every time the display times out mid-conversation.
         */
        const val RECENTLY_PUT_DOWN_MS = 2 * 60_000L
    }
}
