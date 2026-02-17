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

set -euo pipefail

DEFAULT_REGION="${OSRM_REGION:-sudeste}"
INPUT="${1:-$DEFAULT_REGION}"
BASE_URL="${OSM_BASE_URL:-https://download.geofabrik.de/south-america/brazil}"
DATA_DIR="$(cd "$(dirname "$0")" && pwd)/data"

mkdir -p "$DATA_DIR"
cd "$DATA_DIR"

assert_not_html_file() {
    local path="$1"
    if LC_ALL=C head -c 512 "$path" | grep -a -qiE '<!doctype html|<html'; then
        echo "ERRO: '$path' parece HTML, nao um .osm.pbf valido (URL incorreta/redirect)." >&2
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
