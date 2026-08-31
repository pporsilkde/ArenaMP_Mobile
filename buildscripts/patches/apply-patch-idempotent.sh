#!/bin/sh
# Arena Android idempotent dependency patch helper.
# Unified-diff hunk line numbers are intentionally ignored: anchor_patch.py
# locates each change by unique code/context anchors and fails closed on ambiguity.
set -eu
SRC=${1:-}
PATCH_FILE=${2:-}
if [ -z "$SRC" ] || [ -z "$PATCH_FILE" ]; then
    echo "usage: $0 <source-dir> <patch-file> [legacy-strip-level]" >&2
    exit 2
fi
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec python3 "$SCRIPT_DIR/anchor_patch.py" "$SRC" "$PATCH_FILE"
