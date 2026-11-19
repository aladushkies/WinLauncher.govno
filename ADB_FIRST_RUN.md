# Первый запуск на SM-T555 — пошаговый ADB-набор

Подготовка: планшет с root (SuperSU/Magisk), включённая отладка по USB,
на компьютере — adb. Все команды выполнять из папки с APK.

## 0. Установка и разрешения

```bash
adb install -r WinLauncherRoot-gfix-debug.apk
adb shell appops set ru.winlauncher SYSTEM_ALERT_WINDOW allow
adb shell dumpsys package ru.winlauncher | grep -i version
```

## 1. Первый запуск (по шагам внутри приложения)

1. Открыть WinLauncher → «1 · Запустить RootServer (su)» → подтвердить запрос root.
   Это первый реальный тест **R-01**: сервер обязан подняться (токен через временный файл).
2. «2 · Проверить root-соединение» → в статусе должен прийти ответ ping (`"ok":true`).
   Если тишина/таймаут — смотреть раздел «Диагностика» ниже, дальше не идти.
3. «3 · Сканировать задачи» → список задач; заголовки на этом шаге включаться НЕ должны
   (проверка **F-10**), taskbar и краевые зоны — должны.
4. «4 · Включить заголовки окон» → заголовки появляются; «5 · Скрыть заголовки» → исчезают.
5. Назначить DesktopActivity домашним экраном (Настройки → Приложения → Домашнее приложение).

Паралльно в отдельном терминале держать лог:

```bash
adb logcat -c
adb logcat | grep -i winlauncher
```

## 2. F-01 — авиарежим и системные команды (SIGPIPE закрыт?)

1. Открыть Action Center (Win+A или свайп от правого края в fullscreen).
2. Включить «Авиарежим» → радио обязано реально выключиться, плитка — показать состояние,
   прочитанное обратно. Повторить выключение.
3. Проверить остальные плитки: Wi-Fi, Bluetooth, мобильные данные.
4. PrtSc → скриншот в галерее либо тост про временную папку (fallback — допустим).

Провал любого пункта = смотреть logcat и статус в MainActivity; код 141 в ошибках
команд означал бы, что F-01 на устройстве не сработал.

## 3. F-15 — Task View открывается

1. Alt+Tab или свайп от левого края в fullscreen → Task View обязан открыться
   (старый FrameLayout.LayoutParams мог ронять его с ClassCastException).
2. Клик по окну с другого стола → стол переключается, окно не сворачивается обратно (F-04).
3. Выбрать два fullscreen-окна с РАЗНЫХ столов чекбоксами → «Открыть в split 50:50» →
   пара не разбирается на ближайших секундах (G-01).

## 4. Гипотезы E-1…E-10

```bash
# E-1/E-2: стеки и парковка задач
adb shell am stack list
adb shell dumpsys activity stacks | grep -A3 "stackId"
# Запустить любое приложение, найти его taskId в выводе выше, затем:
adb shell am stack movetask <taskId> 2 true     # E-2: уход во freeform (stack 2)
adb shell am stack list                          # E-1/E-5: порядок taskIds, кто верхний

# E-3/E-4: resizeTask и минимальный размер — наблюдать за окном при ресайзе из лаунчера;
# 3 px поля по краям после ресайза = E-3 не подтвердилась.
adb shell dumpsys activity stacks | grep -i bounds

# E-6: скриншот системными средствами под root
adb shell screencap -p /data/local/tmp/e6.png && adb pull /data/local/tmp/e6.png

# E-7: высота Samsung-caption — сравнить заголовки у 2–3 приложений визуально
# (калибровка в «Настроить accessibility-калибровку»).

# E-9: статус-бар двумя механизмами по отдельности
adb shell settings get global policy_control     # записать исходное значение!
adb shell settings put global policy_control immersive.status=*
# ... проверить скрытие статус-бара, затем ОБЯЗАТЕЛЬНО восстановить:
adb shell settings delete global policy_control  # если исходно было пусто

# E-10: docked-пара из лаунчера (split 50:50), затем:
adb shell am stack list                          # пара живёт в stack 3, границы — по факту
```

E-8 (grab /dev/input) — в коде не реализована; безопасный зонд только вручную и с
готовым способом отпустить grab (перезагрузка). Без отдельной подготовки не трогать.

## 5. Диагностика, если RootServer не поднялся

```bash
adb shell su -c id                               # root вообще есть?
adb shell ls -la /data/local/tmp/wl_tok_*        # после запуска следов токена быть НЕ должно
adb logcat -d | grep -iE "wl_root|RootServer"    # ошибки app_process
adb shell ps | grep wl_root                      # процесс сервера жив?
```

## 6. Откат

```bash
adb shell settings delete global policy_control  # вернуть статус-бар
adb uninstall ru.winlauncher
```

Результаты по каждому пункту (E-1…E-10, F-01, F-15, R-01) отмечать в TEST_PLAN.md /
DEVICE_CHECKLIST.md и присылать сюда — по падениям будет следующий круг правок.
