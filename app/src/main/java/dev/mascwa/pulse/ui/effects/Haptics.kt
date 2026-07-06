package dev.mascwa.pulse.ui.effects

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext

/** Whether subtle UI haptics are enabled (driven by Settings). */
val LocalHaptics = staticCompositionLocalOf { true }

/**
 * Expressive, DualSense-style haptic vocabulary. Each cue maps to a [VibrationEffect.Composition] of
 * on-device haptic primitives (the Android analog of a PlayStation controller's nuanced rumble) so the
 * phone can render soft taps, crisp clicks, rising tension, snap releases, heavy thuds and escalating
 * flourishes — not just one flat buzz. See [PulseHaptics].
 */
enum class HapticCue {
    TAP_LIGHT,      // chips / tabs / minor toggles — a soft tick
    SELECT,         // list / nav move — a crisp tick
    TAP_CRISP,      // primary button press — a clean click
    CONFIRM,        // positive confirm — a double-tap
    SUCCESS_RISE,   // reward / complete — a swell into a click
    ERROR_REJECT,   // rejected / failed — a double thud
    CHARGE_RISE,    // building tension (hold / charge) — a rising ramp
    RELEASE_FALL,   // snap release (fire / throw) — a fall into a click
    IMPACT_HEAVY,   // heavy hit / boss reveal — a deep thud + click
    CRIT,           // natural-crit flourish — an escalating trio
    FUMBLE,         // fumble / letdown — a single dull thud
    SHAKE_BUILD,    // gesture shake building — escalating ticks
    FLICK,          // gesture flick — a whip
}

/**
 * On-device haptics engine. Prefers a composition of haptic primitives; falls back to predefined effects
 * and then a one-shot/waveform on devices that don't implement a given primitive. Fully defensive — every
 * path swallows failure and no-ops when there is no vibrator. The global on/off stays the [LocalHaptics]
 * setting, checked by the caller (see [rememberHapticCue]).
 */
class PulseHaptics(context: Context) {

    private val vibrator: Vibrator? = runCatching {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    }.getOrNull()?.takeIf { it.hasVibrator() }

    // Per-primitive support, resolved once and cached (a constant exists at compile time on minSdk 31, but a
    // given device may not implement every primitive — this is a runtime check, not an SDK guard).
    private val primCache = HashMap<Int, Boolean>()
    private fun supports(vararg primitives: Int): Boolean {
        val v = vibrator ?: return false
        return primitives.all { p ->
            primCache.getOrPut(p) { runCatching { v.areAllPrimitivesSupported(p) }.getOrDefault(false) }
        }
    }

    /** Play a cue. Silent no-op when there is no vibrator or the effect can't be built. */
    fun play(cue: HapticCue) {
        val v = vibrator ?: return
        runCatching { build(cue)?.let { v.vibrate(it) } }
    }

    private fun build(cue: HapticCue): VibrationEffect? = when (cue) {
        HapticCue.TAP_LIGHT -> comp(P.LOW_TICK to 0.5f)
            ?: predefined(VibrationEffect.EFFECT_TICK) ?: oneShot(8L, 60)
        HapticCue.SELECT -> comp(P.TICK to 0.6f)
            ?: predefined(VibrationEffect.EFFECT_TICK) ?: oneShot(10L, 90)
        HapticCue.TAP_CRISP -> comp(P.CLICK to 0.7f)
            ?: predefined(VibrationEffect.EFFECT_CLICK) ?: oneShot(14L, 160)
        HapticCue.CONFIRM -> comp(P.CLICK to 0.6f, P.CLICK to 0.9f, delays = intArrayOf(0, 90))
            ?: predefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
            ?: waveform(longArrayOf(0, 14, 70, 20), intArrayOf(0, 170, 0, 220))
        HapticCue.SUCCESS_RISE -> comp(P.SLOW_RISE to 0.8f, P.CLICK to 1.0f, delays = intArrayOf(0, 180))
            ?: predefined(VibrationEffect.EFFECT_HEAVY_CLICK)
            ?: waveform(longArrayOf(0, 120, 0, 30), intArrayOf(0, 120, 0, 255))
        HapticCue.ERROR_REJECT -> comp(P.THUD to 0.7f, P.THUD to 0.9f, delays = intArrayOf(0, 110))
            ?: predefined(VibrationEffect.EFFECT_HEAVY_CLICK)
            ?: waveform(longArrayOf(0, 40, 80, 60), intArrayOf(0, 200, 0, 255))
        HapticCue.CHARGE_RISE -> comp(P.QUICK_RISE to 0.4f, P.SLOW_RISE to 0.85f, delays = intArrayOf(0, 120))
            ?: waveform(longArrayOf(0, 60, 0, 90, 0, 120), intArrayOf(0, 90, 0, 160, 0, 230))
        HapticCue.RELEASE_FALL -> comp(P.QUICK_FALL to 0.8f, P.CLICK to 0.5f, delays = intArrayOf(0, 60))
            ?: predefined(VibrationEffect.EFFECT_CLICK) ?: oneShot(18L, 200)
        HapticCue.IMPACT_HEAVY -> comp(P.THUD to 1.0f, P.CLICK to 0.8f, delays = intArrayOf(0, 40))
            ?: predefined(VibrationEffect.EFFECT_HEAVY_CLICK) ?: oneShot(40L, 255)
        HapticCue.CRIT -> comp(P.CLICK to 1.0f, P.SPIN to 0.8f, P.THUD to 1.0f, delays = intArrayOf(0, 70, 230))
            ?: predefined(VibrationEffect.EFFECT_HEAVY_CLICK)
            ?: waveform(longArrayOf(0, 20, 40, 60, 40, 90), intArrayOf(0, 160, 0, 210, 0, 255))
        HapticCue.FUMBLE -> comp(P.THUD to 0.5f)
            ?: predefined(VibrationEffect.EFFECT_HEAVY_CLICK) ?: oneShot(30L, 120)
        HapticCue.SHAKE_BUILD -> comp(P.LOW_TICK to 0.4f, P.TICK to 0.6f, P.TICK to 0.9f, delays = intArrayOf(0, 90, 180))
            ?: waveform(longArrayOf(0, 30, 60, 40, 60, 60), intArrayOf(0, 90, 0, 160, 0, 230))
        HapticCue.FLICK -> comp(P.QUICK_RISE to 0.6f, P.QUICK_FALL to 0.6f, delays = intArrayOf(0, 50))
            ?: predefined(VibrationEffect.EFFECT_CLICK) ?: oneShot(12L, 180)
    }

    /**
     * Build a primitive composition. [steps] are (primitiveId, scale) pairs; [delays] (ms, defaults 0)
     * space them out. Returns null if any primitive is unsupported (→ caller falls back).
     */
    private fun comp(vararg steps: Pair<Int, Float>, delays: IntArray = IntArray(0)): VibrationEffect? {
        if (!supports(*IntArray(steps.size) { steps[it].first })) return null
        return runCatching {
            var c = VibrationEffect.startComposition()
            steps.forEachIndexed { i, (id, scale) ->
                c = c.addPrimitive(id, scale.coerceIn(0f, 1f), delays.getOrElse(i) { 0 })
            }
            c.compose()
        }.getOrNull()
    }

    private fun predefined(id: Int): VibrationEffect? =
        runCatching { VibrationEffect.createPredefined(id) }.getOrNull()

    private fun oneShot(ms: Long, amplitude: Int): VibrationEffect? =
        runCatching { VibrationEffect.createOneShot(ms, amplitude) }.getOrNull()

    private fun waveform(timings: LongArray, amplitudes: IntArray): VibrationEffect? =
        runCatching { VibrationEffect.createWaveform(timings, amplitudes, -1) }.getOrNull()

    private object P {
        // minSdk 31 covers every primitive at compile time; per-device support is checked at runtime.
        val CLICK = VibrationEffect.Composition.PRIMITIVE_CLICK
        val TICK = VibrationEffect.Composition.PRIMITIVE_TICK
        val LOW_TICK = VibrationEffect.Composition.PRIMITIVE_LOW_TICK
        val QUICK_RISE = VibrationEffect.Composition.PRIMITIVE_QUICK_RISE
        val SLOW_RISE = VibrationEffect.Composition.PRIMITIVE_SLOW_RISE
        val QUICK_FALL = VibrationEffect.Composition.PRIMITIVE_QUICK_FALL
        val THUD = VibrationEffect.Composition.PRIMITIVE_THUD
        val SPIN = VibrationEffect.Composition.PRIMITIVE_SPIN
    }
}

val LocalPulseHaptics = staticCompositionLocalOf<PulseHaptics?> { null }

/**
 * The expressive haptics entry point: returns a `(HapticCue) -> Unit` that plays the cue on the DualSense-
 * style engine, gated by the [LocalHaptics] setting. Builds a per-context engine if none was provided.
 */
@Composable
fun rememberHapticCue(): (HapticCue) -> Unit {
    val enabled = LocalHaptics.current
    val provided = LocalPulseHaptics.current
    val context = LocalContext.current
    val engine = provided ?: remember(context) { PulseHaptics(context) }
    return remember(enabled, engine) { { cue: HapticCue -> if (enabled) engine.play(cue) } }
}

/**
 * Back-compatible adapter for existing call sites that pass a Compose [HapticFeedbackType]. Routes the two
 * legacy constants onto the new expressive cues so those sites feel the DualSense palette with no change.
 */
@Composable
fun rememberHaptics(): (HapticFeedbackType) -> Unit {
    val play = rememberHapticCue()
    return remember(play) {
        { type: HapticFeedbackType ->
            play(if (type == HapticFeedbackType.LongPress) HapticCue.TAP_LIGHT else HapticCue.SELECT)
        }
    }
}
