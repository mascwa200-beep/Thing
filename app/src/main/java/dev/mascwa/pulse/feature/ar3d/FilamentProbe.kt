package dev.mascwa.pulse.feature.ar3d

/**
 * Slice-0 proof that the Google Filament dependency resolves + compiles + packages its arm64 native library
 * into the R8-off release build (CI's assembleRelease is the gate). This is a compile-time reference only —
 * it does NOT initialise the engine or load the native `.so` (that begins in the real renderer). Nothing is
 * wired to any UI yet; the 3D AR renderer lands in the following slices.
 */
internal object FilamentProbe {
    /** The Filament engine class is on the classpath (referenced, never instantiated here). */
    fun engineClass(): Class<*> = com.google.android.filament.Engine::class.java
}
