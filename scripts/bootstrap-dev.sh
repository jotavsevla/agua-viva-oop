#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Uso:
  scripts/bootstrap-dev.sh [--skip-java] [--skip-python] [--skip-node]

Bootstrap local de dependencias de build:
  - Java/Maven: valida Java 25.x e baixa dependencias no repositorio Maven local
  - Python: cria/atualiza solver/.venv e instala solver/requirements.txt
  - Node: instala dependencias do prototipo UI com npm ci
USAGE
}

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SKIP_JAVA=0
SKIP_PYTHON=0
SKIP_NODE=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-java)
      SKIP_JAVA=1
      ;;
    --skip-python)
      SKIP_PYTHON=1
      ;;
    --skip-node)
      SKIP_NODE=1
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Parametro invalido: $1" >&2
      usage
      exit 1
      ;;
  esac
  shift
done

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Comando obrigatorio ausente: $1" >&2
    exit 1
  }
}

log() {
  echo "[bootstrap-dev] $*"
}

if [[ "$SKIP_JAVA" -eq 0 ]]; then
  require_cmd mvn
  log "Baixando dependencias Maven (inclui validacao de Java 25.x)..."
  (
    cd "$ROOT_DIR"
    mvn -B -DskipTests validate dependency:go-offline
  )
fi

if [[ "$SKIP_PYTHON" -eq 0 ]]; then
  require_cmd python3

  if [[ ! -f "$ROOT_DIR/solver/requirements.txt" ]]; then
    echo "Arquivo obrigatorio ausente: solver/requirements.txt" >&2
    exit 1
  fi

  VENV_DIR="$ROOT_DIR/solver/.venv"
  if [[ -d "$VENV_DIR" ]]; then
    if [[ ! -x "$VENV_DIR/bin/python" ]] || ! "$VENV_DIR/bin/python" -c "import sys" >/dev/null 2>&1; then
      log "Virtualenv existente em solver/.venv parece incompativel; recriando..."
      rm -rf "$VENV_DIR"
    fi
  fi

  log "Criando/atualizando virtualenv Python em solver/.venv..."
  python3 -m venv "$VENV_DIR"
  "$VENV_DIR/bin/python" -m pip install --upgrade pip
  "$VENV_DIR/bin/pip" install -r "$ROOT_DIR/solver/requirements.txt"
fi

if [[ "$SKIP_NODE" -eq 0 ]]; then
  require_cmd npm

  if [[ ! -f "$ROOT_DIR/produto-ui/prototipo/package-lock.json" ]]; then
    echo "Arquivo obrigatorio ausente: produto-ui/prototipo/package-lock.json" >&2
    exit 1
  fi

  log "Instalando dependencias Node (npm ci) em produto-ui/prototipo..."
  (
    cd "$ROOT_DIR/produto-ui/prototipo"
    npm ci
  )
fi

log "Bootstrap concluido."
