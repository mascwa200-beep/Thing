package dev.mascwa.pulse.data.media

import android.util.Log
import dev.mascwa.pulse.data.interrogator.NativeBridge

/**
 * The JavaScript engine the media extractor runs YouTube's challenge code in.
 *
 * ⚠️ **WHY THE APP CARRIES A JAVASCRIPT ENGINE AT ALL.** yt-dlp has required an external JS runtime
 * for full YouTube support since **2025.11.12**, and the pinned version is well past that. Run
 * against the exact pin, extraction says so in its own words: *"No supported JavaScript runtime
 * could be found. Only deno is enabled by default... YouTube extraction without a JS runtime has
 * been deprecated, and some formats may be missing."* Chaquopy ships a bare CPython, so the phone
 * has no Deno, Node, Bun or QuickJS anywhere on it — and until the capturing logger landed beside
 * this, the app **discarded that warning**, which is why it went unnoticed.
 *
 * ⚠️ **WHY IN-PROCESS RATHER THAN A BINARY.** yt-dlp invokes every runtime through `Popen` with a
 * configurable path, so shipping the `qjs` executable looks like the easy route. It is not: Android
 * 10+ refuses to exec anything outside `nativeLibraryDir`, which needs `extractNativeLibs=true` —
 * a manifest flag that applies to **every** `.so` in the app (MediaPipe, MapLibre, Vosk, CPython,
 * whisper, llama) on an APK already around 144 MB. Paying that across the whole app to run one
 * script is the wrong trade, so QuickJS is compiled into `liblcarsnative.so` instead and Python
 * reaches it through JNI. See `app/src/main/cpp/js_jni.cpp`.
 *
 * ⚠️ **THE PRODUCT IS WHAT `console.log` PRINTED.** Read out of yt-dlp's own `_construct_stdin`,
 * the script handed over ends with `console.log(JSON.stringify(jsc(...)))` and the caller does
 * `json.loads(stdout)`. So [eval] returns captured standard output, **not** the value the last
 * statement evaluated to. Getting that backwards produces a runtime that works on every toy script
 * and fails on every real one.
 *
 * ⚠️ **Availability is two questions, not one.** The library may load and still have been built
 * without QuickJS — the CMake tree degrades to an engine-less build when the source tree is absent.
 * [available] answers both, and the provider on the Python side must fall back to extraction
 * without a JS runtime rather than failing when it is false.
 */
object JsRuntime {

    private const val TAG = "JsRuntime"

    /**
     * How long one solve may take.
     *
     * ⚠️ **Measured, not guessed.** Run against the real 2.88 MB YouTube player with real n and sig
     * challenges, quickjs-ng v0.16.0 finished in **7.8 seconds on a desktop x86 core** (node, for
     * reference, took 2.1). A phone core is several times slower than that, so a 30-second budget —
     * the obvious round number, and what a first draft used — would be uncomfortably close to the
     * real figure on the device. This is generous on purpose: the cost of being wrong in this
     * direction is a solve abandoned seconds before it would have succeeded, which presents as
     * YouTube simply not working.
     */
    const val DEFAULT_TIMEOUT_MS: Int = 120_000

    /**
     * What the probe found: whether the engine is usable, and — the part that was missing — WHY
     * not when it is not.
     *
     * ⚠️ **ONE computation feeds both [available] and [status], and that is the point.** A first
     * cut had `available()` return a bare boolean while the Python side rendered its false branch
     * as *"engine not in this build"*. CI **proves** that sentence wrong: the workflow asserts the
     * `JsRuntime_nativeVersion` symbol is in the shipped library, so QuickJS is unambiguously
     * compiled in. The app was stating the opposite of a proven fact, which sent a real diagnosis
     * looking in the wrong place. Deriving both answers from one result makes it impossible for
     * the verdict and the reason to disagree.
     */
    private data class Probe(val usable: Boolean, val detail: String)

    private val probe: Probe by lazy {
        when {
            // The library itself never loaded — nothing about QuickJS is implicated. This is the
            // case that was being misreported, and it is a completely different investigation.
            !NativeBridge.available -> Probe(false, "the native library did not load")
            else -> runCatching {
                if (!nativeAvailable()) {
                    // Compiled in BOTH CMake branches, so it answers honestly rather than throwing;
                    // a false here really does mean the engine was left out of this build.
                    Probe(false, "no JavaScript engine compiled into this build")
                } else {
                    Probe(true, "quickjs " + (runCatching { nativeVersion() }.getOrNull() ?: "?"))
                }
            }.getOrElse {
                Log.w(TAG, "JavaScript engine probe failed: ${it.message}")
                Probe(false, "the engine probe threw: ${it.javaClass.simpleName}: ${it.message}")
            }
        }
    }

    /** True when the shared library loaded **and** QuickJS is actually in it. */
    @JvmStatic
    fun available(): Boolean = probe.usable

    /**
     * One line naming what the engine is, or precisely why it is unreachable.
     *
     * This is the sentence the extractor puts in its notes and the diagnostics screen shows. It is
     * the whole diagnosis for a class of failure that is otherwise invisible, so it must never
     * guess: every branch above states something the code actually established.
     */
    @JvmStatic
    fun status(): String = probe.detail

    /**
     * The engine's own version string, or null when there is no engine.
     *
     * Reported into the extractor's diagnostics rather than logged: when a future extraction fault
     * turns on which engine ran, that is the fact worth having on screen.
     */
    @JvmStatic
    fun version(): String? =
        if (!probe.usable) null else runCatching { nativeVersion() }.getOrNull()

    /**
     * Run [script] and return everything it printed, throwing if it failed.
     *
     * ⚠️ **Throwing rather than returning null is for the caller's sake, and the caller is Python.**
     * The only consumer is `lcars_jsi.py`, which has to turn a failure into a
     * `JsChallengeProviderError` carrying a reason; a null would arrive there stripped of the one
     * thing worth reporting. Chaquopy surfaces a JVM exception as a Python exception with the
     * message intact, so the thrown value is the error channel.
     *
     * A script still running at [timeoutMs] is interrupted and reported as a failure rather than
     * being allowed to hold a thread forever.
     */
    @JvmStatic
    @JvmOverloads
    @Throws(IllegalStateException::class)
    fun evalOrThrow(script: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS): String {
        // The reason, not a generic refusal: this message reaches Python as the provider error.
        check(probe.usable) { "no usable JavaScript engine: ${probe.detail}" }
        val box = arrayOfNulls<String>(1)
        val out = nativeEval(script, timeoutMs, box)
        return out ?: throw IllegalStateException(box[0] ?: "JavaScript failed")
    }

    /** ⚠️ Names and package are encoded into the JNI symbols in `js_jni.cpp`. Moving or renaming
     *  this object breaks the link at RUNTIME, not at build time — nothing checks that they agree. */
    private external fun nativeEval(script: String, timeoutMs: Int, errorOut: Array<String?>): String?

    private external fun nativeAvailable(): Boolean

    private external fun nativeVersion(): String
}
