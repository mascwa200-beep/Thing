// The acoustic interrogator's bridge to llama.cpp — stage 5, the adjudicator.
//
// ⚠️ Behind `HAVE_LLAMA` for the same reason whisper's bridge is behind `HAVE_WHISPER`: the tree is
// cloned by CI rather than vendored, so the ABSENCE of these symbols from the shipped library is
// what proves the link failed. A missing symbol is a fact; a runtime flag would be a claim.
//
// ⚠️ **THE SURFACE IS KEPT AS SMALL AS IT CAN BE, AND THAT IS A RISK DECISION.** Every llama_* name
// below is a bet on what the pinned build number carries: llama.cpp renamed a good deal of its C API
// during 2024-25 — `llama_load_model_from_file` became `llama_model_load_from_file`, the vocab
// accessors moved off the model onto a separate handle — and which names a given tag has is a fact
// about that tag, not something to recall. The workflow greps `llama.h` for every symbol used here
// and prints present/absent BEFORE the compile, so a wrong bet is explained in the same round it is
// made. The newer names are used, matching a recent pin.

#include <jni.h>
#include <string>
#include <vector>

#ifdef HAVE_LLAMA
#include "llama.h"

namespace {

// Everything the adjudicator needs, behind one opaque handle. The model is mmapped by llama.cpp, so
// this struct is small however large the weights are.
struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    llama_sampler *sampler = nullptr;
};

inline Session *session_of(jlong handle) { return reinterpret_cast<Session *>(handle); }

std::string piece_of(const llama_vocab *vocab, llama_token token) {
    char buf[256];
    const int n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, /*special=*/false);
    if (n <= 0) return {};
    return std::string(buf, static_cast<size_t>(n));
}

}  // namespace

extern "C" {

/**
 * Load a GGUF model. Returns 0 on any failure.
 *
 * ⚠️ `n_gpu_layers` stays at zero. Offload on Android goes through a backend that varies by vendor,
 * and a wrong guess is a crash inside a driver rather than something this layer can report.
 */
JNIEXPORT jlong JNICALL
Java_dev_mascwa_pulse_data_interrogator_LlamaNative_nativeInit(
        JNIEnv *env, jobject /*thiz*/, jstring model_path, jint context_tokens, jint threads) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    if (path == nullptr) return 0;

    llama_backend_init();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    llama_model *model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(model_path, path);
    if (model == nullptr) return 0;

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = context_tokens > 0 ? static_cast<uint32_t>(context_tokens) : 2048;
    // The batch only ever has to hold one prompt, so sizing it to the context avoids a second
    // decode pass without reserving anything the context does not already imply.
    cp.n_batch = cp.n_ctx;
    cp.n_threads = threads > 0 ? threads : 4;
    cp.n_threads_batch = cp.n_threads;

    llama_context *ctx = llama_init_from_model(model, cp);
    if (ctx == nullptr) {
        llama_model_free(model);
        return 0;
    }

    // ⚠️ Greedy, not sampled. This is a judgement — "is the fallacy really present, and what is the
    // one question worth asking?" — and a temperature that made it creative would make the same
    // utterance adjudicate differently on two runs. Reproducibility is worth more than variety here.
    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

    auto *s = new Session{model, ctx, sampler};
    return reinterpret_cast<jlong>(s);
}

/**
 * Run one prompt and return the completion, or null on failure.
 *
 * Single-turn by design: the caller supplies the whole prompt each time and nothing is carried
 * between calls. A conversation would need the KV cache preserved and invalidated correctly, which
 * is a great deal of state for a subsystem whose every call is an independent judgement.
 */
JNIEXPORT jstring JNICALL
Java_dev_mascwa_pulse_data_interrogator_LlamaNative_nativeComplete(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jstring prompt, jint max_tokens) {
    Session *s = session_of(handle);
    if (s == nullptr || s->ctx == nullptr || prompt == nullptr) return nullptr;

    const char *text = env->GetStringUTFChars(prompt, nullptr);
    if (text == nullptr) return nullptr;
    const std::string in(text);
    env->ReleaseStringUTFChars(prompt, text);

    const llama_vocab *vocab = llama_model_get_vocab(s->model);

    // Negative return is the required buffer size — the documented two-call form.
    const int need = -llama_tokenize(vocab, in.c_str(), static_cast<int32_t>(in.size()),
                                     nullptr, 0, /*add_special=*/true, /*parse_special=*/true);
    if (need <= 0) return nullptr;
    std::vector<llama_token> tokens(static_cast<size_t>(need));
    if (llama_tokenize(vocab, in.c_str(), static_cast<int32_t>(in.size()),
                       tokens.data(), need, true, true) < 0) {
        return nullptr;
    }

    // ⚠️ Refuse rather than truncate. A prompt silently cut in half would still produce a fluent
    // answer, and that answer would be a judgement about half an argument presented as a judgement
    // about the whole one — worse than no answer at all.
    if (static_cast<uint32_t>(need) + static_cast<uint32_t>(max_tokens) >= llama_n_ctx(s->ctx)) {
        return nullptr;
    }

    llama_memory_clear(llama_get_memory(s->ctx), true);

    llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));
    if (llama_decode(s->ctx, batch) != 0) return nullptr;

    std::string out;
    llama_token token = 0;
    for (int i = 0; i < max_tokens; ++i) {
        token = llama_sampler_sample(s->sampler, s->ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        out += piece_of(vocab, token);
        batch = llama_batch_get_one(&token, 1);
        if (llama_decode(s->ctx, batch) != 0) break;
    }
    return env->NewStringUTF(out.c_str());
}

/** Release everything. Safe on 0 and safe twice. */
JNIEXPORT void JNICALL
Java_dev_mascwa_pulse_data_interrogator_LlamaNative_nativeFree(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    Session *s = session_of(handle);
    if (s == nullptr) return;
    if (s->sampler != nullptr) llama_sampler_free(s->sampler);
    if (s->ctx != nullptr) llama_free(s->ctx);
    if (s->model != nullptr) llama_model_free(s->model);
    delete s;
}

}  // extern "C"
#endif  // HAVE_LLAMA
