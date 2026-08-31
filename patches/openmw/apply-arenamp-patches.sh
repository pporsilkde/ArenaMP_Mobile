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
PATCHSET_ID="arenamp-android-x057b-patchchain-06compat-15-x056-yhold-v1"
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

git_apply_3way_is_clean() {
    p=$1
    shift
    log_file=$(mktemp)
    status=0
    git -C "$SRC" apply --3way --check --whitespace=nowarn "$@" "$p" >"$log_file" 2>&1 || status=$?
    if [ "$status" -eq 0 ] && ! grep -q "with conflicts" "$log_file"; then
        rm -f "$log_file"
        return 0
    fi
    rm -f "$log_file"
    return 1
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

    # `git apply --3way --check` can return success while reporting
    # "Applied patch ... with conflicts". Never treat that as a safe merge.
    if git_apply_3way_is_clean "$p"; then
        echo "==> apply $name (git 3-way)"
        git -C "$SRC" apply --3way --whitespace=nowarn "$p"
        return 0
    fi

    echo "ERROR: ArenaMP patch neither applies nor is already present: $name" >&2
    git -C "$SRC" apply --check --whitespace=nowarn "$p" >&2 || true
    return 24
}

apply_mobile_06_core() {
    p=$1
    name=$(basename "$p")
    # These two files drift independently in AMP/main. Their Android changes are
    # applied immediately afterwards by the small context/fuzz patch 06b.
    exclude_render="--exclude=apps/openmw/mwrender/renderingmanager.cpp"
    exclude_ui="--exclude=files/ui/graphicspage.ui"

    if git -C "$SRC" apply --check --whitespace=nowarn "$exclude_render" "$exclude_ui" "$p" >/dev/null 2>&1; then
        echo "==> apply $name (render/UI split out)"
        git -C "$SRC" apply --whitespace=nowarn "$exclude_render" "$exclude_ui" "$p"
        return 0
    fi

    if git -C "$SRC" apply --reverse --check --whitespace=nowarn "$exclude_render" "$exclude_ui" "$p" >/dev/null 2>&1; then
        echo "==> already present $name (render/UI split out)"
        return 0
    fi

    if git_apply_3way_is_clean "$p" "$exclude_render" "$exclude_ui"; then
        echo "==> apply $name (git 3-way, render/UI split out)"
        git -C "$SRC" apply --3way --whitespace=nowarn "$exclude_render" "$exclude_ui" "$p"
        return 0
    fi

    echo "ERROR: ArenaMP mobile core patch failed even with render/UI split out: $name" >&2
    git -C "$SRC" apply --check --whitespace=nowarn "$exclude_render" "$exclude_ui" "$p" >&2 || true
    return 25
}

apply_fuzzy_patch() {
    p=$1
    name=$(basename "$p")

    if patch -d "$SRC" -p1 --forward --batch --fuzz=3 --dry-run < "$p" >/dev/null 2>&1; then
        echo "==> apply $name (context/fuzz)"
        patch -d "$SRC" -p1 --forward --batch --fuzz=3 < "$p"
        return 0
    fi

    if patch -d "$SRC" -p1 --reverse --batch --fuzz=3 --dry-run < "$p" >/dev/null 2>&1; then
        echo "==> already present $name"
        return 0
    fi

    echo "ERROR: ArenaMP context/fuzz patch failed: $name" >&2
    patch -d "$SRC" -p1 --forward --batch --fuzz=3 --dry-run < "$p" >&2 || true
    return 26
}

verify_x056_patchset() {
    test -f "$SRC/server/scripts/groupHelper.lua" || { echo "ERROR: X056 groupHelper missing" >&2; return 31; }
    test -f "$SRC/server/scripts/positionSafetyHelper.lua" || { echo "ERROR: X051 position safety missing" >&2; return 32; }
    test -f "$SRC/files/mygui/ArenaMPChatColor.xml" || { echo "ERROR: ArenaMP color emoji resource missing" >&2; return 33; }
    grep -q "OnPlayerPosition" "$SRC/apps/openmw-mp/processors/player/ProcessorPlayerPosition.hpp" || { echo "ERROR: OnPlayerPosition callback missing" >&2; return 34; }
    grep -q "openPlayerMenu" "$SRC/apps/openmw/mwmp/GUIController.cpp" || { echo "ERROR: Player Menu hold logic missing" >&2; return 35; }
    grep -q "player menu hold" "$SRC/files/settings-default.cfg" || { echo "ERROR: Player Menu hold setting missing" >&2; return 36; }
    if grep -q "FontManager::getInstance().isExist" "$SRC/apps/openmw/mwmp/GUI/GUIChat.cpp"; then
        echo "ERROR: incompatible MyGUI FontManager::isExist returned" >&2
        return 37
    fi
    echo "==> ArenaMP X056 Android feature patchset verified"
}

finish() {
    verify_x056_patchset
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

apply_mobile_06_core "$SCRIPT_DIR/06-arenamp-mobile-cumulative-v1-2.patch"
apply_fuzzy_patch "$SCRIPT_DIR/06b-arenamp-mobile-render-ui-fuzzy.patch"
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
apply_git_patch "$SCRIPT_DIR/13-arenamp-x056-server-network.patch"
apply_git_patch "$SCRIPT_DIR/14-arenamp-x056-client-player-menu.patch"
apply_git_patch "$SCRIPT_DIR/15-arenamp-x056-resources.patch"
finish

trap - EXIT INT TERM HUP
exit 0
