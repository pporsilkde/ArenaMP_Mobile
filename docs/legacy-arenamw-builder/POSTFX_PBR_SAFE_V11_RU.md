# ArenaMW Android V11 — fail-safe PostFX + безопасное переключение PBR

База: V10. Управление, сложная вода, геометрия воды, GLES-тени и предыдущие shader compatibility fixes сохранены.

## Почему весь экран был чёрным

V10 при включении любого эффекта переводил master camera из Android window framebuffer в FBO. Если FBO/attachment или финальный shader не принимался NG-GL4ES/OSG, окно больше не получало исходную сцену — поэтому Bloom, SSR, SMAA и HDR одинаково давали чёрный экран.

V11 никогда не переназначает master camera. Обычная сцена продолжает рендериться в окно. Отдельная PRE_RENDER camera делает RGBA scene capture в FBO, а final mobile compositor рисует обработанный кадр поверх обычной сцены. Если какой-либо post shader не соберётся, исходная сцена всё равно остаётся на экране.

## Mobile depth для SSR/SMAA

Depth texture attachment заменён отдельным lightweight depth pre-pass. Глубина gl_FragCoord.z упаковывается в RGB обычной RGBA8 texture и декодируется в SSR/SMAA. FBO использует обычный GL_DEPTH_COMPONENT16 renderbuffer. Это исключает проблемный GLES depth-texture attachment/copy path.

## Mobile GLSL

Добавлены отдельные Android shader-файлы для post-FX: fullscreen UV через varying, mobile Bloom, SSR, SMAA, HDR final и packed-depth pass. Desktop shaders не меняются.

## PBR quality

На Android изменение `material quality` теперь сохраняется в settings.cfg, но не вызывает live `recreateShaders()`/terrain shader rebuild. Новый уровень применяется после следующего запуска ArenaMW. Это намеренно: live массовая перекомпиляция программ в NG-GL4ES была причиной падения клиента. Изменение terrain LOD также больше не провоцирует live PBR shader rebuild на Android.

## Тест

1. Запустить без эффектов — картинка должна быть как V10.
2. Bloom ON.
3. HDR ON.
4. SMAA ON.
5. SSR ON.
6. Сменить PBR quality — клиент должен остаться жив; перезапустить игру и проверить, что выбранное качество применилось.

При SSR/SMAA дополнительный depth pre-pass увеличивает нагрузку. Это V11 correctness path; после подтверждения стабильности его можно оптимизировать под native GLES/tile GPU.
