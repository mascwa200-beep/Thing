package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ Every threshold asserted here is read off the shipped object rather than retyped, so a change
 * to a rule fails the test that names it rather than quietly agreeing with a stale copy.
 */
class AmbientRulesTest {

    private val t0 = 1_700_000_000_000L

    /** A situation that has held long enough and is sure enough for the rules to act on. */
    private fun held(
        what: Situation,
        confidence: Float = 0.8f,
        forMs: Long = AmbientRules.SETTLE_MS,
    ) = SituationRead(situation = what, confidence = confidence, sinceMs = t0, evidence = listOf("x"))
        .let { it to t0 + forMs }

    /**
     * ⚠️ The default is a phone nothing has been measured about, NOT a phone in a normal state.
     * Defaulting the ringer to NORMAL would make every test that does not care about it silently
     * depend on it, and would hide the gate in `meeting` from every site that relies on it. A test
     * expecting [AmbientAction.SILENCE_RINGER] has to say so.
     */
    private val ringing = SenseContext(ringer = RingerState.NORMAL)

    private fun signals(
        what: Situation = Situation.UNKNOWN,
        confidence: Float = 0.8f,
        forMs: Long = AmbientRules.SETTLE_MS,
        env: EnvReading = EnvReading(),
        phone: SenseContext = SenseContext(),
        events: List<SenseEvent> = emptyList(),
    ): AmbientSignals {
        val (read, now) = held(what, confidence, forMs)
        return AmbientSignals(situation = read, env = env, phone = phone, events = events, nowMs = now)
    }

    private fun actions(s: AmbientSignals) = AmbientRules.decide(s).map { it.action }.toSet()

    // ---- the safety model ----

    /**
     * ⚠️ **The load-bearing property of the whole acting layer.** The decision side cannot express
     * arbitrary execution: whatever a rule concludes, the worst it can ask for is a member of this
     * enum. So widening the blast radius means editing the enum, and editing the enum fails here.
     * Same shape, and the same reason, as `RemoteProtocol`'s allowlist test.
     */
    @Test
    fun `the closed list cannot express anything destructive`() {
        val forbidden = listOf(
            "wipe", "erase", "factory", "reset", "delete", "format", "brick",
            "uninstall", "unenroll", "permanent", "emergency", "password", "credential",
        )
        for (a in AmbientAction.entries) {
            val name = a.name.lowercase()
            for (bad in forbidden) {
                assertFalse("$a looks destructive — it contains '$bad'", bad in name)
            }
        }
    }

    @Test
    fun `every action can be described and undone`() {
        // The board line and the HOLDING NOW row are built from these; a blank one is a hold the
        // owner cannot understand or reverse.
        for (a in AmbientAction.entries) {
            assertTrue("$a has no label", a.label.isNotBlank())
            assertTrue("$a has no undo", a.undo.isNotBlank())
        }
    }

    @Test
    fun `every hold expires, and none of them expires so late it stops being a backstop`() {
        // ⚠️ A backstop is only a backstop if it is shorter than the problem. Twelve hours is the
        // ceiling because the longest defensible hold here is a night's sleep; anything longer would
        // mean a stuck rule could serve somebody badly for a whole day without the floor catching it.
        for (a in AmbientAction.entries) {
            assertTrue("$a never expires", a.maxHoldMs > 0L)
            assertTrue("$a holds for longer than a day of being wrong", a.maxHoldMs <= 12 * 60 * 60_000L)
        }
    }

    @Test
    fun `the expiry is derived from the action and cannot be asked for`() {
        val i = AmbientIntent(AmbientAction.TORCH_ON, "because", fromMs = t0)
        assertEquals(t0 + AmbientAction.TORCH_ON.maxHoldMs, i.untilMs)
        // ...and the torch in particular is minutes rather than hours, because it costs a battery.
        assertTrue(AmbientAction.TORCH_ON.maxHoldMs < AmbientAction.HOLD_NON_URGENT.maxHoldMs)
    }

    // ---- nothing acts on a flicker ----

    @Test
    fun `a situation nobody has settled into is not acted on`() {
        // One second short of the settle time. Every other input says DRIVING.
        val s = signals(Situation.DRIVING, forMs = AmbientRules.SETTLE_MS - 1_000L)
        assertFalse(AmbientRules.settled(s))
        assertTrue("acted on a situation that had only just appeared", AmbientRules.decide(s).isEmpty())
    }

    @Test
    fun `the same situation a moment later is acted on`() {
        val s = signals(Situation.DRIVING, forMs = AmbientRules.SETTLE_MS)
        assertTrue(AmbientRules.settled(s))
        assertTrue(AmbientRules.decide(s).isNotEmpty())
    }

    @Test
    fun `a situation with one signal behind it is not acted on`() {
        // Held for an hour, and still only as sure as a single reading makes it.
        val s = signals(Situation.DRIVING, confidence = 0.33f, forMs = 60 * 60_000L)
        assertFalse(AmbientRules.settled(s))
        assertTrue(AmbientRules.decide(s).isEmpty())
    }

    @Test
    fun `not knowing is never acted on`() {
        // ⚠️ UNKNOWN is the commonest answer, so this is the commonest path through the whole layer.
        val s = signals(Situation.UNKNOWN, confidence = 1f, forMs = 24 * 60 * 60_000L)
        assertFalse(AmbientRules.settled(s))
        assertTrue(AmbientRules.decide(s).isEmpty())
    }

    @Test
    fun `a situation with no start time is not acted on`() {
        // sinceMs is zero until something sets it; treating that as "held since the epoch" would act
        // on the very first reading after a restart.
        val s = AmbientSignals(
            situation = SituationRead(situation = Situation.DRIVING, confidence = 1f, sinceMs = 0L),
            nowMs = t0,
        )
        assertFalse(AmbientRules.settled(s))
        assertTrue(AmbientRules.decide(s).isEmpty())
    }

    // ---- safety is the exception, deliberately ----

    private val alarm = SenseEvent(
        SensoriumEvents.KEY_SMOKE_ALARM, "ALARM HEARD", "a smoke/fire/CO alarm is sounding nearby",
        EventSeverity.ALERT,
    )

    @Test
    fun `an alarm is acted on immediately, with nothing else known`() {
        // ⚠️ No situation, no confidence, no settle time — an alarm that has to sound for ninety
        // seconds before the phone reacts is a phone that reacted too late. What protects against a
        // false positive here is SensoriumEvents.ALERT_MIN_CONF, applied where the sound is
        // recognised, so waiting again would pay that cost twice for nothing.
        // ⚠️ DARK on purpose. The torch is the only physical response the safety rule still has
        // and it is gated on darkness, so a default-lit fixture would assert over an empty list and
        // prove nothing — the "fixture never reached the branch" shape this repository keeps finding.
        val s = AmbientSignals(
            env = EnvReading(light = LightState.DARK), events = listOf(alarm), nowMs = t0,
        )
        assertFalse("the fixture must be unsettled or this proves nothing", AmbientRules.settled(s))
        val out = AmbientRules.decide(s)
        assertTrue("the safety rule produced nothing at all", out.isNotEmpty())
        assertTrue(AmbientAction.TORCH_ON in out.map { it.action })
        assertTrue("an alarm is not routine", out.all { it.urgency == ActionUrgency.RED })
        assertTrue("the reason must say what was heard", out.all { alarm.detail in it.reason })
    }

    @Test
    fun `a merely notable sound is not an alarm`() {
        // ⚠️ DARK, for the same reason the alarm test above is — and this one was WRITTEN without it
        // and was a weak test as a result. The safety rule's only physical response is the torch and
        // the torch is gated on darkness, so a default-lit fixture returns an empty list whether or
        // not a notable sound reaches the rule: it would have passed against a rule that let
        // EVERYTHING through. The negative-test harness is what surfaced that — the perturbation
        // that admits every event failed a different test and left this one green.
        val doorbell = SenseEvent("sound.doorbell", "DOORBELL", "a doorbell", EventSeverity.NOTABLE)
        val dark = EnvReading(light = LightState.DARK)
        assertTrue(
            AmbientRules.decide(AmbientSignals(env = dark, events = listOf(doorbell), nowMs = t0)).isEmpty(),
        )
    }

    @Test
    fun `breaking glass is never answered by lighting the phone up`() {
        // ⚠️ SensoriumEvents raises three ALERTs — a fire alarm, breaking glass and a gunshot-like
        // sound — and only the first of them wants a loud, lit phone. For the other two the same
        // response could be exactly wrong: raising the volume and lighting a torch while somebody is
        // hiding from whoever broke the window makes their situation worse.
        //
        // Nothing is lost by this rule saying nothing. SensoriumEngine already raises an urgent
        // board line for EVERY alert, so glass and gunshots are still announced; what is decided
        // here is only the physical response, and for those two the safe one is none.
        val glass = SenseEvent(
            "sound.glass", "GLASS BREAKING", "the sound of breaking glass was heard",
            EventSeverity.ALERT,
        )
        val gunshot = SenseEvent(
            "sound.gunshot", "GUNSHOT-LIKE SOUND", "a gunshot-like sound was heard",
            EventSeverity.ALERT,
        )
        val dark = EnvReading(light = LightState.DARK)
        assertTrue(AmbientRules.decide(AmbientSignals(env = dark, events = listOf(glass), nowMs = t0)).isEmpty())
        assertTrue(AmbientRules.decide(AmbientSignals(env = dark, events = listOf(gunshot), nowMs = t0)).isEmpty())
    }

    @Test
    fun `the torch comes on for an alarm in the dark and not otherwise`() {
        // ⚠️ A torch in daylight is a flat battery and nothing else.
        val dark = AmbientSignals(
            env = EnvReading(light = LightState.DARK), events = listOf(alarm), nowMs = t0,
        )
        val lit = AmbientSignals(
            env = EnvReading(light = LightState.BRIGHT), events = listOf(alarm), nowMs = t0,
        )
        assertTrue(AmbientAction.TORCH_ON in AmbientRules.decide(dark).map { it.action })
        assertFalse(AmbientAction.TORCH_ON in AmbientRules.decide(lit).map { it.action })
    }

    @Test
    fun `an alarm while driving is acted on as well as the driving`() {
        val s = signals(
            Situation.DRIVING, env = EnvReading(light = LightState.DARK), events = listOf(alarm),
        )
        val out = actions(s)
        assertTrue(AmbientAction.TORCH_ON in out)
        assertTrue(AmbientAction.HOLD_NON_URGENT in out)
    }

    // ---- what each situation asks for ----

    @Test
    fun `driving quietens the phone and leaves the audio alone`() {
        // ⚠️ Somebody driving is listening to something, and stopping it would be an actively
        // dangerous distraction. Video is the exception because video wants eyes.
        val out = actions(signals(Situation.DRIVING))
        assertEquals(
            setOf(
                AmbientAction.HOLD_NON_URGENT,
                AmbientAction.SPEAK_DONT_BUZZ,
                AmbientAction.PAUSE_VIDEO,
                AmbientAction.SUSPEND_DISTRACTING_APPS,
            ),
            out,
        )
    }

    /**
     * ⚠️ **The only rule in the file that reaches the device-owner tier, so this is the only place
     * the ladder can be tested against a real rule rather than a hand-built intent.** What it holds
     * is that asking costs nothing: on a phone where the owner switch is off, the other three
     * driving holds still apply and the fourth is refused with a sentence — which is the whole
     * reason the rules say what they WANT and [AmbientRules.permit] says what they may have.
     */
    @Test
    fun `driving is the one rule that reaches the owner tier, and it is refused until that tier is on`() {
        val wanted = AmbientRules.decide(signals(Situation.DRIVING))

        val off = AmbientRules.permit(wanted, ActionTier.PHONE)
        assertEquals(
            listOf(AmbientAction.SUSPEND_DISTRACTING_APPS),
            off.refused.map { it.intent.action },
        )
        assertTrue("a refusal with no sentence is indistinguishable from a fault",
            off.refused.single().why.isNotBlank())
        // ⚠️ The point of the refusal: the rest of the rule is unaffected. A tier gate that took
        // the whole situation's holds down with it would make turning the owner tier off cost far
        // more than the one action it governs.
        assertEquals(3, off.allowed.size)

        val on = AmbientRules.permit(wanted, ActionTier.OWNER)
        assertTrue(on.refused.isEmpty())
        assertTrue(AmbientAction.SUSPEND_DISTRACTING_APPS in on.allowed.map { it.action })
    }

    @Test
    fun `being put away stops the camera rather than slowing it`() {
        // A phone in a pocket photographs the inside of a pocket: every sip is a lens opened and an
        // indicator lit for a reading that could only ever say "dark". Sound still carries, so the
        // rate drops rather than the sensing stopping.
        val out = actions(signals(Situation.POCKETED))
        assertEquals(setOf(AmbientAction.STOP_CAMERA_SIPS, AmbientAction.LOWER_SENSE_RATE), out)
    }

    @Test
    fun `asleep spends less without ever switching the ears off`() {
        // ⚠️ The first draft of this rule asked to stand the sensors down, which reads as thrift and
        // is not: Sensorium.cadenceFor(STANDDOWN) sets micIntervalSec to 0, documented as OFF, and a
        // smoke alarm is a thing this app hears. That would have deafened the phone every night, for
        // hours, in the one stretch where nobody else is listening either.
        val out = actions(signals(Situation.ASLEEP))
        assertTrue("the rate should drop", AmbientAction.LOWER_SENSE_RATE in out)
        assertTrue("the camera should stop", AmbientAction.STOP_CAMERA_SIPS in out)
        assertTrue(AmbientAction.STAY_SILENT in out)
        assertTrue(AmbientAction.HOLD_NON_URGENT in out)
    }

    @Test
    fun `nothing in the list can switch the ears off`() {
        // ⚠️ Held by ABSENCE rather than by a rule declining to ask: there is no such action, so no
        // rule can reach for one however it is written later. Slowing is in reach (CONSERVE still
        // sips every few minutes, and a real alarm sounds for minutes), and stopping the CAMERA is
        // in reach, because a lens in a dark bedroom reads nothing worth the privacy indicator.
        val deafening = listOf(
            "stand_sensing_down", "stop_sensing", "sensors_off", "mute_mic", "stop_listening",
            "silence_mic", "ears_off",
        )
        for (a in AmbientAction.entries) {
            val name = a.name.lowercase()
            for (bad in deafening) {
                assertFalse("$a would stop the microphone", bad in name)
            }
        }
    }

    /**
     * ⚠️ **The property is now held by ABSENCE, which is stronger than the rule it replaced.** This
     * used to assert that the meeting rule did not ask for a `REQUEST_DND` that existed; that member
     * is gone, so no rule can reach for it however anyone writes one later. What is left to guard is
     * that nobody puts it back without meaning to — Do Not Disturb is a mode people set deliberately
     * and notice the loss of, and HOLD_NON_URGENT already stops OUR notifications without making a
     * claim on everyone else's.
     */
    @Test
    fun `a meeting silences the ringer, and nothing in the list can take over Do Not Disturb`() {
        val out = actions(signals(Situation.IN_A_MEETING, phone = ringing))
        assertTrue(AmbientAction.SILENCE_RINGER in out)
        assertTrue(AmbientAction.STAY_SILENT in out)
        for (a in AmbientAction.entries) {
            val named = "${a.name} ${a.label} ${a.undo}".lowercase()
            assertFalse(
                "$a can take over Do Not Disturb",
                "do not disturb" in named || "dnd" in named,
            )
        }
    }

    @Test
    fun `a ringer that is already silent is left alone`() {
        // ⚠️ Every action here is a HOLD — asserted, then released. Silencing a ringer somebody had
        // already silenced themselves means the release turns it back ON: a phone ringing out loud
        // at the end of a meeting because this layer "undid" something it never did. Transition-only
        // made structural, so it cannot be forgotten by whoever writes the actuator.
        for (r in listOf(RingerState.SILENT, RingerState.VIBRATE)) {
            val out = actions(signals(Situation.IN_A_MEETING, phone = SenseContext(ringer = r)))
            assertFalse("a $r ringer was asked to be silenced", AmbientAction.SILENCE_RINGER in out)
            // ⚠️ And the rest of the rule still fires — without this the guard would also pass if
            // somebody deleted `meeting` outright, which is the "fixture never reached the branch"
            // shape this repository keeps finding.
            assertTrue("the whole meeting rule stopped firing", AmbientAction.STAY_SILENT in out)
        }
        // An UNKNOWN ringer is not a normal one: an actuator that cannot read the current state
        // cannot restore it either.
        assertFalse(AmbientAction.SILENCE_RINGER in actions(signals(Situation.IN_A_MEETING)))
    }

    @Test
    fun `being among people earns silence and nothing more`() {
        // ⚠️ Voices are not a commitment — a pub, a bus and a family kitchen read the same — so
        // holding somebody's notifications back on that alone would be guessing at their evening.
        assertEquals(setOf(AmbientAction.STAY_SILENT), actions(signals(Situation.IN_COMPANY)))
    }

    @Test
    fun `walking and sitting at a desk ask for nothing at all`() {
        // Not every situation earns an action, and the ones that do not are not omissions.
        assertTrue(AmbientRules.decide(signals(Situation.WALKING)).isEmpty())
        assertTrue(AmbientRules.decide(signals(Situation.AT_DESK)).isEmpty())
        assertTrue(AmbientRules.decide(signals(Situation.AT_HOME_IDLE)).isEmpty())
        assertTrue(AmbientRules.decide(signals(Situation.OUT_AND_ABOUT)).isEmpty())
    }

    @Test
    fun `every intent carries the sentence that explains it`() {
        // ⚠️ `ringing` so the meeting rule's ringer branch is reached — without it this sweep would
        // silently stop covering SILENCE_RINGER's reason, which is exactly the shape that makes a
        // green test worthless.
        for (what in Situation.entries) {
            for (i in AmbientRules.decide(signals(what, phone = ringing, events = listOf(alarm)))) {
                assertTrue("$what asked for ${i.action} with no reason", i.reason.isNotBlank())
            }
        }
    }

    // ---- what the rules may actually have ----

    @Test
    fun `an app-tier hold is never refused`() {
        // ⚠️ APP is the floor and has no switch. An app declining to buzz you is not a power that
        // needs a permission, and making it refusable would mean the layer could be switched into a
        // state where it senses everything and may do nothing at all.
        //
        // ⚠️ Stated over EVERY situation, and as "no APP-tier intent is refused" rather than "no
        // intent is refused". The first version asserted the latter and counted driving's three
        // holds — which meant it failed the moment a rule reached a higher tier, reporting a rule
        // change as a violation of a property that had not been violated. An assertion wider than
        // the rule it names is a test that goes off for the wrong reason.
        for (what in Situation.entries) {
            val wanted = AmbientRules.decide(signals(what, phone = ringing, events = listOf(alarm)))
            val d = AmbientRules.permit(wanted, ActionTier.APP)
            val appTier = wanted.filter { it.action.tier == ActionTier.APP }
            assertTrue(
                "$what had an app-tier hold refused at the floor",
                d.refused.none { it.intent.action.tier == ActionTier.APP },
            )
            assertEquals("$what lost an app-tier hold", appTier.size, d.allowed.size)
            assertTrue(d.allowed.all { it.action.tier == ActionTier.APP })
        }
    }

    @Test
    fun `a phone-tier hold is refused, with the reason, while that tier is off`() {
        val wanted = AmbientRules.decide(signals(Situation.IN_A_MEETING, phone = ringing))
        val off = AmbientRules.permit(wanted, ActionTier.APP)
        assertEquals(listOf(AmbientAction.SILENCE_RINGER), off.refused.map { it.intent.action })
        assertTrue(off.refused.single().why.isNotBlank())
        assertFalse(AmbientAction.SILENCE_RINGER in off.allowed.map { it.action })

        val on = AmbientRules.permit(wanted, ActionTier.PHONE)
        assertTrue(on.refused.isEmpty())
        assertTrue(AmbientAction.SILENCE_RINGER in on.allowed.map { it.action })
    }

    /**
     * ⚠️ **Stated over all four combinations rather than case by case**, because the defect this
     * guards is a missing rung rather than a wrong answer: reading only the owner switch would let
     * somebody who never turned the phone tier on find their ringer silenced, having consented to
     * something else entirely. A test that checked three of the four would pass on exactly that.
     */
    @Test
    fun `the tiers are a ladder, so a deeper tier needs every switch below it`() {
        assertEquals(ActionTier.APP, AmbientRules.tierFor(actOnPhone = false, actOnApps = false))
        assertEquals(ActionTier.PHONE, AmbientRules.tierFor(actOnPhone = true, actOnApps = false))
        assertEquals(ActionTier.OWNER, AmbientRules.tierFor(actOnPhone = true, actOnApps = true))
        // ⚠️ The one that matters: the owner switch alone reaches NOTHING. Anything else here is a
        // phone whose ringer can be silenced by somebody who only ever agreed to pausing apps.
        assertEquals(ActionTier.APP, AmbientRules.tierFor(actOnPhone = false, actOnApps = true))
    }

    @Test
    fun `the floor is reachable with every switch off, and is never refusable`() {
        // Both halves of "APP has no switch": it is what you get with nothing on, and nothing can
        // produce a tier below it — so the layer can never be put into a state where it senses
        // everything and may do nothing at all.
        val tiers = listOf(false, true).flatMap { p ->
            listOf(false, true).map { a -> AmbientRules.tierFor(actOnPhone = p, actOnApps = a) }
        }
        assertTrue(ActionTier.APP in tiers)
        assertTrue(tiers.all { it.ordinal >= ActionTier.APP.ordinal })
    }

    @Test
    fun `turning the phone tier on does not turn the owner tier on`() {
        // ⚠️ The tiers are a ladder, not a set of independent switches, and the ordinal ordering is
        // what enforces it — so this fails if anybody reorders the enum.
        val owner = AmbientIntent(AmbientAction.SUSPEND_DISTRACTING_APPS, "why", fromMs = t0)
        assertTrue(AmbientRules.permit(listOf(owner), ActionTier.PHONE).allowed.isEmpty())
        assertEquals(1, AmbientRules.permit(listOf(owner), ActionTier.OWNER).allowed.size)
    }

    @Test
    fun `nothing is allowed that was not asked for`() {
        val wanted = AmbientRules.decide(signals(Situation.ASLEEP))
        val d = AmbientRules.permit(wanted, ActionTier.OWNER)
        assertEquals(wanted.size, d.allowed.size + d.refused.size)
        assertTrue(d.allowed.all { it in wanted })
    }
    // ---- and when nothing is held, why ----

    /**
     * ⚠️ Quiet is the common and correct answer here, and a layer that is quiet without saying why
     * is indistinguishable from a broken one. The scanner's own "LOOK NOW" shipped as exactly that
     * and had to be fixed; this is the same principle applied to the whole acting layer.
     */
    @Test
    fun `nothing held is always explained, and something held never is`() {
        // Firing: no explanation, because there is nothing to explain.
        assertEquals(null, AmbientRules.whyQuiet(signals(Situation.DRIVING)))

        // Every quiet case names its own cause, and none of them repeats another's.
        val unknown = AmbientRules.whyQuiet(signals(Situation.UNKNOWN))!!
        val thin = AmbientRules.whyQuiet(signals(Situation.DRIVING, confidence = 0.2f))!!
        val settling = AmbientRules.whyQuiet(signals(Situation.DRIVING, forMs = 10_000L))!!
        val noRule = AmbientRules.whyQuiet(signals(Situation.WALKING))!!
        val all = listOf(unknown, thin, settling, noRule)
        assertEquals("two causes read the same", 4, all.toSet().size)
        assertTrue(all.all { it.isNotBlank() })

        // ⚠️ Worst-first. An UNKNOWN reading must NOT be reported as waiting to settle: that sends
        // somebody away to wait for something that is never going to arrive.
        assertTrue("an unsure reading was reported as settling", "waiting" !in unknown)
        assertTrue("the settle timer did not say how long", "s" in settling && "waiting" in settling)
        assertTrue("the no-rule case did not name the situation", "walking" in noRule)
    }

    @Test
    fun `an alarm is never reported as quiet`() {
        // Safety is exempt from the settle gate, so it fires with nothing else known — and the
        // explanation has to agree with that rather than talk about settling.
        val alarm = SenseEvent(
            key = SensoriumEvents.KEY_SMOKE_ALARM, title = "ALARM HEARD",
            detail = "a smoke/fire/CO alarm is sounding nearby", severity = EventSeverity.ALERT,
        )
        // ⚠️ DARK, for the same reason as the safety tests above: the torch is the only physical
        // response left and it is gated on darkness, so a lit fixture would make the rule produce
        // nothing and this test would be asserting about a path it never reached.
        assertEquals(
            null,
            AmbientRules.whyQuiet(
                signals(
                    Situation.UNKNOWN, forMs = 0L,
                    env = EnvReading(light = LightState.DARK), events = listOf(alarm),
                ),
            ),
        )
    }

}
