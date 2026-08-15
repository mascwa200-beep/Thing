package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the event extractor's severity discipline: safety sounds demand the strict confidence floor
 * (a 3am false smoke alarm is a real harm), ordinary sounds use the ordinary one, and every
 * environmental transition states only what was measured.
 */
class SensoriumEventsTest {

    @Test
    fun aConfidentAlarmIsAnAlert() {
        val events = SensoriumEvents.fromSounds(
            listOf(PerceptLabel("Smoke detector, smoke alarm", 0.8f)),
        )
        assertEquals(1, events.size)
        assertEquals(EventSeverity.ALERT, events[0].severity)
        assertEquals("sound.smoke_alarm", events[0].key)
    }

    @Test
    fun aWeakAlarmLabelDoesNotAlert() {
        // Below ALERT_MIN_CONF (0.55) but above the notable floor — safety events stay strict.
        assertTrue(
            SensoriumEvents.fromSounds(listOf(PerceptLabel("Fire alarm", 0.45f))).isEmpty(),
        )
    }

    @Test
    fun ordinarySoundsUseTheOrdinaryFloor() {
        val events = SensoriumEvents.fromSounds(listOf(PerceptLabel("Dog", 0.45f)))
        assertEquals(1, events.size)
        assertEquals(EventSeverity.NOTABLE, events[0].severity)
    }

    @Test
    fun glassAndGunshotAreAlertsSirenIsNotable() {
        val e = SensoriumEvents.fromSounds(
            listOf(
                PerceptLabel("Shatter", 0.7f),
                PerceptLabel("Gunshot, gunfire", 0.7f),
                PerceptLabel("Siren", 0.7f),
            ),
        )
        assertEquals(EventSeverity.ALERT, e.first { it.key == "sound.glass" }.severity)
        assertEquals(EventSeverity.ALERT, e.first { it.key == "sound.gunshot" }.severity)
        assertEquals(EventSeverity.NOTABLE, e.first { it.key == "sound.siren" }.severity)
    }

    @Test
    fun pressureEventsFollowTheThresholds() {
        assertEquals(
            "env.pressure_plunge",
            SensoriumEvents.pressureEvent(-3.5f)!!.key,
        )
        assertEquals(EventSeverity.NOTABLE, SensoriumEvents.pressureEvent(-3.5f)!!.severity)
        assertEquals("env.pressure_fall", SensoriumEvents.pressureEvent(-1.4f)!!.key)
        assertNull(SensoriumEvents.pressureEvent(-0.3f))
        assertNull(SensoriumEvents.pressureEvent(2.0f))
        assertNull(SensoriumEvents.pressureEvent(null))
    }

    @Test
    fun lightInTheDarkAtNightIsNotableLightsOutIsLog() {
        val on = SensoriumEvents.lightTransition(prevLux = 2f, lux = 180f, hourOfDay = 2)
        assertEquals("env.lights_on", on!!.key)
        assertEquals(EventSeverity.NOTABLE, on.severity)
        val off = SensoriumEvents.lightTransition(prevLux = 200f, lux = 2f, hourOfDay = 22)
        assertEquals("env.lights_out", off!!.key)
        assertEquals(EventSeverity.LOG, off.severity)
        val day = SensoriumEvents.lightTransition(prevLux = 2f, lux = 180f, hourOfDay = 14)
        assertEquals("env.lights_on_day", day!!.key)
        assertEquals(EventSeverity.LOG, day.severity)
    }

    @Test
    fun smallLightWobbleIsNotATransition() {
        assertNull(SensoriumEvents.lightTransition(prevLux = 20f, lux = 30f, hourOfDay = 14))
        assertNull(SensoriumEvents.lightTransition(prevLux = 9f, lux = 5f, hourOfDay = 14))
        assertNull(SensoriumEvents.lightTransition(prevLux = null, lux = 100f, hourOfDay = 14))
    }

    @Test
    fun magneticSpikeNeedsBothStrengthAndJump() {
        val spike = SensoriumEvents.magneticEvent(prevUt = 45f, ut = 320f)
        assertEquals("env.magnetic_spike", spike!!.key)
        assertEquals(EventSeverity.LOG, spike.severity)
        // Strong but steady (already near a magnet) — no event.
        assertNull(SensoriumEvents.magneticEvent(prevUt = 300f, ut = 320f))
        // A jump that stays within Earth-ambient strength — no event.
        assertNull(SensoriumEvents.magneticEvent(prevUt = 30f, ut = 160f))
        assertNull(SensoriumEvents.magneticEvent(null, 320f))
    }
}
