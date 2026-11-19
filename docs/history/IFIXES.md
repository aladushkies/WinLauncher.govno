# WinLauncher Root — финальный дебаг-аудит и правки раунда I (`I-01…I-12`)

**База:** `WinLauncherRoot-hfix-source.zip` (verify_quality 147/147).
**Итог:** проведена полная построчная ревизия всех 23 Java-файлов, манифеста, ресурсов и `tools/verify_quality.py` после раунда H; найдено и исправлено **12 дефектов** (1 критическая регрессия, 4 высоких, 5 средних, 2 низких).
**Статические проверки:** `tools/verify_quality.py` — **159/159 PASS** (147 прежних + 12 новых проверок `I-01…I-12`).
**Важно:** в среде сборки отсутствуют `javac` и Android SDK, а также планшет SM-T555; все пункты `TEST_PLAN.md` и гипотезы `E-1…E-10` остаются в статусе **Not run** до проверки на реальном устройстве.

---

## Сводная таблица находок и исправлений раунда I

| ID | Серьёзность | Файл(ы) | Что было сломано в `hfix` | Что исправлено в финальной сборке |
|---|---|---|---|---|
| **I-01** | Критическая | `DockedPairManager.java` | Регрессия H-05: `syncVisibility()` искал любое видимое окно с `state == FULLSCREEN`, а окна самой docked-пары имеют `state == FULLSCREEN`, из-за чего разделитель пары уничтожался при каждом `notifyListeners()` | `syncVisibility()` разделяет участников пары (`contains(w)` и `stackId == DOCKED_STACK_ID`) и чужие fullscreen-окна (`foreignFs`): разделитель скрывается только при чужом fullscreen или если оба окна пары невидимы |
| **I-02** | Высокая | `DockedPairManager.java`, `WindowController.java` | Регрессия M-07(2): `abortFirstTransfer()` вызывал `moveFromDockedStack(w, null)`, который всегда переводил окно в `fullscreenStackId` (stack 1) и растягивал на весь экран, даже если окно пришло из freeform (stack 2) | Добавлен `WindowController.rollbackDockedTransfer(w, originalStackId)` — при провале первого трансфера окно возвращается в исходный `originalStackId` и синхронизируется через `refreshTasks(null)` |
| **I-03** | Высокая | `ChromeManager.java` | Регрессия H-04: `removeChrome()` вызывал `finishDragZOrder()` синхронно до `zOrder.remove(id)` (риск реэнтерабельного `render()` и порчи `zOrder`), не сбрасывал `zOrderDirty` при штатном rebuild и не закрывал `shakePreview` при закрытии перетаскиваемого окна | Добавлен флаг `rendering` против реэнтерабельности; `zOrder.remove(id)` перенесён выше `finishDragZOrder()`; `zOrderDirty = false` при перестройке; добавлен `dismissShakePreview()` при закрытии окна во время драга |
| **I-04** | Высокая | `TaskbarOverlay.java` | Регрессия U-04: `itemLp` задавал ширину `WRAP_CONTENT` (`-2`) без горизонтальных отступов, сжимая кнопки задач с `44/52 dp` до узкой полоски вокруг 2 букв; у `HorizontalScrollView` не было `setFillViewport(true)` и скрытия скроллбара | Ширина `itemLp` зафиксирована на `taskBtnW` (`44/52 dp`); включён `setFillViewport(true)`, выключен скроллбар, передан `FrameLayout.LayoutParams(-2, -1)` для `tasks`, добавлены `4 dp` отступы для `start`, `taskView` и `clock` |
| **I-05** | Высокая | `EdgeLiteActivity.java` | Регрессия U-07: `tab.url = url` в `addTab(url)` допускал `null`, что приводило к `NullPointerException` в `setCurrent()` на `tab.url.equals("about:blank")` | URL нормализуется в `safeUrl` (`null`/пустой → `"about:blank"`); в `setCurrent()` используется `"about:blank".equals(tab.url)`; для `URLUtil.guessFileName()` добавлен fallback на `fileName(url)` при пустом результате |
| **I-06** | Средняя | `MainActivity.java` | Недочёт U-02: `status` остался обёрнут во внутренний `ScrollView` внутри внешнего `pageScroll` (вложенный `ScrollView` ломает тач-скролл лога) | Внутренний `ScrollView` убран, `status` добавлен прямо в `root`, у `pageScroll` включён `setFillViewport(true)` |
| **I-07** | Средняя | `LauncherSettingsActivity.java` | Недочёт U-02: `addHeaderView()` вызывался после `setAdapter()`; у строк `ListView` не было боковых отступов `16 dp`, из-за чего список не совпадал с шапкой | `addHeaderView(page, null, false)` перенесён строго до `setAdapter(appAdapter)`; строкам списка задан `row.setPadding(dp(16), dp(4), dp(16), dp(4))` |
| **I-08** | Средняя | `DesktopActivity.java` | Недочёт U-01 / U-06: 20 dp полоска удаления в `FrameLayout` перекрывала верх виджета и обрезала текст `×` (18 sp + 8 dp padding); `startActivityForResult(bind, ...)` был без `try/catch`; `appsDialog` не обнулялся при закрытии кнопкой Back | Обёртка переделана на вертикальный `LinearLayout` с неперекрывающей полоской `28 dp` сверху и отступом `8 dp` между виджетами; `bindWidget` защищён `try/catch` с очисткой `id`; для `appsDialog` добавлены `OnDismissListener` и закрытие старого диалога |
| **I-09** | Средняя | `ResizeGripManager.java` | Недочёт M-04: откат по `ACTION_CANCEL` шёл только в `mode = 1` без `commitMove()`, а `removeAll()` не очищал `g.dragWin` | В `ACTION_CANCEL` добавлен `controller.commitMove(cancelled)` после `requestMove()`; в `removeAll()` добавлен сброс `g.dragWin = null` |
| **I-10** | Средняя | `SnapController.java` | Недочёт H-05: `syncVisibility()` прятал разделители только при наличии `FULLSCREEN`-окна; при переходе на Home (`visible == false` у всех окон) разделители оставались на экране | `syncVisibility()` также проверяет наличие хотя бы одной группы с ≥ 2 видимыми не-свёрнутыми окнами и скрывает/возвращает разделители при уходе на Home и возврате |
| **I-11** | Низкая | `FullscreenEdgeManager.java` | Недочёт U-08: `titleLp.gravity = CENTER_VERTICAL` не центрировал текст внутри `TextView` фиксированной высоты `32 dp` | Добавлен `title.setGravity(Gravity.CENTER_VERTICAL)` |
| **I-12** | Низкая | `AndroidManifest.xml`, `DockedPairManager.java` | Атрибут `android:configChanges` стоял на `<service DockService>` (это атрибут только для `<activity>`, `Service` получает `onConfigurationChanged` автоматически); в конструкторе `Divider` оставался хардкод светлого фона `0xffb7b7b7` | Удалён лишний `android:configChanges` у `<service DockService>`; конструктор `Divider` сразу использует `dark ? 0xff444444 : 0xffb7b7b7` |

---

## Итоги сквозной истории раундов

| Раунд | Охват | Проверки `verify_quality.py` |
|---|---|---|
| Аудит (S1…S44) | Базовый аудит ядра и политики непрозрачности | 43/43 PASS |
| D-01…D-14 | Соответствие `TEST_PLAN.md` (вкладки Edge Lite, docked clamp, RootBridge id/retry, insets, shortcuts) | 58/58 PASS |
| BUG-01…BUG-25 | Регрессии, безопасность handshake S15, ввод, темы, ListView настроек | 84/84 PASS |
| WC2-01…WC2-19 | Полный аудит `WindowController.java` (строки 351–1090), `operationSequence`, откаты стеков | 102/102 PASS |
| R-01…R-06 | Доставка токена RootServer через приватный temp-файл (`fd 0`), stale `pendingMinimizeIds`, `.gitignore` | 107/107 PASS |
| F-01…F-15 | Устранение `SIGPIPE` в `RootServer.runProcess()`, утечки оверлеев, перенос `SnapGroup` между столами | 122/122 PASS |
| G-01…G-04 | Выравнивание столов docked-пары, контраст активной кнопки taskbar, косметика | 126/126 PASS |
| H-01…H-06, M-01…M-07, U-01…U-08 | Поворот/мышь в `DockService`, вытеснение SnapGroup, перетаскивание `MAXIMIZED`, скролл и вёрстка UI | 147/147 PASS |
| **I-01…I-12 (финальный)** | Регрессия разделителя docked-пары, откат `abortFirstTransfer`, реэнтерабельность `ChromeManager`, ширина кнопок taskbar, NPE `addTab`, неперекрывающая полоска виджетов и чистка UI | **159/159 PASS** |

---

## Что делать дальше (на ПК с SDK и на SM-T555)

1. Собрать APK через Gradle (`gradle :app:assembleDebug`) или ручной пайплайн (`aapt2` → `javac` → `d8 --min-api 25` → `zipalign` → `apksigner`) для закрытия **B-01** и проверки компиляции всех правок раунда I.
2. Установить APK на Samsung Galaxy Tab A 9.7 SM-T555 (Android 7.1.1, root) и пройти шаги из `ADB_FIRST_RUN.md`.
3. Выполнить кейсы `TEST_PLAN.md` и базовые гипотезы **E-1…E-10**, уделяя особое внимание `I-01` (видимость и перетаскивание разделителя docked-пары в `stack 3`), `R-01` (запуск `RootServer`), `F-01` (авиарежим без `SIGPIPE`) и `H-01` (поворот экрана при свёрнутом `MainActivity`).
