package dev.mascwa.pulse.core.telemetry

/**
 * How much this phone can actually be asked to do — so both applications adapt to the hardware they
 * are on rather than to the hardware they were written on.
 *
 * The app had **no concept of this at all**. Repo-wide, before this file: `isLowRamDevice` had zero
 * call sites, `getMemoryClass` zero, every thermal API zero, `isDeviceIdleMode` zero, the animator
 * duration scale zero, and `ActivityManager.MemoryInfo` was read twice — both times only to print a
 * string. The one adaptive mechanism in either application was [Sensorium.level], which reads battery
 * and stillness and governs a single service.
 *
 * ## Two rules carry this file, and getting either backwards inverts the whole point
 *
 * ⚠️ **An absent probe is not a weak device.** There is no thermal API below API 29 and no heap
 * headroom figure on some devices. A null reads as *unknown* and never demotes — otherwise every old
 * phone is classed [Pressure.CRITICAL] for the crime of being old, which is the exact opposite of
 * what this is for. [tierOf] and [pressureOf] both take the worst of the signals that are actually
 * PRESENT, and a probe with nothing present answers [Tier.FULL] / [Pressure.NONE]: we do not know, so
 * we do not act.
 *
 * ⚠️ **[Tier.FULL] must stay full.** The directive was "not powerful enough **or overpowered**", so a
 * flagship has to get today's behaviour byte for byte. Every [Budget] value at FULL/NONE is what the
 * code already did before this existed, which is also what makes this safe to ship without hardware
 * to test it on: the phone in the owner's pocket cannot regress.
 *
 * ## Where the numbers come from
 *
 * ⚠️ `ActivityManager.MemoryInfo.totalMem` **excludes kernel-reserved memory**, so a phone sold as
 * "4 GB" reports roughly 3.7 GiB and one sold as "2 GB" reports roughly 1.85. Thresholds are
 * therefore set BETWEEN the nominal sizes rather than at them — a boundary written at a round 4 GiB
 * would put every nominal-4 GB phone on the wrong side of it. See [RAM_MINIMAL] and friends.
 *
 * The tier is deliberately not a score. A phone is as slow as its worst constraint, so the signals
 * vote and the worst vote wins.
 */
object DeviceClass {

    /**
     * What the Android layer measured. **Every field is nullable and that is load-bearing** — see the
     * absent-probe rule above. The Android reader lives in `:core:update` so both applications share
     * one definition of how each of these is read.
     */
    data class Probe(
        /** `ActivityManager.MemoryInfo.totalMem` — physical RAM minus what the kernel reserved. */
        val totalRamBytes: Long? = null,
        /** `MemoryInfo.availMem`. */
        val availRamBytes: Long? = null,
        /** `MemoryInfo.lowMemory` — the system's own "I am short" flag. */
        val systemLowMemory: Boolean? = null,
        /** `ActivityManager.isLowRamDevice` — the platform's own verdict, set by the OEM. */
        val lowRamFlagged: Boolean? = null,
        /** `ActivityManager.getMemoryClass()` — this app's heap ceiling in MB. */
        val memoryClassMb: Int? = null,
        /** `Runtime.availableProcessors()`. Online cores, so it can move; a weak signal by design. */
        val cores: Int? = null,
        /** `Build.VERSION.SDK_INT`. Always known, hence not nullable. */
        val apiLevel: Int = 0,
        /** `PowerManager.getCurrentThermalStatus()`, API 29+. 0..6; see [thermalPressure]. */
        val thermalStatus: Int? = null,
        /** Fraction of this process's own heap ceiling currently in use, 0f..1f. */
        val heapUsedFraction: Float? = null,
        /** `Settings.Global.ANIMATOR_DURATION_SCALE`. 0f means the user turned animations off. */
        val animatorScale: Float? = null,
        /** `PowerManager.isPowerSaveMode`. */
        val powerSave: Boolean? = null,
        /** `PowerManager.isDeviceIdleMode` — doze. */
        val deviceIdle: Boolean? = null,
        /** `ActivityManager.isBackgroundRestricted`, API 28+. The user has told the OS to hold us back. */
        val backgroundRestricted: Boolean? = null,
    )

    /** How much machine there is. Static for the life of the process. */
    enum class Tier { FULL, MODEST, LEAN, MINIMAL }

    /** How much of it is available right now. Changes minute to minute. */
    enum class Pressure { NONE, WARM, HOT, CRITICAL }

    // ---- thresholds, and why each one is where it is -------------------------------------------

    private const val GIB = 1024L * 1024L * 1024L

    /**
     * Boundaries sit BETWEEN nominal RAM sizes, because `totalMem` under-reports (see the class KDoc).
     * Measured landmarks: nominal 2 GB reports ~1.85 GiB, 4 GB ~3.7, 6 GB ~5.6, 8 GB ~7.4.
     */
    /** Below this is a nominal 2 GB phone or smaller — Android Go territory. */
    val RAM_MINIMAL = (2.2 * GIB).toLong()
    /** Below this is a nominal 3 or 4 GB phone. The owner's Galaxy A16 lands here. */
    val RAM_LEAN = (4.5 * GIB).toLong()
    /** Below this is a nominal 6 GB phone. */
    val RAM_MODEST = (6.5 * GIB).toLong()

    /**
     * `getMemoryClass()` is the per-app heap ceiling in MB and varies widely by OEM, so it is a
     * corroborating signal rather than the primary one. The values bracket the common buckets: 32/48
     * on 1 GB-class hardware, 96 on 2-3 GB, 128-192 on 4-6 GB, 256+ above.
     */
    const val HEAP_MINIMAL_MB = 48
    const val HEAP_LEAN_MB = 96
    const val HEAP_MODEST_MB = 192

    /**
     * ⚠️ Core count only ever demotes FULL to MODEST — never more, and never on its own.
     * `availableProcessors()` returns cores currently ONLINE, which the governor moves around, and
     * plenty of weak phones are nominally octa-core. It is the least trustworthy signal here.
     */
    const val CORES_WEAK = 4

    /**
     * Below this API a device is at least eight years old whatever its specification says, so it
     * cannot be [Tier.FULL]. It also cannot report thermal status, which is why the cap matters:
     * without it a 2017 phone with 6 GB would be treated as a flagship AND be unable to say it was
     * overheating.
     */
    const val API_DATED = 28

    fun tierOf(p: Probe): Tier {
        val votes = ArrayList<Tier>(4)

        // ⚠️ Only a TRUE flag votes. `lowRamFlagged == false` is not evidence of a good phone — the
        // property is often simply not configured — so an absent or false flag says nothing at all
        // rather than voting FULL. (It could not outrank a real measurement even if it did: the
        // aggregation below takes the WORST vote, not the average. The rule this line carries is
        // narrower than that, and the comment used to claim otherwise.)
        if (p.lowRamFlagged == true) votes += Tier.MINIMAL
        if (p.systemLowMemory == true) votes += Tier.LEAN

        p.totalRamBytes?.let { ram ->
            votes += when {
                ram <= 0L -> return@let          // a nonsense reading is not a reading
                ram < RAM_MINIMAL -> Tier.MINIMAL
                ram < RAM_LEAN -> Tier.LEAN
                ram < RAM_MODEST -> Tier.MODEST
                else -> Tier.FULL
            }
        }

        p.memoryClassMb?.let { mb ->
            votes += when {
                mb <= 0 -> return@let
                mb <= HEAP_MINIMAL_MB -> Tier.MINIMAL
                mb <= HEAP_LEAN_MB -> Tier.LEAN
                mb <= HEAP_MODEST_MB -> Tier.MODEST
                else -> Tier.FULL
            }
        }

        // Nothing measurable: we do not know, so we do not act.
        var tier = votes.maxByOrNull { it.ordinal } ?: Tier.FULL

        // The two weak signals, each capped so neither can drive the verdict alone.
        if (tier == Tier.FULL && p.cores != null && p.cores in 1..CORES_WEAK) tier = Tier.MODEST
        if (tier == Tier.FULL && p.apiLevel in 1 until API_DATED) tier = Tier.MODEST

        return tier
    }

    /**
     * Map a `PowerManager` thermal status onto pressure.
     *
     * ⚠️ `THERMAL_STATUS_LIGHT` is deliberately [Pressure.NONE]. It is what a phone reports while
     * doing ordinary work — a video call, a map, a game — and treating it as pressure would throttle
     * the app permanently on any device that runs warm, which describes most thin phones.
     */
    fun thermalPressure(status: Int): Pressure = when {
        status <= 1 -> Pressure.NONE      // NONE, LIGHT
        status == 2 -> Pressure.WARM      // MODERATE
        status == 3 -> Pressure.HOT       // SEVERE
        else -> Pressure.CRITICAL         // CRITICAL, EMERGENCY, SHUTDOWN
    }

    const val HEAP_WARM = 0.70f
    const val HEAP_HOT = 0.80f
    const val HEAP_CRITICAL = 0.90f

    fun pressureOf(p: Probe): Pressure {
        val votes = ArrayList<Pressure>(3)
        p.thermalStatus?.let { if (it >= 0) votes += thermalPressure(it) }
        p.heapUsedFraction?.let { f ->
            votes += when {
                f.isNaN() || f < 0f -> return@let
                f >= HEAP_CRITICAL -> Pressure.CRITICAL
                f >= HEAP_HOT -> Pressure.HOT
                f >= HEAP_WARM -> Pressure.WARM
                else -> Pressure.NONE
            }
        }
        if (p.systemLowMemory == true) votes += Pressure.HOT
        return votes.maxByOrNull { it.ordinal } ?: Pressure.NONE
    }

    // ---- what each consumer may spend ----------------------------------------------------------

    /**
     * What the rest of the app is allowed to do at this tier and pressure.
     *
     * ⚠️ **Deliberately does NOT take a [Sensorium.SenseLevel].** The dependency runs one way:
     * DeviceClass feeds the sensing ladder, not the reverse. Taking it here as well would give two
     * places an opinion about the same decision, which is the duplicated-definition drift this
     * repository has corrected six times.
     *
     * ⚠️ Every field here is rendered by the Settings diagnostic from the day it exists. A value
     * computed and never read is this project's oldest recurring defect, and a budget field with no
     * consumer would be a fresh instance of it.
     */
    data class Budget(
        /** Run the purely decorative infinite animations (glows, sweeps, pulses). */
        val decorativeAnimation: Boolean,
        /** Longest edge, in pixels, any decoded image may occupy. */
        val imageDecodePx: Int,
        /** Fraction of the app heap Coil may hold as decoded bitmaps. */
        val imageCacheShare: Double,
        /** Multiply every background polling interval by this. 1f = today's cadence. */
        val backgroundScale: Float,
        /** Concurrent network/IO fan-out. */
        val parallelism: Int,
        /** Run the optional heavy engines — the local model, transcription, the interrogator. */
        val heavyEngines: Boolean,
    )

    /**
     * The whole reading in one call. **This is the entry point every consumer should use** — it is
     * the only place that combines the three inputs, so no caller can honour two of them and forget
     * the third.
     */
    fun budgetFor(p: Probe): Budget =
        budgetFor(tierOf(p), pressureOf(p), animationsAllowed = p.animatorScale != 0f)

    /**
     * ⚠️ The FULL/NONE row is today's behaviour exactly: animations on, no decode cap worth the name,
     * the measured 0.06 cache share both applications already use, unscaled intervals. If this row
     * ever changes, a flagship has been made worse by a change meant for a cheap phone.
     *
     * ⚠️ [animationsAllowed] is **not** a performance signal and does not touch the tier. A zero
     * `ANIMATOR_DURATION_SCALE` means the person went into developer options or accessibility
     * settings and turned animations off across the whole phone — that is an instruction, and a
     * flagship has to obey it exactly as a cheap phone does. Compose does not honour that setting
     * for `rememberInfiniteTransition` on its own, so somewhere has to, and this is that place.
     * ⚠️ Null means the setting could not be read, which is not the same as zero: unknown never
     * demotes, so it allows animation.
     */
    fun budgetFor(tier: Tier, pressure: Pressure, animationsAllowed: Boolean = true): Budget {
        var base = when (tier) {
            Tier.FULL -> Budget(true, 2048, 0.06, 1.0f, 6, true)
            Tier.MODEST -> Budget(true, 1440, 0.05, 1.5f, 4, true)
            Tier.LEAN -> Budget(false, 1080, 0.04, 2.5f, 3, true)
            Tier.MINIMAL -> Budget(false, 720, 0.03, 4.0f, 2, false)
        }
        // Applied to the base before pressure, so no branch below can hand animation back.
        if (!animationsAllowed) base = base.copy(decorativeAnimation = false)

        return when (pressure) {
            Pressure.NONE -> base
            Pressure.WARM -> base.copy(
                backgroundScale = base.backgroundScale * 1.5f,
                parallelism = maxOf(2, base.parallelism - 1),
            )
            Pressure.HOT -> base.copy(
                decorativeAnimation = false,
                imageDecodePx = minOf(base.imageDecodePx, 1080),
                imageCacheShare = minOf(base.imageCacheShare, 0.04),
                backgroundScale = base.backgroundScale * 2.5f,
                parallelism = 2,
            )
            // ⚠️ Still not zero. A device this hot must stop volunteering work, but the emergency
            // path and anything the user is looking at still has to run — a phone that goes silent
            // because it is warm is a worse failure than a phone that is warm.
            Pressure.CRITICAL -> base.copy(
                decorativeAnimation = false,
                imageDecodePx = minOf(base.imageDecodePx, 720),
                imageCacheShare = minOf(base.imageCacheShare, 0.03),
                backgroundScale = base.backgroundScale * 4.0f,
                parallelism = 1,
                heavyEngines = false,
            )
        }
    }

    /**
     * One line for a diagnostic screen. Says what was measured AND what could not be, because
     * "no thermal reading" and "thermal fine" are different facts and a readout that renders them
     * identically is how an absent probe gets mistaken for a healthy one.
     */
    fun describe(p: Probe): String {
        val tier = tierOf(p)
        val pressure = pressureOf(p)
        val known = ArrayList<String>(6)
        val unknown = ArrayList<String>(4)

        p.totalRamBytes?.takeIf { it > 0 }
            ?.let { known += "RAM ${"%.1f".format(java.util.Locale.US, it.toDouble() / GIB)} GiB" }
            ?: run { unknown += "RAM" }
        p.memoryClassMb?.takeIf { it > 0 }?.let { known += "heap ${it} MB" } ?: run { unknown += "heap class" }
        p.cores?.let { known += "$it cores" } ?: run { unknown += "cores" }
        known += "API ${p.apiLevel}"
        p.lowRamFlagged?.let { if (it) known += "flagged low-RAM" }
        p.thermalStatus?.let { known += "thermal $it" } ?: run { unknown += "thermal" }
        p.heapUsedFraction?.takeIf { !it.isNaN() && it >= 0f }
            ?.let { known += "heap ${(it * 100).toInt()}% used" }
            ?: run { unknown += "heap use" }
        p.powerSave?.let { if (it) known += "power saver on" }
        p.deviceIdle?.let { if (it) known += "dozing" }
        p.backgroundRestricted?.let { if (it) known += "background restricted" }
        p.animatorScale?.let { if (it == 0f) known += "animations off system-wide" }

        return buildString {
            append(tier.name).append(" · ").append(pressure.name).append('\n')
            append(known.joinToString(", "))
            if (unknown.isNotEmpty()) append("\nNot measurable here: ").append(unknown.joinToString(", "))
        }
    }
}
