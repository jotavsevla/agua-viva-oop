#!/usr/bin/env bash
# Baixa e preprocessa dados OSM para o OSRM.
# Executar UMA VEZ antes de subir os servicos.
#
# Uso:
#   ./prepare.sh                         # default: sudeste
#   ./prepare.sh norte                   # outra regiao Geofabrik BR
#   ./prepare.sh https://.../city.osm.pbf
#   PBF_URL=https://.../recorte.osm.pbf ./prepare.sh
#   PBF_FILE=recorte-local.osm.pbf ./prepare.sh    # usa arquivo ja presente em data/
#   OSRM_BBOX=-43.9600,-16.8200,-43.7800,-16.6200 ./prepare.sh
#   OSRM_BBOX=-43.9600,-16.8200,-43.7800,-16.6200 OSRM_DATASET_NAME=montes-claros-bbox ./prepare.sh

set -euo pipefail

DEFAULT_REGION="${OSRM_REGION:-sudeste}"
INPUT="${1:-$DEFAULT_REGION}"
BASE_URL="${OSM_BASE_URL:-https://download.geofabrik.de/south-america/brazil}"
DATA_DIR="$(cd "$(dirname "$0")" && pwd)/data"
BBOX="${OSRM_BBOX:-}"
DATASET_NAME="${OSRM_DATASET_NAME:-}"
OSMIUM_IMAGE="${OSMIUM_IMAGE:-asymmetric/osmium-tool}"

BBOX_MIN_LON=""
BBOX_MIN_LAT=""
BBOX_MAX_LON=""
BBOX_MAX_LAT=""

mkdir -p "$DATA_DIR"
cd "$DATA_DIR"

assert_not_html_file() {
    local path="$1"
    if LC_ALL=C head -c 512 "$path" | grep -a -qiE '<!doctype html|<html'; then
        echo "ERRO: '$path' parece HTML, nao um .osm.pbf valido (URL incorreta/redirect)." >&2
        exit 1
    fi
}

is_decimal() {
    local value="$1"
    [[ "$value" =~ ^-?[0-9]+([.][0-9]+)?$ ]]
}

parse_bbox() {
    local raw="$1"
    local extra=""
    IFS=',' read -r BBOX_MIN_LON BBOX_MIN_LAT BBOX_MAX_LON BBOX_MAX_LAT extra <<< "$raw"

    if [ -n "$extra" ] || [ -z "$BBOX_MIN_LON" ] || [ -z "$BBOX_MIN_LAT" ] || [ -z "$BBOX_MAX_LON" ] || [ -z "$BBOX_MAX_LAT" ]; then
        echo "ERRO: OSRM_BBOX invalido. Use min_lon,min_lat,max_lon,max_lat" >&2
        exit 1
    fi

    for value in "$BBOX_MIN_LON" "$BBOX_MIN_LAT" "$BBOX_MAX_LON" "$BBOX_MAX_LAT"; do
        if ! is_decimal "$value"; then
            echo "ERRO: OSRM_BBOX invalido. Coordenada nao numerica: '$value'" >&2
            exit 1
        fi
    done

    if ! awk -v min="$BBOX_MIN_LON" -v max="$BBOX_MAX_LON" 'BEGIN { exit (min < max ? 0 : 1) }'; then
        echo "ERRO: OSRM_BBOX invalido. min_lon deve ser menor que max_lon." >&2
        exit 1
    fi

    if ! awk -v min="$BBOX_MIN_LAT" -v max="$BBOX_MAX_LAT" 'BEGIN { exit (min < max ? 0 : 1) }'; then
        echo "ERRO: OSRM_BBOX invalido. min_lat deve ser menor que max_lat." >&2
        exit 1
    fi
}

DOWNLOAD_URL=""
if [ -n "${PBF_URL:-}" ]; then
    DOWNLOAD_URL="$PBF_URL"
elif [[ "$INPUT" =~ ^https?:// ]]; then
    DOWNLOAD_URL="$INPUT"
else
    DOWNLOAD_URL="${BASE_URL}/${INPUT}-latest.osm.pbf"
fi

if [ -n "${PBF_FILE:-}" ]; then
    PBF="$PBF_FILE"
elif [ -n "$DOWNLOAD_URL" ]; then
    PBF="$(basename "${DOWNLOAD_URL%%\?*}")"
else
    echo "ERRO: nao foi possivel determinar o arquivo .osm.pbf" >&2
    exit 1
fi

if [[ "$PBF" != *.osm.pbf ]]; then
    echo "ERRO: arquivo invalido '$PBF' (esperado sufixo .osm.pbf)" >&2
    exit 1
fi

DATASET="${PBF%.osm.pbf}"

if [ ! -f "$PBF" ]; then
    if [ -z "$DOWNLOAD_URL" ]; then
        echo "ERRO: arquivo '$PBF' nao encontrado em $DATA_DIR e nenhum PBF_URL foi informado." >&2
        exit 1
    fi
    echo "==> Baixando ${DOWNLOAD_URL}..."
    curl -fL -o "$PBF" "$DOWNLOAD_URL" --progress-bar
fi

assert_not_html_file "$PBF"

if [ -n "$BBOX" ]; then
    parse_bbox "$BBOX"

    TARGET_DATASET="$DATASET"
    if [ -n "$DATASET_NAME" ]; then
        TARGET_DATASET="$DATASET_NAME"
    else
        TARGET_DATASET="${DATASET}-bbox"
    fi

    if [[ "$TARGET_DATASET" == *.osm.pbf ]]; then
        TARGET_DATASET="${TARGET_DATASET%.osm.pbf}"
    fi
    if [[ "$TARGET_DATASET" == *.osrm ]]; then
        TARGET_DATASET="${TARGET_DATASET%.osrm}"
    fi

    if [[ ! "$TARGET_DATASET" =~ ^[A-Za-z0-9._-]+$ ]]; then
        echo "ERRO: OSRM_DATASET_NAME invalido '$TARGET_DATASET'." >&2
        echo "Use apenas letras, numeros, ponto, underscore ou hifen." >&2
        exit 1
    fi

    TARGET_PBF="${TARGET_DATASET}.osm.pbf"
    if [ "$TARGET_PBF" = "$PBF" ]; then
        echo "ERRO: dataset alvo da poda coincide com o arquivo fonte ('$PBF')." >&2
        echo "Defina OSRM_DATASET_NAME diferente para evitar sobrescrever o PBF original." >&2
        exit 1
    fi

    if [ ! -f "$TARGET_PBF" ]; then
        echo "==> Aplicando poda por bounding box no dataset..."
        docker run --rm -t -v "$(pwd):/data" "$OSMIUM_IMAGE" \
            extract --strategy=complete_ways \
            --bbox="${BBOX_MIN_LON},${BBOX_MIN_LAT},${BBOX_MAX_LON},${BBOX_MAX_LAT}" \
            "/data/${PBF}" \
            -o "/data/${TARGET_PBF}" \
            --overwrite
    else
        echo "==> Reutilizando dataset podado existente: ${TARGET_PBF}"
    fi

    assert_not_html_file "$TARGET_PBF"
    PBF="$TARGET_PBF"
    DATASET="$TARGET_DATASET"
fi

echo "==> Extraindo rede viaria..."
docker run --rm -t -v "$(pwd):/data" osrm/osrm-backend \
    osrm-extract -p /opt/car.lua "/data/${PBF}"

echo "==> Particionando grafo..."
docker run --rm -t -v "$(pwd):/data" osrm/osrm-backend \
    osrm-partition "/data/${DATASET}.osrm"

echo "==> Customizando pesos..."
docker run --rm -t -v "$(pwd):/data" osrm/osrm-backend \
    osrm-customize "/data/${DATASET}.osrm"

echo "==> Pronto! Dados OSRM em ${DATA_DIR}"
echo "    Dataset gerado: ${DATASET}"
echo "    Suba com: OSRM_DATASET=${DATASET} docker compose up -d osrm solver"
