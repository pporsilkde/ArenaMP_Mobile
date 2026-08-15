# V13.7.3 — Sisah2/Openmw3 + NDK r21e filesystem link fix

The V13.7.2 build reaches 92% of NG-GL4ES and then fails while linking `libng_gl4es.so` because pinned glslang 7099c12 calls `std::filesystem::absolute()` from `TInfoSinkBase::location()`. Android NDK r21e ships the header declarations but not the implementation required by this newer glslang revision.

ArenaMW now patches only those diagnostic-path conversions on Android. The original shader filename/path is printed instead of converting it to an absolute path. Shader parsing, glslang compilation, SPIR-V generation, SPIRV-Cross and NG-GL4ES shader semantics are unchanged.

This deliberately replaces the older unsafe ABI symbol stub: returning a C++ `filesystem::path` by value through a hand-written internal libc++ symbol is ABI-sensitive and unnecessary for a diagnostic-only feature.

`GL4ES_PATCHSET` was bumped to `arenamw-openmw3-android-r21e-v3` so CI invalidates the previous NG-GL4ES build cache.
