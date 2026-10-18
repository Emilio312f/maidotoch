#!/usr/bin/env bash
# Reversión rápida a la versión anterior (objetivo: menos de 30 minutos, Plan §7.2).
#
#   ops/revertir.sh                     # vuelve al código anterior y conserva la base actual
#   ops/revertir.sh --restaurar-base    # además restaura el último respaldo de la base
#
# Restaurar la base descarta lo registrado después del respaldo (pedidos, recomendaciones):
# hacerlo solo si la versión nueva dañó los datos, con autorización del Director de Proyecto.
set -euo pipefail

base="${MAIDOTOUCH_HOME:-/opt/maidotouch}"
servicio="${MAIDOTOUCH_SERVICIO:-maidotouch}"
puerto="${MAIDOTOUCH_PUERTO:-8080}"
db="${MAIDOTOUCH_DB_PATH:-$base/datos/maidotouch.db}"

anterior="$(readlink "$base/anterior" 2>/dev/null || true)"
if [ -z "$anterior" ] || [ ! -d "$anterior" ]; then
  echo "No hay versión anterior registrada en $base/anterior" >&2
  exit 1
fi

systemctl stop "$servicio"
fallida="$(readlink "$base/actual")"
ln -sfn "$anterior" "$base/actual"
ln -sfn "$fallida" "$base/fallida"

if [ "${1:-}" = "--restaurar-base" ]; then
  respaldo="$(ls -1t "$base"/respaldos/*.db 2>/dev/null | head -1)"
  [ -z "$respaldo" ] && { echo "No hay respaldos en $base/respaldos" >&2; exit 1; }
  cp "$db" "$base/respaldos/antes-de-restaurar-$(date -u +%Y%m%dT%H%M%SZ).db"
  rm -f "$db-wal" "$db-shm"
  cp "$respaldo" "$db"
  chown "${MAIDOTOUCH_USUARIO:-maidotouch}:" "$db" 2>/dev/null || true
  echo "Base restaurada desde $respaldo"
fi

systemctl start "$servicio"
esperada="$(cat "$anterior/VERSION" 2>/dev/null || true)"
for _ in $(seq 1 "${MAIDOTOUCH_ESPERA_INTENTOS:-45}"); do
  if curl -fsS "http://127.0.0.1:$puerto/api/salud" 2>/dev/null | grep -q "\"version\":\"$esperada\""; then
    echo "OK: en línea con $(cat "$anterior/VERSION" 2>/dev/null || basename "$anterior")"
    exit 0
  fi
  sleep 2
done
echo "La versión anterior tampoco responde: atender el servicio con el flujo tradicional del mozo y revisar journalctl -u $servicio" >&2
exit 1
