package dev.mascwa.pulse.jarvis.inference

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Preset cloud providers. All speak the **OpenAI-compatible** `/chat/completions` API, so one engine
 * serves them all — pick by [CloudProvider], paste a key, done.
 */
enum class CloudProvider(
    val label: String,
    val baseUrl: String,
    val defaultModel: String,
    val keyUrl: String,
) {
    GEMINI("Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.0-flash", "https://aistudio.google.com/apikey"),
    GROQ("Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", "https://console.groq.com/keys"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-4o-mini", "https://openrouter.ai/keys"),
    OPENAI("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", "https://platform.openai.com/api-keys"),
}

/** A resolved cloud config for one generation; null means cloud is off / no key set. */
data class CloudConfig(val baseUrl: String, val apiKey: String, val model: String)

/**
 * A cloud chat brain speaking the OpenAI-compatible streaming `/chat/completions` API — a drop-in
 * [LocalInferenceEngine] so the console and the agent loop use it transparently when a key is
 * configured. Note: chat text (plus persona + any retrieved knowledge) leaves the device for the
 * chosen provider; this is opt-in and surfaced in the UI.
 */
class CloudInferenceEngine(
    private val configProvider: suspend () -> CloudConfig?,
) : LocalInferenceEngine, ToolCallingEngine {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS) // between streamed chunks; chunks arrive frequently
        .build()

    private val _state = MutableStateFlow<EngineState>(EngineState.Unavailable)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    /** True when a provider key is configured. */
    suspend fun isConfigured(): Boolean = runCatching { configProvider() }.getOrNull() != null

    override suspend fun ensureReady() {
        _state.value = if (isConfigured()) EngineState.Ready else EngineState.Unavailable
    }

    override fun generate(prompt: String, history: List<ChatTurn>, system: String?): Flow<String> = flow {
        val cfg = configProvider()
        if (cfg == null) {
            emit("// cloud AI not configured")
            return@flow
        }
        val payload = buildRequest(cfg.model, prompt, history, system)
        val request = Request.Builder()
            .url(cfg.baseUrl.trimEnd('/') + "/chat/completions")
            .addHeader("Authorization", "Bearer ${cfg.apiKey}")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        val response = runCatching { client.newCall(request).execute() }.getOrElse {
            emit("// cloud error: ${it.message ?: "network failure"}")
            return@flow
        }
        response.use { resp ->
            if (!resp.isSuccessful) {
                emit(errorLine(resp.code, runCatching { resp.body?.string() }.getOrNull()))
                return@flow
            }
            val source = resp.body?.source() ?: run {
                emit("// cloud error: empty response")
                return@flow
            }
            var emittedAny = false
            while (true) {
                currentCoroutineContext().ensureActive() // stop promptly if the collector cancels
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val chunk = runCatching {
                    JSONObject(data).getJSONArray("choices").getJSONObject(0)
                        .optJSONObject("delta")?.optString("content").orEmpty()
                }.getOrDefault("")
                if (chunk.isNotEmpty()) {
                    emittedAny = true
                    emit(chunk)
                }
            }
            if (!emittedAny) emit("…")
        }
    }.flowOn(Dispatchers.IO)

    // --- Native function-calling (ToolCallingEngine) ---

    override suspend fun supportsTools(): Boolean = isConfigured()

    override suspend fun chatWithTools(messages: List<ToolMessage>, tools: List<ToolSpec>): AssistantTurn =
        withContext(Dispatchers.IO) {
            val cfg = configProvider() ?: return@withContext AssistantTurn("// cloud AI not configured")
            val payload = JSONObject()
                .put("model", cfg.model)
                .put("messages", toApiMessages(messages))
                .put("stream", false)
            if (tools.isNotEmpty()) {
                payload.put("tools", toApiTools(tools))
                payload.put("tool_choice", "auto")
            }
            val request = Request.Builder()
                .url(cfg.baseUrl.trimEnd('/') + "/chat/completions")
                .addHeader("Authorization", "Bearer ${cfg.apiKey}")
                .post(payload.toString().toRequestBody(JSON))
                .build()
            val resp = runCatching { client.newCall(request).execute() }.getOrElse {
                return@withContext AssistantTurn("// cloud error: ${it.message ?: "network failure"}")
            }
            resp.use { r ->
                val body = runCatching { r.body?.string() }.getOrNull().orEmpty()
                if (!r.isSuccessful) AssistantTurn(errorLine(r.code, body)) else parseAssistant(body)
            }
        }

    /** Each JarvisTool → an OpenAI function with one string `arg` param (matching `run(arg)`). */
    private fun toApiTools(tools: List<ToolSpec>): JSONArray {
        val arr = JSONArray()
        for (t in tools) {
            val params = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject().put("arg", JSONObject().put("type", "string").put("description", t.description)))
                .put("required", JSONArray().put("arg"))
            arr.put(
                JSONObject().put("type", "function").put(
                    "function",
                    JSONObject().put("name", t.name).put("description", t.description).put("parameters", params),
                ),
            )
        }
        return arr
    }

    private fun toApiMessages(messages: List<ToolMessage>): JSONArray {
        val arr = JSONArray()
        for (m in messages) {
            val o = JSONObject().put("role", m.role)
            // An assistant turn that only requests tools may have no content — send null, not "".
            if (m.toolCalls.isNotEmpty() && m.content.isBlank()) o.put("content", JSONObject.NULL) else o.put("content", m.content)
            if (m.role == "tool") {
                m.toolCallId?.let { o.put("tool_call_id", it) }
                m.name?.let { o.put("name", it) }
            }
            if (m.toolCalls.isNotEmpty()) {
                val tc = JSONArray()
                for (call in m.toolCalls) {
                    tc.put(
                        JSONObject()
                            .put("id", call.id)
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject().put("name", call.name).put("arguments", JSONObject().put("arg", call.arg).toString()),
                            ),
                    )
                }
                o.put("tool_calls", tc)
            }
            arr.put(o)
        }
        return arr
    }

    private fun parseAssistant(body: String): AssistantTurn {
        val message = runCatching {
            JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        }.getOrNull() ?: return AssistantTurn("…")
        val calls = message.optJSONArray("tool_calls")
        if (calls != null && calls.length() > 0) {
            val list = ArrayList<ToolInvocation>(calls.length())
            for (i in 0 until calls.length()) {
                val c = calls.getJSONObject(i)
                val fn = c.optJSONObject("function") ?: continue
                val name = fn.optString("name")
                if (name.isBlank()) continue
                val rawArgs = fn.optString("arguments")
                // `arguments` is normally a JSON object like {"arg":"..."}; take its "arg" (even when
                // empty, e.g. a no-arg tool) and only fall back to the raw string when it isn't valid
                // JSON. (Avoids turning an empty/no-arg call into the literal "{}".)
                val arg = runCatching { JSONObject(rawArgs).optString("arg", "") }.getOrElse { rawArgs }
                list.add(ToolInvocation(c.optString("id").ifBlank { "call_$i" }, name, arg))
            }
            if (list.isNotEmpty()) return AssistantTurn(message.optString("content"), list)
        }
        return AssistantTurn(message.optString("content").ifBlank { "…" })
    }

    private fun buildRequest(model: String, prompt: String, history: List<ChatTurn>, system: String?): JSONObject {
        val messages = JSONArray()
        if (!system.isNullOrBlank()) messages.put(msg("system", system))
        history.forEach { messages.put(msg(if (it.role.equals("user", ignoreCase = true)) "user" else "assistant", it.text)) }
        messages.put(msg("user", prompt))
        return JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", true)
    }

    private fun msg(role: String, content: String) = JSONObject().put("role", role).put("content", content)

    private fun errorLine(code: Int, body: String?): String = when (code) {
        401, 403 -> "// cloud error $code: check your API key, sir."
        404 -> "// cloud error 404: that model isn't available for this key."
        429 -> "// cloud error 429: rate limit reached — give it a moment, sir."
        else -> "// cloud error $code" + (body?.take(140)?.let { ": $it" } ?: "")
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
