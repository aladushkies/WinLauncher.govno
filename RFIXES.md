# WinLauncher Root — исправления R-01…R-06 (поверх wc2-сборки)

База: `winlauncher_wc2/` (verify_quality 102/102). Работа велась в копии `winlauncher_rfix/`.
Исправления R-01…R-05 написаны Arena-агентом (agent mode) по фрагментам двух файлов и интегрированы
минимальными правками в существующий код (Arena переформатировала методы целиком — перенесены
только смысловые изменения, построчно сверенные с реальными файлами). R-06 закрыт при упаковке.

---

## R-01. Критическая: RootServer не запускался (регрессия BUG-04)

- **Файлы:** `app/src/main/java/ru/winlauncher/bridge/RootServerLauncher.java` (`start()`). `RootServer.java` не тронут.
- **Что было:** токен передавался в stdin процесса `su`, но сама команда запускалась в фоне:
  `(nohup sh -c '…' >/dev/null 2>&1 &); exit 0`. По POSIX неинтерактивная оболочка при запуске
  фоновой команды (`&`) принудительно перенаправляет её stdin на `/dev/null`, а немедленный
  `exit 0` закрывал пайп раньше, чем `app_process` доходил до `RootServer.main()`.
  `readTokenFromStdin()` всегда возвращал null — root-мост гарантированно не поднимался.
- **Что сделал Arena:** новая схема доставки токена одной shell-командой: foreground-оболочка
  читает токен в переменную, пишет его с `umask 077` во временный файл, а фоновая
  `nohup sh -c` привязывает этот файл к fd 0 (`exec < "$1"`) и сразу удаляет его — дескриптор
  продолжает ссылаться на открытый inode, файл на диске не остаётся.
- **Как интегрировано:** переменная `shell` и старая `command` удалены; `command` собирается по
  схеме Arena дословно. Запись токена в stdin вызывающим кодом (`start()` ниже по методу) — без
  изменений. Комментарии S17/BUG-04 адаптированы: добавлено объяснение R-01 (почему токен нельзя
  вести пайпом в фоновый процесс и зачем temp-файл).
- **Как проверено:** локальная симуляция в POSIX `sh` с фейковым `app_process`, читающим одну
  строку из stdin:
  - старая команда → фоновый процесс получил **пустой токен**;
  - новая команда → фоновый процесс получил **полный 64-символьный токен**, временных файлов
    `wl_tok_*` после запуска **не осталось** (0 шт.).
  Дополнительно: статическая проверка R-01 в `verify_quality.py` (якоря `wl_tok_`, `exec <`,
  `umask 077`, отсутствие `String shell`).

## R-02. Высокая: pendingMinimizeIds залипал при прерывании сворачивания (регрессия WC2-06)

- **Файл:** `app/src/main/java/ru/winlauncher/WindowController.java` (`minimizeSingle`).
- **Что было:** `minimizeSingle()` добавляет taskId в `pendingMinimizeIds`; если во время полёта
  `moveToStack` приходила другая операция (restore/maximize/fullscreen/close) и увеличивала
  `operationSequence`, оба колбэка выходили по `if(!operationCurrent(w,operation))return;` без
  очистки. Окно больше нельзя было свернуть до его закрытия, а восстановление по Win+D зависало.
- **Как интегрировано:** в **обоих** местах (внешний moveToStack-колбэк и внутренний
  fallback-колбэк) тело stale-ветки заменено на:
  `if (!operationCurrent(w, operation)) { pendingMinimizeIds.remove(w.taskId); restoreShowDesktopWhenReady(); return; }`
- **Как проверено:** проверка R-02 в `verify_quality.py` — ровно 2 вхождения нового тела в файле.

## R-03. Высокая: переиспользованный taskId не инвалидировал старые колбэки (WC2-01 + WC2-06)

- **Файл:** `WindowController.java` (`adoptTasks`, `requestBounds`).
- **Что было:** при `identityChanged == true` объект `Win` переиспользуется, но
  `operationSequence`/`resizeSequence` не увеличивались — незавершённые колбэки предыдущей задачи
  проходили проверки и применялись к новой. В `requestBounds()` не было проверки живости объекта.
- **Как интегрировано:**
  1. В конце блока `if (identityChanged)` в `adoptTasks` добавлены `nextOperation(w);` и
     `++w.resizeSequence;` с комментарием R-03.
  2. В `requestBounds` проверка усилена до
     `if (windows.get(w.taskId) != w || seq != w.resizeSequence) return;`
- **Как проверено:** проверка R-03 в `verify_quality.py` (оба якоря).

## R-04. Средняя: откат WC2-13 поднимал свёрнутое окно на передний план

- **Файл:** `WindowController.java` (`moveToStackThenResize`).
- **Что было:** откат при неудачном resize вызывал `root.moveToStack(w.taskId, oldStack, true, …)`.
  Для окна, восстанавливаемого из свёрнутого состояния (припарковано в fullscreen-стеке с
  `toTop=false`), откат с `toTop=true` выводил его поверх экрана в полный размер.
- **Как интегрировано:** в начале метода зафиксирован
  `final boolean rollbackToTop = w.state != Win.State.MINIMIZED;`, откат использует
  `rollbackToTop` вместо литерала `true`.
- **Как проверено:** проверка R-04 в `verify_quality.py`; заодно обновлена прежняя проверка
  WC2-13, которая искала старую строку отката с `true`.

## R-05. Средняя: notifyListeners() срабатывал раньше отложенного snap detach (WC2-11)

- **Файл:** `WindowController.java` (`moveToStackThenResize`, успешный resize-колбэк).
- **Что было:** `notifyListeners()` вызывался до `after.run()`, а именно в `after` у maximize() и
  restoreToNormal() выполняется отложенный `snapController.detach(w)` — слушатели (TaskbarOverlay,
  ChromeManager) получали уведомление со старым `snapGroupId`, сосед по группе не перерисовывался
  до следующего опроса.
- **Как интегрировано:** порядок поменян: сначала `if (after != null) after.run();`, затем
  `notifyListeners();`, с комментарием R-05.
- **Как проверено:** проверка R-05 в `verify_quality.py` (якорь на новый порядок вызовов).

## R-06. Низкая: из архива поставки выпал .gitignore

- **Что было:** zip собирался по шаблону `*`, который не включает скрытые файлы; без `.gitignore`
  `verify_quality.py` падал с FileNotFoundError на проверке гигиены репозитория.
- **Как закрыто:** архив собран из директории целиком с включением скрытых файлов.
  Проверено `unzip -l`: `.gitignore` присутствует в `WinLauncherRoot-rfix-source.zip`;
  `verify_quality.py`, запущенный по распакованному дереву, проходит полностью.

---

## Итоги проверок

- **verify_quality.py: 107/107 PASS** (102 прежних + 5 новых проверок R-01…R-05; проверка WC2-13
  обновлена под строку отката R-04).
- **javac против android-35: 0 ошибок**, 72 класса.
- **APK** (ручной пайплайн aapt2 → javac → D8 `--min-api 25` → zipalign → apksigner):
  `WinLauncherRoot-rfix-debug.apk`, 127 479 байт, SHA-256
  `a662e253b274505df560b4cd393700544b3d194b8a46ec48f63ac77add77a45c`,
  package `ru.winlauncher`, versionCode 1, versionName `0.2-window-core`,
  minSdk/targetSdk 25, classes.dex (258 860 байт) внутри, `apksigner verify` — OK (Android Debug).
- **Исходники:** `WinLauncherRoot-rfix-source.zip`, включая `.gitignore` (R-06).

Ограничения соблюдены: без прозрачности/blur, правки многострочные, без `//` в середине строки кода.
На реальном SM-T555 ничего не запускалось — все кейсы TEST_PLAN остаются Not run.
