// A JavaScript engine for the media extractor, in-process.
//
// ⚠️ **WHY THIS EXISTS.** yt-dlp has required an external JavaScript runtime for full YouTube
// support since 2025.11.12, and Chaquopy ships a bare CPython with no Deno, Node, QuickJS or Bun
// anywhere on the device. Reproduced against the exact pinned yt-dlp, extraction says so itself:
// "No supported JavaScript runtime could be found ... YouTube extraction without a JS runtime has
// been deprecated, and some formats may be missing." Until now the app discarded that sentence.
//
// ⚠️ **WHY NOT THE OBVIOUS ROUTE.** yt-dlp invokes every runtime through `Popen` with an explicit
// path, so shipping the `qjs` binary looks straightforward. It is not: Android 10+ refuses to exec
// anything outside `nativeLibraryDir`, which needs `extractNativeLibs=true` — a flag that applies to
// EVERY .so in the app (MediaPipe, MapLibre, Vosk, CPython, whisper, llama) on an APK already around
// 144 MB. Paying that across the whole app to run one script is the wrong trade. QuickJS is a small
// C library, this build already compiles native code, and yt-dlp's challenge provider is a
// documented extension point — so the engine goes IN, and Python reaches it through JNI instead of
// through a process.
//
// ⚠️ **THE CONTRACT IS STDOUT, NOT THE EXPRESSION VALUE.** Read out of yt-dlp's own
// `_construct_stdin`, the script it hands over ends with
// `console.log(JSON.stringify(jsc(...)))`, and the caller does `json.loads(stdout)`. So what must
// come back is exactly what `console.log` printed — the value the last statement evaluated to is
// irrelevant. That is the whole reason this file installs its own console rather than evaluating and
// returning a result.

#include <jni.h>

#include <chrono>
#include <cstring>
#include <string>

#if defined(HAVE_QUICKJS)
#include <pthread.h>

extern "C" {
#include "quickjs.h"
}

namespace {

/// Everything one evaluation needs, so the worker thread takes a single pointer.
struct EvalJob {
    const char* script = nullptr;
    size_t scriptLen = 0;
    long long deadlineMs = 0;
    std::string out;      ///< what console.log printed — the actual product
    std::string err;      ///< why it failed, when it did
    bool ok = false;
};

long long nowMs() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

/// Abort a script that will not finish. Without this a runaway loop in adversarial player code
/// would hang the calling thread forever, and the calling thread belongs to the app.
int onInterrupt(JSRuntime*, void* opaque) {
    auto* job = static_cast<EvalJob*>(opaque);
    return (job != nullptr && nowMs() > job->deadlineMs) ? 1 : 0;
}

/// The captured `console.log`. Arguments are stringified and joined with spaces, exactly as a
/// console does, and the line is appended to the job's output.
JSValue consoleLog(JSContext* ctx, JSValueConst, int argc, JSValueConst* argv) {
    auto* job = static_cast<EvalJob*>(JS_GetContextOpaque(ctx));
    if (job == nullptr) return JS_UNDEFINED;
    for (int i = 0; i < argc; i++) {
        size_t len = 0;
        const char* s = JS_ToCStringLen(ctx, &len, argv[i]);
        if (s == nullptr) continue;  // a value whose toString threw: skip it, do not abort
        if (i > 0) job->out.push_back(' ');
        job->out.append(s, len);
        JS_FreeCString(ctx, s);
    }
    job->out.push_back('\n');
    return JS_UNDEFINED;
}

/// ⚠️ Deliberately a no-op rather than absent or routed to the output. The real `qjs` defines only
/// `console.log`, so the script provably does not depend on these — but a stray call must neither
/// throw (which would fail the whole solve) nor land in the output (which would corrupt the JSON
/// the caller is about to parse).
JSValue consoleSink(JSContext*, JSValueConst, int, JSValueConst*) { return JS_UNDEFINED; }

void installConsole(JSContext* ctx) {
    JSValue global = JS_GetGlobalObject(ctx);
    JSValue console = JS_NewObject(ctx);
    JS_SetPropertyStr(ctx, console, "log", JS_NewCFunction(ctx, consoleLog, "log", 1));
    for (const char* name : {"error", "warn", "info", "debug", "trace"}) {
        JS_SetPropertyStr(ctx, console, name, JS_NewCFunction(ctx, consoleSink, name, 1));
    }
    JS_SetPropertyStr(ctx, global, "console", console);
    // `qjs` also defines a bare `print`, so anything written for that runtime keeps working.
    JS_SetPropertyStr(ctx, global, "print", JS_NewCFunction(ctx, consoleLog, "print", 1));
    JS_FreeValue(ctx, global);
}

/// Read a thrown value into `job->err`, including the stack when there is one.
void captureError(JSContext* ctx, EvalJob* job) {
    JSValue ex = JS_GetException(ctx);
    const char* msg = JS_ToCString(ctx, ex);
    job->err = (msg != nullptr) ? msg : "unknown JavaScript error";
    if (msg != nullptr) JS_FreeCString(ctx, msg);
    JSValue stack = JS_GetPropertyStr(ctx, ex, "stack");
    if (!JS_IsUndefined(stack) && !JS_IsException(stack)) {
        const char* st = JS_ToCString(ctx, stack);
        if (st != nullptr) {
            job->err.append("\n").append(st);
            JS_FreeCString(ctx, st);
        }
    }
    JS_FreeValue(ctx, stack);
    JS_FreeValue(ctx, ex);
}

void* runEval(void* arg) {
    auto* job = static_cast<EvalJob*>(arg);
    JSRuntime* rt = JS_NewRuntime();
    if (rt == nullptr) {
        job->err = "could not create the JavaScript runtime";
        return nullptr;
    }
    // Bounded so a hostile or broken script cannot take the device down with it. The player script
    // is a couple of megabytes of source and needs real room to parse, hence a limit in the
    // hundreds of megabytes rather than the tens.
    JS_SetMemoryLimit(rt, 320u * 1024u * 1024u);
    // ⚠️ Below the thread's real stack (see the pthread attr at the call site), so QuickJS raises a
    // catchable "stack overflow" instead of running off the end of the stack — which on Android is
    // not an exception, it is the process dying.
    JS_SetMaxStackSize(rt, 6u * 1024u * 1024u);
    JS_SetInterruptHandler(rt, onInterrupt, job);

    JSContext* ctx = JS_NewContext(rt);
    if (ctx == nullptr) {
        job->err = "could not create the JavaScript context";
        JS_FreeRuntime(rt);
        return nullptr;
    }
    JS_SetContextOpaque(ctx, job);
    installConsole(ctx);

    JSValue result = JS_Eval(ctx, job->script, job->scriptLen, "<challenge>", JS_EVAL_TYPE_GLOBAL);
    if (JS_IsException(result)) {
        captureError(ctx, job);
    } else {
        job->ok = true;
    }
    JS_FreeValue(ctx, result);
    JS_FreeContext(ctx);
    JS_FreeRuntime(rt);
    return nullptr;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_dev_mascwa_pulse_data_media_JsRuntime_nativeEval(
        JNIEnv* env, jobject /*thiz*/, jstring scriptJ, jint timeoutMs, jobjectArray errorOut) {
    if (scriptJ == nullptr) return nullptr;
    const char* script = env->GetStringUTFChars(scriptJ, nullptr);
    if (script == nullptr) return nullptr;

    EvalJob job;
    job.script = script;
    job.scriptLen = std::strlen(script);
    job.deadlineMs = nowMs() + (timeoutMs > 0 ? timeoutMs : 30000);

    // ⚠️ Its OWN thread, with an explicitly large stack. The default stack of whatever thread
    // Chaquopy happens to call from is around a megabyte, and the player script drives QuickJS
    // deep enough to matter; a native stack overflow is a crash, not a failure anybody can report.
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, 16u * 1024u * 1024u);
    pthread_t worker;
    if (pthread_create(&worker, &attr, runEval, &job) == 0) {
        pthread_join(worker, nullptr);
    } else {
        // Could not get a thread: run inline rather than failing outright. The stack is smaller, so
        // QuickJS's own limit is what keeps this honest.
        runEval(&job);
    }
    pthread_attr_destroy(&attr);
    env->ReleaseStringUTFChars(scriptJ, script);

    if (!job.ok) {
        if (errorOut != nullptr && env->GetArrayLength(errorOut) > 0) {
            jstring e = env->NewStringUTF(job.err.empty() ? "JavaScript failed" : job.err.c_str());
            env->SetObjectArrayElement(errorOut, 0, e);
            env->DeleteLocalRef(e);
        }
        return nullptr;
    }
    return env->NewStringUTF(job.out.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_mascwa_pulse_data_media_JsRuntime_nativeAvailable(JNIEnv*, jobject /*thiz*/) {
    return JNI_TRUE;
}

/// ⚠️ **DEFINED ONLY IN THIS BRANCH, AND THAT IS THE WHOLE POINT.** `nativeAvailable` exists in both
/// branches — it has to, or a call from Kotlin would raise `UnsatisfiedLinkError` instead of
/// reporting the absence — so its presence in the shipped library proves nothing at all, and a CI
/// check written against it would pass on a build with no JavaScript engine in it. This one cannot
/// be linked without QuickJS, so `nm -D` finding it is a fact about the build, exactly as
/// `WhisperNative_nativeInit` is. It also earns its keep at runtime: the engine version belongs in
/// the diagnostics the extractor now reports.
extern "C" JNIEXPORT jstring JNICALL
Java_dev_mascwa_pulse_data_media_JsRuntime_nativeVersion(JNIEnv* env, jobject /*thiz*/) {
    const char* v = JS_GetVersion();
    return env->NewStringUTF(v != nullptr ? v : "quickjs");
}

#else  // !HAVE_QUICKJS

// ⚠️ The absence of the engine is reported, not faked. `nativeAvailable` returning false is what
// lets the Python side fall back to extraction without a JS runtime — today's behaviour — instead of
// failing in a way nobody can act on.
extern "C" JNIEXPORT jstring JNICALL
Java_dev_mascwa_pulse_data_media_JsRuntime_nativeEval(
        JNIEnv*, jobject /*thiz*/, jstring, jint, jobjectArray) {
    return nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_mascwa_pulse_data_media_JsRuntime_nativeAvailable(JNIEnv*, jobject /*thiz*/) {
    return JNI_FALSE;
}

#endif  // HAVE_QUICKJS
