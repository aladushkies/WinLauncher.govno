# RootServer is loaded by app_process from the APK. Preserve its entry point and helpers.
-keep class ru.winlauncher.root.** { *; }
-keep class ru.winlauncher.bridge.** { *; }
