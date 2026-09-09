#!/usr/bin/env bash
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
exec bash "$ROOT/external/imagedecoder-houri/tests/wsl/run_all.sh" "$@"
