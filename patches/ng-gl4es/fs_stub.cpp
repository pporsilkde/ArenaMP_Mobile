// fs_stub.cpp — std::filesystem::__absolute stub for NDK r21e
//
// libglslang.a (prebuilt with newer NDK) requires:
//   std::__ndk1::__fs::filesystem::__absolute(
//       const std::__ndk1::__fs::filesystem::path&,
//       std::__ndk1::error_code*)
//
// Correct Itanium ABI mangled name (verified with c++filt):
//   _ZNSt6__ndk14__fs10filesystem10__absoluteERKNS1_4pathEPNS_10error_codeE
//
// NOTE: "__ndk1" is 6 characters (not 7). Earlier versions of this stub
// used "7__ndk1" which produced an invalid symbol and did not satisfy
// the linker.
//
// We use __asm__ to force the exact symbol name, bypassing local type mangling.

// Step 1: declare the local function with asm-renamed symbol (declaration only)
extern "C" void* __fs_abs_impl(void*, void*)
    __asm__("_ZNSt6__ndk14__fs10filesystem10__absoluteERKNS1_4pathEPNS_10error_codeE");

// Step 2: define the function body (compiled under the renamed symbol)
extern "C" __attribute__((visibility("default")))
void* __fs_abs_impl(void* p, void* /*ec*/) {
    return p;  // no-op: return input path unchanged
}
