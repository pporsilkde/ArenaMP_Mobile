#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "$ROOT/buildscripts/rebuild-ng-gl4es.sh" "$@"
