# ArenaMW Mobile V13.7.4

Changes relative to V13.7.3:

- Launcher: removed the lone icon from Game files / Файлы игры so the row aligns with Mods, Graphics and Screen controls.
- Launcher graphics: removed all PBR-water choices. Simple water is the only exposed water choice.
- All presets and Custom force simple shader water, RTT 256 and refraction off. Stale old PBR preference IDs are ignored.
- Engine settings: Water shader mode defaults to `simple`; in-game water selector exposes only Off + Simple; stale explicit `new` is redirected to `simple` at runtime.
- Shadow distance fix: land optimization no longer replaces the selected shadow distance with 25% of world distance or multiplies it by the adaptive scale. Selected shadow distance is now passed directly to ShadowManager with the existing 8192 hard cap.
- Preset cleanup: Balanced uses character shadows / 1024 map / 6144 distance; Quality uses object shadows / 1024 map / 8192 distance. Low-performance profiles keep shadows off.
- Old graphics preset value `auto` now opens as Balanced instead of incorrectly selecting Very Low in the graphics window.
- Preload remains fixed to 1000 / 1 worker on every preset.

Validation:
- Engine patch chain 01..22 applies cleanly to AMW(6).
- `git diff --check` is clean after the complete engine patch chain.
- Android resource XML: 22 files parsed, 0 errors.
- GraphicsPresets.kt compiles with minimal Android stubs.
