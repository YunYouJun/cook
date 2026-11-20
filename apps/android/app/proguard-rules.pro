# Preserve kotlinx.serialization serializers & Room generated classes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keep,includedescriptorclasses class cn.yunyoujun.cook.**$$serializer { *; }
-keepclassmembers class cn.yunyoujun.cook.** {
    *** Companion;
}
-keepclasseswithmembers class cn.yunyoujun.cook.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ONNX Runtime creates Java tensor and metadata objects through JNI.
-keep class ai.onnxruntime.** { *; }

# ML Kit discovers these registrars from manifest metadata and calls their constructors reflectively.
-keep class com.google.mlkit.** implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}
