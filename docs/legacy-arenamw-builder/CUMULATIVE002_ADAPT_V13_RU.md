# ArenaMW Android V13 — адаптация NEW CUMULATIVE 002

База ArenaMW: `https://github.com/pporsilkde/AMW.git`, ветка `main`.

Эта версия Android-builder адаптирована к исходникам, где уже присутствует
`ArenaMW_NEW_CUMULATIVE_002_NPC_Animations_DialogueFacing_WaterShaderSelector`.
Сам cumulative patch повторно в Android-builder не накладывается: он уже находится
в ArenaMW repository. Android patch-chain лишь адаптирован к изменившейся сигнатуре
и логике `MWRender::Water`.

Сохранено из NEW CUMULATIVE 002:
- более активные NPC-анимации;
- разворот NPC к игроку в диалоге;
- Water Shader Mode: Off / Simple / New;
- новые настройки/локализация воды.

Android-specific:
- режим `New` использует GLES-адаптированный сложный PBR fragment shader;
- режим `Simple` продолжает использовать legacy ArenaMW water shader;
- режим `Off` оставляет классическую воду;
- текущие GLES fixes, сложная геометрия воды, тени, touch fixes и stable Android UI сохранены.

## Иконка

Исправлены все Android source-set overrides: `main`, `mainline` и `nightly`.
Раньше `mainline/nightly` продолжали содержать старую иконку с глобусом и
перекрывали OpenMW icon из `main`, поэтому APK показывал старый значок.
Теперь все варианты используют один OpenMW icon. Adaptive foreground уменьшен
до Android safe-zone, чтобы значок не обрезался маской лаунчера.
