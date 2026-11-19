# Раунд H — исправления по повторному аудиту сборки gfix

База: `winlauncher_gfix` (verify 126/126). Работа велась тремя параллельными агентами Arena (H/M/U), код каждого пакета сверен с реальными исходниками и интегрирован локально с точечными правками. Итог: **verify_quality 147/147** (126 старых + 21 новый якорь; 4 старых якоря обновлены под новые механизмы), **javac 0 ошибок**, APK собран ручным SDK-пайплайном.

APK: `WinLauncherRoot-hfix-debug.apk`, 131 556 байт, SHA-256 `722a8054166e0050266ab605700c69e3011fc62b0771fbfaf6db0b9996451bb9`, package `ru.winlauncher`, versionName по-прежнему `0.2-window-core`, min/target SDK 25, debug-подпись.

## Блокеры логики (H)

- **H-01 (critical)** — поворот экрана и подключение мыши при свёрнутом MainActivity: в манифест DockService добавлен `configChanges`, в DockService — `onConfigurationChanged` и `InputManager.InputDeviceListener`; в `updateDisplayGeometry` при смене размера все MAXIMIZED-окна переподгоняются под новую рабочую область.
- **H-02** — отложенный detach (WC2-11) больше не выселяет нового владельца ячейки снапа: удаление только если `tasks.get(cell) == taskId`; у вытесняемого окна членство очищается до restore.
- **H-03** — первый ACTION_MOVE из MAXIMIZED только восстанавливает окно (без requestMove в том же событии); ошибка resize «superseded by newer resize» в moveToStackThenResize считается штатной, без отката и репорта.
- **H-04** — `draggingTaskId` выставляется при старте перетаскивания, а не на ACTION_DOWN; пропущенная из-за драга перестройка z-order помечается `zOrderDirty` и выполняется в `finishDragZOrder()` на UP/CANCEL/removeChrome.
- **H-05** — при видимом FULLSCREEN-окне ChromeManager снимает весь хром, а SnapController и DockedPairManager прячут разделители (`syncVisibility` из `notifyListeners`).
- **H-06** — пункт «Восстановить» в jump-меню: MINIMIZED → `restore`, иначе `restoreNormal`.

## Средняя логика (M)

- **M-01** — MainActivity в onResume переподключает контроллер сервиса после recreate()/поворота.
- **M-02** — системное меню Chrome закрывает предыдущий попап; все три PopupWindow (Chrome, Snap layouts, Taskbar jump) на слое `TYPE_SYSTEM_ALERT`; дубль правого клика (OnContextClickListener на dragArea) удалён.
- **M-03** — единый `WindowController.activateWindow(Win)`: Task View и Диспетчер задач больше не делают двойной restore; вход в docked-пару бампит operationSequence обоих окон (`nextOperation` сделан package-visible).
- **M-04** — ResizeGripManager: окно драга фиксируется на ACTION_DOWN, ACTION_CANCEL откатывает к стартовым границам (через `requestMove` — двухаргументного `requestBounds` в контроллере нет), LauncherPrefs кэшируется в поле.
- **M-05** — `hasAssist()` требует живой dim; клик по кандидату Assist проверяет живость группы; snap из layouts только при отпускании внутри превью.
- **M-06** — временная панель задач не прячется под открытым jump-меню (hideRunnable продлевает показ); `remove()` сначала закрывает меню.
- **M-07** — четыре мелочи: мини-панель FullscreenEdge прячется при смене окна/размера; `abortPending` чистит pendingEnter, а провал первого трансфера пары возвращает окно в исходный стек (не в docked); устаревший набор Show Desktop очищается; plain F11 требует !ctrl/!shift/!win, Alt+Space и Win+Z не работают при выключенном хроме (`ChromeManager.isChromeEnabled()`).

## UI (U)

- **U-01** — виджеты рабочего стола обёрнуты в FrameLayout с тонкой верхней полосой и кнопкой «×»: удаление доступно, даже когда кликабельные RemoteViews перехватывают long-press.
- **U-02** — MainActivity: вся страница в ScrollView, статус минимум 96dp; в настройках ListView стал корнем, блок настроек — его header (раньше список сжимался в 0px в ландшафте).
- **U-03** — карточка Task View кликабельна (тапы не проваливаются в оболочку), высота скроллера — min(300dp, полэкрана), отступы у строк, prev/next недоступны на границах, кнопка пары активна только при ровно двух выбранных окнах.
- **U-04** — кнопки панели задач в HorizontalScrollView с отступами, часы закреплены справа.
- **U-05** — Action Center: открытие настроек закрывает панель, `FLAG_WATCH_OUTSIDE_TOUCH` + закрытие по касанию снаружи, карточки уведомлений — maxLines 3, ellipsize END, отступы.
- **U-06** — диалог «Приложения» закрывается при запуске и в onDestroy; плитки — одним горизонтальным рядом; полоса-псевдопанель следует за темой; `bindWidget` сначала пробует тихий `bindAppWidgetIdIfAllowed` (успех доводит тот же save/configure-поток, что и интент).
- **U-07** — Edge Lite: узкие кнопки без обрезки глифов (minWidth 0, padding 0), вкладка знает URL сразу при создании, загрузки именуются через `URLUtil.guessFileName` с contentDisposition, перерисовка при смене темы в onResume; Диспетчер задач: кнопка «Закрыть» без ALL CAPS и по ширине контента, тема в onResume.
- **U-08** — разделители снапа и пары учитывают тёмную тему; заголовок мини-панели — одна строка с ellipsize и CENTER_VERTICAL, ховер/тач по кнопкам перевзводит автоскрытие, мини-панель не залезает под видимый статус-бар; в настройках тёмный цвет пакета 0xffaaaaaa, отступ у блока имён, сортировка по кэшированным подписям (один Binder-вызов на приложение).

## Отклонения от выводов агентов (сознательные)

- `WindowController.refreshUi()` уже существовал — дубль из пакета H не вносился.
- H-05: вместо occlusion-варианта агента взята более простая transition-логика syncVisibility из отчёта.
- M-04: откат по CANCEL идёт через `requestMove` (метода `requestBounds(Win, Rect)` в контроллере нет).
- U-02 для MainActivity — минимальная версия (ScrollView + minHeight), без полной перестройки buildUi.
- U-04: из пакета U взят только render() панели задач; обрезанный агентом create() не использовался.
- U-06 bindWidget: агент возвращался молча после тихого бинда; интеграция доводит save/configure, как интент-путь.

## Честные ограничения

- Всё по-прежнему **только статика**: на реальном SM-T555 ничего не запускалось, все пункты TEST_PLAN.md остаются «Not run», E-1…E-10 не закрыты.
- Следующий шаг — первый запуск на планшете по ADB_FIRST_RUN.md.
