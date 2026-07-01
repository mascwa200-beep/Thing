package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerceptionTest {

    private fun scene(vararg labels: String) = labels.map { PerceptLabel(it, 0.8f) }
    private fun sound(vararg labels: String) = labels.map { PerceptLabel(it, 0.8f) }

    @Test fun outdoorMovingDaytime() {
        val ctx = Perception.distill(SceneSignals(sceneLabels = scene("city street", "sky"), movement = 0.3f, hourOfDay = 12))
        assertEquals(Setting.OUTDOOR, ctx.setting)
        assertEquals(Activity.MOVING, ctx.activity)
        assertEquals(DayPhase.DAY, ctx.phase)
    }

    @Test fun vehicleFromDashboardAndRoad() {
        val ctx = Perception.distill(SceneSignals(sceneLabels = scene("dashboard", "road"), movement = 0.3f))
        assertEquals(Setting.VEHICLE, ctx.setting)
        assertEquals(Activity.COMMUTING, ctx.activity)
    }

    @Test fun vehicleFromEngineSoundWhileMoving() {
        val ctx = Perception.distill(SceneSignals(soundLabels = sound("engine"), movement = 0.3f))
        assertEquals(Setting.VEHICLE, ctx.setting)
    }

    @Test fun restingPhoneIsStill() {
        // The reported bug: a still phone read as "moving". A tiny movement intensity (noise/bias) is STILL.
        assertEquals(Activity.STILL, Perception.distill(SceneSignals(movement = 0.03f)).activity)
        assertEquals(Activity.STILL, Perception.distill(SceneSignals(movement = 0.07f)).activity) // brief handling
    }

    @Test fun engineSoundWhileStationaryIsNotAVehicle() {
        // Hearing traffic/an engine while at rest must NOT read as "in transit".
        val ctx = Perception.distill(SceneSignals(soundLabels = sound("engine", "traffic"), movement = 0.02f))
        assertEquals(Activity.STILL, ctx.activity)
        assertEquals(false, ctx.setting == Setting.VEHICLE)
    }

    @Test fun indoorWithVoices() {
        val ctx = Perception.distill(SceneSignals(sceneLabels = scene("office", "desk", "monitor"), soundLabels = sound("speech")))
        assertEquals(Setting.INDOOR, ctx.setting)
        assertEquals(Social.VOICES, ctx.social)
        assertEquals(Activity.STILL, ctx.activity)
    }

    @Test fun crowdDetected() {
        val ctx = Perception.distill(SceneSignals(soundLabels = sound("crowd", "chatter")))
        assertEquals(Social.CROWD, ctx.social)
    }

    @Test fun lightLevelsFromLux() {
        assertEquals(LightLevel.DARK, Perception.distill(SceneSignals(lightLux = 5f)).light)
        assertEquals(LightLevel.DIM, Perception.distill(SceneSignals(lightLux = 100f)).light)
        assertEquals(LightLevel.BRIGHT, Perception.distill(SceneSignals(lightLux = 300f)).light)
    }

    @Test fun weakLabelsAreIgnored() {
        val ctx = Perception.distill(SceneSignals(sceneLabels = listOf(PerceptLabel("street", 0.1f))))
        assertEquals(Setting.UNKNOWN, ctx.setting)
        assertTrue(ctx.sceneTags.isEmpty())
    }

    @Test fun emptySignalsAreDefensive() {
        val ctx = Perception.distill(SceneSignals(hourOfDay = 3))
        assertEquals(Setting.UNKNOWN, ctx.setting)
        assertEquals(Activity.STILL, ctx.activity)
        assertEquals(Social.ALONE, ctx.social)
        assertEquals(LightLevel.DIM, ctx.light)
        assertEquals(DayPhase.NIGHT, ctx.phase)
    }

    @Test fun describeReadsCleanly() {
        val ctx = SceneContext(Setting.OUTDOOR, Activity.MOVING, Social.VOICES, LightLevel.DIM, DayPhase.DUSK)
        val d = ctx.describe()
        assertTrue(d.contains("Outdoors"))
        assertTrue(d.contains("on the move"))
        assertTrue(d.contains("voices nearby"))
        assertTrue(d.contains("dusk"))
    }

    @Test fun strategyOutdoorFavoursMobility() {
        val st = Perception.strategy(SceneContext(setting = Setting.OUTDOOR))
        assertTrue(Special.AGILITY in st.favored)
        assertTrue(Special.PERCEPTION in st.favored)
    }

    @Test fun strategyVoicesFavourCharisma() {
        val st = Perception.strategy(SceneContext(social = Social.VOICES))
        assertTrue(Special.CHARISMA in st.favored)
    }

    @Test fun strategyDarkFavoursStealthAndPushesTempo() {
        val st = Perception.strategy(SceneContext(light = LightLevel.DARK))
        assertTrue(Special.PERCEPTION in st.favored)
        assertTrue(Special.AGILITY in st.favored)
        assertEquals(1, st.tempoNudge)
    }

    @Test fun tempoNudgeIsClamped() {
        // Vehicle (+1) + commuting (+1) + dark (+1) → coerced to +1, never above.
        val st = Perception.strategy(SceneContext(setting = Setting.VEHICLE, activity = Activity.COMMUTING, light = LightLevel.DARK))
        assertEquals(1, st.tempoNudge)
    }

    @Test fun emptyContextIsNeutral() {
        val st = Perception.strategy(SceneContext())
        assertTrue(st.favored.isEmpty())
        assertEquals(0, st.tempoNudge)
        assertFalse(st.flavor.isEmpty())
    }

    @Test fun crowdFlavourAndLuck() {
        val st = Perception.strategy(SceneContext(social = Social.CROWD))
        assertTrue(Special.CHARISMA in st.favored)
        assertTrue(Special.LUCK in st.favored)
    }
}
