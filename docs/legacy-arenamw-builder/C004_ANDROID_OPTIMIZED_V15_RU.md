# ArenaMW Android — Cumulative 004 Optimized V15.1

База: Android V14 + ArenaMW NEW CUMULATIVE 004.

## Что перенесено из Cumulative 004

- исправленные позы NPC в диалоге: UpperBody-жесты ограничиваются каналами рук, без резкого задирания/наклона головы;
- более частые закрытые позы в диалоге;
- накопленные NPC ambient-анимации и GUI/QuickLoot-навигация из C003;
- отдельные normal map для Simple/New water;
- fullscreen restore fix через `SDL_GL_GetDrawableSize` с игнорированием временного 0x0 drawable;
- SSR/ArenaSR composite остаётся удалённым.

C004 встроен в Android builder как upstream overlay и применяется до Android compatibility patches. Это делает архив самодостаточным даже если CI восстановит старое дерево ArenaMW из кэша.

## Энергосбережение без «мыльных» текстур

Удалено дополнительное уменьшение текстур через NG-GL4ES `LIBGL_SHRINK=6`. Теперь builder всегда устанавливает `LIBGL_SHRINK=0`.

Все launcher-пресеты, включая «Энергосбережение», сохраняют:

- `Terrain/composite map level = -3..-1 (по профилю)`
- `Terrain/composite map resolution = 1024`

Экономия GPU/CPU в Battery Saver достигается дальностью, LOD, object paging, количеством потоков и лимитом FPS, а не ухудшением splat/composite-текстур.

Игровой Terrain Detail также ограничен уровнями Minimum / Low / Balanced / Medium и теперь использует ту же шкалу:

- Minimum: `-3 / 1024`
- Low: `-3 / 1024`
- Balanced: `-2 / 1024`
- Medium: `-1 / 1024`

То есть разрешение composite map не прыгает между 1024/2048/4096, а качество регулируется только уровнем `-3..-1`.

## Тени

Настройки теней полностью удалены из Android launcher. Launcher и графические presets больше не записывают секцию `[Shadows]` вообще. Тени регулируются только во внутриигровом разделе Shadows.

Внутри игры оставлены карты 512 / 1024 / 2048; 4096/8192 убраны как слишком тяжёлые для мобильного профиля.

## Стабильный Android UI

- HDR и Bloom скрыты из навигации;
- SMAA C004 скрыта на Android и остаётся принудительно выключенной;
- регулировка PBR Material Quality удалена не только из layout: pointer/callback/update-логика удалены из `settingswindow.cpp/.hpp`;
- High/Ultra ландшафт удалены;
- Quality layout уплотнён после удаления Material Quality.

## Инкрементальная сборка

CI теперь может восстановить предыдущий ArenaMW build-tree даже при изменении Android patchset. Добавлен fingerprint `buildscripts/patches/openmw/**`: если исходный commit и patchset те же, mtimes и `.o` сохраняются; если patchset изменился, исходники сбрасываются и патчи применяются заново, но бинарное CMake-дерево не удаляется.

## Проверено

Полная локальная цепочка успешно применена в порядке:

C004 overlay → 01 → 02 → 03 → 04 → 05 → 06 → 07 → 08 → 09 → 10 → 11 → 12 → 16(V15.1).

Проверены `git diff --check`, MyGUI/XML, launcher XML, Kotlin `GraphicsPresets.kt`, shell syntax overlay-script и наличие ключевых C004/Android markers.
