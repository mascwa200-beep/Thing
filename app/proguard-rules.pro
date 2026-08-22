# ---- Kotlinx Serialization ----
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
# Keep generated serializers / companions
-keepclassmembers class **$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class * {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.mascwa.pulse.**$$serializer { *; }
-keep @kotlinx.serialization.Serializable class dev.mascwa.pulse.** { *; }

# ---- Retrofit / OkHttp ----
-keepattributes Signature, Exceptions
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# ---- Models (serialized via reflection-free kotlinx) ----
-keep class dev.mascwa.pulse.core.network.dto.** { *; }
-keep class dev.mascwa.pulse.data.**.dto.** { *; }

# ---- Coroutines ----
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# ---- WorkManager (default rules are bundled; keep our worker entry points) ----
-keep class dev.mascwa.pulse.notifications.** { *; }

# ---- Compose tooling (debug only, harmless to keep) ----
-dontwarn androidx.compose.**

# ============================================================================
# R8 is ENABLED on the shipped build (owner-approved). Everything below keeps
# the reflection-, JNI- and serialization-driven surfaces R8 cannot see, so the
# rest (notably unused material-icons-extended) shrinks safely. Broad keeps are
# deliberate: correctness on a no-local-device build beats maximal shrinking.
# ============================================================================

# ---- App components looked up dynamically / by class name ----
# (Manifest components are auto-kept by AGP; these cover string/reflection lookups.)
-keep class dev.mascwa.pulse.widget.** { *; }     # widget providers matched via ::class.java.name
-keep class dev.mascwa.pulse.security.** { *; }    # device-admin receiver + wifi/keystore

# ---- Room (core:database) ----
-keep class dev.mascwa.pulse.data.jarvis.db.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.**

# ---- MediaPipe LLM inference (JNI + protobuf) ----
-keep class com.google.mediapipe.** { *; }
-keep class mediapipe.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn mediapipe.**
-dontwarn com.google.protobuf.**
-dontwarn com.google.auto.value.**
-dontwarn autovalue.shaded.**
-dontwarn javax.lang.model.**

# ---- Chaquopy (embedded CPython; the whole bridge is JNI + reflection) ----
# ⚠️ Not optional, and the failure mode is the dangerous one: without these R8 renames or removes
# classes the native interpreter resolves BY NAME at runtime, so the build goes green, the APK ships,
# and Python fails on the device. Nothing in CI could catch that — which is exactly why the keep is
# broad. The runtime is a handful of classes; there is nothing worth shrinking here.
-keep class com.chaquo.python.** { *; }
# StaticProxy/PyProxy subclasses are instantiated from Python, so their members are unreachable to R8.
-keep class * extends com.chaquo.python.PyObject { *; }
-keep class * implements com.chaquo.python.PyProxy { *; }
-keepclassmembers class * implements com.chaquo.python.PyProxy { *; }
-dontwarn com.chaquo.python.**

# ---- OUR OWN classes the bundled Python resolves BY NAME (app/src/main/python/*.py) ----
#
# ⚠️ THIS IS THE GAP THE BLOCK ABOVE LEFT OPEN, AND IT SHIPPED BROKEN FOR MONTHS. Those rules keep
# Chaquopy's runtime and anything extending PyObject / implementing PyProxy. They do NOT cover an
# ordinary app class that Python reaches through `jclass('fully.qualified.Name')` — which is
# neither. R8 saw no Kotlin reference to `JsRuntime` (only Python names it, in a string, in an
# asset) so it renamed the class away, and on the device the lookup threw NoClassDefFoundError.
#
# The consequence was not subtle and was not obvious: no JS runtime meant yt-dlp could not
# transform YouTube's `n` parameter, so every signed media URL came back 403 and THEATER could not
# play anything. CI passed throughout — its native gate reads `nm -D` for the C++ JNI symbol, which
# R8 cannot touch, so the engine really was in the .so while the class binding to it was not in the
# DEX. Five rounds of debugging went past it.
#
# `{ *; }` is load-bearing at BOTH ends: Python calls the @JvmStatic
# available()/status()/version()/evalOrThrow(), and JNI binds nativeEval/nativeAvailable/
# nativeVersion by name from C++. Renaming either half breaks the bridge.
-keep class dev.mascwa.pulse.data.media.JsRuntime { *; }
# Same failure, second instance: lcars_extract.py reads VERSION_CODE off this reflectively for the
# build stamp in the diagnostics, and R8 removes BuildConfig by default once its constants are
# inlined. That is why the stamp printed `apk ?` rather than a number.
-keep class dev.mascwa.pulse.BuildConfig { *; }
#
# ⚠️ Adding a `jclass('...')` lookup to the Python WITHOUT a keep here is the same bug again. The
# "Verify Python's Java lookups survived R8" step in android-build.yml exists to catch exactly
# that: it derives the names from the Python sources and asserts each one is in the shipped DEX.

# ---- Vosk + JNA (native STT; JNA binds native via reflection/proxies) ----
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keep class * extends com.sun.jna.** { *; }
-keep interface * extends com.sun.jna.Library { *; }
-keepclassmembers class * extends com.sun.jna.Structure { *; }
-dontwarn org.vosk.**
-dontwarn com.sun.jna.**
-dontwarn java.awt.**

# ---- MapLibre GL Native (JNI renderer calls back into Java) ----
-keep class org.maplibre.android.** { *; }
-keep class com.mapbox.** { *; }
-dontwarn org.maplibre.**
-dontwarn com.mapbox.**

# ---- Spotify App Remote + Gson (reflection-based protocol models) ----
-keep class com.spotify.** { *; }
-dontwarn com.spotify.**
-keep class com.google.gson.** { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-dontwarn com.google.gson.**

# ---- luaj (Lua interpreter; reflection for luajava bindings) ----
-keep class org.luaj.** { *; }
-dontwarn org.luaj.**
-dontwarn javax.script.**
-dontwarn java.lang.management.**

# ---- OkHttp / Okio optional security providers (avoid missing-class build errors) ----
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- Common optional annotations referenced by various libs ----
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.**
-dontwarn org.codehaus.**

# ---- Enums (keep values()/valueOf for kotlinx.serialization of enum fields) ----
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
