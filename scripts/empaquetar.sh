#!/usr/bin/env bash
# Arma el paquete desplegable (Diseño v1.1): un directorio por servicio (bin + lib), las
# herramientas de operación, las dos web-apps compiladas y las migraciones.
# Uso: scripts/empaquetar.sh [directorio-salida]   → <salida>/maidotouch-<version>.tar.gz
set -euo pipefail

raiz="$(cd "$(dirname "$0")/.." && pwd)"
salida="${1:-$raiz/build/paquete}"
version="$(git -C "$raiz" describe --tags --always --dirty 2>/dev/null || echo sin-version)"
servicios=(gateway sesion catalogo seguridad-alimentaria recomendacion adaptador-ia pedido atencion trazabilidad piloto bus)

echo "Empaquetando MaidoTouch $version"
(cd "$raiz/frontend" && npm ci --no-audit --no-fund && npm run build)
(cd "$raiz" && ./gradlew --no-daemon -q installDist)

trabajo="$(mktemp -d)"
trap 'rm -rf "$trabajo"' EXIT
destino="$trabajo/maidotouch-$version"
mkdir -p "$destino/servicios" "$destino/web"
for s in "${servicios[@]}"; do
  cp -R "$raiz/build/$s/install/maidotouch-$s" "$destino/servicios/$s"
  # Cada servicio informa su versión en /salud: el despliegue la usa para verificar el arranque.
  echo "$version" > "$destino/servicios/$s/VERSION"
done
cp -R "$raiz/build/herramientas/install/maidotouch-herramientas" "$destino/herramientas"

# Cada servicio conserva su propio lib/, pero un JAR idéntico en varios servicios se guarda como
# enlace duro: tar lo empaqueta una sola vez y el paquete no crece once veces.
primeros="$trabajo/primeros"
mkdir -p "$primeros"
for jar in "$destino"/servicios/*/lib/*.jar "$destino"/herramientas/lib/*.jar; do
  marca="$primeros/$(basename "$jar")"
  if [ -e "$marca" ] && cmp -s "$(cat "$marca")" "$jar"; then
    ln -f "$(cat "$marca")" "$jar"
  elif [ ! -e "$marca" ]; then
    echo "$jar" > "$marca"
  fi
done
cp -R "$raiz/frontend/dist/comensal" "$destino/web/comensal"
cp -R "$raiz/frontend/dist/backoffice" "$destino/web/backoffice"
cp -R "$raiz/db/migrations" "$destino/migraciones"
cp -R "$raiz/ops" "$destino/ops"
echo "$version" > "$destino/VERSION"

mkdir -p "$salida"
tar -czf "$salida/maidotouch-$version.tar.gz" -C "$trabajo" "maidotouch-$version"
echo "$salida/maidotouch-$version.tar.gz"
