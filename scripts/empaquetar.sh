#!/usr/bin/env bash
# Arma el paquete desplegable: backend (bin + lib), web-app compilada y migraciones.
# Uso: scripts/empaquetar.sh [directorio-salida]   → <salida>/maidotouch-<version>.tar.gz
set -euo pipefail

raiz="$(cd "$(dirname "$0")/.." && pwd)"
salida="${1:-$raiz/build/paquete}"
version="$(git -C "$raiz" describe --tags --always --dirty 2>/dev/null || echo sin-version)"

echo "Empaquetando MaidoTouch $version"
(cd "$raiz/frontend" && npm ci --no-audit --no-fund && npm run build)
(cd "$raiz" && ./gradlew --no-daemon -q installDist)

trabajo="$(mktemp -d)"
trap 'rm -rf "$trabajo"' EXIT
destino="$trabajo/maidotouch-$version"
mkdir -p "$destino"
cp -R "$raiz/build/install/maidotouch/bin" "$raiz/build/install/maidotouch/lib" "$destino/"
cp -R "$raiz/frontend/dist" "$destino/web"
cp -R "$raiz/db/migrations" "$destino/migraciones"
echo "$version" > "$destino/VERSION"

mkdir -p "$salida"
tar -czf "$salida/maidotouch-$version.tar.gz" -C "$trabajo" "maidotouch-$version"
echo "$salida/maidotouch-$version.tar.gz"
