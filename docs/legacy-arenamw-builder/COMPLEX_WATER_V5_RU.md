# Complex Water V5 — GLES compatibility

V5 продолжает V4 и не упрощает сложную PBR-воду.

Исправлены две ошибки, найденные по runtime-логу Android:

1. `assigning non-constant to 'const 4-component vector of float'` на source string 4, строках 120/131/142/.../405.
   Это 19 локальных `const vec4 *Bounds`, вычисляемых через `min/max` и другие локальные значения в `water_pbr_android_data.glsl`.
   Для Android они теперь обычные `vec4`; математика не изменилась.

2. `vgpu_exp2: no matching overloaded function` на строке 994.
   NG-GL4ES simple converter не принимает `exp2(vec3)` в этом пути. Вычисление сделано покомпонентно через три `exp2(float)` с той же формулой Beer-Lambert.

Объектные шейдеры и освещение из V4 не меняются.
