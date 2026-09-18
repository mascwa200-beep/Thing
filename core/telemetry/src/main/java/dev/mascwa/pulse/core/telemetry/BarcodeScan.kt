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
     * A UPC-E code written out as the UPC-A it stands for.
     *
     * ⚠️ **Without this, every UPC-E scan misses, and it misses silently.** UPC-E is not a short
     * barcode — it is a twelve-digit UPC-A with a run of zeros squeezed out, and the decoder hands
     * back the *compressed* eight digits. Read as a number that is 1,234,565; the product it names is
     * 12,345,000,065. Those are different keys, so the lookup finds nothing and the app reports an
     * unknown product for a barcode it read perfectly. UPC-E is what small packets carry — gum,
     * cosmetics, single-serve drinks — so the failure is concentrated on exactly the items a phone
     * scanner is most often pointed at.
     *
     * ⚠️ **Only the caller can know to call this**, and that is why it is not folded into [normalize].
     * EAN-8 is also eight digits and is a product code in its own right; expanding one would invent a
     * twelve-digit number that names nothing. The two are told apart by the symbology the decoder
     * reports, not by the digits, so the expansion belongs at the scan site where the format is known.
     *
     * The rule is the GS1 one, keyed on the last data digit: 0–2 move two digits and open four zeros,
     * 3 moves three and opens five, 4 moves four and opens five, 5–9 move five and open four with the
     * digit itself at the end.
     *
     * ⚠️ **Checked exhaustively against ZXing's own `UPCEReader.convertUPCEtoUPCA`** over all
     * 2,000,000 number-system-0-and-1 codes: zero disagreements. Worked examples, from that run:
     * `01234565` → `012345000065`, `04252614` → `042100005264`, `00123457` → `001234000057`.
     *
     * ⚠️ Number system 0 and 1 only. Those are the two GS1 assigns to UPC-E, and a code carrying
     * anything else is not one — returning null beats expanding it into a plausible-looking number.
     *
     * @return the twelve-digit UPC-A, or null if [raw] is not a UPC-E code.
     */
    fun expandUpcE(raw: String): String? {
        val e = raw.filter { it.isDigit() }
        if (e.length != 8) return null
        val ns = e[0]
        if (ns != '0' && ns != '1') return null
        val d = e.substring(1, 7)
        val check = e[7]
        val body = when (d[5]) {
            '0', '1', '2' -> "${d[0]}${d[1]}${d[5]}0000${d[2]}${d[3]}${d[4]}"
            '3' -> "${d[0]}${d[1]}${d[2]}00000${d[3]}${d[4]}"
            '4' -> "${d[0]}${d[1]}${d[2]}${d[3]}00000${d[4]}"
            else -> "${d[0]}${d[1]}${d[2]}${d[3]}${d[4]}0000${d[5]}"
        }
        return "$ns$body$check"
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
     * ⚠️ **UPC-E is the one exception, and the original wording of this note did not admit it.** A
     * UPC-E's check digit is the check digit of the twelve-digit code it *expands to* — see
     * [expandUpcE] — so applying the weighting to the compressed eight digits is not a weaker check,
     * it is a check of a different number. Measured on five genuine codes: four fail here and all
     * five of their expansions pass. Nothing rejects on the strength of this anywhere, so no product
     * is lost to it; what a caller must not do is read `false` on an eight-digit code as evidence
     * that somebody mistyped it. Expand first, then ask.
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
     * The symbologies a decoder can report, as far as anything downstream needs to care.
     *
     * ⚠️ This exists so that the rule below can be tested. Both scanners run two decoders — ML Kit
     * and ZXing — each with its own constants for the same five symbologies, and the mapping from
     * either one is a two-line `when` in the module that owns that decoder. What must NOT be
     * duplicated is what to *do* with the answer.
     */
    enum class Symbology { EAN_13, EAN_8, UPC_A, UPC_E, OTHER }

    /**
     * What a decode of [text] in [symbology] means, as one code the rest of the application can use.
     *
     * ⚠️ **This is the whole reason the symbology is carried out of the decoder at all.** Every other
     * format is already the number it prints, and passing them through is the entire job; UPC-E is
     * not, and the digits alone cannot say which of the two eight-digit symbologies produced them.
     * A scanner that drops the format on the floor has no way back to the product — see [expandUpcE].
     *
     * ⚠️ **Both applications must call this and neither may improvise**, which is the point of it
     * being here rather than in either scanner. They are near-verbatim twins and have already drifted
     * once; one of them expanding UPC-E while the other did not would be the same barcode resolving
     * in one app and not in the other, on the same phone.
     *
     * @return the code to look up, or null if this decode is not a product barcode.
     */
    fun canonical(text: String, symbology: Symbology): String? {
        val trimmed = text.trim()
        val code = if (symbology == Symbology.UPC_E) expandUpcE(trimmed) ?: return null else trimmed
        return if (plausible(code)) code else null
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
