# ArenaMW Android — Complex Water V3

Цель V3: вернуть сложную PBR-воду ArenaMW на Android, не ломая уже исправленную базовую гамму/объектный рендер.

Что включено:
- PBR BRDF воды;
- reflection + refraction RTT;
- normal-map волны;
- shoreline foam / wobbly shores;
- sunlight scattering / specular highlights;
- rain/actor ripples;
- multiple water types / multicolor / SSS из исходной сложной воды;
- RTT 512, reflection detail 3.

Android-совместимость:
- используется отдельный `water_pbr_android_fragment.glsl`;
- C/C++-суффиксы float (`1.0f`) удалены только из Android-копии;
- тяжёлый FBM raymarch и аналитический FBM-normal stage отключены на Android;
- обычные многослойные animated normal waves остаются;
- desktop `water_pbr_fragment.glsl` не изменяется;
- общий `LIBGL_SIMPLE_SHADERCONV=1` остаётся, поскольку принудительный advanced converter ранее давал сиреневый экран и вылет.

Если вода не скомпилируется, `LIBGL_LOGSHADERERROR=1` и OpenMW logging остаются включены — следующий runtime log должен показать конкретную GLSL-ошибку.
