# Aura release protection (R8). Debug builds are not obfuscated.
-repackageclasses 'a'
-allowaccessmodification
-overloadaggressively
-renamesourcefileattribute ''
# No LineNumberTable/SourceFile is kept on purpose: stack traces reveal nothing about the logic.
-assumenosideeffects class android.util.Log {
    public static int v(...); public static int d(...); public static int i(...);
}

# JNI: native entry points and the classes native code looks up by name must keep their names.
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class ai.onnxruntime.** { *; }
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn java.awt.**
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
