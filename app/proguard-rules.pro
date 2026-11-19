# 保留 JNI 入口，避免注入层回调被裁剪
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.runway.assistant.replay.PrivilegedReplayEngine { *; }
-keep class com.runway.assistant.data.** { *; }
