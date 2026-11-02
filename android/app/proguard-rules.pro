# RegionX Android —— 保留 Compose / Kotlin 序列化相关符号
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontwarn org.jetbrains.annotations.**

# kotlinx.serialization
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.regionx.app.**$$serializer { *; }

# OkHttp 平台提示（仅警告）
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
