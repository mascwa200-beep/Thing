package dev.mascwa.pulse.core.telemetry

/**
 * Deciding when a camera has actually read a barcode.
 *
 * A scanner decodes several times a second, and the decoder is not the problem: ZXing validates the
 * check digit on every EAN and UPC symbology, so a decode is very unlikely to be a corrupted read of
 * the barcode it was pointed at. What it cannot know is whether that was **the barcode you meant**.
 * A shelf, a multipack, a leaflet and the thing in your hand are all in frame at once, and the first
 * decode is whichever the optics happened to resolve first.
 *
 * So this accumulates: a code is confirmed only after the same value comes back [CONFIRMATIONS] times
 * in a row, and a different value resets the count rather than adding to it. Sweeping across a shelf
 * produces a run of one-shot decodes and confirms nothing; holding still on one packet confirms it in
 * a fraction of a second.
 *
 * ⚠️ **The alternative — firing on the first decode — is not merely twitchy, it is wrong.** It logs
 * the wrong product's calories, and the person has no way to tell: the name that comes back is a real
 * food with real numbers, just not theirs.
 *
 * Pure and clock-free, so CI holds the rule. Frames arrive from a camera the tests do not have.
 */
object BarcodeScan {

    /**
     * How many identical decodes make a confirmation.
     *
     * Three, and the number is a balance rather than a preference. Analysis frames arrive at roughly
     * 15–30 a second on a modern phone, so three is well under a fifth of a second of holding still —
     * imperceptible as a delay. Two would confirm on a single stray pair; four starts to feel like
     * the scanner is ignoring you when the light is poor and decodes come intermittently.
     */
    const val CONFIRMATIONS: Int = 3

    /**
     * Lengths a retail product code actually has.
     *
     * EAN-8 and UPC-E are 8, UPC-A is 12, EAN-13 is 13, and ITF-14 (a shipping carton) is 14.
     *
     * ⚠️ Nothing here converts between them, and that is a measured decision rather than an omission:
     * Open Food Facts normalises leading zeros at its own end. Probed against the live API, the same
     * product answers to `0038000138416`, `038000138416` and `38000138416` alike. A UPC-A-to-EAN-13
     * conversion would be code written to solve a problem the source does not have.
     */
    val PRODUCT_LENGTHS: Set<Int> = setOf(8, 12, 13, 14)

    /**
     * Could this string be a product barcode at all?
     *
     * Digits only and a real symbology length. This is not a checksum — the decoder has already done
     * that — it is a guard against a decode that succeeded on something which is not a product code:
     * a CODE-128 shipping label, a QR code, a loyalty-card number printed alongside.
     */
    fun plausible(code: String): Boolean =
        code.length in PRODUCT_LENGTHS && code.all { it.isDigit() }

    /**
     * A scan in progress.
     *
     * Immutable: [see] returns the next state rather than mutating, so the caller holds one value and
     * there is no window in which a frame arriving on the analyser thread can observe a half-updated
     * count.
     */
    data class Progress(
        /** The code seen most recently, or blank before anything has been read. */
        val candidate: String = "",
        /** How many times in a row [candidate] has now been seen. */
        val seen: Int = 0,
    ) {
        /** True once [candidate] has been seen enough times to act on. */
        val confirmed: Boolean get() = candidate.isNotBlank() && seen >= CONFIRMATIONS

        /** 0..1, for a progress indicator that makes the wait legible instead of mysterious. */
        val fraction: Float get() = (seen.toFloat() / CONFIRMATIONS).coerceIn(0f, 1f)
    }

    /**
     * Fold one decode into the scan.
     *
     * An implausible code is ignored entirely rather than resetting the count — a QR code drifting
     * through the frame should not undo the packet you are holding steady.
     */
    fun see(progress: Progress, code: String): Progress {
        val trimmed = code.trim()
        if (!plausible(trimmed)) return progress
        return if (trimmed == progress.candidate) {
            // ⚠️ Stops climbing at the threshold. Left unbounded, a scanner held on one packet for a
            // minute would count into the thousands and `fraction` would be meaningless.
            progress.copy(seen = minOf(progress.seen + 1, CONFIRMATIONS))
        } else {
            Progress(candidate = trimmed, seen = 1)
        }
    }

    /**
     * A frame that decoded nothing.
     *
     * ⚠️ Does NOT reset. Decodes are intermittent in ordinary light — a barcode that reads on frames
     * 1, 3 and 5 is one somebody is holding perfectly still, and a scanner that started over on every
     * blank frame between them would never confirm anything at all.
     */
    fun nothing(progress: Progress): Progress = progress
}
