# WinLauncher Root

Исходный Android-проект лаунчера и управления окнами для Samsung Galaxy Tab A 9.7 SM‑T555 / Android 7.1.1 (API 25). Здесь находятся root-backed window-management prototype, Desktop activity, Edge Lite и системный AppWidgetHost прямо на рабочем столе. Это не законченный production-клон Windows 11: hidden API и часть системных действий зависят от прошивки и должны пройти проверку на устройстве.

## Статус приёмки

- `PLAN.md` содержит краткую карту scope из ТЗ, включая этапы 0–5 и точные формулировки гипотез Этапа 0 E‑1…E‑10. Проверки E‑1…E‑10 вынесены отдельно в `TEST_PLAN.md`.
- Этап 5 намеренно не выполнялся: он опциональный по исходному запросу.
- **Исходники компилируются настоящим `javac` против `android.jar` (API 35)** — 73 class file, 0 ошибок. Проверка живёт в `tools/compile_check.py` и вызывается как пункт J‑09 из `tools/verify_quality.py`. Это доказывает синтаксис и сигнатуры, но не Gradle/AGP-сборку, не D8 и не работу на устройстве.
- **APK не собран, устройство и runtime не проверены.** Android SDK/Gradle в этом окружении отсутствуют.
- `TEST_PLAN.md` задаёт действия, ожидаемые результаты и Pass/Fail; сейчас все device/runtime cases остаются Not run. `DEVICE_CHECKLIST.md` — ручной чек-лист планшета.
- Signing key не поставляется. Samsung/Android Installer принимает корректную подпись APK, но подписывать приложение Samsung production certificate проект не умеет и не заявляет.
- Последний построчный дебаг — раунд J: `docs/history/JFIXES.md` (дефекты J‑01…J‑08, чистка репозитория). Раунд K — централизация UI: `docs/history/KFIXES.md` (токены `UiTokens`, touch targets 48 dp, inset-модель `SystemInsets`). Раунд L — жизненный цикл моста и владение контроллером: `docs/history/LFIXES.md` (ограниченная очередь `RootBridge`, слияние снапшотов, один snapshot в полёте, idle-сессия 120 с, явная регистрация активного контроллера). История более ранних раундов: `docs/history/`.

## Что есть в исходниках

### Desktop, приложения и виджеты

- `DesktopActivity` — отдельная Android Home activity; `MainActivity` остаётся root/window setup экраном.
- Desktop содержит built-in ярлыки Edge Lite, добавления виджетов, настроек, список launchable apps и контекстное меню фона.
- `AppWidgetHost` встроен непосредственно в `DesktopActivity`: виджеты отображаются в прокручиваемой области рабочего стола поверх обоев. Добавление вызывает системный bind/configure flow с согласием пользователя; IDs сохраняются, long-press виджета предлагает удалить.
- Обои — низкозатратная геометрическая Bloom-inspired иллюстрация из сплошных цветов, с ручным переключением и 60-секундным slideshow; оригинальные Windows wallpaper bitmap-файлы не поставляются.
- Edge Lite использует системный Android WebView, DuckDuckGo для поиска, in-memory вкладки с drag-перестановкой, omnibox, ограниченную URL history и Android DownloadManager. Для публичной папки Downloads требуется runtime-разрешение записи.

### Окна, клавиши и панели

- RootServer/RootBridge, task polling, модель окон, квадратные opaque заголовки, resize handles, MAX/FULLSCREEN/minimize/restore, taskbar и системное меню.
- Snap Layouts, Snap Assist с иконками приложений, SnapGroup, dividers, docked pair stack 3 и виртуальные столы до четырёх. Samsung stack IDs/hidden APIs не подтверждены runtime.
- `Alt+Tab` открывает Task View. Это намеренно упрощённый переключатель: отдельный циклический Windows-style popup не реализован.
- `Ctrl+Shift+Esc` открывает простой `TaskManagerActivity`: список известных WindowController задач с кнопками Restore (для свернутых) и Close. Это не диспетчер процессов/сервисов Android.
- `Win+L` намеренно не реализован. Android `DevicePolicyManager.lockNow()` требует активного `DeviceAdminReceiver` с policy `USES_POLICY_FORCE_LOCK`, а активация администратора требует отдельного явного согласия пользователя. Этот проект не регистрирует и не активирует device-admin. Альтернативный `PowerManager.goToSleep()` требует недоступной обычному приложению signature/privileged permission `android.permission.DEVICE_POWER`. Кроме того, Android может перехватить Win+L раньше Accessibility key filter.
- Остальные действия через KeyService: Win+A/N/I/Tab/Z/1…9/T/Home/D, Ctrl+D, Alt+F4, Alt+Space, Ctrl+Shift+Esc, PrtSc, Win+стрелки, Win+Ctrl+Left/Right, F11/Alt+F11. Global key events зависят от Accessibility и от того, пропускает ли их Android. Esc выходит из FULLSCREEN только при включённой настройке.
- Action Center получает реальные уведомления через `NotificationListenerService` после отдельного системного согласия; плитки управления вызывают ограниченные root-команды.

### Samsung/прошивочно-зависимые root-переключатели

RootServer выполняет/читает следующие действия; наличие root UID не гарантирует, что Samsung `svc`, Settings provider и radio services согласованы:

| Переключатель | Что отправляется / читается | Почему может не сработать на Samsung/API 25 |
|---|---|---|
| Wi‑Fi | `svc wifi enable/disable`; читается `settings global wifi_on` | `svc` subcommand может отсутствовать/отличаться в vendor build; Settings key может быть устаревшим относительно WifiService. |
| Bluetooth | `svc bluetooth enable/disable`; читается `settings secure bluetooth_on` | Bluetooth service shell-команда не является стабильным публичным API Android 7.1.1; Samsung может не реализовать её или требовать vendor Binder/службу. |
| Mobile data | `svc data enable/disable`; читается `settings global mobile_data` | `svc data` зависит от Telephony shell-команд и SIM/CSC/carrier policy; multi-SIM/vendor restrictions могут запрещать переключение. |
| Airplane mode | root записывает `global airplane_mode_on` и отправляет `AIRPLANE_MODE` broadcast; читается тот же setting | Samsung Telephony/radio policy может переопределить setting или не применить broadcast; для system-level radio policy требуются привилегии/внутренние API. |

При неизвестном состоянии UI предлагает системные настройки; ненулевой exit/error не считается успехом. Эти команды нужно сверить с реальным системным состоянием и logcat на конкретной прошивке.

PrtSc сохраняет полноэкранный PNG через RootServer в `/sdcard/Pictures/WinLauncher/` и показывает возвращённый путь либо ошибку. RootBridge передаёт только команду и JSON-ответ с путём; bitmap/Base64 не пересылаются. Это отдельная функция, не связанная с оконными переходами: снимки для анимаций запрещены. Snap Assist сейчас показывает значки приложений, а не снимки окон; будущие миниатюры допустимы только в Snap Assist.

## Лёгкий профиль и визуальные ограничения

Launcher-owned overlays используют сплошные цвета и `PixelFormat.OPAQUE`; фоновые поверхности не прозрачны и не используют альфа-канал. Нет blur, Mica, RenderScript-эффектов, теней elevation или bitmap-снимков в переходах. Оконные операции выполняются сразу, а hover/press feedback — лёгкая смена сплошного цвета. Snap Assist использует значки приложений; снимки окон для миниатюр сейчас не нужны. Если их добавят позднее, они будут ограничены миниатюрами Snap Assist. Edge/resize handles — узкие solid-color indicators. Внешний контент WebView и самих сторонних widgets не контролируется.

## Дизайн-токены и системные отступы

- `UiTokens` — единственный источник палитры, типографики, радиусов, отступов и размеров целей касания для всех overlay и экранов. Цвета названы по роли (`surface`, `textPrimary`, `accentPressed`, `danger`, …), а не по оттенку, и все они непрозрачные: состояния задаются отдельными цветами, а не альфа-блендингом. Переезд темы — правка одного класса, а не правка четырнадцати файлов.
- Основные элементы управления (кнопки Task Manager, Task View, Action Center, fullscreen mini-bar, панель задач, Edge Lite, Desktop, Settings) имеют цель касания **не меньше 48 dp**. Строки контекстных меню (32 dp) и высота заголовка стороннего окна остались компактными: первая закреплена verifier-ом, вторая вычисляется из калибровки Samsung caption.
- `SystemInsets` снимает снимок системных отступов дисплея (status bar, navigation bar, `DisplayCutout` с API 28). `WindowController` владеет им, `workArea()` вычитает status bar, cutout, navigation bar и панель задач, а абсолютно позиционируемые overlay (панель задач, Action Center, системное меню заголовка, Snap group hint, fullscreen mini-bar) привязываются к тем же значениям. Полноэкранные окна и docked pair по-прежнему перекрывают системные панели — как настоящее окно рабочего стола.
- Подробности раунда K — `docs/history/KFIXES.md`.

## Сборка

Нужны Android Studio, JDK 17, Android SDK Platform 35 и установленный Gradle 8.7+ (совместимый с Android Gradle Plugin 8.6.1). Gradle wrapper в исходниках не включён. Сборка `gradle :app:assembleDebug` в этом окружении не выполнялась — Android SDK и Gradle здесь отсутствуют.

```sh
gradle :app:assembleDebug
```

После успешной сборки APK: `app/build/outputs/apk/debug/app-debug.apk`. `minSdk=25`, `targetSdk=25`, compile SDK 35, Java 8 source compatibility. Используйте disposable test apps и тестовый планшет; overlays, Accessibility, notification listener, widget binding и storage требуют пользовательских согласий.

Отдельно от Gradle доступна проверка компиляции без Android Studio — она доказывает, что все 23 исходника собираются, но не заменяет Android-сборку:

```sh
ANDROID_JAR=$ANDROID_HOME/platforms/android-35/android.jar JAVA_HOME=/path/to/jdk \
  python3 tools/compile_check.py
```

## Проверки

```sh
python3 tools/verify_quality.py     # 183 статических проверок + пункт J-09 (реальная компиляция)
python3 tools/compile_check.py      # только компиляция (PASS / SKIP / FAIL)
python3 tools/make_release.py       # собрать архив исходников и напечатать SHA-256
```

Verifier выполняет статические source-coherence assertions, XML/delimiter checks и — если найдены `javac` и `android.jar` — настоящую компиляцию всех исходников. Он **не запускает Android build, APK install или device/runtime tests**. Подробный acceptance-plan: `TEST_PLAN.md`; ручной список Samsung-проверок: `DEVICE_CHECKLIST.md`.

## Структура репозитория

| Путь | Что это |
|---|---|
| `app/src/main/java/ru/winlauncher/` | 23 Java-файла лаунчера, root-моста и оверлеев |
| `app/src/main/java/ru/winlauncher/root/` | `RootServer` — сторона, работающая от root |
| `app/src/main/java/ru/winlauncher/bridge/` | `RootBridge` и запуск `app_process` через `su` |
| `tools/verify_quality.py` | статические проверки + compile gate (J‑09) |
| `tools/compile_check.py` | компиляция всех исходников против `android.jar` |
| `tools/make_release.py` | сборка архива исходников и SHA-256 |
| `docs/history/` | журнал раундов аудита: `AUDIT`, `FIXES` (D), `BUGFIXES`, `WC2FIXES`, `RFIXES`, `FFIXES`, `GFIXES`, `HFIXES`, `IFIXES`, `JFIXES` |

## GitHub-подготовка

Корень проекта — `WinLauncherRoot`; `.gitignore` исключает `local.properties`, IDE/Gradle caches, build outputs, APK/AAB, signing keys и генерируемые ZIP-архивы. Архив исходников больше не коммитится: он собирается из рабочего дерева командой `python3 tools/make_release.py` (печатает SHA-256) — так он не может разойтись с исходниками. Не коммитьте keystore, SDK paths, реальные уведомления, пользовательские screenshots или device logs. Внешние шрифтовые файлы не входят в поставку.
