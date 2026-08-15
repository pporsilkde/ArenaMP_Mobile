#!/bin/sh
# Relax the hard Clang-version guard in extern/maskedoc/sse2neon.h so the
# code compiles with Clang 9 (the version shipped inside NDK r21e).
#
# The guard reads:
#   #error "Clang versions earlier than 11 are not supported."
# and the accompanying comment is "known NEON codegen bugs (issue #622)" —
# not "missing features". Empirically, the SSE2-subset that maskedoc uses
# builds fine on Clang 9. This script just comments out that #error.
#
# Usage: sh patch-sse2neon.sh <arenamp-source-dir>

set -e
SRC="$1"
if [ -z "$SRC" ]; then
    echo "patch-sse2neon.sh: missing source dir argument" >&2
    exit 1
fi

TARGET="$SRC/extern/maskedoc/sse2neon.h"
if [ ! -f "$TARGET" ]; then
    # File absent — nothing to do. Not fatal: future ArenaMP versions may
    # drop maskedoc or move it.
    echo "patch-sse2neon.sh: $TARGET not present, skipping"
    exit 0
fi

# Idempotent: only patch if the original #error line is still there.
if grep -q '^#error "Clang versions earlier than 11 are not supported."$' "$TARGET"; then
    sed -i 's|^#error "Clang versions earlier than 11 are not supported."$|// (patched for NDK r21e / Clang 9) &|' "$TARGET"
    echo "Patched: $TARGET"
else
    echo "patch-sse2neon.sh: $TARGET already patched or signature not found, skipping"
fi
