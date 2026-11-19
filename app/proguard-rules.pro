# WinLauncher Root — release ProGuard rules.
# The release build currently ships with minifyEnabled false; this file exists so the
# proguardFiles reference in app/build.gradle resolves (BUG-25). Add keep rules here
# before enabling minification, especially for the reflection-driven RootServer entry
# point (ru.winlauncher.root.RootServer.main) loaded via app_process.
