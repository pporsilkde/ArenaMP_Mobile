#!/bin/sh
# ArenaMP Android cumulative patch applicator.
#
# This wrapper is intentionally idempotent. ExternalProject and GitHub Actions
# can restore a source checkout whose patch stamp was lost or rerun PATCH_COMMAND
# after a CMake change. Reapplying a raw git patch in that state used to abort the
# whole build even when the requested change was already present.
set -eu

SRC=${1:-}
if [ -z "$SRC" ] || [ ! -d "$SRC/.git" ]; then
    echo "apply-arenamp-patches.sh: expected ArenaMP git source directory" >&2
    exit 2
fi

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PATCHSET_ID="arenamp-android-v1.3-robust-patch-driver-06-12"
MARKER="$SRC/.arenamp_android_patchset"

copy_if_changed() {
    from=$1
    to=$2
    if ! cmp -s "$from" "$to" 2>/dev/null; then
        mkdir -p "$(dirname "$to")"
        cp "$from" "$to"
        echo "==> update $(basename "$to")"
    else
        echo "==> already current $(basename "$to")"
    fi
}

apply_git_patch() {
    p=$1
    name=$(basename "$p")

    if git -C "$SRC" apply --check --whitespace=nowarn "$p" >/dev/null 2>&1; then
        echo "==> apply $name"
        git -C "$SRC" apply --whitespace=nowarn "$p"
        return 0
    fi

    if git -C "$SRC" apply --reverse --check --whitespace=nowarn "$p" >/dev/null 2>&1; then
        echo "==> already present $name"
        return 0
    fi

    # Current AMP/main can move context around while the old blob is still in
    # repository history. Let git reconstruct a clean merge when possible.
    if git -C "$SRC" apply --3way --check --whitespace=nowarn "$p" >/dev/null 2>&1; then
        echo "==> apply $name (git 3-way)"
        git -C "$SRC" apply --3way --whitespace=nowarn "$p"
        return 0
    fi

    echo "ERROR: ArenaMP patch neither applies nor is already present: $name" >&2
    git -C "$SRC" apply --check --whitespace=nowarn "$p" >&2 || true
    return 24
}

finish() {
    copy_if_changed "$SCRIPT_DIR/android_main.cpp" "$SRC/apps/openmw/android_main.cpp"
    copy_if_changed "$SCRIPT_DIR/android_server_jni.cpp" "$SRC/apps/openmw-mp/android_server_jni.cpp"
    sh "$SCRIPT_DIR/patch-sse2neon.sh" "$SRC"
    printf '%s
' "$PATCHSET_ID" > "$MARKER"
    echo "==> ArenaMP Android patch set is present: $PATCHSET_ID"
}

# Fast path for a valid restored incremental source tree. Still refresh the two
# generated Android entry points and the NDK compatibility shim if necessary.
if [ -f "$MARKER" ] && [ "$(cat "$MARKER" 2>/dev/null || true)" = "$PATCHSET_ID" ]; then
    finish
    echo "==> ArenaMP Android patch set already verified"
    exit 0
fi

# The source under ExternalProject is a disposable checkout. A stale/partial
# previous patch attempt must never poison the next CI run. If there is no valid
# marker, return it to its checked-out upstream commit before rebuilding the
# deterministic patch state.
if ! git -C "$SRC" diff --quiet --ignore-submodules -- ||    ! git -C "$SRC" diff --cached --quiet --ignore-submodules --; then
    echo "==> stale ArenaMP patch state detected; resetting source checkout"
    git -C "$SRC" reset --hard HEAD >/dev/null
    git -C "$SRC" clean -fdx >/dev/null
fi

cleanup_on_error() {
    status=$?
    if [ "$status" -ne 0 ]; then
        echo "ERROR: ArenaMP Android patch stage failed (status $status)." >&2
        echo "       Restoring clean source so the next build cannot inherit a half-patched tree." >&2
        git -C "$SRC" reset --hard HEAD >/dev/null 2>&1 || true
        git -C "$SRC" clean -fdx >/dev/null 2>&1 || true
    fi
    exit "$status"
}
trap cleanup_on_error EXIT INT TERM HUP

apply_git_patch "$SCRIPT_DIR/06-arenamp-mobile-cumulative-v1-2.patch"
if [ -n "${ARENAMP_NETWORK_COMMIT:-}" ]; then
    python3 "$SCRIPT_DIR/07-enable-network-identity-overrides.py" "$SRC" "$ARENAMP_NETWORK_COMMIT"
else
    python3 "$SCRIPT_DIR/07-enable-network-identity-overrides.py" "$SRC"
fi
apply_git_patch "$SCRIPT_DIR/08-arenamp-android-compact-display-v1-2-7.patch"
copy_if_changed "$SCRIPT_DIR/android_main.cpp" "$SRC/apps/openmw/android_main.cpp"
sh "$SCRIPT_DIR/patch-sse2neon.sh" "$SRC"
copy_if_changed "$SCRIPT_DIR/android_server_jni.cpp" "$SRC/apps/openmw-mp/android_server_jni.cpp"
python3 "$SCRIPT_DIR/09-enable-android-server-host.py" "$SRC"
python3 "$SCRIPT_DIR/10-arenamp-auth-map-inventory-stability.py" "$SRC"
python3 "$SCRIPT_DIR/11-arenamp-aoi-localmap-android.py" "$SRC"
python3 "$SCRIPT_DIR/12-arenamp-magic-mali-stability.py" "$SRC"
finish

trap - EXIT INT TERM HUP
exit 0
