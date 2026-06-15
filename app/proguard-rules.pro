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
