# ArenaMW Mobile V13.6

Base checkpoint: AMW(6) / 731f005b609b37f204f974c36c57496ce5a66beb. Builder still tracks pporsilkde/AMW `main` by default.

## Engine
- Restores stock OpenMW actor-to-actor collision in `ActorConvexCallback`; removes ArenaMW compact actor sweep (0.75/0.88 footprint path).
- Collision scale defaults are restored to 1.0/1.0.
- Quick Loot: normal A_Use / attack button takes the currently selected Quick Loot row while Quick Loot is visible; outside Quick Loot the normal attack/use path is unchanged.

## NG-GL4ES
- Source: https://github.com/Sisah2/NG-GL4ES.git
- Branch/tag: `Openmw3`.
- Builds glslang and SPIRV-Cross from the wrapper's submodules with the same Android NDK.
- Old prebuilt-glslang filesystem ABI stubs are removed.
- Only `libng_gl4es.so` is packaged; SPIRV-Cross is linked into the wrapper by Openmw3.
- `./rebuild-ng-gl4es.sh` (or `buildscripts/rebuild-ng-gl4es.sh`) rebuilds/deploys only NG-GL4ES and keeps OpenMW/OSG/Bullet/MyGUI caches intact.
- Runtime keeps the known-safe `LIBGL_SIMPLE_SHADERCONV=1` for this first Openmw3 test, so renderer change and shader-converter change are not mixed in one test.

## Streaming / presets
- Every preset uses preload distance = 1000 and one preload worker.
- Custom graphics mode also forces preload distance = 1000.
- Legacy settings writer is hard-limited to 1000, so an old preference cannot restore 3000/5000.
- Existing V13.4 conservative paging/VBO/display-list settings remain.

## Launcher UI
- Update/download icon is hidden (`gone`) but its code is retained for later use.
- Graphics settings are reorganized into visually separated panels.
- Settings list has clearer spacing/dividers and icons for main actions.
- Mods rows use rounded separated cards and a bordered selected/drag state.

## Validation
- Active OpenMW patch chain 01-20 applies cleanly to AMW(6).
- `git diff --check` passes on the patched AMW tree.
- Android resource XML parses with zero errors.
- `GraphicsPresets.kt` compiles with a minimal Kotlin stub harness.
- Native builder shell scripts pass `bash -n`.

Full APK/native compilation still requires the Android NDK/dependency downloads in the normal builder/CI environment.
