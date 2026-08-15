# ArenaMW Mobile V13.7.1 — Sisah2/Openmw3 out-of-source build fix

Fixes the GitHub Actions failure from run logs_86383051495.

Fatal error was:

```
CMake Error at 3rdparty/SPIRV-Cross/CMakeLists.txt:71 (message):
  Build out of tree to avoid overwriting Makefile
```

Changes:
- NG-GL4ES / Sisah2 Openmw3 is now configured in `NG-GL4ES-build/`, never in the source tree.
- stale V13.7 in-source `CMakeCache.txt` / `CMakeFiles` are removed before configure.
- install copies `libng_gl4es.so` explicitly from `<BINARY_DIR>`.
- debug-symbol packaging points at `NG-GL4ES-build/libng_gl4es.so`.
- all V13.7 water/UI/gameplay changes are otherwise unchanged.
