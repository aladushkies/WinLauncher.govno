# WinLauncher Root — раунд K: централизованные UI-токены, 48 dp touch targets и inset-модель

**База:** раунд J (`docs/history/JFIXES.md`), HEAD `3d3ff5a`.
**Метод:** инвентаризация палитры и метрик по всем 23 UI-модулям → централизация в
`UiTokens` → единая inset-модель `SystemInsets` → миграция → standalone-компиляция и полный
верификатор.
**Итог статических проверок:** `tools/verify_quality.py` — **174/174 PASS** (было 169/169;
добавлены K‑01…K‑05).
**Компиляция:** 73 class file (было 71: `UiTokens` + `SystemInsets`), 0 ошибок.

---

## 0. Что было до раунда

| Метрика | До K | После K |
|---|---|---|
| Прямых ARGB-литералов в UI-модулях | **283 вхождения / 70 distinct** | **26** — и все они закреплённые verifier-якори |
| Размер текста | 14 разных значений (10…28 sp), разбросанных по файлам | 13 токенов `UiTokens.TEXT_*` |
| Радиусы | 15 вызовов `setCornerRadius`, значения вручную | `UiTokens.RADIUS_NONE / SMALL / MEDIUM` |
| Hit-targets меньше 48 dp | task-manager 42 dp, Task View 42–44 dp, Action Center 38/40 dp, mini-bar 46×32 dp, Edge Lite 44×40 dp | все **основные** элементы ≥ 48 dp |
| Insets | `workArea()` вычитал только status bar сверху и taskbar снизу; navigation bar и `DisplayCutout` игнорировались | `SystemInsets` (status + navigation + cutout), единый источник для `workArea()` и всех абсолютно позиционируемых overlay |

---

## 1. K‑01 — централизованная палитра (`UiTokens`)

`app/src/main/java/ru/winlauncher/UiTokens.java` — единственное место, где живут цвета,
типографика, радиусы, отступы и размеры целей. Класс разделён на смысловые группы:

* **type scale** — `TEXT_HERO…TEXT_TILE` (10…28 sp);
* **radii** — `RADIUS_NONE` (0, обязателен для квадратного chrome сторонних окон),
  `RADIUS_SMALL`, `RADIUS_MEDIUM`;
* **spacing** — `SPACE_TIGHT…SPACE_XL`;
* **hit targets** — `MIN_TOUCH_TARGET` (48), `MIN_TOUCH_TARGET_COMPACT` (44),
  `MENU_ROW_HEIGHT` (32), `LIST_ICON_SIZE` (40);
* **surfaces** — `page`, `pageSoft`, `surface`, `surfaceCard`, `surfaceBar`, `surfaceMini`,
  `surfaceButton`, `surfaceRow`, `surfaceWell`, `surfaceTab`, `surfaceMuted`, `surfaceAssist`,
  `surfacePreview`, `surfaceCellIdle`, `surfaceDivider`, `chromeIdle`, `chromeFocused`,
  `desktopTile`, `widgetStrip`;
* **interaction states** — `surfaceHover`, `surfacePressed`, `surfaceHoverSoft`,
  `surfacePressedSoft`, `surfaceHoverCompact`, `surfacePressedCompact`, `surfaceHoverMuted`,
  `surfacePressedMuted`, `surfaceHoverAccent`, `surfacePressedAccent`;
* **text** — `textPrimary`, `textSecondary`, `textMuted`, `textDisabled`, `textChromeIdle`,
  `textTaskMuted`, `textHint`, `textOnSurface`, `textOnAccent`, `dividerLine`;
* **lines** — `outline`, `outlineSoft`, `outlineStrong`, `outlineMuted`;
* **accent** — `accent`, `accentPressed`, `accentLight`, `accentTint`, `accentTintStrong`;
* **interaction** — `danger`, `dangerPressed`, `dangerPressedCompact`;
* **wallpaper** — `wallpaperBase`, `wallpaperBackdrop(index)`, `wallpaperPetal(index, petal)`.

Все цвета непрозрачные (альфа `FF`): состояния выражены отдельными цветами, а не альфа-блендингом.
Палитра построена «по роли», а не «по оттенку», поэтому переезд темы — правка одного класса.

**Миграция охватила 13 UI-модулей:** `ChromeManager`, `TaskbarOverlay`, `TaskViewOverlay`,
`ActionCenterOverlay`, `FullscreenEdgeManager`, `DesktopActivity`, `LauncherSettingsActivity`,
`MainActivity`, `TaskManagerActivity`, `SnapController`, `DockedPairManager`,
`EdgeLiteActivity`, `ResizeGripManager`.

### Что намеренно осталось литералами

Verifier держит **exact assertions** на конкретные выражения в исходниках. Эти якоря
сохранены байт-в-байт, чтобы регрессионные проверки не потеряли смысл:

| Файл | Закреплённое выражение |
|---|---|
| `ChromeManager` | `bg.setCornerRadius(0);panel.setBackground(bg)`, `bg.setCornerRadius(0);bg.setStroke(dp(1),0xff888888)`, `d.setCornerRadius(0)` |
| `TaskbarOverlay` | `isActive?0xff005a9e`, `isActive?0xff004b85`, `if(isActive)item.setTextColor(Color.WHITE);`, `int taskBtnW=dp(prefs.mouseProfile()?44:52);`, `itemLp.setMarginEnd(dp(4))`, `labels.size()*dp(32)+dp(20)` |
| `TaskViewOverlay` / `SnapController` | `heading.setTextColor(dark?0xfff5f5f5:0xff1a1a1a)`, `heading.setTextSize(18)` |
| `ActionCenterOverlay` | `round(dark?0xff292929:0xfff5f5f5,0)` |
| `FullscreenEdgeManager` | `round(dark?0xff292929:0xfff3f3f3,0)`, `title.setGravity(Gravity.CENTER_VERTICAL);`, `title.setSingleLine(true)` |
| `DesktopActivity` | `prefs.darkTheme()?0xff202020:0xffe8e8e8`, `dp(28)`, `wrap.addView(strip,new LinearLayout.LayoutParams(-1,dp(28)));` |
| `LauncherSettingsActivity` | `0xffaaaaaa`, `namesLp.leftMargin=dp(8)`, `row.setPadding(dp(16), dp(4), dp(16), dp(4));` |
| `DockedPairManager` | `active?(dark?0xff0097ff:0xff0067c0):(dark?0xff888888:0xff666666)` |

Проверка **K‑01** в `tools/verify_quality.py` закрепляет этот список: любой новый «сырой» цвет
в UI-модулях (кроме `UiTokens` и списка якорей) ломает верификатор.

---

## 2. K‑02 — touch targets не меньше 48 dp

Подняты до 48 dp:

| Элемент | Было | Стало |
|---|---|---|
| Task Manager: «Вернуть» / «Закрыть» | 42 dp | 48 dp |
| Task View: навигация по столам, кнопки списка, «Открыть выбранные два окна», «Выйти из docked split», «Закрыть Task View», чекбокс выбора | 42–44 dp | 48 dp |
| Action Center: «Открыть системные настройки соединений», «Разрешить доступ к уведомлениям…», «Закрыть» | 38 / 40 / 44 dp | 48 dp |
| Fullscreen mini-bar: свернуть / вернуть / закрыть и сам бар | 46×32 dp | 48×48 dp |
| Taskbar: кнопки «Пуск» и «Task View» | 44 dp | 48 dp |
| Edge Lite: панель вкладок, кнопки ↵ / ◷ / +, закрытие вкладки | 36–44 dp | 48 dp |
| Desktop: кнопка «Все приложения», строки списка приложений, иконки | 44 dp | 48 dp |
| Settings: строка приложения (иконка 36 dp + padding 4 dp = 44 dp) | 44 dp | 48 dp (`LIST_ICON_SIZE` = 40 dp) |

**Что осознанно не увеличено:**

* строки **контекстных меню** (jump menu панели задач, системное меню заголовка) остались
  32/36 dp — их геометрия закреплена verifier-а (`labels.size()*dp(32)+dp(20)`), а меню на 10
  пунктов при 48 dp не поместилось бы на экран без скролла;
* заголовок **стороннего окна** (`ChromeManager`) остался 32/40 dp — его высота вычисляется из
  калибровки Samsung caption, и её нельзя менять произвольно;
* **delete strip** виджета на рабочем столе остался 28 dp — высота закреплена verifier-а
  (`dp(28)`), но ширина кнопки «×» увеличена до ~48 dp.

---

## 3. K‑03 / K‑04 — inset-модель (status / navigation / cutout)

Новый класс `app/src/main/java/ru/winlauncher/SystemInsets.java` снимает один снимок
системных отступов дисплея:

* `status_bar_height` и `navigation_bar_height` из ресурсов framework;
* `Display.getCutout()` — safe insets сверху, снизу, слева и справа (API 28+, ниже — нули).

`WindowController` владеет этим снимком, обновляет его в `updateDisplayGeometry()` и
раздаёт его наружу через `insets()`, `topInset()`, `bottomInset()`, `rightInset()`.

**`workArea()`** теперь учитывает всё:

```java
int top = hideStatusBar ? 0 : statusInsetPx;      // строка D‑06 сохранена как есть
int safeTop = Math.max(top, insets.cutoutTop);
int safeBottom = Math.max(safeTop, screenHeight - taskbarHeight - insets.bottom());
return new Rect(insets.left(), safeTop, screenWidth - insets.right(), safeBottom);
```

Панель задач стоит над системной навигацией, поэтому снизу вычитаются обе величины.

**Абсолютно позиционируемые overlay привязаны к инсетам:**

| Overlay | Было | Стало |
|---|---|---|
| Панель задач | `Gravity.BOTTOM`, уходила **под** navigation bar | `params.y = -controller.bottomInset()` — панель лежит над навигацией |
| Action Center | `Gravity.RIGHT\|TOP`, начиналась под status bar | верхний padding панели `dp(12) + topInset()` (панель `MATCH_PARENT` по высоте, поэтому смещение окна уводило бы кнопку «Закрыть» за экран) |
| Системное меню fullscreen-заголовка | `p.y = dp(48)` | `p.y = controller.topInset() + dp(48)` |
| Превью «встряхнуть окно» | `p.y = dp(48)` | `p.y = controller.topInset() + dp(48)` |
| Snap group hint | `p.y = dp(52)` | `p.y = controller.topInset() + dp(52)` |
| Fullscreen mini-bar | `y = workArea().top` только если статус-бар видим | `y = workArea().top` всегда — учитывается и cutout |

Полноэкранные окна (`fullscreen()`) по-прежнему занимают весь дисплей, docked pair —
`displayBounds()`: эти поверхности намеренно перекрывают системные панели, как настоящее
окно рабочего стола.

---

## 4. K‑05 — новые проверки верификатора

| Проверка | Что фиксирует |
|---|---|
| **K‑01** | палитра UI живёт в `UiTokens`; сырых цветов вне списка якорей нет |
| **K‑02** | основные элементы управления не меньше 48 dp; прежних размеров (42/38/40/46×32) в коде нет |
| **K‑03** | `workArea()` вычитает navigation bar и уважает cutout; контроллер отдаёт инсеты |
| **K‑04** | панель задач, Action Center и верхние overlay привязаны к инсетам |
| **K‑05** | `SystemInsets` читает status bar, navigation bar и `DisplayCutout` |

---

## 5. Что осталось непроверенным

* **Gradle/AGP-сборка и D8 не выполнялись** — в окружении нет Android SDK/Gradle.
* **APK не собран, устройство SM‑T555 не подключалось.** Runtime-приёмка обязательна:
  проверить root API и stack IDs, resize/drag, переключение задач, все overlay, виджеты,
  WebView, сохранение состояния и обе темы.
* На устройстве нужно отдельно посмотреть, где именно лежит navigation bar (кнопки или
  жест) и есть ли вырез: от этого зависит число свободного места у нижней и верхней кромки.
* `Display.getCutout()` требует API 28, а проект собирается под API 25 — на целевом
  устройстве ветка cutout просто не задействуется, что и задумано.
