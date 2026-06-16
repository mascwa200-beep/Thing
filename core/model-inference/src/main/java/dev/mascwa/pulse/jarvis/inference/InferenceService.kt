package dev.mascwa.pulse.jarvis.inference

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.google.mediapipe.tasks.genai.llminference.LlmInference

/**
 * Hosts the MediaPipe LLM in its own OS process (declared android:process=":inference"). Loading a
 * multi-GB model can crash *natively* — keeping it here means such a crash kills only this process,
 * not the whole app: the caller's binder call throws DeadObjectException and the UI shows an error.
 */
class InferenceService : Service() {

    @Volatile private var llm: LlmInference? = null

    private val binder = object : IInferenceService.Stub() {
        override fun load(modelPath: String?, maxTokens: Int): Boolean {
            val path = modelPath ?: return false
            return try {
                runCatching { llm?.close() }
                llm = LlmInference.createFromOptions(
                    applicationContext,
                    LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(path)
                        .setMaxTokens(if (maxTokens > 0) maxTokens else 1024)
                        .build(),
                )
                true
            } catch (t: Throwable) {
                // A JVM-level failure (e.g. a catchable OOM) → false. A native abort terminates this
                // process instead; the client observes that as a dead binder, not an app crash.
                false
            }
        }

        override fun generate(fullPrompt: String?): String {
            val engine = llm ?: return "// model not loaded"
            val prompt = fullPrompt ?: return "// empty prompt"
            return try {
                engine.generateResponse(prompt)
            } catch (t: Throwable) {
                "// inference fault: ${t.message ?: t.javaClass.simpleName}"
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        runCatching { llm?.close() }
        llm = null
        super.onDestroy()
    }
}
