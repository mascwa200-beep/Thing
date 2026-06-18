package dev.mascwa.pulse.jarvis.inference

import kotlinx.coroutines.flow.Flow

/**
 * Image-understanding capability, implemented by engines that can analyze pictures — currently the cloud
 * brain (an OpenAI-compatible vision model such as gpt-4o-mini via OpenRouter). The on-device model can't
 * see images, so it doesn't implement this; callers check [supportsVision] first.
 */
interface VisionEngine {
    /** True when image analysis is available right now (a vision-capable cloud key is set). */
    suspend fun supportsVision(): Boolean

    /**
     * Streams the model's analysis of [images] (each a `data:image/...;base64,...` URL) guided by
     * [prompt]. [system] is the persona/system prompt.
     */
    fun generateWithImages(prompt: String, images: List<String>, system: String? = null): Flow<String>
}
