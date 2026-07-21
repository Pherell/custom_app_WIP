# DJI MSDK V5 ProGuard Rules
-keep class dji.v5.** { *; }
-keep class dji.sdk.** { *; }
-keep class com.cySdkyc.** { *; }
-keep class com.dji.sdk.** { *; }

-dontwarn dji.v5.**
-dontwarn com.cySdkyc.**
-dontwarn com.dji.sdk.**

# Keep native methods and their classes
-keepclasseswithmembernames class * {
    native <methods>;
}

-keepclassmembers class * {
    native <methods>;
}

-keep class * extends android.app.Application {
    *;
}
