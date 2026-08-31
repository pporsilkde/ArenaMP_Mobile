#!/bin/sh
# ArenaMP Android cumulative patch applicator — anchor edition.
# Textual unified diffs are applied by unique code/context anchors; @@ line
# coordinates are ignored. Python stages use semantic code anchors as well.
set -eu

SRC=${1:-}
if [ -z "$SRC" ] || [ ! -d "$SRC/.git" ]; then
    echo "apply-arenamp-patches.sh: expected ArenaMP git source directory" >&2
    exit 2
fi

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ANCHOR_PATCH="$SCRIPT_DIR/../anchor_patch.py"
PATCHSET_ID="arenamp-android-y001-anchor-x057-main-safe"
MARKER="$SRC/.arenamp_android_patchset"

copy_if_changed() {
    from=$1; to=$2
    if ! cmp -s "$from" "$to" 2>/dev/null; then
        mkdir -p "$(dirname "$to")"
        cp "$from" "$to"
        echo "==> update $(basename "$to")"
    else
        echo "==> already current $(basename "$to")"
    fi
}

apply_anchor_patch() {
    p=$1
    echo "==> anchor patch $(basename "$p")"
    python3 "$ANCHOR_PATCH" "$SRC" "$p"
}

verify_patchset() {
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
    echo "==> ArenaMP X057/Y001 Android feature patchset verified"
}

finish() {
    copy_if_changed "$SCRIPT_DIR/android_main.cpp" "$SRC/apps/openmw/android_main.cpp"
    copy_if_changed "$SCRIPT_DIR/android_server_jni.cpp" "$SRC/apps/openmw-mp/android_server_jni.cpp"
    sh "$SCRIPT_DIR/patch-sse2neon.sh" "$SRC"
    verify_patchset
    printf '%s\n' "$PATCHSET_ID" > "$MARKER"
    echo "==> ArenaMP Android patch set is present: $PATCHSET_ID"
}

if [ -f "$MARKER" ] && [ "$(cat "$MARKER" 2>/dev/null || true)" = "$PATCHSET_ID" ]; then
    finish
    echo "==> ArenaMP Android patch set already verified"
    exit 0
fi

if ! git -C "$SRC" diff --quiet --ignore-submodules -- || ! git -C "$SRC" diff --cached --quiet --ignore-submodules --; then
    echo "==> stale ArenaMP patch state detected; resetting source checkout"
    git -C "$SRC" reset --hard HEAD >/dev/null
    git -C "$SRC" clean -fdx >/dev/null
fi

cleanup_on_error() {
    status=$?
    if [ "$status" -ne 0 ]; then
        echo "ERROR: ArenaMP Android anchor patch stage failed (status $status)." >&2
        echo "       Restoring clean checkout; ambiguous anchors are never forced." >&2
        git -C "$SRC" reset --hard HEAD >/dev/null 2>&1 || true
        git -C "$SRC" clean -fdx >/dev/null 2>&1 || true
    fi
    exit "$status"
}
trap cleanup_on_error EXIT INT TERM HUP

# 06b used to duplicate two drifting hunks from 06 and depended on GNU patch
# fuzz. The anchor engine can safely locate those hunks directly, so the split
# patch and its duplicate source are no longer needed.
apply_anchor_patch "$SCRIPT_DIR/06-arenamp-mobile-cumulative-v1-2.patch"

if [ -n "${ARENAMP_NETWORK_COMMIT:-}" ]; then
    python3 "$SCRIPT_DIR/07-enable-network-identity-overrides.py" "$SRC" "$ARENAMP_NETWORK_COMMIT"
else
    python3 "$SCRIPT_DIR/07-enable-network-identity-overrides.py" "$SRC"
fi
apply_anchor_patch "$SCRIPT_DIR/08-arenamp-android-compact-display-v1-2-7.patch"
copy_if_changed "$SCRIPT_DIR/android_main.cpp" "$SRC/apps/openmw/android_main.cpp"
sh "$SCRIPT_DIR/patch-sse2neon.sh" "$SRC"
copy_if_changed "$SCRIPT_DIR/android_server_jni.cpp" "$SRC/apps/openmw-mp/android_server_jni.cpp"
python3 "$SCRIPT_DIR/09-enable-android-server-host.py" "$SRC"
python3 "$SCRIPT_DIR/10-arenamp-auth-map-inventory-stability.py" "$SRC"
python3 "$SCRIPT_DIR/11-arenamp-aoi-localmap-android.py" "$SRC"
python3 "$SCRIPT_DIR/12-arenamp-magic-mali-stability.py" "$SRC"
# X056/X057 core is now part of AMP main. Only the Android-specific narrow-screen
# Player Menu adaptation remains as a semantic patch.
python3 "$SCRIPT_DIR/13-arenamp-android-responsive-player-menu.py" "$SRC"
finish

trap - EXIT INT TERM HUP
exit 0
