# kotlinx.serialization keeps its generated serializers on the companion of each class.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class dev.primeremote.** {
    *** Companion;
}
-keepclasseswithmembers class dev.primeremote.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.primeremote.core.model.** { *; }
