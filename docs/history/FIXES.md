# WinLauncher Root — исправления D-01…D-14

База: аудированная версия (`winlauncher_audited/`, verify_quality 43/43, javac 0 ошибок).
Все исправления применены по внешнему отчёту о дефектах; устройство SM-T555 для проверки недоступно — все кейсы TEST_PLAN.md по-прежнему **Not run**, проверка выполнена статически (verify_quality + javac + сборка APK).

## По дефектам

| ID | Файл(ы) | Что изменено |
|---|---|---|
| **D-01** | `EdgeLiteActivity.java` | Ячейка вкладки разделена на две зоны: подпись (клик → новый `selectTab()`, только переключение) и отдельная кнопка `×` (клик → `closeTab()`, прежняя логика закрытия). `closeOrSelect()` удалён; long-click/drag перетаскивания перенесены на контейнер ячейки; `onBackPressed()` вызывает `closeTab(current)`. |
| **D-02** | `WindowController.java`, `DockedPairManager.java` | Добавлен `WindowController.commitDockedBounds()` — фиксация границ docked-пары через `requestBounds(..., 0)` без freeform-`clamp()`. `DockedPairManager.commit()` вызывает его для обоих окон вместо `commitMove()`. |
| **D-03** | `WindowController.java` | Применён **предпочтительный вариант отчёта**: кэширующее поле `windowsButtonBehavior` удалено полностью; геттер читает `prefs.windowsButtonBehavior()` напрямую (как `escapeExitsFullscreen`), `toggleMaximize()` и `keyboardMaximizeUp()` используют геттер. Настройка из «Параметров» теперь действует сразу. |
| **D-04** | `root/RootServer.java` | `readDeviceState`: Bluetooth читается из `global bluetooth_on`; при `<NULL>` — fallback на `secure bluetooth_on`. |
| **D-05** | `bridge/RootBridge.java`, `root/RootServer.java` | Клиент: вынесен `execute()` — сверка `id` ответа с `id` запроса (до 4 пропущенных чужих ответов, далее `response id mismatch`), сокет закрывается при **любом** исключении (включая таймаут), один автоматический повтор после переподключения **только** для идемпотентных команд (`ping`, `tasks`, `device_state`). Сервер: `id` разбирается до `try` и всегда присутствует в ответе об ошибке. |
| **D-06** | `WindowController.java` | Добавлено поле `statusInsetPx`, обновляется в `updateDisplayGeometry()` из существующего `statusBarHeight()`; `workArea()` использует его вместо константы 24 px. |
| **D-07** | `KeyService.java` | Перед проглатыванием `ACTION_DOWN` добавлен пропуск: если `controller == null` и комбинация требует контроллера (`needsController`: F11, Alt+F4, Win+стрелки, Win+Ctrl+стрелки, Win+цифры, Ctrl+D, Win+D/T/Home), событие возвращается приложению. |
| **D-08** | `DesktopActivity.java` | Конфигурация виджета запускается через `widgetHost.startAppWidgetConfigureActivityForResult(...)`; `RuntimeException` (включая `SecurityException`) перехватывается: Toast «Не удалось настроить виджет», `deleteAppWidgetId(id)`, сброс `pendingWidgetId`. Прямой `Intent(ACTION_APPWIDGET_CONFIGURE)` удалён. |
| **D-09** | `SnapController.java` | Заголовок Snap Assist: `heading.setTextColor(dark ? 0xfff5f5f5 : 0xff1a1a1a)` вместо `Color.WHITE`. |
| **D-10** | `WindowController.java`, `TaskbarOverlay.java` | (1) Добавлен `taskbarOrder()` — стабильная сортировка по `taskId`; используется в `focusTaskbarIndex()`, `cycleTaskbarWindow()` и при построении кнопок в `TaskbarOverlay.render()` (окна вне набора — временный fullscreen — дописываются в конец в прежнем порядке). (2) Минутный таймер часов `clockTick` в TaskbarOverlay: старт в `enable()`, стоп в `disable()`, перерисовка выровнена по границе минуты. |
| **D-11** | `root/RootServer.java` | Авиарежим: `;` заменён на `&&` в цепочке `settings put … && am broadcast …`; после успешного выполнения возвращается реальное состояние из `readDeviceState("airplane")`, а не запрошенное. |
| **D-12** | `WindowController.java` | `close()` больше не удаляет окно из модели вручную — после успешного `closeTask` вызывает `refreshTasks(null)`, и удаление с очисткой выполняет `adoptTasks()`. В цикле удаления `adoptTasks()` добавлены `pendingDesktopRestoreIds.remove(id)` и удаление id из persisted `virtual_hidden_tasks`. |
| **D-13** | `ActionCenterOverlay.java`, `FullscreenEdgeManager.java` | Радиус внешнего контейнера OPAQUE-окон изменён 8 → 0: панель Action Center и строка mini-заголовка fullscreen. Внутренние кнопки скругления сохранили. |
| **D-14** | `KeyService.java` | Калибровка caption: сканирование только на `TYPE_WINDOW_STATE_CHANGED` и не чаще раза в 1,5 с (`lastCaptionScan`); `findCaption()` принимает только точный ID `com.samsung.android.framework.res:id/caption` (суффиксные совпадения убраны). |
| **PrtSc fallback** | `root/RootServer.java`, `KeyService.java` | При сохранении скриншота в `/data/local/tmp` JSON-ответ содержит `"fallback": true`; Toast по PrtSc в этом случае предупреждает, что снимок попал во временную папку. |

## Расхождения с отчётом (адаптации)

1. **D-04:** вариант отчёта с `BluetoothAdapter.enable()/disable()` и разрешением `BLUETOOTH_ADMIN` **не применён**: `RootServer` работает в `app_process` без Android-контекста приложения, надёжного доступа к адаптеру там нет. Применено основное исправление отчёта (global + secure fallback); переключение по-прежнему идёт через `svc bluetooth` (ограничение прошивки, отмечено ещё в аудите как S40).
2. **D-05:** из двух вариантов отчёта для простоя выбран **повтор идемпотентных команд**; серверный `setSoTimeout(120000)` (правка S2 аудита) сохранён, а не удалён.
3. **D-03:** применён предпочтительный вариант отчёта (поле удалено), поэтому дополнительный синк в `LauncherSettingsActivity.onStop()` не понадобился; существующий синк в `MainActivity` остался безвредным.
4. **D-14:** точный ID применён как в отчёте. Риск: если на конкретной прошивке SM-T555 ID caption отличается, калибровка просто не сработает (поведение как до калибровки), ложной калибровки не будет.

## Проверки

- `tools/verify_quality.py`: расширен 15 новыми проверками (по одной на D-01…D-14 и на флаг PrtSc fallback) — **58/58 PASS** (было 43/43).
- `javac` против `android-35/android.jar` (source/target 8): **0 ошибок**, 68 классов (включая сгенерированный `R`).
- Gradle не использовался (демон не работает на этой VM — не хватает RAM); APK собран ручным пайплайном: aapt2 compile/link → javac → D8 (`--release --min-api 25`) → zipalign → apksigner (debug keystore). `package`/versionCode/versionName добавлены только во временную копию манифеста; исходный манифест не тронут.

## APK

- Файл: `WinLauncherRoot-fixed-debug.apk`
- Размер: **119 287 байт**
- SHA-256: **`35ced446594d0a74c97d2d413633736586232293db12f4b944ef6779691e79e6`**
- package: `ru.winlauncher`, versionCode 1, versionName `0.2-window-core` (без изменений из build.gradle)
- compileSdk 35, minSdk 25, targetSdk 25
- `apksigner verify`: OK (сертификат Android Debug)

Ограничения проекта соблюдены: прозрачности и blur не добавлено (проверки verify_quality на запрещённые эффекты проходят), функции не удалялись.
