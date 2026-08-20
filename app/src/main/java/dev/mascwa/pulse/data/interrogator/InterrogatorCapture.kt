package dev.mascwa.pulse.data.interrogator

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import dev.mascwa.pulse.core.telemetry.VoiceActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * The microphone half of the acoustic interrogator — stage 0.
 *
 * Reads 16 kHz mono float PCM, feeds every frame to the CI-tested [VoiceActivity], and hands whole
 * utterances to the caller. Nothing here decides what an utterance means; nothing in [VoiceActivity]
 * knows what an `AudioRecord` is. Splitting them that way is what lets every threshold be tested.
 *
 * ⚠️ **Audio never leaves this class as audio.** The buffer holding somebody's speech exists for as
 * long as it takes to transcribe and is then dropped; what continues is text, which the storage
 * layer screens and encrypts. That is the same classify-then-discard discipline the Sensorium keeps,
 * applied to the one part of this subsystem where it can still hold.
 */
class InterrogatorCapture(
    private val onUtterance: suspend (FloatArray, cut: Boolean) -> Unit,
) {

    /**
     * Open the microphone and run until the coroutine is cancelled.
     *
     * @return false if the recorder could not be opened at all — no permission, no microphone, or
     *   another app holding exclusive capture. The caller stands the service down rather than
     *   retrying in a loop, because none of those resolve on their own.
     */
    @SuppressLint("MissingPermission") // the service checks RECORD_AUDIO before it ever gets here
    suspend fun run(): Boolean = withContext(Dispatchers.IO) {
        val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBytes <= 0) return@withContext false

        // Several frames of headroom past the platform minimum. A buffer sized exactly to the
        // minimum overruns whenever the reading coroutine is descheduled, and an overrun in an
        // always-on capture is a silently clipped word rather than an error anybody would see.
        val bufferBytes = maxOf(minBytes, FRAME_SAMPLES * BYTES_PER_SAMPLE * BUFFER_FRAMES)

        val recorder = runCatching {
            AudioRecord(
                // VOICE_RECOGNITION rather than MIC: the platform applies noise suppression and
                // echo cancellation tuned for speech, which is what the detector downstream is
                // trying to find. MIC delivers a flatter, noisier signal that would push the
                // learned floor up and make quiet speech harder to hear.
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, CHANNEL, ENCODING, bufferBytes,
            )
        }.getOrNull() ?: return@withContext false

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder.release() }
            return@withContext false
        }

        try {
            runCatching { recorder.startRecording() }.getOrElse { return@withContext false }
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) return@withContext false
            pump(recorder)
            true
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
    }

    private suspend fun pump(recorder: AudioRecord) {
        val frame = FloatArray(FRAME_SAMPLES)
        var detector = VoiceActivity.Detector()

        // ⚠️ A RING OF FRAMES KEPT BEFORE SPEECH IS DECLARED. The detector needs several loud frames
        // to be sure, and by then the first syllable has already gone past — so an utterance handed
        // over without the pre-roll begins mid-word, and whisper reliably invents a plausible word to
        // replace it. Half a second of history costs 32 kB and is the difference between "the scheme
        // failed" and "he scheme failed".
        val preroll = ArrayDeque<FloatArray>(PREROLL_FRAMES)
        var buffer: MutableList<FloatArray>? = null

        while (true) {
            coroutineContext.ensureActive()
            val n = recorder.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
            // A negative return is an error code and a short read is a stopping recorder; neither is
            // something to keep reading through.
            if (n < frame.size) return

            val step = VoiceActivity.feed(detector, frame)
            detector = step.detector

            when (step.event) {
                VoiceActivity.Event.SPEECH_START -> {
                    buffer = ArrayList<FloatArray>(PREROLL_FRAMES + 64).apply {
                        addAll(preroll)
                        add(frame.copyOf())
                    }
                    preroll.clear()
                }

                VoiceActivity.Event.SPEECH_END, VoiceActivity.Event.SPEECH_CUT -> {
                    buffer?.add(frame.copyOf())
                    val whole = buffer?.let(::flatten)
                    buffer = null
                    if (whole != null) {
                        onUtterance(whole, step.event == VoiceActivity.Event.SPEECH_CUT)
                    }
                    // ⚠️ A cut is not an end: the speaker has not stopped, so capture resumes at once
                    // rather than waiting for a fresh onset, and the tail of the cut frame seeds the
                    // next segment so the join is not a hole.
                    if (step.event == VoiceActivity.Event.SPEECH_CUT) {
                        buffer = ArrayList<FloatArray>(PREROLL_FRAMES + 64).apply { add(frame.copyOf()) }
                    }
                }

                VoiceActivity.Event.NONE ->
                    if (buffer != null) {
                        buffer.add(frame.copyOf())
                    } else {
                        // Only copy while nobody is speaking; during speech the frame is appended
                        // anyway and a second copy would double the allocation for nothing.
                        if (preroll.size == PREROLL_FRAMES) preroll.removeFirst()
                        preroll.addLast(frame.copyOf())
                    }
            }
        }
    }

    private fun flatten(frames: List<FloatArray>): FloatArray {
        val out = FloatArray(frames.sumOf { it.size })
        var at = 0
        for (f in frames) { f.copyInto(out, at); at += f.size }
        return out
    }

    companion object {
        /** whisper is trained at 16 kHz; anything else would have to be resampled to reach it. */
        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_FLOAT
        const val BYTES_PER_SAMPLE = 4

        /** 20 ms. Every threshold in [VoiceActivity] counts frames, so this must not change alone. */
        const val FRAME_SAMPLES = 320

        const val BUFFER_FRAMES = 16
        const val PREROLL_FRAMES = 25 // half a second
    }
}
