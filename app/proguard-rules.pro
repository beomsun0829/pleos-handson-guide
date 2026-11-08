# Pleos SDKs (Vehicle, TTS, STT, NaviHelper) talk to car services through AIDL/reflection: keep them whole.
-keep class ai.pleos.** { *; }
-keep interface ai.pleos.** { *; }
-keep class android.car.** { *; }
-dontwarn android.car.**
-dontwarn ai.pleos.**
# The guide's own classes: receivers, the Gleo bridge and JSON-built models are referenced by name.
-keep class io.github.beomsun0829.handsonguide.** { *; }
# Kotlin reflection is a declared dependency of the SDK.
-keep class kotlin.reflect.** { *; }
-dontwarn kotlin.reflect.**
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
# Gson (used by the Vehicle SDK for HVAC area discovery): TypeToken subclasses need their generic signature,
# or area discovery fails and A/C, recirculation and air-cleaner reads go to an unsupported area.
-keepattributes Signature
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class com.google.gson.** { *; }
-keepclassmembers,allowobfuscation class * { @com.google.gson.annotations.SerializedName <fields>; }
