# kotlinx.serialization: keep generated serializers for DTOs
-keepclassmembers class **$$serializer { *** INSTANCE; }
-keepclasseswithmembers class * { @kotlinx.serialization.Serializable <fields>; }
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> { static <1>$Companion Companion; }
-if @kotlinx.serialization.Serializable class ** { static **$* *; }
-keepnames class <1>$$serializer { *** INSTANCE; }

# OkHttp / Retrofit
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepattributes Signature, Exceptions, *Annotation*
