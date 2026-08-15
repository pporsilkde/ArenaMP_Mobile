// fs_stub.S — ARM64 assembly stub for std::filesystem::__absolute
// Provides the missing symbol from NDK r21e when libglslang.a (prebuilt
// with newer NDK) references std::__ndk1::__fs::filesystem::__absolute.
// The function just returns its first argument (the path) unchanged.
// In ARM64 ABI: x0 = first arg (path const&), return value in x0.

    .text
    .align 2
    .global _ZNSt7__ndk14__fs10filesystem10__absoluteERKNS2_4pathEPNS0_10error_codeE
    .type   _ZNSt7__ndk14__fs10filesystem10__absoluteERKNS2_4pathEPNS0_10error_codeE, %function
_ZNSt7__ndk14__fs10filesystem10__absoluteERKNS2_4pathEPNS0_10error_codeE:
    // Return path argument unchanged (x0 already has the return value we want)
    ret
    .size _ZNSt7__ndk14__fs10filesystem10__absoluteERKNS2_4pathEPNS0_10error_codeE, .-_ZNSt7__ndk14__fs10filesystem10__absoluteERKNS2_4pathEPNS0_10error_codeE
