# WinLauncher Root — P1 (RootServer + RootBridge)

Первый инкремент: Android-проект диагностического ядра под Galaxy Tab A 9.7 / Android 7.1.1 (API 25). В APK включены постоянный root-процесс `app_process`, абстрактный LocalSocket, токен + проверка UID, JSON-команды `ping`, `tasks`, `resize`, `front`, `stack`, `close` и клиент с последовательной очередью и coalescing для `resize:<taskId>`.

## Сборка APK

Требуется Android Studio с Android SDK Platform 35 и JDK 17 (AGP 8.6.1). Откройте каталог `WinLauncherRoot` как Gradle-проект или выполните:

```sh
gradle :app:assembleDebug
```

APK появится в `app/build/outputs/apk/debug/app-debug.apk`. `minSdk=25`, `targetSdk=25`; приложение пока является диагностической оболочкой, это не готовый лаунчер.

## Проверка на устройстве

1. Установить APK и открыть его.
2. Нажать «Запустить RootServer», одобрить запрос SuperSU/Magisk.
3. Нажать «Проверить соединение» — ответ должен содержать `ok:true`, `uid:0`, `api:25`.
4. Нажать «Получить список задач». Ответ содержит массив `tasks` с `id`, `stack`, `component`, `bounds`, `visible`, `z`.

Только после проверки P1 выполнять реальные `resize`, `front`, `stack` и `close` на тестовых задачах.

## Границы этого инкремента

- Вызовы скрытого `IActivityManager` делаются reflection-ом. Это избавляет от нестабильных API-stubs при компиляции, но не отменяет проверку имен/сигнатур именно на Samsung API 25.
- Имена стеков `1/2/3`, формат bounds, порядок задач, способность Samsung перемещать/изменять размеры, запуск `app_process` через конкретный SU-менеджер и peer credentials должны быть проверены на устройстве. Реальных результатов устройства в проект не подставлено.
- Пока нет `ITaskStackListener`, автоподписки на события, `start`, `thumb`, `shot`, `key`, настройки status bar и произвольной команды root shell. `tasks` запрашивается явно. Это намеренно ограниченный первый модуль.
- Сервер использует `LocalServerSocket` в abstract namespace. Любая ошибка извлечения UID peer-а приводит к отказу сессии (fail closed).
- Если RootServer уже запущен, повторная команда запуска может завершиться ошибкой bind; это не мешает подключению к существующему серверу.

## Протокол

Первая строка: `{"token":"<64 hex>","uid":<app uid>}`. Далее строки JSON с `id` и `c`. Каждый ответ содержит `id` и `ok`; `tasks` возвращает `tasks`. Ошибки Binder/hidden API возвращаются как `ok:false` и короткий текст.
