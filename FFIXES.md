# WinLauncher Root — исправления F-01…F-15 (поверх rfix-версии)

Метод: правки реализованы агентом Arena в agent-режиме (сессия
https://arena.ai/agent/01a1f39e-0adb-7614-8dd4-b8a12f6f5dc9, код передан текстом
6 частями), затем каждая правка проверена и интегрирована локально вручную.
Аудированная/rfix-копия исходников не тронута; работа велась в `winlauncher_ffix/`.

## Что исправлено

- **F-01 (критическая) — RootServer, SIGPIPE.** Все дочерние процессы переведены на
  единый хелпер `runProcess()` (ProcessBuilder, `redirectErrorStream(true)`): stdin
  закрывается сразу, объединённый поток вычитывается до EOF, только потом `waitFor()`,
  в `finally` — закрытие потока и `destroy()`. Через него переписаны `execAndWait`,
  `captureTo`, `enableAccessibilityService`, `readSetting`; старый `closeStreams()`
  удалён полностью (других вызовов не было). Поведенческая проверка на JVM: старый
  паттерн на выводящей команде даёт exit=141 (SIGPIPE), новый — настоящий код выхода
  (3) и все 1 288 900 байт вывода.
- **F-02 (высокая) — утечка окон Task View / Action Center.** `show()` в обоих классах
  теперь начинает с `dismissActive()` вместо `dismiss()`. FullscreenEdgeManager больше
  не создаёт собственные экземпляры: левый край — `DockService.taskView()`, правый —
  `ActionCenterOverlay.showSingleton(context)`. Отступление от буквы отчёта: оставлен
  fallback-экземпляр TaskViewOverlay на случай, если DockService не поднят (иначе левый
  край в FULLSCREEN просто перестал бы работать); баг-сирота при этом закрыт статическим
  dismiss.
- **F-03 (высокая) — перенос окна на другой стол.** `moveWindowToDesktop()` переносит
  всю SnapGroup целиком (desktopId + `prefs.setTaskDesktop` у всех участников), а при
  уходе с текущего стола добавляет id видимых участников в
  `prefs.virtualDesktopHiddenTasks()` — переключение на целевой стол теперь находит и
  восстанавливает окно (кейс V-03).
- **F-04 (высокая) — выбор окна с другого стола.** В TaskViewOverlay добавлен
  `ensureDesktop(w)` перед focus/restore и перед сборкой docked-пары; в
  TaskManagerActivity кнопка «Вернуть» сначала переключает стол. Окно больше не
  сворачивается обратно на ближайшем тике reconcile.
- **F-05 (средняя) — RootServerLauncher.** В исходнике теперь `printf '%s\\n'`: в
  `su -c` не попадает сырой перевод строки. Проверка симуляцией POSIX sh с поддельным
  app_process: токен из 64 символов доставлен целиком, временный файл удалён.
- **F-06 (средняя) — RootServer.** `readSetting()` возвращает `value.trim()`;
  settings-ветка `setStatusBarHidden()` обёрнута в try/catch → `settingsApplied=false`,
  успех IStatusBarService больше не затирается исключением settings.
- **F-07 (средняя) — KeyService.** Ctrl+Shift+Esc переведён на ACTION_DOWN +
  swallowedKeys. Проверка освобождения swallowedKeys поднята выше всех ранних
  возвратов (controller/taskView), поэтому залипания при остановке контроллера нет.
  Заодно Esc теперь закрывает и jump-меню панели задач (F-13).
- **F-08 (средняя) — DesktopActivity.** `widgetScroll.setFillViewport(true)`; меню фона
  защищено от двойного открытия полем `backgroundMenu` (touch/generic-motion/context-
  click); при `info == null` в renderWidgets вызывается `widgetHost.deleteAppWidgetId(id)`.
- **F-09 (средняя) — тёмная тема.** EdgeLiteActivity: omnibox получил явные
  text/hint-цвета по теме. LauncherSettingsActivity: `initialDark` + `recreate()` при
  смене темы (чекбокс и onResume), в `populateApps` добавлен guard
  `isFinishing() || isDestroyed()`.
- **F-10 (средняя) — DockService / MainActivity.** Автозапуск заголовков в
  `startOwnedOverlays()` закрыт флагом `DockService.chromeRequested`: шаг 3 поднимает
  только мониторинг, taskbar и края; флаг ставят шаги 4 (true) и 5 (false).
  MainActivity запоминает `appliedDark` и делает `recreate()` в onResume при смене темы.
- **F-11 (средняя) — ChromeManager.** `boolean dragInProgress` заменён на
  `int draggingTaskId` + `activeDragTouch`; `removeChrome(id)` при совпадении сбрасывает
  drag и снимает отложенные колбэки DragTouch — z-order больше не зависает.
- **F-12 (средняя) — FullscreenEdgeManager.** Мини-заголовок: `FLAG_WATCH_OUTSIDE_TOUCH`
  (тап снаружи → hideMini), автоскрытие через 3,5 с (таймер перевзводится при
  hover/touch), `hideMini()` в обработчиках min/restore/close и при жесте заголовка.
- **F-13 (низкая) — TaskbarOverlay.** Активная кнопка в светлой теме получает белый
  текст; PopupWindow jump-меню хранится в поле, закрывается при повторном открытии, в
  `disable()` и по Esc (`dismissJumpMenuForEscape()`).
- **F-14 (низкая) — WindowController.** В обе stale-ветки minimizeSingle добавлен
  `clearStaleMinimizeState(w)` (условие: `windows.get(taskId) == w && state != MINIMIZED`)
  — устаревшая запись `task_<id>` в prefs не переживёт прерванное сворачивание.
- **F-15 (низкая).** TaskViewOverlay: `LinearLayout.LayoutParams` вместо
  `FrameLayout.LayoutParams` (старый вариант на устройстве, по всей видимости, ронял
  Task View ClassCastException при замере — проверится первым открытием на планшете).
  TaskManagerActivity: пустой текст красится по тёмной теме.

## Поймано при интеграции (не из отчёта)

Два дефекта того же класса, что BUG-01/BUG-02, внесённые уже при самой интеграции
(строчный `//`-комментарий в середине сжатой строки): в DesktopActivity комментарий
после `setFillViewport(true);` вырезал создание widgetContainer, в EdgeLiteActivity —
`toolbar.addView(omnibox)`. Первый поймала проверка «no live code after //» из
verify_quality, второй — ручной аудит хвостов строк. Оба исправлены, APK пересобран
после исправлений.

## Проверки

- `javac` (android-35, source/target 8): 0 ошибок; классов 70 против 68 у rfix
  (+`RootServer$ProcessResult`, +анонимный Runnable автоскрытия мини-заголовка).
- `tools/verify_quality.py`: **122/122 PASS** (107 прежних + 15 новых F-якорей).
  Шесть старых якорей обновлены под новые механизмы (R-02 — текст stale-веток с F-14;
  BUG-01/13/16/18 и проверка краевых зон — под runProcess, draggingTaskId,
  ensureDesktop и showSingleton); смысл проверок сохранён.
- F-01: JVM-тест старого и нового паттернов (141 против реального кода + полный дренаж).
- F-05: POSIX sh-симуляция запуска RootServer (токен 64/64, временный файл удалён).
- APK: подпись debug-сертификата валидна, badging `ru.winlauncher`, versionCode 1,
  versionName по-прежнему `0.2-window-core`, min/target SDK 25, classes.dex на месте.

## Артефакты

- `WinLauncherRoot-ffix-debug.apk` — 127 460 байт, SHA-256 `972d990b…`
- `WinLauncherRoot-ffix-source.zip` — исходники со скрытыми файлами (.gitignore внутри)

## Что осталось за планшетом

Как и раньше: на реальном SM-T555 ничего не запускалось, все кейсы TEST_PLAN.md —
«Not run». F-01 закроется первым же включением авиарежима с работающим RootServer,
F-15 — первым открытием Task View. Гипотезы E-1…E-10, BUG-15 (stackRank, нужен E-5) и
ограничение WC2-01 остаются открытыми до устройства.
