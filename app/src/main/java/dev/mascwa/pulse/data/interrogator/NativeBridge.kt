package dev.mascwa.pulse.data.interrogator

import android.util.Log

/**
 * The single door between Kotlin and the acoustic interrogator's native layer.
 *
 * ⚠️ **N0 — toolchain proof.** [nativeProbe] does nothing useful; it reports the ABI, API level and
 * compiler that built it. It exists because this repository has never compiled native code — every
 * `.so` it ships (Vosk, JNA, MediaPipe, MapLibre, ExoPlayer) arrives prebuilt inside an AAR — and
 * the development container has neither an NDK nor an Android SDK, while GitHub answers 403 through
 * its proxy. The first compile of anything under `src/main/cpp` therefore happens on a CI runner,
 * roughly 20-35 minutes per attempt, with no local gate at all.
 *
 * Landing that pipeline on its own, before whisper.cpp and llama.cpp are added on top, means the
 * next failure is attributable to one of those two trees rather than to the NDK version, the CMake
 * version, the ABI filter, the AGP wiring or the packaging step.
 *
 * ⚠️ **Loading is best-effort and MUST stay that way.** A missing or unloadable library has to
 * degrade to "the interrogator is unavailable", never to a crash: `System.loadLibrary` throws
 * `UnsatisfiedLinkError`, which is an Error rather than an Exception and so is not caught by the
 * `runCatching` used everywhere else in this codebase. It is caught explicitly here. The same rule
 * will hold when the real engines arrive — a phone that cannot load a 4 GB model still has to run
 * the other forty features.
 */
object NativeBridge {

    private const val TAG = "NativeBridge"
    private const val LIB = "lcarsnative"

    /**
     * True once the shared library is loaded. Read this before calling anything else here.
     *
     * ⚠️ Deliberately not a `by lazy` that throws: callers ask "is the native side there?" far more
     * often than they use it, and the answer has to be cheap and total.
     */
    val available: Boolean = try {
        System.loadLibrary(LIB)
        true
    } catch (e: UnsatisfiedLinkError) {
        // Expected on any build where the native layer did not package — say so once, quietly.
        Log.w(TAG, "native layer unavailable: ${e.message}")
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "native layer refused: ${e.message}")
        false
    }

    /**
     * What the native layer reports about itself, or null when it did not load.
     *
     * The string is assembled from compiler-defined macros on the C++ side, so a real value proves
     * the cross compile happened rather than proving a literal survived.
     */
    fun probe(): String? = if (!available) null else runCatching { nativeProbe() }.getOrNull()

    /** ⚠️ Name and package are encoded into the JNI symbol in `lcars_native.cpp`. Moving or renaming
     *  this class breaks the link at RUNTIME, not at build time — nothing checks that they agree. */
    private external fun nativeProbe(): String
}
