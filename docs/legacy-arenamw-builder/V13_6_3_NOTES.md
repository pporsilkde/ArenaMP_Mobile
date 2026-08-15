# ArenaMW Mobile V13.6.3 — NG-GL4ES/glslang NDK r21 link fix

Fixes the V13.6.2 GitHub Actions failure at the final `libng_gl4es.so` link:

`undefined reference to std::__ndk1::__fs::filesystem::__absolute(...)`

The pinned Openmw3 glslang commit uses `std::filesystem::absolute()` only while formatting shader diagnostic paths (`TInfoSinkBase::location`). Android NDK r21e exposes the header but this build does not resolve the implementation at the final shared-library link.

V13.6.3 extends the existing idempotent Openmw3 compatibility script:
- retains the `std::regex::multiline` workaround from V13.6.2;
- on Android only, glslang diagnostics keep the original path instead of converting it to an absolute filesystem path;
- desktop/non-Android glslang behavior is untouched;
- no NDK upgrade and no `-lc++fs` dependency is introduced;
- NG-GL4ES cache identity is bumped to `ndk-r21-compat-v3`, forcing a one-time rebuild of NG-GL4ES only.
