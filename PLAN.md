# WinLauncher Root — краткое содержание ТЗ (разделы 1–17)

> Краткая карта scope и acceptance из ТЗ, не замена полному документу.

## Этап 0 — гипотезы E‑1…E‑10

Все пункты — гипотезы для проверки на целевом Samsung/Android/root. Для каждого нужны воспроизводимые действия, наблюдаемый результат, pass/fail и доказательство; исходниковая проверка не заменяет устройство.

| ID | Точная формулировка гипотезы |
|---|---|
| E-1 | парковка в fullscreen-стек под Home скрывает окно и восстанавливается через stack 2. |
| E-2 | Chrome и другие после запуска уходят в freeform командой `am stack movetask taskId 2 true`. |
| E-3 | `resizeTask` не клэмпится Samsung до 3 px полей. |
| E-4 | минимальный размер окна, поведение `resizeMode 0/1`. |
| E-5 | порядок taskIds в StackInfo, последний = верхний. |
| E-6 | `getTaskThumbnail` и `SurfaceControl.screenshot` под root. |
| E-7 | высота и вид Samsung-caption у разных приложений, калибровка. |
| E-8 | root-grab `/dev/input` для клавиатуры и touchkey, `injectInputEvent`. |
| E-9 | `IStatusBarService.disable` и `policy_control immersive.status=*`. |
| E-10 | docked-стек с произвольным `resizeDockedStack`. |

## Этап 1 — ядро

- RootServer, RootBridge, оконная модель/WindowController и ChromeManager.
- Root-backed обнаружение/перемещение/изменение bounds/закрытие задач; контроль доступа к локальному bridge.
- Сборка, установка и первичная проверка на целевой версии Android; Samsung hidden APIs/stack IDs отдельно подтвердить.

## Этап 2 — окна как в Windows

- Квадратные opaque chrome и базовые состояния окна; Taskbar, меню и глобальные клавиши.
- Snap Layouts, Snap Assist, SnapGroup, shared dividers, docked/fullscreen stack, управление фокусом и восстановлением.
- FULLSCREEN edge-зоны, панели и лёгкие/отключаемые эффекты.
- Виртуальные рабочие столы, в частности требование п. 10.3: до четырёх desk, переключение, назначение окон, фильтрация Taskbar и восстановление.

## Этап 3 — приложения (раздел 15)

- Встроенные приложения/desktop icons, контекстное меню desktop и Bloom wallpaper/slideshow.
- Edge-lite: вкладки, 40 px toolbar, omnibox, WebView, DuckDuckGo, Android DownloadManager, история, drag-перестановка вкладок.
- `AppWidgetHost`: виджеты прямо на рабочем столе с системным consent/configuration flow.

## Этап 4 — полировка

- Довести настройки, light/dark UI, доступность, жесты, ротацию, производительность на слабом устройстве, документацию и тест-план.
- Прогнать source checks; отдельно провести Android build/install/runtime и Samsung acceptance.

## Этап 5 — не делаем

Этап 5 исключён: в исходном запросе он обозначен как необязательный.

## Сквозные ограничения и приёмка

- Слабый планшет: никаких прозрачных/полупрозрачных overlays, blur/Mica/RenderScript blur, снимков окон или тяжёлых эффектов; сплошные цвета, минимум работы на кадр. Углы окон сторонних приложений квадратные. Сторонние шрифтовые файлы не поставляются.
- Esc выходит из FULLSCREEN только при включённой настройке.
- Все невозможные/зависимые от system privilege или Samsung прошивки функции фиксируются в README с конкретной причиной.
- `TEST_PLAN.md` задаёт действия, ожидаемый результат и Pass/Fail для сборки, этапов, горячих клавиш и feature-групп. `DEVICE_CHECKLIST.md` — ручная Samsung-проверка. Нельзя объявлять source checks результатом сборки или runtime acceptance.
- GitHub-корень — `WinLauncherRoot`; исключить local.properties, build/cache, APK и signing secrets. Архив исходников не коммитится: после изменений пересобрать его командой `python3 tools/make_release.py` (печатает SHA‑256) и сверить с изменениями.
