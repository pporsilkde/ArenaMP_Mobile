# ArenaMW Android V12 — стабильные настройки, тени и моды

База: `pporsilkde/AMW`, ветка `main`.

## Что изменено

### Удалены нестабильные Android post-effects

Из Android patch-chain больше не применяются экспериментальные V9/V10/V11 engine-патчи для HDR/Bloom/SSR/SMAA. В `settings.cfg` эти эффекты дополнительно принудительно держатся выключенными при запуске, чтобы старый пользовательский конфиг не смог вернуть чёрный экран.

В игровом окне настроек разделы Effects, HDR и Bloom убраны из навигации и скрыты. Горячие клавиши F3/F4, которые включали HDR/Bloom, на Android отключены.

### PBR quality

Переключатель `MaterialQuality` скрыт, не заполняется и не получает обработчик изменения. Его callback на Android оставлен пустым compatibility-stub, поэтому случайный вызов не запустит live rebuild PBR/terrain shaders. Мобильный профиль использует фиксированное безопасное значение.

### Ландшафт

Список игровых terrain presets ограничен четырьмя уровнями: Minimum, Low, Balanced, Medium. High и Ultra удалены. В launcher также оставлены только мобильные presets без High/Ultra.

### Тени в launcher

В раздел `Графика и тени` добавлены два отдельных селектора:

- область теней: из пресета / выкл. / игрок+актёры / игрок+актёры+объекты;
- разрешение shadow map: из пресета / 512 / 1024 / 2048.

Первичная V8-миграция больше не перезаписывает выбранные пользователем enable/scope/resolution теней.

### Сохранение включения/отключения модов

База модов теперь является источником истины. `ModsCollection` больше не включает обратно hardcoded plugins и не переставляет их при каждом создании коллекции. `generateOpenmwCfg()` пишет BSA/plugins/groundcover строго по сохранённым `enabled` и `load_order`.

### Значок

APK использует официальный OpenMW icon из исходников ArenaMW/OpenMW (`files/launcher/images/openmw.png`) вместо старого Android start-button icon. Подготовлены обычные и adaptive launcher resources.

## Engine-файлы V12 patch

V12 не является только layout-патчем. Патч затрагивает:

- `apps/openmw/mwgui/settingswindow.cpp`
- `apps/openmw/mwgui/settingswindow.hpp`
- `apps/openmw/mwinput/keyboardmanager.cpp`
- `files/mygui/openmw_settings_window.layout`

Таким образом удаление пунктов синхронизировано с C++/HPP логикой, обработчиками и индексами вкладок.

## Сохранено из рабочей мобильной ветки

Сложная Android PBR-вода, FBM/raymarch geometry, GLES shadow path, исправления object/lighting shaders, mobile tuning и исправленное touch-управление остаются.
