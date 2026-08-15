# ArenaMW Android — Complex Water Geometry + GLES Shadows V7

V7 накладывается поверх V6 и исправляет две ошибки GLES из runtime-лога.

## 1. gl_EyePlaneS/T/R/Q

OSG 3.6.5 использует legacy EYE_LINEAR TexGen для координат shadow map. В GLES
fixed-function TexGen и gl_EyePlane* отсутствуют, поэтому shader converter выдавал:

- `gl_EyePlaneS : undeclared identifier`
- `gl_EyePlaneT : undeclared identifier`
- `gl_EyePlaneR : undeclared identifier`
- `gl_EyePlaneQ : undeclared identifier`

На Android V7 больше не использует эти built-ins. MWShadowTechnique вычисляет явную
матрицу преобразования из пространства камеры игрока в shadow texture space и
передаёт её как `shadowTextureMatrixN`.

Desktop/Windows путь не изменён: там остаётся исходный TexGen/gl_EyePlane код.

## 2. uniform initializers в shadowcasting_vertex.glsl

Удалены GLSL-инициализаторы:

```
uniform bool useDiffuseMapForShadowAlpha = true;
uniform bool alphaTestShadows = true;
```

Значения этих uniform уже выставляются C++ StateSet-кодом, поэтому функциональность
не меняется, а GLES-компилятор больше не отклоняет shader.

## Что НЕ менялось

- Complex Water V5/V6 PBR pipeline
- FBM/raymarch геометрия воды
- reflection/refraction
- foam/SSS/ripples
- object shaders и lighting
- Android manual depth comparison + PCF из V6

## Тест

В предыдущем runtime-логе пользователь отключил тени через настройки после ошибок.
После установки V7 нужно снова включить:

- Enable shadows
- Object shadows
- Actor shadows
- Player shadows

Для первого теста оставить 1 shadow map и 1024 px.
