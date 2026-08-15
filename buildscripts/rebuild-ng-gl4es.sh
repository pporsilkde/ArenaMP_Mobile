#!/usr/bin/env bash
set -euo pipefail
ARCH="${ARCH:-arm64}"
exec "$(dirname "$0")/build.sh" --arch "$ARCH" --ccache --release --ng-gl4es-only "$@"
