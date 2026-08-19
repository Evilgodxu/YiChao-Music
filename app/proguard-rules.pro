# ============================================================
# 基础规则：保留行号信息用于 Release 崩溃堆栈分析
# ============================================================
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ============================================================
# Kotlin Serialization（官方推荐规则）
# https://github.com/Kotlin/kotlinx.serialization/blob/master/rules/common.pro
# ============================================================
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    static ** Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-dontnote kotlinx.serialization.**
-dontwarn kotlinx.serialization.internal.ClassValueReferences
-keepclassmembers public class **$$serializer {
    private ** descriptor;
}

# ============================================================
# Koin 依赖注入（官方推荐规则）
# https://github.com/InsertKoinIO/koin/blob/main/projects/android/koin-android/proguard-rules.pro
# ============================================================
-keep class * extends org.koin.core.module.Module { *; }
-keepclassmembers class * {
    @org.koin.core.annotation.* *;
}

# ============================================================
# Navigation Compose 类型安全 API
# ============================================================
-keep class * implements androidx.navigation.NavType { *; }
-keepclassmembers class * {
    @androidx.navigation.NavType <fields>;
}
