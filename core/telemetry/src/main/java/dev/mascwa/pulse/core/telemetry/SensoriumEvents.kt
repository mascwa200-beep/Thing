package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs

/**
 * Event extraction for the Sensorium — the moments worth acting on, remembering, or at least logging,
 * pulled out of the continuous stream. Three severities with three fates:
 *  - [EventSeverity.ALERT] — safety-relevant right now (smoke/CO alarm, breaking glass, gunshot,
 *    sirens converging): the service raises an urgent line on the LCARS board immediately.
 *  - [EventSeverity.NOTABLE] — worth remembering (doorbell, knocking, a dog going off, thunder, a
 *    storm-front pressure plunge, lights-out): episodic memory + the scanner's event log.
 *  - [EventSeverity.LOG] — texture (light transitions, magnetic field spikes): scanner log only.
 *
 * ALERT detection demands a higher confidence floor than mere classification — a false smoke alarm at
 * 3am is a real harm, so [ALERT_MIN_CONF] is deliberately strict, and the caller must ALSO dedupe by
 * [SenseEvent.key] with a cool-down (a real alarm fires the classifier every sip for minutes).
 */

enum class EventSeverity { LOG, NOTABLE, ALERT }

data class SenseEvent(
    /** Stable dedup key per event KIND ("sound.smoke_alarm") — the caller's cool-down handle. */
    val key: String,
    val title: String,
    val detail: String,
    val severity: EventSeverity,
)

object SensoriumEvents {

    /** Sound classifiers cry wolf below this for safety events. */
    const val ALERT_MIN_CONF = 0.55f
    /** Notable/log sounds may use the ordinary floor. */
    const val NOTABLE_MIN_CONF = 0.40f

    /** (key, vocab, title, detail) — vocab substring-matched on lower-cased labels. */
    private data class SoundRule(
        val key: String,
        val vocab: Set<String>,
        val title: String,
        val detail: String,
        val severity: EventSeverity,
    )

    private val SOUND_RULES = listOf(
        SoundRule(
            "sound.smoke_alarm", setOf("smoke detector", "smoke alarm", "fire alarm", "carbon monoxide"),
            "ALARM HEARD", "a smoke/fire/CO alarm is sounding nearby", EventSeverity.ALERT,
        ),
        SoundRule(
            "sound.glass", setOf("glass", "shatter"),
            "GLASS BREAKING", "the sound of breaking glass was heard", EventSeverity.ALERT,
        ),
        SoundRule(
            "sound.gunshot", setOf("gunshot", "gunfire", "machine gun", "artillery"),
            "GUNSHOT-LIKE SOUND", "a gunshot-like sound was heard", EventSeverity.ALERT,
        ),
        SoundRule(
            "sound.siren", setOf("siren", "civil defense", "ambulance", "police car", "fire engine"),
            "SIREN", "an emergency siren is audible", EventSeverity.NOTABLE,
        ),
        SoundRule(
            "sound.doorbell", setOf("doorbell", "ding-dong"),
            "DOORBELL", "the doorbell rang", EventSeverity.NOTABLE,
        ),
        SoundRule(
            "sound.knock", setOf("knock"),
            "KNOCKING", "knocking was heard", EventSeverity.NOTABLE,
        ),
        SoundRule(
            "sound.dog", setOf("dog", "bark", "howl"),
            "DOG", "a dog is barking nearby", EventSeverity.NOTABLE,
        ),
        SoundRule(
            "sound.baby", setOf("baby cry", "infant cry", "baby laughter"),
            "BABY", "a baby is audible", EventSeverity.NOTABLE,
        ),
        SoundRule(
            "sound.thunder", setOf("thunder"),
            "THUNDER", "thunder was heard", EventSeverity.NOTABLE,
        ),
        SoundRule(
            "sound.water_running", setOf("water tap", "faucet", "filling", "bathtub"),
            "RUNNING WATER", "water is running", EventSeverity.LOG,
        ),
    )

    /** Sound events in one mic sip. The caller dedupes/cools-down by [SenseEvent.key]. */
    fun fromSounds(labels: List<PerceptLabel>): List<SenseEvent> {
        if (labels.isEmpty()) return emptyList()
        val out = mutableListOf<SenseEvent>()
        for (rule in SOUND_RULES) {
            val floor = if (rule.severity == EventSeverity.ALERT) ALERT_MIN_CONF else NOTABLE_MIN_CONF
            val hit = labels.any { l ->
                l.confidence >= floor && rule.vocab.any { l.label.lowercase().contains(it) }
            }
            if (hit) out += SenseEvent(rule.key, rule.title, rule.detail, rule.severity)
        }
        return out
    }

    /** A storm-front read from the 3-hour barometric delta. */
    fun pressureEvent(deltaHpa: Float?): SenseEvent? = when {
        deltaHpa == null -> null
        deltaHpa <= Sensorium.PRESSURE_PLUNGE_HPA -> SenseEvent(
            "env.pressure_plunge", "PRESSURE PLUNGING",
            "barometric pressure fell ${"%.1f".format(-deltaHpa)} hPa in ~3h — a storm front is likely approaching",
            EventSeverity.NOTABLE,
        )
        deltaHpa <= Sensorium.PRESSURE_FALL_HPA -> SenseEvent(
            "env.pressure_fall", "PRESSURE FALLING",
            "barometric pressure is falling — weather may be turning", EventSeverity.LOG,
        )
        else -> null
    }

    /** Lights-out / lights-on / dawn-class transitions between consecutive light readings. */
    fun lightTransition(prevLux: Float?, lux: Float?, hourOfDay: Int): SenseEvent? {
        if (prevLux == null || lux == null) return null
        val wasDark = prevLux < Sensorium.LUX_DARK
        val isDark = lux < Sensorium.LUX_DARK
        val night = hourOfDay >= 21 || hourOfDay < 6
        return when {
            !wasDark && isDark && prevLux >= Sensorium.LUX_DIM -> SenseEvent(
                "env.lights_out", "LIGHTS OUT", "the room just went dark", EventSeverity.LOG,
            )
            wasDark && !isDark && lux >= Sensorium.LUX_DIM && night -> SenseEvent(
                "env.lights_on", "LIGHT IN THE DARK", "light just came on during night hours",
                EventSeverity.NOTABLE,
            )
            wasDark && !isDark && lux >= Sensorium.LUX_DIM -> SenseEvent(
                "env.lights_on_day", "LIGHTS ON", "the room just lit up", EventSeverity.LOG,
            )
            else -> null
        }
    }

    /** Field strength at/above this (µT) reads as a strong magnetic source right next to the phone. */
    const val MAG_SPIKE_UT = 250f
    /** …and the jump from the previous reading must itself be large (Earth ambient is ~25-65 µT). */
    const val MAG_JUMP_UT = 120f

    /** A sudden strong magnetic field — a magnet, a large appliance, an induction surface. Honest
     *  framing only: this is physics next to the phone, never presented as anything more. */
    fun magneticEvent(prevUt: Float?, ut: Float?): SenseEvent? {
        if (prevUt == null || ut == null) return null
        if (ut < MAG_SPIKE_UT || abs(ut - prevUt) < MAG_JUMP_UT) return null
        return SenseEvent(
            "env.magnetic_spike", "MAGNETIC ANOMALY",
            "field strength jumped to ${ut.toInt()} µT — a strong magnet or large appliance is right by the phone",
            EventSeverity.LOG,
        )
    }
}
