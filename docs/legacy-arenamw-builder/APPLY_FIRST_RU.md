# ArenaMW Android V9.1 — кумулятивный патч

Это ChangedFilesOnly-пакет относительно исходного `ArenaMW_Android.zip`.
Он включает всю текущую цепочку исправлений Android-сборки и рендера вплоть до PostFX V9, а также V9.1 Kotlin compile fix.

Ключевой compile fix:
- `JoystickRight.kt` больше не использует несуществующие pointer-tracking override hooks;
- обработка правого стика выполняется через штатный `onTouchEvent(MotionEvent)`;
- файл `JoystickRight.kt` включён в архив принудительно, чтобы заменить сломанную версию даже если в локальной ветке она уже была изменена.

Сохранено:
- AMW repo + incremental build/cache;
- NO-LTO / CI diagnostics / GL4ES prefix & swap fixes;
- Safe Render / object lighting;
- Complex PBR water + FBM geometry;
- GLES shadow path;
- V8 mobile tuning / joystick visuals / FPS HUD offset;
- V9 HDR/Bloom/SSR/SMAA enablement path (по умолчанию эффекты выключены).
