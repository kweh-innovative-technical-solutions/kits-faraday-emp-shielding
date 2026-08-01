# Keep kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.kits.glasses.** {
    *** Companion;
}
-keepclasseswithmembers class com.kits.glasses.** {
    kotlinx.serialization.KSerializer serializer(...);
}
