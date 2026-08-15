# ArenaMW Android V10 — Touch mapping + mobile post FX/HDR

База: POSTFX V9.1 Kotlin Fix. Все предыдущие исправления воды, объектов, освещения, GLES-теней и инкрементальной сборки сохранены.

## Touch

На ультрашироком экране joystick hit-field ошибочно масштабировался `uniformScale=min(scaleX, scaleY)`. Поэтому поле виртуальной шириной 512 занимало только ~616 px на экране 2048×924 вместо ровно 1024 px. Правая зона заканчивалась около x=1640 и крайние ~408 px справа были вне right-look joystick.

V10 использует независимый масштаб X/Y только для двух прозрачных joystick hit-fields:
- left: 0..50% физического экрана;
- right: 50..100% физического экрана;
- кнопки по-прежнему масштабируются единым коэффициентом и не растягиваются;
- Android multi-touch event splitting включён;
- визуальные «грибки» скрыты;
- если их включить для диагностики, центры зеркальные: 34% левой половины и 62% правой половины.

## Bloom / SSR / SMAA

На Android больше не используется `copyTexImage2D` из default framebuffer как источник постэффектов. На tile-based GLES GPU такой late framebuffer copy может вернуть пустое/чёрное содержимое, а копирование depth особенно хрупкое.

При включённом мобильном post FX главная OSG camera теперь рендерит напрямую в FBO:
- COLOR -> `mSceneTexture`;
- DEPTH -> GLES-renderable `GL_DEPTH_COMPONENT16`;
- SSR/Bloom/SMAA читают эти текстуры;
- финальная POST_RENDER camera выводит результат обратно в default framebuffer до MyGUI.

При выключении всех эффектов main camera возвращается на обычный `FRAME_BUFFER`.

## HDR

На Android `hdr lighting` больше не включает `hdrLighting` define во всех object/terrain shaders и не запускает массовую live-рекомпиляцию, из-за которой NG-GL4ES падал.

Вместо этого Android HDR выполняется в финальном compositor pass: exposure + выбранный tonemapper + brightness/saturation + gamma. Настройки HDR и indoor/night uniforms сохраняются. Desktop путь не меняется.

## Что тестировать

1. Без эффектов: картинка должна совпадать с рабочей V9/V7 базой.
2. Bloom отдельно.
3. SMAA отдельно.
4. SSR отдельно (лучше у воды/мокрых поверхностей).
5. HDR отдельно.
6. Затем комбинации HDR+Bloom, SSR+SMAA и все вместе.

Если какой-то pass не запускается, нужен runtime log с `glCompileShader`, `FrameBuffer`/`FBO` и GL errors.
