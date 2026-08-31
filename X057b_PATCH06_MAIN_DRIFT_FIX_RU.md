# ArenaMP Mobile X057b — устойчивый patch 06 для AMP/main

Причина исправления обнаружена по CI-логу `logs_90581425836.zip`.
Сборка обновила ArenaMP с `dbb211820d987e052052e783ca3ac2bd0a0fb82b` до
`e8048f893ef41c7dcaeaa6934723519c0f54195c` и упала ещё до X056-патчей 13–15:
старый `06-arenamp-mobile-cumulative-v1-2.patch` дал 3-way conflicts в
`apps/openmw/mwrender/renderingmanager.cpp` и `files/ui/graphicspage.ui`.

## Что изменено

1. Большой patch 06 применяется без двух наиболее дрейфующих файлов:
   - `apps/openmw/mwrender/renderingmanager.cpp`
   - `files/ui/graphicspage.ui`

2. Их Android-настройки вынесены в небольшой build patch:
   `06b-arenamp-mobile-render-ui-fuzzy.patch`.
   Он содержит только 6 точечных изменений и применяется обычным `patch`
   с контекстным поиском/fuzz, поэтому переносы строк и соседние изменения AMP/main
   больше не заставляют Git выполнять конфликтный merge целого файла.

3. Исправлен ложноположительный 3-way preflight. `git apply --3way --check`
   способен вернуть код 0 и одновременно вывести `Applied patch ... with conflicts`.
   X057b анализирует вывод и допускает 3-way только когда конфликтов нет.

4. X056 build-patches 13/14/15 и Android Y-hold остаются без изменений.

## Порядок

06 core (без render/UI) -> 06b render/UI -> 07 -> 08 -> 09 -> 10 -> 11 -> 12 -> 13 -> 14 -> 15.

## Проверка

- patch-chain на чистой AMP-базе: OK;
- повторное применение по marker: OK;
- искусственный line/context drift в обоих проблемных файлах: OK;
- изменения X056 после 13/14/15: verified;
- `sh -n` для обоих apply-скриптов: OK.

Полную Android NDK/Gradle сборку в этой среде проверить нельзя; исправлен именно
patch-stage, который в предоставленном CI-логе завершался до компиляции ArenaMP.
