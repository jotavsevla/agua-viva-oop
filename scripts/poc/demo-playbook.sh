#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Uso:
  scripts/poc/demo-playbook.sh [--strict-rounds N] [--skip-start-env] [--skip-gate]

Playbook padrao para demo operacional reproduzivel:
  1) sobe ambiente de teste
  2) reseta estado controlado
  3) executa cenarios canonicos (feliz/falha/cancelamento)
  4) roda business gate strict e registra artifact

Flags:
  --strict-rounds N  rodadas strict do gate (default: 3)
  --skip-start-env   nao sobe ambiente (assume ambiente ja ativo)
  --skip-gate        roda somente os cenarios, sem gate final
  -h, --help         mostra ajuda
USAGE
}

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STRICT_ROUNDS=3
SKIP_START_ENV=0
SKIP_GATE=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --strict-rounds)
      STRICT_ROUNDS="$2"
      shift
      ;;
    --strict-rounds=*)
      STRICT_ROUNDS="${1#*=}"
      ;;
    --skip-start-env)
      SKIP_START_ENV=1
      ;;
    --skip-gate)
      SKIP_GATE=1
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

if ! [[ "$STRICT_ROUNDS" =~ ^[0-9]+$ ]] || [[ "$STRICT_ROUNDS" -le 0 ]]; then
  echo "Valor invalido para --strict-rounds: ${STRICT_ROUNDS}" >&2
  exit 1
fi

log() {
  echo "[demo-playbook] $*"
}

cd "$ROOT_DIR"

if [[ "$SKIP_START_ENV" -eq 0 ]]; then
  log "Subindo ambiente de teste"
  ./scripts/poc/start-test-env.sh
else
  log "Pulando subida de ambiente (--skip-start-env)"
fi

log "Resetando estado controlado"
./scripts/poc/reset-test-state.sh

for scenario in feliz falha cancelamento; do
  log "Executando cenario: ${scenario}"
  ./scripts/poc/run-cenario.sh "$scenario"
done

if [[ "$SKIP_GATE" -eq 0 ]]; then
  log "Executando business gate strict (${STRICT_ROUNDS} rodada(s))"
  ./scripts/poc/run-business-gate.sh --mode strict --rounds "$STRICT_ROUNDS"
else
  log "Pulando gate final (--skip-gate)"
fi

log "Playbook finalizado."

