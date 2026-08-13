# OkHttp (未启用混淆时可忽略)
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn okhttp3.**
-dontwarn okio.**

# sherpa-onnx JNI
-keep class com.k2fsa.sherpa.onnx.** { *; }
