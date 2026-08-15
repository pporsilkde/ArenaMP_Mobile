# ArenaMW Android Safe Render V2

V1 подтвердил, что fixed-function/legacy baseline на устройстве рисуется нормально. V2 осторожно возвращает только базовые шейдеры объектов и воду.

## Что включено
- `force shaders = true`
- `lighting method = shaders compatibility`
- `force per pixel lighting = false`
- HDR/Bloom/SSR/SMAA/PBR пока выключены
- auto normal/spec maps пока выключены
- shader water включена
- refraction пока выключена
- shader water ripples пока выключены
- shadows пока выключены
- water RTT уменьшен до 256 для первого Android-теста

## Android water path
На Android `Water::createShaderWaterStateSet` выбирает `water_vertex.glsl` + `water_fragment.glsl` вместо `water_pbr_*`. Desktop/Windows path не меняется.

## Диагностика
OSG WARN и `LIBGL_LOGSHADERERROR=1` включены и в Release APK. Если конкретный shader не компилируется/линкуется, следующий runtime-log должен содержать ошибку.

Цель V2: проверить, что стандартный OpenMW object shader и облегчённый ArenaMW water shader стабильно проходят через `LIBGL_SIMPLE_SHADERCONV=1`. После этого можно возвращать normal/spec maps, refraction и PBR по одному.
