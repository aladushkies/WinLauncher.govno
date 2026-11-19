# Ручная проверка WinLauncher Root на Samsung SM‑T555

**Статус:** не выполнялось. Ни APK, ни планшет в этой среде недоступны. Этот файл — чек-лист, а не приёмка. Подробные действия и ожидаемые результаты по ID приведены в `TEST_PLAN.md`.

Перед началом: только disposable/test apps; зафиксировать Android build fingerprint, root manager, density/resolution, экранную ориентацию и свободную память. Записывать фактический результат, не выводить успех из исходников.

## 1. APK, установка и разрешения

- [ ] Собрать `gradle :app:assembleDebug`; APK создан, ошибки Gradle/Java/resources отсутствуют. (См. B-01)
- [ ] Установить APK на Android 7.1.1, запустить management activity; нет crash при первом запуске. (B-02)
- [ ] Выбрать Desktop activity как Home; Main activity остаётся доступной отдельно. (B-03)
- [ ] Проверить и отдельно согласовать SYSTEM_ALERT_WINDOW, Accessibility/KeyService и Notification Listener; после отзыва разрешения overlays/listener прекращают работу безопасно. (A-01, A-03)
- [ ] Проверить package/signature только после официальной сборки и signer; сертификат сверить с доверенным ожидаемым SHA-256. (P-05)

## 2. Android/root preflight и RootServer

- [ ] Записать модель, Android/API, build fingerprint, разрешение, density, root manager и тестовый набор приложений. (T0)
- [ ] Запустить RootServer через su; SuperSU/Magisk показывает запрос и после одобрения `ping` сообщает ожидаемые uid/api. (R-01)
- [ ] Проверить `tasks`: task ID, package/component, stack, bounds, z-order и видимость совпадают с системой. (R-01, E-5)
- [ ] Неправильные token/UID в disposable harness отклоняются; долгие/некорректные socket-строки не ломают server. (R-01)
- [ ] Проверить доступные операции focus/resize/stack/close только на тестовой задаче. Записать реальные Samsung stack IDs; предполагаемые 1=fullscreen, 2=freeform, 3=docked нельзя считать подтверждёнными до измерения. (R-01, P-04)
- [ ] Проверить статус-бар hidden/visible и реальную inset/рабочую область в обоих режимах. (R-02)
- [ ] Нажать PrtSc на поддерживаемой внешней клавиатуре: RootServer сохраняет PNG в `/sdcard/Pictures/WinLauncher/`, UI показывает путь или ошибку; убедиться, что RootBridge не передаёт bitmap/Base64. (R-03)

### Stage 0 — гипотезы E-1…E-10

Подробные действия, ожидаемые результаты и Pass/Fail для каждой гипотезы записаны в `TEST_PLAN.md`; здесь отмечать факт выполнения на устройстве.

- [ ] E-1: stack 1 under Home hides a task and stack 2 restores it. (TEST_PLAN E-1)
- [ ] E-2: Chrome/other launched tasks move to stack 2 using `am stack movetask <taskId> 2 true`. (E-2)
- [ ] E-3: verify Samsung `resizeTask` behavior and whether there is an unexpected 3 px edge clamp. (E-3)
- [ ] E-4: record minimum window size and `resizeMode` 0/1 behavior. (E-4)
- [ ] E-5: verify StackInfo taskIds order; confirm whether the last ID is topmost. (E-5)
- [ ] E-6: probe `getTaskThumbnail` and `SurfaceControl.screenshot` under root; do not wire snapshots into launcher visuals. (E-6)
- [ ] E-7: compare Samsung caption height/appearance across apps and validate calibration. (E-7)
- [ ] E-8: safely probe root-grab of keyboard/touchkey `/dev/input` and controlled `injectInputEvent`; release the grab. (E-8)
- [ ] E-9: separately test `IStatusBarService.disable` and `policy_control immersive.status=*`, retaining a recovery path. (E-9)
- [ ] E-10: probe docked stack with arbitrary `resizeDockedStack` bounds and record the supported hidden signature. (E-10)

## 3. Окна, заголовки, taskbar, ввод

- [ ] Создать несколько тестовых freeform задач; включить opaque square chrome; проверить light/dark, квадратные чужие окна, сегментацию title bar и системные кнопки. (W-01)
- [ ] Проверить drag/slop, double-click, long-press, hover, правый клик, Alt+Space → system menu, Move/Size стрелками, Enter commit и Esc cancel. (P-03)
- [ ] Проверить resize grips, минимум размера, pointer cursors, snap neighbor boundaries и отмену drag. (W-01, S-04)
- [ ] Проверить normal/maximized/fullscreen/snapped/minimized состояния и сохранение bounds/restore-state после закрытия/перезапуска service. (W-01, W-02)
- [ ] Проверить Minimize через stack 1, fallback в прежний stack и Home; восстановить задачи из taskbar и зафиксировать Samsung-specific различия. (W-03)
- [ ] Проверить Win+D и Ctrl+D: скрыть/вернуть desktop окна; повторить быстрый toggle до завершения minimize. (W-05)
- [ ] Проверить taskbar filtering по desktop, клики, minimize/restore, jump menu/Snap Layouts и fullscreen auto-hide/temporary show. (W-04, S-07)
- [ ] Закрыть задачу; taskbar item, chrome, grips и другие overlays удаляются. (W-01)

## 4. Snap, SnapGroup, fullscreen и docked pair

- [ ] Проверить полуснап, угловой snap и все 6 Snap Layouts на 2–4 окнах; portrait/landscape, gap и inset. (S-01, S-02)
- [ ] Проверить Snap Assist selection, свободные ячейки, Esc/outside/blank dismissal; убедиться, что для кандидатов используются app icons, а не task thumbnails. (S-03)
- [ ] Проверить SnapGroup focus/minimize/restore/detach/maximize/close, обмен занятыми ячейками и minimized-member normalization. (W-06, S-04)
- [ ] Проверить общий divider, min-size clamp и обратное движение после clamp. (S-04)
- [ ] Task View: выбрать две fullscreen задачи, войти в docked stack 3, проверить 50:50, ratio, divider и возврат через край/Alt+F11/Alt+Space/Task View; повторить portrait. (S-05, S-06)
- [ ] FULLSCREEN: top mini-title, bottom taskbar, left Task View, right Action Center; per-app exclusion и double-swipe. (S-07)
- [ ] Esc закрывает overlays; выйти из FULLSCREEN по Esc возможно только при включённой настройке. (S-08)

## 5. Virtual desktops и все горячие клавиши

- [ ] Создать от 1 до 4 столов; навигация, persistence, clamping на первом/последнем столе. (V-01, V-04)
- [ ] Переключить desks с normal/snapped/fullscreen/minimized окнами и SnapGroup; окна скрываются/возвращаются, taskbar содержит текущий desktop. (V-02)
- [ ] Long-press окна в Task View циклически меняет его desktop; назначенное окно отображается на целевом столе. (V-03)
- [ ] Enable KeyService только после согласия пользователя; проверить Win+A, Win+N, Win+I, Win+L, Win+Tab, Win+Z, Win+1…9, Win+T, Win+Home, Ctrl+D, Alt+Tab, Alt+F4, Alt+Space, Ctrl+Shift+Esc, PrtSc, Win+Left/Right/Up/Down, F11, Alt+F11, Win+Ctrl+Left/Right и Esc по `K-01…K-20` в `TEST_PLAN.md`.
- [ ] Для каждого сочетания записать: событие дошло до KeyService, ожидаемое действие, состояние окна до/после; Android может резервировать клавиши.
- [ ] Win+L: подтвердить/documentировать отказ без активного Device Admin с `USES_POLICY_FORCE_LOCK`; `PowerManager.goToSleep()` требует signature permission `android.permission.DEVICE_POWER`, не выданную обычному приложению. Не добавлять device-admin без отдельного согласия. (K-04)
- [ ] Alt+Tab открывает упрощённый Task View, не циклический Windows popup. Ctrl+Shift+Esc открывает `TaskManagerActivity`: проверить список, Close и Restore minimized task. Это оконный диспетчер, не список Android processes/services. (K-11, K-14)

## 6. Action Center, браузер, widgets, wallpaper, settings

- [ ] Без Notification Listener access Action Center предлагает открыть системный экран; после согласия показывает реальные active notifications и открывает content intent. Отозвать доступ и проверить safe fallback. (A-01…A-03)
- [ ] Wi‑Fi/Bluetooth/mobile data/airplane mode: сверить прочитанное состояние с Android Settings, нажать toggle и сверить результат; unsupported vendor command должен показывать ошибку/Settings fallback, не ложный success. (R-04, R-05, A-04)
- [ ] Для Wi‑Fi (`svc wifi`, `global wifi_on`), Bluetooth (`svc bluetooth`, `secure bluetooth_on`), mobile data (`svc data`, `global mobile_data`) и airplane (`global airplane_mode_on` + broadcast) зафиксировать command exit, shell output, observed radio state и logcat. Проверить vendor `svc`, Telephony/CSC policy и ключи состояния отдельно — см. README.
- [ ] Edge Lite: DDG search, omnibox URL/domain, вкладки, переключение/закрытие/reorder drag, back navigation, history/clear, last URL. (I-01, I-02)
- [ ] Edge Lite download: deny/grant storage permission, retry, убедиться в Android DownloadManager и завершённом файле в Downloads. (I-03)
- [ ] Desktop: Home selection, app launcher/list, shortcuts, background long-press/right-click, ручная смена обоев и slideshow. (I-04)
- [ ] Прямо на Desktop нажать ярлык «Добавить виджет», выбрать provider, пройти Android bind/configure consent; убедиться, что AppWidgetHostView отображается поверх desktop wallpaper. (I-05)
- [ ] Взаимодействовать с widget, повернуть экран, пересоздать Desktop, long-press → удалить; проверить ID persistence, перерисовку и удаление host ID. (I-06)
- [ ] Settings: light/dark, input profile, snap gap, fullscreen edge exclusions, status-bar policy сохраняются и реально применяются. (I-07)

## 7. Производительность и ограничения эффектов

- [ ] На слабом устройстве выполнить 30-минутную mixed-use с холодным запуском, несколькими snapped задачами, Task View, Action Center и Edge Lite; записать meminfo/CPU/frame latency/logcat. (P-01)
- [ ] Повторить поворот, мышь attach/detach, swipe/hover/touch/cancel сценарии; проверить геометрию и отсутствие stuck overlays. (P-02, P-03)
- [ ] Source/APK audit: нет `PixelFormat.TRANSLUCENT`, alpha ARGB/`setAlpha`, blur/Mica/RenderScript blur, elevation shadows или thumbnail/snapshot animation; launcher surfaces сплошные. (I-08)
- [ ] Stage 5 не запускать: он опционален и исключён из scope.

## 8. Ограничения проверки

- В текущем workspace нет Android SDK/Gradle и физического SM‑T555; ни один пункт здесь не помечен как выполненный.
- Samsung hidden Binder APIs, caption resource IDs, stack IDs, notification access, Bluetooth shell action и подпись требуют фактической проверки на устройстве.
- Синтаксические/static source checks не являются Java-компиляцией, установкой APK или runtime-acceptance.
