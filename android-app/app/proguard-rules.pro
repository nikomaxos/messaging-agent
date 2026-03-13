# Add project specific ProGuard rules here.

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.** { *; }

# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class com.messagingagent.** {
    @kotlinx.serialization.Serializable <methods>;
}

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep libsu
-keep class com.topjohnwu.superuser.** { *; }

# Keep DataStore
-keep class androidx.datastore.** { *; }

# Timber
-assumenosideeffects class timber.log.Timber {
    public static *** v(...);
    public static *** d(...);
}
