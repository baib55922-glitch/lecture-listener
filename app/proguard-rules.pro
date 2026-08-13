# OkHttp (未启用混淆时可忽略)
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn okhttp3.**
-dontwarn okio.**

# Vosk JNI
-keep class org.vosk.** { *; }
