# ArenaMP Mobile Client V1.1 — что изменено

## Цель

Сборщик переведён с одиночного ArenaMW на Android-клиент ArenaMP/TES3MP из `pporsilkde/AMP`. Сервер в этой версии не собирается.

## Native / CI

- Upstream: `pporsilkde/AMP`, ветка `main` по умолчанию.
- Native target: `tes3mp`.
- APK загружает `libtes3mp.so`, а не `libopenmw.so`.
- `BUILD_OPENMW=ON` — клиентская OpenMW/TES3MP часть включена.
- `BUILD_OPENMW_MP=OFF` — серверный `apps/openmw-mp` subtree отключён.
- `BUILD_BROWSER=OFF`, `BUILD_MASTER=OFF`.
- RakNet/CrabNet снова добавлен отдельным Android ExternalProject, как в старом рабочем MP builder.
- Статическая библиотека: `libRakNetLibStatic.a`.
- GitHub Actions проверяет наличие `libtes3mp.so`, RakNet и `tes3mp-client-default.cfg` до Gradle packaging.

## Launcher / build.ini

В основной экран настроек добавлены:

- IP / адрес сервера;
- порт сервера.

`build.ini` понимает PC-совместимые секции и aliases, включая `[Server]`, `address/ip/host`, `port`, `complete/locked/read-only`, `vanilla-build-server`.

При запуске формируется `--connect=IP:PORT`. При необходимости добавляется `--vanilla-build-server`.

`complete=true` блокирует изменение endpoint с Android-стороны; содержимое/порядок модов остаются редактируемыми и могут сохраняться в манифест — так же, как в текущем PC launcher ArenaMP.

## Адаптация Android-патчей

Существующая цепочка ArenaMW была проверена на текущем AMP. Неконфликтующие изменения перенесены, а конфликтующие участки settings/water/shaders вручную сведены с изменениями AMP. Итог собран в один active cumulative patch:

`buildscripts/patches/openmw/06-arenamp-mobile-cumulative-v1.patch`

Патч затрагивает Android lifecycle/input, settings UI, rendering/shadows/water, mobile limits, native effects safety, HUD/controls, collision/quick-loot и shader compatibility, при этом не заменяет MP networking/game state код ArenaMP одиночной реализацией.

## Mobile graphics policy

- NG-GL4ES Sisah2/Openmw3 сохранён.
- NDK r21e compatibility сохранена.
- Shadow map mobile cap — 1024.
- Shadow distance mobile cap — 8192.
- World viewing distance cap — 40960.
- Mobile water: старые `new/PBR` значения мигрируют в стабильный `simple` path.
- Опасные для текущего NG-GL4ES страницы/переключатели PBR/HDR/Bloom/Effects/Advanced не возвращаются в Android in-game settings.
- MP HUD/chat/layout AMP сохраняются; Android вносит только необходимые layout hunks.

## Проверено локально

- YAML workflow разбирается корректно.
- Android resource XML разбираются корректно.
- Shell scripts проходят `bash -n`.
- Python helper проходит `py_compile`.
- Cumulative patch проходит `git apply --check` на предоставленном `AMP(1)` и применяется без конфликтов.
- Изменённые engine MyGUI layout XML проходят отдельную проверку.

Полный APK локально в этой среде не собирался: Gradle wrapper требует сетевой загрузки Gradle/Android toolchain. Для полной native+APK проверки предназначен включённый GitHub Actions workflow.
