#!/bin/sh
set -eu
BUILDER=${1:-.}
for f in \
  "$BUILDER/patches/openmw/apply-arenamp-patches.sh" \
  "$BUILDER/buildscripts/patches/openmw/apply-arenamp-patches.sh"; do
  sh -n "$f"
  grep -q 'with conflicts' "$f"
  grep -q '06b-arenamp-mobile-render-ui-fuzzy.patch' "$f"
done
for f in \
  "$BUILDER/patches/openmw/06b-arenamp-mobile-render-ui-fuzzy.patch" \
  "$BUILDER/buildscripts/patches/openmw/06b-arenamp-mobile-render-ui-fuzzy.patch"; do
  test -s "$f"
  grep -q 'renderingmanager.cpp' "$f"
  grep -q 'graphicspage.ui' "$f"
done
echo 'X057b patch06 harness: OK'
