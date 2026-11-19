# WinLauncher Root — исправления BUG-01…BUG-25

База: `winlauncher_fixed/` (после D-01…D-14, verify_quality 58/58). Работа велась в копии
`winlauncher_bugfix/`; базовые исходники не тронуты. Все исправления — по отчёту пользователя
от 2026-11-19 12:57. Порядок: хотфикс BUG-01…BUG-06 → BUG-07…BUG-14 → BUG-15…BUG-20 → BUG-21…BUG-25.

## Критические

| ID | Статус | Файлы | Суть изменения |
|---|---|---|---|
| BUG-01 | ✅ | FullscreenEdgeManager.java | `showMini()` переписан многострочно; комментарий D-13 вынесен на отдельную строку. Заголовок, 3 кнопки и их OnClickListener снова живые (были закомментированы однострочным `//`). |
| BUG-02 | ✅ | SnapController.java | Та же регрессия D-09: комментарий вынесен на отдельную строку; `heading.setTextSize/setPadding/root.addView(heading)` снова выполняются. |
| BUG-03 | ✅ | EdgeLiteActivity.java | `OnLongClickListener` перенесён с cell на label (DragShadowBuilder по-прежнему строит тень от cell); в `closeTab` добавлена защита `if(index<0)return;`. |
| BUG-04 | ✅ | RootBridge.java, RootServer.java, RootServerLauncher.java | Домены хеширования разделены: `clientProof=H("client:"+token+":"+nonce)`, `serverProof=H("server:"+token+":"+nonce)` — синхронно на обеих сторонах; reflection-атака на S15 закрыта. Токен больше не передаётся в argv: RootServerLauncher пишет его в stdin процесса `su` (внутренний sh больше не редиректит stdin из /dev/null), RootServer читает одну строку из stdin в `main()` (`readTokenFromStdin`), в argv остался только owner-uid. |

## Высокие

| ID | Статус | Файлы | Суть изменения |
|---|---|---|---|
| BUG-05 | ✅ | DockedPairManager.java | После успешного `begin()`: `busy=false` + выполнение `pendingEnter`; `busy=false;pendingEnter=null;` добавлены в `release()` и `onTaskRemoved()`. |
| BUG-06 | ✅ | DockedPairManager.java | Выход разделителем к ближнему краю: `exit(next<.04f?second:first)`; цвет разделителя `active?0xff0067c0:0xff666666`. |
| BUG-07 | ✅ | DesktopActivity.java | Правый клик: `OnGenericMotionListener` (ACTION_BUTTON_PRESS+BUTTON_SECONDARY) + `OnContextClickListener` (API 23+) + ACTION_DOWN+BUTTON_SECONDARY в OnTouchListener; обработчики (`attachBackgroundHandlers`) повешены на wallpaper, widgetContainer и screen. |
| BUG-08 | ✅ | EdgeLiteActivity.java, AndroidManifest.xml | `configChanges="orientation\|screenSize\|keyboard\|keyboardHidden\|navigation"`; список URL вкладок и индекс текущей сохраняются в `onSaveInstanceState` и восстанавливаются в `onCreate`. |
| BUG-09 | ✅ | FullscreenEdgeManager.java | Поля `lastScreenW/lastScreenH`; ранний return в `update()` только при тех же размерах дисплея — при повороте края пересоздаются. |
| BUG-10 | ✅ | SnapController.java | В `snap()` поиск группы фильтруется `sameDesktop(candidate,w.desktopId)` (добавлен хелпер `sameDesktop`); в `showAssist()` кандидаты: `w.desktopId==controller.currentDesktopId()&&w.stackId==2`. |

## Средние

| ID | Статус | Файлы | Суть изменения |
|---|---|---|---|
| BUG-11 | ✅ | ActionCenterOverlay.java | После deviceAction используются `done.optBoolean("enabled",requested)` и `known` от сервера; при `!known` — сообщение «не подтверждено». |
| BUG-12 | ✅ | KeyService.java | `swallowedKeys`: действие выполняется один раз на ACTION_DOWN (repeatCount 0) по meta события DOWN, ACTION_UP гасится по множеству — раннее отпускание модификатора шорткат не срывает. `needsController` дополнен SYSRQ, altSpace, win+Z; для altTab/win+Tab — проверка `DockService.taskView()!=null`. Ctrl+D — строго `ctrl&&!alt&&!shift&&!win`. |
| BUG-13 | ✅ | RootServer.java | Бэкап policy_control удаляется только при `settingsApplied`; при отсутствующем бэкапе и значении `immersive.status=*` ключ удаляется (самовосстановление); все подпроцессы — через `execAndWait`/`closeStreams` (закрываются все 3 потока, `process.destroy()`), ридеры в readSetting/enableAccessibilityService закрываются. |
| BUG-14 | ✅ | RootBridge.java | Ответ `id:-1 && !ok` возвращается сразу, без ожидания таймаута; handshake в `ensureConnected()` обёрнут в try/catch с `s.close()` при любом сбое. |
| BUG-15 | ✅⚠️ | RootServer.java | `z = stackRank*1000 + i`, где `stackRank = stacks.size()-1-stackIndex`. **Допущение:** порядок стеков «верхний первым» в `getAllStackInfos()` требует подтверждения на SM-T555 в эксперименте E-5. |
| BUG-16 | ✅ | ChromeManager.java | Флаг `dragInProgress` (ставится DragTouch на DOWN, снимается на UP/CANCEL) откладывает перестройку z-order; `setActive()` обновляет фон кнопок minimize/maximize/close по теме; `removeChrome()` вызывает `handler.removeCallbacks(c.showLayoutFlyout)`. |
| BUG-17 | ✅ | TaskbarOverlay.java | Единый `attachTaskButtonBehavior(item,w,isActive)`: правый клик, press-подсветка и hover с восстановлением синего фона активного окна; фон bar обновляется в каждом render(); кэшированные часы пересоздаются при смене темы (`clockDark`); смещение jump-меню `-(labels.size()*dp(32)+dp(20))` вместо фикс. −240dp. |
| BUG-18 | ✅ | TaskViewOverlay.java | Fullscreen-строка: кнопка фокуса (focus+dismiss) + отдельный CheckBox выбора пары; `chooseDesktop()` завершается `show()` (перерисовка списка); подпись «· стол N» добавлена и freeform/minimized-кнопкам. |
| BUG-19 | ✅ | LauncherSettingsActivity.java | ScrollView+LinearLayout заменены на ListView + BaseAdapter (ViewHolder, recycling); перечисление пакетов, сортировка и загрузка иконок — в фоновом потоке, адаптер биндит предзагруженные AppEntry; поведение переключателей (edges disabled) сохранено 1:1. Расхождение с прежним UI: разделители рисует сам ListView, отдельные divider-вьюхи убраны. |
| BUG-20 | ✅ | SnapController.java, KeyService.java | `hasLayoutsPopup()`/`dismissLayoutsForEscape()` подключены в цепочку Esc в KeyService; в `moveDivider()` — `if(old<=0.01f\|\|old>=0.99f)return old;`; карточки Assist обёрнуты в HorizontalScrollView. |

## Низкие

| ID | Статус | Файлы | Суть изменения |
|---|---|---|---|
| BUG-21 | ✅ | ChromeManager.java, SnapController.java | `setCornerRadius(0)` у корневых фонов showFullscreenSystemMenu, showShakePreview (плюс shake preview теперь учитывает darkTheme) и showGroupHint. |
| BUG-22 | ✅ | DesktopActivity.java | `Math.max(dp(120),info.minHeight)` (minHeight уже в px); слайдшоу планируется в onStart и снимается в onStop; статичные часы декоративного бара обновляет `desktopClockTick` между onStart/onStop; устаревшие appWidgetId (info==null) вычищаются из prefs; renderWidgets пропускает пересоздание при неизменном наборе ID (`lastWidgetIdSignature`). |
| BUG-23 | ✅ | MainActivity.java, DockService.java | Чекбоксы вынесены в поля и синкаются в `onResume()` под флагом `syncingChecks` (слушатели не срабатывают); `startOverlays()` в MainActivity и `startOwnedOverlays()` в DockService проверяют `Settings.canDrawOverlays()` до enable() taskbar/edges. |
| BUG-24 | ✅ | LauncherNotificationListener.java | `onListenerDisconnected()` очищает entries под LOCK; `getActiveNotifications()` обёрнут в try/catch. |
| BUG-25 | ✅ | app/proguard-rules.pro (создан), styles.xml, AndroidManifest.xml, MainActivity/LauncherSettingsActivity/TaskManagerActivity/EdgeLiteActivity | proguard-rules.pro создан (пустой, с комментарием); navigationBarColor → тёмный `#202020`; DesktopActivity: `launchMode="singleTask"`, `stateNotNeeded="true"`; тёмная тема по `prefs.darkTheme()` применена минимально (фон страницы и базовые цвета текста) во всех четырёх Activity. |

## Расхождения с отчётом

- **BUG-04:** из двух предложенных каналов токена (stdin или файл 0600) выбран stdin процесса app_process — файл на диске не создаётся вообще.
- **BUG-15:** формула применена, но порядок стеков — допущение до E-5 (см. выше).
- **BUG-19:** поведение выбора сохранено; визуальное отличие — разделители строк ListView вместо ручных divider-вьюх.
- **BUG-25:** «тёмная тема в Activity» сделана минимально (фон/базовые цвета), экраны не переписывались — по заданию.
- WindowController.java строки 351–1090 отчётом не аудировались и в этом раунде не менялись (кроме уже существовавших правок D-серии).

## Проверки

- **verify_quality.py: 84/84 PASS** (58 прежних + обновлённая проверка S15 под BUG-04 + 26 новых:
  проверка класса BUG-01/BUG-02 «живой код после // в той же строке» по всем Java-файлам и по одной
  проверке на каждый BUG-01…BUG-25).
- **Мутационный тест проверки (а):** временное возвращение паттерна BUG-02 в SnapController →
  проверка падает (`FAIL no live code hides after a // comment`); после восстановления — снова PASS.
- **javac** против android-35 android.jar: **0 ошибок**, 72 класса (24 исходных файла + сгенерированный R).
  В ходе сборки найдена и исправлена одна ошибка, внесённая правкой BUG-07: лямбда
  OnContextClickListener должна возвращать boolean.
- **APK** собран ручным пайплайном (aapt2 → javac → D8 `--release --min-api 25` → zipalign → apksigner,
  debug keystore); Gradle не использовался. `apksigner verify` — OK (CN=Android Debug).

## Артефакты

- APK: `WinLauncherRoot-bugfix-debug.apk` — 123 383 байта, SHA-256 `dddf4fb8f3f606efbc130d3d788c94597d8d6f99c390db14a37c1903f548bb78`;
  package `ru.winlauncher`, versionCode 1, versionName `0.2-window-core`, minSdk 25, targetSdk 25, compileSdk 35.
- Исходники: `WinLauncherRoot-bugfix-source.zip` — SHA-256 см. в финальном отчёте (zip пересобран после этого файла).

## Ограничения (без изменений)

- Без прозрачности и blur; функции не удалялись; minSdk/targetSdk 25.
- На реальном SM-T555 ничего не запускалось: все кейсы TEST_PLAN остаются Not run; BUG-15 ждёт E-5.
