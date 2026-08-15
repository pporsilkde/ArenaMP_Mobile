# ArenaMW Android: инкрементальная сборка

Источник ArenaMW для Android-сборки:

    https://github.com/pporsilkde/AMW.git
    branch: main

## Что кэшируется

Отдельно сохраняются:

1. Android/NDK зависимости (Boost, OSG, MyGUI, FFmpeg, SDL2, Bullet, LZ4 и др.).
2. ccache.
3. `buildscripts/build/arm64/arenamw-prefix` — checkout ArenaMW, CMake build tree, object files и ExternalProject stamps.

При следующем запуске workflow восстанавливает последний совместимый ArenaMW build tree.
Если commit `pporsilkde/AMW:main` не изменился, исходники не трогаются вообще и сохраняются mtimes.
Если commit изменился, Git обновляет checkout без удаления binary tree, Android patches применяются заново, после чего CMake/Make пересобирает только устаревшие translation units и их зависимости.

`BUILD_ALWAYS TRUE` относится только к запуску nested build command: это не означает полную перекомпиляцию. Сам CMake-generated build system определяет, какие `.cpp`/`.o` действительно устарели.

## Принудительная чистая сборка ArenaMW

В `Run workflow` есть флаг:

    clean_arenamw_build

Он игнорирует только кэш исходников/build tree ArenaMW. Тяжёлые third-party Android dependencies остаются закэшированными.

## Когда кэш автоматически сбрасывается

Инкрементальный ArenaMW cache несовместим и не восстанавливается, если меняются:

- `buildscripts/build.sh`
- `buildscripts/CMakeLists.txt`
- версия/toolchain-настройки в `buildscripts/include/version.sh`
- Android patches ArenaMW
- сам Android workflow

Это защищает от повторного использования build tree с другими compiler/linker flags или другим набором патчей.
