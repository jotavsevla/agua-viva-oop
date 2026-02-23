#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Uso:
  scripts/poc/smoke-clean-linux.sh [--no-bootstrap]

Executa um smoke end-to-end em Linux limpo (container Ubuntu descartavel),
simulando o onboarding de outra pessoa/maquina.

Variaveis opcionais:
  VM_IMAGE             (default: ubuntu:24.04)
  JAVA_MAJOR           (default: 25)
  COMPOSE_PROJECT_NAME (default: av_vmtest)
  POSTGRES_TEST_PORT   (default: 55435)
  SOLVER_PORT          (default: 18080)
  API_PORT             (default: 18082)
  UI_PORT              (default: 14174)
  SOLVER_REBUILD       (default: 0)

Exemplo:
  COMPOSE_PROJECT_NAME=av_vmtest_ana scripts/poc/smoke-clean-linux.sh
USAGE
}

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VM_IMAGE="${VM_IMAGE:-ubuntu:24.04}"
JAVA_MAJOR="${JAVA_MAJOR:-25}"
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-av_vmtest}"
POSTGRES_TEST_PORT="${POSTGRES_TEST_PORT:-55435}"
SOLVER_PORT="${SOLVER_PORT:-18080}"
API_PORT="${API_PORT:-18082}"
UI_PORT="${UI_PORT:-14174}"
SOLVER_REBUILD="${SOLVER_REBUILD:-0}"
RUN_BOOTSTRAP=1

while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-bootstrap)
      RUN_BOOTSTRAP=0
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

require_cmd docker

if [[ ! -S /var/run/docker.sock ]]; then
  echo "Socket Docker nao encontrado em /var/run/docker.sock" >&2
  exit 1
fi

echo "[smoke-clean-linux] Iniciando validacao em ${VM_IMAGE}"
echo "[smoke-clean-linux] Java alvo: ${JAVA_MAJOR}"
echo "[smoke-clean-linux] Portas: postgres=${POSTGRES_TEST_PORT} solver=${SOLVER_PORT} api=${API_PORT} ui=${UI_PORT}"
echo "[smoke-clean-linux] Compose project: ${COMPOSE_PROJECT_NAME}"

docker run --rm -i \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "${ROOT_DIR}:/workspace" \
  -w /workspace \
  -e COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME}" \
  -e JAVA_MAJOR="${JAVA_MAJOR}" \
  -e POSTGRES_TEST_PORT="${POSTGRES_TEST_PORT}" \
  -e POSTGRES_PORT="${POSTGRES_TEST_PORT}" \
  -e POSTGRES_HOST="host.docker.internal" \
  -e SOLVER_PORT="${SOLVER_PORT}" \
  -e SOLVER_URL="http://host.docker.internal:${SOLVER_PORT}" \
  -e API_PORT="${API_PORT}" \
  -e UI_PORT="${UI_PORT}" \
  -e SOLVER_REBUILD="${SOLVER_REBUILD}" \
  -e RUN_BOOTSTRAP="${RUN_BOOTSTRAP}" \
  "${VM_IMAGE}" \
  bash -s <<'EOF'
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

apt-get update -qq >/dev/null
apt-get install -y -qq \
  "openjdk-${JAVA_MAJOR}-jdk" \
  maven \
  python3 \
  python3-venv \
  python3-pip \
  nodejs \
  npm \
  docker.io \
  docker-compose-v2 \
  curl \
  ca-certificates \
  >/dev/null

JAVA_DETECTED="$(java -version 2>&1 | awk -F[\".] 'NR==1 { if ($2 == "1") print $3; else print $2 }')"
if [[ "${JAVA_DETECTED}" != "${JAVA_MAJOR}" ]]; then
  echo "Java fora do padrao esperado: detectado=${JAVA_DETECTED} esperado=${JAVA_MAJOR}" >&2
  exit 1
fi

if [[ "${RUN_BOOTSTRAP}" == "1" ]]; then
  scripts/bootstrap-dev.sh
fi

scripts/poc/start-test-env.sh
EOF
