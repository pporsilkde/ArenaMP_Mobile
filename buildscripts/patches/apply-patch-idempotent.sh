#!/bin/sh
# Idempotent patch helper for ExternalProject caches.
# Usage: apply-patch-idempotent.sh <source-dir> <patch-file> [strip-level]
set -eu

SRC=${1:-}
PATCH_FILE=${2:-}
STRIP=${3:-1}

if [ -z "$SRC" ] || [ -z "$PATCH_FILE" ]; then
    echo "usage: $0 <source-dir> <patch-file> [strip-level]" >&2
    exit 2
fi
if [ ! -d "$SRC" ]; then
    echo "ERROR: patch source directory does not exist: $SRC" >&2
    exit 2
fi
if [ ! -f "$PATCH_FILE" ]; then
    echo "ERROR: patch file does not exist: $PATCH_FILE" >&2
    exit 2
fi

NAME=$(basename "$PATCH_FILE")
SAFE_NAME=$(printf '%s' "$NAME" | tr -c 'A-Za-z0-9_.-' '_')
PATCH_CKSUM=$(cksum "$PATCH_FILE" | awk '{print $1 "_" $2}')
MARKER="$SRC/.arena_patch_${SAFE_NAME}_${PATCH_CKSUM}"

if [ -f "$MARKER" ]; then
    echo "==> already verified $NAME"
    exit 0
fi

mark_done() {
    : > "$MARKER"
}

forward_ok() {
    patch --dry-run -d "$SRC" -p"$STRIP" -s < "$PATCH_FILE" >/dev/null 2>&1
}
reverse_ok() {
    patch --dry-run -R -d "$SRC" -p"$STRIP" -s < "$PATCH_FILE" >/dev/null 2>&1
}

if forward_ok; then
    echo "==> apply $NAME"
    patch -d "$SRC" -p"$STRIP" -s < "$PATCH_FILE"
    mark_done
    exit 0
fi

if reverse_ok; then
    echo "==> already present $NAME"
    mark_done
    exit 0
fi

# Some upstreams move context while keeping the original blob available in git.
# A clean three-way apply is safe and far less fragile than raw context matching.
if git -C "$SRC" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    if git -C "$SRC" apply --3way --check --whitespace=nowarn "$PATCH_FILE" >/dev/null 2>&1; then
        echo "==> apply $NAME (git 3-way)"
        git -C "$SRC" apply --3way --whitespace=nowarn "$PATCH_FILE"
        mark_done
        exit 0
    fi
fi

echo "ERROR: patch is neither applicable nor already present: $NAME" >&2
echo "       source: $SRC" >&2
echo "       The source tree was left untouched by this helper." >&2
# Print useful diagnostics without creating .rej files.
patch --dry-run -d "$SRC" -p"$STRIP" < "$PATCH_FILE" >&2 || true
exit 23
