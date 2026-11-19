# WinLauncher Root — исправления по аудиту WindowController.java (строки 351–1090)

**Источник находок:** независимый статический аудит Arena (agent mode) фрагмента WindowController.java,
строки 351–1090 — диапазон, не покрытый предыдущими аудитами. ID: WC2-01…WC2-19.
**База:** версия после BUG-01…BUG-25 (`winlauncher_bugfix/`, verify_quality 84/84).
**Работа:** копия в `winlauncher_wc2/`; база не редактировалась. Каждая находка сверена с реальным кодом.

## Итоги проверок

- `tools/verify_quality.py`: **102/102 PASS** (84 прежних + 18 новых проверок WC2).
- `javac` против android-35 (source/target 8): **0 ошибок**, 72 класса.
- APK собран ручным пайплайном (aapt2 → javac → D8 `--min-api 25` → zipalign → apksigner, debug keystore):
  `WinLauncherRoot-wc2-debug.apk`, package `ru.winlauncher`, versionCode 1, versionName `0.2-window-core`,
  minSdk/targetSdk 25, подпись проверена (`apksigner verify` OK, CN=Android Debug).
- На реальном SM-T555 ничего не запускалось; все кейсы TEST_PLAN остаются **Not run**.

## Статус по находкам

| ID | Серьёзность (Arena) | Статус | Что сделано |
|---|---|---|---|
| WC2-01 | High | **Исправлено частично** (достижимая часть) | См. ниже |
| WC2-02 | Medium | Исправлено | Фильтр поддерживаемых стеков в adoptTasks применён и к существующим окнам |
| WC2-03 | Medium | Исправлено | NORMAL-окно после внешнего ресайза обновляет normalBounds |
| WC2-04 | Medium | Исправлено | stateSignature включает packageName, component, title, snapGroupId |
| WC2-05 | Medium | Исправлено | Удаление задачи сбрасывает keyboardModeTarget (+ чистка showDesktopRestoringIds/pendingDesktopPairExitIds) |
| WC2-06 | High | Исправлено | per-window operationSequence + проверка `windows.get(taskId)==w` в асинхронных продолжениях |
| WC2-07 | Conditional High | **Уже безопасно** (подтверждено по коду, без изменений) | RootBridge доставляет колбэки на main thread |
| WC2-08 | Medium | Исправлено | focus() для MINIMIZED идёт через restore(); null-guard |
| WC2-09 | Medium | Исправлено | Очередь повтора Win+D на время restore (showDesktopRestoringIds + queued toggle) |
| WC2-10 | Medium | Исправлено | fullscreen(): `dockedPair.exit(w, () -> fullscreen(w))` |
| WC2-11 | Medium | Исправлено | snap detach отложен до подтверждения операции (maximize/fullscreen/restoreNormal/restoreToNormal/close) |
| WC2-12 | High | Исправлено | fullscreen(): коммит модели только после успешного resize; при неудаче — откат стека + refreshTasks |
| WC2-13 | High | Исправлено | moveToStackThenResize(): при неудаче resize — moveToStack обратно + refreshTasks(null) |
| WC2-14 | Medium | Исправлено | restore(): групповой branch всегда завершает метод |
| WC2-15 | Medium | Исправлено | При неудаче requestBounds requestedBounds откатывается к подтверждённым bounds |
| WC2-16 | Medium | Исправлено | fallbackNormalBounds(): литералы 640/480/240/160 обёрнуты в dp() |
| WC2-17 | Low | Исправлено | Центрирование fallback-окна с учётом work.left/top |
| WC2-18 | Low | Исправлено | readBounds/readConfirmedBounds: строгий парсинг, проверка положительного размера |
| WC2-19 | Low/cond. | Исправлено | Кэш подписей по пакету; resolveTitle на существующей строке — только при смене identity |

## Детали по ключевым находкам

### WC2-01 — переиспользование taskId (частично)
Полноценный stable task-instance key недостижим: RootServer отдаёт по сокету только числовой taskId,
component и bounds — поля «время создания задачи» в протоколе нет. Реализована достижимая часть:
в adoptTasks() для существующей строки сравниваются package/component с сохранёнными; при смене
identity («задача за этим id теперь другая») старое состояние принудительно retire-ится:
`prefs.clearSavedWindow/clearTaskDesktop`, чистка pendingMinimizeIds/showDesktopRestoreIds/
showDesktopRestoringIds/pendingDesktopRestoreIds, сброс keyboardModeTarget, detach из snap-группы,
state/stateBeforeMinimize → NORMAL, minimizeRestoreBounds/normalBounds очищаются, desktopId
перечитывается из prefs. Ограничение: если taskId переиспользован тем же самым пакетом и компонентом
до следующего poll, смена identity не видна — это честное ограничение протокола моста.

### WC2-06 — operationSequence
В `Win` добавлено поле `operationSequence`; в WindowController — `nextOperation(w)` /
`operationCurrent(w, op)` (проверка `windows.get(taskId) == w && w.operationSequence == op`).
Проверки расставлены в асинхронных продолжениях: fullscreen (moveToStack и resize), minimizeSingle
(основной moveToStack и fallback), close (closeTask), moveToStackThenResize (moveToStack и resize —
покрывает также restore/maximize/restoreNormal/restoreToNormal/applySnapBounds),
ensureMinimizedParked (оба moveToStack). Удалённое окно ловится проверкой identity: переиспользованный
taskId получает новый объект Win, поэтому продолжение со старым объектом проверку не проходит.
requestBounds не трогался — у него уже есть собственный resizeSequence.

### WC2-07 — уже безопасно
Подтверждено по `bridge/RootBridge.java`: все результаты доставляются через
`main.post(() -> callback.onResult(value))` (Handler главного потока), т.е. мутации модели из
колбэков WindowController всегда происходят на main thread. Изменений не требуется; в verify_quality
добавлена проверка-якорь на эту строку, чтобы регрессия не прошла незамеченной.

### WC2-09 — второй Win+D во время restore
restoreShowDesktopNow() теперь помещает восстанавливаемые id в `showDesktopRestoringIds` и
запоминает время старта. toggleShowDesktop() при непустом множестве не теряет нажатие, а ставит
`showDesktopToggleQueued`. adoptTasks() на каждом poll вызывает checkShowDesktopRestoreComplete():
когда ни одно окно из множества больше не MINIMIZED (или исчезло), очередь очищается и отложенный
toggle переигрывается. Зависший restore завершается по таймауту 3 с, чтобы очередь не залипала.

### WC2-12 / WC2-13 — коммит только после успеха, откат при неудаче
- fullscreen(): поля stackId/state/visible и bounds выставляются только в успешном resize-колбэке.
  При неудаче resize: report + moveToStack назад в исходный стек (если менялся) и refreshTasks(null)
  в его колбэке; если стек не менялся — сразу refreshTasks(null).
- moveToStackThenResize(): захвачены oldStack и operation до начала; при неудаче resize —
  moveToStack(w, oldStack) с refreshTasks(null) по завершении. Модель при неудаче не мутируется
  (как и раньше, коммит полей был только в успешной ветке).

### WC2-11 — отложенный snap detach
detach(w) убран из начала maximize/fullscreen/restoreNormal/restoreToNormal/close:
- maximize/restoreNormal/restoreToNormal: detach выполняется в `after` moveToStackThenResize
  (т.е. после подтверждённого resize), по захваченному флагу wasSnapped;
- fullscreen: detach — в успешном resize-колбэке;
- close: upfront detach удалён полностью — при неудаче closeTask окно остаётся в группе, при успехе
  detach централизованно делает проход удаления в adoptTasks().

## Прочее

- Правки написаны многострочно; комментариев `//` в середине строки с живым кодом нет
  (класс регрессий BUG-01/BUG-02; verify_quality содержит отдельную проверку на это — PASS).
- Ограничения проекта соблюдены: без прозрачности/blur, функции не удалялись, minSdk/targetSdk 25.
