#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

DB_CONTAINER="${DB_CONTAINER:-postgres-oop-test}"
DB_USER="${DB_USER:-postgres}"
DB_NAME="${DB_NAME:-agua_viva_oop_test}"
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5435}"
DB_PASSWORD="${DB_PASSWORD:-postgres}"

CLIENTES_BBOX="${CLIENTES_BBOX:--43.9600,-16.8200,-43.7800,-16.6200}"
GEOFENCE_SUMMARY_FILE="${GEOFENCE_SUMMARY_FILE:-$ROOT_DIR/artifacts/poc/geofence-summary.json}"

MIN_LON=""
MIN_LAT=""
MAX_LON=""
MAX_LAT=""

usage() {
  cat <<'USAGE'
Uso:
  scripts/poc/check-clientes-geofence.sh

Variaveis opcionais:
  CLIENTES_BBOX=-43.9600,-16.8200,-43.7800,-16.6200
  GEOFENCE_SUMMARY_FILE=artifacts/poc/geofence-summary.json
  DB_CONTAINER=postgres-oop-test
  DB_USER=postgres
  DB_NAME=agua_viva_oop_test
  DB_HOST=localhost
  DB_PORT=5435
  DB_PASSWORD=postgres

Comportamento:
  - Valida todos os clientes contra geofence operacional.
  - Falha se existir cliente sem coordenada, fora da bbox ou com endereco vazio/pendente.
  - Valida deposito (configuracoes deposito_latitude/deposito_longitude) na mesma bbox.
  - Gera evidencia JSON em GEOFENCE_SUMMARY_FILE.
USAGE
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Comando obrigatorio ausente: $1" >&2
    exit 1
  }
}

is_decimal() {
  local value="$1"
  [[ "$value" =~ ^-?[0-9]+([.][0-9]+)?$ ]]
}

parse_bbox() {
  local raw="$1"
  local extra=""
  IFS=',' read -r MIN_LON MIN_LAT MAX_LON MAX_LAT extra <<< "$raw"

  if [[ -n "$extra" || -z "$MIN_LON" || -z "$MIN_LAT" || -z "$MAX_LON" || -z "$MAX_LAT" ]]; then
    echo "CLIENTES_BBOX invalido. Use min_lon,min_lat,max_lon,max_lat." >&2
    exit 1
  fi

  for value in "$MIN_LON" "$MIN_LAT" "$MAX_LON" "$MAX_LAT"; do
    if ! is_decimal "$value"; then
      echo "CLIENTES_BBOX invalido. Coordenada nao numerica: '$value'." >&2
      exit 1
    fi
  done

  if ! awk -v min="$MIN_LON" -v max="$MAX_LON" 'BEGIN { exit (min < max ? 0 : 1) }'; then
    echo "CLIENTES_BBOX invalido: min_lon deve ser menor que max_lon." >&2
    exit 1
  fi

  if ! awk -v min="$MIN_LAT" -v max="$MAX_LAT" 'BEGIN { exit (min < max ? 0 : 1) }'; then
    echo "CLIENTES_BBOX invalido: min_lat deve ser menor que max_lat." >&2
    exit 1
  fi
}

query_sql() {
  local sql="$1"

  if command -v psql >/dev/null 2>&1; then
    PGPASSWORD="$DB_PASSWORD" psql \
      -h "$DB_HOST" \
      -p "$DB_PORT" \
      -U "$DB_USER" \
      -d "$DB_NAME" \
      -v ON_ERROR_STOP=1 \
      -q \
      -Atc "$sql"
    return
  fi

  require_cmd docker
  docker exec -i "$DB_CONTAINER" psql \
    -U "$DB_USER" \
    -d "$DB_NAME" \
    -v ON_ERROR_STOP=1 \
    -q \
    -Atc "$sql"
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

require_cmd jq
parse_bbox "$CLIENTES_BBOX"

TOTAL_CLIENTES="$(query_sql "SELECT COUNT(*) FROM clientes;" | tr -d '[:space:]')"

CLIENTES_VIOLACOES_JSON="$(query_sql "
WITH bounds AS (
  SELECT ${MIN_LON}::numeric AS min_lon, ${MIN_LAT}::numeric AS min_lat, ${MAX_LON}::numeric AS max_lon, ${MAX_LAT}::numeric AS max_lat
),
violacoes AS (
  SELECT
    c.id,
    c.nome,
    c.telefone,
    c.endereco,
    c.latitude,
    c.longitude,
    CASE
      WHEN c.latitude IS NULL OR c.longitude IS NULL THEN 'COORDENADA_AUSENTE'
      WHEN btrim(COALESCE(c.endereco, '')) = '' THEN 'ENDERECO_VAZIO'
      WHEN lower(btrim(c.endereco)) = 'endereco pendente' THEN 'ENDERECO_PENDENTE'
      WHEN c.latitude < b.min_lat OR c.latitude > b.max_lat OR c.longitude < b.min_lon OR c.longitude > b.max_lon THEN 'FORA_BBOX'
      ELSE NULL
    END AS motivo
  FROM clientes c
  CROSS JOIN bounds b
)
SELECT COALESCE(
  json_agg(
    json_build_object(
      'tipo', 'CLIENTE',
      'motivo', motivo,
      'id', id,
      'telefone', telefone,
      'nome', nome,
      'endereco', endereco,
      'latitude', latitude,
      'longitude', longitude
    )
    ORDER BY id
  ) FILTER (WHERE motivo IS NOT NULL),
  '[]'::json
)::text
FROM violacoes;
")"

DEPOSITO_VIOLACOES_JSON="$(query_sql "
WITH bounds AS (
  SELECT ${MIN_LON}::double precision AS min_lon, ${MIN_LAT}::double precision AS min_lat, ${MAX_LON}::double precision AS max_lon, ${MAX_LAT}::double precision AS max_lat
),
cfg AS (
  SELECT
    MAX(CASE WHEN chave = 'deposito_latitude' THEN valor END) AS lat_raw,
    MAX(CASE WHEN chave = 'deposito_longitude' THEN valor END) AS lon_raw
  FROM configuracoes
  WHERE chave IN ('deposito_latitude', 'deposito_longitude')
),
parsed AS (
  SELECT
    lat_raw,
    lon_raw,
    CASE WHEN lat_raw ~ '^-?[0-9]+(\\.[0-9]+)?$' THEN lat_raw::double precision ELSE NULL END AS lat_num,
    CASE WHEN lon_raw ~ '^-?[0-9]+(\\.[0-9]+)?$' THEN lon_raw::double precision ELSE NULL END AS lon_num
  FROM cfg
),
violacoes AS (
  SELECT
    lat_raw,
    lon_raw,
    lat_num,
    lon_num,
    CASE
      WHEN lat_raw IS NULL OR lon_raw IS NULL THEN 'CONFIG_AUSENTE'
      WHEN lat_num IS NULL OR lon_num IS NULL THEN 'CONFIG_INVALIDA'
      WHEN lat_num < b.min_lat OR lat_num > b.max_lat OR lon_num < b.min_lon OR lon_num > b.max_lon THEN 'FORA_BBOX'
      ELSE NULL
    END AS motivo
  FROM parsed
  CROSS JOIN bounds b
)
SELECT COALESCE(
  json_agg(
    json_build_object(
      'tipo', 'DEPOSITO',
      'motivo', motivo,
      'chaveLatitude', 'deposito_latitude',
      'chaveLongitude', 'deposito_longitude',
      'latitudeRaw', lat_raw,
      'longitudeRaw', lon_raw,
      'latitude', lat_num,
      'longitude', lon_num
    )
  ) FILTER (WHERE motivo IS NOT NULL),
  '[]'::json
)::text
FROM violacoes;
")"

CLIENTES_INVALIDOS="$(jq -r 'length' <<< "$CLIENTES_VIOLACOES_JSON")"
DEPOSITO_INVALIDOS="$(jq -r 'length' <<< "$DEPOSITO_VIOLACOES_JSON")"
CLIENTES_OK=$((TOTAL_CLIENTES - CLIENTES_INVALIDOS))
TOTAL_VIOLACOES=$((CLIENTES_INVALIDOS + DEPOSITO_INVALIDOS))

mkdir -p "$(dirname "$GEOFENCE_SUMMARY_FILE")"

jq -n \
  --arg generatedAt "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" \
  --arg bbox "$CLIENTES_BBOX" \
  --argjson minLon "$MIN_LON" \
  --argjson minLat "$MIN_LAT" \
  --argjson maxLon "$MAX_LON" \
  --argjson maxLat "$MAX_LAT" \
  --argjson clientesTotal "$TOTAL_CLIENTES" \
  --argjson clientesOk "$CLIENTES_OK" \
  --argjson clientesInvalidos "$CLIENTES_INVALIDOS" \
  --argjson depositoOk "$(if [[ "$DEPOSITO_INVALIDOS" == "0" ]]; then echo "true"; else echo "false"; fi)" \
  --argjson depositoInvalidos "$DEPOSITO_INVALIDOS" \
  --argjson totalViolacoes "$TOTAL_VIOLACOES" \
  --argjson clientesViolacoes "$CLIENTES_VIOLACOES_JSON" \
  --argjson depositoViolacoes "$DEPOSITO_VIOLACOES_JSON" \
  '{
    generatedAt: $generatedAt,
    bbox: {
      raw: $bbox,
      minLon: $minLon,
      minLat: $minLat,
      maxLon: $maxLon,
      maxLat: $maxLat
    },
    totals: {
      clientesTotal: $clientesTotal,
      clientesOk: $clientesOk,
      clientesInvalidos: $clientesInvalidos,
      depositoOk: $depositoOk,
      depositoInvalidos: $depositoInvalidos,
      violacoesTotal: $totalViolacoes
    },
    violacoes: ($clientesViolacoes + $depositoViolacoes)
  }' > "$GEOFENCE_SUMMARY_FILE"

echo "[check-clientes-geofence] bbox=${CLIENTES_BBOX}"
echo "[check-clientes-geofence] clientes_total=${TOTAL_CLIENTES} clientes_ok=${CLIENTES_OK} clientes_invalidos=${CLIENTES_INVALIDOS}"
echo "[check-clientes-geofence] deposito_ok=$(if [[ "$DEPOSITO_INVALIDOS" == "0" ]]; then echo "true"; else echo "false"; fi) deposito_invalidos=${DEPOSITO_INVALIDOS}"
echo "[check-clientes-geofence] summary=${GEOFENCE_SUMMARY_FILE}"

if [[ "$TOTAL_VIOLACOES" -gt 0 ]]; then
  echo "[check-clientes-geofence] FALHA: violacoes encontradas."
  jq -r '.violacoes[] | "- [\(.tipo)] \(.motivo) :: id=\(.id // "n/a") telefone=\(.telefone // "n/a") lat=\(.latitude // .latitudeRaw // "n/a") lon=\(.longitude // .longitudeRaw // "n/a") endereco=\(.endereco // "n/a")"' "$GEOFENCE_SUMMARY_FILE"
  exit 1
fi

echo "[check-clientes-geofence] OK: todos os clientes e deposito dentro da geofence."
