# ArenaMW Android — Mobile Tuning V8

Основа: рабочий V7 (complex water geometry + GLES shadows).

## Новые мобильные значения по умолчанию

Применяются один раз после установки V8 и затем не перезаписывают пользовательские настройки:

- Water / RTT size: **256**
- Water / wave strength: **0.34** (ползунок остаётся полностью регулируемым)
- Shadows / master switch: **OFF**
- Shadows / shadow map resolution: **512**
- Shadows / number of maps: **1**
- Actor / player / object casters остаются подготовлены, поэтому при ручном включении master switch тени сразу работают.

## Меньше микрофризов

- Auto/default OSG background pools: database 3, pager 3, compile 2, MaxPagedLOD 6.
- Cells preload: 2 потока, 1400 distance, exterior grid/instances enabled.
- Cell/cache expiry: 10 s, чтобы меньше перезагружать недавно использованные данные.
- Physics async threads: 1.
- Complex-water FBM/raymarch geometry сохранена, но дорогая near-field зона плавно заканчивается на 2400 вместо 2900. Полная детализация normal geometry до 1500, затем мягкий fade до 2400.

## Сенсорные стики

- Добавлены два аккуратных полупрозрачных "грибка" для движения и обзора.
- Они показываются во время gameplay и не рисуются поверх режима игрового курсора/меню.
- Большие touch-поля стиков подняты над OSC-кнопками, но центр кнопки пропускает ACTION_DOWN к самой кнопке.
- Если drag начался рядом с кнопкой, stick удерживает жест и не теряется, когда палец проходит через кнопку.
- Добавлен requestDisallowInterceptTouchEvent на активный drag.

## HUD

- FPS counter сдвинут вправо: X 8 -> 30, чтобы не попадать под скругление/вырез экрана.

## Важно

V8 не откатывает V5/V6/V7: сложная PBR-вода, FBM/raymarch geometry, GLES-compatible object lighting и GLES shadows остаются в проекте.
