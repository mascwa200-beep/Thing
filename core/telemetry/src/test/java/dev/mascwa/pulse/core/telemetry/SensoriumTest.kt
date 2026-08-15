package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [Sensorium.distill]'s fusion judgments — especially the two lessons carried forward from the
 * deleted Perception core (a still phone must never read as moving; engine sounds while stationary
 * are not transit) — and the throttle ladder's hysteresis, which is what keeps a battery hovering at
 * a boundary from flapping the whole sensing stack on and off.
 */
class SensoriumTest {

    private fun labels(vararg l: String) = l.map { PerceptLabel(it, 0.8f) }

    @Test
    fun aStillPhoneInAQuietLitRoomReadsAsSuch() {
        val r = Sensorium.distill(
            SenseFrame(
                sceneLabels = labels("desk", "computer monitor", "wall"),
                lightLux = 120f, movement = 0.01f,
            ),
        )
        assertEquals(EnvSetting.INDOOR, r.setting)
        assertEquals(MotionState.STILL, r.motion)
        assertEquals(LightState.LIT, r.light)
        assertEquals(SocialDensity.ALONE, r.social)
        assertTrue(r.seen)
        assertTrue(!r.heard)
    }

    @Test
    fun engineSoundsWhileStationaryAreNotTransit() {
        // The carried-forward Perception fix: VEHICLE requires real motion.
        val r = Sensorium.distill(
            SenseFrame(soundLabels = labels("engine", "car"), movement = 0.01f),
        )
        assertTrue(r.setting != EnvSetting.VEHICLE)
    }

    @Test
    fun engineSoundsPlusMotionAreTransit() {
        val r = Sensorium.distill(
            SenseFrame(soundLabels = labels("engine"), movement = 0.15f),
        )
        assertEquals(EnvSetting.VEHICLE, r.setting)
    }

    @Test
    fun drivingSpeedIsVehicularRegardlessOfLabels() {
        val r = Sensorium.distill(SenseFrame(speedMps = 15f))
        assertEquals(MotionState.DRIVING, r.motion)
        assertEquals(EnvSetting.VEHICLE, r.setting)
    }

    @Test
    fun handlingSitsBetweenStillAndWalking() {
        assertEquals(MotionState.HANDLING, Sensorium.distill(SenseFrame(movement = 0.05f)).motion)
        assertEquals(MotionState.WALKING, Sensorium.distill(SenseFrame(movement = 0.2f)).motion)
        assertEquals(MotionState.STILL, Sensorium.distill(SenseFrame(movement = 0.01f)).motion)
    }

    @Test
    fun bluetoothDensityReadsCrowdWithoutAnySound() {
        assertEquals(SocialDensity.CROWD, Sensorium.distill(SenseFrame(btDeviceCount = 14)).social)
        assertEquals(SocialDensity.FEW, Sensorium.distill(SenseFrame(btDeviceCount = 4)).social)
        assertEquals(SocialDensity.ALONE, Sensorium.distill(SenseFrame(btDeviceCount = 1)).social)
    }

    @Test
    fun voicesReadAsCompanyAndCrowdSoundsAsCrowd() {
        assertEquals(
            SocialDensity.FEW,
            Sensorium.distill(SenseFrame(soundLabels = labels("speech"))).social,
        )
        assertEquals(
            SocialDensity.CROWD,
            Sensorium.distill(SenseFrame(soundLabels = labels("crowd", "chatter"))).social,
        )
    }

    @Test
    fun noiseProfileRanksFromSilenceToLoud() {
        assertEquals(
            NoiseProfile.SILENT,
            Sensorium.distill(SenseFrame(soundLabels = labels("silence"))).noise,
        )
        assertEquals(
            NoiseProfile.CALM,
            Sensorium.distill(SenseFrame(soundLabels = labels("bird", "wind"))).noise,
        )
        assertEquals(
            NoiseProfile.LOUD,
            Sensorium.distill(SenseFrame(soundLabels = labels("music", "shouting", "traffic"))).noise,
        )
        // No mic data at all = assume quiet, never invent noise.
        assertEquals(NoiseProfile.QUIET, Sensorium.distill(SenseFrame()).noise)
    }

    @Test
    fun weakLabelsAreIgnored() {
        val r = Sensorium.distill(
            SenseFrame(sceneLabels = listOf(PerceptLabel("street", 0.1f))),
        )
        assertEquals(EnvSetting.UNKNOWN, r.setting)
    }

    @Test
    fun brightAmbientLightWithoutCameraLeansOutdoor() {
        assertEquals(EnvSetting.OUTDOOR, Sensorium.distill(SenseFrame(lightLux = 8000f)).setting)
        assertEquals(LightState.SUNLIGHT, Sensorium.distill(SenseFrame(lightLux = 8000f)).light)
    }

    @Test
    fun pressureTrendClassifies() {
        assertEquals(
            PressureTrend.PLUNGING,
            Sensorium.distill(SenseFrame(pressureDeltaHpa = -4.2f)).pressureTrend,
        )
        assertEquals(
            PressureTrend.FALLING,
            Sensorium.distill(SenseFrame(pressureDeltaHpa = -1.5f)).pressureTrend,
        )
        assertEquals(
            PressureTrend.STEADY,
            Sensorium.distill(SenseFrame(pressureDeltaHpa = 0.2f)).pressureTrend,
        )
        assertEquals(
            PressureTrend.RISING,
            Sensorium.distill(SenseFrame(pressureDeltaHpa = 1.8f)).pressureTrend,
        )
        assertEquals(null, Sensorium.distill(SenseFrame()).pressureTrend)
    }

    @Test
    fun describeReadsAsOneHumanLine() {
        val line = Sensorium.distill(
            SenseFrame(
                sceneLabels = labels("desk", "wall"), lightLux = 120f,
                movement = 0.01f, soundLabels = labels("speech"),
            ),
        ).describe()
        assertTrue(line.contains("Indoors"))
        assertTrue(line.contains("still"))
        assertTrue(line.contains("company nearby"))
    }

    // ---- the throttle ladder ----

    @Test
    fun ladderPicksLevelsByBatteryAndActivity() {
        val l = Sensorium.level(
            Sensorium.SenseLevel.NOMINAL,
            batteryPct = 80, charging = false, powerSave = false,
            screenOffMinutes = 0, movement = 0.2f,
        )
        assertEquals(Sensorium.SenseLevel.NOMINAL, l)
        assertEquals(
            Sensorium.SenseLevel.SETTLED,
            Sensorium.level(Sensorium.SenseLevel.NOMINAL, 80, false, false, 45, 0.01f),
        )
        assertEquals(
            Sensorium.SenseLevel.CONSERVE,
            Sensorium.level(Sensorium.SenseLevel.NOMINAL, 20, false, false, 0, 0.2f),
        )
        assertEquals(
            Sensorium.SenseLevel.CONSERVE,
            Sensorium.level(Sensorium.SenseLevel.NOMINAL, 80, false, true, 0, 0.2f),
        )
        assertEquals(
            Sensorium.SenseLevel.STANDDOWN,
            Sensorium.level(Sensorium.SenseLevel.CONSERVE, 8, false, false, 0, 0f),
        )
    }

    @Test
    fun ladderHasHysteresisAtTheConserveBoundary() {
        // Throttled at 25%, battery crawls back to 27% — must NOT flap to NOMINAL until 30%.
        assertEquals(
            Sensorium.SenseLevel.CONSERVE,
            Sensorium.level(Sensorium.SenseLevel.CONSERVE, 27, false, false, 0, 0.2f),
        )
        assertEquals(
            Sensorium.SenseLevel.NOMINAL,
            Sensorium.level(Sensorium.SenseLevel.CONSERVE, 31, false, false, 0, 0.2f),
        )
        // A charger recovers immediately at any level.
        assertEquals(
            Sensorium.SenseLevel.NOMINAL,
            Sensorium.level(Sensorium.SenseLevel.STANDDOWN, 8, true, false, 0, 0.2f),
        )
    }

    @Test
    fun cadenceTightensAndReleasesWithTheLadder() {
        val nominal = Sensorium.cadenceFor(Sensorium.SenseLevel.NOMINAL)
        val settled = Sensorium.cadenceFor(Sensorium.SenseLevel.SETTLED)
        val conserve = Sensorium.cadenceFor(Sensorium.SenseLevel.CONSERVE)
        val standdown = Sensorium.cadenceFor(Sensorium.SenseLevel.STANDDOWN)
        assertTrue(nominal.micIntervalSec < settled.micIntervalSec)
        assertTrue(settled.micIntervalSec < conserve.micIntervalSec)
        assertEquals(0, standdown.micIntervalSec)
        assertTrue(nominal.cameraIntervalSec > 0)
        assertEquals(0, conserve.cameraIntervalSec)
        assertTrue(!conserve.cameraOnTrigger) // ALERT ramps allowed down to SETTLED, not CONSERVE
        assertTrue(standdown.fusionHeartbeatSec > conserve.fusionHeartbeatSec)
    }
}
