package dev.mascwa.pulse.core.telemetry

import dev.mascwa.pulse.core.telemetry.DeviceClass.Pressure
import dev.mascwa.pulse.core.telemetry.DeviceClass.Probe
import dev.mascwa.pulse.core.telemetry.DeviceClass.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two rules under test are the ones that invert the feature if they are backwards: an absent
 * probe must never demote, and FULL must stay exactly as it was.
 *
 * ⚠️ RAM figures here are what `MemoryInfo.totalMem` really reports for each nominal size — a phone
 * sold as 4 GB reports about 3.7 GiB — not the round number on the box. A test written against
 * `4 * GIB` would pass against thresholds that misclassify every real 4 GB phone.
 */
class DeviceClassTest {

    private val gib = 1024L * 1024L * 1024L
    private fun gb(nominal: Double): Long = (nominal * 0.925 * gib).toLong() // ~7.5% kernel reserve

    // ---- an absent probe is not a weak device --------------------------------------------------

    @Test
    fun `a probe that measured nothing is FULL and unpressured, because we do not know`() {
        val empty = Probe()
        assertEquals(Tier.FULL, DeviceClass.tierOf(empty))
        assertEquals(Pressure.NONE, DeviceClass.pressureOf(empty))
    }

    @Test
    fun `an old phone with no thermal API is not treated as overheating`() {
        // API 26: getCurrentThermalStatus does not exist, so the probe reports null.
        val old = Probe(totalRamBytes = gb(4.0), memoryClassMb = 128, apiLevel = 26, thermalStatus = null)
        assertEquals(Pressure.NONE, DeviceClass.pressureOf(old))
    }

    @Test
    fun `lowRamFlagged false is not evidence of a good phone and cannot outvote a real measurement`() {
        // 2 GB of real RAM with the OEM flag unset — which is common, the property is often simply
        // not configured. The measurement has to win.
        val p = Probe(totalRamBytes = gb(2.0), lowRamFlagged = false, memoryClassMb = 96)
        assertEquals(Tier.MINIMAL, DeviceClass.tierOf(p))
    }

    // ---- FULL stays full -----------------------------------------------------------------------

    @Test
    fun `a flagship gets exactly today's behaviour`() {
        val pixel = Probe(
            totalRamBytes = gb(16.0), memoryClassMb = 512, cores = 8, apiLevel = 36,
            thermalStatus = 0, heapUsedFraction = 0.3f, lowRamFlagged = false,
        )
        assertEquals(Tier.FULL, DeviceClass.tierOf(pixel))
        assertEquals(Pressure.NONE, DeviceClass.pressureOf(pixel))

        val b = DeviceClass.budgetFor(Tier.FULL, Pressure.NONE)
        assertTrue("animations stay on", b.decorativeAnimation)
        assertEquals("the measured share both apps already use", 0.06, b.imageCacheShare, 0.0)
        assertEquals("intervals unscaled", 1.0f, b.backgroundScale, 0.0f)
        assertTrue("the heavy engines stay available", b.heavyEngines)
    }

    // ---- the tier ladder against real hardware -------------------------------------------------

    @Test
    fun `the owner's two phones land where they should`() {
        // Galaxy A16, 4 GB — the genuine budget device, and the one with no crash on record.
        val a16 = Probe(totalRamBytes = gb(4.0), memoryClassMb = 128, cores = 8, apiLevel = 34)
        assertEquals(Tier.LEAN, DeviceClass.tierOf(a16))

        // Pixel 10 Pro XL, 16 GB.
        val pixel = Probe(totalRamBytes = gb(16.0), memoryClassMb = 512, cores = 8, apiLevel = 36)
        assertEquals(Tier.FULL, DeviceClass.tierOf(pixel))
    }

    @Test
    fun `nominal RAM sizes map to the tier they belong to, boundaries and all`() {
        fun tierAt(nominal: Double) = DeviceClass.tierOf(Probe(totalRamBytes = gb(nominal)))
        assertEquals(Tier.MINIMAL, tierAt(1.0))
        assertEquals(Tier.MINIMAL, tierAt(2.0))
        assertEquals(Tier.LEAN, tierAt(3.0))
        assertEquals(Tier.LEAN, tierAt(4.0))
        assertEquals(Tier.MODEST, tierAt(6.0))
        assertEquals(Tier.FULL, tierAt(8.0))
        assertEquals(Tier.FULL, tierAt(12.0))
    }

    @Test
    fun `the worst signal wins, not the average`() {
        // Plenty of RAM, tiny heap ceiling — an OEM that caps hard. The app still cannot allocate.
        val p = Probe(totalRamBytes = gb(8.0), memoryClassMb = 48)
        assertEquals(Tier.MINIMAL, DeviceClass.tierOf(p))
    }

    @Test
    fun `the platform's own low-RAM verdict is taken even when nothing else is available`() {
        assertEquals(Tier.MINIMAL, DeviceClass.tierOf(Probe(lowRamFlagged = true)))
    }

    @Test
    fun `a nonsense memory reading is ignored rather than believed`() {
        // A zero or negative total is a failed read, not a phone with no memory.
        assertEquals(Tier.FULL, DeviceClass.tierOf(Probe(totalRamBytes = 0L, memoryClassMb = 0)))
    }

    // ---- the two weak signals stay weak --------------------------------------------------------

    @Test
    fun `core count demotes FULL by one step and can do no more than that`() {
        val quad = Probe(totalRamBytes = gb(8.0), memoryClassMb = 256, cores = 4, apiLevel = 34)
        assertEquals(Tier.MODEST, DeviceClass.tierOf(quad))

        // On a phone already below FULL it must not compound — a 4 GB quad-core is LEAN, not MINIMAL.
        val leanQuad = Probe(totalRamBytes = gb(4.0), memoryClassMb = 128, cores = 4, apiLevel = 34)
        assertEquals(Tier.LEAN, DeviceClass.tierOf(leanQuad))
    }

    @Test
    fun `a dated API caps at MODEST and never demotes a phone that is already lower`() {
        val old = Probe(totalRamBytes = gb(8.0), memoryClassMb = 256, cores = 8, apiLevel = 26)
        assertEquals(Tier.MODEST, DeviceClass.tierOf(old))

        val oldAndSmall = Probe(totalRamBytes = gb(2.0), memoryClassMb = 64, cores = 4, apiLevel = 26)
        assertEquals(Tier.MINIMAL, DeviceClass.tierOf(oldAndSmall))
    }

    // ---- pressure ------------------------------------------------------------------------------

    @Test
    fun `thermal LIGHT is not pressure, because it is what an ordinary busy phone reports`() {
        assertEquals(Pressure.NONE, DeviceClass.thermalPressure(0))
        assertEquals(Pressure.NONE, DeviceClass.thermalPressure(1))
        assertEquals(Pressure.WARM, DeviceClass.thermalPressure(2))
        assertEquals(Pressure.HOT, DeviceClass.thermalPressure(3))
        assertEquals(Pressure.CRITICAL, DeviceClass.thermalPressure(4))
        assertEquals(Pressure.CRITICAL, DeviceClass.thermalPressure(6))
    }

    @Test
    fun `a full heap is pressure even when the phone is cold`() {
        assertEquals(Pressure.CRITICAL, DeviceClass.pressureOf(Probe(thermalStatus = 0, heapUsedFraction = 0.95f)))
        assertEquals(Pressure.HOT, DeviceClass.pressureOf(Probe(thermalStatus = 0, heapUsedFraction = 0.85f)))
        assertEquals(Pressure.WARM, DeviceClass.pressureOf(Probe(thermalStatus = 0, heapUsedFraction = 0.75f)))
        assertEquals(Pressure.NONE, DeviceClass.pressureOf(Probe(thermalStatus = 0, heapUsedFraction = 0.4f)))
    }

    @Test
    fun `a NaN heap fraction is discarded rather than read as full`() {
        assertEquals(Pressure.NONE, DeviceClass.pressureOf(Probe(heapUsedFraction = Float.NaN)))
    }

    // ---- the budget ----------------------------------------------------------------------------

    @Test
    fun `pressure tightens a budget monotonically and never loosens it`() {
        for (tier in Tier.entries) {
            var prev = DeviceClass.budgetFor(tier, Pressure.NONE)
            for (pressure in listOf(Pressure.WARM, Pressure.HOT, Pressure.CRITICAL)) {
                val now = DeviceClass.budgetFor(tier, pressure)
                assertTrue("$tier/$pressure decode cap must not grow", now.imageDecodePx <= prev.imageDecodePx)
                assertTrue("$tier/$pressure cache share must not grow", now.imageCacheShare <= prev.imageCacheShare)
                assertTrue("$tier/$pressure intervals must not shorten", now.backgroundScale >= prev.backgroundScale)
                assertTrue("$tier/$pressure parallelism must not grow", now.parallelism <= prev.parallelism)
                prev = now
            }
        }
    }

    @Test
    fun `a weaker tier is never given a bigger budget than a stronger one`() {
        var prev = DeviceClass.budgetFor(Tier.FULL, Pressure.NONE)
        for (tier in listOf(Tier.MODEST, Tier.LEAN, Tier.MINIMAL)) {
            val now = DeviceClass.budgetFor(tier, Pressure.NONE)
            assertTrue("$tier decode cap", now.imageDecodePx <= prev.imageDecodePx)
            assertTrue("$tier cache share", now.imageCacheShare <= prev.imageCacheShare)
            assertTrue("$tier intervals", now.backgroundScale >= prev.backgroundScale)
            assertTrue("$tier parallelism", now.parallelism <= prev.parallelism)
            prev = now
        }
    }

    @Test
    fun `even a critical device keeps some capacity, because going silent is the worse failure`() {
        val worst = DeviceClass.budgetFor(Tier.MINIMAL, Pressure.CRITICAL)
        assertTrue("something must still be able to run", worst.parallelism >= 1)
        assertTrue("images must still be drawable", worst.imageDecodePx > 0)
        assertTrue("the cache must not be zero", worst.imageCacheShare > 0.0)
        assertTrue("intervals are stretched, not stopped", worst.backgroundScale.isFinite())
    }

    // ---- how much background work this phone should be asked to do ------------------------------

    @Test
    fun `a background restriction is honoured hardest, whatever the hardware`() {
        // The person told the OS this app may not run in the background. Spending the one tick it
        // still gets on a cloud reasoning loop is the opposite of honouring that.
        assertEquals(
            DeviceClass.WorkTier.MINIMAL,
            DeviceClass.workTier(Tier.FULL, Pressure.NONE, backgroundRestricted = true),
        )
        assertEquals(
            DeviceClass.WorkTier.MINIMAL,
            DeviceClass.budgetFor(
                Probe(totalRamBytes = gb(16.0), memoryClassMb = 512, backgroundRestricted = true),
            ).work,
        )
    }

    @Test
    fun `an unrestricted flagship does all of it`() {
        assertEquals(DeviceClass.WorkTier.ALL, DeviceClass.workTier(Tier.FULL, Pressure.NONE))
        assertEquals(
            DeviceClass.WorkTier.ALL,
            DeviceClass.workTier(Tier.FULL, Pressure.NONE, backgroundRestricted = false, deviceIdle = false),
        )
    }

    @Test
    fun `heat and weakness trim the discretionary work`() {
        assertEquals(DeviceClass.WorkTier.ESSENTIAL, DeviceClass.workTier(Tier.LEAN, Pressure.NONE))
        assertEquals(DeviceClass.WorkTier.ESSENTIAL, DeviceClass.workTier(Tier.FULL, Pressure.HOT))
        assertEquals(DeviceClass.WorkTier.ESSENTIAL, DeviceClass.workTier(Tier.FULL, Pressure.NONE, deviceIdle = true))
        assertEquals(DeviceClass.WorkTier.MINIMAL, DeviceClass.workTier(Tier.MINIMAL, Pressure.NONE))
        assertEquals(DeviceClass.WorkTier.MINIMAL, DeviceClass.workTier(Tier.FULL, Pressure.CRITICAL))
    }

    @Test
    fun `getting warm never LOOSENS a weak phone's budget`() {
        // ⚠️ The first version used `minOf` on the enum, and because the higher ordinal is the more
        // restrictive tier that RELAXED a MINIMAL device the moment it got warm.
        for (tier in Tier.entries) {
            val calm = DeviceClass.budgetFor(tier, Pressure.NONE).work
            for (pressure in listOf(Pressure.WARM, Pressure.HOT, Pressure.CRITICAL)) {
                assertTrue(
                    "$tier/$pressure",
                    DeviceClass.budgetFor(tier, pressure).work.ordinal >= calm.ordinal,
                )
            }
        }
    }

    @Test
    fun `a trimmed pass always says why, and an untrimmed one says nothing`() {
        assertEquals(null, DeviceClass.workNotice(DeviceClass.WorkTier.ALL, Tier.FULL, Pressure.NONE))
        for (tier in Tier.entries) {
            for (pressure in Pressure.entries) {
                for (restricted in listOf(null, false, true)) {
                    for (idle in listOf(null, false, true)) {
                        val work = DeviceClass.workTier(tier, pressure, restricted, idle)
                        val why = DeviceClass.workNotice(work, tier, pressure, restricted, idle)
                        if (work == DeviceClass.WorkTier.ALL) {
                            assertEquals("$tier/$pressure/$restricted/$idle", null, why)
                        } else {
                            // ⚠️ Not merely non-empty. The first version of this assertion checked
                            // only that, so deleting a whole branch left the generic
                            // "Trimmed this refresh" fallback satisfying it — the guard was asleep
                            // because the assertion was too weak to see the damage. What matters is
                            // that the sentence NAMES the cause.
                            val expected = when {
                                restricted == true -> "restricted"
                                pressure == Pressure.CRITICAL -> "too hot"
                                pressure == Pressure.HOT -> "warm"
                                tier == Tier.MINIMAL || tier == Tier.LEAN -> "little to spare"
                                else -> "dozing"
                            }
                            assertTrue(
                                "$tier/$pressure/$restricted/$idle said: $why",
                                why != null && why.contains(expected),
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- the readout says what it could not measure ---------------------------------------------

    @Test
    fun `the readout separates what was measured from what could not be`() {
        val partial = Probe(totalRamBytes = gb(4.0), memoryClassMb = 128, cores = 8, apiLevel = 26)
        val text = DeviceClass.describe(partial)
        assertTrue(text.startsWith("LEAN · NONE"))
        assertTrue("RAM was measured", text.contains("RAM 3.7 GiB"))
        assertTrue("thermal was not", text.contains("Not measurable here"))
        assertTrue(text.contains("thermal"))
    }

    @Test
    fun `the readout of a fully-probed phone claims nothing is missing`() {
        val whole = Probe(
            totalRamBytes = gb(16.0), memoryClassMb = 512, cores = 8, apiLevel = 36,
            thermalStatus = 0, heapUsedFraction = 0.25f,
        )
        assertFalse(DeviceClass.describe(whole).contains("Not measurable here"))
        // ⚠️ The Budget KDoc claims every field reaches the readout. Hold it to that.
        val text = DeviceClass.describe(whole)
        assertTrue("animations", text.contains("animations on"))
        assertTrue("decode cap", text.contains("2048px"))
        assertTrue("cache share", text.contains("6% of heap"))
        assertTrue("parallelism", text.contains("6 at a time"))
        assertTrue("work tier", text.contains("background work all"))
    }

    @Test
    fun `the readout reports a system-wide animation preference, because the user has said something`() {
        val off = Probe(totalRamBytes = gb(8.0), memoryClassMb = 256, animatorScale = 0f)
        assertTrue(DeviceClass.describe(off).contains("animations off system-wide"))
        val on = Probe(totalRamBytes = gb(8.0), memoryClassMb = 256, animatorScale = 1f)
        assertFalse(DeviceClass.describe(on).contains("animations off system-wide"))
    }

    // ---- animations off system-wide is an instruction, not a measurement --------------------

    @Test
    fun `a flagship obeys animations-off exactly as a cheap phone does`() {
        val flagship = Probe(totalRamBytes = gb(16.0), memoryClassMb = 512, animatorScale = 0f)
        assertEquals("and it must not change the tier", Tier.FULL, DeviceClass.tierOf(flagship))
        assertFalse(DeviceClass.budgetFor(flagship).decorativeAnimation)
    }

    @Test
    fun `an unreadable animator scale is not a request to stop animating`() {
        // Null is "could not read", which is a different fact from "the user turned them off".
        val unknown = Probe(totalRamBytes = gb(16.0), memoryClassMb = 512, animatorScale = null)
        assertTrue(DeviceClass.budgetFor(unknown).decorativeAnimation)

        val on = Probe(totalRamBytes = gb(16.0), memoryClassMb = 512, animatorScale = 1f)
        assertTrue(DeviceClass.budgetFor(on).decorativeAnimation)
    }

    @Test
    fun `pressure cannot hand animation back to a phone that asked for none`() {
        for (pressure in Pressure.entries) {
            assertFalse(
                "$pressure",
                DeviceClass.budgetFor(Tier.FULL, pressure, animationsAllowed = false).decorativeAnimation,
            )
        }
    }

    @Test
    fun `the whole-probe entry point agrees with the parts it is made of`() {
        val p = Probe(totalRamBytes = gb(4.0), memoryClassMb = 128, thermalStatus = 3, animatorScale = 1f)
        assertEquals(
            DeviceClass.budgetFor(DeviceClass.tierOf(p), DeviceClass.pressureOf(p), true),
            DeviceClass.budgetFor(p),
        )
    }

    @Test
    fun `the readout is locale-independent`() {
        val before = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val text = DeviceClass.describe(Probe(totalRamBytes = gb(4.0)))
            assertTrue("a comma decimal would be a different number", text.contains("3.7 GiB"))
            assertFalse("and must not be a German comma", text.contains("3,7"))
        } finally {
            java.util.Locale.setDefault(before)
        }
    }
}
