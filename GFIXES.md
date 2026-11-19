# WinLauncher Root — доводка по ревью ffix-версии (G-01…G-04)

Построчное ревью диффа ffix относительно rfix подтвердило все 15 правок F-01…F-15
и verify_quality 122/122; критических и высоких регрессий не найдено. Ниже — четыре
мелких нюанса из того же ревью, закрытые локально поверх ffix (новая копия
`winlauncher_gfix/`, ffix не тронута).

- **G-01 — docked-пара с разных столов (TaskViewOverlay).** В обработчике enterPair
  второе окно теперь выравнивается по столу первого
  (`moveWindowToDesktop(selected.get(1), selected.get(0).desktopId)`) перед
  `enterDockedPair()` — иначе ближайший тик reconcile разбирал пару.
- **G-02 — контраст активной кнопки taskbar (TaskbarOverlay).** Для `isActive` состояния
  hover/pressed теперь тёмно-синие (`0xff005a9e` / `0xff004b85`) вместо светло-серых —
  белый текст читается во всех состояниях и в обеих темах.
- **G-03 — лишний restore при переносе на текущий стол (WindowController).** В
  `moveWindowToDesktop()` при `desktop == current` id всех участников удаляются из
  `virtualDesktopHiddenTasks()` до restore — reconcile больше не шлёт дублирующий запрос.
- **G-04 — косметика.** DesktopActivity.onDestroy закрывает открытое backgroundMenu
  (WindowLeaked при повороте); в KeyService возвращён пробел после двоеточия в
  fallback-тосте скриншота; в RootServer убраны две продублированные строки Javadoc
  (над captureTo и setStatusBarHidden — остаток интеграции F-01/F-06).

Проверки: verify_quality **126/126 PASS** (122 + 4 новых G-якоря), javac 0 ошибок,
APK пересобран: `WinLauncherRoot-gfix-debug.apk`, 127 460 байт, SHA-256 `51d6933a…`.
Статическая доводка завершена; дальше — только устройство (см. ADB_FIRST_RUN.md).
