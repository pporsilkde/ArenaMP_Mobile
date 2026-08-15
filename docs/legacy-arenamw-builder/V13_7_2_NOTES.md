# V13.7.2

Fixes Sisah2/NG-GL4ES `Openmw3` compilation with the project's Android NDK r21e/libc++.

The upstream converter uses `std::regex::multiline`. The r21e libc++ used by this builder does not expose that `basic_regex` member, so ArenaMW rewrites the affected uniform-line regex to be explicitly line-aware without relying on the C++17 multiline flag. Semantics are preserved for the GI temporal-filter helper.

The NG-GL4ES dependency cache identity now contains a patchset revision and clears both source ExternalProject state and its out-of-source binary directory when the patchset changes, preventing a failed V13.7.1 object from being reused.
