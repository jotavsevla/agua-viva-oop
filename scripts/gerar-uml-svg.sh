#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UML_DIR="$ROOT_DIR/docs/uml"
KROKI_URL="https://kroki.io/plantuml/svg"
PLANTUML_IMAGE="plantuml/plantuml:latest"

if [ ! -d "$UML_DIR" ]; then
  echo "Erro: diretorio de UML nao encontrado: $UML_DIR" >&2
  exit 1
fi

shopt -s nullglob
PUML_FILES=("$UML_DIR"/*.puml)
shopt -u nullglob

if [ "${#PUML_FILES[@]}" -eq 0 ]; then
  echo "Nenhum arquivo .puml encontrado em $UML_DIR"
  exit 0
fi

if command -v docker >/dev/null 2>&1; then
  echo "Gerando UML com Docker + PlantUML ($PLANTUML_IMAGE)"
  for puml in "${PUML_FILES[@]}"; do
    base_name="$(basename "$puml")"
    echo "Gerando ${base_name%.puml}.svg e ${base_name%.puml}.xmi ..."
    docker run --rm -v "$UML_DIR:/workspace" -w /workspace "$PLANTUML_IMAGE" -tsvg "$base_name" >/dev/null
    docker run --rm -v "$UML_DIR:/workspace" -w /workspace "$PLANTUML_IMAGE" -txmi "$base_name" >/dev/null
  done
  echo "SVG e XMI gerados em: $UML_DIR"
  exit 0
fi

if ! command -v curl >/dev/null 2>&1; then
  echo "Erro: sem Docker e sem curl no PATH para fallback via Kroki." >&2
  exit 1
fi

echo "Docker nao encontrado. Fallback: gerando somente SVG via Kroki."
for puml in "${PUML_FILES[@]}"; do
  svg="${puml%.puml}.svg"
  echo "Gerando $(basename "$svg") ..."
  curl -fsS \
    -X POST \
    -H "Content-Type: text/plain" \
    --data-binary "@$puml" \
    "$KROKI_URL" > "$svg"
done

echo "SVGs gerados em: $UML_DIR"
