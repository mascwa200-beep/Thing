package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ Every expected score in here is added up from the shipped rule before the assertion is written,
 * and the arithmetic is in the comment beside it. That habit has caught about twenty mistakes of mine
 * across this project where the code turned out to be right and my recollection did not.
 *
 * The scores, for reference — MIN_SCORE is 4 and CONFIDENT is 6:
 *
 *   driving      vehicle speed 4 · sounds like a vehicle 3 · Bluetooth 1
 *   walking      walking pace 4 · away 1 · outdoors 1
 *   pocketed     covered 2 · screen off 3 · moving 1
 *   asleep       night + untouched 2 · dark 2 · Doze 2 · still 1 · charging 1
 *   atDesk       in use and uncovered 4 · settled 2
 *   inAMeeting   calendar 3 · people 2 · silenced 1 · still 1
 *   inCompany    crowd 4 / few 3 · lively 1 · away 1
 *   atHomeIdle   home network 2 · put down 2 · still 1
 *   outAndAbout  not home 3 · walking 1 · outdoors 1 · people 1
 */
class AmbientSituationTest {

    private val noon = 12
    private val night = 2
    private val now = 1_700_000_000_000L

    private fun read(
        env: EnvReading = EnvReading(),
        phone: SenseContext = SenseContext(),
        hour: Int = noon,
        prev: SituationRead? = null,
        at: Long = now,
    ) = AmbientSituation.read(env, phone, hour, at, prev)

    // ---- refusing to guess ----

    @Test
    fun `a phone that has sensed nothing claims nothing`() {
        val r = read()
        assertEquals(Situation.UNKNOWN, r.situation)
        assertEquals(0f, r.confidence, 0f)
        assertTrue(r.evidence.isEmpty())
    }

    @Test
    fun `one weak signal is not a situation`() {
        // A cabin that sounds like a vehicle scores 3, one short of MIN_SCORE. Nothing else fires,
        // so the honest answer is that we do not know.
        val r = read(EnvReading(setting = EnvSetting.VEHICLE))
        assertEquals(Situation.UNKNOWN, r.situation)
    }

    @Test
    fun `few voices alone are not company`() {
        // inCompany scores 3 for FEW; quiet and at an unknown place adds nothing. 3 < 4.
        val r = read(EnvReading(social = SocialDensity.FEW))
        assertEquals(Situation.UNKNOWN, r.situation)
    }

    // ---- driving ----

    @Test
    fun `a measured vehicle speed is enough on its own`() {
        // 4, exactly MIN_SCORE. 4/6 = 0.667.
        val r = read(EnvReading(motion = MotionState.DRIVING))
        assertEquals(Situation.DRIVING, r.situation)
        assertEquals(0.667f, r.confidence, 0.01f)
        assertEquals(listOf("moving at vehicle speed"), r.evidence)
    }

    @Test
    fun `speed plus a cabin plus Bluetooth is as sure as it gets`() {
        // 4 + 3 + 1 = 8, capped at 1.0 by CONFIDENT = 6.
        val r = read(
            EnvReading(motion = MotionState.DRIVING, setting = EnvSetting.VEHICLE),
            SenseContext(route = AudioRoute.BLUETOOTH),
        )
        assertEquals(Situation.DRIVING, r.situation)
        assertEquals(1f, r.confidence, 0f)
        assertEquals(3, r.evidence.size)
    }

    @Test
    fun `Bluetooth alone never puts anybody in a car`() {
        // ⚠️ The route contributes only once something else already said vehicle. A car stereo, a
        // pair of earbuds and a kitchen speaker are one device type to Android, so on its own this
        // would call washing up a drive.
        val r = read(phone = SenseContext(route = AudioRoute.BLUETOOTH))
        assertEquals(Situation.UNKNOWN, r.situation)
        assertNotEquals(Situation.DRIVING, r.situation)
    }

    // ---- walking and being put away ----

    @Test
    fun `a measured walking pace is a claim in its own right`() {
        val r = read(EnvReading(motion = MotionState.WALKING))
        assertEquals(Situation.WALKING, r.situation)
    }

    @Test
    fun `a phone face-down and dark is put away`() {
        // covered 2 + screen off 3 = 5. Nothing else fires: atDesk refuses on covered, and the
        // home/away rules need a network answer.
        val r = read(EnvReading(covered = true), SenseContext(screenOn = false))
        assertEquals(Situation.POCKETED, r.situation)
        assertEquals(0.833f, r.confidence, 0.01f)
    }

    @Test
    fun `a covered phone with the screen on is somebody holding it to their ear`() {
        // covered 2 alone; the screen-off term is what carries pocketed past the bar.
        //
        // ⚠️ `locked = false` is load-bearing and was added after a negative test. Without it
        // attention() is PRESENT rather than IN_USE, so atDesk refuses on ATTENTION and its own
        // `covered` guard is never reached — deleting that guard broke no test. Unlocked, atDesk
        // would otherwise score 4 + settled 2 = 6 and report somebody mid-call as sitting at a desk,
        // and the only thing stopping it is the line this fixture now exercises.
        val r = read(EnvReading(covered = true), SenseContext(screenOn = true, locked = false))
        assertEquals(Situation.UNKNOWN, r.situation)
    }

    @Test
    fun `an uncovered phone is never reported as put away`() {
        // ⚠️ The precondition is checked before the base score, so without it an uncovered phone
        // gets "something against the front of the phone" as EVIDENCE — a false statement about a
        // sensor reading, which is worse than a wrong situation name. Walking with it in your hand,
        // screen off: pocketed would otherwise score 2 + 3 + 1 = 6 and beat walking's 4.
        val r = read(EnvReading(motion = MotionState.WALKING), SenseContext(screenOn = false))
        assertEquals(Situation.WALKING, r.situation)
        assertTrue("no claim about the front sensors in $r", r.evidence.none { "front" in it })
    }

    @Test
    fun `walking with it in a pocket reports the pocket`() {
        // pocketed 2 + 3 + 1 = 6 against walking 4. Two points clear, so no contender is named —
        // the evidence really is stronger, and a person would say the same.
        val r = read(
            EnvReading(motion = MotionState.WALKING, covered = true),
            SenseContext(screenOn = false),
        )
        assertEquals(Situation.POCKETED, r.situation)
        assertTrue(r.contenders.isEmpty())
    }

    @Test
    fun `when the two are level the other one is named`() {
        // walking 4 + away 1 + outdoors 1 = 6; pocketed 2 + 3 + 1 = 6. A genuine tie.
        val r = read(
            EnvReading(motion = MotionState.WALKING, setting = EnvSetting.OUTDOOR, covered = true),
            SenseContext(screenOn = false, awayFromHome = true),
        )
        assertTrue(r.situation == Situation.WALKING || r.situation == Situation.POCKETED)
        assertTrue(
            "expected the other of the pair in $r",
            r.contenders.contains(Situation.WALKING) || r.contenders.contains(Situation.POCKETED),
        )
    }

    // ---- asleep ----

    @Test
    fun `dark, still, charging and dozing in the small hours is asleep`() {
        // 2 + dark 2 + Doze 2 + still 1 + charging 1 = 8.
        val r = read(
            EnvReading(light = LightState.DARK, motion = MotionState.STILL),
            SenseContext(screenOn = false, idle = true, charging = true),
            hour = night,
        )
        assertEquals(Situation.ASLEEP, r.situation)
        assertEquals(1f, r.confidence, 0f)
    }

    @Test
    fun `the very same evidence at noon is not asleep`() {
        // ⚠️ The limitation, pinned. A dark, still, charging, dozing phone is indistinguishable from
        // one in a drawer, so outside the small hours nothing here will call it sleep — and with no
        // network answer there is nothing else to call it either.
        val r = read(
            EnvReading(light = LightState.DARK, motion = MotionState.STILL),
            SenseContext(screenOn = false, idle = true, charging = true),
            hour = noon,
        )
        assertEquals(Situation.UNKNOWN, r.situation)
    }

    @Test
    fun `a phone being used at three in the morning is not asleep`() {
        // The attention gate, not the clock: somebody is plainly awake.
        val r = read(
            EnvReading(motion = MotionState.STILL),
            SenseContext(screenOn = true, locked = false),
            hour = night,
        )
        assertEquals(Situation.AT_DESK, r.situation)
    }

    @Test
    fun `a glance at a lit locked screen in the night is not asleep either`() {
        // ⚠️ This is the case that actually holds the attention gate, and it took a negative test to
        // find that out: in the test above atDesk scores 6 and asleep-without-its-gate also scores
        // 6, so removing the gate changed nothing there — the fixture never reached the branch.
        //
        // Here attention() is PRESENT, so atDesk refuses (it needs IN_USE) and NOTHING else fires.
        // Asleep without its gate would score 2 + dark 2 + still 1 + charging 1 = 6 and stand alone,
        // so the gate is the only thing between this reading and a false "asleep". Deliberately no
        // tie, so the answer cannot turn on how a sort breaks one.
        //
        // It is also the real situation: a phone charging in a dark bedroom at two in the morning,
        // screen lit and still locked. Somebody looked at a notification. We do not know more.
        val r = read(
            EnvReading(light = LightState.DARK, motion = MotionState.STILL),
            SenseContext(screenOn = true, locked = true, charging = true),
            hour = night,
        )
        assertEquals(Situation.UNKNOWN, r.situation)
    }

    // ---- using it ----

    @Test
    fun `a lit unlocked screen while settled is somebody using the phone`() {
        // 4 + settled 2 = 6.
        val r = read(EnvReading(motion = MotionState.STILL), SenseContext(screenOn = true, locked = false))
        assertEquals(Situation.AT_DESK, r.situation)
        assertEquals(1f, r.confidence, 0f)
    }

    @Test
    fun `a screen that is lit but locked is not somebody using the phone`() {
        // attention() is PRESENT, not IN_USE — a glance at a notification.
        val r = read(EnvReading(motion = MotionState.STILL), SenseContext(screenOn = true, locked = true))
        assertNotEquals(Situation.AT_DESK, r.situation)
    }

    // ---- meetings and company ----

    @Test
    fun `voices and a silenced phone are not a meeting without the calendar`() {
        // ⚠️ The rule that was considered and kept. This is also a quiet cafe, a waiting room and a
        // train, and asserting a meeting wrongly holds back notifications somebody wanted.
        // inCompany scores FEW 3 and stops there.
        val r = read(
            EnvReading(social = SocialDensity.FEW, motion = MotionState.STILL),
            SenseContext(ringer = RingerState.SILENT),
        )
        assertEquals(Situation.UNKNOWN, r.situation)
    }

    @Test
    fun `the same room with something in the diary is a meeting`() {
        // 3 + people 2 + silenced 1 + still 1 = 7.
        val r = read(
            EnvReading(social = SocialDensity.FEW, motion = MotionState.STILL),
            SenseContext(ringer = RingerState.SILENT, calendarBusy = true),
        )
        assertEquals(Situation.IN_A_MEETING, r.situation)
        assertTrue(r.evidence.first().contains("calendar"))
    }

    @Test
    fun `a crowd is company on its own`() {
        val r = read(EnvReading(social = SocialDensity.CROWD))
        assertEquals(Situation.IN_COMPANY, r.situation)
    }

    // ---- home and away ----

    @Test
    fun `home requires a positive answer about the network`() {
        // ⚠️ awayFromHome is null both when no home network is configured and when the SSID cannot
        // be read. Treating either as being at home would put somebody's house on a reading taken in
        // a car park.
        val quiet = EnvReading(motion = MotionState.STILL)
        assertEquals(Situation.UNKNOWN, read(quiet, SenseContext(screenOn = false)).situation)
        // home 2 + put down 2 + still 1 = 5.
        assertEquals(
            Situation.AT_HOME_IDLE,
            read(quiet, SenseContext(screenOn = false, awayFromHome = false)).situation,
        )
    }

    @Test
    fun `out and about also requires a positive answer`() {
        // With no answer about the network the rule does not fire at all; walking carries the read.
        // walking 4 + outdoors 1 = 5.
        val out = EnvReading(motion = MotionState.WALKING, setting = EnvSetting.OUTDOOR)
        val unsaid = read(out, SenseContext())
        assertEquals(Situation.WALKING, unsaid.situation)
        assertEquals(0.833f, unsaid.confidence, 0.01f)

        // ⚠️ That first case is a weak guard on its own and a negative test showed it: let null
        // through and out-and-about scores 3 + walking 1 + outdoors 1 = 5, a TIE with walking, so it
        // is decided by which rule is declared first rather than by the precondition under test.
        //
        // Outdoors and still is the tie-free case — nothing else fires at all, so admitting null
        // would put somebody on "a network that is not home" on a reading that never saw a network.
        // It is the exact mirror of `out and about is reachable` below, one answer apart.
        assertEquals(Situation.UNKNOWN, read(EnvReading(setting = EnvSetting.OUTDOOR)).situation)
    }

    @Test
    fun `out and about is reachable, and that took a correction`() {
        // ⚠️ My arithmetic in the first draft of the test above was wrong and the code was right —
        // I forgot walking's own outdoors term — and chasing that turned up something worse: at a
        // base of 2 this situation could never win anything. See outAndAbout's own note.
        //
        // not home 3 + outdoors 1 = 4. Nothing sharper fires: still, alone, no screen answer.
        val r = read(EnvReading(setting = EnvSetting.OUTDOOR), SenseContext(awayFromHome = true))
        assertEquals(Situation.OUT_AND_ABOUT, r.situation)
    }

    @Test
    fun `how you are moving beats merely being away`() {
        // walking 4 + away 1 + outdoors 1 = 6 against out-and-about 3 + walking 1 + outdoors 1 = 5.
        val r = read(
            EnvReading(motion = MotionState.WALKING, setting = EnvSetting.OUTDOOR),
            SenseContext(awayFromHome = true),
        )
        assertEquals(Situation.WALKING, r.situation)
        assertTrue(r.contenders.contains(Situation.OUT_AND_ABOUT))
    }

    // ---- hysteresis ----

    @Test
    fun `a level reading goes to whoever held it`() {
        // inCompany CROWD 4 + lively 1 = 5; atHomeIdle home 2 + put down 2 + still 1 = 5.
        val env = EnvReading(
            social = SocialDensity.CROWD, noise = NoiseProfile.LIVELY, motion = MotionState.STILL,
        )
        val phone = SenseContext(screenOn = false, awayFromHome = false)

        val fresh = read(env, phone)
        val held = read(env, phone, prev = SituationRead(situation = Situation.AT_HOME_IDLE, sinceMs = 1L))
        assertEquals(Situation.IN_COMPANY, fresh.situation)
        assertEquals(Situation.AT_HOME_IDLE, held.situation)
    }

    @Test
    fun `holding a situation does not make it look better evidenced`() {
        // ⚠️ The bonus ranks and never scores. atHomeIdle is 5, so confidence is 5/6 whether or not
        // it was the incumbent — otherwise a situation would grow more confident by persisting.
        val env = EnvReading(
            social = SocialDensity.CROWD, noise = NoiseProfile.LIVELY, motion = MotionState.STILL,
        )
        val phone = SenseContext(screenOn = false, awayFromHome = false)
        val held = read(env, phone, prev = SituationRead(situation = Situation.AT_HOME_IDLE, sinceMs = 1L))
        assertEquals(0.833f, held.confidence, 0.01f)
    }

    @Test
    fun `hysteresis cannot hold a situation whose reasons have gone`() {
        // Driving, previously; now the phone is sitting still with nothing to say. One point of
        // incumbency must not keep DRIVING alive.
        val r = read(prev = SituationRead(situation = Situation.DRIVING, sinceMs = 1L))
        assertEquals(Situation.UNKNOWN, r.situation)
    }

    @Test
    fun `the bar is checked against the raw score, not the ranked one`() {
        // ⚠️ The case above proves less than it looks and a negative test said so: with nothing
        // sensed NO candidate is built at all, so `read` returns at the empty-list line and the
        // MIN_SCORE comparison is never reached. Moving the bonus into that comparison broke no
        // test — the fixture never reached the branch.
        //
        // A cabin that sounds like a vehicle scores 3, one short of the bar, so this reaches the
        // comparison with a live candidate. Ranked it would be 3 + 1 = 4 and DRIVING would be
        // reported off one point of having been true a moment ago.
        val vehicle = EnvReading(setting = EnvSetting.VEHICLE)
        assertEquals(Situation.UNKNOWN, read(vehicle).situation)
        assertEquals(
            Situation.UNKNOWN,
            read(vehicle, prev = SituationRead(situation = Situation.DRIVING, sinceMs = 1L)).situation,
        )
    }

    // ---- the clock ----

    @Test
    fun `an unchanged situation keeps the time it started`() {
        val env = EnvReading(motion = MotionState.DRIVING)
        val first = read(env, at = 1000L)
        val later = read(env, at = 9000L, prev = first)
        assertEquals(1000L, first.sinceMs)
        assertEquals(1000L, later.sinceMs)
    }

    @Test
    fun `a change of situation restarts the clock`() {
        val first = read(EnvReading(motion = MotionState.DRIVING), at = 1000L)
        val later = read(EnvReading(motion = MotionState.WALKING), at = 9000L, prev = first)
        assertEquals(Situation.WALKING, later.situation)
        assertEquals(9000L, later.sinceMs)
    }

    @Test
    fun `not knowing restarts the clock rather than inheriting it`() {
        // ⚠️ "We have not known what you were doing since nine this morning" is true and useless.
        // What a caller wants is how long the CURRENT answer has held.
        val driving = read(EnvReading(motion = MotionState.DRIVING), at = 1000L)
        val lost = read(at = 9000L, prev = driving)
        assertEquals(Situation.UNKNOWN, lost.situation)
        assertEquals(9000L, lost.sinceMs)
        // ...and once it is the answer, it accumulates like any other.
        val stillLost = read(at = 12_000L, prev = lost)
        assertEquals(9000L, stillLost.sinceMs)
    }

    // ---- the contract every claim keeps ----

    /**
     * ⚠️ **Every situation must be REACHABLE, and this is the test that would have caught the
     * OUT_AND_ABOUT defect** — at a base of 2 that value could be named as a contender and could
     * never be the answer, which is the computed-and-never-used defect wearing a scoring table. A
     * scoring rule can be made unreachable by a change to any OTHER rule's numbers, so nothing
     * local to the rule itself would show it.
     *
     * The keys are checked against the enum rather than hand-counted, so adding a [Situation] with
     * no fixture fails here instead of shipping a value nothing can produce.
     */
    @Test
    fun `every situation is reachable, with a reason and a confidence`() {
        val fixtures = mapOf(
            Situation.DRIVING to read(EnvReading(motion = MotionState.DRIVING)),
            Situation.WALKING to read(EnvReading(motion = MotionState.WALKING)),
            Situation.POCKETED to read(EnvReading(covered = true), SenseContext(screenOn = false)),
            Situation.ASLEEP to read(
                EnvReading(light = LightState.DARK),
                SenseContext(screenOn = false, idle = true, charging = true), hour = night,
            ),
            Situation.AT_DESK to read(EnvReading(), SenseContext(screenOn = true, locked = false)),
            Situation.IN_A_MEETING to
                read(EnvReading(social = SocialDensity.FEW), SenseContext(calendarBusy = true)),
            Situation.IN_COMPANY to read(EnvReading(social = SocialDensity.CROWD)),
            Situation.AT_HOME_IDLE to read(
                EnvReading(motion = MotionState.STILL),
                SenseContext(screenOn = false, awayFromHome = false),
            ),
            Situation.OUT_AND_ABOUT to
                read(EnvReading(setting = EnvSetting.OUTDOOR), SenseContext(awayFromHome = true)),
        )
        assertEquals(
            "every situation but UNKNOWN needs a fixture that produces it",
            Situation.entries.toSet() - Situation.UNKNOWN,
            fixtures.keys,
        )
        for ((want, r) in fixtures) {
            assertEquals("the fixture for $want produced something else", want, r.situation)
            assertTrue("no evidence for $want", r.evidence.isNotEmpty())
            assertTrue("confidence out of range for $want", r.confidence in 0f..1f)
        }
    }

    @Test
    fun `the line names the situation and its reasons`() {
        val r = read(EnvReading(motion = MotionState.DRIVING), SenseContext(route = AudioRoute.BLUETOOTH))
        assertEquals("Driving · moving at vehicle speed, audio over Bluetooth", r.describe())
        assertEquals("Not sure", SituationRead().describe())
    }
}
