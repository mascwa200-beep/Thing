package dev.mascwa.pulse.core.telemetry

/**
 * What the acoustic interrogator is allowed to keep, and for how long.
 *
 * ⚠️ **This subsystem inverts the Sensorium's stated invariant, and that is why this file exists.**
 * The Sensorium's rule is classify-then-discard: raw audio lives only in the recorder's buffer and
 * only text labels ever leave it. The interrogator cannot work that way — a fallacy is a property of
 * what was actually said, so the words themselves have to be written down. Ambient capture also
 * picks up people who never agreed to any of it. The owner's device, sole user, ambient sensing
 * already authorised: this is a constraint to honour carefully, not a reason to refuse. Putting the
 * rules in a pure, tested core rather than in a DAO is the honouring.
 *
 * The rules, in the order they are applied to every utterance:
 *
 *  1. [isSensitive] — some things are never written at all, however short the retention.
 *  2. [redact] — what is written has credential shapes masked first.
 *  3. [prune] — what is written expires by age and by count, whichever bites first.
 *
 * ⚠️ **Refusing to store is the only real protection; redaction is a second line.** A masked card
 * number in a database is still a record that a card number was read out, at a time, in a place. So
 * [isSensitive] drops the utterance entirely rather than storing a redacted version of it, and
 * [redact] exists for the things that slip past a screen which can only see words.
 */
object TranscriptPolicy {

    /** One stored line. [text] is post-[redact]; nothing else is retained about the speaker. */
    data class Entry(val text: String, val atMs: Long)

    // ---- 1. never written ------------------------------------------------------------------

    /**
     * Phrases around which people read out something that must not be recorded.
     *
     * ⚠️ Deliberately narrow. A broad "financial talk" or "health talk" screen was considered and
     * rejected: those are exactly the conversations where reasoning matters most, so screening them
     * out would gut the feature to guard against a risk that redaction already covers. What is
     * screened here is the *reading out of a secret*, which has no argumentative content to lose.
     */
    private val SECRET_CONTEXT = Regex(
        "\\b(password|passcode|passphrase|pin( number| code)?|security code|cvv|cvc|" +
            "one[- ]time (code|password)|verification code|otp|auth(entication)? code|" +
            "sort code|account number|routing number|social security|national insurance|" +
            "seed phrase|recovery phrase|private key|api key|access token)\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * A run of digits long enough to be a card, an account or a long code — with or without the
     * spaces a transcriber inserts between spoken digits ("four one one one …").
     */
    private val LONG_DIGIT_RUN = Regex("(?:\\d[ \\-]?){$MIN_SECRET_DIGITS,}")

    /** A short standalone code, only treated as secret when a secret word is nearby. */
    private val SHORT_CODE = Regex("\\b\\d{4,8}\\b")

    /**
     * True when the utterance should not be written down at all.
     *
     * The two triggers are independent on purpose: a long digit run is card-shaped whatever was said
     * around it, and a secret word next to any code at all is enough even when the code is short.
     */
    fun isSensitive(text: String): Boolean {
        if (LONG_DIGIT_RUN.containsMatchIn(text)) return true
        return SECRET_CONTEXT.containsMatchIn(text) && SHORT_CODE.containsMatchIn(text)
    }

    // ---- 2. redaction ----------------------------------------------------------------------

    /**
     * Credential shapes that survive being spoken or, more likely, get dictated from a screen.
     *
     * ⚠️ These are the same shapes [SecretScrub] guards the debug uploader with, restated here
     * because `core:telemetry` cannot see the app module. They are checked against the *literal*
     * patterns rather than against any live value: this core has no access to the settings store,
     * and must not — the whole point is that the transcript path never touches real credentials.
     */
    private val CREDENTIAL_SHAPES = listOf(
        Regex("\\bsk-[A-Za-z0-9_-]{16,}"),                       // OpenAI-style
        Regex("\\bgh[pousr]_[A-Za-z0-9]{16,}"),                  // GitHub
        Regex("\\bAIza[A-Za-z0-9_-]{20,}"),                      // Google
        Regex("\\bxox[baprs]-[A-Za-z0-9-]{10,}"),                // Slack
        Regex("\\bBearer\\s+[A-Za-z0-9._~+/=-]{16,}", RegexOption.IGNORE_CASE),
        Regex("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"),  // JWT
    )

    /**
     * Mask credential shapes and any remaining digit run of four or more.
     *
     * ⚠️ Order matters and is asserted by test: the credential shapes run FIRST. Measured rather than
     * assumed — for most shapes the digit pass is inert, because `\b\d{4,}\b` needs the run bounded by
     * non-word characters and these keys sit digits-against-letters. The exception is the Slack token,
     * where the run is delimited by hyphens: masking digits first turns
     * `xoxb-<10 digits>-<16-char tail>` into `xoxb-[redacted]-<tail>`, which no longer
     * matches the shape, so the tail survives in the clear. One shape is enough, and more will follow
     * as the list grows — the same ordering trap [SecretScrub] documents for `Authorization: Bearer`.
     */
    fun redact(text: String): String {
        var out = text
        for (p in CREDENTIAL_SHAPES) out = p.replace(out, MASK)
        out = Regex("\\b\\d{4,}\\b").replace(out, MASK)
        return out
    }

    // ---- 3. retention ----------------------------------------------------------------------

    /**
     * Drop what is too old and, beyond that, what is merely too much.
     *
     * ⚠️ Both bounds are enforced, not either. The window is what the owner reasons about ("nothing
     * older than a day"); the count is what keeps a pathological session — a television left on, a
     * long car journey — from filling the device before the window ever expires. Returned
     * oldest-first, matching the input order, so a caller can delete a prefix.
     */
    fun prune(
        entries: List<Entry>,
        nowMs: Long,
        windowMs: Long = WINDOW_MS,
        maxEntries: Int = MAX_ENTRIES,
    ): List<Entry> {
        val fresh = entries.filter { nowMs - it.atMs < windowMs }
        return if (fresh.size <= maxEntries) fresh else fresh.takeLast(maxEntries)
    }

    /** What [prune] would remove — the rows a DAO should delete. */
    fun expired(
        entries: List<Entry>,
        nowMs: Long,
        windowMs: Long = WINDOW_MS,
        maxEntries: Int = MAX_ENTRIES,
    ): List<Entry> {
        val kept = prune(entries, nowMs, windowMs, maxEntries).toSet()
        return entries.filterNot { it in kept }
    }

    /**
     * The whole policy for one utterance: null when it must not be stored, otherwise what to store.
     *
     * ⚠️ Returning null rather than an empty string is deliberate — an empty row is still a row, and
     * a caller that wrote one would be recording that *something* was said at that moment.
     */
    fun admit(text: String, atMs: Long): Entry? {
        val t = text.trim()
        if (t.isEmpty() || isSensitive(t)) return null
        return Entry(redact(t), atMs)
    }

    // ---- constants -------------------------------------------------------------------------

    /** What a masked span becomes. Fixed-width so the length of the original is not leaked either. */
    const val MASK = "[redacted]"

    /**
     * Digits, ignoring separators, at which a run is assumed to be a card or an account rather than
     * a year, a price or a street number. Twelve is below the shortest card number in circulation
     * (13) and above anything ordinary conversation produces.
     */
    const val MIN_SECRET_DIGITS = 12

    /** Nothing survives longer than this, regardless of volume. */
    const val WINDOW_MS = 24 * 60 * 60 * 1000L

    /** Nor beyond this many rows, regardless of age. */
    const val MAX_ENTRIES = 5_000
}
