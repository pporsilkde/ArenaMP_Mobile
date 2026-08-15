#!/bin/bash
set -e
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$DIR/buildscripts/rebuild-ng-gl4es.sh" "$@"
