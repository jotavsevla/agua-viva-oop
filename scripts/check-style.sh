#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

echo "Running style checks (line endings + trailing whitespace)..."

fail=0
checked=0

collect_files() {
  if [[ "$#" -gt 0 ]]; then
    printf '%s\0' "$@"
    return
  fi

  git ls-files -z
}

while IFS= read -r -d '' file; do
  # Ignore removed/unavailable files defensively.
  [[ -f "$file" ]] || continue

  # Skip binary files.
  if ! grep -Iq . "$file"; then
    continue
  fi

  checked=$((checked + 1))

  if grep -nH $'\r' "$file" >/tmp/style-crlf.txt; then
    echo "CRLF detected in $file"
    cat /tmp/style-crlf.txt
    fail=1
  fi

  if grep -nH -E '[[:blank:]]+$' "$file" >/tmp/style-trailing.txt; then
    echo "Trailing whitespace detected in $file"
    cat /tmp/style-trailing.txt
    fail=1
  fi
done < <(collect_files "$@")

if [[ $checked -eq 0 ]]; then
  echo "No text files to check."
  exit 0
fi

if [[ $fail -ne 0 ]]; then
  echo "Style check failed."
  exit 1
fi

echo "Style check passed."
