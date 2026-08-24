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
     * ⚠️ **This note was written for the network path and is only half the story now.** It said that
     * nothing here converts between them because Open Food Facts normalises leading zeros at its own
     * end — probed against the live API, the same product answers to `0038000138416`,
     * `038000138416` and `38000138416` alike — so a conversion would solve a problem the source did
     * not have. That remains true of the API. It is **not** true of the bundled offline database,
     * which is a local table with no server in front of it to be forgiving. See [normalize].
     */
    val PRODUCT_LENGTHS: Set<Int> = setOf(8, 12, 13, 14)

    /**
     * The one key a barcode maps to, whatever form it was printed or scanned in.
     *
     * ⚠️ **This is what makes an integer-keyed offline table correct, not merely compact.** The same
     * product is `031506599323` on a US packet (UPC-A) and `0031506599323` in a European database
     * (EAN-13); the two differ by a leading zero and nothing else. Compared as text they are two
     * different products and half of all US scans miss. Read as numbers they are one value, and the
     * padding question disappears instead of being handled.
     *
     * The same collapse quietly fixes EAN-8 and a GTIN-14 whose indicator digit is zero, since every
     * one of those forms is the same number wearing different amounts of padding.
     *
     * ⚠️ **The check digit is deliberately NOT required here**, and that is the interesting decision.
     * Requiring it would be redundant on the scan path — ZXing has already validated it before this
     * is ever called — and actively harmful on the lookup path, because Open Food Facts is
     * crowd-sourced and genuinely contains rows whose printed code fails the checksum. Rejecting
     * those would make real products on real shelves unfindable to enforce a rule that has already
     * been enforced. [checkDigitValid] exists separately for a caller that wants to *report* data
     * quality rather than act on it.
     *
     * ⚠️ **The one form that will not match** is a true carton code — a GTIN-14 with a non-zero
     * indicator digit, which is a different number from the retail unit inside it. Said plainly here
     * rather than papered over with a conversion that would guess at pack sizes.
     *
     * ⚠️ **Whatever builds the offline database must apply exactly this rule.** A builder that keyed
     * on text, or padded differently, produces a table that compiles, ships and silently answers
     * nothing. The rule lives here so both sides can be pinned to it.
     *
     * @return the numeric key, or null if [raw] is not a product barcode at all.
     */
    fun normalize(raw: String): Long? {
        val digits = raw.filter { it.isDigit() }
        if (digits.length !in PRODUCT_LENGTHS) return null
        // Safe by construction: 14 digits is at most 99,999,999,999,999, four orders of magnitude
        // below Long.MAX_VALUE.
        return digits.toLongOrNull()
    }

    /**
     * Does this code satisfy the GS1 mod-10 check digit?
     *
     * ⚠️ **One rule covers every length, and it is worth knowing why.** Taking the weights from the
     * RIGHT — 3, 1, 3, 1 … over the digits before the check — is exactly equivalent to zero-padding
     * the code to thirteen and applying the EAN-13 weighting, because the padding always lands on
     * the weights it would have had anyway. So EAN-8, UPC-A, EAN-13 and ITF-14 need no special
     * cases, and the version of this that switches on length is a version with four places to get
     * it wrong.
     *
     * Advisory only — see [normalize] for why nothing is rejected on the strength of it.
     */
    fun checkDigitValid(raw: String): Boolean {
        val digits = raw.filter { it.isDigit() }
        if (digits.length !in PRODUCT_LENGTHS) return false
        val body = digits.dropLast(1)
        var sum = 0
        for ((fromRight, ch) in body.reversed().withIndex()) {
            sum += (ch - '0') * if (fromRight % 2 == 0) 3 else 1
        }
        return (10 - sum % 10) % 10 == digits.last() - '0'
    }

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
