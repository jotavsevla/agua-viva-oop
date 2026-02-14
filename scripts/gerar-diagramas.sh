#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET_DIR="$ROOT_DIR/target/diagramas"
JDEPS_DIR="$TARGET_DIR/jdeps"
CLASSPATH_FILE="$ROOT_DIR/target/classpath.txt"

ensure_command() {
  local cmd="$1"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "Erro: comando obrigatorio nao encontrado: $cmd" >&2
    exit 1
  fi
}

node_id_from_pkg() {
  local pkg="$1"
  local id="n_${pkg}"
  id="${id//./_}"
  echo "$id"
}

layer_for_pkg() {
  local pkg="$1"
  case "$pkg" in
    com.aguaviva.domain*) echo "domain" ;;
    com.aguaviva.repository*) echo "repository" ;;
    com.aguaviva.service*) echo "service" ;;
    com.aguaviva.solver*) echo "solver" ;;
    com.aguaviva.api*) echo "api" ;;
    com.aguaviva*) echo "app" ;;
    *) echo "outro" ;;
  esac
}

color_for_layer() {
  local layer="$1"
  case "$layer" in
    domain) echo "#DCFCE7" ;;
    repository) echo "#DBEAFE" ;;
    service) echo "#FEF3C7" ;;
    solver) echo "#FCE7F3" ;;
    api) echo "#E0F2FE" ;;
    app) echo "#E2E8F0" ;;
    *) echo "#F1F5F9" ;;
  esac
}

generate_package_dot() {
  local source_dot="$1"
  local output_file="$2"
  local packages_file
  local edges_file
  local pkg src dst layer color

  packages_file="$(mktemp)"
  edges_file="$(mktemp)"

  awk -F'"' '
    /->/ {
      src = $2
      dst = $4
      gsub(/ \(classes\)/, "", dst)
      if (src ~ /^com\.aguaviva/ && dst ~ /^com\.aguaviva/ && src != dst) {
        print src
        print dst
      }
    }
  ' "$source_dot" | sort -u > "$packages_file"

  awk -F'"' '
    /->/ {
      src = $2
      dst = $4
      gsub(/ \(classes\)/, "", dst)
      if (src ~ /^com\.aguaviva/ && dst ~ /^com\.aguaviva/ && src != dst) {
        print src "|" dst
      }
    }
  ' "$source_dot" | sort -u > "$edges_file"

  {
    echo "digraph \"arquitetura_pacotes\" {"
    echo "  rankdir=LR;"
    echo "  graph [fontname=\"Helvetica\", labelloc=\"t\", label=\"Agua Viva - Dependencias internas por pacote\", fontsize=18, bgcolor=\"white\", pad=\"0.2\", nodesep=\"0.35\", ranksep=\"0.85\", splines=true];"
    echo "  node [shape=box, style=\"rounded,filled\", fontname=\"Helvetica\", fontsize=11, color=\"#334155\", fontcolor=\"#0f172a\", penwidth=1.2];"
    echo "  edge [color=\"#475569\", arrowsize=0.8, penwidth=1.1];"

    while IFS= read -r pkg; do
      [ -z "$pkg" ] && continue
      layer="$(layer_for_pkg "$pkg")"
      color="$(color_for_layer "$layer")"
      echo "  \"$(node_id_from_pkg "$pkg")\" [label=\"$pkg\", fillcolor=\"$color\"];"
    done < "$packages_file"

    while IFS= read -r edge; do
      [ -z "$edge" ] && continue
      src="${edge%%|*}"
      dst="${edge##*|}"
      echo "  \"$(node_id_from_pkg "$src")\" -> \"$(node_id_from_pkg "$dst")\";"
    done < "$edges_file"

    echo "}"
  } > "$output_file"

  rm -f "$packages_file" "$edges_file"
}

generate_layer_dot() {
  local source_dot="$1"
  local output_file="$2"
  local edges_file
  local -a layers=(app api service repository domain solver)
  local layer color src dst

  edges_file="$(mktemp)"
  awk -F'"' '
    function layer(pkg) {
      if (pkg ~ /^com\.aguaviva\.domain(\.|$)/) return "domain"
      if (pkg ~ /^com\.aguaviva\.repository(\.|$)/) return "repository"
      if (pkg ~ /^com\.aguaviva\.service(\.|$)/) return "service"
      if (pkg ~ /^com\.aguaviva\.solver(\.|$)/) return "solver"
      if (pkg ~ /^com\.aguaviva\.api(\.|$)/) return "api"
      if (pkg ~ /^com\.aguaviva(\.|$)/) return "app"
      return ""
    }

    /->/ {
      src_pkg = $2
      dst_pkg = $4
      gsub(/ \(classes\)/, "", dst_pkg)
      src = layer(src_pkg)
      dst = layer(dst_pkg)
      if (src != "" && dst != "" && src != dst) {
        print src "|" dst
      }
    }
  ' "$source_dot" | sort -u > "$edges_file"

  {
    echo "digraph \"arquitetura_camadas\" {"
    echo "  rankdir=LR;"
    echo "  graph [fontname=\"Helvetica\", labelloc=\"t\", label=\"Agua Viva - Dependencias entre camadas\", fontsize=18, bgcolor=\"white\", pad=\"0.2\", nodesep=\"0.5\", ranksep=\"1.0\", splines=true];"
    echo "  node [shape=box, style=\"rounded,filled\", fontname=\"Helvetica\", fontsize=12, color=\"#334155\", fontcolor=\"#0f172a\", penwidth=1.3, width=1.8, height=0.6];"
    echo "  edge [color=\"#475569\", arrowsize=0.9, penwidth=1.2];"

    for layer in "${layers[@]}"; do
      color="$(color_for_layer "$layer")"
      echo "  \"$layer\" [label=\"$layer\", fillcolor=\"$color\"];"
    done

    while IFS= read -r edge; do
      [ -z "$edge" ] && continue
      src="${edge%%|*}"
      dst="${edge##*|}"
      echo "  \"$src\" -> \"$dst\";"
    done < "$edges_file"

    echo "}"
  } > "$output_file"

  rm -f "$edges_file"
}

generate_package_mermaid() {
  local source_dot="$1"
  local output_file="$2"

  {
    echo "graph TD"
    awk -F'"' '
      /->/ {
        src = $2
        dst = $4
        gsub(/ \(classes\)/, "", dst)
        if (src ~ /^com\.aguaviva/ && dst ~ /^com\.aguaviva/ && src != dst) {
          src_id = "n_" src
          dst_id = "n_" dst
          gsub(/[^A-Za-z0-9_]/, "_", src_id)
          gsub(/[^A-Za-z0-9_]/, "_", dst_id)
          print "  " src_id "[\"" src "\"] --> " dst_id "[\"" dst "\"]"
        }
      }
    ' "$source_dot" | sort -u
  } > "$output_file"
}

generate_layer_mermaid() {
  local source_dot="$1"
  local output_file="$2"

  {
    echo "graph TD"
    awk -F'"' '
      function layer(pkg) {
        if (pkg ~ /^com\.aguaviva\.domain(\.|$)/) return "domain"
        if (pkg ~ /^com\.aguaviva\.repository(\.|$)/) return "repository"
        if (pkg ~ /^com\.aguaviva\.service(\.|$)/) return "service"
        if (pkg ~ /^com\.aguaviva\.solver(\.|$)/) return "solver"
        if (pkg ~ /^com\.aguaviva\.api(\.|$)/) return "api"
        if (pkg ~ /^com\.aguaviva(\.|$)/) return "app"
        return ""
      }

      /->/ {
        src_pkg = $2
        dst_pkg = $4
        gsub(/ \(classes\)/, "", dst_pkg)
        src = layer(src_pkg)
        dst = layer(dst_pkg)
        if (src != "" && dst != "" && src != dst) {
          print "  " src " --> " dst
        }
      }
    ' "$source_dot" | sort -u
  } > "$output_file"
}

ensure_command mvn
ensure_command jdeps

cd "$ROOT_DIR"

echo "[1/7] Compilando projeto (sem testes)..."
mvn -q -DskipTests compile

echo "[2/7] Montando classpath de compilacao..."
mvn -q -DincludeScope=compile dependency:build-classpath "-Dmdep.outputFile=$CLASSPATH_FILE"

echo "[3/7] Limpando saida anterior..."
rm -rf "$TARGET_DIR"
mkdir -p "$JDEPS_DIR"

echo "[4/7] Gerando grafo DOT com jdeps..."
jdeps --multi-release 21 \
  --class-path "$(cat "$CLASSPATH_FILE")" \
  --dot-output "$JDEPS_DIR" \
  --recursive "$ROOT_DIR/target/classes"

echo "[5/7] Gerando grafo DOT de dependencias Maven..."
mvn -q dependency:tree -DoutputType=dot "-DoutputFile=$TARGET_DIR/dependencies.dot"

echo "[6/7] Gerando Mermaid (pacotes e camadas)..."
generate_package_mermaid "$JDEPS_DIR/classes.dot" "$TARGET_DIR/pacotes.mmd"
generate_layer_mermaid "$JDEPS_DIR/classes.dot" "$TARGET_DIR/camadas.mmd"
generate_package_dot "$JDEPS_DIR/classes.dot" "$TARGET_DIR/pacotes.dot"
generate_layer_dot "$JDEPS_DIR/classes.dot" "$TARGET_DIR/camadas.dot"

echo "[7/7] Gerando SVG (quando Graphviz estiver disponivel)..."
if command -v dot >/dev/null 2>&1; then
  dot -Tsvg "$TARGET_DIR/pacotes.dot" -o "$TARGET_DIR/pacotes.svg"
  dot -Tsvg "$TARGET_DIR/camadas.dot" -o "$TARGET_DIR/camadas.svg"
  dot -Tsvg "$TARGET_DIR/dependencies.dot" -o "$TARGET_DIR/dependencies.svg"
  echo "SVG gerados com sucesso."
else
  echo "Graphviz (dot) nao encontrado; arquivos SVG nao foram gerados."
fi

echo
echo "Diagramas atualizados (arquivos antigos foram sobrescritos):"
echo " - $JDEPS_DIR/classes.dot"
echo " - $JDEPS_DIR/summary.dot"
echo " - $TARGET_DIR/dependencies.dot"
echo " - $TARGET_DIR/pacotes.dot"
echo " - $TARGET_DIR/camadas.dot"
echo " - $TARGET_DIR/pacotes.mmd"
echo " - $TARGET_DIR/camadas.mmd"
if command -v dot >/dev/null 2>&1; then
  echo " - $TARGET_DIR/pacotes.svg"
  echo " - $TARGET_DIR/camadas.svg"
  echo " - $TARGET_DIR/dependencies.svg"
fi
