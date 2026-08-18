# Kotlin serialization
-keepclassmembers class **$$serializer { *; }
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisible*Annotations
-keep class com.omnieditor.core.model.** { *; }

# Keep OmniError sealed subclasses for when() exhaustiveness
-keep class com.omnieditor.core.model.OmniError$* { *; }

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }

# Compose
-dontwarn androidx.compose.**
