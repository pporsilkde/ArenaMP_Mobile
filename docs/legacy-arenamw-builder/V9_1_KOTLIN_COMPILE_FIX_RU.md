# ArenaMW Android V9.1 — Kotlin compile fix

Кумулятивная версия поверх PostFX V9.

Исправлена ошибка Gradle/Kotlin в `JoystickRight.kt`:

- удалена зависимость от несуществующих в базовом `Joystick` методов `onTrackedPointerDown` / `onTrackedPointerMove`;
- правый стик обрабатывает движение через штатный `onTouchEvent(MotionEvent)`;
- сохранены полупрозрачный грибок, улучшенная зона стика, V8 mobile tuning и весь V9 PostFX;
- HDR/Bloom/SSR/SMAA по умолчанию остаются выключены, но их рабочий путь V9 сохранён;
- вода, сложная геометрия и GLES-тени V7/V8 сохранены.

Этот файл является compile-fix и не меняет native renderer/shader math.
