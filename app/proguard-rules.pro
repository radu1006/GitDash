# --- kotlinx.serialization -------------------------------------------------
# Keep generated serializers and the serializable model classes for release builds.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class com.agentroost.gitdashboard.github.model.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.agentroost.gitdashboard.github.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.agentroost.gitdashboard.**$$serializer { *; }
-keepclassmembers class com.agentroost.gitdashboard.** {
    *** Companion;
}
-keepclasseswithmembers class com.agentroost.gitdashboard.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- OkHttp / Retrofit -----------------------------------------------------
# Retrofit uses reflection on interface annotations and generic signatures.
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-dontwarn javax.annotation.**

# --- androidx.security:security-crypto ------------------------------------
-keep class androidx.security.crypto.** { *; }
