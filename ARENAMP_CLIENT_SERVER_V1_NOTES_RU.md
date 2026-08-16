# ArenaMP Mobile Client + Server V1

База: ArenaMP Mobile Client V1.2.10. По умолчанию исходники AMP зафиксированы на
`0f659371bcbaf9e7e6b94bd6bcb7a81970082234`, как в текущей совместимой PC-базе.
Android `versionCode` сохранён равным `47`.

## Что добавлено

- В один APK теперь собираются Android-клиент `libtes3mp.so` и выделенный сервер
  `libarenamp_server.so`.
- `tes3mp-server` на Android превращается в shared library только под `ANDROID`;
  PC-цель остаётся обычным executable.
- Добавлен статический LuaJIT 2.1.0-beta3 для CoreScripts сервера.
- В APK упаковываются `server/`, `tes3mp-server-default.cfg` и `resources/version`.
- Сервер запускается отдельным Android process `:arenamp_server`, поэтому завершение
  SDL/GameActivity не убивает работающий сервер.
- Сервер работает как foreground service с уведомлением и WakeLock.
- В настройках лаунчера добавлен пункт «Сервер ArenaMP».
- Экран сервера позволяет запускать/останавливать сервер, менять bind address,
  port, max players, hostname/password, включать автозапуск/автоперезапуск и
  просматривать последние 64 KiB server log.
- Уведомление сервера содержит кнопку остановки.
- Добавлена Android-версия PC-функции **Update Hash**: по текущему порядку
  ESM/ESP/groundcover считается CRC32 и генерируется `server/data/requiredDataFiles.json`
  формата `formatVersion=2`.

## Поведение по логике PC launcher

- Если в исходном `build.ini` явно указан удалённый Server address, автозапуск
  локального сервера по умолчанию выключен.
- Если endpoint не задан, локальный server auto-start/auto-restart по умолчанию включён.
- Выбор пользователя сохраняется отдельно в Android preferences.
- При включённом local host launcher не переписывает endpoint в `build.ini`:
  только на текущий запуск клиент получает `127.0.0.1:<port>`.
- Перед автозапуском server port синхронизируется с port текущего Play endpoint.
  Если локальный сервер уже работает на другом порту, он мягко останавливается и
  запускается заново.
- После Start launcher ждёт 900 ms перед запуском клиента, как PC launcher.
- Stop сначала вызывает штатный `Networking::stopServer()`, повторяет запрос через
  500 ms, а спустя 2 s при зависании принудительно завершает только процесс сервера.
- При auto-restart создаётся ZIP backup каталога `server/`; три быстрых падения
  подряд блокируют бесконечный restart loop.

## Android server runtime

На устройстве portable runtime создаётся в private app storage:

`files/arenamp-server/`

Внутри:

- `tes3mp-server-default.cfg`
- `resources/version`
- `server/scripts/...`
- `server/data/...`
- `userdata/tes3mp-server.cfg`
- `userdata/tes3mp-server.log`
- `Backup/archive_*.zip`

При обновлении APK код CoreScripts обновляется, но существующие файлы `server/data`
не перезаписываются.

## Lua

Windows-only `.dll` Lua modules из `server/lib` в Android assets не кладутся.
Стандартная конфигурация ArenaMP использует JSON; `cjson` при отсутствии нативного
модуля откатывается на bundled `dkjson`, а io2 на non-Windows заменяется обычным Lua `io`.
SQLite/PostgreSQL native Lua modules в V1 не портированы.

## Сборка

GitHub Actions теперь запускает:

`build.sh --arch arm64 --ccache --release --client-server`

и проверяет наличие:

- `libtes3mp.so`
- `libarenamp_server.so`
- `libRakNetLibStatic.a`
- `libluajit-5.1.a`
- `server/scripts/serverCore.lua`
- `tes3mp-server-default.cfg`
- server `resources/version`

Incremental ArenaMP source/object cache из V1.2.10 сохранён: при изменении выбранного
`arenamp_ref` CMake пересобирает изменённые C/C++ translation units и зависимые цели.

## Важно

Локальный клиент в host mode не подменяет network identity из `build.ini`: он использует
собственную native/resource identity, потому что server library собрана из того же SHA.
Для удалённого PC-сервера продолжает работать отдельная Parent Network Compatibility
identity из `build.ini`.
