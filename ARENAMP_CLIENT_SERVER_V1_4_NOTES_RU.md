# ArenaMP Mobile Client + Server V1.4

## Главное изменение: portable server в общей памяти

Сервер больше не работает из private app data. При первом запуске Android-лаунчер
разворачивает portable runtime в:

`/storage/emulated/0/ArenaMP/`

Структура:

- `ArenaMP/server/` — CoreScripts, `lib/lua`, постоянные игровые данные `server/data`;
- `ArenaMP/resources/` — server resources/version;
- `ArenaMP/config/tes3mp-server.cfg` — native server cfg;
- `ArenaMP/config/server-config.lua` — постоянный CoreScripts config.lua;
- `ArenaMP/config/tes3mp-server.log` — лог сервера;
- `ArenaMP/config/android-server.status` — статус Android service;
- `ArenaMP/Backup/` — ZIP backup при auto-restart.

Native `libarenamp_server.so` остаётся библиотекой APK (так требует Android), но перед
запуском серверный процесс меняет working directory на `ArenaMP/`. Поэтому `./server/data`
становится обычным доступным для записи `ArenaMP/server/data`, включая JSON-профили игроков.

При обновлении с V1.0–V1.3 существующие `server/data`, cfg и старый лог один раз мигрируют
из private runtime в новый portable runtime. Миграция помечается marker-файлом и больше не
повторяется, поэтому последующая очистка сервера не восстановит старые профили обратно.

## Логи и конфиги

Android server process устанавливает отдельный server-only config root. Native
ConfigurationManager использует `ArenaMP/config`, поэтому `tes3mp-server.log` теперь
пишется именно туда. Клиентский ConfigurationManager этим override не затрагивается.

`ArenaMP/config/server-config.lua` является постоянной копией. Перед каждым запуском она
синхронизируется в `ArenaMP/server/scripts/config.lua`, аналогично логике PC launcher.

## Настройки сервера в Android launcher

Добавлено:

- выбор режима `CO-OP` / `MMO`;
- CO-OP включает shared Journal/Factions/Topics/Reputation/Map/Videos/Kills;
- MMO отключает эти shared progression-параметры;
- bounty остаётся персональным в обоих режимах;
- `Enforce required DataFiles`;
- `Update Hash` автоматически включает enforce и обновляет `requiredDataFiles.json`;
- кнопка расширенного редактирования постоянного `config.lua`;
- `Clear server cells` — очищает только `server/data/cell`;
- `Full server reset` — очищает player/cell/world/map/custom/recordstore и database.db;
- при полном reset сохраняются `requiredDataFiles.json` и `banlist.json`;
- операции очистки доступны только при остановленном сервере.

## Lua CJSON

В Android runtime теперь всегда добавляется `server/lib/lua/cjson.lua`. Это совместимый
слой API CJSON поверх уже встроенного `dkjson`: CoreScripts находят `require("cjson")`,
поэтому сообщение `Could not find Lua CJSON!` больше не должно появляться. Для этого не
добавлялась новая native dependency и не требуется пересборка dependency checkpoint.

## CI / пересборка

V1.4 использует новый ArenaMP incremental cache epoch и больше не восстанавливает старый
V1.3/V1.2.9 ArenaMP object tree. Поэтому первый запуск V1.4 выполняет чистую пересборку
`libtes3mp.so` и `libarenamp_server.so`.

При этом native dependency checkpoint (NDK toolchain, Boost, OSG, NG-GL4ES, RakNet,
LuaJIT и остальные зависимости) восстанавливается отдельно и проверяется по готовым
артефактам. Если checkpoint полный, target `android-dependencies` не пересобирается.

После первого V1.4 build снова работает обычная инкрементальная пересборка C/C++.

Совместимость сохранена:

- ArenaMP source/network base: `0f659371bcbaf9e7e6b94bd6bcb7a81970082234`;
- Android `versionCode = 47`.
