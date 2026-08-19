// The acoustic interrogator's bridge to whisper.cpp.
//
// ⚠️ EVERY ENTRY POINT HERE IS BEHIND `HAVE_WHISPER`, and that is deliberate. The upstream tree is
// cloned by CI rather than vendored, so "did whisper actually get linked?" is a question the build
// has to be able to answer. If these functions were compiled unconditionally and merely returned an
// error without the library, a build that silently lost speech recognition would still export every
// symbol and still go green. Because they vanish instead, CI can assert on `nm -D` over the shipped
// .so — a missing symbol is a fact, where a runtime flag would only be a claim.
//
// The Kotlin side calls these through `external fun`, which resolves lazily: a missing method throws
// UnsatisfiedLinkError at the call, not at class load. That is the same failure the whole library
// being absent produces, so WhisperEngine has exactly one path to handle rather than two.

#include <jni.h>
#include <string>
#include <vector>

#ifdef HAVE_WHISPER
#include "whisper.h"

namespace {

// A context pointer handed to Kotlin as a jlong. Nothing else crosses the boundary: the model, the
// state and the tokenizer all stay on this side.
inline whisper_context *ctx_of(jlong handle) {
    return reinterpret_cast<whisper_context *>(handle);
}

}  // namespace

extern "C" {

/**
 * Load a ggml model from disk. Returns 0 on any failure, which the caller treats as "unavailable"
 * rather than retrying — a model that will not load will not load on the second attempt either.
 */
JNIEXPORT jlong JNICALL
Java_dev_mascwa_pulse_data_interrogator_WhisperNative_nativeInit(
        JNIEnv *env, jobject /*thiz*/, jstring model_path, jboolean use_gpu) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    if (path == nullptr) return 0;

    whisper_context_params params = whisper_context_default_params();
    // ⚠️ Default false. GPU offload on Android goes through a backend that varies enormously by
    // vendor, and a wrong guess is a crash inside a driver rather than a clean failure we can
    // report. The caller decides; the safe answer is the default.
    params.use_gpu = (use_gpu == JNI_TRUE);

    whisper_context *ctx = whisper_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(model_path, path);
    return reinterpret_cast<jlong>(ctx);
}

/**
 * Transcribe one buffer of 16 kHz mono float PCM in [-1, 1].
 *
 * Returns the concatenated segment text, or null on failure. Null and empty are different answers:
 * empty means whisper ran and heard nothing worth writing down, which is the common case for a
 * room with no speech in it, and the caller must not treat that as an error.
 */
JNIEXPORT jstring JNICALL
Java_dev_mascwa_pulse_data_interrogator_WhisperNative_nativeTranscribe(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jfloatArray pcm, jint threads) {
    whisper_context *ctx = ctx_of(handle);
    if (ctx == nullptr || pcm == nullptr) return nullptr;

    const jsize n = env->GetArrayLength(pcm);
    if (n <= 0) return env->NewStringUTF("");

    jfloat *samples = env->GetFloatArrayElements(pcm, nullptr);
    if (samples == nullptr) return nullptr;

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads > 0 ? threads : 2;
    params.translate = false;
    params.language = "en";
    // Nothing is printed: this runs inside a foreground service on somebody's phone, and whisper's
    // default progress output would put the transcript itself into logcat, which is exactly the
    // leak the whole storage layer is built to avoid.
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.no_timestamps = true;
    // Drops the "[BLANK_AUDIO]" and "(silence)" style hallucinations whisper emits on quiet input,
    // which for an always-listening capture is most of what it would otherwise produce.
    params.suppress_blank = true;

    const int rc = whisper_full(ctx, params, samples, static_cast<int>(n));
    // JNI_ABORT: the buffer was not modified, so there is nothing to copy back.
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
    if (rc != 0) return nullptr;

    std::string out;
    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; ++i) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text != nullptr) out += text;
    }
    return env->NewStringUTF(out.c_str());
}

/** Release the model. Safe on 0, because the caller may free a context that never loaded. */
JNIEXPORT void JNICALL
Java_dev_mascwa_pulse_data_interrogator_WhisperNative_nativeFree(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    whisper_context *ctx = ctx_of(handle);
    if (ctx != nullptr) whisper_free(ctx);
}

/** What was linked, for the diagnostic screen. Present only when whisper really is in the build. */
JNIEXPORT jstring JNICALL
Java_dev_mascwa_pulse_data_interrogator_WhisperNative_nativeBackends(
        JNIEnv *env, jobject /*thiz*/) {
    return env->NewStringUTF(whisper_print_system_info());
}

}  // extern "C"
#endif  // HAVE_WHISPER
