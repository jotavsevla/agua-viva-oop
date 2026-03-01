#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

echo "Running style checks (line endings + trailing whitespace)..."

fail=0
checked=0
declare -a shell_files=()
declare -a yaml_files=()

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

  case "$file" in
    *.sh)
      shell_files+=("$file")
      ;;
    *.yml|*.yaml)
      yaml_files+=("$file")
      ;;
  esac

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

if [[ ${#shell_files[@]} -gt 0 ]]; then
  if ! command -v shellcheck >/dev/null 2>&1; then
    echo "shellcheck nao encontrado: instale para validar scripts shell." >&2
    fail=1
  else
    echo "Running shellcheck on ${#shell_files[@]} shell file(s)..."
    if ! shellcheck -x "${shell_files[@]}"; then
      fail=1
    fi
  fi
fi

if [[ ${#yaml_files[@]} -gt 0 ]]; then
  if ! command -v yamllint >/dev/null 2>&1; then
    echo "yamllint nao encontrado: instale para validar YAML." >&2
    fail=1
  else
    echo "Running yamllint on ${#yaml_files[@]} YAML file(s)..."
    if ! yamllint -d "{extends: default, rules: {line-length: {max: 180}, truthy: disable}}" "${yaml_files[@]}"; then
      fail=1
    fi
  fi
fi

if [[ $fail -ne 0 ]]; then
  echo "Style check failed."
  exit 1
fi

echo "Style check passed."
