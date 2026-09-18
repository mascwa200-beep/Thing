package dev.mascwa.pulse.data.sensing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier
import com.google.mediapipe.tasks.audio.core.RunningMode
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.components.containers.AudioData.AudioDataFormat
import com.google.mediapipe.tasks.core.BaseOptions
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.telemetry.PerceptLabel
import dev.mascwa.pulse.core.telemetry.Sensorium
import dev.mascwa.pulse.data.model.ModelFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The Sensorium's EARS: one short mic sip at a time, classified fully on-device by MediaPipe YAMNet
 * into soundscape labels (speech, music, traffic, alarms, …) and immediately discarded. Deliberately
 * DUMB — [sip] captures and classifies exactly once; the SensoriumEngine owns all cadence, adaptive
 * ramping, and the throttle ladder. Unlike the deleted always-recording perception sampler, the
 * microphone is opened per sip and released before returning, so between sips the mic is genuinely
 * free.
 *
 * ⚠️ **[micBusy] does not — and must not — yield to the wake word.** An earlier version of this note
 * claimed it did; it never has, and making it would be worse than the problem. The resident wake loop
 * listens essentially all the time the voice service is running, so a sampler that stood down for it
 * would never sip at all and the Sensorium's ears would be permanently deaf. The two coexist, and
 * whether the platform lets both capture at once is a device question this cannot settle. What
 * [micBusy] does cover is the deliberate, short-lived cases where sipping is either rude or wrong:
 * the console holding the mic for tap-to-talk, and the computer speaking — see the wiring in
 * `AppContainer` for why the second one matters more than it looks.
 *
 * Privacy: classify-then-discard. Raw audio exists only in the recorder's buffer during the sip;
 * only text labels leave this class. Fully defensive: no permission, no model, hardware or classifier
 * failure → an empty list, never a crash.
 */
class AmbientAudioSampler(
    private val context: Context,
    private val http: HttpClient,
    /** True while a sip would be rude or misleading — see the class note and the `AppContainer` wiring. */
    private val micBusy: () -> Boolean = { false },
) {
    private val mutex = Mutex()
    private var classifier: AudioClassifier? = null

    /**
     * What one sip produced.
     *
     * ⚠️ **Two outputs, because a sip has always had two and one of them was being discarded.** The
     * classifier can fail to name anything in a room that is plainly loud — it knows about a few
     * hundred sounds and the world contains more — and the fused reading then called that room quiet.
     * The level was in the capture buffer the whole time.
     */
    data class Sip(val labels: List<PerceptLabel> = emptyList(), val dbfs: Float? = null)

    fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Capture ~[CLIP_MS] of ambient audio and return what it heard. An empty [Sip] when the mic is
     * ungranted or busy, the model is unavailable, or anything at all fails. Serialized — concurrent
     * callers coalesce behind one sip at a time.
     */
    suspend fun sip(): Sip = mutex.withLock {
        if (!hasMic() || micBusy()) return@withLock Sip()
        val c = runCatching { ensureClassifier() }.getOrNull() ?: return@withLock Sip()
        withContext(Dispatchers.Default) {
            var recorder: AudioRecord? = null
            try {
                val r = c.createAudioRecord(AudioFormat.CHANNEL_IN_DEFAULT, SAMPLE_RATE, BUFFER_BYTES)
                recorder = r
                r.startRecording()
                delay(CLIP_MS)
                val audioData = AudioData.create(AudioDataFormat.create(r.format), SAMPLE_RATE)
                val loaded = audioData.load(r)
                Sip(labels = classifyAll(c, audioData), dbfs = levelOf(audioData, loaded))
            } catch (_: Throwable) {
                Sip()
            } finally {
                runCatching { recorder?.stop() }
                runCatching { recorder?.release() }
            }
        }
    }

    /**
     * Every window of the clip, unioned by best score per label.
     *
     * ⚠️ **`classificationResults()` is one entry PER AUDIO WINDOW**, and only the first was being
     * read — so a 1.6 s sip was classified as its opening fraction of a second and the rest thrown
     * away. A doorbell in the second half of a sip simply did not exist.
     *
     * ⚠️ The inner `classifications().firstOrNull()` is a different thing and is correct as it
     * stands: that list is one entry per OUTPUT HEAD, and YAMNet has exactly one. Do not "fix" it to
     * match the loop above.
     */
    private fun classifyAll(c: AudioClassifier, audioData: AudioData): List<PerceptLabel> {
        val best = mutableMapOf<String, Float>()
        for (window in c.classify(audioData).classificationResults()) {
            val cats = window.classifications().firstOrNull()?.categories().orEmpty()
            for (cat in cats) {
                val name = cat.categoryName().lowercase()
                if (name.isBlank()) continue
                val prev = best[name]
                if (prev == null || cat.score() > prev) best[name] = cat.score()
            }
        }
        return best.entries.sortedByDescending { it.value }.take(MAX_RESULTS)
            .map { PerceptLabel(it.key, it.value) }
    }

    /**
     * How loud the sip was, in dBFS, or null when that cannot honestly be said.
     *
     * Two refusals, and both are the house rule about absent measurements rather than caution:
     *
     * ⚠️ **A partly-filled ring is not a quiet room.** [AudioData] is a fixed-size ring and anything
     * it was not given stays zero, so averaging over it after a short read reports a level biased
     * toward silence — by an amount that depends on how short the read was, which is exactly the kind
     * of wrong number nothing downstream could detect.
     *
     * ⚠️ **A buffer of exact zeros is a microphone that delivered nothing, not a silent one.** Real
     * converters dither; digital silence is the signature of a hardware kill switch (the GrapheneOS
     * mic toggle is one) or a capture refused after [hasMic] said yes. Reporting SILENT there would
     * be a confident claim about a room nothing listened to.
     *
     * ⚠️ **Both refusals are decided from the DATA, not from [loaded].** `load(AudioRecord)` returns
     * an int and nothing published says whether that counts floats or frames — and on an interleaved
     * stereo capture those differ by a factor of two, which as a `loaded < buf.size` guard would
     * refuse every reading for ever, silently. Counting how much of the ring is still exactly zero
     * catches an underfill whatever the unit, and catches the dead microphone in the same test. A
     * ring that is 90% filled biases the result by about 0.4 dB, so the threshold only has to catch
     * gross underfill and can afford to be generous.
     */
    private fun levelOf(audioData: AudioData, loaded: Int): Float? {
        if (loaded <= 0) return null
        val buf = audioData.buffer ?: return null
        if (buf.isEmpty()) return null
        var sumSq = 0.0
        var zeros = 0
        for (v in buf) {
            if (v == 0f) zeros++
            sumSq += v.toDouble() * v.toDouble()
        }
        if (zeros > buf.size / 2 || sumSq <= 0.0) return null
        val rms = kotlin.math.sqrt(sumSq / buf.size)
        return (20.0 * kotlin.math.log10(rms)).toFloat()
    }

    /** Release the classifier (the service calls this on teardown; a later sip re-opens lazily). */
    suspend fun close() = mutex.withLock {
        runCatching { classifier?.close() }
        classifier = null
    }

    /** How much of the disk this model is holding — see [ModelFile], including a half-fetched one. */
    fun bytesOnDisk(): Long = ModelFile.bytes(context, MODEL_FILE)

    /**
     * Give the storage back.
     *
     * ⚠️ **Closes the classifier first**, for the reason [ModelFile] leaves to each owner: MediaPipe
     * is handed a path, and deleting the file underneath a live classifier is not something this
     * side can reason about from here. Closing first makes the question moot, and [ensureClassifier]
     * re-opens lazily — so the only cost of discarding while sensing is running is one re-fetch.
     *
     * ⚠️ **Not enough on its own, and the caller has to know that.** A sampler that is still armed
     * will simply download the model again on its next sip, so whoever offers this as a way to free
     * space has to stand ambient sensing down as well — [dev.mascwa.pulse.feature.sensorium.SensoriumViewModel.discardModels]
     * is the one place that does both.
     */
    suspend fun discardModel(): Boolean {
        close()
        return withContext(Dispatchers.IO) { ModelFile.discard(context, MODEL_FILE) }
    }

    private suspend fun ensureClassifier(): AudioClassifier {
        classifier?.let { return it }
        val model = ensureModel()
        val opts = AudioClassifier.AudioClassifierOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(model.absolutePath).build())
            .setRunningMode(RunningMode.AUDIO_CLIPS)
            .setMaxResults(MAX_RESULTS)
            .setScoreThreshold(SCORE_THRESHOLD)
            .build()
        return AudioClassifier.createFromOptions(context, opts).also { classifier = it }
    }

    /** Fetch the ~4 MB YAMNet model once (kept out of the APK), cached in filesDir — the same URL and
     *  filename the pre-b9ba600 stack used, so a still-cached model is reused as-is. */
    private suspend fun ensureModel(): File {
        val f = File(context.filesDir, MODEL_FILE)
        if (f.exists() && f.length() > 0) return f
        val tmp = File(context.filesDir, "$MODEL_FILE.part")
        http.download(MODEL_URL, tmp, MAX_MODEL_BYTES)
        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
        return f
    }

    private companion object {
        const val MODEL_FILE = "yamnet.tflite"
        const val MODEL_URL =
            "https://storage.googleapis.com/mediapipe-models/audio_classifier/yamnet/float32/1/yamnet.tflite"
        const val MAX_MODEL_BYTES = 24L * 1024 * 1024
        const val SAMPLE_RATE = 16000
        const val BUFFER_BYTES = SAMPLE_RATE * 4 * 2 // ~2 s of float PCM headroom
        const val CLIP_MS = 1_600L
        const val MAX_RESULTS = 6

        /**
         * ⚠️ **DERIVED from the consumer's own floor, not a number of its own.** These were 0.25 here
         * and 0.30 in [Sensorium.strong], so the classifier spent its six result slots on labels the
         * fusion core then silently discarded: a sip in a busy room could return six categories and
         * contribute three. Asking for the same floor the reader uses fills every slot with something
         * usable, and makes the two impossible to drift apart.
         */
        const val SCORE_THRESHOLD = Sensorium.MIN_CONF
    }
}
