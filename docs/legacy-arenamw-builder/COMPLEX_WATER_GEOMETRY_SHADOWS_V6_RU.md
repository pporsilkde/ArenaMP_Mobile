# ArenaMW Android — Complex Water Geometry + Shadows V6

V6 накладывается поверх рабочего Complex Water V5.

## Сложная геометрия воды

- `RAYMARCH_WAVES = 1`
- `ANDROID_LITE_WAVES = 0`
- возвращены FBM raymarch-пересечение поверхности и аналитическая FBM-нормаль;
- в Android-варианте векторные `sin/exp` вычисляются покомпонентно, чтобы NG-GL4ES simple converter не требовал отсутствующие vector helper overloads;
- цикл raymarch имеет статическую границу 12 шагов для мобильных GLSL-компиляторов;
- PBR/refraction/reflection/foam/SSS/ripples из V5 сохранены.

## Android shadows V1

Desktop путь `sampler2DShadow + shadow2D()` не меняется.

На Android:

- shadow depth texture переводится в raw-depth (`setShadowComparison(false)`);
- `shadows_fragment.glsl` использует `sampler2D`;
- сравнение глубины выполняется вручную;
- сохранён 3x3 PCF / enhanced 8-tap код, но первый тест запускается с обычным фильтром;
- `CLAMP_TO_BORDER` заменён на `CLAMP_TO_EDGE`, выход за карту обрабатывается внутри shader;
- стартовый профиль: 1 карта 1024x1024, дальность 4096, actor/player/object shadows ON, terrain/indoor OFF.

Это намеренно консервативный первый запуск теней. После подтверждения корректного рендера можно вернуть 2 каскада, terrain shadows и enhanced filtering.
