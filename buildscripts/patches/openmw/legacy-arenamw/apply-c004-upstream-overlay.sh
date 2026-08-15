#!/usr/bin/env sh
set -eu
SRC="$1"
HERE="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
OVERLAY="$HERE/c004-upstream-overlay"
# Cumulative 004 is the exact ArenaMW source level targeted by this Android
# builder. Overlay only the files shipped by C004, then remove the obsolete SSR
# composite that C004 explicitly deletes.
cp -a "$OVERLAY/apps/." "$SRC/apps/"
cp -a "$OVERLAY/components/." "$SRC/components/"
cp -a "$OVERLAY/files/." "$SRC/files/"
rm -f "$SRC/files/shaders/native_effects_composite.frag"
