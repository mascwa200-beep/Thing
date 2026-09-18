package dev.mascwa.pulse.data.sensing

import dev.mascwa.pulse.core.telemetry.AmbientAction
import dev.mascwa.pulse.core.telemetry.AmbientDecision
import dev.mascwa.pulse.core.telemetry.AmbientIntent
import dev.mascwa.pulse.core.telemetry.AmbientRefusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ **An app-module test, and it can exist because `HoldCapability.kt` imports no Android at all.**
 * The tier rule lives in `:core:telemetry` where CI has always run it; this half could not go there,
 * because what a given handset physically has is not something a pure function can be told. Splitting
 * the file so the filter carries no platform type is what makes the difference between a rule CI
 * holds and a rule nobody can run — the same reason `RingerPolicy` sits apart from `PhoneHolds`.
 */
class HoldCapabilityTest {

    private val t0 = 1_700_000_000_000L

    private fun intent(a: AmbientAction) = AmbientIntent(a, "because", fromMs = t0)

    /** A layer that objects to exactly the actions it is given, and says nothing about the rest. */
    private fun layer(vararg blocks: Pair<AmbientAction, String>) = object : HoldCapability {
        private val m = blocks.toMap()
        override fun cannotDo(action: AmbientAction) = m[action]
    }

    private val wanted = AmbientDecision(
        allowed = listOf(
            intent(AmbientAction.HOLD_NON_URGENT),
            intent(AmbientAction.TORCH_ON),
            intent(AmbientAction.SUSPEND_DISTRACTING_APPS),
        ),
    )

    @Test
    fun `an action nothing objects to is left alone`() {
        val out = wanted.minusWhatThisHandsetCannotDo(layer(), layer())
        assertEquals(wanted, out)
    }

    @Test
    fun `no layers at all changes nothing`() {
        // The APP-only configuration: nothing has a capability check, so there is nothing to filter.
        assertEquals(wanted, wanted.minusWhatThisHandsetCannotDo())
    }

    /**
     * ⚠️ **Moved, never dropped — the load-bearing property of the whole file.** `AmbientRules.permit`
     * keeps what it refused so a surface can say why; a hardware refusal that filtered instead would
     * leave a rule that fires, a hold that never appears, and nothing anywhere saying why. That is
     * indistinguishable from a fault, and it is the shape this whole arc exists to remove.
     */
    @Test
    fun `something this handset cannot do is refused with its reason, not dropped`() {
        val out = wanted.minusWhatThisHandsetCannotDo(
            layer(AmbientAction.TORCH_ON to "this phone has no torch"),
        )
        assertEquals(2, out.allowed.size)
        assertTrue(AmbientAction.TORCH_ON !in out.allowed.map { it.action })
        assertEquals(listOf(AmbientAction.TORCH_ON), out.refused.map { it.intent.action })
        assertEquals("this phone has no torch", out.refused.single().why)
        // Nothing is invented and nothing vanishes: every intent asked for is accounted for exactly
        // once, which is the same property `AmbientRulesTest` holds over `permit`.
        assertEquals(wanted.allowed.size, out.allowed.size + out.refused.size)
    }

    /**
     * ⚠️ The reason the filter takes a vararg. Each layer speaks only about its own actions and
     * returns null for everything else, so consulting several in turn must block what ANY of them
     * names — and the one that objects is not always the first.
     */
    @Test
    fun `any layer can block, including one that is not the first`() {
        val phone = layer(AmbientAction.TORCH_ON to "no torch")
        val owner = layer(AmbientAction.SUSPEND_DISTRACTING_APPS to "not the device owner")
        val out = wanted.minusWhatThisHandsetCannotDo(phone, owner)
        assertEquals(listOf(AmbientAction.HOLD_NON_URGENT), out.allowed.map { it.action })
        assertEquals(
            setOf(AmbientAction.TORCH_ON, AmbientAction.SUSPEND_DISTRACTING_APPS),
            out.refused.map { it.intent.action }.toSet(),
        )
        // ⚠️ And in the other order, because a `firstNotNullOfOrNull` that stopped at the first
        // layer with anything to say would pass the arrangement above and fail this one.
        val flipped = wanted.minusWhatThisHandsetCannotDo(owner, phone)
        assertEquals(out.allowed.map { it.action }, flipped.allowed.map { it.action })
        assertEquals(
            out.refused.map { it.intent.action }.toSet(),
            flipped.refused.map { it.intent.action }.toSet(),
        )
    }

    @Test
    fun `a refusal that was already there survives`() {
        // What `AmbientRules.permit` refused for being out of tier must not be lost by a filter that
        // only knows about hardware — the scanner shows one list of "asked for, not allowed".
        val already = AmbientRefusal(intent(AmbientAction.STAY_SILENT), "some earlier reason")
        val out = AmbientDecision(wanted.allowed, listOf(already))
            .minusWhatThisHandsetCannotDo(layer(AmbientAction.TORCH_ON to "no torch"))
        assertTrue(already in out.refused)
        assertEquals(2, out.refused.size)
    }

    @Test
    fun `the first reason offered is the one shown`() {
        // Two layers objecting to one action is not a state that should arise — each owns its own
        // tier's actions — but if it ever does, a person gets one sentence rather than two joined.
        val out = wanted.minusWhatThisHandsetCannotDo(
            layer(AmbientAction.TORCH_ON to "first"),
            layer(AmbientAction.TORCH_ON to "second"),
        )
        assertEquals("first", out.refused.single { it.intent.action == AmbientAction.TORCH_ON }.why)
    }
}
