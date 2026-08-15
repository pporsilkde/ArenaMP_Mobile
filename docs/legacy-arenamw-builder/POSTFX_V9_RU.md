# ArenaMW Android — PostFX V9

База: Mobile Tuning V8 + Complex Water Geometry + GLES Shadows V7.

## Цель

Завести HDR, Bloom и native effects (SSR/SMAA) на Android, но оставить их выключенными по умолчанию.

## Что изменено

1. Лаунчер больше НЕ записывает на каждом запуске:
   - `hdr lighting = false`
   - `bloom enabled = false`
   - `native ssr enabled = false`
   - `smaa enabled = false`

   V9 выставляет эти четыре master-switch в `false` только один раз через migration-флаг
   `arenamw_android_postfx_defaults_v9`. После первого запуска значения из `settings.cfg`
   принадлежат пользователю и больше не откатываются лаунчером.

2. На Android Bloom/SSR/SMAA используют один `NativeEffectsProcessor`.
   Отдельный desktop `BloomProcessor` на Android не создаётся, поэтому нет двух цепочек
   framebuffer-copy callback на одной main camera.

3. Bloom-only режим теперь действительно активирует NativeEffects compositor.
   Цвет main framebuffer копируется всегда, а depth framebuffer — только если включены
   SSR или SMAA. Это уменьшает лишнюю работу и не заставляет Bloom зависеть от depth-copy.

4. HDR shader path адаптирован под NG-GL4ES SIMPLE_SHADERCONV:
   `pow(vec3, vec3)` заменён на три scalar `pow(float, float)` без изменения математики.

5. Исправлено runtime включение HDR. `hdr lighting` — compile-time shader define,
   поэтому при переключении теперь пересоздаются уже загруженные object/actor shaders,
   а terrain и groundcover помечаются на rebuild. HDR не должен требовать перезапуска игры.

## Рекомендуемый порядок теста

Все эффекты после первого запуска V9 будут OFF.

1. Включить только HDR. Проверить объекты/землю/растительность и лог на `glCompileShader FAILED`.
2. Выключить HDR и включить только Bloom. Проверить яркие источники света/небо.
3. Включить HDR + Bloom вместе.
4. Затем отдельно проверить SMAA.
5. SSR проверять последним: он использует копию depth-buffer и является самым тяжёлым эффектом.

Если какой-либо этап даёт shader error, прислать лог начиная с первой строки
`glCompileShader ... FAILED` и весь infolog этого shader ID.
