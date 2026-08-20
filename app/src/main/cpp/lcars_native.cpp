// N0 — the toolchain proof for the acoustic interrogator's native layer.
//
// One JNI entry point that reports what compiled it. It exists to answer a single question that
// nothing else in this repository can answer: does an NDK/CMake build actually wire up, cross
// compile for arm64-v8a, and land inside the release APK?
//
// ⚠️ Everything about this is first-compile-in-CI. The development container has no NDK and no
// Android SDK, and GitHub answers 403 through its proxy, so neither cross compilation nor cloning
// the upstreams is possible locally. Proving the pipeline with a file that cannot fail for
// interesting reasons means the next failure is attributable to whisper.cpp or llama.cpp rather
// than to the plumbing.
//
// The returned string is deliberately built from compiler-defined macros rather than hardcoded:
// it reports the real ABI, the real NDK toolchain and the real build date, so the value proves the
// build happened rather than merely that a literal survived.

#include <jni.h>
#include <string>

// ⚠️ JNI symbol names encode the full package path. If the Kotlin class is ever moved or renamed,
// this function stops being found and the failure surfaces at RUNTIME as UnsatisfiedLinkError on a
// device, not at build time — nothing in the toolchain checks that the two sides agree. Keep the
// Kotlin declaration in dev/mascwa/pulse/data/interrogator/NativeBridge.kt in step with this name.
extern "C" JNIEXPORT jstring JNICALL
Java_dev_mascwa_pulse_data_interrogator_NativeBridge_nativeProbe(JNIEnv *env, jobject /*thiz*/) {
    std::string out;

#if defined(__aarch64__)
    out += "arm64-v8a";
#elif defined(__ARM_ARCH_7A__)
    out += "armeabi-v7a";
#elif defined(__i386__)
    out += "x86";
#elif defined(__x86_64__)
    out += "x86_64";
#else
    out += "unknown-abi";
#endif

#if defined(__ANDROID_API__)
    out += " api=" + std::to_string(__ANDROID_API__);
#endif

#if defined(__clang_major__)
    out += " clang=" + std::to_string(__clang_major__) + "." + std::to_string(__clang_minor__);
#endif

    out += " cpp=" + std::to_string(__cplusplus);
    out += " built=" __DATE__;

    return env->NewStringUTF(out.c_str());
}
