#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ROUNDS="${ROUNDS:-3}"

exec "$ROOT_DIR/scripts/poc/run-e2e-local.sh" --mode strict --rounds "$ROUNDS" "$@"
