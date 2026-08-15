# ArenaMW Mobile V13.7

## Launcher UI
- Removed icons from Mods, Graphics and On-screen controls preference rows.
- Graphics row is now single-line like the neighboring launcher actions for cleaner alignment.
- Removed the preload selector from the Graphics window. Preload remains internally fixed to 1000 with one worker for every preset/custom profile.
- Moved the combined keyboard/F11/F12 touch button to virtual (12,90), directly below the top-left ESC/menu button.

## Water fast path
Patch `21-android-water-fastpath-v13-7.patch` is applied after V13.6.

PBR Android water:
- disables the 12-step near-field raymarch;
- disables analytic FBM normal reconstruction (three extra wave-height evaluations per fragment);
- keeps PBR lighting, reflection/refraction, normal-map animation, rain/ripples and foam;
- disables the decorative procedural sparkle/breathing noise layer on Android;
- reduces foam alpha lookups from 3 to 2;
- limits the fifth high-frequency normal sample to the immediate near field.

Simple water:
- reduces animated normal-map fetches from 4 to 3;
- replaces the exact dielectric Fresnel equation with the cheaper Schlick approximation.

## NG-GL4ES
Still uses `https://github.com/Sisah2/NG-GL4ES.git`, branch `Openmw3`.
The known-working `LIBGL_SIMPLE_SHADERCONV=1` stays the default. The water optimization is source-side, so it does not require globally switching all OpenMW shaders to the advanced converter.
