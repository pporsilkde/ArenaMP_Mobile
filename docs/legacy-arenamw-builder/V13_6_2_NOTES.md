# ArenaMW Mobile V13.6.2

Fixes the next Sisah2/NG-GL4ES `Openmw3` build failure seen with the builder's Android NDK r21e.

The branch uses `std::regex::multiline` in `src/gl/glsl/glsl_for_es.cpp`. NDK r21e's libc++ does not expose that member, so compilation stops after glslang and SPIRV-Cross have already built successfully.

V13.6.2 adds an idempotent NG-GL4ES source compatibility step which replaces only that multiline-regex construct with an equivalent explicit line-boundary expression. It does not disable the new shader converter and does not change ArenaMW, OSG, water, PBR, or gameplay patches.

The NG-GL4ES cache identity is bumped to `ndk-r21-regex-v2`, therefore the next CI run cleans/rebuilds only the NG-GL4ES ExternalProject once. Other native dependency caches stay intact.

`rebuild-ng-gl4es.sh` remains the fast path for subsequent wrapper-only rebuilds.
