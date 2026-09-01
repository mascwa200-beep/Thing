package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    // ---- the device tier folds into the ONE ladder ---------------------------------------------

    private fun lvl(
        tier: DeviceClass.Tier = DeviceClass.Tier.FULL,
        pressure: DeviceClass.Pressure = DeviceClass.Pressure.NONE,
        battery: Int = 90,
        charging: Boolean = false,
        powerSave: Boolean = false,
        screenOffMin: Int = 0,
        movement: Float = 0.5f,
        previous: Sensorium.SenseLevel = Sensorium.SenseLevel.NOMINAL,
    ) = Sensorium.level(
        previous = previous, batteryPct = battery, charging = charging, powerSave = powerSave,
        screenOffMinutes = screenOffMin, movement = movement, tier = tier, pressure = pressure,
    )

    @Test
    fun `a flagship at rest is byte-for-byte what it was before the tier existed`() {
        assertEquals(Sensorium.SenseLevel.NOMINAL, lvl())
        // And the defaults are what an un-migrated call site gets.
        assertEquals(
            lvl(),
            Sensorium.level(
                previous = Sensorium.SenseLevel.NOMINAL, batteryPct = 90, charging = false,
                powerSave = false, screenOffMinutes = 0, movement = 0.5f,
            ),
        )
    }

    @Test
    fun `a weak phone is throttled on a full battery, because battery was never the only cost`() {
        assertEquals(Sensorium.SenseLevel.SETTLED, lvl(tier = DeviceClass.Tier.LEAN, battery = 100, charging = true))
        assertEquals(Sensorium.SenseLevel.CONSERVE, lvl(tier = DeviceClass.Tier.MINIMAL, battery = 100, charging = true))
    }

    @Test
    fun `a phone with room to spare is left alone`() {
        assertEquals(Sensorium.SenseLevel.NOMINAL, lvl(tier = DeviceClass.Tier.MODEST))
        assertEquals(Sensorium.SenseLevel.NOMINAL, lvl(pressure = DeviceClass.Pressure.WARM))
    }

    @Test
    fun `heat throttles and extreme heat stops`() {
        assertEquals(Sensorium.SenseLevel.CONSERVE, lvl(pressure = DeviceClass.Pressure.HOT))
        assertEquals(Sensorium.SenseLevel.STANDDOWN, lvl(pressure = DeviceClass.Pressure.CRITICAL))
    }

    @Test
    fun `the ceiling can never promote a device the battery has already throttled`() {
        // Every combination: a flat battery must stay STANDDOWN whatever the hardware is.
        for (tier in DeviceClass.Tier.entries) {
            for (pressure in DeviceClass.Pressure.entries) {
                assertEquals(
                    "$tier/$pressure",
                    Sensorium.SenseLevel.STANDDOWN,
                    lvl(tier = tier, pressure = pressure, battery = 5),
                )
            }
        }
    }

    @Test
    fun `no combination of tier and pressure is ever less throttled than the battery alone`() {
        // ⚠️ The floors are LITERAL, not another call to `lvl`. The first version of this test
        // compared the function against itself, so replacing the aggregation with `minBy` broke both
        // sides identically and the test passed against a ladder that promoted every device to
        // NOMINAL — the assertion was too weak to see the damage it was written to catch.
        val batteryAlone = mapOf(
            5 to Sensorium.SenseLevel.STANDDOWN,   // at or below the stand-down threshold
            20 to Sensorium.SenseLevel.CONSERVE,   // at or below the conserve threshold
            27 to Sensorium.SenseLevel.NOMINAL,    // above conserve, and not already throttled
            50 to Sensorium.SenseLevel.NOMINAL,
            100 to Sensorium.SenseLevel.NOMINAL,
        )
        for ((battery, floor) in batteryAlone) {
            assertEquals("the floor itself, at $battery%", floor, lvl(battery = battery))
            for (tier in DeviceClass.Tier.entries) {
                for (pressure in DeviceClass.Pressure.entries) {
                    assertTrue(
                        "$battery%/$tier/$pressure",
                        lvl(tier = tier, pressure = pressure, battery = battery).ordinal >= floor.ordinal,
                    )
                }
            }
        }
    }

    // ---- one battery answer, not two -----------------------------------------------------------

    @Test
    fun `conserving has hysteresis and a charger always ends it`() {
        assertTrue(Sensorium.conserveBattery(5, charging = false, previouslyConserving = false))
        assertFalse(Sensorium.conserveBattery(5, charging = true, previouslyConserving = true))
        // Recovering: still conserving through the band, released above it.
        assertTrue(Sensorium.conserveBattery(20, charging = false, previouslyConserving = true))
        assertFalse(Sensorium.conserveBattery(20, charging = false, previouslyConserving = false))
        assertFalse(Sensorium.conserveBattery(40, charging = false, previouslyConserving = true))
    }

    @Test
    fun `the user's own stand-down percentage is honoured and cannot be set absurdly`() {
        assertEquals(
            Sensorium.SenseLevel.STANDDOWN,
            Sensorium.level(
                previous = Sensorium.SenseLevel.NOMINAL, batteryPct = 18, charging = false,
                powerSave = false, screenOffMinutes = 0, movement = 0.5f, standDownPct = 20,
            ),
        )
        // A setting of zero would mean "never stand down", which is not a choice this offers.
        assertTrue(Sensorium.conserveBattery(1, charging = false, previouslyConserving = false, standDownPct = 0))
    }

    // ---- the notification says why -------------------------------------------------------------

    @Test
    fun `every throttled level can account for itself`() {
        // The whole point: "Conserving battery" used to be said whatever the cause.
        assertEquals(
            "the phone is warm",
            Sensorium.reasonFor(
                Sensorium.SenseLevel.CONSERVE, DeviceClass.Tier.FULL, DeviceClass.Pressure.HOT,
                batteryPct = 90, charging = false, powerSave = false,
            ),
        )
        assertEquals(
            "battery is at 12%",
            Sensorium.reasonFor(
                Sensorium.SenseLevel.CONSERVE, DeviceClass.Tier.FULL, DeviceClass.Pressure.NONE,
                batteryPct = 12, charging = false, powerSave = false,
            ),
        )
        assertEquals(
            "this phone has little to spare",
            Sensorium.reasonFor(
                Sensorium.SenseLevel.CONSERVE, DeviceClass.Tier.MINIMAL, DeviceClass.Pressure.NONE,
                batteryPct = 90, charging = true, powerSave = false,
            ),
        )
    }

    @Test
    fun `nothing throttled is ever left without an explanation`() {
        // ⚠️ Driven THROUGH the ladder rather than over every (level, battery) pair. My first
        // version asserted the universal and failed on "CONSERVE at 30%" — a state the ladder cannot
        // produce, because 30 is the recovery threshold and a healthy phone there is NOMINAL. The
        // property worth holding is that every state the ladder REALLY reaches can account for
        // itself, which is both true and stronger than the version that was wrong.
        for (battery in listOf(-1) + (1..100).toList()) {
            for (charging in listOf(false, true)) {
                for (powerSave in listOf(false, true)) {
                    for (tier in DeviceClass.Tier.entries) {
                        for (pressure in DeviceClass.Pressure.entries) {
                            for (screenOff in listOf(0, 60)) {
                                for (previous in Sensorium.SenseLevel.entries) {
                                    val level = Sensorium.level(
                                        previous = previous, batteryPct = battery, charging = charging,
                                        powerSave = powerSave, screenOffMinutes = screenOff,
                                        movement = 0.0f, tier = tier, pressure = pressure,
                                    )
                                    if (level == Sensorium.SenseLevel.NOMINAL) continue
                                    val why = Sensorium.reasonFor(
                                        level, tier, pressure, battery, charging, powerSave,
                                    )
                                    assertTrue(
                                        "$level from $battery% charging=$charging saver=$powerSave " +
                                            "$tier/$pressure screenOff=$screenOff prev=$previous",
                                        why != null && why.isNotBlank(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `an unreadable battery does not stand the whole stack down for ever`() {
        // ⚠️ A live defect before this: `DeviceContext.batteryPct` is -1 when the gauge cannot be
        // read, and the ladder compared it straight against the stand-down threshold — so a phone
        // with a broken gauge sampled nothing, permanently, and said the battery was flat.
        assertEquals(
            Sensorium.SenseLevel.NOMINAL,
            Sensorium.level(
                previous = Sensorium.SenseLevel.NOMINAL, batteryPct = -1, charging = false,
                powerSave = false, screenOffMinutes = 0, movement = 0.5f,
            ),
        )
        // It also asserts nothing in the other direction: a phone already conserving stays there,
        // because "unknown" is not evidence that the battery has recovered either.
        assertEquals(
            Sensorium.SenseLevel.CONSERVE,
            Sensorium.level(
                previous = Sensorium.SenseLevel.CONSERVE, batteryPct = -1, charging = false,
                powerSave = false, screenOffMinutes = 0, movement = 0.5f,
            ),
        )
        assertFalse(Sensorium.conserveBattery(-1, charging = false, previouslyConserving = false))
        assertTrue(Sensorium.conserveBattery(-1, charging = false, previouslyConserving = true))
    }

    @Test
    fun `a healthy phone at full sampling has nothing to explain`() {
        assertEquals(
            null,
            Sensorium.reasonFor(
                Sensorium.SenseLevel.NOMINAL, DeviceClass.Tier.FULL, DeviceClass.Pressure.NONE,
                batteryPct = 90, charging = false, powerSave = false,
            ),
        )
    }

    // --- a phone with no ambient-light sensor -------------------------------------------------
    //
    // ⚠️ Many phones ship without one, and this used to be reported as DIM: a fabricated reading
    // that reached the scanner, the Computer's per-turn environment line and ORACLE's rules.

    @Test
    fun noLightReadingIsUnknownAndNotDim() {
        assertEquals(LightState.UNKNOWN, Sensorium.distill(SenseFrame()).light)
    }

    @Test
    fun anUnknownBrightnessIsLeftOutOfTheSpokenLineEntirely() {
        val line = Sensorium.distill(SenseFrame(movement = 0.01f)).describe()
        // Not "dim" (the old lie), not "unknown" (reads as a fault) — simply absent.
        assertFalse(line.contains("dim"))
        assertFalse(line.lowercase().contains("unknown"))
        // The facets that WERE measured still speak.
        assertTrue(line.contains("still"))
        assertTrue(line.contains("alone"))
    }

    @Test
    fun aRealLuxReadingStillNamesEveryBand() {
        // The bands either side of each threshold, so UNKNOWN cannot have displaced one of them.
        assertEquals(LightState.DARK, Sensorium.distill(SenseFrame(lightLux = 0.5f)).light)
        assertEquals(LightState.DIM, Sensorium.distill(SenseFrame(lightLux = 20f)).light)
        assertEquals(LightState.LIT, Sensorium.distill(SenseFrame(lightLux = 120f)).light)
        assertEquals(LightState.BRIGHT, Sensorium.distill(SenseFrame(lightLux = 2000f)).light)
        assertEquals(LightState.SUNLIGHT, Sensorium.distill(SenseFrame(lightLux = 20000f)).light)
    }

    @Test
    fun aDefaultReadingClaimsNoBrightnessItNeverMeasured() {
        assertEquals(LightState.UNKNOWN, EnvReading().light)
    }
}
