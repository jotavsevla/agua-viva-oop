#!/usr/bin/env bash
# Baixa e preprocessa dados OSM para o OSRM.
# Executar UMA VEZ antes de subir os servicos.
#
# Uso:
#   ./prepare.sh                  # default: sudeste (contem MG)
#   ./prepare.sh nordeste         # outra regiao

set -euo pipefail

REGION=${1:-sudeste}
DATA_DIR="$(cd "$(dirname "$0")" && pwd)/data"

mkdir -p "$DATA_DIR"
cd "$DATA_DIR"

PBF="${REGION}-latest.osm.pbf"

if [ ! -f "$PBF" ]; then
    echo "==> Baixando ${REGION}..."
    curl -L -o "$PBF" "https://download.geofabrik.de/south-america/brazil/${PBF}" --progress-bar
fi

echo "==> Extraindo rede viaria..."
docker run --rm -t -v "$(pwd):/data" osrm/osrm-backend \
    osrm-extract -p /opt/car.lua "/data/${PBF}"

echo "==> Particionando grafo..."
docker run --rm -t -v "$(pwd):/data" osrm/osrm-backend \
    osrm-partition "/data/${REGION}-latest.osrm"

echo "==> Customizando pesos..."
docker run --rm -t -v "$(pwd):/data" osrm/osrm-backend \
    osrm-customize "/data/${REGION}-latest.osrm"

echo "==> Pronto! Dados OSRM em ${DATA_DIR}"
echo "    Agora suba os servicos com: docker compose up -d"
