package dev.mascwa.pulse.scan

import dev.mascwa.pulse.core.telemetry.BarcodeScan

/**
 * What the scanner has to say for itself.
 *
 * ⚠️ **Facts, not sentences.** Each application words these in its own register — one is a Material
 * card and the other is an LCARS panel — but which situation the scanner is in must not be decided
 * twice. [hint] is that decision, and it is pure, so CI holds it.
 */
data class ScanState(
    /** How close the confirmation counter is; see `BarcodeScan.see`. */
    val progress: BarcodeScan.Progress = BarcodeScan.Progress(),
    /** True once the camera is bound and frames are arriving. */
    val running: Boolean = false,
    /**
     * Why the camera could not be opened, in the operating system's own words, or null.
     *
     * ⚠️ **Previously this was swallowed.** `runCatching { … }.getOrNull() ?: return` around both
     * `future.get()` and `bindToLifecycle` meant a camera in use by another application, a policy
     * that forbids it, or a device with no back camera all produced a viewfinder that stayed black
     * for ever with nothing anywhere saying why — indistinguishable from a scanner that is simply
     * bad at reading barcodes, which is what got reported.
     */
    val failure: String? = null,
    /** The torch is lit. */
    val torchOn: Boolean = false,
    /** This camera has a torch at all. Front cameras and some tablets do not. */
    val torchAvailable: Boolean = false,
    /**
     * The frame is too dark to read.
     *
     * Measured from the luminance the decoder is already given, so it costs a sample of an array the
     * frame had to produce anyway — see [ScanTuning.DARK_BELOW].
     */
    val tooDark: Boolean = false,
    /** Milliseconds since anything decoded, or since the scanner started if nothing ever has. */
    val quietMs: Long = 0,
) {
    /** The situation the person holding the phone is actually in. */
    val hint: ScanHint get() = ScanHint.of(this)
}

/**
 * ⚠️ **The three ways a scan fails look identical from behind the glass, and the old scanner said
 * the same thing for all of them.** "Line the barcode up in the frame" is true, unhelpful and
 * sometimes false: the barcode may be lined up perfectly in a room that is too dark, or the camera
 * may never have opened at all. Telling them apart is most of the difference between a scanner that
 * feels broken and one that feels like it is working with you.
 */
enum class ScanHint {
    /** The camera did not open. [ScanState.failure] says what the system reported. */
    BROKEN,

    /** Bound, frames arriving, nothing read yet and no reason to think anything is wrong. */
    LOOKING,

    /** Frames are arriving and they are too dark to read. Offer the torch. */
    TOO_DARK,

    /** Nothing has decoded for long enough to be worth a suggestion — move closer, steady up. */
    STRUGGLING,

    /** A code is coming through and needs holding still for a moment longer. */
    READING,

    /** Confirmed. */
    GOT_IT,
    ;

    companion object {
        /**
         * ⚠️ **Order is the whole of this function.** A confirmed code outranks everything, because
         * a scanner that has just succeeded must never be telling somebody to move closer. A camera
         * that failed outranks the rest, because every other message would be a lie about a
         * viewfinder that is not running. Darkness outranks "struggling" because it is actionable
         * and "struggling" is not.
         */
        fun of(state: ScanState): ScanHint = when {
            state.progress.confirmed -> GOT_IT
            state.failure != null -> BROKEN
            !state.running -> LOOKING
            state.progress.candidate.isNotBlank() -> READING
            state.tooDark -> TOO_DARK
            state.quietMs >= ScanTuning.STRUGGLING_AFTER_MS -> STRUGGLING
            else -> LOOKING
        }
    }
}

/**
 * The numbers, in one place, because every one of them is a judgement somebody may want to change
 * after pointing a real phone at a real packet — which is the only way any of them can be settled.
 */
object ScanTuning {

    /**
     * The analysis resolution asked for, in the sensor's own landscape orientation.
     *
     * ⚠️ **The default is 640×480 and that is genuinely marginal.** An EAN-13 is 95 modules wide; at
     * VGA, filling half the frame, a module is about three pixels, and the moment the packet is at
     * arm's length or the barcode is small — a drinks can, a cosmetic, a spice jar — it is under two
     * and no decoder can help. 1280×960 is four times the pixels for a decode that is still well
     * inside a frame interval, and it is the size a barcode-first application would ask for.
     */
    const val ANALYSIS_WIDTH: Int = 1280
    const val ANALYSIS_HEIGHT: Int = 960

    /**
     * Mean luminance below which the frame is called too dark, 0..255.
     *
     * ⚠️ Chosen to be well clear of a dim room. A kitchen at night sits far above this; what falls
     * under it is a camera pointed at something genuinely unlit, or one whose lens is covered. It is
     * deliberately conservative: a false "too dark" sends somebody to the torch for no reason, which
     * is worse than saying nothing.
     */
    const val DARK_BELOW: Int = 34

    /** How long with no decode at all before the scanner admits it is struggling. */
    const val STRUGGLING_AFTER_MS: Long = 4_000

    /**
     * How many pixels to sample when measuring brightness.
     *
     * The mean of a few hundred evenly-spaced pixels is indistinguishable from the mean of a million
     * for this purpose, and it runs in microseconds on the analyser thread rather than milliseconds.
     */
    const val BRIGHTNESS_SAMPLES: Int = 512
}
