# ArenaMW Android — Complex Water V4 / GLSL compatibility fix

V4 сохраняет сложную PBR-воду из V3 и исправляет две конкретные ошибки компиляции GLSL,
пойманные на реальном Android-устройстве с NG-GL4ES SIMPLE_SHADERCONV=1.

## Исправлено

1. `smoothstep_vgpu : no matching overloaded function` / `cannot convert const float to vec2`
   - источник: vector-overload `smoothstep(vec2, vec2, vec2)` в `water_waves.glsl`;
   - заменён на математически эквивалентную Hermite-интерполяцию по `vec2`:
     `f = f*f*(3-2*f)`.

2. `shadow2D : no matching overloaded function`
   - при `enable shadows = false` функции PCF всё равно компилировались, хотя никогда не вызывались;
   - shadow sampler/helper functions теперь находятся внутри `#if SHADOWS`;
   - Android V4 по-прежнему держит тени выключенными, поэтому старый GLSL 1.20 `shadow2D` вообще не попадает в GLES-компилятор.

## Не откатывалось

- Complex PBR water V3;
- reflection + refraction RTT;
- water ripples / foam / PBR lighting path;
- рабочая гамма Safe Render V1/V2;
- `LIBGL_SIMPLE_SHADERCONV=1`;
- `osgOQ Invalid RQCB` Android workaround;
- AMW incremental build/cache.

## Следующий тест

Если после V4 появится другая GLSL compile error — это уже следующий несовместимый construct,
который можно исправить адресно, не упрощая всю воду.
