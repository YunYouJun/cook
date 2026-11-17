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
