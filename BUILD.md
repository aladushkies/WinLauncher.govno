# Сборка APK из этого архива

Архив содержит всё, что нужно для сборки: `settings.gradle`, `build.gradle`,
`app/build.gradle`, `app/proguard-rules.pro`, `gradle.properties`, манифест, ресурсы
(`app/src/main/res/`) и все 23 Java-исходника. Ничего докачивать из репозитория не нужно.

## 1. Требования

| Компонент | Версия | Зачем |
|---|---|---|
| JDK | **17** | Android Gradle Plugin 8.6.x требует JDK 17 |
| Gradle | **8.7 или новее** | совместимая версия для AGP 8.6.1 |
| Android SDK Platform | **35** | `compileSdk 35` в `app/build.gradle` |
| Android SDK Build-Tools | **35.x** | стандартная связка с Platform 35 |
| Platform-Tools | любая свежая | `adb` для установки на устройство |

Gradle wrapper (`gradlew`) **не включён** в исходники — используйте локально установленный
Gradle. Если нужен wrapper, сгенерируйте его один раз:

```sh
gradle wrapper --gradle-version 8.7
```

## 2. Указать путь к SDK

Два равнозначных способа.

**Вариант A — `local.properties`** (файл не входит в архив, он машинно-специфичен):

```properties
sdk.dir=/путь/к/Android/Sdk
```

**Вариант B — переменная окружения** (файл не нужен вовсе):

```sh
export ANDROID_HOME=/путь/к/Android/Sdk      # либо ANDROID_SDK_ROOT
```

## 3. Сборка

```sh
cd WinLauncherRoot
gradle :app:assembleDebug
```

Готовый APK: `app/build/outputs/apk/debug/app-debug.apk`.

Release-сборка (`gradle :app:assembleRelease`) даёт **неподписанный** APK: keystore
намеренно не поставляется, подпись нужно сделать локально своей ключевой парой.

## 4. Установка на устройство

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Приложение требует root (запуск `RootServer` через `su`/`app_process`), разрешения
`SYSTEM_ALERT_WINDOW`, согласия Accessibility Service для `KeyService` и Notification
Listener Access для `LauncherNotificationListener`. Полный порядок запуска — в
`ADB_FIRST_RUN.md`, ручной чек-лист планшета — в `DEVICE_CHECKLIST.md`.

## 5. Предполётная проверка без Android Studio

В архиве есть проверки, которым не нужен ни SDK, ни Gradle — они доказывают, что
исходники собираются, до того как вы потратите время на полную сборку:

```sh
# 1) статические source-coherence проверки + настоящая компиляция javac
ANDROID_JAR=$ANDROID_HOME/platforms/android-35/android.jar JAVA_HOME=/путь/к/jdk17 \
  python3 tools/verify_quality.py          # ожидается 183/183 PASS

# 2) только компиляция (PASS / SKIP / FAIL)
ANDROID_JAR=$ANDROID_HOME/platforms/android-35/android.jar JAVA_HOME=/путь/к/jdk17 \
  python3 tools/compile_check.py           # ожидается 73 class file, 0 ошибок
```

## 6. Что специально НЕ входит

* `local.properties` — путь к SDK машинно-специфичен;
* keystore и любые ключи подписи;
* APK/AAB, собранные артефакты и кэши Gradle;
* Gradle wrapper;
* иконка лаунчера: в манифесте нет `android:icon`, система использует штатную иконку
  приложения — отдельный `mipmap`-ресурс не нужен и не требуется.
