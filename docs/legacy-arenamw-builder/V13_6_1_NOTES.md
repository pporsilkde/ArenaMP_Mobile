# ArenaMW Mobile V13.6.1 — NG-GL4ES Openmw3 build fix

Fixes GitHub Actions failure while configuring Sisah2/NG-GL4ES `Openmw3`:

`3rdparty/SPIRV-Cross/CMakeLists.txt: Build out of tree to avoid overwriting Makefile`

Changes:
- removed `BUILD_IN_SOURCE 1` from the NG-GL4ES ExternalProject only;
- NG-GL4ES now configures in ExternalProject's separate `NG-GL4ES-build` binary directory;
- install still copies `libng_gl4es.so` from the binary directory into the shared prefix;
- debug-symbol path updated for the new binary directory;
- cache identity includes `out-of-source-v1`, forcing a one-time cleanup of the previously contaminated NG-GL4ES source/build prefix only;
- `rebuild-ng-gl4es.sh` remains an NG-GL4ES-only rebuild path.

No OpenMW gameplay/render patches were changed from V13.6.
