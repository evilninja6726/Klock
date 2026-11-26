# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.klock.app.**$$serializer { *; }
-keepclassmembers class com.klock.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.klock.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Google Tink (AndroidX Security Crypto compile-time annotations)
-dontwarn com.google.errorprone.annotations.**
