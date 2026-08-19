package dev.mascwa.pulse.data.interrogator

import dev.mascwa.pulse.core.telemetry.Discourse
import dev.mascwa.pulse.core.telemetry.FallacyReference
import dev.mascwa.pulse.core.telemetry.Rebuttal
import dev.mascwa.pulse.data.survival.LibraryLookup
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The acoustic interrogator's cascade — everything between a captured utterance and a finding.
 *
 * ```
 *   0  capture      InterrogatorCapture       continuous, trivial
 *   1  transcribe   WhisperEngine             moderate
 *   -  record       TranscriptStore           screened, encrypted, capped
 *   2  claim?       Discourse.consider        pure, free   ─┐ the gate
 *   3  cue?         Fallacies.best            pure, free   ─┘
 *   4  reference    FallacyReference          curated, offline
 *   5  adjudicate   LlamaEngine               expensive, rare
 *   6  compose      Rebuttal.compose          pure, free
 * ```
 *
 * ⚠️ **THE SCARCE RESOURCE IS THE MODEL, NOT THE MICROPHONE.** Stages 2–3 are the whole reason this
 * is a cascade rather than a loop that asks a language model about everything it hears: they are
 * pure, cost nothing, and refuse the overwhelming majority of speech before anything expensive
 * happens. Every refusal names itself, so a quiet subsystem can be told apart from a broken one.
 *
 * ⚠️ **EVERY STAGE AFTER THE FIRST IS OPTIONAL, AND THE FEATURE DEGRADES RATHER THAN BREAKS.** With
 * no adjudicator the finding is composed from the tested frame and labelled
 * [Rebuttal.Provenance.PATTERN_ONLY] — which says on screen that nothing read the argument. That is
 * not a fallback bolted on; it is why `Provenance` exists.
 */
class InterrogatorCascade(
    private val whisper: WhisperEngine,
    private val transcripts: TranscriptStore,
    private val library: LibraryLookup,
    private val llama: LlamaEngine,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** Why the last utterance did or did not produce a finding — for the screen and the log. */
    data class Trace(
        val heard: String,
        val verdict: Discourse.Verdict,
        val atMs: Long,
    )

    private val lock = Mutex()
    private var state = Discourse.CascadeState()

    private val _findings = MutableSharedFlow<Rebuttal.Response>(replay = 1, extraBufferCapacity = 8)

    /** Findings, as they are made. Replay 1 so a screen opened afterwards still sees the last one. */
    val findings: SharedFlow<Rebuttal.Response> = _findings.asSharedFlow()

    private val _lastTrace = MutableStateFlow<Trace?>(null)
    val lastTrace: StateFlow<Trace?> = _lastTrace.asStateFlow()

    /**
     * Run one captured utterance all the way through.
     *
     * @param pcm 16 kHz mono float samples, as [InterrogatorCapture] produces them.
     * @param cut true when the detector ended the segment on its length ceiling rather than on
     *   silence — the speaker was still going, so this text is half a sentence.
     * @return the finding, if the cascade made one.
     */
    suspend fun process(pcm: FloatArray, cut: Boolean = false): Rebuttal.Response? {
        val text = whisper.transcribe(pcm)?.trim().orEmpty()
        if (text.isBlank()) return null

        val at = now()

        // ⚠️ RECORDED BEFORE THE CASCADE RUNS, AND INDEPENDENTLY OF IT. What was said is part of the
        // record whether or not it trips anything, and writing it first means a cascade that throws
        // cannot also lose the line. TranscriptStore screens and encrypts; a line it will not take
        // is simply not stored, never stored in the clear.
        transcripts.record(text, at)

        // ⚠️ A cut segment is transcribed and kept but never judged. Half a sentence reads as a bare
        // assertion — the qualifying clause is in the half that has not arrived — so judging it would
        // manufacture findings out of the detector's own timing. Discourse.segment() is what stitches
        // these back together for a future caller; until something does, the honest thing is silence.
        if (cut) {
            _lastTrace.value = Trace(text, Discourse.Verdict.NO_CLAIM, at)
            return null
        }

        val decision = lock.withLock {
            val d = Discourse.consider(Discourse.Utterance(text, at, at), state, at)
            // ⚠️ The returned state is kept on EVERY path, not only on ESCALATE. Refusals still count
            // the sighting, which is what makes the repeat line meaningful and what makes the
            // cooldown a cooldown rather than a permanent silence.
            state = d.state
            d
        }
        _lastTrace.value = Trace(text, decision.verdict, at)

        val candidate = decision.candidate ?: return null
        if (!decision.escalate) return null

        // Stage 4. Curated, so a fallacy always has a real page behind it — see FallacyReference for
        // the measurement that ruled ranking out here.
        val route = FallacyReference.routeFor(candidate.fallacy.id)
        val found = runCatching { library.exact(route.guideId, route.heading) }.getOrNull()
        val grounding = found?.let {
            Rebuttal.Grounding(
                guideTitle = it.title,
                section = route.heading,
                excerpt = it.body,
                guideId = route.guideId,
            )
        }

        // Stage 5. Absent, still downloading, or simply slow — all the same to the caller, and all
        // leave a weaker finding rather than none.
        val judged = if (llama.loaded) {
            val raw = llama.complete(Rebuttal.judgePrompt(text, candidate, grounding))
            Rebuttal.parseJudgement(raw)
        } else {
            null
        }

        // ⚠️ THE MODEL IS ALLOWED TO SAY NO, AND SAYING NO ENDS IT. Escalating means the cue tripped,
        // not that the mistake is real; the adjudicator exists to throw most of those out. Ignoring
        // its refusal — showing the pattern-only finding anyway — would make stage 5 decorative and
        // the whole cascade a keyword matcher with a language model bolted on.
        if (judged != null && !judged.present) return null

        val response = Rebuttal.compose(
            candidate = candidate,
            modelDraft = judged?.question,
            grounding = grounding,
            timesSeen = decision.timesSeen,
        )
        _findings.emit(response)
        return response
    }

    /** Forget the cooldowns and repeat counts. Paired with purging the transcript. */
    suspend fun reset() = lock.withLock {
        state = Discourse.CascadeState()
        _lastTrace.value = null
    }
}
