#!/usr/bin/env bash
# Levanta los once procesos de MaidoTouch en la máquina de desarrollo (Diseño v1.1).
#
#   scripts/desarrollo.sh          # compila, migra la base y levanta los servicios (Ctrl+C los detiene)
#   scripts/desarrollo.sh demo     # además carga los datos FICTICIOS de desarrollo
#   scripts/desarrollo.sh demo db/catalogo/piloto.json   # con el catálogo piloto (usar una base nueva)
#
# Los tokens de servicio se generan una vez en .env.servicios (no se sube al repositorio). La clave
# del proveedor de IA, si está en .env, solo se entrega al Servicio Adaptador de IA.
# Web-apps: cd frontend && npm run dev (comensal, 5173) y npm run dev:backoffice (5174).
set -euo pipefail

raiz="$(cd "$(dirname "$0")/.." && pwd)"
cd "$raiz"
registros="$raiz/build/desarrollo"
mkdir -p "$registros"

servicios=(sesion seguridad-alimentaria catalogo adaptador-ia trazabilidad recomendacion pedido atencion piloto bus gateway)

./gradlew -q installDist

if [ ! -f .env.servicios ]; then
  build/herramientas/install/maidotouch-herramientas/bin/maidotouch-herramientas tokens > .env.servicios
  chmod 600 .env.servicios
  echo "Tokens de servicio generados en .env.servicios"
fi

set -a
# shellcheck disable=SC1091
source .env.servicios
[ -f .env ] && source .env
set +a
export MAIDOTOUCH_DB_PATH="${MAIDOTOUCH_DB_PATH:-$raiz/maidotouch.db}"
export MAIDOTOUCH_FOTOS_DIR="${MAIDOTOUCH_FOTOS_DIR:-$raiz/fotos}"
export MAIDOTOUCH_MIGRACIONES_DIR="$raiz/db/migrations"
export MAIDOTOUCH_WEB_COMENSAL_DIR="$raiz/frontend/dist/comensal"
export MAIDOTOUCH_WEB_BACKOFFICE_DIR="$raiz/frontend/dist/backoffice"

build/herramientas/install/maidotouch-herramientas/bin/maidotouch-herramientas migrar

pids=()
detener() {
  echo "Deteniendo servicios…"
  kill "${pids[@]}" 2>/dev/null || true
  wait 2>/dev/null || true
}
trap detener EXIT INT TERM

for s in "${servicios[@]}"; do
  if [ "$s" = "adaptador-ia" ]; then
    "build/$s/install/maidotouch-$s/bin/maidotouch-$s" > "$registros/$s.log" 2>&1 &
  else
    env -u ANTHROPIC_API_KEY "build/$s/install/maidotouch-$s/bin/maidotouch-$s" > "$registros/$s.log" 2>&1 &
  fi
  pids+=($!)
done

puerto="${MAIDOTOUCH_URL_GATEWAY:-http://127.0.0.1:8080}"
for _ in $(seq 1 60); do
  if curl -fsS "$puerto/api/salud" >/dev/null 2>&1; then break; fi
  sleep 1
done
curl -fsS "$puerto/api/salud" || { echo "Los servicios no respondieron; revisa $registros/*.log" >&2; exit 1; }
echo

if [ "${1:-}" = "demo" ]; then
  build/herramientas/install/maidotouch-herramientas/bin/maidotouch-herramientas demo ${2:-}
fi

echo "MaidoTouch en $puerto · registros en $registros · Ctrl+C para detener"
wait
